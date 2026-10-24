from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "check-docs-structure.py"
SPEC = importlib.util.spec_from_file_location("check_docs_structure", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DocumentIndexCoverageTest(unittest.TestCase):
    def check_tree(self, pages: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, content in pages.items():
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content)
            known = {p.resolve() for p in root.rglob("*.md")}
            errors: list[str] = []
            with patch.object(MODULE, "ROOT", root), patch.object(MODULE, "DOCS", root / "docs"):
                MODULE.check_layered_directory_indexes(errors, known)
            return errors

    def test_plain_filename_does_not_replace_jump_link(self) -> None:
        errors = self.check_tree({
            "docs/README.md": "[审计](audit/README.md)：审计目录\n",
            "docs/audit/README.md": "report.md：报告说明\n",
            "docs/audit/report.md": "# 报告\n",
        })
        self.assertTrue(any("report.md" in error for error in errors))

    def test_archived_document_requires_local_jump_link(self) -> None:
        errors = self.check_tree({
            "docs/README.md": "[归档](archive/README.md)：历史入口\n",
            "docs/archive/README.md": "[审计](audit/README.md)：历史审计\n",
            "docs/archive/audit/README.md": "# 历史审计\n",
            "docs/archive/audit/report.md": "# 历史报告\n",
        })
        self.assertTrue(any("report.md" in error for error in errors))

    def test_child_name_without_link_does_not_replace_navigation(self) -> None:
        errors = self.check_tree({
            "docs/README.md": "audit/：审计目录\n",
            "docs/audit/README.md": "# 审计\n",
        })
        self.assertTrue(any("audit" in error for error in errors))

    def test_links_with_purpose_cover_current_and_archived_pages(self) -> None:
        errors = self.check_tree({
            "docs/README.md": "[审计](audit/README.md)：当前审计\n[归档](archive/README.md)：历史入口\n",
            "docs/audit/README.md": "[report.md](report.md)：报告覆盖范围\n",
            "docs/audit/report.md": "# 报告\n",
            "docs/archive/README.md": "[历史审计](audit/README.md)：按原基线追溯\n",
            "docs/archive/audit/README.md": "[report.md](report.md)：历史报告范围\n",
            "docs/archive/audit/report.md": "# 历史报告\n",
        })
        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
