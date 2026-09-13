#!/usr/bin/env bash
# =========================================================
# validate-kafka-topics.sh - 校验指定 env 模板的 KAFKA_TOPICS 与 BatchTopics.java 同步
#
# 失败场景:
# 1) BatchTopics.java 新增 active topic 但指定 env 模板没补 → init-kafka-topics 不会建,
#    严格集群里(disable auto-create)首次发布会丢消息
# 2) env 模板列了 BatchTopics.java 不认识的 topic 名 → 配置漂移,白创建占资源
# 3) `.env.example` 是 CI 权威模板；本地可显式传 `.env.local` / `.env.prod` 做同规则复核
# 4) Topic 名违反命名规范 batch.<segment>.<segment>...(纯小写 + 数字 + . + - + _)
#
# 例外白名单(目录元数据,不参与 MQ 实流量,不强求 env 模板同步):
# - OUTBOX_EVENT / WORKER_HEARTBEAT — 仅 ConsoleEventCatalogController 展示用
#
# 本脚本被 .github/workflows/pr-gate.yml + full-ci-gate.yml 调用。
# 失败则 PR 被阻断，要求开发者先同步权威 `.env.example`，再按需刷新本地环境文件。
#
# 注:用 sorted-list diff 而非 bash 4 关联数组,兼容 macOS 默认 bash 3.2 + ubuntu-latest。
# =========================================================
set -euo pipefail

ENV_FILE="${1:-.env.example}"
BATCH_TOPICS_FILE="batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java"

# 目录元数据 topic — 不在运行态 MQ 流量上,不要求 env 模板同步
WHITELIST_RE='^(OUTBOX_EVENT|WORKER_HEARTBEAT)$'

if [[ ! -f "$ENV_FILE" ]]; then
  echo "❌ ERROR: env file not found: $ENV_FILE"
  exit 1
fi
if [[ ! -f "$BATCH_TOPICS_FILE" ]]; then
  echo "❌ ERROR: BatchTopics.java not found at $BATCH_TOPICS_FILE"
  exit 1
fi

extract_topics() {
  local file="$1"
  grep -oE '^KAFKA_TOPICS=.*' "$file" | sed 's/^KAFKA_TOPICS=//' || true
}

TOPICS_VAR="$(extract_topics "$ENV_FILE")"
if [[ -z "$TOPICS_VAR" ]]; then
  echo "❌ ERROR: KAFKA_TOPICS not found in $ENV_FILE"
  exit 1
fi

# 解析 KAFKA_TOPICS → sorted unique list
configured_topics_sorted="$(echo "$TOPICS_VAR" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$' | sort -u)"
topic_count=$(echo "$configured_topics_sorted" | wc -l | tr -d ' ')

# ── 校验 1: topic 名格式 ─────────────────────────────────────────────────
# 允许: batch.task.dispatch.import / batch.trigger.launch.v1 等
TOPIC_RE='^batch\.[a-z0-9_-]+(\.[a-z0-9_-]+)*$'
while IFS= read -r topic; do
  [[ -z "$topic" ]] && continue
  if ! [[ "$topic" =~ $TOPIC_RE ]]; then
    echo "❌ ERROR: invalid topic name format: $topic"
    echo "   expected: batch.<segment>(.<segment>)+ where segment = [a-z0-9_-]+"
    exit 1
  fi
done <<< "$configured_topics_sorted"
echo "✅ $topic_count topics in $ENV_FILE pass naming check"

# ── 校验 2: BatchTopics.java active constants 与 env 模板双向 diff ──────
# 抽 (CONSTANT_NAME, "literal-value") 排除白名单 → 只取 value 排序去重
code_topics_sorted="$(
  grep -oE 'public static final String [A-Z0-9_]+ = "[a-z0-9._-]+"' "$BATCH_TOPICS_FILE" \
    | sed -E 's/public static final String ([A-Z0-9_]+) = "([a-z0-9._-]+)"/\1\t\2/' \
    | awk -F'\t' -v wl="$WHITELIST_RE" '$1 !~ wl { print $2 }' \
    | sort -u
)"

# diff
missing_in_env="$(comm -23 <(echo "$code_topics_sorted") <(echo "$configured_topics_sorted"))"
missing_in_code="$(comm -13 <(echo "$code_topics_sorted") <(echo "$configured_topics_sorted"))"

errors=0
if [[ -n "$missing_in_env" ]]; then
  echo "❌ ERROR: BatchTopics.java active constants missing from $ENV_FILE KAFKA_TOPICS:"
  echo "$missing_in_env" | sed 's/^/   - /'
  echo "   action: add to .env.example，并按需同步本地环境文件"
  errors=$((errors + 1))
fi
if [[ -n "$missing_in_code" ]]; then
  echo "❌ ERROR: $ENV_FILE lists topic(s) not in BatchTopics.java (config drift):"
  echo "$missing_in_code" | sed 's/^/   - /'
  echo "   action: 删除该 topic 或在 BatchTopics.java 加常量"
  errors=$((errors + 1))
fi

if [[ $errors -gt 0 ]]; then
  echo
  echo "💥 BatchTopics.java ↔ $ENV_FILE mismatch — fix and retry"
  exit 1
fi

# ── 校验 3:存在的本地 env 文件与指定模板 KAFKA_TOPICS 一致 ──
for sibling in .env.example .env.local; do
  if [[ ! -f "$sibling" ]]; then
    continue
  fi
  sibling_topics="$(extract_topics "$sibling")"
  if [[ "$sibling_topics" != "$TOPICS_VAR" ]]; then
    echo "❌ ERROR: KAFKA_TOPICS drift between $ENV_FILE and $sibling"
    echo "   $ENV_FILE: $TOPICS_VAR"
    echo "   $sibling: $sibling_topics"
    errors=$((errors + 1))
  fi
done

if [[ $errors -gt 0 ]]; then
  echo
  echo "💥 KAFKA_TOPICS drift between env files — sync them"
  exit 1
fi

echo "✅ KAFKA_TOPICS in $ENV_FILE is aligned with available local env files ($topic_count topics)"
echo "✅ All BatchTopics.java active constants present in env files"
echo "✅ All env topics have matching BatchTopics constants"
