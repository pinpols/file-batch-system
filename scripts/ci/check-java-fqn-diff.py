#!/usr/bin/env python3
# 用途：识别差异新增代码中的 Java 全限定类型名，排除注释和字面量。
# 用法：python3 scripts/ci/check-java-fqn-diff.py <新增行清单>。
"""复用 Java 掩码规则识别新增代码中的全限定类型名。"""
import re
import runpy
import subprocess
import sys
from pathlib import Path

MASK_JAVA = runpy.run_path(str(Path(__file__).with_name('check-test-conventions.py')))['mask_java']
FQN = re.compile(r'\bjava\.(?:util|net|io|time|nio|lang)\.[A-Z][a-zA-Z0-9_]+')


def findings(diff_lines, read_source):
    """在完整源文件掩码上核验差异行，保留原始内容用于诊断。"""
    cache = {}
    found = []
    for raw in diff_lines:
        if not FQN.search(raw):
            continue
        path, number, _ = raw.split(':', 2)
        if path not in cache:
            cache[path] = MASK_JAVA(read_source(path)).splitlines()
        index = int(number) - 1
        if index < 0 or index >= len(cache[path]):
            raise ValueError(f'差异行不在源文件内：{path}:{number}')
        source = cache[path][index]
        if re.match(r'\s*(?:import|package)\b', source):
            continue
        if FQN.search(source):
            found.append(raw)
    return found


def main():
    lines = Path(sys.argv[1]).read_text(encoding='utf-8').splitlines()
    matches = findings(lines, lambda path: subprocess.run(
        ['git', 'show', f'HEAD:{path}'], check=True, capture_output=True, text=True
    ).stdout)
    for line in matches[:10]:
        print(line)


if __name__ == '__main__':
    main()
