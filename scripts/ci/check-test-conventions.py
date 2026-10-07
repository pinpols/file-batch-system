#!/usr/bin/env python3
"""测试约定守护：类级/方法级中文 ``@DisplayName`` 的存量报告与增量拦截。

口径见 ``docs/coding-conventions.md`` §14.5 —— 全项目统一中文 ``@DisplayName``，类级与方法级都要。
覆盖 ``src/test/java`` 下的 Java 测试源码，检查三类缺口：

* ``missing-class-display``  —— 含测试方法的类没有类级 ``@DisplayName``；
* ``missing-method-display`` —— ``@Test`` / ``@ParameterizedTest`` / ``@RepeatedTest`` /
  ``@TestFactory`` 方法没有 ``@DisplayName``；
* ``non-chinese-display``    —— ``@DisplayName`` 文本不含中文。

存量按 ``docs/governance/test-conventions-baseline.txt`` 渐进收敛（标识忽略行号漂移）：

* ``--report``（默认行为）输出人读快照，恒以 0 退出，不阻断历史存量；
* ``--write-baseline`` 按当前缺口重建基线，收敛一项即从基线消失；
* ``--check-baseline`` 只对**相对基线新增**的缺口失败（PR Gate ``PR_TEST_CONVENTIONS`` 用它）。

用法::

    python3 scripts/ci/check-test-conventions.py --report
    python3 scripts/ci/check-test-conventions.py --write-baseline \
        docs/governance/test-conventions-baseline.txt
    python3 scripts/ci/check-test-conventions.py --check-baseline \
        docs/governance/test-conventions-baseline.txt
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BASELINE = ROOT / "docs/governance/test-conventions-baseline.txt"

SKIP_DIRS = {".git", "target", "node_modules", "build", "dist", "reports", ".idea", ".mvn"}

CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
TEST_ANNOTATION = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b")
DISPLAY_NAME = re.compile(r"@DisplayName\s*\(")
CLASS_DECL = re.compile(r"\b(?:class|interface|enum|record)\s+(\w+)")
MODIFIER = re.compile(
    r"(?:public|protected|private|static|final|abstract|synchronized|native|strictfp|default)\b"
)

MISSING_CLASS = "missing-class-display"
MISSING_METHOD = "missing-method-display"
NON_CHINESE = "non-chinese-display"

KIND_LABEL = {
    MISSING_CLASS: "类缺类级 @DisplayName",
    MISSING_METHOD: "测试方法缺 @DisplayName",
    NON_CHINESE: "@DisplayName 不含中文",
}


@dataclass(frozen=True)
class Finding:
    """一条缺口；``identity`` 是基线标识（不含行号，避免行漂移造成伪新增）。"""

    kind: str
    path: str
    qualified: str
    line: int

    @property
    def identity(self) -> str:
        suffix = "class" if self.kind == MISSING_CLASS else "member"
        return f"{self.path}#{self.kind}#{suffix}:{self.qualified}"

    def to_dict(self) -> dict[str, object]:
        return {
            "kind": self.kind,
            "path": self.path,
            "qualified": self.qualified,
            "line": self.line,
        }


@dataclass(frozen=True)
class ClassNode:
    name: str
    decl_start: int
    body_start: int
    body_end: int
    parent: int | None


def mask_java(text: str) -> str:
    """把注释/字符串/字符/文本块替换成等长空白（保留换行），使括号匹配与位置扫描不被字面量干扰。"""
    out = list(text)
    i, n = 0, len(text)
    state = "code"
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                out[i] = out[i + 1] = " "
                state = "line"
                i += 2
                continue
            if ch == "/" and nxt == "*":
                out[i] = out[i + 1] = " "
                state = "block"
                i += 2
                continue
            if text[i : i + 3] == '"""':
                out[i] = out[i + 1] = out[i + 2] = " "
                state = "textblock"
                i += 3
                continue
            if ch == '"':
                out[i] = " "
                state = "string"
                i += 1
                continue
            if ch == "'":
                out[i] = " "
                state = "char"
                i += 1
                continue
            i += 1
            continue

        if ch == "\n":
            out[i] = "\n"
            if state == "line":
                state = "code"
            i += 1
            continue

        if state == "textblock":
            if text[i : i + 3] == '"""':
                out[i] = out[i + 1] = out[i + 2] = " "
                state = "code"
                i += 3
                continue
            out[i] = " "
            i += 1
            continue

        out[i] = " "
        if state == "block":
            if ch == "*" and nxt == "/":
                out[i + 1] = " "
                state = "code"
                i += 2
                continue
            i += 1
            continue
        if ch == "\\":
            if i + 1 < n:
                out[i + 1] = " "
            i += 2
            continue
        if state == "string" and ch == '"':
            state = "code"
        elif state == "char" and ch == "'":
            state = "code"
        i += 1
    return "".join(out)


