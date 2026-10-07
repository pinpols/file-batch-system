#!/usr/bin/env python3
"""check-test-conventions.py 的契约规则回归测试。

守护最容易「编译通过、CI 全绿、运行时静默」的几件事：

1. 注解顺序无关 —— ``@DisplayName`` 写在 ``@Test`` 之前同样算已标注，不能误报；
2. 一个方法多个测试注解（``@ParameterizedTest`` + ``@MethodSource``）只算一条缺口；
3. 字面量不干扰解析 —— 文本块/字符串里的 ``{`` ``}`` 不能让类树或方法归属错位；
4. 注释掉的 ``@Test`` 不算测试方法；
5. 每个含测试方法的类都要有**类级** ``@DisplayName``（含 ``@Nested`` 内部类）；
6. 基线增量拦截：已登记缺口不失败，新增缺口必须失败。
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-test-conventions.py"
SPEC = importlib.util.spec_from_file_location("check_test_conventions", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules["check_test_conventions"] = MODULE
SPEC.loader.exec_module(MODULE)


def write(tmp: Path, name: str, body: str) -> Path:
    path = tmp / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    return path


DISPLAY_KINDS = (MODULE.MISSING_CLASS, MODULE.MISSING_METHOD, MODULE.NON_CHINESE)
NAMING_KINDS = (MODULE.BANNED_NAME, MODULE.NON_PREFERRED_NAME)


def kinds(findings, pool=DISPLAY_KINDS) -> list[str]:
    """按规则族过滤缺口：夹具里的短方法名（m/series）不该干扰 @DisplayName 断言。"""
    return sorted(finding.kind for finding in findings if finding.kind in pool)


def display_findings(findings):
    """只取 @DisplayName 族缺口，避免方法命名缺口影响 [0] 取值断言。"""
    return [finding for finding in findings if finding.kind in DISPLAY_KINDS]


class MaskJavaTest(unittest.TestCase):
    def test_text_block_braces_are_masked(self) -> None:
        text = 'class A { void m() { String s = """{ } { }"""; } }'
        masked = MODULE.mask_java(text)
        self.assertEqual(masked.count("{"), 2)
        self.assertEqual(masked.count("}"), 2)

    def test_string_and_comment_braces_are_masked(self) -> None:
        text = 'class A { // } {\n String s = "} {"; /* } */ }'
        masked = MODULE.mask_java(text)
        self.assertEqual(masked.count("{"), 1)
        self.assertEqual(masked.count("}"), 1)

    def test_length_and_newlines_are_preserved(self) -> None:
        text = 'class A {\n  String s = "x\\ny"; // c\n}\n'
        masked = MODULE.mask_java(text)
        self.assertEqual(len(masked), len(text))
        self.assertEqual(masked.count("\n"), text.count("\n"))


class ScanFileTest(unittest.TestCase):
    def test_fully_annotated_class_has_no_finding(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            path = write(
                tmp,
                "OkTest.java",
                """
package demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("演示：全量标注的样板")
class OkTest {

  @Test
  @DisplayName("下单成功后返回订单号")
  void placeOrder_returnsId() {
    // noop
  }
}
""",
            )
            self.assertEqual(MODULE.scan_file(path, "demo/OkTest.java"), [])

    def test_display_name_before_test_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "OrderTest.java",
                """
@DisplayName("演示：注解顺序")
class OrderTest {

  @DisplayName("取消后状态为 CANCELED")
  @Test
  void cancel_setsCanceled() {}
}
""",
            )
            self.assertEqual(kinds(MODULE.scan_file(path, "demo/OrderTest.java")), [])

    def test_display_name_before_value_source_is_accepted(self) -> None:
        """@ValueSource 的数组初始化大括号深度 ≥1，不得被当成成员边界（真实仓库写法）。"""
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "ValueSourceTest.java",
                '''
@DisplayName("参数化:注解参数里的数组大括号不得干扰判定")
class ValueSourceTest {

  @ParameterizedTest
  @DisplayName("常见与边界 cron 连续 24 次 next 计算须与 Quartz 序列逐项一致")
  @ValueSource(
      strings = {
        "0 0 * * * ?",
        "30 * * * * ?",
      })
  void series(String cron) {}
}
''',
            )
            self.assertEqual(kinds(MODULE.scan_file(path, "demo/ValueSourceTest.java")), [])

    def test_display_name_before_csv_source_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "CsvTest.java",
                '''
@DisplayName("参数化:CSV 入参")
class CsvTest {

  @ParameterizedTest
  @DisplayName("租户与状态组合逐项校验")
  @CsvSource({
    "t1, OPEN",
    "t2, CLOSED",
  })
  void cases(String tenant, String status) {}
}
''',
            )
            self.assertEqual(kinds(MODULE.scan_file(path, "demo/CsvTest.java")), [])

    def test_missing_display_after_value_source_is_still_reported(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "StillMissingTest.java",
                '''
@DisplayName("参数化:确实缺方法级标注时必须报出")
class StillMissingTest {

  @ParameterizedTest
  @ValueSource(strings = {"a", "b"})
  void series(String v) {}
}
''',
            )
            findings = MODULE.scan_file(path, "demo/StillMissingTest.java")
            self.assertEqual(kinds(findings), [MODULE.MISSING_METHOD])
            self.assertEqual(display_findings(findings)[0].qualified, "StillMissingTest.series")

    def test_missing_class_display_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "NoClassDisplayTest.java",
                """
