# 2026-05-19

## ExpHub 项目启动
- 项目名：ExpHub（经验阁）
- 路径：/Users/shiyue/shiyue_dev/exphub
- 技术栈：Java 17 + Spring Boot 3.2.0 + MyBatis-Plus 3.5.3 + MySQL + Thymeleaf + Spring AI MCP
- 端口：3099
- 管理员账号：admin / changeme
- 默认AI助手：openclaw-zhuque / API Key: exphub-zhuque-api-key-2024

## 数据库初始化（MySQL）
1. 先在 MySQL 创建数据库：`CREATE DATABASE exphub DEFAULT CHARSET utf8mb4;`
2. 执行 sql/init.sql 初始化数据

## 部署服务器
- api-server（124.71.132.197）已配置 SSH 免密登录
- 部署路径建议：/opt/exphub/

## 构建部署命令
1. 本地 Maven 打包：`mvn clean package`
2. 上传到服务器：`scp target/exphub-1.0.0.jar root@124.71.132.197:/opt/exphub/`
3. 启动：`java -jar exphub-1.0.0.jar`
4. 或用 start.sh（需先 chmod +x start.sh）

## 访问地址
- 后台管理：http://124.71.132.197:3099/exphub/login
- API 基础地址：http://124.71.132.197:3099/exphub/api
- MCP SSE 端点：http://124.71.132.197:3099/exphub/mcp/sse

## API Key 鉴权
- 所有 /api/* 请求需在 Header 中添加 `X-API-Key: your-api-key`
- Auth 接口（/api/auth/*）无需 Key
- MCP 接口（/mcp/*）无需 Key（开放给 AI 助手连接）

## MCP Server 配置（SSE 模式）
ExpHub 内置 MCP Server，所有 AI 助手都可以连接服务器的 MCP 获取经验。

### CodeBuddy 配置
在 CodeBuddy MCP 设置中添加 SSE 类型的连接：

```json
{
  "name": "exphub",
  "type": "sse",
  "url": "http://你的服务器:3099/exphub/mcp/sse"
}
```

### MCP 可用工具
| 工具 | 说明 |
|------|------|
| `search_experience` | 搜索相关经验 |
| `get_experience_detail` | 获取经验详情 |
| `get_template` | 获取创建经验模板 |
| `create_experience` | 创建新经验 |

## 项目结构
- src/main/java/com/exphub/
  - controller/ — RestController（后台页面 + API）
  - service/ — 业务逻辑
  - mapper/ — MyBatis-Plus Mapper
  - entity/ — 数据实体
  - interceptor/ — API Key 拦截器
  - config/ — WebMvc 配置
  - mcp/ — MCP Server 配置
  - common/ — 统一响应类 R.java
- src/main/resources/
  - templates/ — Thymeleaf 页面
  - application.yml — 配置文件
- sql/init.sql — 数据库初始化脚本