def matching_brace(masked: str, open_index: int) -> int:
    depth = 0
    for i in range(open_index, len(masked)):
        ch = masked[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
    return -1


def class_nodes(masked: str) -> list[ClassNode]:
    """按括号配对建立类树（含 ``@Nested`` 内部类），父亲取包含范围最小的外层类。"""
    raw: list[tuple[str, int, int, int]] = []
    for m in CLASS_DECL.finditer(masked):
        brace = masked.find("{", m.end())
        if brace == -1:
            continue
        end = matching_brace(masked, brace)
        if end == -1:
            continue
        raw.append((m.group(1), m.start(), brace, end))

    nodes: list[ClassNode] = []
    for i, (name, decl_start, body_start, body_end) in enumerate(raw):
        parent = None
        best = None
        for j, (_, other_decl, other_start, other_end) in enumerate(raw):
            if i == j or not (other_start < decl_start < other_end):
                continue
            span = other_end - other_start
            if best is None or span < best:
                best = span
                parent = j
        nodes.append(ClassNode(name, decl_start, body_start, body_end, parent))
    return nodes


def enclosing_class(nodes: list[ClassNode], position: int) -> int | None:
    candidates = [
        i for i, node in enumerate(nodes) if node.body_start < position < node.body_end
    ]
    if not candidates:
        return None
    return min(candidates, key=lambda i: nodes[i].body_end - nodes[i].body_start)


def declaration_block(masked: str, end_index: int, window: int = 4000) -> str:
    """取注解块：从上一个**成员/代码块**边界到声明位置之间的文本。

    边界只认括号深度为 0 的 ``}`` / ``;`` / ``{``。这一条是必须的：``@ValueSource(strings = {...})``
    / ``@CsvSource({...})`` 这类注解参数里的数组初始化大括号深度 ≥1，若也算边界，注解块就会从
    数组内部开始切，导致**已经把 @DisplayName 写在 @ParameterizedTest 下一行的方法被误报为缺失**。
    从声明处**反向**扫描并记录括号深度，遇到第一个深度 0 边界即停（通常是上一个成员的 ``}``），
    因此开销与「距上一个成员的距离」成正比，而不是窗口大小。
    """
    start = max(0, end_index - window)
    depth = 0
    i = end_index - 1
    while i >= start:
        ch = masked[i]
        if ch == ")":
            depth += 1
        elif ch == "(":
            depth = max(0, depth - 1)
        elif depth == 0 and ch in "{};":
            return masked[i + 1 : end_index]
        i -= 1
    return masked[start:end_index]


def display_name_span(masked: str, block_start: int, block_end: int) -> tuple[int, int] | None:
    """在给定区间里找 ``@DisplayName(...)``，返回 (起, 止) 位置；括号内容已被掩码，可直接配对。"""
    m = DISPLAY_NAME.search(masked, block_start, block_end)
    if not m:
        return None
    open_index = m.end() - 1
    close = matching_brace(masked, open_index) if masked[open_index] == "(" else -1
    if close == -1:
        close = masked.find(")", open_index)
    return (m.start(), close if close != -1 else block_end)


def method_signature(masked: str, position: int) -> tuple[str, int] | None:
    """从测试注解之后定位方法名与其参数列表左括号。"""
    i = position
    n = len(masked)
    while i < n:
        while i < n and masked[i].isspace():
            i += 1
        if i < n and masked[i] == "@":
            i += 1
            while i < n and (masked[i].isalnum() or masked[i] in "._$"):
                i += 1
            while i < n and masked[i].isspace():
                i += 1
            if i < n and masked[i] == "(":
                depth = 0
                while i < n:
                    if masked[i] == "(":
                        depth += 1
                    elif masked[i] == ")":
                        depth -= 1
                        if depth == 0:
                            i += 1
                            break
                    i += 1
            continue
        m = MODIFIER.match(masked, i)
        if m:
            i = m.end()
            continue
        break

    nxt = masked.find("(", i)
    if nxt == -1:
        return None
    head = masked[i:nxt]
    names = re.findall(r"\w+", head)
    if not names:
        return None
    return names[-1], nxt


def line_of(text: str, position: int) -> int:
    return text.count("\n", 0, position) + 1


def scan_file(path: Path, rel: str) -> list[Finding]:
    text = path.read_text(encoding="utf-8")
    masked = mask_java(text)
    nodes = class_nodes(masked)
    if not nodes:
        return []

    findings: list[Finding] = []
    seen_methods: set[tuple[int, str]] = set()
    tests_by_class: dict[int, list[str]] = {}

    for m in TEST_ANNOTATION.finditer(masked):
        signature = method_signature(masked, m.end())
        if signature is None:
            continue
        method_name, paren = signature
        owner = enclosing_class(nodes, m.start())
        if owner is None:
            continue
        key = (owner, method_name)
        if key in seen_methods:
            continue
        seen_methods.add(key)
        qualified = f"{nodes[owner].name}.{method_name}"
        tests_by_class.setdefault(owner, []).append(method_name)

        block = declaration_block(masked, paren)
        span = display_name_span(masked, max(0, paren - len(block)), paren)
        if span is None:
            findings.append(Finding(MISSING_METHOD, rel, qualified, line_of(text, m.start())))
        elif not CJK.search(text[span[0] : span[1] + 1]):
            findings.append(Finding(NON_CHINESE, rel, qualified, line_of(text, m.start())))

    for index, node in enumerate(nodes):
        if index not in tests_by_class:
            continue
        block = declaration_block(masked, node.decl_start)
        span = display_name_span(masked, max(0, node.decl_start - len(block)), node.decl_start)
        if span is None:
            findings.append(
                Finding(MISSING_CLASS, rel, node.name, line_of(text, node.decl_start))
            )
        elif not CJK.search(text[span[0] : span[1] + 1]):
            findings.append(
                Finding(NON_CHINESE, rel, node.name, line_of(text, node.decl_start))
            )
    return findings


def test_java_files() -> list[Path]:
    roots: list[Path] = []
    for dirpath, dirnames, _ in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        candidate = Path(dirpath)
        if (
            candidate.name == "java"
            and candidate.parent.name == "test"
            and candidate.parent.parent.name == "src"
        ):
            roots.append(candidate)
    files: list[Path] = []
    for root in roots:
        files.extend(sorted(root.rglob("*.java")))
    return sorted(set(files))


def scan() -> list[Finding]:
    findings: list[Finding] = []
    for path in test_java_files():
        rel = path.relative_to(ROOT).as_posix()
        findings.extend(scan_file(path, rel))
    return sorted(findings, key=lambda f: (f.path, f.kind, f.qualified))


def read_baseline(path: Path) -> set[str]:
    if not path.exists():
        return set()
    entries: set[str] = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        entries.add(line)
    return entries


def write_baseline(path: Path, findings: list[Finding]) -> None:
    identities = sorted({finding.identity for finding in findings})
    body = [
        "# 测试 @DisplayName 缺口基线（类级/方法级中文 @DisplayName，口径见 coding-conventions §14.5）。",
        "# 由 scripts/ci/check-test-conventions.py --write-baseline 生成。",
        "# PR Gate PR_TEST_CONVENTIONS 据此只对新增缺口失败；收敛一项即从本文件删除对应行。",
        "# 标识格式：<相对路径>#<缺口类型>#<class|member>:<限定名>，忽略行号漂移。",
    ]
    body.extend(identities)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(body) + "\n", encoding="utf-8")


