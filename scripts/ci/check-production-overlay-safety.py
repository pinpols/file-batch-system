#!/usr/bin/env python3
"""Validate production Helm values keep the platform fail-closed by default.

The chart intentionally keeps development defaults permissive.  This check makes
the production overlay an executable contract so a release cannot silently fall
back to development security, quota, or network settings.
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
PROD_VALUES = ROOT / "helm/values-prod.yaml"


def get(values: dict, *path: str):
    current = values
    for key in path:
        if not isinstance(current, dict) or key not in current:
            return None
        current = current[key]
    return current


def is_true(value: object) -> bool:
    """Accept YAML booleans and the string form used by existing env-bound values."""
    return value is True or (isinstance(value, str) and value.strip().lower() == "true")


def main() -> int:
    values = yaml.safe_load(PROD_VALUES.read_text(encoding="utf-8")) or {}
    errors: list[str] = []

    required_true = (
        ("security.enforceStrongSecrets", ("security", "enforceStrongSecrets")),
        ("networkPolicy.enabled", ("networkPolicy", "enabled")),
        ("security.loginEncryption.required", ("security", "loginEncryption", "required")),
        ("workerAtomic.productionIsolationRequired", ("workerAtomic", "productionIsolationRequired")),
        ("workerAtomic.requireIsolation", ("workerAtomic", "requireIsolation")),
        ("workerAtomic.isolationAcknowledged", ("workerAtomic", "isolationAcknowledged")),
        ("workerAtomic.networkPolicy.enabled", ("workerAtomic", "networkPolicy", "enabled")),
    )
    for label, path in required_true:
        if not is_true(get(values, *path)):
            errors.append(f"{label} must be true in helm/values-prod.yaml")

    if get(values, "orchestrator", "quota", "redisFailureMode") != "FAIL_CLOSED":
        errors.append("orchestrator.quota.redisFailureMode must be FAIL_CLOSED in production")

    for label, path in (
        ("workerAtomic.serviceAccountName", ("workerAtomic", "serviceAccountName")),
        ("workerAtomic.envFromSecretName", ("workerAtomic", "envFromSecretName")),
    ):
        value = get(values, *path)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"{label} must name an externally managed production resource")

    atomic_network = get(values, "workerAtomic", "networkPolicy", "egress") or {}
    for kind in ("postgresql", "kafka"):
        peers = get(atomic_network, kind, "to") or []
        for peer in peers:
            if get(peer, "ipBlock", "cidr") in {"0.0.0.0/0", "::/0"}:
                errors.append(f"workerAtomic.networkPolicy.egress.{kind} must not allow {get(peer, 'ipBlock', 'cidr')}")

    if errors:
        print("❌ production overlay safety validation failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("✅ production overlay safety contract passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
