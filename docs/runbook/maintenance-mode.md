# 维护 / 降级模式 SOP

> 当前基线：维护状态的后端拦截、公共状态接口、管理员热更新、V215 多副本共享状态、版本 CAS、失联写保护和基础指标已实现；统一写按钮冻结、503 即时跳转和 staging 双实例联测仍是完善项。实施方案见 [Console 维护与服务降级完善方案](../plans/maintenance-degradation-hardening-plan-2026-09-29.md)。

> 适用场景:DB 灰度切换 / 上线滚动期 / 紧急回滚 / 数据修复等需要冻结所有(或仅写)操作的窗口。

## 1. 工作模式

| 模式 | enabled | readOnly | 行为 |
|---|---|---|---|
| 关闭 | `false` | — | 正常 |
| 全冻结 | `true` | `false` | 除白名单外整站返 `503`,前端跳 `/maintenance` 降级页 |
| 只读 | `true` | `true` | `GET` 通过(响应带 `X-Maintenance: read-only` header),`POST/PUT/PATCH/DELETE` 返 `503`,前端禁用写按钮 + 顶部红条 |

## 2. 白名单路径(维护期始终放行)

- `/actuator/**`(健康检查 / 监控)
- `/api/console/auth/check`(登录态探活,nginx auth_request 用)
- `/api/console/auth/logout`(允许用户登出)
- `/api/console/system/maintenance`(状态接口,前端轮询恢复)

`MaintenanceModeFilter` 用 `AntPathMatcher` 匹配上述模式。

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
  - 顶部全局 banner(`message` + ETA 倒计时)
  - 维护页状态
- 统一接入全部业务写按钮、503 后即时跳转和安全回跳，属于 [完善方案](../plans/maintenance-degradation-hardening-plan-2026-09-29.md) 的 P0，不把当前局部页面行为误记为已完成。
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
