#!/usr/bin/env bash
# =========================================================
# sync-from-main.sh
#
# 把长生命周期 feature 分支 ff-merge 到 main(本地 + 远端)。
# 分支清单写死在下面 BRANCHES 数组,与 .git/info/branch-hygiene 对齐。
#
# 用法:
#   bash scripts/local/sync-from-main.sh                 # 同步所有清单分支
#   bash scripts/local/sync-from-main.sh feature/be-bugfixed  # 只同步指定分支
#   bash scripts/local/sync-from-main.sh --dry-run       # 只看会做什么,不动手
#
# 规则:
#   - 仅 fast-forward;有分歧立刻跳过并 warn,不强 rebase(避免改远端历史)
#   - 同步前 fetch --prune;同步后回到原始分支
#   - 任一分支失败不阻断后续(继续同步剩下的)
# =========================================================

set -uo pipefail

# 清单:跟 main 始终保持 ff 关系的 long-lived 分支
BRANCHES=(
  feature/be-bugfixed
  feature/docker-deploy
)

DRY_RUN=0
TARGETS=()

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    -h|--help)
      sed -n '2,18p' "$0"; exit 0 ;;
    *) TARGETS+=("$arg") ;;
  esac
done

[[ ${#TARGETS[@]} -eq 0 ]] && TARGETS=("${BRANCHES[@]}")

ORIG_BRANCH=$(git rev-parse --abbrev-ref HEAD)
RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; RESET=$'\e[0m'

run() {
  if [[ $DRY_RUN -eq 1 ]]; then echo "  [dry-run] $*"; return 0; fi
  "$@"
}

echo "fetching origin (prune)..."
run git fetch origin --prune --quiet

failed=()
synced=()
diverged=()

for b in "${TARGETS[@]}"; do
  echo
  echo "── $b ──"

  if ! git show-ref --verify --quiet "refs/remotes/origin/$b"; then
    echo "  ${YELLOW}skip${RESET}: origin/$b 不存在"
    continue
  fi

  ahead_behind=$(git rev-list --left-right --count "origin/$b...origin/main" 2>/dev/null)
  ahead=$(echo "$ahead_behind" | awk '{print $1}')
  behind=$(echo "$ahead_behind" | awk '{print $2}')

  if [[ "$behind" == "0" ]]; then
    echo "  ${GREEN}up-to-date${RESET}: 已包含 main 全部 commit"
    continue
  fi

  if [[ "$ahead" != "0" ]]; then
    echo "  ${YELLOW}diverged${RESET}: $b 独有 $ahead 个 commit, main 独有 $behind 个 — 跳过(需人工 rebase / merge)"
    diverged+=("$b")
    continue
  fi

  echo "  $b 落后 main $behind 个 commit, ff-merge + push..."
  if ! run git checkout "$b" --quiet; then failed+=("$b"); continue; fi
  if ! run git merge --ff-only origin/main --quiet; then
    echo "  ${RED}ff-merge 失败${RESET}"; failed+=("$b"); continue
  fi
  if ! run git push origin "$b" --quiet; then
    echo "  ${RED}push 失败${RESET}"; failed+=("$b"); continue
  fi
  synced+=("$b")
  echo "  ${GREEN}synced${RESET}"
done

echo
echo "回到原分支 $ORIG_BRANCH"
run git checkout "$ORIG_BRANCH" --quiet 2>/dev/null || true

echo
echo "==== 结果 ===="
echo "  synced  : ${#synced[@]}${synced[@]:+ — ${synced[*]}}"
echo "  diverged: ${#diverged[@]}${diverged[@]:+ — ${diverged[*]}}"
echo "  failed  : ${#failed[@]}${failed[@]:+ — ${failed[*]}}"
[[ ${#failed[@]} -eq 0 ]] || exit 1
