#!/usr/bin/env python3
# 用途：守护 Shell 兼容检查的工作树覆盖、失败传播和可移植替代路径。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_check_shell_linux_portability.py。
"""删除的索引路径不能中断扫描，现存未跟踪脚本仍须执行兼容规则。"""

from __future__ import annotations

import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "check-shell-linux-portability.py"
SPEC = importlib.util.spec_from_file_location("shell_linux_portability", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class ShellLinuxPortabilityTest(unittest.TestCase):
    def test_default_inventory_omits_deleted_and_retains_untracked_scripts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tracked = root / "tracked.sh"
            untracked = root / "untracked.sh"
            tracked.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
            untracked.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
            listing = subprocess.CompletedProcess(
                args=[], returncode=0, stdout="deleted.sh\ntracked.sh\nuntracked.sh\n"
            )
            with patch.object(CHECKER, "ROOT", root), patch.object(
                CHECKER.subprocess, "run", return_value=listing
            ) as git:
                self.assertEqual(CHECKER.tracked_shell_files(), [tracked, untracked])
                self.assertIn("--others", git.call_args.args[0])

    def test_unsafe_untracked_script_still_fails_after_deleted_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "untracked.sh").write_text("date -v+1d\n", encoding="utf-8")
            listing = subprocess.CompletedProcess(
                args=[], returncode=0, stdout="deleted.sh\nuntracked.sh\n"
            )
            output = io.StringIO()
            with patch.object(CHECKER, "ROOT", root), patch.object(
                CHECKER.subprocess, "run", return_value=listing
            ), contextlib.redirect_stdout(output):
                self.assertEqual(CHECKER.main([]), 1)
            self.assertIn("untracked.sh:1: bsd-date-v", output.getvalue())

    def test_portable_stat_fallback_remains_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "portable.sh"
            script.write_text(
                "stat -f %z data || stat -c %s data\n", encoding="utf-8"
            )
            with patch.object(CHECKER, "ROOT", root):
                self.assertEqual(CHECKER.scan_file(script), [])

    def test_shasum_requires_linux_checksum_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            unsafe = root / "unsafe.sh"
            unsafe.write_text("shasum -a 256 data\n", encoding="utf-8")
            portable = root / "portable.sh"
            portable.write_text(
                "if command -v sha256sum >/dev/null 2>&1; then\n"
                "  sha256sum data\n"
                "else\n"
                "  shasum -a 256 data\n"
                "fi\n",
                encoding="utf-8",
            )
            with patch.object(CHECKER, "ROOT", root):
                self.assertIn(
                    "shasum-sha256-without-fallback",
                    "\n".join(CHECKER.scan_file(unsafe)),
                )
                self.assertEqual(CHECKER.scan_file(portable), [])

    def test_gnu_xargs_r_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "unsafe.sh"
            script.write_text("printf '%s\\n' \"$@\" | xargs -r rm -f\n", encoding="utf-8")
            with patch.object(CHECKER, "ROOT", root):
                self.assertIn("gnu-xargs-r", "\n".join(CHECKER.scan_file(script)))

    def test_git_hooks_are_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            hooks = root / ".githooks"
            hooks.mkdir()
            hook = hooks / "pre-push"
            hook.write_text("date -v+1d\n", encoding="utf-8")
            listing = subprocess.CompletedProcess(args=[], returncode=0, stdout="")
            with patch.object(CHECKER, "ROOT", root), patch.object(
                CHECKER.subprocess, "run", return_value=listing
            ):
                self.assertEqual(CHECKER.tracked_shell_files(), [hook])
                self.assertIn("bsd-date-v", "\n".join(CHECKER.scan_file(hook)))


if __name__ == "__main__":
    unittest.main()
