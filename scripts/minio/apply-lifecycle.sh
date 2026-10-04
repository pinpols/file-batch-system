#!/bin/sh
# 对象存储 lifecycle 下发脚本。默认只预览；生产 apply 必须显式确认。
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=scripts/lib/runtime-defaults.sh
. "$SCRIPT_DIR/../lib/runtime-defaults.sh"

environment="${MINIO_LIFECYCLE_ENVIRONMENT:-local}"
apply="false"

usage() {
  cat <<'EOF'
Usage: scripts/minio/apply-lifecycle.sh [--environment local|test|benchmark|prod] [--apply]

默认只输出将要下发的 bucket 与策略文件。--apply 才会执行 mc ilm import。
生产环境执行 --apply 时必须设置:
  MINIO_LIFECYCLE_PROD_ACK=I_UNDERSTAND_PRODUCTION_LIFECYCLE
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --environment)
      environment="${2:-}"
      [ -n "$environment" ] || { echo "--environment requires a value" >&2; exit 2; }
      shift 2
      ;;
    --apply)
      apply="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$environment" in
  local|test|benchmark|prod) ;;
  *)
    echo "Unsupported lifecycle environment: $environment" >&2
    exit 2
    ;;
esac

profile_file="$SCRIPT_DIR/lifecycle-profiles/${environment}.env"
[ -r "$profile_file" ] || { echo "Lifecycle profile not found: $profile_file" >&2; exit 2; }
# shellcheck disable=SC1090
. "$profile_file"

alias_name="${MINIO_ALIAS_NAME:-local}"
endpoint="${MINIO_ENDPOINT:-http://localhost:${BATCH_DEFAULT_MINIO_API_PORT}}"
access_key="${MINIO_ROOT_USER:-${BATCH_S3_ACCESS_KEY:-$BATCH_DEFAULT_MINIO_ACCESS_KEY}}"
secret_key="${MINIO_ROOT_PASSWORD:-${BATCH_S3_SECRET_KEY:-$BATCH_DEFAULT_MINIO_SECRET_KEY}}"
batch_bucket="${MINIO_BUCKET:-${BATCH_S3_BUCKET:-$BATCH_DEFAULT_MINIO_BUCKET}}"
ai_bucket="${MINIO_AI_ATTACHMENT_BUCKET:-${BATCH_CONSOLE_AI_ATTACHMENT_STORAGE_BUCKET:-batch-ai-attachments}}"
rules="${MINIO_LIFECYCLE_BUCKET_RULES:-}"

[ -n "$rules" ] || { echo "MINIO_LIFECYCLE_BUCKET_RULES is empty in $profile_file" >&2; exit 2; }

if [ "$apply" = "true" ]; then
  command -v mc >/dev/null 2>&1 || { echo "mc not found in PATH" >&2; exit 2; }
  [ -n "$access_key" ] || { echo "MinIO access key is required for apply" >&2; exit 2; }
  [ -n "$secret_key" ] || { echo "MinIO secret key is required for apply" >&2; exit 2; }
  if [ "$environment" = "prod" ]; then
    [ "${MINIO_LIFECYCLE_PROD_ACK:-}" = "I_UNDERSTAND_PRODUCTION_LIFECYCLE" ] || {
      echo "prod apply requires MINIO_LIFECYCLE_PROD_ACK=I_UNDERSTAND_PRODUCTION_LIFECYCLE" >&2
      exit 2
    }
    local_default_credentials="${BATCH_DEFAULT_MINIO_ACCESS_KEY}:${BATCH_DEFAULT_MINIO_SECRET_KEY}"
    if [ "${access_key}:${secret_key}" = "$local_default_credentials" ]; then
      echo "prod apply refuses local default MinIO root credentials" >&2
      exit 2
    fi
  fi
  mc alias set "$alias_name" "$endpoint" "$access_key" "$secret_key" >/dev/null
fi

resolve_bucket() {
  case "$1" in
    main) printf '%s\n' "$batch_bucket" ;;
    ai) printf '%s\n' "$ai_bucket" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

for rule in $rules; do
  bucket_key="${rule%%:*}"
  policy_file="${rule#*:}"
  if [ "$bucket_key" = "$rule" ]; then
    echo "Invalid lifecycle rule mapping: $rule" >&2
    exit 2
  fi
  bucket="$(resolve_bucket "$bucket_key")"
  policy_path="$SCRIPT_DIR/$policy_file"
  [ -r "$policy_path" ] || { echo "Lifecycle policy not found: $policy_path" >&2; exit 2; }

  if [ "$apply" = "true" ]; then
    echo "Applying lifecycle: environment=$environment bucket=$bucket policy=$policy_file"
    mc ilm import "$alias_name/$bucket" < "$policy_path" >/dev/null
    mc ilm ls "$alias_name/$bucket"
  else
    echo "DRY-RUN lifecycle: environment=$environment bucket=$bucket policy=$policy_file"
  fi
done
