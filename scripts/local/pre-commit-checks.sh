#!/usr/bin/env bash
# 提交前轻量门禁：只对暂存区命中的文件域执行对应检查。
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
cd "$ROOT"

staged_files=()
while IFS= read -r -d '' file; do
  staged_files+=("$file")
done < <(git diff --cached --name-only --diff-filter=ACMR -z)

if ((${#staged_files[@]} == 0)); then
  exit 0
fi

echo "[pre-commit] 检查暂存区空白和冲突标记..."
git diff --cached --check

java_files=()
shell_files=()
docs_changed=0
scripts_changed=0
workflow_changed=0
for file in "${staged_files[@]}"; do
  [[ "$file" == *.java ]] && java_files+=("$file")
  [[ "$file" == *.sh ]] && shell_files+=("$file")
  [[ "$file" == *.md || "$file" == docs/* ]] && docs_changed=1
  [[ "$file" == scripts/* || "$file" == load-tests/scripts/* || "$file" == .githooks/* ]] \
    && scripts_changed=1
  [[ "$file" == .github/workflows/* || "$file" == .github/actions/* ]] && workflow_changed=1
done

if ((${#java_files[@]} > 0)); then
  echo "[pre-commit] Spotless 格式化 ${#java_files[@]} 个 Java 文件..."
  ./mvnw -q spotless:apply
  for file in "${java_files[@]}"; do
    [[ -f "$file" ]] && git add -- "$file"
  done
fi

if ((${#shell_files[@]} > 0)); then
  command -v shellcheck >/dev/null 2>&1 || {
    echo "[pre-commit] 缺少 shellcheck，无法校验 Shell 变更" >&2
    exit 1
  }
  echo "[pre-commit] 校验 ${#shell_files[@]} 个 Shell 文件..."
  for file in "${shell_files[@]}"; do
    [[ -f "$file" ]] || continue
    bash -n "$file"
    shellcheck -S warning "$file"
  done
fi

if ((workflow_changed == 1)); then
  command -v actionlint >/dev/null 2>&1 || {
    echo "[pre-commit] 缺少 actionlint，无法校验 Workflow 变更" >&2
    exit 1
  }
  echo "[pre-commit] 校验 GitHub Actions..."
  actionlint
fi

if ((scripts_changed == 1)); then
  python3 scripts/ci/check-script-governance.py
fi
if ((docs_changed == 1)); then
  python3 scripts/ci/check-docs-structure.py
fi
python3 scripts/ci/check-repository-hygiene.py

echo "[pre-commit] 轻量门禁通过"
