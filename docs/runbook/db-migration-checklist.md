# DB Migration Checklist

> R7 DB 审计（2026-05-16）回顾 V64-V126 共 63 个 migration 后固化的"未来 migration 必读"规则。每条都对应一个真实历史风险案例。

## 适用范围

凡是新加 Flyway migration（`db/migration/V*.sql`），上线前必须自查本清单。审计 PR 评审者按本清单逐项核对。

## 红线清单

### 1. 大表 DML 禁入 migration

**反例**：[V124 §6](../../db/migration/V124__r3_constraint_hardening.sql)（`DELETE FROM batch.file_record … USING …` 全表 self-join 去重）/ [V108](../../db/migration/V108__result_version_main_model.sql)（`INSERT INTO result_version SELECT … FROM job_instance` 全表窗口函数 backfill）。

**风险**：
- 全表锁 / 长事务阻塞 trigger / orchestrator 主链路（job_instance 是 hot 表）
- 撞 FK 引用导致 migration abort 不可重入
- 失败回滚成本 = 全部 row 重做

**规则**：migration **只 DDL**（CREATE / ALTER / DROP TABLE / INDEX / CONSTRAINT），DML 拆为：
- 独立的 Java 一次性 backfill job（`@ConditionalOnProperty` gate + `ON CONFLICT DO NOTHING` 幂等）
- 或 DBA 手工 SQL 脚本（带 LIMIT / 分批 / `pg_sleep`）

### 2. `tenant_id` / 业务关键列 backfill 必须前置孤儿清理

**反例**：[V84 / V85 / V92](../../db/migration/V84__workflow_node_tenant_id.sql) — `ADD COLUMN tenant_id` → `UPDATE … FROM parent` → `SET NOT NULL`。若 parent 已被删除留下孤儿子行，backfill 留 NULL → `SET NOT NULL` 直接 fail，迁移卡住集群启动。

**规则**：`SET NOT NULL` 前必须有一个：
```sql
DELETE FROM batch.<table> WHERE tenant_id IS NULL;
-- 或显式 backfill 回退
UPDATE batch.<table> SET tenant_id='__ORPHAN__' WHERE tenant_id IS NULL;
```

PR 评审者要求 backfill migration 必须包含孤儿处置 SQL。

### 3. `NOT VALID` 加约束必须同 sprint 配 `VALIDATE` migration

**反例**：[V124](../../db/migration/V124__r3_constraint_hardening.sql) 加 5 处 CHECK / FK `NOT VALID`，原计划运维窗口手工 `VALIDATE CONSTRAINT`，实际滞留 数周未跑；DB 长期处于"约束已加但允许新行违反"drift 状态。V125 / V126 同。R7 收尾 V127 一次性 VALIDATE。

**规则**：
- 加 `NOT VALID` 必须同 sprint 内补 `VALIDATE CONSTRAINT` migration（模板见 V127）
- 启动期 `NotValidConstraintGuard` 扫 `pg_constraint.convalidated=false`，任何未 VALIDATE 约束都 fail-fast
- 不能 VALIDATE（存量真的违反约束）就**不要加约束**，改 service 层断言

### 4. `partial unique index` 必须前置 dedup 探针

**反例**：[V124 P1-4](../../db/migration/V124__r3_constraint_hardening.sql) `CREATE UNIQUE INDEX uk_workflow_run_active … WHERE run_status IN ('CREATED','RUNNING')`。V124 前没这条约束，rerun / 手工创建场景完全可能有多行 active workflow_run → 索引构建期 duplicate key 整个迁移 abort。

**规则**：每个 partial unique 在 migration 内必须前置：
```sql
DO $$
DECLARE dup_count int;
BEGIN
  SELECT COUNT(*) INTO dup_count FROM (
    SELECT tenant_id, workflow_definition_id, biz_date
    FROM batch.workflow_run WHERE run_status IN ('CREATED','RUNNING')
    GROUP BY 1,2,3 HAVING COUNT(*) > 1
  ) t;
  IF dup_count > 0 THEN
    RAISE EXCEPTION 'partial unique index would conflict on % rows, dedup first', dup_count;
  END IF;
END $$;
```

### 5. FK `ON DELETE CASCADE` 不用于审计 / 长寿数据

**反例**：[V119](../../db/migration/V119__job_partition_fk_cascade.sql) 把 `job_execution_log.job_partition_id` 改 CASCADE，意图是 cleanup 序列遗漏时回退。结果是 partition 删除时**整条 audit log 跟着物理删**——审计追溯权威被静默 wipe。R7 收尾 V128 改 `SET NULL`。

**规则**：
- 审计表 / 业务长寿表 → `ON DELETE SET NULL`（解除引用，保数据）
- 运行时父子实体（partition → step / task）→ `ON DELETE CASCADE`（生命周期捆绑）
- service 层显式删除顺序比 FK 行为更可控；CASCADE 只是冗余防御，**不是首选**

### 6. archive 表与热表字段 1:1 镜像

**反例**：[V118](../../db/migration/V118__data_quality_reconciliation.sql) 创建 `archive.data_quality_rule_archive` / `data_quality_check_archive` 时注释承诺 "archive drift check 覆盖"，实际 `ArchiveSchemaDriftCheck.ARCHIVED_TABLES` 漏注册，加列时启动期不 fail-fast。R7 已修。

