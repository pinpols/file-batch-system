# Plan — batch-worker-sdk 2026 H2 演进路线图

> 优先级:Phase 0/1/2/3 必做 · Phase 4 看真实需求 · Phase 5/6 按需启动
> 估时:单人 ~10 周 / 双人并行 ~6 周(不含 Phase 6)
>
> **本 plan 把 4 份 SDK 文档的 50+ 待办去重合并成 28 个独立项**,按依赖关系 + 价值密度组织成 7 个 phase。每个 phase 收官 = 一个租户能直接感知的改善。

## 1. 背景

4 份 SDK 文档已成型,但待办散落,直接挨个做会乱:

| 文档 | 定位 |
|---|---|
| [`review/batch-worker-sdk-deep-review-2026-05-31.md`](../review/batch-worker-sdk-deep-review-2026-05-31.md) | 当前实现的近期修复清单(P0/P1/P2 bug + 协议补强) |
| [`design/worker-deployment-models.md`](../design/worker-deployment-models.md) | 平台 / 自托管两种 worker 边界与能力(协议演进 + 调度上下文下沉) |
| [`design/sdk-industry-benchmark.md`](../design/sdk-industry-benchmark.md) | 跨季度对标演进路线图(Temporal/Conductor 对标项) |
| [`design/sdk-task-type-configuration.md`](../design/sdk-task-type-configuration.md) | 自定义 taskType 配置完整链路(注册体系 + console UX) |

### 核心原则

- **每个 phase 一个完整可验证产出**(不是内部重构,是租户能感知的改善)
- **协议变更走 dual-rollout**:平台先支持新字段,SDK 后用 —— 跨发布周期兼容窗口
- **单 PR ≤ 600 LoC** 净增,超了拆;每个 PR push 前 `mvn clean compile`(memory: `feedback_clean_before_push`)
- **完成项不删**,划线 + 日期 + PR 号;新发现的待办丢下个 phase,不塞当前
- **YAGNI**:没真实需求的 phase 砍掉(P6 默认延后)
- **不为指标写测试**(memory: `feedback_coverage_principle`)
- **🔵 FE 开发可延后**:本 plan 标 🔵 的项为 FE 工作,**默认推迟到 BE 完成验证后再启动**。BE / SDK 侧完成 = 可通过 API / 命令行 / SQL 验证即视为 phase 出口达成,FE 不阻塞 SDK 发版。

### 决策记录(2026-05-31 定稿)

> 启动前 17 个核心决策 + 2 个隐藏决策已落定,本节作为不可变 record。后续 phase 启动不再重新决策,除非有充分理由(在 §14 维护章追加 changelog)。

#### 🔴 Phase 0 前定(立刻)

| # | 决策点 | 选择 | 落地位置 |
|---|---|---|---|
| 1 | 资源 | **2 人并行**(SDK + Platform),FE 按需 | §10 双人并行版 |
| 2 | `/internal/*` OpenAPI | **新建 `docs/api/orchestrator-internal.openapi.yaml`**(SDK 准客户),跟 console-api yaml 分开 | Phase 0 与 wire DTO 一起立 |
| 3 | dual-rollout 兼容窗口 | **2 周** | §4.2 / §15.5 |
| 4 | `schemaVersion` 初始值 | **字符串 `"v1"`**(便于 `v2-rc` / `v2-beta`) | §2.1 |
| 5 | PR tracking 工具 | **GitHub milestone `sdk-h2-2026`** | §13 启动检查清单 |
| 6 | Phase sign-off | **你 + ADR-035 owner 双签** | §13 |

#### 🟡 Phase 3 前定

| # | 决策点 | 选择 | 落地位置 |
|---|---|---|---|
| 7 | taskType code 命名 | **`tenant_<tenantId>_<verb>`** 全小写下划线 | sdk-task-type-configuration.md §3.1 |
| 8 | taskType 版本化 | **`_v2` 后缀** | §6.2 不兼容升级 |
| 9 | sample-tenant-worker 位置 | **主 repo `samples/sample-tenant-worker/`** | §5.1 M3.1 待办 |
| 10 | 跨租户灰度 | **立刻**:按 taskType code(`_v1`/`_v2`);**P5/P6**:按 buildId 增量加 | Phase 3 / Phase 6 |
| 11 | `effective_parameters` 字段类型 | **JSONB** | Phase 3 M3.1 待办 |

#### 🟢 Phase 4+ 前定(中期)

| # | 决策点 | 选择 | 落地位置 |
|---|---|---|---|
| 12 | Phase 4 砍不砍 checkpoint/progress | **W8 时根据真实租户任务时长决定**:都 <5min → 砍;>10min → 全做 | Phase 4 §6.2 决策点 |
| 13 | lease TTL 默认值 | **保持 60s/3min**(偏保守但稳妥) | SDK 配置不动 |
| 14 | testkit 子模块名 | **`batch-worker-sdk-testkit`**(`-testkit` 后缀业界标准) | Phase 5 §7.1 |
| 15 | OTel 依赖方式 | **可选依赖**(`<optional>true</optional>`,保持 SDK 2MB) | Phase 5 §7.1 |
| 16 | typed handler 风格 | **`SdkTypedTaskHandler<I, O>` 基类**(显式 + 编译期类型校验) | Phase 5 §7.1 |
| 17 | Payload codec 形态 | **只给接口 + 文档示例,不内置加密实现** | Phase 6 §8.1 |

#### 🔵 隐藏决策

| # | 决策点 | 选择 |
|---|---|---|
| 隐-1 | Phase 收官 retrospective | **每 Phase 结束 5 行 retro**:实际 vs plan diff + 经验,写在 `docs/plans/sdk-roadmap-2026-h2-progress.md` |
| 隐-2 | 协议 PR 回滚策略 | **默认 revert**;留 1 周观察期才允许 hotfix 兼容补丁 |

#### 决策修订

若 phase 实施过程中发现某条决策需要改:

