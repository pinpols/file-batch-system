import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-java-logging-governance.py"
SPEC = importlib.util.spec_from_file_location("check_java_logging_governance", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class JavaLoggingGovernanceTest(unittest.TestCase):
    def test_rejects_raw_exception_message(self):
        source = 'log.warn("operation failed: {}", exception.getMessage());'

        self.assertEqual(1, MODULE.raw_exception_summary_count(source))

    def test_rejects_raw_exception_to_string(self):
        source = 'logger.error("operation failed: {}", exception.toString());'

        self.assertEqual(1, MODULE.raw_exception_summary_count(source))

    def test_rejects_raw_exception_from_named_audit_logger(self):
        source = (
            'private static final Logger AUDIT = LoggerFactory.getLogger("audit");\n'
            'AUDIT.warn("operation failed: {}", exception.getMessage());'
        )

        self.assertEqual(1, MODULE.raw_exception_summary_count(source))

    def test_allows_safe_single_line_summary(self):
        source = (
            'log.warn("operation failed: {}", '
            "SwallowedExceptionLogger.summary(exception));"
        )

        self.assertEqual(0, MODULE.raw_exception_summary_count(source))

    def test_allows_throwable_as_final_argument(self):
        source = 'log.error("operation failed: taskId={}", taskId, exception);'

        self.assertEqual(0, MODULE.raw_exception_summary_count(source))

    def test_ignores_method_names_inside_literals_and_comments(self):
        source = """
            // log.warn("{}", exception.getMessage());
            log.debug("example exception.getMessage() and closing parenthesis )");
        """

        self.assertEqual(0, MODULE.raw_exception_summary_count(source))

    def test_extracts_multiline_logger_call(self):
        source = """
            log.warn(
                "operation failed: {}",
                exception.getMessage());
        """

        self.assertEqual(1, MODULE.raw_exception_summary_count(source))


if __name__ == "__main__":
    unittest.main()
