# 项目工程深度评估报告

> **产出日期**:2026-04-29
> **评估范围**:全仓库静态评估,覆盖架构、模块边界、代码质量、测试体系、运维与文档
> **评估方法**:模块依赖矩阵 / 跨模块 grep 边界核查 / 代码 reviewer agent 对最近 diff 的扫描 / runbook + ADR + backlog 现状盘点
> **不包含**:动态压测、渗透测试、生产流量回放;结论基于代码与文档静态读

---

## 0. 规模盘点

| 模块 | main LOC / files | test LOC / files | 备注 |
|---|---|---|---|
| batch-common | 5.7K / 169 | 1.9K / 33 | 库,枚举/DTO/Kafka/utils |
| batch-trigger | 4.1K / 59 | 3.6K / 24 | Quartz 入口 |
| batch-orchestrator | **21.5K / 283** | 13.7K / 90 | 状态主机,核心 |
| batch-worker-core | 4.5K / 58 | 1.7K / 12 | Worker 基座 |
| batch-worker-import | 6.5K / 48 | 1.7K / 13 | |
| batch-worker-export | 4.1K / 41 | 1.6K / 13 | |
| batch-worker-dispatch | 4.3K / 55 | 2.2K / 17 | |
| batch-worker-process | 2.6K / 33 | 1.6K / 12 | 最新加入 |
| batch-console-api | **42.5K / 593** | 12.0K / 100 | 最大模块,API 总线 |
| batch-e2e-tests | 0 / 0 | **6.1K / 39** | 19 IT + 6 apps + 7 fixtures |

**合计 ~106K Java LOC + 54K 测试 LOC,测试/源码比 0.51,77 个 Flyway migration(到 V79),23 篇 runbook,9 条 ADR,4 个 GitHub workflow。**

这不是 demo,是中型平台代码库。

---

## 1. 架构与模块边界 — **8/10**

### 硬约束实地核查全部通过

- **模块依赖**:严格分层无反向。worker-* 只依赖 common+worker-core;console-api 只依赖 common;batch-e2e-tests 是唯一聚合所有 worker+console+orchestrator 的反应堆,合理。无循环依赖。
- **状态主机约束**:`grep -E "(UPDATE|INSERT INTO)\s+(job_instance|workflow_run|workflow_node_run)" batch-worker-*` **0 匹配**,worker 确实不直写状态表。
- **outbox 边界**:`grep -E "(UPDATE|DELETE FROM|INSERT INTO)\s+outbox_event" batch-console-api` **0 匹配**,console-api 也没绕过 orchestrator。
- **Outbox 同事务**:`TaskDispatchOutboxService.java:53/67` 使用 `@Transactional(propagation = MANDATORY)` 强制借调用方事务,语义到位。`OutboxOpsApplicationService.java` 有显式注释说明事务边界。

### 风险点

1. **batch-orchestrator 21.5K LOC / 283 文件**,**batch-console-api 42.5K LOC / 593 文件**;console-api 已显式承认 God Service(`docs/analysis/deep-issue-analysis.md` §6 点名 `DefaultConsoleJobApplicationService`),ADR-008 god-class-decomposition 也在册,需要持续拆分。
2. **trigger → orchestrator 仍是同步 HTTP 桥**(deep-issue-analysis §4),触发器层鲁棒性弱于主链路。
3. **batch-trigger 缺 Spring Security 保护**(deep-issue-analysis §1),作为运维入口暴露写接口是边界问题,不是风格问题。
4. **Console 仍保留旧式 `X-Console-Token` 共享密钥直通**(deep-issue-analysis §2),与"生产就绪"叙述不一致。

### 亮点

- ADR 制度真在用:9 条 ADR 覆盖双 ORM、Outbox、Launch T1/T2、Worker 模板、PartitionResolver、Compensation、双数据源、God class、Workflow DSL。
- ADR-009 有 4-stage 推进路线图与现有代码对照(V72 已落 workflow_node_run.output 列)。
- WAP+5 段(PREPARE/COMPUTE/VALIDATE/COMMIT/FEEDBACK)在 PROCESS worker 已对齐,4 worker 平行结构整齐。

---

## 2. 代码质量 — **7/10**

### 1 处规范硬违规(必修)

- **FQN 违规**:`batch-worker-core/src/main/java/com/example/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java:260` 出现 `com.example.batch.common.utils.JsonUtils.fromJson(...)`,未走 import,违反 CLAUDE.md "禁止全限定类名"。修复:加 import 后用短名。

### 4 处"重构未走完"的复制粘贴(同源)

