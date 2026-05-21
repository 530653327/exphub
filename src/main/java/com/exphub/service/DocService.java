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
            log.info("DocService.create: setting author={}/{}", assistant.getAssistantId(), assistant.getAssistantName());
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
        Page<Doc> p = new Page<>(page, size);
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            // 使用 LIKE 模糊搜索（兼容所有 MySQL 环境）
            wrapper.and(w -> w.like("title", kw)
                    .or().like("content", kw)
                    .or().like("aliases", kw)
                    .or().like("summary", kw)
                    .or().like("tags", kw));
            wrapper.orderByDesc("updated_at");
        } else {
            wrapper.orderByDesc("updated_at");
        }
        Page<Doc> result = docMapper.selectPage(p, wrapper);
        
        // 记录搜索日志
        callLogService.logSearch(keyword, (int) result.getTotal());
        
        return result;
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
        return docMapper.selectById(id);
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
}
