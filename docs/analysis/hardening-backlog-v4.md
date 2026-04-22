# 🛡 硬化与遗留问题 Backlog · v4

> 产出日期：2026-04-21
> 基准：`docs/analysis/fix-report-v4.md` 本轮已修清单之外的遗留
> 用途：v5 治理的起点；按"**紧要止血 → 结构性修复 → 增量场景覆盖**"三档分层

---

## 总览

| 优先级 | 条数 | 说明 |
|---|:----:|---|
| **P0 · 立即（止血）** | 3 | ✅ 全部清零：~~P0-1 种子脚本~~ / ~~P0-2 worker 上报~~ / ~~P0-3 空壳 workflow~~ |
| **P1 · 结构性** | 5 | ✅ 全部完成：~~P1-1 workflow 节点串联~~ / ~~P1-2 ParseSupport~~ / ~~P1-3 EXPORT id 校验~~ / ~~P1-4 SQL 白名单~~ / ~~P1-5 DISPATCH 非重试~~ |
| **P2 · 增量场景** | 9 | 本轮没碰的场景；验证型工作，不是 bug（保留，按需再做）|
| **P3 · 小瑕疵** | 4 | ~~P3-1 calendar WARN~~ / ~~P3-2 biz 表索引~~ / ~~P3-3 失败数据~~ / ~~P3-4 DL 堆积~~ 全部完成 |

---

> **2026-04-22 闭环备忘**：P0 / P1 / P3 本轮全部落地，只剩 P2 保留。
>
> 排查了「Workflow partition 停在 WAITING、调度器不 release」疑似遗留：**其实不是调度器 bug**。`WaitingPartitionDispatchScheduler` 在 worker 全部 OFFLINE 时按设计静默重试（`candidates=0 / no_online_workers_in_group`），无可放之物。重启 workers 后，历史 WAITING partition 立即 release；`buildCandidate` 和 `skip partitionId=* reason=*` 的诊断日志已保留（debug 友好）。
>
> 新发现（不在原 backlog）：workflow SETTLE 节点被 EXPORT worker 认领，但 worker 的 step registry 报 `STEP_NOT_FOUND: DISPATCH_PREPARE` —— EXPORT worker 把 payload 里 `steps: ["settle","export","dispatch"]`（workflow 的 defaultParams）当步骤执行链解读。Workflow↔worker-side step executor 协议错位，和 P1-1 payload 合并无关，建议另起一项。

## 一、P0 · 立即止血（3 条）

### V4-P0-1 · 本批 DB 改动入 seed 脚本（✅ 2026-04-21 已完成）

**背景**：Flyway 在本项目只负责平台库 schema 变更（V62/V64/V65/V66 皆是），本批改动全是业务库 DDL + 种子数据 + 运行期运维动作，归属不同。

**原影响**：`./scripts/db/reset.sh` 或重建环境后，以下内容全部消失，tb/tc IMPORT/EXPORT 会回到「全 FAILED」状态。

**落位状态**：

| 改动 | 归宿 | 状态 |
|---|---|---|
| `biz.transaction` / `biz.risk_score` / `biz.risk_alert` 三张表 | `scripts/db/business/create_biz_tables.sql` | ✅ 已写入，含索引与 CHECK 约束；drop 后重放通过 |
| `tb/IMP-TRANSACTION-CSV` / `tc/IMP-RISK-SCORE-JSON` 模板补 `jdbcMappedImport` | `batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql` | ✅ 追加 UPDATE 块；重置 query_param_schema=NULL 后重放，tb IMPORT 真跑到 SUCCESS |
| `tc/EXP-RISK-ALERT-JSON` 模板补 `default_query_sql` + `sqlTemplateExport` | 同上 | ✅ 同一 UPDATE 块，含 `:tenantId + :batchNo` 占位符与 `id` 列（避开包装层 ORDER BY 需求）|
| `default-tenant` 2 条 queue 的 `resource_tag` 清空 | ❌ **不落库** | 临时绕行，根治路径是 P0-2（worker 侧上报 capability_tags），之后 queue.resource_tag 应该恢复 |
| 2 条 `job_definition.enabled=false`（TA_DISPATCH_ORDER / gen_reconcile） | ❌ **不落库**，operator 动作 | 临时止血；修好 fileId 上游 / GENERAL worker 后重新启用 |
| `default-tenant/exp_settlement_csv_v1` 模板修复（workflow SETTLE 用） | ❓ 源头暂未定位 | 该模板在 live DB 是 created_by='system'，应由租户初始化服务/Console 上传插入，非直接 seed；留到 P1-1（workflow 节点串联）批次一并处理 |