| 编号 | 重复点 | 重复次数 | 应在哪里 |
|---|---|---|---|
| I1 | `loadConfiguredSteps` 15 行 | **4** Default*StageExecutor | `AbstractStageExecutor` 默认实现 |
| I2 | `handlePipelineFailure` 12 行 | **3** *StepExecutionAdapter | `AbstractPipelineStepExecutionAdapter` |
| I3 | `private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper()` | **4** 个独立实例 | 抽到基类 |
| I4 | `buildContext` setTenantId/JobCode/BizDate/FileId/WorkerId/RawPayload/Attributes 序列 | **3** | 提 `populateBaseContext` 模板方法 |

具体定位:

- I1: `DefaultImportStageExecutor.java:82-101` / `DefaultExportStageExecutor.java:87-106` / `DefaultDispatchStageExecutor.java:88-107` / `DefaultProcessStageExecutor.java:146-163`
- I2: `ImportStepExecutionAdapter.java:107-123` / `ExportStepExecutionAdapter.java:115-131` / `DispatchStepExecutionAdapter.java:117-133`
- I3: `DefaultImportStageExecutor.java:44` / `DefaultExportStageExecutor.java:42` / `DefaultDispatchStageExecutor.java:45` / `DefaultProcessStageExecutor.java:32`
- I4: `ImportStepExecutionAdapter.java:62-71` / `ExportStepExecutionAdapter.java:62-78` / `DispatchStepExecutionAdapter.java:61-79`

这是典型的"抽了基类但没把共享逻辑上推"的半完成重构,4×4 个文件累计 ~150 行可消除重复。

### 未发现大面积违规

- 未发现 `Charset.forName("UTF-8")` / `ZoneId.systemDefault()` 滥用;
- 未发现 JPA/Hibernate 引用;
- i18n 重构刚完成(近 30 提交一半在 i18n migration: Phase B/C/D/E/F/Phase 2 + S2.1+S2.2),137 处 BizException literal 已迁 messages,工程纪律可见;
- 自定义 metrics 涉及 34 个文件,Counter.builder/Timer.builder 在用,不是空架子。

---

## 3. 测试体系 — **8/10**

### 数量与广度

- **302 个 \*Test.java + 26 个 \*IT.java + 19 个 E2E \*IT.java**,共 347 个测试类。
- E2E 模块 **19 个端到端用例**:
  - 主链路 happy-path:Import / Export / Dispatch / ProcessPipeline ×4
  - 失败路径:ImportFailure / ExportStorageFailure / DispatchFailure / ProcessFailure ×4 + Pipeline 版各 1
  - 边界场景:**MultiTenantConcurrent**、**WorkerProcessRestartRecovery**、**WorkerDrain**、**OutboxForwarderRetry**、**DeadLetterApprovalReplay**、**DedupJobLaunch**、**ExportContentVerification**

  这是少见的高覆盖度——失败/重启/补偿/多租户/排空/死信审批/去重 全部被显式断言。

- 6 个独立 E2eApplication(Console / Orchestrator / 4 个 Worker)+ 7 个 fixture/verifier,**用 Testcontainers 真起 Postgres+Kafka+MinIO**(10 个测试文件含 `@Container`),不是 mock。

### 守卫机制

- `ConsoleMetaEnumRegistrationTest.java` 真实存在(已编译),CLAUDE.md 说的"枚举二选一守护"到位。
- `ExportFileVerifier` / `DispatchReceiptVerifier` 为产物验收,配 Micrometer 指标。
- 4 条 GitHub workflow 分级:`pr-gate / full-ci-gate / staging-gate / capacity-gate`,有 capacity 门禁说明跑过 load-tests。

### 风险

- 单测/集成测试比例:`@SpringBootTest` 85 个,Testcontainers 只 10 个——绝大多数集成测试用 Spring Boot slice 跑,不真起容器。这是性能/稳定性的取舍,但需要警惕"集成测试通过 ≠ 真实环境工作"。
- load-tests 是独立模块未入 reactor,与主版本号靠手工同步,易漂移(根 pom 注释中已自承)。

---

## 4. 运维 / 文档 / 部署 — **8/10**

### 部署形态完备

- 4 套 docker-compose:`docker-compose.yml`(基础设施)/ `.app.yml`(8 服务)/ `.observability.yml`(Prom+Grafana+Loki+Jaeger+OTel+Alertmanager+Exporters)/ `.test.yml`(测试环境)。
- Helm chart `helm/batch-platform/`:6 个 service template(orchestrator / trigger / console-api / worker-import / export / process)+ ingress + pdb + otel-collector,4 套 values 示例(local-k8s / autoscale / minimal-replicas / startup-probes)。
- `.env.local` / `.env.test` / `.env.prod` 三套环境分离。

### 文档体系

