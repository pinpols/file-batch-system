#!/usr/bin/env python3
"""Generate a low-inflation source size report.

The old docs/stats snapshots used physical lines (`cat | wc -l`). This script
keeps that number for comparison, but leads with a leaner logical count:

* Git-tracked files only.
* Excludes docs, generated/build outputs, vendored dependencies and archived
  analysis material.
* Splits production, test, scripts, config and SQL fixtures.
* Counts Java/Go/Rust/TypeScript/C-like files by statements/blocks after
  stripping comments, so formatting line wraps do not inflate the main number.
* Counts Python with the AST statement tree.
* Counts Shell/YAML/SQL as non-empty non-comment lines or SQL statements.
"""

from __future__ import annotations

import argparse
import ast
import datetime as dt
import json
import pathlib
import re
import subprocess
from collections import defaultdict
from dataclasses import dataclass


ROOT = pathlib.Path(__file__).resolve().parents[2]

EXCLUDED_DIRS = {
    ".git",
    ".github",
    ".idea",
    ".mvn",
    ".venv",
    ".vscode",
    "build",
    "docs",
    "logs",
    "node_modules",
    "target",
}

GENERATED_PATTERNS = (
    "/target/",
    "/build/",
    "/generated-sources/",
    "/generated-test-sources/",
    "docs/api/console-api.openapi.yaml",
    "docs/api/orchestrator-internal.openapi.yaml",
    "docs/compliance/sbom.json",
)

CODE_EXTENSIONS = {
    ".java",
    ".py",
    ".go",
    ".rs",
    ".ts",
    ".tsx",
    ".js",
    ".jsx",
    ".sh",
    ".sql",
    ".xml",
    ".yml",
    ".yaml",
    ".toml",
    ".properties",
    ".gradle",
    ".kts",
}

C_LIKE_EXTENSIONS = {".java", ".go", ".rs", ".ts", ".tsx", ".js", ".jsx", ".kts", ".gradle"}
CONFIG_EXTENSIONS = {".xml", ".yml", ".yaml", ".toml", ".properties"}


@dataclass
class FileMetric:
    path: str
    group: str
    language: str
    physical: int
    lean: int


def run_git(args: list[str]) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def has_worktree_changes() -> bool:
    return bool(run_git(["status", "--porcelain"]))


def tracked_files() -> list[pathlib.Path]:
    output = run_git(["ls-files"])
    files: list[pathlib.Path] = []
    for line in output.splitlines():
        path = pathlib.Path(line)
        if should_include(path):
            files.append(path)
    return files


def should_include(path: pathlib.Path) -> bool:
    text = path.as_posix()
    if path.suffix not in CODE_EXTENSIONS:
        return False
    if any(part in EXCLUDED_DIRS for part in path.parts):
        return False
    if any(pattern in text for pattern in GENERATED_PATTERNS):
        return False
    if text.startswith("db/migration/"):
        return False
    if text.endswith(".min.js"):
        return False
    return True


def language(path: pathlib.Path) -> str:
    suffix = path.suffix
    if suffix == ".java":
        return "Java"
    if suffix == ".py":
        return "Python"
    if suffix == ".go":
        return "Go"
    if suffix == ".rs":
        return "Rust"
    if suffix in {".ts", ".tsx"}:
        return "TypeScript"
    if suffix in {".js", ".jsx"}:
        return "JavaScript"
    if suffix == ".sh":
        return "Shell"
    if suffix == ".sql":
        return "SQL"
    if suffix == ".xml":
        return "XML"
    if suffix in {".yml", ".yaml"}:
        return "YAML"
    if suffix == ".toml":
        return "TOML"
    if suffix == ".properties":
        return "Properties"
    return suffix.lstrip(".") or "Other"


def group(path: pathlib.Path) -> str:
    text = path.as_posix()
    if "/src/test/" in text or "/tests/" in text or text.endswith("_test.go") or text.endswith(".test.ts"):
        return "test"
    if text.startswith("scripts/") or "/scripts/" in text:
        return "script"
    if text.startswith("db/") or text.startswith("scripts/db/") or text.startswith("load-tests/sql/"):
        return "sql"
    if text.startswith("docker/") or text.startswith("helm/") or text.startswith(".github/"):
        return "infra-config"
    if path.suffix in CONFIG_EXTENSIONS and "/src/main/" not in text:
        return "config"
    return "prod"


def physical_lines(text: str) -> int:
    if not text:
        return 0
    return text.count("\n") + (0 if text.endswith("\n") else 1)


def strip_c_like_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//.*", "", text)


def c_like_lean(text: str) -> int:
    stripped = strip_c_like_comments(text)
    count = stripped.count(";")
    for line in stripped.splitlines():
        s = line.strip()
        if not s:
            continue
        if s.startswith("@"):
            count += 1
        elif s.endswith("{") and not s.startswith(("else", "try", "finally")):
            count += 1
    return count


