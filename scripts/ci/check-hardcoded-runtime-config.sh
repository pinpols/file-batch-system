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

assert_no_matches \
  'PostgreSQL 测试容器必须通过 TestPostgresContainers 创建' \
  'new[[:space:]]+PostgreSQLContainer' \
  'batch-test-support/src/main/**/*.java' '*/src/test/**/*.java' \
  ':!batch-test-support/src/main/java/io/github/pinpols/batch/testing/TestPostgresContainers.java'

assert_no_matches \
  'Java 测试禁止绕过统一 PG 工厂写死镜像或凭据' \
  'DockerImageName\.parse\("postgres:[0-9]+|withUsername\("batch_user"\)|withPassword\("batch_pass_123"\)' \
  'batch-test-support/src/main/**/*.java' '*/src/test/**/*.java' \
  ':!batch-test-support/src/main/java/io/github/pinpols/batch/testing/TestPostgresContainers.java'

assert_no_matches \
  'Kafka 测试容器必须通过 TestKafkaContainers 创建' \
  'new[[:space:]]+KafkaContainer' \
  'batch-test-support/src/main/**/*.java' '*/src/test/**/*.java' \
  ':!batch-test-support/src/main/java/io/github/pinpols/batch/testing/TestKafkaContainers.java'

assert_no_matches \
  'MinIO 测试容器必须通过 TestObjectStoreContainers 创建' \
  'new[[:space:]]+ObjectStoreContainer' \
  'batch-test-support/src/main/**/*.java' '*/src/test/**/*.java' \
  ':!batch-test-support/src/main/java/io/github/pinpols/batch/testing/TestObjectStoreContainers.java'

assert_no_matches \
  'Java 测试禁止绕过统一 Kafka、Valkey、MinIO 工厂绑定镜像' \
  'DockerImageName\.parse\(TestContainerImages\.(KAFKA|VALKEY|MINIO)\)' \
  'batch-test-support/src/main/**/*.java' '*/src/test/**/*.java' \
  ':!batch-test-support/src/main/java/io/github/pinpols/batch/testing/TestKafkaContainers.java' \
  ':!batch-test-support/src/main/java/io/github/pinpols/batch/testing/TestValkeyContainers.java' \
  ':!batch-test-support/src/main/java/io/github/pinpols/batch/testing/ObjectStoreContainer.java'

assert_no_matches \
  'worker 并发配置键与默认值只能由 WorkerRuntimeConfiguration 维护' \
  'batch\.worker\.max-concurrent-tasks(:8)?' \
  'batch-worker/*/src/main/**/*.java' \
  ':!batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/config/WorkerRuntimeConfiguration.java'

assert_no_matches \
  '已有枚举的 trigger/worker 状态禁止恢复字符串比较' \
  '"(PENDING|PROCESSING|ACCEPTED|WAITING|REJECTED|LAUNCHED|FORWARD_FAILED|GIVE_UP|CRON|FIXED_RATE|DECOMMISSIONED)"\.equalsIgnoreCase' \
  'batch-trigger/src/main/**/*.java' 'batch-worker/core/src/main/**/*.java'

assert_no_matches \
  'Shell 运行时默认值必须引用 scripts/lib/runtime-defaults.sh' \
  '^[[:space:]]*[^#[:space:]].*(batch-postgres-primary|batch_pass_123|batch_user([^[:alnum:]_]|$)|batch_platform([^[:alnum:]_]|$)|batch_business([^[:alnum:]_]|$)|kafka:29092|minioadmin123|/opt/kafka/bin|localhost:(15432|19092|19000|16379))' \
  'scripts/**/*.sh' 'load-tests/scripts/*.sh' \
  ':!scripts/lib/runtime-defaults.sh' \
  ':!scripts/ci/check-hardcoded-runtime-config.sh' \
  ':!scripts/ci/check-postgres-client-fallback.sh'

while IFS= read -r test_file; do
  if grep -q '@SpringBootTest' "$test_file" && ! grep -q 'extends AbstractIntegrationTest' "$test_file"; then
    echo "FAIL: Spring Boot 集成测试使用统一基础设施时必须继承 AbstractIntegrationTest" >&2
    echo "$test_file" >&2
    failures=$((failures + 1))
  fi
done < <(git grep -lE 'Test(Postgres|Kafka|Valkey|ObjectStore)Containers' -- \
  '*/src/test/**/*.java' || true)

if [[ -e docker-compose.test.yml ]]; then
  echo 'FAIL: docker-compose.test.yml 已废弃，请使用 deploy/docker/compose/test.yml' >&2
  failures=$((failures + 1))
fi

if ((failures > 0)); then
  echo "硬编码运行配置契约失败：${failures} 类问题" >&2
  exit 1
fi

echo '✅ 硬编码运行配置契约通过'
