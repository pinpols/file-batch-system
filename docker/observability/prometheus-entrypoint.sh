#!/bin/sh
# 根据 BATCH_DEPLOY_MODE 生成 Java 应用的 Prometheus targets 文件，然后启动 Prometheus。
# container 模式：使用容器名 + 容器端口（Docker 网络内直连）
# local 模式：使用 host.docker.internal + 宿主机映射端口
set -e

MODE="${BATCH_DEPLOY_MODE:-container}"
TARGET_DIR="/etc/prometheus/targets"
mkdir -p "$TARGET_DIR"

if [ "$MODE" = "local" ]; then
  cat > "$TARGET_DIR/app-targets.json" <<'EOF'
[
  {"targets": ["host.docker.internal:18080"], "labels": {"job": "batch-console-api", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["host.docker.internal:18081"], "labels": {"job": "batch-trigger", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["host.docker.internal:18082"], "labels": {"job": "batch-orchestrator", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["host.docker.internal:18083"], "labels": {"job": "batch-worker-import", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["host.docker.internal:18084"], "labels": {"job": "batch-worker-export", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["host.docker.internal:18085"], "labels": {"job": "batch-worker-dispatch", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["host.docker.internal:18086"], "labels": {"job": "batch-worker-process", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["host.docker.internal:18087"], "labels": {"job": "batch-worker-spi", "__metrics_path__": "/actuator/prometheus"}}
]
EOF
else
  cat > "$TARGET_DIR/app-targets.json" <<'EOF'
[
  {"targets": ["console-api:8080"], "labels": {"job": "batch-console-api", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["trigger:8081"], "labels": {"job": "batch-trigger", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["orchestrator:8082"], "labels": {"job": "batch-orchestrator", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["worker-import:8083"], "labels": {"job": "batch-worker-import", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["worker-export:8084"], "labels": {"job": "batch-worker-export", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["worker-dispatch:8085"], "labels": {"job": "batch-worker-dispatch", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["worker-process:8086"], "labels": {"job": "batch-worker-process", "__metrics_path__": "/actuator/prometheus"}},
  {"targets": ["worker-spi:8087"], "labels": {"job": "batch-worker-spi", "__metrics_path__": "/actuator/prometheus"}}
]
EOF
fi

echo "[prometheus-entrypoint] BATCH_DEPLOY_MODE=$MODE — generated app-targets.json"

exec /bin/prometheus "$@"
