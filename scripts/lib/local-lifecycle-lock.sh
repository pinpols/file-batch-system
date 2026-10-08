#!/usr/bin/env bash

# 串行化本地构建、启停操作，避免端口探测和 PID 文件更新互相覆盖。
batch_local_lifecycle_lock_acquire() {
  local root="$1"
  local inherited_dir="${BATCH_LOCAL_LIFECYCLE_LOCK_DIR:-}"
  local inherited_pid=""

  if [[ "${BATCH_LOCAL_LIFECYCLE_LOCK_HELD:-0}" == "1" && -n "$inherited_dir" ]]; then
    inherited_pid="$(awk -F '\t' 'NR == 1 {print $1}' "$inherited_dir/owner" 2>/dev/null || true)"
    local inherited_root=""
    inherited_root="$(awk -F '\t' 'NR == 1 {print $2}' "$inherited_dir/owner" 2>/dev/null || true)"
    if [[ "$inherited_root" == "$root" && "$inherited_pid" =~ ^[0-9]+$ ]] \
        && kill -0 "$inherited_pid" 2>/dev/null; then
      BATCH_LOCAL_LIFECYCLE_LOCK_OWNED=0
      return 0
    fi
  fi

  local root_hash lock_parent lock_dir recovery_dir timeout start owner_pid stale_dir
  root_hash="$(printf '%s' "$root" | cksum | awk '{print $1}')"
  lock_parent="${TMPDIR:-/tmp}"
  lock_dir="$lock_parent/file-batch-system-local-${UID:-$(id -u)}-${root_hash}.lock"
  recovery_dir="${lock_dir}.recovery"
  timeout="${BATCH_LOCAL_LOCK_TIMEOUT_SECONDS:-600}"
  if [[ ! "$timeout" =~ ^[0-9]+$ ]]; then
    echo "ERROR: BATCH_LOCAL_LOCK_TIMEOUT_SECONDS 必须是非负整数，当前值='$timeout'" >&2
    return 2
  fi
  start="$SECONDS"
  mkdir -p "$lock_parent"

  while true; do
    if [[ -d "$recovery_dir" ]]; then
      sleep 1
      continue
    fi
    if mkdir "$lock_dir" 2>/dev/null; then
      break
    fi

    owner_pid="$(awk -F '\t' 'NR == 1 {print $1}' "$lock_dir/owner" 2>/dev/null || true)"
    if [[ -n "$owner_pid" && ! "$owner_pid" =~ ^[0-9]+$ ]]; then
      owner_pid=""
    fi

    if { [[ -n "$owner_pid" ]] && ! kill -0 "$owner_pid" 2>/dev/null; } \
        || { [[ -z "$owner_pid" ]] && (( SECONDS - start >= 5 )); }; then
      # 串行回收失效锁；目录改名后不再操作原锁路径。
      if mkdir "$recovery_dir" 2>/dev/null; then
        owner_pid="$(awk -F '\t' 'NR == 1 {print $1}' "$lock_dir/owner" 2>/dev/null || true)"
        if { [[ -n "$owner_pid" ]] && ! kill -0 "$owner_pid" 2>/dev/null; } \
            || { [[ -z "$owner_pid" ]] && (( SECONDS - start >= 5 )); }; then
          stale_dir="${lock_dir}.stale.$$.$RANDOM"
          if mv "$lock_dir" "$stale_dir" 2>/dev/null; then
            rm -f "$stale_dir/owner"
            rmdir "$stale_dir" 2>/dev/null || true
          fi
        fi
        rmdir "$recovery_dir" 2>/dev/null || true
        continue
      fi
    fi

    if [[ "$timeout" =~ ^[0-9]+$ ]] && (( timeout > 0 && SECONDS - start >= timeout )); then
      echo "ERROR: 等待本地生命周期锁超时(${timeout}s): $lock_dir" >&2
      return 1
    fi
    if (( SECONDS - start == 0 )); then
      echo "等待其他本地构建/启停操作完成..."
    fi
    sleep 1
  done

  printf '%s\t%s\n' "$$" "$root" >"$lock_dir/owner"
  BATCH_LOCAL_LIFECYCLE_LOCK_DIR="$lock_dir"
  BATCH_LOCAL_LIFECYCLE_LOCK_HELD=1
  BATCH_LOCAL_LIFECYCLE_LOCK_OWNED=1
  export BATCH_LOCAL_LIFECYCLE_LOCK_DIR BATCH_LOCAL_LIFECYCLE_LOCK_HELD
}

batch_local_lifecycle_lock_release() {
  [[ "${BATCH_LOCAL_LIFECYCLE_LOCK_OWNED:-0}" == "1" ]] || return 0
  local owner_pid=""
  owner_pid="$(awk -F '\t' 'NR == 1 {print $1}' "${BATCH_LOCAL_LIFECYCLE_LOCK_DIR:-}/owner" 2>/dev/null || true)"
  if [[ "$owner_pid" == "$$" ]]; then
    rm -f "$BATCH_LOCAL_LIFECYCLE_LOCK_DIR/owner"
    rmdir "$BATCH_LOCAL_LIFECYCLE_LOCK_DIR" 2>/dev/null || true
  fi
  unset BATCH_LOCAL_LIFECYCLE_LOCK_DIR BATCH_LOCAL_LIFECYCLE_LOCK_HELD
  BATCH_LOCAL_LIFECYCLE_LOCK_OWNED=0
}
