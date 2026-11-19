# 控制面按租户分片：风险评估与收益（2026-11-19）

> 评估性质：架构评估，不授权实施。结论基于当前代码、迁移、数据库目录和 2026-11-18/19 的容量证据；不是生产验证。

## 一、结论

1. **当前不建议直接实施控制面分片**。先做一轮“容量归因 + 调优”（Tier 0.5），确认单 PostgreSQL 的写入/锁竞争确实是硬瓶颈，而不是后台扫描、autovacuum 或热点 SQL 造成的可消除开销。
2. 如果确认需要分片，采用**按租户组整库分片（每片完整 schema）**，而不是拆运行时表或换分布式 PG。
   - 理由：216 张 `batch` 表带 `tenant_id`，同租户内定义与运行时强关联（303 个外键，`job_definition` 被 44 张表引用、`trigger_request` 被 49 张表引用）。整库分片可以保留全部外键和事务边界；“运行时表拆出去、定义库共享”必须先断掉这些外键或做 CDC 同步，风险更高。
3. 分片的真正成本不在“多起几个 PG”，而在**全局 ID、跨租户查询、单主调度器、迁移扇出、备份恢复和租户迁移**这六项。没有这些配套，分片只会把单库问题换成一致性问题。

## 二、事实地图（当前证据）

| 维度 | 数据 | 来源 |
|---|---|---|
| 带 `tenant_id` 的表 | 216 | `information_schema`（本地 platform 库） |
| 无 `tenant_id` 的表 | 19：`shedlock`、`flyway_schema_history`、`stateful_backend_binding`、`step_registry`、`workflow_node_run`、`pipeline_step_run`、`console_maintenance_state` 等 | 同上 |
| 外键 | 303 个；`trigger_request` 被 49 表引用、`job_definition` 44、`workflow_definition` 16、`business_calendar` 5 | `pg_constraint` |
| 序列 | 103 个（BIGSERIAL） | `information_schema.sequences` |
| 分区 | 1,215 个；`job_instance`、`outbox_event`、`console_usage_daily` 按月分区 | `pg_inherits` |
| RLS | `batch` 35 条策略、`archive` 5 条 | `pg_policies` |
| 单主调度器 | 49 个 `@SchedulerLock` 任务（ShedLock over Redis） | 源码 |
| 跨租户扫描 | `selectRunnableTenantIds` / `selectAllTenantIds` 被等待恢复、SLA 监控、指标、告警重发等使用 | 源码 |
| 全局按 ID 查询 | 9 个 `*AnyTenant` 方法；213 处 `selectById(` 调用（多数带 tenant，但 Console 路由与前端链接依赖数字 ID） | 源码 |
| 既有分片能力 | 业务面 `batch.datasource.business.routing`：CONFIG（hash + silo）或 TABLE placement，`provision-biz-shard.sh` 可幂等加片 | 源码/脚本 |
| Outbox 分片 | `selectPending` 已支持 `hashtext(tenant_id) % shardTotal`，但只是单库内的扫描分片 | `OutboxEventMapper.xml` |

## 三、方案对比

| 方案 | 做法 | 优点 | 主要风险 | 结论 |
|---|---|---|---|---|
| **A. 按租户组整库分片（silo）** | 每片一套完整 `batch` schema，按租户路由；全局目录库保存 tenant→shard | 保留 303 个外键和同租户事务；与业务面既有 routing 模式一致；单租户可独占 | 跨租户查询/聚合、ID 全局性、迁移与备份扇出、租户迁移 | **推荐（若分片）** |
| B. 运行时表分片 + 共享定义库 | 只把 trigger_request/workflow_run/job_instance/outbox 等分片，定义表共享 | 定义只维护一份 | 必须断开或跨库同步 44+16+49 个外键关系；跨库事务不可用；CDC 一致性 | 不推荐 |
| C. 分布式 PG（Citus/CRDB/YGO） | 用分布式数据库承载控制面 | 理论横向扩展 | advisory lock 语义、分布式唯一约束、RLS、触发器/分区、Flyway、跨片事务都要重做；CRDB 不支持 RLS/advisory lock | 仅在单租户写入也撑不住时再评估 |

## 四、收益（需要什么条件才成立）

- **写入吞吐**：租户分布均匀时，N 片近似 N 倍单库上限。当前单库在 8 核上 25 rps、10 万请求时 `job_instance` avg 28.3 s、完成 1.3–4/s（见[压测记录](../verifications/load-test-local-2026-11-15.md)），分 2 片理论可把有效写入提高约 1.7–1.9 倍（考虑跨片开销）。
- **故障半径**：单片故障只影响该片租户；但需要按片部署、按片告警和租户级降级说明。
- **大租户隔离**：silo 租户独占一片，与业务面 `siloOverrides` 语义一致。
- **不引入新存储**：标准 PG 的运维、PITR、监控体系可复用。

收益成立的前提：租户数足够多且分布均匀；否则“少数大租户”仍会把单片打满，收益接近 1。

## 五、风险清单（按严重度）

