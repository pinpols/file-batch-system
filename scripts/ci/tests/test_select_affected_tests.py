# 用途：验证测试影响分析按实际测试类名选测。
# 用法：python3 -m unittest scripts/ci/tests/test_select_affected_tests.py
import contextlib
import importlib.util
import io
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "select-affected-tests.py"
SPEC = importlib.util.spec_from_file_location("select_affected_tests", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SelectAffectedTestsTest(unittest.TestCase):
    def test_source_inventory_excludes_ignored_backups_but_keeps_new_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            (root / ".gitignore").write_text("logs/\nTracked.java\n", encoding="utf-8")
            paths = {
                "main": "module/src/main/java/example/Plugin.java",
                "test": "module/src/test/java/example/PluginTest.java",
                "old_main": "logs/old/src/main/java/example/Plugin.java",
                "old_test": "logs/old/src/test/java/example/PluginTest.java",
                "deleted": "module/src/main/java/example/Removed.java",
                "tracked_ignored": "module/src/main/java/example/Tracked.java",
            }
            for name in paths.values():
                source = root / name
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("package example; class Fixture {}\n", encoding="utf-8")
            subprocess.run(["git", "add", paths["deleted"]], cwd=root, check=True)
            subprocess.run(["git", "add", "-f", paths["tracked_ignored"]], cwd=root, check=True)
            (root / paths["deleted"]).unlink()
            with mock.patch.object(MODULE, "REPO_ROOT", root):
                self.assertEqual(
                    MODULE.all_main_files(), [root / paths["main"], root / paths["tracked_ignored"]]
                )
                self.assertEqual(MODULE.all_test_files(), [root / paths["test"]])

    def test_discovers_only_top_level_test_classes_and_keeps_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "ExampleTest.java"
            source.write_text(
                'package example;\n// class FakeTest {}\n'
                'class FirstIT { String text = "class FakeIT {}"; class NestedTest {} }\n'
                'class SecondTest {}\n',
                encoding="utf-8",
            )
            self.assertEqual(MODULE.test_class_names(source), {"FirstIT", "SecondTest"})
            source.write_text("package example;\ninterface TestMarker {}\n", encoding="utf-8")
            self.assertEqual(MODULE.test_class_names(source), {"ExampleTest"})

    def test_real_mismatched_test_file_resolves_actual_class(self):
        source = (
            SCRIPT.parents[2]
            / "batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/plugin"
            / "ExportPartitionSliceIntegrationTest.java"
        )
        self.assertEqual(MODULE.test_class_names(source), {"ExportPartitionSliceIT"})

    def test_selects_actual_class_for_changed_and_referencing_test(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            test_root = root / "worker/src/test/java/example"
            main_root = root / "worker/src/main/java/example"
            test_root.mkdir(parents=True)
            main_root.mkdir(parents=True)
            production = main_root / "Plugin.java"
            production.write_text("package example; class Plugin {}", encoding="utf-8")
            direct = test_root / "SliceIntegrationTest.java"
            direct.write_text(
                "package example;\nclass SliceIT { class NestedTest {} }\n", encoding="utf-8"
            )
            dependent = test_root / "PluginIntegrationTest.java"
            dependent.write_text(
                "package example;\nclass PluginIT { Plugin value; }\n", encoding="utf-8"
            )
            ordinary = test_root / "OrdinaryTest.java"
            ordinary.write_text("package example;\nclass OrdinaryTest {}\n", encoding="utf-8")
            output = io.StringIO()
            with (
                mock.patch.object(MODULE, "REPO_ROOT", root),
                mock.patch.object(MODULE, "changed_java_files", return_value=[direct, production]),
                mock.patch.object(MODULE, "all_main_files", return_value=[production]),
                mock.patch.object(MODULE, "all_test_files", return_value=[direct, dependent, ordinary]),
                contextlib.redirect_stdout(output),
                contextlib.redirect_stderr(io.StringIO()),
            ):
                self.assertEqual(MODULE.main(), 0)
            self.assertEqual(output.getvalue().splitlines(), ["PluginIT", "SliceIT"])


if __name__ == "__main__":
    unittest.main()