def summarize(findings: list[Finding]) -> dict[str, int]:
    summary = {MISSING_CLASS: 0, MISSING_METHOD: 0, NON_CHINESE: 0}
    for finding in findings:
        summary[finding.kind] = summary.get(finding.kind, 0) + 1
    return summary


def report(findings: list[Finding], limit: int) -> None:
    summary = summarize(findings)
    print("测试约定快照（@DisplayName 类级 + 方法级中文）")
    print(f"  缺口合计: {len(findings)}")
    for kind in (MISSING_CLASS, MISSING_METHOD, NON_CHINESE):
        print(f"    {KIND_LABEL[kind]}: {summary.get(kind, 0)}")

    by_module: dict[str, int] = {}
    for finding in findings:
        module = finding.path.split("/")[0]
        by_module[module] = by_module.get(module, 0) + 1
    if by_module:
        print("  按顶层模块:")
        for module, count in sorted(by_module.items(), key=lambda kv: -kv[1]):
            print(f"    {module}: {count}")

    if findings:
        print(f"  前 {min(limit, len(findings))} 条:")
        for finding in findings[:limit]:
            print(
                f"    {finding.path}:{finding.line} [{KIND_LABEL[finding.kind]}] "
                f"{finding.qualified}"
            )
        if len(findings) > limit:
            print(f"    ...（其余 {len(findings) - limit} 条省略）")


