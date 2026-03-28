#!/bin/sh
# =========================================================
# init-kafka-topics.sh - 初始化本地 / 容器 Kafka topics
# Notes:
# 1) 等待 Kafka 可用后创建平台需要的 topics。
# 2) 默认使用 kafka:29092，可通过环境变量覆盖。
# =========================================================
#   - topic 列表: batch.task.dispatch.import,batch.task.dispatch.export,batch.task.dispatch.dispatch,
#                batch.task.result,batch.task.retry,batch.task.dead-letter
#   - partitions: 3
#   - replication factor: 1
#
# 使用方法：
#   KAFKA_BOOTSTRAP_SERVER=localhost:9092 \
#   KAFKA_TOPICS=batch.task.dispatch.import,batch.task.result \
#     bash scripts/local/init-kafka-topics.sh
set -eu

bootstrap_server="${KAFKA_BOOTSTRAP_SERVER:-kafka:29092}"
topics_csv="${KAFKA_TOPICS:-batch.task.dispatch.import,batch.task.dispatch.export,batch.task.dispatch.dispatch,batch.task.result,batch.task.retry,batch.task.dead-letter}"
topic_partitions="${KAFKA_TOPIC_PARTITIONS:-3}"
replication_factor="${KAFKA_TOPIC_REPLICATION_FACTOR:-1}"

echo "Waiting for Kafka at ${bootstrap_server} ..."
until /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${bootstrap_server}" --list >/dev/null 2>&1; do
  sleep 2
done

old_ifs=$IFS
IFS=','
for raw_topic in $topics_csv; do
  topic="$(echo "${raw_topic}" | tr -d '[:space:]')"
  [ -n "${topic}" ] || continue

  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "${bootstrap_server}" \
    --create \
    --if-not-exists \
    --topic "${topic}" \
    --partitions "${topic_partitions}" \
    --replication-factor "${replication_factor}"
done
IFS=$old_ifs

echo "Kafka topics ready:"
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "${bootstrap_server}" --list
