#!/usr/bin/env bash
# =========================================================
# inspect-observability.sh - 服务巡检：健康检查、指标、Kafka lag 和基础设施 exporter 指标
# Notes:
# 1) 检查各服务的 /actuator/health。
# 2) 检查关键 Prometheus 指标、Redis/Postgres/Kafka/MinIO exporter 指标和 Kafka consumer lag。
# =========================================================
# 使用方法：
#   BATCH_OBSERVABILITY_BASE_URLS=http://localhost:18080,http://localhost:18081,http://localhost:18082 \
#   BATCH_OBSERVABILITY_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
#     bash scripts/ops/inspect-observability.sh

set -euo pipefail

BASE_URLS="${BATCH_OBSERVABILITY_BASE_URLS:-http://localhost:18080,http://localhost:18081,http://localhost:18082}"
REQUIRED_METRICS="${BATCH_OBSERVABILITY_REQUIRED_METRICS:-batch_alert_events_total,batch_job_sla_violation_count,batch_dispatch_deliveries_total,batch_dispatch_circuits_open,http_server_requests_seconds_count}"
KAFKA_BOOTSTRAP_SERVERS="${BATCH_OBSERVABILITY_KAFKA_BOOTSTRAP_SERVERS:-${BATCH_KAFKA_BOOTSTRAP_SERVERS:-}}"
KAFKA_GROUPS="${BATCH_OBSERVABILITY_KAFKA_GROUPS:-batch-worker-import,batch-worker-export,batch-worker-process,batch-worker-dispatch,batch-worker-spi}"
KAFKA_BIN_DIR="${BATCH_OBSERVABILITY_KAFKA_BIN_DIR:-}"
KAFKA_LAG_THRESHOLD="${BATCH_OBSERVABILITY_KAFKA_LAG_THRESHOLD:-1000}"
EXTRA_ENDPOINTS="${BATCH_OBSERVABILITY_EXTRA_ENDPOINTS:-http://localhost:19121/metrics|redis_connected_clients,redis_memory_used_bytes;http://localhost:19187/metrics|pg_up,pg_stat_database_numbackends;http://localhost:19308/metrics|kafka_brokers;http://localhost:19100/metrics|node_load1,node_memory_MemAvailable_bytes,node_filesystem_size_bytes,node_network_receive_bytes_total;http://localhost:19101/metrics|container_cpu_usage_seconds_total,container_memory_working_set_bytes;http://localhost:19000/minio/v2/metrics/cluster|minio_cluster_nodes_offline_total}"
PROMETHEUS_BASE_URL="${BATCH_OBSERVABILITY_PROMETHEUS_BASE_URL:-http://localhost:19090}"
PROMETHEUS_TARGET_JOBS="${BATCH_OBSERVABILITY_PROMETHEUS_TARGET_JOBS:-batch-console-api,batch-trigger,batch-orchestrator,batch-worker-import,batch-worker-export,batch-worker-process,batch-worker-dispatch,batch-worker-spi,redis-exporter,postgres-exporter,kafka-exporter,minio,node-exporter,cadvisor,otel-collector}"

failures=0

log() {
  printf '%s\n' "$*"
}

fail() {
  log "FAIL: $*"
  failures=$((failures + 1))
}

check_health() {
  local base_url="$1"
  local health_body
  if ! health_body="$(curl -fsS "${base_url%/}/actuator/health")"; then
    fail "${base_url}: health endpoint unreachable"
    return
  fi
  if [[ "${health_body}" != *'"status":"UP"'* && "${health_body}" != *'"status": "UP"'* ]]; then
    fail "${base_url}: health status is not UP"
  else
    log "OK: ${base_url} health"
  fi
}

