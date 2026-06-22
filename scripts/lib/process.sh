#!/usr/bin/env bash
# Cross-platform helpers for local process/port handling.

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

process_port_is_listening() {
  local port="${1:?port required}"
  [[ -n "$(process_listen_pids "$port" | head -1)" ]]
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
