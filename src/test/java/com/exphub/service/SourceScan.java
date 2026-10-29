package com.exphub.service;

/**
 * 测试辅助：按签名取出方法体（大括号配对），供「按方法名钉写路径」的守卫使用。
 *
 * <p>为什么要它：守卫原来用「数 {@code setLastVerifiedAt(...)} 出现次数 = 4」。一旦跨两个调用面
 * （{@code resolveVerifiedAt} 与白名单判定 {@code vd.at()}）合并计数，这个数字就失去意义 ——
 * 少一条路径、另一条重复计入，总和照样是 4，测试照样绿。**数量是结果，不能当判定规则。**
 *
 * <p>局限：不做词法分析，字符串/注释里的花括号会干扰配对。所以取不到或取到整文件时返回空串，
 * 由调用方断言「方法体必须非空」，避免静默放行。
 */
final class SourceScan {

    private SourceScan() {
    }

    /**
     * 返回从 {@code signature} 所在方法的第一个 '{' 到配对 '}' 之间的文本（含两端大括号）。
     * 找不到签名、找不到左括号、或括号不配对（取到了文件尾）→ 返回空串。
     */
    static String methodBody(String code, String signature) {
        if (code == null || signature == null) return "";
        int at = code.indexOf(signature);
        if (at < 0) return "";
        int open = code.indexOf('{', at);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String body = code.substring(open, i + 1);
                    // 取到整文件说明配对失败（或者签名就在最外层），一律当失败处理
                    return body.length() >= code.length() ? "" : body;
                }
            }
        }
        return "";
    }
}
