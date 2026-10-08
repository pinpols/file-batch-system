# 扩容现状盘点 + biz 数据层路径决策(2026-06-14)

> **历史决策快照（2026-06-14）**：本文保留当时对 Citus、分区和租户路由的评估。PR #470/#479 已合并并引入 platform 表分区；表格中“待合”等状态只表示记录时点。当前扩容事实和事项请复核 [`scalability-assessment.md`](../architecture/scalability-assessment.md)、相关 ADR 与 [`todo-master.md`](./todo-master.md)。

> 状态:决策已定。与 `docs/agent-baseline.md`「citus 冻结」一致,本文是其判据的合并记录。
> 关联:tag `citus-poc-2026-06-14`(冻结快照)、PR #470(分区抽 main)、
> `docs/design/partition-idempotency-decision.md`。

## 0. TL;DR

- **控制面(platform `batch.*`)扩容/裁冷:已有读副本、归档与 outbox/job_instance 月分区能力**；容量是否足够须按当前部署负载评估。
- **biz 业务数据层:仍是 greenfield**——三条扩容手段没一条真正落到它头上。
- **Citus 当前不解决真实问题**:压在了不需要的控制面,真会涨的 biz 层它又因 RLS 上不了(PoC 实测证伪)。已冻结为只读参考。
- **biz 真要扩,走分区 + 应用层租户路由**(都保 RLS、贴合多租 OLTP),需求驱动,不上 Citus。

## 1. 各层扩容现状(按"做没做 + 覆盖哪层")

| 方案 | 解决 | RLS | 现状 |
|---|---|---|---|
| 声明式分区 | 单表太大 | ✅ 单机分区 RLS 正常 | **platform 已覆盖**:`process_staging`、`outbox_event`、`job_instance`；biz 业务表是否分区需按当前业务 schema 与迁移复核，本文不作为现状清单 |
| 应用层租户路由(tenant→PG 实例) | 跨实例水平扩 | ✅ 每实例普通 PG,RLS 完整 | **没做**:`ActiveTenantRegistry` 只做租户上下文(RLS GUC);唯一 `AbstractRoutingDataSource` 是 console-api 主从路由;biz 单数据源,无实例分片 |
| 垂直扩 + 读副本 + archive 裁冷 | 先撑住 | ✅ | **做了但不覆盖 biz**:读副本仅 console-api(worker/orchestrator 禁用);归档仅 platform 运行态(job_instance/workflow_run),非 biz 数据;垂直扩=换机器 |

**结论**:先撑住的手段全瞄准 platform/控制面;biz 业务数据层零扩容机制。

## 2. Citus 价值评估(为什么冻结)

1. **没有当前规模问题**:docs/agent-baseline.md 早写「需求驱动,非当前缺口」。无表撞 PG 上限;真实数据量痛点(process_staging 118GB)是**分区**解的,不是 Citus。控制面 PG 写有 10-15× 余量,瓶颈在控制面逻辑非 PG。
2. **压错层**:citus 分支 49 表复合 PK + distribute 全在 **platform 控制面**(编排元数据,量级有限),不是会涨的 biz。
3. **真会受益的 biz 上不了**:biz 租户隔离 100% 靠 RLS;PoC(多节点 coord+worker)实测 **RLS 在 Citus 分布表上坏掉**(返 0 行/写报错,GUC 跨节点未正确传播)。详见 [[project_biz_citus_rls_poc_broken]] 记录。
4. **从未进生产**:citus 永不合 main。

**历史结论**(非当前状态账):① outbox/job_instance **分区**由 PR #470/#479 纳入 main;② "新多租大表复合 PK"前瞻规约(不依赖 Citus 也成立);③ 一次「当时不该上 + biz 此路不通」的实证。

## 3. biz 数据层推荐路径(弃 Citus)

biz 是**多租户 OLTP**,扩容天然轴是按租户。比 Citus 干净、且全保 RLS:

1. **声明式分区**(biz_date / tenant LIST)——单表太大,单机分区 RLS 正常(process_staging 同款)。
2. **应用层按租户路由**(tenant→PG 实例)——跨实例水平扩,每实例普通 PG、RLS 完整;复用已建的 `ActiveTenantRegistry`。Citus 为自动分片牺牲 RLS,这条不牺牲。
3. **先榨 runway**:垂直扩 + 读副本(扩到 worker 侧)+ archive 裁冷(扩到 biz 数据)。

**为什么比 Citus 对路**:Citus 甜区是「单租户大到一台装不下」或「跨租户大分析并行」;biz 单租户大概率一台够、且多租隔离禁止跨租户查询(无 Citus 擅长的并行分析负载)。Citus 的强项 biz 用不上,Citus 的代价(RLS 不兼容)biz 付不起。

## 4. 解冻 Citus 的条件

仅当**同时**满足:① 真撞 PG 写墙(控制面 10-15× 余量耗尽 / 单租户 biz 数据超单机);② 确认走**自托管** Citus 而非托管(Azure 托管 Citus = 路径 B,绕开自运维)。流程见 docs/agent-baseline.md「解冻流程」(从 tag 起分支 → 重审计 main delta → 重跑 distribute + sim)。

## 5. 一句话

这版 Citus 是一次押错层的能力储备 + 一次有价值的「现在不该上」实证。最该捞回 main 的是**分区**(PR #470),distribute 留 citus 冻结。biz 扩容是 greenfield,但路径清晰(分区 + 租户路由),需求驱动。
