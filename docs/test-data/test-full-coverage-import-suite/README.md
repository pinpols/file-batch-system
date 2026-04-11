# Test Full Coverage Import Suite

租户：`test-full-coverage`

这套样本用于在前台按 Excel 导入方式初始化同一个租户，所有文件的 `tenant_id`、编码和上下游引用已经对齐。

推荐导入顺序：

1. `01-resource-queue-full-coverage-test.xlsx`
2. `02-batch-window-full-coverage-test.xlsx`
3. `03-business-calendar-full-coverage-test.xlsx`
4. `04-tenant-quota-policy-full-coverage-test.xlsx`
5. `05-file-channel-full-coverage-test.xlsx`
6. `06-file-template-full-coverage-test.xlsx`
7. `07-pipeline-definition-full-coverage-test.xlsx`
8. `08-job-definition-full-coverage-test.xlsx`
9. `09-workflow-full-coverage-test.xlsx`
10. `10-alert-routing-full-coverage-test.xlsx`

说明：

- `resource queue` 中的 `queue-general`、`queue-export`、`queue-workflow` 已与 job definition 样本保持一致。
- `workflow` 引用了 `general-manual-001` 和 `import-cron-001` 这两个 job code。
- `pipeline definition` 使用了 `import-cron-001`、`export-cron-001`，并与 `file channel`、`file template` 的编码保持一致。
- 每个 workbook 都带 `README`、`DICT`、`VALIDATION` sheet，便于和系统模板习惯保持一致。
