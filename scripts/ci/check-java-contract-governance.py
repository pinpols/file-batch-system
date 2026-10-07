#!/usr/bin/env python3
"""固定 Java 边界、有限域字面量及协议键复用的精准增量守卫。"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / "docs/governance/java-contract-governance.json"
BASELINE = ROOT / "docs/governance/java-contract-governance-baseline.json"


@dataclass(frozen=True)
class Finding:
    rule: str
    path: str
    signature: str
    detail: str
    line: int

    def key(self) -> str:
        return "|".join((self.rule, self.path, self.signature, self.detail))


def lexical_source(source: str) -> tuple[str, list[tuple[int, int, str]]]:
    """保留位置，屏蔽注释、字符和文本块；普通字符串单独留作协议值候选。"""
    chars = list(source)
    literals = []
    i = 0
    while i < len(source):
        start = i
        if source.startswith("//", i):
            end = source.find("\n", i)
            i = len(source) if end < 0 else end
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = len(source) if end < 0 else end + 2
        elif source.startswith('"""', i):
            i += 3
            while i < len(source):
                if source.startswith('"""', i) and (i == 0 or source[i - 1] != "\\"):
                    i += 3
                    break
                i += 1
        elif source[i] in ('"', "'"):
            quote = source[i]
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
            if quote == '"':
                literals.append((start, i, source[start + 1:i - 1]))
        else:
            i += 1
            continue
        for position in range(start, min(i, len(source))):
            if chars[position] != "\n":
                chars[position] = " "
    return "".join(chars), literals


def closing(code: str, start: int, left: str, right: str) -> int:
    depth = 0
    for i in range(start, len(code)):
        if code[i] == left:
            depth += 1
        elif code[i] == right:
            depth -= 1
            if depth == 0:
                return i
    return len(code) - 1


def methods(code: str) -> list[tuple[int, int, str, str]]:
    result = []
    # 只解析返回类型及方法头；嵌套括号由平衡扫描处理，不依赖行数。
    pattern = re.compile(r"\b(CommonResponse|PageResponse|Map|List|Object|[A-Z]\w*|void|boolean|int|long|String)\s*(?:<[^;{}()]*>)?\s+(\w+)\s*\(")
    for match in pattern.finditer(code):
        head_start = max(code.rfind(";", 0, match.start()), code.rfind("{", 0, match.start()), code.rfind("}", 0, match.start())) + 1
        modifiers = code[head_start:match.start()]
        if re.search(r"\b(return|new|throw)\b", modifiers):
            continue
        args_start = match.end() - 1
        args_end = closing(code, args_start, "(", ")")
        tail = code[args_end + 1:]
        tail_match = re.match(r"\s*(?:throws\s+[\w.,\s]+)?([;{])", tail)
        if not tail_match:
            continue
        end = args_end + 1 + tail_match.end()
        if tail_match.group(1) == "{":
            end = closing(code, end - 1, "{", "}") + 1
        return_type = re.sub(r"\s+", "", code[match.start():code.rfind(match.group(2), match.start(), args_start)])
        signature = match.group(2) + "(" + re.sub(r"\s+", " ", code[args_start + 1:args_end].strip()) + "):" + return_type
        result.append((match.start(), end, signature, modifiers))
    return result


def enum_codes(root: Path, name: str) -> set[str]:
    source = (root / "batch-common/src/main/java/io/github/pinpols/batch/common/enums" / (name + ".java")).read_text()
    return set(re.findall(r'\b[A-Z][A-Z_0-9]*\s*\(\s*"([^"]+)"', source))


def scan(path: str, source: str, enum_values: set[str] | None = None) -> list[Finding]:
    code, literals = lexical_source(source)
    declarations = methods(code)
    findings = []
    types = []
    for match in re.finditer(r"\b(?:class|interface|record|enum)\s+(\w+)", code):
        brace = code.find("{", match.end())
        if brace >= 0:
            types.append((match.start(), closing(code, brace, "{", "}"), match.group(1)))

    def type_owner(position: int) -> int:
        enclosing = [(end - start, start) for start, end, _ in types if start <= position <= end]
        return min(enclosing)[1] if enclosing else -1

    def owner(position: int) -> str:
        enclosing = [(end - start, signature) for start, end, signature, _ in declarations if start <= position < end]
        return min(enclosing)[1] if enclosing else "<fields>"

    def add(rule: str, position: int, detail: str, signature: str | None = None) -> None:
        findings.append(Finding(rule, path, signature or owner(position), detail, source.count("\n", 0, position) + 1))

    boundary = path.endswith("Controller.java") or bool(re.search(r"\binterface\s+\w*Service\b", code))
    if boundary:
        for start, _, signature, modifiers in declarations:
            if "private" in modifiers or "protected" in modifiers:
                continue
            return_type = signature.split("):", 1)[1]
            if re.search(r"\b(?:Object|Map)(?:<|>|$)", return_type):
                add("JCON-1", start, return_type, signature)

    constants = {}
    declaration_spans = []
    map_key_spans = []
    for call in re.finditer(r"\bMap\.(of|entry)\s*\(", code):
        left = call.end() - 1
        right = closing(code, left, "(", ")")
        depth = 0
        argument = 0
        argument_start = left + 1
        for position in range(left + 1, right + 1):
            char = code[position]
            if char in "([{":
                depth += 1
            elif char in ")]}":
                if position != right:
                    depth -= 1
            if (char == "," and depth == 0) or position == right:
                if argument % 2 == 0:
                    map_key_spans.append((argument_start, position))
                argument += 1
                argument_start = position + 1
    for match in re.finditer(r"\b(?:static\s+final|final\s+static)\s+String\s+((?:KEY|PARAM|MDC)_\w+)\s*=", code):
        end = code.find(";", match.end())
        values = [value for start, stop, value in literals if match.end() <= start < end]
        if len(values) == 1:
            constants[(type_owner(match.start()), values[0])] = match.group(1)
            declaration_spans.append((match.start(), end))
    for start, end, value in literals:
        if enum_values and value in enum_values:
            add("JCON-2", start, value)
        constant_key = (type_owner(start), value)
        if constant_key in constants and not any(left <= start <= right for left, right in declaration_spans):
            before = code[max(0, start - 120):start]
            # 只约束真实 key 消费点；注解、日志、文案和值参数不因同值而被拦。
            first_key = re.search(r"\b(?:get|put|containsKey|getOrDefault|queryParam|remove)\s*\(\s*$", before)
            drift_key = re.search(r"\baddDrift\s*\(\s*[\w.]+\s*,\s*$", before)
            map_key = any(left <= start and end <= right for left, right in map_key_spans)
            if first_key or drift_key or map_key:
                add("JCON-3", start, constants[constant_key] + "=" + value)
    return findings


