import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-java-structured-string-concat.py"
SPEC = importlib.util.spec_from_file_location("check_java_structured_string_concat", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class StructuredStringConcatTest(unittest.TestCase):
    def test_detects_multiline_json_fixture(self):
        source = r'''class Example {
  String body = "{" +
      "\"tenantId\":\"ta\"," +
      "\"jobCode\":\"JOB_A\"}";
}'''
        findings = MODULE.scan_source(source)
        self.assertEqual(["JSON"], [finding.category for finding in findings])

    def test_detects_json_template_with_dynamic_interpolation_lines(self):
        source = r'''class Example {
  String body = "{" +
      "\"tenantId\":\"" +
      tenantId +
      "\",\"jobCode\":\"" +
      jobCode +
      "\"}";
}'''
        self.assertEqual(["JSON"], [finding.category for finding in MODULE.scan_source(source)])

    def test_detects_multiline_sql_fixture(self):
        source = '''class Example {
  String sql = "SELECT id" +
      " FROM batch.job_instance" +
      " WHERE status = 'SUCCESS'";
}'''
        findings = MODULE.scan_source(source)
        self.assertEqual(["SQL"], [finding.category for finding in findings])

    def test_detects_other_structured_templates(self):
        examples = {
            "XML": '''class Example {\n  String xml = "<root>" +\n      "<item>value</item>" +\n      "</root>";\n}''',
            "YAML": '''class Example {\n  String yaml = "job:" +\n      "\\n  name: daily" +\n      "\\n  enabled: true";\n}''',
            "MARKDOWN": '''class Example {\n  String prompt = "# Summary" +\n      "\\n- status" +\n      "\\n- result";\n}''',
            "SCRIPT": '''class Example {\n  String script = "#!/bin/sh" +\n      "\\nset -eu" +\n      "\\nexport MODE=test";\n}''',
            "LUA": '''class Example {\n  String script = "local function transform(row)" +\n      "\\n  return row" +\n      "\\nend";\n}''',
            "PEM": '''class Example {\n  String key = "-----BEGIN PRIVATE KEY-----" +\n      "\\nabc" +\n      "\\n-----END PRIVATE KEY-----";\n}''',
            "CSV": '''class Example {\n  String csv = "name,age,status" +\n      "\\nAlice,30,ACTIVE" +\n      "\\nBob,40,ACTIVE";\n}''',
            "PROPERTIES": '''class Example {\n  String properties = "app.name=batch" +\n      "\\napp.mode=test" +\n      "\\napp.port=8080";\n}''',
            "INI": '''class Example {\n  String ini = "[server]" +\n      "\\nport=8080" +\n      "\\nmode=test";\n}''',
        }
        for category, source in examples.items():
            with self.subTest(category=category):
                self.assertEqual([category], [finding.category for finding in MODULE.scan_source(source)])

    def test_ignores_signature_string(self):
        source = '''class Example {
  String signature = "POST\\n" + "/\\n" + canonicalRequest;
}'''
        self.assertEqual([], MODULE.scan_source(source))

    def test_ignores_text_block_and_comment(self):
        source = '''class Example {
  String body = """
      {"tenantId":"ta"}
      """;
  // String body = "{" + "\\\"tenantId\\\":1" + "}";
}'''
        self.assertEqual([], MODULE.scan_source(source))

    def test_ignores_multiline_concat_without_structured_format(self):
        source = '''class Example {
  String message = "hello " +
      userName +
      " welcome";
}'''
        self.assertEqual([], MODULE.scan_source(source))

    def test_ignores_fixed_width_record_with_colon_without_yaml_spacing(self):
        source = r'''class Example {
  String fixedWidth = "HEADER" +
      "\nFOOTER:total=2" +
      "\nEND";
}'''
        self.assertEqual([], MODULE.scan_source(source))

    def test_incremental_comparison_allows_existing_finding_after_line_shift(self):
        baseline = '''class Example {\n  String body = "{" +\n      "\\\"tenantId\\\":\\\"ta\\\"," +\n      "\\\"jobCode\\\":\\\"JOB_A\\\"}";\n}'''
        current = "// moved down\n" + baseline
        self.assertEqual([], MODULE.added_findings(current, baseline))

    def test_incremental_comparison_rejects_new_finding(self):
        source = r'''class Example {
  String body = "{" +
      "\"tenantId\":\"ta\"," +
      "\"jobCode\":\"JOB_A\"}";
}'''
        self.assertEqual(["JSON"], [finding.category for finding in MODULE.added_findings(source, "")])


if __name__ == "__main__":
    unittest.main()