**验证**：`drop table biz.{transaction,risk_score,risk_alert}` → `psql -f create_biz_tables.sql`（重建成功）→ `UPDATE ... = NULL` 把模板清空 → 跑 multi-tenant-seed 里的 UPDATE 块 → manual launch tb IMPORT → instance 163 SUCCESS，`biz.transaction` 落行。

---

### V4-P0-2 · worker capability_tags 心跳上报链路（✅ 2026-04-21 已完成）

**背景**：V4-BUG-2 的代码修复让 selector 能读 worker.capability_tags，但实际上**没有 worker 上报过 capability_tags**——`WorkerRegistration` 类根本没这个字段，心跳送给 orchestrator 的 `WorkerHeartbeatDto` 里相应位置永远是 `null`。当前 3 个 default 租户 worker 的 `capability_tags` 是 NULL，能跑是因为把 queue 的 `resource_tag` 清空了。

**完成动作**：

| 改动 | 文件 |
|---|---|
| `WorkerConfiguration` 接口加 `default List<String> capabilityTags()` — 默认空列表，保持向后兼容 | `batch-worker-core/.../config/WorkerConfiguration.java` |
| 3 个 `@ConfigurationProperties` record 加 `List<String> capabilityTags` 参数 + `@Override` 把 null 归一成空列表 | `ImportWorkerConfiguration.java` / `ExportWorkerConfiguration.java` / `DispatchWorkerConfiguration.java` |
| `WorkerRegistration` domain 加 `List<String> capabilityTags` 字段 | `batch-worker-core/.../domain/WorkerRegistration.java` |
| `AbstractWorkerLoop.ensureStarted` 把 `cfg.capabilityTags()` 写入 registration | `batch-worker-core/.../support/AbstractWorkerLoop.java:125` |
| `HttpWorkerRegistryClient.toHeartbeatDto` 用 `registration.getCapabilityTags()` 替代 `null` | `batch-worker-core/.../infrastructure/HttpWorkerRegistryClient.java` |
| 3 个 worker 的 `application-local.yml` 声明具体 tag：import=`[ingest]` / export=`[report, workflow]` / dispatch=`[delivery]` | 各 worker 模块 |

**验证**：
- 重启 3 个 worker → heartbeat 一轮后 `worker_registry.capability_tags` 写入正确 JSON 数组
- 恢复 `default-tenant` 2 条 queue 的 `resource_tag`（`export_queue=report` / `workflow_queue=workflow`）
- 重新跑 tb IMPORT → instance 165 SUCCESS，`biz.transaction` 落行。selector 通过 capability_tags 命中，之前需要绕过的种子错配彻底根治。

**同时注意到的小坑**：worker-import 等子模块依赖 batch-worker-core，在只改 worker-core 源码后直接 `mvn package` 某个 worker 模块会用本地 m2 缓存的旧 jar。必须先 `mvn install batch-worker-core` 再 package 下游，否则 bytecode 里根本没 `setCapabilityTags` 调用（debug 时踩过一次）。

**成本**：实际约 2h（含踩坑时间）

---

### V4-P0-3 · 7 个 workflow_definition 是空壳

**现象**：以下 workflow 在 4 个租户下都是 0 node——触发即 no-op：
`wf_compliance_check` / `wf_onboarding` / `wf_full_pipeline` / `wf_data_migration` / `wf_archive_flow` / `wf_settle_dispatch` / `wf_data_migration`

**修法**：要么补种子给它们加合理的 DAG（参考 `wf_eod_process` 4 节点模板），要么把这些空壳 workflow 整体从种子里删除（当前的 workflow_definition 行误导用户）。

**成本**：M（<1d）—— 每个 workflow 都要想清楚节点语义

---

## 二、P1 · 结构性缺口（5 条）

### V4-P1-1 · Workflow 节点间参数自动串联缺失

**现象**：`wf_eod_process` 实测：START→SETTLE 成功（SETTLE 节点生成了真实 file_record 158 / 236 bytes）→ DISPATCH 失败 `fileId missing`。Workflow 引擎**不会**把 SETTLE 的输出（生成的 fileId）自动塞进 DISPATCH 的 partition payload。

