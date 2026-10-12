#!/usr/bin/env python3
"""Require production Java/YAML suppressions to use the reviewed rule registry."""

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
ANNOTATION = re.compile(
    r"@(?P<annotation>SuppressWarnings|SuppressFBWarnings)\s*\((?P<body>.*?)\)",
    re.DOTALL,
)
RULE = re.compile(r'"(?P<rule>[^"\\]+)"')
FB_WARNINGS_VALUE = re.compile(r"\bvalue\s*=\s*(?P<value>\{[^}]*\}|\"(?:\\\\.|[^\"])*\")", re.DOTALL)
INSPECTION_COMMENT = re.compile(r"#\s*noinspection\s+(?P<rule>[A-Za-z][A-Za-z0-9_.]*)\b")
NOSONAR_MARKER = re.compile(r"^\s*NOSONAR\b")

# 此清单采用精确匹配。新增例外必须经过评审，并在此登记及
# docs/standards/java-suppression-registry.md 中说明原因。
KNOWN_RULES = {
    "unchecked",
    "rawtypes",
    "deprecation",
    "ConfigurationProperties",
    "SpringJavaInjectionPointsAutowiringInspection",
    "SpringBootApplicationYaml",
    "PMD.ExcessiveParameterList",
    "PMD.NcssCount",
    "java:S112",
    "java:S1075",
    "java:S1181",
    "java:S1313",
    "java:S1452",
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
# 路由常量不是部署 URL；该误报只允许在已评审代理内使用一次，不能扩成全仓路径豁免。
SCOPED_RULES = {
    "java:S1075": {
        "batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/DefaultConsoleTriggerProxyService.java": 1,
    },
}
GATE_CODE = "JAVA_SUPPRESSION_REGISTRY"
GATE_NAME = "生产代码静态检查抑制项登记"
BASELINE = ROOT / "scripts/ci/java-suppression-baseline.tsv"


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


def production_yaml_configs(candidates: list[str] | None = None) -> list[Path]:
    if candidates is not None:
        return sorted(
            ROOT / relative
            for relative in candidates
            if Path(relative).suffix in {".yml", ".yaml"}
            and "/src/main/resources/" in relative
            and (ROOT / relative).is_file()
            and relative.startswith(SOURCE_PREFIXES)
        )
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", *SOURCE_PREFIXES],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return sorted(
        ROOT / relative
        for relative in result.stdout.splitlines()
        if Path(relative).suffix in {".yml", ".yaml"}
        and "/src/main/resources/" in relative
    )


def nosonar_comment_lines(source: str) -> list[int]:
    """Return actual Java line comments containing a Sonar NOSONAR directive."""
    lines: list[int] = []
    state = "code"
    line = 1
    index = 0
    while index < len(source):
        current = source[index]
        following = source[index : index + 2]
        if current == "\n":
            line += 1
            if state == "line-comment":
                state = "code"
            index += 1
            continue
        if state == "line-comment":
            index += 1
            continue
        if state == "block-comment":
            if following == "*/":
                state = "code"
                index += 2
            else:
                index += 1
            continue
        if state == "text-block":
            if source[index : index + 3] == '\"\"\"':
                state = "code"
                index += 3
            else:
                index += 1
            continue
        if state in {"string", "char"}:
            if current == "\\":
                index += 2
            elif (state == "string" and current == '\"') or (state == "char" and current == "'"):
                state = "code"
                index += 1
            else:
                index += 1
            continue
        if source[index : index + 3] == '\"\"\"':
            state = "text-block"
            index += 3
        elif following == "//":
            comment_end = source.find("\n", index + 2)
            if comment_end < 0:
                comment_end = len(source)
            if NOSONAR_MARKER.search(source[index + 2 : comment_end]):
                lines.append(line)
            state = "line-comment"
            index += 2
        elif following == "/*":
            state = "block-comment"
            index += 2
        elif current == '\"':
            state = "string"
            index += 1
        elif current == "'":
            state = "char"
            index += 1
        else:
            index += 1
    return lines


def scan(candidates: list[str] | None = None) -> list[tuple[str, int, str]]:
    findings: list[tuple[str, int, str]] = []
    for path in production_sources(candidates) + production_yaml_configs(candidates):
        source = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT).as_posix()
        if path.suffix == ".java":
            for match in ANNOTATION.finditer(source):
                line = source.count("\n", 0, match.start()) + 1
                body = match.group("body")
                annotation = match.group("annotation")
                if annotation == "SuppressFBWarnings":
                    value_attribute = FB_WARNINGS_VALUE.search(body)
                    body = (
                        value_attribute.group("value")
                        if value_attribute
                        else re.split(r",\s*justification\s*=", body, maxsplit=1)[0]
                    )
                for rule_match in RULE.finditer(body):
                    rule = rule_match.group("rule")
                    if annotation == "SuppressFBWarnings":
                        rule = f"spotbugs:{rule}"
                    findings.append((relative, line, rule))
            findings.extend((relative, line, "NOSONAR") for line in nosonar_comment_lines(source))
        else:
            for line_number, line in enumerate(source.splitlines(), start=1):
                match = INSPECTION_COMMENT.search(line)
                if match:
                    findings.append((relative, line_number, match.group("rule")))
    return findings


