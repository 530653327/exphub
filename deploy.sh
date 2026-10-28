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
    echo "[0/5] 已加载环境变量 /etc/exphub/.env"
else
    echo "⚠️  警告: 未找到 /etc/exphub/.env，将使用 application.yml 中的默认值"
fi

# 1. 拉取最新代码
echo "[1/5] 拉取最新代码..."
git fetch --all
git reset --hard origin/master

# 2. 编译打包
echo "[2/5] 编译打包..."
export JAVA_HOME=/usr/local/java17
export PATH=$JAVA_HOME/bin:$PATH
mvn clean package -DskipTests -q

# 2.5 数据库迁移（2026-10-25 新增；2026-10-28 改为「按版本表执行」）
# 为什么放在重启前：新代码会 select 新增列，DDL 没跑就会报 Unknown column，服务起不来
#
# ⚠️ 2026-10-28 白虎：原来是「每次部署 for 循环全量重跑所有 migration」。
#    幂等脚本重跑本身不算错，但**只要有人写一句无条件 DDL（比如 DROP INDEX），
#    它就会在每次部署时把线上刚修好的东西再静默破坏一次** —— 20261026c 已经这么坑过一次
#    （唯一索引被删，写入幂等的第二道防线无声失效）。
#    机制不改，下一个写 migration 的人会原样再踩一遍，所以在这里加版本表：
#    同一文件 + 同一 md5 只执行一次；文件内容改了才重跑。
echo "[2.5/5] 执行数据库迁移..."
DB_NAME="${EXPHUB_DB_NAME:-exphub}"
if command -v mysql >/dev/null 2>&1; then
    MYSQL="mysql -u$EXPHUB_DB_USERNAME -p$EXPHUB_DB_PASSWORD $DB_NAME --default-character-set=utf8mb4"
    $MYSQL -e "CREATE TABLE IF NOT EXISTS schema_migrations (
        filename   VARCHAR(200) NOT NULL COMMENT '文件名(basename)',
        checksum   CHAR(32)     NOT NULL COMMENT '文件md5，变了才重跑',
        applied_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (filename)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已执行的migration，避免每次部署全量重跑'" >/dev/null 2>&1
    if [ $? -ne 0 ]; then
        echo "  ⚠️  建 schema_migrations 失败（权限不足？），退化为全量重跑"
    fi
    for f in sql/migrations/migration_2026*.sql; do
        [ -f "$f" ] || continue
        base=$(basename "$f")
        sum=$(md5sum "$f" | cut -c1-32)
        applied=$($MYSQL -N -e "SELECT checksum FROM schema_migrations WHERE filename='$base'" 2>/dev/null | tr -d '[:space:]')
        if [ "$applied" = "$sum" ]; then
            continue
        fi
        if $MYSQL < "$f" >/tmp/exphub_mig.log 2>&1; then
            if [ -n "$applied" ]; then
                $MYSQL -e "UPDATE schema_migrations SET checksum='$sum', applied_at=NOW() WHERE filename='$base'" >/dev/null 2>&1
                echo "  ✅ $base（内容有改动，已重跑）"
            else
                $MYSQL -e "INSERT INTO schema_migrations(filename,checksum) VALUES('$base','$sum')" >/dev/null 2>&1
                echo "  ✅ $base"
            fi
        else
            echo "  ⚠️  $base 执行有报错（若是幂等脚本可忽略重复列提示）"
            tail -5 /tmp/exphub_mig.log
        fi
    done
    rm -f /tmp/exphub_mig.log
else
    echo "  ⚠️  未找到 mysql 客户端，跳过迁移（新代码若依赖新列会启动失败）"
fi

# 2.6 同步 systemd 单元（仓库为准，2026-10-25 从 nohup 切到 systemd 托管）
echo "[2.6/5] 检查 systemd 单元..."
if [ -f deploy/exphub.service ]; then
    if ! cmp -s deploy/exphub.service /etc/systemd/system/exphub.service; then
        cp deploy/exphub.service /etc/systemd/system/exphub.service
        systemctl daemon-reload
        echo "  ✅ systemd 单元已更新（deploy/exphub.service → /etc/systemd/system/）"
    else
        echo "  ✅ systemd 单元已是仓库版本"
    fi