**两个独立缺口**：
1. `workflow_node.node_params` 配置不会被合并到下游 partition 的 Kafka 消息里（所以在 DISPATCH 节点上写死 `channelCode` 也不起作用）
2. 上游节点产物（如 SETTLE 生成的 fileId）没有地方声明「流向下游哪个参数位」

**修法建议**：
- 在 `DefaultSchedulePlanBuilder` 生成每个 partition 的 payload 时，按 workflow_node_run 的 `node_params` 合并进来
- 设计一套 "node output → downstream param" 映射 DSL：类似 `node_params.dispatch.fileId = "$.nodes.SETTLE.output.fileId"`，orchestrator 在派发 DISPATCH partition 之前解析
- 或者退一步：在 `workflow_node_run` 里记录每个节点的 output 结构，下游节点 partition 派发时读上游的

**成本**：L（<3d）—— 涉及 orchestrator 的核心调度逻辑，建议单独开专项

---

### V4-P1-2 · `ParseSupport.writeParsedRecord` 硬编码 `CustomerImportPayload`（✅ 2026-04-22 已完成）

**文件**：`batch-worker-import/src/main/java/com/example/batch/worker/imports/stage/format/ParseSupport.java`

**原症状**：`preserveLogicalRow=false` 时，不管 template 声明啥 schema，rows 统一被 `objectMapper.convertValue(row, CustomerImportPayload.class)` 强转。non-customer 导入（但又没配 `jdbc_mapped_import` 触发 `preserveLogicalRow=true`）会被吞掉除 customer 字段之外的列，validate 阶段报 `customerNo is required`。

**完成动作**：
- `writeParsedRecord` 内部统一把 row 原样 NDJSON 输出（删掉原本 `!preserveLogicalRow` 分支里的 `convertValue(CustomerImportPayload.class)` 及对应 lenient 重试路径）。参数 `preserveLogicalRow` 保留为 API 兼容。
- 同步删除 `CustomerImportPayload` / `DeserializationFeature` 未用 import。
- 行为等价：LoadStep 流式路径本来就按 Map 读 NDJSON（`MAP_TYPE`），ValidateStep 调 `validateChunkRows`（Map 重载），整条主链路的 I/O 形态没变；变的只是非 customer schema 模板不再被默默吞字段。
- `CustomerImportPayload` 类及 `LoadStep.executeLegacy` / `ImportDataQualityService` 里接受 `List<CustomerImportPayload>` 的重载保留，因为它们只被 legacy 内存路径使用，与本次硬编码分支无关——如果后续完全下线 legacy 路径再一起清理。

**回归验证**：同时触发 ta/TA_IMPORT_CUSTOMER（customer 模板）+ tb/TB_IMPORT_TRANSACTION（transaction 模板），instance 170/171 双 SUCCESS；`biz.customer_account` / `biz.transaction` 各自落新行。

**顺手修好的相邻测试**：`ImportIngressScannerTest` / `GenerateStepTest` 引用的老版构造器跟 P0-2 加字段不匹配，一起补参（capability_tags + objectMapper）。

**成本**：实际约 1h

---

### V4-P1-3 · EXPORT sql_template 的包装层要求调用者包含 `id` 列

**现象**：本轮 tc EXPORT 调试中撞过：template default_query_sql 没 `SELECT id, ...` 就报 `bad SQL grammar [WITH base AS (...) ORDER BY base."id"]`。框架强制把用户 SQL 包成 `WITH base AS (...) SELECT * FROM base ORDER BY base."id" ASC LIMIT ?`。

**问题**：
- 约束没文档化，用户模板很容易漏
- 错误信息是 SQL grammar，用户看不懂要加 id
- 不是所有 biz 表都有 `id` 单一主键列（复合主键场景）

**修法**：
- 要么显式检查 query 含 `id` 列，缺失就报 `EXPORT_QUERY_MISSING_ID` 友好错误
- 要么支持在 template 里声明 `orderBy` 列，包装层用这个替代硬编码 `id`
- 要么给复合主键场景做好 fallback（按 ctid/rowid 之类）

**文件**：worker-export 里 export_data 插件的 SQL 包装代码（未精确定位，需查）

**成本**：S（<4h）

---

### V4-P1-4 · EXPORT `sql_template` 对用户占位符严格白名单，`:bizDate` 被拒

**现象**：`EXPORT_GENERATE_FAILED: sql_template_export references unknown named parameter :bizDate — declare it in batch.worker.export.sql-template.allowed-extra-params or remove the reference`

