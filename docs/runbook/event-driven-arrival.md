# 事件驱动到达(Event-Driven Arrival)

> 路线图 Phase 4.1 v1。把 import 到达发现延迟从「最长一个轮询周期(默认 30s)」降到接近实时,**不绑定云厂商、不绕过 Trigger/Orchestrator**。

## 背景

现状 import 到达是纯轮询:`ImportIngressScanner` 每 30s(`batch.worker.import.scanner.poll-interval-millis`)扫一遍配置前缀,发现新对象就「安全发现 + 登记」到 `file_record`,后续由 orchestrator 到达分组/触发机器推进。最坏情况一个对象落地后要等近 30s 才被发现——准实时结算链路嫌慢。

## 机制(v1:事件驱动「提前扫」)

新增一个 **默认关** 的内部 inbound 端点:

```
POST /internal/import/events/object-arrival
Content-Type: application/json

{ "tenantId": "t1", "bucket": "ingress", "objectKey": "ingress/import-20260620-orders.csv" }
```

- 收到通知 → **即时触发一次 `ImportIngressScanner.scan()`**,对象当下就被发现登记,无需等下一个轮询 tick。
- 通知体字段**全部可选**,仅用于日志/可观测定位;实际发现仍交既有扫描器按配置前缀全量扫(语义与轮询完全一致,只是「提前」)。
- **在途守护**:`AtomicBoolean` 让密集通知合并为「至多一次在途扫描」,事件风暴下不会把扫描器打爆。
- 扫描器只做「发现 + 登记」,**不直接起任务**——起任务仍走 Trigger/Orchestrator 主链,事件驱动不改这条边界。

> 轮询调度器(`scheduledScan`,ShedLock `import_ingress_scan`)保持开启作为兜底:事件丢失/事件源故障时,30s 轮询仍会补上发现。事件驱动是**加速层**,不是替代。

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

## 指标

- `batch.import.event_arrival.notifications` — 收到的通知数(含被合并/关闭时的)。
- `batch.import.event_arrival.scans` — 实际触发的即时扫描数。

## 边界 / 后续

- **v1 不做**:通知体里 object-key → tenant/job/bizDate 的精确路由、按 key 定向扫描(单对象)、inbound 鉴权(当前依赖内部网络/网关,同其它 `/internal/*`)。这些是 v2 增强项。
- 与 Phase 4.4(`TriggerType.EVENT`)关系:EVENT launch 侧现状已支持(`/api/triggers/launch` 接受 `triggerType=EVENT`);本特性补的是「到达 → 发现」的事件驱动 producer,二者正交叠加。
