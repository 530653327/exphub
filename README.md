# 2026-05-18

## ExpHub 项目启动
- 项目名：ExpHub（经验阁）
- 路径：C:\Users\shiyue\.qclaw\workspace\ExpHub\
- 技术栈：Java 1.8 + Spring Boot 2.7.18 + MyBatis-Plus 3.5.3 + MySQL + Thymeleaf
- 端口：3099
- 管理员账号：admin / changeme（BCrypt加密，首次启动后修改）
- 默认AI助手：openclaw-zhuque / API Key: exphub-zhuque-api-key-2024

## 数据库初始化（MySQL）
1. 先在 MySQL 创建数据库：`CREATE DATABASE exphub DEFAULT CHARSET utf8mb4;`
2. 如使用 ngram 分词：`CREATE FULLTEXT INDEX ft_content ON docs(title, content, aliases, summary, tags) WITH PARSER ngram;`
3. 执行 sql/init.sql 初始化数据

## 部署服务器
- api-server（124.71.132.197）已配置 SSH 免密登录
- 部署路径建议：/opt/exphub/

## 构建部署命令
1. 本地 Maven 打包：`cd C:\Users\shiyue\.qclaw\workspace\ExpHub && mvn clean package`
2. 上传到服务器：`scp target/exphub-1.0.0.jar root@124.71.132.197:/opt/exphub/`
3. 启动：`java -jar exphub-1.0.0.jar`
4. 或用 start.sh（需先 chmod +x start.sh）

## 访问地址
- 后台管理：http://124.71.132.197:3099/login
- API 基础地址：http://124.71.132.197:3099/api

## API Key 鉴权
- 所有 /api/* 请求需在 Header 中添加 `X-API-Key: your-api-key`
- Auth 接口（/api/auth/*）无需 Key

## 项目结构
- src/main/java/com/exphub/
  - controller/ — RestController（后台页面 + API）
  - service/ — 业务逻辑
  - mapper/ — MyBatis-Plus Mapper
  - entity/ — 数据实体
  - interceptor/ — API Key 拦截器
  - config/ — WebMvc 配置
  - common/ — 统一响应类 R.java
- src/main/resources/
  - mapper/ — XML Mapper（暂时不需要，MyBatis-Plus够用）
  - templates/ — Thymeleaf 页面
  - application.yml — 配置文件
- sql/init.sql — 数据库初始化脚本