# 🏛️ ExpHub — AI 原生经验知识库 + 项目空间

让多个 AI 助手**共享经验、协同进化**的开源平台。

> 🌐 门户网站：[https://cloudim.club](https://cloudim.club) — 注册即获专属 API Key，立即接入 MCP。

---

## 💡 为什么需要 ExpHub？

你在用 AI 编程助手（如 CodeBuddy、Cursor、Copilot 等）时，是否遇到过这些痛点：

- 🔁 **同样的坑反复踩** — 上次花 2 小时解决的问题，下次会话 AI 完全不记得
- 🔀 **多个助手各说各话** — 不同项目用不同 AI，经验零散、无法沉淀
- 📉 **上下文丢失** — 每次新会话从零开始，无法持续积累团队知识
- 🗂️ **经验难以检索** — wiki/文档写了没人看，需要时找不到
- 🧭 **AI 不了解你的项目** — 每次都要重新解释架构、规范、禁忌，还总漏掉关键约束

**ExpHub 解决了这个问题：** 把 AI 助手当作"知识工作者"，通过 MCP 协议让它们自动搜索历史经验、沉淀新知识、追踪待办任务；并可为每个项目建立**项目空间**，让 AI 一进来就拿到项目手册与上次的进度 —— 形成**持续进化的 AI 协作知识网络**。

---

## 🎯 核心能力

| 能力 | 说明 |
|---|---|
| 🔍 **经验搜索** | AI 执行任务前自动搜索相关经验，避免重复踩坑 |
| ✍️ **经验沉淀** | 问题解决后 AI 自动整理为结构化经验，支持 8 种模板 |
| ✅ **待办管理** | AI 自动创建/跟踪/完成待办事项，每次会话优先检查 |
| 🗂️ **项目空间** | 为每个项目建独立空间：项目手册、进度交接、专属经验 |
| 📖 **AGENTS.md 项目手册** | 手册按章节分片存储（推荐 15 章），带 `ALWAYS` / `ON_DEMAND` 加载策略，AI 按需读取不浪费上下文 |
| 🔄 **进度交接** | 每次会话收尾写一条进度记录，下一个 AI 读最近几条即可接续，不丢上下文 |
| 🪄 **工作流提示词生成** | 一句话生成完整的开发任务提示词（自动带上项目的约束与测试命令） |
| 🔐 **多租户隔离** | 每个 API Key 独立知识空间；项目数据默认隔离，不出现在全局搜索 |
| 🌐 **门户注册** | 无需审核，邮箱注册即获 API Key，自主管理 |
| 🔌 **MCP 标准协议** | 兼容所有支持 MCP 的 AI 客户端 |

---

## 🧱 双层结构

| 层 | 放什么 | 可见性 |
|---|---|---|
| **全局经验库** | 通用经验、待办、模板、排期 | 全局搜索可见（按 API Key 隔离） |
| **项目空间** | 项目 AGENTS.md 手册、进度/交接记录、项目专属经验 | **默认隔离**，不出现在全局搜索；可 `promote_to_global` 提升为通用经验 |

一个 API Key 下可建多个项目，用项目名 / 别名 / 编码检索。

---

## 🌐 在线门户

**立即体验：** [https://cloudim.club](https://cloudim.club)

1. 使用邮箱注册，秒级获取专属 API Key
2. 将 API Key 填入 AI 助手的 MCP 配置
3. AI 自动搜索经验、记录知识、追踪待办

### CodeBuddy MCP 配置示例

```json
{
  "mcpServers": {
    "exphub": {
      "type": "sse",
      "url": "https://cloudim.club/exphub/mcp/sse",
      "headers": {
        "authorization-key": "在此输入你的API Key"
      },
      "timeout": 120000,
      "disabledTools": [],
      "disabled": false
    }
  }
}
```

> 💡 其他 MCP 客户端（Cursor、Claude Desktop 等）配置方式类似，只需修改 `url` 和 `headers`。

---

## 🛠️ 技术栈

| 层级 | 技术 |
|---|---|
| 运行环境 | Java 17 |
| 核心框架 | Spring Boot 3.2.0 |
| ORM | MyBatis-Plus 3.5.7 |
| MCP 协议 | Spring AI MCP Server 1.0.1 |
| 数据库 | MySQL 5.7+（当前部署环境 5.7.44；检索基于 ngram 全文索引 + 三级降级） |
| 前端 | Thymeleaf 管理后台 + 纯静态门户 |
| 构建 | Maven 3.x |

### 双端口架构（同一 JVM）

| 端口 | 用途 | 技术栈 |
|---|---|---|
| **3099** | 后台 Web（Thymeleaf 页面 + REST API + 门户） | Spring MVC / **Tomcat** |
| **3098** | MCP 服务（SSE 长连接） | **WebFlux / Netty**（非阻塞） |

> ⚠️ **MCP 必须使用 WebFlux（独立 3098），不要改回 Servlet**
>
> Servlet 版下**每个 SSE 长连接会占用一个 Tomcat 工作线程且长期不释放**，客户端重连 / 连接堆积会耗尽线程池（默认 200 个），导致整个应用假死（进程活着、端口在听，但所有请求超时）。WebFlux 的非阻塞模型不存在该问题。详见 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)。

---

## 🚀 快速开始

### 环境要求

- JDK 17+
- Maven 3.x
- MySQL 5.7+
  > ⚠️ 低版本 MySQL 不支持 `LOCK=NONE` / `ALGORITHM=INPLACE` 类 DDL 语法，改表请在低峰期执行并先备份。

### 1. 初始化数据库

```bash
# 创建数据库
mysql -u root -p -e "CREATE DATABASE exphub DEFAULT CHARSET utf8mb4;"

# 导入初始化脚本
mysql -u root -p exphub < sql/init.sql
```

### 2. 配置环境变量

```bash
export EXPHUB_DB_URL=jdbc:mysql://localhost:3306/exphub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
export EXPHUB_DB_USERNAME=root
export EXPHUB_DB_PASSWORD=your_password
```

### 3. 启动服务

```bash
# 编译
mvn clean package -DskipTests

# 启动（会同时监听 3099 Web 与 3098 MCP）
java -jar target/exphub-1.0.0.jar
```

### 4. 访问

| 地址 | 说明 |
|---|---|
| `http://localhost:3099/exphub/login` | 后台管理（默认账号 admin / admin123） |
| `http://localhost:3098/mcp/sse` | MCP SSE 端点（直连） |
| `https://<你的域名>/exphub/mcp/sse` | MCP SSE 端点（经 Nginx，推荐给 AI 客户端） |
| `http://localhost:3099/exphub/portal/api/register` | 用户注册 API |

> ⚠️ **首次登录后请立即修改默认密码**，在后台右上角账号菜单中操作。

---

## 🐳 Docker 一键部署（推荐）

**只需 3 步，30 秒启动：**

```bash
# 1. 配置数据库密码
cp .env.example .env
# 编辑 .env 中的 EXPHUB_DB_PASSWORD

# 2. 启动（自动建库建表、拉取镜像、编译运行）
docker compose up -d

# 3. 查看日志
docker compose logs -f exphub
```

**包含的服务：**
| 服务 | 说明 |
|---|---|
| `exphub` | Spring Boot 应用，端口 3099（Web）+ 3098（MCP） |
| `mysql` | MySQL 8.0，端口 3307（避免冲突） |

**常用命令：**
```bash
docker compose up -d         # 启动
docker compose down          # 停止并清理
docker compose down -v       # 停止并删除数据卷（重置数据库）
docker compose logs -f exphub  # 实时日志
docker compose restart exphub  # 重启应用
```

---

## 🖥️ 后台管理系统

自行部署 ExpHub 后，可通过后台管理系统进行可视化管理，无需直接操作数据库。

### 登录方式

访问 `http://localhost:3099/exphub/login`，使用默认账号登录：

| 字段 | 值 |
|---|---|
| 用户名 | `admin` |
| 密码 | `admin123` |

> ⚠️ **安全提醒**：首次登录后请立即修改默认密码。

### 功能模块

| 模块 | 功能说明 |
|---|---|
| 📊 **仪表盘** | 平台概览：用户数、经验数、API 调用量等关键指标一览 |
| 📝 **经验管理** | 查看、编辑、删除所有租户的经验内容，支持按模板类型筛选 |
| 🗂️ **项目管理** | 项目空间可视化：新建/编辑/归档项目；**AGENTS.md 分片编辑**（MAIN 索引 + 各章节）；进度记录添加与查看；工作流提示词生成 |
| 🤖 **助手管理** | 管理 AI 助手接入：创建/禁用助手、配置权限（搜索/创建/更新）、分配 API Key |
| 🏥 **调用日志** | 实时查看所有 MCP 工具调用记录，包含请求参数、响应结果、耗时统计 |
| 👤 **账户设置** | 修改管理员密码、管理个人账户信息 |

### 管理场景示例

- **新增 AI 助手接入**：在「助手管理」中创建助手，生成 API Key 后填入 MCP 配置即可
- **给项目建手册**：在「项目管理」新建项目 → 填 MAIN 索引（定位 + 章节目录 + 绝对红线）→ 逐章写入，AI 之后每次会话都能读到
- **排查调用问题**：在「调用日志」中按 API Key 或时间范围筛选，快速定位异常请求
- **清理无效经验**：在「经验管理」中批量更新或删除过期、低质量的经验内容

---

## 📦 MCP 工具列表（共 25 个）

### 全局经验库（12 个）

| 工具 | 功能 |
|---|---|
| `check_my_todos` | 检查待办事项（每次会话优先调用） |
| `list_recent_memories` | 按更新时间倒序列出记忆（自己的全局经验 + 自有项目，游标分页） |
| `search_experience` | 搜索经验（支持 `projectName` 限定项目内搜索） |
| `get_experience_detail` | 获取经验详情 |
| `get_experience_history` | 查看经验的历史版本（平台每次更新自动留存快照） |
| `get_template` | 获取创建经验的模板 |
| `create_experience` | 创建新经验 |
| `update_experience` | 更新已有经验 |
| `update_experience_status` | 更新经验生命周期状态（ACTIVE/COMPLETED/BROKEN/DEPRECATED） |
| `export_document` | 导出完整文档（无截断，适合长文档） |
| `import_document` | 导入替换内容（与 export 配对，自动留存版本快照） |
| `report_experience_result` | 反馈经验是否有帮助（成功/失败都建议反馈） |

### 项目空间（13 个）

| 工具 | 功能 |
|---|---|
| `create_project` | 创建项目空间 |
| `list_projects` | 列出当前 API Key 下的项目 |
| `get_project` | 获取项目详情（含默认测试命令与约束） |
| `update_project` | 更新项目信息 |
| `get_project_agents` | 读取项目手册：不传章节返回 MAIN 索引，传 `chapterNo` 返回该章全文 |
| `update_project_agents_main` | 写入/更新 MAIN 索引（定位 + 章节目录 + 绝对红线，≤2000 字） |
| `update_project_agents_chapter` | 写入/更新单个章节（建议 ≤3000 字，按章独立版本） |
| `export_project_agents` | 导出完整 AGENTS.md（MAIN + 各章按序拼接） |
| `append_project_progress` | 追加一条进度/交接记录（一次一条，各自快照） |
| `get_project_progress` | 读取最近的进度记录（默认 3 条，便于接续） |
| `list_project_experiences` | 列出项目专属经验 |
| `promote_to_global` | 把项目专属经验提升为全局经验 |
| `generate_workflow_prompt` | 生成开发任务提示词（模板 + 项目默认变量 + 本次任务变量） |

### 经验模板类型（8 种）

| 模板 | 用途 |
|---|---|
| `problem_solution` | 问题解决方案 |
| `knowledge_doc` | 知识文档 |
| `todo_list` | 待办事项 |
| `bug_fix` | Bug 修复记录 |
| `config_guide` | 配置指南 |
| `how_to` | 操作指南 |
| `schedule_plan` | 计划排期 |
| `goal` | 目标 |

---

## 🏗️ 项目结构

```
exphub/
├── src/main/java/com/exphub/
│   ├── controller/      # REST API + 页面路由（含 ProjectController 项目空间）
│   ├── service/         # 业务逻辑层（DocService / ProjectService 等）
│   ├── mapper/          # MyBatis-Plus 数据访问
│   ├── entity/          # 数据实体（Doc / Project / AiAssistant 等）
│   ├── interceptor/     # API Key 鉴权（ApiKeyInterceptor）
│   ├── config/          # Spring 配置
│   │   ├── WebMvcConfig                    # 拦截器注册
│   │   ├── SecurityConfig                  # 安全策略
│   │   └── McpContextPropagationConfig     # ★ 身份上下文跨线程传播（Reactor onScheduleHook）
│   ├── mcp/             # MCP 相关
│   │   ├── ExpHubTools.java                # 全部 25 个 MCP 工具定义
│   │   ├── McpReactiveServerConfig.java    # 独立 WebFlux(Netty:3098) + API Key 鉴权
│   │   └── McpRequestBodyLoggingFilter.java
│   └── common/          # 通用工具类
├── src/main/resources/
│   ├── templates/       # Thymeleaf 后台页面（含 projects/ 项目空间页面）
│   └── application.yml  # 主配置文件
├── sql/                 # 数据库脚本
├── portal/              # 门户网站（纯静态）
├── deploy.sh            # 服务器一键部署
└── pom.xml
```

---

## 🔒 安全设计

- **API Key 鉴权**：所有 MCP/API 请求需在 Header 中携带 `authorization-key`
- **租户隔离**：每个 API Key 独立经验空间，互不可见
- **项目空间隔离**：项目手册 / 进度 / 专属经验默认隔离，**不会出现在全局搜索结果中**
- **身份上下文跨线程传播**：MCP 工具实际运行在框架内部的 Reactor 线程上，身份通过 `Schedulers.onScheduleHook` 在调度时捕获、执行时恢复 —— 确保多 Key 场景下隔离**始终可靠**（不依赖裸 `ThreadLocal`，避免池化线程复用导致的身份串号）
- **权限分级**：支持搜索/创建/更新权限分别控制
- **密码加密**：Spring Security BCrypt 加密存储
- **敏感信息提醒**：创建经验时提醒 AI 不记录密码、Token 等敏感数据

---

## 🚢 部署到生产环境

```bash
# 1. 服务器上 clone 项目（Gitee 或 GitHub）
git clone https://gitee.com/coolshiyue/exphub.git /usr/local/exphub
# 或
git clone https://github.com/530653327/exphub.git /usr/local/exphub

# 2. 创建环境变量文件（不提交到 git）
mkdir -p /etc/exphub
cat > /etc/exphub/.env << 'EOF'
EXPHUB_DB_URL=jdbc:mysql://localhost:3306/exphub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
EXPHUB_DB_USERNAME=root
EXPHUB_DB_PASSWORD=your_secure_password
EOF

# 3. 部署
./deploy.sh
```

### Nginx 反向代理参考

```nginx
# MCP SSE + 消息端点 → 独立 WebFlux 服务（3098）
# 注意：必须禁用缓冲，否则 SSE 会被缓冲导致 AI 客户端收不到消息
location /exphub/mcp/ {
    proxy_pass http://127.0.0.1:3098/mcp/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
}

# 后台 Web + 门户 → 应用（3099）
location /exphub/ {
    proxy_pass http://127.0.0.1:3099;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
}
```

> ⚠️ **不要把 `/exphub/mcp/` 指向 3099（WebMvc）** —— 那会让 SSE 长连接占满 Tomcat 线程池并导致应用假死。
> 更多排障细节见 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)

---

## 🤝 参与贡献

- GitHub：[https://github.com/530653327/exphub](https://github.com/530653327/exphub)
- Gitee：[https://gitee.com/coolshiyue/exphub](https://gitee.com/coolshiyue/exphub)
- Issue 反馈：[https://gitee.com/coolshiyue/exphub/issues](https://gitee.com/coolshiyue/exphub/issues)
- 联系邮箱：530653327@qq.com
- 开源协议：MIT License

欢迎提交 PR 或创建 Issue 讨论新功能！

---

**ExpHub — 让 AI 不只回答问题，更能积累智慧。**
