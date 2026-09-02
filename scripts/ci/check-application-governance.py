#!/usr/bin/env python3
"""Validate the application governance inventory and its evidence links."""

from __future__ import annotations

import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/governance/application-governance-contract.yaml"
REQUIRED_CONTROLS = {
    "timeout-budget",
    "runtime-compatibility",
    "fault-injection",
    "alert-runbook-contract",
    "supply-chain",
}
VALID_STATUSES = {"implemented", "planned", "deferred"}


def main() -> int:
    document = yaml.safe_load(CONTRACT.read_text(encoding="utf-8")) or {}
    errors: list[str] = []
    controls = document.get("controls")
    if not isinstance(controls, list):
        errors.append("controls must be a list")
        controls = []

    seen: set[str] = set()
    for item in controls:
        if not isinstance(item, dict):
            errors.append("each control must be a mapping")
            continue
        control_id = item.get("id")
        if not isinstance(control_id, str) or not control_id.strip():
            errors.append("control id must not be blank")
            continue
        if control_id in seen:
            errors.append(f"duplicate control: {control_id}")
        seen.add(control_id)
        if item.get("priority") not in {"P0", "P1", "P2"}:
            errors.append(f"{control_id}: invalid priority")
        if item.get("status") not in VALID_STATUSES:
            errors.append(f"{control_id}: invalid status")
        evidence = item.get("evidence")
        if not isinstance(evidence, list) or not evidence:
            errors.append(f"{control_id}: evidence must not be empty")
            continue
        for relative_path in evidence:
            if not isinstance(relative_path, str) or not (ROOT / relative_path).exists():
                errors.append(f"{control_id}: missing evidence path {relative_path}")
        if not isinstance(item.get("live_validation"), str) or not item["live_validation"].strip():
            errors.append(f"{control_id}: live_validation must be explicit")

    missing = REQUIRED_CONTROLS - seen
    errors.extend(f"missing required control: {control_id}" for control_id in sorted(missing))
    if errors:
        print("❌ application governance contract validation failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(f"✅ application governance contract valid: {len(controls)} controls")
    return 0


if __name__ == "__main__":
    sys.exit(main())
