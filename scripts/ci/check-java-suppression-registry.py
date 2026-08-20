#!/usr/bin/env python3
"""Require production Java suppressions to use the reviewed rule registry."""

from __future__ import annotations

import re
import subprocess
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_PREFIXES = (
    "batch-common/",
    "batch-console-api/",
    "batch-orchestrator/",
    "batch-trigger/",
    "batch-worker/",
    "sdk/java/",
    "security-scan/",
)
ANNOTATION = re.compile(r"@SuppressWarnings\s*\((?P<body>.*?)\)", re.DOTALL)
RULE = re.compile(r'"(?P<rule>[^"\\]+)"')

# This is intentionally an exact registry. A new exception must be reviewed and
# added here together with its reason in docs/standards/java-suppression-registry.md.
KNOWN_RULES = {
    "unchecked",
    "rawtypes",
    "deprecation",
    "ConfigurationProperties",
    "SpringJavaInjectionPointsAutowiringInspection",
    "PMD.ExcessiveParameterList",
    "PMD.NcssCount",
    "java:S112",
    "java:S1181",
    "java:S1313",
    "java:S2068",
    "java:S2077",
    "java:S2259",
    "java:S2583",
    "java:S2589",
    "java:S3330",
    "java:S4502",
    "java:S6218",
}


def production_sources() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--", *SOURCE_PREFIXES],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return sorted(
        ROOT / relative
        for relative in result.stdout.splitlines()
        if relative.endswith(".java") and "/src/main/java/" in relative
    )


def scan() -> list[tuple[str, int, str]]:
    findings: list[tuple[str, int, str]] = []
    for path in production_sources():
        source = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT).as_posix()
        for match in ANNOTATION.finditer(source):
            line = source.count("\n", 0, match.start()) + 1
            for rule_match in RULE.finditer(match.group("body")):
                findings.append((relative, line, rule_match.group("rule")))
    return findings


def main() -> int:
    findings = scan()
    unknown = [finding for finding in findings if finding[2] not in KNOWN_RULES]
    counts = Counter(rule for _, _, rule in findings)
    print(f"Java production suppressions: {len(findings)}")
    print("Reviewed rules: " + ", ".join(f"{rule}={counts[rule]}" for rule in sorted(counts)))
    if unknown:
        print("\nUnregistered production suppressions:")
        for path, line, rule in unknown:
            print(f"  - {path}:{line}: {rule}")
        print(
            "\nAdd the rule to KNOWN_RULES and document its owner/reason before merging."
        )
        return 1
    print("Suppression registry check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
