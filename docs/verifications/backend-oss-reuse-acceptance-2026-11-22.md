# 后端开源组件复用联验（1A Cron 缓存 + Alertmanager）

## 目标、范围与基线

- 目标：收口[后端借鉴与改进规划](../plans/backend-borrowings-and-improvements-2026-07-10.md#backend-oss-reuse-plan) §7——实施 1A Cron 缓存、完成 Alertmanager 通知本地联验；1B/2A 关闭、2B 暂缓只记录理由。
- 基线：`37a817471`（#1455）；工作树 `codex/backend-oss-reuse-review`，改动未提交。
- 环境：macOS，JDK 21.0.12.1，Maven Wrapper，Docker 29.9.3；本地基础设施容器（Postgres/Kafka/Valkey/MinIO/mockserver）在运行；应用进程未常驻，E2E 用 Testcontainers 起真实 Alertmanager v0.28.1 与 Spring Boot 随机端口。
- 范围外：目标环境真实收件渠道、AM HA 与告警风暴、bearer token 轮换；1B/2A/2B 的实施。

## 一、1A Cron 缓存

改动：

- `CronExpressionAdapter` 的 `ConcurrentHashMap` 换为 Caffeine `maximumSize(1024)`；删除生产无调用点的 `evict`（原先只有测试引用）。
- 容量取“活跃租户 × 调度 × 时区”组合的估计值；被淘汰的键在下次调用时走同一解析路径重算，结果不变。
- 新增三个用例：淘汰后重算一致、容量上限、同一表达式跨时区在淘汰下仍独立。

| 命令 | 结果 |
|---|---|
| `./mvnw -B -pl batch-trigger -am -Dtest='CronExpressionAdapterTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test` | 19/19 通过 |
| `./mvnw -B -pl batch-trigger test` | 434 个用例、4 个错误，全部在 `TriggerAsyncLaunchIntegrationTest`（outbox 停在 `NEW`，8–10s 超时） |
| `./mvnw -B -pl batch-trigger -Dtest='TriggerAsyncLaunchIntegrationTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test` | 4/4 通过，30.2s |
| 全模块复跑 `./mvnw -B -pl batch-trigger test` | 434/434 通过，BUILD SUCCESS |

判断：首次 4 个失败只出现在 `TriggerAsyncLaunchIntegrationTest`（真实 PG/Kafka 的 outbox relay 时序），该测试不引用 `CronExpressionAdapter`；单类复跑 4/4、全模块复跑 434/434，说明是全量并发下的瞬时环境问题，未复现，不是缓存改动的功能回归。

## 二、Alertmanager 联验

### 2.1 契约层

| §7.1 步骤 | 证据 |
|---|---|
| 1 route 生成与 `check-config` | `bash scripts/ops/am-migration-smoke.sh` PASS：仓库模板与 `gen-alertmanager-config.py` 由 `alert_routing_config` 样本生成的 yml 均 `amtool check-config` 通过 |
| 3/4 告警入库与标签 | smoke POST `/api/v2/alerts` 后 `GET /api/v2/alerts` 回读 `alertname`/`tenant`/`severity`/`alert_group` 与发送值一致 |
| 5 路由匹配 | `amtool config routes test`：`warning + alert_group=sla` → `batch-sla`；`critical + alert_group=sla` → `batch-pager`；`critical + alert_group=infra` → `batch-pager` |
| 8 silence 形状 | smoke POST `/api/v2/silences` 后回读状态 `active` |
| resolved 回读 | smoke 发送 `endsAt=now` 后，原 firing 不再出现在 `active=true` 集合（未产生幽灵 resolved） |

tenant 按设计不是 AM 路由维度，而是回调时反查渠道的 label；分流验证在 2.2 的专用用例中完成。

### 2.2 全链

命令：`./mvnw -B -pl batch-e2e-tests -am -Dtest='AlertmanagerDeliveryChainE2eIT,JobSlaAlertmanagerIdentityE2eIT' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`
结果：3/3 通过（`AlertmanagerDeliveryChainE2eIT` 2 个用例 60.9s，`JobSlaAlertmanagerIdentityE2eIT` 1 个用例 4.6s），BUILD SUCCESS。用例把内联 SQL 提取为具名变量后，又单独复跑 `AlertmanagerDeliveryChainE2eIT`：2/2 通过、58.8s，证据与最终代码一致。

| §7.1 步骤 | 用例 | 断言 |
|---|---|---|
| 2/3/4/6 投递与日志 | `shouldDeliverFiringAndResolvedThroughAlertmanager` | 真实 AM 容器；渠道首次返回 503 后以相同 `Idempotency-Key` 重试成功；`notification_delivery_log`/`alert_notification_receipt` 记录发生与恢复；resolved 载荷 `status=resolved` |
| 7 租户分流 | `shouldDeliverEachTenantToOwnChannel` | 两个租户经同一 AM receiver 回调后分别落到 `/receive` 与 `/receive-other`；各自正文只含本租户；`notification_delivery_log` 各一条 `event_type=ALERTMANAGER` 的 SUCCESS |
| 8 silence 桥接 | `JobSlaAlertmanagerIdentityE2eIT` | AM 中 `silencedBy` 只命中被静默的告警；resolve 后该告警消失、其余不受影响 |
| 9 inhibit | 同上 | 同组 critical 出现后，warning 的 `inhibitedBy` 恰好 1 条 |
| 10 回滚开关 | `AlertmanagerEmitPublisherTest.disabledPublisher_isNoOp` | `enabled=false` 时 `isEnabled=false` 且 `publishFiring` no-op；未用实时 AM 复证 |

### 2.3 联验中修正的问题

首版租户分流用例用自拟渠道码 `sla-chain-a/b`，与 `AlertmanagerNotifyService` 的 `channelCode = receiver` 反查不一致，返回 `SKIPPED/no_channel`、不写回执。改为两个租户各插一条 `channel_code = sla-chain`（唯一约束是 `tenant_id + channel_code`）后通过；该约束已写进用例注释，避免后续再按渠道码拆分。

## 三、关闭与暂缓（本次无代码）

- 1B 不采纳：`RetryPolicy` 只按异常判定，无法表达按 `TaskResult.statusCode` 重试；替换需自造信号异常并还原响应。见方案 §7.2。
- 2A 不做：`BACKSLASH`/`NONE` 无 Commons CSV 等价配置，仓库也无历史导出模板可作逐字节基准。见方案 §7.3。
- 2B 暂缓：触发条件与届时范围见方案 §7.4。

## 四、未验证与回滚

未验证：真实钉钉/企微/SMS/客户 webhook 的最终送达；staging 与目标环境；AM HA、告警风暴和 bearer token 轮换；本次 JUnit 通过不等于 CI Full Gate 或发布验收通过。

回滚：1A 是单文件加测试差异，可整批回退，缓存冷启动不需要迁移；AM 侧可分别关闭 `batch.console.alertmanager.enabled`、`batch.console.alertmanager.silence.enabled` 与 `batch.alert.am-emit.enabled` 停止外发，`alert_event` 与投递记录保留。
