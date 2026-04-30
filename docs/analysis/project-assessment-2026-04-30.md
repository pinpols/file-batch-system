# 项目工程深度评估报告 v2

> **产出日期**:2026-04-30
> **基线对比**:[`project-assessment-2026-04-29.md`](./project-assessment-2026-04-29.md) v1(整体 7.8/10)
> **评估范围**:全仓库静态评估,聚焦 **2026-04-29 ~ 2026-04-30 这 24h 窗口的演进 delta**
> **评估方法**:量化 grep / 模块依赖矩阵 / 跨模块边界核查 / ADR 落地实地验证 / 3 个并行 agent 分维评估
> **不包含**:动态压测、生产流量回放;Docker daemon 偶发故障未真实验证容器重启效果

---

## 0. 24h 演进定量

| 维度 | 4-29 | 4-30 | Δ |
|---|---|---|---|
| 总 main LOC | ~106K | ~107.7K | **+1.7K** |
| 测试类总数 | 347(302+26+19) | **355**(307+28+20) | **+8** |
| Flyway 最高版本 | V79 | **V80** | +1(`trigger_outbox_event`) |
| ADR | 9 | **10**(+ADR-010) | +1 |
| Runbook | 23 | **25**(+`trigger-async-launch-rollout` + 已有 `feature-switches`) | +2 |
| ops 脚本 | 10 | **11**(+`heal-zombie-pipelines.sh`) | +1 |
| 24h 累计 commit | — | **31** | — |
| 主要演进 | — | ADR-010 trigger 异步化 7 stage 全栈 + 噪声治理 + ADR-009 Stage 2/3 校正 | — |

**质量指标**:
- worker 直写状态表:**0 匹配** ✅
- console-api 直写 outbox_event:**0 匹配** ✅
- JPA/Hibernate 引用:**0** ✅
- `Propagation.MANDATORY` 同事务约束:2 处(TaskDispatchOutboxService) ✅

---

## 1. 架构与模块边界 — **8.5/10**(↑ 0.5)

### ✅ ADR-010 全栈消除最后一处架构裂缝

4-29 评估的"trigger → orchestrator 同步 HTTP 桥"(deep-issue §5.7)曾是整个主链路上**最后一处**与"DB → Outbox → Kafka"主纲领不一致的环节。本会话 24h 内 7 stage 全栈完成:

| Stage | 完成证据 |
|---|---|
| 1 | `db/migration/V80__create_trigger_outbox_event.sql` |
| 2 | `batch-trigger/.../application/TriggerOutboxRelay.java` 224 行 + 7 单测 |
| 3 | `batch-trigger/.../service/DefaultTriggerService.java:202-225` 异步分支 + 灰度开关 |
| 4 | `batch-trigger/.../infrastructure/mq/KafkaTriggerEventPublisher.java` + `batch-orchestrator/.../application/trigger/TriggerLaunchConsumer.java` |
| 5 | **22 测试全绿** = 9 单测 + 7 relay + 4 trigger E2E + 2 跨模块 E2E |
| 6 | `docs/runbook/trigger-async-launch-rollout.md` 完整 SOP |
| 7 | `HttpOrchestratorTriggerAdapter` `@Deprecated(forRemoval=true)` + DefaultTriggerService 首次进入 deprecation WARN |

主链路从 trigger fire 到 worker REPORT 现已**全程异步解耦**,trigger 重启不丢 launch / orchestrator 短暂宕机不阻塞 trigger Quartz 线程。

### ✅ 4-29 deep-issue 6 项现状对照

| # | 4-29 状态 | 4-30 实地核查 |
|---|---|---|
| §5.1 trigger Security | 标"未完成" | ✅ `TriggerSecurityConfiguration.java:42-46`(`cd389a0b` 早前已落,4-29 评估时漏看) |
| §5.2 X-Console-Token | 标"未完成" | ✅ `ff20c36f` 物理删除 9 文件 -148 行,grep 全仓 0 残留 |
| §5.7 trigger→orchestrator 同步桥 | 标"未完成" | ✅ ADR-010 全栈,见上 |
| §5.12 Console Job 过胖 | 标"未完成" | ✅ 主类 90 LOC + 6 兄弟类(本会话 grep 验证)|
| §5.5 Console 幂等不一致 | 中 | 🟡 未触 |
| §5.11 Webhook durability | 中 | 🟡 未触 |

