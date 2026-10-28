package com.exphub.service;

import com.exphub.entity.DocVersionArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 从 {@code archive_reason} 里抠批次号（#714 P0-3：超时后要能按批次确认「到底搬完没有」）。
 *
 * <p>batch 后缀是 2026-10-28 才加的，所以**老数据抠不到是正常的**，不能当成解析失败报错。
 */
class VersionArchiveBatchTest {

    private static DocVersionArchive row(String reason) {
        DocVersionArchive a = new DocVersionArchive();
        a.setDocId(1L);
        a.setVersion(1);
        a.setArchiveReason(reason);
        return a;
    }

    @Test
    @DisplayName("正常形态：中文原因 + batch 后缀")
    void normal() {
        assertEquals("AR20261028-230401",
                VersionArchiveService.batchOf(row("保留最近 10 版，超出部分归档｜batch=AR20261028-230401")));
    }

    @Test
    @DisplayName("老数据没有 batch 后缀 → null（不是解析失败）")
    void legacyRow() {
        assertNull(VersionArchiveService.batchOf(row("保留最近 10 版，超出部分归档")));
        assertNull(VersionArchiveService.batchOf(row(null)));
        assertNull(VersionArchiveService.batchOf(null));
    }

    @Test
    @DisplayName("批次号后面还有别的字，不能把尾巴吞进来")
    void trailingText() {
        assertEquals("AR20261028-230401",
                VersionArchiveService.batchOf(row("归档｜batch=AR20261028-230401｜备注：手工")));
    }

    @Test
    @DisplayName("多个 batch 只取第一个（正常不会出现，防止脏数据把聚合搞乱）")
    void firstWins() {
        assertEquals("AR-A",
                VersionArchiveService.batchOf(row("batch=AR-A batch=AR-B")));
    }
}