def apply_exceptions(findings: list[Finding], exceptions: list[dict]) -> list[Finding]:
    keys = {(row["rule"], row["path"], row["signature"], row["detail"]) for row in exceptions}
    return [f for f in findings if (f.rule, f.path, f.signature, f.detail) not in keys]


def regressions(findings: list[Finding], baseline: dict[str, int]) -> dict[str, int]:
    counts = Counter(f.key() for f in findings)
    return {key: count - baseline.get(key, 0) for key, count in counts.items() if count > baseline.get(key, 0)}


def validate_registry(policy: dict, baseline: dict) -> None:
    if policy.get("schema_version") != 1 or baseline.get("schema_version") != 1:
        raise ValueError("unsupported registry schema_version")
    for exception in policy["exceptions"]:
        if exception["rule"] not in {"JCON-1", "JCON-2", "JCON-3"}:
            raise ValueError("unknown exception rule")
        if not exception.get("reason", "").strip():
            raise ValueError("exception requires a reason")
        for key in ("path", "signature", "detail"):
            if not exception.get(key) or "*" in exception[key]:
                raise ValueError("exception requires exact path/signature/detail")
    for entry in policy["enum_scopes"]:
        if not entry["enums"] or "*" in entry["path"]:
            raise ValueError("enum scope requires exact path and authoritative enums")
    for count in baseline["counts"].values():
        if type(count) is not int or count < 0:
            raise ValueError("baseline counts must be nonnegative integers")


def expand_enum_consumers(paths: list[str], policy: dict) -> list[str]:
    """权威枚举改动时复扫其登记消费者，避免增量模式只扫描枚举本身。"""
    selected = set(paths)
    prefix = "batch-common/src/main/java/io/github/pinpols/batch/common/enums/"
    for entry in policy["enum_scopes"]:
        if any(prefix + name + ".java" in selected for name in entry["enums"]):
            selected.add(entry["path"])
    return sorted(selected)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="*", help="指定 Java 文件；不指定时复扫全部生产 Java")
    parser.add_argument("--base-ref", help="只检查相对 Git ref 新增/修改的生产 Java 文件")
    parser.add_argument("--mode", choices=("baseline", "report"), default="baseline")
    parser.add_argument("--json", action="store_true", help="输出可审计候选清单")
    args = parser.parse_args()
    policy = json.loads(POLICY.read_text())
    baseline_registry = json.loads(BASELINE.read_text())
    try:
        validate_registry(policy, baseline_registry)
    except (KeyError, TypeError, ValueError) as error:
        parser.error(str(error))
    if args.files:
        paths = args.files
    elif args.base_ref:
        tracked = subprocess.check_output(["git", "diff", "--name-only", "--diff-filter=ACMR", args.base_ref, "--"], cwd=ROOT, text=True).splitlines()
        untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, text=True).splitlines()
        paths = sorted(set(tracked + untracked))
    else:
        paths = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard"], cwd=ROOT, text=True).splitlines()
    scopes = {}
    for entry in policy["enum_scopes"]:
        scopes[entry["path"]] = set().union(*(enum_codes(ROOT, name) for name in entry["enums"]))
    findings = []
    for path in expand_enum_consumers(paths, policy):
        file = ROOT / path
        if path.endswith(".java") and "/src/main/java/" in path and file.is_file():
            findings.extend(scan(path, file.read_text(), scopes.get(path)))
    findings = apply_exceptions(findings, policy["exceptions"])
    baseline = baseline_registry["counts"]
    failures = regressions(findings, baseline)
    if args.json:
        print(json.dumps({"counts": dict(Counter(f.key() for f in findings)), "findings": [vars(f) for f in findings]}, ensure_ascii=False, indent=2))
    else:
        for finding in findings:
            if args.mode == "report" or finding.key() in failures:
                print(f"{finding.path}:{finding.line}: {finding.rule} {finding.signature} {finding.detail}")
        print(f"Java contract governance: candidates={len(findings)}, regressions={sum(failures.values())}, mode={args.mode}")
    return 1 if args.mode == "baseline" and failures else 0


if __name__ == "__main__":
    sys.exit(main())
