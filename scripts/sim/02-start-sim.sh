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

echo "==> 等 healthy/ready(30s)..."
for c in sftp; do
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
# mockserver 镜像的 healthcheck 依赖 /bin/sh,当前镜像没有 shell,会误报 unhealthy。
# 这里以 host 端口真实 HTTP readiness 为准。
sm_port="${MOCKSERVER_HOST_PORT:-11080}"
code=""
for i in $(seq 1 30); do
  code=$(curl -s --max-time 5 --connect-timeout 2 -o /dev/null -w "%{http_code}" -X PUT "http://localhost:${sm_port}/mockserver/status" 2>/dev/null || true)
  if [[ "$code" == "200" ]]; then
    echo "  ✓ mockserver HTTP ready"
    break
  fi
  sleep 1
done
if [[ "$code" != "200" ]]; then
  echo "  ✗ mockserver 30s 未 ready(last HTTP=$code)" >&2
  exit 1
fi
echo "  mockserver status: HTTP $code"
stubs=$(curl -s --max-time 30 --connect-timeout 5 -X PUT "http://localhost:${sm_port}/mockserver/retrieve?type=ACTIVE_EXPECTATIONS&format=JSON" 2>/dev/null \
  | python3 -c "import json,sys;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
echo "  mockserver stubs loaded: $stubs"

echo "==> ✅ 模拟器 UP:sftp(host:${SFTP_HOST_PORT:-12222}) mockserver(host:${MOCKSERVER_HOST_PORT:-11080})"
