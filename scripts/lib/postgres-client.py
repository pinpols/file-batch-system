#!/usr/bin/env python3
"""为运维和压测脚本提供一个最小的 psql 兼容执行入口。"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("-h", dest="host")
    parser.add_argument("-p", dest="port")
    parser.add_argument("-U", dest="user")
    parser.add_argument("-d", dest="database")
    parser.add_argument("-f", dest="file")
    parser.add_argument("-c", dest="command")
    parser.add_argument("-v", dest="variables", action="append", default=[])
    parser.add_argument("-t", action="store_true")
    parser.add_argument("-A", action="store_true")
    parser.add_argument("-X", action="store_true")
    parser.add_argument("-P", action="append", default=[])
    return parser.parse_known_args()[0]


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def sql_identifier(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def expand_variables(sql: str, variables: dict[str, str]) -> str:
    def quoted_identifier(match: re.Match[str]) -> str:
        return sql_identifier(variables.get(match.group(1), match.group(1)))

    def quoted_value(match: re.Match[str]) -> str:
        return sql_literal(variables.get(match.group(1), match.group(1)))

    def value(match: re.Match[str]) -> str:
        return sql_literal(variables.get(match.group(1), match.group(1)))

    sql = re.sub(r':"([A-Za-z_][A-Za-z0-9_]*)"', quoted_identifier, sql)
    sql = re.sub(r":'([A-Za-z_][A-Za-z0-9_]*)'", quoted_value, sql)
    # 避免把 PostgreSQL 类型转换符 ::date 的第二个冒号误认为变量。
    return re.sub(r"(?<!:):([A-Za-z_][A-Za-z0-9_]*)", value, sql)


def main() -> int:
    try:
        import psycopg
    except ImportError:
        print(
            "PostgreSQL Python fallback requires psycopg; install with "
            "python3 -m pip install 'psycopg[binary]>=3.2,<4' or use psql/docker",
            file=sys.stderr,
        )
        return 127

    args = parse_args()
    variables = dict(item.split("=", 1) for item in args.variables if "=" in item)
    if args.file:
        sql = Path(args.file).read_text(encoding="utf-8")
    elif args.command is not None:
        sql = args.command
    else:
        sql = sys.stdin.read()
    sql = expand_variables(sql, variables)

    connection = psycopg.connect(
        host=args.host or os.getenv("PGHOST", "localhost"),
        port=args.port or os.getenv("PGPORT", "5432"),
        user=args.user or os.getenv("PGUSER"),
        password=os.getenv("PGPASSWORD"),
        dbname=args.database or os.getenv("PGDATABASE"),
        autocommit=True,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(sql)
            if cursor.description:
                for row in cursor.fetchall():
                    print("|".join("" if value is None else str(value) for value in row))
    finally:
        connection.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
