#!/usr/bin/env python3
"""Validate environment-variable governance docs and critical entry points."""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "docs/runbook/environment-variable-governance.md"

REQUIRED_PATHS = (
    ".env.example",
    "deploy/docker/compose/app.yml",
    "helm/batch-platform/values.yaml",
    "helm/values-prod.yaml",
    "docs/runbook/feature-switch-registry.yml",
    "docs/runbook/feature-switches.md",
    "docs/runbook/config-ops-tiering.md",
    "scripts/ci/check-feature-switch-registry.py",
    "scripts/ci/check-config-defaults-sync.py",
    "scripts/ci/check-helm-env-sync.py",
    "scripts/ci/check-config-governance.py",
)

REQUIRED_DOC_TERMS = (
    "开发 / IDE",
    "本地 Compose",
    "场景测试 / sim",
    "4 天仿真",
    "压测",
    "CI",
    "Staging",
    "生产",
    "check-feature-switch-registry.py",
    "check-config-defaults-sync.py",
    "check-helm-env-sync.py",
    "check-config-governance.py",
)

REQUIRED_ENV_EXAMPLE_VARS = (
    "BATCH_TIMEZONE_DEFAULT_ZONE",
    "BATCH_LOCALE",
    "SPRING_PROFILES_ACTIVE",
    "BATCH_SECURITY_BYPASS_MODE",
    "BATCH_INTERNAL_SECRET",
    "BATCH_PLATFORM_DB_USERNAME",
    "BATCH_BUSINESS_DB_USERNAME",
    "BATCH_S3_ACCESS_KEY",
    "BATCH_S3_SECRET_KEY",
    "BATCH_SHEDLOCK_PROVIDER",
    "BATCH_WORKER_KAFKA_SUBSCRIBE_MODE",
    "BATCH_WORKER_KAFKA_TENANT_ALLOWLIST",
    "BATCH_WORKER_IMPORT_SCANNER_EVENT_ARRIVAL_ENABLED",
)


def main() -> int:
    errors: list[str] = []

    if not DOC.exists():
        errors.append(f"missing doc: {DOC.relative_to(ROOT)}")
    else:
        doc_text = DOC.read_text(encoding="utf-8")
        for term in REQUIRED_DOC_TERMS:
            if term not in doc_text:
                errors.append(f"{DOC.relative_to(ROOT)} missing required term: {term}")

    for relative in REQUIRED_PATHS:
        if not (ROOT / relative).exists():
            errors.append(f"missing required config-governance path: {relative}")

    env_example = ROOT / ".env.example"
    if env_example.exists():
        env_text = env_example.read_text(encoding="utf-8")
        for variable in REQUIRED_ENV_EXAMPLE_VARS:
            if variable not in env_text:
                errors.append(f".env.example missing critical variable template: {variable}")

    if errors:
        print("❌ environment variable governance validation failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("✅ environment variable governance doc and critical entry points are aligned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
