package com.exphub.util;

/**
 * MySQL LIKE 通配符转义（全库唯一实现，2026-11-20 抽出）。
 *
 * <p>用户输入里的百分号、下划线、反斜杠一律当字面量：不转义的话，
 * 搜「100%」会退化成匹配所有行，搜带下划线的项目编码（如 {@code hjs_app}）
 * 会额外命中任意单字符 —— 页面照样 200、数据照样有，只是多了几条，很难被发现。</p>
 *
 * <p>MySQL 的 LIKE 默认转义符就是反斜杠，所以加反斜杠就够，不用额外写 ESCAPE 子句。
 * 反斜杠本身要先转义，否则后面的转义符会被吃掉。</p>
 */
public final class LikeEscape {

    private LikeEscape() {
    }

    public static String escape(String raw) {
        if (raw == null) return null;
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
