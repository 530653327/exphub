# CODEBUDDY.md — ExpHub 项目交接文档

> 新会话启动后请仔细阅读本文档，了解项目全貌、MCP 配置、日常操作和关键踩坑记录。

---

## 一、项目概览

**ExpHub（经验阁）** — AI 助手经验沉淀与复用平台。让不同 AI 助手（如 CodeBuddy）通过 MCP 协议连接共享的经验库，搜索过往解决方案、创建新经验、管理待办事项，实现知识的持续积累。

**在线地址**: https://cloudim.club  
**API 前缀**: `/exphub` (context-path)  
**MCP 端点**: `https://cloudim.club/exphub/mcp/sse`

---

## 二、技术栈

| 层 | 技术 |
|---|---|
| 框架 | Spring Boot 3 + Spring AI (MCP Server) |
| 数据库 | MySQL 8.0 (FULLTEXT 索引 + ngram 分词) |

| 模板 | Thymeleaf |
| 部署 | Docker Compose / 传统 deploy.sh + Nginx |
| 包管理 | Maven (pom.xml) |

---

## 三、MCP SSE 配置 (关键踩坑！)

### 3.1 当前配置

`~/.codebuddy/mcp.json`:
```json
{
  "mcpServers": {
    "exphub": {
      "type": "sse",
      "url": "https://cloudim.club/exphub/mcp/sse",
      "headers": {
        "authorization-key": "exp-af77fa8366ca4c4297af24bfb1e02951"
      },
      "disabledTools": [],
      "disabled": false
    }
  }
}
```

### 3.2 🐛 已知问题：SSE 连接报 `Invalid URL protocol`

**根因**: Spring AI SSE 返回的 `message` endpoint **不包含** context-path 前缀 `/exphub`。  
- SSE 端点返回的 message url: `/mcp/message?sessionId=xxx`（无 `/exphub` 前缀）  
- 客户端直接请求 `/mcp/message` 会 404

**修复方案（服务器端）**: Nginx 新增 `/mcp/` location 做路径改写：

```nginx
location /mcp/ {
    proxy_pass http://127.0.0.1:3099/exphub/mcp/;  # 注意尾部 / 完成路径替换
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

> **配置文件位置**: `/etc/nginx/conf.d/cloudim.club.conf` (服务器上)

**验证命令**:
```bash
# 测试 SSE 端点
curl -s -N --max-time 5 -H "authorization-key: exp-af77fa8366ca4c4297af24bfb1e02951" "https://cloudim.club/exphub/mcp/sse"

# 测试 message 端点转发
curl -s -o /dev/null -w "HTTP %{http_code}" -X POST "https://cloudim.club/mcp/message?sessionId=test" -H "authorization-key: exp-af77fa8366ca4c4297af24bfb1e02951" -H "content-type: application/json" -d '{}'
```

### 3.3 MCP 工具列表

| 工具名 | 功能 | Spring Bean |
|---|---|---|
| `search_experience` | 搜索经验库（全文搜索） | `ExpHubTools` |
| `get_experience_detail` | 获取经验详细内容 | `ExpHubTools` |
| `create_experience` | 创建新经验（沉淀知识） | `ExpHubTools` |
| `update_experience` | 更新已有经验 | `ExpHubTools` |
| `update_experience_status` | 更新经验状态 | `ExpHubTools` |
| `get_template` | 获取经验模板 | `ExpHubTools` |
| `check_my_todos` | 检查待办事项 | `ExpHubTools` |

**MCP Server 配置类**: `src/main/java/com/exphub/mcp/McpServerConfig.java`

---

## 四、经验生命周期 (核心工作流)

```
search_experience → 未找到 → get_template → create_experience → 完成
       ↓ 找到
  get_experience_detail → 参考复用
       
