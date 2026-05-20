#!/bin/bash
# ExpHub 部署脚本
# 用法: ./deploy.sh 或 bash deploy.sh

set -e

echo "========================================"
echo "  ExpHub 自动化部署脚本"
echo "========================================"

cd /usr/local/exphub

# 1. 拉取最新代码
echo "[1/5] 拉取最新代码..."
git fetch --all
git reset --hard origin/master
echo "  ✓ 代码更新完成"

# 2. 设置 Java 环境
echo "[2/5] 配置 Java 17 环境..."
export JAVA_HOME=/usr/local/java17
export PATH=$JAVA_HOME/bin:$PATH
echo "  ✓ JAVA_HOME=$JAVA_HOME"

# 3. 编译打包
echo "[3/5] 编译打包 (跳过测试)..."
mvn clean package -DskipTests -q
echo "  ✓ 编译完成"

# 4. 杀掉旧进程
echo "[4/5] 停止旧进程..."
PID=$(netstat -tlnp 2>/dev/null | grep ':3099' | awk '{print $7}' | cut -d'/' -f1)
if [ -n "$PID" ]; then
    echo "  发现旧进程 PID: $PID"
    kill -9 $PID 2>/dev/null || true
    sleep 2
    echo "  ✓ 旧进程已停止"
else
    echo "  ✓ 无旧进程"
fi

# 5. 启动应用
echo "[5/5] 启动应用..."
nohup $JAVA_HOME/bin/java -jar target/exphub-1.0.0.jar > app.log 2>&1 &
echo "  ✓ 启动命令已执行"

echo ""
echo "========================================"
echo "  部署完成!"
echo "========================================"
echo "  查看日志: tail -f /usr/local/exphub/app.log"
echo "  访问地址: https://cloudim.club/exphub"
echo ""
