#!/bin/sh
# =========================================================
# init-kafka-topics.sh - 初始化 Kafka topics
# 说明：
# 1) 等待 Kafka 可用后创建平台需要的 topics。
# 2) 生产 / 测试 / 托管 Kafka 必须显式传入 KAFKA_BOOTSTRAP_SERVER 和 CLI 路径；
#    未传时才回退到仓库本地 Compose 默认值。
# =========================================================
#   - topic 列表: batch.task.dispatch.import,batch.task.dispatch.export,batch.task.dispatch.process,
#                batch.task.dispatch.dispatch,batch.task.dispatch.atomic,batch.task.result,
#                batch.task.retry,batch.task.dead-letter
#   - 分区数：默认全部 4；可通过 KAFKA_PARTITIONS_DISPATCH / _RESULT / _RETRY / _DEAD_LETTER 单独覆盖
#   - 副本因子：默认 1（dev）；prod 设 KAFKA_TOPIC_REPLICATION_FACTOR=3 + KAFKA_TOPIC_MIN_INSYNC_REPLICAS=2
#   - 保留期：默认不覆盖；需要环境级治理时设 KAFKA_TOPIC_RETENTION_MS 或分类型变量
#
# 使用方法（显式连接外部或本地端口）：
#   KAFKA_BOOTSTRAP_SERVER=localhost:19092 \
#   KAFKA_TOPICS=batch.task.dispatch.import,batch.task.result \
#     bash scripts/data/init-kafka-topics.sh
#
# 外部环境：
#   - 安装 Kafka CLI，并设置 KAFKA_BIN_DIR=/path/to/kafka/bin；或
#   - 直接设置 KAFKA_TOPICS_BIN=/path/to/kafka-topics.sh。
#
# 生产环境配置示例（10 实例 × 4 并发，3 节点 Kafka 集群）：
#   KAFKA_TOPIC_REPLICATION_FACTOR=3
#   KAFKA_TOPIC_MIN_INSYNC_REPLICAS=2      # ← 配 RF=3 + producer acks=all，在途事件 0 丢
#   KAFKA_PARTITIONS_DISPATCH=40
#   KAFKA_PARTITIONS_RESULT=20
#   KAFKA_PARTITIONS_RETRY=10
#   KAFKA_PARTITIONS_DEAD_LETTER=5
#
# 副本因子 / min.insync.replicas 均可配，默认值保持 dev 单 broker 行为不变：
#   - KAFKA_TOPIC_REPLICATION_FACTOR：默认 1；prod 设 3。
#   - KAFKA_TOPIC_MIN_INSYNC_REPLICAS：默认空（不下发 topic 级 min.insync，沿用 broker 默认 1）；
#     prod 设 2，则建 topic 时随 `--config min.insync.replicas=2` 一并落地，不依赖
#     “运维记得单独在 broker 上配”。RF=1 时若设 min.insync>1 会让该 topic 无法写入，故 dev 默认留空。
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=scripts/lib/runtime-defaults.sh
. "$SCRIPT_DIR/../lib/runtime-defaults.sh"

bootstrap_server="${KAFKA_BOOTSTRAP_SERVER:-$BATCH_DEFAULT_KAFKA_CONTAINER_BOOTSTRAP}"
kafka_container_bin_dir="${KAFKA_CONTAINER_BIN_DIR:-$BATCH_DEFAULT_KAFKA_CONTAINER_BIN_DIR}"
default_topics="batch.task.dispatch.import,batch.task.dispatch.export,batch.task.dispatch.process,batch.task.dispatch.dispatch,batch.task.dispatch.atomic,batch.task.result,batch.task.retry,batch.task.dead-letter,batch.trigger.launch.v1,batch.verifier.failure.v1,batch.workflow.terminal.v1"
default_direct_topics="batch.task.dispatch.import.node.import-node-1,batch.task.dispatch.export.node.export-node-1,batch.task.dispatch.process.node.process-node-1,batch.task.dispatch.dispatch.node.dispatch-node-1,batch.task.dispatch.atomic.node.atomic-node-1"
# 平台核心 topic 和内置 worker direct topic 永远必须存在。KAFKA_TOPICS 与
# KAFKA_DIRECT_WORKER_TOPICS 都只追加自定义 topic，不能因旧的 gitignored .env 覆盖而漏掉
# direct / trigger / verifier topic。direct topic 若由 broker 自动创建，会继承 broker 默认分区数，
# 导致 worker 并发预算与初始化声明不一致。
topics_csv="${default_topics},${default_direct_topics}"
if [ -n "${KAFKA_DIRECT_WORKER_TOPICS:-}" ]; then
  topics_csv="${topics_csv},${KAFKA_DIRECT_WORKER_TOPICS}"
