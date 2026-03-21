#!/bin/sh
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
