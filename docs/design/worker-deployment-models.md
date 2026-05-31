# Worker 部署模型:平台 worker vs 自托管 SDK

**状态**:基线说明 · 2026-05-31
**关联 ADR**:[ADR-035](../adr/ADR-035-tenant-self-hosted-sdk.md) · [ADR-036](../adr/ADR-036-sdk-business-templates.md) · [ADR-029](../adr/ADR-029-atomic-worker-isolation.md)
**关联模块**:`batch-worker-{core,import,export,process,dispatch,atomic}` · `batch-worker-sdk`

> 本文档讲清两种 worker 的**边界、互补关系、混用方式、选型决策**。属架构基线,长期维护;具体协议字段在 [`docs/api/`](../api/),具体演进历史在 ADR-035。

---

## 1. 一句话定位

> **一套调度大脑(orchestrator),两种执行手臂(平台 worker / 自托管 SDK)。**
>
> 不是替代关系,是互补 —— 同一工作流可混用两种 worker。

---

## 2. 对比矩阵

| 维度 | 平台 worker(`batch-worker-*`)| 自托管 SDK(`batch-worker-sdk`)|
|---|---|---|
| **部署位置** | 平台 K8s | 租户 K8s / VM |
| **运行时** | 平台统一 JDK / 依赖 | 租户自由定 |
| **能力面** | 完整 5 阶段 Pipeline 状态机 + 直写 `file_record` / `pipeline_instance` | 只 CLAIM → EXECUTE → REPORT |
| **依赖** | `batch-common`(MyBatis / Flyway / Redis / DB 凭据) | 4 个轻量(jackson / kafka / slf4j / JDK) |
| **跟平台耦合** | 同 reactor 同代码库,版本必须一致 | 协议解耦,SDK 版本可滞后 |
| **数据可达性** | 必须能访问平台 DB + 业务数据源 | **平台访问不到租户数据** |
| **特权** | 持平台 DB 写权限 | 只有 API Key + Kafka 凭据 |
| **故障爆炸半径** | 多租户共享(handler 挂 = 全租户卡) | 单租户隔离 |
| **成本归属** | 平台账单 | 租户账单 |
| **运维方** | 平台团队 | 租户团队 |

---

## 3. 三类任务的归属

### 类型 A:**只能平台 worker**(SDK 干不了)

- **Pipeline 5 阶段任务**(IMPORT / EXPORT / PROCESS / DISPATCH 内置流水线)
  - 原因:这些阶段直接写 `pipeline_instance` / `file_record` / `pipeline_stage_*`,worker 跟 orchestrator 同库强一致;SDK 协议层没暴露这些状态机
  - 红线:CLAUDE.md「Worker 不能直接写 `job_instance` / `workflow_run`」,平台内置 worker 通过 `batch-common` 用专属接口写 pipeline 表,SDK 没这个口子
- **跨租户共享的标准化任务**(平台统一格式转换 / 统一对账 / 跨租户聚合)

### 类型 B:**只能自托管 SDK**(平台 worker 干不了)

- **租户业务数据不能出域**:私有 DB / SFTP / 内网 LDAP / 私有云对象存储
- **租户专属运行时**:特殊 JDBC 驱动(Oracle / Sybase / Informix)、Native 依赖(SAP RFC)、特定 Python 版本
- **合规审计要求"代码在我家执行"**:GDPR / 等保 / 金融合规 / 数据本地化
- **租户专属 Atomic 任务**:租户自家 shell 脚本 / 内部 SQL(避免给平台 atomic worker 任意命令执行的权限,见 ADR-029)

### 类型 C:**两种都行,看政策**(用户选择)

- 中等规模 import / export(数据脱敏后愿意进平台)
- 业务无关的格式转换 / 数据校验
- 标准 SQL 跑批

**典型选型路径**:租户初期上云全走平台 worker(开箱即用,运维省事);随业务变重 / 合规收紧 / 数据规模变大,**逐步把敏感任务迁到自托管 SDK**。

---

## 4. 互补的工作方式

关键点:**编排统一**,orchestrator 不区分下游 worker 是平台的还是自托管的。

