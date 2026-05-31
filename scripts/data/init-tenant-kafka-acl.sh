#!/bin/sh
# =========================================================
# init-tenant-kafka-acl.sh — ADR-035 Phase 3:per-tenant Kafka SASL/SCRAM 用户 + ACL
#
# 为某个租户在 Kafka 集群上:
#   1. 创建 SCRAM-SHA-512 用户 (kafka-configs.sh --alter --add-config)
#   2. 授 read 权限到该租户的 dispatch topic(s) 和 consumer group
#   3. 不授任何 cluster 级权限,不授其他租户 topic 权限(隔离)
#
# 用法:
#   KAFKA_BOOTSTRAP_SERVER=kafka:29092 \
#   ADMIN_CONFIG=/tmp/admin-client.properties \
#   TENANT_ID=bigcorp \
#   TENANT_PASSWORD='generated-strong-secret' \
#   WORKER_TYPES=import,export \
#     sh scripts/data/init-tenant-kafka-acl.sh
#
# admin-client.properties 必须有 sasl 管理员凭据(创 SCRAM user / ACL 用)。
#
# 输出:把生成的 sasl.jaas.config 字符串写到 stdout,运维拷贝到 K8s Secret
# (worker-tenant.yaml envFrom → BATCH_KAFKA_SASL_JAAS)。
#
# 见 docs/runbook/per-tenant-worker-onboarding.md §kafka-acl
# =========================================================
set -eu

bootstrap_server="${KAFKA_BOOTSTRAP_SERVER:?required}"
admin_config="${ADMIN_CONFIG:?required, e.g. /tmp/admin-client.properties}"
tenant_id="${TENANT_ID:?required}"
tenant_password="${TENANT_PASSWORD:?required, 32+ chars random}"
worker_types_csv="${WORKER_TYPES:-import,export,process,dispatch}"

# 与 init-tenant-topics.sh 保持一致的 sanitize 规则
sanitize() {
  echo "$1" | sed 's/[^a-zA-Z0-9._-]/_/g'
}
safe_tenant="$(sanitize "${tenant_id}")"
scram_user="batch-tenant-${safe_tenant}"
group_id="${safe_tenant}-sample-workers"

echo "==> [1/3] 创建 SCRAM-SHA-512 用户 ${scram_user}"
/opt/kafka/bin/kafka-configs.sh \
  --bootstrap-server "${bootstrap_server}" \
  --command-config "${admin_config}" \
  --alter \
  --add-config "SCRAM-SHA-512=[iterations=8192,password=${tenant_password}]" \
  --entity-type users \
  --entity-name "${scram_user}"

echo "==> [2/3] 授 read ACL 到该 tenant 的 dispatch topics + consumer group"
for raw_type in $(IFS=','; echo $worker_types_csv); do
  wt="$(echo "${raw_type}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
  [ -n "${wt}" ] || continue
  topic="batch.task.dispatch.${wt}.${safe_tenant}"
  echo "    + topic ${topic} → User:${scram_user} READ + DESCRIBE"
  /opt/kafka/bin/kafka-acls.sh \
    --bootstrap-server "${bootstrap_server}" \
    --command-config "${admin_config}" \
    --add \
    --allow-principal "User:${scram_user}" \
    --operation Read --operation Describe \
    --topic "${topic}"
done

echo "    + group ${group_id} → User:${scram_user} READ"
/opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server "${bootstrap_server}" \
  --command-config "${admin_config}" \
  --add \
  --allow-principal "User:${scram_user}" \
  --operation Read \
  --group "${group_id}"

echo "==> [3/3] 输出 K8s Secret 用的 sasl.jaas.config(把下面这一行整段塞到 BATCH_KAFKA_SASL_JAAS)"
echo ""
echo "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"${scram_user}\" password=\"${tenant_password}\";"
echo ""
echo "完成。建议:轮换密码后重新执行此脚本(--alter --add-config 同名 user 会覆盖)。"
