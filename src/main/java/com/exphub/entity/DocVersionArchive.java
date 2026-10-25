package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 版本快照冷存档（{@code doc_versions_archive}）。
 *
 * <p><b>为什么要这张表</b>（2026-10-26）：{@code doc_versions} 只增不减，
 * 而「验证一个改动」本身就要写多次 patch —— 整理/验证动作正在喂大它要清理的东西。
 * 实测 598 条 / 3.87 MB，Top5 文档占 42%。没有归档能力，瘦身只能手工 SQL。
 *
 * <p><b>为什么不直接删</b>：红线「只标记、不删除」。删了就永远回查不到
 * 「当时那版到底写的是什么」。搬到冷表既能瘦身主表，又保住证据链。
 *
 * <p><b>为什么没有 {@code @TableField} 时间填充注解</b>：{@code createdAt} 存的是
 * <b>原版本的创建时间</b>，不是归档时间，必须从 {@link DocVersion} 原样搬过来，
 * 不能由框架填当前时间。归档时间单独放 {@code archivedAt}。
 *
 * <p><b>为什么不建外键到 docs</b>：加了 {@code ON DELETE CASCADE} 之后，
 * 删文档会把存档一起带走 —— 那存档就名存实亡。宁可留孤儿，也不让证据跟着正文消失。
 * 所以这里冗余存 {@code docTitle}，原文档删了仍能看懂这是哪篇的哪个版本。
 */
@Data
@TableName("doc_versions_archive")
public class DocVersionArchive {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    /** 冗余存标题：原文档删除后仍能看懂 */
    private String docTitle;
    private Integer version;
    private String content;
    private String aliases;
    private String summary;
    private String updatedBy;
    private String updatedName;
    private String changeType;
    /** 该版本原本的创建时间（不是归档时间） */
    private LocalDateTime createdAt;
    private LocalDateTime archivedAt;
    private String archivedBy;
    private String archiveReason;
}