def suppression_targets_type(source: str, line_number: int) -> bool:
    """Whether an annotation on this line applies to a type rather than a member."""
    type_declaration = re.compile(
        r"^(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*"
        r"(?:class|interface|enum|record)\b"
    )
    for line in source.splitlines()[line_number:]:
        candidate = line.strip()
        if (
            not candidate
            or candidate.startswith(("//", "/*", "*", "*/"))
            or candidate.startswith("@")
        ):
            continue
        return type_declaration.match(candidate) is not None
    return False


def broad_scope_suppressions(
    findings: list[tuple[str, int, str]],
) -> list[tuple[str, int, str]]:
    violations = []
    for path, line, rule in findings:
        if rule != "java:S2077":
            continue
        source = (ROOT / path).read_text(encoding="utf-8")
        if suppression_targets_type(source, line):
            violations.append((path, line, rule))
    return violations


def unregistered(findings: list[tuple[str, int, str]]) -> list[tuple[str, int, str]]:
    counts = Counter((path, rule) for path, _, rule in findings)
    return [
        finding for finding in findings
        if finding[2] not in KNOWN_RULES
        or (
            finding[2] in SCOPED_RULES
            and counts[(finding[0], finding[2])] > SCOPED_RULES[finding[2]].get(finding[0], 0)
        )
    ]


def suppression_counts(findings: list[tuple[str, int, str]]) -> Counter[tuple[str, str]]:
    return Counter((path, rule) for path, _, rule in findings)


def read_baseline() -> Counter[tuple[str, str]]:
    counts: Counter[tuple[str, str]] = Counter()
    if not BASELINE.is_file():
        return counts
    for line_number, line in enumerate(BASELINE.read_text(encoding="utf-8").splitlines(), start=1):
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3 or not parts[2].isdigit():
            raise ValueError(f"{BASELINE}:{line_number}: expected path<TAB>rule<TAB>count")
        key = (parts[0], parts[1])
        if key in counts:
            raise ValueError(f"{BASELINE}:{line_number}: duplicate entry {parts[0]} {parts[1]}")
        counts[key] = int(parts[2])
    return counts


def write_baseline(findings: list[tuple[str, int, str]]) -> None:
    lines = [
        "# Reviewed production Java @SuppressWarnings and YAML IDE inspection inventory.",
        "# Update only after reviewing the source change and documenting its reason.",
        "# Format: repository-relative path<TAB>rule<TAB>approved occurrence count.",
    ]
    lines.extend(
        f"{path}\t{rule}\t{count}"
        for (path, rule), count in sorted(suppression_counts(findings).items())
    )
    BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    args = list(argv or [])
    if any(arg.startswith("-") for arg in args) and args != ["--write-baseline"]:
        print(f"Unknown arguments: {' '.join(args)}", file=sys.stderr)
        return 2
    findings = scan(None if not args or args == ["--write-baseline"] else args)
    if args == ["--write-baseline"]:
        write_baseline(findings)
        print(f"Wrote reviewed suppression baseline: {BASELINE} ({len(findings)} occurrences)")
        return 0
    unknown = unregistered(findings)
    broad_scope = broad_scope_suppressions(findings)
    counts = Counter(rule for _, _, rule in findings)
    print(f"Production Java/YAML suppressions: {len(findings)}")
    print("Reviewed rules: " + ", ".join(f"{rule}={counts[rule]}" for rule in sorted(counts)))
    if not BASELINE.is_file():
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | baseline missing: {BASELINE}")
        print("Run --write-baseline only after reviewing all current production suppressions.")
        return 1
    try:
        baseline = read_baseline()
    except ValueError as error:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | {error}")
        return 1
    current_counts = suppression_counts(findings)
    increased = [
        (path, rule, count, baseline.get((path, rule), 0))
        for (path, rule), count in sorted(current_counts.items())
        if count > baseline.get((path, rule), 0)
    ]
    if unknown:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        print("\nUnregistered production suppressions:")
        for path, line, rule in unknown:
            print(f"  - {path}:{line}: {rule}")
        print(
            "\nAdd the rule to KNOWN_RULES and document its owner/reason before merging."
        )
        return 1
    if increased:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | new suppression occurrences")
        for path, rule, current, approved in increased:
            print(f"  - {path}: {rule} current={current} approved={approved}")
        print("Review the exception reason and update the baseline deliberately.")
        return 1
    if broad_scope:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | broad S2077 scope")
        for path, line, rule in broad_scope:
            print(f"  - {path}:{line}: {rule} must be scoped to the SQL sink method")
        return 1
    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
