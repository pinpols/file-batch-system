#!/usr/bin/env python3
# 用途：检查脚本头注释、脚本索引与 CI 工作流索引。
# 用法：在仓库根目录执行 python3 scripts/ci/check-script-governance.py；参数与前置环境见下方说明及所属 README。
"""校验脚本头、脚本索引和 CI 工作流索引，避免执行入口与说明漂移。"""

from __future__ import annotations

import re
import subprocess
import ast
import importlib.util
import io
import tokenize
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
GATE_CODE = "SCRIPT_GOVERNANCE"
GATE_NAME = "脚本治理"
DIRECT_SCRIPT_CALL = re.compile(r'^\s*"?\$ROOT/(scripts/[A-Za-z0-9_./-]+\.sh)"?(?:\s|$)')
LINK = re.compile(r"(?<!!)\[[^\]]*]\(([^)]+)\)")
SCRIPT_SUFFIXES = {".sh", ".ps1", ".bash", ".zsh"}


def is_script(path: Path) -> bool:
    if path.suffix in SCRIPT_SUFFIXES:
        return True
    if path.as_posix() in {".githooks/pre-commit", ".githooks/pre-push"}:
        return True
    return ("scripts" in path.parts or path.parts[0] == "load-tests") and path.suffix in {".py"}


def versionable_paths(root: Path) -> set[Path]:
    output = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root, text=True,
    )
    return {Path(name) for name in output.split("\0") if name and (root / name).is_file()}


def index_links(index: Path) -> dict[Path, bool]:
    targets: dict[Path, bool] = {}
    fenced = False
    for line in index.read_text(encoding="utf-8").splitlines():
        if re.match(r"^\s*(?:```|~~~)", line):
            fenced = not fenced
            continue
        if fenced:
            continue
        described = bool(re.search(r"[\w\u4e00-\u9fff]", LINK.sub("", line)))
        for match in LINK.finditer(line):
            raw = match.group(1).strip().strip("<>").split(maxsplit=1)[0]
            if raw.startswith(("https://", "http://", "mailto:", "#", "codex:")):
                continue
            target = unquote(raw.partition("#")[0])
            if target:
                resolved = (index.parent / target).resolve()
                targets[resolved] = targets.get(resolved, False) or described
    return targets


def check_indexes(root: Path, paths: set[Path]) -> list[str]:
    errors: list[str] = []
    # SQL 仅登记已有路径，不检查或改写 SQL/Flyway 正文。
    scripts = {p for p in paths if "tests" not in p.parts and (
        is_script(p) or (p.suffix == ".sql" and p.parts[0] in {"scripts", "load-tests"})
        or p.parent == Path("scripts/ci/baselines") and p.name != "README.md"
    )}
    workflows = {p for p in paths if p.parent == Path(".github/workflows") and p.suffix in {".yml", ".yaml"}}
    actions = {p for p in paths if p.as_posix().startswith(".github/actions/") and p.name in {"action.yml", "action.yaml"}}
    indexes = {p for p in paths if p.name == "README.md" and (
        p.parts[0] == "scripts" or p in {Path("load-tests/README.md"), Path(".github/workflows/README.md"), Path(".github/actions/README.md")}
    )}
    owners: dict[Path, Path] = {}
    for asset in sorted(scripts | workflows | actions):
        if asset in workflows:
            index = Path(".github/workflows/README.md")
        elif asset in actions:
            index = Path(".github/actions/README.md")
        else:
            index = next((parent / "README.md" for parent in asset.parents if parent / "README.md" in paths), Path("README.md"))
        owners[asset] = index
        indexes.add(index)
    # scripts 一级职责目录必须有独立索引；嵌套 SQL 等配套资产不另建无必要的目录。
    for asset in sorted(scripts):
        if asset.parts[0] == "scripts" and len(asset.parts) > 2:
            required = Path(*asset.parts[:2]) / "README.md"
            if required not in paths:
                errors.append(f"missing script directory index: {required}")
    for index in sorted(indexes):
        if index not in paths:
            errors.append(f"missing asset index: {index}")
            continue
        links = index_links(root / index)
        for target in links:
            if not target.exists():
                errors.append(f"stale index link: {index} -> {target.relative_to(root) if target.is_relative_to(root) else target}")
        for asset, owner in owners.items():
            if owner == index and not links.get((root / asset).resolve()):
                errors.append(f"index must link asset with purpose: {index} -> {asset}")
        if index.parts[0] == "scripts" and index != Path("scripts/README.md"):
            parent = next((p / "README.md" for p in index.parent.parents if p / "README.md" in paths), None)
            if parent and not index_links(root / parent).get((root / index).resolve()):
                errors.append(f"parent README must link child index with purpose: {parent} -> {index}")
    return errors


