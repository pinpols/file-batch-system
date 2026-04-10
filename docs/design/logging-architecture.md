# 日志体系架构

> 本文档描述 batch-platform 系统的完整日志体系，包括后端应用日志、业务审计日志、前端埋点日志的采集、存储与观测方案。

---

## 1. 整体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                          前端（Vue）                              │
│                                                                  │
│  logger.ts（环形缓冲区 500 条）                                    │
│    ├─→ localStorage（本地快照，用户自助导出排障）                    │
│    └─→ POST /api/console/telemetry/events（批量上报到后端）         │
└──────────────────────────┬───────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────────────┐
│                     后端（Spring Boot）                           │
│                                                                  │
│  console-api / orchestrator / trigger / worker-*                 │
│    ├─→ slf4j + MDC → 应用日志文件（/var/log/batch/*.log）          │
│    └─→ 业务审计表（PostgreSQL batch schema）                      │
└──────────────────────────┬───────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────────────┐
│                      日志管道（Loki 方案）                         │
│                                                                  │
│  Promtail → Loki → Grafana                                       │
│  （按 service / tenantId / traceId / level 等标签查询）            │
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. 后端应用日志

### 2.1 技术栈

- **日志框架**：SLF4J + Logback（Spring Boot 默认）
- **日志注解**：Lombok `@Slf4j`
- **结构化上下文**：MDC（Mapped Diagnostic Context）

### 2.2 日志格式

由 `batch-defaults.yml` 统一配置，支持两种格式：

| 环境 | `BATCH_LOG_FORMAT` | 输出格式 |
|------|-------------------|---------|
| 本地开发 | 留空（默认） | 纯文本 console pattern |
| 生产 / K8s | `ecs` | JSON（ECS 格式），Loki / Elasticsearch 直接按字段索引 |

Console pattern：

```
%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] [%X{service:-} %X{tenantId:-} %X{traceId:-} %X{requestId:-} %X{jobInstanceId:-} %X{fileId:-}] %logger{40} - %msg%n
```

### 2.3 MDC 统一字段

| 字段 | 注入来源 | 用途 |
|------|---------|------|
| `service` | 各模块启动时设置 | 区分控制面和各 worker |
| `tenantId` | HTTP Filter / Kafka Consumer | 租户隔离 |
| `traceId` | HTTP Filter / Kafka Consumer | 链路追踪 |
| `requestId` | HTTP Filter | 请求关联 |
| `jobInstanceId` | 调度循环 / Worker 消费 | 作业实例关联 |
| `fileId` | 文件处理链路 | 文件实体关联 |

### 2.4 MDC 注入点

| 模块 | 注入点 | 注入字段 |
|------|--------|---------|
| console-api | `ConsoleRequestContextFilter` | service, tenantId, traceId, requestId |
| orchestrator / trigger | `HttpRequestMdcFilter` | service, tenantId, traceId, requestId |
| orchestrator 内部调度 | 各 Scheduler 循环体 | tenantId, traceId, jobInstanceId |
| worker | `AbstractTaskConsumer` | tenantId, traceId, taskId, jobInstanceId, workerId, workerType, runMode |

### 2.5 日志级别约定

| 级别 | 场景 |
|------|------|
| `ERROR` | 系统异常、不可恢复错误 |
| `WARN` | 业务异常、可恢复告警 |
| `INFO` | 关键业务事件、状态变更 |
| `DEBUG` | 调试信息（生产环境关闭） |

### 2.6 存储方案

**应用运行日志不存数据库**，走文件 + Loki 管道：

```
应用 logback → 日志文件
    ↓
Promtail（regex 解析 MDC 字段为 label）
    ↓
Loki（按标签查询，不做全文索引）
    ↓
Grafana（可视化看板）
```

不存数据库的原因：
- 应用日志量大，写 DB 会成为瓶颈
- 日志查询模式是全文检索 + 时间范围过滤，关系型 DB 不适合
- DB 故障会反过来阻塞日志写入，拖垮应用

### 2.7 OTLP 链路追踪

除 Loki 外，同时通过 OTLP 协议导出 Trace 和 Logs 到 OTel Collector：

```yaml
management:
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/traces
    logging:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/logs
```

---

## 3. 业务审计日志

业务审计日志存数据库表，用于合规审计和运维排查，与应用运行日志分离。

### 3.1 审计表清单

| 表 | 用途 | 写入时机 |
|----|------|---------|
| `job_execution_log` | 作业执行记录 | 作业实例状态变更 |
| `file_audit_log` | 文件操作审计（上传、下载、删除） | 文件操作完成 |
| `config_change_log` | 配置变更记录（发布、灰度、回滚、轮换） | 配置操作完成 |
| `console_ai_audit_log` | AI 对话审计 | AI 对话请求/响应 |
| `alert_event` | 告警事件（SLA 超时、分区失败等） | 告警触发 |
| `outbox_event` | Outbox 消息投递记录 | 事务提交 |
| `event_delivery_log` | Outbox 重试投递日志 | 投递成功/失败 |
| `webhook_delivery_log` | Webhook 投递记录 | Webhook 回调完成 |
| `notification_delivery_log` | 通知投递记录 | 通知发送完成 |
| `config_sync_log` | 配置同步日志（跨环境导入/导出） | 同步操作完成 |

### 3.2 归档策略

`archive_policy` 表定义按表维度的归档规则，支持对 `job_instance`、`workflow_run`、`file_record`、`audit_log`、`outbox_event` 等表按保留天数自动清理。

---

## 4. 前端埋点日志

### 4.1 采集层（前端）

前端使用自研轻量级操作日志系统（`src/utils/logger.ts`），核心设计：

- **环形缓冲区**：内存中最多保留 500 条日志
- **定时落盘**：每 10 秒写入 localStorage，页面关闭前也会写一次
- **容错**：localStorage 写满时自动缩容，数据损坏时静默重置

### 4.2 日志分类

| 类别 | 采集点 | 说明 |
|------|--------|------|
| `route` | Vue Router afterEach 钩子 | 页面切换（页面名、来源、目标） |
| `click` | `v-track-click` 自定义指令 | 按钮/操作点击，声明式埋点 |
| `api` | Axios 响应拦截器 | API 请求结果（method、url、status） |
| `error` | 全局错误处理（Vue + window） | JS 异常、unhandledrejection |

### 4.3 上报通道（方案 A：slf4j 直出）

前端批量上报到后端，后端通过 slf4j 打到应用日志，复用 Promtail → Loki 管道：

```
前端 logger.ts
    ├─→ localStorage（本地快照）
    └─→ POST /api/console/telemetry/events（批量上报）
              ↓
         ConsoleTelemetryController
              ↓ slf4j log.info/warn/error + MDC
         应用日志文件（console.log）
              ↓
         Promtail → Loki → Grafana
```

### 4.4 上报策略

| 类别 | 策略 | 原因 |
|------|------|------|
| `error` | 立即上报 | 最有价值，量小 |
| `api`（失败） | 立即上报 | 接口报错需前后端串联排查 |
| `api`（成功） | 批量上报或采样 | 量大，可降级 |
| `click` | 批量上报（攒 20 条或 30 秒） | 行为分析，不急 |
| `route` | 批量上报 | 同上 |

### 4.5 上报端点

```
POST /api/console/telemetry/events
Content-Type: application/json
Authorization: Bearer <jwt>

{
  "app": "batch-console",
  "userId": "admin",
  "sessionId": "sess-abc123",
  "events": [
    {
      "type": "error",
      "name": "TypeError: Cannot read property 'id' of undefined",
      "ts": "2026-04-10T12:00:00.000Z",
      "page": "/jobs",
      "props": { "stack": "at JobList.vue:42", "componentName": "JobList" }
    },
    {
      "type": "click",
      "name": "触发 Job",
      "ts": "2026-04-10T12:00:01.000Z",
      "page": "/jobs",
      "props": { "jobCode": "daily-settlement" }
    }
  ]
}
```

- 外层 `app`、`userId`、`sessionId` 提供会话上下文，不需要每条 event 重复
- 每批最多 50 条 event
- `type`：事件类别（route / click / api / error）
- `name`：事件名称或描述
- `ts`：ISO 8601 时间字符串
- `props`：任意 key-value 对象，后端序列化为 JSON 字符串记录
- 需要 JWT 认证
- 后端通过 MDC 注入 `frontendApp`、`frontendUserId`、`frontendEventType`、`frontendPage`，便于 Loki 按标签过滤
- `error` 类型以 ERROR 级别记录，其余以 INFO 级别记录

### 4.6 选择方案 A 的原因

| 对比项 | 方案 A（slf4j 直出） | 方案 B（走 Kafka） |
|--------|---------------------|-------------------|
| 额外依赖 | 无 | 新增 Kafka topic |
| 实现复杂度 | 一个 Controller | Controller + Producer + Consumer |
| 适用量级 | 内部控制台（百级用户） | 千级以上用户 |
| 观测管道 | 复用现有 Promtail → Loki | 需独立 Consumer 写 Loki/ClickHouse |

当前是内部控制台场景，方案 A 零额外依赖、完全复用现有日志管道，够用。未来用户量增长后，只需将 Controller 中的 `log.info()` 替换为 `kafkaTemplate.send()` 即可平滑切换到方案 B。

---

## 5. Grafana 观测看板建议

| 看板 | 数据源 | 核心面板 |
|------|--------|---------|
| 后端错误总览 | Loki | ERROR 日志按 service 分布、按时间趋势 |
| 请求链路追踪 | Loki + Tempo | 按 traceId 查看完整调用链 |
| 前端错误监控 | Loki | `{frontendCategory="error"}` 按页面/时间聚合 |
| 前端用户行为 | Loki | `{frontendCategory="click"}` 按 action 统计 |
| 租户维度日志 | Loki | `{tenantId="xxx"}` 按租户过滤所有前后端日志 |
| 业务审计查询 | PostgreSQL | config_change_log、file_audit_log 等表的 Grafana 直连 |

---

## 6. Loki LogQL 常用查询示例

```logql
# 后端 ERROR 日志
{service="batch-orchestrator"} |= "ERROR"

# 按租户查日志
{tenantId="default-tenant"}

# 前端错误日志
{frontendCategory="error"}

# 前端某页面的点击行为
{frontendCategory="click", frontendPage="/jobs"}

# 按 traceId 追踪完整链路
{traceId="abc123"}
```
