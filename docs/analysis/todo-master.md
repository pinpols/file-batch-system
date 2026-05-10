# TODO Master · 全仓待办整合

> 整合范围：`docs/` 全部 md（analysis / runbook / architecture / design / compliance / changelog）+ 代码 `TODO` / `FIXME` / `@Deprecated forRemoval` 注解
> 维护规则见底部

---

## 一、状态总览

| 状态 | 计数 | 主要主题 |
|---|---:|---|
| ✅ **完成** | 62+ | 含 ADR-009/010/011 全栈 / EXCEL-GODCLASS 6/7 / WEBHOOK-DURABILITY / IDEMPOTENCY 三层 / FQN 全清 / Prometheus 告警 / NOISE 治理 / X-Console-Token 物删 / 12 旧 Excel 端点物删 + OpenAPI 同步 / Query factory + 守护测试 / POSITIONAL-ARGS v4 闭环；以及第 1 阶段 P0（ADR-012 / ADR-023 / ADR-025）+ 第 2 阶段 P1（ADR-021 / ADR-022 / ADR-026） |
| 🟡 **半成** | 3 | ADR-010 Stage 6/7 灰度（灰度门禁未到）· ADR-009 Stage 4 业务方触发 · WF-design-1/2 字段先决条件未就位 |
| ⏳ **待做** | 9 | 前端待办（ADR-018/020/026 Console UI）/ 横切（CI lint dry-run + audit/metric label）/ Quartz staging 7 项（🔒 ops/DBA/BIZ/staging）/ LIC-2 SBOM（🔒 ops 部分） |
| 🟡 **暂缓** | 2 | 第 3 阶段 ADR-024 冷热分层 / ADR-027 资源亲和（触发条件未达，priority-scope §5 守红线）|
| ❌ **不做** | 4 | V5-P2-1 / V5-P2-9 / V5-NEW-1 / V5-NEW-2（理由见各项）|

**环境分布**（可执行性切片）：

| 类别 | 计数 | 说明 |
|---|---:|---|
| ✅ **本地可独立完成** | ~24 | 剩余多数与外部环境 / 灰度门禁绑定 |
| 🔒 **本地不能做（挂起）** | 23 | 需 ops / staging / prod / DBA / BIZ 配合 — 集中列在 §九 |

---

## 二、ADR 优先级三阶段进展（priority-scope 镜像）

权威源：[`archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md`](../archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md) + 各 ADR 顶部"范围边界（Scope Discipline）"小节 + CLAUDE.md "ADR 实施范围纪律" 章。

### 第 1 阶段 P0（已落 backend）✅

| ADR | 主题 | 落地证据 |
|---|---|---|
| ADR-012 | 失败分类 | V111 |
| ADR-023 | 多日历联动 | V112-V114 |
| ADR-025 | Workflow 静态校验 | 15 条规则全员到齐（V1-V15） |

### 第 2 阶段 P1（已落 backend）✅

| ADR | 主题 | 落地证据 |
|---|---|---|
| ADR-021 | 数据对账 v1.0 | V118 + DataQualityCheckExecutor + EFFECTIVE gate |
| ADR-022 | Forensic v0.1 | V116 + 同步 bundle + SHA-256 |
| ADR-026 | dry-run 全链路 | V115 + V117 + DryRunGuard SPI + L1/L2/L3 service + SUCCESS_DRY_RUN / FAILED_DRY_RUN 终态 + L3 真接 SQL EXPLAIN / MinIO bucketExists / HTTP HEAD |

### 第 3 阶段（暂缓）

| ADR | 主题 | 触发条件未达 |
|---|---|---|
| ADR-024 | 冷热分层 | archive 行数未到阈值 |
| ADR-027 | 资源亲和 | worker_group ≥ 8 / 异构硬件 / 多机房 / 合规隔离 等条件未出现 |

### 前端待办（接口已就绪，前端可开干）⏳

| ID | 主题 | 后端契约 |
|---|---|---|
| **FE-1** | ADR-026 Console UI 演练模式 toggle | `POST /api/console/ops/dry-run/plan` 已开放 |
| **FE-2** | ADR-018 跨日 DAG Console UI | `ConsoleWorkflowNodeResponse.crossDayDependencies` 已返回 |
| **FE-3** | ADR-020 批次日重放 Console UI + ALL/ALL_FAILED/SUBSET E2E | 5 个 `/api/console/ops/batch-day-replay/sessions...` 端点已开放 |

