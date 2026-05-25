package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.entity.DocVersion;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocVersionMapper;
import com.exphub.entity.User;
import com.exphub.interceptor.ApiKeyInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class DocService {

    private static final Logger log = LoggerFactory.getLogger(DocService.class);

    @Autowired
    private DocMapper docMapper;

    @Autowired
    private DocVersionMapper versionMapper;

    @Autowired
    private CallLogService callLogService;

    @Transactional
    public Doc create(Doc doc) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        log.info("DocService.create: assistant from ThreadLocal={}, title={}, tags={}, aliases={}", 
            assistant != null ? assistant.getAssistantId() : "NULL",
            doc.getTitle(), doc.getTags(), doc.getAliases());
        
        if (assistant != null) {
            doc.setAuthorId(assistant.getAssistantId());
            doc.setAuthorName(assistant.getAssistantName());
            doc.setApiKey(assistant.getApiKey());  // API Key 级别的经验隔离
            log.info("DocService.create: setting author={}/{}, apiKey={}", 
                assistant.getAssistantId(), assistant.getAssistantName(), assistant.getApiKey());
        } else {
            // 后台页面操作，使用默认管理员
            log.warn("DocService.create: CURRENT_ASSISTANT is NULL, falling back to default");
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpSession session = attrs.getRequest().getSession(false);
                User user = session != null ? (User) session.getAttribute("user") : null;
                if (user != null) {
                    doc.setAuthorId("user_" + user.getId());
                    doc.setAuthorName(user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
                } else {
                    doc.setAuthorId("system");
                    doc.setAuthorName("系统");
                }
            } else {
                doc.setAuthorId("system");
                doc.setAuthorName("系统");
            }
        }
        doc.setVersion(1);
        doc.setCallCount(0);
        doc.setSuccessCount(0);
        doc.setFailCount(0);
        doc.setRating(new BigDecimal("5.00"));
        doc.setRatingCount(0);
        doc.setStatus("ACTIVE");
        docMapper.insert(doc);
        
        // 记录操作日志
        callLogService.logCreate(doc);
        
        return doc;
    }

    public Page<Doc> search(String keyword, int page, int size) {
        return search(keyword, null, null, page, size);
    }

    public Page<Doc> search(String keyword, String templateType, int page, int size) {
        return search(keyword, templateType, null, page, size);
    }

    public Page<Doc> search(String keyword, String templateType, String includeStatus, int page, int size) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        String apiKey = (assistant != null) ? assistant.getApiKey() : null;
        String tt = (templateType != null && !templateType.trim().isEmpty()) ? templateType.trim() : null;
        List<String> statusList = null;
        if (includeStatus != null && !includeStatus.trim().isEmpty()) {
            statusList = Arrays.asList(includeStatus.split(","));
        }
        
        // ===== 有搜索关键词：优先使用 FULLTEXT，命中率更高、性能更好 =====
        if (keyword != null && !keyword.trim().isEmpty()) {
            String ftQuery = toFulltextQuery(keyword.trim());
            long total = docMapper.countByFulltext(ftQuery, apiKey, tt, statusList);
            
            if (total > 0) {
                // FULLTEXT 命中 → 按相关性 + 调用次数排序
                long offset = (long) (page - 1) * size;
                List<Doc> records = docMapper.searchByFulltext(ftQuery, apiKey, tt, statusList, offset, (long) size);
                
                Page<Doc> result = new Page<>(page, size);
                result.setRecords(records);
                result.setTotal(total);
                
                callLogService.logSearch(keyword, (int) total, records);
                if (assistant != null) {
                    for (Doc doc : records) incrementCallCount(doc.getId());
                }
                return result;
            }
            // FULLTEXT 无结果 → 降级到 LIKE 兜底（处理特殊字符/极短词等场景）
        }
        
        // ===== LIKE 兜底或空关键词 =====
        return searchWithLike(keyword, templateType, includeStatus, page, size);
    }
    
    /**
     * LIKE 搜索兜底（FULLTEXT 无结果或空关键词时使用）
     */
    private Page<Doc> searchWithLike(String keyword, String templateType, String includeStatus, int page, int size) {
        Page<Doc> p = new Page<>(page, size);
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null) {
            wrapper.and(w -> w.eq("api_key", assistant.getApiKey()).or().isNull("api_key"));
        }
        
        if (templateType != null && !templateType.trim().isEmpty()) {
            wrapper.eq("template_type", templateType.trim());
        }
        
        if (includeStatus != null && !includeStatus.trim().isEmpty()) {
            String[] statuses = includeStatus.split(",");
            wrapper.in("status", (Object[]) statuses);
        }
        
        long total = 0;
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            String[] tokens = kw.split("[\\s,，、]+");
            if (tokens.length == 1) {
                String t = tokens[0].trim();
                wrapper.and(w -> w.like("title", t)
                        .or().like("content", t)
                        .or().like("aliases", t)
                        .or().like("summary", t)
                        .or().like("tags", t));
            } else {
                // 多关键词 AND 逻辑：每个词必须在至少一个字段中出现
                // 使用 MyBatis-Plus like() 参数化查询，防止 SQL 注入
                for (String token : tokens) {
                    String t = token.trim();
                    if (t.isEmpty()) continue;
                    wrapper.and(w -> w.like("title", t)
                            .or().like("content", t)
                            .or().like("aliases", t)
                            .or().like("summary", t)
                            .or().like("tags", t));
                }
            }
            total = docMapper.selectCount(wrapper);
            wrapper.orderByDesc("updated_at");
        } else {
            wrapper.orderByDesc("updated_at");
            total = docMapper.selectCount(null);
        }
        Page<Doc> result = docMapper.selectPage(p, wrapper);
        result.setTotal(total);
        
        callLogService.logSearch(keyword, (int) total, result.getRecords());
        
        if (assistant != null) {
            for (Doc doc : result.getRecords()) {
                incrementCallCount(doc.getId());
            }
        }
        
        return result;
    }
    
    /**
     * 将用户搜索关键词转换为 FULLTEXT BOOLEAN MODE 查询字符串
     * - 单关键词：直接使用（ngram 自动分词）
     * - 多关键词：+前缀实现 AND 逻辑，每个词必须在结果中出现
     */
    private String toFulltextQuery(String keyword) {
        // 移除 BOOLEAN MODE 特殊字符（+ -> < ( ) ~ * " @），替换为空格分词
        String cleaned = keyword.replaceAll("[+\\-><\\(\\)~\\*\"@]", " ");
        String[] tokens = cleaned.split("[\\s,，、]+");
        
        List<String> parts = new ArrayList<>();
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            parts.add(t);
        }
        
        if (parts.isEmpty()) {
            // 全是特殊字符，返回原始内容去掉符号
            return keyword.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", " ");
        }
        
        if (parts.size() == 1) {
            return parts.get(0);
        }
        
        // 多关键词：+前缀 = AND 逻辑
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("+").append(part);
        }
        return sb.toString();
    }

    public Page<Doc> listByCategory(String category, int page, int size) {
        Page<Doc> p = new Page<>(page, size);
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        if (category != null && !category.trim().isEmpty()) {
            wrapper.eq("category", category.trim());
        }
        wrapper.orderByDesc("updated_at");
        return docMapper.selectPage(p, wrapper);
    }

    public Page<Doc> listByAuthor(String authorId, int page, int size) {
        Page<Doc> p = new Page<>(page, size);
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        wrapper.eq("author_id", authorId);
        wrapper.orderByDesc("updated_at");
        return docMapper.selectPage(p, wrapper);
    }

    public Doc getById(Long id) {
        Doc doc = docMapper.selectById(id);
        // API Key 级别隔离：MCP/API 请求可查看同 Key 下的经验 + 管理员创建的经验（api_key 为空）
        if (doc != null) {
            AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
            if (assistant != null) {
                // 管理员创建的经验（api_key 为空）对所有助手可见
                if (doc.getApiKey() != null && !assistant.getApiKey().equals(doc.getApiKey())) {
                    return null; // 不可见
                }
                // MCP/API 调用查看详情时，增加调用计数
                incrementCallCount(id);
            }
        }
        return doc;
    }

    @Transactional
    public Doc update(Long id, Doc newDoc) {
        Doc old = docMapper.selectById(id);
        if (old == null) {
            return null;
        }
        // save version snapshot
        DocVersion v = new DocVersion();
        v.setDocId(id);
        v.setVersion(old.getVersion());
        v.setContent(old.getContent());
        v.setAliases(old.getAliases());
        v.setSummary(old.getSummary());
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null) {
            v.setUpdatedBy(assistant.getAssistantId());
            v.setUpdatedName(assistant.getAssistantName());
        } else {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpSession session = attrs.getRequest().getSession(false);
                User user = session != null ? (User) session.getAttribute("user") : null;
                if (user != null) {
                    v.setUpdatedBy("user_" + user.getId());
                    v.setUpdatedName(user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
                } else {
                    v.setUpdatedBy("system");
                    v.setUpdatedName("系统");
                }
            } else {
                v.setUpdatedBy("system");
                v.setUpdatedName("系统");
            }
        }
        versionMapper.insert(v);

        // update doc
        newDoc.setId(id);
        newDoc.setVersion(old.getVersion() + 1);
        newDoc.setAuthorId(old.getAuthorId());
        newDoc.setAuthorName(old.getAuthorName());
        newDoc.setCallCount(old.getCallCount());
        newDoc.setSuccessCount(old.getSuccessCount());
        newDoc.setFailCount(old.getFailCount());
        newDoc.setRating(old.getRating());
        newDoc.setRatingCount(old.getRatingCount());
        docMapper.updateById(newDoc);
        Doc updated = docMapper.selectById(id);
        
        // 记录更新日志
        callLogService.logUpdate(updated);
        
        return updated;
    }

    @Transactional
    public void delete(Long id) {
        Doc doc = docMapper.selectById(id);
        String title = doc != null ? doc.getTitle() : "未知";
        docMapper.deleteById(id);
        
        // 记录删除日志
        callLogService.logDelete(id, title);
    }

    public void updateCallResult(Long docId, boolean success) {
        if (docId == null) return;
        Doc doc = docMapper.selectById(docId);
        if (doc == null) return;
        doc.setCallCount(doc.getCallCount() + 1);
        if (success) {
            doc.setSuccessCount(doc.getSuccessCount() + 1);
        } else {
            doc.setFailCount(doc.getFailCount() + 1);
        }
        docMapper.updateById(doc);
    }

    /**
     * 简单增加调用计数（不区分成功失败）
     */
    private void incrementCallCount(Long docId) {
        if (docId == null) return;
        Doc doc = docMapper.selectById(docId);
        if (doc == null) return;
        doc.setCallCount(doc.getCallCount() + 1);
        docMapper.updateById(doc);
    }

    @Transactional
    public void updateRating(Long docId, Integer rating) {
        if (docId == null || rating == null) return;
        Doc doc = docMapper.selectById(docId);
        if (doc == null) return;
        int count = doc.getRatingCount();
        BigDecimal avg = doc.getRating();
        BigDecimal newAvg = avg.multiply(new BigDecimal(count))
                .add(new BigDecimal(rating))
                .divide(new BigDecimal(count + 1), 2, BigDecimal.ROUND_HALF_UP);
        doc.setRating(newAvg);
        doc.setRatingCount(count + 1);
        docMapper.updateById(doc);
    }

    public List<DocVersion> getVersions(Long docId) {
        QueryWrapper<DocVersion> wrapper = new QueryWrapper<>();
        wrapper.eq("doc_id", docId).orderByDesc("version");
        return versionMapper.selectList(wrapper);
    }

    public long countAll() {
        return docMapper.selectCount(null);
    }

    public long countByAuthor(String authorId) {
        return docMapper.selectCount(new QueryWrapper<Doc>().eq("author_id", authorId));
    }

    public List<Doc> getHotDocs(int limit) {
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "ACTIVE").orderByDesc("call_count").last("LIMIT " + limit);
        return docMapper.selectList(wrapper);
    }

    public List<String> getCategories() {
        return docMapper.getCategories();
    }

    public long countProblemDocs() {
        return docMapper.selectCount(new QueryWrapper<Doc>().eq("status", "BROKEN"));
    }

    /**
     * 更新经验状态（ACTIVE / COMPLETED / BROKEN / DEPRECATED）
     */
    @Transactional
    public Doc updateStatus(Long id, String status) {
        Doc doc = docMapper.selectById(id);
        if (doc == null) {
            return null;
        }
        doc.setStatus(status);
        docMapper.updateById(doc);
        
        // 记录状态变更日志
        callLogService.logUpdate(doc);
        
        return doc;
    }

    /**
     * 按模板类型查询（用于 check_my_todos 等场景）
     */
    public List<Doc> listByTemplateType(String templateType) {
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        // API Key 级别隔离
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null) {
            wrapper.and(w -> w.eq("api_key", assistant.getApiKey()).or().isNull("api_key"));
        }
        wrapper.eq("template_type", templateType)
               .eq("status", "ACTIVE")
               .orderByDesc("updated_at");
        return docMapper.selectList(wrapper);
    }
}
