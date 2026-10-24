#!/usr/bin/env bash
# 用途：本地与每日 CI 共用的隔离可靠性回归套件。
# 用法：在仓库根目录执行 bash scripts/local/backend-reliability-tests.sh；参数与前置环境见下方说明及所属 README。
# 本地与每日 CI 共用的隔离可靠性回归套件。
# 数据库、Kafka 与对象存储依赖均由 Testcontainers 管理。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

MODULES="batch-orchestrator,batch-worker/core,batch-worker/import,batch-worker/process,batch-worker/dispatch"
TESTS="BundleArrivalLauncherConcurrencyIntegrationTest,FileGovernanceIntegrationTest,OutboxPublishCircuitBreakerKafkaFailureIntegrationTest,ExactlyOnceCrashRecoveryIntegrationTest,ImportIngressScannerTest,ImportIngressScannerIntegrationTest,ImportPreprocessPipelineTest,PreprocessStepObjectLoadIntegrationTest,LoadStepCheckpointCrashResumeIntegrationTest,ProcessStageSkipCrashResumeIntegrationTest,RemoteFilesystemNasPathTest,AbstractTaskConsumerBackpressureTest,OutboxPublishCircuitBreakerTest"

exec ./mvnw -B -ntp -pl "$MODULES" -am \
  -Dtest="$TESTS" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