### 横切关注点（未做）⏳

| ID | 主题 |
|---|---|
| **CC-1** | CI lint 守护：step plugin 必经 DryRunGuard |
| **CC-2** | audit + metric label 加 dry_run 维度 |

---

## 三、⏳ 待做

### A. POSITIONAL-ARGS 治理（V6-P2-POSITIONAL-ARGS）· P2 · ✅ 已闭环

> 状态：v4 已闭环，并行会话产出 + 守护测试到位。详细方案见 [`positional-args-cleanup-plan.md`](./positional-args-cleanup-plan.md)。CLAUDE.md "调用方约束" 子节由本方案沉淀。

历史详细计划项（POS-1 ~ POS-5）已全部完成，归 §五。

### B. Query Record 工厂 · P2 · ✅ 已闭环

QF-1/QF-2/QF-3 全部完成，包含守护测试 `QueryRecordConstructionConventionTest`。归 §五。

### C. ADR-009 Stage 4（业务方按需触发）· P2 deferred

| ID | 主题 | 来源 |
|---|---|---|
| **ADR9-S4** | 7 workflow 配 DSL 引用上游节点 output | hardening-backlog v6 / ADR-009-workflow-param-dsl.md |

> 状态：Stage 1 / 1.2 / 2 / 3 代码 ✅（`DefaultWorkflowNodeDispatchService.mergeNodeParams` 集成 `WorkflowParamResolver` + 4 worker `*StepExecutionAdapter` 填 `NODE_OUTPUTS`）。Stage 4 deferred — 现 seed 节点间 `mergeUpstreamPartitionOutputs` 自动透传 `fileId` 已够用，业务方设计跨节点参数串联时按 §10 文档配。

### D. ADR-010 灰度 + 物删 · P0/P1（V6-D-2 / V6-D-3）

> 🔒 本节全部 7 项需 ops / staging / prod 配合，本地不能独立完成 — 见 §九

| ID | 主题 | 来源 | 关联门禁 |
|---|---|---|---|
| **ADR10-S6-pre-1** | V80 migration 应用到目标环境 | trigger-async-launch-rollout:9 | 灰度前置 |
| **ADR10-S6-pre-2** | Kafka topic `batch.trigger.launch.v1` 已创建 | trigger-async-launch-rollout:10 | 灰度前置 |
| **ADR10-S6-pre-3** | orchestrator/trigger 镜像版本校验 | trigger-async-launch-rollout:11 | 灰度前置 |
| **ADR10-S6-pre-4** | orchestrator consumer group 状态校验 | trigger-async-launch-rollout:12 | 灰度前置 |
| **ADR10-S6-pre-5** | Prometheus 指标抓取验证 | trigger-async-launch-rollout:13 | 灰度前置 |
| **ADR10-S6-rollout** | staging → canary → prod 按 SOP 执行 | hardening-backlog v6 | operational |
| **ADR10-S7-removal** | 物理删除 `HttpOrchestratorTriggerAdapter` 及同步 HTTP 路径 | hardening-backlog v6 | 全量切稳定 1 minor 后 |

> 代码线索：`HttpOrchestratorTriggerAdapter.java:27` 已带 `@Deprecated(since="ADR-010 Stage 6", forRemoval=true)`。

### E. Quartz → HashedWheelTimer 切换收尾 · P0/P1

> 现状：phase 1 默认值已切到 `wheel`（changelog 2026-04-26），Quartz 仍保留作 opt-in 回退。要彻底删 codepath 需以下验证齐全。
> 🔒 本节 7 项需 ops/DBA/BIZ/staging 配合（QZ-pre-1/3 · QZ-prep-3 · QZ-stage-1/2/3 · QZ-rollback-2），其余本地可做 — 见 §九

