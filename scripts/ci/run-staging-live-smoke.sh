#!/usr/bin/env bash
# =========================================================
# run-staging-live-smoke.sh - staging live rollout / rollback smoke wrapper
# Notes:
# 1) 直接复用 run-full-regression.sh 的 deploy smoke + deployment verification。
# 2) 默认启用 live staging 执行；可通过环境变量显式覆盖。
# =========================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

export BATCH_DEPLOY_SMOKE_ENABLE_LIVE="${BATCH_DEPLOY_SMOKE_ENABLE_LIVE:-true}"
export BATCH_DEPLOY_VERIFICATION_ENABLE_LIVE="${BATCH_DEPLOY_VERIFICATION_ENABLE_LIVE:-true}"

exec bash "$ROOT_DIR/scripts/ci/run-full-regression.sh" \
  --skip-default-tests \
  --skip-it-suite \
  --with-deploy-smoke \
  --with-deployment-verification \
  "$@"