1. 在 §14 维护章追加 changelog(日期 + 决策 # + 旧值 → 新值 + 理由)
2. 同步本节表格(划线旧值,补新值)
3. 影响范围 ≥ 1 个 phase 的修订需要 ADR-035 owner 二次签字

---

### Roadmap 全景

```
[Phase 0] 协议基础 (W1, 3d)
    ↓
[Phase 1] SDK 硬伤修复 (W1-2, 1w)            ← 纯 SDK 可独立发版
    ↓
[Phase 2] 调度上下文下沉 (W2-4, 1.5w)         ← 平台双向通讯
    ↓
[Phase 3] taskType 注册 ⭐ (W4-7, 2-3w)       ← BE 优先,FE 延后
    ├── M3.1 后端通 (W4-5)   ✅ 必做
    └── M3.2 console UI      🔵 FE,可延后
    ↓
[Phase 4] 长任务可控性 (W8-9, 2w)             ← BE 必做,UI 🔵 延后
    ↓
[Phase 5] 开发体验 (W9-10, 2w)                ← testkit / 类型安全(纯 SDK)
    │
    └── [Phase 6] 合规企业级 (按需)            ← 真实需求驱动

[Phase 7] 观测性收尾 (可穿插任何 phase, 0.5w)

🔵 = FE 工作,默认延后到 BE 完成验证后启动,不阻塞 SDK 发版
```

---

## 2. Phase 0 — 协议演进基础

**目标**:不做这个,后面所有协议改动都会破坏租户。
**估时**:3 天 · **产出**:1 个 PR · **依赖**:无

### 2.1 待办

| # | 任务 | 工作量 | 责任 |
|---|---|---|---|
| 0.1 | ~~`TaskDispatchMessage.schemaVersion` 字段(`"v1"`)+ reject 未知 major~~ ✅ 2026-05-31 #SDK-P0-1 | 1h | BE |
| 0.2 | ~~SDK 自有 wire DTO records(`RegisterRequest` / `HeartbeatRequest` / `ClaimRequest` / `ReportRequest` / `RenewRequest`),放 `sdk/wire/`~~ ✅ 2026-05-31 #SDK-P0-1 | 4h | SDK |
| 0.3 | ~~`SdkWireContractTest` —— SDK record 反序列化到平台 DTO 不丢字段,CI 拦截漂移~~ ✅ 2026-05-31 #SDK-P0-1 | 2h | BE |
| 0.4 | ~~Dual-rollout 指南文档(SDK / orchestrator 交错升级)~~ ✅ 2026-05-31 #SDK-P0-1 | 1h | Docs |

### 2.2 DoD

- ✅ `schemaVersion` 在 `TaskDispatchMessage` / `RegisterResponse` 里
- ✅ SDK 侧 5 个 wire record 类 + 反序列化测试
- ✅ `SdkWireContractTest` 覆盖所有 DTO 必填字段
- ✅ 修改 DTO 字段名 → 契约测试立刻 fail
- ✅ 156 现有测试全过

### 2.3 验证

```bash
# 模拟字段漂移
git diff orchestrator/.../WorkerHeartbeatDto.java  # 改字段名
mvn -pl batch-orchestrator test -Dtest=SdkWireContractTest
# Expected: FAIL with "field 'workerCode' missing in SDK RegisterRequest"
```

### 2.4 风险与缓解

- **老 SDK 没 schemaVersion** → 平台对缺字段 fallback `"v1"`(向后兼容)

---

## 3. Phase 1 — SDK 自身硬伤修复

**目标**:纯 SDK,跟平台无协议变更,可独立发版。
**估时**:1 周 · **产出**:3-4 个 PR · **依赖**:Phase 0

### 3.1 待办

| # | 任务 | 工作量 | 文件 |
|---|---|---|---|
| 1.1 | `stop()` 顺序倒置(Kafka 先 → drain → heartbeat/lease 最后 → deactivate) | 3h | `client/BatchPlatformClient.java` |
| 1.2 | CLAIM 401/403 fail-fast,5xx 指数退避 | 2h | `dispatcher/TaskDispatcher.java` |
| 1.3 | `ConsumerRebalanceListener.onPartitionsAssigned()` 处理 paused 状态恢复 | 4h | `dispatcher/KafkaTaskConsumer.java` |
| 1.4 | `HeartbeatScheduler` 改 `scheduleWithFixedDelay`(避免追赶式) | 1h | `scheduler/HeartbeatScheduler.java` |
| 1.5 | `IOException` message 去掉 errBody 明文(改 `log.debug`) | 1h | `internal/PlatformHttpClient.java` |
| 1.6 | `BatchPlatformClient.metrics()` POJO + `isHealthy()` boolean | 2h | `client/BatchPlatformClient.java` |
| 1.7 | `KafkaTaskConsumer` 异常退出 → 设 `isHealthy()=false`,不静默死 | 1h | `dispatcher/KafkaTaskConsumer.java` |

### 3.2 PR 拆分

| PR | 内容 | LoC 估 |
|---|---|---|
| #SDK-P1-1 | stop() 顺序 + ConsumerRebalanceListener | ~300 |
| #SDK-P1-2 | CLAIM 401/403 fail-fast + 5xx 退避 | ~250 |
| #SDK-P1-3 | HeartbeatScheduler 改 fixed-delay + IOException message | ~150 |
| #SDK-P1-4 | metrics() + isHealthy() + consumer 死时上报 | ~300 |

### 3.3 DoD

- ✅ 156 现有测试全绿
- ✅ 新增 8 测试覆盖上述路径
- ✅ 模拟 401 → SDK 5s 内 fail-fast 退出 + 退出码非 0
- ✅ 模拟 rebalance → paused 状态正确恢复(`MockConsumer`)
- ✅ `metrics()` 可被租户进程暴露到 Prometheus
- ✅ SDK jar 大小 < 2.1 MB

---

## 4. Phase 2 — 调度上下文下沉 + 双向通道

**目标**:SDK 不再"瞎子",平台暂停 / 异常 / 业务日变化都能即时感知。
**估时**:1.5 周 · **产出**:1 BE PR + 1 SDK PR · **依赖**:Phase 0

### 4.1 待办

| # | 任务 | 工作量 | 模块 |
|---|---|---|---|
| 2.1 | `TaskDispatchMessage.schedulingContext`(bizDate/prev/next/isHoliday/attemptNo/triggerCode/triggerType/workflowRunId) | 1d | orchestrator |
| 2.2 | `SdkTaskContext` 7 个 getter:`bizDate() / prevBizDate() / nextBizDate() / isHoliday() / attemptNo() / triggerCode() / workflowRunId()` | 0.3d | SDK |
| 2.3 | Heartbeat response 加 platform directive:`platformStatus / desiredMaxConcurrent / shouldDrain / pausedTaskTypes / nextHeartbeatHint` | 1.5d | orchestrator |
| 2.4 | SDK 4 态状态机:`NORMAL / DEGRADED / PAUSED / DRAINING`,`dispatcher` + `KafkaTaskConsumer` 联动 | 0.5d | SDK |

### 4.2 dual-rollout 关键纪律

1. **先发布 orchestrator**(填 `schedulingContext` + heartbeat response 加字段)
2. **观察 1 个发布周期**(老 SDK 拿到新字段 ignore,不出问题)
3. **再发布 SDK**(开始用新字段)

### 4.3 PR 拆分

| PR | 模块 | 内容 |
|---|---|---|
| #ORCH-P2-1 | orchestrator | `schedulingContext` 派单时填(`TaskDispatchService` + `BatchDateService` 集成) |
| #ORCH-P2-2 | orchestrator | Heartbeat response 加 platform directive |
| #SDK-P2-1 | SDK | `SdkTaskContext` 7 个 getter + 反序列化 |
| #SDK-P2-2 | SDK | platformStatus 4 态状态机 + dispatcher/consumer 联动 |

### 4.4 DoD

- ✅ console "暂停 workflow" → 30s 内 SDK dispatcher 不再消费新消息
- ✅ 租户 handler 用 `ctx.bizDate()` 写日期分区,跟平台对账日期完全对齐
- ✅ 集成测覆盖 4 态状态机切换路径
- ✅ 老 SDK 升级前后无 break

---

## 5. Phase 3 — 自定义 taskType 注册 ⭐ 价值密度最高

**目标**:租户运营在 console 拖节点 → 自动出表单(不再瞎填)。
**估时**:2-3 周 · **产出**:跨 4 方协作 · **依赖**:Phase 0

> 这是租户接入第一周就会卡的点。**第一个真实租户来之前,M3.1 必须完成**。

### 5.1 M3.1 — 后端通(1.5 周)

| # | 任务 | 工作量 | 模块 |
|---|---|---|---|
| 3.1.1 | `custom_task_type_registry` 表 + Flyway V160 + archive 镜像(CLAUDE.md 红线) | 4h | orchestrator |
| 3.1.2 | `SdkTaskTypeDescriptor` API + `SdkTaskHandler.descriptor()` 可选方法 | 4h | SDK |
| 3.1.3 | SDK `register()` body 加 `taskTypes[].descriptor` 段 | 2h | SDK |
| 3.1.4 | orchestrator register handler upsert 到 registry(`source=SDK_DECLARED`) | 6h | orchestrator |
| 3.1.5 | orchestrator `TaskDispatchService` 派单合并 `defaults + node.parameters + 模板替换` | 1d | orchestrator |
| 3.1.6 | `task.effective_parameters JSONB` 字段(审计快照) | 4h | orchestrator |
| 3.1.7 | `sample-tenant-worker/` 示例 repo(ADR-035 路线图 P1.4 项) | 1d | SDK + docs |

### 5.2 M3.2 — console UI 跟上 🔵 **FE,可延后**(1-1.5 周)

> M3.1 完成即可让租户接入(SDK 上报 descriptor + 平台存 registry + 派单合并参数)。M3.2 是**运营体验增强**,延后到有真实运营需求时再启动。
> 延后期间运营用 console-api 的 OpenAPI / Swagger UI 临时配 workflow_node.parameters。

| # | 任务 | 工作量 | 模块 | 状态 |
|---|---|---|---|---|
| 3.2.1 | console-api `/console/custom-task-types/{tenantId}` GET endpoints | 4h | console-api | ✅ 跟 M3.1 同期做(BE 已经在改) |
| 3.2.2 | FE "我的 taskType" 列表 / 详情页(读 registry) | 1d | FE | 🔵 延后 |
| 3.2.3 | FE 工作流编辑器:拖自定义节点 → 按 schema 渲染表单 | 2d | FE | 🔵 延后 |
| 3.2.4 | FE 模板变量补全(`${bizDate}` / `${trigger.fireTime}` / ...) | 1d | FE | 🔵 延后 |
| 3.2.5 | SDK README 加"敏感凭据走 env,不走 parameters"硬规约 | 1h | docs | ✅ 跟 M3.1 同期 |

### 5.3 PR 拆分

| PR | 模块 | 内容 |
|---|---|---|
| #ORCH-P3-1 | orchestrator | `custom_task_type_registry` 表 + Flyway V160 + archive |
| #ORCH-P3-2 | orchestrator | register upsert + 派单合并 |
| #SDK-P3-1 | SDK | `SdkTaskTypeDescriptor` API + register 上报 |
| #SDK-P3-2 | SDK | sample-tenant-worker 示例 repo |
| #ORCH-P3-3 | orchestrator | `task.effective_parameters` 字段 + 写入逻辑 |
| #API-P3-1 | console-api | `/console/custom-task-types/*` |
| #FE-P3-1 🔵 | FE | "我的 taskType" 页(**延后**) |
| #FE-P3-2 🔵 | FE | 工作流编辑器按 schema 渲染 + 模板补全(**延后**) |

### 5.4 DoD

**M3.1 出口(BE 必达)**:

- ✅ 租户写 handler + descriptor + 部署 → 30s 内 registry 表里能 SELECT 到这个 taskType
- ✅ console-api Swagger 测试 `POST /workflow-nodes` 引用该 taskType → 派单消息 `parameters` 正确合并
- ✅ `effective_parameters` 写入 task 表,SQL 查得到当时合并的参数
- ✅ sample-tenant-worker repo 可被租户 fork 即用
- ✅ M3.1 完成后 SDK 可独立发版

**M3.2 出口(FE,🔵 延后)**:

- 拖节点 → 自动出表单,必填校验生效
- 模板变量补全可用
- 真实运营需求出现时启动

### 5.5 关键纪律

- 敏感凭据(DB 密码 / OAuth secret)**禁止**走 parameters —— README + descriptor 验证警告
- taskType 不兼容升级 → 灰度切流(新 code `_v2`,旧的保留 30 天)

---

## 6. Phase 4 — 长任务可控性

**目标**:5000 万行 import 不再"黑盒"30 分钟。Temporal heartbeat-with-details 等价能力。
**估时**:2 周 · **产出**:1 BE + 1 SDK + UI 跟进 · **依赖**:Phase 2

### 6.1 待办

| # | 任务 | 工作量 | 模块 | 状态 |
|---|---|---|---|---|
| 4.1 | Heartbeat 携带任意 details(合并 progress / checkpoint / lease 续约同 endpoint) | 1.5d | orchestrator + SDK | ✅ |
| 4.2 | `workflow_node.taskTimeout`(startToClose 语义),平台超时强制 cancel | 1d | orchestrator | ✅ |
| 4.3 | Cancel push:heartbeat response 携带 `cancelRequested`(不等 60s 轮询) | 0.5d | orchestrator | ✅ |
| 4.4 | `SdkTaskContext.isCancelled()` + 长循环 check 文档示例 | 0.5d | SDK | ✅ |
| 4.5 | LeaseRenewalScheduler 检测 lease revoked(404/410)→ 标记 task revoked | 0.5d | SDK | ✅ |
| 4.6 | console "任务详情" 页显示最新 heartbeat details(进度 / checkpoint) | 1d | FE | 🔵 延后 |

### 6.2 决策点

**如果租户任务都 < 5 min,可砍掉**:
- ❌ checkpoint 存储
- ❌ progress 详情 UI

**必做(无论任务长短)**:
- ✅ Cancel push(关副作用)
- ✅ 多种 timeout(异常兜底)

### 6.3 PR 拆分

| PR | 模块 | 内容 |
|---|---|---|
| #ORCH-P4-1 | orchestrator | Heartbeat 接收 details + cancel push 字段 |
| #ORCH-P4-2 | orchestrator | `workflow_node.taskTimeout` + 超时 cancel 逻辑 |
| #SDK-P4-1 | SDK | `SdkTaskContext.isCancelled()` + 长循环 check |
| #SDK-P4-2 | SDK | LeaseRenewal 检测 revoked + heartbeat details API |
| #FE-P4-1 🔵 | FE | 任务详情页显示 details(**延后**) |

### 6.4 DoD

**BE 出口(必达)**:

- ✅ SDK 用 heartbeat-with-details API 上报进度,DB 里能 SELECT 到最新 details
- ✅ 手动 cancel(`POST /internal/tasks/{t}/cancel`)→ 5s 内 SDK 停(不等 60s lease 超时)
- ✅ task 配 `taskTimeout=2h` 跑超时 → 平台主动 cancel + SDK 收到信号

**FE 出口(🔵 延后)**:

- console "任务详情" 页每 10s 看到一次进度更新

---

## 7. Phase 5 — 开发体验 / 类型安全(纯 SDK)

**目标**:租户写 handler 不再用 `Map<String, Object>` 瞎转型。
**估时**:2 周 · **依赖**:Phase 3(descriptor schema 已立)

### 7.1 待办

| # | 任务 | 工作量 |
|---|---|---|
| 5.1 | `SdkTypedTaskHandler<I, O>` 泛型基类,框架 Jackson 反序列化 parameters → I,handler 返回 O | 1d |
| 5.2 | `batch-worker-sdk-testkit` 子模块:`FakeBatchPlatform` + 嵌入 EmbeddedKafka | 2-3d |
| 5.3 | `@BatchWorkerTest` JUnit5 扩展 + `TaskDispatchMessage.Builder` 测试夹具 | 1d |
| 5.4 | Worker fingerprint:`register()` 上报 `buildId / sdkVersion / hostName / pid` | 0.3d |
| 5.5 | OTel context propagation:`runtimeAttributes.traceparent` + 可选依赖 | 0.5d |

### 7.2 决策点

- **testkit**:第一个真实租户接入时再做;0 租户时纯投资,ROI 不可见
- **OTel**:租户提需求再做;否则 MDC traceId 凑合用

### 7.3 PR 拆分

| PR | 内容 |
|---|---|
| #SDK-P5-1 | `SdkTypedTaskHandler<I, O>` + Jackson 集成 |
| #SDK-P5-2 | `batch-worker-sdk-testkit` 新子模块 + FakeBatchPlatform |
| #SDK-P5-3 | Worker fingerprint |
| #SDK-P5-4 | OTel context propagation(可选依赖) |

### 7.4 DoD

- ✅ 租户用 testkit 写 handler 集成测,5s 跑完
- ✅ `SdkTypedTaskHandler<ImportRequest, ImportResult>` 编译期类型校验
- ✅ Jaeger 看到完整链路(平台 span → SDK handler span)

---

## 8. Phase 6 — 合规 / 企业级(默认延后)

**目标**:满足 GDPR / 等保 / 金融合规需求。
**估时**:2 周 · **依赖**:Phase 2/3 · **默认状态**:🚫 砍掉(YAGNI)

### 8.1 待办(全部需求驱动)

| # | 任务 | 触发条件 |
|---|---|---|
| 6.1 | Payload codec(加密):`BatchPlatformClientConfig.payloadCodec` | 有租户提合规需求 |
| 6.2 | JSON Schema 校验 task input(platform 派单前) | Phase 3 后运营反馈"还是错配" |
| 6.3 | Rate limit per workflow/taskType(`workflow_definition.taskRateLimit`) | 出过 abuse 事故 |
| 6.4 | 金丝雀 routing by buildId(`workflow_node.targetBuildId`) | 同租户多版本 SDK 并存 |
| 6.5 | 控制 topic `batch.task.control.<tenantId>` push(cancel / shutdown / pause 实时) | 运维窗口需求 |
| 6.6 | `WORKER_SHUTDOWN_REQUESTED` 远程信号 + graceful drain | 同 6.5 |

### 8.2 决策点

默认全砍,**触发条件出现再启动单项**。不预先实施。

---

## 9. Phase 7 — 观测性收尾(可穿插)

**目标**:补 P2 级观测项。
**估时**:0.5 周 · **依赖**:Phase 1

### 9.1 待办

| # | 任务 | 工作量 |
|---|---|---|
| 7.1 | Kafka consumer lag 自检 + 暴露到 `metrics()` | 0.3d |
| 7.2 | CLAIM / REPORT 连续失败 fail-fast 阈值(N 次连续 4xx → 退出) | 0.3d |
| 7.3 | `register()` 失败时进程退出码非 0(K8s 拉起重试) | 0.1d |

可跟任何其他 phase 一起做,不单开 sprint。

---

## 10. 时间表

### 单人全职

| 周 | Phase | 重点 |
|---|---|---|
| W1 | Phase 0 + Phase 7 部分 | 协议基础 + metrics 框架 |
| W2 | Phase 1 | SDK 硬伤(4 PR) |
| W3-4 | Phase 2 | 调度上下文 + dual-rollout |
| W5-7 | Phase 3 M1 | taskType 注册后端通 + sample repo |
| W8-9 | Phase 3 M2 + Phase 4 起步 | console UI + heartbeat details |
| W10 | Phase 4 收尾 + Phase 5 选择性 | 长任务 + 必要 testkit |

**合计 10 周**,Phase 5 选择性 / Phase 6 默认砍。

### 双人并行(推荐)

- **SDK 工程师**:Phase 1 SDK 改造 → Phase 2/3/4 的 SDK 侧 PR
- **Platform 工程师**:Phase 0 协议 → Phase 2/3/4 的 orchestrator 侧 PR
- **FE 工程师**:🔵 **本 plan 暂不安排 FE 工作** —— Phase 3 M3.2 + Phase 4 任务详情页统一延后到 BE 验证通过后再启动

**合计 ~6 周(纯 BE + SDK)**;FE 后续按真实运营需求另起 mini-sprint。

### ⚙️ 每 Phase 并发模式(不是所有 Phase 都拆 agent)

**核心原则**:多 agent overhead 不是免费的(沟通 / rebase / 冲突解决),只在**真低耦合**时拆。

| Phase | 并发模式 | 原因 | Agent 配置 |
|---|---|---|---|
| **Phase 0** | 🟢 单 agent(3d) | 1 个 PR 完成跨模块协议立基,拆 agent overhead > 收益 | Agent-Schema 一人 |
| **Phase 1** | 🟡 单 agent 串行 4 PR(1w) | 纯 SDK,4 PR **频繁碰同一文件**(`BatchPlatformClient` / `TaskDispatcher` 多 PR touched),并行只增 rebase 冲突 | Agent-SDK 一人按顺序 |
| **Phase 2** | 🟢 双 agent 严格串行(1.5w) | dual-rollout:ORCH 先 → 等 2 周观察 → SDK 跟。**强制串行,不能并行** | Agent-ORCH → Agent-SDK 接力 |
| **Phase 3** ⭐ | 🟢🟢 **多 agent 全开**(2-3w) | 跨 4 方 + 文件低耦合,**最大收益**。M3.1 后端 Schema + ORCH + SDK + sample 4 路并行 | 4 个 agent 同时:Agent-Schema(Flyway)+ Agent-ORCH(派单 / register upsert)+ Agent-SDK(descriptor API)+ Agent-Docs(sample repo) |
| **Phase 4** | 🟢 双 agent 严格串行(2w) | 同 Phase 2,dual-rollout 模式 | Agent-ORCH → Agent-SDK |
| **Phase 5** | 🟢🟢 **多 agent 全并行**(2w) | P5-1/2/3/4 四件事(typed handler / testkit / fingerprint / OTel)**互不依赖** | 4 个 Agent-SDK 实例同时做不同 PR |
| **Phase 6** | 🟡 按需,单 agent | 触发条件出现才做,通常 1-2 项 | — |
| **Phase 7** | 🟢 单 agent 穿插(0.5w) | 小项,塞任意 phase 末尾 | 任何空 agent 顺手 |

#### 拆 agent 的决策信号

| 信号 | 拆 / 不拆 |
|---|---|
| 工作量 < 3 天 | ❌ 不拆 |
| 同文件多 PR 触碰 | ❌ 不拆,串行做 |
| 跨模块协议依赖(dual-rollout) | ✅ 拆,但**严格串行**(不是并行) |
| 跨模块文件独立(SDK / orch / API) | ✅ 拆,可并行 |
| 同模块新增独立功能(P5 四件事) | ✅ 拆,可并行 |
| 文档 / 测试 / sample | ✅ 拆,跟代码并行 |

#### 实际并发收益分布

- **Phase 3**:2-3w → 1w 内(并发节省 ~60%)
- **Phase 5**:2w → 1w 内(并发节省 ~50%)
- **其他 Phase**:并发收益 < 20%,串行更稳

**这就是为什么本 plan 不无脑"全程多 agent"** —— 错配会让 Phase 0/1/2 反慢(rebase 冲突 + 沟通成本)。

### FE 延后期的 workaround

| 缺 FE 的能力 | 临时替代 |
|---|---|
| "我的 taskType" 列表页 | console-api Swagger UI / SQL 查 `custom_task_type_registry` |
| 工作流编辑器按 schema 渲染表单 | OpenAPI / Swagger UI / 直接 POST 派单 |
| 任务详情页进度展示 | `task` 表 + `task_progress` 表 SQL 查最新 details |
| 模板变量补全 | 文档列出可用变量,人工填写 |

延后**不影响 SDK 发版**,但租户运营体验会差。第一个真实运营提需求时再启动 FE 工作(预计 1-2 周)。

---

## 11. 跨 phase 风险与缓解

| 风险 | Phase | 缓解 |
|---|---|---|
| 协议改动破坏在用租户 | 0/2/3/4 | dual-rollout 纪律 + `schemaVersion` 兼容窗口 |
| 跨 4 方协作 stall | 3 | M1/M2 拆分,UI 没好不阻塞 SDK 发布 |
| scope 蔓延 | 全部 | 新发现的事丢下个 phase,不塞当前 |
| 文档变 stale | 全部 | 每周五 1h 同步 doc + git log 对账;完成项划线不删 |
| "全绿"骗自己 | 全部 | 每 phase 出口对照 [`sdk-industry-benchmark`](../design/sdk-industry-benchmark.md) §2 矩阵核对(memory: `feedback_audit_vs_architecture_review`) |
| stale cache 漏报真错 | 全部 | push 前 `mvn clean compile`(memory: `feedback_clean_before_push`) |
| 测试覆盖率刷指标 | 全部 | 不为指标写测试(memory: `feedback_coverage_principle`) |
| 并行构建 target 冲突 | 全部 | 隔离 clean 复验(memory: `feedback_mvn_build_gotchas`) |
| HEAD 漂移 | 全部 | commit/push 前 `git branch + log + status`(memory: `feedback_verify_branch_state`) |

---

## 12. 决策点(随时回看本节)

### ✅ 必做(无悬念)

Phase 0 / Phase 1 / Phase 2 / Phase 3 —— 不做这些,租户接入反复出事。

### ⚠️ 看真实需求做

- Phase 4 完整版 —— 任务都 < 5min 时可砍 checkpoint / progress,保留 cancel + timeout
- Phase 5 testkit —— 第一个真实租户接入时再做
- Phase 5 OTel —— 租户提需求再做

### 🔵 FE 工作统一延后(独立 mini-sprint)

| 项 | 所属 Phase | 估时 | 触发条件 |
|---|---|---|---|
| "我的 taskType" 列表页 | P3 M3.2 | 1d | 第一个租户运营提需求 |
| 工作流编辑器按 schema 渲染表单 | P3 M3.2 | 2d | 同上 |
| 模板变量补全 | P3 M3.2 | 1d | 同上 |
| 任务详情页 heartbeat details | P4 | 1d | 长任务真实跑起来 + 运维提需求 |

**合计 ~1 周**,作为独立 mini-sprint 在 BE 全部完成后启动。延后期租户用 Swagger UI / SQL 临时替代(详见 §10 FE 延后期的 workaround)。

### 🚫 暂砍掉(YAGNI)

- Phase 6 全部 6 项 —— 触发条件出现再启动单项
- 金丝雀 routing by buildId
- Rate limit per workflow/taskType
- JSON Schema 校验(等 Phase 3 schema 立起来后看反馈)
- Payload codec
- 控制 topic push

---

## 13. 启动检查清单(Phase 0 启动前)

- [ ] 本 plan 已 review 通过
- [ ] 确认资源(单人 / 双人)
- [ ] 创建 milestone `sdk-h2-2026`(GitHub / Linear)
- [ ] 创建 7 个 phase 的 tracking issue,关联本 plan
- [ ] 第一个 PR 模板:`#SDK-P0-1 schemaVersion + wire DTO records`
- [ ] 跟 ADR-035 owner 对齐:范围边界不越界,本 plan 不取代 ADR

---

## 14. 维护

- **每个 PR merge 后**:对应 phase 待办划线 + 日期 + PR 号
- **每周五 1h**:本 plan + 4 份 SDK doc 同步;新发现的事归类到对应 phase
- **每 phase 收官**:写 5 行 changelog 到 `docs/changelog.md`(日期倒序)
- **中期 review**:`2026-08-31` / `2026-10-31` / `2026-12-31` 三次,重新评估 Phase 4/5/6 是否启动

---

## 15. 多 Agent 并发执行策略

### 15.1 为什么用多 agent

本 plan 28 个待办、跨 4 个模块(SDK / orchestrator / console-api / FE),单 agent 串行做要 10 周。**用多 agent 按"文件 owner 不交叉"分发**,可以把 BE 部分压到 4 周内完成。

### 15.2 Agent 分组(按文件 owner 物理隔离,避免 merge 冲突)

| Agent | 模块范围 | 不碰的文件 |
|---|---|---|
| **Agent-SDK** | `batch-worker-sdk/**` | orchestrator / console-api / FE 全部 |
| **Agent-ORCH** | `batch-orchestrator/**` + `batch-common/**`(协议契约) | SDK / console-api / FE |
| **Agent-API** | `batch-console-api/**` + OpenAPI yaml | SDK / orchestrator 核心 / FE |
| **Agent-Schema** | `batch-orchestrator/src/main/resources/db/migration/**`(Flyway)+ archive 镜像 | 任何 java |
| **Agent-Docs** | `docs/**`(本 plan + 4 份 SDK doc + ADR) | 任何代码 |

**原则**:agent 之间**只读对方代码**(参考接口),**禁止互改**。需要跨模块改的(比如新增协议字段),**串行做**:Agent-ORCH 先改 DTO + merge → Agent-SDK 拉最新后跟改。

### 15.3 启动前必做(每个 agent 每次启动)

```bash
# 1. 同步主干
git fetch origin main
git checkout main
git pull --rebase origin main          # 拉最新,memory: feedback_verify_branch_state

# 2. 确认起点
git log -1 --oneline                    # 记录基线 commit
git status                              # 必须 clean

# 3. 创建 feature 分支(每个 PR 一个分支)
git checkout -b feature/sdk-p0-schemaversion

# 4. 清构建,避免 stale cache(memory: feedback_clean_before_push)
mvn clean compile -pl <自己模块>

# 5. 跑现有测试基线
mvn test -pl <自己模块>                 # 必须全绿才开始改
```

**禁止**:基于过期分支开发 / 不 clean 直接 push / 多个 agent 共用同一分支。

### 15.4 PR 冲突预防矩阵

按"哪些文件可被多 agent 并行改 vs 必须串行"明确:

| 文件类型 | 多 agent 并行? | 备注 |
|---|---|---|
| `batch-worker-sdk/src/main/java/**` | 不同子目录可并行 | 同文件必须 sequence |
| `batch-orchestrator/.../TaskController.java` | ❌ 必须串行 | 协议入口,改动集中 |
| `batch-orchestrator/.../TaskDispatchService.java` | ❌ 必须串行 | 派单核心,改动集中 |
| Flyway V*.sql migration | ❌ 必须串行 | 版本号冲突 |
| `docs/api/console-api.openapi.yaml` | ❌ 必须串行 | YAML merge 难调和 |
| `docs/**/*.md` | ✅ 不同文件并行 | section 锚点不重叠 |
| `pom.xml` 父 / 子 | ❌ 必须串行 | 版本号 / 依赖列表合并易乱 |
| 测试文件(各自模块) | ✅ 不同文件并行 | 跨模块测放 batch-orchestrator |

### 15.5 PR 合并节奏(协议先,使用方后)

跨模块协议变更走 **三步合并**:

```
T0: Agent-ORCH 改 DTO 加新字段(可选 / null-safe) → 单独 PR
T0 → T0+1d: 合 main + 部署 RC,观察 1 个发布周期
T1: Agent-SDK 用新字段 → 单独 PR
T1 → T1+1d: 合 main + 部署 SDK 新版
T2: Agent-API / FE 跟上(如需) → 单独 PR
```

**不要**:同一 PR 同时改协议端 + 使用端(回滚困难 + review 难)。

### 15.6 整体验证流程(每个 phase 出口)

每个 phase 收官前,跑**整合验证**(不是单模块测试):

```bash
# 1. 全仓 clean + 全模块 build(memory: feedback_mvn_build_gotchas)
mvn clean install -DskipTests              # 编译期问题先暴露
mvn verify                                 # 跑所有单测 + IT(failsafe)

# 2. 跑 SDK 契约测试(从 Phase 0 起就要绿)
mvn -pl batch-orchestrator test -Dtest=SdkWireContractTest

# 3. 跑 SDK 自身测试
mvn -pl batch-worker-sdk test

# 4. 跑 e2e 冒烟(如有覆盖本 phase 改动的)
mvn -pl e2e-tests verify -Dgroups=smoke    # e2e 用 verify 不用 install

# 5. ArchUnit 守护测(不能破坏架构约束)
mvn -pl batch-orchestrator test -Dtest=*ArchTest
```

**任何一步红 → phase 不能 close,回查最近 PR**。

### 15.7 沟通节奏

- **每 PR merge** → 在 plan 对应 phase 表里划线 + 加日期 + PR 号
- **每天一次** → 同步各 agent 进度(写在 `docs/plans/sdk-roadmap-2026-h2-progress.md`,日期倒序)
- **每个 phase 出口** → 全 agent review,签字 close,然后才能启动下一 phase
- **跨模块卡住** → 立刻拉同步会议,不允许"我等你你等我"超过 4 小时

### 15.8 PR 合并顺序与冲突防护(严格执行)

#### A. 依赖图(箭头 = 必须先 merge)

```
                        #SDK-P0-1 (schemaVersion + wire DTO + 契约测试)
                        ┃ 阻塞全部后续 PR
                        ▼
        ┌───────────────┴──────────────────────────┐
        ▼                                          ▼
  Phase 1 (纯 SDK 链)                       Phase 2 协议层 (orch 先)
  #SDK-P1-1 stop + rebalance               #ORCH-P2-1 schedulingContext
        ▼                                          ▼
  #SDK-P1-2 CLAIM fail-fast                #ORCH-P2-2 heartbeat directive
        ▼                                          ▼  (跨 1 个发布周期)
  #SDK-P1-3 HeartbeatScheduler + IOEx      #SDK-P2-1 SdkTaskContext getters
        ▼                                          ▼
  #SDK-P1-4 metrics + isHealthy            #SDK-P2-2 4 态状态机
                                                   ▼
                            Phase 3 M3.1 (schema → upsert → effective → SDK 上报)
                              #ORCH-P3-1 registry 表 + Flyway V160
                                          ▼
                              #ORCH-P3-2 register upsert + dispatch merge
                                          ▼
                              #ORCH-P3-3 effective_parameters 字段
                                          ▼
                              #SDK-P3-1 SdkTaskTypeDescriptor + register 上报
                                          ▼
                              #SDK-P3-2 sample-tenant-worker
                                          ▼
                              #API-P3-1 console-api endpoints
                                          ▼
                            Phase 4
                              #ORCH-P4-1 heartbeat details + cancel push
                                          ▼
                              #ORCH-P4-2 taskTimeout
                                          ▼
                              #SDK-P4-1 isCancelled
                                          ▼
                              #SDK-P4-2 LeaseRenewal + heartbeat details
                                          ▼
                            Phase 5(纯 SDK,可全并行)
                              #SDK-P5-1 / 2 / 3 / 4 互不依赖
```

#### B. 文件级冲突防护(同文件被多 PR 触碰,必须串行)

| 文件 | 触碰的 PR | 串行顺序 | 冲突防护 |
|---|---|---|---|
| `BatchPlatformClient.java` | P1-1 (stop), P1-4 (metrics) | P1-1 → P1-4 | P1-4 启动前 `git pull --rebase` + 处理冲突 |
| `TaskDispatcher.java` | P1-1 (drain), P1-2 (fail-fast), P4-1 (isCancelled) | P1-1 → P1-2 → P4-1 | 每次启动 rebase |
| `HeartbeatScheduler.java` | P1-3 (fixed-delay), P2-2 (state machine) | P1-3 → P2-2 | 同上 |
| `SdkTaskContext.java` | P2-1 (getters), P3-1 (descriptor 字段), P4-1 (isCancelled) | P2-1 → P3-1 → P4-1 | 同上 |
| `PlatformHttpClient.java` | P1-3 (IOEx msg), P3-1 (register payload), P4-2 (heartbeat details) | P1-3 → P3-1 → P4-2 | 同上 |
| `TaskDispatchMessage.java` | **P0** (schemaVersion), P2-1 (schedulingContext), P4-1 (cancel flag) | P0 → P2-1 → P4-1 | 同上 |
| `TaskController.java` (orch) | P2-1, P3-2, P4-1 | P2-1 → P3-2 → P4-1 | 同上 |
| `TaskDispatchService.java` (orch) | P2-1, P3-2, P3-3 | P2-1 → P3-2 → P3-3 | 同上 |
| `WorkerController.java` (orch) | P2-2 (heartbeat response), P3-2 (register descriptor) | P2-2 → P3-2 | 同上 |
| Flyway `V*.sql` | **P3-1** (V160), **P3-3** (V161 effective_parameters), **P4-2** (V162 taskTimeout) | V160 → V161 → V162 | 版本号串行分配,**禁同号** |
| `OpenAPI yaml` | 几乎所有 BE PR 都改 | 全串行 | YAML merge 难,只允许一个 PR 同时改 yaml |
| `pom.xml` (子模块新增) | P5-2 (testkit 新模块加进父 pom) | 单独一个 PR,不混改 | — |
| `CLAUDE.md` / `coding-conventions.md` | 任何架构红线变更 | 全串行 | 不允许并行改根 doc |

#### C. 可并行的"绿色区"(同 phase 内可同时开工)

| Phase | 可并行 PR 组 |
|---|---|
| Phase 1 | #SDK-P1-3(`HeartbeatScheduler`)+ #SDK-P1-4 (`metrics()` 新增方法)—— 不同文件,可同时改 |
| Phase 3 | #SDK-P3-2 sample-tenant-worker(新 repo / 模块,跟主代码无冲突)可跟 ORCH-P3-x 并行 |
| Phase 4 | #ORCH-P4-2 taskTimeout(独立字段)可跟 #ORCH-P4-1 并行 |
| Phase 5 | P5-1 / P5-2 / P5-3 / P5-4 全部纯 SDK 新增,**全并行** |
| Phase 7 | P7-x 都是小项,可穿插任何 phase 末尾 |

#### D. 每个 PR 启动前的硬规约(每次都要跑)

```bash
# 1. 拉最新主干(memory: feedback_verify_branch_state)
git checkout main && git pull --rebase origin main

# 2. 看是否有依赖 PR 未 merge(查 §15.8.A 依赖图)
gh pr list --state merged --search "in:title #SDK-P0-1"   # 必须 merged

# 3. 创分支
git checkout -b feature/sdk-px-yy

# 4. clean 编译(memory: feedback_clean_before_push)
mvn clean compile -pl <自己模块>

# 5. 跑基线测试
mvn test -pl <自己模块>

# 6. 开始改代码
```

#### E. 每次 push 前的硬规约

```bash
# 1. rebase 最新 main(避免落后冲突)
git fetch origin main
git rebase origin/main

# 2. 若有冲突 → 手工解 → 重新 clean
git rebase --continue
mvn clean compile -pl <自己模块>   # 必跑,memory: feedback_clean_before_push

# 3. 跑全测试
mvn verify -pl <自己模块>

# 4. push(memory: feedback_verify_branch_state — 用分支名直推,不动用户当前分支)
git push origin feature/sdk-px-yy
```

#### F. PR 合并的硬规约

| 规约 | 理由 |
|---|---|
| **一次只允许一个 PR 处于"等 merge"状态触碰同一文件** | 第二个 PR 必须等第一个 merge 才能开始 |
| **触碰协议 DTO 的 PR 必须先 merge,使用端 PR 后开** | dual-rollout 纪律 |
| **Flyway 版本号在 PR 描述里申明**(如"占用 V160") | 防止双人同时改 V160 |
| **合并前必跑 `mvn clean install -DskipTests` + `mvn verify`** | memory: `feedback_mvn_build_gotchas` |
| **跨模块 PR 不允许**(SDK + orch 同一 PR) | 回滚困难 + review 难 |
| **每个 PR ≤ 600 LoC** | review 质量 |

#### G. 冲突处理 SOP

```
情况 1:rebase 时冲突
  → 看冲突文件是不是"同一文件被多 PR 触碰"(查 §15.8.B)
  → 是 → 应主动等待前 PR merge,不该硬开
  → 否 → 普通手工 merge

情况 2:push 时被 reject(主干已变)
  → git fetch + git rebase origin/main
  → 重新跑 clean + test

情况 3:CI fail
  → 先看是不是"stale cache" 导致(memory: feedback_mvn_build_gotchas)
  → 本地 mvn clean install 复现
  → 真错才 fix,不要瞎改

情况 4:跨 phase 依赖 PR 延误
  → 不允许"跳过依赖直接开后续 PR"
  → 改做并行绿色区的 PR(查 §15.8.C)
  → 或转测试 / 文档工作
```

#### H. 一句话执行总结

> **协议先,使用方后;同文件串行,跨文件并行;每 PR push 前 clean + rebase + verify;Flyway 版本号串行分配。**

#### I. ⚠️ 高频被门禁拦截的两类(每个 PR 提交前必自查)

**这两类是历史上拦下最多 PR 的门禁,push 前必须先本地过一遍**。

##### I.1 Java 编码反例(`coding-conventions.md` 10 条)

**自查清单**(从 CLAUDE.md「Java 编码细则」搬来):

| # | 规则 | 高频反例 | 自查命令 |
|---|---|---|---|
| 1 | **禁全限定类名(FQN)**,必走 `import` | `java.util.concurrent.TimeUnit.SECONDS` 直接写在代码里 | `grep -rn "java\.\(util\|net\|io\|time\)\." src/main --include="*.java" \| grep -v import \| grep "\."` |
| 2 | 方法参数 **≤ 6**;≥ 7 必须封装 Command/Param 类 | `void f(a,b,c,d,e,f,g)` | 人工 review,看新方法签名 |
| 3 | DI **只用构造器**(`@RequiredArgsConstructor`);**禁** `@Autowired` field 注入 | `@Autowired private Foo foo;` | `grep -rn "@Autowired" src/main --include="*.java"` —— 只允许 `@Lazy @Autowired private SelfType self;` 自调用 workaround |
| 4 | `@Transactional` **只放 Service 公共方法** | `@Transactional` 在 Controller / Mapper | `grep -rn "@Transactional" src/main/java/.../controller/` —— 应该 0 命中 |
| 5 | 业务异常一律 `BizException.of(ResultCode.X, "error.<scope>.<reason>", args...)` | `new BizException(code, literal)` / `throw new RuntimeException(...)` | `grep -rn "new BizException(" src/main --include="*.java"` |
| 6 | Controller 返回值一律 `CommonResponse<T>`,**禁**裸返 DTO | `return user;` | `grep -rn "public.*Controller" src/main --include="*.java"` 后人工核 |
| 7 | 日志**用占位符**,不用拼接 | `log.info("user=" + u)` | `grep -rnE 'log\.\w+\([^"]*"[^"]*"\s*\+' src/main --include="*.java"` |
| 8 | `@Builder` 加到普通 class 必须配 `@NoArgsConstructor` + `@AllArgsConstructor`(或 `@Tolerate`) | 裸 `@Builder` + 隐式空参 class | `grep -B2 "@Builder" src/main --include="*.java" \| grep -A2 "class "` 人工核 |
| 9 | if-chain / switch **≥ 3 分支**必须改 `Map<String, Handler>` 路由表 | 4 个 `else if` 散排 | 人工 review |
| 10 | 集合返回 `List.of()` / `Map.of()` 不可变,**禁**返 `new ArrayList<>(...)` 后又 add | `return new ArrayList<>(list)` | `grep -rn "new ArrayList<>" src/main --include="*.java"` 人工核 |

**红线**(违反 = 直接 reject):

- ❌ Spring Data JDBC entity 加 `@Builder`
- ❌ **重命名任何字段**(破坏 mybatis xml `#{q.xxx}` / canonical constructor)
- ❌ `ZoneId.systemDefault()`(用 `BatchTimezoneProvider`)
- ❌ `Charset.forName("UTF-8")` / 字面量(用 `StandardCharsets.UTF_8`)

**SDK 模块的额外注意点**(`batch-worker-sdk` 不依赖 Spring,但仍守这 10 条):

- 规约 #3 在 SDK 里改成"Builder 模式注入,禁 setter 注入"
- 规约 #4 在 SDK 里 N/A(无 `@Transactional`)
- 规约 #5 在 SDK 里改成 `SdkTaskResult.fail(Throwable)`,**不用** `BizException`
- 规约 #6 在 SDK 里 N/A(无 Controller)
- 其他 7 条同样适用

**每个 PR push 前必跑**:

```bash
# 一键自查脚本(放在 batch-orchestrator/scripts/check-antipatterns.sh)
mvn checkstyle:check -pl <自己模块>
mvn pmd:check -pl <自己模块>
mvn spotbugs:check -pl <自己模块>
```

##### I.2 API 文档对齐(`pr-gate` 重点拦截项)

**触发条件**(CLAUDE.md「API 文档同步」段):
改 `batch-console-api` 控制层(新增 / 删除 / 改 path / 改请求响应字段)**必须同 PR 更新**:

- `docs/api/console-api.openapi.yaml`(补 path + schema,无悬空 `$ref`)
- `docs/api/console-api-protocol.md`(Changelog 表追加日期 + 摘要)

**本 plan 涉及 API 变更的 PR**(必须同步 doc):

| PR | controller 变更 | 必同步的 docs |
|---|---|---|
| #API-P3-1 | 新增 `/console/custom-task-types/*` GET endpoints(Phase 3 M3.2) | `console-api.openapi.yaml` + `console-api-protocol.md` |
| #ORCH-P2-1 | `TaskController` register / heartbeat response 加字段 | 若 SDK 走 `/internal/*`,看是否在 OpenAPI 范围 |
| #ORCH-P3-2 | `WorkerController.register` 接收 descriptor | 同上 |
| #ORCH-P4-1 | `TaskController.heartbeat` 接收 details + 返回 cancel push | 同上 |

**自查清单**(push 前):

```bash
# 1. 列出本 PR 改了哪些 controller
git diff --name-only main..HEAD | grep -E "Controller\.java$"

# 2. 看是否同时改了 OpenAPI yaml
git diff --name-only main..HEAD | grep -E "openapi\.yaml$"

# 3. 若 controller 改了但 yaml 没改 → ❌ 立刻补,否则 pr-gate fail
# 4. 看 protocol.md 是否有日期倒序的 Changelog 项
git diff main..HEAD docs/api/console-api-protocol.md | head -30
```

**`/internal/*` 是否在 OpenAPI 范围?**:历史上有过歧义。**本 plan 的硬约定**:
- `batch-console-api` 的 controller 必入 OpenAPI(给 FE / 客户)
- `batch-orchestrator` 的 `/internal/*` controller **本来不入** OpenAPI(内部协议)
- **但** SDK 用 `/internal/*`,SDK 算"准客户" → 本 plan 决定**也入 OpenAPI**,放 `docs/api/orchestrator-internal.openapi.yaml`(新建)
- Phase 0 配合 wire DTO 一起立这个 yaml

##### I.3 一键 self-check 脚本(已实现 ✅ 2026-05-31)

脚本位置:[`scripts/local/pre-push-sdk-checks.sh`](../../scripts/local/pre-push-sdk-checks.sh)

**已集成到** `.git/hooks/pre-push`(2026-05-31 加):

```bash
# 守护 1:禁推保护分支(已有)
# 守护 2:SDK 路线图自查(新加)— 调用 scripts/local/pre-push-sdk-checks.sh
# 守护 3:Auto-create PR(已有)
```

**自查内容**:

1. **Java 编码反例**(扫"本 PR 真新增的行",不扫历史):
   - 规约 #1 FQN(必走 import)
   - 规约 #3 @Autowired field 注入(只允许构造器)
   - 规约 #4 @Transactional 在 Controller/Mapper
   - 规约 #5 throw new RuntimeException(test 跳过)
   - 规约 #7 日志拼接
   - 红线:ZoneId.systemDefault / Charset.forName

2. **API 文档对齐**:
   - `batch-console-api` controller 改了 → 强制 `console-api.openapi.yaml` + `console-api-protocol.md` 同步
   - `batch-orchestrator` `/internal/*` controller 改了 → 提示(P0 立 yaml 后强制)

3. **Flyway 版本号唯一性**:
   - 检测本 PR 新增的 `V*__*.sql` 是否跟已有冲突
   - 批表 DDL 检测 → 提醒补 archive 镜像

4. **clean compile**(默认开,可 `--skip-build` 跳过):
   - 识别改动模块,只 build 这些(快)
   - memory: `feedback_clean_before_push`

**用法**:

```bash
# 自动:每次 git push 触发(已集成 pre-push hook)
git push origin feature/xxx

# 手动:开发中随时跑
bash scripts/local/pre-push-sdk-checks.sh
bash scripts/local/pre-push-sdk-checks.sh --skip-build     # 快速预检
bash scripts/local/pre-push-sdk-checks.sh --base origin/dev # 自定义 base

# 紧急绕过(用前三思)
SKIP_SDK_CHECKS=1 git push
```

**实测结果**(2026-05-31 在 main 跑):
- 44 个 Java 文件 / 3109 行新增 → 30s 内完成扫描
- 仅扫"本 PR 新增行",无假阳性历史代码误报
- 真发现 SDK `ShellAtomicHandler:131` 一个 `throw new RuntimeException` 真违规

这两类是历史上**最频繁被 CI pr-gate 拦下**的(memory: `feedback_mvn_build_gotchas` 同类教训),**push 前本地过一遍** > 等 CI 来扇耳光。

### 15.9 单对话连续执行模式(自动跑完 + checkpoint)

> 适用场景:你**不想盯进度**,希望 AI agent 在一个对话里**连续做完所有 phase**,只在关键节点停下来让你 review + merge PR。

#### A. 执行流(无人值守 + checkpoint 制)

```
agent 在一个对话里顺序执行:

Phase 0 完成 → push PR → 不等你 merge,直接开下一个
       ↓
Phase 1 完成 → push PR(基于 Phase 0 分支,堆叠 PR)
       ↓
Phase 2 完成 → push PR
       ↓
... 一直到 checkpoint 才停下报告

每完成一个 phase,agent 自动:
  - plan doc 对应待办划线 + 加日期 + PR 号
  - 简短一句"Phase N 完成,PR #X,继续"
  - 不等你回应,直接开下一个
```

#### B. 处理"看似阻塞"的事(agent 跳过 / 真停)

| 情况 | agent 行为 |
|---|---|
| **dual-rollout 2 周等待** | **跳过** —— 开发阶段不真等,在 PR 描述里标"实际部署时观察 2 周" |
| **PR 没 merge 下一 phase 怎么开** | 堆叠 PR(base = 上一个 PR 的 feature 分支),GitHub 支持 |
| **测试跑得慢** | 每 phase 只跑自己模块测试,不跑全仓 verify(memory: `feedback_p1a_no_full_test`) |
| **CI 还没反馈** | 不等 CI,本地 `mvn clean compile + test -pl` 绿就 push |
| **架构歧义 / 决策点 #12** | **停** —— 报告 + 等你回 |
| **真 bug / 真编译错** | **停** —— debug 或上报 |

#### C. 3 个强制 Checkpoint(agent 主动停)

| Checkpoint | 时点 | 累积 PR 数 | 你要做什么 |
|---|---|---|---|
| **CP-1** | Phase 0 + 1 完成 | ~5 PR | review 协议基础 + SDK 硬伤修复 |
| **CP-2** | Phase 2 + 3 完成 | ~13 PR | review 调度上下文 + taskType 注册 |
| **CP-3** | Phase 4 + 5 + 7 完成 | ~20 PR | review 长任务 + 开发体验,全收尾 |

每个 checkpoint **不超过 8 小时**(agent 累积工作 + 文件读写量大概上限),你回来:**review 一批 PR + merge + 说"继续"** → agent 接 CP-N+1。

#### D. agent 启动话术

```
启动,自动连续执行所有 phase,checkpoint 时停下
```

agent 立刻:

1. 忽略 PR #190 等待 merge 的事(基于当前状态起跑)
2. 拉新分支 `feature/sdk-phase-0` 开 Phase 0
3. Phase 0 完 push PR → **不停**,开 Phase 1 分支 `feature/sdk-phase-1`(base = phase-0)
4. 一直堆到 **CP-1** 报告
5. 你回来 merge / 反馈,说"继续",agent 接 CP-2

#### E. 现实限制(诚实期望管理)

| 限制 | 影响 |
|---|---|
| **上下文压缩** | 7 phase 累积代码 + 测试输出,大概 Phase 4-5 之间触发压缩,可能丢细节 |
| **真 bug 时** | agent 停下诊断,不硬干 |
| **PR review 是你的活** | agent 只 push 不 merge,异步 review |
| **决策点遇到歧义** | agent 停下问你,但大部分已在「决策记录」定 |
| **不真等 dual-rollout** | dev 阶段跳过 2 周等待,部署阶段才真等(部署计划另算) |

#### F. agent 能做 / 不能做

| ✅ 能做 | ❌ 不能做 |
|---|---|
| 代码 + 测试 + push + PR 自动化 | 替你 merge PR(sign-off 权限) |
| plan doc 同步划线(进度可追溯) | 替你做 W8 决策(看真实数据) |
| 按「决策记录」已定的事执行 | 架构歧义自己决定 |
| 遇到块停下报告,不硬干 | 真实 6 周跑完(物理 dual-rollout / 生产观察必须真等) |
| pre-push hook 自检每次都跑 | — |

#### G. 跟其他执行模式对比

| 模式 | 你的工作量 | 总墙钟 | 风险 |
|---|---|---|---|
| 完全手动(每 phase 开新对话) | 高,每 phase 都陪跑 | 6 周 | 低 |
| 多窗口手动并行(§15.2) | 中,review × N 窗口 | 4 周 | 中 |
| **本模式(单对话连续 + checkpoint)** | **低,3 次 checkpoint review** | **1-2 天 dev + 部署观察 2-4 周** | **中**(上下文压缩) |
| /schedule routines | 中低,merge 时机分散 | 4-5 周 | 中(routine 不擅长意外) |
| /loop 单对话自动 | 中,对话要一直开 | 4 周 | 高(对话挂 = 中断) |

#### H. 适用 / 不适用

**✅ 适用**:
- 你想最小化交互,只在 PR review 时介入
- dev 阶段集中冲刺(deployment 验证另开窗口)
- 已经按「决策记录」定好所有原则性问题
- 你信任 agent 在 7 phase 内不引入大架构错误

**❌ 不适用**:
- 你想细看每一步实现细节
- 真实租户已经在跑,需要严格 dual-rollout
- 多个团队 review 不同 phase
- 高度不确定的探索式开发(本 plan 不属于,plan 已成型)

---

## 16. 验收标准(Acceptance Criteria)

每个 phase 出口的客观验证标准。**不达标 phase 不能 close**。

### 16.1 Phase 0 — 协议演进基础

| # | 验收项 | 验证方式 |
|---|---|---|
| AC-0.1 | `TaskDispatchMessage` 包含 `schemaVersion` 字段,默认 `"v1"` | `cat TaskDispatchMessage.java | grep schemaVersion` |
| AC-0.2 | SDK 收到未知 major 版本 → reject + 日志 ERROR | 单测 `SchemaVersionRejectTest` 绿 |
| AC-0.3 | SDK 5 个 wire DTO records 全部存在(`sdk/wire/`) | `find batch-worker-sdk -name "*Request.java"` 数 5 个 |
| AC-0.4 | `SdkWireContractTest` 存在且初次跑绿 | `mvn -pl batch-orchestrator test -Dtest=SdkWireContractTest` |
| AC-0.5 | 故意改一个 DTO 字段名 → 契约测试 fail | 手工 demo 一次,截图归档 |
| AC-0.6 | 156 现有测试全绿 + 5 新增 | `mvn test` 全模块 |

### 16.2 Phase 1 — SDK 自身硬伤修复

| # | 验收项 | 验证方式 |
|---|---|---|
| AC-1.1 | `stop()` 顺序符合"Kafka 先 → drain → heartbeat/lease 最后 → deactivate" | 集成测 `BatchPlatformClientStopOrderTest` 绿 |
| AC-1.2 | 模拟 401 → SDK 5s 内退出 + 退出码非 0 | E2E 脚本 `verify-401-fail-fast.sh` |
| AC-1.3 | 模拟 rebalance + paused 状态 → 正确恢复(`MockConsumer`) | 单测 `RebalancePausedRecoveryTest` |
| AC-1.4 | `HeartbeatScheduler` 调用 `scheduleWithFixedDelay`(不是 AtFixedRate) | `grep scheduleWithFixedDelay HeartbeatScheduler.java` |
| AC-1.5 | `IOException` message 无 errBody 明文 | 单测断言 message ≤ 80 字符 |
| AC-1.6 | `metrics()` 返回非 null,含 6 字段(processed/failed/claimFailures/reportFailures/inFlightCount/lastHeartbeatAt) | 单测 + 实际启动 demo |
| AC-1.7 | `isHealthy()` 在 consumer 死时返 false | 单测注入异常验证 |
| AC-1.8 | SDK jar < 2.1 MB | `ls -la target/batch-worker-sdk-*.jar` |

### 16.3 Phase 2 — 调度上下文 + 双向通道

| # | 验收项 | 验证方式 |
|---|---|---|
| AC-2.1 | `TaskDispatchMessage.schedulingContext` 含 7 字段(bizDate/prev/next/isHoliday/attemptNo/triggerCode/triggerType/workflowRunId) | Jackson 反序列化测试 |
| AC-2.2 | SDK 端 `SdkTaskContext` 暴露 7 个 getter | 单测覆盖所有 getter |
| AC-2.3 | 平台日历 `2026-06-01` 是节假日 → 派单时 `isHoliday=true` | 集成测 `BizDateInjectionTest` |
| AC-2.4 | Heartbeat response 含 `platformStatus / desiredMaxConcurrent / pausedTaskTypes` | OpenAPI / 实测 |
| AC-2.5 | console 设 `workflow.status=PAUSED` → 30s 内 SDK dispatcher 不消费新消息 | E2E `verify-pause-propagation.sh` |
| AC-2.6 | `platformStatus=DEGRADED + desiredMaxConcurrent=2` → SDK 4 个 worker 降到 2 | 集成测 |
| AC-2.7 | dual-rollout:老 SDK 升级前后无 break | 用旧 jar 跑当前平台,7 天观察 |

### 16.4 Phase 3 M3.1 — 自定义 taskType 注册(后端通)

| # | 验收项 | 验证方式 |
|---|---|---|
| AC-3.1 | `custom_task_type_registry` 表存在 + archive 镜像表存在 | `\d custom_task_type_registry` |
| AC-3.2 | Flyway V160 applied + `archive_drift_check` 启动期不 fail | 平台启动日志 |
| AC-3.3 | SDK `SdkTaskHandler.descriptor()` 暴露,可选实现 | API 文档 + 编译测试 |
| AC-3.4 | SDK register 上报 `taskTypes[].descriptor` → 30s 内平台 registry 表能 SELECT 到 | E2E + SQL 验证 |
| AC-3.5 | console-api / Swagger POST workflow_node 引用该 taskType → 派单 message 含合并后 parameters | OpenAPI 测试 |
| AC-3.6 | `task.effective_parameters` 字段写入,SQL 查得到当时合并的参数 | `SELECT effective_parameters FROM task WHERE id=...` |
| AC-3.7 | sample-tenant-worker repo 可 fork 即用,5 分钟内本地跑起来 | 实际操作演示 |
| AC-3.8 | SDK README 含"敏感凭据走 env,不走 parameters"硬规约段落 | 文档 review |
| AC-3.9 | M3.1 完成后 SDK 可独立发版(不依赖 M3.2 FE) | 发布演练 |

### 16.5 Phase 3 M3.2 — console UI 跟上(🔵 延后)

| # | 验收项 | 验证方式 |
|---|---|---|
| AC-3.10 | "我的 taskType" 列表页可访问,显示当前租户所有 taskType | 手工 UI 测试 |
| AC-3.11 | 工作流编辑器拖自定义节点 → 自动出表单(按 schema) | 手工 UI 测试 |
| AC-3.12 | 必填校验 / 类型校验 / 范围校验生效 | 故意填错触发校验 |
| AC-3.13 | 模板变量补全可用(输入 `${` 弹候选) | 手工 UI 测试 |

### 16.6 Phase 4 — 长任务可控性

| # | 验收项 | 验证方式 |
|---|---|---|
| AC-4.1 | SDK heartbeat 可携带 details bytes,平台收下并存到 `task_progress` 表 | E2E + SQL |
| AC-4.2 | 重派时 SDK 通过 API 拿回上次 details(断点续跑) | 模拟 lease 超时测试 |
| AC-4.3 | `workflow_node.taskTimeout` 字段存在,超时平台主动 cancel | E2E `verify-task-timeout.sh` |
| AC-4.4 | Cancel push:heartbeat response 含 `cancelRequested=true` → SDK 5s 内停 | E2E |
| AC-4.5 | `SdkTaskContext.isCancelled()` API 工作正常 | 单测 |
| AC-4.6 | LeaseRenewal 检测 404/410 → 标记 task revoked | 单测 |
| AC-4.7 | UI 任务详情显示进度(🔵 延后) | 手工 UI 测试 |

### 16.7 Phase 5 — 开发体验 / 类型安全

| # | 验收项 | 验证方式 |
|---|---|---|
| AC-5.1 | `SdkTypedTaskHandler<I, O>` 泛型基类可编译 + 反序列化绿 | 单测 |
| AC-5.2 | `batch-worker-sdk-testkit` 模块发布,可作为 test 依赖引入 | maven 坐标可解 |
| AC-5.3 | `FakeBatchPlatform` 支持 register / heartbeat / claim / report / renew 全协议 | testkit 自测 |
| AC-5.4 | 租户用 testkit 写一个 handler 集成测,5s 内跑完 | 实际演示 |
| AC-5.5 | Worker fingerprint(buildId / sdkVersion / hostName / pid)写入 `worker` 表 | SQL 验证 |
| AC-5.6 | OTel context propagation:Jaeger 看到平台 → SDK 完整链路 | Jaeger 截图 |

### 16.8 Phase 7 — 观测性收尾

| # | 验收项 | 验证方式 |
|---|---|---|
| AC-7.1 | `metrics()` 含 `kafkaConsumerLag` 字段 | 单测 + 实际启动 |
| AC-7.2 | 连续 10 次 CLAIM 4xx → SDK 退出码 42 | E2E |
| AC-7.3 | `register()` 失败 → 进程退出码非 0(K8s 拉起重试) | E2E |

### 16.9 整体验收(跨 phase)

| # | 验收项 | 验证方式 |
|---|---|---|
| AC-Overall-1 | 全仓 `mvn clean install` 绿 | CI 跑 |
| AC-Overall-2 | 全仓 `mvn verify` 绿(单测 + IT) | CI 跑 |
| AC-Overall-3 | ArchUnit 守护测全绿 | `mvn -Dtest=*ArchTest test` |
| AC-Overall-4 | SDK jar < 2.1 MB(依赖不爆) | 构建产物大小 |
| AC-Overall-5 | OpenAPI / `docs/api/` 跟 controller 一致(CI `pr-gate` 拦截) | CI |
| AC-Overall-6 | 4 份 SDK doc 的待办全划线 / 标"延后"/ 标"砍" | 文档 review |
| AC-Overall-7 | `changelog.md` 含每 phase 5 行总结 | 文档 review |
| AC-Overall-8 | sample-tenant-worker 可 fork 即用 | 实际演示 |

---

## 17. 参考

- [batch-worker-sdk 深度评估](../review/batch-worker-sdk-deep-review-2026-05-31.md)
- [worker-deployment-models](../design/worker-deployment-models.md)
- [sdk-industry-benchmark](../design/sdk-industry-benchmark.md)
- [sdk-task-type-configuration](../design/sdk-task-type-configuration.md)
- [ADR-035 租户自托管 SDK](../adr/ADR-035-tenant-self-hosted-sdk.md)
- [multi-tenant-isolation-plan](./multi-tenant-isolation-plan-2026-05-31.md)(姊妹 plan)
