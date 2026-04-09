#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# generate-sdk.sh — 从 batch-console-api 导出 OpenAPI spec 并
# 生成 TypeScript 客户端 SDK。
#
# 用法:
#   ./scripts/ci/generate-sdk.sh              # 仅生成
#   ./scripts/ci/generate-sdk.sh --publish    # 生成并发布到 npm
# ──────────────────────────────────────────────────────────────
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
CONSOLE_DIR="$ROOT_DIR/batch-console-api"
OUTPUT_DIR="$CONSOLE_DIR/target/generated-sdk/typescript"
SPEC_FILE="$CONSOLE_DIR/target/openapi.json"

PUBLISH=false
if [[ "${1:-}" == "--publish" ]]; then
    PUBLISH=true
fi

printf '=== Step 1: Build project (skip tests) ===\n'
"$ROOT_DIR/mvnw" -f "$ROOT_DIR/pom.xml" install -DskipTests -q

printf '=== Step 2: Export OpenAPI spec & generate SDK ===\n'
"$ROOT_DIR/mvnw" -f "$CONSOLE_DIR/pom.xml" \
    verify -Popenapi-codegen -DskipTests \
    -Dspring-boot.run.arguments="--spring.profiles.active=openapi-export" \
    || {
        printf 'ERROR: OpenAPI codegen failed. Ensure the app can start locally.\n' >&2
        exit 1
    }

if [[ ! -f "$SPEC_FILE" ]]; then
    printf 'ERROR: OpenAPI spec not found at %s\n' "$SPEC_FILE" >&2
    exit 1
fi

printf '=== Step 3: Verify generated SDK ===\n'
if [[ ! -d "$OUTPUT_DIR" ]]; then
    printf 'ERROR: Generated SDK directory not found at %s\n' "$OUTPUT_DIR" >&2
    exit 1
fi

FILE_COUNT=$(find "$OUTPUT_DIR" -name '*.ts' | wc -l | tr -d ' ')
printf 'Generated %s TypeScript files\n' "$FILE_COUNT"

# Copy spec to a stable location for version control or downstream consumers
cp "$SPEC_FILE" "$ROOT_DIR/docs/openapi.json" 2>/dev/null || true

if [[ "$PUBLISH" == true ]]; then
    printf '=== Step 4: Publish SDK to npm ===\n'
    if ! command -v npm &>/dev/null; then
        printf 'ERROR: npm not found in PATH\n' >&2
        exit 1
    fi
    cd "$OUTPUT_DIR"
    npm install
    npm run build 2>/dev/null || true
    npm publish --access public
    printf 'SDK published successfully.\n'
else
    printf 'SDK generated at: %s\n' "$OUTPUT_DIR"
    printf 'OpenAPI spec at:  %s\n' "$SPEC_FILE"
    printf 'Run with --publish to publish to npm.\n'
fi
