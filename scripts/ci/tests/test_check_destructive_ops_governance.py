import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CHECKER = ROOT / "scripts/ci/check-destructive-ops-governance.py"
DESTRUCTIVE_OPS = ROOT / "scripts/lib/destructive-ops.sh"


class DestructiveOpsGovernanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "scripts/local").mkdir(parents=True)
        self.baseline = self.root / "baseline.json"

    def tearDown(self):
        self.temp.cleanup()

    def run_checker(self):
        return subprocess.run(
            ["python3", str(CHECKER), "--root", str(self.root), "--baseline", str(self.baseline)],
            capture_output=True,
            text=True,
            check=False,
        )

    def write_baseline(self, entries):
        self.baseline.write_text(json.dumps(entries), encoding="utf-8")

    def test_detects_growth_for_database_and_service_deletes(self):
        script = self.root / "scripts/local/cleanup.sh"
        script.write_text(
            "rm \"$TMP_FILE\"\n"
            "psql -c 'TRUNCATE TABLE biz.customer'\n"
            "dropdb batch_test\n"
            "pg_restore -c -d batch_test backup.dump\n"
            "kafka-topics.sh --bootstrap-server localhost:9092 \\\n"
            "  --delete --topic batch.test\n"
            "kafka-consumer-groups.sh --delete --group batch-test\n"
            "kafka-delete-records.sh --bootstrap-server localhost:9092\n"
            "aws s3api delete-objects --bucket test --delete file://objects.json\n"
            "aws s3 sync ./backup s3://test/backup --delete\n"
            "minio_mc rm --recursive --force local/test-bucket/prefix\n"
            "mc mirror --remove local/source local/target\n"
            "redis-cli -h localhost DEL batch:test:key\n"
            "echo 'DEL batch:test:old' | redis-cli --pipe\n"
            "valkey-cli FLUSHDB\n",
            encoding="utf-8",
        )
        self.write_baseline({})

        result = self.run_checker()

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        for category in (
            "filesystem-remove",
            "database-drop",
            "postgres-clean-restore",
            "database-truncate",
            "kafka-delete",
            "s3-delete",
            "redis-delete",
        ):
            self.assertIn(category, result.stderr)

    def test_rm_scanner_does_not_confuse_docker_run_rm_flag(self):
        script = self.root / "scripts/local/containers.sh"
        script.write_text("docker run --rm image:tag\n", encoding="utf-8")
        self.write_baseline({})

        result = self.run_checker()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_detects_machine_and_kubernetes_deletion_commands(self):
        (self.root / "scripts/local/maintenance.sh").write_text(
            "find /tmp/owned -type f -delete\n"
            "kubectl -n sandbox delete pod worker-0\n"
            "helm uninstall ephemeral-release\n",
            encoding="utf-8",
        )
        self.write_baseline({})

        result = self.run_checker()

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("filesystem-permanent-delete", result.stderr)
        self.assertIn("kubernetes-delete", result.stderr)

    def test_passes_when_inventory_is_at_or_below_reviewed_baseline(self):
        script = self.root / "scripts/local/cleanup.sh"
        script.write_text("rm -rf \"$TMP_DIR\"\n", encoding="utf-8")
        self.write_baseline({"scripts/local/cleanup.sh|filesystem-remove": 1})

        result = self.run_checker()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_ignores_shell_comments(self):
        script = self.root / "scripts/local/safe.sh"
        script.write_text("# rm -rf /\necho safe\n", encoding="utf-8")
        self.write_baseline({})

        result = self.run_checker()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_local_host_guard_rejects_remote_endpoint(self):
        result = subprocess.run(
            ["bash", "-c", 'source "$1"; batch_require_local_host postgres.example.com', "test", str(DESTRUCTIVE_OPS)],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("目标不是本机/本地 Compose 服务", result.stderr)

    def test_local_docker_guard_rejects_remote_context(self):
        fake_bin = self.root / "bin"
        fake_bin.mkdir()
        docker = fake_bin / "docker"
        docker.write_text(
            "#!/bin/sh\n"
            "case \"$1:$2\" in\n"
            "  info:*) exit 0 ;;\n"
            "  'context:show') echo remote-prod ;;\n"
            "  'context:inspect') echo tcp://prod.example.com:2376 ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        docker.chmod(0o755)
        result = subprocess.run(
            ["bash", "-c", 'source "$1"; batch_require_local_docker_context', "test", str(DESTRUCTIVE_OPS)],
            env={**os.environ, "PATH": f"{fake_bin}:{os.environ['PATH']}"},
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("仅允许本机 Docker socket", result.stderr)

    def test_local_docker_guard_requires_compose_project_match(self):
        fake_bin = self.root / "compose-bin"
        fake_bin.mkdir()
        docker = fake_bin / "docker"
        docker.write_text(
            "#!/bin/sh\n"
            "case \"$1:$2\" in\n"
            "  info:*) exit 0 ;;\n"
            "  'context:show') echo local ;;\n"
            "  'context:inspect') echo unix:///var/run/docker.sock ;;\n"
            "esac\n"
            "if [ \"$1\" = inspect ]; then echo other-project; fi\n",
            encoding="utf-8",
        )
        docker.chmod(0o755)
        result = subprocess.run(
            ["bash", "-c", 'source "$1"; batch_require_compose_container pg batch-platform', "test", str(DESTRUCTIVE_OPS)],
            env={**os.environ, "PATH": f"{fake_bin}:{os.environ['PATH']}"},
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("不属于 Compose project", result.stderr)

    def test_local_docker_guard_rejects_protected_project_name(self):
        result = subprocess.run(
            ["bash", "-c", 'source "$1"; batch_require_compose_container pg production', "test", str(DESTRUCTIVE_OPS)],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("受保护环境 production", result.stderr)

    def test_disk_cleanup_requires_typed_root_before_running(self):
        result = subprocess.run(
            ["bash", "scripts/local/cleanup-disk.sh", "--apply"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("--confirm-root", result.stderr)

    def test_failover_drill_requires_target_confirmation_before_kubectl(self):
        result = subprocess.run(
            ["bash", "scripts/ha/failover-drill.sh", "all"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("拒绝执行故障注入", result.stderr)


if __name__ == "__main__":
    unittest.main()
