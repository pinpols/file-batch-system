#!/usr/bin/env bash
# 用途：可执行: source scripts/lib/process.sh; process_listen_pids <port>。
# 用法：由调用脚本 source scripts/lib/process.sh 后使用公共变量或函数。
# 本地进程与端口管理的跨平台辅助函数。

process_listen_pids() {
  local port="${1:?port required}"

  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk '/^[0-9]+$/'
    return 0
  fi

  if command -v ss >/dev/null 2>&1; then
    ss -H -ltnp "sport = :$port" 2>/dev/null \
      | sed -nE 's/.*pid=([0-9]+).*/\1/p' \
      | sort -u
    return 0
  fi

  if command -v fuser >/dev/null 2>&1; then
    fuser -n tcp "$port" 2>/dev/null | tr ' ' '\n' | awk '/^[0-9]+$/'
    return 0
  fi

  return 0
}

process_first_listen_pid() {
  local port="${1:?port required}"
  local first_pid="" pid
  while IFS= read -r pid; do
    if [[ -z "$first_pid" && "$pid" =~ ^[0-9]+$ ]]; then
      first_pid="$pid"
    fi
  done < <(process_listen_pids "$port")
  if [[ -n "$first_pid" ]]; then
    printf '%s\n' "$first_pid"
  fi
  return 0
}

process_port_is_listening() {
  local port="${1:?port required}"
  [[ -n "$(process_first_listen_pid "$port")" ]]
}

process_pid_uses_jar() {
  local pid="${1:?pid required}"
  local expected_jar="${2:?jar path required}"
  local command_line process_cwd relative_jar
  [[ "$pid" =~ ^[0-9]+$ && "$expected_jar" == /* ]] || return 1
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [[ -n "$command_line" ]] || return 1
  [[ " $command_line " == *" -jar $expected_jar "* ]] && return 0

  process_cwd="$(process_pid_cwd "$pid")" || return 1
  [[ "$expected_jar" == "$process_cwd"/* ]] || return 1
  relative_jar="${expected_jar#"$process_cwd"/}"
  [[ " $command_line " == *" -jar $relative_jar "* ]]
}

process_pid_cwd() {
  local pid="${1:?pid required}"
  local process_cwd=""
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1

  if [[ -e "/proc/$pid/cwd" ]]; then
    process_cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null || true)"
  elif command -v lsof >/dev/null 2>&1; then
    process_cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
  fi

  [[ "$process_cwd" == /* ]] || return 1
  printf '%s\n' "$process_cwd"
}

process_pid_command_contains_path() {
  local pid="${1:?pid required}"
  local expected_path="${2:?path required}"
  local command_line
  [[ "$pid" =~ ^[0-9]+$ && "$expected_path" == /* ]] || return 1
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [[ -n "$command_line" && "$command_line" == *"$expected_path"* ]]
}

process_listeners_owned_by_jar() {
  local port="${1:?port required}" expected_jar="${2:?jar path required}"
  local pids pid
  local -a owned_pids=()
  pids="$(process_listen_pids "$port")" || return 1
  while IFS= read -r pid; do
    [[ -n "$pid" ]] || continue
    if ! process_pid_uses_jar "$pid" "$expected_jar"; then
      echo "ERROR: port $port is occupied by a process outside this worktree; refusing to stop it (pid=$pid, expected=$expected_jar)" >&2
      return 1
    fi
    owned_pids+=("$pid")
  done <<<"$pids"
  printf '%s\n' "${owned_pids[@]}"
}

process_kill_listeners() {
  local port="${1:?port required}"
  local signal="${2:-TERM}"
  local pid
  process_listen_pids "$port" | while IFS= read -r pid; do
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    kill "-$signal" "$pid" 2>/dev/null || true
  done
}