| ID | 主题 | 来源 |
|---|---|---|
| **QZ-pre-1** | 业务方明确 cron 精度 SLA | quartz-replacement-design:848 |
| **QZ-pre-2** | cron 兼容性扫描确认 L/W/# 表达式为 0 | quartz-replacement-design:849 |
| **QZ-pre-3** | trigger_runtime_state schema DBA 评审 | quartz-replacement-design:850 |
| **QZ-pre-4** | trigger_request fire 唯一约束不冲突验证 | quartz-replacement-design:851 |
| **QZ-prep-1** | Redis ShedLock 在 trigger 模块就位 | quartz-replacement-design:855 |
| **QZ-prep-2** | Quartz `auto-start=false` 切换路径验证 | quartz-replacement-design:856 |
| **QZ-prep-3** | 4 个 Quartz health metric 在 Grafana 显示 | quartz-replacement-design:857 |
| **QZ-decision-1** | 集成测试矩阵全部通过 | quartz-replacement-design:861 |
| **QZ-decision-2** | 性能测试达标 | quartz-replacement-design:862 |
| **QZ-decision-3** | failover IT 100 次循环无双 fire / 漏 fire | quartz-replacement-design:863 |
| **QZ-stage-1** | Staging 环境跑 2 周无回归 | quartz-replacement-design:867 |
| **QZ-stage-2** | 生产灰度方案制定与验证 | quartz-replacement-design:868 |
| **QZ-stage-3** | 监控告警 3 项就位（QPS/lag/duplicate）| quartz-replacement-design:869 |
| **QZ-rollback-1** | 回滚配置项验证过 | quartz-replacement-design:873 |
| **QZ-rollback-2** | Quartz 数据迁回 SQL 验证 | quartz-replacement-design:874 |
| **QZ-rollback-3** | trigger_runtime_state 表保留不删 | quartz-replacement-design:875 |

### F. Workflow 设计规范 8 校验项 · P1

> 性质：设计 review checklist，非实施工作。Workflow 落库前 PR review 时勾选。

| ID | 主题 | 来源 |
|---|---|---|
| **WF-design-1** | GATEWAY 节点显式写 joinMode | workflow-dependency-guide:323 |
| **WF-design-2** | joinMode=N_OF 必须带 joinThreshold | workflow-dependency-guide:324 |
| **WF-design-3** | 非 START 节点至少一条入边 | workflow-dependency-guide:325 |
| **WF-design-4** | 非 END 节点至少一条出边 | workflow-dependency-guide:326 |
| **WF-design-5** | JOB 节点 related_job_code 有效性验证 | workflow-dependency-guide:327 |
| **WF-design-6** | CONDITION 边 condition_expr 必须配置 | workflow-dependency-guide:328 |
| **WF-design-7** | Workflow 不能有循环 | workflow-dependency-guide:329 |
| **WF-design-8** | workflow definition enabled=true | workflow-dependency-guide:330 |

### G. 删除策略决策清单 · P1 · ✅ 已落地为 PR 模板

5 项 checklist 嵌入 `.github/PULL_REQUEST_TEMPLATE.md` "涉及删除语义的接口" 段，reviewer PR-time 勾选即可。同模板顺带嵌 4 类常见 review checklist（console-api / 字典 / 方法参数 / i18n / 规范条款）。

### H. 合规收尾 · P3

| ID | 主题 | 来源 | 状态 |
|---|---|---|---|
| ~~**LIC-1**~~ | NOTICE 文件 copyright + 上游聚合完整化 | license-risk-assessment:171 | ✅ 2026-05-01 NOTICE 升级：从 16 行指针式扩到 ~110 行，聚合 Spring Boot/POI/Kafka/Flyway/MyBatis/MinIO/Logback/SLF4J 等 20+ 主要上游 attribution（满足 Apache-2.0 §4(d)）|
| **LIC-2** | SBOM 与第三方清单嵌入 artifact | license-risk-assessment:173 | 🔒 部分 ops（本地能改 maven，CI 注入 + artifact 校验需 ops），见 §九 |
| ~~**LIC-3**~~ | 新依赖 license 检查与约束 | license-risk-assessment:174 | ✅ 2026-05-01 `scripts/ci/check-dependency-licenses.sh` 落地 |

### I. Worker 灰度升级 runbook 验证 · P2

> 🔒 完整端到端验证需 staging worker 集群，本地仅能补 IT 模拟 — 见 §九

| ID | 主题 | 来源 |
|---|---|---|
| **WK-up-1** | drain 接口能否发起并查询 claimed-tasks | rolling-upgrade-workers:76 |
| **WK-up-2** | 超时后 Orchestrator 接管确认 | rolling-upgrade-workers:77 |
| **WK-up-3** | force-offline 紧急场景验证 | rolling-upgrade-workers:78 |

### J. @Deprecated forRemoval 物删积压 · P3

> 代码线索：grep `forRemoval=true`
> 🔒 DEP-1 需灰度门禁，DEP-3/4/5 已物删；DEP-2 本地可做 — 见 §九

