# Runbook · Phase A · 翻 strict 模式 操作手册

> Phase A 收尾 — 把 RLS 从「transition 模式(未设 SET 兜底允许)」翻到「strict 模式(未设 SET 强制拦截)」。
>
> 配套:`docs/runbook/multi-tenant-rls.md` §3 是设计依据,本文档是**翻转操作手册**(给 on-call 用)。

## 0. 现状与决策存档(2026-06-21「华而不实」体检维度6)

体检维度6 指出 biz RLS「DB 级返 0 行」的保证在**生产默认态/多节点态下并不成立**,据此明确以下现状与决策(本次**不翻默认**):

| 维度 | 现状 | 含义 |
|---|---|---|
| **默认模式** | `transition`(GUC `app.tenant_id` 未设 → 放行全表) | 默认部署下 RLS 不是硬拦截;隔离实际靠应用层 mapper 列过滤 + 分片路由 |
| **strict** | 非默认,需显式翻(本手册) | 翻了才有「DB 级强制 + 可证明」 |
| **console-api** | **完全不调 RLS session**(`applyIfPresent` 只在 worker/orchestrator 数据面) | console 读 biz 不受 RLS 保护,只靠 `ConsoleTenantGuard` + 列过滤 |
| **Citus 多节点** | RLS **实测坏**(GUC 跨节点不传播,返 0 行/写报错),仅单节点验过 | strict 默认开 = 等于绑定单机部署;多节点要先解 GUC 传播 |

**决策(2026-06-21)**:**保持 transition 默认,不单机盲翻 strict**。理由:① 翻 strict 在多节点 Citus 上会坏,翻默认等于悄悄绑定单机;② console-api 未接 RLS,即便翻了 strict 这层也不在 RLS 覆盖内;③ 应用层(mapper 强制 `tenant_id` + 分片路由 + `requireTenant`)当前已守住跨租。**这层 DB 级保证要在真多节点环境验证 GUC 传播 + 给 console-api 接上 RLS session 之后,再按 §1 触发条件评估翻转**——属维度6 的环境验证项,非代码层能单方面完成。

## 1. 决策 — 是否要现在翻

**默认不翻**(transition 已提供 80% 价值)。下列情况之一才考虑翻:

| 触发条件 | 紧迫性 |
|---|---|
| 合规审计要求「DB 层强制 + 可证明」(不能只说"我们应用层会过滤") | 🟥 高 |
| 真实跨租户泄露事故复盘要求把 RLS 提到 strict | 🟥 高 |
| 平台进入「正式 SaaS 多租」业务阶段 | 🟧 中 |
| 单纯想"做完闭环" | 🟩 低,可缓 |

> **保守原则**:翻 strict 是单向门(回滚有应急脚本但是事故级别),非必要不翻。

## 2. 翻转前置 checklist(全项 ✅ 才能开干)

