from pathlib import Path
import os
import re
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[3]


class PrePushModuleSelectionTest(unittest.TestCase):
    def select_modules(self, paths: list[str]) -> list[str]:
        script = (ROOT / "scripts/local/pre-push-sdk-checks.sh").read_text(encoding="utf-8")
        selector = re.search(r"^  MODULES=\$\([\s\S]*?\)\n", script, re.MULTILINE)
        self.assertIsNotNone(selector, "pre-push module selection must remain testable")
        result = subprocess.run(
            ["bash", "-c", selector.group() + '\nprintf "%s\\n" "$MODULES"'],
            cwd=ROOT,
            env={**os.environ, "CHANGED_JAVA": "\n".join(paths)},
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout.splitlines()

    def test_e2e_module_name_is_not_truncated(self) -> None:
        self.assertEqual(
            ["batch-e2e-tests"],
            self.select_modules(["batch-e2e-tests/src/test/java/ImportPipelineE2eIT.java"]),
        )

    def test_worker_aggregator_is_selected_once(self) -> None:
        self.assertEqual(
            ["batch-worker"],
            self.select_modules([
                "batch-worker/core/src/main/java/Wrapper.java",
                "batch-worker/import/src/main/java/ParseStep.java",
            ]),
        )

    def test_more_than_five_changed_modules_are_not_dropped(self) -> None:
        modules = [
            "batch-common", "batch-console-api", "batch-e2e-tests", "batch-orchestrator",
            "batch-test-support", "batch-trigger", "batch-worker",
        ]
        self.assertEqual(
            modules, self.select_modules([f"{module}/src/main/java/Example.java" for module in modules])
        )


if __name__ == "__main__":
    unittest.main()
