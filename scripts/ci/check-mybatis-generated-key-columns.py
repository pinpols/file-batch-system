#!/usr/bin/env python3
"""守护 MyBatis 自增主键只向 PostgreSQL 请求实际需要的列。

PostgreSQL JDBC 在 ``useGeneratedKeys=true`` 且未声明 ``keyColumn`` 时，会把
``RETURNING *`` 附加到 INSERT。控制面热路径中的 JSONB、错误详情和运行快照因此会在
每次插入后整行回传并由 MyBatis 解码，既没有业务价值，也放大数据库与 JVM 压力。

本仓库的自增键统一为 ``id``。所有启用 generated keys 的 INSERT 必须显式声明
``keyColumn=\"id\"``；``keyProperty`` 仍可使用 ``id``、``p.id`` 等参数对象路径。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


GENERATED_KEY_INSERT = re.compile(
    r"<insert\b(?=[^>]*\buseGeneratedKeys=\"true\")[^>]*>",
    re.IGNORECASE | re.DOTALL,
)
ID_KEY_COLUMN = re.compile(r"\bkeyColumn=\"id\"", re.IGNORECASE)


def mapper_files(root: Path, candidates: list[str] | None = None) -> list[Path]:
    if candidates is not None:
        return [
            root / relative
            for relative in candidates
            if relative.endswith("Mapper.xml") and (root / relative).is_file()
        ]
    return [
        mapper
        for mapper in root.rglob("*Mapper.xml")
        if "target" not in mapper.parts
    ]


def main(argv: list[str] | None = None) -> int:
    root = Path(__file__).resolve().parents[2]
    offenders: list[str] = []
    candidates = argv if argv else None

    for mapper in mapper_files(root, candidates):
        content = mapper.read_text(encoding="utf-8", errors="replace")
        for match in GENERATED_KEY_INSERT.finditer(content):
            if ID_KEY_COLUMN.search(match.group(0)):
                continue
            line = content.count("\n", 0, match.start()) + 1
            tag = " ".join(match.group(0).split())
            offenders.append(f"{mapper.relative_to(root)}:{line}: {tag}")

    if offenders:
        print(
            "MyBatis generated-key INSERT must declare keyColumn=\"id\"; "
            "otherwise PostgreSQL JDBC returns the complete row:",
            file=sys.stderr,
        )
        for offender in offenders:
            print(f"  {offender}", file=sys.stderr)
        return 1

    print("MyBatis generated-key INSERTs return only the id column")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