- 23 篇 runbook 覆盖:autoscaling、incident-response、orchestrator-statefulset-migration、rolling-upgrade-workers、daily-inspection、troubleshooting-decision-tree、pg-table-partitioning、minio-lifecycle-policy、quartz-capacity-baseline、wheel-scheduler-rollout 等运维全场景。
- ADR、changelog、coding-conventions、agent-baseline、analysis(deep-issue / hardening-backlog / fix-report)、api(OpenAPI YAML + protocol.md)、design、dict、testing、test-data、compliance 一应俱全。
- `docs/analysis/hardening-backlog.md` v5 已迭代 5 版,**23 项硬化项 19 已完成 / 2 部分 / 2 不做 / 0 待办**——backlog 真在烧。

### 数据库治理

- 77 个 Flyway 版本走到 V79,migration 节奏与功能演进同步:近期 V72 加 workflow_node_run.output / V75 PROCESS staging / V76 PROCESS step / V79 archive 列同步。

### 已知未关闭项(deep-issue-analysis)

| # | 问题 | 严重度 |
|---|---|---|
| 1 | trigger 模块无 Spring Security | 边界问题(高) |
| 2 | X-Console-Token 共享密钥直通 | 安全(高) |
| 3 | 幂等责任边界 trigger/console/db 不一致 | 中 |
| 4 | trigger→orchestrator 同步 HTTP 桥 | 鲁棒性(中) |
| 5 | DefaultConsoleJobApplicationService god service | 维护性(中) |
| 6 | Webhook/审批补跑 durability+事务边界弱 | 中 |

这 6 条是 deep-issue-analysis 自己列的,**意味着团队心里有数**,只是排期问题。

---

## 5. 综合评分与优先建议

| 维度 | 评分 | 说明 |
|---|---|---|
| 架构与模块边界 | **8/10** | 硬约束零越权,ADR 在用;有几个 god class |
| 代码质量 | **7→7.5/10** | 半完成重构已收尾(↓ 见已完成);仍有 god service 待拆 |
| 测试体系 | **8/10** | E2E 覆盖罕见地全 |
| 运维就绪度 | **8/10** | 三套部署 + 23 runbook + 完整观测栈;trigger 缺 Security |

**整体 7.8/10**——这是一个**有持续工程纪律的中型平台**:架构边界、ADR、E2E、runbook、Flyway、observability、i18n 都在动且互相对齐,backlog 真清账。但还**没到生产就绪**:trigger 缺 Security、X-Console-Token 直通、Console God Service、trigger→orchestrator 同步桥,这些都是"离量产差一步"的典型表现。

### ✅ 已完成

#### 本次评估后落地

| 项 | Commit | 说明 |
|---|---|---|
| 半完成重构 #2 | `4e634c7c` | 4×`ERROR_OBJECT_MAPPER` + 4×`loadConfiguredSteps` + 3×`handlePipelineFailure` 上提到基类,消除 ~150 行复制;`AbstractPipelineStepExecutionAdapter:265` 一处 FQN 违规修了 |
| i18n 业务路径收口 | `23137b2c` | 56 文件横扫 console / orchestrator / trigger / worker 业务路径,BizException 全量从 literal message 迁到 i18n key + args 三元组(配套 9 个 test 同步成 messageKey/messageArgs 行为断言);承接 `c74a9644`(plugin)+ `4e634c7c`(SqlPlugin)同流水线 |
| ops 增量 | `f0eff4ae` | prometheus 告警规则 + seed/load 脚本同步 |
| 评估快照 | `d325e44a` / `79e3a35b` | 本文档落盘 + 后续追加下一步计划 |
| S5-d 真删 X-Console-Token compat | `ff20c36f` | 主代码 + yaml + OpenAPI + 测试全量物理删除(9 文件 +20/-168);只留 JWT + SSE ticket + bypass-mode 三条认证链 |
| S5-c trigger SecurityIntegrationTest | `e8e48a6e` | `TriggerSecurityFilterTest` 5 类边界守护(无 header/错 header/对 header/actuator 跳过/bypass-mode);CI pr-gate 自动覆盖 |
| S0 deep-issue/backlog/fix-report 滚版 | `8ac1ea2d` | §5.1 / §5.2 / §5.12 标已修;ADR-009 Stage 1.2 标已落;deep-issue 与 backlog 口径同步 |
| ADR-009 Stage 2/3 校正 + §10 文档 | `8b520102` | 实地查代码发现 Stage 2/3 已落;workflow-dependency-guide §10 节点间参数串联文档落盘 |
| **ADR-010 trigger 异步解耦草稿** | `e1b37cfd` | 立 ADR-010(202 行,7 阶段 ~7-8 人天路线图);复用 ADR-002 模式 + V80 schema + Kafka topic v1 + 灰度开关 + 4 类 E2E 守护 |
| **ADR-009 Stage 1.2/2/3 全栈实装**(下文 §5"评估时遗漏"小节经第四轮代码核查推翻) | `a9469407` | 30 文件 +678/-20:协议层加 outputs / 4 worker adapter 填 NODE_OUTPUTS / orchestrator 持久化到 workflow_node_run.output / WorkflowParamResolver 160 行 + 10 单测 / DefaultWorkflowNodeDispatchService.mergeNodeParams 接入;`wf_probe_mixed.REPORT` seed 演示 `$.nodes.PROCESS.output.processedCount` 引用 |
| ADR-009 Stage 1.2 SqlPlugin 测试断言修复 | `4e634c7c` | `requireTargetTableExists` 改用专用 i18n key `error.process.target_table_not_found`;`prepare_failsFast_whenTargetTableDoesNotExist` 断言改为基于 BizException.getMessageKey/getMessageArgs |
| Worker 插件层 73 处 failure 迁 i18n 三元组 | `c74a9644` | V78 八表 errorKey/errorArgs 列在 step 失败路径上真正生效;30 个 i18n key 中英双语 |
| **ADR-010 Stage 1-3 trigger outbox 框架**(并行 Claude 会话完成) | `9587b8bf` / `087f6b7a` / `1ca3a957` | Stage 1: V80 trigger_outbox_event + LaunchEnvelope DTO + Mapper;Stage 2: TriggerOutboxRelay 224 行 + 7 单测(空批/成功/失败/反序列化/抢占/异常隔离/退避函数);Stage 3: DefaultTriggerService 加异步分支 + 灰度开关 batch.trigger.async-launch.enabled |
| **ADR-010 Stage 4-7 Kafka publisher/consumer + runbook + deprecation** | `22b330ea` | 11 文件 +949/-0:trigger 加 spring-kafka + KafkaTriggerEventPublisher + TriggerKafkaProducerConfiguration;orchestrator 加 OrchestratorKafkaConsumerConfiguration + TriggerLaunchConsumer(409 dedup→ack / 429 限流→ack / runtime→抛出);TriggerLaunchConsumerTest 6 单测 + KafkaTriggerEventPublisherTest 3 单测;docs/runbook/trigger-async-launch-rollout.md 225 行灰度切换 runbook;HttpOrchestratorTriggerAdapter @Deprecated(forRemoval=true)+ DefaultTriggerService 首次同步路径 WARN(AtomicBoolean 防刷屏) |

