#!/usr/bin/env python3
"""Detect changed repository scopes for local and GitHub Actions routing.

The classifier is intentionally conservative. A pull request diff is classified
from ``base...head``. Events without a trustworthy PR diff fall back to the
full scope so a routing failure cannot silently skip a required gate.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from pathlib import PurePosixPath


ROOT = Path(__file__).resolve().parents[2]
SCOPE_NAMES = (
    "java",
    "sql",
    "database",
    "scripts",
    "docs",
    "config",
    "api",
    "sdk",
    "ci",
    "tests",
    "docker",
    "helm",
    "maven",
    "unknown",
)
CODE_SCOPES = set(SCOPE_NAMES) - {"docs", "unknown"}


def under(path: str, prefix: str) -> bool:
    return path == prefix or path.startswith(f"{prefix}/")


def is_markdown(path: str) -> bool:
    return path.endswith((".md", ".mdx", ".rst"))


def is_config(path: str) -> bool:
    if path.startswith(".github/") or under(path, "docs"):
        return False
    name = PurePosixPath(path).name
    if name.startswith(".env") or name in {"Makefile", ".editorconfig"}:
        return True
    if name.startswith(("docker-compose", "docker-bake")):
        return True
    if under(path, "helm") or under(path, "deploy"):
        return True
    if "/src/main/resources/" in f"/{path}" and name.endswith(
        (".yml", ".yaml", ".properties", ".toml")
    ):
        return True
    return False


def classify_path(path: str) -> set[str]:
    """Return every scope touched by one repository-relative path."""
    scopes: set[str] = set()
    normalized = path.removeprefix("./")
    name = PurePosixPath(normalized).name

    if is_markdown(normalized) or under(normalized, "docs") or under(normalized, ".agents"):
        scopes.add("docs")

    if normalized.startswith((".github/workflows/", ".github/actions/")) or under(
        normalized, "scripts/ci"
    ):
        scopes.add("ci")

    if normalized.endswith(".java"):
        scopes.add("java")
    if normalized == "pom.xml" or normalized.endswith("/pom.xml") or under(
        normalized, ".mvn"
    ) or normalized.startswith("mvnw"):
        scopes.update({"java", "maven"})

    if normalized.endswith(".sql") or under(normalized, "db") or under(
        normalized, "scripts/db"
    ) or "/src/main/resources/mapper/" in f"/{normalized}":
        scopes.update({"sql", "database"})

    if under(normalized, "scripts") or under(normalized, "load-tests") or under(
        normalized, ".githooks"
    ) or name == "Makefile":
        scopes.add("scripts")

    if under(normalized, "sdk") or under(normalized, "docs/api/sdk-contract-fixtures"):
        scopes.add("sdk")
    if normalized in {
        "docs/api/sdk-shared-constants.yaml",
        "docs/api/orchestrator-internal.openapi.yaml",
        "docs/sdk/byo-conformance-contract.md",
    }:
        scopes.add("sdk")
    if under(normalized, "batch-common/src/main/java/io/github/pinpols/batch/common/enums"):
        scopes.add("sdk")
    if under(normalized, "batch-common/src/main/java/io/github/pinpols/batch/common/security"):
        scopes.add("sdk")

    if "/src/test/" in f"/{normalized}/" or under(normalized, "tests") or under(
        normalized, "batch-e2e-tests"
    ):
        scopes.add("tests")

    if normalized.startswith(("Dockerfile", "docker/")) or name.startswith(
        ("Dockerfile", "docker-compose", "docker-bake")
    ):
        scopes.add("docker")
    if under(normalized, "helm"):
        scopes.add("helm")

    if normalized.startswith("docs/api/") and normalized.endswith(
        (".yaml", ".yml", ".json")
    ):
        scopes.add("api")
    if normalized.endswith("openapi.yaml") or normalized.endswith("openapi.yml"):
        scopes.add("api")
    if under(normalized, "scripts/codegen") or name.endswith("Controller.java"):
        scopes.add("api")

    if is_config(normalized):
        scopes.add("config")

    if not scopes:
        scopes.add("unknown")
    return scopes


def requires_unit(path: str) -> bool:
    """Return whether the path can affect the Maven unit-test reactor."""
    normalized = path.removeprefix("./")
    return (
        (normalized.startswith("batch-") and "/src/" in normalized)
        or under(normalized, "db/migration")
        or normalized == "pom.xml"
        or normalized.endswith("/pom.xml")
        or under(normalized, ".mvn")
        or normalized.startswith("mvnw")
    )


def classify_paths(paths: list[str]) -> dict[str, object]:
    scopes: set[str] = set()
    for path in paths:
        scopes.update(classify_path(path))
    code_changed = bool(scopes & CODE_SCOPES)
    return {
        "changed_files": len(paths),
        "scopes": sorted(scopes),
        "docs-only": bool(paths) and not code_changed and "unknown" not in scopes,
        "unit-required": any(requires_unit(path) for path in paths) or "unknown" in scopes,
        **{scope: scope in scopes for scope in SCOPE_NAMES},
    }


def full_result() -> dict[str, object]:
    return {
        "changed_files": 0,
        "scopes": ["full"],
        "docs-only": False,
        "unit-required": True,
        **{scope: scope != "unknown" for scope in SCOPE_NAMES},
        "unknown": False,
    }


def changed_paths(base: str, head: str) -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "-z", f"{base}...{head}", "--"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return [item.decode("utf-8") for item in result.stdout.split(b"\0") if item]


def write_outputs(result: dict[str, object], output_path: str | None) -> None:
    if not output_path:
        return
    lines = [f"{key}={str(value).lower() if isinstance(value, bool) else value}" for key, value in result.items() if key != "scopes"]
    lines.append(f"scopes={','.join(result['scopes'])}")
    with open(output_path, "a", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")


def write_summary(result: dict[str, object], summary_path: str | None, mode: str) -> None:
    if not summary_path:
        return
    scopes = ", ".join(result["scopes"])
    lines = [
        "### Change scope",
        f"- mode: `{mode}`",
        f"- changed files: `{result['changed_files']}`",
        f"- scopes: `{scopes}`",
        f"- docs-only: `{str(result['docs-only']).lower()}`",
    ]
    with open(summary_path, "a", encoding="utf-8") as summary:
        summary.write("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.environ.get("CHANGE_SCOPE_BASE", "origin/main"))
    parser.add_argument("--head", default=os.environ.get("CHANGE_SCOPE_HEAD", "HEAD"))
    parser.add_argument(
        "--event-name",
        default=os.environ.get("GITHUB_EVENT_NAME", "pull_request"),
    )
    parser.add_argument("--github-output", help="Append outputs for GitHub Actions")
    parser.add_argument("--summary", help="Append a short report to GITHUB_STEP_SUMMARY")
    parser.add_argument("--json", action="store_true", help="Print machine-readable JSON")
    args = parser.parse_args()

    if args.event_name != "pull_request":
        result = full_result()
        mode = f"full-fallback:{args.event_name}"
    else:
        if not args.base or not args.head:
            print("change scope requires both --base and --head for pull_request", file=sys.stderr)
            return 2
        try:
            paths = changed_paths(args.base, args.head)
        except subprocess.CalledProcessError as exc:
            detail = exc.stderr.decode("utf-8", errors="replace").strip() if exc.stderr else ""
            message = f"unable to compute change scope: git diff exited {exc.returncode}"
            if detail:
                message += f": {detail}"
            print(message, file=sys.stderr)
            return 2
        result = classify_paths(paths)
        mode = f"diff:{args.base}...{args.head}"

    write_outputs(result, args.github_output)
    write_summary(result, args.summary, mode)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    else:
        print(f"change scope: mode={mode}")
        print(f"changed files: {result['changed_files']}")
        print(f"scopes: {', '.join(result['scopes'])}")
        print(f"docs-only: {str(result['docs-only']).lower()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
