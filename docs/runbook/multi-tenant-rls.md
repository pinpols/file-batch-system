# Runbook · 业务库 RLS(Phase A 多租隔离)

> Phase A 落地的 PostgreSQL Row-Level Security 防御层。详见 `docs/plans/multi-tenant-isolation-plan-2026-05-31.md` §Phase A。

## 1. 它做什么

在 `biz.*` 所有包含 `tenant_id` 的租户业务表(+ `batch.process_staging`)上启用 PostgreSQL RLS,**DB 层强制 `tenant_id` 等于 session 变量 `app.tenant_id`**。

- 应用层 SQL 漏写 `WHERE tenant_id = ?` 时,DB 自己挡住跨租户读
- 应用层试图 INSERT 别租户的行时,DB 拒绝(POLICY 违反)
- BYPASSRLS role(`batch_business_admin`)用于平台跨租户聚合,审计可见

## 2. 部署 / 启用

### 一次性脚本(批量幂等)

```bash
# 密码由运维 / secret 后端(Vault / K8s Secret)注入,脚本不含默认密码;
# 缺 -v writer_password / admin_password 会 fail-safe RAISE 报错(不会建弱密码角色)。
psql -d batch_business -v ON_ERROR_STOP=1 \
  -v writer_password="$BIZ_WRITER_PASSWORD" \
  -v admin_password="$BIZ_ADMIN_PASSWORD" \
  -f scripts/db/business/rls-phase-a.sql

# 只读排故角色(readonly = RLS 生效;readonly_all = BYPASSRLS 跨租只读)
psql -d batch_business -v ON_ERROR_STOP=1 \
  -v readonly_password="$BIZ_READONLY_PASSWORD" \
  -v readonly_all_password="$BIZ_READONLY_ALL_PASSWORD" \
  -f scripts/db/business/diagnostic-readonly-role.sql
```

效果:
- 创建 `batch_business_writer` role(RLS 生效)+ `batch_business_admin` role(BYPASSRLS,审计专用)
- 所有包含 `tenant_id` 的 biz 表 + `batch.process_staging` 启用 RLS + FORCE + `tenant_isolation_strict` policy；分区子表继承父表策略

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
                      'process_event_copy','process_order_event','risk_alert','risk_score',
                      'settlement_batch','settlement_detail','transaction','process_staging');
```

期望:每张表都有 1 行 policy,`relrowsecurity=t` + `relforcerowsecurity=t`。

### 启动期自动 healthcheck

`RlsPolicyHealthIndicator`(`batch-common`)注册到 `/actuator/health`,缺 ENABLE / FORCE / policy 任一即报 DOWN,details 列出缺哪张表。

## 3. Phase A strict 模式（当前默认）

### 3.1 两种模式 policy 对比

**当前 policy(strict 模式 — 默认)**:

```sql
USING (tenant_id = current_setting('app.tenant_id', true))
WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
```

- `app.tenant_id` 未设 / 空 → 不匹配任何行
- 设了 → 仅允许相同 tenant_id
- worker 必须 `SET LOCAL` 才能读写,否则:
- 读:返 0 行
- 写:`new row violates row-level security policy` 异常

`RlsTenantSessionSupport` 对缺失/非法上下文和 `set_config` 失败直接抛异常，不允许以 WARN 降级继续访问业务库。
transition policy 已从正常安装脚本移除，仅保留在应急回滚脚本中；回滚期间健康检查会保持 DOWN，修复后必须重新安装 strict policy。

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

### 3.3 strict 模式上线 checklist

翻之前必须每一项 ✅:

- [x] **A.** 所有 biz.* 写入/读取路径已接线(见 §3.2 表,4 个 plugin + 1 个 adapter)— PR #155/#158/#160
- [ ] **B.** sim 跑全链路一遍，确认没有 RLS 失败日志 — **部署环境验证项**
- [ ] **C.** 生产 prod-shadow 跑 ≥ 1 周，actuator/health 持续绿 — **部署环境验证项**
- [x] **D.** strict IT 覆盖未 SET、正确租户、跨租户写入和 set_config 接线
- [x] **E.** strict 安装、启动 fail-fast、健康检查和应急回滚手册已落库
- [x] **F.** runbook §6 已升级为「strict 模式标准排查路径」+ 5 步排查优先级 — PR #161

**代码与默认部署策略已切到 strict；B/C 仍是必须在真实 sim/staging 环境留证的运维验收项，不能用本地单测替代。**

### 3.4 strict 落地内容

当前实现包含:

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

2. **更新** `RlsClosedWorldChecker`：只接受 `tenant_isolation_strict`，并拒绝含 `IS NULL` 回退的策略

3. **更新** `RlsTenantSessionSupport`:缺 tenant、非法 tenant、`set_config` 失败均 fail-closed

4. **更新** `RlsProperties.startupFailFast` 默认 `true`，生产缺 RLS 时拒绝启动

5. **回滚预案**:保留 `scripts/db/business/rls-phase-a-rollback-to-transition.sql`，仅用于事故止血，回滚后必须恢复 strict

### 3.5 strict 验收判断

| 情况 | 建议 |
|---|---|
| sim 跑通 + 单环境 prod-shadow ≥ 1 周 + 0 RLS 失败 | ✅ 可签署上线 |
| sim/staging 仍有 RLS 失败 | ❌ 阻断上线，修复接线或数据源配置 |
| 新增 biz.* 表后 | ❌ 先补 RLS、闭世界检查和接线测试 |

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
3. 新表创建后执行 `rls-phase-a.sql` 或 `rls-phase-a-strict.sql`；脚本会动态发现所有含 `tenant_id` 的非分区父表，不需要维护数组
4. 非租户元数据表只能通过 `batch.rls.exempt-tables` 显式豁免，并在评审中说明原因
5. `RlsPhaseAMigrationCoverageTest` 必过，确保三份脚本仍保持动态发现
6. 上线后 `actuator/health` 必绿(自动验证 ENABLE + FORCE + policy)

漏 RLS 安装或误把租户表加入豁免 → health DOWN，启动 fail-fast 阻断部署。

## 6. 故障排查

### Symptom: 应用 SELECT 返 0 行,期望有数据(strict 模式标准排查路径)

```
2026-05-31T12:00 i.g.p.b.w.i.s.LoadStep - inserted 0 rows for tenant=ta
```

**transition 模式下**:几乎不会因 RLS 返 0 行(未设回退允许)。先排查业务过滤 / 数据本身。

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

**修复**:跑 `psql -d batch_business -v ON_ERROR_STOP=1 -v writer_password="$BIZ_WRITER_PASSWORD" -v admin_password="$BIZ_ADMIN_PASSWORD" -f scripts/db/business/rls-phase-a.sql`(脚本幂等;密码由运维/secret 后端注入,缺失会 fail-safe 报错不建弱角色),或检查新加表是否补了 migration(见 §5)。

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
- 代码:`batch-common/src/main/java/io/github/pinpols/batch/common/rls/`
  - `RlsTenantContextHolder` ThreadLocal(PR #155)
  - `RlsTenantSessionSupport` SET LOCAL 工具(PR #155)
  - `RlsPolicyHealthIndicator` healthcheck(PR #155)
- 测试:`batch-common/src/test/java/io/github/pinpols/batch/common/rls/`
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
| 待定 | TBD | Phase A strict 模式 — 翻 policy 去掉 IS NULL 回退(§3.4 描述) |
