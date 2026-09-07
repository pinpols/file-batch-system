# 控制面吞吐闭环优化计划（2026-09-07）

## 结论

现有 10 万请求证据表明，入口持久化与 Kafka 投递能够承接突发，但单机控制面持续排空约为
18-20 jobs/s。Atomic worker 执行 p95 仅几十毫秒，主要压力位于 trigger launch 消费、实例构建、
claim/report 与共享 PostgreSQL 写入竞争。继续提高 Trigger 并发或固定 Relay 速率只会更快地产生积压。

本分支只改控制面流量整形和已被指标证明的热路径，不改变 job/partition/task 状态机、幂等键、
租约、Kafka offset、事务 outbox 与终态 CAS 语义。所有新策略默认关闭或保留固定基线回退。

## 五个阶段

| 阶段 | 改造 | 安全边界 | 验收 |
|---|---|---|---|
| 1 | Trigger 采样 launch consumer group lag，以 AIMD 调整 Relay 有效发布速率 | API 仍先写 DB；未知信号降到最小速率；不丢 outbox | 单测覆盖未知、软/硬阈值和恢复；配置全环境对齐 |
| 2 | 复核 Orchestrator trigger consumer 的 in-flight 边界；仅在连接池证据表明确有必要时引入 pause/resume | 当前 listener 同步执行且并发固定为 6，已天然有界；禁止重复增加 semaphore、线程池或队列 | 压力下无连接池耗尽，单实例同时 launch 不超过配置并发 |
| 3 | 复用现有 Hikari 与消费耗时压力信号，不重复建设指标 | 已有 `hikaricp.connections.*`、`batch.trigger.launch.consume.duration` 和 Kafka queue age；不为采样增加业务表全表 count | 指标低基数，能关联 lag、池等待与消费耗时 |
| 4 | 复核 launch T1/T2 数据库往返，只合并语义等价 SQL | 不跨请求合并事务；不弱化 dedup、RLS、advisory lock | 真 PG 并发 IT 覆盖重复、崩溃恢复和跨租 |
| 5 | 以 1k/1w/10w 阶梯复验并固化灰度参数 | 10 万只是容量边界，不直接成为生产承诺 | HTTP 接收明确、全终态、无残留，留存 lag/锁/WAL/池指标 |

## 当前进度

- 阶段 1：代码与配置已完成。默认 `adaptive-release-enabled=false`，固定 40 events/s 行为不变；未知 lag 会收缩到最小速率，采样使用独立调度线程并复用单个 Kafka AdminClient。
- 阶段 2：代码复核完成。现有 listener 同步执行、并发固定为 6，不存在无界 in-flight；当前不增加冗余背压组件。
- 阶段 3：代码复核完成。消费时长、Kafka queue age 与 Hikari 指标均已存在；阶段 5 直接采集并关联分析。
- 阶段 4：完成首项语义等价优化：trigger request 与同 dedup key 最新 job instance 投影由两次查询合并为一次；真实 PostgreSQL IT 已覆盖最新 attempt 与租户条件。
- 阶段 5：1k、1w 与 10w 严格容量复验均已完成。10w 达到全终态、零请求错误、零残留；
  自适应限流在 Kafka lag 触及软阈值后主动降速并恢复，证明保护链路生效。

## 阶段 1 参数

| 参数 | 默认值 | 说明 |
|---|---:|---|
| `max-publish-events-per-second` | 40 | 单 Trigger 进程上限 |
| `min-publish-events-per-second` | 5 | lag 未知或持续高压时的最小前进速率 |
| `lag-soft-threshold` | 1000 | 达到后每个新样本降低当前速率的 25% |
| `lag-hard-threshold` | 5000 | 达到后每个新样本将当前速率减半 |
| `adaptive-increase-step` | 1 | lag 恢复后每个样本的加性恢复步长 |
| `lag-sample-interval-millis` | 5000 | Kafka Admin 只读采样间隔 |
| `lag-query-timeout-millis` | 2000 | 查询超时；超时发布未知样本 |

这些值是保守灰度起点，不是生产最终参数。生产启用前必须按 orchestrator 副本数、topic 分区数、
PostgreSQL 连接预算和真实 worker 组合重新取数。

## 已完成验证

