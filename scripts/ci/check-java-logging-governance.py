#!/usr/bin/env python3
"""Enforce Java console and exception logging conventions."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
STDOUT_WRITE = re.compile(r"\bSystem\s*\.\s*out\b")
STDERR_WRITE = re.compile(r"\bSystem\s*\.\s*err\b")
STACK_TRACE = re.compile(r"\bprintStackTrace\s*\(")
LOGGER_DECLARATION = re.compile(r"\b(?:Logger|ThrottledLogger)\s+([A-Za-z_$][A-Za-z0-9_$]*)")
RAW_EXCEPTION_SUMMARY = re.compile(r"\.\s*(?:getMessage|toString)\s*\(\s*\)")
SWALLOWED_LOGGER_CALL = re.compile(r"SwallowedExceptionLogger\s*\.\s*(?:info|warn|error)\s*\(")
# where 标签口径：稳定、可机器匹配的单行标签，用于日志检索/告警锚点。
# 两种可接受形式 —— `catch:<被捕获的声明类型 SimpleName>` 或 `<kebab-case 动作 id>`。
SWALLOWED_LABEL = re.compile(r"^[A-Za-z][A-Za-z0-9]*(?:[-_:.][A-Za-z0-9]+)*$")
STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
CLI_STDOUT_PREFIX = Path("security-scan/src/main/java/")
GATE_CODE = "JAVA_LOGGING_GOVERNANCE"
GATE_NAME = "Java 日志治理"


def mask_literals_and_comments(source: str) -> str:
    chars = list(source)
    state = "code"
    index = 0
    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""
        if state == "code":
            if char == "/" and next_char == "/":
                chars[index] = chars[index + 1] = " "
                state = "line-comment"
                index += 2
                continue
            if char == "/" and next_char == "*":
                chars[index] = chars[index + 1] = " "
                state = "block-comment"
                index += 2
                continue
            if char == '"':
                chars[index] = " "
                state = "string"
            elif char == "'":
                chars[index] = " "
                state = "char"
        elif state == "line-comment":
            if char == "\n":
                state = "code"
            else:
                chars[index] = " "
        elif state == "block-comment":
            chars[index] = " "
            if char == "*" and next_char == "/":
                chars[index + 1] = " "
                state = "code"
                index += 2
                continue
        else:
            chars[index] = " "
            if char == "\\" and index + 1 < len(source):
                chars[index + 1] = " "
                index += 2
                continue
            if (state == "string" and char == '"') or (state == "char" and char == "'"):
                state = "code"
        index += 1
    return "".join(chars)


def logger_calls(source: str) -> list[str]:
    """Extract balanced logger invocations while ignoring parentheses in literals/comments."""
    calls: list[str] = []
    cursor = 0
    searchable = mask_literals_and_comments(source)
    logger_names = {"log", "logger", "LOG", "LOGGER"}
    logger_names.update(LOGGER_DECLARATION.findall(searchable))
    logger_call = re.compile(
        rf"\b(?:{'|'.join(re.escape(name) for name in sorted(logger_names))})"
        r"\s*\.\s*(?:trace|debug|info|warn|error)\s*\("
    )
    while match := logger_call.search(searchable, cursor):
        opening = match.end() - 1
        depth = 0
        state = "code"
        index = opening
        while index < len(source):
            char = source[index]
            next_char = source[index + 1] if index + 1 < len(source) else ""
            if state == "code":
                if char == "/" and next_char == "/":
                    state = "line-comment"
                    index += 2
                    continue
                if char == "/" and next_char == "*":
                    state = "block-comment"
                    index += 2
                    continue
                if char == '"':
                    state = "string"
                elif char == "'":
                    state = "char"
                elif char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
                    if depth == 0:
                        calls.append(source[match.start() : index + 1])
                        cursor = index + 1
                        break
            elif state == "line-comment":
                if char == "\n":
                    state = "code"
            elif state == "block-comment":
                if char == "*" and next_char == "/":
                    state = "code"
                    index += 2
                    continue
            elif char == "\\":
                index += 2
                continue
            elif (state == "string" and char == '"') or (state == "char" and char == "'"):
                state = "code"
            index += 1
        else:
            cursor = match.end()
    return calls


def raw_exception_summary_count(source: str) -> int:
    return sum(
        bool(RAW_EXCEPTION_SUMMARY.search(mask_literals_and_comments(call)))
        for call in logger_calls(source)
    )


def split_call_arguments(source: str, open_paren: int) -> list[str]:
    """按顶层逗号切出实参，跳过字符串/字符字面量、注释与嵌套括号。

    必须跳过注释：实参区间里夹一行注释（例如写反例说明）时，
    注释里的字符串字面量会被误当成实参内容。
    """
    args: list[str] = []
    current: list[str] = []
    depth = 0
    index = open_paren
    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""
        if char in "\"'":
            quote = char
            current.append(char)
            index += 1
            while index < len(source):
                current.append(source[index])
                if source[index] == "\\":
                    index += 1
                    if index < len(source):
                        current.append(source[index])
                elif source[index] == quote:
                    break
                index += 1
        elif char == "/" and next_char == "/":
            while index < len(source) and source[index] != "\n":
                index += 1
            current.append(" ")
            continue
        elif char == "/" and next_char == "*":
            index += 2
            while index + 1 < len(source) and not (
                source[index] == "*" and source[index + 1] == "/"
            ):
                index += 1
            index += 2
            current.append(" ")
            continue
        elif char in "([{":
            depth += 1
            if depth > 1:
                current.append(char)
        elif char in ")]}":
            depth -= 1
            if depth == 0:
                break
            current.append(char)
        elif char == "," and depth == 1:
            args.append("".join(current))
            current = []
        else:
            current.append(char)
        index += 1
    args.append("".join(current))
    return args


def swallowed_label_violations(source: str) -> list[str]:
    """返回 SwallowedExceptionLogger 第二实参中不合 where 口径的字符串字面量。

    第二实参是常量引用（如 RemoteFilesystemDispatchSupport.LOG_CATCH_EXCEPTION）时，
    调用点没有字面量可校验，此处跳过 —— 常量定义处的字面量同样受本规则约束。
    定位用 mask_literals_and_comments（等长替换，偏移不变），避免注释/字符串里的示例被误报。
    """
    masked = mask_literals_and_comments(source)
    violations: list[str] = []
    for match in SWALLOWED_LOGGER_CALL.finditer(masked):
        arguments = split_call_arguments(source, match.end() - 1)
        if len(arguments) < 2:
            continue
        violations.extend(
            literal
            for literal in STRING_LITERAL.findall(arguments[1])
            if not SWALLOWED_LABEL.match(literal)
        )
    return violations


def java_sources(candidates: list[str] | None = None) -> set[Path]:
    if candidates is not None:
        return {
            ROOT / relative
            for relative in candidates
            if relative.endswith(".java") and (ROOT / relative).is_file()
        }
    java_files: set[Path] = set()
    java_files.update(ROOT.glob("batch-*/**/src/**/*.java"))
    for root in (ROOT / "sdk", ROOT / "security-scan", ROOT / "examples"):
        if root.is_dir():
            java_files.update(root.glob("**/src/**/*.java"))
    return {path for path in java_files if "target" not in path.relative_to(ROOT).parts}


def main(argv: list[str] | None = None) -> int:
    candidates = argv if argv else None
    errors: list[str] = []
    java_files = java_sources(candidates)

    for path in sorted(java_files):
        relative = path.relative_to(ROOT)
        source = path.read_text(encoding="utf-8")
        if STACK_TRACE.search(source):
            errors.append(f"{relative}: do not print exception stacks directly; use the logging policy")
        is_cli_output = relative.as_posix().startswith(CLI_STDOUT_PREFIX.as_posix())
        if STDOUT_WRITE.search(source) and not is_cli_output:
            errors.append(
                f"{relative}: application and test code must not write directly to System.out"
            )
        if STDERR_WRITE.search(source):
            errors.append(f"{relative}: direct System.err writes are prohibited; use the logger")
        if "/src/main/" in relative.as_posix():
            count = raw_exception_summary_count(source)
            if count:
                errors.append(
                    f"{relative}: {count} logger call(s) use Throwable.getMessage()/toString(); "
                    "use SwallowedExceptionLogger.summary() for a safe single-line summary, "
                    "or pass Throwable as the final SLF4J argument"
                )
        for label in swallowed_label_violations(source):
            errors.append(
                f"{relative}: SwallowedExceptionLogger 的 where 标签 {label!r} 不合口径；"
                "应为稳定可检索的单行标签（`catch:<异常类型>` 或 kebab-case 动作 id），"
                "不得含空白/分号/换行，见 docs/design/logging-architecture.md"
            )

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(
        f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} | "
        "reason=console output is limited to the security-scan CLI, exception logs use safe summaries, "
        "and SwallowedExceptionLogger where labels are searchable single-line tokens"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
