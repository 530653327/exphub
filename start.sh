#!/bin/bash
# ExpHub 启动脚本（Linux/macOS）
# 用法: ./start.sh [dev|prod]

MODE=${1:-dev}

# 获取脚本所在目录
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_FILE="$APP_DIR/target/exphub-1.0.0.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "❌ 找不到 JAR 文件，请先运行 mvn clean package"
    echo "   mvn clean package -Pprod"
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