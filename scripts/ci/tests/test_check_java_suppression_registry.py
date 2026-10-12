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
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "check-java-suppression-registry.py"
SPEC = importlib.util.spec_from_file_location("check_java_suppression_registry", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class JavaSuppressionRegistryTest(unittest.TestCase):

    def test_main_accepts_incremental_source_paths(self) -> None:
        """pre-commit 的增量文件参数不能被误判为未知选项。"""
        candidates = ["batch-common/src/main/java/Example.java"]
        with patch.object(MODULE, "scan", return_value=[]) as scan:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(MODULE.main(candidates), 0)
            scan.assert_called_once_with(candidates)

    def test_main_rejects_unknown_options_before_scanning(self) -> None:
        with patch.object(MODULE, "scan") as scan:
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(MODULE.main(["--unknown"]), 2)
            scan.assert_not_called()

    def test_contract_path_exception_is_exact_and_cannot_grow(self) -> None:
        path = next(iter(MODULE.SCOPED_RULES["java:S1075"]))
        allowed = (path, 23, "java:S1075")
        self.assertEqual(MODULE.unregistered([allowed]), [])
        self.assertEqual(len(MODULE.unregistered([allowed, allowed])), 2)
        self.assertEqual(len(MODULE.unregistered([("Other.java", 23, "java:S1075")])), 1)

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

    def test_yaml_candidate_filter_covers_all_production_resource_configs(self) -> None:
        application = "batch-trigger/src/main/resources/application.yml"
        worker_defaults = "batch-worker/dispatch/src/main/resources/dispatch-worker.yml"
        unrelated = "batch-trigger/src/main/resources/logback-spring.xml"
        test_config = "batch-trigger/src/test/resources/application-test.yml"

        kept = MODULE.production_yaml_configs([application, unrelated, test_config])

        self.assertEqual(
            [path.relative_to(MODULE.ROOT).as_posix() for path in kept], [application])

        kept = MODULE.production_yaml_configs([application, worker_defaults, unrelated, test_config])
        self.assertEqual(
            [path.relative_to(MODULE.ROOT).as_posix() for path in kept],
            [application, worker_defaults],
        )

    def test_yaml_ide_suppressions_are_scanned(self) -> None:
        original_java = MODULE.production_sources
        original_yaml = MODULE.production_yaml_configs
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT) as tmp:
            probe = Path(tmp) / "application-probe.yml"
            probe.write_text("# noinspection SpringBootApplicationYaml\n", encoding="utf-8")
            MODULE.production_sources = lambda _candidates=None: []
            MODULE.production_yaml_configs = lambda _candidates=None: [probe]
            try:
                findings = MODULE.scan()
            finally:
                MODULE.production_sources = original_java
                MODULE.production_yaml_configs = original_yaml

        self.assertEqual(
            findings,
            [(probe.relative_to(MODULE.ROOT).as_posix(), 1, "SpringBootApplicationYaml")],
        )

    def test_unregistered_yaml_inspection_is_rejected(self) -> None:
        self.assertEqual(
            MODULE.unregistered([("batch-trigger/src/main/resources/application.yml", 1, "UnknownInspection")]),
            [("batch-trigger/src/main/resources/application.yml", 1, "UnknownInspection")],
        )

    def test_spotbugs_suppression_is_inventoried_and_requires_registration(self) -> None:
        original_java = MODULE.production_sources
        original_yaml = MODULE.production_yaml_configs
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT) as tmp:
            probe = Path(tmp) / "SpotbugsProbe.java"
            probe.write_text(
                '@SuppressFBWarnings(value = {"NP_NULL_ON_SOME_PATH", "EI_EXPOSE_REP"}, '
                'justification = "Not justified")\n'
                "class SpotbugsProbe {}\n",
                encoding="utf-8",
            )
            MODULE.production_sources = lambda _candidates=None: [probe]
            MODULE.production_yaml_configs = lambda _candidates=None: []
            try:
                self.assertEqual(
                    MODULE.scan(),
                    [
                        (probe.relative_to(MODULE.ROOT).as_posix(), 1, "spotbugs:NP_NULL_ON_SOME_PATH"),
                        (probe.relative_to(MODULE.ROOT).as_posix(), 1, "spotbugs:EI_EXPOSE_REP"),
                    ],
                )
                self.assertTrue(MODULE.unregistered(MODULE.scan()))
            finally:
                MODULE.production_sources = original_java
                MODULE.production_yaml_configs = original_yaml

    def test_nosonar_comment_is_detected_but_string_and_text_block_are_not(self) -> None:
        source = '''
class Probe {
  String example = "// NOSONAR";
  String template = """
      // NOSONAR
      """;
  // NOSONAR: hide analyzer findings
}
'''
        self.assertEqual(MODULE.nosonar_comment_lines(source), [7])

    def test_s2077_suppression_must_target_a_method_not_a_type(self) -> None:
        class_source = '''
@SuppressWarnings("java:S2077")
@Component
public class DynamicSqlAdapter {}
'''
        method_source = '''
@SuppressWarnings("java:S2077")
public void executeValidatedSql() {}
'''

        self.assertTrue(MODULE.suppression_targets_type(class_source, 2))
        self.assertFalse(MODULE.suppression_targets_type(method_source, 2))

    def test_new_known_yaml_inspection_still_requires_baseline_entry(self) -> None:
        original_java = MODULE.production_sources
        original_yaml = MODULE.production_yaml_configs
        original_baseline = MODULE.BASELINE
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT) as tmp:
            probe = Path(tmp) / "application-probe.yml"
            probe.write_text("# noinspection SpringBootApplicationYaml\n", encoding="utf-8")
            MODULE.production_sources = lambda _candidates=None: []
            MODULE.production_yaml_configs = lambda _candidates=None: [probe]
            MODULE.BASELINE = Path(tmp) / "baseline.tsv"
            MODULE.BASELINE.write_text("", encoding="utf-8")
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(MODULE.main([]), 1)
            finally:
                MODULE.production_sources = original_java
                MODULE.production_yaml_configs = original_yaml
                MODULE.BASELINE = original_baseline

    def test_main_flags_unregistered_rule(self) -> None:
        """未知规则失败；既有明确登记的 suppression 可通过。"""
        original = MODULE.production_sources
        original_yaml = MODULE.production_yaml_configs
        original_baseline = MODULE.BASELINE
        # 临时目录建在仓库内，scan() 才能算出相对路径。
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT) as tmp:
            probe = Path(tmp) / "ZzSuppressionProbe.java"
            MODULE.production_sources = lambda _candidates=None: [probe]
            MODULE.production_yaml_configs = lambda _candidates=None: []
            MODULE.BASELINE = Path(tmp) / "baseline.tsv"
            try:
                probe.write_text(
                    "package probe;\n\n"
                    '@SuppressWarnings("probe:Unregistered")\n'
                    "public class ZzSuppressionProbe {}\n",
                    encoding="utf-8",
                )
                MODULE.BASELINE.write_text("", encoding="utf-8")
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(MODULE.main([]), 1)

                probe.write_text(
                    "package probe;\n\n"
                    '@SuppressWarnings("unchecked")\n'
                    "public class ZzSuppressionProbe {}\n",
                    encoding="utf-8",
                )
                relative = probe.relative_to(MODULE.ROOT).as_posix()
                MODULE.BASELINE.write_text(f"{relative}\tunchecked\t1\n", encoding="utf-8")
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(MODULE.main([]), 0)
            finally:
                MODULE.production_sources = original
                MODULE.production_yaml_configs = original_yaml
                MODULE.BASELINE = original_baseline

    def test_known_rule_addition_requires_baseline_update(self) -> None:
        """全局已知规则不能让新文件中的 suppression 自动豁免。"""
        original = MODULE.production_sources
        original_yaml = MODULE.production_yaml_configs
        original_baseline = MODULE.BASELINE
        with tempfile.TemporaryDirectory(dir=MODULE.ROOT) as tmp:
            probe = Path(tmp) / "ZzNewSuppression.java"
            probe.write_text(
                '@SuppressWarnings("unchecked")\npublic class ZzNewSuppression {}\n',
                encoding="utf-8",
            )
            MODULE.production_sources = lambda _candidates=None: [probe]
            MODULE.production_yaml_configs = lambda _candidates=None: []
            MODULE.BASELINE = Path(tmp) / "baseline.tsv"
            MODULE.BASELINE.write_text("", encoding="utf-8")
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(MODULE.main([]), 1)
            finally:
                MODULE.production_sources = original
                MODULE.production_yaml_configs = original_yaml
                MODULE.BASELINE = original_baseline


if __name__ == "__main__":
    unittest.main()
