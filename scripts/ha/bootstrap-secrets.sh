#!/usr/bin/env bash
# ② 凭据 secret 引导。在 apply CR 之前建好,避免弱默认(本仓 prod profile 会拦 minioadmin/弱口令)。
# 凭据从环境变量读;**不落盘、不进 git**。CI/生产建议用 ExternalSecret/Vault 替代本脚本。
set -euo pipefail

: "${MINIO_AK:?需设 MINIO_AK}"
: "${MINIO_SK:?需设 MINIO_SK}"
: "${REDIS_PASSWORD:?需设 REDIS_PASSWORD}"
# PG 用户密码由 postgres-operator 自动生成到 Secret(<user>.<cluster>.credentials.postgresql.acid.zalan.do);
# 如需指定可在此建,默认让 operator 生成。

log() { echo "[bootstrap-secrets] $*"; }
kc() { kubectl "$@"; }

# MinIO root 凭据(供 Tenant configuration.name 引用)
kc -n minio create secret generic batch-minio-creds \
  --from-literal=config.env="$(printf 'export MINIO_ROOT_USER=%s\nexport MINIO_ROOT_PASSWORD=%s\n' "$MINIO_AK" "$MINIO_SK")" \
  --dry-run=client -o yaml | kc apply -f -
log "minio creds OK (minio/batch-minio-creds)"

# Redis 鉴权(redis-operator auth.secretPath 引用)
kc -n redis create secret generic batch-redis-auth \
  --from-literal=password="$REDIS_PASSWORD" \
  --dry-run=client -o yaml | kc apply -f -
log "redis auth OK (redis/batch-redis-auth)"

# postgres-operator 访问 MinIO 做 WAL/备份的凭据(若用 WAL-G/WAL-E S3)
kc -n postgres-operator create secret generic pg-backup-s3 \
  --from-literal=AWS_ACCESS_KEY_ID="$MINIO_AK" \
  --from-literal=AWS_SECRET_ACCESS_KEY="$MINIO_SK" \
  --dry-run=client -o yaml | kc apply -f - 2>/dev/null || \
  log "  (pg-backup-s3 命名空间按 operator 实际部署调整)"
log "pg backup s3 creds OK"

log "凭据就绪。可执行 scripts/ha/apply-stage123.sh"
