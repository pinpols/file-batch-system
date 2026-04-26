# 运行质量与 SLA 设计

> 拆自 mega 设计文档 ch.11，对照当前实现做了**实际状态标注**（哪些已落地、哪些只是设计）。

## 1. 任务执行 SLA

约束任务实例在预期时间内完成，为窗口管理、升级告警、运维介入提供判断依据。

### 1.1 设计边界

- SLA 可按**任务实例 / 流程节点 / 分片粒度**统计，**以任务实例为主要口径**
- SLA 超时**默认触发告警**，不直接终止运行中的任务
- 父子节点 SLA 可分别配置，不强制自动继承

### 1.2 SLA 字段

| 字段 | 说明 |
|---|---|
| `deadline` | 最晚完成时间（绝对时刻） |
| `expected_duration` | 预计执行时长（相对值，毫秒） |

### 1.3 当前实现

- ✅ `JobSlaScheduler`（`batch-orchestrator/.../infrastructure/sla/JobSlaScheduler.java`）周期扫描运行中实例，超时打告警
- ✅ 字段挂在 `job_definition` + `job_instance` 上
- 状态枚举（`JobInstanceStatus`）：`CREATED / WAITING / READY / RUNNING / PARTIAL_FAILED / SUCCESS / FAILED / CANCELLED / TERMINATED`，**没有专门的 SLA_TIMEOUT 状态**——超时仅做告警，不改变状态机

## 2. 文件到达 SLA 与等待策略

针对**上游文件驱动的导入场景**，单独定义"文件到达 SLA"。设计上原本规划在 `job_instance.status` 加 `WAITING_ARRIVAL` 枚举，**实际落地**为 `file_record.metadata_json` 上的 `arrivalState` 字段 + 独立的 `file_arrival_group` 监控视图。

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

实际：仅 `JobSlaScheduler` 扫超时 + 告警通道（webhook / email / dingtalk）齐全；分级矩阵 / 自动降级未落。

## 相关文档

- [batch-day-design.md](./batch-day-design.md) — 批次日 / batch_window，决定 SLA 起算锚点
- [file-pipeline-design.md](./file-pipeline-design.md) — 文件链路与质量校验落点
- [`../architecture/workflow-dependency-guide.md`](../architecture/workflow-dependency-guide.md) — 节点依赖（join_mode）影响等待策略
- [`../runbook/incident-response.md`](../runbook/incident-response.md) — SLA 告警出现后的应急 SOP
- [`../analysis/hardening-backlog.md`](../analysis/hardening-backlog.md) — 待落地：SLA 升级矩阵 / 自动降级
