# 维护 / 降级模式 SOP

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

### 3.1 环境变量(推荐 — 不改代码不重启 reactor)

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

Spring Boot `@ConfigurationProperties` 在容器重启后生效(scope = singleton),**这些 env 是启动期 binding,不支持热更**。如需热更,后续可补 `@RefreshScope` + `actuator/refresh`。

### 3.2 docker-compose / Helm

`docker-compose.app.yml` / Helm values 添加同名 env,滚动重启 console-api。

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

- 启动 + 每 30s 调一次 `GET /system/maintenance`,根据 `enabled` 切换:
  - 顶部全局 banner(`message` + ETA 倒计时)
  - 写按钮 disable(`readOnly=true` 时)
- 接到 503 + `maintenance:true` body 时自动跳 `/maintenance` 降级页,该页继续 30s 轮询直到 `enabled=false` 自动返回首页
- 移动端 MAppBar 顶部红条,逻辑共享 `useAppStore` 的 `maintenance` 字段

## 6. 监控

- Grafana 监控 `X-Maintenance` 命中率 / 维护期长度
- nginx access log 过滤 `status=503` + `req-header[X-Maintenance]` 区分维护期 vs 真服务异常
- Prometheus 加 `console_maintenance_active{readOnly="true|false"}` gauge(后续 micrometer registration)

## 7. 回滚

只需把 `BATCH_CONSOLE_MAINTENANCE_ENABLED=false` 再次重启即可。前端会在 30s 内自动检测到 `enabled=false`,banner 消失、写按钮恢复、降级页跳回首页。

## 8. 实施位置

- 后端:`batch-console-api/.../config/ConsoleMaintenanceProperties.java`、`support/maintenance/MaintenanceModeFilter.java`、`web/ConsoleSystemController.java`
- 前端:`stores/app.ts maintenance state`、`composables/useMaintenancePolling.ts`、`components/common/MaintenanceBanner.vue`、`views/error/MaintenancePage.vue`
- 安全链:`ConsoleSecurityConfiguration` 把 `MaintenanceModeFilter` 放在 RateLimit / Auth 之前,且 permitAll `/api/console/system/maintenance`
