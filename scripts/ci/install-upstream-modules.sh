#!/usr/bin/env bash
# 用途：安装 E2E 与治理测试依赖的上游 Maven 模块。
# 用法：在仓库根目录执行 bash scripts/ci/install-upstream-modules.sh；参数与前置环境见下方说明及所属 README。
set -euo pipefail

# Maven Central 的临时 5xx 不应让完整 E2E 门禁在依赖安装阶段直接失败。
# 保持与 full/staging 原步骤相同的 reactor 范围，只对下载失败做有限重试。
# 恢复用例离线打包 worker 前需预先解析 Spring Boot 插件依赖。
# 解析失败会留下 *.lastUpdated 负缓存；不删掉的话下一轮会在本地否定缓存上立刻失败，不再访问 Central。
attempts=4

clear_negative_resolution_cache() {
  local repository="${HOME}/.m2/repository"
  [[ -d "$repository" ]] || return 0
  # 失败解析会留下 *.lastUpdated 负缓存；即使同目录已有 pom、缺 jar 时也一样。
  # Central 恢复后必须全部清掉，否则下一轮会直接命中负缓存、不再重新下载。
  find "$repository" -name '*.lastUpdated' -delete
}

for attempt in $(seq 1 "$attempts"); do
  if [[ "$attempt" -gt 1 ]]; then
    clear_negative_resolution_cache
  fi
  printf 'Install upstream Maven modules (attempt %s/%s)\n' "$attempt" "$attempts"
  if ./mvnw -U install \
      -DskipTests \
      -Dspring-boot.repackage.skip=true \
      -pl '!batch-e2e-tests' \
      -B \
      -Dmaven.wagon.http.retryHandler.count=5 \
      -Dmaven.wagon.http.retryHandler.requestSentEnabled=true &&
      ./mvnw -B -q -ntp -pl batch-worker/dispatch spring-boot:help; then
    exit 0
  fi

  if [[ "$attempt" -lt "$attempts" ]]; then
    sleep $((attempt * 15))
  fi
done

echo "Failed to install upstream Maven modules after ${attempts} attempts." >&2
exit 1
