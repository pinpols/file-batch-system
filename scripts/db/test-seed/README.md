# 系统测试种子包

这里放系统测试用的可重复种子 SQL。
推荐的测试分层说明见 [docs/testing/test-strategy.md](../../testing/test-strategy.md)。

## 包含内容

- `platform_seed.sql`
  - 向 `batch_platform.batch` 装载队列、配额、作业、工作流、流水线、文件、审批、告警、重试、Outbox 和治理状态等基础数据。
- `platform_edge_cases.sql`
  - 补充枚举状态、错误表、Outbox 投递日志、重试日志和文件错误记录等边界样本。
- `business_seed.sql`
  - 向 `batch_business.biz` 装载导入 / 导出源数据。
- `business_edge_cases.sql`
  - 补充更多结算批次状态和结算明细终态样本。

## 加载方式

优先使用辅助脚本：

```bash
scripts/data/load-system-test-data.sh
```

Or run the SQL directly:

```bash
psql -U batch_user -d batch_platform -f docs/sql/system-test/platform_seed.sql
psql -U batch_user -d batch_platform -f docs/sql/system-test/platform_edge_cases.sql
psql -U batch_user -d batch_business -f docs/sql/system-test/business_seed.sql
psql -U batch_user -d batch_business -f docs/sql/system-test/business_edge_cases.sql
```

基础种子脚本对专用测试库是幂等的：会先清理覆盖范围内的表，再写入一组固定数据。边界样本在基础种子上叠加额外覆盖。
