#!/bin/sh
# Generate local file discovery targets, then start Prometheus.
set -eu

generate_targets() {
mode=${BATCH_DEPLOY_MODE:-container}
target_dir=/prometheus/targets
mkdir -p "$target_dir"

validate_port() {
  case "$2" in
    ""|*[!0-9]*)
      echo "Invalid scrape port for $1: $2" >&2
      exit 2
      ;;
  esac
}

case "$mode" in
  container)
    set -- \
      console-api 18080 batch-console-api \
      trigger 18081 batch-trigger \
      orchestrator 18082 batch-orchestrator \
      worker-import 18083 batch-worker-import \
      worker-export 18084 batch-worker-export \
      worker-dispatch 18085 batch-worker-dispatch \
      worker-process 18086 batch-worker-process \
      worker-atomic 18087 batch-worker-atomic
    ;;
  local)
    host=${PROMETHEUS_HOST_TARGET:-host.docker.internal}
    case "$host" in
      ""|*[!A-Za-z0-9.-]*)
        echo "Invalid PROMETHEUS_HOST_TARGET: expected a hostname or IPv4 address" >&2
        exit 2
        ;;
    esac
    validate_port batch-console-api "${CONSOLE_API_PORT:-18080}"
    validate_port batch-trigger "${TRIGGER_PORT:-18081}"
    validate_port batch-orchestrator "${ORCHESTRATOR_PORT:-18082}"
    validate_port batch-worker-import "${WORKER_IMPORT_PORT:-18083}"
    validate_port batch-worker-export "${WORKER_EXPORT_PORT:-18084}"
    validate_port batch-worker-dispatch "${WORKER_DISPATCH_PORT:-18085}"
    validate_port batch-worker-process "${WORKER_PROCESS_PORT:-18086}"
    validate_port batch-worker-atomic "${WORKER_ATOMIC_PORT:-18087}"
    set -- \
      "$host" "${CONSOLE_API_PORT:-18080}" batch-console-api \
      "$host" "${TRIGGER_PORT:-18081}" batch-trigger \
      "$host" "${ORCHESTRATOR_PORT:-18082}" batch-orchestrator \
      "$host" "${WORKER_IMPORT_PORT:-18083}" batch-worker-import \
      "$host" "${WORKER_EXPORT_PORT:-18084}" batch-worker-export \
      "$host" "${WORKER_DISPATCH_PORT:-18085}" batch-worker-dispatch \
      "$host" "${WORKER_PROCESS_PORT:-18086}" batch-worker-process \
      "$host" "${WORKER_ATOMIC_PORT:-18087}" batch-worker-atomic
    ;;
  *)
    echo "Unsupported BATCH_DEPLOY_MODE '$mode'; expected container or local" >&2
    exit 2
    ;;
esac

tmp_file="$target_dir/app-targets.json.tmp"
printf '[\n' > "$tmp_file"
first=true
while [ "$#" -gt 0 ]; do
  host=$1
  port=$2
  job=$3
  shift 3
  if [ "$first" = true ]; then
    first=false
  else
    printf ',\n' >> "$tmp_file"
  fi
  printf '  {"targets":["%s:%s"],"labels":{"job":"%s","__metrics_path__":"/actuator/prometheus"}}' \
    "$host" "$port" "$job" >> "$tmp_file"
done
printf '\n]\n' >> "$tmp_file"
mv "$tmp_file" "$target_dir/app-targets.json"

case "${PROMETHEUS_ENABLE_HOST_METRICS:-false}" in
  true) printf '[{"targets":["node-exporter:9100"]}]\n' > "$target_dir/node-exporter-targets.json" ;;
  false) printf '[]\n' > "$target_dir/node-exporter-targets.json" ;;
  *) echo "PROMETHEUS_ENABLE_HOST_METRICS must be true or false" >&2; exit 2 ;;
esac

case "${PROMETHEUS_ENABLE_CONTAINER_METRICS:-false}" in
  true) printf '[{"targets":["cadvisor:8080"]}]\n' > "$target_dir/cadvisor-targets.json" ;;
  false) printf '[]\n' > "$target_dir/cadvisor-targets.json" ;;
  *) echo "PROMETHEUS_ENABLE_CONTAINER_METRICS must be true or false" >&2; exit 2 ;;
esac
}

generate_targets
echo "[prometheus-entrypoint] BATCH_DEPLOY_MODE=$mode — generated app-targets.json"
exec /bin/prometheus "$@"