class NoClassDisplayTest {

  @Test
  @DisplayName("有方法级但无类级")
  void m() {}
}
""",
            )
            findings = MODULE.scan_file(path, "demo/NoClassDisplayTest.java")
            self.assertEqual(kinds(findings), [MODULE.MISSING_CLASS])
            self.assertEqual(display_findings(findings)[0].qualified, "NoClassDisplayTest")

    def test_missing_method_display_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "NoMethodDisplayTest.java",
                """
@DisplayName("有类级但方法缺")
class NoMethodDisplayTest {

  @Test
  void m() {}
}
""",
            )
            findings = MODULE.scan_file(path, "demo/NoMethodDisplayTest.java")
            self.assertEqual(kinds(findings), [MODULE.MISSING_METHOD])
            self.assertEqual(display_findings(findings)[0].qualified, "NoMethodDisplayTest.m")

    def test_multiple_test_annotations_count_once(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "ParamTest.java",
                """
@DisplayName("参数化：只算一条")
class ParamTest {

  @ParameterizedTest
  @MethodSource("args")
  void m(int v) {}
}
""",
            )
            findings = MODULE.scan_file(path, "demo/ParamTest.java")
            self.assertEqual(kinds(findings), [MODULE.MISSING_METHOD])
            self.assertEqual(len([f for f in findings if f.kind in DISPLAY_KINDS]), 1)

    def test_commented_out_test_is_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "CommentedTest.java",
                """
@DisplayName("注释掉的测试不算")
class CommentedTest {

  // @Test
  // void disabled() {}

  @Test
  @DisplayName("真正的测试")
  void real() {}
}
""",
            )
            self.assertEqual(kinds(MODULE.scan_file(path, "demo/CommentedTest.java")), [])

    def test_text_block_does_not_shift_class_tree(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "SqlTest.java",
                '''
@DisplayName("SQL 文本块不影响类树")
class SqlTest {

  @Test
  @DisplayName("含大括号的 SQL 仍正常解析")
  void sql() {
    String ddl = """
        CREATE TABLE t (id int);
        SELECT '}' FROM t;
        """;
  }
}
''',
            )
            self.assertEqual(kinds(MODULE.scan_file(path, "demo/SqlTest.java")), [])

    def test_nested_class_is_checked_separately(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "OuterTest.java",
                """
@DisplayName("外层：已标注")
class OuterTest {

  @Test
  @DisplayName("外层用例")
  void outer() {}

  class InnerTest {

    @Test
    @DisplayName("内部类用例")
    void inner() {}
  }
}
""",
            )
            findings = MODULE.scan_file(path, "demo/OuterTest.java")
            self.assertEqual(kinds(findings), [MODULE.MISSING_CLASS])
            self.assertEqual(display_findings(findings)[0].qualified, "InnerTest")

    def test_english_display_name_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "EnglishTest.java",
                """
@DisplayName("English only")
class EnglishTest {

  @Test
  @DisplayName("returns 42")
  void m() {}
}
""",
            )
            findings = MODULE.scan_file(path, "demo/EnglishTest.java")
            self.assertEqual(kinds(findings), [MODULE.NON_CHINESE, MODULE.NON_CHINESE])
            self.assertEqual(
                {finding.qualified for finding in findings}, {"EnglishTest", "EnglishTest.m"}
            )

    def test_file_without_tests_is_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = write(
                Path(raw),
                "Helper.java",
                """
class Helper {

