#!/bin/bash
# ExpHub 部署入口（仓库外，不在 git reset 范围内）。设计说明见仓库内 deploy.sh 的 [1/5] 段。
# 只做两件事：git 同步 → 带 EXPHUB_SKIP_PULL=1 调仓库内逻辑。不写任何业务逻辑（防止自举）。
set -euo pipefail
REPO="${EXPHUB_REPO:-/usr/local/exphub}"
cd "$REPO"
git fetch --all --quiet
git reset --hard origin/master --quiet
echo "[入口] 已同步到 $(git log -1 --format='%h %s')"
export EXPHUB_SKIP_PULL=1
exec bash "$REPO/deploy.sh" "$@"
