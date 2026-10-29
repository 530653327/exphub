package com.exphub.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具描述里「真冗余（重复陈述）」必须为 0 —— 守卫 #674 验收口径 C。
 *
 * <p><b>为什么换口径</b>：前两轮口径（「教学话术清零」「可压缩冗余 100% 清除」）都是
 * <b>主观、开放集</b> —— 阁主 2026-10-26 18:0x 明确指出：
 * <blockquote>
 * 前几轮清的是「教学话术」，这本来就是主观、开放集，再清十轮也清不干净。
 * 而真正量大、客观可枚举的重复陈述，我一次都没列过。
 * </blockquote>
 * 阁主给的终态判据是：<b>「真冗余（重复陈述）清零；功能信息按下表白名单保留」</b>，
 * 客观判据 = <b>「同一事实说了两遍」</b>，且<b>有唯一答案</b>。
 *
 * <p><b>本守卫实现的两条客观判据</b>（都不是审美判断）：
 * <ol>
 *   <li><b>判据 A —— 同工具内重复</b>：同一个工具的「工具描述 + 全部参数描述」合起来，
 *       同一个分句（以 。；！？ 切分）出现 ≥2 次 → 报红。
 *       典型：{@code mark_for_deletion} 的 authorName 与 updatedName 都在说「后台待删队列」。</li>
 *   <li><b>判据 B —— 跨工具机制说明重复</b>：同一类<b>机制说明</b>（等价优先规则 / Key 兜底 /
 *       多助手共用 Key / 仅写日志）在 ≥2 个工具里各说一遍 → 报红。
 *       判据是「这类机制全库只允许有一个权威处」。</li>
 * </ol>
 *
 * <p><b>⚠️ 上一版守卫有两条装饰性规则</b>（2026-10-26 18:0x 烛龙自查发现）：
 * <ul>
 *   <li>{@code {"两者都传时以本参数为准"}} —— 线上实际文案是「两者都传<b>以</b>本参数为准」，
 *       <b>少一个「时」字，字面匹配永远不命中</b>；</li>
 *   <li>{@code {"归档只是搬位置、不是删除"}} —— 线上实际是「归档不是删除」，同样永远不命中。</li>
 * </ul>
 * 这两条规则声称在抓「重复机制说明」，实际是<b>死的</b> —— 与「守卫绑了冒号漏掉无冒号形态」
 * 是同一族病：<b>规则表和被检查的对象对不上，绿灯就是空话</b>。
 * 本版不再用字面量表，改成按语义分类 + 权威处白名单。
 *
 * <p><b>白名单（阁主 2026-10-26 18:0x 拍板，一个字都别动）</b>：
 * 枚举值举例 / 字数上限 / 行为保证 / 安全警告 / 格式禁令 / 工具选择指引 / 「一次传一段」「一次一条」。
 * 这些是<b>功能信息</b>，删了模型就不知道该怎么用 —— 压过头比不压更贵。
 * {@link #whitelistFunctionalInfoMustSurvive()} 逐项断言它们还在。
 *
 * <p><b>数据源</b>：本守卫用反射读注解，与 Spring AI 生成 tools/list 的数据源同源
 * （{@code @Tool.description} / {@code @ToolParam.description}）。
 * 线上真实载荷的交叉验证由 {@code tools/tool_desc_measure.py verify} 完成
 * （用线上 tools/list JSON 逐条比对源码提取值，41 工具 / 158 参数必须完全一致）。
 */
class ToolDescriptionRedundancyTest {

    /**
     * 机制说明分类：{分类名, 识别该机制的正则}。
     *
     * <p>每类机制在<b>全库只允许有一个权威处</b>（{@link #MECH_AUTHORITY} 指定）。
     * 其余地方再出现 → 报红。
     *
     * <p>⚠️ 写这里的坑（上一版就踩过）：
     * <ol>
     *   <li><b>正则必须匹配线上真实文案</b>，不能凭印象写。上一版写「两者都传<b>时</b>以本参数为准」，
     *       线上是「两者都传<b>以</b>本参数为准」→ 规则永远不命中。</li>
     *   <li><b>用语义特征而不是整句</b>（如「等价」+「为准」），
     *       否则改一个字就又漏检 —— 跟「绑冒号」是同一类病。</li>
     * </ol>
     */
    private static final String[][] MECHANISMS = {
            // 签名参数的等价与优先级规则
            {"等价优先规则", "等价.*为准|为准.*等价"},
            // 签名兜底：不传时用 Key 绑定的助手名
            {"Key兜底", "不传=用[^。]*Key[^。]*绑定"},
            // 多助手共用同一 Key 时的必传要求
            {"多助手共用Key", "多助手[^。]*共用[^。]*Key[^。]*(必传|必须传)"},
            // 署名只写日志、不进快照
            {"仅写日志", "仅写日志"},
            // 「与 authorName 等价」——参数关系，每个工具都要说，属必要信息
            {"与authorName等价", "与\\s*authorName\\s*等价"},
    };

    /**
     * 每类机制的<b>唯一权威处</b>（工具名 + 参数名，参数名为 null 表示工具描述）。
     * 只有权威处允许出现该机制；其余位置出现 → 报红。
     */
    private static final Map<String, String[]> MECH_AUTHORITY = new LinkedHashMap<>();

    static {
        // 权威处的选择原则：<b>该机制最需要被模型知道的地方</b>。
        // 「等价优先规则」→ append_experience（写入快照，最常被调用的写入工具）。
        // 「Key兜底」「多助手共用Key」→ append_experience（署名落快照时归属错了最难追）。
        // 「仅写日志」→ cancel_deletion（撤销待删，署名需要留痕）。
        // 「与authorName等价」→ 无权威处限制（这是**参数关系**，每个工具都必须说，见 MECH_AUTHORITY_EXEMPT）。
        MECH_AUTHORITY.put("等价优先规则", new String[]{"append_experience", "updatedName"});
        MECH_AUTHORITY.put("Key兜底", new String[]{"append_experience", "authorName"});
        MECH_AUTHORITY.put("多助手共用Key", new String[]{"append_experience", "authorName"});
        MECH_AUTHORITY.put("仅写日志", new String[]{"cancel_deletion", "updatedName"});
    }

    /**
     * 豁免：这些机制<b>每个工具都必须说</b>，不适用「唯一权威处」限制。
     * 「与 authorName 等价」是**两个参数之间的关系** —— 模型调用该工具时必须知道这俩什么关系，
     * 删了就不知道该传哪个。这类不是「同一机制说多遍」，是「每处都在说各自的关系」。
     */
    private static final String[] MECH_AUTHORITY_EXEMPT = {"与authorName等价"};

    /**
     * 判据 A 的<b>必要重复白名单</b>：这些在同一工具内出现多次是**必要的**，不是冗余。
     *
     * <p>例：{@code update_experience} 的 tags / aliases / summary / relatedIds 四个参数
     * 各自都要说「清空用 clearFields」—— 四个字段是四个独立参数，各自要能独立看懂，
     * 只在工具描述里说一遍，模型看单个参数时就没法知道这个字段怎么清。
     */
    private static final String[] INTRA_TOOL_EXEMPT = {
            "清空用 clearFields",
    };

    // ═══════════════════════════════════════════════════════════════
    // ① 判据 A：同工具内，同一分句说了两遍
    // ═══════════════════════════════════════════════════════════════

    @Test
    void sameToolMustNotRepeatTheSameClauseTwice() {
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> e : byTool().entrySet()) {
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (String[] item : e.getValue()) {
                for (String clause : splitClauses(item[1])) {
                    if (clause.length() < 6) continue;
                    if (isExempt(clause, INTRA_TOOL_EXEMPT)) continue;
                    seen.merge(clause, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> c : seen.entrySet()) {
                if (c.getValue() >= 2) {
                    hits.add(e.getKey() + " 「" + c.getKey() + "」说了 " + c.getValue() + " 遍");
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "同一个工具内同一句说了两遍（真冗余）：\n  " + String.join("\n  ", hits));
    }

    /**
     * 判据 A 的补充：<b>同一工具内，两个不同参数说了同一件事</b>（分句不完全相同也算）。
     *
     * <p>为什么单靠「分句逐字相同」不够：真实的同工具内重复长这样 ——
     * <pre>
     *   authorName: 可选。署名（谁标的），显示在后台待删队列
     *   updatedName: 可选。**谁改的**——显示在后台待删队列
     * </pre>
     * 两句的<b>分句不完全相同</b>（前半段措辞不同），但都在说「后台待删队列」这同一个事实。
     * 只查分句相等 → 漏检。
     *
     * <p>所以这里用<b>子串包含</b>判重：若 A 的某个分句（去掉前缀修饰后）被 B 的分句包含，
     * 且两者长度差 ≥2 字（避免「可选。」这类碎片误判），判为同工具内重复。
     */
    @Test
    void sameToolMustNotSayTheSameThingInTwoDifferentWords() {
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> e : byTool().entrySet()) {
            List<String[]> items = e.getValue();
            for (int i = 0; i < items.size(); i++) {
                for (int j = i + 1; j < items.size(); j++) {
                    for (String ca : splitClauses(items.get(i)[1])) {
                        if (ca.length() < 8) continue;
                        if (isExempt(ca, INTRA_TOOL_EXEMPT)) continue;
                        for (String cb : splitClauses(items.get(j)[1])) {
                            if (cb.length() < 8) continue;
                            // 剥掉「可选。」「**谁改的**——」这类前缀修饰后比较实质内容
                            String na = coreOf(ca);
                            String nb = coreOf(cb);
                            if (na.length() < 8 || nb.length() < 8) continue;
                            if (Math.abs(na.length() - nb.length()) < 2) continue;
                            if (na.contains(nb) || nb.contains(na)) {
                                hits.add(e.getKey() + " 的 " + items.get(i)[0] + " 与 " + items.get(j)[0]
                                        + " 说了同一件事：「" + na + "」/「" + nb + "」");
                            }
                        }
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "同一个工具内两个参数说了同一件事（真冗余）：\n  " + String.join("\n  ", hits));
    }

    /** 剥掉前缀修饰（「可选。」「**谁改的**——」等），只留实质内容 */
    private String coreOf(String clause) {
        String s = clause;
        s = s.replaceAll("^可选[。\\.]?\\s*", "");
        s = s.replaceAll("^\\*\\*[^*]+\\*\\*——\\s*", "");
        s = s.replaceAll("^必填[。\\.]?\\s*", "");
        s = s.replaceAll("^\\s+", "");
        return s.trim();
    }

    // ═══════════════════════════════════════════════════════════════
    // ② 判据 B：跨工具的机制说明只允许一个权威处
    // ═══════════════════════════════════════════════════════════════

    @Test
    void eachMechanismMustHaveOnlyOneAuthority() {
        List<String> hits = new ArrayList<>();
        for (String[] mech : MECHANISMS) {
            String name = mech[0];
            if (Arrays.asList(MECH_AUTHORITY_EXEMPT).contains(name)) continue;
            String[] authority = MECH_AUTHORITY.get(name);
            if (authority == null) continue;
            Pattern p = Pattern.compile(mech[1]);
            for (Map.Entry<String, List<String[]>> e : byTool().entrySet()) {
                for (String[] item : e.getValue()) {
                    String where = item[0].equals("tool") ? e.getKey() : e.getKey() + "." + item[0];
                    boolean isAuthority = e.getKey().equals(authority[0]) && item[0].equals(authority[1]);
                    if (isAuthority) continue;
                    Matcher m = p.matcher(item[1]);
                    if (m.find()) {
                        hits.add("机制「" + name + "」在 " + where + " 又说了一遍（权威处只在 "
                                + authority[0] + "." + authority[1] + "）：" + m.group());
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "同一机制在多个工具各说一遍（真冗余）：\n  " + String.join("\n  ", hits));
    }

    // ═══════════════════════════════════════════════════════════════
    // ③ 白名单：功能信息必须还在（压过头比不压更贵）
    // ═══════════════════════════════════════════════════════════════

    @Test
    void whitelistFunctionalInfoMustSurvive() {
        // ⚠️ 白名单条目必须用**代码里实际存在的措辞**，不能按口述转写。
        //    第一版写「尚未删除」「不要写版本号」「改错字用 patch」——
        //    代码里实际是「别写版本号」「改错字」（没有「用 patch」），
        //    「尚未删除」根本不在描述文案里（它在 mark_for_deletion 的**回执**里，不是描述）。
        //    写成不存在的措辞 → 守卫永远报红 → 守卫变成噪声 → 大家开始忽略它。
        Map<String, String> must = new LinkedHashMap<>();
        // 枚举值举例（取值范围定义，删了模型不知道填什么）
        must.put("枚举值举例-bug_fix", "bug_fix");
        must.put("枚举值举例-过时/环境不符", "过时/环境不符");
        must.put("枚举值举例-templateType清单", "problem_solution/knowledge_doc");
        // 字数上限（副作用指引）
        must.put("字数上限-1500", "≤1500 字");
        must.put("字数上限-30", "30 字内");
        must.put("字数上限-3000", "≤3000 字");
        // 行为保证
        must.put("行为保证-不会误报", "不会误报");
        must.put("行为保证-立即消失", "立即从搜索结果消失");
        must.put("行为保证-标记待删不是删除", "标记待删，不是删除");
        must.put("行为保证-原样取回", "原样取回");
        // 安全警告
        must.put("安全警告-严禁", "严禁");
        must.put("安全警告-密钥", "密钥");
        must.put("安全警告-两道保险", "两道保险");
        // 格式禁令
        must.put("格式禁令-不可清空", "不可清空");
        must.put("格式禁令-别写版本号", "别写版本号");
        // 工具选择指引（路由，删了模型会选错工具）
        must.put("路由-改错字", "改错字");
        must.put("路由-不要整篇写回", "不要整篇写回");
        must.put("路由-一次传一段", "一次传一段");
        must.put("路由-一次一条", "一次一条");
        // 必要的数据安全语义（#674 红线：待删文档不能当正常文档用）
        must.put("数据安全-待删", "待删");
        must.put("数据安全-闸门在用户手里", "由用户在后台确认后才真正删除");

        String all = allDescriptions();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : must.entrySet()) {
            if (!all.contains(e.getValue())) {
                missing.add(e.getKey() + " → 「" + e.getValue() + "」不见了");
            }
        }
        assertTrue(missing.isEmpty(),
                "白名单里的功能信息被删了（压过头比不压更贵）：\n  " + String.join("\n  ", missing));
    }

    // ═══════════════════════════════════════════════════════════════
    // ④ 参数数与工具数定死（阁主 2026-10-26 18:0x：「158 vs 178 定死」）
    // ═══════════════════════════════════════════════════════════════

    @Test
    void toolAndParamCountMustBePinned() {
        // 158 是**线上 tools/list 的 inputSchema.properties 总数**，
        // 与源码 @ToolParam 数一致（tools/tool_desc_measure.py verify 逐工具零差异）。
        // 之前 #674 里并存「158」和「178」两个数 —— 178 是烛龙误报（提取器 bug 把 false 当参数名），
        // 作废。这个断言把数字钉死，下次参数真变了会立刻报红，不会又出现两个数并存。
        int toolCount = 0;
        int paramCount = 0;
        for (Method m : ExpHubTools.class.getDeclaredMethods()) {
            if (m.getAnnotation(Tool.class) == null) continue;
            toolCount++;
            for (Parameter p : m.getParameters()) {
                if (p.getAnnotation(ToolParam.class) != null) paramCount++;
            }
        }
        // 2026-10-28：41 → 43，新增 reopen_candidate（P0-2 撤回已判候选）
        // 与 list_archive_batches（P0-3 按批次查归档）。参数 158 → 163。
        // 2026-10-29：43 → 44，新增 list_candidate_history（判定流水可读）；参数 163 → 165。
        // 同日 P1-4：scan_similar_docs 加 enqueueProgress，参数 165 → 166。
        // 同日 P2-6：44 → 45，新增 replace_section（按节整节替换）；参数 166 → 170。
        // 同日 #752：五个写工具各加 verifiedAt（update/patch/append/replace_section/import_document），
        //            参数 170 → 175。工具数不变 —— create 是自动填，不需要参数。
        assertEquals(45, toolCount, "工具数变了（45 → " + toolCount + "），需要同步 README/门户/本断言");
        assertEquals(175, paramCount, "参数数变了（175 → " + paramCount + "），需要同步 README/门户/本断言");
    }

    // ═══════════════════════════════════════════════════════════════
    // ⑤ 反向验证：守卫必须能抓到它声称要抓的东西
    // ═══════════════════════════════════════════════════════════════

    @Test
    void guardMustCatchRealDuplication() {
        // 反测 1：同工具内重复（mark_for_deletion 原来的真实形态）
        // ⚠️ 两条必须**逐字相同**才构成同工具内重复（守卫按分句比对）。
        //    第一版反测写成「…队列。」vs「…队列」（一个带句号一个不带）→ 分句不同 → 守卫没抓到，
        //    差点把「守卫失效」当成「没有冗余」。
        List<String[]> oneTool = new ArrayList<>();
        oneTool.add(new String[]{"authorName", "可选。署名（谁标的），显示在后台待删队列"});
        oneTool.add(new String[]{"updatedName", "可选。**谁改的**——显示在后台待删队列"});
        assertTrue(sameToolContentDup("mark_for_deletion", oneTool).size() > 0,
                "守卫抓不住同工具内重复 —— 判据 A 已失效");

        // 反测 1b：分句/内容都不同就不算重复（不能误伤）
        List<String[]> notDup = new ArrayList<>();
        notDup.add(new String[]{"authorName", "可选。署名（谁标的），显示在后台待删队列"});
        notDup.add(new String[]{"updatedName", "可选。**谁改的**——与 authorName 等价"});
        assertTrue(sameToolContentDup("mark_for_deletion", notDup).isEmpty(),
                "守卫误伤了不同内容 —— 判据 A 太宽了");

        // 反测 2：跨工具机制重复（原文案「仅写日志」6 处）
        List<String[]> twoTools = new ArrayList<>();
        twoTools.add(new String[]{"cancel_deletion", "可选。**谁改的**——仅写日志"});
        twoTools.add(new String[]{"delete_project", "可选。**谁改的**——仅写日志"});
        assertTrue(mechHits("仅写日志", twoTools).size() > 0, "守卫抓不住跨工具机制重复 —— 判据 B 已失效");

        // 反测 3：白名单必须放行「清空用 clearFields」×4（必要重复，不是冗余）
        List<String[]> fourFields = new ArrayList<>();
        fourFields.add(new String[]{"tags", "可选。标签，逗号分隔。清空用 clearFields"});
        fourFields.add(new String[]{"aliases", "可选。别名，逗号分隔。清空用 clearFields"});
        assertTrue(intraToolHits("update_experience", fourFields).isEmpty(),
                "守卫误伤了必要重复（4 个字段各自的清空方式）");
    }

    @Test
    void guardMustNotFlagNecessaryInfo() {
        // 「与 authorName 等价」在每个工具都要说（参数关系），不该被判成机制重复
        List<String[]> many = new ArrayList<>();
        many.add(new String[]{"set_flags", "可选。**谁改的**——与 authorName 等价"});
        many.add(new String[]{"patch_experience", "可选。**谁改的**——与 authorName 等价"});
        assertTrue(mechHits("与authorName等价", many).isEmpty(),
                "守卫误伤了「与 authorName 等价」（每个工具都必须说的参数关系）");

        // 权威处本身不能被报红
        List<String[]> authority = new ArrayList<>();
        authority.add(new String[]{"append_experience", "可选。**谁改的**——与 authorName 等价，两者都传以本参数为准"});
        assertTrue(mechHits("等价优先规则", authority).isEmpty(),
                "守卫把权威处也报红了 —— 判据 B 的权威处定位有 bug");
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════

    /** 工具名 → [{参数名(null=工具描述), 描述}] */
    private Map<String, List<String[]>> byTool() {
        Map<String, List<String[]>> map = new LinkedHashMap<>();
        for (Method m : ExpHubTools.class.getDeclaredMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool == null) continue;
            List<String[]> items = new ArrayList<>();
            items.add(new String[]{"tool", tool.description()});
            for (Parameter p : m.getParameters()) {
                ToolParam tp = p.getAnnotation(ToolParam.class);
                if (tp == null) continue;
                items.add(new String[]{p.getName(), tp.description()});
            }
            map.put(tool.name(), items);
        }
        return map;
    }

    private String allDescriptions() {
        StringBuilder sb = new StringBuilder();
        for (Method m : ExpHubTools.class.getDeclaredMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool == null) continue;
            sb.append(tool.description()).append('\n');
            for (Parameter p : m.getParameters()) {
                ToolParam tp = p.getAnnotation(ToolParam.class);
                if (tp == null) continue;
                sb.append(tp.description()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 判据 A 的命中检测（供反测复用） */
    private List<String> intraToolHits(String toolName, List<String[]> items) {
        List<String> hits = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String[] item : items) {
            for (String clause : splitClauses(item[1])) {
                if (clause.length() < 6) continue;
                if (isExempt(clause, INTRA_TOOL_EXEMPT)) continue;
                seen.merge(clause, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> c : seen.entrySet()) {
            if (c.getValue() >= 2) hits.add(toolName + " 「" + c.getKey() + "」×" + c.getValue());
        }
        return hits;
    }

    /** 判据 A 的「同工具内说了同一件事」命中检测（供反测复用） */
    private List<String> sameToolContentDup(String toolName, List<String[]> items) {
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                for (String ca : splitClauses(items.get(i)[1])) {
                    if (ca.length() < 8) continue;
                    if (isExempt(ca, INTRA_TOOL_EXEMPT)) continue;
                    for (String cb : splitClauses(items.get(j)[1])) {
                        if (cb.length() < 8) continue;
                        String na = coreOf(ca);
                        String nb = coreOf(cb);
                        if (na.length() < 8 || nb.length() < 8) continue;
                        if (Math.abs(na.length() - nb.length()) < 2) continue;
                        if (na.contains(nb) || nb.contains(na)) {
                            hits.add(toolName + " 「" + na + "」/「" + nb + "」");
                        }
                    }
                }
            }
        }
        return hits;
    }

    /** 判据 B 的命中检测（供反测复用）：items = [{工具名, 描述}] */
    private List<String> mechHits(String mechName, List<String[]> items) {
        String[] mech = null;
        for (String[] m : MECHANISMS) {
            if (m[0].equals(mechName)) mech = m;
        }
        if (mech == null) return List.of();
        if (Arrays.asList(MECH_AUTHORITY_EXEMPT).contains(mechName)) return List.of();
        String[] authority = MECH_AUTHORITY.get(mechName);
        if (authority == null) return List.of();
        Pattern p = Pattern.compile(mech[1]);
        List<String> hits = new ArrayList<>();
        for (String[] item : items) {
            boolean isAuthority = item[0].equals(authority[0]);
            if (isAuthority) continue;
            if (p.matcher(item[1]).find()) hits.add(item[0] + ": " + item[1]);
        }
        return hits;
    }

    /** 以中文句末标点切分 */
    private List<String> splitClauses(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("(?<=[。；！？])")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private boolean isExempt(String clause, String[] exemptList) {
        for (String e : exemptList) {
            if (clause.contains(e)) return true;
        }
        return false;
    }
}