check_metrics() {
  local base_url="$1"
  local metrics_body
  if ! metrics_body="$(curl -fsS "${base_url%/}/actuator/prometheus")"; then
    fail "${base_url}: prometheus endpoint unreachable"
    return
  fi
  for metric in ${REQUIRED_METRICS//,/ }; do
    if ! grep -Eq "^${metric}([{[:space:]]|$)" <<<"${metrics_body}"; then
      fail "${base_url}: missing metric ${metric}"
    fi
  done
  log "OK: ${base_url} metrics"
}

check_kafka_lag() {
  if [[ -z "${KAFKA_BOOTSTRAP_SERVERS}" || -z "${KAFKA_GROUPS}" ]]; then
    log "SKIP: kafka lag check (bootstrap servers or groups not set)"
    return
  fi

  local kafka_consumer_groups=""
  if [[ -n "${KAFKA_BIN_DIR}" && -x "${KAFKA_BIN_DIR%/}/kafka-consumer-groups.sh" ]]; then
    kafka_consumer_groups="${KAFKA_BIN_DIR%/}/kafka-consumer-groups.sh"
  elif command -v kafka-consumer-groups.sh >/dev/null 2>&1; then
    kafka_consumer_groups="$(command -v kafka-consumer-groups.sh)"
  else
    log "SKIP: kafka lag check (kafka-consumer-groups.sh not found)"
    return
  fi

  IFS=',' read -r -a groups <<<"${KAFKA_GROUPS}"
  for group in "${groups[@]}"; do
    local output total_lag
    if ! output="$("${kafka_consumer_groups}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --describe --group "${group}" 2>&1)"; then
      fail "kafka group ${group}: describe failed"
      log "${output}"
      continue
    fi
    total_lag="$(awk 'NR > 1 && $5 ~ /^[0-9]+$/ {sum += $5} END {print sum + 0}' <<<"${output}")"
    if [[ "${total_lag}" -gt "${KAFKA_LAG_THRESHOLD}" ]]; then
      fail "kafka group ${group}: lag ${total_lag} exceeds threshold ${KAFKA_LAG_THRESHOLD}"
    else
      log "OK: kafka group ${group} lag ${total_lag}"
    fi
  done
}

check_extra_endpoints() {
  IFS=';' read -r -a targets <<<"${EXTRA_ENDPOINTS}"
  for target in "${targets[@]}"; do
    local url metrics body metric_list
    url="${target%%|*}"
    metric_list="${target#*|}"
    if [[ "${url}" == "${metric_list}" ]]; then
      fail "extra endpoint config missing metric list: ${url}"
      continue
    fi

    if ! body="$(curl -fsS "${url}")"; then
      fail "${url}: metrics endpoint unreachable"
      continue
    fi

    IFS=';' read -r -a metric_groups <<<"${metric_list//,/;}"
    for metric in "${metric_groups[@]}"; do
      if ! grep -Eq "^${metric}([{[:space:]]|$)" <<<"${body}"; then
        fail "${url}: missing metric ${metric}"
      fi
    done
    log "OK: ${url}"
  done
}

check_prometheus_targets() {
  local body jobs_csv
  if ! body="$(curl -fsS "${PROMETHEUS_BASE_URL%/}/api/v1/targets?state=active")"; then
    fail "prometheus targets api unreachable: ${PROMETHEUS_BASE_URL}"
    return
  fi

  jobs_csv="$(python3 -c 'import json,sys; data=json.load(sys.stdin); print(",".join(sorted({t["labels"].get("job","") + ":" + t.get("health","") for t in data.get("data",{}).get("activeTargets",[])})))' <<<"${body}" 2>/dev/null || true)"
  if [[ -z "${jobs_csv}" ]]; then
    fail "prometheus targets api returned no active targets"
    return
  fi

  IFS=',' read -r -a expected_jobs <<<"${PROMETHEUS_TARGET_JOBS}"
  for job in "${expected_jobs[@]}"; do
    if [[ "${jobs_csv}" != *"${job}:up"* ]]; then
      fail "prometheus target not up: ${job}"
    fi
  done
  log "OK: prometheus targets"
}

IFS=',' read -r -a urls <<<"${BASE_URLS}"
for url in "${urls[@]}"; do
  check_health "${url}"
  check_metrics "${url}"
done

check_kafka_lag
check_extra_endpoints
check_prometheus_targets

if [[ "${failures}" -gt 0 ]]; then
  log "Observability inspection failed with ${failures} issue(s)"
  exit 1
fi

log "Observability inspection passed"
