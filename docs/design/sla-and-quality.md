# 运行质量与 SLA 设计

> 拆自 mega 设计文档 ch.11，对照当前实现做了**实际状态标注**（哪些已落地、哪些只是设计）。

## 1. 任务执行 SLA

约束任务实例在预期时间内完成，为窗口管理、升级告警、运维介入提供判断依据。

### 1.1 设计边界

- 作业监控以**作业实例**为主要业务口径；Worker task timeout 是执行安全边界，不能替代作业 SLA。
- 作业硬超时、运行耗时预警、到期未启动、结束晚是四种不同事件，必须有独立配置来源和验收口径。
- 软 SLA 违约只告警/升级，不直接终止运行；硬 timeout 才按超时策略收敛任务或实例。
- 父子作业、Workflow 节点和分区的 SLA 继承不是当前已交付能力；没有显式配置时不得假定自动继承。

### 1.2 SLA 字段

| 配置/快照 | 语义 | 当前来源与边界 |
|---|---|---|
| `job_definition.timeout_seconds` | 作业硬超时；worker 已开始执行后，超过该时长可终止实例 | Console 作业配置；`0` 表示不启用硬超时。不能因为实例处于 RUNNING 派发态就从队列等待开始强杀 |
| `expectedDurationSeconds` / `expected_duration_seconds` | 软 SLA 预计运行时长 | 当前可由 launch params 指定；缺省回退 `job_definition.timeout_seconds`。扫描器对 WAITING/READY/RUNNING 的超限实例告警；当前和硬 timeout 使用同一字段作默认值，语义尚未完全解耦 |
| `deadlineAt` / `deadline` / `slaDeadlineAt` | 最晚完成时刻 | 当前可由 launch params 指定，并与 job timeout 推导值、批量日 SLA deadline 取最早值；最终快照到 `job_instance.deadline_at` |
| 结束时刻 `finished_at` 与 `deadline_at` | 结束是否晚于业务 deadline | Console 有完成时限统计；当前没有确认到终态晚完成后的专门告警事件 |

### 1.3 当前实现

- ✅ `JobInstanceTimeoutEnforcer` 根据作业 `timeout_seconds` 执行硬超时；仅在至少一个 worker task 已 RUNNING 后计时，避免把派发排队时间当执行耗时。
- ✅ `JobSlaScheduler` 周期扫描 WAITING/READY/RUNNING 实例；`deadline_at` 已过或 `expected_duration_seconds` 已超限时写告警事件，并以 `sla_alerted_at` 防重复。
- ✅ 首次 SLA 告警后支持一个可配置延迟的升级级别；当前不是按 50%/100%/deadline 多级策略矩阵。
- ⚠️ “到期未执行”当前只覆盖进入 WAITING/READY/RUNNING 且 deadline 过期的实例；仍停留在 CREATED 的实例由 launch 恢复链路处理，不属于 `JobSlaScheduler` 扫描范围，恢复失败还需独立告警。
- ⚠️ “执行结束晚”当前可由 `finished_at > deadline_at` 做看板统计，但未确认有终态发生时的独立通知/告警。
- ⚠️ 软 `expectedDurationSeconds` 尚未作为独立的作业定义字段与硬 `timeoutSeconds` 完全解耦；当前缺省复用硬超时值。作业监控产品化前应提供独立软 SLA 默认值和是否告警策略，并保留每次实例快照。
- `JobInstanceStatus` 的完整值表以 [核心模型](../architecture/core-model.md#42-当前统一状态口径) 为准；没有专门的 `SLA_TIMEOUT` 状态。软 SLA 只告警，不改变状态机；硬 timeout 按独立超时策略收敛。

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
