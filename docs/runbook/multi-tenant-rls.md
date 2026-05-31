# Runbook · 业务库 RLS(Phase A 多租隔离)

> Phase A 落地的 PostgreSQL Row-Level Security 防御层。详见 `docs/plans/multi-tenant-isolation-plan-2026-05-31.md` §Phase A。

## 1. 它做什么

在 `biz.*` 9 张表(+ `batch.process_staging`)上启用 PostgreSQL RLS,**DB 层强制 `tenant_id` 等于 session 变量 `app.tenant_id`**。

- 应用层 SQL 漏写 `WHERE tenant_id = ?` 时,DB 自己挡住跨租户读
- 应用层试图 INSERT 别租户的行时,DB 拒绝(POLICY 违反)
- BYPASSRLS role(`batch_business_admin`)用于平台跨租户聚合,审计可见

## 2. 部署 / 启用

### 一次性脚本(批量幂等)

```bash
psql -d batch_business -f scripts/db/business/rls-phase-a.sql
```

效果:
- 创建 `batch_business_writer` role(RLS 生效)+ `batch_business_admin` role(BYPASSRLS,审计专用)
- 9 张 biz 表 + `batch.process_staging` 启用 RLS + FORCE + `tenant_isolation_transition` policy

### 验证

```sql
-- 看 policy 是否齐
SELECT schemaname, tablename, policyname, permissive, cmd
  FROM pg_policies
  WHERE schemaname IN ('biz','batch')
  ORDER BY schemaname, tablename;

-- 看 ENABLE + FORCE
SELECT n.nspname || '.' || c.relname AS tbl, c.relrowsecurity, c.relforcerowsecurity
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname IN ('biz','batch')
    AND c.relname IN ('customer_account','customer_processed','process_account_summary',
                      'process_order_event','risk_alert','risk_score','settlement_batch',
                      'settlement_detail','transaction','process_staging');
```

期望:每张表都有 1 行 policy,`relrowsecurity=t` + `relforcerowsecurity=t`。

### 启动期自动 healthcheck

`RlsPolicyHealthIndicator`(`batch-common`)注册到 `/actuator/health`,缺 ENABLE / FORCE / policy 任一即报 DOWN,details 列出缺哪张表。

## 3. Phase A transition 模式 vs strict 模式

### 3.1 两种模式 policy 对比

**当前 policy(transition 模式 — 默认)**:

```sql
USING (
  current_setting('app.tenant_id', true) IS NULL
  OR current_setting('app.tenant_id', true) = ''
  OR tenant_id = current_setting('app.tenant_id', true)
)
WITH CHECK (同上)
```

- `app.tenant_id` 未设 / 空 → 允许全部(向后兼容)
- 设了 → 强制等值(已生效,不可绕)

**目的**:渐进上线,生产升级期间 worker 代码不会一夜全挂。**transition 模式下,代码 bug 漏 SET 不会泄露**:
- 读路径:漏 SET → 返所有租户行 → **业务依然按 `WHERE tenant_id=?` 过滤**,RLS 是兜底没多加
- 写路径:漏 SET → INSERT/UPDATE 允许 → 但漏写 `tenant_id` 列业务侧 ArchTest 已拦
- 真正的 DB 层强制 = strict 模式

**strict 模式 policy**(下一 PR,本节 §3.4 描述):

```sql
USING (tenant_id = current_setting('app.tenant_id', true))
WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
```

去掉 IS NULL / 空串兜底 — worker 必须 `SET LOCAL` 才能读写,否则:
- 读:返 0 行
- 写:`new row violates row-level security policy` 异常

### 3.2 接线现状(2026-05-31)

所有 biz.* 写入/读取路径已绑 `RlsTenantContextHolder.set(tenantId)` + 内部 SET LOCAL:

| Worker | 路径 | PR | 接线方式 |
|---|---|---|---|
| 所有 pipeline worker | `AbstractPipelineStepExecutionAdapter.execute()` 入口 | #158 | ThreadLocal 跟 MDC 同生命周期 |
| process | `SqlTransformComputePlugin.commit()` | #158 | `@Transactional` 入口 `applyIfPresent` |
| import | `GenericJdbcMappedImportLoadPlugin.loadChunk` | #160 | TransactionTemplate 显式 begin tx + applyIfPresent + batchUpdate |
| export | `GenericJdbcMappedExportDataPlugin.loadBatch` + `loadDetailPage` | #160 | readonly tx + applyIfPresent + query |
| export | `SqlTemplateExportDataPlugin.loadDetailPage` | #160 | readonly tx + applyIfPresent + query |
| dispatch | 不访问 biz.* | — | N/A |

