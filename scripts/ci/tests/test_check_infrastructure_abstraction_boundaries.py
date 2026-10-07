"""基础设施边界增量门禁:既有引用与新增引用分别判定。"""

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "check-infrastructure-abstraction-boundaries.py"
SPEC = importlib.util.spec_from_file_location("infrastructure_boundary", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class InfrastructureBoundaryTest(unittest.TestCase):
    def test_existing_import_survives_logging_only_change(self):
        before = "import java.sql.SQLException;\nlog.warn(value);"
        after = "import java.sql.SQLException;\nlog.warn(LogSanitizer.value(value));"
        self.assertEqual(MODULE.new_references(before, after), [])

    def test_new_import_is_rejected(self):
        self.assertEqual(len(MODULE.new_references("", "import java.sql.SQLException;")), 1)

    def test_additional_usage_cannot_reuse_existing_exemption(self):
        reference = "return t instanceof java.sql.SQLTimeoutException;"
        self.assertEqual(len(MODULE.new_references(reference, reference + "\n" + reference)), 1)

    def test_formatting_and_comments_do_not_introduce_debt(self):
        before = "import java.sql.SQLException;"
        after = "// java.sql.Connection\n import   java.sql.SQLException;"
        self.assertEqual(MODULE.new_references(before, after), [])


if __name__ == "__main__":
    unittest.main()
