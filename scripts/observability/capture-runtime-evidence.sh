#!/usr/bin/env bash
set -euo pipefail

# 该脚本只采集证据，不启动、停止或重启任何服务。
# 地址全部由环境变量提供，既可用于 Docker，也可用于 staging 或宿主机部署。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EVIDENCE_DIR="${EVIDENCE_DIR:-${ROOT_DIR}/artifacts/observability/$(date -u +%Y%m%dT%H%M%SZ)}"
CONSOLE_URL="${CONSOLE_URL:-http://localhost:18080}"
ORCHESTRATOR_URL="${ORCHESTRATOR_URL:-http://localhost:18082}"
OTEL_COLLECTOR_METRICS_URL="${OTEL_COLLECTOR_METRICS_URL:-http://localhost:8888/metrics}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:19090}"
TRACEPARENT="${TRACEPARENT:-00-$(printf '%032d' 1)-$(printf '%016d' 1)-01}"

mkdir -p "${EVIDENCE_DIR}"

need_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf '缺少命令: %s\n' "$1" >&2
    exit 2
  }
}

capture_url() {
  local name="$1"
  local url="$2"
  local output="${EVIDENCE_DIR}/${name}.body"
  local headers="${EVIDENCE_DIR}/${name}.headers"
  curl --fail --silent --show-error --connect-timeout "${CURL_CONNECT_TIMEOUT_SECONDS:-5}" \
    --max-time "${CURL_MAX_TIME_SECONDS:-20}" \
    -H "traceparent: ${TRACEPARENT}" \
    -D "${headers}" -o "${output}" "${url}"
  printf '%s\n' "${url}" > "${EVIDENCE_DIR}/${name}.url"
}

need_command curl

capture_url console-health "${CONSOLE_URL}/actuator/health"
capture_url console-metrics "${CONSOLE_URL}/actuator/prometheus"
capture_url orchestrator-health "${ORCHESTRATOR_URL}/actuator/health"
capture_url collector-metrics "${OTEL_COLLECTOR_METRICS_URL}"

# Prometheus 查询保存当前容量指标快照；没有该指标时明确失败，避免空报告被误判为通过。
for query in \
  'batch_console_maintenance_shared_state_available' \
  'batch_console_replica_replay_lag_seconds' \
  'batch_orchestrator_scheduler_queue_oldest_wait_seconds' \
  'batch_worker_semaphore_available' \
  'batch_outbox_pending_events' \
  'batch_dead_letter_tasks_pending'; do
  encoded_query="$(printf '%s' "${query}" | od -An -tx1 | tr -d ' \n' | sed 's/../%&/g')"
  capture_url "prometheus-${query}" "${PROMETHEUS_URL}/api/v1/query?query=${encoded_query}"
done

cat > "${EVIDENCE_DIR}/README.txt" <<EOF
采集时间(UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)
Traceparent: ${TRACEPARENT}
说明: 本目录只证明端点可达、指标可抓取以及指定容量指标可被 Prometheus 查询。
说明: 业务请求的 traceId、Tempo/Loki 链路和告警触发仍需按 runbook 使用真实请求补证。
EOF

printf '运行证据已写入: %s\n' "${EVIDENCE_DIR}"