**4-29 评估累计 4 处口径滞后(漏看 + 误判),本次 v2 已实地 grep 全部核查**。

### ⚠️ 风险点

1. **新发现 console-api Excel god class 系列**(4-29 未点名):
   - `DefaultConsoleWorkflowExcelApplicationService` **1512 LOC**
   - `DefaultConsolePipelineDefinitionExcelApplicationService` **1061 LOC**
   - `DefaultConsoleBusinessCalendarExcelApplicationService` **1009 LOC**
   - `DefaultConsoleJobDefinitionExcelApplicationService` 887 LOC
   - `ConfigPackageExcelValidator` 873 LOC
   - `DefaultConsoleTenantConfigPackageExcelApplicationService` 846 LOC
   - `DefaultConsoleTenantConfigInitApplicationService` 823 LOC

   一组 7 个 800+ LOC 的 Excel 处理类。原 `DefaultConsoleJobApplicationService` god class 已拆,但 Excel 系列没动 → **下一轮 ADR-008 god-class-decomposition 工作的主战场**。

2. **`DefaultTaskOutcomeService` 926 LOC** + **`DefaultWorkflowNodeDispatchService` 840 LOC**:orchestrator 内业务编排核心,持续胀大,需排期拆。

3. **`ImportDataQualityService` 809 LOC**:worker-import 内,质量校验逻辑集中度高。

---

## 2. 代码质量 — **7.7/10**(↑ 0.2)

### ✅ 本会话治理成果

| 项 | 状态 |
|---|---|
| 4-29 半完成重构 I1-I4 + FQN | I1/I2/I3 + FQN 修了(`4e634c7c`),I4 deferred |
| i18n 业务路径收口 | 56 文件 BizException 全量迁 i18n key + 9 测试同步(`23137b2c`) |
| 运行日志噪声治理 | `aa249bf8` ChannelConfigMerge `LEGACY_REDUNDANT_KEYS` + FileGovernance `processingDelayMaxAgeSeconds` |
| ADR-010 实施代码新增 | trigger +600 LOC main + +800 LOC test;orchestrator +600 LOC main + ~400 LOC test;无新违规 |

### 🔴 残留违规(本次 v2 grep 实测)

**FQN 违规 9 处**(4-29 时 1 处,本次扫到 9 — 部分是 4-29 漏扫,部分是 i18n 迁移期间引入):

| 文件:行 | 违规 |
|---|---|
| `BizExceptionUtils.java:69` | `com.example.batch.common.enums.ResultCode.SYSTEM_ERROR` |
| `ConsoleAuthenticationFilter.java:93` | `com.example.batch.common.enums.ResultCode.UNAUTHORIZED` |
| `ConsoleAuthenticationFilter.java:116` | `com.example.batch.common.enums.ResultCode.FORBIDDEN` |
| `ConfigPackageExcelValidator.java:855` | `com.example.batch.common.utils.ConsoleTextSanitizer.normalize` |
| `PartitionLifecycleService.java:17` | `com.example.batch.common.enums.PartitionStatus.CREATED` |
| `PlatformFileRuntimeRepository.java:209/251/268/290` | 4 处 `com.example.batch.common.enums.PipelineRunStatus.*` |

**`ZoneId.systemDefault()` 真违规 1 处**:`QuotaResetPolicy.java:36`(已 `@Deprecated`,但仍在用);其它 4 处都是 `BatchTimezoneProperties/Provider` 的 javadoc 注释,不算违规。

**`Charset.forName("UTF-8")` 真违规 0 处**:唯一匹配是 `EncodingUtils.java:15` 的注释"禁用 Charset.forName"指引,非违规。

**JPA / Hibernate**:0 ✅

### 🟡 复杂度热点(>500 LOC class 共 10 个)

见 §1 风险点。Excel 系列 + orchestrator 核心 service + worker 数据质量服务三组,均超 800 LOC。

---

## 3. 测试体系 — **8.2/10**(↑ 0.2)

### ✅ ADR-010 双层 E2E 闭合

