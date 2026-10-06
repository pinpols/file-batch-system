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

# 此清单采用精确匹配。新增例外必须经过评审，并在此登记及
# docs/standards/java-suppression-registry.md 中说明原因。
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
    "java:S2093",
    "java:S2259",
    "java:S2583",
    "java:S2589",
    "java:S3330",
    "java:S4502",
    "java:S5164",
    "java:S6218",
}
GATE_CODE = "JAVA_SUPPRESSION_REGISTRY"
GATE_NAME = "Java SuppressWarnings 登记"


def production_sources(candidates: list[str] | None = None) -> list[Path]:
    if candidates is not None:
        return sorted(
            ROOT / relative
            for relative in candidates
            if relative.endswith(".java")
            and "/src/main/java/" in relative
            and (ROOT / relative).is_file()
            and relative.startswith(SOURCE_PREFIXES)
        )
    result = subprocess.run(
        # --cached 列出已跟踪文件，--others --exclude-standard 补上尚未 add 的新文件；
        # 只扫已跟踪文件会漏掉本地在途新增的 suppression（CI 检出时全为已跟踪，故 CI 无感知）。
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", *SOURCE_PREFIXES],
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


def scan(candidates: list[str] | None = None) -> list[tuple[str, int, str]]:
    findings: list[tuple[str, int, str]] = []
    for path in production_sources(candidates):
        source = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT).as_posix()
        for match in ANNOTATION.finditer(source):
            line = source.count("\n", 0, match.start()) + 1
            for rule_match in RULE.finditer(match.group("body")):
                findings.append((relative, line, rule_match.group("rule")))
    return findings


def main(argv: list[str] | None = None) -> int:
    candidates = argv if argv else None
    findings = scan(candidates)
    unknown = [finding for finding in findings if finding[2] not in KNOWN_RULES]
    counts = Counter(rule for _, _, rule in findings)
    print(f"Java production suppressions: {len(findings)}")
    print("Reviewed rules: " + ", ".join(f"{rule}={counts[rule]}" for rule in sorted(counts)))
    if unknown:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        print("\nUnregistered production suppressions:")
        for path, line, rule in unknown:
            print(f"  - {path}:{line}: {rule}")
        print(
            "\nAdd the rule to KNOWN_RULES and document its owner/reason before merging."
        )
        return 1
    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