def check_headers(root: Path, paths: set[Path]) -> list[str]:
    errors: list[str] = []
    for path in sorted(p for p in paths if is_script(p)):
        header: list[str] = []
        for line in (root / path).read_text(encoding="utf-8-sig").splitlines()[:40]:
            if line.strip() and not line.lstrip().startswith("#"):
                break
            header.append(line)
        for field in ("用途", "用法"):
            values = [m.group(1).strip() for line in header if (m := re.match(rf"^# {field}：(.+)$", line))]
            if len(values) != 1 or len(values[0]) < 6 or re.search(r"TODO|待补|待填写", values[0], re.IGNORECASE):
                errors.append(f"script header requires one nonempty '# {field}：...' before code: {path}")
        if not any(re.match(r"^# 用途：.*[\u4e00-\u9fff]", line) for line in header):
            errors.append(f"script purpose must use simplified Chinese: {path}")
    return errors


def check_script_comments(root: Path, paths: set[Path]) -> list[str]:
    # 复用已有说明性注释判定，保留许可证、分析器指令和代码示例的相同边界。
    spec = importlib.util.spec_from_file_location("comment_language", ROOT / "scripts/ci/check-comment-language.py")
    language = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(language)
    errors: list[str] = []
    for path in sorted(p for p in paths if is_script(p)):
        source = (root / path).read_text(encoding="utf-8-sig")
        if path.suffix == ".py":
            comments = [(token.start[0], token.string) for token in tokenize.generate_tokens(io.StringIO(source).readline) if token.type == tokenize.COMMENT]
            # Python 的模块、类和函数文档字符串属于说明，不扫描普通业务字符串。
            for node in ast.walk(ast.parse(source)):
                if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
                    doc = ast.get_docstring(node)
                    if doc and re.search(r"[A-Za-z]", doc) and not re.search(r"[\u4e00-\u9fff]", doc):
                        errors.append(f"Python docstring must use Chinese: {path}:{node.body[0].lineno}")
        else:
            comments = list(enumerate(source.splitlines(), 1))
        for number, line in comments:
            if language.prose_comment(line, path.suffix or ".sh"):
                errors.append(f"script explanatory comment must use Chinese: {path}:{number}")
    return errors


def main() -> int:
    paths = versionable_paths(ROOT)
    errors = check_indexes(ROOT, paths) + check_headers(ROOT, paths) + check_script_comments(ROOT, paths)

    ci_index = (SCRIPTS / "ci" / "README.md").read_text(encoding="utf-8")
    guards = sorted(
        path.name
        for path in (SCRIPTS / "ci").iterdir()
        if path.is_file()
        and path.suffix in {".py", ".sh"}
        and path.name.startswith(("check-", "validate-"))
    )
    for guard in guards:
        if guard not in ci_index:
            errors.append(f"CI guard is not registered in scripts/ci/README.md: {guard}")

    direct_scripts: set[str] = set()
    for caller in SCRIPTS.rglob("*.sh"):
        for line in caller.read_text(encoding="utf-8").splitlines():
            match = DIRECT_SCRIPT_CALL.match(line)
            if match:
                direct_scripts.add(match.group(1))
    for script in sorted(direct_scripts):
        result = subprocess.run(
            ["git", "ls-files", "--stage", "--", script],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        entries = [line for line in result.stdout.splitlines() if line.endswith(f"\t{script}")]
        if not entries:
            errors.append(f"directly invoked script is not tracked by Git: {script}")
        elif any(line.split(" ", 1)[0] != "100755" for line in entries):
            errors.append(f"directly invoked script must have Git mode 100755: {script}")

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(
        f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} "
        f"| guards={len(guards)} direct-executable={len(direct_scripts)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
