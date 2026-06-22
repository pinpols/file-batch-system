#!/usr/bin/env bash
# =========================================================
# stop-all.sh - 停止本地 batch 平台 Java 进程
# 说明：
# 1) 停止顺序：三个 worker -> trigger / console -> orchestrator（与 start-all 对称）。
# 2) 每阶段 SIGTERM 后等待，再对本阶段残留 kill -9，然后进入下一阶段。
# 3) PID 文件：TAB 三列或旧版空格两列；读入内存后按阶段消费。
# 4) 再用 ps+awk 按 jar 路径子串匹配（避免 pgrep 正则问题）。
# 5) 可选 STOP_DOCKER=1 停止 Docker Compose（仅 stop，不 down）。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../lib/logging.sh
source "$ROOT/scripts/lib/logging.sh"
# shellcheck source=../lib/process.sh
source "$ROOT/scripts/lib/process.sh"
PID_FILE="$(log_pid_file "$ROOT" start-all.pids)"
APP_LOG_DIR="$(log_current_dir "$ROOT" app app)"
RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env.local}"

# 全量扫描顺序（回退轮询）；分阶段停止时按 PHASE 使用子集
RUNTIME_JAVA_NAMES=(
  worker-import worker-export worker-process worker-dispatch worker-atomic
  trigger console
  orchestrator
)

LEGACY_JAR_MODULES=(
  batch-worker-import
  batch-worker-export
  batch-worker-process
  batch-worker-dispatch
  batch-worker-atomic
  batch-trigger
  batch-console-api
  batch-orchestrator
)

killed=0
already_killed=()
PID_FILE_LINES=()

_java_ps_lines() {
  if ps -ax -o pid= -o command= >/dev/null 2>&1; then
    ps -ax -o pid= -o command= 2>/dev/null
  elif ps -A -o pid= -o args= >/dev/null 2>&1; then
    ps -A -o pid= -o args= 2>/dev/null
  else
    ps -eo pid= -o args= 2>/dev/null
  fi
}

_pids_cmd_contains() {
  local needle="$1"
  _java_ps_lines | awk -v n="$needle" '
    NF < 2 { next }
    {
      pid = $1
      line = ""
      for (i = 2; i <= NF; i++) {
        line = (i == 2 ? "" : line " ") $i
      }
      if (index(line, n) == 0) next
      if (index(line, "java") == 0) next
      print pid
    }' | sort -u
}

kill_pid() {
  local label="$1" pid="$2"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 0
  for prev in "${already_killed[@]+"${already_killed[@]}"}"; do
    [[ "$prev" == "$pid" ]] && return 0
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "  kill $label (pid=$pid)"
    kill "$pid" 2>/dev/null || true
    already_killed+=("$pid")
    ((killed += 1)) || true
  fi
}

_pid_cmdline() {
  ps -p "$1" -o command= 2>/dev/null | sed 's/^[[:space:]]*//'
}

_should_kill_pid_file_entry() {
  local name="$1" pid="$2" jarpath="${3:-}"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  local cmd
  cmd="$(_pid_cmdline "$pid")"
  [[ -n "$cmd" ]] || return 0
  if [[ -n "$jarpath" ]]; then
    [[ "$cmd" == *"$jarpath"* ]] && return 0
  fi
  [[ "$cmd" == *"build/runtime-jars/${name}.jar"* ]] && return 0
  [[ "$cmd" == *"/${name}.jar"* && "$cmd" == *"runtime-jars"* ]] && return 0
  echo "  跳过 ${name} pid=${pid}（命令行与预期 jar 不符，避免误杀）" >&2
  return 1
}

_parse_pid_line() {
  local line="$1"
  _p_name="" _p_pid="" _p_jar=""
  if [[ "$line" == *$'\t'* ]]; then
    IFS=$'\t' read -r _p_name _p_pid _p_jar <<<"$line"
  else
    _p_name="${line%% *}"
    local rest="${line#"$_p_name"}"
    rest="${rest#"${rest%%[![:space:]]*}"}"
    _p_pid="${rest%% *}"
    _p_jar="${rest#"$_p_pid"}"
    _p_jar="${_p_jar#"${_p_jar%%[![:space:]]*}"}"
  fi
}