def check_baseline(path: Path, findings: list[Finding]) -> int:
    baseline = read_baseline(path)
    current = {finding.identity for finding in findings}
    added = sorted(current - baseline)
    if not added:
        print(
            f"✅ 通过 | code=TEST_CONVENTIONS | gate=测试 @DisplayName 约定 "
            f"（基线 {len(baseline)} 项，当前 {len(current)} 项，新增 0 项）"
        )
        return 0

    print(
        f"❌ 新增 {len(added)} 处测试 @DisplayName 缺口（基线 {len(baseline)} 项，当前 {len(current)} 项）",
        file=sys.stderr,
    )
    lookup = {finding.identity: finding for finding in findings}
    for identity in added[:40]:
        finding = lookup.get(identity)
        where = f"{finding.path}:{finding.line}" if finding else identity
        label = KIND_LABEL.get(finding.kind, finding.kind) if finding else ""
        print(f"    {where} [{label}] {identity.split(':')[-1]}", file=sys.stderr)
    if len(added) > 40:
        print(f"    ...（其余 {len(added) - 40} 处省略）", file=sys.stderr)
    print(
        "  修复：按 docs/coding-conventions.md §14.5 为测试类补类级中文 @DisplayName、"
        "为每个测试方法补方法级中文 @DisplayName；",
        file=sys.stderr,
    )
    print(
        f"  收敛基线：python3 scripts/ci/check-test-conventions.py --write-baseline {path}",
        file=sys.stderr,
    )
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description="测试 @DisplayName 约定守护")
    parser.add_argument(
        "--report",
        action="store_true",
        help="打印人读快照（默认行为），恒以 0 退出",
    )
    parser.add_argument("--json", metavar="PATH", help="输出机器可读 JSON")
    parser.add_argument("--write-baseline", metavar="PATH", help="按当前缺口重建基线")
    parser.add_argument("--check-baseline", metavar="PATH", help="只对相对基线新增的缺口失败")
    parser.add_argument("--limit", type=int, default=40, help="报告最多打印多少条明细")
    args = parser.parse_args()

    findings = scan()

    if args.json:
        out = Path(args.json)
        out.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "schemaVersion": 1,
            "summary": summarize(findings),
            "total": len(findings),
            "findings": [finding.to_dict() for finding in findings],
        }
        out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"已写入 JSON 快照: {out}（{len(findings)} 条缺口）")

    if args.write_baseline:
        write_baseline(Path(args.write_baseline), findings)
        print(
            f"已重建基线: {args.write_baseline}（{len({f.identity for f in findings})} 项缺口）"
        )
        return 0

    if args.check_baseline:
        return check_baseline(Path(args.check_baseline), findings)

    report(findings, args.limit)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
