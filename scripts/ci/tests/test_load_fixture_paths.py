# 用途：验证压测夹具的宿主路径、Worker 路径及清理范围隔离。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_load_fixture_paths.py。
"""使用本地替身验证脚本参数，不连接数据库或 Console。"""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
PREPARE = ROOT / "load-tests/scripts/prepare-worker-load-data.sh"
CLEANUP = ROOT / "load-tests/scripts/cleanup-worker-load-data.sh"


class LoadFixturePathsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.host_root = self.root / "isolated host"
        self.worker_root = "/worker-visible root"
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.calls = self.root / "calls.jsonl"
        self._stub("psql", r'''import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with Path(os.environ["CAPTURE_CALLS"]).open("a") as out:
    out.write(json.dumps(args) + "\n")
script = args[args.index("-f") + 1] if "-f" in args else ""
if script.endswith("select-single-job-workflow-id.sql"):
    print("123")
elif script.endswith("select-workflow-code-by-id.sql"):
    print("JOB-WF-123")
elif script.endswith("select-worker-load-dispatch-file-id.sql"):
    print("101")
elif script.endswith("p2-run-instance-residue-count.sql"):
    print("0")
''')
        self._stub("curl", 'print(\'{"code":"SUCCESS"}\')\n')
        self._stub("sleep", "pass\n")
        self.env = {
            "PATH": str(self.bin_dir) + os.pathsep + os.environ["PATH"],
            "BATCH_ENV_LOADED": "1",
            "RUN_ID": "owned-run",
            "OUT_DIR": str(self.root / "output"),
            "LOAD_TEST_HOST_ROOT": str(self.host_root),
            "LOAD_TEST_WORKER_ROOT": self.worker_root,
            "BATCH_CONSOLE_TOKEN": "local-test-token",
            "BATCH_CONSOLE_CSRF_TOKEN": "local-test-csrf",
            "CONSOLE_BASE_URL": "http://console.invalid",
            "PGHOST": "database.invalid",
            "PGPORT": "1",
            "PGUSER": "fixture",
            "PLATFORM_DB": "fixture_platform",
            "BUSINESS_DB": "fixture_business",
            "CAPTURE_CALLS": str(self.calls),
            "PROCESS_SOURCE_ROWS": "1",
            "PROCESS_ACCOUNT_COUNT": "1",
        }

    def _stub(self, name, body):
        path = self.bin_dir / name
        path.write_text(f"#!{sys.executable}\n" + body)
        path.chmod(0o755)

    def _run(self, script):
        return subprocess.run(
            ["bash", str(script)], cwd=ROOT, env=self.env, text=True, capture_output=True
        )

    def test_preparation_uses_host_path_for_files_and_worker_paths_for_sql(self):
        result = self._run(PREPARE)
        self.assertEqual(0, result.returncode, result.stderr)
        fixture = self.host_root / "load-test/owned-run-dispatch-000001.txt"
        self.assertTrue(fixture.is_file())
        self.assertTrue((self.host_root / "local-dispatch").is_dir())
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        platform = next(call for call in calls if any(
            arg.endswith("prepare-worker-load-platform.sql") for arg in call
        ))
        self.assertIn("dispatch_dir=/worker-visible root/load-test", platform)
        self.assertIn("dispatch_target_dir=/worker-visible root/local-dispatch", platform)
        sql = (ROOT / "load-tests/sql/prepare-worker-load-platform.sql").read_text()
        self.assertIn(":'dispatch_dir'", sql)
        self.assertIn(":'dispatch_target_dir'", sql)
        self.assertNotIn("/tmp/batch/local-dispatch", sql)

    def test_cleanup_only_removes_owned_run_in_host_root(self):
        source = self.host_root / "load-test"
        target = self.host_root / "local-dispatch"
        source.mkdir(parents=True)
        target.mkdir(parents=True)
        for path in (source / "owned-run-dispatch-000001.txt", source / "other-run-dispatch-000001.txt",
                     target / "owned-run-output.txt", target / "other-run-output.txt"):
            path.write_text("fixture")
        result = self._run(CLEANUP)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((source / "owned-run-dispatch-000001.txt").exists())
        self.assertFalse((target / "owned-run-output.txt").exists())
        self.assertTrue((source / "other-run-dispatch-000001.txt").exists())
        self.assertTrue((target / "other-run-output.txt").exists())

    def test_unsafe_root_is_rejected_before_files_or_database(self):
        for root in ("/", "//", "/.", "relative", "/tmp/../elsewhere"):
            with self.subTest(root=root):
                self.env["LOAD_TEST_HOST_ROOT"] = root
                result = self._run(PREPARE)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("LOAD_TEST_HOST_ROOT", result.stderr)
                self.assertFalse(self.calls.exists())

    def test_worker_path_is_validated_before_files_or_database(self):
        self.env["LOAD_TEST_WORKER_ROOT"] = "/tmp/../outside"
        result = self._run(PREPARE)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("LOAD_TEST_WORKER_ROOT", result.stderr)
        self.assertFalse(self.calls.exists())

    def test_original_default_roots_remain_unchanged(self):
        environment = {"PATH": os.environ["PATH"], "BATCH_ENV_LOADED": "1"}
        result = subprocess.run(
            ["bash", "-c", 'source load-tests/scripts/env.sh; printf "%s\n%s\n" '
             '"$LOAD_TEST_HOST_ROOT" "$LOAD_TEST_WORKER_ROOT"'],
            cwd=ROOT, env=environment, text=True, capture_output=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["/tmp/batch", "/tmp/batch"], result.stdout.splitlines())


if __name__ == "__main__":
    unittest.main()
