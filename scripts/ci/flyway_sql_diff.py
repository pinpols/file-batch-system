# 用途：比较 Flyway 迁移的有效 SQL，识别纯注释及对象说明变更。
# 用法：在仓库根目录执行 python3 scripts/ci/flyway_sql_diff.py BASE_REF MIGRATION_PATH [MIGRATION_PATH ...]。
"""比较 Flyway 迁移，移除 SQL 注释和 COMMENT ON 元数据后检查有效语句。"""

from __future__ import annotations

import io
import re
import subprocess
import sys
from pathlib import Path


def append_separator(statement: list[str]) -> None:
    """在语句文本间保留一个分隔符，不改写引号或 dollar quote 内部。"""
    if statement and not statement[-1].isspace():
        statement.append(" ")


def executable_statements(sql: str) -> list[str]:
    """返回去除普通注释和 COMMENT ON 元数据后的规范化 SQL 语句。"""
    statements: list[str] = []
    statement: list[str] = []
    index = 0
    state = "normal"
    dollar_tag = ""
    block_depth = 0
    escape_string = False

    while index < len(sql):
        character = sql[index]
        following = sql[index + 1] if index + 1 < len(sql) else ""

        if state == "line_comment":
            if character == "\n":
                state = "normal"
                append_separator(statement)
            index += 1
            continue

        if state == "block_comment":
            if character == "/" and following == "*":
                block_depth += 1
                index += 2
                continue
            if character == "*" and following == "/":
                block_depth -= 1
                index += 2
                if block_depth == 0:
                    state = "normal"
                    append_separator(statement)
                continue
            index += 1
            continue

        if state == "single_quote":
            statement.append(character)
            index += 1
            if escape_string and character == "\\" and index < len(sql):
                statement.append(sql[index])
                index += 1
                continue
            if character == "'":
                if index < len(sql) and sql[index] == "'":
                    statement.append(sql[index])
                    index += 1
                else:
                    state = "normal"
            continue

        if state == "double_quote":
            statement.append(character)
            index += 1
            if character == '"':
                if index < len(sql) and sql[index] == '"':
                    statement.append(sql[index])
                    index += 1
                else:
                    state = "normal"
            continue

        if state == "dollar_quote":
            if sql.startswith(dollar_tag, index):
                statement.append(dollar_tag)
                index += len(dollar_tag)
                state = "normal"
            else:
                statement.append(character)
                index += 1
            continue

        if character == "-" and following == "-":
            state = "line_comment"
            index += 2
            continue
        if character == "/" and following == "*":
            state = "block_comment"
            block_depth = 1
            index += 2
            continue
        if character == "'":
            escape_string = (
                index > 0
                and sql[index - 1] in "eE"
                and (
                    index < 2 or not (sql[index - 2].isalnum() or sql[index - 2] == "_")
                )
            )
            state = "single_quote"
            statement.append(character)
            index += 1
            continue
        if character == '"':
            state = "double_quote"
            statement.append(character)
            index += 1
            continue
        if character == "$":
            match = re.match(r"\$[A-Za-z_0-9]*\$", sql[index:])
            if match:
                dollar_tag = match.group()
                state = "dollar_quote"
                statement.append(dollar_tag)
                index += len(dollar_tag)
                continue
        if character == ";":
            normalized = "".join(statement).strip()
            if normalized and not re.match(r"(?is)^COMMENT\s+ON\b", normalized):
                statements.append(normalized)
            statement.clear()
            index += 1
            continue

        if character.isspace():
            append_separator(statement)
        else:
            statement.append(character)
        index += 1

    normalized = "".join(statement).strip()
    if normalized and not re.match(r"(?is)^COMMENT\s+ON\b", normalized):
        statements.append(normalized)
    return statements


def is_comment_only_change(base_sql: str, changed_sql: str) -> bool:
    return executable_statements(base_sql) == executable_statements(changed_sql)


def load_base_files(base_ref: str, migration_paths: list[str]) -> dict[str, str | None]:
    request = "".join(f"{base_ref}:{path}\n" for path in migration_paths)
    result = subprocess.run(
        ["git", "cat-file", "--batch"],
        input=request.encode("utf-8"),
        check=True,
        capture_output=True,
    )
    output = io.BytesIO(result.stdout)
    base_files: dict[str, str | None] = {}

    for path in migration_paths:
        header = output.readline().decode("utf-8").strip().split()
        if header and header[-1] == "missing":
            base_files[path] = None
            continue
        if len(header) != 3 or header[1] != "blob":
            raise ValueError(f"unexpected git cat-file response for {path}: {header}")
        size = int(header[2])
        blob = output.read(size)
        if len(blob) != size or output.read(1) != b"\n":
            raise ValueError(f"incomplete git cat-file response for {path}")
        base_files[path] = blob.decode("utf-8")
    return base_files


def main() -> int:
    if len(sys.argv) < 3:
        print(
            "用法：flyway_sql_diff.py BASE_REF MIGRATION_PATH [MIGRATION_PATH ...]",
            file=sys.stderr,
        )
        return 2

    base_ref, *migration_paths = sys.argv[1:]
    try:
        base_files = load_base_files(base_ref, migration_paths)
        for migration_path in migration_paths:
            changed_sql = (
                Path(migration_path).read_text(encoding="utf-8")
                if Path(migration_path).exists()
                else ""
            )
            base_sql = base_files[migration_path]
            if base_sql is None:
                print(f"NEW\t{migration_path}")
            elif is_comment_only_change(base_sql, changed_sql):
                print(f"COMMENT\t{migration_path}")
            else:
                print(f"SQL\t{migration_path}")
    except (OSError, UnicodeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"无法安全比较迁移文件：{error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
