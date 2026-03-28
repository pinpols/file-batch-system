#!/bin/sh
# =========================================================
# init-minio.sh - 初始化本地 / 容器 MinIO 资源
# Notes:
# 1) 等待 MinIO 可用后创建默认 bucket。
# 2) 默认使用 local alias，可通过环境变量覆盖。
# =========================================================
#   - endpoint: http://minio:9000
#   - bucket: batch-dev
#
# 使用方法：
#   MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin123 \
#   MINIO_ENDPOINT=http://localhost:9000 \
#     bash scripts/local/init-minio.sh
set -eu

alias_name="${MINIO_ALIAS_NAME:-local}"
endpoint="${MINIO_ENDPOINT:-http://minio:9000}"
bucket="${MINIO_BUCKET:-batch-dev}"

echo "Waiting for MinIO at ${endpoint} ..."
until mc alias set "${alias_name}" "${endpoint}" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null 2>&1; do
  sleep 2
done

mc mb --ignore-existing "${alias_name}/${bucket}"

echo "MinIO bucket ready:"
mc ls "${alias_name}"