```
workflow_definition (在 console-api 定义)
├─ node-1: taskType=IMPORT_STANDARD     → 平台 worker-import 接(平台域)
├─ node-2: taskType=tenant_xyz_validate → 自托管 SDK 接(租户内网验证)
├─ node-3: taskType=EXPORT_STANDARD     → 平台 worker-export 接(平台域)
└─ node-4: taskType=tenant_xyz_notify   → 自托管 SDK 接(租户内网 webhook)
```

orchestrator 派单按 `taskType` 路由到对应 worker pool / Kafka topic:

- `taskType=IMPORT_STANDARD` → 内部 Kafka topic → 平台 worker 消费
- `taskType=tenant_xyz_*` → `batch.task.dispatch.tenant-xyz.*` → 租户 SDK 消费

**一个工作流可以混用两种 worker**,编排逻辑同一套。这是 ADR-035 设计最关键的一点:**协议契约统一**(`TaskClaimRequest` / `TaskExecutionReportDto` 同 schema),只是承载体不同。

### 路由规则(强约束)

| 规则 | 说明 |
|---|---|
| 一个 `taskType` 只能注册到一处 | 否则派单路由歧义;平台侧 `TaskTypeRegistry` 保证唯一性 |
| 自托管 taskType **必须** 加 `tenant_` 前缀或租户命名空间 | 跟内置 taskType 隔离,审计可识别 |
| Kafka topic 按 `batch.task.dispatch.<tenantId>.*` 分租户 | 自托管 SDK 只订阅自家 topic,无法窃听其他租户 |
| SDK 不能注册内置 taskType(IMPORT/EXPORT/PROCESS/DISPATCH 等) | 平台侧 `TaskTypeRegistry` reject |

### 4.1 自托管享受的平台调度能力

**关键断言**:自托管 worker = **平台调度体系的一等公民执行节点**。trigger / workflow / 重试 / 补偿 / 审批 / fan-out / SLA / 优先级,全部对自托管 worker 生效,跟平台 worker 一模一样。

#### A. 触发方式(`batch-trigger` 模块全支持)

| 触发类型 | 配置示例 | 适用场景 |
|---|---|---|
| **CRON** | `cron_expression: "0 0 2 * * ?"` | 每天 / 每周 / 每月定时跑批 |
| **FIXED_RATE** | `interval: 5m` | 固定频率轮询(不等上次完成) |
| **FIXED_DELAY** | `delay: 10m` | 固定延迟(等上次完成 + N) |
| **ONE_SHOT** | `fire_at: 2026-06-01T03:00:00+08:00` | 延迟一次性任务 |
| **EVENT** | `event_source: kafka / webhook` | 外部事件驱动 |
| **ON_WORKFLOW_COMPLETE** | `upstream_workflow: xxx` | 上游 workflow 完成级联触发 |
| **MANUAL** | console "立即运行" 按钮 / API | 手动触发 / 重跑 |

**典型配置**:

```yaml
trigger_definition:
  trigger_code: tenant_xyz_daily_import
  trigger_type: CRON
  cron_expression: "0 0 2 * * ?"        # 每天 2:00
  timezone: Asia/Shanghai
  workflow_code: tenant_xyz_import_wf

workflow_definition:
  workflow_code: tenant_xyz_import_wf
  nodes:
    - taskType: tenant_xyz_import        # ← 派到自托管 SDK
```

每天 2:00 `batch-trigger` fire → orchestrator launch → 派单到租户 SDK。**租户进程零定时代码**。

#### B. 编排能力(`workflow_definition` 全支持)

- ✅ **DAG 编排**:自托管节点 + 平台节点可混串在同一 workflow_run
- ✅ **重试策略**:`retry_policy` (max_attempts / backoff / initial_delay) 全部生效
- ✅ **GATEWAY 条件分支**:基于 `SdkTaskResult.outputs` 字段决策
- ✅ **补偿 / Saga**:补偿节点同样派到自托管
- ✅ **审批节点**:审批未通过前 SDK 不会被派单,自动等
- ✅ **Fan-out 分片**:`partitionInvocationId` 分发到多副本 SDK 并发执行
- ✅ **优先级 / 亲和性**(ADR-027 范围内):自托管 worker pool 是合法目标
- ✅ **SLA 监控 / 超时告警**:基于 `created_at` / `started_at` / `completed_at`
- ✅ **手动重跑 / 跳过 / 强制完成**:console 运维动作全可用

