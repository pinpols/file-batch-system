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
    "business-datasource-role",
}
VALID_STATUSES = {"implemented", "planned", "deferred"}


def validate_business_role_defaults(errors: list[str]) -> None:
    yaml_paths = (
        ROOT / "helm/batch-platform/values.yaml",
        ROOT / "helm/values-prod.yaml",
        ROOT / "helm/batch-platform/examples/values-local-k8s.yaml",
    )
    for path in yaml_paths:
        document = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        username = document.get("postgresql", {}).get("business", {}).get("username")
        if username != "batch_business_writer":
            errors.append(
                f"{path.relative_to(ROOT)}: business database username must be "
                "batch_business_writer"
            )

    env_example = (ROOT / ".env.example").read_text(encoding="utf-8")
    if "BATCH_BUSINESS_DB_USERNAME=batch_business_writer" not in env_example:
        errors.append(".env.example: business database username must use batch_business_writer")

    compose = (ROOT / "deploy/docker/compose/app.yml").read_text(encoding="utf-8")
    unsafe = "BATCH_DATASOURCE_BUSINESS_USERNAME: ${BATCH_DATASOURCE_BUSINESS_USERNAME:-batch_user}"
    if unsafe in compose:
        errors.append("deploy/docker/compose/app.yml: business datasource defaults to batch_user")


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
    validate_business_role_defaults(errors)
    if errors:
        print("❌ application governance contract validation failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(f"✅ application governance contract valid: {len(controls)} controls")
    return 0


if __name__ == "__main__":
    sys.exit(main())