check_my_todos → 有待办 → get_experience_detail → 完成任务 → update_experience_status(COMPLETED)
```

**关键规则**:
- `search_experience` 默认只查 `ACTIVE` 状态的经验
- 搜不到时必须调用 `create_experience` 沉淀新知识
- `check_my_todos` 是每次会话开始时优先调用
- 完成待办后用 `update_experience_status` 标记为 `COMPLETED`

---

## 五、日常操作

### 5.1 改代码后部署

```bash
cd /Users/shiyue/shiyue_dev/exphub
git add -A && git commit -m "描述变更" && git push origin master
ssh root@cloudim.club "bash /usr/local/exphub/deploy.sh"
```

### 5.2 部署流程 (deploy.sh)

1. 加载 `/etc/exphub/.env` 环境变量（数据库密码等敏感信息）
2. `git fetch --all && git reset --hard origin/master`
3. `mvn clean package -DskipTests -q`（JAVA_HOME=/usr/local/java17）
4. `kill` 旧进程（端口 3099），`nohup java -jar` 新版本

### 5.3 Docker Compose 部署

```bash
cp .env.example .env    # 编辑设置数据库密码

```

### 5.4 门户网站部署

```bash
cd /Users/shiyue/shiyue_dev/exphub-portal
git add -A && git commit -m "描述变更" && git push origin master
ssh root@cloudim.club "bash /var/www/exphub-portal/deploy.sh"
```

---

## 六、项目目录结构

```
exphub/
├── deploy.sh                  # 服务器部署脚本
├── docker-compose.yml         # Docker 一键部署
├── Dockerfile                 # 应用镜像
├── docker-entrypoint.sh       # 容器入口
├── pom.xml                    # Maven 依赖
├── sql/                       # 数据库初始化脚本
├── src/main/java/com/exphub/
│   ├── ExpHubApplication.java       # 启动类
│   ├── mcp/
│   │   ├── McpServerConfig.java     # MCP Server 配置
│   │   └── ExpHubTools.java         # MCP 工具实现（6个@Tool方法）
│   ├── controller/                  # Web 控制器
│   │   └── InfoController.java      # /api/info 健康检查
│   ├── interceptor/
│   │   └── ApiKeyInterceptor.java   # API Key 认证拦截器
│   ├── service/                     # 业务服务层
│   ├── entity/                      # 实体类 (Doc, DocTemplate, AiAssistant)
│   └── config/                      # 配置类
└── src/main/resources/
    ├── application.yml              # 主配置
    └── templates/                   # Thymeleaf 模板
```

---

## 七、关键文件速查

| 文件 | 作用 |
|---|---|
| `McpServerConfig.java` | MCP SSE Server 配置，注册 ExpHubTools 为 MCP 工具 |
| `ExpHubTools.java` | 6 个 `@Tool` 方法，MCP 核心逻辑 |
| `ApiKeyInterceptor.java` | 通过 `InheritableThreadLocal` 存储当前调用者身份（保证 MCP 异步线程安全） |
| `deploy.sh` | 服务器部署，JAVA_HOME=/usr/local/java17，端口 3099 |

| `CODEBUDDY.md` | 本文件，新会话交接文档 |

---

## 八、MCP 认证与多租户

- 每个 API Key 关联 `AiAssistant` 实体，包含权限位：`canSearch`、`canCreate`、`canUpdate`
- 通过 `InheritableThreadLocal` 传递身份（Spring AI MCP 框架的异步线程需要继承父线程上下文）
- 当前使用的 API Key: `exp-af77fa8366ca4c4297af24bfb1e02951`（你有完整搜索+创建+更新权限）

---

## 九、最近更新记录


2. **门户网站 Docker 教程弹窗**: `exphub-portal/index.html` 中 Docker 卡片可点击，弹出 6 步小白教程
3. **经验沉淀闭环**: `search_experience` 搜不到时提示 AI 自动创建经验
4. **待办提醒**: `check_my_todos` 新增完成提醒

---

## 十、快速检查清单

新会话启动后，请按以下顺序操作：

1. ✅ 阅读本文件
2. ✅ 调用 `check_my_todos` 检查是否有待办任务
3. ✅ 确认 MCP 连接正常（如果报 `Invalid URL protocol`，检查 Nginx `/mcp/` location 配置）
4. ✅ 用 `search_experience` 搜索要处理的问题是否有现成经验
5. ✅ 做好事 → `create_experience` 沉淀成果

---

> 作者: CodeBuddy (上一会话)  
> 最后更新: 2026-05-26
