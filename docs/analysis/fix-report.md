# 深度问题修复报告

> 最近一次大批闭环：2026-04-21（V4 滚动版本，历史 V1-V3 见 [`../archive/analysis/`](../archive/analysis/)）
> 范围：多租户业务链路打通 + 关联代码 bug 修复
> 验证基线：所有修复在本地全链路真跑通（真表 / 真数据 / 真出站文件）+ 6 条新增单元测试全部通过 + 重启无回退

---

## 修复总览

| 维度 | 总数 | 代码修复 | 配置/种子修复 | 备注 |
|------|:----:|:--------:|:-------------:|------|
| 代码 Bug | 2 | 2 | 0 | trigger security + selector capability_tags |
| 运维/数据源配置 | 1 | 0 | 1 | worker-import JDBC URL 加 stringtype=unspecified |
| 种子错配 / 表缺失 | 3 | 0 | 3 | biz.transaction/risk_score/risk_alert 建表 + 4 模板补配置 |
| 噪声止血 | 4 | 0 | 4 | 禁用 4 条永远跑不通的 cron（暂时） |
| 场景端到端打通 | 3 | 0 | 3 | tb IMPORT / tc IMPORT / tc EXPORT 全部通过 |
| 能力验证 | 3 | 0 | 0 | RERUN / 多分片 / Dead-letter 回放 |
| **合计** | **16** | **2** | **11** | |

---

## 一、🐛 代码 Bug 闭环（2 条）

### V4-BUG-1 · Trigger 管理接口 `/api/triggers/management/*` 全线 HTTP 403

**文件**：`batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/TriggerSecurityConfiguration.java`

**症状**：`/list` / `/scheduler-status` / `/drain/status` 等端点返回 `HTTP 403 Content-Length: 0`。`bypass-mode=true` 本应让所有内部接口放行，但实际所有请求都被 Spring Security 拒绝。

**根因**：`InternalSecretFilter.setAuthenticated()` 使用 `AnonymousAuthenticationToken` 作为成功后的认证令牌。Spring Security 的 `anyRequest().authenticated()` 匹配器通过 `AuthenticatedAuthorizationManager#isGranted` → `trustResolver.isAnonymous()` 检测匿名并**主动拒绝**，导致 filter 内"认证成功"与 authorization 层"认证失败"语义冲突。

**修复**：改用 `UsernamePasswordAuthenticationToken.authenticated("internal", null, ROLE_INTERNAL)`。匿名→已认证切换。

```java
// 必须用非匿名令牌——Spring Security 的 .authenticated() 匹配器通过
// AuthenticatedAuthorizationManager#isGranted 调用 trustResolver.isAnonymous()，
// 任何 AnonymousAuthenticationToken 都会被判定未认证而返回 403。
var auth = UsernamePasswordAuthenticationToken.authenticated(
    "internal", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
SecurityContextHolder.getContext().setAuthentication(auth);
```

**验证**：重建 jar + 重启 trigger，`/list` 返回 20 条已注册 Quartz trigger；`/scheduler-status` / `/drain/status` 全部 200。

---

### V4-BUG-2 · `DefaultWorkerSelector` 忽略 `capability_tags` 数组