| ID | 主题 | 代码位置 | 备注 |
|---|---|---|---|
| **DEP-1** | `HttpOrchestratorTriggerAdapter` 物删 | batch-trigger:HttpOrchestratorTriggerAdapter.java:27 | 同 ADR10-S7-removal |
| **DEP-2** | `BatchSecurityProperties.testingOpen` 物删 | batch-common:BatchSecurityProperties.java:45,51 | since=2026-04-19，1 minor 后移除 |
| ~~**DEP-3**~~ | `ConsoleAlertRoutingExcelController` 4 处旧端点物删 | batch-console-api | ✅ 2026-05-01 物删 + OpenAPI 同步 |
| ~~**DEP-4**~~ | `ConsoleFileTemplateExcelController` 4 处旧端点物删 | batch-console-api | ✅ 同上 |
| ~~**DEP-5**~~ | `ConsoleResourceQueueExcelController` 4 处旧端点物删 | batch-console-api | ✅ 同上 |

### K. 代码内 follow-up · P3

| ID | 主题 | 代码位置 |
|---|---|---|
| **EXT-1** | V5-P2-4-ext: JOB / BATCH compensation happy-path | `DefaultCompensationServiceTest.java:167`（`// TODO V5-P2-4-ext`） |

### L. 历史一次性失败修复 · P3 · ✅

`HIST-1` 4 个 E2E ConditionTimeout 失败已修（2026-05-01 校验：`docs/testing/e2e-coverage.md:151` "全套 E2E 无已知失败"）。

---

## 四、🟡 半成

### V6-P2-EXCEL-GODCLASS 6/7 完成

- 进度：6 个 god class 拆完平均 -67% LOC，1 个保留
- 保留项：`ConfigPackageExcelValidator` 874 LOC — 已是 single-purpose validator，内部 8 个 `validateXxxRows` 共享 cross-reference 数据，split 反而 fragment + overhead → 评估为"不拆"
- 来源：hardening-backlog v6
- 下一步：无（评估为完整状态，7 类标 6/7 是因第 7 类决定不动）

### V5-P1-1 ADR-009 Workflow DSL · 代码 100%，业务配置 0%

- 代码：Stage 1（V72）/ 1.2（worker outputs）/ 2（`WorkflowParamResolver` 160 LOC + 10 单测）/ 3（`mergeNodeParams` 集成）全栈
- 业务：Stage 4（7 workflow 配 DSL）deferred，等业务方触发
- 来源：hardening-backlog v6 / project-assessment v1

### ADR-010 Stage 6 灰度 / Stage 7 物删

- 状态：文档 + 代码 ✅，operational 0%
- 未做：见 §三-D
- 🔒 全部 7 项需 ops / staging / prod — 见 §九

### Worker 4 模块单测密度（V6-D-5）

- 未做：各 `Default*StageExecutor` + `*StepExecutionAdapter` 加 5-10 个单测
- 来源：hardening-backlog v6

---

## 五、✅ 已完成（简表）

> 维护：每月 review，把 ✅ 项移到 §归档（避免本节越写越长）

### deep-issue §5（全 6 项 ✅）

- §5.1 trigger Spring Security：`cd389a0b`（2026-04-22）— `TriggerSecurityConfiguration:42-46` 真起 SecurityFilterChain
- §5.2 X-Console-Token：✅ commit `ff20c36f` 主代码 + yaml + OpenAPI + 测试 9 文件 +20/-168 物理删除；grep 全仓 0 残留
- §5.5 / §5.6 / §5.10 idempotency 三层边界：ADR-011 定稿，3 层代码已实施
- §5.7 trigger → orchestrator 异步：ADR-010 全栈 7 stage（`9587b8bf` / `087f6b7a` / `1ca3a957` / `22b330ea` / `788b637d` / `68bc49e8`），22 测试全绿
- §5.11 webhook durability：V81 + `WebhookDeliveryRelay` 278 行 + 7 单测全绿
- §5.12 Console Job god-class：`DefaultConsoleJobApplicationService` 现 90 LOC + 6 兄弟类 1278 LOC

### ADR 路线图（5 完成 / 2 deferred / 2 暂缓）

