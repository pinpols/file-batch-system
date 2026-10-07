from pathlib import Path
import unittest

import yaml


WORKFLOW = Path(__file__).resolve().parents[3] / ".github/workflows/daily-sim-strict-validation.yml"


class DailyValidationWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        self.job = self.workflow["jobs"]["sim-and-strict"]
        self.steps = self.job["steps"]

    def test_strict_runs_after_sim_even_when_sim_fails(self) -> None:
        steps = {step.get("id"): step for step in self.steps if "id" in step}
        strict = steps["strict_verify"]
        self.assertEqual(
            "${{ always() && steps.start_apps.outcome == 'success' }}", strict["if"]
        )
        self.assertLess(self.steps.index(steps["sim_harness"]), self.steps.index(strict))
        self.assertIn("--steps=5", strict["run"])
        self.assertIn("set -o pipefail", strict["run"])
        self.assertNotIn("continue-on-error", strict)
        self.assertNotIn("continue-on-error", steps["sim_harness"])
        self.assertIn("set -o pipefail", steps["sim_harness"]["run"])
        quiesce = next(step for step in self.steps if step["name"] == "Quiesce simulated schedules")
        self.assertEqual(strict["if"], quiesce["if"])
        self.assertLess(self.steps.index(steps["sim_harness"]), self.steps.index(quiesce))
        self.assertLess(self.steps.index(quiesce), self.steps.index(strict))
        self.assertNotIn("continue-on-error", quiesce)

    def test_sim_timeout_reserves_time_for_strict(self) -> None:
        steps = {step.get("id"): step for step in self.steps if "id" in step}
        quiesce = next(step for step in self.steps if step["name"] == "Quiesce simulated schedules")
        self.assertLess(
            steps["sim_harness"]["timeout-minutes"]
            + quiesce["timeout-minutes"]
            + steps["strict_verify"]["timeout-minutes"],
            self.job["timeout-minutes"],
        )

    def test_failed_runtime_validation_blocks_image_build(self) -> None:
        images = self.workflow["jobs"]["nightly-image-build"]
        self.assertIn("sim-and-strict", images["needs"])
        self.assertIn("needs.sim-and-strict.result == 'success'", images["if"])


if __name__ == "__main__":
    unittest.main()
