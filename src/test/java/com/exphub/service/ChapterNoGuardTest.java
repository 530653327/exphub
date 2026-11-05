package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节号必填校验（2026-11-05）。
 *
 * <p>一次实测产生了 3 条脏数据（#1163 / #1164 / #1184），根因是 chapterNo 为空时：
 * ① 标题被拼成「第 null 章」；
 * ② 按章号查不到已有章节 → 每次调用都再新建一条，同一章在库里躺好几份。
 *
 * <p>这条拦截的意义不在「报错」，在于**不让错误静默变成数据**：
 * 之前它不报错、不中断，只是悄悄多出一条谁也不知道该不该留的章节。
 */
class ChapterNoGuardTest {

    @Test
    @DisplayName("null 章号被挡住，且说明为什么（不是一句「参数错误」）")
    void rejectsNullChapterNo() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProjectService.requireChapterNo(null));
        assertTrue(e.getMessage().contains("第 null 章"), "要说清会生成什么，实际=" + e.getMessage());
        assertTrue(e.getMessage().contains("重复章节"), "要说清后果（每次调用都新建），实际=" + e.getMessage());
    }

    @Test
    @DisplayName("空白章号同样挡住（空串比 null 更容易被忽略）")
    void rejectsBlankChapterNo() {
        assertThrows(IllegalArgumentException.class, () -> ProjectService.requireChapterNo(""));
        assertThrows(IllegalArgumentException.class, () -> ProjectService.requireChapterNo("   "));
    }

    @Test
    @DisplayName("正常章号原样返回，且去掉首尾空格（避免「1」和「 1」被当成两章）")
    void trimsValidChapterNo() {
        assertEquals("1", ProjectService.requireChapterNo("1"));
        assertEquals("15", ProjectService.requireChapterNo(" 15 "));
    }
}