- [x] **A. 接线完成**(PR #155/#158/#160)
  - 验证:`mvn test -pl batch-common -Dtest='Rls*'` → 全过(含 `RlsStrictModePreflightIntegrationTest`)
- [ ] **B. sim 全链路验证**
  - 跑 `bash scripts/sim/01-init-biz.sh && bash scripts/sim/05-load.sh && bash scripts/sim/06-verify.sh`
  - 查 worker 日志:`grep "RLS SET LOCAL failed" logs/be-acceptance/*.log` → 必须 0 行
- [ ] **C. prod-shadow 跑 ≥ 1 周**
  - 生产环境镜像跑当前代码(transition + 接线齐),持续观察 1 周
  - 查 actuator/health `/rls` indicator 持续绿
  - 应用日志 `grep "RLS SET LOCAL failed"` → 必须 0 行
- [ ] **D. preflight test 全过**
  - 本 PR 已加 `RlsStrictModePreflightIntegrationTest` — 7 个 test 覆盖 transition + strict + 漏 SET + 回滚 5 个场景
- [ ] **E. on-call 知道回滚命令**
  - 本文档 §5 标准回滚命令,on-call 朗读 1 遍并签字
- [ ] **F. 业务低峰期**
  - 避开年终结算 / 大促 / 月底对账等业务关键期

## 3. 翻转操作步骤

### 3.1 准备

**T-1 天**:
1. 给业务方 / on-call 发通知(本次翻转可能影响应用层 bug 暴露成 DB 错误)
2. 准备回滚 shell 一行命令的命令模板:
   ```bash
   psql $BATCH_BUSINESS_DB_URL -f scripts/db/business/rls-phase-a-rollback-to-transition.sql
   ```
3. 准备监控仪表板:`actuator/health/rls`, 应用日志 grep `"row-level security"`

### 3.2 执行翻转(5 分钟)

```bash
# 在业务低峰期,生产 batch_business 库
psql $BATCH_BUSINESS_DB_URL -f scripts/db/business/rls-phase-a-strict.sql
```

预期输出:
```
DO
DO
```

### 3.3 验证(15 分钟)

```sql
-- 1. policy 切换成功
SELECT schemaname, tablename, policyname
  FROM pg_policies
  WHERE schemaname IN ('biz','batch')
    AND policyname IN ('tenant_isolation_strict', 'tenant_isolation_transition');
-- 期望:10 行,全部 policyname='tenant_isolation_strict'(transition 0 行)
```

```bash
# 2. healthcheck 持续绿
curl -s http://console-api/actuator/health/rls
# 期望:status=UP
```

```bash
# 3. worker 日志监控 30 分钟
tail -f logs/console-api/console-api.log | grep -E "row-level security|RLS SET LOCAL failed"
# 期望:0 行(若有,立刻执行 §5 回滚)
```

### 3.4 跟踪 24 小时

每小时检查一次:
- `pg_policies` 表 strict 仍存在
- 应用日志无 `"row-level security violation"`
- actuator/health 持续绿
- 业务 KPI(import 成功率 / export 成功率 / job_instance 终态分布)无异常

## 4. 翻转后的代码变更

本 PR 同时更新:

- `RlsPolicyHealthIndicator.EXPECTED_POLICY_NAME` **接受 transition 或 strict 任一**(灰度兼容)
- `RlsPhaseAMigrationCoverageTest` 校验 strict migration 覆盖全表
- runbook `multi-tenant-rls.md` §3.1 标 transition「已废弃,留作回滚」+ strict 标「当前」

## 5. 回滚(紧急)

### 5.1 触发条件(任一即回滚)

- 应用日志 30 分钟内 `"row-level security violation"` > 10 条
- 业务 KPI(import/export 成功率)5 分钟内跌 > 20%
- 真实租户报「数据看不到了」
- actuator/health/rls 红

### 5.2 回滚命令(on-call 朗读)

```bash
psql $BATCH_BUSINESS_DB_URL -f scripts/db/business/rls-phase-a-rollback-to-transition.sql
```

预期 5 秒内执行完。

### 5.3 验证回滚成功

```sql
SELECT count(*) FROM pg_policies
  WHERE schemaname IN ('biz','batch')
    AND policyname='tenant_isolation_transition';
-- 期望:10
```

```bash
# 应用日志立刻无新 "row-level security violation"
tail -f logs/console-api/console-api.log | grep "row-level security"
# 应停止增长
```

### 5.4 复盘

强制要求:回滚后 24 小时内:
1. 找出**哪个 worker 路径漏接线** 导致漏 SET LOCAL(grep `SqlTransformComputePlugin.commit` 等已知路径之外的写 biz.* 操作)
2. 补接线,加 unit test 守护
3. 重跑 §2 checklist B/C/D 项
4. 再次走 §3 翻转流程

## 6. 已知陷阱

### 6.1 平台聚合脚本(forensic / 跨租户报表)

如果聚合脚本之前用 `batch_user` role 跨租户查,翻 strict 后会**返 0 行**(每查一个 tenant 都要 SET LOCAL 切换)。

**修复**(翻 strict 前完成):
- 改用 `batch_business_admin` role(BYPASSRLS)
- 见 `multi-tenant-rls.md` §4 「平台聚合查询」段

### 6.2 测试环境

测试用 `batch_user` 通常是 SUPERUSER,**SUPERUSER 绕过所有 RLS**。要在测试里验 RLS 必须 `SET LOCAL ROLE` 切非特权 role。

参考:`RlsStrictModePreflightIntegrationTest` 里 `CREATE ROLE rls_app_user NOSUPERUSER NOBYPASSRLS` 模式。

### 6.3 `@Async` 路径(若有)

ThreadLocal 不传 — `RlsTenantContextHolder` 在子线程返 null → strict 模式下子线程操作全失败。

**修复**:`TaskDecorator` 显式 propagate(同 SLF4J MDC 处理)。

当前 worker 主路径是 Kafka consumer 线程,不涉及异步,**不影响**。

## 7. 翻转后:能否再回 strict→transition→strict 反复?

**可以**,完全幂等。但每次翻转都该走完 §2 checklist + §3 步骤,不要把它当"配置项"随便切。

## 8. 关联

- `docs/runbook/multi-tenant-rls.md`(总 runbook,设计依据)
- `scripts/db/business/rls-phase-a.sql`(transition policy 安装 — 还能跑,幂等)
- `scripts/db/business/rls-phase-a-strict.sql`(本翻转脚本)
- `scripts/db/business/rls-phase-a-rollback-to-transition.sql`(回滚脚本)
- `batch-common/src/test/java/com/example/batch/common/rls/RlsStrictModePreflightIntegrationTest.java`(翻转前必过的 preflight)
