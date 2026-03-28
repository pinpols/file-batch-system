#!/usr/bin/env python3
"""
Verify that docs/api/console-api.openapi.yaml documents exactly the same
HTTP routes as batch-console-api Console*Controller classes.

Exit 1 on mismatch with a readable diff (openapi-only vs code-only).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError as exc:  # pragma: no cover
    print("Missing PyYAML. Install with: pip install pyyaml", file=sys.stderr)
    raise SystemExit(2) from exc

REPO_ROOT = Path(__file__).resolve().parents[2]
OPENAPI_PATH = REPO_ROOT / "docs" / "api" / "console-api.openapi.yaml"
CONTROLLER_GLOB = (
    REPO_ROOT
    / "batch-console-api"
    / "src"
    / "main"
    / "java"
    / "com"
    / "example"
    / "batch"
    / "console"
    / "web"
)

CLASS_MAPPING = re.compile(
    r"@RequestMapping\s*\(\s*(?:value\s*=\s*)?\"(/api/console[^\"]*)\"\s*\)"
)
METHOD_MAPPING = re.compile(
    r"@(?P<ann>GetMapping|PostMapping)\s*\(\s*(?:value\s*=\s*)?\"(?P<path>/[^\"]*)\"",
    re.MULTILINE,
)
CLASS_DECL = re.compile(r"public\s+class\s+Console\w+Controller\b")


def join_paths(base: str, sub: str) -> str:
    base = base.rstrip("/")
    if not sub or sub == "/":
        return base
    if not sub.startswith("/"):
        sub = "/" + sub
    return base + sub


def openapi_routes() -> set[tuple[str, str]]:
    data = yaml.safe_load(OPENAPI_PATH.read_text(encoding="utf-8"))
    paths = data.get("paths") or {}
    out: set[tuple[str, str]] = set()
    for path, item in paths.items():
        if not str(path).startswith("/api/console"):
            continue
        if not isinstance(item, dict):
            continue
        for method in ("get", "post", "put", "patch", "delete"):
            if method in item:
                out.add((method.upper(), path))
    return out


def controller_routes() -> set[tuple[str, str]]:
    out: set[tuple[str, str]] = set()
    for java in sorted(CONTROLLER_GLOB.glob("Console*Controller.java")):
        text = java.read_text(encoding="utf-8")
        decl = CLASS_DECL.search(text)
        if not decl:
            continue
        prefix = text[: decl.start()]
        m_class = CLASS_MAPPING.search(prefix)
        if not m_class:
            print(f"WARN: no @RequestMapping on {java.relative_to(REPO_ROOT)}", file=sys.stderr)
            continue
        base = m_class.group(1)
        body = text[decl.start() :]
        for m in METHOD_MAPPING.finditer(body):
            ann = m.group("ann")
            sub = m.group("path")
            method = "GET" if ann == "GetMapping" else "POST"
            full = join_paths(base, sub)
            out.add((method, full))
    return out


def main() -> int:
    if not OPENAPI_PATH.is_file():
        print(f"OpenAPI file not found: {OPENAPI_PATH}", file=sys.stderr)
        return 2

    api_paths = openapi_routes()
    code_paths = controller_routes()

    only_openapi = sorted(api_paths - code_paths)
    only_code = sorted(code_paths - api_paths)

    if not only_openapi and not only_code:
        print(
            f"OK: OpenAPI and Console*Controller agree ({len(api_paths)} routes under /api/console)."
        )
        return 0

    print("Console OpenAPI vs Controller path mismatch.\n", file=sys.stderr)
    if only_openapi:
        print("In OpenAPI but not in Console*Controller (GET/POST path):", file=sys.stderr)
        for m, p in only_openapi:
            print(f"  {m} {p}", file=sys.stderr)
        print(file=sys.stderr)
    if only_code:
        print("In Console*Controller but not in OpenAPI:", file=sys.stderr)
        for m, p in only_code:
            print(f"  {m} {p}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
