#!/usr/bin/env bash
# scripts/ci/check-env-prod-sync.sh
#
# V6-OPS-1: 校验 .env.prod 与 .env.example 关键 key 同步,
# 防止新增配置项漏 prod 部署导致运行时坑(典型例子:KAFKA_TOPICS 缺新 topic 导致 trigger
# 异步推送失败)。
#
# 工作原理:
#   - 提取 .env.example 中所有 KEY=VALUE 形式的 key 集合(忽略注释 / 空行 / VALUE)
#   - 提取 .env.prod 同上
#   - 对比两个 key 集合,输出差异
#   - 默认:.env.example 有但 .env.prod 缺 → fail(prod 漏配)
#                .env.prod 有但 .env.example 缺 → 仅 WARN(可能是 prod-specific)
#
# 用法:
#   ./scripts/ci/check-env-prod-sync.sh
#   BATCH_CI_SKIP_ENV_SYNC_GATE=1 ./scripts/ci/...   # escape hatch

set -euo pipefail

if [[ "${BATCH_CI_SKIP_ENV_SYNC_GATE:-0}" == "1" ]]; then
  echo "[env-sync] BATCH_CI_SKIP_ENV_SYNC_GATE=1 — 跳过(仅 dev 本地 debug 应使用)"
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

EXAMPLE_FILE=".env.example"
PROD_FILE=".env.prod"

if [[ ! -f "$EXAMPLE_FILE" ]]; then
  echo "[env-sync] FAIL: $EXAMPLE_FILE 不存在"
  exit 1
fi
if [[ ! -f "$PROD_FILE" ]]; then
  echo "[env-sync] FAIL: $PROD_FILE 不存在(生产部署样本必须维护)"
  exit 1
fi

# 提取 KEY 集合(忽略空行和 # 注释行,忽略不含 = 的行)
extract_keys() {
  grep -E '^[A-Z_][A-Z0-9_]*=' "$1" | sed 's/=.*//' | sort -u
}

EX_KEYS=$(extract_keys "$EXAMPLE_FILE")
PROD_KEYS=$(extract_keys "$PROD_FILE")

# example 有 but prod 缺 = 漏配
MISSING_IN_PROD=$(comm -23 <(echo "$EX_KEYS") <(echo "$PROD_KEYS"))
# prod 有 but example 缺 = 仅 WARN
MISSING_IN_EXAMPLE=$(comm -13 <(echo "$EX_KEYS") <(echo "$PROD_KEYS"))

# 白名单:三类 key 不要求 prod 显式声明
#   1. dev/local 凭据占位:prod 走 secret manager 注入,.env.prod 不写
#   2. 可选 feature 开关:prod 默认关闭即可,不显式列
#   3. 别名/等价 key:prod 用另一组 key 表达同语义
DEV_ONLY_WHITELIST=(
  # ── 凭据/项目名(prod 走 secret manager) ──
  "COMPOSE_PROJECT_NAME"
  "POSTGRES_PASSWORD"
  "POSTGRES_REPLICATION_PASSWORD"
  "POSTGRES_REPLICATION_USER"
  "BUSINESS_DB_NAME"
  "BATCH_CONSOLE_REPLICA_PASSWORD"
  "OPENAI_API_KEY"
  # ── 可选 feature 开关(prod 默认关) ──
  "BATCH_CONSOLE_READ_REPLICA_ENABLED"
  "BATCH_CONSOLE_REPLICA_URL"
  "BATCH_CONSOLE_REPLICA_USER"
  "POSTGRES_REPLICA_PORT"
  # ── 别名/等价 key(prod 用 REDIS_HOST/PORT + TZ 表达) ──
  "BATCH_REDIS_HOST"
  "BATCH_REDIS_PORT"
  "BATCH_TIMEZONE_DEFAULT_ZONE"
)

is_dev_only() {
  local key="$1"
  for w in "${DEV_ONLY_WHITELIST[@]}"; do
    [[ "$key" == "$w" ]] && return 0
  done
  return 1
}

# 过滤白名单
FILTERED_MISSING_IN_PROD=()
while IFS= read -r key; do
  [[ -z "$key" ]] && continue
  if ! is_dev_only "$key"; then
    FILTERED_MISSING_IN_PROD+=("$key")
  fi
done <<< "$MISSING_IN_PROD"

if [[ ${#FILTERED_MISSING_IN_PROD[@]} -gt 0 ]]; then
  echo
  echo "[env-sync] FAIL: $EXAMPLE_FILE 有但 $PROD_FILE 缺 ${#FILTERED_MISSING_IN_PROD[@]} 个 key:"
  echo
  for k in "${FILTERED_MISSING_IN_PROD[@]}"; do
    echo "  - $k"
  done
  echo
  echo "[env-sync] 处理建议:"
  echo "  1. 把缺失 key 补到 $PROD_FILE(用 prod-safe 默认值)"
  echo "  2. 如果该 key 是 dev/local 专用,加到本脚本 DEV_ONLY_WHITELIST"
  echo "  3. 如果是新引入的 key,补 docs/runbook/feature-switches.md 说明"
  exit 1
fi

if [[ -n "$MISSING_IN_EXAMPLE" ]]; then
  echo
  echo "[env-sync] WARN: $PROD_FILE 有但 $EXAMPLE_FILE 缺(可能是 prod-specific,确认一下):"
  echo "$MISSING_IN_EXAMPLE" | sed 's/^/  - /'
  echo
fi

echo "[env-sync] PASS: $EXAMPLE_FILE 和 $PROD_FILE 关键 key 已同步($(echo "$EX_KEYS" | wc -l | tr -d ' ') keys checked)"
