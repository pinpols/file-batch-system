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

## 3. Phase A transition 模式 vs 未来 strict 模式

**当前 policy(transition 模式)**:

```sql
USING (
  current_setting('app.tenant_id', true) IS NULL
  OR current_setting('app.tenant_id', true) = ''
  OR tenant_id = current_setting('app.tenant_id', true)
)
```

- `app.tenant_id` 未设 → 允许全部(向后兼容现有 worker 代码,SET LOCAL 还没全铺开)
- 设了 → 强制等值

**目的**:渐进上线,worker 代码逐步加 `SET LOCAL app.tenant_id` 不会一夜全挂。

**未来 strict 模式**(后续 PR):

```sql
USING (tenant_id = current_setting('app.tenant_id', true))
```

去掉 IS NULL 分支 — worker 必须显式 SET 才能读写,否则 0 行返回 / INSERT 拒绝。这才是真正的 DB 层强制。

**翻 strict 的前提**:所有 biz.* 写入路径都已经 `RlsTenantContextHolder.runWithTenant + RlsTenantSessionSupport.applyIfPresent` 包好(`MultiTenantIsolationIntegrationTest` 跑通 RLS 反例 ≥ 1 周)。

## 4. 应用代码怎么用

### 写入路径(worker LoadStep / CommitStep 等)

```java
// 1. 在 worker 拿到 jobInstance 后,绑定 tenant 到 ThreadLocal
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

### Symptom: 应用 SELECT 返 0 行,期望有数据

```
2026-05-31T12:00 c.e.b.w.i.l.LoadStep - inserted 0 rows for tenant=ta
```

**可能原因**:RLS 在 strict 模式 + worker 没 SET `app.tenant_id`。

**确认**:
```sql
-- worker 连进去后跑
SELECT current_setting('app.tenant_id', true);
-- 应返 'ta'。返空或 NULL → SET LOCAL 没生效
```

**修复**:
- worker 代码检查是否调了 `RlsTenantContextHolder.runWithTenant(tenantId, ...)`
- Spring tx 范围对吗?`SET LOCAL` 必须在事务内、且跟 INSERT/SELECT 同一 connection
- 若用 `@Async`,ThreadLocal 不会传播,需 `TaskDecorator` 显式 propagate

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
  - `RlsTenantContextHolder` ThreadLocal
  - `RlsTenantSessionSupport` SET LOCAL 工具
  - `RlsPolicyHealthIndicator` healthcheck
- 测试:`batch-common/src/test/java/com/example/batch/common/rls/`
  - `RlsTenantIsolationIntegrationTest` 5 个反例验证
  - `RlsPhaseAMigrationCoverageTest` migration 清单一致性守护
- Migration:`scripts/db/business/rls-phase-a.sql`
