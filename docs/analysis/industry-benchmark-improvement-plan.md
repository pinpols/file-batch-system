# 行业对标改进计划

> 状态：可执行计划 v1（2026-05-13）。
>
> 基线：本仓库当前 `main` HEAD（27 个 ADR + maturity-assessment L4 + scalability-assessment 中等量级 production-ready）。
>
> 对标对象：Spring Batch / Apache Airflow / Apache DolphinScheduler / XXL-Job / Temporal / Argo Workflows / Dagster。
>
> 编写依据：实际读过本仓内 ADR + maturity-assessment + scalability-assessment + 业务代码后，逐项与上述对手对照得出的差距清单（详见 §对比矩阵）。

## 目录

1. [现状定位](#1-现状定位)
2. [对标矩阵](#2-对标矩阵)
3. [改进项（按 ROI 排序）](#3-改进项按-roi-排序)
4. [显式不做的项](#4-显式不做的项)
5. [实施路线图](#5-实施路线图)
6. [验收标准](#6-验收标准)

---

## 1. 现状定位

**核心定位**：面向**文件批量交付**的**多租户调度平台**——文件传输 / 导入 / 导出 / 派发链 + 工作流编排 + 配置驱动 worker。

业内最近的对标 ≈ **Apache DolphinScheduler + XXL-Job + Spring Batch + Camunda 的交集**，但聚焦"文件 + 多租户"窄场景，专精度更高。

**已经做对的设计基线**（不在本改进计划范围内，仅作背景）：

- DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT 主链（ADR-002）
- Orchestrator 单写者 + Worker CLAIM/lease 模型（ADR-014/016）
- 多租 `tenant_id` 从 model 层就在所有业务表
- 配置驱动 pipeline / template / channel（不写 Java）
- DAG + GATEWAY + 受限 JSONPath 参数 DSL（ADR-009）
- 失败分类（ADR-012）+ 补偿（ADR-006）+ 熔断 + 退避
- 27 个 ADR + maturity L4 + 守护测试 + PMD 0 violation 基线

**已知短板（不在本计划，由 scalability-assessment.md 路线图覆盖）**：

- 分库分表（亿级/天才有 ROI）
- 多 AZ 部署 / federation
- 完整 OTel（已有 7 字段 MDC，但 trace export 还有 gap）

---

## 2. 对标矩阵

| 维度 | Spring Batch | Airflow | DolphinScheduler | XXL-Job | Temporal | Argo | 本系统 | 评价 |
|---|---|---|---|---|---|---|---|---|
| Job repository | ✓ | ✓ | ✓ | ✓ | ✓（event history）| ✓ | ✓ | 持平 |
| 分块（chunk） | ✓ ItemReader | — | — | — | activity 内 | — | pipeline_step + WAP+bookends | **本系统更现代** |
| DAG 编排 | flow + decision | ✓ Python | ✓ | — | code-as-workflow | ✓ YAML | workflow_node + GATEWAY + DSL | 持平或更克制 |
| 跨任务数据传递 | ExecutionContext | XCom 持久化 KV | env vars | — | activity 返回值 | parameter passing | `$.nodes.X.output.*` | **本系统更克制**（静态校验） |
| Sensor（外部条件 wait）| — | ✓ ExternalTaskSensor 等 10+ | partial | — | wait_signal | suspend/resume | ❌ **缺** | **P0 借鉴 Airflow** |
| 失败补偿 / saga | — | on_failure_callback | — | — | activity saga | retry strategy | compensation_command + REQUIRES_NEW | 持平 |
| Restart from failure | restart-from-step | clear + retry | rerun | rerun | event replay | resubmit | RERUN + result_version + replay session | **本系统更强** |
| 路由策略 | local/remote | celery/k8s | 9 种 | 9 种 | task queue | template | DefaultWorkerSelector（单一）| **P1 借鉴 XXL-Job** |
| 弹性扩缩 | — | celery worker autoscale | — | — | worker pool | k8s pod autoscale | helm 静态 replicas | **P1 借鉴 Argo + KEDA** |
| Workflow 版本灰度 | — | DAG paused | — | — | versions | — | workflow_version 字段 | **P2 实现"按租户绑版本"** |
| 数据资产血缘 | — | — | — | — | — | — | Dagster 独有 | **不做**（数据治理 scope，红线） |
| 多语言 worker | JVM-only | Python/Bash | shell/Python/R/Java | Bean only | Go/Java/Python/TS | container | Java only | **不做**（运维复杂度无业务必要） |

---

## 3. 改进项（按 ROI 排序）

### P0-1: Sensor 节点（外部条件等待）

**借鉴对象**：Airflow `ExternalTaskSensor` / `FileSensor` / `HttpSensor` / `SqlSensor`。

**当前缺口**：workflow 只能在 DAG 内等待上游 Job 完成，**等不了外部信号**：
- 等外部文件到达指定目录
- 等外部 API 返回特定状态
- 等 Kafka topic offset 推进到某位置
- 等业务 DB 某行满足条件

**实现设计**：

新增节点类型 `WAIT`：
```
workflow_node.node_type = WAIT
workflow_node.node_params (JSONB):
  {
    "sensor_type": "FILE_ARRIVAL" | "HTTP_POLL" | "KAFKA_OFFSET" | "DB_ROW_EXISTS",
    "sensor_spec": {
      // FILE_ARRIVAL: { "channel_code": "...", "pattern": "settle-*.csv", "max_age_seconds": 3600 }
      // HTTP_POLL:    { "url": "https://...", "method": "GET", "match": "$.status == 'READY'" }
      // KAFKA_OFFSET: { "topic": "...", "partition": 0, "min_offset": 12345 }
      // DB_ROW_EXISTS:{ "schema": "biz", "sql": "SELECT 1 FROM ... WHERE ..." }
    },
    "timeout_seconds": 3600,
    "poll_interval_seconds": 30,
    "on_timeout": "FAIL" | "SKIP_DOWNSTREAM"
  }
```

执行方：复用 worker-core 加一个 `SensorWorker`（独立轻量 worker，不占主链 worker 资源）。

校验扩展：ADR-025 加 V16 规则（sensor_type 必填、sensor_spec 按 type 结构正确、timeout > poll_interval）。

**工作量**：2-3 工人日
- 数据库：node_type 字典加 WAIT；node_params 解析支持 sensor_spec
- 后端：新增 `SensorWorker` Spring bean + 4 个 SensorPolicy 子类（轮询 + 超时 + 状态机推进到完成或失败）
- 前端：WorkflowDesigner 加 WAIT 节点类型 + 专用属性面板（按 sensor_type 显示不同字段）
- 测试：每个 sensor_type 的 happy / timeout / 取消 各 1 个 IT

**ROI**：解锁"跨 system 编排"场景。比如对账场景的"等上游系统把 T-1 结算文件 push 到 SFTP 后再触发本系统对账"——目前只能用 cron 定时探测，会出现"未到先跑"或"已到延迟跑"问题。Sensor 一举解决。

### P0-2: on_failure / on_success Webhook Callback

**借鉴对象**：Airflow `on_failure_callback / on_success_callback` + DolphinScheduler 报警挂载。

**当前缺口**：失败只能进 `alert_routing_config` 推消息给人，不能**自动触发业务侧补偿动作**：
- 失败后调用外部 incident 系统建工单
- 失败后通知上游 PaaS 标记本批次"延后"
- 成功后通知下游 BI 系统"数据已就位"

**实现设计**：

`workflow_node` 加字段（或 `workflow_definition` 加全局 callback）：

```
workflow_node.callbacks (JSONB):
  {
    "on_failure": [
      { "type": "HTTP", "url": "https://...", "method": "POST", "headers": {...}, "body_template": "{...}" },
      { "type": "KAFKA", "topic": "biz.notification.v1", "key_template": "{tenantId}:{jobCode}" }
    ],
    "on_success": [...]
  }
```

执行方：orchestrator 状态机推进到 SUCCESS / FAILED 时同事务写 `outbox_event` (event_type=NODE_CALLBACK)，由 `OutboxPollScheduler` 异步推 Kafka，再由 `CallbackDispatcher` 消费 + 实际发 HTTP。

**为什么走 outbox 不直接 HTTP**：避免 callback 失败把 workflow 状态机卡住；callback 失败有 retry / DLQ 走兜底。

**工作量**：1-2 工人日
- DB：node_params 内嵌字段（不动 schema）
- 后端：`CallbackDispatcher` Spring bean + 2 个 driver (HTTP / Kafka)
- 前端：WorkflowDesigner inspector 加 callback 配置（DSL editor 复用）

**ROI**：解锁"事件驱动跨系统集成"。

### P1-1: Worker 路由策略多元化

**借鉴对象**：XXL-Job 9 种策略 / DolphinScheduler 路由策略。

**当前缺口**：`DefaultWorkerSelector` 只按 `last_heartbeat_at DESC` 选第一个 ONLINE worker，**没有负载感知**。

**已有但未充分使用**：`worker_registry.metrics_*` 字段（CPU、partitions_running 等心跳上报），但 selector 没读。

**实现设计**：

加配置项 `tenant.routing_strategy` (`resource_queue.routing_strategy` per-queue 覆盖)：

| 策略 | 行为 |
|---|---|
| `FIRST_ONLINE` | 当前默认 |
| `LEAST_LOADED` | 按 `partitions_running ASC` 排 |
| `LEAST_RECENT_USE` | 按 `last_assigned_at ASC` 排，公平轮询 |
| `STICKY_BY_TENANT` | 同租户优先粘上次 worker（降低缓存抖动） |
| `FAILOVER_ORDERED` | 主备列表，活的第一个 |
| `BROADCAST` | 分片广播（一个 partition 派给 N 个 worker 各干一片） |

**工作量**：2-3 工人日
- DB：resource_queue 加 routing_strategy 列（V12X migration）
- 后端：`DefaultWorkerSelector` 拆 strategy chain；按上面 6 种策略实现
- 测试：每个策略 1 个 IT（mock 多 worker + 验证选中目标）

**ROI**：海量场景下负载更均匀；金融对账类小批量"必须打到同一 worker"的需求也能支撑。

### P1-2: KEDA / HPA Driven Worker 弹性扩缩

**借鉴对象**：Argo Workflows + KEDA / k8s HPA。

**当前缺口**：helm chart 只能配静态 `replicaCount`，**没法按 `job_partition WHERE status=WAITING` 队列深度自动扩 worker**。

**实现设计**：

在 helm chart 加 KEDA ScaledObject 模板：

```yaml
# helm/batch-platform/templates/scaledobject.yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: {{ .Release.Name }}-worker-import
spec:
  scaleTargetRef:
    name: {{ .Release.Name }}-worker-import
  minReplicaCount: 1
  maxReplicaCount: {{ .Values.workerImport.maxReplicas }}
  triggers:
    - type: postgresql
      metadata:
        connectionFromEnv: BATCH_DB_URL
        query: "SELECT COUNT(*) FROM batch.job_partition WHERE status='WAITING' AND worker_group='import'"
        targetQueryValue: "10"  # 每 10 个 waiting partition 触发一次扩容
```

并加一个 metric exporter（Prometheus）暴露 `batch_partition_waiting_count{worker_group="..."}`，KEDA 也可走 Prometheus trigger。

**工作量**：3-4 工人日
- helm: 加 ScaledObject 模板 + values.yaml 节
- backend: 加 Prometheus metric `batch_partition_waiting_count`（按 worker_group label）
- runbook: 加 KEDA 部署指引（需 k8s 1.20+）
- 测试: minikube + KEDA mock + 压测验证 worker 自动扩

**ROI**：突发流量场景从"半分钟才扩 N 个 worker"变成"30 秒内扩好"；闲时也能缩到 1 个 worker 省资源。

### P2-1: Workflow 版本灰度

**借鉴对象**：Temporal versions / Airflow paused DAG。

**当前缺口**：`workflow_definition.version` 字段存在但**所有租户共用一个 version**，改 workflow 影响面是全部租户。

**实现设计**：

加 `workflow_tenant_pin` 表（轻量映射）：

```sql
CREATE TABLE batch.workflow_tenant_pin (
  id BIGSERIAL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  workflow_code VARCHAR(128) NOT NULL,
  pinned_version INT NOT NULL,
  pinned_by VARCHAR(128) NOT NULL,
  pinned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  reason VARCHAR(512),
  UNIQUE (tenant_id, workflow_code)
);
```

`LaunchService` 解析 workflow 时：

```
1. SELECT pinned_version FROM workflow_tenant_pin WHERE tenant_id=? AND workflow_code=?
2. 若有 pinned → 用 pinned_version
3. 否则用 max(version)
```

**工作量**：1 工人日（V13X migration + service 改 1 处 + 前端 list 页加"按租户固定版本"按钮）

**ROI**：可以做"灰度发布新版本 workflow 到一个租户验证一周，再全量"的策略，是企业级运维必备。

### P2-2: CronTab 风格调度预览

**借鉴对象**：DolphinScheduler / Quartz Scheduler UI。

**当前缺口**：JobDefinitionList 编辑页填了 `schedule_expr` 立刻保存，**没有"未来 N 次触发时间预览"**，cron 写错只能等到第一次 fire 失败时发现。

**实现设计**：

前端用 `cron-parser`（已有的 vue-cron 库或类似）+ 后端可加 `POST /api/console/jobs/schedule/preview` 接受 cron + timezone 返回未来 10 次触发时间。

**工作量**：0.5 工人日（前端纯 JS 解析 + 编辑表单加 preview 区）

**ROI**：低门槛改善，运营人员不用每次靠 cron 文档比对。

---

## 4. 显式不做的项

| 想法 | 来源 | 不做的理由 |
|---|---|---|
| Software-defined assets / 血缘 | Dagster | 数据治理领域，本系统 scope-discipline §ADR 红线明确拒收 |
| Python DAG 代码 | Airflow | JVM 单语言运维成本低；多语言 worker 引入运维灾难（Python 版本 / venv / 依赖管理） |
| Event sourcing workflow history | Temporal | 状态机驱动更适合批量任务特性（不是长事务）；event sourcing 引入 history 膨胀 + replay 复杂度 |
| 可视化 ETL pipeline 设计器（拖拽生成 SQL） | DataStage / Talend | 配置 + plugin 模型已足够；可视化 ETL 是 90 年代遗物，反而锁死灵活性 |
| 多语言 worker（shell / Python / R 节点） | DolphinScheduler | 增运维复杂度无业务必要；现有 PROCESS sqlTransformCompute 已能覆盖 95% SQL 场景 |
| 真正的"无代码"配置 GUI（如 nodred） | n8n / Zapier | 与可视化 ETL 同理；本系统目标用户是 ops 而非业务方 |
| 全 k8s-native（CRD-based workflow） | Argo / Tekton | 部署侧已经 helm 化但运行不需要 k8s primitive；CRD 化反而锁死部署目标 |

---

## 5. 实施路线图

| 阶段 | 时长 | 内容 | 总工作量 |
|---|---|---|---|
| **Wave 1 — P0 解锁外部集成** | 1 周内 | P0-1 Sensor 节点 + P0-2 Webhook callback | 3-5 工人日 |
| **Wave 2 — P1 运维能力补齐** | 2 周内 | P1-1 路由策略 + P1-2 KEDA 弹性 | 5-7 工人日 |
| **Wave 3 — P2 UX 与灰度** | 1 周内 | P2-1 版本灰度 + P2-2 cron 预览 | 1.5 工人日 |

每个 Wave 内的项可并行（无相互依赖）。Wave 之间无强依赖，可根据优先级灵活组合。

---

## 6. 验收标准

每项改进必须满足：

1. **代码**：通过 PMD / Spotless / vue-tsc / ESLint 守护；新增 IT/单测覆盖核心路径
2. **文档**：对应 ADR 文档（Sensor → ADR-028、Callback → ADR-029 等）写齐"做什么 / 不做什么 / 红线"
3. **OpenAPI 同步**：涉及 API 变化的（如 Sensor 配置 / Webhook 配置）同步 `console-api.openapi.yaml` + Changelog
4. **前端 UI**：能在 WorkflowDesigner / JobDefinitionList 等页面看到效果；不能只在后端可用
5. **测试 fixture**：ta/tb/tc 至少一个租户的配置包覆盖新功能（如 ta 加一个 Sensor 节点，tb 加一个 webhook callback）

---

## 7. 备忘

- 本计划与 [`scalability-assessment.md`](../architecture/scalability-assessment.md) §6 路线图**互补不重叠**：scalability 侧重"量级 → 分库分表 / 多 AZ"，本计划侧重"能力 → Sensor / callback / 路由 / 弹性"
- 本计划与 ADR-021/022/024/027 等"治理 / 合规"类 ADR 互补不重叠；治理类 ADR 的范围边界（什么不做）已经写死，本计划在那些红线之内活动
- 完成后需补 `docs/architecture/maturity-assessment.md` 重评，预期从 L4 推到 L4+