#### C. 为什么自托管也能享受这些

trigger 不感知下游 worker 是谁,按 `taskType` 路由:

```
batch-trigger (cron / fixed-rate / event)
    │  trigger_outbox_event
    ▼
orchestrator (launch workflow_run)
    │  按 taskType 路由
    ├──→ 内部 Kafka topic → 平台 worker 消费
    └──→ batch.task.dispatch.<tenantId>.* → 自托管 SDK 消费
```

**自托管节点跟平台节点对 trigger / workflow 引擎完全无差别**。

#### D. 反模式:别在 SDK 进程里自己写 cron / scheduler

技术上能用 `ScheduledExecutorService` / Quartz 在进程内写定时器,但**强烈不建议**:

| 反模式 | 后果 |
|---|---|
| 进程内 `scheduleAtFixedRate` | 租户部 3 副本 → 3 个进程同时 fire → 任务重复 3 次 |
| 进程内 Quartz | 状态不可见,console 上查不到运行记录,运维盲区 |
| 进程内自实现重试 | 没补偿、没审批联动,跟 workflow 引擎脱节 |
| 进程内 cron 持久化(写自家 DB) | 跨重启可能丢任务 / 状态跟平台不一致 |
| 进程内业务定时 + 平台 trigger 并存 | 同一业务两种调度入口,运维理解成本爆炸 |

**正确做法**:**所有调度都在平台 trigger + workflow 配置**,SDK 进程纯被动 poll Kafka 接活。

### 4.2 自托管 **不享受** 的能力清单

为避免选型时误判,把"自托管 worker 拿不到的"按 6 类列清楚。

#### A. Pipeline 5 阶段状态机(永远不能用)

| 能力 | 原因 |
|---|---|
| 内置 Pipeline taskType(IMPORT/EXPORT/PROCESS/DISPATCH/FILE_STEP) | 平台 worker 独占,SDK 注册被 `TaskTypeRegistry` reject |
| 直写 `file_record` / `pipeline_instance` / `pipeline_stage_*` | CLAUDE.md 红线:Worker 不能直接写状态机表 |
| Pipeline stage 级生命周期事件 | 平台只看 task 终态 + counts(ADR-035 §6 路径 3) |
| `batch_day` 业务日历联动 | 平台 worker 用 `BatchDateService`,SDK 不暴露 |

→ **标准化文件流水线必须走平台 worker**;自托管只跑"租户自家业务"。

#### B. 平台基础设施直连(架构隔离)

| 能力 | 替代方案 |
|---|---|
| 平台 PostgreSQL 主库 | SDK 经 HTTP `/internal/*` 间接;租户存自家 DB |
| 平台 Redis(ShedLock / 缓存) | 租户自带或不用 |
| 平台内部 Kafka topics(`outbox_event` / `event_outbox_retry` / `trigger_outbox_event`) | SDK 只能订阅 `batch.task.dispatch.<tenant>.*` |
| `batch-common` 工具库(MyBatis mappers / Flyway / `BatchTimezoneProvider` / `IdGenerator`) | 不能依赖,自己实现 |
| 平台连接池(HikariCP / Kafka Producer) | SDK 内部维护 JDK HttpClient + KafkaConsumer |
| 平台 Flyway 迁移 | SDK 不能改平台 schema |

→ **数据 / 凭据完全租户自治**(这是卖点,不是缺陷)。

#### C. 平台横切功能(框架级)

| 能力 | SDK 端 |
|---|---|
| 统一异常体系(`BizException` + `ResultCode` + i18n) | ❌ SDK 用 `SdkTaskResult.fail(Throwable)` |
| i18n 错误码(`messages.properties`) | ❌ |
| 领域字典(`DictEnum`) | ❌ |
| 统一日志格式(`logback-spring.xml`) | ⚠️ MDC 字段名一致,logback config 租户自定 |
| `@Transactional` 事务模板 | ❌ 无 Spring,租户自管事务 |
| Spring AOP / 自调用 | ❌ |
| 构造器注入 / DI 容器 | ❌ Builder 模式手工组装 |
| `@PreAuthorize` / RBAC | ❌ 只接 `X-Batch-Api-Key`,租户内部不验权 |
| 审计日志(`@LogAudit`) | ❌ |
| `bypass-mode` 总开关 | ❌ |

