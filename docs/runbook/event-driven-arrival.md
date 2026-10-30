# 事件驱动到达(Event-Driven Arrival)

> 路线图 Phase 4.1 v1。把 import 到达发现延迟从「最长一个轮询周期(默认 30s)」降到接近实时,**不绑定云厂商、不绕过 Trigger/Orchestrator**。

## 当前状态

import 到达发现默认仍保留轮询兜底:`ImportIngressScanner` 每 30s(`batch.worker.import.scanner.poll-interval-millis`)扫一遍配置前缀,发现新对象就「安全发现 + 登记」到 `file_record`,后续由 orchestrator 到达分组/触发机器推进。

事件驱动到达已经作为 **v1 加速层** 落地,但默认关闭。开启 `batch.worker.import.scanner.event-arrival.enabled=true` 后,对象存储或上游网关可以在对象落地时通知 import worker,把发现延迟从「最长一个轮询周期」降到接近实时。

事件驱动不是替代轮询:事件丢失、事件源故障、Webhook 重试耗尽时,轮询仍会补上发现。

## 机制(v1:事件驱动「提前扫」)

新增一个 **默认关** 的内部 inbound 端点:

```
POST /internal/import/events/object-arrival
Content-Type: application/json

{ "tenantId": "t1", "bucket": "ingress", "objectKey": "ingress/import-20260620-orders.csv" }
```

- 请求必须携带平台内部共享密钥:

```
X-Internal-Secret: <BATCH_INTERNAL_SECRET>
```

- 两个扫描开关都开启时，收到通知会调用 `ImportIngressScanner.scan()`；对象是否被发现和登记取决于本次扫描结果。
- 通知体字段**全部可选**,仅用于日志/可观测定位;实际发现仍交既有扫描器按配置前缀全量扫(语义与轮询完全一致,只是「提前」)。
- **在途守护**:`AtomicBoolean` 让密集通知合并为「至多一次在途扫描」,事件风暴下不会压垮扫描器。
- 扫描器只做「发现 + 登记」,**不直接起任务**——起任务仍走 Trigger/Orchestrator 主链,事件驱动不改这条边界。

> 轮询调度器(`scheduledScan`,ShedLock `import_ingress_scan`)保持开启作为回退:事件丢失/事件源故障时,30s 轮询仍会补上发现。事件驱动是**加速层**,不是替代。

## 事件源(不绑定云厂商)

任何「对象落地能发 HTTP 通知」的来源都可对接:

- **AWS S3** → EventBridge / SNS → HTTP 订阅
- **MinIO** → bucket notification(webhook)
- **阿里云 OSS / 腾讯云 COS** → 事件通知 → 函数/网关转发
- **自建 poller / 上游推送网关** → 直接 POST

映射「对象 → 该扫哪个 worker/前缀」由部署侧路由(通知发到对应 import worker 实例);v1 不在通知体里做 tenant/job 解析(发现仍按 worker 配置前缀)。

## 开关

| 键 | 默认 | 说明 |
|---|---|---|
| `batch.worker.import.scanner.event-arrival.enabled` | `false` | 开启事件驱动端点。关闭时端点存在但只回 `triggered=false`,行为等价历史纯轮询 |
| `batch.worker.import.scanner.enabled` | `true` | 扫描总开关。关闭时通知返回 `triggered=false`、`reason=scanner-disabled`，不调用扫描器 |

对应环境变量:

```bash
BATCH_WORKER_IMPORT_SCANNER_EVENT_ARRIVAL_ENABLED=true
```

Compose / Helm 均已显式透传该变量；Helm values 入口为:

```yaml
orchestrator:
  importScanner:
    eventArrivalEnabled: true
```

## 指标

- `batch.import.event_arrival.notifications` — 事件开关开启时收到的通知数，包含扫描总开关关闭及在途合并的通知。
- `batch.import.event_arrival.scans` — Controller 调用即时扫描器的次数,不代表扫描实际执行或对象成功登记。