#### 评估时遗漏 — 实际已落地(2026-04-30 复查发现)

> 以下两项原 §5 标"未完成",实地查代码后发现已在评估前落地,deep-issue §1+§2+§6 描述滞后。

| 项 | 实地证据 | 落地状态 |
|---|---|---|
| **S1 trigger Spring Security**(原 deep-issue §1) | `batch-trigger/src/main/java/com/example/batch/trigger/config/TriggerSecurityConfiguration.java:42-46` 真起 `SecurityFilterChain` 把 `/actuator/**` 之外的请求强制 `authenticated()`;最早 commit `cd389a0b`(2026-04-22 v4 闭环) | ✅ 已落 |
| **S1 X-Console-Token**(原 deep-issue §2) | `legacyHeaderAuthEnabled` 在 `ConsoleSecurityProperties.java:38` 代码默认 `true`,但 `application.yml:67` 用 env `BATCH_CONSOLE_LEGACY_HEADER_AUTH_ENABLED:false` 覆盖默认关闭,注释"5.2: 默认关闭旧式 X-Console-Token 鉴权" | 🟡 deprecated,opt-in compat |
| **S2 `DefaultConsoleJobApplicationService` 拆分**(原 ADR-008 + deep-issue §6) | 该类现仅 **90 LOC**(纯 delegate);拆出 6 个兄弟类:`ConsoleJobOpsSupport`(407)、`ConsoleJobQueryService`(226)、`DefaultConsoleJobApprovalService`(192)、`DefaultConsoleJobRecoveryService`(230)、`DefaultConsoleJobTriggerService`(133) | ✅ 已拆 |

> **后续动作**:更新 `docs/analysis/deep-issue-analysis.md` §1+§2+§6,把这三项移到"已修"清单(原文称 v4 issue 全景但未滚版到 v5);hardening-backlog v6 同步反映。

### 🔴 仍未完成(评估口径,需复核)

> **2026-04-30 复盘**:本节原列两项均已落地,本节存档保留以备审计追溯。
>
> 1. ~~trigger → orchestrator 异步化~~ — ADR-010 Stage 1-7 全栈完成(`9587b8bf` / `087f6b7a` / `1ca3a957` / `22b330ea`),trigger → outbox → Kafka → orchestrator consumer → launch 链路代码就绪;开关 `batch.trigger.async-launch.enabled=true` 即可启用。**剩余仅 operational** — staging → prod-canary → prod 灰度执行(按 `docs/runbook/trigger-async-launch-rollout.md` 步骤),以及稳定 1 minor 后物理删除同步 HTTP 路径(Stage 7 forRemoval 标记已加)。
> 2. ~~ADR-009 Workflow DSL Stage 2-4~~ — 全栈完成(`a9469407` / `8b520102`),详见 §S4。

