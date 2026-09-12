#!/usr/bin/env bash
# Install the pinned Maven Daemon used by local build scripts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=maven-env.sh
source "$ROOT/scripts/local/maven-env.sh"

VERSION="${BATCH_MVND_VERSION:-1.0.5}"
PLATFORM="${BATCH_MVND_PLATFORM:-$(batch_detect_mvnd_platform)}"
INSTALL_DIR="${BATCH_MVND_INSTALL_DIR:-$HOME/.local/share}"
BIN_DIR="${BATCH_MVND_BIN_DIR:-$HOME/.local/bin}"
MVND_HOME="${BATCH_MVND_HOME:-$INSTALL_DIR/maven-mvnd-${VERSION}-${PLATFORM}}"
ARCHIVE="maven-mvnd-${VERSION}-${PLATFORM}.tar.gz"
URL_PRIMARY="https://downloads.apache.org/maven/mvnd/${VERSION}/${ARCHIVE}"
URL_ARCHIVE="https://archive.apache.org/dist/maven/mvnd/${VERSION}/${ARCHIVE}"

if [[ "${1:-}" == "--check" ]]; then
  echo "version=$VERSION"
  echo "platform=$PLATFORM"
  echo "mvnd_home=$MVND_HOME"
  echo "mvnd_bin=${BATCH_MVND_BIN:-$BIN_DIR/mvnd}"
  batch_resolve_maven_command "$ROOT"
  exit 0
fi

mkdir -p "$INSTALL_DIR" "$BIN_DIR"

if [[ -x "$MVND_HOME/bin/mvnd" ]]; then
  ln -sfn "$MVND_HOME/bin/mvnd" "$BIN_DIR/mvnd"
  echo "mvnd already installed: $MVND_HOME"
  "$BIN_DIR/mvnd" --version | sed -n '1,3p'
  exit 0
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

download() {
  local url="$1" out="$2"
  curl -fsSL "$url" -o "$out"
}

if ! download "$URL_PRIMARY" "$tmpdir/$ARCHIVE"; then
  download "$URL_ARCHIVE" "$tmpdir/$ARCHIVE"
fi

if download "${URL_PRIMARY}.sha512" "$tmpdir/${ARCHIVE}.sha512" \
  || download "${URL_ARCHIVE}.sha512" "$tmpdir/${ARCHIVE}.sha512"; then
  (
    cd "$tmpdir"
    shasum -a 512 -c "${ARCHIVE}.sha512"
  )
fi

rm -rf "$MVND_HOME"
tar -xzf "$tmpdir/$ARCHIVE" -C "$INSTALL_DIR"
ln -sfn "$MVND_HOME/bin/mvnd" "$BIN_DIR/mvnd"

echo "mvnd installed: $MVND_HOME"
"$BIN_DIR/mvnd" --version | sed -n '1,3p'
