# 后端架构审核 · 调度准入与投递恢复

2026-11-21 对 `main` 的 `51c82f49b` 审核。发现 4 项运行行为问题，其中 2 项 P1、2 项 P2。定向反例覆盖等待补槽、人工恢复与 Outbox 重新领取。当前事项见 [TODO Master](../plans/todo-master.md)。

## P1 · 同一线程池里的父任务等不到子任务（ARCH-20261121-01）

Workflow 进入终态后，`WorkflowTerminalOutboxService:95` 注册提交后补槽。每个租户的父任务提交到 `LAUNCH_WAIT_RESUME_EXECUTOR`。父任务认领等待请求后，向同一个线程池提交逐条 launch，再同步等待全部完成。池的核心和最大线程数相等，默认 4，队列默认可容纳 100 条。子任务成功入队时不会触发调用线程执行的拒绝策略。[终态回调](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/WorkflowTerminalOutboxService.java) · [提交父任务，第 74 行](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/WorkflowLaunchWaitResumeTrigger.java) · [提交子任务和等待，第 98–108 行](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/WorkflowLaunchWaitReconciler.java) · [线程池配置，第 99–110 行](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/config/OrchestratorAsyncConfiguration.java)

**实测**　使用当前源码的调度器、提交后触发器和 Spring 执行器配置。在 4 个租户各有 1 条可认领等待时，用屏障让 4 个父任务同时完成认领。JVM 栈中 4 个工作线程都停在 `CompletableFuture.join`，队列里有 4 个子任务，恢复请求的方法调用次数为 0。该实例的补槽池无法自行推进这些任务。定时扫描也将逐条恢复提交到这个池。此复现使用模拟等待数据，未连接业务库。

**整改与验收**　解除同池父子同步等待。保留租户去重、认领租约和每轮有界并行。分别用并发度 1 和默认 4 的真实执行器验证终态回调与定时扫描，要求每条等待进入 launch，父任务完成后同租户仍能触发下一轮。

## P1 · 多槽位只释放一个时，等待队列无法领取（ARCH-20261121-02）

同一租户、Workflow、业务日和 dry-run 模式下，新请求可以占用 `1..上限` 中的空槽。已有等待请求尚无自己的根运行时，`claimDue` 却要求这组内所有 `CREATED/RUNNING` 运行都已离开。定时扫描和终态唤醒共用此查询。只要组内持续有一个活跃运行，已排队请求就可能长期得不到机会，即使期间有空槽供新请求使用。[等待领取 SQL，第 70–80 行](../../batch-orchestrator/src/main/resources/mapper/WorkflowLaunchWaitMapper.xml) · [新请求准入 SQL，第 101–116 行](../../batch-orchestrator/src/main/resources/mapper/WorkflowRunMapper.xml) · [ADR-050](../architecture/adr/ADR-050-multiple-active-runs-per-biz-date.md)

**实测**　向 PostgreSQL 准入语句传入上限 2，建两条运行和一条到期等待。槽 2 的运行结束后执行正式 `claimDue`，等待项 `attempt_count` 仍为 0。随后执行新请求的正式准入 SQL，新请求占用槽 2。

**整改与验收**　等待候选按同组配置和可用槽判定，最终占槽继续交由数据库唯一索引裁决。保留同请求已有根运行的幂等补收尾、租户隔离和到期限制。验收覆盖上限 1、上限 2 满载后只释放一个槽、全满与并发抢同一空槽。

## P2 · 暂停的 Workflow 恢复时只能使用原槽位（ARCH-20261121-03）

[ADR-050](../architecture/adr/ADR-050-multiple-active-runs-per-biz-date.md) 规定运行离开 `CREATED/RUNNING` 即释放槽位。[ADR-044](../architecture/adr/ADR-044-instance-pause-resume-batch-day-serial.md) 规定人工恢复从既有 DAG 继续。Console 的恢复入口转发给 Orchestrator，后者按预期状态把原行从 `PAUSED` 更新为 `RUNNING`，没有重新选择空槽。若另一运行占用了原槽，即使同组其他槽空闲，部分唯一索引也会阻止恢复。[Console 入口，第 79–82 行](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/web/ConsoleWorkflowRunController.java) · [恢复服务，第 109–111、142–148 行](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunManagementApplicationService.java) · [状态更新，第 216 行](../../batch-orchestrator/src/main/resources/mapper/WorkflowRunMapper.xml) · [V386 唯一索引](../../db/migration/V386__workflow_active_runs_per_biz_date.sql)

**实测**　上限为 2。暂停槽 1 的 A，新请求 B 占槽 1，槽 2 空闲。用正式 SQL 恢复 A，PostgreSQL 返回 `23505`。A 仍为 `PAUSED`，同组活跃运行只有 B。此次未测 HTTP 错误映射。

**整改与验收**　恢复时为原运行原子地取得可用槽位，提交后再推进 DAG。验证原槽被占但另有空槽时原 run 恢复、已完成节点不重跑。满槽时保持暂停并返回明确的容量结果，并发恢复与新 launch 始终受同一上限约束。

