package com.exphub.service;

import com.exphub.entity.Doc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窄列字段的应用层截断（2026-10-29 线上实测触发）。
 *
 * <p>触发经过：给 #714 写一条 500+ 字的 summary，`update_experience` 直接报
 * {@code Data truncation: Data too long for column 'summary'} —— 因为 sql_mode 含
 * {@code STRICT_TRANS_TABLES}，超长是整条 update 失败，不是静默截断。
 * 用户看到的是「更新失败」，真正原因是摘要多了十几个字，且报错里不含长度信息。
 *
 * <p>所以「应用层截到列宽」是正确性要求。下面按真实列宽钉住：
 * title 200｜summary 500｜tags 500｜aliases 500｜category 50｜chapter_title 200。
 */
class DocColumnClampTest {

    @Test
    @DisplayName("summary 超 500 截到 500 —— 就是这次线上报错的那个字段")
    void summaryClamped() {
        Doc d = new Doc();
        d.setSummary("摘".repeat(620));
        DocService.clampColumns(d);
        assertEquals(500, d.getSummary().length(), "summary 列宽 500");
    }

    @Test
    @DisplayName("title / tags / aliases / category / chapterTitle 各自按列宽截")
    void otherColumnsClamped() {
        Doc d = new Doc();
        d.setTitle("标".repeat(260));
        d.setTags("tag".repeat(200));
        d.setAliases("别名".repeat(400));
        d.setCategory("类".repeat(80));
        d.setChapterTitle("章".repeat(260));
        DocService.clampColumns(d);
        assertEquals(200, d.getTitle().length());
        assertEquals(500, d.getTags().length());
        assertEquals(500, d.getAliases().length());
        assertEquals(50, d.getCategory().length());
        assertEquals(200, d.getChapterTitle().length());
    }

    @Test
    @DisplayName("不超长的字段原样保留，null 保持 null（不能把没传的字段写成空串）")
    void shortValuesUntouchedAndNullPreserved() {
        Doc d = new Doc();
        d.setSummary("短摘要");
        d.setTitle(null);
        DocService.clampColumns(d);
        assertEquals("短摘要", d.getSummary());
        assertNull(d.getTitle(), "没传的字段必须保持 null，否则会被当成「清空」写库");
        assertNull(d.getTags());
    }

    @Test
    @DisplayName("emoji 压在边界上不被切成半个（否则 utf8mb4 写入报 Incorrect string value）")
    void surrogatePairNotSplit() {
        Doc d = new Doc();
        // 499 个汉字 + 一个 emoji（占 2 char）= 501 → 必须截断，但不能切开 emoji
        d.setSummary("字".repeat(499) + "😀");
        assertEquals(501, d.getSummary().length(), "样本前提");
        DocService.clampColumns(d);
        String s = d.getSummary();
        assertTrue(s.length() <= 500, "不能超列宽：" + s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                assertTrue(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)),
                        "第 " + i + " 位是孤立 high surrogate，写不进 utf8mb4");
            }
        }
    }
}
