# 业务术语字典

> 跨团队 / 新人 / 外部对接的术语统一口径。每条 1-2 句定义 + 链到详细设计。

## 调度与编排

| 术语 | 定义 | 详见 |
|---|---|---|
| **job** | 一个可被触发执行的业务任务定义。配置在 `job_definition` 表。 | [`../design/data-model-ddl.md`](../design/data-model-ddl.md) |
| **job_instance** | job 的一次执行运行时记录。状态机：CREATED → WAITING → READY → RUNNING → SUCCESS/FAILED/PARTIAL_FAILED/CANCELLED/TERMINATED。 | [`../architecture/core-model.md`](../architecture/core-model.md) |
| **partition** | job_instance 的分片单元。一个 job_instance 切成 N 个 partition 并行执行。 | [`../architecture/adr/ADR-005-partition-count-resolver-chain.md`](../architecture/adr/ADR-005-partition-count-resolver-chain.md) |
| **task** | partition 内最小执行单元（worker 实际跑的事）。一个 partition 可包含 1 到多 task。 | [`../architecture/core-model.md`](../architecture/core-model.md) |
| **trigger** | 触发器。CRON / FIXED_RATE / FIXED_DELAY / ONE_TIME / API / MANUAL / FILE_EVENT 7 种。 | [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) §1 |
| **launch** | 触发请求转化为 job_instance 的动作（trigger → orchestrator）。详见 ADR-003 T1/T2 拆分。 | [`../architecture/adr/ADR-003-launch-t1-t2-split.md`](../architecture/adr/ADR-003-launch-t1-t2-split.md) |
| **claim** | worker 从 outbox 消息中"认领"task 的动作。CLAIM 是悲观锁兜底，确保单 task 单 worker 执行。 | [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) §2 |
| **dispatch** | 文件分发动作（worker-dispatch 把生成好的文件推到外部渠道：SFTP / API / OSS / Email 等）。 | [`../design/file-pipeline-design.md`](../design/file-pipeline-design.md) |

## 工作流

| 术语 | 定义 | 详见 |
|---|---|---|
| **workflow** | 多 job 编排的 DAG。配置在 `workflow_definition` + `workflow_node` + `workflow_edge`。 | [`../architecture/workflow-dependency-guide.md`](../architecture/workflow-dependency-guide.md) |
| **workflow_run** | workflow 的一次执行运行时记录。 | 同上 |
| **node** | DAG 中的节点（START / END / TASK / GATEWAY / FILE_STEP / JOB）。 | 同上 |
| **edge** | DAG 中的边（SUCCESS / FAILURE / CONDITION / ALWAYS）。 | 同上 |
| **GATEWAY** | 网关节点，控制并行汇聚 join_mode（ALL_OF / ANY_OF / N_OF_M）。 | 同上 |
| **join_mode** | 网关汇聚策略。决定多前驱完成后是否推进。 | 同上 |

## 文件链路

| 术语 | 定义 | 详见 |
|---|---|---|
| **file_record** | 文件流转主表。一行 = 一次文件接收 / 处理 / 分发。 | [`../design/file-pipeline-design.md`](../design/file-pipeline-design.md) |
| **biz_date** | 业务日期。批量系统的核心时间维度，跟自然日不一定相等（节假日 / 周末 / 调休）。 | [`../design/batch-day-design.md`](../design/batch-day-design.md) |
| **batch_day** | 业务日历对应的批次日。同一 biz_date 的所有任务共享该 batch_day_instance。 | 同上 |
| **batch_window** | 批量窗口。某段时间允许某类任务执行；窗外任务挂起或拒绝。 | 同上 |
| **arrival** | 文件到达。INBOUND 文件落到 file_record 的瞬间。 | [`../design/sla-and-quality.md`](../design/sla-and-quality.md) §2 |
| **arrival_state** | 文件到达状态：WAITING_ARRIVAL / TRIGGERED / TIMEOUT。 | 同上 |

## 可靠性 / 治理

