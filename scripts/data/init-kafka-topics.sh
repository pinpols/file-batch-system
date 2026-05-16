#!/bin/sh
# =========================================================
# init-kafka-topics.sh - 初始化本地 / 容器 Kafka topics
# Notes:
# 1) 等待 Kafka 可用后创建平台需要的 topics。
# 2) 默认使用 kafka:29092，可通过环境变量覆盖。
# =========================================================
#   - topic 列表: batch.task.dispatch.import,batch.task.dispatch.export,batch.task.dispatch.process,
#                batch.task.dispatch.dispatch,batch.task.result,batch.task.retry,batch.task.dead-letter
#   - 分区数：默认全部 3；可通过 KAFKA_PARTITIONS_DISPATCH / _RESULT / _RETRY / _DEAD_LETTER 单独覆盖
#   - replication factor: 1
#
# 使用方法：
#   KAFKA_BOOTSTRAP_SERVER=localhost:19092 \
#   KAFKA_TOPICS=batch.task.dispatch.import,batch.task.result \
#     bash scripts/data/init-kafka-topics.sh
#
# 生产环境配置示例（10 实例 × 4 并发，3 节点 Kafka 集群）：
#   KAFKA_TOPIC_REPLICATION_FACTOR=3
#   KAFKA_PARTITIONS_DISPATCH=40
#   KAFKA_PARTITIONS_RESULT=20
#   KAFKA_PARTITIONS_RETRY=10
#   KAFKA_PARTITIONS_DEAD_LETTER=5
# 注意：生产环境还需在 Kafka broker 配置 min.insync.replicas=2
set -eu

bootstrap_server="${KAFKA_BOOTSTRAP_SERVER:-kafka:29092}"
topics_csv="${KAFKA_TOPICS:-batch.task.dispatch.import,batch.task.dispatch.export,batch.task.dispatch.process,batch.task.dispatch.dispatch,batch.task.result,batch.task.retry,batch.task.dead-letter,batch.trigger.launch.v1,batch.verifier.failure.v1}"
default_partitions="${KAFKA_TOPIC_PARTITIONS:-3}"
replication_factor="${KAFKA_TOPIC_REPLICATION_FACTOR:-1}"

# 各 topic 类型分区数（未设置则回退到 default_partitions）
partitions_dispatch="${KAFKA_PARTITIONS_DISPATCH:-${default_partitions}}"
partitions_result="${KAFKA_PARTITIONS_RESULT:-${default_partitions}}"
partitions_retry="${KAFKA_PARTITIONS_RETRY:-${default_partitions}}"
partitions_dead_letter="${KAFKA_PARTITIONS_DEAD_LETTER:-${default_partitions}}"

# 根据 topic 名称后缀匹配分区数
resolve_partitions() {
  topic="$1"
  case "${topic}" in
    *.dispatch.import|*.dispatch.export|*.dispatch.dispatch)
      echo "${partitions_dispatch}" ;;
    *.task.result)
      echo "${partitions_result}" ;;
    *.task.retry)
      echo "${partitions_retry}" ;;
    *.dead-letter)
      echo "${partitions_dead_letter}" ;;
    *)
      echo "${default_partitions}" ;;
  esac
}

echo "Waiting for Kafka at ${bootstrap_server} ..."
until /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${bootstrap_server}" --list >/dev/null 2>&1; do
  sleep 2
done

old_ifs=$IFS
IFS=','
for raw_topic in $topics_csv; do
  topic="$(echo "${raw_topic}" | tr -d '[:space:]')"
  [ -n "${topic}" ] || continue

  partitions="$(resolve_partitions "${topic}")"

  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "${bootstrap_server}" \
    --create \
    --if-not-exists \
    --topic "${topic}" \
    --partitions "${partitions}" \
    --replication-factor "${replication_factor}"
done
IFS=$old_ifs

echo "Kafka topics ready."
exit 0
