#!/bin/sh
# =========================================================
# init-minio.sh - 初始化本地 / 容器 MinIO 资源
# 说明：
# 1) 等待 MinIO 可用后创建批量文件桶和 AI 附件专用桶。
# 2) 默认使用 local alias，可通过环境变量覆盖。
# =========================================================
#   - endpoint: http://minio:9000
#   - bucket: batch-dev
#
# 使用方法：
#   MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin123 \
#   MINIO_ENDPOINT=http://localhost:19000 \
#     bash scripts/data/init-minio.sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=../lib/runtime-defaults.sh
. "$SCRIPT_DIR/../lib/runtime-defaults.sh"

alias_name="${MINIO_ALIAS_NAME:-local}"
endpoint="${MINIO_ENDPOINT:-$BATCH_DEFAULT_MINIO_CONTAINER_ENDPOINT}"
bucket="${MINIO_BUCKET:-$BATCH_DEFAULT_MINIO_BUCKET}"
ai_attachment_bucket="${MINIO_AI_ATTACHMENT_BUCKET:-batch-ai-attachments}"

if [ "$bucket" = "$ai_attachment_bucket" ]; then
  echo "AI attachment bucket must differ from batch file bucket" >&2
  exit 1
fi

echo "Waiting for MinIO at ${endpoint} ..."
until mc alias set "${alias_name}" "${endpoint}" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null 2>&1; do
  sleep 2
done

mc mb --ignore-existing "${alias_name}/${bucket}"
mc mb --ignore-existing "${alias_name}/${ai_attachment_bucket}"

echo "MinIO buckets ready:"
mc ls "${alias_name}"
