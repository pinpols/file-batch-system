#!/usr/bin/env bash
# 按不可变 release manifest 部署后端 Compose 服务，并在失败时恢复上一稳定版本。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../lib/python-runtime.sh
source "$ROOT/scripts/lib/python-runtime.sh"

readonly MANIFEST_TOOL="$ROOT/scripts/deploy/release_manifest.py"
readonly BASE_COMPOSE="$ROOT/docker-compose.yml"
readonly APP_COMPOSE="$ROOT/deploy/docker/compose/app.yml"
readonly DEFAULT_STATE_DIR="/var/lib/batch-platform/releases"
readonly SERVICES=(
  console-api trigger orchestrator worker-import worker-export worker-process worker-dispatch
  worker-atomic
)

DEPLOY_LOCK_DIR=""

cleanup_deploy_lock() {
  if [[ -n "$DEPLOY_LOCK_DIR" ]]; then
    rmdir "$DEPLOY_LOCK_DIR" 2>/dev/null || true
    DEPLOY_LOCK_DIR=""
  fi
}

trap cleanup_deploy_lock EXIT

usage() {
  cat <<'EOF'
Usage:
  compose-release.sh plan MANIFEST
  compose-release.sh deploy MANIFEST ENVIRONMENT
  compose-release.sh verify MANIFEST ENVIRONMENT
  compose-release.sh rollback ENVIRONMENT

Required for deploy/verify/rollback:
  BATCH_RELEASE_ENV_FILE       Compose env file on the deployment host

Optional:
  BATCH_RELEASE_STATE_DIR      Release state directory (default /var/lib/batch-platform/releases)
  BATCH_RELEASE_PROJECT_NAME   Compose project name (default batch-platform-ENVIRONMENT)
  BATCH_RELEASE_HEALTH_TIMEOUT Health wait seconds (default 180)
  BATCH_RELEASE_HEALTH_URLS    Comma-separated HTTP health URLs
  BATCH_RELEASE_RETENTION      Number of successful manifests retained (default 10)
EOF
}

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "ERROR: required command not found: $command_name" >&2
    return 2
  fi
}

validate_environment() {
  case "$1" in
    staging | production) ;;
    *)
      echo "ERROR: environment must be staging or production" >&2
      return 2
      ;;
  esac
}

manifest_value() {
  local manifest="$1"
  local field="$2"
  "$PYTHON_BIN" - "$manifest" "$field" <<'PY'
import json
import sys

value = json.load(open(sys.argv[1], encoding="utf-8"))
for segment in sys.argv[2].split("."):
    value = value[segment]
print(value)
PY
}

validate_manifest() {
  "$PYTHON_BIN" "$MANIFEST_TOOL" validate "$1" >/dev/null
}

render_overlay() {
  "$PYTHON_BIN" "$MANIFEST_TOOL" render-compose "$1" --output "$2"
}

release_state_dir() {
  local environment="$1"
  printf '%s/%s' "${BATCH_RELEASE_STATE_DIR:-$DEFAULT_STATE_DIR}" "$environment"
}

compose_project_name() {
  local environment="$1"
  printf '%s' "${BATCH_RELEASE_PROJECT_NAME:-batch-platform-$environment}"
}

compose_args() {
  local environment="$1"
  local overlay="$2"
  COMPOSE_ARGS=(
    --project-name "$(compose_project_name "$environment")"
    --env-file "$BATCH_RELEASE_ENV_FILE"
    -f "$BASE_COMPOSE"
    -f "$APP_COMPOSE"
    -f "$overlay"
    --profile apps
    --profile replica
  )
}

precheck() {
  local manifest="$1"
  require_command docker
  batch_require_python
  docker compose version >/dev/null
  [[ -f "$manifest" ]] || {
    echo "ERROR: release manifest not found: $manifest" >&2
    return 2
  }
  [[ -f "$BASE_COMPOSE" && -f "$APP_COMPOSE" ]] || {
    echo "ERROR: deployment bundle is missing Compose files" >&2
    return 2
  }
  [[ -n "${BATCH_RELEASE_ENV_FILE:-}" && -f "$BATCH_RELEASE_ENV_FILE" ]] || {
    echo "ERROR: BATCH_RELEASE_ENV_FILE must point to an existing env file" >&2
    return 2
  }
  validate_manifest "$manifest"
}

container_matches_manifest() {
  local service="$1"
  local expected_image="$2"
  local container_id
  container_id="$(docker compose "${COMPOSE_ARGS[@]}" ps -q "$service")"
  [[ -n "$container_id" ]] || return 1

  local actual_image status health
  actual_image="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
  status="$(docker inspect --format '{{.State.Status}}' "$container_id")"
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id")"
  [[ "$actual_image" == "$expected_image" && "$status" == "running" ]] || return 1
  [[ "$health" == "none" || "$health" == "healthy" ]]
}

health_urls_ready() {
  local raw_urls="${BATCH_RELEASE_HEALTH_URLS:-}"
  [[ -z "$raw_urls" ]] && return 0
  require_command curl
  local urls url
  IFS=',' read -r -a urls <<< "$raw_urls"
  for url in "${urls[@]}"; do
    [[ -z "$url" ]] && continue
    curl --fail --silent --show-error --max-time 10 "$url" >/dev/null || return 1
  done
}

