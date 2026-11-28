#!/usr/bin/env python3
# 用途：增量检查新增/修改生产 Java 的冗余 else 与业务层通用异常捕获。
# 用法：在仓库根目录执行 python3 scripts/ci/check-java-control-flow.py [--base origin/main]。
"""控制流可读性增量门禁。

只检查本次 diff 新增的生产 Java 行，已存在代码作为基线不动：

1. then 块以 ``return`` / ``throw`` / ``continue`` / ``break`` 结束时仍写 ``else``；
2. ``application`` / ``domain`` 业务层新增 ``catch (Exception|Throwable)``。

异常处理器、调度器、消费者、Relay、启动审计等边界类允许捕获通用异常；基础设施层不在本规则范围。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "CONTROL_FLOW"
GATE_NAME = "控制流可读性"
TERMINAL = re.compile(r"^(?:return|throw|continue|break)\b")
GENERIC_CATCH = {"Exception", "Throwable", "java.lang.Exception", "java.lang.Throwable"}
ALLOWED_CLASS_SUFFIXES = (
    "ExceptionHandler",
    "Scheduler",
    "Consumer",
    "Relay",
    "Runner",
    "Startup",
    "Audit",
    "Application",
)


def code_mask(text: str) -> str:
    """把注释、字符串、字符与文本块替换为空格，保持长度和行号。"""
    out = list(text)
    i = 0
    n = len(text)
    state = "code"
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                out[i] = out[i + 1] = " "
                state = "line"
                i += 2
                continue
            if ch == "/" and nxt == "*":
                out[i] = out[i + 1] = " "
                state = "block"
                i += 2
                continue
            if ch == '"' and text.startswith('"""', i):
                for k in range(i, min(i + 3, n)):
                    out[k] = " "
                state = "text"
                i += 3
                continue
            if ch == '"':
                out[i] = " "
                state = "string"
                i += 1
                continue
            if ch == "'":
                out[i] = " "
                state = "char"
                i += 1
                continue
            i += 1
            continue
        if state == "line":
            if ch == "\n":
                state = "code"
            else:
                out[i] = " "
            i += 1
            continue
        if state == "block":
            if ch == "*" and nxt == "/":
                out[i] = out[i + 1] = " "
                state = "code"
                i += 2
                continue
            if ch != "\n":
                out[i] = " "
            i += 1
            continue
        if state in ("string", "char"):
            quote = '"' if state == "string" else "'"
            if ch == "\\":
                out[i] = " "
                if i + 1 < n and text[i + 1] != "\n":
                    out[i + 1] = " "
                i += 2
                continue
            if ch == quote:
                out[i] = " "
                state = "code"
            elif ch != "\n":
                out[i] = " "
            i += 1
            continue
        if state == "text":
            if text.startswith('"""', i):
                for k in range(i, min(i + 3, n)):
                    out[k] = " "
                state = "code"
                i += 3
                continue
            if ch != "\n":
                out[i] = " "
            i += 1
            continue
    return "".join(out)


def skip_space(mask: str, index: int) -> int:
    while index < len(mask) and mask[index].isspace():
        index += 1
    return index


def matching(mask: str, start: int, opening: str, closing: str) -> int:
    depth = 0
    for index in range(start, len(mask)):
        if mask[index] == opening:
            depth += 1
        elif mask[index] == closing:
            depth -= 1
            if depth == 0:
                return index
    return -1


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def block_always_exits(mask: str, start: int, end: int) -> bool:
    """保守判断 then 块最后一个顶层语句是否终止。"""
    block = mask[start:end]
    stripped = block.rstrip()
    if not stripped.endswith(";"):
        return False
    position = len(stripped) - 1
    depth = 0
    statement_start = 0
    for index in range(position - 1, -1, -1):
        ch = stripped[index]
        if ch in ")]":
            depth += 1
        elif ch in "([":
            depth -= 1
        elif depth == 0 and ch in ";{}":
            if ch != ";":
                return False
            statement_start = index + 1
            break
    statement = stripped[statement_start:position].strip()
    return bool(TERMINAL.match(statement))


