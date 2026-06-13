#!/usr/bin/env bash
# ③ 一键 apply 阶段 1-3 CR + 等就绪。前置:install-operators.sh + bootstrap-secrets.sh 已跑,
# 且 deploy/ha/*.yaml 的占位符(STORAGE_CLASS 等)已改。幂等:kubectl apply。
set -euo pipefail
HA_DIR=${HA_DIR:-deploy/ha}
log() { echo "[apply-stage123] $*"; }

# 占位符防呆:还残留 STORAGE_CLASS 就停
if grep -rq "STORAGE_CLASS" "$HA_DIR"/*.yaml; then
  echo "❌ $HA_DIR 仍含占位符 STORAGE_CLASS,先按真集群改完再 apply"; exit 1
fi

log "namespaces"; kubectl apply -f "$HA_DIR/00-namespaces.yaml"

# 阶段1:PG HA(含 PgBouncer)
log "阶段1 PG"; kubectl apply -f "$HA_DIR/10-postgres-zalando.yaml"
kubectl -n batch-data wait --for=condition=Ready --timeout=600s postgresql/batch-pg 2>/dev/null \
  || kubectl -n batch-data rollout status statefulset/batch-pg --timeout=600s || true

# 阶段2:Kafka / Redis / MinIO
log "阶段2 Kafka"; kubectl apply -f "$HA_DIR/20-kafka-strimzi.yaml"
kubectl -n kafka wait --for=condition=Ready --timeout=600s kafka/batch-kafka || true
log "阶段2 Redis"; kubectl apply -f "$HA_DIR/30-redis-failover.yaml"
log "阶段2 MinIO"; kubectl apply -f "$HA_DIR/40-minio-tenant.yaml"
kubectl -n minio rollout status statefulset -l v1.min.io/tenant=batch-minio --timeout=600s || true

log "阶段3 备份:postgres CR 已声明 logicalBackup + WAL→MinIO;恢复演练见 docs/runbook/backup-and-pitr.md §2"
log "全部 apply 完成。跑 scripts/ha/failover-drill.sh 验证 HA。"
