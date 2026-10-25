# AGENTS.md — ExpHub 开发约定

给接手这个仓库的 AI 与协作者看。每条都对应一次真实事故或一次返工，不是风格偏好。

## 0. 项目一句话

ExpHub 是「AI 经验知识库 + 项目空间」，通过 MCP 协议给多个 AI 助手共用。
双端口同 JVM：**3098 = MCP（WebFlux，禁止改回 Servlet）**，3099 = Web/Thymeleaf。
线上 `https://cloudim.club/exphub`，部署目录 `/usr/local/exphub`。

---

## 1. 红线

| 红线 | 具体要求 |
|---|---|
| **模型无删除权** | MCP 侧只能 `mark_for_deletion`（必须写原因）。真删只能在后台由人点。不要给 AI 加任何删除/覆盖主表的工具 |
| **删除必须级联** | 删 `docs` 必须同事务删 `doc_versions`。只删主表会留下历史**正文全文**孤儿：界面看不到、`get_experience_history` 也查不到，形成「以为删干净了，其实全文还在」 |
| **敏感信息不入库** | 创建/更新经验的 `content` 不允许出现密码、API Key、Token。提醒调用方，而不是替它脱敏（脱敏会破坏命令可执行性） |
| **正文不可整篇覆盖** | 改一小段用 `patch_experience`，追加用 `append_experience`。`update_experience` 整篇写回只在拿到完整正文（`export_document`）时用 |

---

## 2. 副作用不得拖垮主业务（#627，通用规范）

一个纯副作用的日志字段曾把搜索打成 500：`call_logs.detail` 是 `varchar(500)`，
实际内容到了 480 字，在 `STRICT_TRANS_TABLES` 下 insert 报错，而 insert 在搜索主流程里、无 try-catch、同事务。

**四条一起做，少一条都不算修完：**

1. **列宽留够**：记录类/日志类字段用 `TEXT`，不要按「正常情况」估算长度
2. **按列宽截断**：写入前截断到列宽（`CallLogWriter` 的做法，截断处标注原长度）
3. **异常不上抛**：写日志用 try-catch 吞掉并降级 log，副作用失败不能影响主流程
4. **独立事务**：`REQUIRES_NEW`，主业务回滚不能带走日志、日志失败也不能拖垮主业务

**同类型历史事故**：微信转账 `fail_reason` 超长抛异常（#367）、`order_no` 为 null 报 500（#213）。
共同点：给附带信息留的长度按正常输入估算，没考虑极端值。

---

## 3. 工具变更必须同步三处（M11 已复发三次）

工具清单是运行时从 `tools/list` 拉的（自动同源），**但下面三处是人手写的，不会自动更新**：

1. `README.md` 工具表（数量 + 条目）
2. `README.en.md` 工具表（**最容易漏，它现在落后中文版**）
3. 下游项目的工具名/能力说明（如忆阁 `ChatService`、`localDefinitions()`）

**规则：新增、改名、删除任何 MCP 工具后，先 grep 一遍这三处。**

比"漏一条说明"更严重的是**给了错误的路由**：说明写「append 优先用本地实现」，
模型就放心走「读全文 + 整篇写回」，而读取端有长度上限，写回就静默丢掉未读到的那截。

---

## 4. 版本快照的语义（A3 之后）

| 场景 | `doc_versions.content` | `change_type` |
|---|---|---|
| 改了正文（update 带 content / append / patch / import） | 旧版全文 | `CONTENT` |
| 只改元数据（tags / summary / title / 归属 / 打分） | **NULL** | `META` |
| 只打标记（`set_flags`） | **不写版本表** | — |

`NULL` 的含义是「本次没动正文」，**不是数据丢失**。读取端遇到 NULL 必须明确告诉调用方
「该版仅元数据变更，正文请看更早的正文版本」，不能静默返回空。

清理历史快照前必须先 `mysqldump` 备份。判断冗余：正文与更早某版完全相同（MD5 相同）。

---

## 5. 署名约定

同一个 API Key 只能绑一个 `assistant_name`，而一个 Key 常被多个助手共用。
所以**署名由调用方自报**（`authorName` 参数），Key 上的名字只当兜底。