**文件**：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelector.java`

**症状**：worker_registry 的 `capability_tags` JSONB 字段里声明的多能力被 selector 完全忽略；resource_queue 带 tag 的 queue 只能匹配 `worker.resource_tag`（单值）相等的 worker，一旦多个 queue 要求不同 tag 就没法用一个 worker 兼顾。

**根因**：`matchesResourceTag` 只比较 `queue.resourceTag ⟂ worker.resourceTag`，没读 `worker.capabilityTags`。schema 里既给了数组字段，代码却没用上。

**修复**：扩展匹配规则——队列 tag 等于 `worker.resource_tag`（单值）**或**命中 `worker.capability_tags` 数组中任意一项（忽略大小写）均算匹配。畸形 JSON 降级为"不匹配"并 WARN，不让坏数据触发异常。

新增单元测试 `DefaultWorkerSelectorTest`（6 case）：无 tag / 单值命中 / 数组命中 / 大小写不敏感 / 双侧都不匹配 / 畸形 JSON 不崩。

---

## 二、⚙️ 配置 / 数据源修复（1 条）

### V4-CFG-1 · worker-import JDBC URL 加 `stringtype=unspecified`

**文件**：`batch-worker-import/src/main/resources/application-local.yml`

**症状**：`GenericJdbcMappedImportLoadPlugin` 通过 `ps.setObject(idx, value)` 绑定 String 形态的值到 `biz.transaction.amount`（NUMERIC）/ `biz.transaction.txn_date`（DATE），Postgres 报 `column "amount" is of type numeric but expression is of type character varying`。

**根因**：Postgres JDBC 默认把 `setObject(String)` 绑定为 `varchar`，不做隐式类型转换。这是所有 jdbc_mapped_import 含非字符串列的模板通病。

**修复**：business datasource URL 加 `?stringtype=unspecified`，让 Postgres 服务端按列类型自动 cast。

> 影响面：只影响 IMPORT 写 biz 表这条链路；ta 原来跑通是因为 biz.customer_account 所有列都是 varchar，没撞上。

---

## 三、🗄️ 种子数据补齐（6 条）

### 3.1 新建 3 张 biz 表（只在本地 `batch_business` 建，未入 `scripts/db/business/create_biz_tables.sql`）

| 表 | 列要点 |
|---|---|
| `biz.transaction` | tenant_id / txn_no / account_no / txn_type / amount NUMERIC(20,2) / currency_code / txn_date / remark；unique(tenant_id, txn_no) |
| `biz.risk_score` | tenant_id / entity_id / entity_type / score_value NUMERIC(10,2) / score_band / score_date / model_version；unique(tenant_id, entity_id, score_date) |
| `biz.risk_alert` | tenant_id / alert_id / entity_id / alert_type / severity / alert_date / description；unique(tenant_id, alert_id) |

**⚠️ 状态**：DB 重置后会丢；正确归宿是 `scripts/db/business/create_biz_tables.sql`，**不是 Flyway**（Flyway 在本项目只管平台库 schema）。见 `hardening-backlog-v4.md#V4-P0-1`。

### 3.2 更新 4 条 file_template_config（只存在于本地 DB）

| 租户/模板 | 改动 | 用途 |
|---|---|---|
| `tb / IMP-TRANSACTION-CSV` | 补 `query_param_schema.jdbcMappedImport`（schema/table/columnMappings/conflictColumns） | IMPORT 生效路径 |
| `tc / IMP-RISK-SCORE-JSON` | 补 `query_param_schema.jdbcMappedImport` | 同上 |
| `tc / EXP-RISK-ALERT-JSON` | 补 `default_query_sql`（含 `:tenantId` + `:batchNo` 占位符、`id` 列用于 ORDER BY）+ `query_param_schema.sqlTemplateExport` | EXPORT 生效路径 |
| `default-tenant / exp_settlement_csv_v1` | 换 SQL 指向 `biz.settlement_detail`（原来指 `biz.settlement` 不存在）+ 补 `sqlTemplateExport` + 重写 `field_mappings` | Workflow SETTLE 节点生效 |

**⚠️ 状态**：DB 重置后会丢；正确归宿是 `scripts/db/test-seed/platform_seed.sql`，**不是 Flyway**。

### 3.3 queue resource_tag 清理（已写入数据库）

`default-tenant` 的 `export_queue` / `workflow_queue` `resource_tag` 清空（worker 没有对应 tag 时永远卡 WAITING 的临时解法）。长期真正的修法是给 worker 注册 capability_tags（#V4-BUG-2 已补代码支持），但 worker 侧心跳上报链路还没改。

