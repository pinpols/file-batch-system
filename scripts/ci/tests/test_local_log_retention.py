# 用途：临时工作树验证日志归档的内容保存、保护边界和失败处理。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_local_log_retention.py。
"""临时工作树验证日志归档的内容保存、保护边界和失败处理。"""

import importlib.util
import io
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("local_log_retention", ROOT / "scripts/local/manage-logs.py")
LOGS = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = LOGS
SPEC.loader.exec_module(LOGS)


class LocalLogRetentionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        (self.root / ".git").mkdir()
        (self.root / "logs").mkdir()
        (self.root / "docs").mkdir()
        self.now = time.time()
        self.old = self.now - 40 * 86400
        self.policy = LOGS.load_policy(LOGS.DEFAULT_POLICY)
        self.opened = patch.object(LOGS, "open_files", return_value=set())
        self.opened.start()
        self.addCleanup(self.opened.stop)

    def file(self, relative, content=b"old log\x00\n"):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        os.utime(path, (self.old, self.old))
        return path

    def plan(self, category="runs"):
        selected, _ = LOGS.plan(self.root, self.policy, [category], self.now,
                               LOGS.references(self.root), LOGS.linked_targets(self.root),
                               LOGS.open_files(self.root))
        return selected[category]

    def test_preview_does_not_change_files_or_create_archives(self):
        path = self.file("logs/runs/tests/old/output.log")
        before = sorted(self.root.rglob("*"))
        args = SimpleNamespace(category=None, apply=False, prune_archives=False)
        with patch("sys.stdout", new=io.StringIO()):
            LOGS.execute(args, self.root, self.policy)
        self.assertEqual(before, sorted(self.root.rglob("*")))
        self.assertEqual(b"old log\x00\n", path.read_bytes())

    def test_archive_preserves_bytes_and_removes_only_verified_source(self):
        path = self.file("logs/runs/tests/old/output.log", b"evidence\x00\n" * 100)
        units = self.plan()
        archive, hashes = LOGS.archive_units(self.root, "runs", units, self.now)
        manifest = LOGS.verify_archive(archive)
        self.assertEqual("logs/runs/tests/old/output.log", manifest["files"][0]["path"])
        with tarfile.open(archive) as package:
            self.assertEqual(b"evidence\x00\n" * 100,
                             package.extractfile(manifest["files"][0]["path"]).read())
        LOGS.remove_archived_sources(self.root, units, hashes)
        self.assertFalse(path.parent.exists())
        self.assertTrue(archive.exists())

    def test_pins_references_latest_open_files_and_symlinks_are_protected(self):
        reference = self.file("logs/runs/tests/referenced/output.log")
        (self.root / "docs/evidence.md").write_text(f"证据 `{reference.relative_to(self.root)}`\n")
        self.file("logs/runs/tests/pinned/.keep", b"")
        self.file("logs/runs/tests/pinned/output.log")
        latest = self.file("logs/runs/tests/latest-run/output.log")
        (latest.parent.parent / "latest").symlink_to(latest.parent)
        opened = self.file("logs/runs/tests/open/output.log")
        LOGS.open_files.return_value = {opened}
        outside = self.file("outside/valuable.log")
        link_dir = self.root / "logs/runs/tests/linked"
        link_dir.mkdir()
        (link_dir / "external").symlink_to(outside)
        self.assertEqual([], self.plan())
        self.assertTrue(outside.exists())

    def test_recent_nested_file_keeps_old_directory(self):
        path = self.file("logs/runs/tests/long-running/nested/output.log")
        os.utime(path, None)
        os.utime(path.parent.parent, (self.old, self.old))
        self.assertEqual([], self.plan())

    def test_corrupt_archive_never_removes_source(self):
        path = self.file("logs/runs/tests/old/output.log")
        verifier = LOGS.verify_archive

        def corrupt(package):
            package.write_bytes(b"broken compressed data")
            return verifier(package)

        with patch.object(LOGS, "verify_archive", side_effect=corrupt):
            with self.assertRaises(tarfile.TarError):
                LOGS.archive_units(self.root, "runs", self.plan(), self.now)
        self.assertTrue(path.exists())
        self.assertEqual([], list((self.root / "logs/archive").rglob("*.tar.gz")))
        self.assertEqual([], list((self.root / "logs/archive").rglob(".partial-*")))

    def test_changed_source_after_archiving_is_kept(self):
        path = self.file("logs/runs/tests/old/output.log")
        units = self.plan()
        archive, hashes = LOGS.archive_units(self.root, "runs", units, self.now)
        path.write_text("new evidence")
        with patch("sys.stdout", new=io.StringIO()):
            LOGS.remove_archived_sources(self.root, units, hashes)
        self.assertEqual("new evidence", path.read_text())
        self.assertTrue(archive.exists())

    def test_verification_root_file_reference_is_protected(self):
        path = self.file("logs/verification/old.log")
        (self.root / "docs/evidence.md").write_text(f"证据 `{path.relative_to(self.root)}`")
        self.assertEqual([], self.plan("verification"))

    def test_expiry_requires_managed_archive_and_honors_new_evidence_reference(self):
        source = self.file("logs/runs/tests/old/output.log")
        archive, hashes = LOGS.archive_units(self.root, "runs", self.plan(), self.now - 100 * 86400)
        LOGS.remove_archived_sources(self.root, self.plan(), hashes)
        self.assertEqual([archive], list(LOGS.expired_archives(
            self.root, self.policy, self.now, set(), set())))
        (self.root / "docs/evidence.md").write_text(f"证据 `{source.relative_to(self.root)}`")
        self.assertEqual([], list(LOGS.expired_archives(
            self.root, self.policy, self.now, LOGS.references(self.root), set())))

    def test_apply_rejects_confirmation_for_another_worktree(self):
        with patch.object(sys, "argv", ["manage-logs.py", "--root", str(self.root),
                                        "--apply", "--confirm-root", str(self.root / "wrong")]):
            with patch("sys.stderr", new=io.StringIO()):
                with self.assertRaises(SystemExit) as failure:
                    LOGS.main()
        self.assertEqual(2, failure.exception.code)
        self.assertFalse((self.root / ".local").exists())

    def test_current_logs_are_excluded_and_repeated_apply_is_idempotent(self):
        current = self.file("logs/current/app/orchestrator.log")
        self.file("logs/archive/app-orchestrator/old.log")
        self.file("logs/verification/workflow/old.log")
        args = SimpleNamespace(category=None, apply=True, prune_archives=False)
        with patch("sys.stdout", new=io.StringIO()):
            LOGS.execute(args, self.root, self.policy)
            archives = list((self.root / "logs/archive").rglob("*.tar.gz"))
            LOGS.execute(args, self.root, self.policy)
        self.assertEqual(2, len(archives))
        self.assertEqual(sorted(archives), sorted((self.root / "logs/archive").rglob("*.tar.gz")))
        self.assertEqual(b"old log\x00\n", current.read_bytes())

    def test_symlinked_archive_parent_does_not_write_outside_logs(self):
        self.file("logs/runs/tests/old/output.log")
        outside = self.root / "outside"
        outside.mkdir()
        (self.root / "logs/archive").symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "软链"):
            LOGS.archive_units(self.root, "runs", self.plan(), self.now)
        self.assertEqual([], list(outside.iterdir()))

    def test_missing_occupancy_evidence_stops_before_writing(self):
        source = self.file("logs/runs/tests/old/output.log")
        LOGS.open_files.side_effect = ValueError("lsof unavailable")
        args = SimpleNamespace(category=None, apply=True, prune_archives=False)
        with self.assertRaisesRegex(ValueError, "lsof"):
            LOGS.execute(args, self.root, self.policy)
        self.assertTrue(source.exists())
        self.assertFalse((self.root / "logs/archive").exists())

    def test_concurrent_maintenance_is_rejected(self):
        with LOGS.maintenance_lock(self.root):
            with self.assertRaisesRegex(ValueError, "另一日志归档任务"):
                with LOGS.maintenance_lock(self.root):
                    self.fail("second writer acquired lock")

    def test_relative_markdown_evidence_links_are_protected(self):
        self.file("logs/verification/workflow/old.log")
        directory = self.root / "docs/plans"
        directory.mkdir()
        (directory / "evidence.md").write_text("[证据](../../logs/verification/workflow/old.log)")
        self.assertEqual([], self.plan("verification"))

    def test_latest_archive_is_not_expired(self):
        self.file("logs/runs/tests/old/output.log")
        archive, _ = LOGS.archive_units(self.root, "runs", self.plan(), self.now - 100 * 86400)
        (self.root / "logs/latest-archive").symlink_to(archive)
        self.assertEqual([], list(LOGS.expired_archives(self.root, self.policy, self.now, set(), set())))

    def test_zero_day_override_remains_a_read_only_preview(self):
        source = self.file("logs/runs/tests/old/output.log")
        with patch.object(sys, "argv", ["manage-logs.py", "--root", str(self.root), "--keep-days", "0"]):
            with patch("sys.stdout", new=io.StringIO()):
                self.assertEqual(0, LOGS.main())
        self.assertTrue(source.exists())
        self.assertFalse((self.root / "logs/archive").exists())


if __name__ == "__main__":
    unittest.main()