- ✅ **ADR-009** Workflow DSL Stage 1 / 1.2 / 2 / 3 全栈
- ✅ **ADR-010** trigger 异步 Stage 1-5 代码 100%
- ✅ **ADR-011** idempotency boundary 定稿
- ✅ **ADR-012** 失败分类（V111）
- ✅ **ADR-021** 数据对账 v1.0（V118 + DataQualityCheckExecutor + EFFECTIVE gate）
- ✅ **ADR-022** Forensic v0.1（V116 + 同步 bundle + SHA-256）
- ✅ **ADR-023** 多日历联动（V112-V114）
- ✅ **ADR-025** Workflow 静态校验（15 条规则 V1-V15）
- ✅ **ADR-026** dry-run 全链路（V115 + V117 + DryRunGuard SPI + L1/L2/L3 service）
- 🟡 ADR-009 Stage 4 deferred / ADR-010 Stage 6+7 灰度+物删 deferred
- 🟡 ADR-024 / ADR-027 暂缓（触发条件未达）

### v2 评估 4 项硬化（全 ✅）

- V6-OPS-1 Kafka topics（含 `scripts/ci/validate-kafka-topics.sh` 守护，三向 diff）
- V6-OPS-2 Prometheus 3 告警：`0c623eb0`
- V6-Q-1 9 处 FQN 违规：`8dc6eac1`
- V6-NOISE-1 运行日志噪声治理：`aa249bf8` / `0d650fab`

### v6 P2 god-class 拆分（全 ✅）

- V6-P2-WEBHOOK-DURABILITY：`b74e0a0c`
- V6-P2-ORCHESTRATOR-GODCLASS：`7d6faad6`（`DefaultTaskOutcomeService` 926→795 / `DefaultWorkflowNodeDispatchService` 840→371）
- V6-P2-EXCEL-GODCLASS：6/7 拆完平均 -67% LOC（`002b8864` + `bd0f0532` + `b9eefb47`）
- V6-P2-CONSOLE-IDEMPOTENCY：3 层代码各自归位
- V6-P2-POSITIONAL-ARGS v4：闭环 + 守护测试到位

### Query Record 工厂（QF-1 / QF-2 / QF-3 ✅）

12+ Query record 静态工厂补齐 + `QueryRecordConstructionConventionTest` 守护到位 + ~25 处 `new XxxQuery(t, null, null, null, ...)` call site 替换完成。

### v5 历史 P0-P3（19 项 ✅）

详见 [`hardening-backlog.md`](./hardening-backlog.md) §四（已完成 v5 清账表）。

---

## 六、❌ 不做（归档）

| ID | 主题 | 理由 |
|---|---|---|
| V5-P2-1 | 6 渠道非 SFTP dispatch 单 adapter IT | 业务接入对应渠道时再做，无前置 |
| V5-P2-9 | Workflow PIPELINE/MIXED + GATEWAY/FILE_STEP 节点 | 依赖 V5-P1-1 完整落地后再做（现 ADR-009 Stage 4 也 deferred）|
| V5-NEW-1 | workflow steps 协议错位 | 不构成 bug — worker 代码不读 task_payload.steps，4-24 commit `3dbb6d22` 修了 resolveJobCode + node_params 后未复现 |
| V5-NEW-2 | exp_settlement_csv_v1 模板源头 | default-tenant 7 个 "system" 模板之一，业务无引用，不影响主链路 — 历史遗留 + 不影响，不再追溯 |

---

## 七、维护规则

1. **新发现待办**：加进对应主题段（A-L），用编号 `<主题缩写>-<序号>`
2. **状态变更**：
   - ⏳ → 🟡：开干时改
   - 🟡 → ✅：验收门禁通过（单测 / IT / 灰度后）
   - ✅：本节保留 1 个月，然后归档到 hardening-backlog
3. **每月 review**：用 grep + 真实代码状态校验每条，避免"顶部已完成 / 明细未更新 / 引用悬空"的不一致
4. **删除 / 不做**：进 §六，带理由
5. **源文档变更同步**：hardening-backlog / deep-issue / project-assessment 状态变更时，同步本文件；反向不做（本文件是聚合视图，不取代源文档）

---

## 八、对账校验

