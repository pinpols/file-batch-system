# batch_business.biz 首版业务表示例

> 2026-10-18初始审查：示例库的分区/权限事实与整改见[分区专项](../../../docs/plans/database-partition-lifecycle-remediation-2026-10-18.md)。当时叶子直接授权与RLS覆盖存在缺口；后续RLS脚本及运行守护已补局部实现和真实PG证据，见下方说明与整合验证矩阵。transaction/risk_score/risk_alert持续扩建、PROCESS目标profile、多库、权限和在途保护仍需独立验收。

## 范围

本目录只提供“业务库示例模型”，用于本地联调和工程脚手架联通，不代表最终业务主数据模型。

数据库与 schema：

- Database: `batch_business`
- Schema: `biz`

## 设计目标

覆盖两个最小场景：

1. 导入
   - 对应设计文档中的 `customerImport`
   - 文件解析后落业务主表 `biz.customer_account`

2. 导出
   - 对应设计文档中的 `settlementExport`
   - 从 `biz.settlement_batch + biz.settlement_detail` 读取数据生成导出文件

## 表清单

- `biz.customer_account`
  - 客户主数据导入目标表示例
- `biz.settlement_batch`
  - 结算导出批次头表示例
- `biz.settlement_detail`
  - 结算导出明细表示例

## 建模假设

1. 业务库与平台库物理隔离，平台状态、链路、审计不下沉到业务库。
2. `tenant_id` 仍保留在业务表中，方便多租户隔离与导出裁剪。
3. 导入场景采用 UPSERT 口径，因此客户表使用 `(tenant_id, customer_no)` 唯一约束。
4. 导出场景绑定 `biz_date / accounting_period / batch_no`，避免实时全表导出。
5. 结算明细允许按批次、业务日期、客户号、状态组合检索，便于分页/游标导出。
6. 本示例不把业务表和平台表直接建立跨库外键，只通过业务键或导出参数关联。

## 审计字段规范

业务表示例统一包含：

- `tenant_id`
- `created_at`
- `updated_at`
- `created_by`
- `updated_by`

在需要追踪导入来源或导出口径时补充：

- `source_file_name`
- `source_batch_no`
- `source_trace_id`
- `snapshot_mode`
- `snapshot_ts`
- `source_partitions`

## 本地执行示例

### RLS 安装、切换与失败边界

`rls-phase-a.sql`、`rls-phase-a-strict.sql`、`rls-phase-a-rollback-to-transition.sql` 均由 psql 直接执行，脚本内部启用 `ON_ERROR_STOP`，在同一事务中修改全部父表/叶子；安装还将角色、授权一起提交。后段失败非零退出并回滚整批，不以“部分表成功”完成初始化。调用方必须传递失败，不要用 `|| true` 掩盖。

安装需要调用方显式注入 writer/admin 密码；新 writer 明确为 NOSUPERUSER/NOBYPASSRLS，已有同名 writer 若为 SUPERUSER/BYPASSRLS 则拒绝，不能静默复用或代改集群权限。普通应用不得使用管理员账号。

运行守护只接受本项目生成的 strict 形态：FOR ALL / PERMISSIVE / TO PUBLIC，USING/WITH CHECK 均为 `tenant_id = current_setting('app.tenant_id', true)`。未知额外 PERMISSIVE 策略或含 OR true 等宽松表达式报不健康；RESTRICTIVE 附加策略可保留。该守护不做任意自定义SQL的等价证明，也不会自动删除未知策略。

transition 是明确的事故降级，会允许无租户上下文读取，期间健康检查必须 DOWN；全新数据库正常部署始终使用 strict，不自动回退。三份实际脚本的父/叶目录、普通账号读写、失败回滚和策略反例见 `RlsScriptPartitionIntegrationTest`；本地定向证据不替代多物理库/生产部署验收。

### 示例表初始化

```bash
docker exec -i batch-postgres \
  psql -U batch_user -d batch_business -v ON_ERROR_STOP=1 \
  < scripts/db/business/create_biz_tables.sql
```

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [create_biz_tables.sql](create_biz_tables.sql) | batch_business.biz 业务表（导入/导出演示模型） |
| [diagnostic-readonly-role.sql](diagnostic-readonly-role.sql) | biz.* 只读排故角色(least privilege)。补 rls-phase-a 缺的「排查故障用哪个账户」。 |
| [migrate-process-staging-to-partitioned.sql](migrate-process-staging-to-partitioned.sql) | 一次性迁移:batch.process_staging 非分区旧表 → 天级 RANGE 分区 |
| [rls-phase-a-rollback-to-transition.sql](rls-phase-a-rollback-to-transition.sql) | Phase A · 回滚 strict → transition(应急脚本) |
| [rls-phase-a-strict.sql](rls-phase-a-strict.sql) | Phase A · 翻 strict 模式(从 transition policy → strict policy) |
| [rls-phase-a.sql](rls-phase-a.sql) | 供 [dr-drill.sh](../backup/dr-drill.sh) 使用的配套 SQL。 |
