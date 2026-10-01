#!/usr/bin/env bash
# 使用固定版本的 MinIO 客户端；不依赖 MinIO 服务镜像内置 mc。
# 本工具仅通过 Docker 运行，与 scripts/sim 的执行方式保持一致。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=env-common.sh
source "$ROOT/scripts/lib/env-common.sh"

MINIO_CONTAINER="${MINIO_CONTAINER:-$BATCH_DEFAULT_MINIO_CONTAINER}"
MINIO_MC_IMAGE_REPOSITORY="${MINIO_MC_IMAGE_REPOSITORY:-bitnamilegacy/minio}"
MINIO_MC_IMAGE_TAG="${MINIO_MC_IMAGE_TAG:-2025.7.23-debian-12-r1}"
MINIO_MC_ALIAS="${MINIO_MC_ALIAS:-local}"
MINIO_MC_ENDPOINT="${MINIO_MC_ENDPOINT:-http://minio:9000}"
MINIO_MC_ACCESS_KEY="${MINIO_MC_ACCESS_KEY:-${BATCH_S3_ACCESS_KEY:-${MINIO_ROOT_USER:-}}}"
MINIO_MC_SECRET_KEY="${MINIO_MC_SECRET_KEY:-${BATCH_S3_SECRET_KEY:-${MINIO_ROOT_PASSWORD:-}}}"

if [[ -z "${MINIO_MC_NETWORK:-}" ]]; then
  MINIO_MC_NETWORK="$(docker inspect -f '{{range $name, $network := .NetworkSettings.Networks}}{{println $name}}{{end}}' \
    "$MINIO_CONTAINER" 2>/dev/null | head -n 1 || true)"
fi
MINIO_MC_NETWORK="${MINIO_MC_NETWORK:-${COMPOSE_PROJECT_NAME:-batch-platform}_batch-network}"

if [[ -z "$MINIO_MC_ACCESS_KEY" || -z "$MINIO_MC_SECRET_KEY" ]]; then
  echo "MinIO CLI credentials are not configured" >&2
  exit 2
fi

exec docker run --rm -i \
  --network "$MINIO_MC_NETWORK" \
  -e MC_CONFIG_DIR=/tmp/mc-config \
  -e "MINIO_MC_ALIAS=$MINIO_MC_ALIAS" \
  -e "MINIO_MC_ENDPOINT=$MINIO_MC_ENDPOINT" \
  -e "MINIO_MC_ACCESS_KEY=$MINIO_MC_ACCESS_KEY" \
  -e "MINIO_MC_SECRET_KEY=$MINIO_MC_SECRET_KEY" \
  --entrypoint /bin/sh \
  "$MINIO_MC_IMAGE_REPOSITORY:$MINIO_MC_IMAGE_TAG" \
  -c 'set -eu
      mc alias set "$MINIO_MC_ALIAS" "$MINIO_MC_ENDPOINT" "$MINIO_MC_ACCESS_KEY" "$MINIO_MC_SECRET_KEY" >/dev/null
      exec mc "$@"' \
  sh "$@"