# runtime 短名 -> Maven 模块目录名（用于 /module/target/ 扫描）
_legacy_module_for_runtime() {
  case "$1" in
    orchestrator) echo batch-orchestrator ;;
    trigger) echo batch-trigger ;;
    console) echo batch-console-api ;;
    worker-import) echo batch-worker-import ;;
    worker-export) echo batch-worker-export ;;
    worker-process) echo batch-worker-process ;;
    worker-dispatch) echo batch-worker-dispatch ;;
    worker-atomic) echo batch-worker-atomic ;;
    *) echo "" ;;
  esac
}

_kill_from_pid_lines_for_names() {
  local line n matched
  for line in "${PID_FILE_LINES[@]+"${PID_FILE_LINES[@]}"}"; do
    _parse_pid_line "$line"
    matched=0
    for n in "$@"; do
      if [[ "$_p_name" == "$n" ]]; then
        matched=1
        break
      fi
    done
    [[ "$matched" -eq 1 ]] || continue
    if _should_kill_pid_file_entry "$_p_name" "$_p_pid" "$_p_jar"; then
      kill_pid "$_p_name" "$_p_pid"
    fi
  done
}

_scan_runtime_and_legacy_for_names() {
  local n pid mod abs_jar
  for n in "$@"; do
    while read -r pid; do
      [[ -z "$pid" ]] && continue
      kill_pid "$n" "$pid"
    done < <(_pids_cmd_contains "build/runtime-jars/${n}.jar")
    abs_jar="${RUNTIME_JAR_DIR}/${n}.jar"
    if [[ -f "$abs_jar" ]]; then
      while read -r pid; do
        [[ -z "$pid" ]] && continue
        kill_pid "$n(abs)" "$pid"
      done < <(_pids_cmd_contains "$abs_jar")
    fi
    mod="$(_legacy_module_for_runtime "$n")"
    [[ -n "$mod" ]] || continue
    while read -r pid; do
      [[ -z "$pid" ]] && continue
      kill_pid "$mod" "$pid"
    done < <(_pids_cmd_contains "/${mod}/target/")
  done
}

_force_kill_names() {
  local n pid mod abs_jar
  for n in "$@"; do
    while read -r pid; do
      [[ -z "$pid" ]] && continue
      echo "  force terminate pid=$pid ($n)"
      kill -9 "$pid" 2>/dev/null || true
    done < <(_pids_cmd_contains "build/runtime-jars/${n}.jar")
    abs_jar="${RUNTIME_JAR_DIR}/${n}.jar"
    if [[ -f "$abs_jar" ]]; then
      while read -r pid; do
        [[ -z "$pid" ]] && continue
        echo "  force terminate pid=$pid ($n abs)"
        kill -9 "$pid" 2>/dev/null || true
      done < <(_pids_cmd_contains "$abs_jar")
    fi
    mod="$(_legacy_module_for_runtime "$n")"
    [[ -n "$mod" ]] || continue
    while read -r pid; do
      [[ -z "$pid" ]] && continue
      echo "  force terminate pid=$pid ($mod target)"
      kill -9 "$pid" 2>/dev/null || true
    done < <(_pids_cmd_contains "/${mod}/target/")
  done
}

stop_phase() {
  local label="$1"
  shift
  echo "==> ${label}"
  local k0=$killed
  _kill_from_pid_lines_for_names "$@"
  _scan_runtime_and_legacy_for_names "$@"
  local k1=$killed
  if ((k1 > k0)); then
    echo "  等待本阶段进程退出（${STOP_WAIT_SEC:-5}s）..."
    sleep "${STOP_WAIT_SEC:-5}"
    _force_kill_names "$@"
  fi
}

