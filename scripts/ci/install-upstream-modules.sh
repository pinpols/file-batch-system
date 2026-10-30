#!/usr/bin/env bash
# 用途：安装 E2E 与治理测试依赖的上游 Maven 模块。
# 用法：在仓库根目录执行 bash scripts/ci/install-upstream-modules.sh；参数与前置环境见下方说明及所属 README。
set -euo pipefail

# Maven Central 的临时 5xx 不应让完整 E2E 门禁在依赖安装阶段直接失败。
# 保持与 full/staging 原步骤相同的 reactor 范围，只对下载失败做有限重试。
attempts=3

for attempt in $(seq 1 "$attempts"); do
  printf 'Install upstream Maven modules (attempt %s/%s)\n' "$attempt" "$attempts"
  if ./mvnw -U install \
      -DskipTests \
      -Dspring-boot.repackage.skip=true \
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
