# 用途：校验 SIM 导出场景中 MinIO JSON 列表的记录。
# 用法：由SIM 文件对账入口导入 minio_listing 模块并调用其公共函数。
"""核对基线 SIM 导出使用的 MinIO mc --json 列表记录。"""

import json
import sys


def summarize_listing(lines: str) -> tuple[int, int]:
    """返回对象总数与零字节对象数，排除目录和 .keep 条目。"""
    object_count = 0
    zero_byte_count = 0
    for line_number, line in enumerate(lines.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            entry = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid mc JSON at line {line_number}: {error.msg}") from error
        if entry.get("type") != "file":
            continue
        key = entry.get("key")
        size = entry.get("size")
        if not isinstance(key, str) or not key:
            raise ValueError(f"mc file record at line {line_number} has no object key")
        if key.endswith(".keep"):
            continue
        if isinstance(size, bool) or not isinstance(size, int) or size < 0:
            raise ValueError(f"mc file record at line {line_number} has invalid size")
        object_count += 1
        if size == 0:
            zero_byte_count += 1
    return object_count, zero_byte_count


if __name__ == "__main__":
    try:
        objects, empty = summarize_listing(sys.stdin.read())
    except ValueError as error:
        print(error, file=sys.stderr)
        raise SystemExit(2)
    print(f"{objects}\t{empty}")