→ SDK 代码风格更"原生 Java",而不是 Spring 项目。

#### D. 可观测性(跨域断层)

| 能力 | 现状 |
|---|---|
| 平台 Prometheus / Grafana | ⚠️ SDK 没指标暴露面,需租户自接 |
| 分布式 tracing 自动续传 | ⚠️ `traceId` MDC 透传,但链路平台看不到 SDK 侧 span |
| 平台日志聚合(ELK / Loki) | ❌ SDK 日志在租户环境 |
| 健康检查 endpoint(`/actuator/health`) | ❌ 租户进程自暴露 |
| JaCoCo 覆盖率 / 质量门禁 | ❌ 租户 codebase 不在平台 CI |

→ **SLA 排障需要租户配合**,平台运维 grep 不到 SDK 日志。

#### E. 调度 / 编排自主权(刻意限制)

| 能力 | SDK |
|---|---|
| handler 内 spawn 子 task | ❌ 破坏状态机 |
| handler 内调 orchestrator API 派生工作流 | ❌ |
| 进程内 cron / scheduler | ⚠️ 技术上能写,**强烈不建议**(见 §4.1 反模式) |
| 跨 taskType 编排 | ❌ 走平台 workflow |
| 跨租户协调 | ❌ 平台 workflow 不允许跨租户依赖 |
| 优先级抢占 | ❌ SDK FIFO 接 Kafka |
| 接 outbox_event 做副作用 | ❌ outbox 是平台内部表 |

→ 故意不给。租户想编排 → 配 workflow + trigger。

#### F. 运维特权(平台域内)

| 能力 | SDK |
|---|---|
| `ConsoleOrchestratorProxyService` 运维(outbox cleanup / republish) | ❌ |
| Dry-run / 对账 / Forensic(ADR-021/022/026) | ❌ |
| 修改 `workflow_definition` / `trigger_definition` | ⚠️ 走 console-api 凭账号,SDK 不能改 |
| 查看其他租户任务 | ❌ 多租隔离强制 |
| 管理 worker 池容量 | ❌ 自家 SDK 进程扩缩容是租户运维事 |

#### 速查总表

| 类别 | 不享受的核心能力 | 决策影响 |
|---|---|---|
| **A. Pipeline 状态机** | 内置 5 阶段 / `file_record` 写 / `batch_day` | 标准文件流水线必须走平台 worker |
| **B. 平台基建** | DB / Redis / 内部 Kafka / `batch-common` | 自治为主,设计上不该蹭 |
| **C. 横切功能** | i18n / `BizException` / Spring AOP / 审计 | 代码风格更原生,功能自己实现 |
| **D. 可观测性** | 指标 / tracing 续传 / 日志聚合 | 运维分裂,SLA 排障要租户配合 |
| **E. 编排自主** | 子 task spawn / 内进程 cron / 跨域协调 | 故意限制,走 workflow + trigger |
| **F. 运维特权** | outbox 运维 / dry-run / 跨租户视野 | 平台域内动作走 console |

---

## 5. 自托管的真正价值

不要被"SDK 只能单次执行"误导 —— 实际是**长期、并发、多 handler 持续执行**:

| 维度 | 实际能力 |
|---|---|
| 进程生命周期 | 常驻,持续 poll Kafka |
| 并发 | 单进程默认 4 个 task 并发(`maxConcurrentTasks` 1–64) |
| Handler 数 | 一个进程可注册 N 个 taskType |
| 水平扩展 | 同 `kafkaGroupId` 部 N 个进程,Kafka 自动 partition 分配 |
| 单 task 体量 | 无上限 — 跑 10M 行 import / 50 GB SFTP / 30min SQL 都行,通过 lease renewal 续命 |
| 长任务支持 | heartbeat + lease renewal scheduler 都活着 |

