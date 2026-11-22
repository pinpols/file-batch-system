#!/usr/bin/env python3
# 用途：验证 Bash 3.2 兼容门禁的规则、显式豁免与 Git 钩子覆盖。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_check_shell_bash32_compat.py。
"""macOS 自带 Bash 3.2 与 CI Bash 5 的语法差异必须在提交前被拦截。"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "check-shell-bash32-compat.py"
SPEC = importlib.util.spec_from_file_location("shell_bash32_compat", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class ShellBash32CompatTest(unittest.TestCase):
    def scan(self, content: str) -> str:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "probe.sh"
            script.write_text(content, encoding="utf-8")
            with patch.object(CHECKER, "ROOT", root):
                return "\n".join(CHECKER.scan_file(script))

    def test_case_expansions_are_rejected(self):
        self.assertIn("lowercase-expansion", self.scan('echo "${value,,}"\n'))
        self.assertIn("uppercase-expansion", self.scan('echo "${value^^}"\n'))

    def test_bash4_array_helpers_are_rejected(self):
        self.assertIn("mapfile", self.scan("mapfile -t rows < <(printf 'a\\n')\n"))
        self.assertIn("readarray", self.scan("readarray -t rows < <(printf 'a\\n')\n"))
        self.assertIn("associative-array", self.scan("declare -A lookup=()\n"))
        self.assertIn("associative-array", self.scan("local -A lookup=()\n"))

    def test_indexed_arrays_and_comment_mentions_are_allowed(self):
        self.assertEqual(
            self.scan(
                "# mapfile is documented here\n"
                "local -a rows=()\n"
                "rows+=(\"a\")\n"
            ),
            "",
        )

    def test_explicit_exemption_allows_bash4_script(self):
        self.assertEqual(
            self.scan(
                "#!/usr/bin/env bash\n"
                "# shell-min-version: 4\n"
                "declare -A lookup=()\n"
            ),
            "",
        )

    def test_git_hooks_are_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            hooks = root / ".githooks"
            hooks.mkdir()
            hook = hooks / "pre-commit"
            hook.write_text('echo "${value,,}"\n', encoding="utf-8")
            listing = subprocess.CompletedProcess(args=[], returncode=0, stdout="")
            with patch.object(CHECKER, "ROOT", root), patch.object(
                CHECKER.subprocess, "run", return_value=listing
            ):
                self.assertEqual(CHECKER.tracked_shell_files(), [hook])
                self.assertIn(
                    "lowercase-expansion", "\n".join(CHECKER.scan_file(hook))
                )


if __name__ == "__main__":
    unittest.main()
