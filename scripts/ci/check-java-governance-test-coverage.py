#!/usr/bin/env python3
"""核对 Java 架构/约定守卫的源码清单与 Surefire 执行报告。"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


GOVERNANCE_SUFFIXES = ("ArchTest.java", "ConventionTest.java")
PACKAGE_PATTERN = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)


@dataclass(frozen=True)
class GovernanceTest:
    source: Path
    module: Path
    qualified_name: str

    @property
    def report(self) -> Path:
        return self.module / "target" / "surefire-reports" / f"TEST-{self.qualified_name}.xml"


def discover(root: Path) -> list[GovernanceTest]:
    tests: list[GovernanceTest] = []
    for source in sorted(root.glob("**/src/test/java/**/*.java")):
        if not source.name.endswith(GOVERNANCE_SUFFIXES):
            continue
        relative = source.relative_to(root)
        parts = relative.parts
        source_index = parts.index("src")
        module = root.joinpath(*parts[:source_index])
        content = source.read_text(encoding="utf-8")
        package_match = PACKAGE_PATTERN.search(content)
        if package_match is None:
            raise ValueError(f"Java governance test has no package declaration: {relative}")
        tests.append(
            GovernanceTest(
                source=relative,
                module=module,
                qualified_name=f"{package_match.group(1)}.{source.stem}",
            )
        )
    return tests


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--verify-reports", action="store_true")
    args = parser.parse_args()

    root = args.root.resolve()
    try:
        tests = discover(root)
    except (OSError, ValueError) as exc:
        print(f"❌ {exc}", file=sys.stderr)
        return 1

    if not tests:
        print("❌ 未发现 *ArchTest / *ConventionTest，治理测试路由可能已漂移。", file=sys.stderr)
        return 1

    duplicates = sorted(
        name for name in {test.qualified_name for test in tests} if sum(t.qualified_name == name for t in tests) > 1
    )
    if duplicates:
        print(f"❌ 治理测试全限定名重复: {', '.join(duplicates)}", file=sys.stderr)
        return 1

    if args.verify_reports:
        missing = [test for test in tests if not test.report.is_file()]
        if missing:
            print("❌ 以下 Java 治理测试未产生 Surefire 报告:", file=sys.stderr)
            for test in missing:
                print(f"   - {test.source}", file=sys.stderr)
            return 1

    modules = sorted({str(test.module.relative_to(root)) for test in tests})
    mode = "执行完整性" if args.verify_reports else "源码清单"
    print(f"✅ Java 治理测试{mode}通过: tests={len(tests)}, modules={len(modules)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
