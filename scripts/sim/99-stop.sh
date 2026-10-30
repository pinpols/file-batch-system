#!/usr/bin/env bash
# 用途：停模拟器容器(sftp + mockserver),保留 volume 让下次启动数据延续。
# 用法：在仓库根目录执行 bash scripts/sim/99-stop.sh；参数与前置环境见下方说明及所属 README。
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
