#!/usr/bin/env bash
# 校验仓库 Shell 脚本语法，并要求 ShellCheck warning 为零。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

command -v shellcheck >/dev/null 2>&1 || {
  echo "FAIL: shellcheck is required" >&2
  exit 1
}

shell_files=()
while IFS= read -r -d '' file; do
  [[ -f "$file" ]] && shell_files+=("$file")
done < <(git ls-files --cached --others --exclude-standard -z -- '*.sh')
if ((${#shell_files[@]} == 0)); then
  echo "Shell guard passed: no tracked shell scripts"
  exit 0
fi

for file in "${shell_files[@]}"; do
  bash -n "$file"
done

report="$(mktemp)"
trap 'rm -f "$report"' EXIT

if ! printf '%s\0' "${shell_files[@]}" \
  | xargs -0 -n 20 -P 4 shellcheck -S warning -f gcc >"$report" 2>&1; then
  cat "$report" >&2
  echo "FAIL: ShellCheck warnings are not allowed" >&2
  exit 1
fi

echo "Shell guard passed: ${#shell_files[@]} scripts are syntax-valid with zero ShellCheck warnings"
