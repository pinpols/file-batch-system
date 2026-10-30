# 用途：验证推送钩子隔离 Git 环境并保护主分支。
# 用法：在仓库根目录运行 python3 -m unittest scripts.ci.tests.test_pre_push_environment。
"""推送钩子隔离 Git 环境，并保留受保护分支检查。"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]


class PrePushEnvironmentTest(unittest.TestCase):
    def test_isolates_git_environment_and_protects_main(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "repo"
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            hooks = repo / ".githooks"
            hooks.mkdir()
            shutil.copy(ROOT / ".githooks/pre-push", hooks / "pre-push")
            gate = repo / "scripts/ci/run-gate.sh"
            gate.parent.mkdir(parents=True)
            gate.write_text(
                "#!/usr/bin/env bash\n"
                "set -euo pipefail\n"
                '[[ -z "${GIT_DIR:-}" ]]\n'
                '[[ "$(git rev-parse --show-toplevel)" == "$PWD" ]]\n'
                'fixture="$(mktemp -d)"\n'
                'git init -q "$fixture"\n'
                'printf test > "$fixture/a"\n'
                'git -C "$fixture" add a\n'
                '[[ "$(git config --local --get core.bare)" == false ]]\n'
                'rm -rf "$fixture"\n',
                encoding="utf-8",
            )
            env = {**os.environ, "GIT_DIR": str(repo / ".git")}
            env.pop("GIT_WORK_TREE", None)
            for target, expected in (("refs/heads/feature/test", 0), ("refs/heads/main", 1)):
                with self.subTest(target=target):
                    result = subprocess.run(
                        ["bash", str(hooks / "pre-push")],
                        cwd=repo,
                        env=env,
                        input=f"refs/heads/local abc {target} 000\n",
                        capture_output=True,
                        text=True,
                    )
                    self.assertEqual(expected, result.returncode, result.stderr)
            bare = subprocess.check_output(
                ["git", "config", "--local", "--get", "core.bare"], cwd=repo, text=True
            )
            self.assertEqual("false", bare.strip())


if __name__ == "__main__":
    unittest.main()
