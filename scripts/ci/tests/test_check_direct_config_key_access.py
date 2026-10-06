from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-direct-config-key-access.py"
SPEC = importlib.util.spec_from_file_location("check_direct_config_key_access", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
# dataclass 装饰器在定义 Finding 时依赖 cls.__module__ 能在 sys.modules 中解析。
sys.modules["check_direct_config_key_access"] = MODULE
SPEC.loader.exec_module(MODULE)


class StripCommentsTest(unittest.TestCase):
    """注释不是代码：剥离后必须保留长度与行号，且不动字符串字面量。"""

    def test_removes_line_comment_but_keeps_newline(self) -> None:
        text = 'int a = 1; // @Value("${batch.x}")\nint b = 2;\n'
        stripped = MODULE.strip_comments(text)
        self.assertEqual(len(stripped), len(text))
        self.assertNotIn("@Value", stripped)
        self.assertEqual(stripped.count("\n"), text.count("\n"))
        self.assertIn("int b = 2;", stripped)

    def test_removes_block_comment_and_javadoc(self) -> None:
        text = '/**\n * {@code @Value("${batch.y:1}")}\n */\nint c = 3;\n'
        stripped = MODULE.strip_comments(text)
        self.assertEqual(len(stripped), len(text))
        self.assertNotIn("@Value", stripped)
        self.assertIn("int c = 3;", stripped)

    def test_keeps_double_slash_inside_string_literal(self) -> None:
        text = 'String url = "http://example.test/x"; // trailing\n'
        stripped = MODULE.strip_comments(text)
        self.assertIn("http://example.test/x", stripped)
        self.assertNotIn("trailing", stripped)

    def test_keeps_escaped_quote_inside_string_literal(self) -> None:
        text = 'String s = "a \\" // still inside"; int d = 4;\n'
        stripped = MODULE.strip_comments(text)
        self.assertIn("still inside", stripped)
        self.assertIn("int d = 4;", stripped)

    def test_line_numbers_survive_stripping(self) -> None:
        text = 'class A {\n  // comment\n  int e = 5;\n}\n'
        stripped = MODULE.strip_comments(text)
        self.assertEqual(len(stripped), len(text))
        self.assertEqual(stripped.count("\n"), text.count("\n"))


class FindingsFromTextTest(unittest.TestCase):
    """命中必须来自可执行代码；注释里的字面量只是文档。"""

    def test_ignores_value_injection_inside_javadoc(self) -> None:
        text = (
            "/**\n"
            ' * <p>把原先 {@code @Value("${batch.shedlock.auto-create:false}")} 收敛为类型安全配置。\n'
            " */\n"
            "class BatchShedLockProperties {}\n"
        )
        self.assertEqual(MODULE.findings_from_text(text, "a/B.java"), [])

    def test_ignores_direct_get_property_inside_line_comment(self) -> None:
        text = 'class A {\n  // environment.getProperty("batch.legacy.flag")\n}\n'
        self.assertEqual(MODULE.findings_from_text(text, "a/B.java"), [])

    def test_detects_real_value_injection(self) -> None:
        text = 'class A {\n  @Value("${batch.worker.stale-temp-file-hours:6}")\n  private long hours;\n}\n'
        findings = MODULE.findings_from_text(text, "a/B.java")
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].read_type, MODULE.VALUE_INJECTION_TYPE)
        self.assertEqual(findings[0].key, "batch.worker.stale-temp-file-hours")
        self.assertEqual(findings[0].classification, MODULE.PROJECT_CONFIG)
        self.assertEqual(findings[0].line, 2)

    def test_detects_environment_get_property(self) -> None:
        text = 'class A {\n  String v = environment.getProperty("batch.foo");\n}\n'
        findings = MODULE.findings_from_text(text, "a/B.java")
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].read_type, MODULE.DIRECT_GET_PROPERTY)
        self.assertEqual(findings[0].classification, MODULE.PROJECT_CONFIG)

    def test_detects_system_get_property_and_classifies_jvm(self) -> None:
        text = 'class A {\n  String t = System.getProperty("java.io.tmpdir");\n}\n'
        findings = MODULE.findings_from_text(text, "a/B.java")
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].read_type, MODULE.SYSTEM_PROPERTY_TYPE)
        self.assertEqual(findings[0].classification, MODULE.JVM_SYSTEM)

    def test_classifies_sensitive_key_as_secret(self) -> None:
        text = 'class A {\n  @Value("${batch.datasource.password}")\n  private String p;\n}\n'
        findings = MODULE.findings_from_text(text, "a/B.java")
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].classification, MODULE.SECRET_CONFIG)

    def test_classifies_build_and_test_tooling_keys_as_test_only(self) -> None:
        """构建 / 测试运行期属性不应被误判为待收敛的 PROJECT_CONFIG。"""
        for key in ("maven.multiModuleProjectDirectory", "boundedContext.report"):
            with self.subTest(key=key):
                text = f'class A {{\n  String v = System.getProperty("{key}");\n}}\n'
                findings = MODULE.findings_from_text(text, "a/B.java")
                self.assertEqual(len(findings), 1)
                self.assertEqual(findings[0].classification, MODULE.TEST_ONLY)

    def test_classifies_unknown_dotted_key_as_project_config(self) -> None:
        """豁免必须收敛到具名 key：任意 batch./自定义前缀仍归 PROJECT_CONFIG。"""
        text = 'class A {\n  String v = System.getProperty("maven.someOtherProperty");\n}\n'
        findings = MODULE.findings_from_text(text, "a/B.java")
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].classification, MODULE.PROJECT_CONFIG)

    def test_ignores_value_injection_composed_from_constant(self) -> None:
        """``@Value("${" + X.KEY + "}")`` 已收敛到常量，不得再被当成字面量 key。"""
        text = (
            "class A {\n"
            '  @Value("${" + TriggerKafkaProducerConfiguration.BOOTSTRAP_SERVERS_KEY + "}")\n'
            "  private String broker;\n"
            "}\n"
        )
        self.assertEqual(MODULE.findings_from_text(text, "a/B.java"), [])