- **Layer 1**(`batch-trigger/.../integration/TriggerAsyncLaunchE2eIT`):4 个 @Test 真起 PG+Kafka 容器,断言 outbox 状态机 + Kafka topic 真投递 + envelope 字段精确 + 反序列化失败 GIVE_UP + crash recovery
- **Layer 2**(`batch-e2e-tests/.../TriggerAsyncLaunchFullChainE2eIT`):2 个 @Test 真起 orchestrator + Kafka,验证 consumer→LaunchApplicationService→job_instance INSERT + 同 envelope 投 3 次只产生 1 instance(uk_job_instance_tenant_dedup 兜底)
- **配套**:`E2eTriggerApplication` scaffold 落盘 + batch-e2e-tests pom 加 trigger 依赖 + batch-trigger pom spring-boot exec classifier
- **TriggerOutboxRelayTest 7 单测**:边界覆盖扎实(空批/成功/失败/反序列化/抢占/异常隔离 + 退避函数 0-10 全梯度)
- **TriggerSecurityFilterTest 5 守护**:无 token / 错 header / 对 header / actuator 跳过 / bypass-mode

### 🟡 残留缺口(test agent 实测)

1. **Worker 4 模块单测密度仍低**:本会话改了 4 个 `Default*StageExecutor` + 4 个 `*StepExecutionAdapter`,但配套单测未补
2. **`E2eTriggerApplication` scaffold 无用例**:已落盘但暂无 IT 真起本 application,trigger+orchestrator 双 ApplicationContext 全链路 E2E 仍是空缺
3. **Testcontainers 真容器 IT 比 11.8% → ~16%**(微升):大量 `@SpringBootTest` 用 mock 而非真容器
4. **覆盖率门禁 25% LINE 未调高**:注释承诺"6 月内到 40%"但无 ratchet 机制
5. **SQL CI 无 schema-diff 步骤**:Flyway 仅在 IT 启动时跑,新增列但 Mapper ResultMap 漏改的问题只有运行时才暴露

### 守卫机制完整

`ConsoleMetaEnumRegistrationTest` 4 个 @Test、`ExportFileVerifier` / `DispatchReceiptVerifier` 产物验收、4 套 GitHub workflow gate(pr / full-ci / staging / capacity)、JaCoCo 25% LINE 强制阻断 — 都在用。

---

## 4. 运维就绪度 — **8.5/10**(↑ 0.5)

### ✅ 新增资产

- `docs/runbook/trigger-async-launch-rollout.md` 280 行 staging→canary→prod 三阶段 SOP + 回滚预案 + 24h 对账 SQL
- `docs/runbook/feature-switches.md` `batch.trigger.async-launch.enabled` §3.7 已收录
- `scripts/ops/heal-zombie-pipelines.sh` + `make ops-heal-zombie-pipelines` target 闭环 zombie 治理
- V80 `trigger_outbox_event` schema 含 UK 防重 + partial index `(status, next_publish_at)` 热点
- `docker-compose` trigger 段补 `BATCH_KAFKA_BOOTSTRAP_SERVERS` env + `depends_on: kafka`(smoke test 发现的 bug 已修)

### 🔴 高优先 ops 缺口(运维 agent 锁定 5 项)

| 优先级 | 缺口 | 证据 |
|---|---|---|
| **P1** | `.env.prod:16` 的 `KAFKA_TOPICS` **缺 `batch.trigger.launch.v1`**(只有 6 topic) | 与 `.env.example:59` 不一致;生产切异步开关后 relay 全 FAILED |
| **P1** | Prometheus 3 条 ADR-010 告警未落 | `TriggerOutboxBacklogGrowing` / `TriggerLaunchFailureSpike` / `TriggerOutboxGiveUp` 仅在 runbook §建议;`prometheus-batch-rules.yml` 第 231 行结束 |
| **P2** | Helm trigger deployment 未显式声明 `BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED` | 依赖 yml fallback 静默激活;helm diff 看不到开关变化 |
| **P2** | `hardening-backlog.md` 仍 v5(2026-04-26)未滚 v6 | ADR-009 / ADR-010 全栈完成不反映;重蹈 v4→v5 顶部已完成但明细不对齐的历史错误 |
| **P3** | `.github/workflows/pr-gate.yml:94-127` **缺 `batch-worker-process` case 分支** | process worker 变更无精确作用域检测,在最活跃路径上 CI 配置漂移 |

