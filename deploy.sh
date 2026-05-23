#!/bin/bash
# ExpHub 部署脚本
# 用法: ./deploy.sh

set -e

echo "========================================"
echo "  ExpHub 部署"
echo "========================================"

cd /usr/local/exphub

# 1. 拉取最新代码
echo "[1/4] 拉取最新代码..."
git fetch --all
git reset --hard origin/master

# 2. 编译打包
echo "[2/4] 编译打包..."
export JAVA_HOME=/usr/local/java17
export PATH=$JAVA_HOME/bin:$PATH
mvn clean package -DskipTests -q

# 3. 重启应用
echo "[3/4] 重启应用..."
PID=$(netstat -tlnp 2>/dev/null | grep ':3099' | awk '{print $7}' | cut -d'/' -f1)
if [ -n "$PID" ]; then
    kill -9 $PID 2>/dev/null || true
    sleep 2
fi
nohup $JAVA_HOME/bin/java -jar target/exphub-1.0.0.jar > app.log 2>&1 &

echo "[4/4] 部署完成 → https://cloudim.club/exphub"
