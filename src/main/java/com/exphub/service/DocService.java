package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.entity.DocVersion;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocVersionMapper;
import com.exphub.interceptor.ApiKeyInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class DocService {

    @Autowired
    private DocMapper docMapper;

    @Autowired
    private DocVersionMapper versionMapper;

    @Transactional
    public Doc create(Doc doc) {
        AiAssistant assistant = ApiKeyInterceptor.CURRENT_ASSISTANT.get();
        doc.setAuthorId(assistant.getAssistantId());
        doc.setAuthorName(assistant.getAssistantName());
        doc.setVersion(1);
        doc.setCallCount(0);
        doc.setSuccessCount(0);
        doc.setFailCount(0);
        doc.setRating(new BigDecimal("5.00"));
        doc.setRatingCount(0);
        doc.setStatus("ACTIVE");
        docMapper.insert(doc);
        return doc;
    }

    public Page<Doc> search(String keyword, int page, int size) {
        Page<Doc> p = new Page<>(page, size);
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String sql = "MATCH(title, content, aliases, summary, tags) AGAINST('" + keyword.replace("'", "''") + "' IN NATURAL LANGUAGE MODE)";
            wrapper.apply(sql);
            wrapper.orderByDesc("call_count", "updated_at");
        } else {
            wrapper.orderByDesc("updated_at");
        }
        return docMapper.selectPage(p, wrapper);
    }

    public Page<Doc> listByCategory(String category, int page, int size) {
        Page<Doc> p = new Page<>(page, size);
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        wrapper.eq("category", category);
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
        AiAssistant assistant = ApiKeyInterceptor.CURRENT_ASSISTANT.get();
        v.setUpdatedBy(assistant.getAssistantId());
        v.setUpdatedName(assistant.getAssistantName());
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
        return docMapper.selectById(id);
    }

    @Transactional
    public void delete(Long id) {
        docMapper.deleteById(id);
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