### 文档体系

- ADR 10 条(+ADR-010)
- 25 篇 runbook(+`trigger-async-launch-rollout` + 已有 `feature-switches`)
- `docs/changelog.md:9-13` 有 2026-04-30 三条记录覆盖 async-launch / i18n / Workflow DSL
- `deep-issue-analysis.md:125/160/421` §5.1/§5.2/§5.12 头部已标已修,与代码事实基本对齐

---

## 5. 综合评分

| 维度 | 4-29 v1 | 4-30 v2 | Δ | 关键变化 |
|---|---|---|---|---|
| 架构与模块边界 | 8 | **8.5** | +0.5 | ADR-010 消除最后一处同步桥;但发现 console-api Excel 系列 god class 群 |
| 代码质量 | 7→7.5 | **7.7** | +0.2 | 半完成重构收尾 + i18n + 噪声治理;残留 9 处 FQN 违规需修 |
| 测试体系 | 8 | **8.2** | +0.2 | trigger 异步链路双层 E2E 闭合;worker 单测密度仍稀,真容器比 16% |
| 运维就绪度 | 8 | **8.5** | +0.5 | ADR-010 runbook + zombie 脚本;`.env.prod` topic 缺 + Prometheus 告警未落是阻塞项 |

**整体 7.8 → 8.2**(+0.4)— 24h 窗口内 ADR-010 全栈是结构性提升;一致性短板从"主链路最后一处同步桥"挪到"console-api Excel god class 群 + ops 边界配置漂移"。

---

## 6. 下一步计划(优先级 v2)

### 🔴 上线前阻塞(P1,~1-2 天)

1. **修 `.env.prod:16` 加 `batch.trigger.launch.v1` topic** — 5 分钟改动,不修生产切开关 100% 故障
2. **加 Prometheus 3 条 ADR-010 告警**(`TriggerOutboxBacklogGrowing` / `TriggerLaunchFailureSpike` / `TriggerOutboxGiveUp`)— 从 runbook §建议拷到 `prometheus-batch-rules.yml`,半天

### 🟡 主线工作(P2,2-4 周)

3. **拆 console-api Excel god class 系列**(7 个 800+ LOC 类)— ADR-008 god-class-decomposition 第二战场,单独排 sprint
4. **拆 `DefaultTaskOutcomeService`(926)+ `DefaultWorkflowNodeDispatchService`(840)** — orchestrator 核心 service,影响面大需谨慎
5. **滚 hardening-backlog v6** + 头部"P0/P1 全部完成"重新校准 — 2 小时
6. **修 9 处 FQN 违规** — 半天机械修改
7. **ADR-010 灰度切换 operational** — staging → canary → prod,按 runbook 执行

### 🟢 渐进改善(P3,持续)

8. **Worker 4 模块单测密度补齐** — `Default*StageExecutor` + `*StepExecutionAdapter` 各加 5-10 个单测
9. **覆盖率门禁 25% → 35%** — 加 ratchet 机制每 sprint 提一档
10. **`E2eTriggerApplication` scaffold 真用例** — trigger+orchestrator 双 ApplicationContext 同 JVM 全链路
11. **SQL CI schema-diff 步骤** — 加 `flyway:validate` 到 pr-gate
12. **修 `pr-gate.yml` `batch-worker-process` case 分支**

### 节奏建议

```
Day 1 [必做]: P1 .env.prod + Prometheus 告警(1-2 天 → 解锁灰度)
Day 3-5:    P2-5 hardening-backlog v6 + P2-6 FQN 修(快赢)
Week 2-3:   P2-7 ADR-010 灰度切换 staging → canary → prod
Week 4+:    P2-3 / P2-4 god class 拆分(需独立 ADR + 多 PR)
P3:         背景渐进,每 sprint 抽 1-2 项
```

---

## 6.5 第三方 agent 复评新发现(本次 v2 补录)

架构/代码质量 agent(后台跑 ~21 分钟才回)报告 3 个 4-29 v1 与本次 §1-§4 grep 都漏的项:

