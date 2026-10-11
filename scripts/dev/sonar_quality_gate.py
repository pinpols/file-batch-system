#!/usr/bin/env python3
"""Validate Sonar's evaluated quality-gate conditions against its configuration."""

from __future__ import annotations

import argparse
import json
import os
import sys


IGNORABLE_METRICS = {"new_security_hotspots_reviewed"}


def evaluate_quality_gate(config: dict, result: dict) -> tuple[bool, str]:
    configured_conditions = config.get("conditions")
    project_status = result.get("projectStatus")
    if not isinstance(configured_conditions, list) or not configured_conditions:
        return False, "configured quality gate has no conditions"
    if not isinstance(project_status, dict):
        return False, "analysis result has no projectStatus"

    configured_metrics = {
        condition.get("metric") or condition.get("metricKey")
        for condition in configured_conditions
        if isinstance(condition, dict)
    }
    if None in configured_metrics or len(configured_metrics) != len(configured_conditions):
        return False, "configured quality gate has invalid or duplicate metrics"

    evaluated_conditions = project_status.get("conditions")
    if not isinstance(evaluated_conditions, list) or not evaluated_conditions:
        return False, "analysis result has no evaluated conditions"

    evaluated_metrics = {
        condition.get("metricKey")
        for condition in evaluated_conditions
        if isinstance(condition, dict)
    }
    if None in evaluated_metrics or len(evaluated_metrics) != len(evaluated_conditions):
        return False, "analysis result has invalid or duplicate condition metrics"

    missing = configured_metrics - evaluated_metrics
    unexpected = evaluated_metrics - configured_metrics
    ignored_conditions = project_status.get("ignoredConditions") is True
    ignored_hotspot_only = (
        ignored_conditions and missing == IGNORABLE_METRICS
    )
    if unexpected:
        return False, f"analysis returned unconfigured conditions: {', '.join(sorted(unexpected))}"
    if missing and not ignored_hotspot_only:
        return False, f"configured conditions were not evaluated: {', '.join(sorted(missing))}"
    if ignored_conditions and not ignored_hotspot_only:
        return False, "Sonar ignored conditions outside the supported hotspot-review case"

    invalid_statuses = [
        f"{condition['metricKey']}={condition.get('status', 'UNKNOWN')}"
        for condition in evaluated_conditions
        if condition.get("status") not in {"OK", "ERROR"}
    ]
    if invalid_statuses:
        return False, f"analysis returned unknown condition statuses: {', '.join(invalid_statuses)}"

    status = project_status.get("status", "UNKNOWN")
    if status != "OK":
        failed = [
            f"{condition['metricKey']}={condition.get('actualValue', 'unknown')}"
            for condition in evaluated_conditions
            if condition.get("status") == "ERROR"
        ]
        return False, f"quality gate status={status}; failed={', '.join(failed) or 'none'}"

    summary = (
        f"quality_gate=OK | configured={len(configured_metrics)}"
        f" | evaluated={len(evaluated_metrics)}"
    )
    if ignored_hotspot_only:
        summary += " | ignored=new_security_hotspots_reviewed"
    return True, summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gate", required=True)
    args = parser.parse_args()

    try:
        config = json.loads(os.environ["SONAR_GATE_CONFIG_JSON"])
        result = json.loads(os.environ["SONAR_GATE_RESULT_JSON"])
    except (KeyError, json.JSONDecodeError) as exc:
        print(f"❌ 不通过 | code=SONAR_QUALITY_GATE_RESPONSE_INVALID | detail={exc}", file=sys.stderr)
        return 1

    passed, detail = evaluate_quality_gate(config, result)
    if not passed:
        print(f"❌ 不通过 | code=SONAR_QUALITY_GATE_INVALID | gate={args.gate} | {detail}", file=sys.stderr)
        return 1

    print(f"✅ 通过 | code=SONAR_QUALITY_GATE | gate={args.gate} | {detail} | new_code_period=30d")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
