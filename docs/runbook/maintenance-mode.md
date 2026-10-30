# 维护 / 降级模式 SOP

> 当前基线：维护状态的后端拦截、公共状态接口、管理员热更新、V215 多副本共享状态、版本 CAS、失联写保护和基础指标已实现；Console 支持计划开始时间，后端以 `active` 字段和请求拦截器按同一共享状态生效。前端对已审计写操作显式禁用，Axios 拦截器和后端过滤器分别作为兜底；全站每个写按钮的视觉覆盖及 Console/Orchestrator 多实例 staging 联测仍需验收。实施方案见 [Console 维护与服务降级完善方案](../plans/maintenance-degradation-hardening-plan-2026-09-29.md)。

> 适用场景:DB 灰度切换 / 上线滚动期 / 紧急回滚 / 数据修复等需要冻结所有(或仅写)操作的窗口。

## 1. 工作模式

| 模式 | enabled | readOnly | 行为 |
|---|---|---|---|
| 关闭 | `false` | — | 正常 |
| 全冻结 | `true` | `false` | 普通请求返 `503` 并跳转 `/maintenance`；管理员仅可读取诊断信息，不能写普通业务数据；维护控制接口仍放行 |
| 只读 | `true` | `true` | `GET` 通过(响应带 `X-Maintenance: read-only` header),`POST/PUT/PATCH/DELETE` 返 `503`,前端禁用写按钮 + 顶部红条 |
| 计划维护 | `true` | 任意 | `startsAt` 到达前仅展示公告和倒计时，业务请求仍按正常模式处理；到时后 `active=true`，按 `readOnly` 转入只读或全冻结 |

`startsAt` 为空表示立即生效。公共状态接口返回服务端计算的 `active`；前端不以本地倒计时代替服务端裁决。计划开始前可取消公告或调整计划；如果提交的开始时间已过，则视为立即生效。

## 2. 白名单路径(维护期始终放行)

- `/actuator/**`(健康检查 / 监控)
- `/internal/am-notify/**`（维护期间继续接收告警，由独立共享令牌鉴权）
- `/api/console/auth/check`(登录态探活,nginx auth_request 用)
- `/api/console/auth/logout`(允许用户登出)
- `/api/console/system/maintenance`(状态接口,前端轮询恢复)

`MaintenanceModeFilter` 用 `AntPathMatcher` 匹配上述模式。

全冻结下，管理员只对非写请求保留诊断旁路（响应头 `X-Maintenance: admin-bypass`）；不能通过 `ROLE_ADMIN` 绕过普通业务写冻结。维护状态查询和管理员维护控制端点单独列入白名单，并仍由 Spring Security 校验权限。

## 3. 开启 / 关闭

### 3.1 环境变量(启动期配置)

```bash
# 全冻结
BATCH_CONSOLE_MAINTENANCE_ENABLED=true \
BATCH_CONSOLE_MAINTENANCE_MESSAGE="DB 主从切换,预计 5 分钟" \
BATCH_CONSOLE_MAINTENANCE_ETA_AT="2026-05-19T18:30:00Z"

# 只读
BATCH_CONSOLE_MAINTENANCE_ENABLED=true \
BATCH_CONSOLE_MAINTENANCE_READ_ONLY=true \
BATCH_CONSOLE_MAINTENANCE_MESSAGE="批处理收尾中,暂停写操作"

# 关闭
BATCH_CONSOLE_MAINTENANCE_ENABLED=false
```

Spring Boot `@ConfigurationProperties` 在容器重启后生效(scope = singleton),**这些 env 是启动期 binding,不支持热更**。变更由 Helm checksum 触发滚动更新；禁止局部添加 `@RefreshScope`，动态需求必须走数据库版本、缓存失效和实例确认链路。

### 3.2 docker-compose / Helm

`deploy/docker/compose/app.yml` / Helm values 添加同名 env,滚动重启 console-api。

### 3.3 不要在代码里改默认值

`application.yml` 的默认必须 `false`,生产开启依赖环境变量,避免误推代码上线。

## 4. 验证

