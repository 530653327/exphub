package com.exphub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 项目识别用的 df 常量表（#678 §5.3：「df 常量表不是第二道保险，它就是三分支的判据本身」）。
 *
 * <p>数据来自 {@code tools/df_snapshot.json}，构建时同步到 {@code classpath:/df_snapshot.json}；
 * 两份文件由 {@code AliasMatcherTest.dfSnapshotIsInSyncWithTools} 钉住，防止服务端读到旧语料。
 * 分母固定为全局域 ACTIVE 文档数（{@code n_global}），与 #677 12.2 口径一致。</p>
 *
 * <p><b>缺数不放行</b>：查不到词返回 {@code null}，调用方必须拒绝，不许当 0 处理 ——
 * 2026-11-05 B6/B7 曾因快照只有 40 词被误拒，重抓后转绿，规则本身没错。</p>
 */
public final class DfTable {

    private static final String RESOURCE = "/df_snapshot.json";
    private static final DfTable LOADED = load();

    private final int nGlobal;
    private final Map<String, Integer> df;
    private final String capturedAt;

    private DfTable(int nGlobal, Map<String, Integer> df, String capturedAt) {
        this.nGlobal = nGlobal;
        this.df = df;
        this.capturedAt = capturedAt;
    }

    private static DfTable load() {
        try (InputStream in = DfTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                System.err.println("DfTable: " + RESOURCE + " 不存在，项目识别将全部缺数不放行");
                return new DfTable(0, Map.of(), "");
            }
            JsonNode root = new ObjectMapper().readTree(in);
            int n = root.path("n_global").asInt(0);
            Map<String, Integer> map = new HashMap<>();
            JsonNode terms = root.path("df_global");
            terms.fields().forEachRemaining(
                    e -> map.put(normKey(e.getKey()), e.getValue().asInt()));
            return new DfTable(n, Map.copyOf(map), root.path("captured_at").asText(""));
        } catch (Exception e) {
            System.err.println("DfTable load failed: " + e.getMessage());
            return new DfTable(0, Map.of(), "");
        }
    }

    public static DfTable loaded() {
        return LOADED;
    }

    /** 词在全局域文档里出现的百分比；查不到返回 null（缺数不放行）。 */
    public Double pct(String term) {
        if (nGlobal <= 0 || term == null) return null;
        Integer count = df.get(normKey(term));
        return count == null ? null : count * 100.0 / nGlobal;
    }

    /**
     * 词表 key 与查询 term 必须走**同一套**归一（NFKC + 小写）。
     * 否则全角括号的别名（如「拆解宝（报废车回收模块）」）在判定侧被 NFKC 转成半角，
     * 查表时却用原 key，直接变成「df=UNKNOWN → 缺数不放行」的假漏召。
     */
    private static String normKey(String s) {
        return Normalizer.normalize(s == null ? "" : s.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    public int nGlobal() {
        return nGlobal;
    }

    public int terms() {
        return df.size();
    }

    public String capturedAt() {
        return capturedAt;
    }
}
