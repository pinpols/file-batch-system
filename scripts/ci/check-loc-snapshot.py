#!/usr/bin/env python3
"""Verify that the current lean LOC snapshot matches the tracked source tree."""

from __future__ import annotations

import pathlib
import re
import subprocess
import sys
import tempfile


ROOT = pathlib.Path(__file__).resolve().parents[2]
STATS_DIR = ROOT / "docs" / "stats"
CURRENT_SNAPSHOT = STATS_DIR / "loc-current-lean.md"
SOURCE_RE = re.compile(r"生成来源：HEAD `[^`]+`(?: \+ 当前工作区改动)?")


def fail(message: str) -> int:
    print(f"❌ {message}", file=sys.stderr)
    return 1


def normalize_report(text: str) -> str:
    return SOURCE_RE.sub("生成来源：HEAD `<normalized>`", text).strip() + "\n"


def run_generator(output: pathlib.Path) -> None:
    subprocess.run(
        ["python3", "scripts/dev/lean-loc-report.py", "--write", str(output)],
        cwd=ROOT,
        check=True,
        stdout=subprocess.DEVNULL,
    )


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


def main() -> int:
    snapshot = CURRENT_SNAPSHOT
    if not snapshot.is_file():
        return fail("docs/stats 缺少 loc-current-lean.md 当前代码量快照")

    for index_path in (STATS_DIR / "README.md", STATS_DIR / "archive" / "README.md"):
        if snapshot.name not in read(index_path):
            return fail(f"{index_path.relative_to(ROOT)} 未指向最新代码量快照 {snapshot.name}")

    with tempfile.TemporaryDirectory(prefix="fbs-loc-") as tmp:
        generated = pathlib.Path(tmp) / snapshot.name
        run_generator(generated)
        expected = normalize_report(read(generated))
        actual = normalize_report(read(snapshot))
        if expected != actual:
            print(f"❌ {snapshot.relative_to(ROOT)} 不是当前源码的最新代码量快照", file=sys.stderr)
            print("   请执行：", file=sys.stderr)
            print(f"   python3 scripts/dev/lean-loc-report.py --write {snapshot.relative_to(ROOT)}", file=sys.stderr)
            return 1

    print(f"✅ LOC snapshot is current: {snapshot.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
