# Citus W8 真集群实测发现(2026-06-11,3 节点 citus 13.0/PG17)

> 结论:**schema 层全面就绪**(Flyway V1..V179 在 coordinator 原生跑通;01-distribute 把
> 49 张表转 distributed[+74 分区子表共 123]、32 张 reference,完整性自检过;orchestrator
> 进程能启动并持续运行)。**运行时层存在一类结构性改造项**:全局轮询器模式,清单见下。

## distribute 过程中实测出的 6 条 Citus 约束(已全部编码进 01-distribute.sql)

| # | 约束 | 处置 |
|---|---|---|
| 1 | create_distributed_table 不能在多语句事务中(表带 reference-FK 时) | 逐条顶层语句 + pg_temp 幂等函数 |
| 2 | 被引用表必须先分布(FK 依赖序) | trigger_request 提为锚,显式序 |
| 3 | 分区表出站 FK 的转换期校验是跨节点 complex join,不支持 | ②.5 暂卸 → ④ 加回 |
| 4 | distributed↔local 间不可存在 FK | ②.6 通用 FK 暂存表机制(stash→distribute→restore) |
| 5 | 所有 UNIQUE 必须含分布列 | **V179**:8 条"父id域内唯一"漏网补 tenant_id(W1-W6 只动了 PK 的盲区) |
| 6 | 带触发器的表默认拒分布 | `citus.enable_unsafe_triggers=on`(colocate 后触发器内查询必落本地分片,语义安全) |
| 7 | FK `ON DELETE SET NULL` 含分布列不支持(列级形态也拒) | 2 条转应用层守护(V171 先例;job_execution_log/file_audit_log) |

## 运行时实测发现(下一阶段“应用层 Citus 适配”的工作清单)

1. **跨分片 `FOR UPDATE` 不支持**:`could not run distributed query with FOR UPDATE/SHARE`。
   全仓 5 处(3 mapper:CompensationCommandMapper / JobPartitionMapper / WorkflowNodeRunMapper)。
   这些是全局轮询/抢占查询(SKIP LOCKED 模式)→ 必须改"按租户路由"(外层循环活跃租户,
   WHERE tenant_id = ? 等值过滤后单分片路由,FOR UPDATE 即合法)或改 coordinator 本地队列。
2. **连接扇出**:app Hikari 池 × 分片数放大,默认配置秒打爆 worker max_connections
   ("too many clients")。缓解:coordinator `citus.max_shared_pool_size`(可热载);
   生产需统筹 worker max_connections / app 池 / shard 数三元组。
3. **全局扫描提示**:Citus 自身 HINT "Consider using an equality filter on the distributed
   table's partition column"——所有跨租户运维查询(console 全局列表/审计检索)在 Citus 上是
   fan-out 查询,语义可用但要按 §3.3 决策(走 replica/数仓,不打 coordinator)。

## 与 citus-introduction-plan 的对账

- §0.5 两大 POC ✅(RLS 透传 + ugk 档B);PK 复合化全量完成(49 表,超原 23 表口径)
- 原"12-20 周"估算中 schema 部分:**实际 1 个工作日内完成**(agent 流水线 + 既有铺路);
  剩余真实工作量集中在:全局轮询器按租户路由改造(5 处 FOR UPDATE + OutboxPollScheduler
  类全局扫描)、连接池三元组调优、worker/console 全栈在 Citus 上的 e2e —— 即"运行时适配"阶段。

## 连接三元组建议(最后一公里 T5 固化)

约束链:`Σ(各服务 Hikari max-pool) ≤ citus.max_shared_pool_size × 有效并发系数 ≤ worker max_connections - 预留`。
本地基线:8 服务 × Hikari 10 = 80 潜在 coordinator 连接,每个分布式查询经
coordinator 扇出 → `citus.max_shared_pool_size=25` 限流(实测可活,排队可接受);
生产按 worker max_connections(默认 100→建议 ≥400)与分片并行度重算。
集群管理已脚本化:`scripts/local/citus-cluster.sh {up|initdb|status|down}`。