| 范围 | 结果 |
|---|---|
| Trigger Relay、预算、AIMD、停机栅栏与调度器单测 | 27 tests，0 failure / 0 error |
| Orchestrator launch 校验与真实 PostgreSQL mapper IT | 11 tests，0 failure / 0 error；覆盖无历史实例和最高 `run_attempt` |
| Java 可读性、suppression 登记与 Spotless | 通过 |
| feature switch、应用/Compose 默认值、Helm env 同步 | 通过 |
| Helm lint | 1 chart，0 failure |

## 本机阶梯复验

以下数据来自同一套 `local,benchmark` 容器、Atomic SQL 任务和严格容量门禁。代码默认与
`.env.local` 基线仍为 40 events/s；60/80 仅为本机 A/B，不是生产参数承诺。

| 轮次 | Relay 上限 | 结果 | 总耗时 | HTTP P95 | task claim P95 | Kafka lag 峰值 | outbox 峰值 |
|---|---:|---|---:|---:|---:|---:|---:|
| 1k 冒烟 | 40/s | 1000/1000，零错误、零残留 | 约 1m08s | 111ms | 1.317s | 未持续积压 | 未持续积压 |
| 1w 基线 | 40/s | 10000/10000，零错误、零残留 | 约 4m38s | 36ms | 1.395s | 13 | 5955 |
| 1w 80/s 冷启动原实现 | 80/s | 10000/10000，零错误、零残留 | 约 4m46s | 30ms | 0.393s | 13 | 7668 |
| 1w 80/s 冷启动修复后 | 80/s | 10000/10000，零错误、零残留 | 约 3m02s | 83ms | 21.401s | 约 1000 | 2835 |
| 1w 60/s 冷启动修复后 | 60/s | 10000/10000，零错误、零残留 | 约 3m18s | 96ms | 6.484s | 119 | 4003 |
| 10w 严格容量复验 | 60/s | 100000/100000，零错误、零残留 | 约 33m20s | 77ms | 33.078s | 1067 | 71488 |

冷启动原实现遇到未知 lag 后从 5/s 每 5 秒只恢复 1/s，提高实验上限反而放大排空时间。
现已改为首次健康低 lag 样本直接恢复到配置上限；运行期发生高 lag 或采样故障后仍按 AIMD
渐进恢复。80/s 会把吞吐压力转移为明显的 claim 排队，当前本机 10w 候选采用 60/s。

10w 遥测共采集 900 个样本。自适应发布速率在 33-60/s 之间变化；Trigger Hikari active 峰值
22、Orchestrator Hikari active 峰值 14，两者 pending 峰值均为 0；PostgreSQL lock waiter 峰值
为瞬时 2，WAL 增量约 8.43 GB。Atomic worker 执行 p95 为 39ms，而 task claim p95 为
33.078s，进一步确认瓶颈位于 Trigger outbox 排空以及 Orchestrator/PostgreSQL 控制面状态写入，
不在 worker 执行阶段。

所有轮次应用日志均无 ERROR、连接池耗尽或 429。10w 自动清理后 `trigger_request`、
`job_instance` 与 `trigger_outbox_event` 残留均为 0。60/s 仅作为当前本机灰度候选；代码、
`.env.local` 与部署默认值继续保持 40/s，避免把单机 A/B 参数直接带入生产。

10w 是本机容量边界复验，不应直接外推为生产容量；生产启用前仍需按副本数、Kafka 分区、
PostgreSQL IOPS/WAL 和混合 worker 负载重新标定。

## 延伸优化：第 4、6、7、8 项

### 4. 合并事务内更新后查询

已完成控制面 claim 热路径收敛：

- `job_task` 的 READY → RUNNING 认领改为 `UPDATE ... RETURNING *`。成功时直接使用数据库返回的
  新版本行，不再按主键重查；CAS 未命中时仍重读竞争者状态，保持原 409/幂等响应语义。
- `job_partition` 的 READY → RUNNING 认领同样直接返回新行。独立 partition lifecycle 成功路径
  少一次查询；task 与 partition 联动认领仍在同一事务内，任一 CAS 失败继续整体回滚。
- 原有 Outbox 批量领取、批量发布结果回写和 lease renew 已使用 set-based
  `UPDATE ... RETURNING`，本轮不重复建设。

所有 SQL 继续包含 `tenant_id`、前态和 `version` 条件；没有删除终态守卫、invocation fence、
幂等键或事务边界。真实 PostgreSQL 并发认领测试与相关单测共 42 个通过。

### 6. 按任务规模选择持久化粒度

