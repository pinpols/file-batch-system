#!/usr/bin/env python3
"""对发布影响和架构规范变更执行 changelog 同步检查。"""

from __future__ import annotations

import argparse
import subprocess


RELEASE_PREFIXES = (
    "db/migration/",
    "deploy/docker/",
    "deploy/ha/",
    "helm/batch-platform/templates/",
    "helm/batch-platform/files/",
    "docs/api/sdk-contract-fixtures/",
)
RELEASE_FILES = {
    "pom.xml",
    ".env.example",
    "docker-compose.yml",
    "docker-compose.kafka-ha.yml",
    "helm/batch-platform/Chart.yaml",
    "helm/batch-platform/values.yaml",
    "helm/batch-platform/values-canary.yaml",
    "helm/values-prod.yaml",
    "docs/api/console-api.openapi.yaml",
    "docs/api/orchestrator-internal.openapi.yaml",
    "docs/api/sdk-shared-constants.yaml",
}


def changed_files(base: str | None) -> set[str]:
    if base:
        command = ["git", "diff", "--name-only", f"{base}...HEAD"]
    else:
        command = ["git", "diff", "--name-only", "HEAD"]
    output = subprocess.check_output(command, text=True)
    changed = {line for line in output.splitlines() if line}
    if not base:
        untracked = subprocess.check_output(
            ["git", "ls-files", "--others", "--exclude-standard"], text=True
        )
        changed.update(line for line in untracked.splitlines() if line)
    return changed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    args = parser.parse_args()
    changed = changed_files(args.base)
    errors: list[str] = []

    architecture_changed = "CLAUDE.md" in changed or any(
        path.startswith("docs/architecture/adr/") for path in changed
    )
    if architecture_changed and "docs/changelog.md" not in changed:
        errors.append("CLAUDE.md or ADR changed without docs/changelog.md")

    release_changed = any(
        path in RELEASE_FILES or path.startswith(RELEASE_PREFIXES) for path in changed
    )
    if release_changed and "CHANGELOG.md" not in changed:
        errors.append("release-sensitive configuration/contract changed without CHANGELOG.md")

    if errors:
        print("Changelog sync guard failed:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("Changelog sync guard passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
