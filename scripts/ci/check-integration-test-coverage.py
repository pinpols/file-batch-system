#!/usr/bin/env python3
"""校验 full-ci-gate 的 Maven verify shard 覆盖所有主 reactor 集成测试模块。"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FULL_CI = ROOT / ".github" / "workflows" / "full-ci-gate.yml"
GATE_CODE = "INTEGRATION_TEST_COVERAGE"
GATE_NAME = "集成测试覆盖"


def strip_namespace(tag: str) -> str:
    return tag.rsplit("}", maxsplit=1)[-1]


def read_modules(pom: Path) -> list[str]:
    root = ET.parse(pom).getroot()
    modules: list[str] = []
    for element in root.iter():
        if strip_namespace(element.tag) == "module" and element.text:
            modules.append(element.text.strip())
    return modules


def collect_reactor_modules(module_dir: Path, result: set[Path]) -> None:
    pom = module_dir / "pom.xml"
    if not pom.is_file():
        return
    result.add(module_dir.relative_to(ROOT))
    for child in read_modules(pom):
        collect_reactor_modules(module_dir / child, result)


def has_non_e2e_integration_tests(module_dir: Path) -> bool:
    test_root = module_dir / "src" / "test"
    if not test_root.is_dir():
        return False
    for path in test_root.rglob("*.java"):
        name = path.name
        if name.startswith("Abstract"):
            continue
        if name.endswith("E2eIT.java"):
            continue
        if name.endswith("IntegrationTest.java") or name.endswith("IT.java"):
            return True
    return False


def integration_test_modules() -> set[str]:
    modules: set[Path] = set()
    for module in read_modules(ROOT / "pom.xml"):
        collect_reactor_modules(ROOT / module, modules)
    return {
        path.as_posix()
        for path in sorted(modules)
        if path.as_posix() != "batch-e2e-tests"
        and has_non_e2e_integration_tests(ROOT / path)
    }


def full_ci_verify_modules() -> set[str]:
    text = FULL_CI.read_text(encoding="utf-8")
    # GitHub Actions run block 中命令常用反斜线续行；压成一行后解析 verify -pl。
    normalized = re.sub(r"\\\s*\n\s*", " ", text)
    modules: set[str] = set()
    for match in re.finditer(r"\./mvnw\s+verify\s+-pl\s+([A-Za-z0-9,_/-]+)(?P<tail>.*?)(?:\n|$)", normalized):
        tail = match.group("tail")
        if "-DskipITs=false" not in tail:
            continue
        modules.update(item for item in match.group(1).split(",") if item)
    return modules


def existing_module_paths() -> set[str]:
    modules: set[Path] = set()
    for module in read_modules(ROOT / "pom.xml"):
        collect_reactor_modules(ROOT / module, modules)
    return {path.as_posix() for path in modules}


def print_list(title: str, values: list[str]) -> None:
    print(title)
    for value in values:
        print(f"   - {value}")


def main() -> int:
    actual = integration_test_modules()
    covered = full_ci_verify_modules()
    existing = existing_module_paths()

    missing = sorted(actual - covered)
    stale = sorted(module for module in covered if module not in existing)

    print(f"ℹ️  主 reactor 非 E2E 集成测试模块: {len(actual)} 个")
    print(f"ℹ️  full-ci-gate verify -DskipITs=false 覆盖模块: {len(covered)} 个")

    if not missing and not stale:
        print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} | modules={len(actual)}")
        return 0

    print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
    if missing:
        print_list("full-ci-gate 的 verify shard 未覆盖这些含集成测试的主 reactor 模块:", missing)
    if stale:
        print_list("full-ci-gate 的 verify -pl 引用了不存在的 reactor 模块:", stale)
    print()
    print("修复方式:把缺失模块加入 full-ci-gate 某个 unit-it shard 的 `mvn verify -pl ... -DskipITs=false`,")
    print("或确认该目录不是主 reactor 后移动测试/重命名测试。E2E `*E2eIT` 由 e2e shard 守护单独覆盖。")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
