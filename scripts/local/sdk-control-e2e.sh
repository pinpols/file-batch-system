#!/usr/bin/env bash
# 在真实服务链路中对自托管 SDK Worker 注入暂停、恢复、取消和排空控制。
set -uo pipefail

LANG_FILTER="${1:-all}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../lib/sdk-e2e-common.sh
source "$ROOT/scripts/lib/sdk-e2e-common.sh"

case "$LANG_FILTER" in
  all) LANGUAGES=(java go python typescript rust) ;;
  java|go|python|typescript|rust) LANGUAGES=("$LANG_FILTER") ;;
  *) echo "usage: $0 [all|java|go|python|typescript|rust]" >&2; exit 2 ;;
esac

SDK_CONTROL_E2E=true
SDK_CONTROL_E2E_DELAY_MS=15000
export SDK_CONTROL_E2E SDK_CONTROL_E2E_DELAY_MS

sdk_control_state() {
  curl -fsS "$SDK_CONTROL_PROXY_URL/__control/status"
}

sdk_control_pause_proxy() {
  curl -fsS -X POST "$SDK_CONTROL_PROXY_URL/__control" \
    -H 'Content-Type: application/json' \
    -d "{\"workerCode\":\"$1\",\"paused\":$2}" >/dev/null
}

sdk_control_launch() {
  local after_id="$1" deadline instance=""
  SDK_E2E_IDEMPOTENCY_KEY="sdk-control-${LANG_ID}-$$-$(date +%s)-$RANDOM"
  curl -fsS -X POST "${TRIGGER_URL}/api/triggers/launch" \
    -H "X-Internal-Secret: ${BATCH_INTERNAL_SECRET}" \
    -H "Idempotency-Key: ${SDK_E2E_IDEMPOTENCY_KEY}" \
    -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"${TENANT}\",\"jobCode\":\"${SDK_E2E_JOB_CODE}\",\"bizDate\":\"$(date +%F)\",\"triggerType\":\"API\"}" >/dev/null || return 1
  deadline=$((SECONDS + SDK_E2E_INSTANCE_WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    instance="$(sdk_e2e_q_file select-latest-job-instance-id-after.sql \
      -v tenant_id="$TENANT" -v job_code="$SDK_E2E_JOB_CODE" -v after_id="$after_id")"
    [[ -n "$instance" ]] && break
    sleep 1
  done
  [[ -n "$instance" ]] || return 1
  SDK_E2E_LAUNCH_SETTLED=1
  printf '%s' "$instance"
}

sdk_control_task_id() {
  local instance="$1" deadline=$((SECONDS + SDK_E2E_TERMINAL_WAIT_SECONDS)) task_id=""
  while (( SECONDS < deadline )); do
    task_id="$(sdk_e2e_q_file select-latest-task-for-instance.sql \
      -v tenant_id="$TENANT" -v instance_id="$instance")"
    [[ -n "$task_id" ]] && break
    sleep 1
  done
  [[ -n "$task_id" ]] || return 1
  printf '%s' "$task_id"
}

sdk_control_wait_task_state() {
  local task_id="$1" expected="$2" timeout="$3" deadline state
  deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    state="$(sdk_e2e_q_file select-task-control-state.sql \
      -v tenant_id="$TENANT" -v task_id="$task_id")"
    [[ "$state" == "$expected" ]] && { printf '%s' "$state"; return 0; }
    sleep 1
  done
  printf '%s' "${state:-missing}"
  return 1
}

sdk_control_wait_running() {
  local instance="$1" deadline=$((SECONDS + SDK_E2E_TERMINAL_WAIT_SECONDS)) task_id state
  while (( SECONDS < deadline )); do
    task_id="$(sdk_control_task_id "$instance")" || return 1
    state="$(sdk_e2e_q_file select-task-control-state.sql \
      -v tenant_id="$TENANT" -v task_id="$task_id")"
    if [[ "$state" == RUNNING\|false || "$state" == RUNNING\|true ]]; then
      printf '%s' "$task_id"
      return 0
    fi
    sleep 1
  done
  return 1
}

sdk_control_wait_proxy_heartbeat() {
  local expected_key="$1" deadline=$((SECONDS + 45)) state value
  while (( SECONDS < deadline )); do
    state="$(sdk_control_state)"
    value="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], 0))' "$expected_key" <<<"$state")"
    (( value > 0 )) && return 0
    sleep 1
  done
  return 1
}

sdk_control_launch_task_and_wait_running() {
  local after_id="$1" instance task_id
  instance="$(sdk_control_launch "$after_id")" || return 1
  task_id="$(sdk_control_wait_running "$instance")" || return 1
  printf '%s %s' "$instance" "$task_id"
}

sdk_control_wait_worker_exit() {
  local pid="$1" deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 1
  done
  return 1
}

sdk_e2e_check_stack || exit 1

SDK_CONTROL_WORKER_CODE=""
SDK_CONTROL_WORKER_PID=""
SDK_CONTROL_PROXY_PID=""

sdk_control_cleanup() {
  if [[ -n "$SDK_CONTROL_WORKER_PID" ]]; then
    kill "$SDK_CONTROL_WORKER_PID" 2>/dev/null || true
    for _ in {1..10}; do
      kill -0 "$SDK_CONTROL_WORKER_PID" 2>/dev/null || break
      sleep 1
    done
    if [[ -n "$SDK_CONTROL_WORKER_CODE" ]]; then
      pkill -f "$SDK_CONTROL_WORKER_CODE" 2>/dev/null || true
    fi
  fi
  [[ -z "$SDK_CONTROL_PROXY_PID" ]] || kill "$SDK_CONTROL_PROXY_PID" 2>/dev/null || true
  if [[ -n "$SDK_CONTROL_WORKER_CODE" ]]; then
    sdk_e2e_cleanup "$SDK_CONTROL_WORKER_CODE" >/dev/null 2>&1 || true
  fi
}

run_language() {
  LANG_ID="$1"
  local worker_code="sdk-ctrl-${LANG_ID}-$$"
  local worker_log="/tmp/sdk-control-${LANG_ID}.log"
  local proxy_log="/tmp/sdk-control-proxy-${LANG_ID}.log"
  local proxy_port="" proxy_url=""
  local first_instance="0" cancel_instance="" cancel_task="" drain_task="" state=""
  : >"$worker_log"
  : >"$proxy_log"
  SDK_CONTROL_WORKER_CODE="$worker_code"
  SDK_CONTROL_WORKER_PID=""
  SDK_CONTROL_PROXY_PID=""
  SDK_E2E_JOB_CODE="sdk_control_${LANG_ID}_$$"
  export SDK_E2E_QUEUE_CODE="sdk_control_queue_$$"
  export SDK_E2E_LAUNCH_SETTLED=0

  trap sdk_control_cleanup EXIT

  sdk_e2e_say "${LANG_ID}: seed isolated fixture and pause-injection proxy"
  RAW="$(sdk_e2e_seed_api_key "$worker_code")"
  sdk_e2e_ensure_echo_job || { sdk_e2e_fail "could not seed echo job"; return 1; }
  sdk_e2e_precreate_topic "$worker_code" || return 1
  SDK_CONTROL_UPSTREAM="$ORCH_URL" python3 -u "$ROOT/scripts/local/sdk-control-fault-proxy.py" >"$proxy_log" 2>&1 &
  SDK_CONTROL_PROXY_PID=$!
  for _ in {1..50}; do
    proxy_port="$(awk '/^LISTENING / {print $2; exit}' "$proxy_log")"
    [[ -n "$proxy_port" ]] && break
    kill -0 "$SDK_CONTROL_PROXY_PID" 2>/dev/null || { tail -30 "$proxy_log"; return 1; }
    sleep 0.1
  done
  [[ -n "$proxy_port" ]] || { sdk_e2e_fail "fault proxy did not start"; return 1; }
  proxy_url="http://127.0.0.1:${proxy_port}"
  SDK_CONTROL_PROXY_URL="$proxy_url"
  SDK_E2E_WORKER_BASE_URL="$proxy_url"
  export SDK_CONTROL_PROXY_URL SDK_E2E_WORKER_BASE_URL
  sdk_control_pause_proxy "$worker_code" true || return 1

  sdk_e2e_say "${LANG_ID}: register real worker; wait for injected PAUSED heartbeat"
  SDK_CONTROL_WORKER_PID="$(sdk_e2e_start_worker "$LANG_ID" "$worker_code" "$RAW" "$worker_log")" || return 1
  sdk_e2e_assert_register "$worker_code" "$SDK_CONTROL_WORKER_PID" "$worker_log" || return 1
  sdk_control_wait_proxy_heartbeat pausedHeartbeats || {
    sdk_e2e_fail "no heartbeat reached the PAUSED injection point"; tail -30 "$proxy_log"; return 1;
  }
  sdk_e2e_pass "real heartbeat transport received injected PAUSED directive"

  sdk_e2e_say "${LANG_ID}: pause blocks claim; resume permits real Kafka task; cancel via Orchestrator API"
  cancel_instance="$(sdk_control_launch 0)" || { sdk_e2e_fail "launch under PAUSED failed"; return 1; }
  first_instance="$cancel_instance"
  cancel_task="$(sdk_control_task_id "$cancel_instance")" || return 1
  sleep 6
  state="$(sdk_e2e_q_file select-task-control-state.sql -v tenant_id="$TENANT" -v task_id="$cancel_task")"
  [[ "$state" != RUNNING\|* ]] || { sdk_e2e_fail "task was claimed while PAUSED: $state"; return 1; }
  ! grep -q "control-e2e handler started" "$worker_log" || {
    sdk_e2e_fail "handler executed while PAUSED"; return 1;
  }
  sdk_control_pause_proxy "$worker_code" false || return 1
  sdk_control_wait_proxy_heartbeat normalHeartbeats || {
    sdk_e2e_fail "worker did not receive NORMAL after resume"; return 1;
  }
  sdk_e2e_pass "pause prevented claim; NORMAL resumed consumption"

  cancel_task="$(sdk_control_wait_running "$cancel_instance")" || {
    sdk_e2e_fail "task did not start after resume"; return 1;
  }
  curl -fsS -X POST "${ORCH_URL}/internal/tasks/${cancel_task}/cancel" \
    -H "X-Internal-Secret: ${BATCH_INTERNAL_SECRET}" -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"${TENANT}\",\"reason\":\"sdk-control-e2e-${LANG_ID}\"}" >/dev/null || {
      sdk_e2e_fail "real task cancel API failed"; return 1;
    }
  sdk_control_wait_task_state "$cancel_task" "CANCELLED|true" 30 >/dev/null || {
    sdk_e2e_fail "SDK did not consume cancelRequested and report CANCELLED"; tail -40 "$worker_log"; return 1;
  }
  grep -q "control-e2e cancellation observed" "$worker_log" || {
    sdk_e2e_fail "cancel terminal state lacked handler cancellation evidence"; return 1;
  }
  sdk_e2e_pass "real cancel request reached handler and task reported CANCELLED"

  sdk_e2e_say "${LANG_ID}: drain running task; allow completion and worker deactivation"
  read -r _ drain_task < <(sdk_control_launch_task_and_wait_running "$first_instance")
  [[ -n "$drain_task" ]] || { sdk_e2e_fail "could not start drain probe task"; return 1; }
  curl -fsS -X POST "${ORCH_URL}/internal/workers/${worker_code}/drain" \
    -H "X-Internal-Secret: ${BATCH_INTERNAL_SECRET}" -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"${TENANT}\",\"timeoutSeconds\":45}" | grep -q 'DRAINING' || {
      sdk_e2e_fail "real worker drain API did not return DRAINING"; return 1;
    }
  sdk_control_wait_task_state "$drain_task" "SUCCESS|false" 45 >/dev/null || {
    sdk_e2e_fail "in-flight task did not complete during drain"; tail -40 "$worker_log"; return 1;
  }
  sdk_e2e_say "${LANG_ID}: drain settled; simulate supervisor termination and verify SDK shutdown"
  kill -TERM "$SDK_CONTROL_WORKER_PID" 2>/dev/null || true
  sdk_control_wait_worker_exit "$SDK_CONTROL_WORKER_PID" || {
    sdk_e2e_fail "worker process did not exit after supervisor termination"; tail -40 "$worker_log"; return 1;
  }
  state="$(sdk_e2e_q_file select-worker-status.sql -v tenant_id="$TENANT" -v worker_code="$worker_code")"
  [[ "$state" != ONLINE && -n "$state" ]] || {
    sdk_e2e_fail "worker registry remained ONLINE after drain: ${state:-missing}"; return 1;
  }
  sdk_e2e_pass "drain preserved in-flight success; supervisor termination stopped worker (registry=${state})"
  printf '%s: pause/resume injected, cancel real, drain real; logs=%s\n' "$LANG_ID" "$worker_log"
  sdk_control_cleanup
  trap - EXIT
  SDK_CONTROL_WORKER_CODE=""
  SDK_CONTROL_WORKER_PID=""
  SDK_CONTROL_PROXY_PID=""
}

for language in "${LANGUAGES[@]}"; do
  run_language "$language" || exit 1
done

echo "SDK control E2E complete: ${LANGUAGES[*]}"
