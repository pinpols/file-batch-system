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
| **V6-DBA-P1-4** | `ArchiveSchemaDriftCheck` 列**类型/nullability**比对 | 🟡 部分 | 现已比对热/冷表 column **名集合**（`ArchiveSchemaDriftCheck:86-87` hotCols vs coldCols，14 张表）；尚未比对列**类型/可空性**。补 `data_type`/`is_nullable` 比对即闭环（启动期 fail-fast 已在）|
| **V6-D-5** | Worker 4 模块单测密度补齐 | 待办（低优先）| 各 `Default*StageExecutor` + `*StepExecutionAdapter` 加 5-10 单测；非 blocker，趁改这些类时顺带补 |

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
