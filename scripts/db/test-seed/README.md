# 系统测试种子包

这里放系统测试用的可重复种子 SQL。
推荐的测试分层说明见 [docs/testing/README.md](../../../docs/testing/README.md)。

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
psql -U batch_user -d batch_platform -f scripts/db/test-seed/platform_seed.sql
psql -U batch_user -d batch_platform -f scripts/db/test-seed/platform_edge_cases.sql
psql -U batch_user -d batch_business -f scripts/db/test-seed/business_seed.sql
psql -U batch_user -d batch_business -f scripts/db/test-seed/business_edge_cases.sql
```

基础种子脚本对专用测试库是幂等的：会先清理覆盖范围内的表，再写入一组固定数据。边界样本在基础种子上叠加额外覆盖。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [business_edge_cases.sql](business_edge_cases.sql) | batch_business.biz edge-case seed data (CANCELLED / ARCHIVED 等异常状态样本) |
| [business_seed.sql](business_seed.sql) | batch_business.biz seed data (默认租户 / 联调初始数据) |
| [platform_edge_cases.sql](platform_edge_cases.sql) | 重建测试租户和平台夹具表，会清空原数据。仅限隔离测试数据库，禁止连接共享或生产环境。 |
| [platform_seed.sql](platform_seed.sql) | 重建测试种子涉及的表，会清空原数据。仅限隔离测试数据库，禁止连接共享或生产环境。 |