用户写了业务日期 filter 必须通过配置项 `batch.worker.export.sql-template.allowed-extra-params` 声明才能用，否则直接拒。

**修法**：
- 把常用业务占位符（`:bizDate` / `:tenantId` / `:batchNo`）写入默认 allowed list
- 或者在异常消息里给出「改这个配置」的完整 key 路径和 yaml 位置

**成本**：XS

---

### V4-P1-5 · DISPATCH 默认重试策略 `EXPONENTIAL max=3` 对硬错场景浪费资源

**现象**：fileId missing 是一次性硬错（payload 里就没这个字段），根本不会因为等一等就变好。当前 workflow_node 的 DISPATCH 节点默认 `retry_policy=EXPONENTIAL retry_max_count=3`，会白白重试 3 次，每次有指数 backoff。dead_letter 里全是这种浪费产生的记录。

**修法**：在 `PrepareDispatchStep` 的 `DISPATCH_PREPARE_FILE_MISSING` / `DISPATCH_PREPARE_CHANNEL_NOT_FOUND` 错误分支标记为 non-retryable，让 retry governance 跳过重试直入 DL。

**成本**：XS

---

## 三、P2 · 增量场景覆盖（9 条）

本轮都没碰，属「验证型」工作，不是 bug。

| 编号 | 场景 | 成本 | 建议顺序 |
|---|---|---|---|
| V4-P2-1 | 非 SFTP dispatch 渠道：OSS / LOCAL / API / API_PUSH / EMAIL / NAS 6 条 | M | 先做 OSS（MinIO 就绪）和 LOCAL（文件系统够用），API / API_PUSH 需 mock 后端 |
| V4-P2-2 | 业务日历门禁（`business_calendar` + `batch_window`） | S | trigger 日志一直刷 `calendar definition not found: default-calendar` WARN，种子缺日历 |
| V4-P2-3 | quota / fair-share 配额压测 | M | 需并发压测工具 / JMeter |
| V4-P2-4 | compensation 独立验证（本轮被 DL 回放触发过，没单独跑） | S | |
| V4-P2-5 | 文件 archive / redispatch 控制端点 | S | |
| V4-P2-6 | drain enable/disable（只测了 status） | XS | 测 POST /drain/enable + /disable + 任务回退 |
| V4-P2-7 | worker drain 生命周期（DRAINING → DECOMMISSIONED） | S | |
| V4-P2-8 | 文件格式路径：FIXED_WIDTH / XML（只跑过 CSV + JSON） | M | |
| V4-P2-9 | Workflow 的 PIPELINE / MIXED 类型、join 模式（ALL/ANY/ANY_N）、GATEWAY / FILE_STEP 节点 | L | V4-P1-1 做完之后再上，否则下游节点永远 fileId missing |

---

## 四、P3 · 小瑕疵（4 条）

| 编号 | 问题 | 文件 | 成本 |
|---|---|---|---|
| V4-P3-1 | trigger 日志每 30min 刷 `calendar definition not found: default-calendar / strict-calendar` WARN（种子缺日历） | `DefaultTriggerService` | XS |
| V4-P3-2 | `biz.transaction` 等 3 张表 DDL 缺 index；规模上来后查询慢 | `batch_business`/biz schema | XS |
| V4-P3-3 | 仍有 100+ 条 FAILED `job_instance` 堆积（历史噪声），UI 快照容易被这些干扰 | 数据清理 | XS |
| V4-P3-4 | `dead_letter_task` 有 110+ 条 NEW 状态堆积，应定期归档 | 数据清理 / runbook | XS |

---

## 推荐下一批做什么

建议先吃 **P0 三条 + P1-2（ParseSupport 硬编码）** 作为 v5 第一批：
- P0-1 种子脚本固化（`create_biz_tables.sql` + `platform_seed.sql`，**不走 Flyway**）：保证重置后回归不丢 2-3 天工作量
- P0-2 worker capability_tags 心跳上报：让 BUG-2 的代码修复真正生效，并同时把 P0-1 里临时清掉的 queue.resource_tag 恢复回去
- P0-3 workflow 空壳：种子正本清源
- P1-2 ParseSupport：把已经暴露的设计 smell 清掉，避免将来再踩

合计约 **2 个人天**，全部是小而确定的活，完成后系统的"再现性"和"正确性"都大幅提升。

之后再进 **P1-1（workflow 节点间串联）**——这是**把 workflow DAG 真正用起来**的唯一剩余技术阻碍，单独立项专做。
