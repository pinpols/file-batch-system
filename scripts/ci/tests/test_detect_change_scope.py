from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "detect-change-scope.py"
SPEC = importlib.util.spec_from_file_location("detect_change_scope", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DetectChangeScopeTest(unittest.TestCase):
    def test_docs_only_has_no_code_scope(self) -> None:
        result = MODULE.classify_paths(["docs/runbook/ci.md", "CHANGELOG.md"])

        self.assertTrue(result["docs-only"])
        self.assertFalse(result["unit-required"])
        self.assertEqual(["docs"], result["scopes"])
        self.assertFalse(result["java"])

    def test_java_sql_sdk_and_ci_are_reported_independently(self) -> None:
        result = MODULE.classify_paths(
            [
                "batch-orchestrator/src/main/java/example/Job.java",
                "db/migration/V1__job.sql",
                "sdk/python/src/batch_sdk/client.py",
                ".github/workflows/pr-gate.yml",
            ]
        )

        self.assertFalse(result["docs-only"])
        self.assertTrue(result["java"])
        self.assertTrue(result["sql"])
        self.assertTrue(result["database"])
        self.assertTrue(result["sdk"])
        self.assertTrue(result["ci"])
        self.assertTrue(result["unit-required"])

    def test_ci_only_change_skips_maven_unit_but_keeps_ci_scope(self) -> None:
        result = MODULE.classify_paths([".github/workflows/pr-gate.yml"])

        self.assertFalse(result["docs-only"])
        self.assertFalse(result["unit-required"])
        self.assertTrue(result["ci"])

    def test_module_test_source_is_marked_as_test_and_unit_scope(self) -> None:
        result = MODULE.classify_paths(["batch-trigger/src/test/java/example/TriggerTest.java"])

        self.assertTrue(result["tests"])
        self.assertTrue(result["unit-required"])

    def test_openapi_is_api_and_not_docs_only(self) -> None:
        result = MODULE.classify_paths(["docs/api/console-api.openapi.yaml"])

        self.assertFalse(result["docs-only"])
        self.assertFalse(result["unit-required"])
        self.assertTrue(result["docs"])
        self.assertTrue(result["api"])

    def test_unknown_files_fail_closed(self) -> None:
        result = MODULE.classify_paths(["some-new-root-format.bin"])

        self.assertFalse(result["docs-only"])
        self.assertTrue(result["unit-required"])
        self.assertTrue(result["unknown"])

    def test_non_pull_request_falls_back_to_full_scope(self) -> None:
        result = MODULE.full_result()

        self.assertFalse(result["docs-only"])
        self.assertTrue(result["java"])
        self.assertTrue(result["sql"])
        self.assertTrue(result["sdk"])
        self.assertFalse(result["unknown"])


if __name__ == "__main__":
    unittest.main()
