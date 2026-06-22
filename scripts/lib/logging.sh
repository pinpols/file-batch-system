#!/usr/bin/env bash
# 统一本地日志布局:
#   logs/current/<kind>/      常驻进程当前日志(app/docker)
#   logs/runs/<kind>/<run>/   一次性任务日志(test/be-acceptance/sim)
#   logs/archive/legacy/      首次迁移旧目录时的历史归档
#   logs/pids/                本地进程 pid 文件

log_stamp() {
  date +%Y%m%d-%H%M%S
}

log_git_sha() {
  git -C "$1" rev-parse --short HEAD 2>/dev/null || printf 'nogit'
}

log_sanitize_label() {
  printf '%s' "$1" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -E 's/[^a-z0-9._-]+/-/g; s/^-+//; s/-+$//'
}

log_run_id() {
  local root="$1"
  local label="${2:-run}"
  label="$(log_sanitize_label "$label")"
  printf '%s-%s-%s' "$label" "$(log_stamp)" "$(log_git_sha "$root")"
}

log_init_layout() {
  local root="$1"
  mkdir -p \
    "$root/logs/current" \
    "$root/logs/runs" \
    "$root/logs/archive/legacy" \
    "$root/logs/pids"
}

log_archive_legacy_path() {
  local root="$1"
  local name="$2"
  name="$(printf '%s' "$name" | tr '/' '-')"
  printf '%s/logs/archive/legacy/%s-%s' "$root" "$name" "$(log_stamp)"
}

log_link_dir() {
  local root="$1"
  local legacy_name="$2"
  local target="$3"
  local legacy="$root/logs/$legacy_name"

  mkdir -p "$target" "$(dirname "$legacy")"
  if [[ -L "$legacy" ]]; then
    rm -f "$legacy"
  elif [[ -e "$legacy" ]]; then
    local archive
    archive="$(log_archive_legacy_path "$root" "$legacy_name")"
    mkdir -p "$(dirname "$archive")"
    mv "$legacy" "$archive"
  fi
  ln -s "$target" "$legacy"
}

log_current_dir() {
  local root="$1"
  local kind="$2"
  local legacy_name="${3:-$kind}"
  local target="$root/logs/current/$kind"
  local legacy="$root/logs/$legacy_name"

  log_init_layout "$root"
  mkdir -p "$(dirname "$target")"
  if [[ -L "$legacy" ]]; then
    rm -f "$legacy"
  elif [[ -d "$legacy" && ! -e "$target" ]]; then
    mv "$legacy" "$target"
  elif [[ -e "$legacy" ]]; then
    local archive
    archive="$(log_archive_legacy_path "$root" "$legacy_name")"
    mkdir -p "$(dirname "$archive")"
    mv "$legacy" "$archive"
  fi
  mkdir -p "$target"
  ln -sfn "$target" "$legacy"
  printf '%s' "$target"
}

log_run_dir() {
  local root="$1"
  local kind="$2"
  local label="${3:-$kind}"
  local run_id="${LOG_RUN_ID:-$(log_run_id "$root" "$label")}"
  local dir="$root/logs/runs/$kind/$run_id"
  local latest="$root/logs/runs/$kind/latest"

  log_init_layout "$root"
  mkdir -p "$dir"
  if [[ -L "$latest" || -f "$latest" ]]; then
    rm -f "$latest"
  elif [[ -e "$latest" ]]; then
    local archive
    archive="$(log_archive_legacy_path "$root" "runs-$kind-latest")"
    mkdir -p "$(dirname "$archive")"
    mv "$latest" "$archive"
  fi
  ln -sfn "$dir" "$latest"
  printf '%s' "$dir"
}

log_pid_file() {
  local root="$1"
  local filename="${2:-start-all.pids}"
  local target="$root/logs/pids/$filename"
  local legacy="$root/logs/$filename"

  log_init_layout "$root"
  if [[ -L "$legacy" ]]; then
    rm -f "$legacy"
  elif [[ -f "$legacy" && ! -f "$target" ]]; then
    mv "$legacy" "$target"
  elif [[ -e "$legacy" ]]; then
    local archive
    archive="$(log_archive_legacy_path "$root" "$filename")"
    mkdir -p "$(dirname "$archive")"
    mv "$legacy" "$archive"
  fi
  ln -sfn "$target" "$legacy"
  printf '%s' "$target"
}