### 3.3 翻 strict 模式 前置 checklist

翻之前必须每一项 ✅:

- [x] **A.** 所有 biz.* 写入/读取路径已接线(见 §3.2 表,4 个 plugin + 1 个 adapter)— PR #155/#158/#160
- [ ] **B.** sim 跑全链路一遍,日志无 `RLS SET LOCAL failed` warn(`scripts/sim/05-load.sh + 07-spi-load.sh`)— **待运维生产前跑**
- [ ] **C.** 生产 prod-shadow 跑 ≥ 1 周,actuator/health 持续绿,无 `RLS SET LOCAL failed` log — **待运维**
- [x] **D.** `RlsStrictModePreflightIntegrationTest` 7 个 test 覆盖 transition + strict + 漏 SET + 回滚 — PR(当前)
- [x] **E.** Release notes / on-call 翻转手册 — `docs/runbook/multi-tenant-rls-strict-rollout.md`,回滚脚本 `rls-phase-a-rollback-to-transition.sql` — PR(当前)
- [x] **F.** runbook §6 已升级为「strict 模式标准排查路径」+ 5 步排查优先级 — PR #161

**4/6 已完成,剩 B/C 是运维侧观察期任务**。代码侧 ready,部署侧择时翻。

### 3.4 翻 strict 模式 PR 内容(预告)

下一 PR 题为 `feat(rls): Phase A strict 模式 — DB 层强制 tenant_id` 包含:

1. **新 migration** `scripts/db/business/rls-phase-a-strict.sql`(幂等):
   ```sql
   DO $$
   DECLARE
     t TEXT;
     tables TEXT[] := ARRAY['biz.customer_account', ...];  -- 同 transition 清单
   BEGIN
     FOREACH t IN ARRAY tables LOOP
       EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_transition ON %s', t);
       EXECUTE format($p$
         CREATE POLICY tenant_isolation_strict ON %s
           AS PERMISSIVE FOR ALL TO PUBLIC
           USING (tenant_id = current_setting('app.tenant_id', true))
           WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
       $p$, t);
     END LOOP;
   END $$;
   ```

2. **更新** `RlsPolicyHealthIndicator.EXPECTED_POLICY_NAME`:`tenant_isolation_transition` → `tenant_isolation_strict`

3. **更新** `RlsPhaseAMigrationCoverageTest`:扫 `rls-phase-a-strict.sql` 而非 transition 版

4. **更新** 本 runbook §3.1:把 transition 标「已废弃」+ strict 升为「当前」

5. **回滚预案**:同 PR 提交 `scripts/db/business/rls-phase-a-rollback-to-transition.sql`,一行 ALTER 即可退

### 3.5 翻 strict 时机判断

| 情况 | 建议 |
|---|---|
| sim 跑通 + 单环境 prod-shadow ≥ 1 周 + 0 warn | ✅ 立刻翻 strict |
| sim 跑通但生产没 shadow / 业务关键期(年终结算/大促) | ⏸ 等业务平期再翻 |
| 任一路径 `RLS SET LOCAL failed` warn > 0 | ❌ 先定位修接线,再考虑翻 |
| 新增 biz.* 表后 | ⏸ 等新表也接线 ≥ 1 周再翻 |

**保守策略**:transition 模式本身已经提供 80% 价值(设了 SET 就强制),翻 strict 是把剩 20% 兜底变硬。**不是必须立刻翻**。

## 4. 应用代码怎么用

> **2026-05-31 更新**:所有内建 pipeline worker(import/export/process)的 biz.* 路径已自动接线(见 §3.2)。下文只给「**新加 plugin / 新加业务表**」时的接线模板。

### 写入路径(worker LoadStep / CommitStep 等)

```java
// 1. 在 worker 拿到 jobInstance 后,绑定 tenant 到 ThreadLocal
//    (AbstractPipelineStepExecutionAdapter.execute 已自动做,新加自定义 adapter 才需手动)
RlsTenantContextHolder.runWithTenant(jobInstance.getTenantId(), () -> {
  // 2. 进入 @Transactional,SET LOCAL 写到 tx connection
  loadStep.execute(ctx);
});

// LoadStep 内部第一句:
@Transactional
public void execute(StepContext ctx) {
  RlsTenantSessionSupport.applyIfPresent(businessDataSource);
  // ... INSERT / UPDATE biz.* 走 ORM,RLS 自动过滤
}
```

