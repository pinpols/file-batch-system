# batch_business.biz 首版业务表示例

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

```bash
docker exec -i batch-postgres \
  psql -U batch_user -d batch_business -v ON_ERROR_STOP=1 \
  < docs/sql/business/V1__create_biz_example_tables.sql
```
