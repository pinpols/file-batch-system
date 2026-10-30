#!/usr/bin/env bash
# 用途：核验端到端分片的测试报告与实际执行情况。
# 用法：bash scripts/ci/check-e2e-run-completeness.sh "<逗号分隔测试类>" <reports-dir>。
# 守护端到端分片的实际执行情况。
#
# 背景:full-ci-gate / staging-gate 的 e2e 用 `-Dtest=<逗号分隔类名>` +
# `-Dsurefire.failIfNoSpecifiedTests=false` 跑。后者的作用是「某 shard 上没匹配类时
# 空跑也算成功」——但它同时把「清单里列了某类、却因路径错位 / 改名 / 编译漏装而没被选中」
# 的情况一并静默捕获并抑制:该类没跑,CI 仍绿,造成「全量已跑」的假象。
#
# check-e2e-shard-coverage.sh 守的是**静态**侧(清单 ⊇ 仓库实际 *E2eIT,防漏列);
# 本脚本守的是**运行**侧(清单类有报告且至少执行一个用例，方法分片逐项核对),
# 防「列了但没真跑」。两者互补,缺一不可。
#
# 用法(在跑完 e2e 的 job 里、上传 report 之后调):
#   bash scripts/ci/check-e2e-run-completeness.sh "<逗号分隔类名>" <reports-dir>
# 例:
#   bash scripts/ci/check-e2e-run-completeness.sh "$TESTS" batch-e2e-tests/target/surefire-reports
#
# 判定:类选择须有唯一且至少一个非跳过用例的报告；方法选择须与报告逐项一致。
set -euo pipefail

TESTS_RAW="${1:?用法: check-e2e-run-completeness.sh \"<逗号分隔类名>\" <reports-dir>}"
REPORTS_DIR="${2:?用法: check-e2e-run-completeness.sh \"<逗号分隔类名>\" <reports-dir>}"

# 类选择需至少运行一个用例；方法选择须逐项核对，不能只凭报告文件存在判定成功。
python3 - "$TESTS_RAW" "$REPORTS_DIR" <<'PY'
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

selectors, directory = sys.argv[1:]
name_pattern = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*\Z")
seen = set()
for selector in selectors.split(","):
    selector = selector.strip()
    cls, separator, methods = selector.partition("#")
    if not name_pattern.fullmatch(cls) or cls in seen:
        sys.exit(f"❌ 非法或重复的测试类选择: {selector}")
    seen.add(cls)
    reports = list(Path(directory).glob(f"TEST-*.{cls}.xml"))
    direct = Path(directory) / f"TEST-{cls}.xml"
    if direct.is_file():
        reports.append(direct)
    if len(reports) != 1:
        sys.exit(f"❌ 测试类报告缺失或重复: {cls}")
    try:
        cases = ElementTree.parse(reports[0]).getroot().findall("testcase")
    except (ElementTree.ParseError, OSError) as error:
        sys.exit(f"❌ 无法读取测试报告: {reports[0]}: {error}")
    if not cases or all(case.find("skipped") is not None for case in cases):
        sys.exit(f"❌ 测试类没有实际执行的用例: {cls}")
    if any(case.find("failure") is not None or case.find("error") is not None for case in cases):
        sys.exit(f"❌ 测试报告存在失败: {cls}")
    if not separator:
        continue
    expected = methods.split("+")
    if not all(name_pattern.fullmatch(method) for method in expected) or len(expected) != len(set(expected)):
        sys.exit(f"❌ 非法或重复的方法选择: {selector}")
    actual = [case.get("name") for case in cases]
    if len(actual) != len(set(actual)) or set(actual) != set(expected):
        sys.exit(f"❌ 方法报告与选择不一致: {cls}; expected={expected}; actual={actual}")
    if any(case.find("skipped") is not None for case in cases):
        sys.exit(f"❌ 所选方法存在跳过: {cls}")
if not seen:
    sys.exit("❌ 测试选择为空")
print("✅ E2E 报告已验证，所选方法与执行结果一致")
PY
