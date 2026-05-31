#!/bin/sh
# =========================================================
# init-tenant-topics.sh - 为 per-tenant worker pool 创建独立派发 topic
#
# 见 docs/runbook/per-tenant-worker-onboarding.md
# 见 docs/plans/multi-tenant-isolation-plan-2026-05-31.md §Phase D
#
# 命名规约（与 orchestrator BatchTopicResolver TENANT 模式一致）：
#     batch.task.dispatch.{type}.{tenantId}
#   ↑ producer 在 base topic（batch.task.dispatch.{type}）后追加 ".{tenantId}"。
#   worker TENANT_SCOPED 模式订阅正则 ^base(\.(tenantId))?$ 命中此 topic。
#
#   注意：不是 batch.task.dispatch.tenant.{tenantId}.{type}（旧 plan 的笔误，已订正）。
#
# 用法：
#   KAFKA_BOOTSTRAP_SERVER=localhost:19092 \
#   TENANTS=bigcorp,acme \
#   WORKER_TYPES=import,export \
#     sh scripts/data/init-tenant-topics.sh
#
# tenantId 非法字符（Kafka topic 仅允许 [a-zA-Z0-9._-]）会被替换为 "_"，与
# BatchTopicResolver.safe() 行为一致——否则 worker 订阅正则匹配不上。
# =========================================================
set -eu

bootstrap_server="${KAFKA_BOOTSTRAP_SERVER:-kafka:29092}"
tenants_csv="${TENANTS:?TENANTS env required, e.g. TENANTS=bigcorp,acme}"
worker_types_csv="${WORKER_TYPES:-import,export,process,dispatch}"
partitions="${KAFKA_PARTITIONS_DISPATCH:-${KAFKA_TOPIC_PARTITIONS:-3}}"
replication_factor="${KAFKA_TOPIC_REPLICATION_FACTOR:-1}"

# 与 BatchTopicResolver.safe(): [^a-zA-Z0-9._-] -> _
sanitize() {
  echo "$1" | sed 's/[^a-zA-Z0-9._-]/_/g'
}

echo "Waiting for Kafka at ${bootstrap_server} ..."
until /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${bootstrap_server}" --list >/dev/null 2>&1; do
  sleep 2
done

old_ifs=$IFS
for raw_tenant in $(IFS=','; echo $tenants_csv); do
  tenant="$(sanitize "$(echo "${raw_tenant}" | tr -d '[:space:]')")"
  [ -n "${tenant}" ] || continue
  for raw_type in $(IFS=','; echo $worker_types_csv); do
    wt="$(echo "${raw_type}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
    [ -n "${wt}" ] || continue
    topic="batch.task.dispatch.${wt}.${tenant}"
    echo "Creating ${topic} (partitions=${partitions}, rf=${replication_factor})"
    /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server "${bootstrap_server}" \
      --create \
      --if-not-exists \
      --topic "${topic}" \
      --partitions "${partitions}" \
      --replication-factor "${replication_factor}"
  done
done
IFS=$old_ifs

echo "Per-tenant topics ready."
exit 0
