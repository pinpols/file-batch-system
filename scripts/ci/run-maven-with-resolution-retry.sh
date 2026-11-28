#!/usr/bin/env bash
# 用途：为 CI 中的 Maven 命令增加「仅针对依赖解析类瞬态失败」的重试，避免 Maven Central 抖动把测试分片判红。
# 用法：bash scripts/ci/run-maven-with-resolution-retry.sh -- ./mvnw test -pl batch-common -B
#       重试次数与退避可用 MAVEN_RESOLUTION_RETRY_ATTEMPTS / MAVEN_RESOLUTION_RETRY_BACKOFF_SECONDS 覆盖。
set -uo pipefail

if [[ "${1:-}" != "--" || $# -lt 2 ]]; then
    echo "usage: $0 -- <maven command...>" >&2
    exit 2
fi
shift

attempts="${MAVEN_RESOLUTION_RETRY_ATTEMPTS:-3}"
backoff_seconds="${MAVEN_RESOLUTION_RETRY_BACKOFF_SECONDS:-30}"

# 只匹配依赖/插件解析失败与传输层中断；编译错误、测试失败与业务断言必须立刻失败，不能被重试掩盖。
transient_pattern='Could not transfer artifact|Failed to read artifact descriptor|PluginResolutionException|Failed to collect dependencies|Could not resolve dependencies|Could not resolve artifact|Connection reset|Read timed out|502 Bad Gateway|503 Service Unavailable|504 Gateway Timeout'

log_file="$(mktemp)"
trap 'rm -f "$log_file"' EXIT

for attempt in $(seq 1 "$attempts"); do
    set +e
    "$@" 2>&1 | tee "$log_file"
    status="${PIPESTATUS[0]}"
    set -e
    if [[ "$status" -eq 0 ]]; then
        exit 0
    fi
    if ! grep -Eq "$transient_pattern" "$log_file"; then
        exit "$status"
    fi
    : >"$log_file"
    if [[ "$attempt" -ge "$attempts" ]]; then
        echo "依赖解析在 ${attempts} 次尝试后仍失败，放弃重试" >&2
        exit "$status"
    fi
    wait_seconds=$((backoff_seconds * attempt))
    echo "依赖解析类瞬态失败（第 ${attempt}/${attempts} 次），${wait_seconds}s 后重试" >&2
    sleep "$wait_seconds"
done