典型租户部署形态:

```
租户 K8s namespace:
  ├─ tenant-xyz-import-worker × 3 副本   (每个并发 4 = 12 路 import)
  ├─ tenant-xyz-export-worker × 2 副本   (每个并发 4 = 8 路 export)
  └─ tenant-xyz-atomic-worker × 1 副本   (跑租户自家 shell/SQL)
```

### 不可替代的价值(平台共享 worker 给不了)

1. 🔴 **数据不出租户网络** —— 平台只看到 Kafka 派单 + HTTP 报告,业务数据**全在租户域内流转**。合规 / GDPR / 数据本地化一刀切搞定。**这一条就足够养活整个 ADR-035。**
2. 🔴 **执行环境完全自主** —— 租户自己定 JDK / 依赖 / Native lib / CA 证书 / 内网连通性
3. 🔴 **故障隔离 + 资源归属** —— 一个租户 OOM 不影响其他租户;计算资源走租户 K8s quota,**成本归租户**
4. 🟡 **SLA 自主** —— 加副本即可,不挤平台共享池

---

### 5.1 调度上下文下沉:bizDate / 平台状态 / attemptNo

自托管 SDK 默认是"瞎子" —— 当前 `TaskDispatchMessage` 只有 `parameters / runtimeAttributes`,没有任何"业务日 + 调度元信息"。本节说明哪些上下文**应当下沉**,哪些**不能下沉**。

#### A. 应当下沉:调度时刻的具体值

**1. 业务日(bizDate)及相邻日**

| 字段 | 用途 |
|---|---|
| `bizDate` | 数据按业务日分区 / 跟平台对账对齐 |
| `prevBizDate` | 跨日逻辑(对账 / 补偿 / 回溯) |
| `nextBizDate` | 预生成下日数据 |
| `isHoliday` | 决定全量 vs 增量 / 决定跑或跳 |

**反例(租户自己算 bizDate)的坑**:跨午夜执行错位、节假日规则不同步、跨时区错位。

**2. 调度溯源元信息**

| 字段 | 用途 |
|---|---|
| `attemptNo` | 区分首次 vs 重试,handler 做"重试时跳过通知"等语义 |
| `triggerCode` / `triggerType` | 业务知道是 cron / 手动 / 上游 workflow 触发 |
| `workflowRunId` | 关联同一 workflow 内的兄弟 task(做幂等键 / 关联日志) |

**协议草案**:`TaskDispatchMessage.schedulingContext`:

```json
{
  "taskId": 12345,
  "tenantId": "tenant-xyz",
  "taskType": "tenant_xyz_import",
  "parameters": {...},
  "schedulingContext": {
    "bizDate": "2026-05-31",
    "prevBizDate": "2026-05-30",
    "nextBizDate": "2026-06-01",
    "isHoliday": false,
    "triggerCode": "tenant_xyz_daily",
    "triggerType": "CRON",
    "workflowRunId": 98765,
    "attemptNo": 1
  }
}
```

**SDK API**:

```java
public final class SdkTaskContext {
    public LocalDate bizDate();
    public LocalDate prevBizDate();
    public LocalDate nextBizDate();
    public boolean isHoliday();
    public int attemptNo();
    public String triggerCode();
    public Long workflowRunId();
    // ...
}
```

#### B. 不应下沉:平台内部规则与表

| 内容 | 为什么不下沉 |
|---|---|
| `batch_day` 表语义 / 节假日规则表 | 数据耦合;租户重启失同步;规则变更要全租户升级 |
| 日切时刻 / 时区切换逻辑 | 平台是单一权威源 |
| `BatchDateService` 实现 | 内部服务,不暴露 |
| 工作流 DAG 结构 / 兄弟节点状态 | 破坏状态机封装 |

**核心原则**:**下沉值,不下沉规则**。平台调度时刻把 bizDate 算好,SDK 只读使用。

#### C. 跨日 / 重试时的一致性

