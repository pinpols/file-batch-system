#!/usr/bin/env python3
"""守护:禁止 mapper XML 里出现「位置式」INSERT ... SELECT *（无显式列名）。

为什么:`INSERT INTO archive.x_archive SELECT * FROM batch.x` 是**按列位置**插入。
归档对齐只靠启动期 `ArchiveSchemaDriftCheck`,但它只比对热/冷表的**列名集合**,
**不校验列顺序**。热表/归档表列名相同但顺序变了(一次 migration 重排列就会)→
drift 守护放过,归档却**静默错位**(A 列数据进 B 列),是难发现的数据损坏。

规则:`insert into <table>` 后**必须**紧跟显式列名清单 `( ... )`,不得直接接 `select *`。
显式列名是按名映射,与列顺序无关,从根上消除错位风险。

退出码:发现违例 → 1(CI 挂红);无 → 0。
"""
import re
import sys
from pathlib import Path

# insert into <table>  直接接  select *(中间无 `(列名)` 列清单)。\s 跨换行。
POSITIONAL = re.compile(r"insert\s+into\s+[\w.]+\s+select\s+\*", re.IGNORECASE)


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    offenders: list[str] = []
    for xml in root.rglob("*Mapper.xml"):
        if "/target/" in str(xml):
            continue
        text = xml.read_text(encoding="utf-8", errors="replace")
        for m in POSITIONAL.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            snippet = " ".join(m.group(0).split())
            offenders.append(f"{xml.relative_to(root)}:{line}: {snippet}")

    if offenders:
        print("❌ 发现位置式 INSERT ... SELECT *(归档静默错位风险),改成显式列名:", file=sys.stderr)
        for o in offenders:
            print(f"   {o}", file=sys.stderr)
        print(
            "\n修法:insert into archive.x_archive (col1, col2, ...) select col1, col2, ... from batch.x",
            file=sys.stderr,
        )
        return 1

    print("✅ 无位置式 INSERT ... SELECT *(归档复制均为显式列名)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
