#!/usr/bin/env python3
"""核心术语文档同步门禁测试。"""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/ci/check-terminology-doc-sync.py"
SPEC = importlib.util.spec_from_file_location("check_terminology_doc_sync", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class TerminologyDocSyncTest(unittest.TestCase):
    def test_stale_values_are_detected_and_write_repairs_them(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            enum_path = root / "SampleStatus.java"
            document = root / "docs/sample.md"
            document.parent.mkdir(parents=True)
            enum_path.write_text(
                "public enum SampleStatus {\n  CREATED(0),\n  SUCCESS(1);\n  private final int code;\n}\n",
                encoding="utf-8",
            )
            document.write_text(
                "<!-- enum-sync:SampleStatus:start -->\n`CREATED`\n"
                "<!-- enum-sync:SampleStatus:end -->\n",
                encoding="utf-8",
            )

            with (
                patch.object(MODULE, "ROOT", root),
                patch.object(MODULE, "ENUM_SOURCES", {"SampleStatus": enum_path}),
                patch.object(
                    MODULE,
                    "REQUIRED_MARKERS",
                    {"docs/sample.md": {"SampleStatus"}},
                ),
            ):
                self.assertEqual(1, len(MODULE.process_document(document, write=False)))
                self.assertEqual([], MODULE.process_document(document, write=True))
                self.assertEqual([], MODULE.process_document(document, write=False))

            self.assertIn("`CREATED`, `SUCCESS`", document.read_text(encoding="utf-8"))

    def test_missing_required_marker_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            document = root / "docs/sample.md"
            document.parent.mkdir(parents=True)
            document.write_text("# Sample\n", encoding="utf-8")

            with (
                patch.object(MODULE, "ROOT", root),
                patch.object(MODULE, "ENUM_SOURCES", {}),
                patch.object(
                    MODULE,
                    "REQUIRED_MARKERS",
                    {"docs/sample.md": {"SampleStatus"}},
                ),
            ):
                errors = MODULE.process_document(document, write=False)

            self.assertEqual(
                ["docs/sample.md: enum-sync:SampleStatus marker count is 0, expected 1"],
                errors,
            )


if __name__ == "__main__":
    unittest.main()
