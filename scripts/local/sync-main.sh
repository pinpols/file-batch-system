#!/usr/bin/env bash
# =========================================================
# ⚠️ 已废弃(2026-06-13):feature/docker-deploy 部署分支已取消,部署内容合入 main。
#    main → 常驻分支(citus)的同步改用 scripts/local/sync-from-main.sh。本脚本保留仅作历史参考。
# sync-main.sh - 把 origin/main 的更新合并到本地 feature/docker-deploy
# 说明：
# 1) 仅做 git 操作,不触发部署。部署走 scripts/docker/deploy.ps1 或 up-apps.sh。
# 2) 默认要求当前在 feature/docker-deploy 分支 + 工作树干净。
# 3) 首次同步若两边无共同祖先(本地是快照仓库),会自动加 --allow-unrelated-histories,
#    会引入 origin/main 全部内容,生成一次性大 merge commit。后续同步行为正常。
# 4) 合并成功不自动 push;部署验证通过后再手动 git push origin feature/docker-deploy。
# Usage:
#   ./scripts/local/sync-main.sh           # 交互式,会 prompt 确认
#   ./scripts/local/sync-main.sh --yes     # 跳过 prompt(脚本/CI 用)
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

DEPLOY_BRANCH="${DEPLOY_BRANCH:-feature/docker-deploy}"
MAIN_BRANCH="${MAIN_BRANCH:-main}"
REMOTE="${REMOTE:-origin}"

YES=false
[[ "${1:-}" == "--yes" || "${1:-}" == "-y" ]] && YES=true

# --- 1. 前置校验 ---
current=$(git rev-parse --abbrev-ref HEAD)
if [[ "$current" != "$DEPLOY_BRANCH" ]]; then
  echo "ERR: 当前分支是 '$current',需在 '$DEPLOY_BRANCH' 上才能同步。" >&2
  echo "     先 git checkout $DEPLOY_BRANCH" >&2
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "ERR: 工作树有未提交改动,先 commit 或 stash。当前状态:" >&2
  git status --short >&2
  exit 1
fi

if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
  echo "ERR: remote '$REMOTE' 不存在。先 git remote add $REMOTE <URL>。" >&2
  exit 1
fi

# --- 2. fetch + 校验 origin/main ---
echo "==> fetch $REMOTE..."
git fetch "$REMOTE" --prune

if ! git rev-parse "$REMOTE/$MAIN_BRANCH" >/dev/null 2>&1; then
  echo "ERR: $REMOTE/$MAIN_BRANCH 不存在,远端没有 main 分支。" >&2
  exit 1
fi

# --- 3. 显示本次合并范围 ---
echo ""
echo "==> 准备合并 $REMOTE/$MAIN_BRANCH → $DEPLOY_BRANCH"
echo "    当前 HEAD     : $(git rev-parse --short HEAD) $(git log -1 --format=%s)"
echo "    合并目标 HEAD : $(git rev-parse --short "$REMOTE/$MAIN_BRANCH") $(git log -1 --format=%s "$REMOTE/$MAIN_BRANCH")"

# --- 4. 共同祖先 / unrelated-histories 检测 ---
unrelated_arg=""
base=$(git merge-base HEAD "$REMOTE/$MAIN_BRANCH" 2>/dev/null || true)
if [[ -z "$base" ]]; then
  echo ""
  echo "⚠️  无共同祖先 (unrelated histories)"
  echo "    本地 $DEPLOY_BRANCH 与 $REMOTE/$MAIN_BRANCH 历史不相关。"
  echo "    将加 --allow-unrelated-histories,合并会一次性引入 origin/main 全部内容,"
  echo "    生成 1 个大 merge commit。后续 sync 行为正常。"
  unrelated_arg="--allow-unrelated-histories"
else
  echo "    共同祖先     : $(git rev-parse --short "$base")"
fi

# diff 摘要
stat_line=$(git diff "HEAD..$REMOTE/$MAIN_BRANCH" --stat | tail -n 1)
echo ""
echo "==> diff 规模 (HEAD..$REMOTE/$MAIN_BRANCH): $stat_line"

# --- 5. 确认 ---
if ! $YES; then
  echo ""
  read -rp "继续合并? [y/N] " ans
  if [[ "$ans" != "y" && "$ans" != "Y" ]]; then
    echo "取消。"
    exit 0
  fi
fi

# --- 6. 合并 ---
echo ""
echo "==> merging..."
msg="sync: merge $REMOTE/$MAIN_BRANCH into $DEPLOY_BRANCH ($(date +%Y-%m-%d))"
if ! git merge $unrelated_arg --no-edit -m "$msg" "$REMOTE/$MAIN_BRANCH"; then
  echo ""
  echo "ERR: merge 有冲突。看 git status,手动解决后:" >&2
  echo "       git add <files>" >&2
  echo "       git commit  # (msg 会用上面那条)" >&2
  exit 1
fi

# --- 7. 完成提示 ---
echo ""
echo "✔ 合并完成。HEAD: $(git rev-parse --short HEAD)"
echo ""
echo "  下一步:"
echo "    1) 跑构建/启动验证:"
echo "         pwsh ./scripts/ps1/docker/deploy.ps1"
echo "       或 ./scripts/docker/up-apps.sh"
echo "    2) 容器全部 healthy 之后再 push:"
echo "         git push $REMOTE $DEPLOY_BRANCH"
echo "    3) 验证失败 → git reset --hard HEAD~1 撤回本次 sync,排查后重做。"