| 校验项 | 命令 | 说明 |
|---|---|---|
| Query record 工厂数 | `for f in $(find docs/query/*.java); do grep -c 'public static.*\bof' $f; done` | 已闭环，详见 §五 Query Record 工厂段 |
| 代码 TODO 总数 | `rg '\b(TODO\|FIXME\|XXX\|HACK)\b' --glob '*.java'` | 1 处（`DefaultCompensationServiceTest:167` V5-P2-4-ext，详见 §三-K）|
| `@Deprecated forRemoval=true` | `rg 'forRemoval' --glob '*.java'` | 5 类（HttpOrchestratorTriggerAdapter / BatchSecurityProperties / 3 个 ExcelController），后 3 个已物删 |
| 多 null 占位 inline new（≥2 null）| `rg -nU --multiline 'new \w+\([^)]*null[^)]*null[^)]*\)' --glob '*.java'` | POSITIONAL-ARGS v4 闭环后回归 0；新增由守护测试拦截 |

---

## 九、🔒 本地不能做（需 ops / staging / DBA / 业务方 配合）

> 用途：本仓库内的 Claude / 开发者**无法独立完成**的项，挂在这里直至外部条件就绪。
> 图例：每条标注阻塞类型 — `[ops]` 部署/CD · `[staging]` 预发环境 · `[prod]` 生产环境 · `[DBA]` 数据库变更评审 · `[BIZ]` 业务方决策 · `[client]` 外部 API 客户端确认

| ID | 主题 | 阻塞类型 | 卡在哪 |
|---|---|---|---|
| **ADR10-S6-pre-1** | V80 migration 应用到目标环境 | `[ops]` | 需在 staging / prod 跑 flyway，本地仓库只能确认 SQL 文件 |
| **ADR10-S6-pre-2** | Kafka topic `batch.trigger.launch.v1` 创建 | `[ops]` | 需 staging / prod Kafka 集群操作 |
| **ADR10-S6-pre-3** | orchestrator/trigger 镜像版本校验 | `[ops]` | 需 CD 系统拉取镜像 tag |
| **ADR10-S6-pre-4** | orchestrator consumer group 状态校验 | `[staging]` | 需 staging Kafka 集群 |
| **ADR10-S6-pre-5** | Prometheus 指标抓取验证 | `[staging]` | 需 staging Prometheus + 抓取目标 |
| **ADR10-S6-rollout** | staging → canary → prod 灰度执行 | `[ops][staging][prod]` | 按 `trigger-async-launch-rollout.md` SOP，需真部署环境 |
| **ADR10-S7-removal** | 物理删除 `HttpOrchestratorTriggerAdapter` | `[prod]` 灰度门禁 | 需 ADR10-S6 灰度全量切稳定 1 minor 后才能删 |
| **DEP-1** | `HttpOrchestratorTriggerAdapter` 物删 | `[prod]` 灰度门禁 | 同上，本质同一项 |
| **QZ-pre-1** | 业务方明确 cron 精度 SLA | `[BIZ]` | 需业务方答复"是否容忍 ±100ms 精度差异" |
| **QZ-pre-3** | trigger_runtime_state schema DBA 评审 | `[DBA]` | 需 DBA 团队 review |
| **QZ-prep-3** | 4 个 Quartz health metric 在 Grafana 显示 | `[staging]` | 需 staging Grafana 配 dashboard |
| **QZ-stage-1** | Staging 环境跑 2 周无回归 | `[staging]` | 需 staging 真跑 + 观察期 |
| **QZ-stage-2** | 生产灰度方案制定与验证 | `[ops][prod]` | 需运维制定灰度 SOP |
| **QZ-stage-3** | 监控告警 3 项就位（QPS/lag/duplicate）| `[staging]` | 需 staging 监控 |
| **QZ-rollback-2** | Quartz 数据迁回 SQL 验证 | `[staging]` | 需迁移演练环境（有真 Quartz QRTZ_* 表）|
| **WK-up-1** | drain 接口能否发起并查询 claimed-tasks（完整验证）| `[staging]` | 本地可补 IT 模拟，完整端到端验证需 staging worker 集群 |
| **WK-up-2** | 超时后 Orchestrator 接管确认（完整验证）| `[staging]` | 同上 |
| **WK-up-3** | force-offline 紧急场景验证（完整验证）| `[staging]` | 同上 |
| **LIC-2** | SBOM 嵌入 artifact + 第三方清单 | `[ops]` 部分 | 本地能改 maven 配置，但 CI 注入 + artifact 校验需 ops |

**小计**：~19 条挂"本地不能做"，其中 ADR-010 灰度 7 + Quartz 切换 7 + Worker 灰度 3 + LIC 1 + 其他 1。

剩余（本地可独立完成）：FE-1/2/3 / CC-1/2 / EXT-1 等少量短期项。