fi
if [ -n "${KAFKA_TOPICS:-}" ]; then
  topics_csv="${topics_csv},${KAFKA_TOPICS}"
fi
default_partitions="${KAFKA_TOPIC_PARTITIONS:-4}"
replication_factor="${KAFKA_TOPIC_REPLICATION_FACTOR:-1}"
# 空 = 不下发 topic 级 min.insync.replicas（dev 默认，沿用 broker 默认）；prod 设 2。
min_insync_replicas="${KAFKA_TOPIC_MIN_INSYNC_REPLICAS:-}"
kafka_topics_bin="${KAFKA_TOPICS_BIN:-}"
if [ -z "${kafka_topics_bin}" ] && [ -n "${KAFKA_BIN_DIR:-}" ]; then
  kafka_topics_bin="${KAFKA_BIN_DIR%/}/kafka-topics.sh"
fi
if [ -z "${kafka_topics_bin}" ]; then
  if command -v kafka-topics.sh >/dev/null 2>&1; then
    kafka_topics_bin="$(command -v kafka-topics.sh)"
  else
    kafka_topics_bin="${kafka_container_bin_dir}/kafka-topics.sh"
  fi
fi
if ! command -v "${kafka_topics_bin}" >/dev/null 2>&1; then
  echo "kafka-topics.sh not found: set KAFKA_BIN_DIR or KAFKA_TOPICS_BIN" >&2
  exit 2
fi
kafka_configs_bin="${KAFKA_CONFIGS_BIN:-}"
if [ -z "${kafka_configs_bin}" ] && [ -n "${KAFKA_BIN_DIR:-}" ]; then
  kafka_configs_bin="${KAFKA_BIN_DIR%/}/kafka-configs.sh"
fi
if [ -z "${kafka_configs_bin}" ]; then
  if command -v kafka-configs.sh >/dev/null 2>&1; then
    kafka_configs_bin="$(command -v kafka-configs.sh)"
  else
    kafka_configs_bin="${kafka_container_bin_dir}/kafka-configs.sh"
  fi
fi
if ! command -v "${kafka_configs_bin}" >/dev/null 2>&1; then
  kafka_configs_bin=""
fi

# 各 topic 类型分区数（未设置则回退到 default_partitions）
partitions_dispatch="${KAFKA_PARTITIONS_DISPATCH:-${default_partitions}}"
partitions_result="${KAFKA_PARTITIONS_RESULT:-${default_partitions}}"
partitions_retry="${KAFKA_PARTITIONS_RETRY:-${default_partitions}}"
partitions_dead_letter="${KAFKA_PARTITIONS_DEAD_LETTER:-${default_partitions}}"
partitions_trigger_launch="${KAFKA_PARTITIONS_TRIGGER_LAUNCH:-12}"

# 根据 topic 名称后缀匹配分区数
resolve_partitions() {
  topic="$1"
  case "${topic}" in
    *.dispatch.import|*.dispatch.export|*.dispatch.process|*.dispatch.dispatch|*.dispatch.atomic|*.node.*)
      echo "${partitions_dispatch}" ;;
    *.task.result)
      echo "${partitions_result}" ;;
    *.task.retry)
      echo "${partitions_retry}" ;;
    *.dead-letter)
      echo "${partitions_dead_letter}" ;;
    batch.trigger.launch.v1)
      echo "${partitions_trigger_launch}" ;;
    *)
      echo "${default_partitions}" ;;
  esac
}

