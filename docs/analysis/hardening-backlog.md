# 硬化与遗留问题 Backlog

> 滚动版本：**v7**（2026-06-15 校准）。v6 及更早完整清账见归档快照
> [`docs/archive/analysis/hardening-backlog-v6.md`](../archive/analysis/hardening-backlog-v6.md)（v3/v4 同目录）。
> 维护规则：见底部。

---

## 总览

v6 周期（57 项硬化条目）**已实质收敛**。2026-06-15 重新核实代码/迁移/CI 后，v6 残留的多数 🟡 项已落地：

| v6 残留项 | v6 标注 | 2026-06-15 实际 | 证据 |
|---|---|---|---|
| DBA-P0-1 `outbox_event` 月分区 | 🟡 待 ops | ✅ 已落地 | `db/migration/V172__outbox_event_monthly_partition.sql`（PR #470）|
| DBA-P0-2 `job_instance` 月分区 | 🟡 待 ops | ✅ 已落地 | `db/migration/V173__job_instance_monthly_partition.sql`（PR #470/#479）|
| OPS-1 `.env.prod`↔`.env.example` CI 同步 | ✅ 部分（治本待 CI）| ✅ 完成 | `scripts/ci/check-env-prod-sync.sh` 已在仓 |
| V6-P2-POSITIONAL-ARGS inline argc>6 清理 | 方案定稿 | ✅ 完成 | `PositionalArgsConventionTest` 守护已落（CI 绿即无回潮）|

→ v6 实际仅剩 **3 项真未决**，均为低优先 / 证据驱动，无代码级 blocker。

---

## 待办（v7 活清单）

| 编号 | 主题 | 性质 | 触发 / 完成条件 |
|---|---|---|---|
| **V6-DBA-P1-1/P1-2** | `job_instance` / `workflow_run` 冗余索引 DROP | 🟡 加新已完成，DROP 待取证 | 需生产 `pg_stat_user_indexes.idx_scan` 数据证明旧索引零命中后，发 V14x `DROP INDEX`。流程见 [`runbook/index-consolidation-2026-05.md`](../runbook/index-consolidation-2026-05.md)（V142/V143 已加新索引 + 回退 UNIQUE，无功能缺口，纯瘦身）|
| **V6-DBA-P1-4** | `ArchiveSchemaDriftCheck` 列**类型/nullability**比对 | ✅ 完成 | `ArchiveSchemaDriftCheck.checkColumnTypesOnStartup()` 已比对 `data_type`、字符长度、数值精度/scale 和限制性 `is_nullable`；`ArchiveSchemaDriftCheckIntegrationTest` 覆盖类型漂移。|
| **V6-D-5** | Worker 4 模块单测密度补齐 | 待办（低优先）| 各 `Default*StageExecutor` + `*StepExecutionAdapter` 加 5-10 单测；非 blocker，趁改这些类时顺带补 |
| **V7-TEST-1** | `batch.datasource.business.routing.*` boot 级集成测试 | 待办（低优先）| 组件/RLS IT（`BusinessMultiShardRouting*` / `RlsTenantIsolation*`）已有；补 Testcontainers 起完整 worker 的 enabled=true/false + placement-source=CONFIG/TABLE 开关 IT |
| **V7-TEST-2** | console security rate-limit 真实 HTTP 限流 IT | 待办（低优先）| `ConsoleRateLimitFilterTest` 已有行为覆盖；补真实 HTTP 请求验证 429（expensive-op / file-op 各一条）|
| **V7-TEST-3** | `batch.shedlock.provider` jdbc 切换集成测试 | 待办（低优先）| Redis 故障路径已有 `RedisDownToxicIT`；补 jdbc provider 装配/切换 IT（全停→切→全起，验证无重复触发）|
| **V7-TEST-4** | 其余 P1 开关 IT：`ai.enabled` 开启路径（stub LLM）、`worker.atomic.enabled-task-types` 白名单装配、`storage.encryption.decorator-enabled` 落盘加密、`storage.s3.auto-create-bucket` 建桶行为、`resource-scheduler.default-exceeded-strategy` 超限策略、`file-governance.arrival.require-verified` 到达组拦截 | 待办（低优先）| 目前仅单测或组件级覆盖；按上表逐项补真实链路 IT |

### ❌ 不做（已论证，仅存档）

| 编号 | 场景 | 原因 |
|---|---|---|
| V5-P2-1 | 6 类非 SFTP dispatch 渠道单 adapter IT | 业务接入对应渠道时再做 |
| V5-P2-9 | Workflow PIPELINE / MIXED + GATEWAY / FILE_STEP 节点端到端 | 依赖业务驱动；机制已就绪（ADR-009 全 4 stage 落地）|
| P2-3-ext / P2-4-ext | quota 打满压测 / compensation JOB+BATCH 类 | smoke + 4/6 happy-path 已覆盖；剩余留业务真需要时立项 |

---

## 维护规则

- **每发版**：把"已完成"项移到归档（`docs/archive/analysis/hardening-backlog-vN.md`），活清单只留未决，避免越来越长。
- **每月**：用 grep + DB 查 + 迁移/CI 核重核每条状态，避免"顶部已完成 / 明细未更新"不一致（v6→v7 这次校准就是修这个漂移）。
- **新发现**：先加进 V7-NEW-N，下次重排时归类到 P0/P1/P2/P3。