**规则**：
- 新加归档表 → 同 PR 加入 `ArchiveSchemaDriftCheck.ARCHIVED_TABLES`
- 热表 `ALTER ADD COLUMN` → 同 PR 加 `ALTER archive.<x>_archive ADD COLUMN`（V77 → V79 三轮同步是反例参考）
- 启动期 drift check 是最后回退，不是替代

### 7. `CHECK` 状态机枚举二次扩展 → release-engineering 锁序

**反例**：[V101 → V107](../../db/migration/V107__batch_day_settling_status.sql) V101 把 `ck_batch_day_instance_status` 收紧到 7 值，V107 又扩到 8 值（加 SETTLING）。若 app 先部分推 prod（写 SETTLING）而 V107 还没跑 → CHECK 违反；反向回滚（先迁后部署）虽安全但需运维强约束。

**规则**：
- 状态机扩展（CHECK 列表放开 / 收紧）严格遵守 release 序：
  - **放开** CHECK：migration **先于** app deploy（DB 接受新值，老 app 不会写新值）
  - **收紧** CHECK：app **先于** migration（老值新代码已停写，再收紧 DB）
- 同一 sprint 不要同时收紧 + 立即放开（V101 → V107 模式）

### 8. 列类型变更（窄化）禁入

**风险**：`ALTER COLUMN type` 窄化（`VARCHAR(512)` → `VARCHAR(64)`、`BIGINT` → `INTEGER`）期间，老应用对该列已有数据 / 写入路径会失败。

**规则**：
- 加宽（widening）OK，PG 11+ 是 metadata-only
- 窄化必须分 3 步：① 双写新列 → ② migration 拷贝旧数据到新列 → ③ 后续 sprint 删旧列
- 直接 `ALTER COLUMN type` 窄化在 PR 评审 reject

### 9. `CREATE INDEX` 在大表必须 `CONCURRENTLY`

**风险**：默认 `CREATE INDEX` 持 `ACCESS EXCLUSIVE` 锁，热表（`job_instance` / `job_task` / `outbox_event`）会让生产读写全部阻塞。

**规则**：
- 大表（行数 > 1M）的 index 用 `CREATE INDEX CONCURRENTLY`
- 注意：`CONCURRENTLY` 不能在事务内，单 migration 文件如有事务包裹要拆出
- Flyway `transactional: false` 标记必要时使用

### 10. Migration 失败必须可重入（idempotent）

**反例**：V124 用 `DO $$ IF NOT EXISTS pg_constraint THEN ADD … END $$` 包裹 — **正确**示范。直接 `ALTER TABLE … ADD CONSTRAINT` 无 IF NOT EXISTS 在 Flyway 重跑 / docker test 环境重复迁移时挂。

**规则**：
- CHECK / FK 用 `DO $$ ... IF NOT EXISTS (SELECT FROM pg_constraint WHERE conname=...) THEN ALTER TABLE ... ADD CONSTRAINT ... END $$`
- 列 / 索引用原生 `ADD COLUMN IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`
- 表用 `CREATE TABLE IF NOT EXISTS`
- 任何 DML 用 `ON CONFLICT DO NOTHING` 或 `WHERE NOT EXISTS`

## PR 评审 checklist

每个 migration PR 在 review comment 复制粘贴回答：

```
- [ ] 没有全表 DML？(规则 1)
- [ ] tenant_id / NOT NULL backfill 前置孤儿处置？(规则 2)
- [ ] NOT VALID 约束同 sprint 配 VALIDATE migration？(规则 3)
- [ ] partial unique 前置 dedup 探针？(规则 4)
- [ ] FK CASCADE 仅用于父子运行时实体，审计走 SET NULL？(规则 5)
- [ ] 新归档表加入 ArchiveSchemaDriftCheck.ARCHIVED_TABLES？(规则 6)
- [ ] CHECK 状态机扩展遵守 release 序？(规则 7)
- [ ] 列类型变更是加宽（窄化必须 3 步）？(规则 8)
- [ ] 大表 CREATE INDEX 用 CONCURRENTLY？(规则 9)
- [ ] migration 可重入（IF NOT EXISTS / ON CONFLICT）？(规则 10)
```

## 自动守护

- `ArchiveSchemaDriftCheck`（启动期，orchestrator）— 规则 6
- `NotValidConstraintGuard`（启动期，orchestrator）— 规则 3
- `BatchStartupSelfCheck`（启动期，all modules）— 基础 schema 存在性

## 历史案例索引

| 规则 | Migration | 案例 |
|---|---|---|
| 1 | V124 §6 | file_record 全表 self-join DELETE |
| 1 | V108 | result_version 大表 backfill |
| 2 | V84 / V85 / V92 | tenant_id backfill 漏孤儿 |
| 3 | V124 / V125 / V126 | 7 处 NOT VALID 滞留未 VALIDATE |
| 4 | V124 P1-4 | uk_workflow_run_active 存量违反 |
| 5 | V119 | job_execution_log CASCADE 静默吞审计 |
| 6 | V118 | data_quality 归档表漏注册 |
| 7 | V101 → V107 | batch_day_instance status CHECK 二次扩展 |
| 10 | V124 全文 | DO $$ IF NOT EXISTS pg_constraint $$ 模板示范 |
