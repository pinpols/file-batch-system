#!/usr/bin/env python3
"""
Test Impact Analysis (TIA) POC — 测试类粒度。

策略(方案 B,自建静态依赖图):
  1. git diff <base>..<head> 拿改动的 .java 文件
  2. 提取改动文件的 fully-qualified class name(package + filename)
  3. 反向 import 图迭代(最多 5 跳)— 求出受影响的 main FQN 闭包
     - 精确 import 匹配
     - import com.foo.* 通配匹配
     - 同 package 直接用 simple-name 也算引用(扫 java text 字界)
  4. 扫所有测试源 (*Test.java / *IT.java / *Tests.java),复用同样的匹配规则
     找到任一引用受影响 FQN 的测试 → 加入选中集
  5. 测试类自身被改 → 直接选中

输出:
  stdout 一行一个 test 类(simple class name,逗号拼给 mvn -Dtest 用)
  stderr 诊断信息

为何不识别 properties/yml/SQL/Flyway?见 docs/runbook/tia-poc-2026-05-22.md「盲区」一节。
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;", re.MULTILINE)

TEST_GLOB = "src/test/java"
MAIN_GLOB = "src/main/java"
EXCLUDE_PATHS = (".claude/", "target/", "build/")
MAX_HOPS = 5


def log(msg: str) -> None:
    print(msg, file=sys.stderr)


def run(cmd: list[str]) -> str:
    return subprocess.check_output(cmd, cwd=REPO_ROOT, text=True)


def changed_java_files(base: str, head: str) -> list[Path]:
    out = run(["git", "diff", "--name-only", f"{base}..{head}"])
    files = []
    for line in out.splitlines():
        if not line.endswith(".java"):
            continue
        if any(seg in line for seg in EXCLUDE_PATHS):
            continue
        p = REPO_ROOT / line
        if p.exists():
            files.append(p)
    return files


def read_text(java_file: Path) -> str:
    try:
        return java_file.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def fqn_of(java_file: Path) -> str | None:
    m = PACKAGE_RE.search(read_text(java_file))
    if not m:
        return None
    return f"{m.group(1)}.{java_file.stem}"


def package_of(java_file: Path) -> str | None:
    m = PACKAGE_RE.search(read_text(java_file))
    return m.group(1) if m else None


def imports_of(java_file: Path) -> set[str]:
    return set(IMPORT_RE.findall(read_text(java_file)))


def is_test_file(java_file: Path) -> bool:
    rel = java_file.relative_to(REPO_ROOT).as_posix()
    return TEST_GLOB in rel and (
        java_file.stem.endswith("Test")
        or java_file.stem.endswith("IT")
        or java_file.stem.endswith("Tests")
    )


def all_main_files() -> list[Path]:
    out: list[Path] = []
    for java in REPO_ROOT.rglob("*.java"):
        rel = java.relative_to(REPO_ROOT).as_posix()
        if any(seg in rel for seg in EXCLUDE_PATHS):
            continue
        if MAIN_GLOB in rel:
            out.append(java)
    return out


def all_test_files() -> list[Path]:
    out: list[Path] = []
    for java in REPO_ROOT.rglob("*.java"):
        rel = java.relative_to(REPO_ROOT).as_posix()
        if any(seg in rel for seg in EXCLUDE_PATHS):
            continue
        if TEST_GLOB not in rel:
            continue
        if not (
            java.stem.endswith("Test")
            or java.stem.endswith("IT")
            or java.stem.endswith("Tests")
        ):
            continue
        out.append(java)
    return out


def references_any(
    java_file: Path,
    imports: set[str],
    target_fqns: set[str],
) -> bool:
    if not target_fqns:
        return False
    for imp in imports:
        if imp.endswith(".*"):
            pkg = imp[:-2]
            if any(fqn.rsplit(".", 1)[0] == pkg for fqn in target_fqns):
                return True
        elif imp in target_fqns:
            return True

    pkg = package_of(java_file)
    if pkg:
        same_pkg_simples = [
            fqn.rsplit(".", 1)[-1]
            for fqn in target_fqns
            if fqn.rsplit(".", 1)[0] == pkg
        ]
        if same_pkg_simples:
            text = read_text(java_file)
            for simple in same_pkg_simples:
                if re.search(rf"\b{re.escape(simple)}\b", text):
                    return True
    return False


def main() -> int:
    base = os.environ.get("TIA_BASE", "origin/main")
    head = os.environ.get("TIA_HEAD", "HEAD")
    log(f"[tia] base={base} head={head} root={REPO_ROOT}")

    try:
        changed = changed_java_files(base, head)
    except subprocess.CalledProcessError as exc:
        log(f"[tia] git diff failed: {exc}")
        return 2

    if not changed:
        log("[tia] no changed .java files (非 java 改动? 见 runbook 盲区)")
        return 0

    changed_main_fqns: set[str] = set()
    directly_changed_tests: set[str] = set()
    for f in changed:
        fqn = fqn_of(f)
        if not fqn:
            continue
        if is_test_file(f):
            directly_changed_tests.add(fqn.rsplit(".", 1)[-1])
        else:
            changed_main_fqns.add(fqn)

    log(f"[tia] changed java files: {len(changed)}")
    log(f"[tia] changed main FQNs: {len(changed_main_fqns)}")
    for fqn in sorted(changed_main_fqns):
        log(f"[tia]   - {fqn}")
    log(f"[tia] directly changed tests: {len(directly_changed_tests)}")

    main_files = all_main_files()
    main_index: dict[str, Path] = {}
    for jf in main_files:
        fqn = fqn_of(jf)
        if fqn:
            main_index[fqn] = jf
    log(f"[tia] indexed {len(main_index)} main source files")

    impacted: set[str] = set(changed_main_fqns)
    frontier: set[str] = set(changed_main_fqns)
    for hop in range(1, MAX_HOPS + 1):
        if not frontier:
            break
        next_frontier: set[str] = set()
        for fqn, jf in main_index.items():
            if fqn in impacted:
                continue
            if references_any(jf, imports_of(jf), frontier):
                next_frontier.add(fqn)
        new_count = len(next_frontier - impacted)
        log(f"[tia] hop {hop}: +{new_count} impacted main FQNs")
        impacted |= next_frontier
        frontier = next_frontier
        if not new_count:
            break

    log(f"[tia] total impacted main FQNs (transitive): {len(impacted)}")

    test_files = all_test_files()
    log(f"[tia] scanning {len(test_files)} test files")

    selected: set[str] = set(directly_changed_tests)
    for tf in test_files:
        if references_any(tf, imports_of(tf), impacted):
            selected.add(tf.stem)

    log(f"[tia] selected tests: {len(selected)} / {len(test_files)}")
    for name in sorted(selected):
        print(name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
