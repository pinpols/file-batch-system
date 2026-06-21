#!/usr/bin/env bash
# 守护:e2e shard 实际跑数 == 清单声明数(运行侧闭环)。
#
# 背景:full-ci-gate / staging-gate 的 e2e 用 `-Dtest=<逗号分隔类名>` +
# `-Dsurefire.failIfNoSpecifiedTests=false` 跑。后者的作用是「某 shard 上没匹配类时
# 空跑也算成功」——但它同时把「清单里列了某类、却因路径错位 / 改名 / 编译漏装而没被选中」
# 的情况一并静默捕获并抑制:该类没跑,CI 仍绿,造成「全量已跑」的假象。
#
# check-e2e-shard-coverage.sh 守的是**静态**侧(清单 ⊇ 仓库实际 *E2eIT,防漏列);
# 本脚本守的是**运行**侧(本 shard 实际产出的 testsuite report 数 == 清单声明的类数),
# 防「列了但没真跑」。两者互补,缺一不可。
#
# 用法(在跑完 e2e 的 job 里、上传 report 之后调):
#   bash scripts/ci/check-e2e-run-completeness.sh "<逗号分隔类名>" <reports-dir>
# 例:
#   bash scripts/ci/check-e2e-run-completeness.sh "$TESTS" batch-e2e-tests/target/surefire-reports
#
# 判定:
#   expected = 清单里去空白后的非空类名个数(逗号分隔)
#   actual   = reports-dir 下 TEST-*<类名>.xml(每个跑过的 testsuite 一个)且类名 ∈ 清单 的个数
#   expected != actual  → FAIL(列出缺哪个类的 report)
set -euo pipefail

TESTS_RAW="${1:?用法: check-e2e-run-completeness.sh \"<逗号分隔类名>\" <reports-dir>}"
REPORTS_DIR="${2:?用法: check-e2e-run-completeness.sh \"<逗号分隔类名>\" <reports-dir>}"

# 清单 → 去空白/换行 → 逐行类名(去空行去重)。
expected_classes="$(printf '%s' "$TESTS_RAW" | tr -d ' \t\n' | tr ',' '\n' | sed '/^$/d' | sort -u)"
expected_count="$(printf '%s\n' "$expected_classes" | grep -c . || true)"

if [[ "$expected_count" -eq 0 ]]; then
  echo "❌ 清单解析后为空 —— 传入的 -Dtest 清单可能有误: [$TESTS_RAW]"
  exit 1
fi

if [[ ! -d "$REPORTS_DIR" ]]; then
  echo "❌ surefire reports 目录不存在: $REPORTS_DIR"
  echo "   e2e 这一步可能根本没跑(编译失败 / module 路径错),不能算通过。"
  exit 1
fi

# surefire 对每个跑过的测试类产出一个 TEST-<FQCN>.xml。
# 取文件名里的简单类名(去 TEST- 前缀、去 .xml、去包名)。
ran_classes="$(
  find "$REPORTS_DIR" -name 'TEST-*.xml' -type f 2>/dev/null \
    | sed -E 's#.*/TEST-##; s/\.xml$//; s/.*\.//' \
    | sort -u || true
)"

# 只数「确实在本 shard 清单里」的已跑类(排除清单外噪声)。
matched="$(comm -12 <(printf '%s\n' "$expected_classes") <(printf '%s\n' "$ran_classes") || true)"
matched_count="$(printf '%s\n' "$matched" | grep -c . || true)"

# 清单里列了、但没产出 report 的类(= 没真跑)。
not_run="$(comm -23 <(printf '%s\n' "$expected_classes") <(printf '%s\n' "$ran_classes") || true)"

echo "ℹ️  清单声明 ${expected_count} 个 *E2eIT;reports 目录实跑命中 ${matched_count} 个"

if [[ "$matched_count" -ne "$expected_count" ]]; then
  echo
  echo "❌ 运行侧未闭环:清单声明 ${expected_count} 个,实际只跑了 ${matched_count} 个。"
  echo "   下列类列在 -Dtest 清单里、但没产出 surefire report(因路径/改名/匹配问题被静默跳过):"
  printf '   - %s\n' $not_run
  echo
  echo "💥 failIfNoSpecifiedTests=false 会把这种「列了但没跑」吞成绿色 —— 必须修正清单或类名。"
  exit 1
fi

echo "✅ e2e 运行侧闭环:清单声明的 ${expected_count} 个 *E2eIT 均已实跑并产出 report"