def python_lean(text: str) -> int:
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return nonblank_noncomment(text, "#")
    return sum(isinstance(node, ast.stmt) for node in ast.walk(tree))


def nonblank_noncomment(text: str, comment_prefix: str) -> int:
    count = 0
    for line in text.splitlines():
        s = line.strip()
        if s and not s.startswith(comment_prefix):
            count += 1
    return count


def sql_lean(text: str) -> int:
    without_block = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    lines = []
    for line in without_block.splitlines():
        before_comment = line.split("--", 1)[0].strip()
        if before_comment:
            lines.append(before_comment)
    joined = "\n".join(lines)
    statements = [part.strip() for part in joined.split(";") if part.strip()]
    return len(statements)


def xml_lean(text: str) -> int:
    without_comments = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    return len(re.findall(r"<[^!?/][^>]*(?:/>|>)", without_comments))


def yaml_lean(text: str) -> int:
    count = 0
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        if re.match(r"^-?\s*[^:#]+:", s) or s.startswith("- "):
            count += 1
    return count


def lean_count(path: pathlib.Path, text: str) -> int:
    suffix = path.suffix
    if suffix in C_LIKE_EXTENSIONS:
        return c_like_lean(text)
    if suffix == ".py":
        return python_lean(text)
    if suffix == ".sh":
        return nonblank_noncomment(text, "#")
    if suffix == ".sql":
        return sql_lean(text)
    if suffix == ".xml":
        return xml_lean(text)
    if suffix in {".yml", ".yaml"}:
        return yaml_lean(text)
    return nonblank_noncomment(text, "#")


def collect() -> list[FileMetric]:
    metrics: list[FileMetric] = []
    for rel in tracked_files():
        full = ROOT / rel
        try:
            text = full.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            text = full.read_text(encoding="utf-8", errors="ignore")
        metrics.append(
            FileMetric(
                path=rel.as_posix(),
                group=group(rel),
                language=language(rel),
                physical=physical_lines(text),
                lean=lean_count(rel, text),
            )
        )
    return metrics


def summarize(metrics: list[FileMetric], key: str) -> list[tuple[str, int, int, int, float]]:
    buckets: dict[str, list[FileMetric]] = defaultdict(list)
    for metric in metrics:
        buckets[getattr(metric, key)].append(metric)
    rows = []
    for name, values in buckets.items():
        files = len(values)
        physical = sum(item.physical for item in values)
        lean = sum(item.lean for item in values)
        ratio = lean / physical if physical else 0
        rows.append((name, files, physical, lean, ratio))
    return sorted(rows, key=lambda row: row[3], reverse=True)


def markdown_table(rows: list[tuple[str, int, int, int, float]], first_col: str) -> str:
    lines = [
        f"| {first_col} | Files | Physical LOC | Lean logical LOC | Lean/Physical |",
        "|---|---:|---:|---:|---:|",
    ]
    for name, files, physical, lean, ratio in rows:
        lines.append(f"| {name} | {files:,} | {physical:,} | {lean:,} | {ratio:.1%} |")
    return "\n".join(lines)


def top_files(metrics: list[FileMetric], limit: int) -> str:
    rows = sorted(metrics, key=lambda item: item.lean, reverse=True)[:limit]
    lines = ["| File | Group | Language | Physical LOC | Lean logical LOC |", "|---|---|---|---:|---:|"]
    for item in rows:
        lines.append(f"| `{item.path}` | {item.group} | {item.language} | {item.physical:,} | {item.lean:,} |")
    return "\n".join(lines)


def render_report(metrics: list[FileMetric], date: str, commit: str) -> str:
    physical = sum(item.physical for item in metrics)
    lean = sum(item.lean for item in metrics)
    ratio = lean / physical if physical else 0
    files = len(metrics)
    source = f"HEAD `{commit}`"
    if has_worktree_changes():
        source += " + 当前工作区改动"
    return f"""# 精简代码量统计 — {date}

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：{source}。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| {files:,} | {physical:,} | {lean:,} | {ratio:.1%} |

## 按用途

{markdown_table(summarize(metrics, "group"), "Group")}

## 按语言

{markdown_table(summarize(metrics, "language"), "Language")}

## 最大文件（按 Lean logical LOC）

{top_files(metrics, 20)}

## 复跑

```bash
python3 scripts/dev/lean-loc-report.py --write docs/stats/loc-{date}-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", type=pathlib.Path, help="Write markdown report to this path.")
    parser.add_argument("--json", action="store_true", help="Print raw file metrics as JSON.")
    args = parser.parse_args()

    metrics = collect()
    if args.json:
        print(json.dumps([item.__dict__ for item in metrics], ensure_ascii=False, indent=2))
        return

    today = dt.date.today().isoformat()
    commit = run_git(["rev-parse", "--short=9", "HEAD"])
    report = render_report(metrics, today, commit)
    if args.write:
        output = args.write if args.write.is_absolute() else ROOT / args.write
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(report, encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