**当前真正未落项**:无主线工程缺口。次级项见 §S5。

---

## 6. 评估方法与覆盖说明

- **模块依赖**:基于各 `pom.xml` 内 `<artifactId>batch-*</artifactId>` 引用直查。
- **架构边界**:跨模块 grep 状态表 / outbox 表的 UPDATE/INSERT/DELETE,核 worker、console-api 是否越权。
- **代码质量**:`feature-dev:code-reviewer` agent 对当前未提交 diff(12 个 modified 文件,主要是 i18n + WAP 相关 worker/common 改动)+ 抽样基类静态扫描。
- **测试体系**:`find` 全仓 *Test.java / *IT.java 计数 + 打开 batch-e2e-tests 全用例列表 + 抽样 Testcontainers 注解。
- **运维**:罗列 docker-compose / helm / .env / docs/runbook 资产 + 抽样 observability 编排 + 数 micrometer 注册点 + 读 hardening-backlog v5 / deep-issue-analysis 头部。

**未覆盖**:运行时性能、生产流量回放、渗透测试、跨集群 chaos。如需,需另立专项。

---

## 7. 与其他文档的分工

| 文档 | 关系 |
|---|---|
| `docs/analysis/deep-issue-analysis.md` | 单 issue 深挖,本报告引用其 §1-§6 |
| `docs/analysis/hardening-backlog.md` | 滚动 backlog,本报告引用其 v5 已完成/部分完成统计 |
| `docs/analysis/fix-report.md` | 修复 commit 记录 |
| `docs/architecture/architecture-truth.md` | 架构现状("是什么"),本报告基于其结构做边界核查 |
| `docs/architecture/maturity-assessment.md` | 单维度成熟度,本报告做横向综合评分 |

本报告是**点状项目快照**,不替代上述滚动文档。下次评估建议在主链路或 console-api 出现重大重构后再做。

---

## 8. 下一步计划

按价值/成本/解锁关系排序,**S1 必须先做**(上线前阻塞);S2-S4 可并行排期。

> **复查后口径调整**(2026-04-30):S1(trigger Security)和 S2(console god service 拆分)经实地代码核查已落地(详见 §5"评估时遗漏 — 实际已落地"小节);本节原 S1/S2 子任务**不再适用**,改为收尾性的 follow-up 列在 S5。
>
> 主线工作降为 S3 + S4 两项;S0 是新加的"评估口径校正",必须先完成以避免后续工作错位。

### S0 — 评估口径校正(0.5 天,前置)

**目标**:更新 deep-issue / hardening-backlog,反映 S1/S2 已完成的事实,避免后续 sprint 还按旧口径排期。

**子任务**:
1. `docs/analysis/deep-issue-analysis.md` §1+§2+§6 标"已修",指向 `cd389a0b`(trigger Security)+ console job 拆分历史 commit。
2. `docs/analysis/hardening-backlog.md` 滚 v6,把 deep-issue §1+§2+§5(god service)移到"已完成"。
3. `docs/analysis/fix-report.md` 追加引用本次校正的 commit。

**完成标志**:三份文档不再把这三项标为"未完成";本评估文档 §5 与 deep-issue/backlog 互相一致。

### S3 — trigger → orchestrator 异步化 ✅ 全栈完成(2026-04-30)

> **状态**:Stage 1-7 全部代码就绪;剩余仅 operational rollout(staging → prod-canary → prod 灰度切换)+ 稳定 1 minor 后物理删除同步路径。

