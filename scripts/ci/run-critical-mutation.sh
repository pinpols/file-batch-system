#!/usr/bin/env bash
# 用途：对关键状态转换与生命周期映射运行 PIT 变异测试。
# 用法：在仓库根目录执行 bash scripts/ci/run-critical-mutation.sh；参数与前置环境见下方说明及所属 README。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

./mvnw -B -pl batch-orchestrator -am -DskipTests install

run_target() {
  local module="$1"
  local target_class="$2"
  local target_test="$3"
  ./mvnw -B -Pmutation-critical -pl "$module" test-compile \
    org.pitest:pitest-maven:mutationCoverage \
    -DtargetClasses="$target_class" \
    -DtargetTests="$target_test"
}

run_target \
  batch-common \
  io.github.pinpols.batch.common.utils.FileStateMachine \
  io.github.pinpols.batch.common.utils.FileStateMachineTest

run_target \
  batch-orchestrator \
  io.github.pinpols.batch.orchestrator.infrastructure.statemachine.DefaultLifecycleEventMapper \
  io.github.pinpols.batch.orchestrator.infrastructure.statemachine.DefaultLifecycleEventMapperTest