| 编号 | 风险 | 触发条件 | 影响 | 缓解 | 证据/状态 |
|---|---|---|---|---|---|
| **SH-01** | 全局数字 ID 冲突 | 各片独立 BIGSERIAL；跨片出现相同 id | Console 链接、`*AnyTenant` 查询、审计与外部集成按 id 定位错误；数据串租户 | 所有 API 以 `(tenant, id)` 为键；Console 路由带 tenant；或引入全局 ID（snowflake/ULID）；9 个 AnyTenant 方法逐项消灭 | 待设计 |
| **SH-02** | 跨租户查询与聚合 | 平台管理员列表、使用统计、告警/审计总览、跨租户对账 | 需要 scatter-gather；无全局快照，分页/排序/计数复杂；延迟上升 | 建立聚合层（异步物化视图或查询服务）；明确“最终一致”的展示约定 | 待设计 |
| **SH-03** | 49 个单主调度器 | 每片需要独立 leader；SLA/指标/清理等全局任务要 fan-out | 任务重复执行或漏执行；Redis 锁键需按片隔离 | SchedulerLock 键加片前缀；全局任务改为按片 fan-out + 汇总；建立调度归属清单 | 待设计 |
| **SH-04** | 定义与运行时的归属 | 租户迁移、跨租户共享定义（默认租户 demo） | 迁租户必须连定义一起搬；共享定义无法跨片引用 | A 方案下定义随租户走；禁止跨租户引用；共享样例改为每片自带 | 待设计 |
| **SH-05** | 迁移扇出 | 386 个迁移 × N 片 | 版本漂移、部署非原子、回滚逐片 | 每片 Flyway + 迁移前 schema 校验；发布编排按“先迁移、再切流量”；保留单片回滚 | 待设计 |
| **SH-06** | 备份/PITR | N 套数据库 | 无跨片一致性时间点；恢复演练成本 ×N；RPO/RTO 需按片承诺 | 每片独立 PITR + 恢复演练；跨片一致性靠业务对账而不是数据库时间点 | 待设计 |
| **SH-07** | 租户迁移/再平衡 | 大租户增长或热点租户 | 控制面数据量大，需双写/回放/切换窗口；无现成工具 | 先做只读迁移 + 短双写窗口 + 行数/摘要校验；迁移期间禁止该租户新运行 | 待设计 |
| **SH-08** | Worker/Orchestrator 路由 | Worker 注册、claim/report、SDK base-url | Worker 连错片 → 任务不可见或重复 | 按租户下发 orchestrator 地址；Kafka 消费组按片划分；SDK 增加 shard 发现 | 待设计 |
| **SH-09** | 可观测性割裂 | 指标、日志、追踪、告警按片 | 现有单库仪表盘失效；告警阈值需按片 | 指标加 shard 维度；全局面板聚合；告警按片+全局双阈值 | 待设计 |
| **SH-10** | 跨租户事务丢失原子性 | 管理员批量操作、跨租户清理 | 部分成功、无法回滚 | 改为按租户逐片执行 + 补偿；明确“非原子”的操作语义 | 待设计 |
| **SH-11** | 运维复杂度 | N 片升级、扩容、故障处理 | 值班负担与误操作风险上升 | Helm/Compose 分片模板、统一巡检脚本、演练 | 待设计 |
| **SH-12** | 测试成本 | IT/E2E 需要多片夹具 | 测试矩阵 ×N；CI 时长上升 | 分片路由的单测 + 两片集成；E2E 只在 canary 片跑 | 待设计 |

## 六、成本粗估

| 工作项 | 人月 |
|---|---:|
| 目录库 + 租户路由 + 单片兼容开关 | 1.0 |
| 全局 ID 策略与 9 个 `*AnyTenant` 收敛 | 1.0 |
| 调度器 fan-out（49 个）与归属清单 | 1.0 |
| Console 跨租户聚合（查询/统计/告警/审计） | 1.5 |
| 迁移扇出与发布编排 | 0.5 |
| 备份/PITR/监控/运维模板 | 1.0 |
| 租户迁移工具（双写、校验、回切） | 2.0 |
| **合计** | **约 8 人月**（不含业务侧联调与压测） |

Canary（2 片 + 少量新租户）预计 4–6 周；全量迁移取决于租户数量和数据量。

## 七、前置验证：先做 Tier 0.5 容量归因

在投入分片前，先回答“单库还有多少可挖的空间”：

1. 复跑 25 rps / 10w，但在压测期间**降低后台扫描频率**（SLA 监控、指标扫描、告警重发），观察 `job_instance` 时长是否从 28 s 回落。
2. 对 `trigger_request`、`outbox_event`、`job_instance` 做 autovacuum 参数与统计信息核对，确认 autovacuum 是否与热点写竞争。
3. 评估减少每次运行的写放大（outbox 批量、claim/report 合并、结算围栏范围）。
4. 记录基线：单库在目标注入率下的 PG CPU、阻塞会话、run 时长、收敛时间。

**Go / No-Go 阈值（建议）**：

- Go：调优后 25 rps 持续 10w 仍无法在 90 分钟内收敛，且 PG CPU 持续 > 80% 或阻塞会话 > 20；同时业务目标确实需要 > 单库容量。
- No-Go：调优后能在目标窗口收敛，或 PG CPU < 70%；此时分片属于过度设计。

## 八、若决定实施：分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 目录库（tenant→shard）、连接路由、单片开关（默认单库行为不变） | 单库回归全绿；路由单测覆盖 hash/silo/未知租户 |
| P1 | 新租户落到 canary 片；存量租户仍单片 | 新租户端到端（触发、运行、查询、告警）通过 |
| P2 | Console 跨租户聚合（列表、统计、审计） | 与单库结果对账一致；分页/排序语义明确 |
| P3 | 调度器 fan-out 与全局任务汇总 | 无重复执行；SLA/指标按片可见 |
| P4 | 租户迁移工具（只读迁移 + 双写 + 校验 + 回切） | 单租户迁移演练成功，回切可用 |
| P5 | 运维模板、备份/PITR 演练、容量基线 | 每片 RPO/RTO 达标；故障演练通过 |

## 九、待补证据

- 单库在目标注入率下的**归因**（后台扫描 / autovacuum / 热点 SQL 各占多少）；
- 租户规模分布（大租户占比）——决定分片收益是否成立；
- Console 跨租户查询清单（哪些必须强一致、哪些可最终一致）；
- 目标规模（日作业量、并发租户数、SLA）。
