#!/usr/bin/env bash
# =============================================================================
# sdk-handler-tests.sh — 5 类 worker handler(batteries)单测 × Java + Python SDK
#
# 测的是各 SDK **内建/抽象 handler** 对 5 类 worker 的支持:
#   import · export · process · dispatch · atomic
# (Java: sdk/java/core 的 handler 包;Python: sdk/python 的 tests/handler。)
#
# 这些是「类型相关」的 batteries 单测,与 wire 路径无关(wire 全链路由
# scripts/local/sdk-e2e-local.sh 覆盖,见 docs/sdk/local-e2e-coverage.md)。
#
# 用法:
#   bash scripts/local/sdk-handler-tests.sh            # java + python 都跑
#   bash scripts/local/sdk-handler-tests.sh java       # 只跑 Java
#   bash scripts/local/sdk-handler-tests.sh python     # 只跑 Python
#
# 环境变量(均有默认,可覆盖):
#   JAVA_HOME        Java 21 home(缺省自动探测 /usr/libexec/java_home -v 21)
#   MVN              maven 可执行(默认 mvn)
#   JAVA_SDK_MODULE  Java SDK 模块(默认 sdk/java/core)
#   JAVA_TEST_FILTER surefire -Dtest 过滤(默认 handler 包)
#   PY_SDK_DIR       Python SDK 目录(默认 sdk/python)
#   PY_TEST_PATH     Python 测试路径(默认 tests/handler)
#   PYTEST           pytest 运行器(缺省:有 uv 用 `uv run python -m pytest`,
#                    否则 `python -m pytest`)
# 退出码:任一侧有失败/错误即非 0。
# =============================================================================
set -uo pipefail

LANG_SEL="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # scripts/local
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"                # 仓库根
SDK_DIR="${ROOT_DIR}/sdk"

# ── env 提取(可覆盖)────────────────────────────────────────────────────────
: "${MVN:=mvn}"
: "${JAVA_SDK_MODULE:=sdk/java/core}"
: "${JAVA_TEST_FILTER:=io.github.pinpols.batch.sdk.handler.**}"
: "${PY_SDK_DIR:=${SDK_DIR}/python}"
: "${PY_TEST_PATH:=tests/handler}"
if [[ -z "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
export JAVA_HOME
if [[ -z "${PYTEST:-}" ]]; then
  if command -v uv >/dev/null 2>&1; then PYTEST="uv run python -m pytest"; else PYTEST="python -m pytest"; fi
fi

say()  { printf '\n=== %s ===\n' "$*"; }
pass() { printf '✅ %s\n' "$*"; }
fail() { printf '❌ %s\n' "$*"; }

RC=0

run_java() {
  say "Java SDK handler 单测(${JAVA_SDK_MODULE},5 类 worker handler)"
  [[ -n "${JAVA_HOME:-}" ]] && echo "  JAVA_HOME=${JAVA_HOME}"
  local log; log="$(mktemp -t sdk-handler-java.XXXXXX)"
  # 不加 -q,保留 surefire 汇总行;-am 关闭以免把无匹配的依赖模块算作失败。
  "${MVN}" -f "${ROOT_DIR}/pom.xml" -pl "${JAVA_SDK_MODULE}" test \
    -Dspotless.check.skip=true \
    -Dtest="${JAVA_TEST_FILTER}" -Dsurefire.failIfNoSpecifiedTests=false \
    > "${log}" 2>&1
  local ex=$?
  # surefire 末尾聚合行:Tests run: N, Failures: F, Errors: E, Skipped: S
  local line; line="$(grep -aE '^\[INFO\] Tests run:|^Tests run:' "${log}" | tail -1)"
  echo "  ${line:-（无汇总行,见 ${log}）}"
  if [[ $ex -eq 0 ]]; then pass "Java handler 单测通过"; else fail "Java handler 单测失败(日志 ${log})"; tail -25 "${log}"; RC=1; fi
}

run_python() {
  say "Python SDK handler 单测(python/${PY_TEST_PATH},5 类 worker handler)"
  echo "  PYTEST=${PYTEST}"
  local log; log="$(mktemp -t sdk-handler-py.XXXXXX)"
  ( cd "${PY_SDK_DIR}" && ${PYTEST} "${PY_TEST_PATH}" -q -rs ) | tee "${log}"
  local ex=${PIPESTATUS[0]}
  # 解析 "N passed, M skipped" 并在有 skip 时显式列原因。concrete handler
  # (atomic/builtin/typed)由各子目录测试直接 import 真实类覆盖,不会被 skip;
  # 顶层 golden/contract/abstract 跨切面测试用 conftest 软 import「公共子模块名」
  # 软门控,模块若以私有名 + 类再导出落地,这些门会判 "not yet merged" 而 stale-skip。
  # 告警是为了让这种 stale 跳过可见,不被「全绿」掩盖。
  local summ passed skipped
  summ="$(grep -aE '[0-9]+ passed' "${log}" | tail -1)"
  passed="$(printf '%s' "$summ"  | grep -oaE '[0-9]+ passed'  | grep -oaE '[0-9]+' || echo 0)"
  skipped="$(printf '%s' "$summ" | grep -oaE '[0-9]+ skipped' | grep -oaE '[0-9]+' || echo 0)"
  if [[ $ex -ne 0 ]]; then fail "Python handler 单测失败"; RC=1; return; fi
  pass "Python handler 单测通过(passed=${passed} skipped=${skipped})"
  if [[ ${skipped:-0} -gt 0 ]]; then
    printf '⚠ 有 %s 条被 skip(均在顶层 golden/contract/abstract 跨切面测试)。\n' "${skipped}"
    printf '   若 skip 原因是 "not yet merged" 但模块其实已在 main,说明 conftest 软门已 stale。\n'
    printf '   注:concrete handler(atomic/builtin/typed)由子目录测试覆盖,不在此列。详查:\n'
    grep -aE 'SKIPPED' "${log}" | sed -E 's/.*conftest.py:[0-9]+: //; s/.*: //' | sort | uniq -c | sort -rn | head -12
    [[ "${FAIL_ON_SKIP:-0}" == "1" ]] && { fail "FAIL_ON_SKIP=1:把 skip 当失败"; RC=1; }
  fi
}

case "${LANG_SEL}" in
  java)   run_java ;;
  python) run_python ;;
  all)    run_java; run_python ;;
  *) fail "未知参数 '${LANG_SEL}'(java|python|all)"; exit 2 ;;
esac

say "汇总"
[[ $RC -eq 0 ]] && pass "全部 handler 单测通过(${LANG_SEL})" || fail "有 handler 单测失败(${LANG_SEL})"
exit $RC
