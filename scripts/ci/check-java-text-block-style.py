#!/usr/bin/env python3
# 用途：Java 文本块格式。
# 用法：在仓库根目录执行 python3 scripts/ci/check-java-text-block-style.py；参数与前置环境见下方说明及所属 README。
"""检查 Java 文本块布局，保持与 Spotless 格式化一致。"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "JAVA_TEXT_BLOCK_STYLE"
GATE_NAME = "Java 文本块格式"


def tracked_java_sources(candidates: list[str] | None = None) -> list[Path]:
    if candidates is not None:
        return [
            ROOT / relative
            for relative in candidates
            if relative.endswith(".java") and (ROOT / relative).is_file()
        ]
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "*.java"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return sorted(
        ROOT / line
        for line in result.stdout.splitlines()
        if line.endswith(".java") and (ROOT / line).is_file()
    )


def closing_delimiter_has_content(line: str) -> bool:
    before_delimiter = line.split('"""', 1)[0]
    return bool(before_delimiter.strip())


def scan_file(path: Path) -> list[str]:
    errors: list[str] = []
    source = path.read_text(encoding="utf-8")
    state = "code"
    start_line = 0
    index = 0
    while index < len(source):
        char = source[index]
        following = source[index : index + 2]
        if state == "line-comment":
            if char == "\n":
                state = "code"
        elif state == "block-comment":
            if following == "*/":
                state = "code"
                index += 2
                continue
        elif state in {"string", "char", "text-block"}:
            if char == "\\":
                index += 2
                continue
            if state == "text-block" and source.startswith('"""', index):
                line_start = source.rfind("\n", 0, index) + 1
                if source[line_start:index].strip():
                    end_line = source.count("\n", 0, index) + 1
                    relative = path.relative_to(ROOT).as_posix()
                    errors.append(
                        f"{relative}:{start_line}-{end_line}: put the closing text block delimiter on its own line"
                    )
                state = "code"
                index += 3
                continue
            if (state == "string" and char == '"') or (state == "char" and char == "'"):
                state = "code"
        elif following == "//":
            state = "line-comment"
            index += 2
            continue
        elif following == "/*":
            state = "block-comment"
            index += 2
            continue
        elif source.startswith('"""', index):
            state = "text-block"
            start_line = source.count("\n", 0, index) + 1
            index += 3
            continue
        elif char == '"':
            state = "string"
        elif char == "'":
            state = "char"
        index += 1
    return errors


def main(argv: list[str] | None = None) -> int:
    candidates = argv if argv else None
    errors: list[str] = []
    for path in tracked_java_sources(candidates):
        errors.extend(scan_file(path))

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
