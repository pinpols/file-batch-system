#!/usr/bin/env bash
# 契约防漂移 snapshot 测试(迁移方案 §7.2):给定固定 alert_routing_config 行 → 固定 alertmanager.yml 输出。
# 生成器逻辑一改动、输出漂移即红。可在 CI/本地跑:bash scripts/ops/gen-alertmanager-config-test.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INPUT="$HERE/testdata/alert-routing-sample.json"
EXPECTED="$HERE/testdata/alert-routing-sample.expected.yml"

ACTUAL="$(mktemp)"
trap 'rm -f "$ACTUAL"' EXIT

python3 "$HERE/gen-alertmanager-config.py" --input "$INPUT" --output "$ACTUAL"

if diff -u "$EXPECTED" "$ACTUAL"; then
  echo "OK: gen-alertmanager-config snapshot matches"
else
  echo "FAIL: gen-alertmanager-config output drifted from snapshot" >&2
  echo "  若为有意变更,更新 $EXPECTED" >&2
  exit 1
fi

# 若本机有 amtool,顺带校验渲染结果语法(可选,缺则跳过)。
if command -v amtool >/dev/null 2>&1; then
  amtool check-config "$ACTUAL" && echo "OK: amtool check-config passed"
else
  echo "SKIP: amtool not installed (上线/CI 环境校验)"
fi