_residual_force_all() {
  local left=0 name pid
  for name in "${RUNTIME_JAVA_NAMES[@]}"; do
    while read -r pid; do
      [[ -z "$pid" ]] && continue
      echo "  残留进程 kill -9 pid=$pid ($name)"
      kill -9 "$pid" 2>/dev/null || true
      ((left += 1)) || true
    done < <(_pids_cmd_contains "build/runtime-jars/${name}.jar")
  done
  if ((left > 0)); then
    echo "  已清理 ${left} 个残留 Java 进程。"
  fi
}

echo "==> 停止 Spring Boot 进程（先 worker，再 trigger/console，最后 orchestrator）..."

if [[ -f "$PID_FILE" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "${line//[[:space:]]}" ]] && continue
    PID_FILE_LINES+=("$line")
  done <"$PID_FILE"
  rm -f "$PID_FILE"
fi

stop_phase "阶段 1/3：停止 worker-import / worker-export / worker-process / worker-dispatch / worker-atomic" worker-import worker-export worker-process worker-dispatch worker-atomic
stop_phase "阶段 2/3：停止 trigger / console" trigger console
stop_phase "阶段 3/3：停止 orchestrator" orchestrator

if ((killed > 0)); then
  echo "已发出停止信号并完成分阶段清理的进程数（累计）: ${killed}"
else
  echo "  未发现运行中的 batch 进程。"
fi

_residual_force_all

[[ -f "$PID_FILE" ]] && rm -f "$PID_FILE"

# ── 端口残留检查 ──────────────────────────────────────────
_check_port_residual() {
  local names=(
    "orchestrator"
    "trigger"
    "console"
    "worker-import"
    "worker-export"
    "worker-process"
    "worker-dispatch"
    "worker-atomic"
  )
  local ports=(
    "${BATCH_ORCHESTRATOR_PORT:-18082}"
    "${BATCH_TRIGGER_PORT:-18081}"
    "${BATCH_CONSOLE_PORT:-18080}"
    "${BATCH_WORKER_IMPORT_PORT:-18083}"
    "${BATCH_WORKER_EXPORT_PORT:-18084}"
    "${BATCH_WORKER_PROCESS_PORT:-18086}"
    "${BATCH_WORKER_DISPATCH_PORT:-18085}"
    "${BATCH_WORKER_ATOMIC_PORT:-18087}"
  )
  local residuals=()
  local i
  for ((i = 0; i < ${#names[@]}; i++)); do
    local name="${names[$i]}"
    local port="${ports[$i]}"
    local pid
    pid="$(process_listen_pids "$port" | head -1)"
    if [[ -n "$pid" ]]; then
      residuals+=("  ✗ ${name} 端口 ${port} 仍被 pid=${pid} 占用")
    fi
  done
  if (( ${#residuals[@]} > 0 )); then
    echo ""
    echo "WARNING: 以下端口仍有进程残留，可能需要手动处理：" >&2
    for msg in "${residuals[@]+"${residuals[@]}"}"; do
      echo "$msg" >&2
    done
    echo "  可执行: source scripts/lib/process.sh; process_listen_pids <port>" >&2
  else
    echo "  端口检查通过，所有应用端口已释放。"
  fi
}

_check_port_residual

if [[ "${STOP_DOCKER:-}" == "1" ]]; then
  echo "==> Docker Compose 停止（STOP_DOCKER=1，保持环境不删除）..."
  _LOCAL_SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
  # shellcheck source=docker-path.sh
  source "${_LOCAL_SCRIPT_DIR}/docker-path.sh"
  ensure_docker_on_path
  unset _LOCAL_SCRIPT_DIR
  cd "$ROOT" && docker compose --env-file "$COMPOSE_ENV_FILE" stop
else
  echo "提示：仅停止 Java 进程；基础依赖（含 Redis）仍在运行。停止容器请执行: ./scripts/docker/down-apps.sh"
  echo "     或: COMPOSE_ENV_FILE=.env.test STOP_DOCKER=1 ./scripts/local/stop-all.sh"
fi
