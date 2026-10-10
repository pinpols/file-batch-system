#!/usr/bin/env python3
"""Keep shared backend test configuration in its batch-test-support source."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHARED_CONFIG = "batch-test-support/src/main/resources/batch-test-defaults.yml"
TEST_CONFIG_GLOB = "**/src/test/resources/application-test.yml"


def flatten_yaml_paths(text: str) -> set[str]:
    stack: list[tuple[int, str]] = []
    leaves: set[str] = set()
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.match(r"^( *)([A-Za-z0-9_-]+):(?:\s*(.*))?$", line)
        if not match:
            continue
        indent = len(match.group(1))
        key = match.group(2)
        value = (match.group(3) or "").strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = ".".join([entry[1] for entry in stack] + [key])
        if value:
            leaves.add(path)
        else:
            stack.append((indent, key))
    return leaves


def inspect_sources(shared_text: str, test_configs: dict[str, str]) -> list[str]:
    shared_paths = flatten_yaml_paths(shared_text)
    errors: list[str] = []
    if not shared_paths:
        return [f"{SHARED_CONFIG}: 未发现共享测试配置项"]

    for name, text in test_configs.items():
        if "batch-test-defaults.yml" not in text:
            errors.append(f"{name}: 未导入共享测试配置 {SHARED_CONFIG}")
        duplicate_paths = shared_paths & flatten_yaml_paths(text)
        errors.extend(f"{name}: 重复定义共享测试配置 {path}" for path in sorted(duplicate_paths))

    return errors


def run_self_tests() -> bool:
    shared = "spring:\n  flyway:\n    locations: classpath:db/migration\nbatch:\n  scheduling:\n    await-termination-seconds: 1\n"
    cases = [
        ("共享配置导入且无重复应通过", {"module/application-test.yml": "spring:\n  config:\n    import: classpath:batch-test-defaults.yml\nbatch:\n  app: true\n"}, 0),
        ("遗漏共享配置导入应拦截", {"module/application-test.yml": "batch:\n  app: true\n"}, 1),
        ("测试 YAML 重复共享值应拦截", {"module/application-test.yml": "spring:\n  config:\n    import: classpath:batch-test-defaults.yml\n  flyway:\n    locations: classpath:db/migration\n"}, 1),
    ]
    passed = True
    for title, configs, expected in cases:
        actual = len(inspect_sources(shared, configs))
        ok = actual == expected
        print(f"{'PASS' if ok else 'FAIL'} {title}")
        passed &= ok
    return passed


def main() -> int:
    if not run_self_tests():
        print(
            "❌ 不通过 | code=TEST_CONFIG_SOURCES_SELF_TEST | gate=测试配置事实来源 | "
            "exit_code=1 | action=fix_and_retry",
            file=sys.stderr,
        )
        return 1
    shared_path = ROOT / SHARED_CONFIG
    test_configs = {path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8") for path in ROOT.glob(TEST_CONFIG_GLOB)}
    errors = inspect_sources(shared_path.read_text(encoding="utf-8"), test_configs)
    if errors:
        print("[test-config-sources] 检查失败:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        print(
            "❌ 不通过 | code=TEST_CONFIG_SOURCES | gate=测试配置事实来源 | "
            "exit_code=1 | action=fix_and_retry",
            file=sys.stderr,
        )
        return 1
    print(f"[test-config-sources] 已确认共享配置覆盖 {len(test_configs)} 个测试配置")
    print(
        "✅ 通过 | code=TEST_CONFIG_SOURCES | gate=测试配置事实来源 | "
        "exit_code=0 | action=none"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