def redundant_else_lines(text: str, mask: str) -> set[int]:
    lines: set[int] = set()
    for match in re.finditer(r"\bif\b", mask):
        index = skip_space(mask, match.end())
        if index >= len(mask) or mask[index] != "(":
            continue
        close = matching(mask, index, "(", ")")
        if close < 0:
            continue
        body = skip_space(mask, close + 1)
        if body >= len(mask):
            continue
        if mask[body] == "{":
            block_end = matching(mask, body, "{", "}")
            if block_end < 0 or not block_always_exits(mask, body + 1, block_end):
                continue
            next_index = block_end + 1
        else:
            semicolon = mask.find(";", body)
            if semicolon < 0:
                continue
            statement = mask[body:semicolon].strip()
            if not TERMINAL.match(statement):
                continue
            next_index = semicolon + 1
        next_index = skip_space(mask, next_index)
        if mask.startswith("else", next_index) and (
            next_index + 4 >= len(mask) or not (mask[next_index + 4].isalnum() or mask[next_index + 4] in "_$")
        ):
            lines.add(line_of(text, next_index))
    return lines


def is_business_path(path: str) -> bool:
    return "/src/main/java/" in path and ("/application/" in path or "/domain/" in path)


def is_allowed_boundary(path: str) -> bool:
    stem = Path(path).stem
    return any(stem.endswith(suffix) for suffix in ALLOWED_CLASS_SUFFIXES)


def generic_catch_lines(path: str, text: str, mask: str) -> set[int]:
    if not is_business_path(path) or is_allowed_boundary(path):
        return set()
    lines: set[int] = set()
    for match in re.finditer(r"\bcatch\b", mask):
        index = skip_space(mask, match.end())
        if index >= len(mask) or mask[index] != "(":
            continue
        close = matching(mask, index, "(", ")")
        if close < 0:
            continue
        caught = re.sub(r"\bfinal\b", "", mask[index + 1 : close]).strip()
        caught_type = re.split(r"\s+", caught, maxsplit=1)[0] if caught else ""
        if caught_type in GENERIC_CATCH:
            lines.add(line_of(text, match.start()))
    return lines


def added_lines(base: str | None) -> dict[str, set[int]]:
    command = ["git", "diff", "--unified=0"]
    command.append(f"{base}...HEAD" if base else "HEAD")
    command.extend(["--", "*.java"])
    diff = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True).stdout
    path: str | None = None
    line = 0
    result: dict[str, set[int]] = {}
    for raw in diff.splitlines():
        if raw.startswith("+++ b/"):
            path = raw[6:]
            continue
        if raw.startswith("@@"):
            found = re.search(r"\+(\d+)", raw)
            line = int(found.group(1)) if found else 0
            continue
        if raw.startswith("+") and not raw.startswith("+++"):
            if path and "/src/main/java/" in path:
                result.setdefault(path, set()).add(line)
            line += 1
        elif not raw.startswith("-") and line:
            line += 1
    if base is None:
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "-z", "--", "*.java"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        for relative in untracked.split("\0"):
            if not relative or "/src/main/java/" not in relative:
                continue
            target = ROOT / relative
            if target.is_file():
                count = len(target.read_text(encoding="utf-8").splitlines())
                result[relative] = set(range(1, count + 1))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", help="比较 base...HEAD；不传时检查 HEAD 到工作树")
    args = parser.parse_args()

    findings: list[tuple[str, int, str]] = []
    for path, lines in sorted(added_lines(args.base).items()):
        target = ROOT / path
        if not target.is_file():
            continue
        text = target.read_text(encoding="utf-8")
        mask = code_mask(text)
        for number in sorted(redundant_else_lines(text, mask) & lines):
            findings.append((path, number, "then 块已终止，else 冗余"))
        for number in sorted(generic_catch_lines(path, text, mask) & lines):
            findings.append((path, number, "业务层捕获 Exception/Throwable"))

    if findings:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1", file=sys.stderr)
        for path, number, message in findings:
            print(f"  - {path}:{number}: {message}", file=sys.stderr)
        return 1

    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
