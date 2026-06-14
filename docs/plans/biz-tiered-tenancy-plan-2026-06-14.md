# biz 数据层 Tiered 多租实施方案(设计存档)

> 状态:**设计存档,非当前缺口**。需求驱动——仅当 biz 撞单机墙时按本方案执行。
> 定位:把"该做时照着做"的设计落到纸上,现在**不建**(YAGNI;customer_account ~22k 行,无需求)。
> 关联:[`docs/analysis/scaling-state-and-biz-path-2026-06-14.md`](../analysis/scaling-state-and-biz-path-2026-06-14.md)(扩容现状+路径决策)、CLAUDE.md「citus 冻结」。

## 0. 一句话

biz 扩容终局 = **Tiered**:海量小租户走 **Pooled 分片**(每片多租 + RLS),少数巨型/合规租户走 **Silo**(独占库,物理隔离)。**弃自托管 Citus**(RLS 在分布表上实测坏掉,见 PoC);走应用层租户路由(Notion/Figma/Salesforce 路线)。

## 1. 为什么是 Tiered + 为什么现在不建

- **扩容次序**:垂直扩 → 单实例分区(retention)→ **应用层租户路由(Pooled 分片)** → **大租户拎 Silo** →(永不自托管 Citus)。
- **现在不建**:无任何 biz 表撞单机;垂直扩+分区能买数年 runway。提前建 = 重蹈 Citus「给没有的问题压错层」。
- **延后零数据模型成本**:Tiered 纯应用层(路由 + N 数据源),**不改 schema/数据模型**,需求来了直接加。

## 2. 架构契合度(地基已就位,且被测试焊死)

| 前提 | 现状 |
|---|---|
| 访问统一带 tenant(可干净分片根本) | ✅ 全模块 `MapperXmlTenantGuardArchTest` + `BaseMapperXmlTenantGuardArchTest` 静态强制每条 mapper 带 tenant_id |
| biz 访问收敛 | ✅ 仅 import/export/process 3 worker 模块经各自 `BusinessDataSourceConfiguration` 访问 biz |
| 无跨租户 biz 聚合 | ✅ platform 层不碰 biz;biz 无跨片 join(embarrassingly shardable) |
| 平台/biz 已双数据源 | ✅ 加 N 个 biz 数据源是扩展既有模式 |
| 租户上下文 + RLS | ✅ `ActiveTenantRegistry` + biz.* RLS 现成 |

**结论**:最贵的前提(严格租户作用域)已满足且强制。剩下全是应用层装配。

## 3. 实施方案

### 3.1 路由层(核心)
- 在 **batch-common** 新增共享 `BusinessRoutingDataSource extends AbstractRoutingDataSource`,`determineCurrentLookupKey()` 从 `ActiveTenantRegistry` 取当前 tenant → 查 placement 映射 → 返回目标数据源 key。
- **3 个 worker 模块**(import/export/process)的 `BusinessDataSourceConfiguration` 改为注入这个共享路由数据源,**别 3 处各写一遍**(顺带消除现有重复)。
- 目标数据源集合 = {pooled-shard-0..M-1} ∪ {silo-<tenant>...},按需懒初始化 Hikari 池。

### 3.2 tenant→placement 映射
- 平台库新增 `biz_tenant_placement(tenant_id PK, tier, datasource_key, status)`,tier ∈ {POOLED, SILO}。
- 路由时查(带缓存,TTL 可配;参照 ActiveTenantRegistry 缓存惯例)。新租户默认 POOLED + 按 hash 落某 shard。

### 3.3 RLS 分层
- **Pooled 分片**:每片仍多租 → **RLS 照旧**(`SET LOCAL app.tenant_id`,现有 `RlsTenantSessionSupport` 不变)。
- **Silo**:库里单租户 → RLS 多余(物理隔离);路由层对 silo 可跳过 SET(或无害保留)。

### 3.4 biz schema 多目标同步(**最大前置债**)
- biz 不走 Flyway(手工脚本 + docker init)。N 个 shard/silo 的 biz schema 必须一致,否则漂移噩梦。
- **前置工作**:给 biz 上**可重复、可对 N 目标执行的迁移机制**(给 business 库引迁移工具,或脚本化 apply-to-all + drift 校验)。**这是 Tiered 的第一道工序,先于路由。**

### 3.5 tier 分配 + 升舱迁移
- 分配:新租户落 POOLED;运维可标记某租户升 SILO。
- 升舱迁移工具:`pg_dump` 该租户子集(WHERE tenant_id=) → 灌目标 → 切 placement → 校验 → 清源。带切换窗口/一致性处理。

## 4. 不做 / 边界

- **不上自托管 Citus**:RLS 在 Citus 分布表实测坏掉(返 0 行/写报错,见 PoC 记录);跨租分析负载在多租隔离下本就不存在 → Citus 甜区用不上、代价付不起。
- **托管路径 B**:若将来要"DB 透明分片 + 省自运维",选 **Azure Cosmos DB for PostgreSQL(托管 Citus)**,而非自托管——但仍要先验 RLS。
- **archive 不做**:biz 控量用分区 + drop/detach 旧分区,不搭 archive 机器(系统记录归档高风险 + 越界合规)。

## 5. 触发条件(何时执行本方案)

任一成立:
- 单个租户 biz 数据逼近单机容量/性能上限;
- biz 总量超出"垂直扩 + 单实例分区"的 runway(由现有磁盘/库体积告警预警:`NodeDiskWillFillIn4Hours` / `pg_database_size>200GB` / `NodeDiskSpaceLow`)。

## 6. 分阶段

| 阶段 | 内容 | 前置 |
|---|---|---|
| **P0(前置债)** | biz schema 多目标迁移机制 + drift 校验 | — |
| **P1** | batch-common 路由数据源 + placement 表 + 3 worker 接入(单 shard 起步,行为等价现状) | P0 |
| **P2** | Pooled 分片:扩到 M 个 shard,新租户 hash 落片,RLS 片内保持 | P1 |
| **P3** | Silo tier:大租户升舱迁移工具 + 路由支持 silo | P2 |

P1 可"单分片"无损上线(等价现状),把路由层先焊进去、零风险,后续 P2/P3 按需扩。

## 7. 业界对照(背书)

- **Notion**:vanilla Postgres 按 workspace 应用层分片(480 shard),**弃 Citus**。
- **Figma**:自建 DBProxy 路由层分片 Postgres,**弃分布式 SQL**。
- **Salesforce / Shopify / AWS SaaS Lens**:Tiered(小租户 pooled + 大租户 silo)是规模化标准。

本方案与头部一致:Pooled 分片(保 RLS)为主力,Silo 为巨型/合规特例,共用应用层租户路由。

## 8. 风险

- 🟡 **N 目标 schema 漂移**(biz 无 Flyway)→ P0 治理是硬前置。
- 🟡 **路由正确性**:路错=租户写错位/查空;但物理隔离使隔离更强(路由 bug 不跨租泄漏)。
- 🟡 **运维 ×N**:N 实例备份/监控/容灾;Tiered 把"少数大租户"单独养可控成本。
- 🟡 **升舱迁移**:逐租户 dump/load/切换,需窗口 + 一致性。
- 🟢 console 跨租聚合:biz 多租隔离本禁跨租查询,基本无 scatter-gather。
