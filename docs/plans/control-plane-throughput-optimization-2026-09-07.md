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
- 阶段 5：1k 与 1w 已完成；10w 待最终复验。任何一档失败先保留现场，不自动删除证据。

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

冷启动原实现遇到未知 lag 后从 5/s 每 5 秒只恢复 1/s，提高实验上限反而放大排空时间。
现已改为首次健康低 lag 样本直接恢复到配置上限；运行期发生高 lag 或采样故障后仍按 AIMD
渐进恢复。80/s 会把吞吐压力转移为明显的 claim 排队，当前本机 10w 候选采用 60/s。

三轮 1w 中 Trigger/Orchestrator Hikari pending 均为 0，PostgreSQL lock waiter 仅瞬时出现 1，
应用日志无 ERROR、连接池耗尽或 429。每轮清理后 trigger request 与 job instance 残留均为 0。

10w 仍是本机容量边界复验，不应直接外推为生产容量；生产启用前仍需按副本数、Kafka 分区、
PostgreSQL IOPS/WAL 和混合 worker 负载重新标定。
