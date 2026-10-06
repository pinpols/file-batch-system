#!/usr/bin/env python3
"""守护 Testcontainers 复用容器的清理谓词，防止用不存在的标签字面量。

`withReuse(true)` 的容器由 Testcontainers 打上 `org.testcontainers.hash` 标签
（定义在 `org.testcontainers.containers.GenericContainer`），复用逻辑只认该标签。
清理脚本若改用 `reuse-hash` 之类的字面量判定「复用容器要保留」，谓词会恒不匹配
→ 正在运行的复用容器被判为孤儿删除，`withReuse` 的跨 JVM 复用加速静默失效。
本守护在静态层阻断该回归。

规则：
1. 引用 `org.testcontainers` 的 Shell 脚本不得出现 `reuse-hash` 字面量
   （该字面量在 Testcontainers 产物中不存在）。
2. 过滤 `label=org.testcontainers=true` 的 Shell 脚本必须同时引用真实标签
   `org.testcontainers.hash`，否则无法把复用容器与真孤儿区分开。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "TESTCONTAINERS_REUSE_LABEL"
GATE_NAME = "Testcontainers 复用标签判定"

TESTCONTAINERS_MARKER = "org.testcontainers"
REUSE_HASH_LABEL = "org.testcontainers.hash"
CONTAINER_FILTER = "label=org.testcontainers=true"
PHANTOM_LITERAL = "reuse-hash"


def violations(rel_path: str, text: str) -> list[str]:
    """返回单个脚本的违规说明；空列表表示通过。"""
    if TESTCONTAINERS_MARKER not in text:
        return []

    errors: list[str] = []
    if PHANTOM_LITERAL in text:
        errors.append(
            f"{rel_path}: 出现 '{PHANTOM_LITERAL}' 字面量，该标签在 Testcontainers "
            f"产物中不存在；真实复用标签是 '{REUSE_HASH_LABEL}'"
        )
    if CONTAINER_FILTER in text and REUSE_HASH_LABEL not in text:
        errors.append(
            f"{rel_path}: 过滤 testcontainers 容器（{CONTAINER_FILTER}）却未引用 "
            f"'{REUSE_HASH_LABEL}'，会把正在运行的复用容器误判为孤儿"
        )
    return errors


def tracked_shell_scripts() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "*.sh"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [ROOT / line for line in result.stdout.splitlines() if line.strip()]


def main() -> int:
    errors: list[str] = []
    scripts = tracked_shell_scripts()
    for path in scripts:
        rel_path = path.relative_to(ROOT).as_posix()
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            errors.append(f"{rel_path}: 无法读取（{exc}）")
            continue
        errors.extend(violations(rel_path, text))

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        print(
            f"  提示：Testcontainers 复用容器带 '{REUSE_HASH_LABEL}' 标签，"
            f"不要用 '{PHANTOM_LITERAL}' 判定",
            file=sys.stderr,
        )
        return 1

    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} | scripts={len(scripts)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
