#!/usr/bin/env bash
# 防止本机路径、旧端口和镜像内部路径重新散落到运行配置。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

failures=0

assert_no_matches() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches
  if matches="$(git grep -nE "$pattern" -- "$@" 2>/dev/null)"; then
    echo "FAIL: $label" >&2
    printf '%s\n' "$matches" >&2
    failures=$((failures + 1))
  fi
}

assert_no_matches \
  '当前文档禁止提交个人绝对路径' \
  "/Users/|/var/folders/|[A-Za-z]:\\\\Users\\\\" \
  'docs/**' ':!docs/archive/**'

assert_no_matches \
  '运维脚本禁止使用已废弃的 8080/8082 本地端口' \
  'localhost:808(0|2)([^0-9]|$)' \
  'scripts/ops/*.sh'

assert_no_matches \
  '可运行入口和现行文档禁止使用旧 Console/Trigger/Orchestrator 端口' \
  'localhost:808(0|1|2)([^0-9]|$)' \
  'Makefile' 'load-tests/pom.xml' 'load-tests/README.md' \
  'security-scan/**' 'scripts/ci/security-scan.sh' \
  'docs/runbook/orchestrator-statefulset-migration.md' \
  ':!security-scan/src/test/**'

assert_no_matches \
  'local profile 必须继承可覆盖的公共 Redis 地址' \
  '^[[:space:]]+(host: localhost|port: 16379)$' \
  'batch-*/src/main/resources/application-local.yml' \
  'batch-worker/*/src/main/resources/application-local.yml'

assert_no_matches \
  '脚本必须通过 KAFKA_CONTAINER_BIN_DIR 调用 Kafka 容器 CLI' \
  '/opt/kafka/bin/' \
  'scripts/**/*.sh' 'load-tests/scripts/*.sh' \
  ':!scripts/ci/check-hardcoded-runtime-config.sh'

assert_no_matches \
  'SonarQube 镜像必须固定版本或 digest' \
  'sonarqube:community([^@[:alnum:]._-]|$)' \
  'scripts/dev/sonar-scan.sh'

if [[ -e docker-compose.test.yml ]]; then
  echo 'FAIL: docker-compose.test.yml 已废弃，请使用 deploy/docker/compose/test.yml' >&2
  failures=$((failures + 1))
fi

if ((failures > 0)); then
  echo "硬编码运行配置契约失败：${failures} 类问题" >&2
  exit 1
fi

echo '硬编码运行配置契约通过'
