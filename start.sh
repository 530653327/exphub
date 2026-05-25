#!/bin/bash
# ExpHub 启动脚本（Linux/macOS）
# 用法: ./start.sh [dev|prod]
#
# 首次使用：
#   1. cp .env.example .env
#   2. 编辑 .env 填入数据库密码等配置
#   3. source .env && ./start.sh

MODE=${1:-dev}

# 获取脚本所在目录
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_FILE="$APP_DIR/target/exphub-1.0.0.jar"

# 自动加载 .env 文件（如果存在）
if [ -f "$APP_DIR/.env" ]; then
    set -a
    source "$APP_DIR/.env"
    set +a
    echo "📄 已加载 $APP_DIR/.env"
elif [ -z "$EXPHUB_DB_PASSWORD" ]; then
    echo "⚠️  未找到 .env 文件且未设置 EXPHUB_DB_PASSWORD"
    echo "   请执行: cp .env.example .env 并填入数据库密码"
    echo ""
fi

if [ ! -f "$JAR_FILE" ]; then
    echo "❌ 找不到 JAR 文件，请先运行 mvn clean package"
    exit 1
fi

if [ "$MODE" = "prod" ]; then
    echo "🚀 以生产模式启动 ExpHub..."
    nohup java -jar "$JAR_FILE" --spring.profiles.active=prod > app.log 2>&1 &
    echo "✅ ExpHub 已启动 (PID: $!)"
    echo "   访问地址: http://localhost:3099"
    echo "   日志文件: $APP_DIR/app.log"
else
    echo "🔧 以开发模式启动 ExpHub..."
    java -jar "$JAR_FILE"
fi