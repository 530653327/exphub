package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.Doc;
import com.exphub.entity.DocShare;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocShareMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DocShareService {

    private static final Logger log = LoggerFactory.getLogger(DocShareService.class);

    @Autowired
    private DocShareMapper shareMapper;

    @Autowired
    private DocMapper docMapper;

    /**
     * 创建分享链接
     * @param docId 经验ID
     * @param days 过期天数，-1 表示永不过期，0 表示当天过期
     * @param adminUserId 创建者（管理员用户ID）
     * @return 分享令牌 token
     */
    @Transactional
    public String createShare(Long docId, int days, Long adminUserId) {
        Doc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new IllegalArgumentException("经验不存在: " + docId);
        }

        DocShare share = new DocShare();
        share.setDocId(docId);
        share.setToken(UUID.randomUUID().toString().replace("-", ""));
        share.setCreatedBy(adminUserId);

        if (days >= 0) {
            share.setExpireAt(LocalDateTime.now().plusDays(days));
        }
        // days == -1: expireAt 保持 null，表示永不过期

        shareMapper.insert(share);
        log.info("创建分享链接: docId={}, token={}, expireAt={}, createdBy={}", docId, share.getToken(), share.getExpireAt(), adminUserId);
        return share.getToken();
    }

    /**
     * 通过 token 获取分享信息，如果已过期返回 null
     */
    public DocShare getByToken(String token) {
        DocShare share = shareMapper.selectOne(
            new QueryWrapper<DocShare>().eq("token", token)
        );
        if (share == null) return null;
        // 检查是否过期
        if (share.getExpireAt() != null && share.getExpireAt().isBefore(LocalDateTime.now())) {
            log.info("分享链接已过期: token={}, expireAt={}", token, share.getExpireAt());
            return null;
        }
        return share;
    }

    /**
     * 获取分享关联的经验文档
     */
    public Doc getSharedDoc(String token) {
        DocShare share = getByToken(token);
        if (share == null) return null;
        return docMapper.selectById(share.getDocId());
    }

    /**
     * 列出指定经验的分享链接
     */
    public List<DocShare> listByDocId(Long docId) {
        return shareMapper.selectList(
            new QueryWrapper<DocShare>()
                .eq("doc_id", docId)
                .orderByDesc("created_at")
        );
    }

    /**
     * 删除分享链接
     */
    @Transactional
    public void deleteShare(Long id) {
        shareMapper.deleteById(id);
        log.info("删除分享链接: id={}", id);
    }
}
