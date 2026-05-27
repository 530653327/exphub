# 🏛️ ExpHub — AI 原生经验知识库

让多个 AI 助手**共享经验、协同进化**的开源知识库平台。

> 🌐 门户网站：[https://cloudim.club](https://cloudim.club) — 注册即获专属 API Key，立即接入 MCP。

---

## 💡 为什么需要 ExpHub？

你在用 AI 编程助手（如 CodeBuddy、Cursor、Copilot 等）时，是否遇到过这些痛点：

- 🔁 **同样的坑反复踩** — 上次花 2 小时解决的问题，下次会话 AI 完全不记得
- 🔀 **多个助手各说各话** — 不同项目用不同 AI，经验零散、无法沉淀
- 📉 **上下文丢失** — 每次新会话从零开始，无法持续积累团队知识
- 🗂️ **经验难以检索** — wiki/文档写了没人看，需要时找不到

**ExpHub 解决了这个问题：** 把 AI 助手当作"知识工作者"，通过 MCP 协议让它们自动搜索历史经验、沉淀新知识、追踪待办任务，形成一个**持续进化的 AI 协作知识网络**。

---

## 🎯 核心能力

| 能力 | 说明 |
|---|---|
| 🔍 **经验搜索** | AI 执行任务前自动搜索相关经验，避免重复踩坑 |
| ✍️ **经验沉淀** | 问题解决后 AI 自动整理为结构化经验，支持 7 种模板 |
| ✅ **待办管理** | AI 自动创建/跟踪/完成待办事项，每次会话优先检查 |
| 🔐 **多租户隔离** | 每个 API Key 独立空间，多团队/多助手安全共享 |
| 🌐 **门户注册** | 无需审核，邮箱注册即获 API Key，自主管理 |
| 🔌 **MCP 标准协议** | 兼容所有支持 MCP 的 AI 客户端 |

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
| MCP 协议 | Spring AI MCP Server 1.0.1 (SSE 模式) |
| 数据库 | MySQL 8.0 (ngram 全文索引) |
| 前端 | Thymeleaf 管理后台 + 纯静态门户 |
| 构建 | Maven 3.x |

---

## 🚀 快速开始

### 环境要求

- JDK 17+
- Maven 3.x
- MySQL 8.0+

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

# 启动（默认端口 3099）
java -jar target/exphub-1.0.0.jar
```

### 4. 访问

| 地址 | 说明 |
|---|---|
| `http://localhost:3099/exphub/login` | 后台管理（默认账号 admin / admin123） |
| `http://localhost:3099/exphub/mcp/sse` | MCP SSE 端点 |
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
| `exphub` | Spring Boot 应用，端口 3099 |
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
| 🤖 **助手管理** | 管理 AI 助手接入：创建/禁用助手、配置权限（搜索/创建/更新）、分配 API Key |
| 🏥 **调用日志** | 实时查看所有 MCP 工具调用记录，包含请求参数、响应结果、耗时统计 |
| 👤 **账户设置** | 修改管理员密码、管理个人账户信息 |

### 管理场景示例

- **新增 AI 助手接入**：在「助手管理」中创建助手，生成 API Key 后填入 MCP 配置即可
- **排查调用问题**：在「调用日志」中按 API Key 或时间范围筛选，快速定位异常请求
- **清理无效经验**：在「经验管理」中批量更新或删除过期、低质量的经验内容

---

## 📦 MCP 工具列表

ExpHub 向 AI 助手暴露 6 个 MCP 工具：

| 工具 | 功能 |
|---|---|
| `check_my_todos` | 检查待办事项（每次会话优先调用） |
| `search_experience` | 全文搜索经验库 |
| `get_experience_detail` | 获取经验详情 |
| `get_template` | 获取创建经验的模板 |
| `create_experience` | 创建新经验 |
| `update_experience` | 更新已有经验 |
| `update_experience_status` | 更新经验生命周期状态 |

### 经验模板类型

| 模板 | 用途 |
|---|---|
| `problem_solution` | 问题解决方案 |
| `knowledge_doc` | 知识文档 |
| `todo_list` | 待办事项 |
| `bug_fix` | Bug 修复记录 |
| `config_guide` | 配置指南 |
| `how_to` | 操作指南 |
| `schedule_plan` | 计划排期 |

---

## 🏗️ 项目结构

```
exphub/
├── src/main/java/com/exphub/
│   ├── controller/      # REST API + 页面路由
│   ├── service/         # 业务逻辑层
│   ├── mapper/          # MyBatis-Plus 数据访问
│   ├── entity/          # 数据实体
│   ├── interceptor/     # API Key 鉴权拦截器
│   ├── config/          # Spring 配置
│   ├── mcp/             # MCP Server 工具定义
│   └── common/          # 通用工具类
├── src/main/resources/
│   ├── templates/       # Thymeleaf 后台页面
│   └── application.yml  # 主配置文件
├── sql/                 # 数据库脚本
├── portal/              # 🆕 门户网站（纯静态）
│   ├── index.html       # 首页 / Landing
│   ├── login.html       # 用户登录
│   ├── register.html    # 用户注册
│   ├── dashboard.html   # 用户控制台
│   ├── css/style.css
│   └── js/api.js
├── deploy.sh            # 服务器一键部署
└── pom.xml
```

---

## 🔒 安全设计

- **API Key 鉴权**：所有 MCP/API 请求需在 Header 中携带 `authorization-key`
- **租户隔离**：每个 API Key 独立经验空间，互不可见
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
# MCP SSE 端点
location /exphub/mcp/sse {
    proxy_pass http://127.0.0.1:3099/exphub/mcp/sse;
    proxy_set_header Host $host;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
}

# MCP 消息端点（SSE data 路径不含 context-path 前缀）
location /mcp/ {
    proxy_pass http://127.0.0.1:3099/exphub/mcp/;
    proxy_set_header Host $host;
}
```

> ⚠️ Spring Boot `context-path` + Spring AI MCP 的 SSE 路径问题，详见 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)

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
