#!/bin/bash
# ExpHub 部署入口（仓库外，不在 git 同步覆盖范围内）。设计说明见仓库内 deploy.sh 的 [1/5] 段。
# 只做两件事：git 同步 → 带 EXPHUB_SKIP_PULL=1 调仓库内逻辑。不写任何业务逻辑（防止自举）。
set -euo pipefail
REPO="${EXPHUB_REPO:-/usr/local/exphub}"
LOG="${EXPHUB_DEPLOY_LOG:-$REPO/deploy.log}"
# 只兜「还没交给内层就失败」；exec 会丢掉 trap，内层自己写，不会重复记两行
ENTRY_EXIT() { rc=$?; printf '%s entry=git-sync deploy=n/a commit=%s exit=%d step=1/5 拉取(git 同步失败)\n' \
    "$(date '+%F %T')" "$(git log -1 --format=%h 2>/dev/null || echo unknown)" "$rc" >> "$LOG" 2>/dev/null || true; }
trap ENTRY_EXIT EXIT
cd "$REPO"
git fetch --all --quiet
# 2026-11-09 起不用 git reset --hard（会静默丢弃本地改动）：脏工作区明拒，干净才快进
[ -z "$(git status --porcelain -uno)" ] || { echo "❌ 部署中止：工作区有未提交改动，拒绝覆盖（详见 git status）" >&2; exit 1; }
git merge --ff-only origin/master --quiet
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/master)" ] || { echo "❌ 部署中止：本地 HEAD 与 origin/master 不一致（本地有提交或分叉），拒绝部署" >&2; exit 1; }
echo "[入口] 已同步到 $(git log -1 --format='%h %s')"
export EXPHUB_SKIP_PULL=1
exec bash "$REPO/deploy.sh" "$@"