---

## 四、🔕 噪声止血（4 条禁用）

这 4 条 cron 作业在当前种子下**永远跑不通**，且失败会产生 dead_letter + FAILED instance + 重试风暴。临时禁用（`job_definition.enabled=false`）让观察通道清爽。

| 租户/作业 | 永远跑不通的原因 |
|---|---|
| `ta / TA_DISPATCH_ORDER` | cron FIXED_RATE 300s 周期触发，payload 里没有 `fileId`，`PrepareDispatchStep` 报 `DISPATCH_PREPARE_FILE_MISSING` |
| `tb / TB_IMPORT_TRANSACTION` ※ | 本来禁用，后来为验证 IMPORT 跑通又重新启用；可视为**已修复**（模板补好 + 表建好，cron 能通） |
| `tc / TC_EXPORT_RISK_ALERT` ※ | 本来禁用，后来为验证 EXPORT 跑通又重新启用；同上，可视为**已修复** |
| `default-tenant / gen_reconcile` | worker_group = GENERAL，但 worker_registry 里根本没注册 GENERAL worker |

※ 标记的两条已修复并验证。`TA_DISPATCH_ORDER` 和 `gen_reconcile` 仍保持禁用。

---

## 五、✅ 场景端到端打通（3 条）

| 场景 | 证据链 |
|---|---|
| **tb IMPORT**（TRANSACTION CSV） | manual launch → instance 112/113/116/128 全 SUCCESS；`biz.transaction` 3 行（每次 ON CONFLICT 覆盖，共 9 条记录试过） |
| **tc IMPORT**（RISK_SCORE JSON） | instance 117 SUCCESS；`biz.risk_score` 3 行 |
| **tc EXPORT**（RISK_ALERT JSON.gz） | instance 120 SUCCESS；生成 `file_record id=156` / `risk_alerts_{bizDate}.json.gz` / 792 bytes / status=GENERATED |

---

## 六、🧪 能力验证（3 条）

| 能力 | 验证结论 |
|---|---|
| **RERUN 触发语义**（V62 新加） | instance 128：`run_attempt=2 / parent_instance_id=116 / trigger_type=RERUN`，同 dedup_key 未被判重复；唯一键 `(tenant_id, dedup_key, run_attempt)` 三列方案正确工作 |
| **多分片**（STATIC，`partitionCount=3`） | instance 129：`expected=3 / success=3 / failed=0`；三条 job_partition 并行全部通过 |
| **Dead-letter 回放**（含审批流） | DL#111 → `/dead-letters/replay` 生成 approval `apr-...` PENDING → `/approvals/{no}/approve` 执行 → `dead_letter_task.replay_status: NEW → SUCCESS`，触发 compensation `cmp-...` |

---

## 七、基础设施运维事件

**事件**：trigger JVM (18081) 与 console JVM (18080) 一度进入"半死"状态：
- 所有 HTTP 请求返回 `HTTP 500 Content-Length: 0`，无栈
- 数小时零日志输出
- Quartz worker 线程全 `Object.wait()`，stdout/stderr 重定向到 `.log` 但文件未增长
- RSS 异常低（24 MB）—— macOS 过夜休眠后 JVM 未能正常恢复

**处理**：`./scripts/local/restart.sh console trigger` 重启即恢复。**非代码 bug**，属本地开发环境已知副作用。

---

## 文件改动清单

**代码改动**：
- `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/TriggerSecurityConfiguration.java`
- `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelector.java`
- `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelectorTest.java`（新增）
- `batch-worker-import/src/main/resources/application-local.yml`

