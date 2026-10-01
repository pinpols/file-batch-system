#!/usr/bin/env python3
"""比较两份 CycloneDX SBOM 的依赖语义，忽略跨环境的不稳定展示字段。"""

from __future__ import annotations

import argparse
import difflib
import json
from pathlib import Path
from typing import Any


PLATFORM_COMPONENT_PREFIXES = (
    "pkg:maven/io.netty/netty-resolver-dns-native-macos@",
)


def is_platform_component(reference: Any) -> bool:
    return isinstance(reference, str) and reference.startswith(PLATFORM_COMPONENT_PREFIXES)


def component_identity(component: dict[str, Any]) -> dict[str, Any]:
    licenses = component.get("licenses") or []
    hashes = component.get("hashes") or []
    return {
        "type": component.get("type"),
        "group": component.get("group"),
        "name": component.get("name"),
        "version": component.get("version"),
        "scope": component.get("scope"),
        "purl": component.get("purl"),
        "licenses": sorted(
            licenses,
            key=lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True),
        ),
        "hashes": sorted(
            hashes,
            key=lambda value: (str(value.get("alg")), str(value.get("content"))),
        ),
    }


def canonicalize(document: dict[str, Any]) -> dict[str, Any]:
    metadata = document.get("metadata") or {}
    root_component = metadata.get("component") or {}
    components = [
        component_identity(value)
        for value in document.get("components") or []
        if not is_platform_component(value.get("purl"))
    ]
    components.sort(
        key=lambda value: (
            str(value.get("group")),
            str(value.get("name")),
            str(value.get("version")),
            str(value.get("purl")),
        )
    )

    dependencies = [
        {
            "ref": value.get("ref"),
            "dependsOn": sorted(
                ref
                for ref in value.get("dependsOn") or []
                if not is_platform_component(ref)
            ),
        }
        for value in document.get("dependencies") or []
        if not is_platform_component(value.get("ref"))
    ]
    dependencies.sort(key=lambda value: str(value.get("ref")))

    return {
        "bomFormat": document.get("bomFormat"),
        "specVersion": document.get("specVersion"),
        "version": document.get("version"),
        "rootComponent": {
            key: root_component.get(key)
            for key in ("type", "group", "name", "version", "purl")
        },
        "components": components,
        "dependencies": dependencies,
    }


def load(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"SBOM 根节点必须是对象：{path}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tracked", type=Path)
    parser.add_argument("generated", type=Path)
    args = parser.parse_args()

    tracked = json.dumps(
        canonicalize(load(args.tracked)), ensure_ascii=False, indent=2, sort_keys=True
    ).splitlines()
    generated = json.dumps(
        canonicalize(load(args.generated)), ensure_ascii=False, indent=2, sort_keys=True
    ).splitlines()
    if tracked == generated:
        return 0

    diff = difflib.unified_diff(
        tracked,
        generated,
        fromfile=str(args.tracked),
        tofile=str(args.generated),
        lineterm="",
    )
    print("\n".join(list(diff)[:200]))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
