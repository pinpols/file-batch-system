#!/usr/bin/env python3
"""检查新增的纯英文说明性注释。"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORD = re.compile(r"[A-Za-z]{2,}")
HAN = re.compile(r"[\u3400-\u9fff]")
ENGLISH_HINT = re.compile(
    r"\b(?:the|a|an|is|are|was|were|to|for|when|if|only|should|must|this|that|"
    r"with|from|into|because|before|after|while|until|each|all|not|does|do|can|"
    r"will|may|use|used|using|ensure|avoid|keep|return|value|method|request|response|"
    r"and|as|on|of|by|add|create|persist|track|support|allow|prevent|store|align|"
    r"extend|delete|update|record|let|lets|without|must|only|query|replay|table|rows)\b",
    re.IGNORECASE,
)
DIRECTIVE = re.compile(
    r"^(?:shellcheck\b|noqa\b|type:\s*ignore\b|pylint:\b|eslint\b|prettier\b|"
    r"noinspection\b|go:(?:build|generate|embed)\b|flyway:|sqlfluff:|SPDX-License-Identifier\b)",
    re.IGNORECASE,
)
LICENSE = re.compile(r"^(?:copyright\b|all rights reserved\b|licensed under the\b|apache license\b)", re.IGNORECASE)
SQL_EXAMPLE = re.compile(r"^(?:select|from|where|insert|update|delete|alter|create|drop|group by|order by)\b", re.IGNORECASE)
CODE_EXAMPLE = re.compile(
    r"^(?:bash|sh|python|python3|mvn|make|docker|git|psql|curl|source|export|"
    r"scripts/|\./|~/|/|-{1,2}[a-z][a-z0-9-]*\b|[A-Z][A-Z0-9_]*=|"
    r"[a-z0-9_.-]+\.(?:sh|py|sql)\b)|"
    r"\bON CONFLICT\b|\bLIKE\s+batch\.|\b[A-Za-z][A-Za-z0-9_]*\s*[=<>]|\{[^}]*\}",
)
SUPPORTED = {
    ".java", ".go", ".py", ".rs", ".ts", ".tsx", ".js", ".jsx", ".sh",
    ".sql", ".yml", ".yaml", ".xml", ".properties", ".toml",
}


def prose_comment(line: str, suffix: str) -> str | None:
    """Return comment prose when the line is a full-line English comment."""
    stripped = line.lstrip()
    if stripped.startswith("#!"):
        return None
    markers = {
        ".java": ("//", "/*", "*"), ".go": ("//", "/*", "*"),
        ".rs": ("//", "/*", "*"), ".ts": ("//", "/*", "*"),
        ".tsx": ("//", "/*", "*"), ".js": ("//", "/*", "*"),
        ".jsx": ("//", "/*", "*"), ".py": ("#",), ".sh": ("#",),
        ".sql": ("--",), ".yml": ("#",), ".yaml": ("#",),
        ".properties": ("#", "!"), ".toml": ("#",), ".xml": ("<!--",),
    }.get(suffix.lower(), ())
    marker = next((value for value in markers if stripped.startswith(value)), None)
    if marker is None:
        return None
    text = stripped[len(marker):].strip()
    text = re.sub(r"(?:\*/|-->)\s*$", "", text).strip()
    text = text.lstrip("*").strip()
    if not text or HAN.search(text) or DIRECTIVE.match(text) or LICENSE.match(text):
        return None
    if SQL_EXAMPLE.match(text) or re.fullmatch(r"[A-Z0-9_./:= -]+", text):
        return None
    if suffix.lower() == ".sql" and (
        re.match(r"^(?:AND|OR)\s+", text, re.IGNORECASE)
        or re.match(r"^[A-Za-z_][\w.]*\s{2,}[A-Z]", text)
        or re.match(r"^scope:\s*[A-Z0-9_ /,.-]+$", text)
        or re.search(r"\b(?:BOOLEAN|VARCHAR|JSONB|INTEGER|TEXT|DATE|TIMESTAMPTZ)\b", text)
        or re.match(r"^(?:ON CONFLICT\b|LIKE\s+batch\.)", text, re.IGNORECASE)
        or re.match(r"^[\w.]+\s*[<>=]+\s*[\w.]+(?:\s*[<>=]+\s*[\w.]+)*$", text)
        or "->" in text
        or "<=>" in text
        or text.startswith("`")
        or re.match(r"^[A-Z][A-Z0-9_]*(?:\s+[A-Z][A-Z0-9_]*)+\s*$", text)
    ):
        return None
    if re.match(r"^(?:docs/|scripts/|helm/|deploy/|\.github/|[A-Za-z0-9_.-]+\.(?:ya?ml|json|sql|sh|py))", text):
        return None
    if re.fullmatch(r"[A-Za-z0-9_.-]+\s+\([^)]*\)", text):
        return None
    words = WORD.findall(text)
    if "_" in text and not ENGLISH_HINT.search(text):
        return None
    if CODE_EXAMPLE.search(text):
        return None
    if len(words) >= 4 and ENGLISH_HINT.search(text):
        return text
    if len(words) >= 3 and ENGLISH_HINT.search(text):
        return text
    return None


def parse_diff(diff: str) -> list[tuple[str, int, str]]:
    findings: list[tuple[str, int, str]] = []
    path = ""
    line_number = 0
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
            continue
        if line.startswith("@@"):
            match = re.search(r"\+(\d+)(?:,\d+)?", line)
            if match:
                line_number = int(match.group(1)) - 1
            continue
        if line.startswith("+") and not line.startswith("+++"):
            line_number += 1
            if Path(path).suffix.lower() in SUPPORTED:
                prose = prose_comment(line[1:], Path(path).suffix.lower())
                if prose:
                    findings.append((path, line_number, prose))
        elif not line.startswith("-"):
            line_number += 1
    return findings


def get_diff(staged: bool, base_ref: str | None) -> str:
    command = ["git", "diff", "--unified=0"]
    if staged:
        command.append("--cached")
    elif base_ref:
        command.extend([f"{base_ref}...HEAD"])
    else:
        raise ValueError("one of --staged or --base-ref is required")
    command.extend(["--", "*.java", "*.go", "*.py", "*.rs", "*.ts", "*.tsx", "*.js", "*.jsx", "*.sh", "*.sql", "*.yml", "*.yaml", "*.xml", "*.properties", "*.toml"])
    result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
    return result.stdout


def scan_paths(paths: list[str]) -> list[tuple[str, int, str]]:
    listed = subprocess.run(
        ["git", "ls-files", "-z", "--", *paths],
        cwd=ROOT,
        check=True,
        capture_output=True,
    ).stdout.split(b"\0")
    findings: list[tuple[str, int, str]] = []
    for raw_path in listed:
        if not raw_path:
            continue
        path = raw_path.decode("utf-8")
        if Path(path).suffix.lower() not in SUPPORTED:
            continue
        for number, line in enumerate((ROOT / path).read_text(encoding="utf-8").splitlines(), 1):
            prose = prose_comment(line, Path(path).suffix.lower())
            if prose:
                findings.append((path, number, prose))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--staged", action="store_true", help="检查暂存区新增行")
    source.add_argument("--base-ref", help="检查相对基线新增行")
    source.add_argument("--paths", nargs="+", help="扫描指定路径下的全部现存注释")
    args = parser.parse_args()
    findings = scan_paths(args.paths) if args.paths else parse_diff(get_diff(args.staged, args.base_ref))
    if findings:
        print("❌ 不通过 | code=COMMENT_LANGUAGE | gate=注释语言规范 | exit_code=1")
        for path, line, text in findings:
            print(f"  {path}:{line}: {text}")
        print("说明性注释请使用简体中文；机器指令、标准许可证声明和代码示例除外。")
        return 1
    print("✅ 通过 | code=COMMENT_LANGUAGE | gate=注释语言规范")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
