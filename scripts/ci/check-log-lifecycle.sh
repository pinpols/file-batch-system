#!/usr/bin/env bash
# 验证本地日志归档、命名防碰撞和清理候选边界。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../lib/logging.sh
source "$ROOT/scripts/lib/logging.sh"

TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() {
  echo "日志生命周期契约失败: $*" >&2
  exit 1
}

log_init_layout "$TEST_ROOT"
mkdir -p "$TEST_ROOT/logs/current/app"

printf 'first\n' >"$TEST_ROOT/logs/current/app/console.log"
BATCH_LOG_ARCHIVE_SUFFIX='../Unsafe Suffix' \
  log_archive_active_log_file "$TEST_ROOT" app console >/dev/null

first_archive="$(find "$TEST_ROOT/logs/archive/app" -type f -name 'console.*-unsafe-suffix.log' -print -quit)"
[[ -n "$first_archive" ]] || fail '自定义归档后缀未被安全净化'
[[ "$(cat "$first_archive")" == 'first' ]] || fail '首个归档内容不完整'

# 固定时间戳，确保同一秒内连续归档不会覆盖前一份文件。
log_stamp() { printf '20260913-120000'; }
printf 'second\n' >"$TEST_ROOT/logs/current/app/trigger.log"
BATCH_LOG_ARCHIVE_SUFFIX='collision' \
  log_archive_active_log_file "$TEST_ROOT" app trigger >/dev/null
printf 'third\n' >"$TEST_ROOT/logs/current/app/trigger.log"
BATCH_LOG_ARCHIVE_SUFFIX='collision' \
  log_archive_active_log_file "$TEST_ROOT" app trigger >/dev/null

[[ -f "$TEST_ROOT/logs/archive/app/trigger.20260913-120000-collision.log" ]] \
  || fail '缺少首个固定时间戳归档'
[[ -f "$TEST_ROOT/logs/archive/app/trigger.20260913-120000-collision.1.log" ]] \
  || fail '同秒归档未生成防碰撞序号'
[[ "$(cat "$TEST_ROOT/logs/archive/app/trigger.20260913-120000-collision.log")" == 'second' ]] \
  || fail '同秒归档覆盖了首份内容'

printf 'active\n' >"$TEST_ROOT/logs/current/app/orchestrator.log"
touch -t 202001010000 "$TEST_ROOT/logs/current/app/orchestrator.log"
touch -t 202001010000 "$TEST_ROOT/logs/archive/app/trigger.20260913-120000-collision.log"

candidates=()
while IFS= read -r -d '' file; do
  candidates+=("$file")
done < <(log_find_archived_log_files "$TEST_ROOT" app 7)

[[ "${#candidates[@]}" -eq 1 ]] || fail '清理候选必须只包含过期归档日志'
[[ "${candidates[0]}" == "$TEST_ROOT/logs/archive/app/trigger.20260913-120000-collision.log" ]] \
  || fail '活跃日志进入了清理候选'

echo '日志生命周期契约通过'
