from __future__ import annotations

import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "document_naming", Path(__file__).resolve().parents[1] / "check-doc-timestamp-policy.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DocumentNamingPolicyTest(unittest.TestCase):
    def result(self, name: str) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "docs/audit").mkdir(parents=True)
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("# 文档\n")
            output = io.StringIO()
            with patch.object(MODULE, "ROOT", root), patch.object(
                MODULE, "tracked_markdown", return_value=[name]
            ), patch.object(MODULE, "UNDATED_REPORT_DOC_ALLOWLIST", set()), patch.object(
                MODULE, "MONTH_OR_PHASE_PLAN_ALLOWLIST", set()
            ), contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                code = MODULE.main()
            return code, output.getvalue()

    def test_current_asset_has_stable_undated_name(self) -> None:
        self.assertEqual(0, self.result("docs/design/core-model.md")[0])

    def test_periodic_report_uses_complete_date(self) -> None:
        self.assertEqual(0, self.result("docs/audit/review-2026-10-24.md")[0])

    def test_month_and_half_year_cannot_replace_date(self) -> None:
        for name in ("docs/plans/review-2026-07.md", "docs/plans/review-2026-h2.md"):
            with self.subTest(name=name):
                self.assertNotEqual(0, self.result(name)[0])

    def test_impossible_calendar_date_is_rejected(self) -> None:
        code, output = self.result("docs/audit/review-2026-02-30.md")
        self.assertNotEqual(0, code)
        self.assertIn("invalid calendar date", output)

    def test_archived_material_retains_date_and_matching_category(self) -> None:
        self.assertEqual(0, self.result("docs/archive/audit/review-v2-2026-10-24.md")[0])
        self.assertNotEqual(0, self.result("docs/archive/audit/review.md")[0])
        self.assertNotEqual(0, self.result("docs/archive/unowned/review-2026-10-24.md")[0])

    def test_archived_supporting_material_uses_business_topic(self) -> None:
        code, output = self.result("docs/archive/audit/adr-038-review-2026-10-24.md")
        self.assertNotEqual(0, code)
        self.assertIn("business topic", output)


if __name__ == "__main__":
    unittest.main()
