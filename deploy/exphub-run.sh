#!/bin/bash
# ============================================================================
# ExpHub 部署入口（**仓库外**，2026-10-29 新增）
#
# 为什么要有这一层：原来的 deploy.sh 自己就躺在它要 `git reset --hard` 的仓库里。
# 于是「改 deploy.sh 的那次部署」有个必然的坑 —— bash 按需读取脚本，reset 换了文件
# inode，但当前进程仍从旧文件继续读，跑的是旧逻辑。2026-10-28 加版本表那次就是这样：
# 输出「7 个迁移全跑 ✅」，可 schema_migrations 根本没建，跑第二次才生效。
#
# 修法有两层，这一层是根：
#   1) 入口搬出仓库（本文件，装在 /opt/deploy/exphub-run.sh）—— 它不在 reset 范围内，
#      永远读到自己的完整版本；git 同步完再调仓库内的 deploy.sh（新进程新文件，没有 inode 问题）
#   2) 仓库内 deploy.sh 保留 md5 自重入作为兜底 —— 有人图省事直接 `bash deploy.sh` 时仍安全
#
# 用法：
#   /opt/deploy/exphub-run.sh          # 正常部署（先同步代码）
#   EXPHUB_SKIP_PULL=1 ... 由本脚本内部设置，无需手工传
#
# 安装/更新：由仓库内 deploy.sh 每次部署时自动从 deploy/exphub-run.sh 覆盖安装。
# ============================================================================
set -euo pipefail

REPO="${EXPHUB_REPO:-/usr/local/exphub}"

cd "$REPO"

echo "[入口] 仓库外入口 $0 → $REPO"

# 1. 先把代码同步到最新（这一步在仓库内，但**执行者**是本文件，不受 reset 影响）
git fetch --all --quiet
git reset --hard origin/master --quiet
echo "[入口] 已同步到 $(git log -1 --format='%h %s')"

# 2. 带着「已同步」标记调用仓库内逻辑：跳过它自己的拉取与自重入
export EXPHUB_SKIP_PULL=1
exec bash "$REPO/deploy.sh" "$@"
