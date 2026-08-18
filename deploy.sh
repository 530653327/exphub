#!/bin/bash
# ExpHub 部署脚本
# 用法: ./deploy.sh
#
# 首次部署前需要在 /etc/exphub/.env 中配置数据库密码等敏感信息：
#   mkdir -p /etc/exphub
#   cat > /etc/exphub/.env << 'EOF'
#   EXPHUB_DB_URL=jdbc:mysql://localhost:3306/exphub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true
#   EXPHUB_DB_USERNAME=root
#   EXPHUB_DB_PASSWORD=your_password
#   EOF

set -e

echo "========================================"
echo "  ExpHub 部署"
echo "========================================"

cd /usr/local/exphub

# 0. 加载环境变量（服务器安全位置，不随代码提交）
if [ -f /etc/exphub/.env ]; then
    set -a
    source /etc/exphub/.env
    set +a
    echo "[0/4] 已加载环境变量 /etc/exphub/.env"
else
    echo "⚠️  警告: 未找到 /etc/exphub/.env，将使用 application.yml 中的默认值"
fi

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
# 杀掉所有旧实例：应用为双端口（3098 MCP SSE + 3099 Web），同一 JVM 占用
# 按 jar 名 pkill 最稳妥，避免漏杀只监听 3098 的旧进程
pkill -f 'exphub-1.0.0.jar' 2>/dev/null || true
# 兜底：按端口确认释放（双端口都清）
for PORT in 3098 3099; do
    PID=$(netstat -tlnp 2>/dev/null | grep ":$PORT " | awk '{print $7}' | cut -d'/' -f1 | head -1)
    if [ -n "$PID" ]; then
        kill -9 $PID 2>/dev/null || true
    fi
done
sleep 2
nohup $JAVA_HOME/bin/java -jar target/exphub-1.0.0.jar > app.log 2>&1 &

echo "[4/4] 部署完成 → https://cloudim.club/exphub"
