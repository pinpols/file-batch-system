import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-java-lombok-injection.py"
SPEC = importlib.util.spec_from_file_location("check_java_lombok_injection", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class InjectionRuleTest(unittest.TestCase):
    def test_rejects_field_injection(self):
        source = "class Service {\n  @Autowired\n  private Client client;\n}"
        self.assertIn("field injection", MODULE.find_injection_violations(source)[0])

    def test_rejects_inline_field_injection(self):
        source = "class Service {\n  @Autowired private Client client;\n}"
        self.assertIn("field injection", MODULE.find_injection_violations(source)[0])

    def test_rejects_inline_qualified_field_injection(self):
        source = 'class Service {\n  @Autowired @Qualifier("primary") private Client client;\n}'
        self.assertIn("field injection", MODULE.find_injection_violations(MODULE.mask_literals(source))[0])

    def test_rejects_multiline_field_injection(self):
        source = "class Service {\n  @Autowired\n  private Map<\n      String, Client> clients;\n}"
        self.assertIn("field injection", MODULE.find_injection_violations(source)[0])

    def test_rejects_setter_injection(self):
        source = "class Service {\n  @Autowired\n  void setClient(Client client) {}\n}"
        self.assertIn("method injection", MODULE.find_injection_violations(source)[0])

    def test_allows_constructor_injection(self):
        source = "class Service {\n  @Autowired\n  Service(Client client) {}\n}"
        self.assertEqual([], MODULE.find_injection_violations(source))

    def test_rejects_non_setter_method_injection(self):
        source = "class Service {\n  @Autowired\n  void initialize(Client client) {}\n}"
        self.assertIn("method injection", MODULE.find_injection_violations(source)[0])

    def test_ignores_annotations_in_comments(self):
        source = "class Service {\n  // @Autowired private Client client;\n}"
        self.assertEqual([], MODULE.find_injection_violations(MODULE.strip_comments(source)))

    def test_ignores_annotations_in_string_literals(self):
        source = 'class Service { String example = "@Autowired private Client client;"; }'
        stripped = MODULE.mask_literals(MODULE.strip_comments(source))
        self.assertEqual([], MODULE.find_injection_violations(stripped))

    def test_allows_only_registered_logger_call_count(self):
        path = "batch-common/src/main/java/io/github/pinpols/batch/common/web/AbstractApiExceptionHandler.java"
        approved = "LoggerFactory.getLogger(getClass())"
        self.assertEqual([], MODULE.find_logger_violations(path, approved))
        self.assertTrue(MODULE.find_logger_violations(path, approved + " + LoggerFactory.getLogger(Service.class)"))

    def test_rejects_logger_in_unapproved_class(self):
        self.assertTrue(MODULE.find_logger_violations("batch-common/src/main/java/Example.java", "LoggerFactory.getLogger(Example.class)"))


if __name__ == "__main__":
    unittest.main()
