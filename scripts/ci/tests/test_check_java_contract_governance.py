"""固定契约门禁的正反例；禁止通过扩大字符串范围降低误报控制。"""
import importlib.util
from pathlib import Path
import sys
import unittest

PATH = Path(__file__).resolve().parents[1] / "check-java-contract-governance.py"
SPEC = importlib.util.spec_from_file_location("java_contract_governance", PATH)
guard = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = guard
SPEC.loader.exec_module(guard)


class JavaContractGovernanceTest(unittest.TestCase):
    def check(self, source, path="sample/src/main/java/ExampleController.java", values=None):
        return guard.scan(path, source, values)

    def test_fixed_object_response(self):
        rows = self.check("class ExampleController { public CommonResponse<Object> trigger() { return null; } }")
        self.assertEqual(["JCON-1"], [row.rule for row in rows])

    def test_multiline_nested_map(self):
        rows = self.check("class ExampleController { public PageResponse<\n Map<String, Object>> list() { return null; } }")
        self.assertEqual("PageResponse<Map<String,Object>>", rows[0].detail)

    def test_typed_dto_is_allowed(self):
        self.assertEqual([], self.check("class ExampleController { public CommonResponse<StatusResponse> status() { return null; } }"))

    def test_application_interface(self):
        rows = self.check("interface ExampleService { List<Object> list(); }", "sample/src/main/java/ExampleService.java")
        self.assertEqual("JCON-1", rows[0].rule)

    def test_private_implementation_map_is_not_boundary(self):
        self.assertEqual([], self.check("class ExampleController { private Map<String,Object> params() { return null; } }"))

    def test_annotations_with_parentheses(self):
        rows = self.check('class ExampleController { public CommonResponse<Map<String,Integer>> cleanup(@Pattern(regexp="x") String prefix) { return null; } }')
        self.assertIn("cleanup(", rows[0].signature)

    def test_comments_and_text_blocks_ignored(self):
        source = '''class ExampleController {
          // public CommonResponse<Object> falsePositive() {}
          String sql = """
          FAILED public CommonResponse<Object> phantom() {}
          """;
          String literal = "public CommonResponse<Object> phantom() {}";
        }'''
        self.assertEqual([], self.check(source))

    def test_enum_scope_only(self):
        source = 'class Example { String state = "FAILED"; }'
        self.assertEqual([], self.check(source))
        self.assertEqual("JCON-2", self.check(source, values={"FAILED"})[0].rule)

    def test_protocol_key_reuse(self):
        source = 'class Example { static final String KEY_ID = "id"; public void run() { values.get("id"); } }'
        rows = self.check(source)
        self.assertEqual(["JCON-3"], [row.rule for row in rows])

    def test_constant_declaration_annotation_and_message_allowed(self):
        source = 'class Example { static final String KEY_ID = "id"; @Label("id") public void run() { log.info("id"); } }'
        self.assertEqual([], self.check(source))

    def test_map_factory_keys_but_not_values(self):
        source = 'class Example { static final String KEY_ID = "id"; public void run() { Map.of("id", "id", "other", "id"); values.put("other", "id"); } }'
        self.assertEqual(["JCON-3"], [row.rule for row in self.check(source)])

    def test_nested_map_factory_arguments(self):
        source = 'class Example { static final String KEY_ID = "id"; public void run() { Map.of("other", List.of(1, 2), "id", "value"); } }'
        self.assertEqual(["JCON-3"], [row.rule for row in self.check(source)])

    def test_constants_do_not_cross_nested_type(self):
        source = 'class Example { static final String KEY_ID = "id"; class Nested { public void run() { values.get("id"); } } }'
        self.assertEqual([], self.check(source))

    def test_exact_method_exception(self):
        rows = self.check("class ExampleController { public CommonResponse<Object> first() { return null; } public CommonResponse<Object> second() { return null; } }")
        first = rows[0]
        exceptions = [{"rule": first.rule, "path": first.path, "signature": first.signature, "detail": first.detail, "reason": "动态响应"}]
        remaining = guard.apply_exceptions(rows, exceptions)
        self.assertEqual(["second():CommonResponse<Object>"], [row.signature for row in remaining])

    def test_baseline_count_cannot_grow(self):
        row = guard.Finding("JCON-1", "A.java", "m()", "Object", 1)
        self.assertEqual({row.key(): 1}, guard.regressions([row, row], {row.key(): 1}))

    def test_line_movement_does_not_change_baseline_key(self):
        source = "class ExampleController { public CommonResponse<Object> trigger() { return null; } }"
        before = self.check(source)[0]
        after = self.check("\n\n" + source)[0]
        self.assertEqual(before.key(), after.key())
        self.assertNotEqual(before.line, after.line)

    def test_wildcard_exception_cannot_disable_boundary_rule(self):
        policy = {"schema_version": 1, "enum_scopes": [], "exceptions": [
            {"rule": "JCON-1", "path": "src/*", "signature": "m()", "detail": "Object", "reason": "动态"}
        ]}
        with self.assertRaises(ValueError):
            guard.validate_registry(policy, {"schema_version": 1, "counts": {}})

    def test_negative_or_boolean_baseline_count_is_rejected(self):
        policy = {"schema_version": 1, "enum_scopes": [], "exceptions": []}
        for count in (-1, True):
            with self.subTest(count=count), self.assertRaises(ValueError):
                guard.validate_registry(policy, {"schema_version": 1, "counts": {"x": count}})

    def test_enum_change_rescans_registered_consumer(self):
        enum = "batch-common/src/main/java/io/github/pinpols/batch/common/enums/JobInstanceStatus.java"
        policy = {"enum_scopes": [{"path": "consumer.java", "enums": ["JobInstanceStatus"]}]}
        self.assertEqual([enum, "consumer.java"], guard.expand_enum_consumers([enum], policy))
        self.assertEqual(["other.java"], guard.expand_enum_consumers(["other.java"], policy))


if __name__ == "__main__":
    unittest.main()