## P2 · Outbox 旧发布者可以覆盖新一轮领取（ARCH-20261121-04）

Orchestrator 领取派发事件时递增 `publish_attempt`，批量领取仅返回 ID。发布者等待 Kafka 确认后，按租户、ID 和 `PUBLISHING` 写成功、失败或放弃。旧发布者停顿超过超时与调度锁有效期，另一实例可回收并重新领取同一事件。此时状态再次为 `PUBLISHING`，旧结果仍能命中新一轮。Trigger Outbox 和 Worker REPORT Outbox 已各自校验领取凭据，可复用这一做法。[领取及回写，第 129–171 行，回收第 231 行](../../batch-orchestrator/src/main/resources/mapper/OutboxEventMapper.xml) · [发布及审计，第 157–166 行](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/DefaultScheduleForwarder.java) · [调度锁](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mq/OutboxPollScheduler.java) · [Trigger 对照](../../batch-trigger/src/main/resources/mapper/TriggerOutboxEventMapper.xml) · [Worker 对照](../../batch-worker/core/src/main/resources/mapper/WorkerReportOutboxPgMapper.xml)

**实测**　依次执行 A 领取、超时回收、B 领取、A 写失败、B 写成功。A 的旧失败更新 1 行，B 的成功更新 0 行，数据库留下 `FAILED / publish_attempt=2`。若 B 已收到 Kafka ACK，数据库仍会安排重投。此处验证了数据库判定，未实际发送 Kafka 消息。

**整改与验收**　领取时返回本轮凭据，成功、失败和放弃写回均匹配该凭据。区分真实发送尝试与实际生效的状态转换，避免落错轮次的重试审计。用两次领取验证旧成功、旧失败和旧放弃均更新 0 行，新发布者能独立完成；再用 Kafka 确认与 DB 回写交错验证派发记录。

## 文档中另有两处需要校准

- [系统职责 §2.3](../architecture/system-scope-boundary.md) 将 Outbox + Kafka 写作 exactly-once。[ADR-002](../architecture/adr/ADR-002-transactional-outbox.md) 定义的是至少一次投递与 Worker 幂等认领，与当前发布过程一致。建议在职责清单明确投递保证和消费者责任。
- [ADR-050](../architecture/adr/ADR-050-multiple-active-runs-per-biz-date.md) 称归档表不保留槽位列。[V386](../../db/migration/V386__workflow_active_runs_per_biz_date.sql) 实际给冷表添加该列并默认取 1。两条[按运行归档](../../batch-orchestrator/src/main/resources/mapper/WorkflowRunMapper.xml)和[按实例归档](../../batch-orchestrator/src/main/resources/mapper/SuccessInstanceArchiveMapper.xml)的插入都省略它。按当前实现，冷表该值无法证明历史运行实际使用的槽位。若仅用于并发裁决，说明这一限制即可；若要审计原槽位，需另行调整两条复制路径。这项核对限于 DDL 与 SQL。

## 审核覆盖与证据边界

| 边界 | 核对范围 | 本轮证据 |
|---|---|---|
| 模块所有权和依赖 | 根 Maven reactor、平台模块与 SDK、业务层直连客户端、应用治理约束 | 3 个仓库静态门禁通过，未逐类检查全部应用 |
| 调度与准入 | Trigger 请求身份、Workflow 新建、等待扫描、终态唤醒 | 源码、V386、当前 Mapper SQL 与 JVM 反例，见 01、02 |
| Console 管理与执行态 | Console 代理、Orchestrator 人工恢复、状态 CAS 和提交后推进 | 源码与数据库反例，见 03。既有 JobInstance 暂停保护仍存在于回报聚合与版本条件中 |
| 消息与 Worker | 派发 Outbox 的领取、ACK、回收，Trigger 与 Worker REPORT 的领取凭据、Worker 消费确认入口 | 源码与数据库反例，见 04。五类 Worker 的业务副作用和各语言 SDK 未逐一执行 |
| 存储、容量与部署 | 平台数据库唯一索引、热冷归档、既有分片结论与容量台账 | 本轮没有吞吐测量。10 万请求、混版、外部存储、目标库权限和生产故障恢复仍按[原计划](../plans/system-design-capacity-validation-plan-2026-11-09.md)验收 |

**实际检查。** `git ls-remote origin refs/heads/main` 与审核工作树 HEAD 一致。`python3 scripts/ci/check-dependency-boundaries.py`、`python3 scripts/ci/check-direct-client-boundaries.py`、`python3 scripts/ci/check-application-governance.py` 均通过。

数据库反例使用本机已有的 `postgres:17.11` 镜像，以 `postgres --single` 运行，关闭容器网络，数据仅在临时文件系统。执行 V386 和从 XML 展开的对应 Mapper SQL，使用最小表结构与合成数据，按明确顺序排列各次写入。JVM 探针用 JDK 21 编译当前工作树的执行器配置、补槽触发器、恢复器及配置类，复用本机项目依赖。以上验证只覆盖列明的行为；未执行完整 Flyway、Spring HTTP、Kafka、多进程或全量 CI。
