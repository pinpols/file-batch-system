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
mapper_xml_files=()
docs_changed=0
scripts_changed=0
workflow_changed=0
loc_affecting_changed=0
env_file_changed=0
config_default_changed=0
feature_switch_changed=0
env_governance_changed=0
maven_descriptor_changed=0
helm_changed=0
for file in "${staged_files[@]}"; do
  [[ "$file" == *.java ]] && java_files+=("$file")
  [[ "$file" == *.sh ]] && shell_files+=("$file")
  [[ "$file" == *Mapper.xml ]] && mapper_xml_files+=("$file")
  [[ "$file" == *.md || "$file" == docs/* ]] && docs_changed=1
  [[ "$file" == scripts/* || "$file" == load-tests/scripts/* || "$file" == .githooks/* ]] \
    && scripts_changed=1
  [[ "$file" == .github/workflows/* || "$file" == .github/actions/* ]] && workflow_changed=1
  [[ "$file" == .env* ]] && env_file_changed=1
  [[ "$file" == *.yml || "$file" == *.yaml || "$file" == docker-compose*.yml || "$file" == .env* ]] \
    && config_default_changed=1
  [[ "$file" == docs/runbook/feature-switch-registry.yml || "$file" == *.yml || "$file" == *.yaml || "$file" == docker-compose*.yml ]] \
    && feature_switch_changed=1
  [[ "$file" == .env* || "$file" == docs/runbook/environment-variable-governance.md || "$file" == docs/runbook/feature-switches.md || "$file" == docs/runbook/config-ops-tiering.md || "$file" == scripts/ci/check-env-variable-governance.py ]] \
    && env_governance_changed=1
  [[ "$file" == pom.xml || "$file" == */pom.xml ]] && maven_descriptor_changed=1
  [[ "$file" == helm/* ]] && helm_changed=1
  if [[ "$file" != docs/* && "$file" != db/migration/* ]]; then
    case "$file" in
      *.java|*.sh|*.py|*.yml|*.yaml|*.xml|*.ts|*.tsx|*.rs|*.go|*.toml|*.properties|*.sql)
        loc_affecting_changed=1
        ;;
    esac
  fi
done

if ((${#java_files[@]} > 0)); then
  gate_run PRE_COMMIT_SPOTLESS "Java Spotless 格式化（${#java_files[@]} 个文件）" \
    ./mvnw -q spotless:apply
  gate_run PRE_COMMIT_JAVA_LOGGING "Java 日志与异常输出治理" \
    "$PYTHON_BIN" scripts/ci/check-java-logging-governance.py "${java_files[@]}"
  gate_run PRE_COMMIT_JAVA_READABILITY "Java 可读性约定" \
    "$PYTHON_BIN" scripts/ci/check-java-readability.py "${java_files[@]}"
  gate_run PRE_COMMIT_JAVA_TEXT_BLOCK_STYLE "Java 文本块格式" \
    "$PYTHON_BIN" scripts/ci/check-java-text-block-style.py "${java_files[@]}"
  gate_run PRE_COMMIT_JAVA_SUPPRESSION_REGISTRY "Java 抑制项注册表" \
    "$PYTHON_BIN" scripts/ci/check-java-suppression-registry.py "${java_files[@]}"
  gate_run PRE_COMMIT_MAPOF_NULL_VALUES "Map/List/Set.of 空值风险" \
    "$PYTHON_BIN" scripts/ci/check-mapof-null-values.py "${java_files[@]}"
  for file in "${java_files[@]}"; do
    [[ -f "$file" ]] && git add -- "$file"
  done
fi

if ((${#mapper_xml_files[@]} > 0)); then
  gate_run PRE_COMMIT_MYBATIS_GENERATED_KEYS "MyBatis generated key 列限制" \
    "$PYTHON_BIN" scripts/ci/check-mybatis-generated-key-columns.py "${mapper_xml_files[@]}"
  gate_run PRE_COMMIT_NO_POSITIONAL_INSERT_SELECT_STAR "禁止位置式 INSERT SELECT *" \
    "$PYTHON_BIN" scripts/ci/check-no-positional-insert-select-star.py "${mapper_xml_files[@]}"
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
  gate_run PRE_COMMIT_SHELL_LINUX_PORTABILITY "Shell Linux 可移植性" \
    "$PYTHON_BIN" scripts/ci/check-shell-linux-portability.py "${shell_files[@]}"
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
  gate_run PRE_COMMIT_DOC_TIMESTAMP_POLICY "文档日期命名策略" \
    "$PYTHON_BIN" scripts/ci/check-doc-timestamp-policy.py
  gate_run PRE_COMMIT_CODE_DOC_REFERENCES "代码与文档路径引用" \
    "$PYTHON_BIN" scripts/ci/check-code-doc-references.py
fi
if ((env_file_changed == 1)); then
  gate_run PRE_COMMIT_ENV_FILE_SHELL_SAFETY ".env 文件 Shell 安全" \
    "$PYTHON_BIN" scripts/ci/check-env-file-shell-safety.py
fi
if ((config_default_changed == 1)); then
  gate_run PRE_COMMIT_CONFIG_DEFAULTS_SYNC "配置默认值同步" \
    "$PYTHON_BIN" scripts/ci/check-config-defaults-sync.py --check
fi
if ((feature_switch_changed == 1)); then
  gate_run PRE_COMMIT_FEATURE_SWITCH_REGISTRY "功能开关注册表" \
    "$PYTHON_BIN" scripts/ci/check-feature-switch-registry.py
fi
if ((env_governance_changed == 1)); then
  gate_run PRE_COMMIT_ENV_VARIABLE_GOVERNANCE "环境变量治理" \
    "$PYTHON_BIN" scripts/ci/check-env-variable-governance.py
fi
if ((maven_descriptor_changed == 1)); then
  gate_run PRE_COMMIT_DEPENDENCY_BOUNDARIES "Maven 模块依赖边界" \
    "$PYTHON_BIN" scripts/ci/check-dependency-boundaries.py
fi
if ((helm_changed == 1)); then
  gate_run PRE_COMMIT_HELM_ENV_SYNC "Helm 环境变量同步" \
    "$PYTHON_BIN" scripts/ci/check-helm-env-sync.py
  gate_run PRE_COMMIT_PRODUCTION_OVERLAY "Helm 生产配置安全" \
    "$PYTHON_BIN" scripts/ci/check-production-overlay-safety.py
fi
if ((loc_affecting_changed == 1)); then
  update_loc_snapshot() {
    local staged_tree
    local snapshot_commit
    local tmp_dir
    local tmp_worktree

    staged_tree="$(git write-tree)"
    snapshot_commit="$(git commit-tree "$staged_tree" -p HEAD -m pre-commit-loc-snapshot)"
    tmp_dir="$(mktemp -d)"
    tmp_worktree="$tmp_dir/worktree"
    cleanup_loc_worktree() {
      env -u GIT_INDEX_FILE -u GIT_DIR -u GIT_WORK_TREE \
        git worktree remove "$tmp_worktree" --force >/dev/null 2>&1 || true
      rm -rf "$tmp_dir"
    }
    if ! env -u GIT_INDEX_FILE -u GIT_DIR -u GIT_WORK_TREE \
      git worktree add --detach "$tmp_worktree" "$snapshot_commit" >/dev/null; then
      cleanup_loc_worktree
      return 1
    fi
    if ! (
      unset GIT_INDEX_FILE GIT_DIR GIT_WORK_TREE
      cd "$tmp_worktree"
      "$PYTHON_BIN" scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md >/dev/null
      "$PYTHON_BIN" scripts/ci/check-loc-snapshot.py
    ); then
      cleanup_loc_worktree
      return 1
    fi
    cp "$tmp_worktree/docs/stats/loc-current-lean.md" docs/stats/loc-current-lean.md
    cleanup_loc_worktree
    git add docs/stats/loc-current-lean.md
  }
  gate_run PRE_COMMIT_LOC_SNAPSHOT "代码量快照同步" update_loc_snapshot
fi
gate_run PRE_COMMIT_REPOSITORY_HYGIENE "仓库卫生" \
  "$PYTHON_BIN" scripts/ci/check-repository-hygiene.py

gate_result PASS PRE_COMMIT_ALL "所有适用的 pre-commit 门禁"
