import unittest

from scripts.dev.sonar_quality_gate import evaluate_quality_gate


HOTSPOT_METRIC = "new_security_hotspots_reviewed"


def condition(metric: str, status: str = "OK") -> dict[str, str]:
    return {"metricKey": metric, "status": status, "actualValue": "0"}


class SonarQualityGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = {
            "conditions": [
                {"metric": "new_violations"},
                {"metric": "new_coverage"},
                {"metric": "new_duplicated_lines_density"},
                {"metric": HOTSPOT_METRIC},
            ]
        }
        self.result = {
            "projectStatus": {
                "status": "OK",
                "ignoredConditions": False,
                "conditions": [
                    condition("new_violations"),
                    condition("new_coverage"),
                    condition("new_duplicated_lines_density"),
                    condition(HOTSPOT_METRIC),
                ],
            }
        }

    def test_accepts_all_configured_conditions_when_quality_gate_is_ok(self) -> None:
        passed, _ = evaluate_quality_gate(self.config, self.result)

        self.assertTrue(passed)

    def test_accepts_only_sonars_ignored_hotspot_condition(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = status["conditions"][:-1]
        status["ignoredConditions"] = True

        passed, detail = evaluate_quality_gate(self.config, self.result)

        self.assertTrue(passed)
        self.assertIn(HOTSPOT_METRIC, detail)

    def test_rejects_other_missing_condition_even_when_sonar_ignored_conditions(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = [
            condition("new_violations"),
            condition("new_duplicated_lines_density"),
            condition(HOTSPOT_METRIC),
        ]
        status["ignoredConditions"] = True

        passed, detail = evaluate_quality_gate(self.config, self.result)

        self.assertFalse(passed)
        self.assertIn("new_coverage", detail)

    def test_rejects_missing_hotspot_condition_when_not_marked_ignored(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = status["conditions"][:-1]

        passed, _ = evaluate_quality_gate(self.config, self.result)

        self.assertFalse(passed)

    def test_rejects_empty_and_unconfigured_results(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = []
        self.assertFalse(evaluate_quality_gate(self.config, self.result)[0])

        status["conditions"] = [condition("unexpected_metric")]
        self.assertFalse(evaluate_quality_gate(self.config, self.result)[0])

    def test_rejects_failed_condition_and_non_ok_gate(self) -> None:
        status = self.result["projectStatus"]
        status["status"] = "ERROR"
        status["conditions"][0] = condition("new_violations", "ERROR")

        passed, detail = evaluate_quality_gate(self.config, self.result)

        self.assertFalse(passed)
        self.assertIn("status=ERROR", detail)


if __name__ == "__main__":
    unittest.main()
