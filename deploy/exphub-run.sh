#!/bin/bash
# ExpHub 部署入口（仓库外，不在 git reset 范围内）。设计说明见仓库内 deploy.sh 的 [1/5] 段。
# 只做两件事：git 同步 → 带 EXPHUB_SKIP_PULL=1 调仓库内逻辑。不写任何业务逻辑（防止自举）。
set -euo pipefail
REPO="${EXPHUB_REPO:-/usr/local/exphub}"
LOG="${EXPHUB_DEPLOY_LOG:-$REPO/deploy.log}"
# 只兜「还没交给内层就失败」（fetch/reset 断网）：exec 会丢掉这个 trap，
# 内层 deploy.sh 自己用 EXIT trap 写带退出码的结果行，不会重复记两行。
ENTRY_EXIT() {
    rc=$?
    printf '%s entry=git-sync deploy=n/a commit=%s exit=%d step=1/5 拉取(git 同步失败)\n' \
        "$(date '+%F %T')" "$(git -C "$REPO" log -1 --format=%h 2>/dev/null || echo unknown)" "$rc" >> "$LOG" 2>/dev/null || true
}
trap ENTRY_EXIT EXIT
cd "$REPO"
git fetch --all --quiet
git reset --hard origin/master --quiet
echo "[入口] 已同步到 $(git log -1 --format='%h %s')"
export EXPHUB_SKIP_PULL=1
exec bash "$REPO/deploy.sh" "$@"