fi

# 3. 重启应用（systemd 托管）
# 2026-10-25 变更：原本是 nohup 起进程，systemd 单元形同摆设——
#   `systemctl status exphub` 常年 failed、systemctl stop 杀不掉真进程、进程崩了没人拉。
# 现在统一走 systemctl：deploy.sh 不再自己起 java，只负责「清场 + 交给 systemd」。
echo "[3/5] 重启应用（systemd）..."

# 清历史 failed：start-limit 计数不清掉，restart 会直接返回失败且不起进程
systemctl reset-failed exphub 2>/dev/null || true

# 兜底清场：历史 nohup 残留进程会占着 3098/3099，
# systemd 拉起来就端口冲突 → 撞 StartLimitBurst → 又进 failed（2026-08-18 就是这么挂的）
pkill -f 'exphub-1.0.0.jar' 2>/dev/null || true
for PORT in 3098 3099; do
    PID=$(netstat -tlnp 2>/dev/null | grep ":$PORT " | awk '{print $7}' | cut -d'/' -f1 | head -1)
    if [ -n "$PID" ]; then
        kill -9 $PID 2>/dev/null || true
    fi
done
sleep 2

if ! systemctl restart exphub; then
    echo "❌ systemctl restart exphub 失败，最近日志："
    journalctl -u exphub -n 30 --no-pager 2>/dev/null || true
    exit 1
fi
echo "  ✅ systemctl restart 已提交，主进程 PID=$(systemctl show -p MainPID exphub | cut -d= -f2)"

# 4. 启动自检（2026-10-24 新增）
# 此前部署完就宣告成功，应用起不来也无人知晓。这里轮询 /health：
#   - 最多等 90 秒（Spring Boot + 连接池初始化约 6-10 秒）
#   - /health 返回 200 才算成功；503 表示 DB 不可用
echo "[4/5] 启动自检..."
HEALTH_URL="http://127.0.0.1:3099/exphub/health"
OK=0
for i in $(seq 1 30); do
    sleep 3
    CODE=$(curl -s --noproxy '*' -o /tmp/exphub_health.json -m 5 -w "%{http_code}" "$HEALTH_URL" 2>/dev/null || echo "000")
    if [ "$CODE" = "200" ]; then
        OK=1
        break
    fi
    # 进程已死就不用等了，直接报错
    if ! pgrep -f 'exphub-1.0.0.jar' > /dev/null 2>&1; then
        echo "❌ 应用进程已退出，部署失败"
        break
    fi
done

if [ "$OK" = "1" ]; then
    echo "✅ 健康检查通过: $(cat /tmp/exphub_health.json 2>/dev/null)"
    # MCP 端口探测（不校验业务，只看能否建连）
    MCP_CODE=$(curl -s --noproxy '*' -o /dev/null -m 5 -w "%{http_code}" http://127.0.0.1:3098/mcp/sse 2>/dev/null || echo "000")
    if [ "$MCP_CODE" = "200" ] || [ "$MCP_CODE" = "401" ]; then
        echo "✅ MCP(3098) 可建连 (http=$MCP_CODE)"
    else
        echo "⚠️  MCP(3098) 异常 (http=$MCP_CODE)，请检查 app.log"
    fi
    echo "[5/5] 部署完成 → https://cloudim.club/exphub"
else
    echo "=========================================="
    echo "❌ 部署失败：健康检查未通过（最后 http=$CODE）"
    echo "--- /health 响应 ---"; cat /tmp/exphub_health.json 2>/dev/null
    echo ""
    echo "--- app.log 末尾 30 行 ---"; tail -30 app.log
    rm -f /tmp/exphub_health.json
    exit 1
fi
rm -f /tmp/exphub_health.json