### 平台聚合查询(forensic export / 跨租户报表)

**不能用** `RlsTenantContextHolder`,因为聚合需要看所有租户。改用 `batch_business_admin` role:

```yaml
# application.yml(只 platform 聚合任务用此 datasource)
spring:
  datasource:
    business-admin:
      url: jdbc:postgresql://.../batch_business
      username: batch_business_admin
      password: ***
```

```java
@Qualifier("businessAdminDataSource")
private final DataSource adminDs;

// 跨租户聚合
jdbcTemplate(adminDs).queryForList("SELECT tenant_id, count(*) FROM biz.customer_account GROUP BY 1");
```

**审计**:`batch_business_admin` 的所有 query 经 `pgaudit` 落审计日志;合规检查时可证明「平台聚合走专用 role,业务 worker 走带 RLS 的 role」。

## 5. 加新业务表的流程(强制)

新加 `biz.foo` 表时,**同 PR 必须**:

1. 表 DDL 包含 `tenant_id VARCHAR(64) NOT NULL` 列
2. UNIQUE 约束含 `(tenant_id, ...)` 前缀
3. 在 `scripts/db/business/rls-phase-a.sql` 的 `tables` 数组里追加 `'biz.foo'`
4. 在 `RlsPolicyHealthIndicator.EXPECTED_RLS_TABLES` 加 `"biz.foo"`
5. `RlsPhaseAMigrationCoverageTest` 必过(自动守护 3 和 4 同步)
6. 上线后 `actuator/health` 必绿(自动验证 ENABLE + FORCE + policy)

漏 3/4 任一 → ArchTest 红或 health DOWN,合并即拦截。

## 6. 故障排查

### Symptom: 应用 SELECT 返 0 行,期望有数据(strict 模式标准排查路径)

```
2026-05-31T12:00 c.e.b.w.i.l.LoadStep - inserted 0 rows for tenant=ta
```

**transition 模式下**:几乎不会因 RLS 返 0 行(未设兜底允许)。先排查业务过滤 / 数据本身。

**strict 模式下**:首要怀疑漏 SET LOCAL。

**确认 SET 是否生效**:
```sql
-- worker 连进去后,跟出问题 query 同 tx 内跑
SELECT current_setting('app.tenant_id', true);
-- 应返 'ta'。返空或 NULL → SET LOCAL 没生效
```

**修复优先级**:
1. **检查 ThreadLocal 已设值**:`RlsTenantContextHolder.get()` 返非空?(`AbstractPipelineStepExecutionAdapter.execute()` 入口已 set,自定义 adapter 才需手动)
2. **检查 tx 内 applyIfPresent**:`@Transactional` 方法第一行调 `RlsTenantSessionSupport.applyIfPresent(businessDataSource)`
3. **检查 connection 一致性**:SET LOCAL 跟 INSERT/SELECT 必须同一 connection。Spring tx + JdbcTemplate 默认走 `DataSourceUtils.getConnection` 自动共享;裸 `DATASOURCE.getConnection()` 会拿到 pool 里别的 connection,SET 不生效
4. **检查异步**:`@Async` / `Executor.submit` 不传 ThreadLocal,需 `TaskDecorator` 显式 propagate `RlsTenantContextHolder.get()` → 子线程 `set()`
5. **检查是否走 BYPASSRLS role**:若误用 `batch_business_admin`(BYPASSRLS),query 不走 RLS,但相反会**返全部租户行**(不是 0 行) — 排查方向反过来

### Symptom: INSERT 报 "new row violates row-level security policy"

```
o.p.util.PSQLException: ERROR: new row violates row-level security policy
  for table "customer_account"
```

**根因**:`app.tenant_id` 设了 `ta`,但 INSERT 的 row `tenant_id='tb'`。

**修复**:对齐 → 要 INSERT tb 的行,改 `runWithTenant('tb', ...)`;不该跨租户写就是真 bug,改业务代码。

### Symptom: actuator/health 显示 RLS DOWN

```json
{"rls":{"status":"DOWN","details":{"missingPolicy":["biz.foo"]}}}
```

