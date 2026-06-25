# ============================================================
# ExpHub Docker 镜像 - 多阶段构建
# ============================================================

# ---- 阶段 1：编译 ----
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
# 先下载依赖（利用 Docker 缓存层）
RUN mvn dependency:go-offline -q || true
COPY src ./src
RUN mvn clean package -DskipTests -q

# ---- 阶段 2：运行 ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# 复制构建产物
COPY --from=builder /build/target/exphub-1.0.0.jar app.jar

# 复制启动脚本
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

EXPOSE 3099 3098

ENTRYPOINT ["/app/docker-entrypoint.sh"]