新增 `batch.orchestrator.persistence-granularity.enabled`，默认 `false`。开启后，仅当 DYNAMIC/AUTO
作业没有显式指定 `targetItemsPerPartition` 或 `targetBytesPerPartition` 时，按规模选择 partition/task
数量：

| 档位 | 默认判定 | 默认目标粒度 |
|---|---|---|
| compact | 不超过 10 万条或 256 MiB | 单 partition/task |
| standard | 不超过 1000 万条或 8 GiB | 100 万条或 512 MiB/partition |
| large | 超过 standard | 50 万条或 256 MiB/partition |

该策略只调整 fan-out 数量，不省略 `job_partition`、`job_task`、step、outbox 或审计记录，因此没有
引入第二套状态机。调用方显式目标始终优先；最终仍受现有 `min/maxPartitionCount` 和 256 上限约束。
Compose、Helm、开关登记和运维手册已同步。规模分级与显式优先级共 22 个测试通过，feature switch、
配置默认值、Helm env 同步和 `helm lint` 均通过。

### 7. Orchestrator 按租户分片水平扩展

现有 Outbox 查询按 `hash(tenant_id) % shardTotal` 分片，任务认领和状态回报继续依赖数据库 CAS；
没有拆分租户状态机或削弱幂等约束。部署侧保留两种模式：

- `STATIC` 默认模式使用 StatefulSet ordinal 和固定 `shardTotal`，扩缩容通过 Helm 滚动更新。
- `DYNAMIC` 模式使用 Redis ZSET 成员租约生成确定性的 `shardTotal/shardIndex`，允许 HPA 或 KEDA
  调整 StatefulSet 副本数。Redis 不可用或当前 Pod 不在成员集合时停止轮询，不使用过期分配继续消费。

本轮补齐动态模式的生产收尾：`application.yml` 显式绑定 mode、成员键、heartbeat、TTL 和 memberId；启动时
拒绝非正心跳或小于三个心跳周期的 TTL；Pod 正常销毁时通过 `@PreDestroy` 主动注销，异常退出继续由
TTL 兜底。Helm 默认按 namespace/release 生成独立成员键，避免共享 Redis 的多套部署互相参与分片。
Compose、Helm、开关登记与运维文档已同步，完整回归脚本新增 autoscale values 渲染断言，
防止后续出现“创建了 HPA 但应用仍按 STATIC 运行”的配置漂移。

边界保持不变：本项扩展的是 Orchestrator Outbox 推进能力，不会消除 PostgreSQL 写入、Kafka 分区数
或下游 Worker 吞吐上限。生产启用前仍需在真实 Redis/StatefulSet 上执行 2→4→2 扩缩容演练，并核对
Outbox 无重复副作用、无永久遗漏且 backlog 最终归零。

### 8. 外置 PostgreSQL 生产基线

应用 Chart 继续只连接外置 PostgreSQL，不在应用 Helm 生命周期内创建或升级数据库。HA 模板补齐
以下可标定起点：

- Patroni 一主两备、PgBouncer transaction mode 与同步复制策略保持不变。
- 4 GiB 实例基线采用 `shared_buffers=1GiB`、`work_mem=4MiB`、
  `maintenance_work_mem=512MiB`。
- `max_wal_size=16GiB`、`checkpoint_timeout=15min`、
  `checkpoint_completion_target=0.9`、WAL compression，降低 10 万突发写入的 checkpoint 尖峰。
- 增强 autovacuum worker/扫描频率并开启 `track_io_timing`，用于热表膨胀和 I/O 瓶颈诊断。

新增只读预检：

```bash
PG_READINESS_URL='postgresql://user:password@host:5432/batch_platform?sslmode=require' \
PG_EXPECTED_APP_CONNECTIONS=160 PG_CONNECTION_RESERVE=40 PG_READINESS_STRICT=1 \
  scripts/db/check-postgres-control-plane-readiness.sh
```

检查 SQL 独立位于 `scripts/db/postgres-control-plane-readiness.sql`。本机执行已正确拒绝开发库的
`max_wal_size=1GiB`、`checkpoint_timeout=5min`，并提示 `track_io_timing`/checksum 未开启；HA YAML
解析、shell 语法和 ShellCheck 通过。仓库侧实现已完成，但生产完成态仍要求 DBA 在真实 writer/pooler、
真实 IOPS 和备份故障域上执行 strict 预检、PITR 与 failover 演练。
