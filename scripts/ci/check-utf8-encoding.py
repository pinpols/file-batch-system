#!/usr/bin/env python3
"""检查仓库文本文件编码为 UTF-8；仅允许登记过的 UTF-8 BOM fixture。"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "UTF8_ENCODING"
GATE_NAME = "仓库文本 UTF-8 编码"
UTF8_BOM = b"\xef\xbb\xbf"
BOM_ALLOWLIST = {"batch-worker/import/src/test/resources/fixtures/import-customers-utf8bom.csv"}
BINARY_SUFFIXES = {
    ".7z", ".a", ".class", ".dylib", ".exe", ".gif", ".gz", ".ico", ".jar",
    ".jpeg", ".jpg", ".mov", ".mp3", ".mp4", ".o", ".pdf", ".png", ".so",
    ".sqlite", ".tar", ".tgz", ".war", ".wasm", ".webp", ".woff", ".woff2",
    ".xls", ".xlsx", ".zip",
}


def encoding_error(path: str, data: bytes) -> str | None:
    if b"\0" in data:
        return "NUL byte in text file; binary files must use a registered binary suffix"
    if data.startswith(UTF8_BOM):
        if path not in BOM_ALLOWLIST:
            return "UTF-8 BOM is not allowed for this file"
        data = data[len(UTF8_BOM) :]
    try:
        data.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        return f"invalid UTF-8 at byte {exc.start}: {exc.reason}"
    return None


def git_paths(staged: bool, base: str | None) -> list[str]:
    if staged:
        command = ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"]
    elif base:
        command = ["git", "diff", f"{base}...HEAD", "--name-only", "--diff-filter=ACMR", "-z"]
    else:
        command = ["git", "ls-files", "-co", "--exclude-standard", "-z"]
    result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True)
    return [item.decode("utf-8", errors="surrogateescape") for item in result.stdout.split(b"\0") if item]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", action="store_true", help="check staged paths only")
    parser.add_argument("--base", help="check changed paths relative to this Git base ref")
    args = parser.parse_args()

    checked = 0
    skipped_binary = 0
    errors: list[tuple[str, str]] = []
    for relative in git_paths(args.staged, args.base):
        path = ROOT / relative
        if not path.is_file() or path.is_symlink():
            continue
        if path.suffix.lower() in BINARY_SUFFIXES:
            skipped_binary += 1
            continue
        data = path.read_bytes()
        checked += 1
        error = encoding_error(relative, data)
        if error:
            errors.append((relative, error))

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for path, error in errors:
            print(f"  - {path}: {error}")
        return 1

    print(
        f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} | text_files={checked} "
        f"| scope={'staged' if args.staged else f'base:{args.base}' if args.base else 'repository'} "
        f"| binary_skipped={skipped_binary} | bom_allowlist={len(BOM_ALLOWLIST)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
