# 告警分诊:枚举语义 → 分诊排序 → 去重归并 → 处置/升级

当用户问「现在有哪些告警 / 该先处理哪个 / 告警态势 / 某类告警要不要升级」时,
先调用只读工具 `getOpenAlerts`(当前未决 OPEN 告警)、必要时 `getRecentAlerts`(近期告警,不限状态)拿到真实告警,
再据此分诊。工具只在当前租户内只读查询,**AI 只给建议**:ack / silence / close 由人在控制台(`/api/console/alerts/{id}/ack|silence|close`)操作,AI 不代执行、不写库。

告警来自 `alert_event` 表,由 orchestrator 侧 `DefaultAlertEventService.raise(...)` 登记,同 `dedup_fingerprint`(tenantId+alertType+resourceKey 的哈希)会归并到同一行并累加 `occurrenceCount`。

## severity(严重度,枚举 AlertSeverity)
固定四档,分诊排序从高到低:
- `CRITICAL` — 严重,业务受损/SLA 已破,最高优先。
- `ERROR` — 错误,功能失败但未必全局受损。
- `WARN` — 警告,潜在风险或阈值临近。
- `INFO` — 信息,仅告知。
分诊时先按 severity 排序,同档再按 `occurrenceCount`(反复次数)和 `lastSeenAt`(是否仍在持续)排。

## status(状态,枚举 AlertStatus)
- `OPEN` — 待处理(新建即 OPEN),分诊主要看这些。
- `ACKED` — 已确认(有人接手处理中)。
- `SUPPRESSED` — 已抑制(silence,主动静默,通常噪声或已知在处理)。
- `CLOSED` — 已关闭(处置完成);CLOSED 是终态,不能再回退到非 CLOSED。

## alertType(告警类型,自由字符串,可扩展)
类型不是封闭枚举,是登记方传入的字符串;当前系统内在用的真实类型:
- `JOB_SLA_VIOLATION` — 作业 SLA 超时(实例长时间未完成)。
- `JOB_SLA_VIOLATION_ESCALATED` — SLA 超时且升级后(见下方升级机制)。
- `ASSET_FRESHNESS_STALE` — 资产新鲜度:数据陈旧(超过 staleAt 仍未更新)。
- `ASSET_FRESHNESS_MISSING` — 资产新鲜度:到期数据缺失(expectedAt 未到达)。
- `WORKFLOW_NODE_MANUAL_SKIP` — 工作流节点被人工跳过(留痕告警)。
经 Alertmanager webhook 接入的外部告警,其 alertType 可能是外部来源自定义字符串。遇到不认识的 alertType 时,不要编造语义,按 title / detailJson / severity 客观描述。

## 关键字段(工具返回)
- `occurrenceCount` — 同指纹已归并的发生次数;**去重归并**看它:同一 alertType 反复出现应归并成「这条告警已发生 N 次」而非逐条列举。
- `firstSeenAt` / `lastSeenAt` — 首次/最近一次;`lastSeenAt` 很近说明仍在持续发生。
- `traceId` — 关联排障链路,可引导用户去 trace 快照 / 执行日志进一步定位。
- `title` / `detailJson` — 概要与明细。

## 升级(escalation)机制
`alert_event` 有 `escalation_tier` / `escalation_notified_tier`:OPEN 告警超过 ack-SLA 未确认会自动抬升 tier 并二次通知(webhook)。
判断「要不要升级」时:CRITICAL 且 `occurrenceCount` 持续增长、`lastSeenAt` 仍在推进、长时间 OPEN 未 ACK 的,建议尽快 ack 并处置;
已 `SUPPRESSED`/`ACKED` 的通常无需再提醒升级。AI 只建议,实际升级由后台 escalation notifier 按水位线推进。

## 分诊输出建议(给人看的)
1. 一句话态势:当前 OPEN 告警共 N 条,其中 CRITICAL M 条。
2. 按 severity 排序列出,合并同类(标注 occurrenceCount)。
3. 每条给方向性处置建议 + 是否建议升级 + 可跟进的 traceId。
4. 明确「以上为建议,ack/silence/close 请在控制台操作」。
