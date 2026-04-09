# 结构化日志管道示例

平台当前的统一 MDC 口径是 `service`、`tenantId`、`traceId`、`requestId`、`jobInstanceId`、`fileId`。控制台、调度、触发和 HTTP 型 worker 已统一到同一类 console pattern，便于 Loki / ELK / OpenSearch 做字段解析和跨服务关联。

## 当前日志格式

仓库中的 console pattern 已包含统一字段：

```text
%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] [%X{service:-} %X{tenantId:-} %X{traceId:-} %X{requestId:-} %X{jobInstanceId:-} %X{fileId:-}] %logger{40} - %msg%n
```

## Loki / Promtail 示例

下面是一个和当前 pattern 对齐的 Promtail 示例，只做字段抽取，不改业务日志内容：

```yaml
scrape_configs:
  - job_name: batch-apps
    static_configs:
      - targets: [localhost]
        labels:
          job: batch-apps
          __path__: /var/log/batch/*.log
    pipeline_stages:
      - regex:
          expression: '^(?P<ts>[^ ]+) (?P<level>[^ ]+) \\[(?P<thread>[^]]+)\\] \\[(?P<service>[^ ]*) (?P<tenantId>[^ ]*) (?P<traceId>[^ ]*) (?P<requestId>[^ ]*) (?P<jobInstanceId>[^ ]*) (?P<fileId>[^ ]*)\\] (?P<logger>[^ ]+) - (?P<msg>.*)$'
      - labels:
          service:
          tenantId:
          traceId:
          requestId:
          jobInstanceId:
          fileId:
          level:
      - output:
          source: msg
```

## 约定

- `service` 用于区分控制面和各 worker。
- `tenantId` 用于租户隔离与排障聚合。
- `traceId` 和 `requestId` 用于链路追踪。
- `jobInstanceId` 和 `fileId` 用于把调度、补偿、文件治理日志关联到同一实体。

---

## 日志租户隔离机制

> 日期：2026-04-09

### 整体方案

所有模块共用 SLF4J MDC + 统一 log pattern，**逻辑隔离**（按字段过滤），不做物理隔离（不按租户分文件/分 topic）。

### MDC 注入点

| 模块 | 注入点 | 注入的 MDC 字段 |
|------|--------|----------------|
| console-api | `ConsoleRequestContextFilter` — 每个 HTTP 请求入口 | `service` `tenantId` `traceId` `requestId` |
| orchestrator / trigger | `HttpRequestMdcFilter` — 通用 Servlet Filter | `service` `tenantId` `traceId` `requestId` |
| orchestrator 内部调度 | `JobSlaScheduler` `PartitionLeaseReclaimScheduler` `WaitingPartitionDispatchScheduler` `DefaultScheduleForwarder` `DefaultRetryGovernanceService` — 每轮循环手动 put | `tenantId` `traceId` `jobInstanceId` |
| worker | `AbstractTaskConsumer` — 每条 Kafka 消息消费前 | `tenantId` `traceId` `taskId` `jobInstanceId` `workerId` `workerType` `runMode` |
| worker pipeline step | `AbstractPipelineStepExecutionAdapter` — 每个 step 执行前 | `tenantId` `traceId` `jobInstanceId` `workerId` `runMode` |

### 隔离粒度

| 维度 | MDC 字段 | 查询示例（Loki LogQL） |
|------|---------|----------------------|
| 租户 | `tenantId` | `{tenantId="tenant-a"}` |
| 请求链路 | `traceId` + `requestId` | `{traceId="1a2b3c"}` |
| 作业实例 | `jobInstanceId` | `{jobInstanceId="12345"}` |
| 文件 | `fileId` | `{fileId="67890"}` |
| 服务 | `service` | `{service="batch-orchestrator"}` |

### 生命周期管理

- **HTTP 请求**：Filter 入口 put → finally `BatchMdc.clear()` 清除全部字段。
- **Kafka 消费**：`AbstractTaskConsumer` 消费前 put → finally 逐个 remove（`tenantId` `traceId` `taskId` `jobInstanceId` `workerId` `workerType` `runMode`）。
- **内部调度循环**：`BatchMdc.withTenantAndTrace()` 自动 save/restore 上下文，循环体处理单个 job 时额外 put `jobInstanceId`，finally remove。

### 日志格式切换

配置项：`BATCH_LOG_FORMAT` 环境变量（`batch-defaults.yml`）。

| 环境 | 值 | 输出格式 |
|------|---|---------|
| 本地开发 | 留空（默认） | 纯文本，console pattern |
| 生产 / K8s | `ecs` | JSON（ECS 格式），Loki / ELK 直接按字段索引 |

### 已知局限

| 局限 | 说明 | 风险等级 |
|------|------|---------|
| 无物理隔离 | 所有租户写同一个日志文件/流，靠字段过滤而非分文件 | 低 — 生产日志系统（Loki/ELK）都支持标签过滤 |
| Kafka 消费线程池共享 | 多租户消息在同一个 consumer group，靠代码保证 MDC 不串 | 低 — 当前每条消息处理前 put、finally remove，链路完整 |
| 异步线程池 MDC 不传播 | `@Async` 或自定义线程池不会自动携带 MDC | 中 — 当前关键路径均为同步或手动注入，但若后续引入异步需加 `TaskDecorator` |
| 无独立的 MDC 传播装饰器 | 缺少通用的 `MdcTaskDecorator` 包装 | 低 — 目前无需，但异步扩展时应优先补充 |
