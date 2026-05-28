#!/usr/bin/env bash
# 停模拟器容器(sftp + mockserver),保留 volume 让下次启动数据延续。
# 全清:docker compose -f scripts/sim/compose.yml down -v
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
ENV_FILE="${COMPOSE_ENV_FILE:-.env.local}"
docker compose --env-file "$ENV_FILE" \
  -f docker-compose.yml -f scripts/sim/compose.yml \
  stop sftp mockserver
echo "==> ✅ 已停"
