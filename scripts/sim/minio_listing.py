"""Validate MinIO mc --json listing records for baseline SIM exports."""

import json
import sys


def summarize_listing(lines: str) -> tuple[int, int]:
    """Return (object_count, zero_byte_count), excluding folder and .keep entries."""
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
