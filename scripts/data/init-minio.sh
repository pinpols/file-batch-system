#!/bin/sh
# =========================================================
# init-minio.sh - 初始化 S3 兼容对象存储 bucket
# 说明：
# 1) 等待 S3 兼容对象存储可用后创建批量文件桶和 AI 附件专用桶。
# 2) 生产 / 测试 / 托管对象存储必须显式传入 endpoint、凭据和 bucket；
#    未传时才回退到仓库本地 Compose 默认值。
# =========================================================
#   - local fallback endpoint: http://minio:9000
#   - local fallback bucket: batch-dev
#
# 使用方法（显式连接外部或本地端口）：
#   MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin123 \
#   MINIO_ENDPOINT=http://localhost:19000 \
#     bash scripts/data/init-minio.sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=scripts/lib/runtime-defaults.sh
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

if [ "${MINIO_LIFECYCLE_APPLY_ON_INIT:-true}" = "true" ]; then
  lifecycle_script="${MINIO_LIFECYCLE_SCRIPT:-/scripts/minio/apply-lifecycle.sh}"
  if [ -x "${lifecycle_script}" ]; then
    MINIO_ALIAS_NAME="${alias_name}" \
    MINIO_ENDPOINT="${endpoint}" \
    MINIO_BUCKET="${bucket}" \
    MINIO_AI_ATTACHMENT_BUCKET="${ai_attachment_bucket}" \
      "${lifecycle_script}" --environment "${MINIO_LIFECYCLE_ENVIRONMENT:-local}" --apply
  else
    echo "MinIO lifecycle script not found or not executable: ${lifecycle_script}; skip lifecycle apply"
  fi
fi

echo "MinIO buckets ready:"
mc ls "${alias_name}"
