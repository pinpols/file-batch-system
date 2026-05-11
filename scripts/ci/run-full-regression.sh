#!/usr/bin/env bash
# =========================================================
# run-full-regression.sh - CI / staging 统一回归入口
# Notes:
# 1) 默认执行 Maven 默认测试（单元 + *IntegrationTest）和 E2E 套件（*E2eIT）。
# 2) 可选执行压测 smoke、部署 smoke、升级/回滚验证和巡检。
# 3) 支持在 `--` 之后透传额外 Maven 参数。
# =========================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-$HOME/.docker/run/docker.sock}"
export DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}"

RUN_DEFAULT_TESTS=true
RUN_IT_SUITE=true
RUN_LOAD_SMOKE=false
RUN_LOAD_CAPACITY=false
RUN_DEPLOY_SMOKE=false
RUN_DEPLOY_VERIFICATION=false
RUN_INSPECTION=false
RUN_STATIC_GATES=true
declare -a EXTRA_MVN_ARGS=()

current_step=""

DEFAULT_HELM_IMAGE="${BATCH_HELM_IMAGE:-alpine/helm:3.17.3}"
DEFAULT_LIVE_DEPLOY_TIMEOUT="${BATCH_DEPLOY_SMOKE_TIMEOUT:-10m}"
DEFAULT_LIVE_READINESS_TIMEOUT_SECONDS="${BATCH_DEPLOY_SMOKE_READINESS_TIMEOUT_SECONDS:-180}"
DEFAULT_VERIFICATION_RELEASE="${BATCH_DEPLOY_VERIFICATION_RELEASE:-batch-platform-verification}"
DEFAULT_VERIFICATION_NAMESPACE="${BATCH_DEPLOY_VERIFICATION_NAMESPACE:-batch-verification}"
DEFAULT_VERIFICATION_TIMEOUT="${BATCH_DEPLOY_VERIFICATION_TIMEOUT:-10m}"
DEFAULT_VERIFICATION_READINESS_TIMEOUT_SECONDS="${BATCH_DEPLOY_VERIFICATION_READINESS_TIMEOUT_SECONDS:-180}"

usage() {
  cat <<'EOF'
Usage:
  bash scripts/ci/run-full-regression.sh [options] [-- <extra maven args>]

Options:
  --skip-static-gates   Skip version/dependency/PMD/Spotless gates
  --skip-default-tests   Skip reactor default tests (*Test / *IntegrationTest)
  --skip-it-suite        Skip E2E suite (batch-e2e-tests, *E2eIT)
  --with-load-smoke      Run Gatling JobLaunchSimulation smoke
  --with-load-capacity   Run Gatling CapacityBaselineSimulation (~5-10 min, real ramp)
  --with-deploy-smoke    Run Helm lint/template deployment smoke
  --with-deployment-verification
                         Run upgrade / rollback verification smoke
  --with-inspection      Run scripts/ops/inspect-all.sh after tests
  --help                 Show this message

Examples:
  bash scripts/ci/run-full-regression.sh
  bash scripts/ci/run-full-regression.sh --with-load-smoke
  bash scripts/ci/run-full-regression.sh --with-deploy-smoke
  bash scripts/ci/run-full-regression.sh --with-deployment-verification
  bash scripts/ci/run-full-regression.sh -- --pl batch-trigger -am
EOF
}

banner() {
  local message="$1"
  printf '\n%s\n' "$(printf '=%.0s' {1..72})"
  printf '== %s\n' "$message"
  printf '%s\n' "$(printf '=%.0s' {1..72})"
}

