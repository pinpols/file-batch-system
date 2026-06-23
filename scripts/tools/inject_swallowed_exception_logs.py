#!/usr/bin/env python3
"""
Inject SwallowedExceptionLogger calls into catch blocks that neither throw nor log.
Run from repo root: python3 scripts/tools/inject_swallowed_exception_logs.py
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

catch_start = re.compile(r"}\s*catch\s*\(")
has_log = re.compile(
    r"\b(log\.(trace|debug|info|warn|error)\(|logger\.(trace|debug|info|warn|error)\()",
    re.I,
)
has_throw = re.compile(r"\bthrow\b")

SKIP_DIRS = {"/target/", "/node_modules/"}

EXPECTED_TYPES = (
    "NumberFormatException",
    "DateTimeParseException",
    "IllegalArgumentException",
    "NoSuchMessageException",
    "ReflectiveOperationException",
    "DuplicateKeyException",
    "UnsupportedOperationException",
    "InterruptedException",
    "IllegalStateException",  # some SSE paths; script picks info vs warn by heuristic below
)

WARN_HINT_TYPES = ("RuntimeException", "Exception", "IOException", "SQLException")


def strip_strings_comments(text: str) -> str:
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            i = text.find("\n", i)
            if i < 0:
                break
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j < 0:
                break
            i = j + 2
            continue
        c = text[i]
        if c in "\"'":
            delim = c
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == delim:
                    i += 1
                    break
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def top_level_class_name(path: str) -> str:
    base = os.path.basename(path)
    if base.endswith(".java"):
        return base[:-5]
    return "Unknown"


def should_use_warn(exc_type_simple: str) -> bool:
    if exc_type_simple in WARN_HINT_TYPES:
        return True
    if exc_type_simple in EXPECTED_TYPES:
        return False
    # Unknown/simple heuristic
    return False


def pick_where(exc_type_simple: str) -> str:
    return f"catch:{exc_type_simple}"


def ensure_import(text: str) -> str:
    needle = "io.github.pinpols.batch.common.logging.SwallowedExceptionLogger"
    if needle in text:
        return text
    lines = text.splitlines(keepends=True)
    insert_at = 0
    last_import = -1
    for i, ln in enumerate(lines):
        if ln.startswith("import "):
            last_import = i
            insert_at = i + 1
        elif ln.startswith("package "):
            insert_at = max(insert_at, i + 1)
    imp = f"import {needle};\n"
    if last_import >= 0:
        lines.insert(insert_at, imp)
    else:
        # After package
        lines.insert(insert_at, "\n" + imp)
    return "".join(lines)


def scan_transform(text: str, outer_class: str) -> tuple[str, int]:
    """Returns (new_text, num_changes)."""
    category_literal = outer_class + ".class"
    changes = 0
    i = 0
    result_parts: list[str] = []
    while True:
        m = catch_start.search(text, i)
        if not m:
            result_parts.append(text[i:])
            break
        result_parts.append(text[i : m.start()])
        j = text.find("(", m.start())
        depth = 0
        p = j
        while p < len(text):
            c = text[p]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            p += 1
        if p >= len(text):
            break
        param_clause = text[j + 1 : p].strip()
        p += 1
        while p < len(text) and text[p].isspace():
            p += 1
        if p >= len(text) or text[p] != "{":
            i = m.end()
            continue
        body_open = p
        body_start = p + 1
        depth = 1
        q = body_start
        while q < len(text) and depth:
            c = text[q]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            elif c in "\"'":
                delim = c
                q += 1
                while q < len(text):
                    if text[q] == "\\":
                        q += 2
                        continue
                    if text[q] == delim:
                        break
                    q += 1
            q += 1
        body = text[body_start : q - 1]
        scrubbed = strip_strings_comments(body)
        if has_throw.search(scrubbed) or has_log.search(scrubbed):
            result_parts.append(text[m.start() : q])
            i = q
            continue

        # parse simple Java exception declaration "FQCN param"
        parts = param_clause.rsplit(None, 1)
        if len(parts) != 2:
            result_parts.append(text[m.start() : q])
            i = q
            continue
        exc_decl = parts[0].strip()
        param_name = parts[1].strip()
        exc_simple = exc_decl.split(".")[-1]

        if "|" in param_clause or exc_simple.strip() == "":
            result_parts.append(text[m.start() : q])
            i = q
            continue

        method = "warn" if should_use_warn(exc_simple) else "info"
        where = pick_where(exc_simple)

        catch_kw = text.find("catch", m.start())

        trace_call = (
            f"\n    SwallowedExceptionLogger.{method}"
            f"({category_literal}, \"{where}\", {param_name});\n    "
        )

        injected = (
            text[m.start() : catch_kw]
            + text[catch_kw : body_open + 1]
            + trace_call
            + body
            + text[q - 1 : q]
        )
        result_parts.append(injected)
        changes += 1
        i = q

    new_text = "".join(result_parts)
    return new_text, changes


def main() -> int:
    changed_files = 0
    total_snippets = 0
    for dirpath, _, files in os.walk(ROOT):
        if any(s in dirpath for s in SKIP_DIRS):
            continue
        if "/src/main/java/" not in dirpath:
            continue
        if "/security-scan/" in dirpath:
            continue
        for fn in files:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                text = open(path, encoding="utf-8").read()
            except OSError:
                continue
            outer = top_level_class_name(path)
            new_text, n = scan_transform(text, outer)
            if n == 0:
                continue
            new_text = ensure_import(new_text)
            open(path, "w", encoding="utf-8").write(new_text)
            rel = path[len(ROOT) + 1 :]
            print(f"{rel}: +{n} catch log(s)")
            changed_files += 1
            total_snippets += n
    print(f"Done. Files={changed_files}, injections={total_snippets}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
