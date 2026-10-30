# 运行质量与 SLA 设计

> **Workflow 新根的监控边界**：Job 告警在实例生成后保留，并关联根 WorkflowRun/节点；尚未物化的 JOB 不能靠扫描 JobInstance 判断漏跑，计划机会、输入预期与缺失义务需按 occurrence/expected-work 链判断。根失败、输入缺失和 Job 耗时告警不能互相替代。下方 Job 监控专用 SQL 仍有自身 schedule/timezone 策略口径，不据“日程归 Workflow”就假定其所有历史列消费者已消失；告警继承与新根行为须看对应专项证据。

> 拆自 mega 设计文档 ch.11，对照当前实现做了**实际状态标注**（哪些已落地、哪些只是设计）。

## 1. 任务执行 SLA

约束任务实例在预期时间内完成，为窗口管理、升级告警、运维介入提供判断依据。

### 1.1 设计边界

- 作业监控以**作业实例**为主要业务口径；Worker task timeout 是执行安全边界，不能替代作业 SLA。
- 作业硬超时、耗时过久、启动过晚、完成过晚是四种不同事件，必须有独立配置来源和验收口径。
- 软 SLA 违约只告警/升级，不直接终止运行；硬 timeout 才按超时策略收敛任务或实例。
- 父子作业、Workflow 节点和分区的 SLA 继承不是当前已交付能力；没有显式配置时不得假定自动继承。

### 1.2 SLA 字段

| 配置/快照 | 语义 | 当前来源与边界 |
|---|---|---|
| `job_definition.timeout_seconds` | 作业硬超时；从首次实际执行入口 `execution_started_at` 起算，超过该时长可终止实例 | 启动时冻结到实例执行策略；`0` 表示不启用硬超时。缺少实际执行入口时不推断时间，不能因为实例处于 RUNNING 派发态就从队列等待开始强杀 |
| `expectedDurationSeconds` / `expected_duration_seconds` | 旧实例级软 SLA 预计运行时长 | 由 launch params 指定；缺省回退 `job_definition.timeout_seconds`。当作业定义启用了 `soft_runtime_seconds` 时，同类运行时长告警以作业级策略为准，避免重复告警 |
| `deadlineAt` / `deadline` / `slaDeadlineAt` | 最晚完成时刻 | 当前可由 launch params 指定，并与 job timeout 推导值、批量日 SLA deadline 取最早值；最终快照到 `job_instance.deadline_at` |
| `soft_runtime_seconds` | 作业级软运行时长阈值 | 按 `execution_started_at + 阈值` 与完成或当前时刻比较；`started_at` 保留派发语义，不参与纯执行耗时。缺少真实入口时保持未知；默认关闭，只告警、不改变状态 |
| `start_grace_seconds` | 启动过晚阈值 | 从真实执行资格时刻起算；当前 Workflow 子作业取节点激活请求与捕获依赖生效的较晚值，旧 Cron/依赖保留计划口径。实际入口晚于期限可补报；无资格依据的独立手工运行不推算迟到，0 关闭 |
| `completion_deadline_local_time` + `completion_deadline_day_offset` | 绝对完成钟点和日期偏移 | 按作业时区解释；旧 Cron 使用计划日期，Workflow 子作业及无计划但有依赖资格的作业使用业务日期。偏移 0/1，与相对完成窗口互斥，留空关闭 |
| `dependency_completion_window_seconds` | 相对完成窗口 | 从可证实的执行资格时刻起算，适用于当前 Workflow 子作业及有计划/依赖资格的运行；不受旧 Job 调度类型限制。与绝对完成钟点互斥，0 关闭 |
| 结束时刻 `finished_at` 与实例 `deadline_at` | 结束是否晚于实例级业务 deadline | 非定时/手动实例需要明确截止时刻时，沿用 launch deadline 与旧实例级 SLA 语义；不回退到 `created_at` 推导“过晚” |

### 1.3 当前实现

