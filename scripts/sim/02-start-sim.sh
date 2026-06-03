#!/usr/bin/env bash
# =========================================================
# 02-start-sim.sh:起模拟下游容器(sftp + mockserver)
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
ENV_FILE="${COMPOSE_ENV_FILE:-.env.local}"

echo "==> docker compose up sftp + mockserver(network: batch-network)"
docker compose --env-file "$ENV_FILE" \
  -f docker-compose.yml -f scripts/sim/compose.yml up -d sftp mockserver

echo "==> 等 healthy(30s)..."
for c in sftp mockserver; do
  healthy=0
  for i in $(seq 1 30); do
    status=$(docker inspect "$c" --format '{{.State.Health.Status}}' 2>/dev/null || echo "missing")
    if [[ "$status" == "healthy" ]]; then echo "  ✓ $c"; healthy=1; break; fi
    sleep 1
  done
  if [[ "$healthy" -ne 1 ]]; then
    echo "  ✗ $c 30s 未 healthy(last status=$status)" >&2
    exit 1
  fi
done

echo "==> 验证 endpoint 联通"
docker exec sftp /bin/sh -c "ls -d /home/ta/inbound /home/tb/inbound /home/tc/inbound" 2>&1 | head
# mockserver 镜像没有 curl,从 host 探(端口已发布)。
sm_port="${MOCKSERVER_HOST_PORT:-11080}"
code=$(curl -s --max-time 30 --connect-timeout 5 -o /dev/null -w "%{http_code}" -X PUT "http://localhost:${sm_port}/mockserver/status" 2>&1)
echo "  mockserver status: HTTP $code"
stubs=$(curl -s --max-time 30 --connect-timeout 5 -X PUT "http://localhost:${sm_port}/mockserver/retrieve?type=ACTIVE_EXPECTATIONS&format=JSON" 2>/dev/null \
  | python3 -c "import json,sys;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
echo "  mockserver stubs loaded: $stubs"

echo "==> ✅ 模拟器 UP:sftp(host:${SFTP_HOST_PORT:-12222}) mockserver(host:${MOCKSERVER_HOST_PORT:-11080})"
