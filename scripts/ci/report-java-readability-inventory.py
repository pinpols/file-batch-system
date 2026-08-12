#!/usr/bin/env python3
"""Report Java readability refactoring candidates without modifying sources."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass
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
SELF_INJECTION = re.compile(
    r"@Lazy\s+@Autowired\s+private\s+(?P<type>[A-Za-z_$][\w$<>., ?]*)\s+self\s*;",
    re.MULTILINE,
)
MAP_OBJECT = re.compile(r"\bMap\s*<\s*String\s*,\s*Object\s*>")
SUPPRESSION = re.compile(r"@SuppressWarnings\s*\(")
CONFIGURATION = re.compile(r"@Configuration\b(?:\s*\([^)]*\))?")
PUBLIC_MAP_CONTRACT = re.compile(
    r"\bpublic\s+(?:static\s+)?(?:[\w$.?]+\s+)*"
    r"(?:Map\s*<\s*String\s*,\s*Object\s*>|List\s*<\s*Map\s*<\s*String\s*,\s*Object\s*>\s*>)"
    r"\s+[A-Za-z_$][\w$]*\s*\(",
    re.MULTILINE,
)
WIDE_PARAMETER_SUPPRESSION = re.compile(
    r'@SuppressWarnings\s*\(\s*"PMD\.ExcessiveParameterList"\s*\)'
)
LARGE_FILE_THRESHOLD = 700


@dataclass(frozen=True)
class SourceFact:
    path: str
    lines: int
    map_count: int
    suppression_count: int
    configuration_count: int
    public_map_contracts: tuple[str, ...]
    wide_parameter_suppression_count: int
    self_types: tuple[str, ...]

    @property
    def public_map_contract_count(self) -> int:
        return len(self.public_map_contracts)

    @property
    def module(self) -> str:
        return self.path.split("/", maxsplit=1)[0]


def tracked_main_sources() -> list[Path]:
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


def inspect(path: Path) -> SourceFact:
    source = path.read_text(encoding="utf-8")
    public_map_contracts = tuple(
        f"L{source.count(chr(10), 0, match.start()) + 1}: "
        f"{' '.join(match.group(0).split())[:-1]}"
        for match in PUBLIC_MAP_CONTRACT.finditer(source)
    )
    return SourceFact(
        path=path.relative_to(ROOT).as_posix(),
        lines=len(source.splitlines()),
        map_count=len(MAP_OBJECT.findall(source)),
        suppression_count=len(SUPPRESSION.findall(source)),
        configuration_count=len(CONFIGURATION.findall(source)),
        public_map_contracts=public_map_contracts,
        wide_parameter_suppression_count=len(WIDE_PARAMETER_SUPPRESSION.findall(source)),
        self_types=tuple(match.group("type").strip() for match in SELF_INJECTION.finditer(source)),
    )


def markdown(facts: list[SourceFact]) -> str:
    module_counts = Counter(fact.module for fact in facts)
    self_injections = [fact for fact in facts if fact.self_types]
    large_files = sorted(
        (fact for fact in facts if fact.lines >= LARGE_FILE_THRESHOLD),
        key=lambda fact: (-fact.lines, fact.path),
    )
    public_map_contracts = [fact for fact in facts if fact.public_map_contract_count]
    wide_parameter_files = [fact for fact in facts if fact.wide_parameter_suppression_count]
    config_files = [fact for fact in facts if fact.configuration_count]

    lines = [
        "# Java 可读性治理扫描快照",
        "",
        "> 由 `python3 scripts/ci/report-java-readability-inventory.py` 生成。",
        "> 本报告只列候选，不把行数、Map 或 suppression 数量直接判定为缺陷。",
        "",
        "## 汇总",
        "",
        "| 指标 | 数量 |",
        "|---|---:|",
        f"| 生产 Java 源文件 | {len(facts)} |",
        f"| CGLIB 自注入类 | {len(self_injections)} |",
        f"| `Map<String, Object>` 出现次数 | {sum(fact.map_count for fact in facts)} |",
        f"| 含 Map 的源文件 | {sum(1 for fact in facts if fact.map_count)} |",
        f"| public Map 契约候选 | {sum(fact.public_map_contract_count for fact in public_map_contracts)} |",
        f"| public Map 契约候选文件 | {len(public_map_contracts)} |",
        f"| `@SuppressWarnings` | {sum(fact.suppression_count for fact in facts)} |",
        f"| 含 suppression 的源文件 | {sum(1 for fact in facts if fact.suppression_count)} |",
        f"| `@Configuration` 类 | {sum(fact.configuration_count for fact in config_files)} |",
        f"| 大于等于 {LARGE_FILE_THRESHOLD} 行的源文件 | {len(large_files)} |",
        f"| `PMD.ExcessiveParameterList` 显式例外 | {sum(fact.wide_parameter_suppression_count for fact in wide_parameter_files)} |",
        "",
        "## 模块源文件",
        "",
        "| 模块 | 生产 Java 文件 |",
        "|---|---:|",
    ]
    lines.extend(f"| `{module}` | {count} |" for module, count in sorted(module_counts.items()))

    lines.extend(
        [
            "",
            "## CGLIB 自注入",
            "",
            "| 文件 | 自注入类型 |",
            "|---|---|",
        ]
    )
    lines.extend(
        f"| `{fact.path}` | `{', '.join(fact.self_types)}` |" for fact in self_injections
    )

    lines.extend(
        [
            "",
            f"## 大类候选（大于等于 {LARGE_FILE_THRESHOLD} 行）",
            "",
            "| 文件 | 行数 |",
            "|---|---:|",
        ]
    )
    lines.extend(f"| `{fact.path}` | {fact.lines} |" for fact in large_files)

    lines.extend(
        [
            "",
            "## Public Map 契约候选",
            "",
            "> 此处只做词法候选。插件参数、metadata、JSONB、动态聚合和外部扩展字段应保留 Map。",
            "",
            "| 文件 | 候选方法 |",
            "|---|---|",
        ]
    )
    lines.extend(
        f"| `{fact.path}` | {'<br>'.join(f'`{contract}`' for contract in fact.public_map_contracts)} |"
        for fact in public_map_contracts
    )

    lines.extend(
        [
            "",
            "## 宽参数显式例外",
            "",
            "| 文件 | 例外数 |",
            "|---|---:|",
        ]
    )
    lines.extend(
        f"| `{fact.path}` | {fact.wide_parameter_suppression_count} |"
        for fact in wide_parameter_files
    )
    lines.append("")
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="write the report to this repository-relative path")
    parser.add_argument(
        "--check",
        type=Path,
        help="fail when this repository-relative report differs from the generated snapshot",
    )
    return parser.parse_args()


def resolve(path: Path) -> Path:
    return path if path.is_absolute() else ROOT / path


def main() -> int:
    args = parse_args()
    report = markdown([inspect(path) for path in tracked_main_sources()])
    if args.output:
        output = resolve(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(report, encoding="utf-8")
    if args.check:
        expected = resolve(args.check)
        if not expected.exists() or expected.read_text(encoding="utf-8") != report:
            print(f"Java readability inventory is stale: {expected.relative_to(ROOT)}", file=sys.stderr)
            return 1
    if not args.output and not args.check:
        print(report, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
