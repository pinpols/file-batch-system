# 运行质量与 SLA 设计

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
| `job_definition.timeout_seconds` | 作业硬超时；worker 已开始执行后，超过该时长可终止实例 | Console 作业配置；`0` 表示不启用硬超时。不能因为实例处于 RUNNING 派发态就从队列等待开始强杀 |
| `expectedDurationSeconds` / `expected_duration_seconds` | 旧实例级软 SLA 预计运行时长 | 由 launch params 指定；缺省回退 `job_definition.timeout_seconds`。当作业定义启用了 `soft_runtime_seconds` 时，同类运行时长告警以作业级策略为准，避免重复告警 |
| `deadlineAt` / `deadline` / `slaDeadlineAt` | 最晚完成时刻 | 当前可由 launch params 指定，并与 job timeout 推导值、批量日 SLA deadline 取最早值；最终快照到 `job_instance.deadline_at` |
| `soft_runtime_seconds` | 作业级软运行时长阈值 | `job_monitoring_policy`；按 `started_at + 阈值` 计算，适用于所有调度类型；新建作业默认关闭，须按作业显式配置；只告警，不改变执行状态 |
| `start_grace_seconds` | 启动过晚阈值 | Cron 独立作业以 `scheduledAt` 为基准；依赖作业以满足执行资格时刻为基准。后者若同时有计划触发时刻，取计划时刻与依赖就绪时刻较晚者。超出阈值仍未启动时告警；新建 Cron/依赖作业默认采用 `BATCH_JOB_MONITORING_DEFAULT_START_GRACE_SECONDS`（300 秒），显式 `0` 关闭 |
| `completion_deadline_local_time` + `completion_deadline_day_offset` | Cron 独立作业的本地完成截止钟点及相对计划触发日偏移 | 仅无上游依赖的 `CRON`；使用 `job_definition.timezone`，偏移 `0` 为当日、`1` 为次日；到截止钟点时仍未结束则告警，留空关闭 |
| `dependency_completion_window_seconds` | 依赖作业的最晚完成窗口 | 仅声明上游依赖的作业；从下游满足执行资格时起算。依赖作业若同时为 Cron，基准取 `scheduledAt` 与上游结果 `effective_at` 较晚者。`0` 关闭；独立固定频率和手动/API/外部事件作业不使用完成时限 |
| 结束时刻 `finished_at` 与实例 `deadline_at` | 结束是否晚于实例级业务 deadline | 非定时/手动实例需要明确截止时刻时，沿用 launch deadline 与旧实例级 SLA 语义；不回退到 `created_at` 推导“过晚” |

### 1.3 当前实现