| Stage | 状态 | 实地证据 |
|---|---|---|
| **Stage 1** ✅ | V80 trigger_outbox_event 表 + LaunchEnvelope DTO + TriggerOutboxEventMapper | `db/migration/V80__create_trigger_outbox_event.sql` / `LaunchEnvelope.java` 37 行 / `TriggerOutboxEventEntity` 41 行 — `9587b8bf` |
| **Stage 2** ✅ | TriggerOutboxRelay 周期发布器(ShedLock 互斥 + FOR UPDATE SKIP LOCKED + 退避)+ 7 单测 | `TriggerOutboxRelay.java` 224 行 + `TriggerOutboxRelayTest` 7/7 全绿 — `087f6b7a` |
| **Stage 3** ✅ | DefaultTriggerService 加 outbox 写入分支(同事务) + 灰度开关 `batch.trigger.async-launch.enabled` | `DefaultTriggerService.java:202-225 persistAndForward` 异步路径分支 + `insertPendingAndOutboxOrReturnExisting` 同事务 INSERT — `1ca3a957` |
| **Stage 4** ✅ | trigger 端 KafkaTriggerEventPublisher impl + ProducerConfig;orchestrator 端 ConsumerConfig + TriggerLaunchConsumer @KafkaListener | `KafkaTriggerEventPublisher.java` 101 行(headers: X-Trace-Id/X-Tenant-Id/X-Envelope-Version) / `TriggerLaunchConsumer.java`(409→ack / 429→ack / runtime→抛出 listener 重试) / `BatchTopics.TRIGGER_LAUNCH_V1` 常量 — `22b330ea` |
| **Stage 5** ✅(全栈) | 单测 9 + 全 Testcontainer Kafka E2E 6 = 15 全绿 | (a) 单测 9/9:TriggerLaunchConsumerTest 6 + KafkaTriggerEventPublisherTest 3 — `22b330ea`。(b) **Layer 1** trigger 端 4 个 E2E IT(`TriggerAsyncLaunchE2eIT` in batch-trigger,真起 PG+Kafka):happy path / 同 idempotencyKey 双写防重 / crash recovery / 坏 payload GIVE_UP — **4/4 in 45.89s** — `788b637d`。(c) **Layer 2** 跨模块全链路 E2E(`TriggerAsyncLaunchFullChainE2eIT` in batch-e2e-tests):手工 publish Kafka → orchestrator TriggerLaunchConsumer 真消费 → LaunchApplicationService.launch → job_instance INSERT(含 dedup 兜底验证) — **2/2 in 74.66s** — `68bc49e8`。配套:`E2eTriggerApplication` scaffold、batch-e2e-tests pom 加 trigger 依赖、batch-trigger pom 加 spring-boot exec classifier(让 e2e 模块拉得到普通类) |
| **Stage 6** ✅(文档) | 灰度切换 runbook | `docs/runbook/trigger-async-launch-rollout.md` 225 行(staging 验证 / prod-canary 24h / prod 全量 + 回滚预案 + 24h 对账 SQL + Prometheus 告警建议)— `22b330ea`。**剩余 operational**:实际执行灰度按 runbook 进行 |
| **Stage 7** ✅(标记) | HttpOrchestratorTriggerAdapter `@Deprecated(forRemoval=true, since="ADR-010 Stage 6")` + DefaultTriggerService.forwardToOrchestrator 首次进入 1 条 deprecation WARN(AtomicBoolean 防刷屏)| `22b330ea`。**剩余**:灰度全量切稳定 1 minor 后物理删除 HTTP 同步路径 |

**完成标志**(已达成):
- ✅ 开关切到 true 后,trigger fire → outbox INSERT 同事务,trigger 进程崩溃不丢 launch(由 relay 重启后继续投递)
- ✅ orchestrator 重启期间 trigger 持续写 outbox,relay 退避后继续投递,不阻塞 trigger Quartz 线程
- ✅ 15 测试全绿覆盖核心路径:9 单测(consumer 6 + publisher 3) + relay 7 + Layer 1 trigger 端 4 E2E + Layer 2 跨模块 2 E2E,真起 Testcontainers PG+Kafka 全链路验证
- ✅ 灰度切换 + 回滚有完整 runbook 操作步骤

### S4 — ADR-009 Workflow DSL(基础设施全部完成,业务配置 deferred)

**实地核查后修订**(2026-04-30):S4 Stage 1 / 1.2 / 2 / 3 已全部落地,基础设施完备;Stage 4(给现有 workflow 配 DSL)实际是业务方按需触发的配置工作,不是基础设施 todo。

**已落地**:

> **2026-04-30 第四轮校正**:本节原标"Stage 1.2/2/3 ✅"系第三轮速判,当时 grep 命中 `TaskExecutionReport.outputs` 等字符串以为已实装;实地 `a9469407` 提交前 grep 验证发现 `TaskOutcomeCommand` 仅 10 字段(无 outputs)、`WorkflowParamResolver.java` 不存在、`mergeNodeParams` 仅 17 行无 resolver 调用——**实际只有 V72 schema 落了 Stage 1**。Stage 1.2/2/3 全栈实装落于本会话 commit `a9469407`(详见上表)。

