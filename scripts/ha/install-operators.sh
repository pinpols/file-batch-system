#!/usr/bin/env bash
# ① 安装 4 个 HA operator(集群级,各一次)。配合 deploy/ha/*.yaml。
# 幂等:helm upgrade --install;重复跑安全。
# 前置:kubectl 指向目标集群 + helm 3。版本用变量便于锁定/升级。
set -euo pipefail

STRIMZI_NS=${STRIMZI_NS:-strimzi-system}
PG_OP_NS=${PG_OP_NS:-postgres-operator}
REDIS_OP_NS=${REDIS_OP_NS:-redis-operator}
MINIO_OP_NS=${MINIO_OP_NS:-minio-operator}

STRIMZI_VER=${STRIMZI_VER:-0.45.0}
PG_OP_VER=${PG_OP_VER:-1.12.2}
REDIS_OP_VER=${REDIS_OP_VER:-3.3.0}
MINIO_OP_VER=${MINIO_OP_VER:-7.0.1}

log() { echo "[install-operators] $*"; }

require() { command -v "$1" >/dev/null 2>&1 || { echo "缺少 $1"; exit 1; }; }
require kubectl
require helm

log "kubectl context = $(kubectl config current-context)"
read -r -p "确认在此集群安装 4 个 operator?[y/N] " ok; [ "$ok" = "y" ] || exit 1

# 1) Strimzi(Kafka)
helm repo add strimzi https://strimzi.io/charts/ >/dev/null 2>&1 || true
helm repo update >/dev/null
helm upgrade --install strimzi strimzi/strimzi-kafka-operator \
  --namespace "$STRIMZI_NS" --create-namespace --version "$STRIMZI_VER" --wait
log "Strimzi OK ($STRIMZI_NS)"

# 2) Zalando postgres-operator(= Patroni;PG HA + PgBouncer + 备份 + Citus)
helm repo add postgres-operator-charts \
  https://opensource.zalando.com/postgres-operator/charts/postgres-operator >/dev/null 2>&1 || true
helm repo update >/dev/null
helm upgrade --install postgres-operator postgres-operator-charts/postgres-operator \
  --namespace "$PG_OP_NS" --create-namespace --version "$PG_OP_VER" --wait
log "postgres-operator OK ($PG_OP_NS)"
log "  ⚠️ 备份/WAL→MinIO 需在 operator configmap 配 AWS_S3_ENDPOINT/凭据(见 deploy/ha/10-postgres-zalando.yaml 注释)"

# 3) spotahome redis-operator(Sentinel)
helm repo add redis-operator https://spotahome.github.io/redis-operator >/dev/null 2>&1 || true
helm repo update >/dev/null
helm upgrade --install redis-operator redis-operator/redis-operator \
  --namespace "$REDIS_OP_NS" --create-namespace --version "$REDIS_OP_VER" --wait
log "redis-operator OK ($REDIS_OP_NS)"

# 4) MinIO Operator
helm repo add minio-operator https://operator.min.io >/dev/null 2>&1 || true
helm repo update >/dev/null
helm upgrade --install minio-operator minio-operator/operator \
  --namespace "$MINIO_OP_NS" --create-namespace --version "$MINIO_OP_VER" --wait
log "minio-operator OK ($MINIO_OP_NS)"

log "全部 operator 就绪。下一步:scripts/ha/bootstrap-secrets.sh 建凭据 → scripts/ha/apply-stage123.sh"