  int add(int a, int b) {
    return a + b;
  }
}
""",
            )
            self.assertEqual(MODULE.scan_file(path, "demo/Helper.java"), [])


    def test_method_naming_rules(self) -> None:
        """§14.4：禁 testXxx/test1/test_xxx/xxx_test；只接受 shouldXxx... 或含下划线的形状。"""
        cases = {
            "testFoo": MODULE.BANNED_NAME,
            "test1": MODULE.BANNED_NAME,
            "test_something": MODULE.BANNED_NAME,
            "something_test": MODULE.BANNED_NAME,
            "contextLoads": MODULE.NON_PREFERRED_NAME,
            "stoppedMonitorDoesNotSampleLag": MODULE.NON_PREFERRED_NAME,
            "shouldPauseWhenPermitsExhausted": None,
            "shouldPauseWhenPermitsExhausted_thenResume": None,
            "resumeAfterRelease_whenPermitsExhausted": None,
            "testing": MODULE.NON_PREFERRED_NAME,
        }
        for name, expected in cases.items():
            with self.subTest(name=name):
                with tempfile.TemporaryDirectory() as raw:
                    path = write(
                        Path(raw),
                        "NamingTest.java",
                        f'''
@DisplayName("命名规则")
class NamingTest {{

  @Test
  @DisplayName("用例")
  void {name}() {{}}
}}
''',
                    )
                    findings = MODULE.scan_file(path, "demo/NamingTest.java")
                    naming = [
                        finding.kind
                        for finding in findings
                        if finding.kind in (MODULE.BANNED_NAME, MODULE.NON_PREFERRED_NAME)
                    ]
                    self.assertEqual(naming, [] if expected is None else [expected])


class BaselineTest(unittest.TestCase):
    def test_write_then_check_passes(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            baseline = Path(raw) / "baseline.txt"
            findings = [
                MODULE.Finding(MODULE.MISSING_METHOD, "a/A.java", "A.m", 3),
                MODULE.Finding(MODULE.MISSING_CLASS, "a/A.java", "A", 1),
            ]
            MODULE.write_baseline(baseline, findings)
            self.assertEqual(len(MODULE.read_baseline(baseline)), 2)
            self.assertEqual(MODULE.check_baseline(baseline, findings), 0)

    def test_new_finding_fails(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            baseline = Path(raw) / "baseline.txt"
            known = [MODULE.Finding(MODULE.MISSING_METHOD, "a/A.java", "A.m", 3)]
            MODULE.write_baseline(baseline, known)
            added = known + [MODULE.Finding(MODULE.MISSING_METHOD, "a/A.java", "A.n", 9)]
            self.assertEqual(MODULE.check_baseline(baseline, added), 1)

    def test_identity_ignores_line_drift(self) -> None:
        first = MODULE.Finding(MODULE.MISSING_METHOD, "a/A.java", "A.m", 3)
        moved = MODULE.Finding(MODULE.MISSING_METHOD, "a/A.java", "A.m", 300)
        self.assertEqual(first.identity, moved.identity)

    def test_missing_baseline_file_means_empty(self) -> None:
        self.assertEqual(MODULE.read_baseline(Path("/nonexistent/baseline.txt")), set())


class RealRepoTest(unittest.TestCase):
    def test_scan_is_stable_and_identities_unique(self) -> None:
        """扫描必须真的覆盖到仓库测试源码，且标识唯一。

        注意**不**断言「存在缺口」：治理到位后缺口就是 0，这是目标态而非异常。
        """
        scanned = MODULE.test_java_files()
        self.assertGreater(len(scanned), 500)
        findings = MODULE.scan()
        identities = [finding.identity for finding in findings]
        self.assertEqual(len(identities), len(set(identities)))
        for finding in findings:
            self.assertTrue(finding.path.endswith(".java"))
            self.assertIn("/src/test/java/", f"/{finding.path}")

    def test_real_repo_baseline_entries_point_to_existing_files(self) -> None:
        """基线只允许登记**仍存在**的测试文件；文件被删/改名会留下永远无法收敛的幽灵条目。

        注意：这里**不**断言「基线 ⊆ 当前缺口」。共享工作树下常有并发收敛（一个模块刚补齐、
        另一个还在写），此刻基线条目会短暂「陈旧」——陈旧条目对增量门禁无害（`--check-baseline`
        只对相对基线**新增**的缺口失败），由收敛方按流程重跑 `--write-baseline` 收敛即可。
        真正需要拦住的是指向不存在文件的幽灵条目。
        """
        baseline = MODULE.read_baseline(MODULE.DEFAULT_BASELINE)
        if not baseline:
            self.skipTest("基线尚未生成")
        missing = {
            entry.split("#", 1)[0]
            for entry in baseline
            if not (MODULE.ROOT / entry.split("#", 1)[0]).is_file()
        }
        self.assertEqual(missing, set())


if __name__ == "__main__":
    unittest.main(verbosity=2)
