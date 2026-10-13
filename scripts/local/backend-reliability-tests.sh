#!/usr/bin/env bash
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