- ✅ `JobInstanceTimeoutEnforcer` 根据作业 `timeout_seconds` 执行硬超时；以首次 `execution_started_at` 为起点，并要求至少一个 worker task 仍为 RUNNING，避免把派发排队时间当执行耗时。
- 硬超时扫描与终结均使用实例冻结的 `executionPolicy.timeoutSeconds`；启动后修改作业定义不改变在途实例。缺少执行策略的旧快照回退当前作业定义。终结在实例锁内再次确认时长已到、实例和至少一个 task 仍为 RUNNING，避免扫描后条件变化仍按旧候选终止。
- 任务级 `task_timeout_seconds` 是软取消，按本次任务认领的 `started_at` 计算。扫描后发取消时再次核对任务版本及当前超时条件，避免旧候选取消已重新开始或不再超时的尝试；人工取消仍按其独立规则处理。
- ✅ `JobSlaScheduler` 周期扫描 WAITING/READY/RUNNING 实例，并补扫已结束且有 `finished_at` 的非演练终态实例；`deadline_at` 已过或旧 `expected_duration_seconds` 已超限时写告警事件，活跃实例按当前时刻评估，终态实例按实际结束时刻评估，并以 `sla_alerted_at` 防重复。每个候选独立事务内 CAS 更新标记、写执行/审计日志并落告警事件；任一持久化失败回滚当前候选，后续扫描可重试，且不阻塞同批其他候选。启用作业级软运行时长策略后，同一作业的旧时长分支不再重复告警。
- ✅ 首次 SLA 告警后支持一个可配置延迟的升级级别；当前不是按 50%/100%/deadline 多级策略矩阵。
- ✅ 软时限按每次运行的真实资格评估；配置可预先保存到 MANUAL 作业，只有存在计划、依赖或当前 Workflow 节点激活证据时才判断迟到。完成期限可选择相对窗口或绝对钟点，互斥校验由 API、配置包和策略页面共同保持。
- ✅ Console Dashboard 的正式 SLA 总览、日趋势和按作业报表只纳入 SUCCESS/FAILED/PARTIAL_FAILED，排除取消及 dry-run；终态实例若有 `deadline_at` 却缺少 `finished_at`，仍留在总数/SLA 分母，不归入“按时”或“违约”。总览和按作业达标率均以对应 `totalWithSla` / `slaTotalWithSla` 为分母；日趋势保留按时和违约两类计数。此处是报表计数口径，不替代告警扫描的候选判断；异常记录可由分母与分类计数的差值发现。
- ✅ 旧实例级 `deadline_at` 与 `expected_duration_seconds` 违规支持扫描间隙内的终态补报；只有可信 `finished_at` 的成功/失败/部分失败/取消/终止实例纳入，dry-run、未知状态和缺少结束时刻的记录排除。新三类时限规则补报可证实的迟启动、迟完成和已完成耗时违约；没有实际入口的旧历史记录不从派发/创建时间倒推，CREATED 恢复失败仍有独立告警。
- ✅ 作业策略由定义 API 和配置包持久化，按当前策略评估；运行、启动和完成各有生效时间，阈值变更仅重置对应规则，严重级别编辑不重置。V355 为旧运行/启动规则继承最近策略保存时间作为可证实的保守下界，避免升级时间截断重启补报；不回溯此前已结束的事件。
- ✅ Job 四类旁路告警在独立事务中非阻塞尝试获取既有实例事务锁, 按租户、实例、违约类型重新读取当前策略和候选, 使用复核结果写告警。实例繁忙或条件失效时不声明 claim；繁忙实例仍可在后续扫描补报。查询复用原扫描 SQL, 不另设一套判断口径。claim 与告警持久化失败一起回滚。
- ✅ 近期终态失败分区告警读取 `job_instance.failed_partition_count`；仅扫描 lookback 窗口内、非 dry-run 且状态为 FAILED/PARTIAL_FAILED 的实例，每轮限批并以 `(tenant, instance, violation)` claim 去重。取消、终止、重试中和零失败分区不触发该告警。
- ⚠️ 策略尚未固化为每个实例的创建时快照；若业务要求“运行期间修改策略不影响在途实例”，需另立兼容性设计，不应悄悄改变当前实时策略语义。
- `JobInstanceStatus` 的完整值表以 [核心模型](../architecture/core-model.md#42-当前统一状态口径) 为准；没有专门的 `SLA_TIMEOUT` 状态。软 SLA 只告警，不改变状态机；硬 timeout 按独立超时策略收敛。

### 1.3.1 Workflow 根级监控补齐状态

当前工作分支新增 `workflow_monitoring_policy`，独立保存根级启动宽限、软运行耗时和本地完成截止钟点；默认均关闭，不向子 Job 自动继承。根级扫描按租户进入数据库 RLS 上下文，复用既有 `AlertEventService`、`alert_event` 和 Alertmanager 通道，产生 `WORKFLOW_NOT_STARTED_BY_DEADLINE`、`WORKFLOW_NOT_COMPLETED_BY_DEADLINE`、`WORKFLOW_RUNNING_TOO_LONG`。

开始及完成时限从 `scheduled_occurrence` 的计划时刻，或 `expected_work_occurrence` 的到期/预期时刻判定，未创建 TriggerRequest/Run 也可产生候选。实际 Run 已存在时保留其真实开始/完成时间；机会显式终结只记录 `opportunityClosedAt`，不能伪装为 Run 完成。耗时按根开始至实际结束计算，包含 WAIT/SENSOR 等编排等待。已开始/结束后仍可补判违约；策略生效前已终结的历史记录不回溯。

告警资源按根运行或独立预期机会定位，三类各自去重；发送前持有单资源/类型事务锁重新核对策略、资格及既有告警。根级候选、未建 Run 的计划机会、策略隔离、前端配置和租户配置包均已接线；策略接口读写按租户校验，采用显式列映射，不依赖 MyBatis 全局下划线转驼峰设置。Job 实际执行计时、未建实例 Workflow 子作业机会及两阶段自动解除/恢复重投已有定向证据。真实 Orchestrator→Alertmanager→Console 联合发送和部署环境 firing/resolved 验收仍待完成，见[专项计划](../plans/job-workflow-sla-completion-plan-2026-10-25.md)。

### 1.4 作业监控告警的隔离边界

作业 SLA 监控属于控制面内的独立监控子域，不另建通用监控平台，也不进入作业执行主链路。它复用现有 `AlertEventService`、`alert_event` 和通知投递机制；策略配置、周期扫描和告警去重记录独立维护。

必须满足以下边界：

- **主链路不依赖监控**：Trigger、launch、实例/分区/任务状态推进、Worker claim/report 不得同步调用监控服务，也不得等待监控扫描或告警发布。
- **监控不推进作业生命周期**：候选扫描读取 `job_instance`；SLA 处理只允许用 tenant/id 条件 CAS 更新 `sla_alerted_at` 去重标记，不得更新实例状态、版本、租约或进度。该标记、执行/审计日志和告警事件在单候选独立事务内提交或回滚；其他监控告警的 claim 仍使用独立表和事务。
- **claim 生命周期跟随热表实例**：`job_monitoring_alert_claim` 只负责防重复，不是审计事实；终态实例归档到冷表时，在同一事务删除其 claim。归档失败则整个事务回滚，避免源实例仍在热表但幂等保护被提前清掉。
- **失败只损失监控及时性**：监控查询、claim、日志/告警事件落库或通知发布失败时，不回滚、不阻塞作业事务；失败记录日志/低基数指标，后续扫描可重试。每个候选的 SLA 标记、执行/审计日志与事件落库必须同一事务，避免“已去重但审计/告警未落库”；单候选事务失败不得回滚整批扫描。
- **执行资源隔离且有界**：监控使用专用调度线程，不占用 Outbox 或共享调度池；每轮查询有批量上限、超时和明确的失败边界，不在 Prometheus 抓取时执行数据库查询。监控不得无限重试或堆积候选。
- **首次启用保护**：最终失败分区扫描默认只回看最近 3600 秒，范围为 60 秒至 30 天；通过 `BATCH_SLA_JOB_MONITORING_FAILED_PARTITION_LOOKBACK_SECONDS` 调整。它不是历史审计/补偿扫描，超出窗口的旧失败不会被补发。
- **不宣称共享数据库下绝对零影响**：当前监控与主链路共用 PostgreSQL，独立线程和有界查询能隔离线程/事务耦合，但仍共享数据库 CPU、IO、缓存和连接资源。若容量验证显示明显竞争，或要求资源级强隔离，应将监控查询切到只读副本并设置独立连接池；不以本地异步化替代该验收。
- **策略变更语义明确**：作业级监控策略按扫描时读取的最新配置评估仍运行实例，不改变其执行状态或硬超时语义。完成晚告警只评估 `completion_deadline_updated_at` 之后完成的实例；该时间仅在完成时钟或日偏移实际改变时更新，其他作业字段或监控阈值变更不会重置历史回放基线。若未来改为实例级策略快照，必须证明快照写入不会进入主链路关键事务。
- **旧 SLA 兼容与规则优先级**：新 `soft_runtime_seconds` 是同一作业的软运行时长告警权威配置；启用后，旧 `expected_duration_seconds` 的同类运行时长分支不再重复发告警。旧绝对 `deadline_at` 仍是独立业务截止规则，允许与软运行时长分别告警。策略更新对扫描时仍未终态的实例即时生效；完成时限只评估完成时限阈值最近变更之后完成的实例，不回放更早的历史终态。

### 1.5 配置入口和事件类型

| 作业执行方式 | 耗时过久 | 启动过晚 | 完成过晚 |
|---|---:|---:|---:|
| 独立 Cron 定时作业 | 支持 | 支持，按计划触发时刻 + 启动宽限期 | 支持，按计划触发日、作业时区和完成截止钟点 |
| 当前 Workflow 子作业（含 MANUAL 定义） | 支持，实际入口至完成/当前时刻 | 支持，节点激活与捕获依赖较晚值 + 宽限 | 支持，相对资格窗口或业务日期绝对钟点 |
| 独立固定频率作业 | 支持 | 不支持 | 不支持，不推导轮次截止时间 |
| 声明上游依赖的作业（不论调度类型） | 支持 | 支持，按执行资格时刻 + 启动宽限期 | 支持，按执行资格时刻 + 完成窗口 |
| 无依赖且无 Workflow 资格的独立手动/API/外部事件作业 | 支持 | 不支持 | 不支持；若调用方提供实例级业务 deadline，仍按既有实例 SLA 评估 |

当前 Workflow 子作业的执行资格取节点激活请求创建时刻与捕获依赖结果 `effectiveAt` 的较晚值，不能沿用根计划时间；旧 Cron/依赖运行保留对应计划与就绪口径。未提供完整可用依赖证据时不伪装为无依赖。新建作业运行耗时默认 0；旧 Cron/依赖启动宽限默认 300 秒，其余默认 0，均可显式配置。

作业级阈值及级别存于 `job_monitoring_policy`，Console API 与配置包 `job_monitoring_policy` sheet 读写同一策略。策略可预先配置；每次运行仍需真实资格，独立手工运行不以创建时间判断迟到。绝对钟点和相对窗口互斥；阈值 0 或钟点留空关闭，级别支持 WARN、ERROR、CRITICAL。三类软时限事件为 `JOB_RUNNING_TOO_LONG`、`JOB_NOT_STARTED_BY_DEADLINE`、`JOB_NOT_COMPLETED_BY_DEADLINE`，支持扫描间隙内已发生违约的补报；近期终态失败分区为 `JOB_FINAL_PARTITION_FAILURE`。四类复用 `alert_event` 与既有通知路由；扫描及持久化失败继续由 `BatchJobMonitoringScanFailures`、`BatchJobMonitoringEventFailures` 监控。阈值只告警，不推进作业状态。
- **开关粒度**：平台运维总开关为 `BATCH_SLA_JOB_MONITORING_ENABLED`，关闭后暂停该功能全部周期扫描；业务阈值按作业、按告警类型配置，阈值 `0` 即关闭该类告警。当前不增加租户级重复总开关：它会与平台总开关及作业阈值形成多层优先级。若未来需要租户整体退出监控，应以明确的产品/合规需求增加租户策略，并定义它与平台强制告警的优先级和审计语义。
- **扫描资源配置**：`batch.sla.job-monitoring` 使用独立单线程调度器；默认周期 30 秒、每类最多 50 个候选，周期按 fixed-delay 从一轮完成后计时。周期和批大小只控制告警发现时延与扫描预算，不承诺 PG 资源隔离。持续观测查询耗时、含告警写入的总扫描耗时、候选数、查询失败和告警落库失败；使用真实分区数据验证查询计划后再调整批次或索引。

验收时至少注入监控查询超时、告警写入失败和通知端不可用，并确认作业创建、派发、Worker 执行、终态落库均不等待监控且结果不变；另外检查高实例量下扫描 SQL 的执行计划、耗时和批次上限。通知端不可用只允许影响告警送达，由现有告警投递重试机制恢复。

成熟实现也采用相似的职责分离：Airflow Deadline Alerts 用参考时刻和间隔计算截止点，由调度器周期评估并支持异步回调；Prometheus 周期评估规则，再由 Alertmanager 负责告警分组、去重和路由。BFS 复用现有控制面和 `alert_event`，不照搬成独立监控平台。参考：[Airflow Deadline Alerts](https://airflow.apache.org/docs/apache-airflow/stable/howto/deadline-alerts.html)、[Prometheus Alertmanager](https://prometheus.io/docs/alerting/latest/alertmanager/)。

## 2. 文件到达 SLA 与等待策略

针对**上游文件驱动的导入场景**，单独定义"文件到达 SLA"。设计上原本规划在 `job_instance.instance_status` 加 `WAITING_ARRIVAL` 枚举，**实际落地**为 `file_record.metadata_json` 上的 `arrivalState` 字段 + 独立的 `file_arrival_group` 监控视图。

### 2.1 配置字段

| 字段 | 说明 |
|---|---|
| `expected_arrival_time` | 期望到达时间 |
| `latest_tolerable_time` | 最晚容忍时间 |
| `arrival_timeout_action` | 超时动作：`BLOCK_DOWNSTREAM` / `WAIT_MORE` / `MANUAL_CONFIRM` / `SKIP_BATCH` / `EMPTY_RUN` |
| `notify_manual` | 是否通知人工 |
| `notify_channels` | 通知渠道 |
| `allow_empty_run` | 是否允许空跑 |
| `allow_skip_biz_date` | 是否允许跳过当日批次 |

### 2.2 运行规则

1. 文件未到达时进入 `WAITING_ARRIVAL`，开始统计到达延迟
2. 超过 `expected_arrival_time` 先**预警**，超过 `latest_tolerable_time` 再执行**超时动作**
3. `BLOCK_DOWNSTREAM` → 后续节点、相关导出链路、依赖任务**一并阻断**
4. `EMPTY_RUN` / `SKIP_BATCH` → 必须**写审计日志**，绑定业务日期、批次号、操作来源
5. `MANUAL_CONFIRM` → 控制台必须支持「继续等待 / 跳过批次 / 执行空跑」三类受控操作

### 2.3 当前实现

| 组件 | 位置 |
|---|---|
| `FileGovernanceScheduler` | `batch-orchestrator/.../infrastructure/file/FileGovernanceScheduler.java` |
| `DefaultFileGovernanceService` | `batch-orchestrator/.../application/service/DefaultFileGovernanceService.java` |
| `FileGovernanceMapper.xml` | `batch-orchestrator/src/main/resources/mapper/` |
| `FileArrivalGroupMapper.xml`（控制台监控视图） | `batch-console-api/src/main/resources/mapper/` |

控制台监控字段：`arrivalState`（`WAITING_ARRIVAL` / `TRIGGERED` / `TIMEOUT`）、`waitFileGroupMode`（`ALL_OF` 等）、`requiredFileSet`、`arrived_count` / `triggered_count` / `timeout_count` / `waiting_count`。

## 3. 数据质量控制

### 3.1 内置质量检查类型

- `row_count_check` — 期望行数比对
- `checksum_check` — 校验和比对（MD5 / SHA-256）
- `schema_check` — 字段 / 类型一致性
- `null_check` — 必填非空

### 3.2 失败处置

质量检查失败 → 任务进入 `FAILED`，写明细到错误表，触发告警 / 降级 / 人工介入。

> 详细错误处理 / 重试策略：[file-pipeline-design.md](./file-pipeline-design.md) §错误处理章节 + [`../architecture/adr/ADR-006-compensation-requires-new.md`](../architecture/adr/ADR-006-compensation-requires-new.md)。

## 4. 数据校验规则（行内字段级）

### 4.1 可配置规则类型

- 字段非空（NOT NULL）
- 字段长度（min / max length）
- 字段范围（min / max value）
- 正则表达式
- 自定义脚本（受限）

### 4.2 配置示例

```text
customer_id NOT NULL
amount > 0
amount <= 1000000
phone REGEX ^1[3-9]\d{9}$
```

### 4.3 落地位置

- **导入侧**：`PreprocessStep` / `ValidateStep`（`batch-worker-import`）执行行级校验
- **错误行收集**：写入错误对象（MinIO 桶 `batch-error-output`）+ 错误明细表
- **配置存储**：`file_template_config.validation_rules` JSONB 字段

## 5. SLA 升级与告警分级

> 该子能力当前**部分实现**——告警通道齐全，但 SLA 升级矩阵仍在路线图上（详见 [`../analysis/hardening-backlog.md`](../plans/hardening-backlog.md)）。

设计目标：

- L1 告警：超 `expected_duration` 50% → 通知 owner
- L2 告警：超 `expected_duration` 100% → 通知 owner + on-call
- L3 告警：超 `deadline` → 通知 owner + on-call + 业务方 + 触发自动降级

实际：已具备首次 SLA 事件和单次延迟升级配置；50% / 100% / deadline 分级矩阵、作业 owner/on-call 责任路由和自动降级未落。作业定义字段、告警事件、Prometheus 规则与 Console 统计应保持同一 SLA 语义，不得各自推导不同阈值。

## 相关文档

- [batch-day-design.md](./batch-day-design.md) — 批次日 / batch_window，决定 SLA 起算锚点
- [file-pipeline-design.md](./file-pipeline-design.md) — 文件链路与质量校验落点
- [`../architecture/workflow-dependency-guide.md`](../architecture/workflow-dependency-guide.md) — 节点依赖（join_mode）影响等待策略
- [`../runbook/incident-response.md`](../runbook/incident-response.md) — SLA 告警出现后的应急 SOP
- [`../analysis/hardening-backlog.md`](../plans/hardening-backlog.md) — 待落地：SLA 升级矩阵 / 自动降级

### 逐实体通知证据

V356 的 `alert_notification_receipt` 按租户、平台告警指纹和接收渠道保存最终渠道成功回执；发生与恢复分别记录。Console 分批投递完整的已接收实体列表，只有本批最终渠道成功后才写回执，写回执失败仍向 Alertmanager 返回可重试失败。回执写入有真实事务回滚/恢复验证，外部投递为至少一次。

回执已落地，V357 已接入按租户、原始指纹和持久关闭时间的 CLOSED 恢复补发；活动与关闭两类按尝试水位公平轮询。自动解除现由发送轮询按租户和活动行快照调用来源判定 SQL：Job/Workflow 的实际运行、明确终态及关闭机会分别处理；Workflow 子作业未建实例时以 `trigger_request` 为稳定来源身份，实例创建后沿用相同资源键；自然恢复要求首次发生通知已有最终渠道成功回执，策略撤销以 `POLICY_DISABLED` 区分，明确关闭的计划机会记为 `OPPORTUNITY_CLOSED`。未知、陈旧、跨租户及未完成来源不会被关闭。第八批 26 项定向测试通过，其中 21 项真实 PostgreSQL；第十批针对未建实例候选、发射和恢复的联合 42 项测试通过，其中 29 项真实 PostgreSQL。端到端 HTTP 最终收件仍待验。实施证据及剩余范围见 [专项计划](../plans/job-workflow-sla-completion-plan-2026-10-25.md)。

## Workflow 归档后的开始与完成事实

根机会监控按来源请求关联热 `workflow_run` 和冷 `archive.workflow_run_archive`, 同一请求存在热运行时
优先使用热事实。归档不将已开始/已结束解释为尚未执行: 按时完成后归档不能新增开始或完成违约;
归档后迟启动/迟完成仍保留原时间证据, dry-run 继续排除。冷热定位复用 V358 的来源请求索引。