- ✅ `JobInstanceTimeoutEnforcer` 根据作业 `timeout_seconds` 执行硬超时；仅在至少一个 worker task 已 RUNNING 后计时，避免把派发排队时间当执行耗时。
- ✅ `JobSlaScheduler` 周期扫描 WAITING/READY/RUNNING 实例；`deadline_at` 已过或旧 `expected_duration_seconds` 已超限时写告警事件，并以 `sla_alerted_at` 防重复。启用作业级软运行时长策略后，同一作业的旧时长分支不再重复告警。
- ✅ 首次 SLA 告警后支持一个可配置延迟的升级级别；当前不是按 50%/100%/deadline 多级策略矩阵。
- ✅ 作业监控时限按执行资格区分：无依赖 Cron 使用作业时区的完成截止钟点；声明上游依赖的作业使用相对完成窗口；独立固定频率、手动/API/外部事件作业只适用耗时告警。依赖作业的资格时刻由上游结果生效时间与计划触发时间共同确定，不使用轮询发现时刻。
- ⚠️ 旧 `JobSlaScheduler` 的 deadline 扫描范围是 WAITING/READY/RUNNING；作业级 `start_grace_seconds` 扫描另外覆盖 CREATED/WAITING/READY/RUNNING 中具备 `scheduledAt` 的到期实例。两者都不改变实例状态；CREATED 恢复链路自身失败仍需依赖专门的恢复失败告警。
- ✅ 作业级监控策略由作业定义 API 配置并持久化；运行耗时过久适用于所有调度类型。Cron 独立作业与依赖作业支持开始过晚、完成过晚；固定频率独立作业及手动/API/外部事件作业只支持运行耗时。依赖就绪时刻来自上游 EFFECTIVE 结果的 `effective_at`，随触发请求写入实例参数快照；不会以 readiness 轮询发现时间代替业务就绪时间。依赖作业的完成窗口从执行资格时刻起算，Cron 独立作业按计划触发日、作业时区、本地钟点和日偏移判断，不使用创建时刻兜底。运行耗时阈值无统一默认值；Cron/依赖作业的启动宽限默认 300 秒。策略当前按最新配置评估仍运行的实例，不改变实例状态机；完成晚告警不会回溯策略更新时间之前已完成的历史实例。
- ✅ 近期终态失败分区告警读取 `job_instance.failed_partition_count`；仅扫描 lookback 窗口内、非 dry-run 且状态为 FAILED/PARTIAL_FAILED 的实例，每轮限批并以 `(tenant, instance, violation)` claim 去重。取消、终止、重试中和零失败分区不触发该告警。
- ⚠️ 策略尚未固化为每个实例的创建时快照；若业务要求“运行期间修改策略不影响在途实例”，需另立兼容性设计，不应悄悄改变当前实时策略语义。
- `JobInstanceStatus` 的完整值表以 [核心模型](../architecture/core-model.md#42-当前统一状态口径) 为准；没有专门的 `SLA_TIMEOUT` 状态。软 SLA 只告警，不改变状态机；硬 timeout 按独立超时策略收敛。

### 1.4 作业监控告警的隔离边界

作业 SLA 监控属于控制面内的独立监控子域，不另建通用监控平台，也不进入作业执行主链路。它复用现有 `AlertEventService`、`alert_event` 和通知投递机制；策略配置、周期扫描和告警去重记录独立维护。

必须满足以下边界：

- **主链路不依赖监控**：Trigger、launch、实例/分区/任务状态推进、Worker claim/report 不得同步调用监控服务，也不得等待监控扫描或告警发布。
- **监控不写运行态**：扫描只读 `job_instance`；不得更新实例状态、版本、租约、进度或 SLA 标记。监控配置与去重 claim 使用独立表和独立事务。
- **claim 生命周期跟随热表实例**：`job_monitoring_alert_claim` 只负责防重复，不是审计事实；终态实例归档到冷表时，在同一事务删除其 claim。归档失败则整个事务回滚，避免源实例仍在热表但幂等保护被提前清掉。
- **失败只损失监控及时性**：监控查询、claim、告警事件落库或通知发布失败时，不回滚、不阻塞作业事务；失败记录日志/低基数指标，后续扫描可重试。告警 claim 与事件落库应在同一独立事务中，避免“已去重但事件未落库”。
- **执行资源隔离且有界**：监控使用专用调度线程，不占用 Outbox 或共享调度池；每轮查询有批量上限、超时和明确的失败边界，不在 Prometheus 抓取时执行数据库查询。监控不得无限重试或堆积候选。
- **首次启用保护**：最终失败分区扫描默认只回看最近 3600 秒，范围为 60 秒至 30 天；通过 `BATCH_SLA_JOB_MONITORING_FAILED_PARTITION_LOOKBACK_SECONDS` 调整。它不是历史审计/补偿扫描，超出窗口的旧失败不会被补发。
- **不宣称共享数据库下绝对零影响**：当前监控与主链路共用 PostgreSQL，独立线程和有界查询能隔离线程/事务耦合，但仍共享数据库 CPU、IO、缓存和连接资源。若容量验证显示明显竞争，或要求资源级强隔离，应将监控查询切到只读副本并设置独立连接池；不以本地异步化替代该验收。
- **策略变更语义明确**：作业级监控策略按扫描时读取的最新配置评估仍运行实例，不改变其执行状态或硬超时语义。完成晚告警只评估 `completion_deadline_updated_at` 之后完成的实例；该时间仅在完成时钟或日偏移实际改变时更新，其他作业字段或监控阈值变更不会重置历史回放基线。若未来改为实例级策略快照，必须证明快照写入不会进入主链路关键事务。
- **旧 SLA 兼容与规则优先级**：新 `soft_runtime_seconds` 是同一作业的软运行时长告警权威配置；启用后，旧 `expected_duration_seconds` 的同类运行时长分支不再重复发告警。旧绝对 `deadline_at` 仍是独立业务截止规则，允许与软运行时长分别告警。策略更新对扫描时仍未终态的实例即时生效；完成时限只评估完成时限阈值最近变更之后完成的实例，不回放更早的历史终态。

### 1.5 配置入口和事件类型

| 作业执行方式 | 耗时过久 | 启动过晚 | 完成过晚 |
|---|---:|---:|---:|
| 独立 Cron 定时作业 | 支持 | 支持，按计划触发时刻 + 启动宽限期 | 支持，按计划触发日、作业时区和完成截止钟点 |
| 独立固定频率作业 | 支持 | 不支持 | 不支持，不推导轮次截止时间 |
| 声明上游依赖的作业（不论调度类型） | 支持 | 支持，按执行资格时刻 + 启动宽限期 | 支持，按执行资格时刻 + 完成窗口 |
| 无依赖的手动/API/外部事件作业 | 支持 | 不支持 | 不支持；若调用方提供实例级业务 deadline，仍按既有实例 SLA 评估 |

“执行资格时刻”是上游结果生效时间；依赖作业同时具有计划触发时刻时，取两者较晚者。新建作业的运行耗时默认值为 `0`（关闭），启动宽限期默认 300 秒（仅适用于 Cron 和依赖作业）；完成过晚均由作业显式设置，依赖完成窗口默认为 `0`（关闭）。当前调度字典提供 `CRON`、`FIXED_RATE`、`MANUAL`，不根据固定频率的轮询周期推算独立作业完成期限。

作业级阈值及各自告警级别存于独立的 `job_monitoring_policy` 表。Console 作业 API 承载策略读写契约；Excel 完整配置包将其放在单独的 `job_monitoring_policy` sheet，每个作业一行。运行耗时阈值对所有调度类型有效，平台默认 `0`（关闭），按作业显式配置后生效。启动宽限期默认 300 秒，适用于 Cron 与依赖作业；完成期限为无依赖 Cron 配置本地截止钟点，依赖作业配置 `dependency_completion_window_seconds`，两种完成规则均须显式配置。独立固定频率和无依赖手动/API/外部事件作业只适用耗时监控。API 省略阈值或配置包行留空采用平台默认；策略页面或配置包显式 `0` 表示关闭。级别支持 `WARN`、`ERROR`、`CRITICAL`，缺省 `WARN`。三类软时限事件分别为 `JOB_RUNNING_TOO_LONG`、`JOB_NOT_STARTED_BY_DEADLINE` 和 `JOB_NOT_COMPLETED_BY_DEADLINE`。若扫描晚于实例完成，仍会补发其已超过完成期限的告警。近期终态失败分区事件为 `JOB_FINAL_PARTITION_FAILURE`。四类事件都走现有 `alert_event` 及通知路由，并由独立 Prometheus 规则 `BatchJobRunningTooLong`、`BatchJobNotStartedByDeadline`、`BatchJobNotCompletedByDeadline`、`BatchFinalPartitionFailures` 告警；扫描/告警持久化自身失败另由 `BatchJobMonitoringScanFailures` 和 `BatchJobMonitoringEventFailures` 监控。阈值告警只提供运维信号，不会中断或改变作业状态。
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

> 该子能力当前**部分实现**——告警通道齐全，但 SLA 升级矩阵仍在路线图上（详见 [`../analysis/hardening-backlog.md`](../analysis/hardening-backlog.md)）。

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
- [`../analysis/hardening-backlog.md`](../analysis/hardening-backlog.md) — 待落地：SLA 升级矩阵 / 自动降级
