#!/usr/bin/env python3
# 用途：集中式空值检查。
# 用法：在仓库根目录执行 python3 scripts/ci/check-empty-checks.py；参数与前置环境见下方说明及所属 README。
"""阻止生产 Java 新增零散的 null / empty / blank 判断。

统一判断入口是 ``EmptyChecks``。本检查只拦本次 diff 新增的生产代码，避免把历史
遗留一次性大改，也避免把有明确语义的 ``Objects.equals``、``Optional`` 等误判为
空值判断。历史代码由业务迁移逐步收口；从本检查接入后不得继续新增同类写法。

用法：
  python3 scripts/ci/check-empty-checks.py
  python3 scripts/ci/check-empty-checks.py --base origin/main
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


CHECK_PATTERNS = (
    (
        "null 判断",
        re.compile(r"(?:(?<![=!])(?:==|!=)\s*null\b|\bnull\s*(?:==|!=)(?![=]))"),
    ),
    ("字符串 / 集合空判断", re.compile(r"(?:\.isEmpty\s*\(\)|\.isBlank\s*\(\))")),
    (
        "集合大小判断",
        re.compile(r"(?:\.size\s*\(\)\s*(?:==|!=|>|>=|<|<=)\s*0|\b0\s*(?:==|!=|<|<=|>|>=)\s*[^;]*\.size\s*\(\))"),
    ),
    (
        "数组长度判断",
        re.compile(r"(?:\.length\s*(?:==|!=|>|>=|<|<=)\s*0|\b0\s*(?:==|!=|<|<=|>|>=)\s*[^;]*\.length\b)"),
    ),
    (
        "分散工具类空判断",
        re.compile(
            r"\b(?:CollectionUtils|StringUtils|ObjectUtils)\."
            r"(?:isEmpty|isNotEmpty|isBlank|isNotBlank|hasText|hasLength)\s*\("
        ),
    ),
)

ALLOW_MARKER = re.compile(r"empty-check:\s*allow(?:\s*-\s*(?P<reason>\S.*))?")
GATE_CODE = "EMPTY_CHECKS"
GATE_NAME = "空值判断统一入口"


def is_production_java(path: str) -> bool:
    return path.endswith(".java") and "/src/main/java/" in f"/{path}"


def is_pure_merge_revert(base: str | None) -> bool:
    """纯合并回退恢复的是合并前代码，不能将其误计为新增行。"""
    if not base:
        return False
    try:
        base_revision = subprocess.run(
            ["git", "rev-parse", "--verify", base],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        head_parents = subprocess.run(
            ["git", "rev-list", "--parents", "-n1", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.split()

        # pull_request 工作流通常检出合成的合并提交。
        candidates = ["HEAD"]
        if len(head_parents) == 3:
            candidates = head_parents[1:]
    except subprocess.CalledProcessError:
        return False

    for candidate in candidates:
        try:
            candidate_parents = subprocess.run(
                ["git", "rev-list", "--parents", "-n1", candidate],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.split()
            subject = subprocess.run(
                ["git", "log", "-1", "--format=%s", candidate],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
        except subprocess.CalledProcessError:
            continue
        if (
            len(candidate_parents) == 2
            and candidate_parents[1] == base_revision
            and subject.startswith('Revert "Merge ')
        ):
            return True
    return False


def added_lines(base: str | None) -> list[tuple[str, int, str]]:
    command = ["git", "diff", "--unified=0"]
    if base:
        command.append(f"{base}...HEAD")
    else:
        # 本地默认检查 HEAD 到工作树，包含 staged 和 unstaged 修改。
        command.append("HEAD")
    command.extend(["--", "*.java"])
    result = subprocess.run(command, check=True, capture_output=True, text=True)

    path = None
    line_no = 0
    additions: list[tuple[str, int, str]] = []
    for raw in result.stdout.splitlines():
        if raw.startswith("+++ b/"):
            path = raw[6:]
            continue
        if raw.startswith("@@"):
            match = re.search(r"\+([0-9]+)", raw)
            line_no = int(match.group(1)) if match else 0
            continue
        if raw.startswith("+") and not raw.startswith("+++"):
            if path and is_production_java(path):
                additions.append((path, line_no, raw[1:]))
            line_no += 1
        elif not raw.startswith("-") and line_no:
            line_no += 1
    if base is None:
        # 未 git add 的新实现也属于本地工作树新增代码；显式 base 仍只比较提交范围。
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "-z", "--", "*.java"],
            check=True,
            capture_output=True,
            text=True,
        )
        for relative in untracked.stdout.split("\0"):
            path = Path(relative)
            if is_production_java(relative) and path.is_file():
                additions.extend(
                    (relative, index, source)
                    for index, source in enumerate(path.read_text(encoding="utf-8").splitlines(), 1)
                )
    return additions


def matches_gate(source: str) -> bool:
    stripped = source.strip()
    if not stripped or stripped.startswith(("//", "/*", "*")):
        return False
    if ALLOW_MARKER.search(source):
        return False
    return any(pattern.search(source) for _, pattern in CHECK_PATTERNS)


def matched_lines(lines: list[str]) -> list[str]:
    return [line for line in lines if matches_gate(line)]


def pattern_occurrences(lines: list[str]) -> int:
    """匹配模式的累计出现次数；格式化只改换行，不改变次数。"""
    total = 0
    for source in lines:
        stripped = source.strip()
        if not stripped or stripped.startswith(("//", "/*", "*")):
            continue
        if ALLOW_MARKER.search(source):
            continue
        for _, pattern in CHECK_PATTERNS:
            total += len(pattern.findall(source))
    return total


def extract_base_tree(base: str, target: Path) -> bool:
    """一次解包 base 的 Java 树，避免逐文件 git show 在大 diff 上过慢。

    base 中没有 Java 文件或归档不可用时返回 False，调用方回退到逐文件 git show。
    """
    archive = subprocess.Popen(
        ["git", "archive", base, "--", "*.java"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    try:
        subprocess.run(["tar", "-x", "-C", str(target)], stdin=archive.stdout, check=False)
    finally:
        if archive.stdout:
            archive.stdout.close()
        archive.wait()
    return archive.returncode == 0


def read_base_lines(base_dir: Path, path: str) -> list[str]:
    file = base_dir / path
    return file.read_text(encoding="utf-8").splitlines() if file.is_file() else []


def read_head_lines(path: str) -> list[str]:
    file = Path(path)
    return file.read_text(encoding="utf-8").splitlines() if file.is_file() else []


def normalize_line(source: str) -> str:
    return re.sub(r"\s+", "", source)


def new_violations(
    violations: list[tuple[str, int, str, str]], base: str
) -> list[tuple[str, int, str, str]]:
    """按文件对比 base 与 head 的匹配次数，只保留真正新增的空值判断。

    整仓格式化会改变换行与缩进，使既有判断在逐行 diff 中表现为“新增行”；
    这里按文件比较模式出现次数（格式化不改变次数），次数增加时才上报。
    """
    by_path: dict[str, list[tuple[str, int, str, str]]] = {}
    for violation in violations:
        by_path.setdefault(violation[0], []).append(violation)

    base_dir = Path(tempfile.mkdtemp(prefix="empty-checks-base-"))
    remaining: list[tuple[str, int, str, str]] = []
    try:
        extracted = extract_base_tree(base, base_dir)
        for path, path_violations in by_path.items():
            base_lines = read_base_lines(base_dir, path)
            if not extracted:
                base_lines = (subprocess.run(
                    ["git", "show", f"{base}:{path}"], capture_output=True, text=True
                ).stdout.splitlines())
            base_matched = matched_lines(base_lines)
            extra = pattern_occurrences(read_head_lines(path)) - pattern_occurrences(base_lines)
            if extra <= 0:
                continue
            base_normalized = {normalize_line(line) for line in base_matched}
            candidates = [
                violation
                for violation in path_violations
                if normalize_line(violation[3]) not in base_normalized
            ] or path_violations
            remaining.extend(candidates[:extra])
    finally:
        shutil.rmtree(base_dir, ignore_errors=True)
    return remaining


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base",
        help="比较 base...HEAD；不传时检查 HEAD 到当前工作树的改动",
    )
    args = parser.parse_args()

    if is_pure_merge_revert(args.base):
        print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} | reason=pure merge revert")
        return 0

    violations = []
    bad_allows = []
    for path, line_no, source in added_lines(args.base):
        if path.endswith("/EmptyChecks.java"):
            continue
        stripped = source.strip()
        if not stripped or stripped.startswith(("//", "/*", "*")):
            continue
        allow = ALLOW_MARKER.search(source)
        if allow:
            if not allow.group("reason"):
                bad_allows.append((path, line_no, stripped))
            continue
        for label, pattern in CHECK_PATTERNS:
            if pattern.search(source):
                violations.append((path, line_no, label, stripped))
                break

    if bad_allows:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1", file=sys.stderr)
        print("empty-check 豁免必须写明原因：", file=sys.stderr)
        for path, line_no, source in bad_allows:
            print(f"  {path}:{line_no}: {source}", file=sys.stderr)
        return 1

    violations = new_violations(violations, args.base or "HEAD") if violations else violations

    if violations:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1", file=sys.stderr)
        print("发现新增零散空值判断，请改用 EmptyChecks：", file=sys.stderr)
        for path, line_no, label, source in violations:
            print(f"  {path}:{line_no}: [{label}] {source}", file=sys.stderr)
        print("确需保留原生判断时，在同一行追加：// empty-check: allow - 原因", file=sys.stderr)
        return 1

    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