**DB 改动落位状态**（见 hardening-backlog-v4#V4-P0-1）：
- ✅ 3 张新表：`biz.transaction` / `biz.risk_score` / `biz.risk_alert` → 已写入 `scripts/db/business/create_biz_tables.sql`（含索引 + CHECK 约束）
- ✅ 3 条 tb/tc 模板（`IMP-TRANSACTION-CSV` / `IMP-RISK-SCORE-JSON` / `EXP-RISK-ALERT-JSON`）`query_param_schema` / `default_query_sql` → 已写入 `batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql` 的 UPDATE 块
- ❓ `default-tenant/exp_settlement_csv_v1`（workflow SETTLE 用） → 源头不在 seed（live DB 是 created_by='system'，可能由 Console 上传/租户初始化服务产生），留到 P1-1 批次
- ❌ 2 条 `resource_queue` 行 `resource_tag` 清空 → 临时绕行，**不入种子**；根治靠 P0-2（worker 心跳带 capability_tags）
- ❌ 3 条 `job_definition` 行 `enabled=false` → 运维临时动作，**不入种子**

**重放验证**：drop 3 张 biz 表 → 跑 `create_biz_tables.sql` 重建；reset 3 条模板的 query_param_schema 为 NULL → 跑 multi-tenant-seed 的 UPDATE 块补回 → manual 触发 tb IMPORT → instance 163 SUCCESS，`biz.transaction` 真落行。

---

## 仍未闭环的问题

详见 [`./hardening-backlog.md`](./hardening-backlog.md)。三大块：

1. Workflow DAG 节点间参数 / 文件自动串联（SETTLE→DISPATCH）— 已部分修（Stage 1+1.2），剩 Stage 2-4
2. 种子治理（7 个空壳 workflow、本次 DB 改动未入 Flyway）
3. 未覆盖场景（非 SFTP dispatch、calendar、quota 压测、compensation 独立、workflow PIPELINE/MIXED 等）

---

## 八、2026-04-30 校正记录

口径校正：`project-assessment-2026-04-29` 评估当时 §5 部分项目仍标"未完成"，实际仓库代码已落地。下表为补录证据，deep-issue / hardening-backlog 同步对齐。

| 项 | 状态 | 证据 / commit |
|---|---|---|
| deep-issue §5.1 Trigger Security | 🟢 已修 | `cd389a0b`（2026-04-22 V4 闭环）；`TriggerSecurityConfiguration.java:42-46` 真起 `SecurityFilterChain` 把 `/actuator/**` 之外强制 `authenticated()` |
| deep-issue §5.2 X-Console-Token | 🟡 部分修 | `application.yml:67` `BATCH_CONSOLE_LEGACY_HEADER_AUTH_ENABLED:false` 默认关闭 compat 路径；真删动作详见归档 `project-assessment-2026-04-29.md` §8 S5-d |
| deep-issue §5.12 Console Job 过胖 | 🟢 已修 | `DefaultConsoleJobApplicationService` 现 90 LOC 纯 delegate，拆出 `ConsoleJobOpsSupport`(407) / `ConsoleJobQueryService`(226) / `DefaultConsoleJobApprovalService`(192) / `DefaultConsoleJobRecoveryService`(230) / `DefaultConsoleJobTriggerService`(133) 共 6 个兄弟类 |
| ADR-009 Stage 1.2 worker outputs 上报管线 | 🟢 已修 | `TaskExecutionReport.outputs` + `DefaultTaskExecutionWrapper.java:108-117` 透传 + `WorkflowNodeRunMapper.xml:84-85` 写 jsonb；`ImportStepExecutionAdapter.java:112` 已填 `NODE_OUTPUTS`（E/D/P 按需后补） |
| 半完成基类重构 | 🟢 已修 | `4e634c7c`（2026-04-29）：4×`ERROR_OBJECT_MAPPER` + 4×`loadConfiguredSteps` + 3×`handlePipelineFailure` 上提到基类，消除 ~150 行复制；FQN 违规修了 |
| i18n 业务路径收敛 | 🟢 已修 | `23137b2c`（2026-04-29）：56 文件 BizException 全量从 literal message 迁到 i18n key + args 三元组；9 个 test 同步 |