run_mvn() {
  local -a cmd=(mvn -q "$@")
  if (( ${#EXTRA_MVN_ARGS[@]} > 0 )); then
    cmd+=("${EXTRA_MVN_ARGS[@]}")
  fi
  "${cmd[@]}"
}

run_step() {
  local label="$1"
  shift
  current_step="$label"
  banner "$label"
  "$@"
}

on_error() {
  local exit_code=$?
  if [[ -n "$current_step" ]]; then
    printf '\nFAILED: %s\n' "$current_step" >&2
  fi
  exit "$exit_code"
}

trap on_error ERR

resolve_docker_bin() {
  if command -v docker >/dev/null 2>&1; then
    command -v docker
    return 0
  fi
  for candidate in \
    /Applications/Docker.app/Contents/Resources/bin/docker \
    /opt/homebrew/bin/docker \
    /usr/local/bin/docker
  do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

resolve_kubectl_bin() {
  if command -v kubectl >/dev/null 2>&1; then
    command -v kubectl
    return 0
  fi
  for candidate in \
    /Applications/Docker.app/Contents/Resources/bin/kubectl \
    /opt/homebrew/bin/kubectl \
    /usr/local/bin/kubectl
  do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

run_helm() {
  if command -v helm >/dev/null 2>&1; then
    helm "$@"
    return 0
  fi

  local docker_bin
  docker_bin="$(resolve_docker_bin)" || {
    printf 'deploy smoke requires helm or docker, but neither is available.\n' >&2
    return 1
  }

  PATH="$(dirname "$docker_bin"):$PATH" \
    "$docker_bin" run --rm \
      -u "$(id -u):$(id -g)" \
      -v "$ROOT_DIR:$ROOT_DIR" \
      -w "$ROOT_DIR" \
      "$DEFAULT_HELM_IMAGE" "$@"
}

run_kubectl() {
  local kubectl_bin
  kubectl_bin="$(resolve_kubectl_bin)" || {
    printf 'live deploy smoke requires kubectl, but none is available.\n' >&2
    return 1
  }
  PATH="$(dirname "$kubectl_bin"):$PATH" "$kubectl_bin" "$@"
}

assert_manifest_contains() {
  local file="$1"
  local pattern="$2"
  if ! grep -Fq "$pattern" "$file"; then
    printf 'deploy smoke assertion failed: %s not found in %s\n' "$pattern" "$file" >&2
    return 1
  fi
}

rollout_and_probe_release() {
  local namespace="$1"
  local release_name="$2"
  local timeout="$3"
  local readiness_timeout_seconds="$4"
  local local_port_base="${BATCH_DEPLOY_SMOKE_LOCAL_PORT_BASE:-18080}"
  local components=(console-api trigger orchestrator worker-import worker-export worker-process worker-dispatch)
  local index=0

  for component in "${components[@]}"; do
    local deployment_name="${release_name}-${component}"
    run_kubectl -n "$namespace" rollout status "deployment/${deployment_name}" --timeout="$timeout"
  done

  for component in "${components[@]}"; do
    local service_name="${release_name}-${component}"
    local target_port
    local local_port
    local pf_pid=""
    local pf_log
    local deadline

    target_port="$(run_kubectl -n "$namespace" get service "$service_name" -o jsonpath='{.spec.ports[0].port}')"
    if [[ -z "$target_port" ]]; then
      printf 'failed to resolve service port for %s\n' "$service_name" >&2
      return 1
    fi

    local_port="$((local_port_base + index))"
    index="$((index + 1))"
    pf_log="$(mktemp "${TMPDIR:-/tmp}/batch-port-forward.${component}.XXXXXX.log")"

    run_kubectl -n "$namespace" port-forward "service/${service_name}" "${local_port}:${target_port}" >"$pf_log" 2>&1 &
    pf_pid=$!

    deadline="$((SECONDS + readiness_timeout_seconds))"
    while (( SECONDS < deadline )); do
      if ! kill -0 "$pf_pid" 2>/dev/null; then
        cat "$pf_log" >&2
        rm -f "$pf_log"
        return 1
      fi
      if curl -fsS "http://127.0.0.1:${local_port}/actuator/health/readiness" | grep -q '"status":"UP"'; then
        kill "$pf_pid" >/dev/null 2>&1 || true
        wait "$pf_pid" 2>/dev/null || true
        rm -f "$pf_log"
        pf_pid=""
        break
      fi
      sleep 2
    done

    if [[ -n "$pf_pid" ]]; then
      kill "$pf_pid" >/dev/null 2>&1 || true
      wait "$pf_pid" 2>/dev/null || true
      cat "$pf_log" >&2 || true
      rm -f "$pf_log"
      printf 'readiness probe did not become UP for %s within %s seconds\n' "$service_name" "$readiness_timeout_seconds" >&2
      return 1
    fi
  done
}

deploy_smoke() {
  local chart_dir="$ROOT_DIR/helm/batch-platform"
  local prod_values="$ROOT_DIR/helm/values-prod.yaml"
  local release_name="${BATCH_DEPLOY_SMOKE_RELEASE:-batch-platform-smoke}"
  local namespace="${BATCH_DEPLOY_SMOKE_NAMESPACE:-batch-smoke}"
  local values_file="${BATCH_DEPLOY_SMOKE_VALUES_FILE:-$prod_values}"
  local render_dir
  render_dir="$(mktemp -d "${TMPDIR:-/tmp}/batch-deploy-smoke.XXXXXX")"

  local -a secret_args=(
    --set-string postgresql.platform.password="${BATCH_DEPLOY_SMOKE_PLATFORM_DB_PASSWORD:-smoke-platform-pass}"
    --set-string postgresql.business.password="${BATCH_DEPLOY_SMOKE_BUSINESS_DB_PASSWORD:-smoke-business-pass}"
    --set-string minio.accessKey="${BATCH_DEPLOY_SMOKE_MINIO_ACCESS_KEY:-smoke-access-key}"
    --set-string minio.secretKey="${BATCH_DEPLOY_SMOKE_MINIO_SECRET_KEY:-smoke-secret-key}"
  )

  run_helm lint "$chart_dir"
  run_helm lint "$chart_dir" -f "$prod_values" "${secret_args[@]}"

  run_helm template "$release_name" "$chart_dir" --namespace "$namespace" >"$render_dir/default.yaml"
  run_helm template "$release_name" "$chart_dir" --namespace "$namespace" -f "$prod_values" "${secret_args[@]}" >"$render_dir/prod.yaml"

  assert_manifest_contains "$render_dir/default.yaml" "kind: Deployment"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-trigger"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-orchestrator"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-console-api"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-worker-import"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-worker-export"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-worker-process"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-worker-dispatch"
  assert_manifest_contains "$render_dir/default.yaml" "kind: ConfigMap"
  assert_manifest_contains "$render_dir/default.yaml" "kind: Secret"

  assert_manifest_contains "$render_dir/prod.yaml" "kind: Ingress"
  assert_manifest_contains "$render_dir/prod.yaml" "kind: HorizontalPodAutoscaler"
  assert_manifest_contains "$render_dir/prod.yaml" "name: ${release_name}-worker-import"
  assert_manifest_contains "$render_dir/prod.yaml" "name: ${release_name}-worker-export"
  assert_manifest_contains "$render_dir/prod.yaml" "name: ${release_name}-worker-process"
  assert_manifest_contains "$render_dir/prod.yaml" "name: ${release_name}-worker-dispatch"

  if [[ "${BATCH_DEPLOY_SMOKE_KEEP_RENDERED:-false}" != "true" ]]; then
    rm -rf "$render_dir"
  else
    printf 'Rendered manifests kept at %s\n' "$render_dir"
  fi

  if [[ "${BATCH_DEPLOY_SMOKE_ENABLE_LIVE:-false}" == "true" ]]; then
    local -a values_args=()
    if [[ -n "$values_file" ]]; then
      values_args=(-f "$values_file")
    fi

    run_kubectl config current-context >/dev/null
    run_helm upgrade --install "$release_name" "$chart_dir" \
      --namespace "$namespace" \
      --create-namespace \
      --wait \
      --timeout "$DEFAULT_LIVE_DEPLOY_TIMEOUT" \
      "${values_args[@]}" \
      "${secret_args[@]}"

    rollout_and_probe_release \
      "$namespace" \
      "$release_name" \
      "$DEFAULT_LIVE_DEPLOY_TIMEOUT" \
      "$DEFAULT_LIVE_READINESS_TIMEOUT_SECONDS"
  fi
}

deployment_verification() {
  local chart_dir="$ROOT_DIR/helm/batch-platform"
  local prod_values="$ROOT_DIR/helm/values-prod.yaml"
  local release_name="${DEFAULT_VERIFICATION_RELEASE}"
  local namespace="${DEFAULT_VERIFICATION_NAMESPACE}"
  local values_file="${BATCH_DEPLOY_VERIFICATION_VALUES_FILE:-$prod_values}"
  local render_dir
  local verification_id
  render_dir="$(mktemp -d "${TMPDIR:-/tmp}/batch-deploy-verification.XXXXXX")"
  verification_id="rollback-verification-$(date +%Y%m%d%H%M%S)-$$-$RANDOM"

  local -a secret_args=(
    --set-string postgresql.platform.password="${BATCH_DEPLOY_VERIFICATION_PLATFORM_DB_PASSWORD:-verify-platform-pass}"
    --set-string postgresql.business.password="${BATCH_DEPLOY_VERIFICATION_BUSINESS_DB_PASSWORD:-verify-business-pass}"
    --set-string minio.accessKey="${BATCH_DEPLOY_VERIFICATION_MINIO_ACCESS_KEY:-verify-access-key}"
    --set-string minio.secretKey="${BATCH_DEPLOY_VERIFICATION_MINIO_SECRET_KEY:-verify-secret-key}"
  )

  run_helm lint "$chart_dir"
  run_helm lint "$chart_dir" -f "$prod_values" "${secret_args[@]}"
  run_helm template "$release_name" "$chart_dir" --namespace "$namespace" >"$render_dir/default.yaml"
  run_helm template "$release_name" "$chart_dir" --namespace "$namespace" -f "$prod_values" "${secret_args[@]}" >"$render_dir/prod.yaml"

  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-trigger"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-orchestrator"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-console-api"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-worker-import"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-worker-export"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-worker-process"
  assert_manifest_contains "$render_dir/default.yaml" "name: ${release_name}-worker-dispatch"

  if [[ "${BATCH_DEPLOY_VERIFICATION_KEEP_RENDERED:-false}" != "true" ]]; then
    rm -rf "$render_dir"
  else
    printf 'Rendered manifests kept at %s\n' "$render_dir"
  fi

  if [[ "${BATCH_DEPLOY_VERIFICATION_ENABLE_LIVE:-false}" != "true" ]]; then
    printf 'deployment verification live mode disabled; only static chart checks were executed.\n'
    return 0
  fi

  local -a values_args=()
  if [[ -n "$values_file" ]]; then
    values_args=(-f "$values_file")
  fi

  run_kubectl config current-context >/dev/null
  run_helm uninstall "$release_name" --namespace "$namespace" >/dev/null 2>&1 || true

  run_helm upgrade --install "$release_name" "$chart_dir" \
    --namespace "$namespace" \
    --create-namespace \
    --wait \
    --timeout "$DEFAULT_VERIFICATION_TIMEOUT" \
    "${values_args[@]}" \
    "${secret_args[@]}"

  rollout_and_probe_release \
    "$namespace" \
    "$release_name" \
    "$DEFAULT_VERIFICATION_TIMEOUT" \
    "$DEFAULT_VERIFICATION_READINESS_TIMEOUT_SECONDS"

  run_helm upgrade "$release_name" "$chart_dir" \
    --namespace "$namespace" \
    --wait \
    --timeout "$DEFAULT_VERIFICATION_TIMEOUT" \
    "${values_args[@]}" \
    "${secret_args[@]}" \
    --set-string podAnnotations.rollbackVerificationRunId="$verification_id"

  rollout_and_probe_release \
    "$namespace" \
    "$release_name" \
    "$DEFAULT_VERIFICATION_TIMEOUT" \
    "$DEFAULT_VERIFICATION_READINESS_TIMEOUT_SECONDS"

  local annotation_value
  annotation_value="$(run_kubectl -n "$namespace" get deploy "${release_name}-orchestrator" -o jsonpath='{.spec.template.metadata.annotations.rollbackVerificationRunId}' 2>/dev/null || true)"
  if [[ -z "$annotation_value" ]]; then
    printf 'deployment verification upgrade did not apply the expected annotation marker.\n' >&2
    return 1
  fi

  run_helm rollback "$release_name" 1 \
    --namespace "$namespace" \
    --wait \
    --timeout "$DEFAULT_VERIFICATION_TIMEOUT"

  rollout_and_probe_release \
    "$namespace" \
    "$release_name" \
    "$DEFAULT_VERIFICATION_TIMEOUT" \
    "$DEFAULT_VERIFICATION_READINESS_TIMEOUT_SECONDS"

  annotation_value="$(run_kubectl -n "$namespace" get deploy "${release_name}-orchestrator" -o jsonpath='{.spec.template.metadata.annotations.rollbackVerificationRunId}' 2>/dev/null || true)"
  if [[ -n "$annotation_value" ]]; then
    printf 'deployment verification rollback did not remove the annotation marker.\n' >&2
    return 1
  fi

  if [[ "${BATCH_DEPLOY_VERIFICATION_KEEP_RELEASE:-true}" != "true" ]]; then
    run_helm uninstall "$release_name" --namespace "$namespace"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-static-gates)
      RUN_STATIC_GATES=false
      shift
      ;;
    --skip-default-tests)
      RUN_DEFAULT_TESTS=false
      shift
      ;;
    --skip-it-suite)
      RUN_IT_SUITE=false
      shift
      ;;
    --with-load-smoke)
      RUN_LOAD_SMOKE=true
      shift
      ;;
    --with-load-capacity)
      RUN_LOAD_CAPACITY=true
      shift
      ;;
    --with-deploy-smoke)
      RUN_DEPLOY_SMOKE=true
      shift
      ;;
    --with-deployment-verification)
      RUN_DEPLOY_VERIFICATION=true
      shift
      ;;
    --with-inspection)
      RUN_INSPECTION=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --)
      shift
      EXTRA_MVN_ARGS=("$@")
      break
      ;;
    *)
      printf 'Unknown option: %s\n\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$RUN_DEFAULT_TESTS" == false && "$RUN_IT_SUITE" == false && "$RUN_LOAD_SMOKE" == false && "$RUN_LOAD_CAPACITY" == false && "$RUN_DEPLOY_SMOKE" == false && "$RUN_DEPLOY_VERIFICATION" == false && "$RUN_INSPECTION" == false ]]; then
  printf 'Nothing to run. Use --help for options.\n' >&2
  exit 2
