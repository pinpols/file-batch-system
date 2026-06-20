# 告警升级阶梯(Alert Escalation Ladder)

> 运维告警闭环:让长期无人 ack 的告警在日志/指标侧持续放大可见度,而不是静默淹没在告警表里。

## 背景

`batch.alert_event` 表用 `dedup_fingerprint` 去重落库 orchestrator 各子系统(SLA / 熔断 / drain / 对账等)的告警。
此前一条告警 emit 后若无人处理,状态停在 `OPEN` 也只是静静躺着——没有任何机制随时间放大它的存在感。
升级阶梯补上这一环:OPEN 告警超过 ack-SLA 仍未被确认(状态未转 `ACKED`)时,周期性 sweep 会逐级抬升其
`escalation_tier`,每升一级打一条 ERROR 日志 + `batch.alert.escalations` 计数,供日志告警 / 指标看板侧的
二级链路放大。

## 机制

| 组件 | 职责 |
|---|---|
| `V176__alert_event_escalation.sql` | `alert_event` 加 `escalation_tier`(默认 0)+ `escalated_at`;OPEN 行 partial index 让 sweep 廉价 |
| `AlertEscalationScheduler` | 默认每 60s sweep 一次,ShedLock `alert_escalation_sweep` 单节点执行,优雅停机时跳过 |
| `DefaultAlertEventService#escalateOverdue` | 选出超期 OPEN 告警,逐条 CAS 升级 tier,打日志 + 计数 |

**SLA 随 tier 递进**:第 `N` 级需静默 `slaMinutes * N` 分钟才触发(越往上越慢),避免单故障短时间连环升级刷屏。
升级用 `expectedTier` 乐观守护,被并发 ack 或其它节点抢先升级时跳过(不重复计数)。

**边界**:升级只放大「可见度」(日志 + 指标),不直接改 `severity`、不重新走 emit/通知派发链路——
把告警实际推到外部通道(短信/邮件/Webhook)的二级放大仍由现有日志/指标告警规则承接。状态机不介入。

## 配置开关(`batch.alert.escalation.*`)

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 整体开关;`false` 时 sweep 直接短路 |
| `sla-minutes` | `30` | 每级静默阈值基数(分钟),第 N 级需 `sla-minutes*N` 分钟 |
| `max-tier` | `3` | 升到此 tier 后停止(防无限升级) |
| `batch-limit` | `200` | 单次 sweep 最多处理条数,控制单事务规模 |
| `poll-interval-millis` | `60000` | sweep 周期 |

## 指标 / 日志

- `batch.alert.escalations{alert_type,tier}` — 每次成功升级 +1,可在看板上对 tier≥2 设二级告警。
- 每次升级一条 `ERROR` 日志,带 `alertId / tenantId / alertType / severity / traceId`,供日志告警规则匹配。