| # | 发现 | 核实 | 处理 |
|---|---|---|---|
| 1 | `messages.properties` 缺 3 个 worker i18n key | ❌ **误报** — 实际两个 properties 文件都已补齐 | 不修 |
| 2 | `DefaultExportStageExecutor.java:107` 中文 fallback `"找不到步骤实现:"` 与 Import/Dispatch 英文不一致 | ✅ 真问题 | 本次 commit 直接修(5s 改) |
| 3 | **Webhook 无 scheduler 重试** — `WebhookDeliveryLogEntity.nextRetryAt` 字段存在但无 `@Scheduled` driver,delivery_log 只审计不驱动重投 | ✅ 真问题 | deferred 到下个 sprint(deep-issue §5.11 闭环候选) |

**Webhook durability 升级建议**:参照 ADR-002 outbox 模式或 ADR-010 trigger_outbox 模式,加 `WebhookDeliveryRelay @Scheduled` 周期扫 `next_retry_at <= now()` 的失败行重投,达到上限标 GIVE_UP + alert。预估 2-3 天工作。本项 deep-issue §5.11 应同时滚到 hardening-backlog v6 + 排独立 PR。

---

## 7. 校正历史(累计第 8 轮)

> 本评估文档自 2026-04-29 v1 至 2026-04-30 v2 累计 8 轮校正,平均每天 1+ 轮:

1. ~~S1 trigger Security 未完成~~ → 实际 `cd389a0b` 已落
2. ~~S2 god service 拆分未完成~~ → 实际已拆 6 兄弟类
3. ~~ADR-009 Stage 2/3 未完成~~ → 实际已落
4. ADR-009 Stage 1.2 全栈本会话真正落地
5. ADR-010 trigger 异步解耦 7 stage 落地
6. ADR-010 Stage 5 双层 E2E 清账(本会话 22 测试全绿)
7. 运行日志噪声治理(本会话 ChannelConfigMerge + FileGovernance)
8. **本次 v2 全维实地 grep 复评** — 锁定 P1 ops 缺口 / Excel god class 群 / 9 FQN 违规
9. **v2 P1+P2 一把过清账**(2026-04-30 下午):本会话 + 并发 session 协作 — `0c623eb0` Prometheus 3 条 ADR-010 告警从 runbook §建议落 `prometheus-batch-rules.yml`;`8dc6eac1` 9 处 FQN 违规 5 文件批量改;`6d977766` `hardening-backlog` 滚 v6;**并发 `b74e0a0c`** 一把过 V6-P2-WEBHOOK-DURABILITY 全栈(V81 + Relay 278 行 + 7 单测 + Prometheus 告警) + V6-P2-ORCH-GODCLASS 部分(`DefaultTaskOutcomeService` 926→795 LOC -14%)+ V6-P2-EXCEL-GODCLASS 部分(`DefaultConsoleWorkflowExcelApplicationService` 1512→1074 LOC -29%)。**剩余 follow-up**:6 个 Excel god class(PipelineDef 1061 / BusinessCalendar 1009 / JobDef 887 / ConfigPackage 873 / TenantConfigPackage 846 / TenantConfigInit 823)+ `DefaultWorkflowNodeDispatchService` 840 拆分 + Console idempotency 三层边界设计

> **教训**:评估口径必须以"全栈 grep + 单测全绿 + commit ref"为权威,不能依赖滚动文档单向声称。下次评估应在剩余 6 个 Excel god class + `DefaultWorkflowNodeDispatchService` 拆完 + Console idempotency 设计落地后再做(预计 2026-05-中旬)。

---

## 8. 与其他文档的分工

| 文档 | 关系 |
|---|---|
| `project-assessment-2026-04-29.md` | v1 基线;本 v2 引用其结构 + 7 轮校正历史 |
| `deep-issue-analysis.md` | §5.1/§5.2/§5.12 头部已标已修;§5.5/§5.11 仍标 |
| `hardening-backlog.md` | 仍 v5,需滚 v6(列在 §6 P2-5) |
| `fix-report.md` | §八 2026-04-30 校正补录已记录 |
| `ADR-010-trigger-async-decoupling.md` | 7 stage 路线图实施事实源 |
| `runbook/trigger-async-launch-rollout.md` | 灰度切换 SOP |

下次评估快照建议命名 `project-assessment-2026-05-NN.md`,在 ADR-010 灰度全量切完 + Excel god class 拆完后做。
