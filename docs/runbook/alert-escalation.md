# 告警升级阶梯(Alert Escalation Ladder)

> 运维告警闭环:让长期无人 ack 的告警在日志/指标侧持续放大可见度,而不是静默淹没在告警表里。

## 背景

`batch.alert_event` 表用 `dedup_fingerprint` 去重落库 orchestrator 各子系统(SLA / 熔断 / drain / 对账等)的告警。
此前一条告警 emit 后若无人处理,状态停在 `OPEN` 也只是静静躺着——没有任何机制随时间放大它的存在感。
升级阶梯补上这一环:OPEN 告警超过 ack-SLA 仍未被确认(状态未转 `ACKED`)时,周期性 sweep 会逐级抬升其
`escalation_tier`,每升一级打一条 ERROR 日志 + `batch.alert.escalations` 计数,供日志告警 / 指标看板侧的
二级链路放大。

## 机制

阶梯分两段:**orchestrator 抬 tier(可见度)** + **console-api 推通知(最后一公里)**。

| 组件 | 模块 | 职责 |
|---|---|---|
| `V176__alert_event_escalation.sql` | db | `alert_event` 加 `escalation_tier`(默认 0)+ `escalated_at`;OPEN 行 partial index 让 sweep 廉价 |
| `AlertEscalationScheduler` | orchestrator | 默认每 60s sweep 一次,ShedLock `alert_escalation_sweep` 单节点执行,优雅停机时跳过 |
| `DefaultAlertEventService#escalateOverdue` | orchestrator | 选出超期 OPEN 告警,逐条 CAS 升级 tier,打日志 + 计数 |
| `V181__alert_event_escalation_notify.sql` | db | `alert_event` 加 `escalation_notified_tier`(通知水位线,默认 0)+ OPEN 行 notify 扫描 partial index |
| `AlertEscalationNotifier` | console-api | 默认每 60s 扫「`escalation_tier > escalation_notified_tier` 的 OPEN 告警」,经现有 webhook 投递链路推「告警已升级」,再 CAS 推进水位线;自管理调度 + ShedLock `alert-escalation-notify` 多实例互斥 |

**SLA 随 tier 递进**:第 `N` 级需静默 `slaMinutes * N` 分钟才触发(越往上越慢),避免单故障短时间连环升级刷屏。
升级用 `expectedTier` 乐观守护,被并发 ack 或其它节点抢先升级时跳过(不重复计数)。

**为什么 notifier 在 console-api 而非 orchestrator**:升级在 orchestrator 抬 tier 写共享表 `alert_event`;真正的通知渠道配置 / webhook 投递 /
`ConsoleRealtimeDomainEvent` → 分发器全在 console-api,且 console-api **无 Kafka consumer**。让 console-api 轮询共享表上「刚升级未通知」的行,复用它本就有的「定时轮询 + ShedLock + webhook 投递」范式(同 `WebhookDeliveryRelay`),
零新增 Kafka / policy 表 / 跨模块依赖。投递事件 `alerts/alert-escalated` 与告警 ack 的 `alert-updated` 走同一条流,订阅 `alerts` 流的 webhook 自动收到。

**至少一次 + 不重复**:notifier 先发事件再 CAS 推进水位线(发了没标 → 下轮重发,at-least-once);ShedLock 保证多实例不并发轮询;
`markEscalationNotified` 的 CAS(`escalation_notified_tier = expected AND status='OPEN'`)兜住「被并发 ack / 抢先通知」的竞态。每次 tier 抬升只成功推进一次水位线 ⇒ 只通知一次。

**边界(v1)**:平台**已接通的投递渠道只有 WEBHOOK(+ Web Push)**;`notification_channel` 里的 EMAIL / 钉钉 / 企微仍是配置占位、sender 未实现,
故升级通知 v1 只覆盖 WEBHOOK。要让邮件 / IM 真正被呼叫,需另行实现对应 sender(独立后续)。`alert_routing_config`(前端「告警路由」页管的表)目前仍是孤儿配置、无运行时消费方,不在本回路内。升级**不改 `severity`、不重走 emit**,状态机不介入。

## 配置开关(`batch.alert.escalation.*`)

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 整体开关;`false` 时 sweep 直接短路 |
| `sla-minutes` | `30` | 每级静默阈值基数(分钟),第 N 级需 `sla-minutes*N` 分钟 |
| `max-tier` | `3` | 升到此 tier 后停止(防无限升级) |
| `batch-limit` | `200` | 单次 sweep 最多处理条数,控制单事务规模 |
| `poll-interval-millis` | `60000` | sweep 周期 |

### 升级通知(`batch.alert.escalation.notify.*`,console-api 侧)

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 整体开关;`false` 时 notifier 不创建(退化回纯日志/指标放大) |
| `poll-interval-millis` | `60000` | notifier 轮询周期,与 orchestrator 升级 sweep 对齐 |
| `batch-size` | `100` | 单轮最多处理多少条待通知升级告警 |

## 指标 / 日志

- `batch.alert.escalations{alert_type,tier}` — orchestrator 每次成功抬 tier +1,可在看板上对 tier≥2 设二级告警。
- 每次升级一条 `ERROR` 日志,带 `alertId / tenantId / alertType / severity / traceId`,供日志告警规则匹配。
- `batch.alert.escalation.notifications` — console-api 每次成功把升级推到平台内 webhook +1(水位线推进成功才计数)。
- notifier 每次成功通知一条 `INFO` 日志:`Alert escalation pushed to in-platform notification: alertId=… tier=…`。
