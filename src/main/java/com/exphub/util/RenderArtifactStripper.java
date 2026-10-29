package com.exphub.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 渲染痕迹剥离工具（E11 / E13）。
 *
 * <p>原实现是 {@code ExpHubTools.stripRenderArtifacts}（private static），只覆盖 MCP 经验增删改与导入；
 * 2026-10-25 实测发现 service 层写入路径（append_project_progress / AGENTS.md MAIN 与章节）**没有剥离**，
 * 于是把纯文本处理逻辑下沉到这里，mcp 层与 service 层共用同一份规则，避免以后再各写一套。</p>
 */
public final class RenderArtifactStripper {

    private RenderArtifactStripper() {
    }

        /**
         * 剥离「读取端渲染产生的痕迹」（E11，2026-10-24 忆阁实测发现）。
         *
         * 背景（这是一次真实事故）：get_experience_detail / export_document 返回的是
         * **渲染视图**——除了正文，还自动拼了历史导读、关联经验列表、「📌 文档结尾」标记。
         * AI 维护文档时的典型动作是「读出来 → 改一点 → 整体写回」，
         * 如果直接把读到的内容写回 content，这些**渲染出来的内容就被固化进数据库**，
         * 而且每写一次叠一层：实测 ID=101 的 content 里积了 v5~v9 五段历史导读。
         *
         * 同类错误已发生过一次（E1：MAIN 索引的标题/目录重复），那次是靠「改工具描述+读取端清洗」，
         * 这次证明**必须再加一道写入端防御**——不能指望调用方把读到的内容自己摘干净。
         *
         * 剥离清单：
         *  - 历史导读块（> 📚 **历史导读** 起，到分隔线）
         *  - 关联经验段（## 📎 关联经验 起，到结尾——它是读取时按 relatedIds 实时渲染的）
         *  - 「📌 文档结尾」标记
         *  - 头部元信息块（**分类** / **作者** / **版本** / **标签** / **状态** / **验证时间** / **关联经验**: —— 渲染时加的行）
         *  - **导出回执头**（📄 文档导出成功！… / 内容已完整导出（共 N 字）。）
         *    —— export_document 是长文读取的主路径，它同样会拼一段头；不剥就会被整篇写回固化
         *  - **存疑横幅**（D1，2026-10-25 加）：⛔ 已被取代 / 📌 本条取代了 / ⚑ 标记 / 标记说明 / 标记人
         *    以及它们后面那两行 `> 结论以新条为准…` / `> ⚠️ 本条被标记为…`
         *    —— 这几块是**读取端的提示**，不是文档内容。剥不掉就会随写回变成正文里的一行「已把本条标记为待复核」，
         *       而正文里的这句提示永远不会随着标记清除而消失（自己给自己刻了个假碑）
         *  - 终端残留（脚本 Terminated / curl 报错 / bash 行号）—— 这是 AI 复制输出时误带的
         */
        public static String strip(String content) {
            if (content == null || content.isEmpty()) {
                return "";
            }
            String[] lines = content.split("\n", -1);
            StringBuilder kept = new StringBuilder();
            boolean inHeaderMeta = true;   // 开头的元信息块（遇到第一个非元信息行就关闭）
            int i = 0;
            while (i < lines.length) {
                String raw = lines[i];
                String t = raw.trim();

                // 1) 历史导读块：整块跳过（含其后的空行与分隔线）
                if (t.matches("^>\\s*📚?\\s*\\*\\*历史导读\\*\\*.*$")) {
                    i++;
                    while (i < lines.length && (lines[i].trim().startsWith(">") || lines[i].trim().isEmpty())) {
                        i++;
                    }
                    if (i < lines.length && lines[i].trim().matches("^-{3,}$")) {
                        i++;
                    }
                    continue;
                }

                // 2) 关联经验段：只跳过该段本身，**不能 break** ——
                // 实测 ID=101 里有 4 段关联经验夹在正文中间，break 会把后面所有正文一起丢掉
                if (t.matches("^#{1,6}\\s*\\S*\\s*关联经验\\s*$") || t.matches("^\\*\\*关联经验\\*\\*\\s*[:：].*$")) {
                    i++;
                    while (i < lines.length) {
                        String cur = lines[i].trim();
                        if (cur.matches("^-{3,}$") || cur.matches("^#{1,6}\\s+\\S.*$")) {
                            break;
                        }
                        i++;
                    }
                    // 停在分隔线上时一并吃掉
                    if (i < lines.length && lines[i].trim().matches("^-{3,}$")) {
                        i++;
                    }
                    continue;
                }

                // 3) 文档结尾标记
                if (t.matches("^(📌\\s*)?文档结尾.*$") || t.matches("^📌.*文档结尾.*$")) {
                    i++;
                    continue;
                }

                // 4) 导出回执头（export_document 拼的，不是正文）
                if (t.matches("^📄\\s*文档导出成功.*$")
                        || t.matches("^内容已完整导出（共\\s*[0-9,]+\\s*字）.*$")) {
                    i++;
                    continue;
                }

                // 5) 存疑横幅（D1）：取代关系与标记，都是读取端提示，不是文档内容。
                //    只认这几个明确前缀，不用宽泛的「包含标记就丢」——
                //    正文里本来就可能出现「标记」二字，宽泛匹配会吃掉真内容。
                //
                //    ⚠️ 2026-10-26 16:5x 补：新增「🗑 待删」族（待删状态块）。
                //    这块是**读端渲染出来的**（detail / export 共用 doubtBanner），
                //    剥不掉就会在「读出来 → 改一点 → 整篇写回」时把
                //    「🗑 待删: 本条已标记为待删」固化进正文，
                //    而且那行提示**不会随后续 cancel_deletion 消失** —— 等于给文档刻了个假碑。
                //    第四行「> ⚠️ 本条被标记为…」复用既有前缀，无需新增。
                if (t.matches("^\\*\\*\\s*(⛔\\s*已被取代|📌\\s*本条取代了|⚑\\s*标记|标记说明|标记人"
                            + "|🗑\\s*待删|🗑\\s*待删原因|🗑\\s*待删标记人)\\s*\\*\\*\\s*[:：].*$")
                        || t.matches("^>\\s*结论以新条为准.*$")
                        || t.matches("^>\\s*⚠️?\\s*本条被标记为.*$")) {
                    i++;
                    continue;
                }

                // 6) 终端残留
                if (t.matches("^.*mcpcall\\.sh: line \\d+:.*$")
                        || t.matches("^.*Terminated:\\s*\\d+.*$")
                        || t.matches("^bash: line \\d+:.*$")
                        || t.matches("^curl: \\(\\d+\\).*$")) {
                    i++;
                    continue;
                }

                // 7) 头部元信息块：只处理开头连续的 **xxx**: 行与分隔线
                //    ⚠️ 2026-10-26 16:5x 补「状态」：detail 头部现在有 `**状态**: 🗑 待删（未删除）`
                //    这一行（阁主实跑发现回执不提待删状态）。它同样是渲染产物，
                //    不剥就会被整篇写回固化进正文 —— 而正文里的状态行永远不会随标记清除而更新，
                //    等于给文档刻了个假碑。
                if (inHeaderMeta) {
                    if (t.matches("^\\*\\*(分类|作者|版本|标签|关联经验|状态|验证时间)\\*\\*\\s*[:：].*$")
                            || t.matches("^-{3,}$")) {
                        i++;
                        continue;
                    }
                    // 空行：开头那几个是排版残留，跳过；标题之后的空行属于正文，
                    // 吃掉会让「# 标题\n\n正文」被压成「# 标题\n正文」
                    if (t.isEmpty()) {
                        if (kept.length() > 0) {
                            kept.append(raw).append("\n");
                        }
                        i++;
                        continue;   // 注意：空行不退出元信息区，**分类** 行可能在它后面
                    }
                    // `# 文档标题` 保留（正文结构的一部分），但仍在元信息区内——
                    // 让紧随其后的 **分类** / **版本** 行继续被剥掉
                    if (t.matches("^#\\s+\\S.*$")) {
                        kept.append(raw).append("\n");
                        i++;
                        continue;
                    }
                    inHeaderMeta = false;
                }

                kept.append(raw).append("\n");
                i++;
            }
            // 8) 开头重复的同名 H1 只留一个。
            //    渲染视图会把「# 标题」拼在最前面，而文档正文自己也可能以同名 H1 开头，
            //    导读和元信息都被剥掉后，这两行就贴在一起变成两行相同标题。
            //    （2026-10-24 23:4x 拿 ID=101 做写入端幂等实验时实测出来的：
            //    导读/关联经验确被剥离，但标题被复制了一份。）
            String[] out = kept.toString().split("\n", -1);
            List<Integer> dupH1 = new ArrayList<>();
            for (int k = 0; k < out.length; k++) {
                String t = out[k].trim();
                if (t.isEmpty() || t.matches("^-{3,}$")) {
                    continue;
                }
                // 只收**同名**的连续 H1；标题不同或遇到正文就停
                if (t.matches("^#\\s+\\S.*$")
                        && (dupH1.isEmpty() || t.equals(out[dupH1.get(0)].trim()))) {
                    dupH1.add(k);
                    continue;
                }
                break;
            }
            String result;
            if (dupH1.size() > 1) {
                // 留第一个，其余整串删掉
                Set<Integer> drop = new LinkedHashSet<>(dupH1.subList(1, dupH1.size()));
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < out.length; k++) {
                    if (!drop.contains(k)) {
                        sb.append(out[k]).append("\n");
                    }
                }
                result = sb.toString().trim();
            } else {
                result = kept.toString().trim();
            }
            while (result.endsWith("---")) {
                result = result.substring(0, result.length() - 3).trim();
            }
            // 剥掉元信息后容易留下连续空行，压成一个
            return result.replaceAll("\n{3,}", "\n\n");
        }
}
