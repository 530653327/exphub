#!/bin/bash
# ============================================================
# ExpHub Docker 启动脚本

# ============================================================
set -e

echo "========================================"
echo "  ExpHub Docker 启动"
echo "========================================"

# 等待 MySQL 就绪
echo "[1/3] 等待 MySQL 就绪..."
until (echo > /dev/tcp/${EXPHUB_DB_HOST:-mysql}/3306) 2>/dev/null; do
    echo "  等待 MySQL 启动中..."
    sleep 3
done
echo "  MySQL 已就绪 ✓"





    sleep 2
done


# 启动应用
echo "[3/3] 启动 ExpHub (port 3099)..."
exec java -Xmx512m -jar /app/app.jar
