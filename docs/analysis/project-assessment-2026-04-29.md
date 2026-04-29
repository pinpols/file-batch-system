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

#### 评估时遗漏 — 实际已落地(2026-04-30 复查发现)

> 以下两项原 §5 标"未完成",实地查代码后发现已在评估前落地,deep-issue §1+§2+§6 描述滞后。

| 项 | 实地证据 | 落地状态 |
|---|---|---|
| **S1 trigger Spring Security**(原 deep-issue §1) | `batch-trigger/src/main/java/com/example/batch/trigger/config/TriggerSecurityConfiguration.java:42-46` 真起 `SecurityFilterChain` 把 `/actuator/**` 之外的请求强制 `authenticated()`;最早 commit `cd389a0b`(2026-04-22 v4 闭环) | ✅ 已落 |
| **S1 X-Console-Token**(原 deep-issue §2) | `legacyHeaderAuthEnabled` 在 `ConsoleSecurityProperties.java:38` 代码默认 `true`,但 `application.yml:67` 用 env `BATCH_CONSOLE_LEGACY_HEADER_AUTH_ENABLED:false` 覆盖默认关闭,注释"5.2: 默认关闭旧式 X-Console-Token 鉴权" | 🟡 deprecated,opt-in compat |
| **S2 `DefaultConsoleJobApplicationService` 拆分**(原 ADR-008 + deep-issue §6) | 该类现仅 **90 LOC**(纯 delegate);拆出 6 个兄弟类:`ConsoleJobOpsSupport`(407)、`ConsoleJobQueryService`(226)、`DefaultConsoleJobApprovalService`(192)、`DefaultConsoleJobRecoveryService`(230)、`DefaultConsoleJobTriggerService`(133) | ✅ 已拆 |

> **后续动作**:更新 `docs/analysis/deep-issue-analysis.md` §1+§2+§6,把这三项移到"已修"清单(原文称 v4 issue 全景但未滚版到 v5);hardening-backlog v6 同步反映。

### 🔴 仍未完成(评估口径,需复核)

1. **trigger → orchestrator 异步化**(deep-issue §4 同步 HTTP 桥)——把触发器纳入 outbox/Kafka 体系。仓库内 `grep -rn "trigger_outbox\|TriggerOutbox\|TriggerAsyncLaunch" --include="*.java" --include="*.sql"` **0 匹配**,`docs/architecture/adr/ADR-010*` 不存在。**未落。**
2. **ADR-009 Workflow DSL Stage 2-4**——`grep WorkflowParamResolver` 0 匹配;orchestrator/worker 里 `workflow_node_run.output` 写入点未找到。**仅 V72 落了 Stage 1 (output 列)**。

> 若上述判断与团队口径不一致(如 PR 待 merge / 在另一仓库 / 已合并但 grep 关键字不对),请提供线索或 commit ref,本节将更新。

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

### S3 — trigger → orchestrator 异步化(deep-issue §4,2-3 周)

**目标**:把触发器纳入主链路 outbox/Kafka 体系,消除同步 HTTP 桥的鲁棒性短板。

**子任务**:
1. 现状梳理:`DefaultTriggerService` / `DefaultScheduleForwarder` 当前同步 HTTP 调用 orchestrator 的 `/internal/launch/**` 接口;失败处理依赖 client retry。
2. 设计:加 `trigger_outbox_event` 表(或复用现有 outbox)+ `TriggerOutboxRelay` 周期 publisher 写到 Kafka topic `batch.trigger.launch.v1`;orchestrator 起 `TriggerLaunchConsumer` 消费同款入站契约。
3. 立 ADR-010(trigger-async-decoupling),记录 topic 协议、幂等键、retention 策略。
4. **灰度切换**:加 `batch.trigger.async-launch.enabled` 开关,默认 false;先 e2e 跑通,再生产灰度切。
5. 守护:`trigger.outbox.publish.lag` 指标 + alert;`TriggerAsyncLaunchE2eIT` 加 case。

**完成标志**:开关切到 true 后,trigger 重启不丢任何 launch;orchestrator 短暂宕机不阻塞 trigger。

### S4 — ADR-009 Workflow DSL Stage 2-4(2-3 sprint)

**目标**:已落 Stage 1(V72 加 `workflow_node_run.output` 列),继续 Stage 2-4。

**子任务**(按 ADR-009 路线图):
- **Stage 2**:worker 各 stage 把 output 写到 `workflow_node_run.output`(Map → JSONB);现在多数走 attributes,需要在 `AbstractStageExecutor.runStageLoop` 收口处统一刷出。
- **Stage 3**:`WorkflowParamResolver` 从 upstream node output 取值的 DSL(`{{node.<id>.output.<field>}}`),配套 grammar + parser + 单测。
- **Stage 4**:`SchedulePlanBuilder` 集成 resolver,把 DSL 在 launch T2 阶段解析成具体 task_payload;触发 e2e 跨节点参数传递场景。

**完成标志**:E2E 中可用 `wf_eod_process` 这种含 SETTLE → EXPORT → DISPATCH 的 workflow 完整跑通,各节点参数靠 DSL 串联,无需在 console 手写 templating。

### S5 — 次级清单(随手优化,不阻塞主线)

| # | 项 | 估时 | 触发 |
|---|---|---|---|
| a | I4 `buildContext` 模板抽取 | 0.5 天 | 等 4 个 `*JobContext` 出现共同基类时再做(避免现在强抽 scope creep) |
| b | i18n 测试债二轮扫荡 | 1 天 | 23137b2c 已收 9 处,但 `assertThat.hasMessageContaining` 这类断言全仓再扫一遍兜底 |
| c | trigger SecurityIntegrationTest 守护补齐 | 1 天 | S1 已落 Security,但若 CI 还没有"无 token → 401"守护测试,补一个;同款扩到 console/orchestrator |
| d | 真删 `X-Console-Token` 兼容路径 | 1-2 天 | 当所有前端都切到 JWT 后,从 `ConsoleSecurityProperties` / 应用 yaml / OpenAPI 彻底删除 legacy header 分支(不再 deprecated 而是物理删除) |

### 节奏建议(校正后)

```
Day 1 [必做]:S0 评估口径校正(deep-issue/backlog 滚版),解锁后续判断
Week 1-2:    S3 trigger 异步化(立 ADR-010 → 加 trigger_outbox → Kafka topic → 灰度切换)
Week 3-5:    S4 Workflow DSL Stage 2-4 按 sprint 节奏推进
S5 a/b/c/d 见缝插针,任意 1 天空档可清
```

每个里程碑结束更新 `hardening-backlog.md` + 在本目录追加新评估快照(命名 `project-assessment-YYYY-MM-DD.md`)。下次评估应同时滚 deep-issue-analysis 和 hardening-backlog,避免本次"评估口径滞后于代码"再发生。
