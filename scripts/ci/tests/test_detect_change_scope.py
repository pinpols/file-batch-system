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
    def test_contract_policy_is_not_docs_only(self) -> None:
        result = MODULE.classify_paths(["docs/governance/java-contract-governance.json"])
        self.assertTrue(result["ci"])
        self.assertFalse(result["docs-only"])

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

    def test_known_root_tool_configs_skip_maven_unit(self) -> None:
        result = MODULE.classify_paths([".nvmrc", ".node-version", "qodana.yaml"])

        self.assertTrue(result["config"])
        self.assertFalse(result["unknown"])
        self.assertFalse(result["unit-required"])
        self.assertFalse(result["docs-only"])

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

    def test_workflow_change_is_ci_and_requires_static_checks(self) -> None:
        result = MODULE.classify_paths([".github/workflows/full-ci-gate.yml"])

        self.assertTrue(result["ci"])
        self.assertFalse(result["unit-required"])
        self.assertFalse(result["docs-only"])

    def test_deployment_change_hits_config_docker_and_helm(self) -> None:
        result = MODULE.classify_paths(
            ["docker/worker/Dockerfile", "helm/batch-platform/values-prod.yaml"]
        )

        self.assertTrue(result["config"])
        self.assertTrue(result["docker"])
        self.assertTrue(result["helm"])
        self.assertFalse(result["docs-only"])

    def test_unclassified_file_cannot_be_docs_only(self) -> None:
        result = MODULE.classify_paths(["new-root-tool.txt", "docs/runbook/ci.md"])

        self.assertTrue(result["unknown"])
        self.assertTrue(result["unit-required"])
        self.assertFalse(result["docs-only"])


if __name__ == "__main__":
    unittest.main()
