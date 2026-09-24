#!/usr/bin/env bash
set -euo pipefail

# 五语言 SDK 控制面 HTTP 的真实 loopback socket 故障矩阵。DNS 顺序由测试注入，
# TCP accept queue 黑洞、建连竞速、超时和 POST 均走操作系统真实 socket。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PYTHON_BIN="${SDK_HE_PYTHON:-}"
if [[ -z "$PYTHON_BIN" ]]; then
  if [[ -x "$ROOT/sdk/python/.venv/bin/python" ]]; then
    PYTHON_BIN="$ROOT/sdk/python/.venv/bin/python"
  else
    PYTHON_BIN="${PYTHON:-python3}"
  fi
fi

MATRIX_FILE="$(mktemp "${TMPDIR:-/tmp}/batch-sdk-he-matrix.XXXXXX")"
COUNTS_FILE="$(mktemp "${TMPDIR:-/tmp}/batch-sdk-he-counts.XXXXXX")"
FIXTURE_PID=""

cleanup() {
  if [[ -n "$FIXTURE_PID" ]]; then
    kill "$FIXTURE_PID" >/dev/null 2>&1 || true
    wait "$FIXTURE_PID" >/dev/null 2>&1 || true
  fi
  rm -f "$MATRIX_FILE" "$COUNTS_FILE"
}
trap cleanup EXIT INT TERM

"$PYTHON_BIN" "$ROOT/scripts/ci/sdk-happy-eyeballs-fixture.py" \
  --matrix-file "$MATRIX_FILE" \
  --counts-file "$COUNTS_FILE" &
FIXTURE_PID="$!"

for _ in $(seq 1 100); do
  if [[ -s "$MATRIX_FILE" ]]; then
    break
  fi
  if ! kill -0 "$FIXTURE_PID" >/dev/null 2>&1; then
    echo "[sdk-he] fixture exited before readiness" >&2
    exit 1
  fi
  sleep 0.1
done
if [[ ! -s "$MATRIX_FILE" ]]; then
  echo "[sdk-he] fixture readiness timeout" >&2
  exit 1
fi

export BATCH_SDK_HE_MATRIX_FILE="$MATRIX_FILE"
export NO_PROXY="localhost,127.0.0.1,::1${NO_PROXY:+,$NO_PROXY}"
export no_proxy="localhost,127.0.0.1,::1${no_proxy:+,$no_proxy}"

echo "[sdk-he] Java real socket matrix"
(cd "$ROOT" && ./mvnw -B -ntp -q -pl sdk/java/core \
  -Dtest=PlatformHttpClientTest#realSocketHappyEyeballsMatrix \
  -Dsurefire.rerunFailingTestsCount=0 test)

echo "[sdk-he] Python real socket matrix"
(cd "$ROOT/sdk/python" && \
  "$PYTHON_BIN" -m pytest -q \
    tests/test_happy_eyeballs.py::test_real_socket_happy_eyeballs_matrix)

echo "[sdk-he] TypeScript real socket matrix"
(cd "$ROOT/sdk/typescript" && \
  node --test --test-name-pattern='real socket Happy Eyeballs matrix' \
    --experimental-strip-types tests/transport.test.ts)

echo "[sdk-he] Go real socket matrix"
(cd "$ROOT/sdk/go" && \
  go test ./client -run TestHTTPTransport_RealSocketHappyEyeballsMatrix \
    -count=1 -timeout=15s)

echo "[sdk-he] Rust real socket matrix"
(cd "$ROOT/sdk/rust" && \
  cargo test --features http real_socket_happy_eyeballs_matrix -- --nocapture)

"$PYTHON_BIN" - "$COUNTS_FILE" <<'PY'
import json
import sys
from pathlib import Path

counts = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = {
    "ipv4_only": 5,
    "ipv6_only": 5,
    "dual_live": 5,
    "ipv6_blackhole": 5,
    "ipv4_blackhole": 5,
    "all_blackhole": 0,
}
if counts != expected:
    raise SystemExit(f"SDK HE request-count mismatch: expected={expected}, actual={counts}")
print(f"[sdk-he] request counts verified: {counts}")
PY

echo "[sdk-he] all five-language real socket matrices passed"
