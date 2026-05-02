# Governance 表职责边界 — 命名相似但职责正交的表对

> **结论先行**：本文档列出系统里**几对名字看起来像、容易被误认为重叠**的 governance 表，逐对说明各自管什么、归哪个 module、以及为什么不合并。读到代码或表名感觉"这俩是不是干一件事"时，**先来这里查一遍**。

---

## 1. `config_approval` vs `approval_command`

### 一句话区分

| 表 | 一句话 |
|---|---|
| `batch.config_approval` | **配置发布的审批流**：用户改了一版 schedule / quota / workflow 配置，提交 release 等审批后才生效 |
| `batch.approval_command` | **运行时操作的审批命令**：catch-up / replay / 手动触发等需要审批的临时操作 |

### 字段对比

| 维度 | `config_approval` | `approval_command` |
|---|---|---|
| 模块 | `batch-console-api`（`ConfigApprovalMapper`）| `batch-orchestrator`（`ApprovalCommandMapper`），console 侧也有读视图 |
| 关联实体 | `release_id` → `config_release`（一版配置变更） | `target_type` + `target_id`（运行时实体，如 `BATCH_DAY` / `JOB_INSTANCE`）+ `payload_json`（操作参数） |
| 审批语义 | "这版配置我批不批"（YES = 配置 release 生效）| "对 X 做 Y 操作我批不批"（YES = 触发命令） |
| 主要字段 | `release_id` / `approval_status` / `requested_by` / `reviewed_by` / `review_comment` / `expired_at` | `approval_no` / `approval_type` / `action_type` / `target_type` / `target_id` / `payload_json` / `requester_id` / `approver_id` / `source_trace_id` / `source_idempotency_key` |
| 状态机 | `PENDING / APPROVED / REJECTED / EXPIRED` | `PENDING / APPROVED / REJECTED / EXECUTED`（含执行态） |
| 是否承载执行 payload | ❌（payload 在 `config_release` 上）| ✅（`payload_json` 直接是命令参数） |
| 触发后续动作 | release 生效 → 各 config 表 upsert | 命令执行 → orchestrator 推进运行时（如 `catchUpBatchDay()`） |

### 为什么不合并

1. **生命周期不同**：config_approval 跟随 release 版本；approval_command 一次操作一行
2. **payload 形态不同**：config 是结构化配置变更（10+ 张表的 upsert），command 是单次操作（1 个 action + 1 组参数）
3. **执行链路不同**：config 走 `ConsoleTenantConfigInitApplicationService` 批量 upsert；command 走 orchestrator 状态机推进
4. **跨模块所有权不同**：config 主要由 console-api 写读，command 由 orchestrator 写、console-api 读视图

### 何时该看哪张

| 场景 | 看哪张 |
|---|---|
| 用户改了 schedule，提 release 待审批 | `config_approval` |
| Admin 给某个 batch_day 触发 catch-up 待审批 | `approval_command`（`action_type=CATCH_UP`） |
| Admin 重放某个 partition 待审批 | `approval_command`（`action_type=REPLAY_PARTITION`） |
| 历史 release 审批轨迹 | `config_approval` |
| 历史运行时命令审批轨迹 | `approval_command` |

---

## 2. `subscription_rule` vs `alert_routing_config`

### 一句话区分

| 表 | 一句话 |
|---|---|
| `batch.subscription_rule` | **租户订阅规则**：租户自己设"我要接收哪些事件类型 / 严重度的通知"，决定**哪些事件 → 推给我** |
| `batch.alert_routing_config` | **平台告警路由配置**：按 team / alert_group / severity 把告警路由到 receiver，含分组/抑制/重复间隔策略，决定**告警 → 怎么发出去**|

### 字段对比

| 维度 | `subscription_rule` | `alert_routing_config` |
|---|---|---|
| 触发源 | **业务事件**（job 完成、文件入库、SLA 到点等）| **告警事件**（SLA breach、worker offline、积压超阈值等） |
| 关注维度 | 租户 + `event_types` + `severity_filter` + `job_code_filter` | 租户 + `team` + `alert_group` + `severity` |
| 投递目标 | `channel_code` → JOIN `notification_channel`（钉钉 / 邮件 / webhook 等）| `receiver`（路由到具体接收者，例如 oncall team / 邮件组） |
| 是否含分组策略 | ❌（来一个发一个） | ✅（`group_by` / `group_wait_seconds` / `group_interval_seconds` / `repeat_interval_seconds`，Prometheus AlertManager 风格） |
| 类比 | RSS subscription | Prometheus AlertManager routing tree |
| 主消费者 | `NotificationDispatchService`（事件驱动）| `AlertRoutingResolver`（告警驱动） |
| 模块 | `batch-console-api`（`SubscriptionRuleMapper`）| `batch-console-api`（`AlertRoutingConfigMapper`），由 alert pipeline 读取 |

### 为什么不合并

1. **消费域不同**：subscription 在 **业务事件域**（batch 完成、文件归档），alert 在 **告警事件域**（SLA / 健康度）
2. **投递语义不同**：subscription 是"推给订阅人"（fan-out），alert 是"分组抑制后发给 oncall"（debounce + dedup）
3. **配置粒度不同**：subscription 是租户级 self-service，alert 是平台级 ops 配置
4. **失败重试不同**：subscription 失败走通用 retry；alert 走 AlertManager 重发间隔（`repeat_interval_seconds`）

### 何时该看哪张

| 场景 | 看哪张 |
|---|---|
| 租户配置"job_X 完成时通知我钉钉" | `subscription_rule` |
| 平台配置"SLA breach 告警发给 ops-team" | `alert_routing_config` |
| 租户配置"任何 job 失败都给我邮件" | `subscription_rule`（`event_types` 含 `JOB_FAILED`） |
| 平台配置"严重告警分组 5 分钟一次" | `alert_routing_config`（`group_interval_seconds=300`） |
| 用户问"为什么我没收到通知" | 先查 `subscription_rule.enabled` + `notification_channel.enabled` |
| 运维问"为什么 oncall 没被 page" | 先查 `alert_routing_config` 的 routing tree + receiver 配置 |

---

## 3. 通用判别原则

遇到"两张表名字相似"的疑虑，按下面顺序判断：

1. **module 不同** → 大概率职责不同（不同 module owner = 不同问题域）
2. **关联实体不同** → 大概率不重叠（一个挂 release，一个挂 runtime entity = 不同生命周期）
3. **状态机不同** → 大概率不重叠（一个是 4 态，一个含 EXECUTED 终态 = 不同语义）
4. **payload 字段形态不同** → 大概率不重叠（结构化 release vs 自由 JSON command = 不同表达力）

如果 4 条都过了 → 真重叠 → 走 ADR 评估合并。

---

## 4. 相关文档

- [CLAUDE.md §架构硬约束](../../CLAUDE.md) — 模块边界
- [CLAUDE.md §领域数据字典](../../CLAUDE.md) — 各 status 枚举
- [docs/api/console-api-protocol.md](../api/console-api-protocol.md) — console API 中 config_approval / approval_command 的对外协议
- [docs/design/i18n.md](../design/i18n.md) — `LocalizedErrorCarrier` 在 11 张表上的应用，含 approval 表
