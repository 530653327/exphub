#!/bin/bash
# ExpHub 部署脚本
# 自重入探针（2026-10-29）：改本脚本的那次部署必须立刻用新版本，不能等下一次
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
#
# ⚠️ 2026-10-29 结构修正（阁主验收意见）：根因是这个脚本**自己就在要被 reset 的仓库里**。
# 自重入（下面那段）是把补丁做对了，但补丁不该是常态机制 ——
# 现在推荐用仓库外入口：/opt/deploy/exphub-run.sh，它先 git 同步、再带着 EXPHUB_SKIP_PULL=1
# 调本脚本。入口文件不在仓库里，永远不会被 reset 替换，也就没有 inode 问题。
# 直接 `bash deploy.sh` 仍然可用（兼容旧习惯），此时靠自重入兜底。
if [ "${EXPHUB_SKIP_PULL:-0}" = "1" ]; then
    echo "[1/5] 跳过拉取（外部入口已 git 同步：$(git log -1 --format='%h %s')）"
else
    echo "[1/5] 拉取最新代码..."
    git fetch --all
    SELF_SUM_BEFORE=$(md5sum "$0" | cut -d' ' -f1)
    git reset --hard origin/master
    SELF_SUM_AFTER=$(md5sum "$0" | cut -d' ' -f1)

    # ⚠️ 自重入（2026-10-29 实测踩到）：bash 是按需读取脚本文件的，[1/5] 的 reset 会把
    # deploy.sh 换成新 inode，但**当前 bash 仍从旧文件继续读** —— 于是「改了这个脚本的
    # 那次部署」跑的是旧逻辑，本次改动要到下一次部署才生效。
    # 2026-10-28 加版本表那次就是这样：第一次部署输出「7 个迁移全跑」，但 schema_migrations
    # 根本没建（旧脚本没有建表逻辑），跑第二次才生效，很容易误判成「改动没写对」。
    if [ "$SELF_SUM_BEFORE" != "$SELF_SUM_AFTER" ] && [ -z "$EXPHUB_SELF_REEXEC" ]; then
        echo "  ↻ deploy.sh 本次有更新 → 用新版本重新执行（否则仍跑旧逻辑）"
        export EXPHUB_SELF_REEXEC=1
        exec bash "$0" "$@"
    fi
fi

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

# 2.7 安装/更新仓库外部署入口（2026-10-29）
# 入口不能放在仓库里，否则 git reset --hard 会把它换成新 inode，本次部署仍读旧逻辑。
# 这里每次从仓库模板覆盖安装一次，保证运维侧用的永远是仓库里的版本，不用手工同步。
#
# ⚠️ 自举风险（阁主指出）：入口不在版本控制里，靠本脚本分发 —— 入口坏了只能靠本脚本修，
#    而本脚本又要靠入口调到。所以入口必须**锁死在 20 行以内、只做 git 同步 + 调内层**，
#    并且每次都把它的 md5 记进 deploy.log，被改动至少看得见。
if [ -f deploy/exphub-run.sh ]; then
    mkdir -p /opt/deploy
    if cmp -s deploy/exphub-run.sh /opt/deploy/exphub-run.sh; then
        echo "  ✅ 仓库外入口已是仓库版本（/opt/deploy/exphub-run.sh）"
    else
        install -m 755 deploy/exphub-run.sh /opt/deploy/exphub-run.sh
        echo "  ✅ 仓库外入口已安装/更新（/opt/deploy/exphub-run.sh）"
    fi
    ENTRY_MD5=$(md5sum /opt/deploy/exphub-run.sh | cut -d' ' -f1)
    INNER_MD5=$(md5sum deploy.sh | cut -d' ' -f1)
    ENTRY_LINES=$(wc -l < /opt/deploy/exphub-run.sh | tr -d ' ')
    echo "     入口 md5=${ENTRY_MD5}（${ENTRY_LINES} 行）｜内层 deploy.sh md5=${INNER_MD5}"
    if [ "$ENTRY_LINES" -gt 20 ]; then
        echo "  ⚠️  入口脚本 ${ENTRY_LINES} 行，超过约定上限 20 行 —— 它不在版本控制内，越复杂越难救"
    fi
    printf '%s entry=%s(%s行) deploy=%s commit=%s\n' \
        "$(date '+%F %T')" "$ENTRY_MD5" "$ENTRY_LINES" "$INNER_MD5" "$(git log -1 --format=%h)" \
        >> /usr/local/exphub/deploy.log
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
