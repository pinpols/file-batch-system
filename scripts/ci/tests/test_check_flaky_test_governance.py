import datetime as dt
import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-flaky-test-governance.py"
SPEC = importlib.util.spec_from_file_location("check_flaky_test_governance", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class FlakyTestGovernanceTest(unittest.TestCase):
    def test_accepts_complete_future_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "module/src/test/java/example/Test.java"
            path.parent.mkdir(parents=True)
            path.write_text(
                '@FlakyTest(issue = "#123", owner = "@batch-team", expiresOn = "2026-12-31")\n',
                encoding="utf-8",
            )
            self.assertEqual([], MODULE.find_violations(Path(directory), dt.date(2026, 10, 7)))

    def test_rejects_expired_or_incomplete_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "module/src/test/java/example/Test.java"
            path.parent.mkdir(parents=True)
            path.write_text(
                '@FlakyTest(issue = "123", owner = "team", expiresOn = "2026-01-01")\n',
                encoding="utf-8",
            )
            violations = MODULE.find_violations(Path(directory), dt.date(2026, 10, 7))
            self.assertEqual(3, len(violations))


if __name__ == "__main__":
    unittest.main()