- 任务在 5/30 23:59 派出,5/31 00:05 重试 → **两次都用 5/30 的 bizDate**(写在派单消息里)
- SDK **不能**用 `LocalDate.now()` 覆盖
- SDK **不能**跨进程重启缓存 bizDate(下次派单平台会重传)
- 跨任务的 bizDate 同步靠 `workflowRunId`,不靠 SDK 进程内状态

#### D. Heartbeat 双向通道(server-driven directive)

`heartbeat` response 不再只是 200 OK,带 platform directive:

```json
{
  "platformStatus": "DEGRADED",
  "desiredMaxConcurrent": 2,
  "shouldDrain": false,
  "pausedTaskTypes": ["tenant_xyz_old"],
  "bizDate": "2026-06-01",
  "nextHeartbeatHint": "60s"
}
```

| platformStatus | SDK 行为 |
|---|---|
| `NORMAL` | 正常 |
| `DEGRADED` | 按 `desiredMaxConcurrent` 降并发,记 warn |
| `PAUSED` | 立刻 `KafkaConsumer.pause()`,保持 heartbeat 等恢复 |
| `DRAINING` | 不接新消息(同 `stop()` 但不退进程),等 in-flight 完 |

`pausedTaskTypes` 命中 → SDK CLAIM 前自检 → 直接 return 不消费(平台后续重派)。

**这是当前协议的空白**(详见 review doc §2.6),修复见 review doc P1 列表。

#### E. SDK 端主动感知(不依赖 heartbeat 推)

| 检测项 | 行为 |
|---|---|
| **Kafka consumer lag** | `consumer.endOffsets() - position()`,暴露到 `metrics()`,本地告警 |
| **REPORT 连续失败 N 次** | fail-fast(平台可能挂了) |
| **CLAIM 连续 4xx** | fail-fast(凭据 / 配置坏了) |
| **Heartbeat 连续失败** | warn 并标记 `isHealthy()=false` |

---

## 6. 内部 stage 自由 vs 跨 task 编排

ADR-035 §6 路径 3 划的边界:

| 范围 | 谁说了算 |
|---|---|
| **单 task 内部步骤**(租户业务 stage 串联) | 租户 SDK handler 内部自由,平台不感知 |
| **跨 task 协调**(任务 A 完了再跑 B,fan-out,补偿,审批) | **必须**走平台 workflow_node,SDK 不能自己派生 |

合法示例 —— 一个 task 内串多个内部步骤:

```java
@Override
public SdkTaskResult execute(SdkTaskContext ctx) {
    try (var ftp = openSftp()) {
        var raw       = ftp.download(ctx.parameters().get("path"));
        var parsed    = parse(raw);
        var validated = validate(parsed);
        var enriched  = enrich(validated);
        var rows      = loadToTenantDb(enriched);
        return SdkTaskResult.ok("imported " + rows, Map.of("rows", rows));
    }
}
```

平台只看终态 + counts,内部 stage 是租户的"自由区"。

**禁止**:在 SDK handler 里 spawn 新 task / 直接调 orchestrator API 派生子任务 —— 状态机会分裂。

---

## 7. 行业类比

| 系统 | 中央编排 | 边缘执行节点 |
|---|---|---|
| Temporal | Server(workflow 状态机) | Worker SDK(只跑 Activity) |
| Airflow | Scheduler(DAG 编排) | Celery / K8sExecutor Worker |
| Argo Workflows | Controller | Pod(单 step) |
| AWS Step Functions + Lambda | Step Functions | Lambda |
| GitHub Actions | github.com workflow | Self-hosted runner |
| **本系统** | orchestrator + workflow 引擎 | **平台 worker / 自托管 SDK** |

没有任何成熟系统是 worker 自编排 —— 编排需要全局视图(谁先谁后、重试、补偿、SLA、资源池),全局视图在中央。

---

## 8. 常见误解(避免歧义)

