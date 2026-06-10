#!/usr/bin/env bash
# scripts/ci/check-no-enable-preview.sh
#
# JDK preview 守护:禁止 `--enable-preview` / maven `<enablePreview>true` 进入
# 会被生产构建或运行加载的配置(pom.xml / Dockerfile / helm values / docker-compose /
# .github workflows / 启动脚本)。
#
# 背景(对齐 CLAUDE.md「ADR 与范围纪律」+ docs/analysis/jdk-feature-usage-analysis 的 P1):
#   项目主工程以 JDK 25 为编译/运行基线,但 preview/incubator 特性(Structured Concurrency、
#   Stable Values、Primitive Patterns 等)语法/行为在跨小版本间不稳定,且 `--enable-preview`
#   会让 class 文件带 preview 标记 → 同一 minor JDK 才能运行,破坏生产可移植性。
#   preview/incubator 只允许在 spike / benchmark 模块(load-tests)里实验,绝不进生产 profile。
#
# 仅扫「构建/运行配置」,不扫文档(*.md):docs 与 CLAUDE.md 会以文字形式提及
# "enable-preview" 来说明本规则,那是合法的,不应误报。
#
# 用法:
#   ./scripts/ci/check-no-enable-preview.sh                 # 扫全仓构建/运行配置
#   BATCH_CI_SKIP_PREVIEW_GATE=1 ./scripts/ci/check-...sh   # escape hatch(仅 dev 本地)

set -euo pipefail

if [[ "${BATCH_CI_SKIP_PREVIEW_GATE:-0}" == "1" ]]; then
  echo "[preview-gate] BATCH_CI_SKIP_PREVIEW_GATE=1 — 跳过(仅 dev 本地 debug 应使用)"
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# 扫描目标:只取「会进生产构建/运行」的配置文件。
#   - pom.xml            maven-compiler-plugin <compilerArgs>/<enablePreview>
#   - Dockerfile*        ENTRYPOINT / ENV JAVA_OPTS
#   - helm/**            values 里的 javaOptsExtra
#   - docker-compose*    services 的 JAVA_OPTS/environment
#   - .github/workflows  CI 里的 mvn/java 命令
#   - scripts/**/*.sh    启动脚本(排除本 ci 守护脚本自身,避免自指误报)
# 豁免:
#   - load-tests/        benchmark 模块,允许 spike preview
#   - *.md / docs/       文档以文字提及本规则,合法
#   - target/            构建产物
TARGETS=()
while IFS= read -r f; do
  case "$f" in
    *.md|docs/*|*/target/*|target/*) continue ;;
    load-tests/*) continue ;;
    scripts/ci/check-no-enable-preview.sh) continue ;;
  esac
  TARGETS+=("$f")
done < <(git ls-files \
  '*pom.xml' \
  'docker/Dockerfile*' '**/Dockerfile*' \
  'helm/**/*.yaml' 'helm/**/*.yml' \
  'docker-compose*.yml' 'docker/compose/*.yml' \
  '.github/workflows/*.yml' '.github/workflows/*.yaml' \
  '.github/actions/**/*.yml' \
  'scripts/**/*.sh' 2>/dev/null | sort -u)

if [[ ${#TARGETS[@]} -eq 0 ]]; then
  echo "[preview-gate] 无扫描目标"
  exit 0
fi

# 检测模式:
#   --enable-preview                 java / javac / mvn compilerArg
#   <enablePreview>true              maven-compiler-plugin configuration
PATTERN='--enable-preview|<enablePreview>[[:space:]]*true'

VIOLATIONS=()
for f in "${TARGETS[@]}"; do
  [[ -f "$f" ]] || continue
  while IFS=: read -r lineno line; do
    VIOLATIONS+=("$f:$lineno:${line#"${line%%[![:space:]]*}"}")
    # PATTERN 以 `--` 开头,必须用 `--` 终止 grep 选项解析,否则被当成 flag。
  done < <(grep -nE -- "$PATTERN" "$f" 2>/dev/null || true)
done

if [[ ${#VIOLATIONS[@]} -gt 0 ]]; then
  echo
  echo "[preview-gate] FAIL: 检测到 JDK preview 启用标记 ${#VIOLATIONS[@]} 处(禁止进入生产构建/运行):"
  echo
  for v in "${VIOLATIONS[@]}"; do
    echo "  $v"
  done
  echo
  echo "[preview-gate] 处理建议:"
  echo "  - preview/incubator(Structured Concurrency / Stable Values / Primitive Patterns / Vector API 等)"
  echo "    禁止进入生产 profile;只能在 load-tests(benchmark)模块做隔离实验。"
  echo "  - 若确需本地临时跑 preview spike,设 BATCH_CI_SKIP_PREVIEW_GATE=1(不要提交带 --enable-preview 的配置)。"
  echo "  - 背景见 docs/analysis/jdk-feature-usage-analysis-*.md「不建议现在使用」小节。"
  exit 1
fi

echo "[preview-gate] PASS: 无 --enable-preview / <enablePreview>(扫描了 ${#TARGETS[@]} 个构建/运行配置文件)"