resolve_retention_ms() {
  topic="$1"
  case "${topic}" in
    *.dispatch.import|*.dispatch.export|*.dispatch.process|*.dispatch.dispatch|*.dispatch.atomic|*.node.*)
      echo "${KAFKA_TOPIC_RETENTION_MS_DISPATCH:-${KAFKA_TOPIC_RETENTION_MS:-}}" ;;
    *.task.result)
      echo "${KAFKA_TOPIC_RETENTION_MS_RESULT:-${KAFKA_TOPIC_RETENTION_MS:-}}" ;;
    *.task.retry)
      echo "${KAFKA_TOPIC_RETENTION_MS_RETRY:-${KAFKA_TOPIC_RETENTION_MS:-}}" ;;
    *.dead-letter)
      echo "${KAFKA_TOPIC_RETENTION_MS_DEAD_LETTER:-${KAFKA_TOPIC_RETENTION_MS:-}}" ;;
    batch.trigger.launch.v1)
      echo "${KAFKA_TOPIC_RETENTION_MS_TRIGGER_LAUNCH:-${KAFKA_TOPIC_RETENTION_MS:-}}" ;;
    *)
      echo "${KAFKA_TOPIC_RETENTION_MS:-}" ;;
  esac
}

apply_topic_config() {
  topic="$1"
  key="$2"
  value="$3"
  [ -n "${value}" ] || return 0
  if [ -z "${kafka_configs_bin}" ]; then
    echo "kafka-configs.sh not found: set KAFKA_BIN_DIR or KAFKA_CONFIGS_BIN before applying ${key}" >&2
    exit 2
  fi
  "${kafka_configs_bin}" \
    --bootstrap-server "${bootstrap_server}" \
    --entity-type topics \
    --entity-name "${topic}" \
    --alter \
    --add-config "${key}=${value}" >/dev/null
}

echo "Waiting for Kafka at ${bootstrap_server} ..."
until "${kafka_topics_bin}" --bootstrap-server "${bootstrap_server}" --list >/dev/null 2>&1; do
  sleep 2
done

topic_partitions() {
  topic="$1"
  "${kafka_topics_bin}" \
    --bootstrap-server "${bootstrap_server}" \
    --describe \
    --topic "${topic}" 2>/dev/null \
    | awk -F'PartitionCount: ' '
        !found && NF > 1 {
          split($2, a, " ")
          partitions = a[1]
          found = 1
        }
        END {
          if (found) print partitions
        }'
}

ensure_topic() {
  topic="$1"
  partitions="$2"
  current="$(topic_partitions "${topic}")"
  if [ -z "${current}" ]; then
    # min.insync.replicas 为空则不下发该 --config（dev 行为不变）。
    if [ -n "${min_insync_replicas}" ]; then
      "${kafka_topics_bin}" \
        --bootstrap-server "${bootstrap_server}" \
        --create \
        --if-not-exists \
        --topic "${topic}" \
        --partitions "${partitions}" \
        --replication-factor "${replication_factor}" \
        --config "min.insync.replicas=${min_insync_replicas}"
    else
      "${kafka_topics_bin}" \
        --bootstrap-server "${bootstrap_server}" \
        --create \
        --if-not-exists \
        --topic "${topic}" \
        --partitions "${partitions}" \
        --replication-factor "${replication_factor}"
    fi
    return
  fi
  if [ "${current}" -lt "${partitions}" ]; then
    echo "Increasing ${topic} partitions ${current} -> ${partitions}"
    "${kafka_topics_bin}" \
      --bootstrap-server "${bootstrap_server}" \
      --alter \
      --topic "${topic}" \
      --partitions "${partitions}"
  fi
}

old_ifs=$IFS
IFS=','
for raw_topic in $topics_csv; do
  topic="$(echo "${raw_topic}" | tr -d '[:space:]')"
  [ -n "${topic}" ] || continue

  partitions="$(resolve_partitions "${topic}")"
  retention_ms="$(resolve_retention_ms "${topic}")"

  ensure_topic "${topic}" "${partitions}"
  apply_topic_config "${topic}" "retention.ms" "${retention_ms}"
  apply_topic_config "${topic}" "cleanup.policy" "${KAFKA_TOPIC_CLEANUP_POLICY:-}"
  apply_topic_config "${topic}" "min.insync.replicas" "${min_insync_replicas}"
done
IFS=$old_ifs

echo "Kafka topics ready."
exit 0