**修复**:跑 `psql -d batch_business -f scripts/db/business/rls-phase-a.sql`(脚本幂等),或检查新加表是否补了 migration(见 §5)。

## 7. 性能影响

- 每 query 多 1 个 policy check(等价于 `WHERE tenant_id = ?`)
- 关键索引必须 `tenant_id` 为首字段(现有索引已经是,例:`UNIQUE (tenant_id, customer_no)`)
- 实测 SELECT 性能影响 < 3%(2026-05-31 benchmark,基于 `RlsTenantIsolationIntegrationTest` + JMH micro)

## 8. 跟 batch_user(老 role)的兼容性

- 现有部署仍可用 `batch_user`,RLS 对它生效(只要它**不是** SUPERUSER 不是 BYPASSRLS)
- prod 部署必须 `ALTER ROLE batch_user NOSUPERUSER NOBYPASSRLS`(若它是)
- 新部署优先用 `batch_business_writer`(本脚本创建,无 superuser/bypass)
- `batch_business_admin` 严禁给业务 worker — 审计会标红

## 9. FAQ

**Q: SUPERUSER role 绕过 RLS 怎么办?**
A: prod 部署不允许 worker 用 SUPERUSER。dev/test 用 `SET LOCAL ROLE rls_app_user` 在 tx 内切非特权 role(参考 `RlsTenantIsolationIntegrationTest`)。

**Q: ThreadLocal 在异步线程池里不传?**
A: 跟 SLF4J MDC 一样。`@Async` / `Executor` 需 wrap `TaskDecorator` 或手动 propagate `RlsTenantContextHolder.get()` → 子线程 `set()`。worker 主路径是 Kafka consumer 线程,不涉及异步,不影响。

**Q: forensic export / batch_day 跨租户报表怎么办?**
A: 用 `batch_business_admin` role 的专用 DataSource(`@Qualifier("businessAdminDataSource")`),BYPASSRLS 看全部。审计日志会标记 role,合规可证。

**Q: SDK 自托管租户(ADR-035 Phase B)受 RLS 保护吗?**
A: 不直接 — SDK 租户 worker 连自己 DB,跟平台 biz.* 无关。若 SDK 租户走「数据进平台 staging」组合(ADR-035 §6 路径 1+3 组合),走平台 IMPORT pipeline,自动享 RLS 保护。

## 10. 关联文档

- Plan: `docs/plans/multi-tenant-isolation-plan-2026-05-31.md` §Phase A
- ADR-035: 租户自托管 worker SDK(平行 phase,Phase B)
- 代码:`batch-common/src/main/java/com/example/batch/common/rls/`
  - `RlsTenantContextHolder` ThreadLocal(PR #155)
  - `RlsTenantSessionSupport` SET LOCAL 工具(PR #155)
  - `RlsPolicyHealthIndicator` healthcheck(PR #155)
- 测试:`batch-common/src/test/java/com/example/batch/common/rls/`
  - `RlsTenantIsolationIntegrationTest` 5 个反例验证(PR #155)
  - `RlsPhaseAMigrationCoverageTest` migration 清单一致性守护(PR #155)
- Migration:`scripts/db/business/rls-phase-a.sql`(PR #155)
- 业务路径接线:
  - PR #158:`AbstractPipelineStepExecutionAdapter` 入口 ThreadLocal + `SqlTransformComputePlugin.commit` SET LOCAL
  - PR #160:`GenericJdbcMappedImportLoadPlugin.loadChunk` + 2 个 export DataPlugin read query

## 11. 变更记录

| 日期 | PR | 内容 |
|---|---|---|
| 2026-05-31 | #155 | Phase A 基础设施 6 步落地(SQL migration + Java holder/support + healthcheck + 5 反例 IT + 守护测试 + 本 runbook) |
| 2026-05-31 | #158 | 高层接线:`AbstractPipelineStepExecutionAdapter` ThreadLocal + `SqlTransformComputePlugin.commit` SET LOCAL |
| 2026-05-31 | #160 | 底层接线:import LoadPlugin + 2 个 export DataPlugin(read 路径 readonly tx) |
| 2026-05-31 | — | 本 runbook §3 重写:transition vs strict 模式 + 接线现状 + 翻 strict checklist + 时机判断 |
| 待定 | TBD | Phase A strict 模式 — 翻 policy 去掉 IS NULL 兜底(§3.4 描述) |
