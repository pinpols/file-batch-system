#!/usr/bin/env python3
"""阻止新增的结构化多行字符串拼接。"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "JAVA_STRUCTURED_STRING_CONCAT"
GATE_NAME = "Java 结构化多行字符串"
STRING = re.compile(r'"(?:\\.|[^"\\])*"')
STRUCTURED_MARKERS = {
    "JSON": re.compile(r'"[A-Za-z_][A-Za-z0-9_.-]*"\s*:'),
    "XML": re.compile(r"</?[A-Za-z][A-Za-z0-9_.:-]*(?:\s|/?>)"),
    # YAML 映射冒号后要求分隔空白，避免误判 "FOOTER:total=2" 这类定长记录。
    "YAML": re.compile(r"(?m)^\s{0,8}[A-Za-z_][A-Za-z0-9_.-]*:(?:\s+[^:]|\s*$)"),
    "SQL": re.compile(
        r"(?is)\b(?:SELECT\s+.+?\s+FROM|INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM|CREATE\s+(?:TABLE|INDEX|VIEW)|ALTER\s+TABLE)\b"
    ),
    "MARKDOWN": re.compile(r"(?m)^\s{0,3}(?:#{1,6}\s|[-*+]\s|\d+\.\s)"),
    "SCRIPT": re.compile(r"(?m)^\s{0,4}(?:#!/|(?:if|for|while|case|function)\b|(?:set|export)\s+\w+=)"),
    "LUA": re.compile(r"(?m)^\s*(?:local\s+(?:function|[A-Za-z_]\w*\s*=)|function\s+[A-Za-z_]|return\s+)"),
    "PEM": re.compile(r"-----BEGIN [A-Z0-9 ]+-----"),
    "CSV": re.compile(r"(?m)^[^,\r\n]+,[^,\r\n]+,[^,\r\n]+$"),
    "INI": re.compile(r"(?m)^\s*\[[A-Za-z0-9_. -]+\]\s*$"),
    "PROPERTIES": re.compile(r"(?m)^\s*[A-Za-z_][\w.-]*\s*=\s*\S"),
}


@dataclass(frozen=True)
class Finding:
    category: str
    line: int
    signature: str


def mask_non_code(source: str, mask_strings: bool = True) -> str:
    """屏蔽注释和文本块；按需保留普通字符串字面量。"""
    chars = list(source)
    i = 0
    while i < len(source):
        start = i
        if source.startswith("//", i):
            end = source.find("\n", i)
            i = len(source) if end < 0 else end
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = len(source) if end < 0 else end + 2
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = len(source) if end < 0 else end + 3
        elif source[i] == '"':
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == '"':
                    i += 1
                    break
                else:
                    i += 1
        elif source[i] == "'":
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == "'":
                    i += 1
                    break
                else:
                    i += 1
        else:
            i += 1
            continue
        if (
            source.startswith('"""', start)
            or source.startswith("//", start)
            or source.startswith("/*", start)
            or (mask_strings and (source.startswith('"', start) or source.startswith("'", start)))
        ):
            for position in range(start, min(i, len(source))):
                if chars[position] != "\n":
                    chars[position] = " "
    return "".join(chars)


def candidate_groups(source: str) -> list[tuple[int, int, str]]:
    """查找源代码中跨多行拼接普通字符串的片段。"""
    code = mask_non_code(source)
    strings_visible = mask_non_code(source, mask_strings=False)
    lines = source.splitlines(keepends=True)
    offsets = []
    cursor = 0
    for line in lines:
        offsets.append(cursor)
        cursor += len(line)

    active: list[int] = []
    groups: list[tuple[int, int, str]] = []

    def finish_group() -> None:
        if len(active) >= 3:
            first, last = active[0], active[-1]
            left, right = offsets[first], offsets[last] + len(lines[last])
            groups.append((first + 1, last + 1, source[left:right]))

    for index, line in enumerate(lines):
        start, end = offsets[index], offsets[index] + len(line)
        code_line = code[start:end]
        has_string = bool(STRING.search(strings_visible[start:end]))
        stripped_code = code_line.strip()
        starts_with_plus = stripped_code.startswith("+")
        ends_with_plus = stripped_code.endswith("+")
        previous_continues = bool(active) and code[
            offsets[index - 1] : offsets[index]
        ].rstrip().endswith("+")
        if (not active and has_string and (starts_with_plus or ends_with_plus)) or (
            active and (starts_with_plus or ends_with_plus or (has_string and previous_continues))
        ):
            active.append(index)
            if code_line.rstrip().endswith(";"):
                finish_group()
                active = []
            continue
        if active:
            finish_group()
            active = []
    if active and len(active) >= 3:
        finish_group()
    return groups


def normalize(source: str) -> str:
    return re.sub(r"\s+", " ", source).strip()


def scan_source(source: str) -> list[Finding]:
    findings = []
    for start_line, _, expression in candidate_groups(source):
        fragments = STRING.findall(mask_non_code(expression, mask_strings=False))
        logical_text = "".join(fragment[1:-1] for fragment in fragments)
        logical_text = (
            logical_text.replace(r"\n", "\n")
            .replace(r"\r", "\r")
            .replace(r"\t", "\t")
            .replace(r'\"', '"')
            .replace(r"\\", "\\")
        )
        for category, marker in STRUCTURED_MARKERS.items():
            multiline_categories = {
                "CSV", "PROPERTIES", "INI", "MARKDOWN", "SCRIPT", "LUA", "YAML"
            }
            if category in multiline_categories and "\n" not in logical_text:
                continue
            if marker.search(logical_text):
                findings.append(Finding(category, start_line, normalize(expression)))
                break
    return findings


def added_findings(current_source: str, baseline_source: str) -> list[Finding]:
    current_findings = scan_source(current_source)
    current = Counter((finding.category, finding.signature) for finding in current_findings)
    baseline = Counter(
        (finding.category, finding.signature) for finding in scan_source(baseline_source)
    )
    added = []
    for finding in current_findings:
        key = (finding.category, finding.signature)
        if current[key] > baseline[key]:
            added.append(finding)
            current[key] -= 1
    return added


def git_text(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=ROOT, check=True, capture_output=True, text=True
    )
    return result.stdout


def changed_java_paths(base_ref: str | None, files: list[str]) -> list[str]:
    if files:
        return sorted({path for path in files if path.endswith(".java")})
    if base_ref:
        return [
            path for path in git_text("diff", "--name-only", f"{base_ref}...HEAD").splitlines()
            if path.endswith(".java")
        ]
    return [
        path
        for path in git_text("ls-files", "--", "*.java").splitlines()
        if path.endswith(".java")
    ]


def source_at_ref(ref: str, path: str) -> str | None:
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    return result.stdout if result.returncode == 0 else None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="*", help="可选的 Java 文件列表")
    parser.add_argument("--base-ref", help="只阻止相对该 ref 新增的候选；默认对比 HEAD")
    args = parser.parse_args(argv)
    baseline_ref = args.base_ref or "HEAD"
    paths = changed_java_paths(args.base_ref, args.files)
    findings: list[tuple[str, Finding]] = []

    for relative in paths:
        path = ROOT / relative
        if not path.is_file():
            continue
        current_source = path.read_text(encoding="utf-8")
        baseline_source = source_at_ref(baseline_ref, relative)
        for finding in added_findings(current_source, baseline_source or ""):
            findings.append((relative, finding))

    if findings:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for path, finding in findings:
            print(
                f"  - {path}:{finding.line}: 新增 {finding.category} 多行字符串拼接；"
                "改用 text block，动态值优先使用 .formatted() 或序列化器"
            )
        return 1

    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