| 误解 | 实际 |
|---|---|
| "自托管能取代平台 worker" | ❌ Pipeline 5 阶段 SDK 实现不了 |
| "平台 worker 能取代自托管" | ❌ 数据出域 + 合规问题解不开 |
| "两边能跑同一个 taskType" | ❌ 一个 taskType 只能注册到一处(派单路由歧义) |
| "选其中一种就够了" | ❌ 大企业租户必然混用:标准任务走平台,敏感任务走自托管 |
| "自托管 = 平台帮你管的精简版" | ❌ 自托管 = 完全自治,平台只调度不运维 |
| "自托管 = 单次执行" | ❌ 长期常驻 + 并发 + 多 handler + 水平扩展 |
| "自托管支持自定义编排" | ❌ task 内自由,跨 task 必须走平台 workflow |

---

## 9. 选型决策树

```
租户提需求:有个 X 任务想跑
   │
   ├─ X 涉及租户私有数据 / 数据不能出域? ──── 是 ──→ 自托管 SDK
   │
   ├─ X 需要租户专属运行时(特殊驱动 / native deps)? ─ 是 ──→ 自托管 SDK
   │
   ├─ X 是合规审计要求"代码在我家执行"? ─────── 是 ──→ 自托管 SDK
   │
   ├─ X 跑租户自家 shell / 任意 SQL? ───────── 是 ──→ 自托管 SDK(避免给平台 atomic 任意命令权限)
   │
   ├─ X 是 Pipeline 5 阶段标准流水线? ──────── 是 ──→ 平台 worker(SDK 干不了)
   │
   ├─ X 跨多租户共享(平台统一对账等)? ─────── 是 ──→ 平台 worker
   │
   └─ X 是普通业务任务,数据不敏感,租户也不想运维 ──→ 平台 worker(默认)
```

---

## 10. 迁移路径(平台 → 自托管)

租户从平台 worker 迁到自托管 SDK 的标准流程:

1. **盘点 taskType** —— 找出哪些任务需要迁(类型 B 必迁,类型 C 选择性迁)
2. **租户 K8s 准备** —— 部署 SDK 进程模板(参考 ADR-035 sample-tenant-worker)
3. **协议接通** —— 配 API Key(P2 上线后)/ Kafka SASL(P3 上线后)
4. **影子运行** —— 新 taskType `tenant_xxx_v2` 先在自托管跑,workflow 双发(平台老 + SDK 新),对比结果
5. **切换** —— workflow 改路由到新 taskType,老 taskType 留 30 天回滚窗口
6. **下线** —— 平台侧 `TaskTypeRegistry` 摘除老 taskType

⚠️ **禁止**:同一 taskType 两边并行注册 —— 派单路由会歧义。

---

## 11. 重派与幂等

**核心断言**:平台对同一 `taskId` **不只派一次**。租户业务必须做幂等。

### 11.1 重派的 6 个触发点

| # | 触发 | 说明 | 同 taskId? |
|---|---|---|---|
| 1 | **Lease 超时** | Worker CLAIM 后未按 `leaseRenewInterval` 续约 → orchestrator 重派到其他 worker | ✅ 同 taskId |
| 2 | **Worker 失联** | Heartbeat 超阈值 → 该 worker 上所有 in-flight task 全 reclaim | ✅ 同 taskId |
| 3 | **业务失败重试** | `SdkTaskResult.fail()` + `retry_policy.max_attempts > 1` → 按退避策略重派 | ✅ 同 taskId,`attempt_no` 递增 |
| 4 | **工作流补偿 / GATEWAY 循环** | 下游失败触发 `compensation_node`;或 DAG 配循环 | ⚠️ 通常新 task,语义上"重做" |
| 5 | **运维手动** | console "重试" 按钮 / "重跑失败节点" | ✅ 同 taskId |
| 6 | **Trigger 周期** | cron / event trigger fire,新 workflow_run + 新 task | ❌ 新 task,但同 taskType |

### 11.2 幂等责任划分

| 谁负责 | 保证什么 |
|---|---|
| 平台 orchestrator | `Idempotency-Key` 去重 state machine 推进 |
| SDK 框架 | CLAIM/REPORT 注入 `Idempotency-Key`;CLAIM 失败不进 handler;Lease renewal 主动续约 |
| **租户 handler** | **业务幂等**:同 `taskId` 跑两次,副作用一致(upsert / 原子写 / 外部 API 幂等键) |

### 11.3 租户业务幂等推荐模式

**业务键**:`taskId` + `partitionInvocationId`(分片场景)