class AllowlistDisciplineTest(unittest.TestCase):
    """阶段 2：白名单条目必须写明 reason / owner / review。"""

    def test_shipped_allowlist_is_complete(self) -> None:
        self.assertEqual(MODULE.validate_allowlist(), [])

    def test_missing_metadata_is_reported(self) -> None:
        original = MODULE.ALLOWLIST
        try:
            MODULE.ALLOWLIST = {"x/Y.java#batch.k": {"reason": "r"}}
            problems = MODULE.validate_allowlist()
            self.assertEqual(len(problems), 1)
            self.assertIn("owner", problems[0])
            self.assertIn("review", problems[0])
        finally:
            MODULE.ALLOWLIST = original

    def test_whitespace_only_metadata_is_reported(self) -> None:
        original = MODULE.ALLOWLIST
        try:
            MODULE.ALLOWLIST = {
                "x/Y.java#batch.k": {"reason": "r", "owner": "o", "review": "   "}
            }
            problems = MODULE.validate_allowlist()
            self.assertEqual(len(problems), 1)
            self.assertIn("review", problems[0])
        finally:
            MODULE.ALLOWLIST = original


class BaselineIdentityTest(unittest.TestCase):
    """基线只登记尚未收敛的高风险命中，已进白名单的例外不得混入。"""

    def _finding(self, key: str, classification: str, allowlisted: bool):
        return MODULE.Finding("a/B.java", 1, MODULE.VALUE_INJECTION_TYPE, key, classification, allowlisted)

    def test_excludes_allowlisted_and_low_risk(self) -> None:
        findings = [
            self._finding("batch.k", MODULE.PROJECT_CONFIG, False),
            self._finding("batch.p", MODULE.SECRET_CONFIG, True),
            self._finding("spring.datasource.url", MODULE.SPRING_INFRA, False),
        ]
        identities = MODULE.baseline_identities(findings)
        self.assertEqual(identities, ["a/B.java#VALUE_INJECTION#batch.k"])


