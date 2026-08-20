#!/usr/bin/env bash
set -euo pipefail

# Maven Central 的临时 5xx 不应让完整 E2E 门禁在依赖安装阶段直接失败。
# 保持与 full/staging 原步骤相同的 reactor 范围，只对下载失败做有限重试。
attempts=3

for attempt in $(seq 1 "$attempts"); do
  printf 'Install upstream Maven modules (attempt %s/%s)\n' "$attempt" "$attempts"
  if ./mvnw -U install \
      -DskipTests \
      -pl '!batch-e2e-tests' \
      -B \
      -Dmaven.wagon.http.retryHandler.count=5 \
      -Dmaven.wagon.http.retryHandler.requestSentEnabled=true; then
    exit 0
  fi

  if [[ "$attempt" -lt "$attempts" ]]; then
    sleep $((attempt * 10))
  fi
done

echo "Failed to install upstream Maven modules after ${attempts} attempts." >&2
exit 1
