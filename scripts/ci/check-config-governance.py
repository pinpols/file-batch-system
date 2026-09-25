#!/usr/bin/env python3
"""校验启动期配置登记完整性，并阻止引入未经治理的运行时刷新机制。"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "docs/runbook/config-governance-registry.yml"
RUNTIME_REGISTRY = (
    ROOT / "batch-console-api/src/main/resources/config-governance-registry.json"
)
PROPERTY_PATTERN = re.compile(
    r'^\s*@ConfigurationProperties\b\s*\(\s*(?:prefix\s*=\s*)?"([^"]+)"\s*\)',
    re.MULTILINE,
)
DECLARATION_PATTERN = re.compile(
    r"\b(?:class|record|interface)\s+(\w+)|\b(\w+)\s*\([^;{}]*\)\s*\{"
)
SENSITIVE_PATTERN = re.compile(
    r"(?:password|secret|token|credential|private[-_.]?key|kms|signing)", re.IGNORECASE
)
FORBIDDEN_SOURCE_PATTERNS = ("@RefreshScope", "ConfigurationPropertiesRebinder")
FORBIDDEN_DEPENDENCIES = (
    "spring-cloud-starter-config",
    "spring-cloud-context",
    "nacos-config",
    "apollo-client",
)


def production_java_files() -> list[Path]:
    return sorted(
        path
        for path in ROOT.glob("**/src/main/java/**/*.java")
        if "/target/" not in path.as_posix()
    )


def inventory() -> list[dict[str, object]]:
    entries: list[dict[str, object]] = []
    for path in production_java_files():
        source = path.read_text(encoding="utf-8")
        for match in PROPERTY_PATTERN.finditer(source):
            declaration = DECLARATION_PATTERN.search(source, match.end())
            if declaration is None:
                raise ValueError(f"无法识别 @ConfigurationProperties 声明: {path}")
            symbol = declaration.group(1) or declaration.group(2)
            prefix = match.group(1)
            relative = path.relative_to(ROOT).as_posix()
            sensitivity = (
                "SECRET"
                if SENSITIVE_PATTERN.search(prefix)
                or SENSITIVE_PATTERN.search(symbol)
                or SENSITIVE_PATTERN.search(source[match.end() : declaration.end() + 3000])
                else "PUBLIC"
            )
            entries.append(
                {
                    "id": f"{relative}#{symbol}",
                    "className": symbol,
                    "prefix": prefix,
                    "source": "STATIC",
                    "activation": "RESTART_REQUIRED",
                    "sensitivity": sensitivity,
                }
            )
    return sorted(entries, key=lambda item: str(item["id"]))


def expected_document() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "description": (
            "生产源码配置治理登记表；由 scripts/ci/check-config-governance.py 维护。"
            "source 与 activation 是正交维度，SECRET 是敏感级别，不冒充生效方式。"
        ),
        "configurationProperties": inventory(),
        "dynamicDbFamilies": [
            {
                "key": "business-calendar",
                "source": "DYNAMIC_DB",
                "activation": "IMMEDIATE_AFTER_CONFIRMATION",
            },
            {
                "key": "batch-window",
                "source": "DYNAMIC_DB",
                "activation": "IMMEDIATE_AFTER_CONFIRMATION",
            },
            {
                "key": "job-definition",
                "source": "DYNAMIC_DB",
                "activation": "IMMEDIATE_AFTER_CONFIRMATION",
            },
            {
                "key": "workflow-definition",
                "source": "DYNAMIC_DB",
                "activation": "IMMEDIATE_AFTER_CONFIRMATION",
            },
            {
                "key": "tenant-quota-policy",
                "source": "DYNAMIC_DB",
                "activation": "IMMEDIATE_AFTER_CONFIRMATION",
            },
            {
                "key": "config-release",
                "source": "DYNAMIC_DB",
                "activation": "IMMEDIATE_AFTER_CONFIRMATION",
            },
        ],
    }


def rendered_document() -> str:
    # JSON 是 YAML 1.2 的合法子集，可用标准库稳定校验，避免 CI 额外安装 PyYAML。
    return json.dumps(expected_document(), ensure_ascii=False, indent=2) + "\n"


def forbidden_usages() -> list[str]:
    violations: list[str] = []
    for path in production_java_files():
        source = path.read_text(encoding="utf-8")
        for pattern in FORBIDDEN_SOURCE_PATTERNS:
            if pattern in source:
                violations.append(f"{path.relative_to(ROOT)}: 禁止使用 {pattern}")
    for path in sorted(ROOT.glob("**/pom.xml")):
        if "/target/" in path.as_posix():
            continue
        source = path.read_text(encoding="utf-8")
        for dependency in FORBIDDEN_DEPENDENCIES:
            if dependency in source:
                violations.append(f"{path.relative_to(ROOT)}: 禁止依赖 {dependency}")
    return violations


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="按当前源码重建登记表")
    args = parser.parse_args()

    violations = forbidden_usages()
    if violations:
        print("❌ configuration governance validation failed:", file=sys.stderr)
        print("\n".join(violations), file=sys.stderr)
        return 1

    expected = rendered_document()
    if args.write:
        REGISTRY.write_text(expected, encoding="utf-8")
        RUNTIME_REGISTRY.write_text(expected, encoding="utf-8")
        print(
            "✅ configuration governance registry written: "
            f"{len(inventory())} configuration properties"
        )
        return 0

    if not REGISTRY.exists() or REGISTRY.read_text(encoding="utf-8") != expected:
        print(
            "❌ configuration governance validation failed:\n"
            "配置治理登记表与源码不一致；执行 "
            "python3 scripts/ci/check-config-governance.py --write 后提交结果。",
            file=sys.stderr,
        )
        return 1
    if not RUNTIME_REGISTRY.exists() or RUNTIME_REGISTRY.read_text(encoding="utf-8") != expected:
        print(
            "❌ configuration governance validation failed:\n"
            "Console 运行时配置目录与治理登记表不一致；请执行 --write。",
            file=sys.stderr,
        )
        return 1
    print(
        "✅ configuration governance valid: "
        f"{len(inventory())} configuration properties, no runtime refresh violations"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
