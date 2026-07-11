# Alertmanager 完整迁移方案(emit 直连 + 直接切换 direct cutover + 退役自研升级链路)

> 状态:方案(RFC),仅文档,未改生产代码。
> 前提裁定:**系统尚未上线,无存量真实告警流量与真实下游** —— 不需要影子期/双发对账,采用**直接迁移(direct cutover)**:
> emit→AM 直接接通(配置开关兜底回滚),自研升级链路同 PR 置关、紧随 PR 删净,一刀切。
> 目标:把「告警分组 / 去重 / 静默 / 抑制 / 升级路由」这套通知**编排**职责从自研链路迁到 Alertmanager(下称 AM),
> 同时把「告警产生 / 租户隔离 / 审计 / console 展示」牢牢留在 fbs。

相关 PR / 迁移:#603(升级最后一公里 webhook)、#775(投递日志)、#777(AM 出口 sink)、
#779–781(通知限流 tenant key)、#784/#789(SSRF pin);V19 / V43 / V176 / V181。

---

## 0. TL;DR

- **AM sink(#777)现在只做「出口的后半段」**:`POST /internal/am-notify/{receiver}`
  (`AlertmanagerNotifyController.java:46`)接住 AM 已经分组/路由完的 webhook,复用既有多渠道 sender 投递到真实渠道并落
  `notification_delivery_log`。**它是 AM→console→下游,不是 fbs→AM。**
- **迁移真正缺的那一段是「入口」**:fbs 内部 `alertEventService.emit(...)` 产生的运维告警(SLA / 熔断 / drain /
  资产新鲜度 / workflow 校验),目前**从未推进 AM**(全仓 `grep api/v2/alerts` 为空)。
  本方案补这一段:emit 落库后并行 `POST {AM}/api/v2/alerts`,直接接通。
- **`alert_routing_config`(V43)裁定 = 复用为「AM route 生成器的输入」,不废弃、不新增运行时消费者**。
  该表 schema(`group_by / group_wait / group_interval / repeat_interval / receiver / severity / team / alert_group`)
  V43 建表注释已明写 "Aligns with Alertmanager route semantics"(`V43__create_alert_routing_config.sql:5`),
  它天生就是 AM route 的关系型镜像。迁移里它从「前端能配但运行时无人消费」升级为「渲染成 `alertmanager.yml` route 树的输入表」。
- **迁移一句话**:一期完成 —— emit→AM 接通(开关可回滚)+ am-notify 按 tenant label 反查渠道 + route 生成器,
  同 PR 把自研 `AlertEscalationScheduler`/`AlertEscalationNotifier` 置为默认关(代码留一版作回滚),紧随 PR 删净;
  验证 = 本地 docker AM 全链 smoke + sim/e2e 断言,真下游/HA/风暴留到上线前 checklist。
- **❌ 不迁清单**(见 §3):告警产生逻辑、租户隔离、审计(alert.ack/silence/close)、console 展示与 `alert_event` 表,
  一律留在 fbs。AM 只接管「一条 OPEN 告警产生之后到通知发出之前」的编排。

---

## 1. 现状核查(亲读代码,给出 `文件:行` / 表 / PR)

### 1.1 告警的产生与落库(留 fbs,不迁)

- 统一 emit 入口 `DefaultAlertEventService.emit(...)`(`DefaultAlertEventService.java:34`):
  校验 `tenantId + alertType` → 补默认(serviceName=`batch-orchestrator`、severity=`WARN`)→ 算
  `dedup_fingerprint = hash(tenantId, alertType, resourceKey)` → `alertEventMapper.insertOrMerge(...)`
  靠 `alert_event` 唯一约束 `uk_alert_event_dedup (tenant_id, dedup_fingerprint)`(`V19__alert_event.sql:24`)做 UPSERT 去重,
  同时打 `batch.alert.events` 计数。
- emit 的调用点(告警的真实来源,全部留 fbs):
  - `AlertInternalController.java:25`(`POST /internal/alerts` 内网入口)
  - `AssetFreshnessPolicyService.java:114`(资产新鲜度)
  - `WorkflowRunManagementApplicationService.java:215`
  - `BatchDayGateService.java:237` / `LaunchBatchDayService.java:599`(批次门禁 / 启动)
  - `CrossDayDependencyReconciler.java:221` / `WorkflowValidatorReconciler.java:110`
  - `JobSlaScheduler.java:132` / `:179`(SLA 违约)
- `alert_event` 表(`V19`):`severity ∈ {INFO,WARN,ERROR,CRITICAL}`、`status ∈ {OPEN,ACKED,SUPPRESSED,CLOSED}`;
  按 `tenant_id` 建索引与唯一约束——**告警从产生就是租户维度的**。

### 1.2 自研升级链路(要退役的对象)

- **orchestrator 侧抬升**:`AlertEscalationScheduler.sweep()`(`AlertEscalationScheduler.java:30`,默认 60s,ShedLock 单节点)
  → `escalateOverdue(...)`(`DefaultAlertEventService.java:69`):把超过 `ack-SLA * tier` 仍 OPEN 的告警逐级抬升
  `escalation_tier`(V176:`escalation_tier` / `escalated_at`,`V176__alert_event_escalation.sql:12`),
  每升一级打 ERROR 日志 + `batch.alert.escalations` 计数。**headless:只放大可见度,不主动通知。**
- **console 侧最后一公里(PR#603)**:`AlertEscalationNotifier`(`AlertEscalationNotifier.java`,自管理
  `ScheduledExecutorService` + programmatic ShedLock,默认 60s):取 `escalation_tier > escalation_notified_tier`
  的行(V181 水位线,`V181__alert_event_escalation_notify.sql:20`)→ 发 `alerts / ALERT_ESCALATED` 领域事件
  → 走现有 webhook 分发 → CAS 推进 `escalation_notified_tier`,保证每次 tier 抬升只通知一次。
  **边界(`AlertEscalationNotifier.java:48`、`AlertEscalationNotifyProperties.java:11`):v1 只覆盖 WEBHOOK(+ Web Push);
  EMAIL / 钉钉 / 企微 sender 尚未接通。** 这正是自研链路的最大短板——AM 迁移顺带补齐多渠道。
- **console 治理动作(审计,留 fbs)**:`ConsoleAlertController`(`ConsoleAlertController.java:35/45/55`)
  的 ack / silence / close,`@Idempotent` + `@AuditAction`(`alert.ack` / `alert.silence` / `alert.close`),
  `@PreAuthorize ROLE_ADMIN|ROLE_TENANT_ADMIN`。

### 1.3 AM sink(#777)——现在到哪一步

**方向是 AM → console → 下游(出口后半段),不是 fbs → AM。**

- 端点 `AlertmanagerNotifyController.receive(...)`(`AlertmanagerNotifyController.java:46`):
  `POST /internal/am-notify/{receiver}`,内网端点,AM 独立进程够不到 console cookie/JWT,
  用**共享密钥 bearer token**自校验,**fail-closed:未配 token 一律 401**(`:61-77`;`AlertmanagerNotifyProperties.java:23`)。
- 编排 `AlertmanagerNotifyService.deliver(...)`(`AlertmanagerNotifyService.java:54`):
  `receiver` 路径变量**直接映射** `notification_channel.channel_code`,反查
  `properties.getTenantId()`(默认 `"system"`,`AlertmanagerNotifyProperties.java:27`)下的渠道;
  WEBHOOK 走 `WebhookDispatcher`(自带 SSRF + 超时),其余走 `NotificationSenderRegistry`;
  落一条 `notification_delivery_log`(`eventType=ALERTMANAGER`,`ruleId=0`,`:133-151`)。
  缺渠道 → `am.notify.skipped` 计数 + 返回 `SKIPPED`,不落库(`:58-65`)。
- typed 契约:`AlertmanagerWebhookPayload`(AM webhook v4)/ `AlertmanagerAlert`,渲染器
  `AlertmanagerAlertRenderer.render(...)` 把批量告警折叠成人类可读正文(`maxAlerts` 上限防撑爆)。
- 路由「方案 B(消费 `alert_routing_config` 做 receiver→channel 映射)」在
  `AlertmanagerNotifyService.java:26` 注释里明写**首版不做**。

**默认开关**:`batch.console.alertmanager.enabled` 默认 `true`(`AlertmanagerNotifyProperties.java:21`),
但 `bearerToken` 空则端点一律 401——即**代码 enabled、密钥未配则实际关闭(fail-closed)**。

**结论:sink 出口后半段已就绪并安全;迁移缺的是入口(fbs→AM push)和 route 配置生成两段。**

### 1.4 metrics 侧的 AM 链路(已存在,与 emit 链路平行)

- Prometheus 已配 `alerting → alertmanager:9093`(`prometheus.yml:10-13`),规则文件
  `prometheus-batch-rules.yml` 有 **76 条 `alert:` 规则**,每条带 `severity / team / alert_group` label,
  正好喂 AM route 树(`alertmanager-batch-template.yml` 的 `group_by:[alertname,team,alert_group,severity]` + `routes`)。
- **注意**:这条是 metrics-driven(PromQL 阈值触发),和 `alert_event.emit` 的**业务事件驱动**是**两套来源**。
  迁移只处理 emit 这套业务告警;metrics 这套本就走 AM,复用同一 route/receiver/inhibit 配置即可,是「已经在 AM 里」的既成事实,
  也是本地验证 route/模板的现成素材。

### 1.5 出口安全层(AM egress 要不要过同样的层——要)

- SSRF:`CallbackUrlValidator.java` + `SsrfGuardedDns.java`(#784/#789),`WebhookDispatcher` 投递时校验并 pin。
- 限流:`ConsoleRateLimitFilter` / `SlidingWindowRateLimiter`(#779–781 含 tenant key)。
- **因为 AM egress 复用 `AlertmanagerNotifyService → WebhookDispatcher / NotificationSenderRegistry`
  同一批 sender,SSRF/超时天然继承**;`am-notify` 端点自身也应挂到 console 限流链(见 §8 风险)。

### 1.6 `alert_routing_config`(V43)——运行时消费者核查

- 运行时**只有 CRUD + 配置同步**:`ConsoleAlertRoutingApplicationService`(`list/create/update/toggle`,纯 CRUD 接口)、
  `ConsoleAlertRoutingController`、`AlertRoutingSaveRequest`、config-sync bundle
  (`ConfigSyncBundlePayload` / `TenantConfigBatchInitRequest`)。
- **无任何运行时组件按这张表做告警路由**(自研 `AlertEscalationNotifier` 只发单一 `alerts` 流,不读该表)。
- **裁定:它是「提前建好、等 AM 来消费」的 AM route 关系型镜像**。迁移中把它变成
  `alertmanager.yml` route 树的 single source of truth(生成器读表 → 渲染 → `amtool check-config` → reload)。
  **不删、不加运行时路由消费者**(路由交给 AM 进程)。见 §6.4 归宿。

---

## 2. 目标态职责划分

| 职责 | 归属 | 说明 |
|---|---|---|
| 告警**产生**(SLA/熔断/drain/新鲜度/workflow) | **fbs** | `emit(...)` 调用点全留;AM 不感知业务 |
| 落库 + 去重(`dedup_fingerprint` UPSERT) | **fbs** | `alert_event` 表是审计与 console 展示的底座 |
| 租户隔离 | **fbs** | 告警产生即带 `tenant_id`;AM 靠 label 携带,不做隔离(见 §4) |
| 分组 group_by / 去重(通知层) / repeat 抑制 | **AM** | route 的 `group_by / group_interval / repeat_interval` |
| 静默 silence | **AM(主)+ fbs(镜像)** | AM silence 为准;console silence 单向桥接(见 §3.5) |
| 抑制 inhibit(critical 压 warning) | **AM** | `inhibit_rules`(`alertmanager-batch-template.yml:68`) |
| 升级路由(按 severity/团队分 receiver) | **AM** | route 树 + receiver;取代 `escalation_tier` 阶梯 |
| 最后一公里投递(webhook/钉钉/企微/slack/短信) | **fbs(am-notify sink)** | AM → `am-notify` → 既有 sender,顺带补齐多渠道 |
| 投递日志 | **fbs** | `notification_delivery_log`(#775) |
| console 展示 / ack / silence / close 审计 | **fbs** | `ConsoleAlertController` 不动 |

AM 接管的边界严格限定为:**「一条 OPEN 告警产生之后 → 通知发出之前」的编排(分组/去重/静默/抑制/路由)**。

---

## 3. ❌ 不迁清单(与目标态同等重要)

1. **告警产生逻辑**:所有 `emit(...)` 调用点、阈值判定、`resourceKey`/`dedup_fingerprint` 语义留 fbs。
   AM 是纯下游,绝不反向决定「什么算告警」。
2. **租户隔离**:`alert_event` 的 `tenant_id` 维度、`uk_alert_event_dedup` 的按租户去重、console 查询的租户过滤——全留 fbs。
   AM 是**单实例全局**,不具备租户强隔离,只能靠 label 携带 `tenant_id`(见 §4),**不得把租户隔离寄托给 AM**。
3. **审计留 fbs**:`alert.ack / alert.silence / alert.close`(`ConsoleAlertController.java:35/45/55`,`@AuditAction`)
   继续走 console 审计链。AM 的 silence 操作**不产生 fbs 审计**,故 console 侧 silence 必须落审计后再向 AM 同步(见下)。
4. **`alert_event` 表**:**永久保留作审计 / console 展示**,不随升级链路退役而删(见 §6.3 退役清单)。
5. **console silence ↔ AM silence 的语义差(不迁,做单向桥接)**:
   - console silence 作用于 `alert_event.status=SUPPRESSED`(单条业务告警);
   - AM silence 作用于 label matcher(一类告警,时间窗)。
   - **裁定:单向桥接,与迁移同 PR 落地**(系统未上线,没有可依赖的过渡期;自研 notifier 退役后,console silence
     若不桥接到 AM,将只改 fbs 状态而 AM 照样 repeat 通知,语义直接破损)。实现:console silence/close 时,由 fbs
     **调 AM `POST /api/v2/silences`** 建等价 matcher(按 `alertname=alert_type` + `tenant` label,时长取请求参数或默认)。
     反向(AM UI 建的 silence 回写 `alert_event`)**不做**,文档标注「AM UI silence 不反映到 console 展示」为已知语义差,
     约定 silence 统一从 console 操作。

---

## 4. 多租户 label 设计(AM 单实例)

AM 单实例全局,租户维度只能靠 **label 携带**。emit 直连时,fbs 把 `alert_event` 字段映射成 AM alert 的 labels:

| `alert_event` / emit 字段 | AM label | 备注 |
|---|---|---|
| `alert_type` | `alertname` | AM 分组/inhibit 的主键之一 |
| `tenant_id` | `tenant` | 租户维度;route 可按 `tenant` 二次分流到租户专属 receiver |
| `severity`(INFO/WARN/ERROR/CRITICAL) | `severity`(info/warning/error/critical) | **大小写 + 词形要映射**:AM 模板用小写 `critical`/`warning`(`alertmanager-batch-template.yml:12/71`);`ERROR→error`、`CRITICAL→critical`、`WARN→warning`、`INFO→info` |
| `service_name` | `service` | 展示 / 分组辅助 |
| (route 分类)`alert_group` | `alert_group` | 由 `alert_type` 映射表推导(dispatch/sla/freshness/capacity/…),对齐 route matcher(`:15-25`) |
| `team` | `team` | 同上,来自 `alert_routing_config.team` 或 alert_type 映射 |
| `resource_key` | `resource`(可选,不进 `group_by`) | 高基数,**不放 `group_by`**(见 §8 基数风险) |
| `trace_id` | annotation `trace_id`(**annotation 不是 label**) | 高基数,放 annotation |
| `title` / `detail_json` | annotation `summary` / `description` | 人类可读,进 annotation,不进 label |

annotations:`summary`(title)、`description`(detail_json 摘要)、`trace_id`、`alert_id`(回链 console)。

**租户路由两种形态,二选一(建议 A)**:
- **A. 全局 receiver + label 分流(推荐 v1)**:所有租户共用一套 route/receiver,route 按 `tenant` matcher 决定去哪个 receiver;
  `am-notify` 端负责最终落到「哪个租户的哪个渠道」。**硬前置**:`am-notify` 当前 `tenantId` 是单值默认 `system`
  (`AlertmanagerNotifyProperties.java:27`),必须扩成**按 payload 的 `tenant` label 反查该租户的渠道**(见 §7 附录 patch 建议)。
- **B. 每租户一套 receiver(高隔离)**:route 为每租户生成子树。租户多时 route 树膨胀、reload 频繁,**YAGNI,后置**。

**基数守则**:进 `group_by` 的 label 只能是**低基数枚举**(alertname / tenant / severity / alert_group / team);
`resource_key / trace_id / instance` 一律进 annotation 或非分组 label,否则 AM 分组爆炸(见 §8)。

---

## 5. 目标态数据流(迁移后)

```
业务事件 ──emit()──► alert_event(去重/落库/租户/审计,留 fbs)
                         │
                         ├─(保留)──► console 展示 / ack / silence / close(审计;silence 单向桥接到 AM)
                         │
                         └─(新增, emit 直连)──► POST {AM}/api/v2/alerts  (labels: alertname/tenant/severity/alert_group/team)
                                                        │
                                            Alertmanager(分组/去重/静默/抑制/路由 —— 编排职责)
                                                        │  route 由 alert_routing_config 生成
                                                        ▼
                                       POST /internal/am-notify/{receiver}(#777 sink,bearer)
                                                        │
                                            AlertmanagerNotifyService → 既有 sender(SSRF/限流复用)
                                                        ▼
                                       真实渠道(webhook/钉钉/企微/slack/短信)+ notification_delivery_log
```

Prometheus metrics 那 76 条规则同样汇入中间的 AM 框(§1.4),复用同一 route/inhibit——迁移后 emit 业务告警与 metrics 告警**统一编排**,是本次迁移的额外收益。

---

## 6. 直接迁移(direct cutover)设计

**原则:系统未上线,无存量流量 —— 一刀切接通,配置开关兜底回滚,不做双发对账、不做影子期、不做灰度 allowlist。**

### 6.1 emit→AM 直连

在 `DefaultAlertEventService.emit(...)`(`DefaultAlertEventService.java:34`)**成功 `insertOrMerge` 之后**,
追加旁路 publisher(新增 `AlertmanagerEmitPublisher`,orchestrator 侧):把刚落库的 `AlertEventEntity`
映射成 AM `PostableAlert`,`POST {alertmanager}/api/v2/alerts`。

- **失败隔离**:AM 调用**包在独立 try/catch**,异常只 warn + 打 `batch.alert.am_emit.failed` 计数,**绝不冒泡**污染 emit 事务;
  DB 落库为准,AM 推送尽力而为(重复 fire AM 端幂等)。
- **异步 + 超时 + 熔断**:短超时(如 2s)HTTP,异步(独立线程池)避免拖慢 emit 主路径;连续失败熔断降级为只落库。
- **事务边界**:emit 是 `@Transactional`(`:33`),AM push 必须放**事务提交后**
  (`TransactionSynchronization.afterCommit` 或异步),否则事务回滚了 AM 却收到了告警。
- **firing/resolved 语义**:靠周期 re-emit(30–60s,对仍 OPEN 的告警重发)维持 firing;
  console close 时发一条带 `endsAt` 的 resolved(与 silence 桥接同一批实现,工作量极小,v1 就带上,
  否则 CLOSED 告警要等 `resolve_timeout: 5m`(`alertmanager-batch-template.yml:2`)才消)。

### 6.2 配置开关(仅回滚用,无灰度维度)

新增 `AlertmanagerEmitProperties`(`prefix=batch.alert.am-emit`):
- `enabled`(全局唯一开关;**合入即默认 true** —— 未上线,直接切,关掉即回滚);
- `endpoint` / `timeout-millis`(默认 2000)/ `resend-interval-seconds`(默认 60)。

**不做** tenant/alert-type allowlist —— 没有存量流量需要灰度保护,回滚粒度就是全局开关。

### 6.3 自研升级链路退役(同 PR 置关 + 紧随删净)

- **PR-1(迁移 PR)**:emit 直连 + am-notify 租户反查 + silence/close 桥接落地;同 PR 把
  `batch.alert.escalation.enabled`(`AlertEscalationProperties.java:22`)与
  `batch.alert.escalation.notify.enabled`(`AlertEscalationNotifyProperties.java:17`)**默认值翻成 false**
  (代码保留一版,作为回滚路径:AM 出问题时 `am-emit.enabled=false` + 两个自研开关翻回 true,秒级恢复原行为)。
- **PR-2(退役 PR,紧随)**:PR-1 验证绿后删净:
  - orchestrator:`AlertEscalationScheduler`、`escalateOverdue(...)`(`DefaultAlertEventService.java:69`)、
    `AlertEscalationProperties`;
  - console:`AlertEscalationNotifier`、`AlertEscalationNotifyProperties`、`ALERT_ESCALATED` 领域事件链路(如无其它消费者);
  - **迁移(不裸删)**:`alert_event` 的 `escalation_tier / escalated_at`(V176)、`escalation_notified_tier`(V181)列
    及配套 partial index —— 新 Flyway 迁移分两步(先停写标废弃、后 drop),遵循本仓「加约束双守护 / squawk NOT VALID」惯例。

**保留(不退役)**:`alert_event` 表主体(V19)、`emit(...)` 全链路、`ConsoleAlertController` ack/silence/close、
`AlertmanagerNotifyController/Service`(#777,升格为主力出口)、`notification_delivery_log`(#775)、
`alert_routing_config`(V43,见下)。

### 6.4 `alert_routing_config` 的归宿

- **不废弃**。新增**离线/半自动生成器**(建议 `scripts/ops/gen-alertmanager-config.*`):
  读 `alert_routing_config`(`route_code/team/alert_group/severity/receiver/group_by/*_seconds`)
  → 渲染 `alertmanager.yml` route 树 + receiver 列表(receiver webhook 指向 `am-notify/{receiver}`)
  → `amtool check-config` → 挂载 + `POST {AM}/-/reload`。
  生成时**校验每个 receiver 有对应 `notification_channel`**,防 `am.notify.skipped` 静默蒸发。
- 前端现有 CRUD(`ConsoleAlertRoutingController`)从此有了运行时意义:改配置 → 重新生成 → reload AM。
- v1 手动触发生成(不做实时 watch)。

---

## 7. 验证方案(本地全链 smoke + sim/e2e)

**未上线 = 没有真实流量可对账,验证全部在本地/CI 完成;上线前另有一小节 checklist(§7.3)。**

### 7.1 本地 docker AM 全链 smoke(observability overlay,AM v0.28.1,`docker/compose/observability.yml:27`)

一条端到端断言链(建议做成脚本,风格对齐 `sim-harness.sh` 系列):
1. 起全栈 + observability overlay;route 由生成器从 `alert_routing_config` 种子数据渲染,`amtool check-config` 绿;
2. 触发一条业务告警(打 `POST /internal/alerts`,`AlertInternalController.java:25`,带 tenant/alert_type/severity);
3. 断言 `alert_event` 落库(UPSERT 去重);
4. 断言 AM `GET /api/v2/alerts` 出现该 alert 且 labels 正确(alertname/tenant/severity 词形/alert_group);
5. 断言 `amtool config routes test` 命中预期 receiver;
6. 等 `group_wait` 后断言 console 收到 `am-notify` 回调、`notification_delivery_log` 落
   `eventType=ALERTMANAGER` 的 SUCCESS 行、mockserver(模拟下游 webhook)收到渲染后的通知;
7. **租户分流**:两个租户各发一条,断言各自落到对应租户的 channel(验证 am-notify tenant label 反查);
8. **silence 桥接**:console silence → 断言 AM `GET /api/v2/silences` 出现等价 matcher、repeat 周期内不再收到通知;
   console close → 断言 AM 侧 resolved;
9. **inhibit**:同 alert_group 注入 critical + warning,断言 warning 被压;
10. **回滚开关**:`am-emit.enabled=false` 后 emit 只落库不打 AM(断言 AM 无新 alert)。

### 7.2 单测 / IT / sim-e2e 断言

- 单测:label 映射(severity 词形、alert_group 推导)、事务 afterCommit 时序、失败隔离(AM 500/超时不冒泡)、
  `PostableAlert` 序列化契约(对齐 AM openapi v2);
- IT:am-notify 租户反查(tenant label→channel,含缺渠道→SKIPPED+计数)、silence 桥接幂等;
- sim/e2e:把 §7.1 的 smoke 收编进 sim 家族(注意本仓 sim 残留治理惯例:AM 容器/告警种子要进 00-clean/98-quiesce 收尾);
- 契约防漂移:route 生成器输出 snapshot 测试(给定表数据 → 固定 yml 输出)。

### 7.3 上线前生产 checklist(上线时再做,不阻塞本迁移)

- 真实下游送达(真钉钉/企微/短信 provider、真 webhook 端点)实测一轮;
- AM HA(≥2 副本 gossip cluster,`--cluster.peer`)或明确接受单实例 SPOF(与本仓「单实例试生产接受」惯例一致);
- 告警风暴演练(批量注入,验 AM 分组/抑制不漏 CRITICAL、`am-notify` 端点限流);
- bearer token(`alertmanager-batch-template.yml:41` 占位符)纳入密钥管理与轮换;
- `am.notify.skipped` / `batch.alert.am_emit.failed` 元告警接到值班渠道。

### 附录:代码侧最小改动清单(供 PR-1,本任务不实现)

1. **orchestrator**:`AlertmanagerEmitPublisher` + `AlertmanagerEmitProperties`(§6.1/6.2),挂
   `DefaultAlertEventService.emit(...)` 事务提交后;label 映射表(alert_type→alert_group/team、severity 词形);OPEN 告警 re-emitter。
2. **am-notify 租户反查(硬前置)**:`AlertmanagerNotifyService.deliver(...)` 改为**优先取 payload
   `commonLabels.tenant` 反查该租户渠道**,`AlertmanagerNotifyProperties.tenantId` 降级为缺 label 时的 fallback
   (`AlertmanagerNotifyProperties.java:27`)。
3. **silence/close 桥接**:`ConsoleAlertApplicationService` 的 silence/close 路径追加 AM `POST /api/v2/silences` /
   resolved 推送(失败隔离,不影响 console 主流程)。
4. **route 生成器**:§6.4 脚本。
5. **自研链路两个开关默认值翻 false**(PR-1)→ 删净(PR-2)。

---

## 8. 风险清单

1. **AM 单点(SPOF)**:单实例 AM 挂 → emit 直连失败(已失败隔离,告警仍落 `alert_event`,console 可见,但**无人被通知**)。
   未上线阶段可接受;上线前按 §7.3 决定 HA 或接受。缓解:`am_emit.failed` 计数 + 元告警;PR-2 删净前可翻回自研开关。
2. **退役后的回滚窗口**:PR-2 删净自研链路后,回滚只剩「修 AM」一条路。缓解:PR-1→PR-2 之间留至少一轮完整 sim/e2e +
   本地 smoke 全绿;PR-2 不急合。
3. **tenant label 基数**:`tenant` 进 `group_by` 尚可(租户数有限),但 `resource_key/trace_id/instance` 若误入
   label/group_by → AM 分组爆炸、内存膨胀。缓解:§4 基数守则,高基数一律进 annotation;snapshot 测试锁住 label 集合。
4. **静默语义差(console silence vs AM silence)**:单向桥接,AM UI silence 不回写 console。缓解:约定 silence 统一从
   console 操作,AM UI 只读;文档明示。
5. **route 无匹配 / channel 未预建**:AM 路由到某 receiver 但 `notification_channel` 没建同名渠道 →
   `am.notify.skipped` 静默蒸发(`AlertmanagerNotifyService.java:60` 已警示)。缓解:生成器校验 receiver↔channel 对齐(§6.4);
   `am.notify.skipped` 元告警;smoke 步骤 6 兜底。
6. **am-notify 端点安全 / 限流**:permitAll + bearer 自校验,未纳入 console 限流链 → 告警风暴可打爆该端点。
   缓解:把 `/internal/am-notify/**` 挂到 `ConsoleRateLimitFilter`(#779–781);bearer token 纳入密钥轮换(§7.3)。
7. **firing/resolved 语义**:漏 re-emit → AM 误判 resolved 提前停通知;不发 resolved → CLOSED 告警多响 `resolve_timeout`(5m)。
   缓解:`resend-interval < resolve_timeout`;close 桥接发 endsAt(§6.1)。
8. **事务边界**:AM push 若在 emit 事务内且未 afterCommit → 回滚后 AM 已收(幽灵告警)。缓解:§6.1 强制事务提交后异步推,单测锁时序。
9. **升级阶梯语义的替代确认**:自研 tier 阶梯(30/60/90min 逐级放大)退役后,AM 的等价物是 `repeat_interval` +
   critical 路由到 pager;**没有「越久没 ack 越吵」的逐级抬升**。若确需阶梯语义,后续可用 route 嵌套 + 多 receiver 近似;
   v1 接受 repeat_interval 语义(未上线,无既有运维习惯要兼容)。

---

## 9. 分期与工作量(direct cutover,一期完成)

| 阶段 | 内容 | 工作量(人天) | 验收标准 |
|---|---|---|---|
| **PR-1 迁移**(一期主体) | emit 直连 publisher + 开关 + label 映射 + re-emitter(1.5–2);am-notify 租户反查(0.5–1);silence/close 桥接(0.5–1);route 生成器 + amtool 校验(1);本地全链 smoke 脚本 + 单测/IT/sim 断言(1–1.5);自研两开关默认翻 false(0.1) | **4.5–6.5** | §7.1 smoke 全 10 步绿;§7.2 单测/IT/sim 绿;`mvn clean verify` 绿;回滚开关演练过 |
| **PR-2 退役**(紧随) | 删 Scheduler/Notifier/属性/事件链路;V176/V181 列两步废弃迁移;文档更新 | **1–1.5** | §6.3 清单删净;squawk/守护测试绿;full-ci-gate 绿 |
| (上线时)生产 checklist | §7.3:真下游/HA 决策/风暴演练/密钥/元告警 | 上线项目内计,约 1–2 | checklist 逐项勾 |

**核心迁移+退役合计约 5.5–8 人天,无日历等待期**(此前影子期方案是准备 6–9 人天 + 影子/切流数周日历;
缩水来源 = 删掉双发对账工具、灰度 allowlist、影子期与切流分档观察)。

---

## 10. AM 部署形态

- **现状**:单实例 `prom/alertmanager:v0.28.1`(`docker/compose/observability.yml:27`),配置文件挂载
  `alertmanager-batch-template.yml`,宿主端口 `19093→9093`。Prometheus 已指向 `alertmanager:9093`(`prometheus.yml:13`)。
- **配置管理**:文件驱动 + `amtool check-config` 校验 + `POST /-/reload` 热加载;route 由 §6.4 生成器产出。
  模板里 `REPLACE_WITH_AM_NOTIFY_BEARER_TOKEN`(`alertmanager-batch-template.yml:41`)部署时渲染真实 token
  (AM 不做 env 替换)。
- **HA(生产,上线时决策)**:AM 官方 gossip cluster(≥2 副本,`--cluster.peer`),去重与 silence 跨副本一致;
  k8s 生产可走 kube-prometheus-stack 的 Alertmanager CRD(影响生成器输出形态:yml vs `AlertmanagerConfig` CRD,列入待核实)。
  **本迁移 v1 单实例。**

---

## 11. 待核实 / 开放问题

- `alert_type → alert_group/team` 的权威映射来源:新建映射配置,还是收敛到 `alert_routing_config`(可能需加 `alert_type` 维度)——待设计。
- EMAIL/IM sender 成熟度:`NotificationSenderRegistry` 已有 slack/钉钉/企微/短信 sender 类,各自是否可直接承接 AM 出口流量需核实。
- k8s 生产是否走 kube-prometheus-stack Operator(影响 §6.4 生成器输出形态)。
- 升级阶梯语义是否需要在 AM 侧用嵌套 route 近似(§8.9,v1 接受 repeat_interval 语义)。

---

## Changelog

- 2026-07(v2,本版):按用户裁定「系统未上线,不需要影子期」改写为 **direct cutover** 方案 —— 删除影子期/双发对账/
  灰度 allowlist/切流分档;验证收敛为本地 docker 全链 smoke + sim/e2e;新增上线前生产 checklist(§7.3);
  自研升级链路改为「PR-1 同步置关(留回滚)+ PR-2 紧随删净」;工作量从「6–9 人天 + 数周日历」缩至「5.5–8 人天,无日历等待」。
  保留:am-notify 租户反查硬前置、`alert_routing_config`=route 生成器输入的裁定、console silence 单向桥接(升格为 PR-1 内必做)。
- 2026-07(v1):emit 旁路双发 + 影子期灰度 + 切流退役方案(已被 v2 取代)。
