#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="${RUSTFS_POC_IMAGE:-rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff}"
RUN_ID="$$-$(date +%s)"
CONTAINER="rustfs-poc-${RUN_ID}"
VOLUME="rustfs-poc-${RUN_ID}"

for command in docker curl openssl; do
  if ! command -v "$command" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$command" >&2
    exit 2
  fi
done

if [[ ! -x "$ROOT_DIR/mvnw" ]]; then
  printf 'Maven wrapper is missing or not executable: %s/mvnw\n' "$ROOT_DIR" >&2
  exit 2
fi

docker info >/dev/null
access_key="poc$(openssl rand -hex 8)"
secret_key="$(openssl rand -hex 24)"
docker volume create "$VOLUME" >/dev/null

cleanup() {
  local status=$?
  trap - EXIT
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

docker run -d \
  --name "$CONTAINER" \
  --label "com.file-batch-system.poc=rustfs" \
  --publish 127.0.0.1::9000 \
  --publish 127.0.0.1::9001 \
  --volume "$VOLUME:/data" \
  --env "RUSTFS_ACCESS_KEY=$access_key" \
  --env "RUSTFS_SECRET_KEY=$secret_key" \
  "$IMAGE" /data >/dev/null

api_port="$(docker port "$CONTAINER" 9000/tcp | sed 's/.*://')"
console_port="$(docker port "$CONTAINER" 9001/tcp | sed 's/.*://')"
endpoint="http://127.0.0.1:$api_port"
api_ready=false
console_ready=false

for ((attempt = 0; attempt < 90; attempt++)); do
  if curl --silent --show-error --fail "$endpoint/health" >/dev/null 2>&1; then
    api_ready=true
  fi
  if curl --silent --show-error --fail "http://127.0.0.1:$console_port/rustfs/console/health" >/dev/null 2>&1; then
    console_ready=true
  fi
  if [[ "$api_ready" == true && "$console_ready" == true ]]; then
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$CONTAINER")" != true ]]; then
    docker logs "$CONTAINER" >&2 || true
    exit 1
  fi
  sleep 2
done

if [[ "$api_ready" != true || "$console_ready" != true ]]; then
  docker logs "$CONTAINER" >&2 || true
  printf 'RustFS readiness timeout (API=%s, Console=%s)\n' "$api_ready" "$console_ready" >&2
  exit 1
fi

printf 'RustFS image: %s\nS3 endpoint: %s\nConsole health: ready on 127.0.0.1:%s\n' \
  "$IMAGE" "$endpoint" "$console_port"

cd "$ROOT_DIR"
  S3_COMPAT_POC_ENDPOINT="$endpoint" \
  S3_COMPAT_POC_ACCESS_KEY="$access_key" \
  S3_COMPAT_POC_SECRET_KEY="$secret_key" \
  ./mvnw -B -pl batch-test-support -am \
    -Dtest=S3CompatibleObjectStorePocTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test

scripts/local/s3-compatible-minio-migration.sh "$endpoint" "$access_key" "$secret_key"
