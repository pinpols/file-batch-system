#!/usr/bin/env bash
# =========================================================
# 07-spi-load.sh:触发 ADR-029 专用 SPI worker 的 4 类原子任务(shell/sql/stored-proc/http)
#
# 纯 curl 循环,不依赖 k6。模拟混合原子任务负载,验证 SPI worker 在 sim 环境下端到端可用。
#
# 前置:
#   - platform_seed.sql 的 spi_*_demo job 已 seed(job_type='SPI',默认 tenant=default-tenant)
#   - batch-worker-spi 进程在跑(start-all.sh 已含 worker-spi)
#   - 该 worker 上对应 SPI 执行器已 enable(默认全关:batch.worker.executors.<type>.enabled=true)
#     stored-proc 还需目标过程存在 + schema/白名单放行;http 需出口域名白名单放行
#
# 用法:
#   bash scripts/sim/07-spi-load.sh                 # 默认 5 轮 × 4 job
#   ROUNDS=20 bash scripts/sim/07-spi-load.sh       # 20 轮
#   SPI_TENANT=default-tenant bash scripts/sim/07-spi-load.sh
#   ONLY=spi_sql_demo bash scripts/sim/07-spi-load.sh   # 只触某一个
# =========================================================
set -uo pipefail

TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:18081}"
ROUNDS="${ROUNDS:-5}"
SPI_TENANT="${SPI_TENANT:-default-tenant}"
BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
ONLY="${ONLY:-}"

# seed 里的 4 个 SPI demo job(default_params 自带执行器协议,触发无需带 params)
SPI_JOBS=(spi_shell_demo spi_sql_demo spi_stored_proc_demo spi_http_demo)
[[ -n "$ONLY" ]] && SPI_JOBS=("$ONLY")

total=0; succ=0
echo "==> SPI 原子任务负载:${ROUNDS} 轮 × ${#SPI_JOBS[@]} job(tenant=${SPI_TENANT})"
START=$(date +%s)

for round in $(seq 1 "$ROUNDS"); do
  for job in "${SPI_JOBS[@]}"; do
    total=$((total + 1))
    req_id="sim-spi-${round}-${job}-$(date +%s%N | tail -c 8)"
    resp=$(curl -sf -X POST \
      -H "Content-Type: application/json" \
      -H "X-Tenant-Id: $SPI_TENANT" \
      -H "X-Request-Id: $req_id" \
      "$TRIGGER_BASE/api/triggers/launch" \
      -d "{\"tenantId\":\"$SPI_TENANT\",\"jobCode\":\"$job\",\"triggerType\":\"API\",\"bizDate\":\"$BIZ_DATE\",\"requestId\":\"$req_id\"}" 2>&1)
    if echo "$resp" | grep -qE '"code"\s*:\s*"(SUCCESS|OK)"'; then
      succ=$((succ + 1))
      printf "."
    else
      printf "x"
    fi
  done
done

echo
ELAPSED=$(( $(date +%s) - START ))
echo "==> 完成:触发 ${succ}/${total} 成功,用时 ${ELAPSED}s"
echo "    (这里只统计 launch 受理;原子任务执行终态请查 job_task 或 console)"
[[ "$succ" -eq "$total" ]] || { echo "!! 有触发失败,检查 SPI worker 是否在跑 + 执行器是否 enable"; exit 1; }
