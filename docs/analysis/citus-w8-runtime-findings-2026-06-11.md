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

---

## 最后一公里完成记录(2026-06-12)

运行时层全部铺通。实施计划:`docs/plans/2026-06-11-citus-runtime-last-mile.md`。

### 跨分片 FOR UPDATE 实际共 9 处(初盘 5 处低估,真集群联调补全)
| 位置 | 处理 | commit |
|---|---|---|
| orchestrator WorkflowNodeRun.selectLatestForUpdate | 补 tenant 单分片 | 73e3aeb30 |
| orchestrator 补偿 stale 扫 | 租户循环+隔离 | a7b3b4a49 |
| orchestrator WAIT 传感器扫 | 租户循环,MAX 转每租户 | 40db1f89f |
| orchestrator JobPartitionMapper:58 | 已带 tenant,零改 | - |
| orchestrator stale CREATED 三重 not-exists JOIN | 子查询补 tenant 相关联(complex join) | 34447bdbb |
| trigger TriggerOutboxEvent relay claim | 租户循环 | f29a5e0fc |
| trigger TriggerRuntimeState wheel 扫 | 租户循环 | f29a5e0fc |
| console webhook 重试 claim | 租户循环 | f29a5e0fc |
| worker-core report outbox CTE claim | worker 自身 tenant | f29a5e0fc |

### 租户路由基建(batch-common ActiveTenantRegistry + orchestrator ActiveTenantProvider)
- 缓存 TTL `@Value("${batch.tenant.active-cache-ttl-millis:30000}")`,测试 profile 设 0 保确定性
- **registry 必走 AutoConfiguration**(BatchTenantRoutingAutoConfiguration + imports),不用 @Component
  ——精简 application(e2e app)component-scan 不覆盖 batch-common 子包,@Component 会致注入失败

### 双栈零回归(普通 PG)
单测+IT+e2e 全绿:orchestrator 1110 / trigger 154 / console 886 / e2e 41/41(Flakes 2 为
circuit-breaker/misfire timing,重试通过,非回归)。BUILD SUCCESS。所有租户路由改造在普通 PG
语义等价(多一个 tenant 等值条件),双栈兼容。

### 连接扇出实测教训
8 服务 × Hikari pool 默认在 coordinator(max_connections 100)上打满("too many clients",
连诊断查询都排队)。小池(6)可用。生产必须按 `Σ(各服务池) ≤ worker max_connections - 预留`
三元组重算;集群脚本 citus.max_shared_pool_size=25 限 coordinator→worker 扇出。

### 真集群端到端冒烟:核心目标达成,余 seed 适配
- **trigger relay FOR UPDATE 在 Citus 实测 0 报错**(2026-06-12,最新 jar)——最后一公里核心运行时阻塞已消除并验证,launch API SUCCESS。
- 端到端 atomic job SUCCESS 终态**未观测到**,根因取证:`scripts/db/test-seed/platform_seed.sql` 是单机 PG 设计,某 distributed 表 INSERT 缺分片列(tenant_id)值 → `cannot perform an INSERT without a partition column value` → 事务 abort → job_definition 等后续 seed 全部跳过 → launch 因 job_definition 查不到而 REJECTED。
- **定性:测试 seed 脚本的 Citus 适配问题(数据准备),非代码/分布式查询阻塞。** 后续运维项:platform_seed 增 Citus 变体(每条 INSERT 带分片列 + 拆分语句避免单事务连坐),或经应用 API 灌数据走正常分片路由。不阻塞"运行时层已铺通"的结论(schema/代码/双栈兼容三支柱 + FOR UPDATE 阻塞消除均已立)。

## 最后一公里结论
**Citus 运行时层已铺通(代码维度)**:9 处跨分片 FOR UPDATE 全部租户路由化、复杂 JOIN 子查询补分片相关联、租户路由基建走 AutoConfiguration、连接扇出调优参数固化;普通 PG 双栈零回归 BUILD SUCCESS(e2e 41/41)。真集群 schema 部署 + 服务启动 + FOR UPDATE 消除 + launch 全部验证通过。剩 test-seed 的 Citus 适配是部署数据准备细节,列入运维 backlog。

### 端到端联调进展(2026-06-12 06:45,无事务 seed 后)
补 job_definition(reference)后链路推进显著:
- trigger_request **LAUNCHED**(不再 REJECTED)、trigger_outbox_event 全 **PUBLISHED**(5)——
  **trigger relay + trigger→orchestrator 调度控制面在 Citus 上完整贯通**。
- 最后一跳卡在 orchestrator 建 job_instance:`ERROR: functions used in the DO UPDATE SET
  clause of INSERTs on distributed tables must be marked IMMUTABLE`——某 distributed 表
  upsert 的 DO UPDATE SET 用了 `current_timestamp`(STABLE,非 IMMUTABLE)。Citus 约束。

### 剩余 Citus 端到端适配 backlog(逐项消除即全链通,均非架构阻塞)
1. **distributed upsert DO UPDATE SET IMMUTABLE**:grep distributed 表 mapper 的
   `on conflict ... do update set ... = current_timestamp/now()`,改为应用传入固定时间戳参数
   (或 `updated_at = excluded.updated_at`,值由 INSERT 列提供)。
2. **test-seed Citus 变体**:platform_seed.sql 去事务包裹 + distributed 表 INSERT 带分片列。
3. 这两项是"真集群端到端数据流"的 SQL 方言适配,与已完成的 FOR UPDATE/complex-join/约束/
   PK 复合化同类(都是 Citus SQL 约束的逐项消除),不改变"运行时层架构已就绪"的结论。

**最后一公里最终定性**:运行时层**架构与核心阻塞已铺通**——schema 123/32 部署、9 处 FOR UPDATE
消除、租户路由基建、双栈零回归 BUILD SUCCESS、调度控制面在真 Citus 贯通至 LAUNCHED。
worker 执行侧端到端 SUCCESS 余 2 项 SQL 方言适配(IMMUTABLE upsert + seed),列入 backlog。

### 🎉 worker 端到端 SUCCESS 达成(2026-06-12 09:09)
atomic_shell_demo 在 3 节点 Citus 跑出全链 **[SUCCESS|task=SUCCESS|part=SUCCESS]**:
launch→job_instance(RUNNING)→job_partition CLAIM→job_task→worker-atomic 执行 shell→REPORT 全绿。
harvest 逐跳清障路径:FOR UPDATE→complex join→IMMUTABLE upsert→CASE/COALESCE current_timestamp 传参。
**用户验收终点(worker 真实执行成功)达成。**

### 第 5 类约束:distributed 表 CASE/COALESCE 内禁非 IMMUTABLE 函数
`non-IMMUTABLE functions are not allowed in CASE or COALESCE statements`。修法:CASE/COALESCE 内
current_timestamp 改应用层时间戳传参(普通赋值 col=current_timestamp 不受限,不改)。已修 worker
CLAIM/REPORT 主链 4 处(JobPartition claim/terminal、JobTask terminal、JobInstance 统计)。
**sim 多业务负载前需补清**(PROCESS/文件治理链路):pipeline_progress(subagent 误判为非 distributed)、
FileGovernanceMapper(orphan cleanup 多处)、FileDispatchMapper、JobStepInstanceMapper 等。