```bash
# 状态(始终 200)
curl -s http://localhost:18080/api/console/system/maintenance | jq .
# {"data":{"enabled":true,"readOnly":false,"message":"…","etaAt":"…"},…}

# 业务接口(开启后 503)
curl -i http://localhost:18080/api/console/queries/instances?tenantId=demo
# HTTP/1.1 503
# X-Maintenance: blocked
# Retry-After: 300
# {"maintenance":true,"readOnly":false,"message":"…","etaAt":"…"}

# 只读模式 GET 通过
curl -i http://localhost:18080/api/console/jobs
# HTTP/1.1 200
# X-Maintenance: read-only
```

## 5. 前端配合

- 当前实现：启动 + 每 30s 调一次 `GET /system/maintenance`,根据 `enabled` 切换:
  - 顶部全局 banner(`message` + 计划开始倒计时 / ETA 恢复提示)
  - 维护页状态
- 计划公告在 `startsAt` 到点后最多约 5 秒由前端轮询更新按钮状态；后端从到点起立即拦截写请求，不依赖前端轮询。
- `useWriteAvailability` / 权限计算负责已接入页面的按钮禁用，Axios 拦截器阻止遗漏页面提交写请求。全站视觉禁用需以实际写 API 到按钮的映射清单验收，不能仅凭请求拦截器宣称所有按钮都已禁用。
- 移动端 MAppBar 顶部红条,逻辑共享 `useAppStore` 的 `maintenance` 字段

## 6. 监控

- 当前可从 access log 观察 `X-Maintenance` 命中情况；已接入 `batch.console.maintenance.enabled`、`read_only`、`shared_state_available`、`version` gauge，以及副本缺失和 replay lag 告警。
- nginx access log 过滤 `status=503` + `req-header[X-Maintenance]` 区分维护期 vs 真服务异常
- 维护 503 计数、维护期过长和下游 fallback 比例仍需在 staging 运行证据中校准阈值；不以本地静态规则代替真实告警触发验证。

## 7. 回滚

启动期环境变量关闭需要滚动重启；管理员热更新关闭可立即生效。前端通过轮询发现 `enabled=false` 后清理公告，具体写按钮恢复和原路由安全回跳以完善方案的 P0 验收为准。

## 8. 实施位置

- 后端:`batch-console-api/.../config/ConsoleMaintenanceProperties.java`、`support/maintenance/MaintenanceModeFilter.java`、`web/ConsoleSystemController.java`
- 前端:`stores/app.ts maintenance state`、`composables/useMaintenancePolling.ts`、`components/common/MaintenanceBanner.vue`、`views/error/MaintenancePage.vue`
- 安全链:`ConsoleSecurityConfiguration` 在认证后、RateLimit 前执行 `MaintenanceModeFilter`，并放行 `/api/console/system/maintenance`

## 9. Orchestrator 实例排空

Console 维护开关只控制 Console API，不会自动冻结全平台服务。计划维护单个 Orchestrator 实例时，先调用内部 drain API：

```text
GET  /internal/orchestrator/drain/status
POST /internal/orchestrator/drain/enable
POST /internal/orchestrator/drain/disable
```

`enable` 先将实例标记为 draining，使后续进入 listener 的 Launch 消息延迟重投，再暂停该实例的 Trigger Launch listener 并将 Readiness 置为拒绝流量；已经进入 launch 业务处理的单条消息允许完成。消息继续遵循 Kafka 手动确认与幂等重投语义。同一 Kafka 工厂中的 OpenLineage listener 不受影响。确认 Pod 已从 Service endpoints 摘除、Launch 消费已暂停后再进行滚动维护；取消维护时调用 `disable` 恢复 listener 和 Readiness。应用进程收到关闭事件时也会进入同一排空流程。

## 10. 维护期通知抑制（2026-10-26）

维护开关不关闭 Prometheus 规则、业务告警生成、事件持久化或恢复判断，也不把原故障改成已恢复。Console 将共享状态发布为 `BatchPlannedMaintenance` 控制信号；Alertmanager 仅对同一 `maintenance_scope`、同一 `job` 的 `BatchServiceDown` 和 `BatchCoreServiceTargetMissing` 抑制通知。控制信号进入空接收端，不作为业务消息发送。数据完整性、SLA、监控自身故障、维护同步失败与维护超时均不在抑制名单。

### 部署前置与范围