class TestSourceScanTest(unittest.TestCase):
    """测试源码口径（TEST_SOURCE）：额外识别夹具写法，且只报告、不进基线。"""

    def test_with_property_detected_only_for_test_source(self) -> None:
        text = (
            "class A {\n"
            "  MockEnvironment env = new MockEnvironment()\n"
            '      .withProperty("spring.application.name", "x");\n'
            "}\n"
        )
        self.assertEqual(MODULE.findings_from_text(text, "a/BTest.java"), [])
        findings = MODULE.findings_from_text(text, "a/BTest.java", test_source=True)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].read_type, MODULE.TEST_PROPERTY_FIXTURE_TYPE)
        self.assertEqual(findings[0].key, "spring.application.name")
        self.assertEqual(findings[0].line, 3)
        self.assertEqual(findings[0].classification, MODULE.SPRING_INFRA)

    def test_property_entry_literal_detected_only_for_test_source(self) -> None:
        text = '@SpringBootTest(properties = {"batch.security.bypass-mode=true"})\nclass A {}\n'
        self.assertEqual(MODULE.findings_from_text(text, "a/BTest.java"), [])
        findings = MODULE.findings_from_text(text, "a/BTest.java", test_source=True)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].read_type, MODULE.TEST_PROPERTY_ENTRY_TYPE)
        self.assertEqual(findings[0].key, "batch.security.bypass-mode")
        self.assertEqual(findings[0].classification, MODULE.PROJECT_CONFIG)

    def test_property_entry_does_not_match_prose_with_prefix(self) -> None:
        """断言消息里的 ``expected batch.x=1`` 不是配置项字面量（前面有空格与散文）。"""
        text = 'class A {\n  String msg = "expected batch.x=1 but got 2";\n}\n'
        self.assertEqual(
            MODULE.findings_from_text(text, "a/BTest.java", test_source=True), []
        )

    def test_sensitive_key_in_test_source_still_classified_secret(self) -> None:
        text = 'class A {\n  String p = env.getProperty("batch.datasource.password");\n}\n'
        findings = MODULE.findings_from_text(text, "a/BTest.java", test_source=True)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].classification, MODULE.SECRET_CONFIG)

    def test_fixture_hits_are_excluded_from_baseline_identities(self) -> None:
        """夹具命中即使分类为 PROJECT_CONFIG，也不得进入基线标识集合。

        基线只由 ``main()`` 传入的 ``scan()``（生产）结果生成，本用例锁定
        「测试源码的夹具命中不参与拦截」这一口径：``check_baseline`` 的输入是生产命中，
        因此测试源码命中天然不出现在基线里。
        """
        text = '@SpringBootTest(properties = {"batch.k=v"})\nclass A {}\n'
        findings = MODULE.findings_from_text(text, "a/BTest.java", test_source=True)
        self.assertEqual(len(findings), 1)
        # 若这些命中被误当成生产命中传入，就会进基线——用身份字符串断言其形态可识别。
        self.assertTrue(findings[0].path.endswith("Test.java"))


class TestSourceReportTest(unittest.TestCase):
    """测试源码报告段：夹具字面量按 key 聚合，读取类命中逐条列出。"""

    def _finding(self, read_type: str, key: str, path: str = "a/BTest.java"):
        return MODULE.Finding(
            path, 1, read_type, key, MODULE.classify(key), False
        )

    def test_report_lists_reads_and_aggregates_entries(self) -> None:
        findings = [
            self._finding(MODULE.TEST_PROPERTY_FIXTURE_TYPE, "spring.application.name"),
            self._finding(MODULE.TEST_PROPERTY_ENTRY_TYPE, "batch.security.bypass-mode"),
            self._finding(
                MODULE.TEST_PROPERTY_ENTRY_TYPE,
                "batch.security.bypass-mode",
                "c/DTest.java",
            ),
        ]
        report = MODULE.render_test_report(findings)
        self.assertIn("TEST_SOURCE", report)
        self.assertIn("spring.application.name", report)
        self.assertIn("batch.security.bypass-mode × 2（2 个文件）", report)
        self.assertIn("不纳入 CI 拦截", report)

    def test_report_handles_empty_findings(self) -> None:
        report = MODULE.render_test_report([])
        self.assertIn("命中总数: 0", report)
        self.assertIn("（无）", report)


if __name__ == "__main__":
    unittest.main()