当前仅有上述两个计数器,尚无即时/轮询扫描的成功与失败、最近成功时间、登记结果或对象到达至 `file_record` 的延迟指标,也没有对应的 Prometheus 告警。通知撞上在途扫描时会合并并返回 `triggered=false`；扫描器先取对象列表快照,若通知对应对象在快照形成后才落地,本轮可能看不到它,依赖下一轮轮询兜底,当前没有合并数量或事件到登记延迟指标。剩余指标和告警验收跟踪于 [TODO Master OBS-ENV-05](../plans/todo-master.md#事件与可用性监控目标环境验收2026-10-26)。

## 本地验证

1. 启动 import worker 时打开开关:

```bash
BATCH_WORKER_IMPORT_SCANNER_EVENT_ARRIVAL_ENABLED=true ./scripts/docker/up-apps.sh worker-import
```

2. 上传数据文件或 sidecar manifest 到 scanner 前缀后,手工模拟对象存储通知:

```bash
curl -fsS -X POST 'http://localhost:18083/internal/import/events/object-arrival' \
  -H "Content-Type: application/json" \
  -H "X-Internal-Secret: ${BATCH_INTERNAL_SECRET}" \
  -d '{"tenantId":"t1","bucket":"batch-dev","objectKey":"ingress/import-20260620-orders.csv"}'
```

3. 预期返回:

```json
{"code":"SUCCESS","data":{"triggered":true}}
```

4. 验证 `file_record` 已登记,并观察指标 `batch.import.event_arrival.notifications` /
`batch.import.event_arrival.scans` 增长。

## MinIO webhook 接入示例

本地 MinIO 可通过 bucket notification 调用一个内部网关或轻量转发器,由转发器补
`X-Internal-Secret` 后转发到 import worker。不要把 `BATCH_INTERNAL_SECRET` 直接暴露给外部对象存储控制面。

示例流程:

```bash
mc alias set batch-local http://localhost:19000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

# Webhook endpoint 建议指向内部转发器,由转发器补 X-Internal-Secret。
mc admin config set batch-local notify_webhook:import_arrival \
  endpoint="http://<internal-gateway>/internal/minio/import-arrival" \
  queue_limit="10000"

mc admin service restart batch-local

mc event add batch-local/batch-dev arn:minio:sqs::import_arrival:webhook \
  --event put --prefix ingress/
```

转发器只需要把 MinIO 事件中的 bucket/object key 转成平台通知体,再调用
`/internal/import/events/object-arrival`。如果没有转发器,也可以让上游推送网关直接按本文接口调用。

## 边界 / 后续

- **v1 不做**:通知体里 object-key → tenant/job/bizDate 的精确路由、按 key 定向扫描(单对象)。这些是 v2 增强项。
- **v1 已做**:内部共享密钥鉴权。生产开启事件到达时必须配置非默认 `BATCH_INTERNAL_SECRET`,并只允许对象存储事件源或内部转发器访问 import worker 的 `/internal/**`。
- 如果事件到达 QPS 很高,当前 in-flight 合并会把并发通知折叠为一次全量扫描；扫描期间新到通知不会排队补扫,最终由下一轮轮询兜底。需要严格低延迟时再做 v2 的 keyed scan / coalescing queue。
- 与 Phase 4.4(`TriggerType.EVENT`)关系：显式投递到单个 Workflow 时仍使用 `POST /api/triggers/launch`，请求需提供 `externalEventCode`、`externalEventSource`、`externalEventId`、`workflowDefinitionId` 和 `triggerType=EVENT`。需要按事件规则自动投递到多个 Workflow 时，使用 `POST /api/triggers/events`，提供 `tenantId`、`externalEventCode`、`externalEventId`、`bizDate`，以及可选的 `params`、`dataIntervalStart`、`dataIntervalEnd`、`dryRun`；不要传 `workflowDefinitionId` 或 `externalEventSource`，目标集合由已启用的事件预期定义解析，来源从 API Key 读取。首个接纳会冻结目标集合和来源 `bizDate`，同一来源事件重投不会随规则变化扩展/缩减目标；同一事件身份带不同 payload 会冲突。两个入口均使用 `Idempotency-Key`，自动路由可通过 `GET /api/triggers/events/status?tenantId=...` 携带同一幂等键查询聚合状态。每个目标返回 TriggerRequest 状态与其 `workflowRunId/workflowRunStatus`；根 Workflow 完成状态不能从 TriggerRequest 的旧 JobInstance 关联字段推断。

### Trigger 外部事件来源凭据

外部系统调用 `POST /api/triggers/launch` 或 `POST /api/triggers/events` 时，必须同时设置 `X-Batch-Api-Key` 与 `X-Batch-Tenant-Id`。API Key 的 `key_name` 是事件来源；单目标 `/launch` 请求体中的 `externalEventSource` 必须与 `key_name` 完全一致，且 `triggerType=EVENT`。多目标 `/events` 不接受请求体指定来源或目标，服务端使用凭据来源并解析启用的事件预期目标。两种入口的 API Key 都必须显式包含 `trigger.event` scope，通配 scope `*` 不授予 EVENT 来源权限，`tenantId` 也必须与 API Key 所属租户一致。不匹配时返回 403，且不得写入 TriggerRequest、Outbox 或事件投递记录。

同一凭据只可调用 EVENT 启动及来源隔离的状态查询：单目标使用 `GET /api/triggers/launch/status`，多目标使用 `GET /api/triggers/events/status`。状态查询以 API Key 绑定的来源和租户过滤，避免该来源读取其他来源的请求。其他 Trigger 管理操作仍使用平台内部 `X-Internal-Secret`。API Key 验证成功结果进程内缓存 1 分钟，因此跨服务吊销的最长生效窗口为 1 分钟；到期时间每次缓存命中仍会重新检查。

租户管理员创建 API Key 时，设置名称为稳定的 source 标识并显式填写 `trigger.event` scope。不要使用默认通配 scope 作为事件来源授权。
