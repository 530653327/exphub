package com.exphub.service;

/**
 * 一次正文写入的「来历」——决定快照留痕方式，也决定要不要在日志里点名「本条没校验 hash」。
 *
 * <p>为什么要显式区分：A5 B 方案（2026-10-30）把「整篇写回必须带 originalHash」立在
 * MCP 工具层，而 {@code DocService.update} 同时被门户、迁移脚本、系统回填复用。
 * 后者拿不到也不该拿 hash（人是在浏览器里直接编辑全文的），
 * <b>但不能因此变成没人知道的旁路</b> —— 所以它必须落到 {@link #INTERNAL} 并且每次写正文留一行日志。
 */
public enum WriteOrigin {

    /** 内部/门户路径：不校验 hash（调用方本来就持有全文），写正文时记一行日志留痕。 */
    INTERNAL,

    /**
     * 局部编辑工具（{@code replace_section}）：服务端自己拼出新全文，调用方从没持有过全文。
     *
     * <p>它和 {@link #INTERNAL} 的区别只在「日志里怎么称呼」：把局部工具混进「门户/迁移」里，
     * 事后翻日志会以为门户改过这篇。两者都确实没有 hash 可比 —— 也不需要。
     */
    LOCAL_EDIT,

    /** MCP 整篇写回（update_experience / import_document）：调用方已用 originalHash 证明读到全文。 */
    HASH_VERIFIED,

    /**
     * 明知本次写回会截断/精简正文，仍然要写（force=true）。
     *
     * <p>⚠️ 它<b>不能</b>跳过 hash 校验 —— 跳过校验的 force 就是 #124（6240 字 → 26 字）的绕道。
     * 这里只表示「hash 对上了，而且我知道会变短」，快照 change_type 额外标 {@code CONTENT_FORCED}。
     */
    FORCED
}
