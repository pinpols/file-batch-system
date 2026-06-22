#!/usr/bin/env bash
set -euo pipefail

# 多语言 SDK live transport gate。
# 预期外部已经启动 Kafka/Redpanda,并通过 KAFKA_BOOTSTRAP 暴露 PLAINTEXT broker。
# 覆盖:
#   Java       EmbeddedKafka + HTTP fake testkit
#   Python     live Kafka + aiohttp FakeBatchPlatform
#   TypeScript live kafkajs adapter
#   Go         live segmentio/kafka-go adapter
#   Rust       live rdkafka adapter(feature=gated)

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:19092}"
PYTHON_BIN="${PYTHON:-}"
if [[ -z "$PYTHON_BIN" ]]; then
  for candidate in python3.12 python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
      PYTHON_BIN="$candidate"
      break
    fi
  done
fi

echo "[sdk-live] KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP}"
echo "[sdk-live] Python=$("$PYTHON_BIN" -c 'import sys; print(sys.executable)')"
"$PYTHON_BIN" - <<'PY'
import sys

if sys.version_info < (3, 12):
    raise SystemExit(f"Python SDK live test requires Python >= 3.12, got {sys.version}")
PY

echo "[sdk-live] Java testkit"
(cd "$ROOT" && mvn -B -pl sdk/java/testkit -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=FakeBatchPlatformSelfTest \
  test)

echo "[sdk-live] Python live Kafka + HTTP fake"
(
  cd "$ROOT/sdk/python"
  PYTHON_VENV_DIR="${PYTHON_VENV_DIR:-${TMPDIR:-/tmp}/batch-sdk-python-live-venv}"
  rm -rf "$PYTHON_VENV_DIR"
  "$PYTHON_BIN" -m venv "$PYTHON_VENV_DIR"
  VENV_PYTHON="$PYTHON_VENV_DIR/bin/python"
  "$VENV_PYTHON" -m pip install --upgrade pip
  "$VENV_PYTHON" -m pip install -e .[dev]
  KAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" "$VENV_PYTHON" -m pytest tests/test_kafka_live_integration.py -v
)

echo "[sdk-live] TypeScript live Kafka adapter"
(
  cd "$ROOT/sdk/typescript"
  npm install --include=optional --package-lock=false --no-audit --no-fund
  node --test --experimental-strip-types 'tests/lifecycle.test.ts' 'tests/transport.test.ts'
  KAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" node --test --experimental-strip-types 'kafka/*.integration.test.ts'
)

echo "[sdk-live] Go client lifecycle + HTTP transport"
(
  cd "$ROOT/sdk/go"
  go test ./...
)

echo "[sdk-live] Go live Kafka adapter"
(
  cd "$ROOT/sdk/go/kafka"
  KAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" go test ./...
)

echo "[sdk-live] Rust client lifecycle + HTTP transport"
(
  cd "$ROOT/sdk/rust"
  cargo test --features http
)

echo "[sdk-live] Rust live Kafka adapter"
(
  cd "$ROOT/sdk/rust"
  if ! command -v cmake >/dev/null 2>&1; then
    CMAKE_VENV_DIR="${CMAKE_VENV_DIR:-${TMPDIR:-/tmp}/batch-sdk-cmake-venv}"
    if [[ ! -x "$CMAKE_VENV_DIR/bin/cmake" ]]; then
      rm -rf "$CMAKE_VENV_DIR"
      "$PYTHON_BIN" -m venv "$CMAKE_VENV_DIR"
      "$CMAKE_VENV_DIR/bin/python" -m pip install --upgrade pip
      "$CMAKE_VENV_DIR/bin/python" -m pip install cmake
    fi
    export PATH="$CMAKE_VENV_DIR/bin:$PATH"
  fi
  cargo clean
  KAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" cargo test --features kafka end_to_end_consume_against_real_broker -- --nocapture
)

echo "[sdk-live] all SDK live transport checks passed"
