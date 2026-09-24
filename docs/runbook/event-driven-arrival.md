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

- 收到通知 → **即时触发一次 `ImportIngressScanner.scan()`**,对象当下就被发现登记,无需等下一个轮询 tick。
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

- `batch.import.event_arrival.notifications` — 收到的通知数(含被合并/关闭时的)。
- `batch.import.event_arrival.scans` — 实际触发的即时扫描数。

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
- 与 Phase 4.4(`TriggerType.EVENT`)关系:EVENT launch 侧现状已支持(`/api/triggers/launch` 接受 `triggerType=EVENT`);本特性补的是「到达 → 发现」的事件驱动 producer,二者正交叠加。