| 术语 | 定义 | 详见 |
|---|---|---|
| **outbox** | 事务性 Outbox 模式。状态写库 + Kafka 投递在同一事务，保证至少一次投递。 | [`../architecture/adr/ADR-002-transactional-outbox.md`](../architecture/adr/ADR-002-transactional-outbox.md) |
| **DLQ** | Dead Letter Queue。Kafka 消费失败超过阈值的消息进 DLQ topic + `dead_letter_task` 表，等人工 / AI 重放。 | [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) §1.8 |
| **compensation** | 补偿。任务失败后的自动 / 手动恢复动作（rerun job / retry partition / replay file / DLQ replay）。 | [`../runbook/compensation-cleanup.md`](../runbook/compensation-cleanup.md) |
| **misfire** | Quartz 触发器到期未执行的状态（调度器停机 / 资源紧张时发生）。默认 fire-now 策略：恢复后立刻补跑一次。 | [`../architecture/quartz-replacement-design.md`](../architecture/quartz-replacement-design.md) |
| **drain** | Worker 优雅下线。停止接新 task + 等已认领 task 完成 + 释放 lease + 退出。 | [`../runbook/rolling-upgrade-workers.md`](../runbook/rolling-upgrade-workers.md) |
| **lease** | 租约。worker 占用 partition 的时间窗口，过期未续被其他 worker 抢占。 | `PartitionLeaseProperties` |
| **shedlock** | 分布式锁（基于 Redis）。多实例同时持有时只允许一个执行调度任务。 | [`../runbook/ha-elastic-scaling.md`](../runbook/ha-elastic-scaling.md) |
| **bypass_mode** | 全链路安全旁路总开关（`batch.security.bypass-mode`）。仅本地 / 联调；prod 禁用。 | [`../coding-conventions.md`](../coding-conventions.md) §21 |
| **idempotency_key** | 幂等键。客户端在写接口的 `Idempotency-Key` header，相同值 N 次调用 = 1 次执行。 | [`../api/console-api-protocol.md`](../api/console-api-protocol.md) |

## 多租户 / 安全

| 术语 | 定义 | 详见 |
|---|---|---|
| **tenant** | 租户。配置 / 任务 / 文件 / 审计的最大隔离边界。 | [`../design/multi-tenant-and-security.md`](../design/multi-tenant-and-security.md) §1 |
| **GLOBAL_ROLES** | 跨租户角色（ADMIN / AUDITOR / CONFIG_ADMIN）。可读所有租户数据，写仍需审批。 | 同上 §2 |
| **secret_version** | 密钥版本。同一 secret_ref 多版本并存，支持轮换窗口 + 兼容期。 | 同上 §5 |
| **config_release** | 配置发布版本。DRAFT → PUBLISHED → GRAY → ROLLED_BACK，已创建实例不被在线修改穿透。 | 同上 §12 |

## 模块代号

| 简称 | 全名 | 职责 |
|---|---|---|
| **trigger** | batch-trigger | 触发器（Quartz / Wheel） |
| **orchestrator** | batch-orchestrator | 编排引擎，状态主机 |
| **worker-import** | batch-worker-import | 文件导入 worker |
| **worker-export** | batch-worker-export | 文件导出 worker |
| **worker-dispatch** | batch-worker-dispatch | 文件分发 worker |
| **worker-core** | batch-worker-core | Worker 共享框架 |
| **console-api** | batch-console-api | 控制台 BFF |
| **common** | batch-common | 跨模块共享代码 |

## 维护规则

- **本字典手写**——业务术语没有"代码源头"可自动生成。控制在 ~50 词以内，每条 1-2 句。
- **不重复 design 文档**——只给术语下定义 + 跳转链接，不展开原理。
- **新增术语阈值**：跨团队 / 跨模块出现 ≥ 3 次理解分歧时再加。否则散落在 design 文档里上下文解释更清楚。
- **不接受同义词污染**：每概念一个权威词，废弃词标 deprecated 并给替代。
