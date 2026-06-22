#!/usr/bin/env bash
# =========================================================
# analyze-soak.sh — 解析 JFR + jcmd 输出,生成 markdown soak 报告
#
# 报告 6 维度(对应 plan 报告项):
#   1) 内存:Heap / Eden / Old / Metaspace 趋势(jfr summary jdk.GCHeapSummary)
#   2) 线程:线程数 / 阻塞时长(jdk.ThreadCPULoad)
#   3) 连接池:Hikari active / idle / wait(actuator 历史采样)
#   4) GC:Full GC 次数 + 累计停顿(jdk.GarbageCollection)
#   5) 业务:job_instance 完成数 + P50/P95/P99(psql)
#   6) 跨日:batch_day_instance 状态翻转(psql)
#
# 输出:logs/soak/soak-report-${SOAK_RUN_ID}.md
# =========================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOAK_LOG_DIR="${SOAK_LOG_DIR:-$ROOT_DIR/logs/soak}"
SOAK_RUN_ID="${SOAK_RUN_ID:-soak-unknown}"
REPORT="$SOAK_LOG_DIR/soak-report-${SOAK_RUN_ID}.md"

export PGPASSWORD

psql_q() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -At -v ON_ERROR_STOP=1 "$@" 2>/dev/null || echo "(query failed)"
}

echo "==> analyze-soak: 生成 $REPORT"

{
  echo "# Soak Report — ${SOAK_RUN_ID}"
  echo
  echo "- Generated: $(date -u +%FT%TZ)"
  echo "- Log dir: \`${SOAK_LOG_DIR}\`"
  echo

  # === 1. 内存 ===
  echo "## 1. 内存(Heap / Eden / Old / Metaspace)"
  echo
  shopt -s nullglob
  jfrs=("$SOAK_LOG_DIR"/jvm-${SOAK_RUN_ID}-*.jfr)
  if (( ${#jfrs[@]} == 0 )); then
    echo "_(no JFR file found,跳过;若 JVM 启动参数已注入应在运行结束/duration 到期后产出)_"
  else
    for jfr in "${jfrs[@]}"; do
      echo "### $(basename "$jfr")"
      echo '```text'
      if command -v jfr >/dev/null 2>&1; then
        jfr summary "$jfr" 2>&1 | grep -E "jdk\.(GCHeapSummary|MetaspaceSummary)" | head -10 || \
          echo "(no GCHeapSummary in summary)"
      else
        echo "(jfr CLI not available — 用 JDK 提供的 'jfr summary $jfr')"
      fi
      echo '```'
    done
  fi
  echo

  # === 2. 线程 ===
  echo "## 2. 线程"
  echo '```text'
  if (( ${#jfrs[@]} > 0 )) && command -v jfr >/dev/null 2>&1; then
    for jfr in "${jfrs[@]}"; do
      echo "-- $(basename "$jfr") --"
      jfr summary "$jfr" 2>&1 | grep -E "jdk\.(ThreadCPULoad|ThreadStart|ThreadEnd|JavaMonitorWait)" | head -10
    done
  else
    echo "(JFR not available)"
  fi
  echo '```'
  echo

  # === 3. 连接池 ===
  echo "## 3. 连接池(Hikari)"
  echo
  echo "(monitor-soak.sh 轮询了 hikaricp.connections.active / idle / max;详见 monitor log)"
  echo '```text'
  grep -E "hikari" "$SOAK_LOG_DIR/${SOAK_RUN_ID}.monitor.log" 2>/dev/null | tail -20 || echo "(no hikari samples in monitor log)"
  echo '```'
  echo

  # === 4. GC ===
  echo "## 4. GC(Full GC 次数 / 累计停顿)"
  echo '```text'
  if (( ${#jfrs[@]} > 0 )) && command -v jfr >/dev/null 2>&1; then
    for jfr in "${jfrs[@]}"; do
      echo "-- $(basename "$jfr") --"
      jfr summary "$jfr" 2>&1 | grep -E "jdk\.(GarbageCollection|GCPhasePause|YoungGarbageCollection|OldGarbageCollection)" | head -10
    done
  else
    echo "(JFR not available)"
  fi
  echo '```'
  echo

  # === 5. 业务 ===
  echo "## 5. 业务(job_instance 完成数 + 延迟)"
  echo '```text'
  psql_q -c "
    select
      job_code,
      count(*) as total,
      count(*) filter (where instance_status='SUCCESS') as success,
      round(avg(extract(epoch from (finished_at - created_at))) filter (where finished_at is not null)::numeric, 3) as avg_s,
      round(percentile_cont(0.50) within group (order by extract(epoch from (finished_at - created_at))) filter (where finished_at is not null)::numeric, 3) as p50_s,
      round(percentile_cont(0.95) within group (order by extract(epoch from (finished_at - created_at))) filter (where finished_at is not null)::numeric, 3) as p95_s,
      round(percentile_cont(0.99) within group (order by extract(epoch from (finished_at - created_at))) filter (where finished_at is not null)::numeric, 3) as p99_s
    from batch.job_instance
    where params_snapshot::text like '%${SOAK_RUN_ID}%'
    group by job_code
    order by job_code;"
  echo '```'
  echo

  # === 6. 跨日 ===
  echo "## 6. 跨日(batch_day_instance 状态翻转)"
  echo '```text'
  psql_q -c "
    select tenant_id, biz_date, status, created_at, updated_at
    from batch.batch_day_instance
    where updated_at >= now() - interval '36 hours'
    order by biz_date, updated_at
    limit 50;" 2>/dev/null || echo "(batch_day_instance 不存在或无数据)"
  echo '```'
  echo
  echo "> 跨日时间偏移 依赖 \`-Dbatch.testing.clock-offset\`;当前 BatchDateTimeSupport 未读该属性。"
  echo "> 真正生效需在 Clock bean 上实现 offset 注入,见 docs/plans/r3-3-soak-tests.md 的阻塞说明。"
  echo

  # === 退出条件触发 ===
  echo "## Exit Condition"
  echo
  stop_file="$SOAK_LOG_DIR/${SOAK_RUN_ID}.stop"
  if [[ -s "$stop_file" ]]; then
    echo "**TRIGGERED** — 阈值越界,monitor 提前停车留现场。"
    echo
    echo '```text'
    cat "$stop_file"
    echo '```'
  else
    echo "_未触发,跑完正常退出(空 stop flag 仅作 monitor 停车信号)_"
  fi
} > "$REPORT"

echo "==> analyze-soak: 完成 $REPORT"