```java
@Override
public SdkTaskResult execute(SdkTaskContext ctx) {
    String idemKey = ctx.taskId() + "-" + ctx.runtimeAttributes().getOrDefault("partitionInvocationId", "0");

    // 1. 数据库 upsert(不是 insert)
    repo.upsert(idemKey, payload);

    // 2. 外部 API 调用带幂等键
    externalApi.call(payload, idemKey);

    // 3. 文件原子写(写临时文件 + rename,而不是追加)
    Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

    return SdkTaskResult.ok(...);
}
```

**反模式**:

- ❌ `INSERT INTO ... VALUES (...)` 不带唯一键 → 重派后产生重复行
- ❌ 直接调"发邮件 / 发短信"无幂等键 → 重派后用户收两遍
- ❌ 文件 append 模式写入 → 重派后内容翻倍
- ❌ 业务计数器 `count = count + delta` → 重派后多加一次

### 11.4 SDK 框架当前已知风险

⚠️ **跨进程同 task 并发**:Worker A GC 卡死 → lease 超时 → orchestrator 派给 B → A 恢复后继续跑 → 双进程并行。

- SDK 当前无 cancellation token 机制;A 的 handler 不会主动 abort
- A 最终 REPORT 时会被平台 reject(lease 已不属于自己),但**业务副作用已经发生**
- 缓解:**租户业务幂等是唯一兜底**

后续修复方向(见 review doc P0):`SdkTaskContext.isCancelled()` + `LeaseRenewalScheduler` 检测 lease revoked。

### 11.5 跟"重派"打交道时,handler 应当

1. **每次都按"第一次"写代码**,不假设"上次跑了什么"
2. **从 `SdkTaskContext` 读所有需要的状态**,不要依赖进程内 in-memory 缓存
3. **副作用走幂等接口**(upsert / idempotency-key)
4. **长任务定期 check `Thread.currentThread().isInterrupted()`**(`stop()` drain 时框架会 interrupt)
5. **不要在 handler 里 spawn 后台线程**做异步副作用 —— 重派时上一次的后台线程还在跑

### 11.6 平台暂停 / 异常时 handler 应当

依赖 §5.1 D 节的 `platformStatus` 推送(实施后):

| 平台状态 | handler 行为 | 框架行为 |
|---|---|---|
| `NORMAL` | 正常跑 | 全速消费 |
| `DEGRADED` | 长任务定期 check `ctx.isCancelled()`,允许 framework 中断 | `KafkaConsumer` 按 `desiredMaxConcurrent` 降速 |
| `PAUSED` | 当前 task 跑完即止,**不**派生副作用 / 不发外部通知 | `KafkaConsumer.pause()`,heartbeat 继续 |
| `DRAINING` | 当前 task 尽快收尾(若可 checkpoint 则 checkpoint 后退出) | 不接新消息 |

**租户业务约束**:

1. **长循环里 check `ctx.isCancelled()`** —— 平台撤销 lease / DRAINING 时框架会标记
2. **副作用前再 check 一次** —— 发外部 API / 写跨域数据前确认未被取消
3. **不要把"是否暂停"缓存到进程内** —— heartbeat tick 才是最新事实

---

## 12. 维护

- 协议字段变更 → 同步 `docs/api/` + `batch-worker-sdk` wire schema(见 `docs/review/batch-worker-sdk-deep-review-2026-05-31.md` §2)
- 部署模型策略变更(如新增第三种 worker 形态)→ 必须先开 ADR,本文档落后跟随
- 选型决策树变更 → 同步 console "新建工作流" 帮助文案

---

**参考**

- [ADR-035 租户自托管 SDK](../adr/ADR-035-tenant-self-hosted-sdk.md)
- [ADR-036 SDK 业务模板](../adr/ADR-036-sdk-business-templates.md)
- [ADR-029 Atomic worker 隔离](../adr/ADR-029-atomic-worker-isolation.md)
- [batch-worker-sdk 深度评估](../review/batch-worker-sdk-deep-review-2026-05-31.md)
- [Pipeline vs Workflow 边界](pipeline-vs-workflow-definition.md)