fi

if [[ "$RUN_STATIC_GATES" == true ]]; then
  run_step "Version alignment (pom.xml / Chart.yaml / .env.*)" bash scripts/ci/check-version-alignment.sh

  run_step "Dependency boundary checks" python3 scripts/ci/check-dependency-boundaries.py

  # PMD 0 violation 基线达成 (2026-04-26 maturity §6 P1 #2 收尾)，让 PMD 真正阻断 CI。
  # 调试模式可显式 export BATCH_CI_SKIP_PMD_GATE=1 让 dev 本地 escape；CI 不应设此变量。
  if [[ "${BATCH_CI_SKIP_PMD_GATE:-}" == "1" ]]; then
    echo "[PMD gate] skipped via BATCH_CI_SKIP_PMD_GATE=1 (debug only)"
  else
    # 注：必须先 test-compile 让 reactor 编译 batch-common 等 sibling 模块，否则下游
    # 模块解析 com.example.batch:batch-common:${revision} 失败（CI 干净 .m2 找不到）。
    run_step "PMD — code conventions (fail PR if violations introduced)" run_mvn -DskipTests test-compile pmd:check -fae
  fi

  run_step "Spotless — code formatting" run_mvn spotless:check -fae
fi

if [[ "$RUN_DEFAULT_TESTS" == true ]]; then
  run_step \
    "Reactor Default Tests (*Test / *IntegrationTest)" \
    run_mvn test -fae
  # Coverage gate — 真正阻断 CI（2026-04-26 启用）。阈值见 pom.xml jacoco-maven-plugin（起步 25% LINE）。
  # 调试模式可显式 export BATCH_CI_SKIP_COVERAGE_GATE=1 让 dev 本地 escape；CI 不应设此变量。
  if [[ "${BATCH_CI_SKIP_COVERAGE_GATE:-}" == "1" ]]; then
    echo "[Coverage gate] skipped via BATCH_CI_SKIP_COVERAGE_GATE=1 (debug only)"
  else
    # 用 @check execution id 而非裸 jacoco:check —— 后者走 default-cli execution 拿不到 pom.xml
    # check execution 配的 rules（因为 check execution phase=none，rules 只挂在该 execution 上）。
    run_step "Coverage gate (jacoco:check@check, fail PR if below threshold)" run_mvn jacoco:check@check -fae
  fi
