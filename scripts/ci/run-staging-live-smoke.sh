#!/usr/bin/env bash
# 用途：调用统一回归入口执行 staging 升级与回滚 smoke。
# 用法：在仓库根目录执行 bash scripts/ci/run-staging-live-smoke.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# 封装 staging 的真实升级与回滚 smoke 入口。
# 说明：
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
