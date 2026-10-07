#!/usr/bin/env bash
# Java 架构/约定守卫统一入口：供本地 pre-push 与 PR/Full/Staging Gate 共用。
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
cd "$ROOT"

MODULES="batch-test-support,batch-orchestrator,batch-trigger,batch-worker/core,batch-worker/import,batch-worker/export,batch-worker/process,batch-worker/dispatch,batch-worker/atomic,batch-console-api,batch-e2e-tests"

python3 scripts/ci/check-java-governance-test-coverage.py
./mvnw test -pl "$MODULES" -am \
  -Dtest='*ArchTest,*ConventionTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dspotless.check.skip=true -Dpmd.skip=true -fae -B
python3 scripts/ci/check-java-governance-test-coverage.py --verify-reports
