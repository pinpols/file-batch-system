import re
import unittest
from pathlib import Path

from scripts.dev.sonar_quality_gate import evaluate_quality_gate
from scripts.dev.sonar_quality_gate import parse_measures


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
        passed, _ = evaluate_quality_gate(self.config, self.result, {})

        self.assertTrue(passed)

    def test_accepts_all_evaluated_conditions_with_ignored_conditions_flag(self) -> None:
        self.result["projectStatus"]["ignoredConditions"] = True

        passed, _ = evaluate_quality_gate(self.config, self.result, {})

        self.assertTrue(passed)

    def test_accepts_only_sonars_ignored_hotspot_condition(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = status["conditions"][:-1]
        status["ignoredConditions"] = True

        passed, detail = evaluate_quality_gate(
            self.config, self.result, {"new_security_hotspots": 0}
        )

        self.assertTrue(passed)
        self.assertIn(HOTSPOT_METRIC, detail)

    def test_accepts_coverage_and_duplication_ignored_below_twenty_new_lines(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = [condition("new_violations")]
        status["ignoredConditions"] = True

        passed, detail = evaluate_quality_gate(
            self.config,
            self.result,
            {"new_lines": 12, "new_security_hotspots": 0},
        )

        self.assertTrue(passed)
        self.assertIn("new_coverage", detail)
        self.assertIn("new_duplicated_lines_density", detail)

    def test_rejects_coverage_and_duplication_missing_at_twenty_or_more_lines(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = [condition("new_violations"), condition(HOTSPOT_METRIC)]
        status["ignoredConditions"] = True

        passed, detail = evaluate_quality_gate(
            self.config,
            self.result,
            {"new_lines": 20, "new_security_hotspots": 0},
        )

        self.assertFalse(passed)
        self.assertIn("new_coverage", detail)

    def test_accepts_sonar_omission_with_small_change_evidence_when_flag_is_false(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = [condition("new_violations"), condition(HOTSPOT_METRIC)]
        status["ignoredConditions"] = False

        passed, detail = evaluate_quality_gate(
            self.config,
            self.result,
            {
                "new_lines": 12,
                "new_security_hotspots": 0,
            },
        )

        self.assertTrue(passed)
        self.assertIn("new_coverage", detail)
        self.assertIn("new_duplicated_lines_density", detail)

    def test_coverage_is_not_ignored_at_twenty_new_lines(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = [
            condition("new_violations"),
            condition("new_duplicated_lines_density"),
            condition(HOTSPOT_METRIC),
        ]

        passed, detail = evaluate_quality_gate(
            self.config,
            self.result,
            {
                "new_lines": 20,
                "new_security_hotspots": 0,
            },
        )

        self.assertFalse(passed)
        self.assertIn("new_coverage", detail)
        self.assertIn("new_lines < 20", detail)

    def test_rejects_missing_hotspot_condition_without_zero_hotspot_evidence(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = status["conditions"][:-1]

        passed, _ = evaluate_quality_gate(self.config, self.result, {})

        self.assertFalse(passed)

    def test_parses_period_measures_and_ignores_invalid_values(self) -> None:
        measures = parse_measures(
            {
                "component": {
                    "measures": [
                        {"metric": "new_lines", "period": {"value": "12"}},
                        {"metric": "new_security_hotspots", "value": "0"},
                        {"metric": "new_coverage", "value": "NaN"},
                    ]
                }
            }
        )

        self.assertEqual(
            measures,
            {"new_lines": 12, "new_security_hotspots": 0},
        )

    def test_parses_legacy_periods_measure_shape(self) -> None:
        measures = parse_measures(
            {
                "component": {
                    "measures": [
                        {"metric": "new_lines", "periods": [{"value": "12"}]}
                    ]
                }
            }
        )

        self.assertEqual(measures, {"new_lines": 12})

    def test_parses_new_code_evidence_from_modern_measure_period(self) -> None:
        measures = parse_measures(
            {
                "component": {
                    "measures": [
                        {"metric": "new_lines", "period": {"value": "12"}},
                        {
                            "metric": "new_security_hotspots",
                            "period": {"value": "0"},
                        },
                    ],
                    "period": {"mode": "NUMBER_OF_DAYS", "parameter": "30"},
                }
            }
        )

        self.assertEqual(measures, {"new_lines": 12, "new_security_hotspots": 0})

    def test_rejects_other_missing_condition_even_when_sonar_ignored_conditions(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = [
            condition("new_violations"),
            condition("new_duplicated_lines_density"),
            condition(HOTSPOT_METRIC),
        ]
        status["ignoredConditions"] = True

        passed, detail = evaluate_quality_gate(self.config, self.result, {})

        self.assertFalse(passed)
        self.assertIn("new_coverage", detail)

    def test_accepts_missing_hotspot_condition_when_no_new_hotspots_even_if_flag_is_false(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = status["conditions"][:-1]

        passed, detail = evaluate_quality_gate(
            self.config, self.result, {"new_security_hotspots": 0}
        )

        self.assertTrue(passed)
        self.assertIn(HOTSPOT_METRIC, detail)

    def test_reports_actual_gate_failure_when_only_hotspot_condition_is_omitted(self) -> None:
        status = self.result["projectStatus"]
        status["status"] = "ERROR"
        status["conditions"][0] = {
            **condition("new_violations", "ERROR"),
            "actualValue": "108",
        }
        status["conditions"] = status["conditions"][:-1]

        passed, detail = evaluate_quality_gate(
            self.config, self.result, {"new_security_hotspots": 0}
        )

        self.assertFalse(passed)
        self.assertIn("quality gate status=ERROR", detail)
        self.assertIn("new_violations=108", detail)

    def test_rejects_empty_and_unconfigured_results(self) -> None:
        status = self.result["projectStatus"]
        status["conditions"] = []
        self.assertFalse(evaluate_quality_gate(self.config, self.result, {})[0])

        status["conditions"] = [condition("unexpected_metric")]
        self.assertFalse(evaluate_quality_gate(self.config, self.result, {})[0])

    def test_rejects_failed_condition_and_non_ok_gate(self) -> None:
        status = self.result["projectStatus"]
        status["status"] = "ERROR"
        status["conditions"][0] = condition("new_violations", "ERROR")

        passed, detail = evaluate_quality_gate(self.config, self.result, {})

        self.assertFalse(passed)
        self.assertIn("status=ERROR", detail)

    def test_ephemeral_ci_bootstraps_analysis_before_enforcing_gate(self) -> None:
        workflow = (
            Path(__file__).resolve().parents[3] / ".github/workflows/sonar-gate.yml"
        ).read_text(encoding="utf-8")
        commands = re.findall(
            r"bash scripts/dev/sonar-scan\.sh (--full --skip-build(?: --quality-gate)?)",
            workflow,
        )

        self.assertEqual(
            commands,
            ["--full --skip-build", "--full --skip-build --quality-gate"],
        )

    def test_quality_gate_requests_modern_period_metadata(self) -> None:
        script = (
            Path(__file__).resolve().parents[3] / "scripts/dev/sonar-scan.sh"
        ).read_text(encoding="utf-8")

        self.assertIn('--data-urlencode "additionalFields=period"', script)
        self.assertIn(
            '--data-urlencode "metricKeys=new_lines,new_security_hotspots"',
            script,
        )


if __name__ == "__main__":
    unittest.main()