| Stage | 状态 | 实地证据(post-`a9469407`) |
|---|---|---|
| **Stage 1** ✅ | DDL: V72 migration 加 `workflow_node_run.output` JSONB 列 | `db/migration/V72__add_workflow_node_run_output.sql` |
| **Stage 1.2** ✅ | 协议层 outputs 字段全栈 + 4 worker adapter populate + orchestrator 持久化 | `TaskExecutionReport.outputs` / `TaskExecutionReportDto.outputs` (+`@JsonIgnoreProperties` 保护滚动升级) / `TaskOutcomeCommand.outputs` 11 字段 record / `UpdateNodeRunStatusParam.output` / 4 `*StepExecutionAdapter.buildSuccessResponse` 填 `NODE_OUTPUTS` (Import: fileId/recordCount/parsedCount/...; Export: fileId/objectName/checksumValue/...; Process: processedCount/batchKey/...; Dispatch: receiptCode/channelCode/...) / `DefaultTaskExecutionWrapper` 提取透传 / `DefaultTaskOutcomeService.serializeOutputs` 写 JSONB / JOB 节点 `signalParentVirtualTask` 透传子作业 outputs |
| **Stage 2** ✅ | `WorkflowParamResolver` 类(160 行)+ `WorkflowRunContext` 接口 + 10 单测 | `application/workflow/WorkflowParamResolver.java`(`$.nodes.<code>.output.<key>` / `$.workflowRun.<key>` 引用 + 3 类 fail-fast + 嵌套路径下钻 + Map/List 递归);`WorkflowParamResolverTest.java` 10/10 全绿 |
| **Stage 3** ✅ | resolver 集成 + 文档 | `DefaultWorkflowNodeDispatchService.mergeNodeParams` 注入 resolver + `loadWorkflowRunContext`(`selectByWorkflowRunId` 加载兄弟节点 output 反序列化构造 ctx,不持久化);TASK + JOB 双派发路径都接入;`error.workflow.param_ref_invalid` 中英双语 i18n;`workflow-dependency-guide.md §10` 节点间参数串联文档 |

**Stage 4 — seed 演示锚点已落,业务配置随业务推进**:

`a9469407` 在 `wf_probe_mixed.REPORT` 节点 `node_params` 加 `$.nodes.PROCESS.output.processedCount` + `$.workflowRun.bizDate` 引用,作为 e2e 烟测锚点。

ADR-009 原文提到"给现有 wf_eod_process 等 7 个 workflow 配 DSL",但仓库 seed 实际只有 6 个 workflow(`TA_WF_SETTLEMENT` / `TB_WF_RECONCILE` / `TC_WF_RISK_PIPELINE` / `wf_probe_pipeline` / `wf_probe_gateway` / `wf_probe_mixed`),其中前 3 个仅 START→END 骨架,后 3 个 probe 系列仅 `wf_probe_mixed` 是真实跨节点链;`wf_eod_process` 在仓库不存在。**无 SETTLE→DISPATCH 真实跨 workflow 链路**——`mergeUpstreamPartitionOutputs` 已自动透传 fileId/fileCode 等规约字段满足现状。

**已具备能力**:任何 workflow 设计者现在就可以在 `workflow_node.node_params` 配 `{"fileId": "$.nodes.SETTLE.output.fileId"}` 这样的引用,resolver 会在派发前自动解析。

**业务方触发条件**(出现以下任一即应配 DSL):

- 跨节点字段名不同(上游产 `settledFileId`,下游期望 `fileId`)
- `mergeUpstreamPartitionOutputs` 自动透传不够(超出 fileId/fileCode 等规约 key)
- workflow 跨多个 jobInstance(如 JOB 节点链),`mergeUpstreamPartitionOutputs` 同 jobInstance 边界外的字段透传

**配置例**:见 `docs/architecture/workflow-dependency-guide.md §10.3` + `multi-tenant-seed.sql:558-559`(wf_probe_mixed.REPORT 节点 e2e 锚点)。

### S5 — 次级清单(随手优化,不阻塞主线)

| # | 项 | 估时 | 触发 |
|---|---|---|---|
| a | I4 `buildContext` 模板抽取 | 0.5 天 | 等 4 个 `*JobContext` 出现共同基类时再做(避免现在强抽 scope creep) |
| b | i18n 测试债二轮扫荡 | 1 天 | 23137b2c 已收 9 处,但 `assertThat.hasMessageContaining` 这类断言全仓再扫一遍兜底 |
| c | trigger SecurityIntegrationTest 守护补齐 | 1 天 | S1 已落 Security,但若 CI 还没有"无 token → 401"守护测试,补一个;同款扩到 console/orchestrator |
| d | 真删 `X-Console-Token` 兼容路径 | 1-2 天 | 当所有前端都切到 JWT 后,从 `ConsoleSecurityProperties` / 应用 yaml / OpenAPI 彻底删除 legacy header 分支(不再 deprecated 而是物理删除) |

### 节奏建议(2026-04-30 第五轮校正,ADR-010 全栈完成后)

