#!/usr/bin/env python3
"""
Verify that docs/api/console-api.openapi.yaml documents exactly the same
HTTP routes as batch-console-api controller classes under web/ and web/realtime/.

Exit 1 on mismatch with a readable diff (openapi-only vs code-only).
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

try:
    import yaml
except ImportError as exc:  # pragma: no cover
    print("Missing PyYAML. Install with: pip install pyyaml", file=sys.stderr)
    raise SystemExit(2) from exc

REPO_ROOT = Path(__file__).resolve().parents[2]
OPENAPI_PATH = REPO_ROOT / "docs" / "api" / "console-api.openapi.yaml"


def base_package() -> str:
    """基础包名 = 根 pom.xml 的 <project><groupId>(单一真相源)。

    改基础包名只需改 pom groupId,本脚本(及任何复用者)自动跟随,避免硬编码
    com/example/batch 这类路径段在重命名时被漏改 → 静默扫不到 Controller。
    取 <project> 直接子级 groupId(非 <parent> 里的),namespace-agnostic。
    """
    root = ET.parse(REPO_ROOT / "pom.xml").getroot()
    tag = root.tag
    ns = tag[: tag.index("}") + 1] if "}" in tag else ""
    gid = root.find(f"{ns}groupId")  # 直接子级 = 本项目 groupId(不会取到 parent 的)
    if gid is None or not (gid.text or "").strip():
        raise SystemExit("could not read <project><groupId> from root pom.xml")
    return gid.text.strip()


# P1-A Stage 1 后,Controller 按 bounded context 散落到 domain/<ctx>/web/,
# 从 console/ 根目录递归扫所有 *Controller.java(不钉死 web/);
# 包路径由 pom groupId 派生(见 base_package),重命名免改本脚本。
BASE_PACKAGE = base_package()
CONSOLE_ROOT = (
    REPO_ROOT
    / "batch-console-api"
    / "src"
    / "main"
    / "java"
    / Path(*BASE_PACKAGE.split("."))
    / "console"
)
if not CONSOLE_ROOT.is_dir():
    raise SystemExit(
        f"CONSOLE_ROOT 不存在: {CONSOLE_ROOT}\n"
        f"(基础包 {BASE_PACKAGE} 派生路径扫不到 console controllers —— "
        f"pom groupId 与实际包路径不一致?)"
    )

CLASS_MAPPING = re.compile(r"@RequestMapping\s*(?:\((?P<args>.*?)\))?", re.DOTALL)
METHOD_MAPPING = re.compile(
    r"@(?P<ann>RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\s*(?:\((?P<args>.*?)\))?",
    re.DOTALL,
)
CLASS_DECL = re.compile(r"public\s+class\s+Console\w+(?:Controller|RealtimeController)\b")
REQUEST_METHOD_MAPPING = {
    "GetMapping": {"GET"},
    "PostMapping": {"POST"},
    "PutMapping": {"PUT"},
    "DeleteMapping": {"DELETE"},
    "PatchMapping": {"PATCH"},
}
REQUEST_METHOD_RE = re.compile(r"RequestMethod\.(GET|POST|PUT|DELETE|PATCH)")
PATH_ATTRIBUTE_RE = re.compile(r"(?:^|,)\s*(?:value|path)\s*=\s*\"([^\"]*)\"", re.DOTALL)
DIRECT_PATH_RE = re.compile(r"^\s*\"([^\"]*)\"\s*$", re.DOTALL)


def join_paths(base: str, sub: str) -> str:
    base = base.rstrip("/")
    if not sub or sub == "/":
        return base
    if not sub.startswith("/"):
        sub = "/" + sub
    return base + sub


def extract_paths(args: str | None) -> list[str]:
    if args is None:
        return ["/"]
    matches = [match.group(1) for match in PATH_ATTRIBUTE_RE.finditer(args)]
    if matches:
        return matches
    direct = DIRECT_PATH_RE.search(args.strip())
    if direct:
        return [direct.group(1)]
    return ["/"]


def extract_methods(annotation: str, args: str | None) -> set[str]:
    if annotation in REQUEST_METHOD_MAPPING:
        return REQUEST_METHOD_MAPPING[annotation]
    if not args:
        return set()
    return {match.group(1) for match in REQUEST_METHOD_RE.finditer(args)}


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
    for java in sorted(CONSOLE_ROOT.rglob("*Controller.java")):
        text = java.read_text(encoding="utf-8")
        decl = CLASS_DECL.search(text)
        if not decl:
            continue
        prefix = text[: decl.start()]
        m_class = CLASS_MAPPING.search(prefix)
        if not m_class:
            print(f"WARN: no @RequestMapping on {java.relative_to(REPO_ROOT)}", file=sys.stderr)
            continue
        class_paths = extract_paths(m_class.group("args"))
        body = text[decl.start() :]
        for m in METHOD_MAPPING.finditer(body):
            ann = m.group("ann")
            methods = extract_methods(ann, m.group("args"))
            if not methods:
                continue
            method_paths = extract_paths(m.group("args"))
            for base in class_paths:
                for sub in method_paths:
                    full = join_paths(base, sub)
                    for method in methods:
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
        print("In OpenAPI but not in controller code:", file=sys.stderr)
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
