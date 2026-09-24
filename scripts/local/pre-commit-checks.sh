#!/usr/bin/env bash
# 提交前轻量门禁：只对暂存区命中的文件域执行对应检查。
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
cd "$ROOT"
# shellcheck source=../lib/python-runtime.sh
source "$ROOT/scripts/lib/python-runtime.sh"
source "$ROOT/scripts/lib/gate-result.sh"
batch_require_python

staged_files=()
while IFS= read -r -d '' file; do
  staged_files+=("$file")
done < <(git diff --cached --name-only --diff-filter=ACMR -z)

if ((${#staged_files[@]} == 0)); then
  gate_skip PRE_COMMIT_NO_STAGED_FILES pre-commit 暂存区为空
  exit 0
fi

gate_run PRE_COMMIT_DIFF_CHECK "暂存区空白与冲突标记" git diff --cached --check

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
  gate_run PRE_COMMIT_SPOTLESS "Java Spotless 格式化（${#java_files[@]} 个文件）" \
    ./mvnw -q spotless:apply
  for file in "${java_files[@]}"; do
    [[ -f "$file" ]] && git add -- "$file"
  done
fi

if ((${#shell_files[@]} > 0)); then
  check_shell_files() {
    command -v shellcheck >/dev/null 2>&1 || {
      echo "缺少 shellcheck，无法校验 Shell 变更" >&2
      return 1
    }
    for file in "${shell_files[@]}"; do
      [[ -f "$file" ]] || continue
      bash -n "$file"
      shellcheck -S warning "$file"
    done
  }
  gate_run PRE_COMMIT_SHELLCHECK "Shell 语法与 ShellCheck（${#shell_files[@]} 个文件）" \
    check_shell_files
fi

if ((workflow_changed == 1)); then
  check_workflows() {
    command -v actionlint >/dev/null 2>&1 || {
      echo "缺少 actionlint，无法校验 Workflow 变更" >&2
      return 1
    }
    actionlint
  }
  gate_run PRE_COMMIT_ACTIONLINT "GitHub Actions lint" check_workflows
fi

if ((scripts_changed == 1)); then
  gate_run PRE_COMMIT_SCRIPT_GOVERNANCE "脚本治理" \
    "$PYTHON_BIN" scripts/ci/check-script-governance.py
fi
if ((docs_changed == 1)); then
  gate_run PRE_COMMIT_DOCS_STRUCTURE "文档结构" \
    "$PYTHON_BIN" scripts/ci/check-docs-structure.py
fi
gate_run PRE_COMMIT_REPOSITORY_HYGIENE "仓库卫生" \
  "$PYTHON_BIN" scripts/ci/check-repository-hygiene.py

gate_result PASS PRE_COMMIT_ALL "所有适用的 pre-commit 门禁"