```
✅ DONE(本评估会话累计 16 commits):
  S0   - 评估口径校正(deep-issue/backlog/fix-report 滚 v6/v7) — 8ac1ea2d
  S5-d - 真删 X-Console-Token compat 路径(主代码 + yaml + OpenAPI + 测试) — ff20c36f
  S5-c - TriggerSecurityFilterTest 守护 5 类边界 — e8e48a6e
  S4 Stage 1/1.2/2/3 - ADR-009 DSL 基础设施全栈(DDL + 协议层 outputs + 4 worker NODE_OUTPUTS +
                       orchestrator 持久化 + WorkflowParamResolver + dispatchService 集成
                       + §10 文档 + e2e seed 锚点) — a9469407(实装) / 8b520102(文档)
  Worker 73 处 failure 迁 i18n 三元组 - c74a9644(V78 八表 errorKey 真生效)
  SqlPlugin i18n key 收尾 - 4e634c7c
  半完成基类重构 + i18n 业务路径收口 - 4e634c7c / 23137b2c

  ★ S3 - ADR-010 trigger → orchestrator 异步解耦 全 7 Stage 完成
    Stage 1: V80 trigger_outbox_event + LaunchEnvelope DTO + Mapper — 9587b8bf
    Stage 2: TriggerOutboxRelay 周期发布(ShedLock + 退避) + 7 单测 — 087f6b7a
    Stage 3: DefaultTriggerService 异步分支 + 灰度开关 — 1ca3a957
    Stage 4: Kafka publisher/consumer 双侧实装 + 6+3 单测 — 22b330ea
    Stage 5: 15 测试全绿 = 9 单测(consumer 6 + publisher 3) + 7 relay + 4 trigger E2E
             (Layer 1 in batch-trigger) + 2 全链路 E2E (Layer 2 in batch-e2e-tests),
             真起 Testcontainers PG+Kafka — 22b330ea / 788b637d / 68bc49e8
    Stage 6: 灰度切换 runbook 完整 (staging→canary→prod + 回滚 + 对账 SQL) — 22b330ea
    Stage 7: HttpOrchestratorTriggerAdapter @Deprecated forRemoval + WARN — 22b330ea

🟡 deferred(基础设施完备,触发条件出现再做):
  S3 Stage 6 实操 - staging → prod-canary 24h → prod 全量切换
                    (operational,按 runbook 执行,不在代码 todo 范围)
  S3 Stage 7 物理删除 - 灰度全量切稳定 1 minor 后,删 HttpOrchestratorTriggerAdapter +
                        DefaultTriggerService.forwardToOrchestrator 同步路径
  S4 Stage 4 业务全量配 DSL - 目前 wf_probe_mixed 已配 e2e 锚点,业务方 workflow 跨节点
                              参数串联需求出现时按 §10 文档/§10.3 配置例配
  S5-a I4 buildContext 模板抽取(等 4 个 *JobContext 出现共同基类时再做)
  S5-b i18n 测试债二轮扫(23137b2c 已收 9 处,剩余按需)

🟠 下次评估必须复核(累计发现的口径漂移):
  - deep-issue §1+§2+§5+§6 已修但未滚版到 v5
  - ADR-009 Stage 1.2/2/3 第三轮速判误标已落,实际本会话 a9469407 才全栈
  - 评估口径与代码事实差距曾达 2-4 周
  - ADR-010 第四轮草稿仅立 ADR,本轮 (2026-04-30) 已全栈实装
```

> **注**:本评估五轮校正(2026-04-30 起)累计发现 6 项原标"未完成/已完成"与实地代码不符 + 1 项主线工作完成:
> - 第一轮: S1 trigger Security / S2 god service 拆分(实际已完成)
> - 第二轮: ADR-009 Stage 2/3(实际已完成)
> - 第三轮: ADR-009 Stage 1.2(声称已完成,实际仅 V72 schema)
> - 第四轮: ADR-009 Stage 1.2/2/3 全栈本会话 `a9469407` 真正落地
> - 第五轮: ADR-010 trigger 异步解耦全 7 Stage 落地(`9587b8bf`/`087f6b7a`/`1ca3a957`/`22b330ea`),原"实施中独立分支"路径已并入 main
> - 第六轮: ADR-010 Stage 5 全 Testcontainer E2E 双层覆盖(`788b637d` Layer 1 trigger 端 4/4 + `68bc49e8` Layer 2 跨模块 2/2),原 deferred 项清账;ADR-010 代码工作 100% 完成
> - 第七轮: 运行日志噪声治理 — `aa249bf8` 收敛 ChannelConfigMerge `receipt_policy/enabled/channel_type` redundant key WARN(240/30min → ~0)+ FileGovernance `processingDelayMaxAgeSeconds` 排除 zombie pipeline(60/30min → ~0);`0d650fab` 加 `heal-zombie-pipelines.sh` + `make ops-heal-zombie-pipelines` target 闭环 zombie 转 FAILED 终态。docker daemon 重启验证留 user ops 执行
>
> 下次评估**强制要求**:(1) 同步滚 deep-issue / hardening-backlog;(2) 关键模块先 grep 验证(如 `WorkflowParamResolver` / `outputs` 字段 / `TriggerOutboxRelay`)再下结论,避免速判;(3) ADR Stage 状态以"全栈 grep + 单测全绿 + commit ref"为权威。

每个里程碑结束更新 `hardening-backlog.md` + 在本目录追加新评估快照(命名 `project-assessment-YYYY-MM-DD.md`)。下次评估应同时滚 deep-issue-analysis 和 hardening-backlog,避免本次"评估口径滞后于代码"再发生。
