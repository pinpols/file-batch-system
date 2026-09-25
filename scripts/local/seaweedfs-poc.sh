#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="${SEAWEEDFS_POC_IMAGE:-chrislusf/seaweedfs:4.47@sha256:ce9e796f1fe6f06968f4c04bdaf8f678dad9c8acdfef3d244133d71bfa6bf882}"
RUN_ID="$$-$(date +%s)"
CONTAINER="seaweedfs-poc-${RUN_ID}"
VOLUME="seaweedfs-poc-${RUN_ID}"
BUCKET="seaweedfs-poc-${RUN_ID}"

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
  local exit_code=$?
  trap - EXIT
  docker rm --force "$CONTAINER" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  exit "$exit_code"
}
trap cleanup EXIT

docker run -d \
  --name "$CONTAINER" \
  --label com.file-batch-system.poc=seaweedfs \
  --publish 127.0.0.1::8333 \
  --publish 127.0.0.1::9333 \
  --publish 127.0.0.1::23646 \
  --volume "$VOLUME:/data" \
  --env "AWS_ACCESS_KEY_ID=$access_key" \
  --env "AWS_SECRET_ACCESS_KEY=$secret_key" \
  --env "S3_BUCKET=$BUCKET" \
  "$IMAGE" mini -dir=/data >/dev/null

s3_port="$(docker port "$CONTAINER" 8333/tcp | sed 's/.*://')"
master_port="$(docker port "$CONTAINER" 9333/tcp | sed 's/.*://')"
admin_port="$(docker port "$CONTAINER" 23646/tcp | sed 's/.*://')"
endpoint="http://127.0.0.1:$s3_port"
master_endpoint="http://127.0.0.1:$master_port"
ready=false

for ((attempt = 0; attempt < 90; attempt++)); do
  if curl --silent --show-error --fail "$master_endpoint/cluster/status" >/dev/null 2>&1; then
    ready=true
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$CONTAINER")" != true ]]; then
    docker logs "$CONTAINER" >&2 || true
    exit 1
  fi
  sleep 2
done

if [[ "$ready" != true ]]; then
  docker logs "$CONTAINER" >&2 || true
  printf 'SeaweedFS master readiness timeout\n' >&2
  exit 1
fi

printf 'SeaweedFS image: %s\nS3 endpoint: %s\nMaster: %s\nAdmin UI: http://127.0.0.1:%s\n' \
  "$IMAGE" "$endpoint" "$master_endpoint" "$admin_port"

cd "$ROOT_DIR"
S3_COMPAT_POC_ENDPOINT="$endpoint" \
S3_COMPAT_POC_ACCESS_KEY="$access_key" \
S3_COMPAT_POC_SECRET_KEY="$secret_key" \
  ./mvnw -B -pl batch-test-support -am \
    -Dtest=S3CompatibleObjectStorePocTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test

scripts/local/s3-compatible-minio-migration.sh "$endpoint" "$access_key" "$secret_key"
