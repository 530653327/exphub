package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.Doc;
import com.exphub.entity.DocVersion;
import com.exphub.entity.DocVersionArchive;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocVersionArchiveMapper;
import com.exphub.mapper.DocVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本快照归档：把 {@code doc_versions} 里超出保留窗口的历史版本搬到 {@code doc_versions_archive}。
 *
 * <p><b>解决的问题</b>（越哥 2026-10-26 00:50 拍板）：
 * 文档自己写着「没有归档能力」，而验证动作本身在制造快照膨胀 ——
 * 阁主为验证写 7 次 patch，#654 从 v25 涨到 v32；烛龙又改多轮到 v42。
 * <b>整理记忆的动作正在喂大它要整理的东西。</b>
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li><b>绝不碰 {@code docs.content}</b> —— 归档的只是历史副本，当前正文一个字都不动</li>
 *   <li><b>先写归档表、再删主表</b>，且同一事务。反序会出现「主表删了、归档没写成」的丢数据窗口</li>
 *   <li><b>不删最新版本</b> —— 保留窗口按 version 倒序算，永远保住最近的 N 版</li>
 * </ol>
 *
 * <p><b>为什么保留窗口按「版本数」而不是「时间」</b>：
 * 高频文档（如 #654 一晚上 v1→v42）按时间算会保留太多，低频文档按时间算会只剩 1 版。
 * 版本数才能保证「无论改得多频繁，都还能回看最近 N 次改了什么」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionArchiveService {

    /** 默认保留最近 10 版：够回看近期改动，又不会让主表继续膨胀 */
    public static final int DEFAULT_KEEP_RECENT = 10;
    /** 保留窗口下限：少于 3 版就失去「回看历史」的意义 */
    public static final int MIN_KEEP_RECENT = 3;
    /** 保留窗口上限，防止有人传 99999 变成什么都不做 */
    public static final int MAX_KEEP_RECENT = 200;

    private final DocVersionMapper versionMapper;
    private final DocVersionArchiveMapper archiveMapper;
    private final DocMapper docMapper;

    /** 单篇文档的归档结果 */
    public record DocArchiveResult(Long docId, String title, int keptBefore, int archived, int keptAfter) {}

    /** 整体归档结果 */
    public record ArchiveResult(boolean dryRun,
                                int keepRecent,
                                int docsScanned,
                                int docsTouched,
                                int archivedRows,
                                long archivedBytes,
                                int remainingRows,
                                List<DocArchiveResult> details,
                                List<String> notes) {}

    /**
     * 执行归档。
     *
     * @param docId      只处理某篇；null = 全库
     * @param keepRecent 每篇保留最近几版
     * @param dryRun     true = 只算不写（默认行为由调用方决定）
     * @param operator   操作人，写入 archive.archived_by
     */
    @Transactional
    public ArchiveResult archive(Long docId, Integer keepRecent, boolean dryRun, String operator) {
        int keep = normalizeKeep(keepRecent);
        List<String> notes = new ArrayList<>();

        // 找出「版本数超过保留窗口」的文档。只按数量筛，不按内容 —— 归档与内容无关
        QueryWrapper<DocVersion> probe = new QueryWrapper<>();
        probe.select("doc_id", "COUNT(*) AS cnt").groupBy("doc_id").having("COUNT(*) > " + keep);
        if (docId != null) probe.eq("doc_id", docId);
        List<Map<String, Object>> over = versionMapper.selectMaps(probe);

        if (over.isEmpty()) {
            notes.add("没有文档的版本数超过 " + keep + " 版，无需归档。");
            return new ArchiveResult(dryRun, keep, 0, 0, 0, 0,
                    countVersions(null), List.of(), notes);
        }

        List<DocArchiveResult> details = new ArrayList<>();
        int archivedTotal = 0;
        long bytesTotal = 0;
        int docsTouched = 0;

        for (Map<String, Object> row : over) {
            Long id = ((Number) row.get("doc_id")).longValue();
            int cnt = ((Number) row.get("cnt")).intValue();

            // 取该文档全部版本，按 version 倒序：前 keep 个保留，其余归档
            List<DocVersion> all = versionMapper.selectList(
                    new QueryWrapper<DocVersion>().eq("doc_id", id).orderByDesc("version"));
            if (all.size() <= keep) continue;

            List<DocVersion> toArchive = all.subList(keep, all.size());
            Doc doc = docMapper.selectById(id);
            String title = doc == null ? null : doc.getTitle();

            if (dryRun) {
                int bytes = 0;
                for (DocVersion v : toArchive) {
                    bytes += v.getContent() == null ? 0 : v.getContent().length();
                }
                details.add(new DocArchiveResult(id, title, cnt, toArchive.size(), keep));
                archivedTotal += toArchive.size();
                bytesTotal += bytes;
                if (!toArchive.isEmpty()) docsTouched++;
                continue;
            }

            // ⚠️ 顺序不能反：先插归档表，成功后再删主表。
            // 反序 = 「主表已删、归档没写成」→ 数据永久丢失，而归档的全部意义就是「可回查」
            List<DocVersionArchive> archives = new ArrayList<>(toArchive.size());
            for (DocVersion v : toArchive) {
                DocVersionArchive a = new DocVersionArchive();
                a.setDocId(v.getDocId());
                a.setDocTitle(title);
                a.setVersion(v.getVersion());
                a.setContent(v.getContent());
                a.setAliases(v.getAliases());
                a.setSummary(v.getSummary());
                a.setUpdatedBy(v.getUpdatedBy());
                a.setUpdatedName(v.getUpdatedName());
                a.setChangeType(v.getChangeType());
                // 注意：搬原时间，不是 now()。归档时间单独放 archivedAt
                a.setCreatedAt(v.getCreatedAt());
                a.setArchivedAt(LocalDateTime.now());
                a.setArchivedBy(operator);
                a.setArchiveReason("保留最近 " + keep + " 版，超出部分归档");
                archives.add(a);
            }
            int inserted = insertAll(archives);
            if (inserted != toArchive.size()) {
                // 插入数不符时不删主表 —— 宁可这次白做，也不能少一行
                notes.add("#" + id + " 归档插入 " + inserted + " / 期望 " + toArchive.size()
                        + " 行，为安全起见**未删除主表任何行**");
                continue;
            }

            // 归档确认写入后，才删主表对应行
            List<Long> ids = new ArrayList<>(toArchive.size());
            for (DocVersion v : toArchive) ids.add(v.getId());
            int deleted = versionMapper.deleteBatchIds(ids);
            if (deleted != ids.size()) {
                notes.add("#" + id + " 主表删除 " + deleted + " / 期望 " + ids.size()
                        + " 行（归档已写入，数据不丢，但需人工核对残留）");
            }

            long bytes = 0;
            for (DocVersion v : toArchive) bytes += v.getContent() == null ? 0 : v.getContent().length();
            details.add(new DocArchiveResult(id, title, cnt, toArchive.size(), keep));
            archivedTotal += toArchive.size();
            bytesTotal += bytes;
            docsTouched++;
        }

        int remaining = countVersions(docId);
        log.info("VersionArchiveService.archive: dryRun={} keep={} archived={} docs={} by={}",
                dryRun, keep, archivedTotal, docsTouched, operator);
        return new ArchiveResult(dryRun, keep, over.size(), docsTouched, archivedTotal, bytesTotal,
                remaining, details, notes);
    }

    /** 分批插入，避免单条 SQL 太大 */
    private int insertAll(List<DocVersionArchive> list) {
        int n = 0;
        for (DocVersionArchive a : list) {
            n += archiveMapper.insert(a);
        }
        return n;
    }

    /** 主表当前行数（只算某篇或全库） */
    private int countVersions(Long docId) {
        QueryWrapper<DocVersion> qw = new QueryWrapper<>();
        if (docId != null) qw.eq("doc_id", docId);
        return Math.toIntExact(versionMapper.selectCount(qw));
    }

    /** 保留窗口参数收敛：越界不静默修正，由调用方决定是否拒绝（见 ExpHubTools 的分层校验） */
    public static int normalizeKeep(Integer keepRecent) {
        if (keepRecent == null) return DEFAULT_KEEP_RECENT;
        if (keepRecent < MIN_KEEP_RECENT) return MIN_KEEP_RECENT;
        if (keepRecent > MAX_KEEP_RECENT) return MAX_KEEP_RECENT;
        return keepRecent;
    }

    public static boolean isKeepValid(Integer keepRecent) {
        if (keepRecent == null) return true;
        return keepRecent >= MIN_KEEP_RECENT && keepRecent <= MAX_KEEP_RECENT;
    }

    /** 归档表概览（给人看「到底存了多少、占多大」） */
    public Map<String, Object> archiveStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        QueryWrapper<DocVersionArchive> qw = new QueryWrapper<>();
        long rows = archiveMapper.selectCount(qw);
        long docs = 0;
        QueryWrapper<DocVersionArchive> g = new QueryWrapper<>();
        g.select("COUNT(DISTINCT doc_id) AS c");
        List<Map<String, Object>> m = archiveMapper.selectMaps(g);
        if (!m.isEmpty() && m.get(0).get("c") != null) docs = ((Number) m.get(0).get("c")).longValue();
        out.put("archivedRows", rows);
        out.put("archivedDocs", docs);
        return out;
    }

    /** 某篇文档的归档明细（回查用：证明「搬走的那几版」都能原样取回） */
    public List<DocVersionArchive> listArchived(Long docId) {
        if (docId == null) return List.of();
        return archiveMapper.selectList(new QueryWrapper<DocVersionArchive>()
                .eq("doc_id", docId).orderByAsc("version"));
    }
}