verify_release() {
  local manifest="$1"
  local timeout="${BATCH_RELEASE_HEALTH_TIMEOUT:-180}"
  local deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    local ready=true service expected_image
    for service in "${SERVICES[@]}"; do
      expected_image="$(manifest_value "$manifest" "images.$service")"
      if ! container_matches_manifest "$service" "$expected_image"; then
        ready=false
        break
      fi
    done
    if [[ "$ready" == true ]] && health_urls_ready; then
      return 0
    fi
    sleep 5
  done
  echo "ERROR: release did not become healthy within ${timeout}s" >&2
  return 1
}

capture_failure_snapshot() {
  local state_dir="$1"
  local timestamp
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$state_dir/failures/$timestamp"
  docker compose "${COMPOSE_ARGS[@]}" ps -a \
    > "$state_dir/failures/$timestamp/compose-ps.txt" 2>&1 || true
  docker compose "${COMPOSE_ARGS[@]}" logs --no-color --tail 300 "${SERVICES[@]}" \
    > "$state_dir/failures/$timestamp/compose-logs.txt" 2>&1 || true
}

append_audit() {
  local state_dir="$1"
  local environment="$2"
  local release_id="$3"
  local result="$4"
  "$PYTHON_BIN" - "$state_dir/deployments.jsonl" "$environment" "$release_id" "$result" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

path = Path(sys.argv[1])
record = {
    "recordedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "environment": sys.argv[2],
    "releaseId": sys.argv[3],
    "result": sys.argv[4],
}
with path.open("a", encoding="utf-8") as output:
    output.write(json.dumps(record, separators=(",", ":")) + "\n")
PY
}

retain_manifests() {
  local state_dir="$1"
  local keep="${BATCH_RELEASE_RETENTION:-10}"
  "$PYTHON_BIN" - "$state_dir/releases" "$keep" <<'PY'
import sys
from pathlib import Path

root = Path(sys.argv[1])
keep = int(sys.argv[2])
manifests = sorted(root.glob("*.json"), key=lambda path: path.stat().st_mtime, reverse=True)
for stale in manifests[max(keep, 1):]:
    stale.unlink()
PY
}

activate_manifest() {
  local manifest="$1"
  local environment="$2"
  local state_dir="$3"
  local allow_rollback="$4"
  local release_id overlay
  release_id="$(manifest_value "$manifest" releaseId)"
  overlay="$state_dir/release.compose.yml"
  render_overlay "$manifest" "$overlay"
  compose_args "$environment" "$overlay"

  docker compose "${COMPOSE_ARGS[@]}" pull "${SERVICES[@]}"
  docker compose "${COMPOSE_ARGS[@]}" up -d --no-build "${SERVICES[@]}"
  if verify_release "$manifest"; then
    cp "$manifest" "$state_dir/releases/$release_id.json"
    cp "$manifest" "$state_dir/stable.json"
    append_audit "$state_dir" "$environment" "$release_id" PASSED
    retain_manifests "$state_dir"
    echo "release deployed: environment=$environment releaseId=$release_id"
    return 0
  fi

  capture_failure_snapshot "$state_dir"
  append_audit "$state_dir" "$environment" "$release_id" FAILED
  if [[ "$allow_rollback" == true && -f "$state_dir/previous.json" ]]; then
    echo "deployment failed; restoring previous stable release" >&2
    activate_manifest "$state_dir/previous.json" "$environment" "$state_dir" false
  fi
  return 1
}

deploy_release() {
  local manifest="$1"
  local environment="$2"
  validate_environment "$environment"
  precheck "$manifest"
  local state_dir
  state_dir="$(release_state_dir "$environment")"
  DEPLOY_LOCK_DIR="$state_dir/.deploy-lock"
  mkdir -p "$state_dir/releases"
  if ! mkdir "$DEPLOY_LOCK_DIR" 2>/dev/null; then
    echo "ERROR: another deployment holds $DEPLOY_LOCK_DIR" >&2
    DEPLOY_LOCK_DIR=""
    return 3
  fi
  if [[ -f "$state_dir/stable.json" ]]; then
    cp "$state_dir/stable.json" "$state_dir/previous.json"
  fi
  activate_manifest "$manifest" "$environment" "$state_dir" true
}

verify_command() {
  local manifest="$1"
  local environment="$2"
  validate_environment "$environment"
  precheck "$manifest"
  local state_dir overlay
  state_dir="$(release_state_dir "$environment")"
  overlay="$state_dir/verify.compose.yml"
  mkdir -p "$state_dir"
  render_overlay "$manifest" "$overlay"
  compose_args "$environment" "$overlay"
  verify_release "$manifest"
}

rollback_command() {
  local environment="$1"
  validate_environment "$environment"
  local state_dir manifest
  state_dir="$(release_state_dir "$environment")"
  manifest="$state_dir/previous.json"
  precheck "$manifest"
  mkdir -p "$state_dir/releases"
  activate_manifest "$manifest" "$environment" "$state_dir" false
}

main() {
  local command="${1:-}"
  case "$command" in
    plan)
      [[ $# -eq 2 ]] || { usage; return 2; }
      batch_require_python
      validate_manifest "$2"
      render_overlay "$2" /dev/stdout
      ;;
    deploy)
      [[ $# -eq 3 ]] || { usage; return 2; }
      deploy_release "$2" "$3"
      ;;
    verify)
      [[ $# -eq 3 ]] || { usage; return 2; }
      verify_command "$2" "$3"
      ;;
    rollback)
      [[ $# -eq 2 ]] || { usage; return 2; }
      rollback_command "$2"
      ;;
    *)
      usage
      return 2
      ;;
  esac
}

main "$@"
