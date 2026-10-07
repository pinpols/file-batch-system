#!/usr/bin/env python3
"""Detect changed repository scopes for local and GitHub Actions routing.

The classifier is intentionally conservative. A pull request diff is classified
by comparing the ``base`` and ``head`` trees directly. Events without a trustworthy PR diff fall back to the
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
UNIT_SHARD_NAMES = (
    "unit-a-required",
    "unit-b1-required",
    "unit-b2-workers-required",
    "unit-b2-console-required",
)
ALL_UNIT_SHARDS = frozenset(UNIT_SHARD_NAMES)
CODE_SCOPES = set(SCOPE_NAMES) - {"docs", "unknown"}
ROOT_TOOL_CONFIG_FILES = {
    ".gitleaks.toml",
    ".gitleaksignore",
    ".node-version",
    ".nvmrc",
    ".python-version",
    ".squawk.toml",
    ".trivyignore",
    ".trivyignore.yaml",
    "qodana.yaml",
}


def under(path: str, prefix: str) -> bool:
    return path == prefix or path.startswith(f"{prefix}/")


def is_markdown(path: str) -> bool:
    return path.endswith((".md", ".mdx", ".rst"))


def is_config(path: str) -> bool:
    if path.startswith(".github/") or under(path, "docs"):
        return False
    name = PurePosixPath(path).name
    if path in ROOT_TOOL_CONFIG_FILES:
        return True
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
    ) or normalized in {
        "docs/governance/java-contract-governance.json",
        "docs/governance/java-contract-governance-baseline.json",
    }:
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


def required_unit_shards(path: str) -> set[str]:
    """Return the conservative unit-test shards affected by one path."""
    normalized = path.removeprefix("./")
    # E2E 源码由 static-checks 的全 reactor test-compile 保证可编译，并在 main / nightly
    # 的六片 E2E 中执行；PR 单元分片不重复启动。
    if under(normalized, "batch-e2e-tests/src"):
        return set()
    if (
        under(normalized, "db/migration")
        or normalized == "pom.xml"
        or normalized.endswith("/pom.xml")
        or under(normalized, ".mvn")
        or normalized.startswith("mvnw")
        or under(normalized, "batch-common/src")
        or under(normalized, "batch-test-support/src")
    ):
        return set(ALL_UNIT_SHARDS)

    if under(normalized, "batch-orchestrator/src") or under(normalized, "sdk/java"):
        return {"unit-a-required"}
    if under(normalized, "batch-worker/core/src"):
        return {
            "unit-a-required",
            "unit-b1-required",
            "unit-b2-workers-required",
        }
    if any(
        under(normalized, prefix)
        for prefix in (
            "batch-trigger/src",
            "batch-worker/process/src",
            "batch-worker/dispatch/src",
        )
    ):
        return {"unit-b1-required"}
    if any(
        under(normalized, prefix)
        for prefix in (
            "batch-worker/import/src",
            "batch-worker/export/src",
            "batch-worker/atomic/src",
        )
    ):
        return {"unit-b2-workers-required"}
    if under(normalized, "batch-console-api/src"):
        return {"unit-b2-console-required"}

    # 新增 reactor 模块在显式登记前保守全跑，避免范围路由漏掉测试。
    if normalized.startswith("batch-") and "/src/" in normalized:
        return set(ALL_UNIT_SHARDS)
    return set()


def classify_paths(paths: list[str]) -> dict[str, object]:
    scopes: set[str] = set()
    unit_shards: set[str] = set()
    for path in paths:
        scopes.update(classify_path(path))
        unit_shards.update(required_unit_shards(path))
    code_changed = bool(scopes & CODE_SCOPES)
    if "unknown" in scopes:
        unit_shards.update(ALL_UNIT_SHARDS)
    return {
        "changed_files": len(paths),
        "scopes": sorted(scopes),
        "docs-only": bool(paths) and not code_changed and "unknown" not in scopes,
        "unit-required": bool(unit_shards),
        **{shard: shard in unit_shards for shard in UNIT_SHARD_NAMES},
        **{scope: scope in scopes for scope in SCOPE_NAMES},
    }


def full_result() -> dict[str, object]:
    return {
        "changed_files": 0,
        "scopes": ["full"],
        "docs-only": False,
        "unit-required": True,
        **{shard: True for shard in UNIT_SHARD_NAMES},
        **{scope: scope != "unknown" for scope in SCOPE_NAMES},
        "unknown": False,
    }


def changed_paths(base: str, head: str) -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "-z", base, head, "--"],
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
        mode = f"tree-diff:{args.base}..{args.head}"

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
