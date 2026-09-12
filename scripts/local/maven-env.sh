#!/usr/bin/env bash
# Shared Maven command resolution for local scripts.
#
# Order:
#   1. pinned local mvnd wrapper path (~/.local/bin/mvnd by default)
#   2. repository Maven Wrapper
#   3. mvnd on PATH
#   4. mvn on PATH

batch_detect_mvnd_platform() {
  local os arch
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  arch="$(uname -m)"
  case "$arch" in
    arm64 | aarch64) arch="aarch64" ;;
    x86_64 | amd64) arch="amd64" ;;
    *) echo "unsupported-${arch}"; return 1 ;;
  esac
  case "$os" in
    darwin | linux) printf '%s-%s\n' "$os" "$arch" ;;
    *) echo "unsupported-${os}"; return 1 ;;
  esac
}

batch_mvnd_home() {
  local version platform
  version="${BATCH_MVND_VERSION:-1.0.5}"
  platform="${BATCH_MVND_PLATFORM:-$(batch_detect_mvnd_platform)}"
  printf '%s/maven-mvnd-%s-%s\n' "${BATCH_MVND_INSTALL_DIR:-$HOME/.local/share}" "$version" "$platform"
}

batch_export_mvnd_home_if_present() {
  local home
  home="${BATCH_MVND_HOME:-$(batch_mvnd_home)}"
  if [[ -d "$home" ]]; then
    export MVND_HOME="${MVND_HOME:-$home}"
  fi
}

batch_resolve_maven_command() {
  local repo_root mvnd_bin
  repo_root="${1:-$(pwd)}"
  mvnd_bin="${BATCH_MVND_BIN:-$HOME/.local/bin/mvnd}"
  if [[ -x "$mvnd_bin" ]]; then
    batch_export_mvnd_home_if_present
    printf '%s\n' "$mvnd_bin"
    return 0
  fi
  if [[ -x "$repo_root/mvnw" ]]; then
    printf '%s\n' "$repo_root/mvnw"
    return 0
  fi
  if command -v mvnd >/dev/null 2>&1; then
    batch_export_mvnd_home_if_present
    command -v mvnd
    return 0
  fi
  if command -v mvn >/dev/null 2>&1; then
    command -v mvn
    return 0
  fi
  echo "ERROR: mvnd/mvn/mvnw not found" >&2
  return 127
}
