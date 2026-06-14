#!/usr/bin/env python3
"""守护:biz.* 业务表的多租 + RLS 强制约束(CLAUDE.md §多租隔离 + RLS 盲区防回归)。

业务库 biz.* 不走 Flyway,表定义在 scripts/db/business/create_biz_tables.sql,
RLS 严格策略在 rls-phase-a-strict.sql 的 tables[] 数组里逐表 CREATE POLICY。

风险:新增 biz 基表时漏加 tenant_id 列、或漏进 RLS tables[] 数组 → 该表无租户隔离,
跨租户数据可被串读/串写,且 e2e 全绿挖不出(RLS 是 DB 层,测试库未必翻 strict)。

判定(对 create_biz_tables.sql 里每张 biz 基表,分区子表 PARTITION OF 豁免):
  A. 必须含 tenant_id 列
  B. 必须出现在 rls-phase-a-strict.sql 的 tables[] 数组(strict RLS 覆盖)
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DDL = ROOT / "scripts/db/business/create_biz_tables.sql"
RLS = ROOT / "scripts/db/business/rls-phase-a-strict.sql"

CREATE_RE = re.compile(
    r"create\s+table\s+(?:if\s+not\s+exists\s+)?(biz\.[a-z0-9_]+)(.*)", re.IGNORECASE
)


def base_biz_tables_with_bodies(sql: str):
    """返回 {表名: 列定义体}，跳过 `PARTITION OF`(分区子表,继承父表 tenant_id/RLS)。"""
    tables = {}
    lines = sql.splitlines()
    i = 0
    while i < len(lines):
        m = CREATE_RE.search(lines[i])
        if not m:
            i += 1
            continue
        name, rest = m.group(1), m.group(2)
        if "partition of" in rest.lower():
            i += 1
            continue
        # 收集到该 CREATE 语句结束(括号配平后遇到行尾分号)。
        body, depth, started = [], 0, False
        j = i
        while j < len(lines):
            seg = lines[j]
            depth += seg.count("(") - seg.count(")")
            if "(" in seg:
                started = True
            body.append(seg)
            if started and depth <= 0:
                break
            j += 1
        tables[name] = "\n".join(body)
        i = j + 1
    return tables


def rls_covered_tables(sql: str):
    m = re.search(r"tables\s+TEXT\[\]\s*:=\s*ARRAY\[(.*?)\]", sql, re.IGNORECASE | re.DOTALL)
    if not m:
        return set()
    return set(re.findall(r"'(biz\.[a-z0-9_]+)'", m.group(1), re.IGNORECASE))


def main() -> int:
    if not DDL.exists() or not RLS.exists():
        print(f"❌ 缺少 DDL/RLS 文件: {DDL} / {RLS}")
        return 1

    tables = base_biz_tables_with_bodies(DDL.read_text(encoding="utf-8"))
    rls = rls_covered_tables(RLS.read_text(encoding="utf-8"))

    no_tenant, no_rls = [], []
    for name, body in sorted(tables.items()):
        if not re.search(r"\btenant_id\b", body):
            no_tenant.append(name)
        if name not in rls:
            no_rls.append(name)

    print(f"ℹ️  biz 基表 {len(tables)} 张;strict RLS 覆盖 {len(rls)} 张")
    ok = True
    if no_tenant:
        ok = False
        print("❌ 以下 biz 基表缺 tenant_id 列(违反多租隔离):")
        print("\n".join(f"   - {t}" for t in no_tenant))
    if no_rls:
        ok = False
        print("❌ 以下 biz 基表未进 rls-phase-a-strict.sql 的 tables[] 数组(无 strict RLS 隔离):")
        print("\n".join(f"   - {t}" for t in no_rls))

    if not ok:
        print("\n💥 新增 biz 基表必须含 tenant_id + 登记进 strict RLS tables[] 数组。")
        return 1
    print("✅ 所有 biz 基表均含 tenant_id 且被 strict RLS 覆盖")
    return 0


if __name__ == "__main__":
    sys.exit(main())
