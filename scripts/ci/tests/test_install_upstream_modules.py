# 用途：验证 E2E 上游安装与打包插件预热的成功及失败传播。
# 用法：python3 -m unittest scripts.ci.tests.test_install_upstream_modules。
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "install-upstream-modules.sh"


class InstallUpstreamModulesTest(unittest.TestCase):
    def run_installer(self, plugin_exit: int):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            (root / "mvnw").write_text(
                '#!/bin/bash\nprintf "%s\\n" "$*" >> "$CALL_LOG"\n'
                'case "$*" in *spring-boot:help*) exit "$PLUGIN_EXIT";; esac\nexit 0\n',
                encoding="utf-8",
            )
            for command in ("find", "sleep"):
                executable = bin_dir / command
                executable.write_text("#!/bin/bash\nexit 0\n", encoding="utf-8")
                executable.chmod(0o755)
            (root / "mvnw").chmod(0o755)
            log = root / "calls.log"
            env = dict(os.environ, PATH=f"{bin_dir}:{os.environ['PATH']}",
                       CALL_LOG=str(log), PLUGIN_EXIT=str(plugin_exit))
            result = subprocess.run(["bash", str(SCRIPT)], cwd=root, env=env,
                                    text=True, capture_output=True, check=False)
            return result, log.read_text(encoding="utf-8").splitlines()

    def test_install_and_plugin_resolution_succeed(self):
        result, calls = self.run_installer(0)
        self.assertEqual(0, result.returncode)
        self.assertEqual(2, len(calls))
        self.assertIn("spring-boot:help", calls[1])

    def test_plugin_resolution_failure_blocks_after_retries(self):
        result, calls = self.run_installer(1)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(3, sum("spring-boot:help" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
