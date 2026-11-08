#!/usr/bin/env python3
# 用途：把 CodeQL SARIF 每条结果的 relatedLocations 裁到 GitHub 代码扫描可展示的上限。
# 用法：python3 scripts/ci/trim-codeql-sarif-related-locations.py <SARIF 文件或目录>。
"""上传前裁剪相关位置，避免 GitHub 丢弃超额位置后在状态页留下警告。

GitHub 对每条结果只保留前 100 个 relatedLocations，其余记入分析警告。
这里按同样顺序保留前 100 个，不改规则、主位置和 partialFingerprints，
因此不拆开已聚合的告警指纹。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

MAX_RELATED_LOCATIONS = 100


def trim_sarif(document: dict) -> int:
    removed = 0
    for run in document.get("runs") or []:
        for result in run.get("results") or []:
            related = result.get("relatedLocations")
            if not isinstance(related, list) or len(related) <= MAX_RELATED_LOCATIONS:
                continue
            removed += len(related) - MAX_RELATED_LOCATIONS
            result["relatedLocations"] = related[:MAX_RELATED_LOCATIONS]
    return removed


def sarif_files(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    if path.is_dir():
        return sorted(item for item in path.glob("*.sarif") if item.is_file())
    return []


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("缺少 SARIF 文件或目录", file=sys.stderr)
        return 1
    targets: list[Path] = []
    for raw in argv[1:]:
        targets.extend(sarif_files(Path(raw)))
    if not targets:
        print("未找到 SARIF 文件", file=sys.stderr)
        return 1
    for target in targets:
        document = json.loads(target.read_text(encoding="utf-8"))
        removed = trim_sarif(document)
        target.write_text(
            json.dumps(document, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"{target}: removed {removed} relatedLocations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
