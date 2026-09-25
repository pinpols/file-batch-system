#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET_ENDPOINT="${1:?usage: s3-compatible-minio-migration.sh endpoint access-key secret-key}"
TARGET_ACCESS_KEY="${2:?usage: s3-compatible-minio-migration.sh endpoint access-key secret-key}"
TARGET_SECRET_KEY="${3:?usage: s3-compatible-minio-migration.sh endpoint access-key secret-key}"

for command in mc openssl shasum; do
  if ! command -v "$command" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$command" >&2
    exit 2
  fi
done

# shellcheck source=../lib/env-common.sh
source "$ROOT_DIR/scripts/lib/env-common.sh"
batch_load_default_env

SOURCE_ACCESS_KEY="${MINIO_ROOT_USER:-}"
SOURCE_SECRET_KEY="${MINIO_ROOT_PASSWORD:-}"
SOURCE_ENDPOINT="${MINIO_ENDPOINT:-http://localhost:${MINIO_API_PORT}}"
if [[ -z "$SOURCE_ACCESS_KEY" || -z "$SOURCE_SECRET_KEY" ]]; then
  printf 'Local MinIO credentials are unavailable; load them through the repository environment configuration.\n' >&2
  exit 2
fi

RUN_ID="$(openssl rand -hex 8)"
SOURCE_ALIAS="pocsrc${RUN_ID}"
TARGET_ALIAS="poctgt${RUN_ID}"
SOURCE_BUCKET="s3-poc-src-${RUN_ID}"
TARGET_BUCKET="s3-poc-dst-${RUN_ID}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/s3-migration-poc.XXXXXX")"

cleanup() {
  local status=$?
  trap - EXIT
  mc rm --recursive --force "${SOURCE_ALIAS}/${SOURCE_BUCKET}" >/dev/null 2>&1 || true
  mc rb "${SOURCE_ALIAS}/${SOURCE_BUCKET}" >/dev/null 2>&1 || true
  mc rm --recursive --force "${TARGET_ALIAS}/${TARGET_BUCKET}" >/dev/null 2>&1 || true
  mc rb "${TARGET_ALIAS}/${TARGET_BUCKET}" >/dev/null 2>&1 || true
  mc alias rm "$SOURCE_ALIAS" >/dev/null 2>&1 || true
  mc alias rm "$TARGET_ALIAS" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
  exit "$status"
}
trap cleanup EXIT

mc alias set "$SOURCE_ALIAS" "$SOURCE_ENDPOINT" "$SOURCE_ACCESS_KEY" "$SOURCE_SECRET_KEY" >/dev/null
mc alias set "$TARGET_ALIAS" "$TARGET_ENDPOINT" "$TARGET_ACCESS_KEY" "$TARGET_SECRET_KEY" >/dev/null
mc mb "${SOURCE_ALIAS}/${SOURCE_BUCKET}" >/dev/null
mc mb "${TARGET_ALIAS}/${TARGET_BUCKET}" >/dev/null

mkdir -p "$WORK_DIR/source" "$WORK_DIR/source-download" "$WORK_DIR/target-download"
for size_kib in {1..32}; do
  openssl rand -out "$WORK_DIR/source/object-${size_kib}.bin" "$((size_kib * 1024))"
done

mc cp --recursive "$WORK_DIR/source/" "${SOURCE_ALIAS}/${SOURCE_BUCKET}/" >/dev/null
mc mirror "${SOURCE_ALIAS}/${SOURCE_BUCKET}" "${TARGET_ALIAS}/${TARGET_BUCKET}" >/dev/null
mc cp --recursive "${SOURCE_ALIAS}/${SOURCE_BUCKET}/" "$WORK_DIR/source-download/" >/dev/null
mc cp --recursive "${TARGET_ALIAS}/${TARGET_BUCKET}/" "$WORK_DIR/target-download/" >/dev/null

manifest() {
  (
    cd "$1"
    find . -type f -print | LC_ALL=C sort | while IFS= read -r file; do
      shasum -a 256 "$file"
    done
  )
}

manifest "$WORK_DIR/source" >"$WORK_DIR/expected.sha256"
manifest "$WORK_DIR/source-download" >"$WORK_DIR/source.sha256"
manifest "$WORK_DIR/target-download" >"$WORK_DIR/target.sha256"
cmp "$WORK_DIR/expected.sha256" "$WORK_DIR/source.sha256"
cmp "$WORK_DIR/expected.sha256" "$WORK_DIR/target.sha256"
printf 'MinIO migration check passed: objects=32 bytes=589824 sha256=matched endpoint=%s\n' \
  "$TARGET_ENDPOINT"
