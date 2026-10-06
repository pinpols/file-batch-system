#!/usr/bin/env bash
# =========================================================
# validate-kafka-topics.sh - 校验全仓 topic 字面量与 BatchTopics.java 同步
#
# BatchTopics.java 是 topic 名的唯一权威。本脚本覆盖 5 处 topic 载体:
#   1) 指定 env 模板的 KAFKA_TOPICS（默认 `.env.example`）—— 双向 diff
#   2) `batch-defaults.yml` 的 `${BATCH_TOPIC_*:默认值}` —— 必须都是 BatchTopics 常量
#   3) `helm/batch-platform/**` 中的 `"batch.*"` 字面量 —— 必须都是 BatchTopics 常量
#   4) `scripts/data/init-kafka-topics.sh` 的 `default_topics` —— 必须覆盖全部 active 常量
#   5) `load-tests/scripts/cleanup-load-test-environment.sh` 的 topic 清单 —— 必须都是常量
#
# 失败场景:
# 1) BatchTopics.java 新增 active topic 但指定 env 模板没补 → init-kafka-topics 不会建,
#    严格集群里(disable auto-create)首次发布会丢消息
# 2) env 模板列了 BatchTopics.java 不认识的 topic 名 → 配置漂移,白创建占资源
# 3) `.env.example` 是 CI 权威模板；本地可显式传 `.env.local` / `.env.prod` 做同规则复核
# 4) Topic 名违反命名规范 batch.<segment>.<segment>...(纯小写 + 数字 + . + - + _)
# 5) 部署侧（helm / batch-defaults / init 脚本 / load-tests）复制了错的 topic 名或漏了新 topic
#
# 例外白名单(目录元数据,不参与 MQ 实流量,不强求 env 模板同步):
# - OUTBOX_EVENT / WORKER_HEARTBEAT — 仅 ConsoleEventCatalogController 展示用
#
# 本脚本被 .github/workflows/pr-gate.yml 调用。
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

# ── 校验 4~7: 其它 topic 载体 ⊆ BatchTopics.java ────────────────────────
# 这些文件各自复制了 topic 名，此前无任何守护。漂移后果分两类:
# - batch-defaults.yml 默认值 / helm keda topic / load-tests 清单写错名 → 指向不存在的 topic
# - init-kafka-topics.sh default_topics 漏项 → 严格集群(disable auto-create)不建该 topic
# 注:只做「字面量 ∈ BatchTopics」与「BatchTopics ⊆ init 清单」两个方向，
# 不做全等 —— 各载体按设计只承载自己关心的子集（如 helm 只管 5 个 dispatch topic）。

# 断言 $2 里每个值都在 code_topics_sorted 中
check_subset() {
  local label="$1" values="$2"
  if [[ -z "$values" ]]; then
    return 0
  fi
  local unknown
  unknown="$(comm -23 <(printf '%s\n' "$values") <(printf '%s\n' "$code_topics_sorted"))"
  if [[ -n "$unknown" ]]; then
    echo "❌ ERROR: $label 含 BatchTopics.java 未定义的 topic:"
    echo "$unknown" | sed 's/^/   - /'
    echo "   action: 改用 BatchTopics.java 中的常量值，或先在该文件定义常量"
    errors=$((errors + 1))
  fi
}

# 校验 4: batch-defaults.yml 的 ${BATCH_TOPIC_*:默认值}
# 属性缺失时这些默认值直接生效（裸 java -jar / 未注入 BATCH_TOPIC_* 的部署）
BATCH_DEFAULTS_FILE="batch-common/src/main/resources/batch-defaults.yml"
if [[ ! -f "$BATCH_DEFAULTS_FILE" ]]; then
  echo "❌ ERROR: $BATCH_DEFAULTS_FILE not found"
  exit 1
fi
defaults_topics_sorted="$(
  grep -oE '\$\{BATCH_TOPIC_[A-Z0-9_]+:[a-z0-9._-]+\}' "$BATCH_DEFAULTS_FILE" \
    | sed -E 's/^\$\{BATCH_TOPIC_[A-Z0-9_]+:([a-z0-9._-]+)\}$/\1/' | sort -u || true
)"
check_subset "batch-defaults.yml 的 BATCH_TOPIC_* 默认值" "$defaults_topics_sorted"

# 校验 5: helm 中带引号的 "batch.*" 字面量
# 只取引号形式 —— yaml 里还有 `batch.example.com/...` 这类 k8s label 前缀，裸 token 匹配会误报
HELM_TOPIC_FILES=(
  "helm/batch-platform/values.yaml"
  "helm/batch-platform/examples/values-autoscale.yaml"
  "helm/batch-platform/templates/worker-tenant.yaml"
)
helm_topics_sorted="$(
  for helm_file in "${HELM_TOPIC_FILES[@]}"; do
    if [[ ! -f "$helm_file" ]]; then
      echo "❌ ERROR: $helm_file not found" >&2
      exit 1
    fi
    grep -oE '"batch\.[a-z0-9._-]+"' "$helm_file" | tr -d '"' || true
  done | sort -u
)"
check_subset "helm/batch-platform 的 topic 字面量" "$helm_topics_sorted"

# 校验 6: init-kafka-topics.sh 的 default_topics 必须覆盖全部 active 常量
# 该行自带注释「平台核心 topic 永远必须存在」，故要求完整覆盖（不含 direct topic 行）
INIT_TOPICS_FILE="scripts/data/init-kafka-topics.sh"
init_defaults_sorted="$(
  grep -oE '^default_topics="[^"]*"' "$INIT_TOPICS_FILE" \
    | sed -E 's/^default_topics="//; s/"$//' \
    | tr ',' '\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | grep -v '^$' | sort -u || true
)"
missing_in_init="$(comm -23 <(printf '%s\n' "$code_topics_sorted") <(printf '%s\n' "$init_defaults_sorted"))"
if [[ -n "$missing_in_init" ]]; then
  echo "❌ ERROR: $INIT_TOPICS_FILE 的 default_topics 未覆盖 BatchTopics.java 的 active 常量:"
  echo "$missing_in_init" | sed 's/^/   - /'
  echo "   action: 补进 default_topics（该清单声明为「平台核心 topic 永远必须存在」）"
  errors=$((errors + 1))
fi

# 校验 7: load-tests 清理脚本的 topic 清单（用于 --reset-kafka-topics 删除）
# 只校验名字合法，不要求完整 —— 该清单按设计是"load test 自己管理的子集"
LOAD_TESTS_TOPICS_FILE="load-tests/scripts/cleanup-load-test-environment.sh"
load_tests_topics_sorted="$(
  grep -oE 'batch\.[a-z0-9_-]+(\.[a-z0-9_-]+)+' "$LOAD_TESTS_TOPICS_FILE" \
    | grep -v '\.node\.' | sort -u || true
)"
check_subset "load-tests 清理脚本的 topic 清单" "$load_tests_topics_sorted"

if [[ $errors -gt 0 ]]; then
  echo
  echo "💥 topic 字面量与 BatchTopics.java 不一致 — fix and retry"
  exit 1
fi

echo "✅ KAFKA_TOPICS in $ENV_FILE is aligned with available local env files ($topic_count topics)"
echo "✅ All BatchTopics.java active constants present in env files"
echo "✅ All env topics have matching BatchTopics constants"
echo "✅ batch-defaults.yml / helm / init-kafka-topics.sh / load-tests 的 topic 字面量均与 BatchTopics.java 一致"