代号按「你在改哪个仓库」认，不按会话认：

| 仓库 | 代号 | git 署名 |
|---|---|---|
| `exphub`（本仓库） | 烛龙 | `烛龙 <zhulong@cloudim.club>` |
| `memhaven`（忆阁） | 白泽 | `白泽 <baize@cloudim.club>` |

---

## 6. 记录约定

**所有执行记录、进度、决策只写 #631 一个文档**（`append_experience` / `patch_experience`）。
不要 `create_experience` 建新条目，不要 `append_project_progress`。原因：分散在多处必然漂移，
#101 已经因为「同一件事记在两个地方」漂过三次。

---

## 7. 改动流程

```
改代码 → mvn clean test（必须 clean，增量编译会掩盖 Lombok 问题）
      → git commit（署名烛龙）→ git push origin master（一次推 gitee + github 两个远端）
      → 部署交给人执行：cd /usr/local/exphub && bash deploy.sh
```

**几个坑，别再踩：**

- **`deploy.sh` 会 `git reset --hard` 自己**。改了这个脚本后第一次部署执行的仍是旧脚本内容（bash 已读进内存），新增步骤会被跳过。**改 deploy.sh 后必须跑两遍。**
- **DDL 必须早于重启**：新代码 select 新列，列不存在就 `Unknown column` 起不来。`deploy.sh` 的 2.5 步已按这个顺序。
- **编译目标 Java 17**，本机若用 JDK 21 需显式 `JAVA_HOME`；服务器用 `/usr/local/java17`。
- **部署会让现有 SSE 会话失效**（`sse_session_not_found`），客户端必须重连才能看到新工具。这不是 bug。
- **`mysql -N -B` 会把换行转义成 `\n`**，导出正文必须加 `--raw`。
- **`mysql -e` 不加 `--default-character-set=utf8mb4`** 会把中文显示成 `?`（是显示问题，用 `HEX()` 可验真实字节）。

---

## 8. 测试

纯单元测试为主（JUnit 5 + Mockito），不起 Spring 上下文。

优先给「读代码看不出来」的行为写测试：

- 写路径**不写**什么（`set_flags` 不能写版本表）
- 删除**多删**了什么（级联清 `doc_versions`）
- 边界拒绝逻辑（`patch` 的 find 必须唯一命中、flags 白名单过滤）

`mvn compile` 不 clean 会因旧 class 掩盖问题，**只认 `mvn clean test`**。

---

## 9. 记忆整理（D1）的边界

2.0 的核心是「整理记忆」，但整理必须是**可撤销的标记动作**，不是批量改写。

**三条硬规则：**

1. **只标记、不合并。** `mark_superseded` 只写 `superseded_by` 一个指针；`resolve_candidate(duplicate)` 只给两条打 `duplicate` 标记。**任何工具都不改正文**。合并错了不可逆，比不整理更糟。
2. **只记录、不自动清理。** `review_candidates` 记录「待判断」，`PENDING` 状态不触发任何数据变更。真正的删除只有一条路径：`mark_for_deletion` → 用户在后台确认。
3. **判过的结论要记住。** 候选对被判为 `DISTINCT` 后，同一对不再重新入队 —— 否则每周都问同一个问题，人很快就无视整个队列。

**取代链为什么只存一个方向：**
`superseded_by` 只存在「被取代」的那条上，反向关系用 `WHERE superseded_by = ?` 反查。存两列必然出现「A 说被 B 取代、B 的列表里没有 A」这种双写漂移。

**防环**：`chainReaches` 沿取代链最多走 `MAX_SUPERSEDE_HOPS`(20) 步，走回起点就拒绝。A→B→A 会让「最新版是谁」永远算不出来。

**候选队列为什么必须落库：**
如果候选只出现在某次工具返回的文本里，下一次调用就没了，人也没机会判断，于是同一对重复被算出来、重复被忽略 —— 整理永远推进不下去。

**增量入口不用定时任务：**
`list_recent_memories(since=...)` 由 AI 在会话里主动调，而不是加 `@Scheduled`。定时任务失败是静默的（参考 `systemd` 挂两个多月没人发现）；让「谁在跑谁顺手整理」，失败当场可见。