fi

if [[ "$RUN_IT_SUITE" == true ]]; then
  run_step \
    "E2E Suite (batch-e2e-tests, *E2eIT)" \
    run_mvn test -pl batch-e2e-tests -am -Dsurefire.failIfNoSpecifiedTests=false
fi

if [[ "$RUN_LOAD_SMOKE" == true ]]; then
  run_step \
    "Load Smoke (JobLaunchSimulation)" \
    bash -lc "cd '$ROOT_DIR/load-tests' && mvn -q gatling:test -Dsimulation=JobLaunchSimulation -Dusers.peak=\${BATCH_LOAD_SMOKE_USERS_PEAK:-5} -Dduration.seconds=\${BATCH_LOAD_SMOKE_DURATION_SECONDS:-30} -Dramp.seconds=\${BATCH_LOAD_SMOKE_RAMP_SECONDS:-10}"
fi

if [[ "$RUN_LOAD_CAPACITY" == true ]]; then
  # CapacityBaselineSimulation 内置 SLO 断言（write p95<500ms / read p99<300ms / err<1%），
  # 失败即非零退出，作为容量回归门禁。stepped ramp 25→200 users，约 5-10 分钟。
  run_step \
    "Capacity Baseline (CapacityBaselineSimulation)" \
    bash -lc "cd '$ROOT_DIR/load-tests' && mvn -q gatling:test -Dsimulation=CapacityBaselineSimulation -DjobCode=\${BATCH_LOAD_CAPACITY_JOB_CODE:-E2E_IMPORT_LOAD} -DtenantId=\${BATCH_LOAD_CAPACITY_TENANT_ID:-t1}"
fi

if [[ "$RUN_DEPLOY_SMOKE" == true ]]; then
  run_step \
    "Deployment Smoke (Helm lint + template)" \
    deploy_smoke
fi

if [[ "$RUN_DEPLOY_VERIFICATION" == true ]]; then
  run_step \
    "Deployment Verification (upgrade / rollback)" \
    deployment_verification
fi

if [[ "$RUN_INSPECTION" == true ]]; then
  run_step \
    "Post-run Inspection" \
    bash "$ROOT_DIR/scripts/ops/inspect-all.sh"
fi

banner "FULL REGRESSION PASSED"
