#!/usr/bin/env python3
"""check-java-suppression-registry.py 的扫描范围与规则登记回归测试。"""

from __future__ import annotations

import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "check-java-suppression-registry.py"
SPEC = importlib.util.spec_from_file_location("check_java_suppression_registry", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class JavaSuppressionRegistryTest(unittest.TestCase):

    def test_default_enumeration_includes_untracked_files(self) -> None:
        """默认扫描必须覆盖尚未 git add 的新文件。

        只列已跟踪文件时，本地在途新增的 suppression 不会被校验，直到提交后才暴露；
        所以枚举必须同时带 --others 与 --exclude-standard。
        """
        captured: dict[str, list[str]] = {}
        original = MODULE.subprocess.run

        def fake_run(args, **_kwargs):
            captured["args"] = list(args)
            return subprocess.CompletedProcess(args, 0, stdout="", stderr="")

        MODULE.subprocess.run = fake_run
        try:
            MODULE.production_sources()
        finally:
            MODULE.subprocess.run = original

        args = captured["args"]
        self.assertEqual(args[:2], ["git", "ls-files"])
        self.assertIn("--others", args)
        self.assertIn("--exclude-standard", args)

    def test_candidate_filter_keeps_only_production_sources(self) -> None:
        production = sorted(MODULE.ROOT.glob("batch-common/src/main/java/**/*.java"))[0]
        test_source = sorted(MODULE.ROOT.glob("batch-common/src/test/java/**/*.java"))[0]
        production_rel = production.relative_to(MODULE.ROOT).as_posix()
        test_rel = test_source.relative_to(MODULE.ROOT).as_posix()

        kept = MODULE.production_sources(
            [production_rel, test_rel, "docs/example.java", "batch-common/README.md"])

        self.assertEqual(
            [path.relative_to(MODULE.ROOT).as_posix() for path in kept], [production_rel])

    def test_main_flags_unregistered_rule(self) -> None:
        """未登记的规则必须让门禁失败，已登记的规则必须通过。"""
        original = MODULE.production_sources
        # 临时目录建在仓库内，scan() 才能算出相对路径。
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT) as tmp:
            probe = Path(tmp) / "ZzSuppressionProbe.java"
            MODULE.production_sources = lambda _candidates=None: [probe]
            try:
                probe.write_text(
                    "package probe;\n\n"
                    '@SuppressWarnings("probe:Unregistered")\n'
                    "public class ZzSuppressionProbe {}\n",
                    encoding="utf-8",
                )
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(MODULE.main([]), 1)

                probe.write_text(
                    "package probe;\n\n"
                    '@SuppressWarnings("unchecked")\n'
                    "public class ZzSuppressionProbe {}\n",
                    encoding="utf-8",
                )
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(MODULE.main([]), 0)
            finally:
                MODULE.production_sources = original


if __name__ == "__main__":
    unittest.main()
