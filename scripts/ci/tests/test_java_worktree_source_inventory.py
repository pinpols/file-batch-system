#!/usr/bin/env python3
# 用途：Java 门禁必须覆盖未跟踪生产源码,同时保持显式提交范围和测试目录边界。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_java_worktree_source_inventory.py。
"""Java 门禁必须覆盖未跟踪生产源码,同时保持显式提交范围和测试目录边界。"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).parents[1]


def load_checker(name: str):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), SCRIPTS / f"{name}.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


EMPTY = load_checker("check-empty-checks")
READABILITY = load_checker("check-java-readability")
TEXT_BLOCK = load_checker("check-java-text-block-style")
STRUCTURED = load_checker("check-java-structured-string-concat")
PRODUCTION = "batch-common/src/main/java/probe/Probe.java"
TEST_SOURCE = "batch-common/src/test/java/probe/ProbeTest.java"


@contextlib.contextmanager
def temporary_repository():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "empty-hooks").mkdir()
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        git_commit(root)
        previous = Path.cwd()
        try:
            os.chdir(root)
            with (
                patch.object(READABILITY, "ROOT", root),
                patch.object(TEXT_BLOCK, "ROOT", root),
                patch.object(STRUCTURED, "ROOT", root),
            ):
                yield root
        finally:
            os.chdir(previous)


def git_commit(root: Path):
    subprocess.run(
        ["git", "-C", str(root), "-c", "user.name=gate-fixture", "-c",
         "user.email=gate-fixture@example.invalid", "-c", "commit.gpgsign=false", "-c",
         f"core.hooksPath={root / 'empty-hooks'}", "commit", "--allow-empty", "-qm", "fixture"],
        check=True,
    )


def write_source(root: Path, name: str, content: str) -> Path:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


class JavaWorktreeSourceInventoryTest(unittest.TestCase):

    def test_structured_default_inventory_includes_untracked_and_omits_deleted(self):
        with temporary_repository() as root:
            production = write_source(root, PRODUCTION, "class Probe {}\n")
            subprocess.run(["git", "add", PRODUCTION], check=True)
            git_commit(root)
            production.unlink()
            write_source(root, TEST_SOURCE, "class ProbeTest {}\n")

            self.assertEqual(STRUCTURED.changed_java_paths(None, []), [TEST_SOURCE])
            self.assertEqual(STRUCTURED.changed_java_paths("HEAD", []), [])

    def test_structured_cli_rejects_new_untracked_template_and_accepts_text_block(self):
        with temporary_repository() as root:
            write_source(root, TEST_SOURCE, '''class ProbeTest {
  String sql = "SELECT id" +
      " FROM batch.job_instance" +
      " WHERE status = 'SUCCESS'";
}''')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(STRUCTURED.main([]), 1)
            write_source(root, TEST_SOURCE, '''class ProbeTest {
  String sql = """
      SELECT id FROM batch.job_instance WHERE status = 'SUCCESS'
      """;
}''')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(STRUCTURED.main([]), 0)

    def test_text_block_inventory_omits_deleted_tracked_sources(self):
        with temporary_repository() as root:
            source = write_source(root, PRODUCTION, "class Probe {}\n")
            subprocess.run(["git", "add", PRODUCTION], check=True)
            git_commit(root)
            source.unlink()

            self.assertEqual(TEXT_BLOCK.tracked_java_sources(), [])
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(TEXT_BLOCK.main([]), 0)

    def test_text_block_cli_checks_untracked_violation_then_accepts_valid_layout(self):
        with temporary_repository() as root:
            write_source(root, PRODUCTION, 'class Probe { String text = """\n'
                         '  select 1""";\n}\n')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(TEXT_BLOCK.main([]), 1)
            write_source(root, PRODUCTION, 'class Probe { String text = """\n'
                         '  select 1\n  """;\n}\n')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(TEXT_BLOCK.main([]), 0)

    def test_text_block_inventory_includes_staged_and_test_sources_once_but_not_ignored(self):
        with temporary_repository() as root:
            production = write_source(root, PRODUCTION, "class Probe {}\n")
            test_source = write_source(root, TEST_SOURCE, "class ProbeTest {}\n")
            write_source(root, ".gitignore", "ignored/\n")
            write_source(root, "ignored/Generated.java", "class Generated {}\n")
            subprocess.run(["git", "add", PRODUCTION], check=True)

            self.assertEqual(TEXT_BLOCK.tracked_java_sources(), sorted([production, test_source]))

    def test_text_block_explicit_candidates_keep_requested_scope(self):
        with temporary_repository() as root:
            production = write_source(root, PRODUCTION, "class Probe {}\n")
            write_source(root, TEST_SOURCE, "class ProbeTest {}\n")

            self.assertEqual(TEXT_BLOCK.tracked_java_sources([PRODUCTION, "missing.java"]), [production])

    def test_empty_default_includes_untracked_production_lines(self):
        with temporary_repository() as root:
            write_source(root, PRODUCTION, "class Probe { boolean missing(Object value) {\n"
                         "  return value == null;\n} }\n")
            write_source(root, TEST_SOURCE, "class ProbeTest { Object value = null; }\n")

            additions = EMPTY.added_lines(None)

            self.assertEqual({name for name, _, _ in additions}, {PRODUCTION})
            self.assertIn((PRODUCTION, 2, "  return value == null;"), additions)

    def test_empty_explicit_base_does_not_mix_untracked_inputs(self):
        with temporary_repository() as root:
            write_source(root, PRODUCTION, "class Probe { boolean missing(Object v) { return v == null; } }\n")

            self.assertEqual(EMPTY.added_lines("HEAD"), [])

    def test_staged_new_source_is_not_duplicated(self):
        with temporary_repository() as root:
            write_source(root, PRODUCTION, "class Probe { boolean missing(Object v) { return v == null; } }\n")
            subprocess.run(["git", "add", PRODUCTION], check=True)

            self.assertEqual(len(EMPTY.added_lines(None)), 1)

    def test_empty_cli_rejects_untracked_violation_and_accepts_valid_source(self):
        with temporary_repository() as root:
            write_source(root, PRODUCTION, "class Probe { boolean missing(Object v) { return v == null; } }\n")
            with patch.object(sys, "argv", ["check-empty-checks.py"]):
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(EMPTY.main(), 1)
                write_source(root, PRODUCTION, "class Probe { boolean missing(Object v) { return EmptyChecks.isNull(v); } }\n")
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(EMPTY.main(), 0)

    def test_readability_includes_untracked_production_but_not_tests(self):
        with temporary_repository() as root:
            production = write_source(root, PRODUCTION, "class Probe { void run() { var value = 1; } }\n")
            write_source(root, TEST_SOURCE, "class ProbeTest { void run() { var value = 1; } }\n")

            self.assertEqual(READABILITY.tracked_main_sources(), [production])

    def test_existing_helper_on_same_line_does_not_hide_native_predicates(self):
        with temporary_repository() as root:
            with patch.object(sys, "argv", ["check-empty-checks.py"]):
                for source in (
                    "class Probe { boolean missing(Object a, Object b) { return EmptyChecks.isNull(a) || b == null; } }\n",
                    "class Probe { boolean missing(Object a, Object b) { return Objects.requireNonNull(a) == b && b == null; } }\n",
                ):
                    with self.subTest(source=source):
                        write_source(root, PRODUCTION, source)
                        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                            self.assertEqual(EMPTY.main(), 1)

    def test_null_precondition_is_allowed_without_masking_other_checks(self):
        with temporary_repository() as root:
            write_source(root, PRODUCTION, "class Probe { Object validate(Object v) { return Objects.requireNonNull(v); } }\n")
            with patch.object(sys, "argv", ["check-empty-checks.py"]):
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(EMPTY.main(), 0)

    def test_readability_cli_rejects_untracked_var_and_configuration_then_accepts_fix(self):
        with temporary_repository() as root:
            write_source(root, PRODUCTION, "@Configuration class Probe { void run() { var value = 1; } }\n")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(READABILITY.main([]), 1)
            self.assertIn("explicit type", output.getvalue())
            self.assertIn("proxyBeanMethods", output.getvalue())

            write_source(root, PRODUCTION, "@Configuration(proxyBeanMethods = false) class Probe { void run() { int value = 1; } }\n")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(READABILITY.main([]), 0)

    def test_deleted_and_ignored_sources_are_not_read(self):
        with temporary_repository() as root:
            kept = write_source(root, PRODUCTION, "class Probe {}\n")
            deleted = write_source(root, "batch-common/src/main/java/probe/Deleted.java", "class Deleted {}\n")
            subprocess.run(["git", "add", "batch-common"], check=True)
            git_commit(root)
            deleted.unlink()
            ignored = "batch-common/src/main/java/probe/Ignored.java"
            write_source(root, ".gitignore", ignored + "\n")
            write_source(root, ignored, "class Ignored { void run() { var value = 1; } }\n")

            self.assertEqual(READABILITY.tracked_main_sources(), [kept])
            self.assertEqual(EMPTY.added_lines(None), [])


if __name__ == "__main__":
    unittest.main()
