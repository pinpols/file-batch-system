#!/usr/bin/env python3
"""校验当前文档目录入口、仓库内链接和禁止提交的本机产物。"""

from __future__ import annotations

import html
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
LINK = re.compile(r"!?\[[^\]]*]\(([^)]+)\)")
SKIP_SCHEMES = ("http://", "https://", "mailto:", "codex:", "#")
INDEX_EXEMPT = {"archive", "test-data"}
HEADING = re.compile(r"^#{1,6}\s+(.+?)\s*$")


def tracked_docs() -> list[Path]:
    output = subprocess.check_output(["git", "ls-files", "docs"], cwd=ROOT, text=True)
    return [ROOT / line for line in output.splitlines() if line]


def versionable_paths() -> set[Path]:
    output = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
        cwd=ROOT,
        text=True,
    )
    return {(ROOT / line).resolve() for line in output.splitlines() if line}


def markdown_anchors(document: Path) -> set[str]:
    anchors: set[str] = set()
    duplicates: dict[str, int] = {}
    for line in document.read_text(encoding="utf-8", errors="replace").splitlines():
        anchors.update(re.findall(r'<a\s+(?:name|id)=["\']([^"\']+)["\']', line, re.IGNORECASE))
        match = HEADING.match(line)
        if not match:
            continue
        heading = re.sub(r"\s+#+\s*$", "", match.group(1).strip())
        heading = re.sub(r"<[^>]+>", "", heading)
        heading = re.sub(r"[`*_~]", "", heading)
        heading = html.unescape(heading).lower()
        base = re.sub(r"[^\w\-\s]", "", heading, flags=re.UNICODE)
        base = re.sub(r"\s", "-", base)
        duplicate = duplicates.get(base, 0)
        duplicates[base] = duplicate + 1
        anchors.add(base if duplicate == 0 else f"{base}-{duplicate}")
    return anchors


def check_directory_indexes(errors: list[str]) -> None:
    root_index = (DOCS / "README.md").read_text(encoding="utf-8")
    for directory in sorted(path for path in DOCS.iterdir() if path.is_dir()):
        if directory.name not in INDEX_EXEMPT and not (directory / "README.md").is_file():
            errors.append(f"missing directory index: {directory.relative_to(ROOT)}/README.md")
        if directory.name not in root_index:
            errors.append(f"docs/README.md does not mention directory: {directory.name}/")
        index = directory / "README.md"
        if not index.is_file():
            continue
        index_text = index.read_text(encoding="utf-8")
        for document in sorted(directory.glob("*.md")):
            if document.name != "README.md" and document.name not in index_text:
                errors.append(
                    f"directory index does not mention document: {document.relative_to(ROOT)}"
                )


def check_internal_links(errors: list[str]) -> None:
    known_paths = versionable_paths()
    anchor_cache: dict[Path, set[str]] = {}
    documents = [ROOT / "README.md", *sorted(DOCS.rglob("*.md"))]
    for document in documents:
        if "archive" in document.relative_to(ROOT).parts:
            continue
        for line_number, line in enumerate(
            document.read_text(encoding="utf-8", errors="replace").splitlines(), 1
        ):
            for match in LINK.finditer(line):
                raw_target = match.group(1).strip()
                if raw_target.startswith("<") and raw_target.endswith(">"):
                    raw_target = raw_target[1:-1]
                raw_target = raw_target.split(maxsplit=1)[0]
                if not raw_target or raw_target.startswith(SKIP_SCHEMES):
                    continue
                target, _, fragment = raw_target.partition("#")
                target = unquote(target)
                fragment = unquote(fragment)
                if not target:
                    continue
                resolved = (document.parent / target).resolve()
                if not resolved.is_relative_to(ROOT):
                    continue
                if not resolved.exists():
                    errors.append(f"broken link: {document.relative_to(ROOT)}:{line_number}: {raw_target}")
                    continue
                if resolved.is_relative_to(ROOT) and resolved.is_file() and resolved not in known_paths:
                    errors.append(
                        f"link target is ignored or not versionable: "
                        f"{document.relative_to(ROOT)}:{line_number}: {raw_target}"
                    )
                    continue
                if fragment and resolved.is_file() and resolved.suffix == ".md":
                    anchors = anchor_cache.setdefault(resolved, markdown_anchors(resolved))
                    if fragment not in anchors:
                        errors.append(
                            f"missing markdown anchor: "
                            f"{document.relative_to(ROOT)}:{line_number}: {raw_target}"
                        )


def check_residual_files(errors: list[str]) -> None:
    for path in tracked_docs():
        if path.name == ".DS_Store":
            errors.append(f"tracked Finder metadata: {path.relative_to(ROOT)}")
        if (
            path.parent == DOCS / "backlog"
            and re.fullmatch(r"be-acceptance-\d{4}-\d{2}-\d{2}\.md", path.name)
        ):
            errors.append(f"tracked generated acceptance report: {path.relative_to(ROOT)}")


def main() -> int:
    errors: list[str] = []
    check_directory_indexes(errors)
    check_internal_links(errors)
    check_residual_files(errors)
    if errors:
        print("Document structure guard failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print("Document structure guard passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
