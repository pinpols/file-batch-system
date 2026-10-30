#!/usr/bin/env bash
# 用途：保留 SDK 测试日志和真实退出码，供 CI 汇总。
# 用法：bash scripts/ci/capture-sdk-test.sh <名称> -- <命令> [参数...]。
set -euo pipefail
if [[ $# -lt 3 || "$2" != "--" || ! "$1" =~ ^[a-zA-Z0-9._-]+$ ]]; then
  echo '用法: capture-sdk-test.sh <名称> -- <命令> [参数...]' >&2
  exit 2
fi
name="$1"
shift 2
report_dir="${SDK_TEST_REPORT_DIR:?SDK_TEST_REPORT_DIR 未设置}"
mkdir -p "$report_dir"
set +e
"$@" 2>&1 | tee "$report_dir/$name.log"
results=("${PIPESTATUS[@]}")
set -e
printf '%s\n' "${results[0]}" > "$report_dir/$name.exit"
if [[ "${results[1]}" -ne 0 ]]; then
  exit "${results[1]}"
fi
exit "${results[0]}"