- Console 与 Prometheus 必须配置相同、跨环境和工作树唯一的 `BATCH_MAINTENANCE_ALERT_SCOPE`。空范围不启用自动抑制，缺少范围标签的告警继续通知。Docker 范围编码使用字母、数字及 `_.:-`。
- `BATCH_CONSOLE_ALERTMANAGER_SILENCE_ENABLED=true`，且 `BATCH_CONSOLE_ALERTMANAGER_API_BASE_URL` 必须指向实际 Alertmanager。裸 JVM 通常使用宿主映射地址，Compose 使用容器网络地址；两者不要混用。此配置属于重启生效项，不是动态维护状态。
- Helm ServiceMonitor 的服务选择器同时限定 name/component/Release instance; monitor 自身的 metadata 标签不能代替服务选择条件。同命名空间双 Release 不得互相抓取或重打维护范围。
- Docker Prometheus 同时给应用 target 和外部告警添加范围标签，覆盖 `absent()` 目标缺失告警。Helm 从 `alertmanager.console.maintenanceScope` 取范围，空值派生为 `namespace.release`；ServiceMonitor、可用性规则选择器和 Console ConfigMap 使用同一值。站点仍需启用 `silenceEnabled` 并配置 `apiBaseUrl`，加载更新后的 Alertmanager 模板和 Prometheus 规则。
- 公告 `affectedServices` 保留展示用途，并新增有限的通知范围解释：识别 `console-api`、`trigger`、`orchestrator`、`worker-import`、`worker-export`、`worker-process`、`worker-dispatch`、`worker-atomic`，也接受对应 `batch-` 前缀。留空只抑制 Console 可用性通知，不扩大到全平台；未知标记不抑制。该字段不执行服务启停、业务路由或单服务请求冻结。

部署范围标签属于告警身份。首次加载或更换范围时应核对已有 firing/抑制信号；修改范围前先结束旧维护并确认旧控制信号已解除，再切换 Console 与 Prometheus，避免旧标签信号残留到原截止时间。

### 时间、结束与失败

- 公告发布但 `startsAt` 未到时不抑制通知。到点后按共享状态轮询同步；轮询与 HTTP 重试会有短暂传播延迟，计划停机前应核对同步指标和 Alertmanager 中的控制信号，不把保存成功当作已完成摘流或通知抑制。
- 抑制截止采用 `etaAt`；缺省从持久化的 `startsAt` 或 `updatedAt` 加默认 120 分钟计算，复用 `silence.default-duration-minutes`。最大窗口为 24 小时。重试、后台轮询及进程重启不从当前时间续期；需要延长时须显式更新共享维护状态。
- Console 每 5 秒检查状态，正常每 30 秒重发一次控制信号；范围、活动状态或版本变化时立即同步下一轮。提前关闭或缩小范围会发布相同身份的解除信号，多副本重复发布不会制造不同标签身份。
- 控制信号携带固定 RFC3339 `endsAt`。即使 Console 随后停机、不再刷新，到期也会解除抑制；这遵循 [Alertmanager Alerts API](https://prometheus.io/docs/alerting/latest/alerts_api/) 的生命周期。抑制解除不会制造业务恢复回执，仍存在的故障由 Alertmanager 正常通知。
- 未配置地址/范围、共享状态不可确认、未知服务范围或同步请求失败时，`batch_console_maintenance_alert_suppression_available=0`，保留未确认状态；已经成功发送的信号最多保留到原固定截止时间。`BatchConsoleMaintenanceSuppressionUnavailable` 提示维护抑制未确认，`BatchConsoleMaintenanceWindowOverdue` 提示窗口已结束。截止只恢复告警通知，不自动取消业务冻结。

### 维护 HTTP 503

统一观测约定为所有 HTTP 请求提供相同的标签集合：已确认维护拒绝为 `maintenance=planned`，其余为 `maintenance=none`，避免同名 Prometheus 指标因标签结构不同而注册失败。普通 HTTP 错误率的分子、分母与请求量下限均排除此类请求，防止误报或以大量维护请求稀释真实 500。共享维护状态失联产生的 503 不打此标记，真实错误继续告警。不要按整个服务、全部 503 或“维护开启”直接屏蔽 HTTP 错误。

### 验证边界

实现与验证记录维护在[完善方案](../plans/maintenance-degradation-hardening-plan-2026-09-29.md#2026-10-26-维护通知联动补齐)。隔离 Alertmanager 与规则 fixture 证明抑制策略，不替代站点配置加载、跨 Console 副本状态收敛、带任务滚动启停和真实最终收件验收。
