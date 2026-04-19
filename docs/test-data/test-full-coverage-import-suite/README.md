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

## E2E 渠道基础设施（ta/tb/tc）

前端跑文件导入 / 文件发送的端到端链路需要真实 SFTP / OSS / HTTP 对端。用 `test` profile 一起起：

```bash
docker compose --profile test up -d           # 只起 sftp + mockserver
docker compose --profile apps --profile test up -d   # 连同 Java 服务一起
```

三租户 `file_channel_config` 的 endpoint 已对齐容器网络名：

| 租户 | channel_code | 类型 | target_endpoint | 账号/路径 |
|---|---|---|---|---|
| ta | `ta_sftp_inbound` | SFTP | `sftp:22` | user=`ta` / pass=`ta_pass_123` / `/inbound` |
| ta | `ta_oss_export` | OSS | `http://minio:9000` | bucket=`batch-dev` / prefix=`ta/outbound/report/` |
| tb | `tb_sftp_inbound` | SFTP | `sftp:22` | user=`tb` / pass=`tb_pass_123` / `/inbound` |
| tb | `tb_api_push` | API_PUSH | `http://mockserver:1080/tb/callback` | tokenHeader=`X-API-Token` |
| tb | `tb_oss_statement` | OSS | `http://minio:9000` | bucket=`batch-dev` / prefix=`tb/outbound/statement/` |
| tc | `tc_api_risk_push` | API_PUSH | `http://mockserver:1080/tc/ingest` | tokenHeader=`X-API-Token` |
| tc | `tc_sftp_score` | SFTP | `sftp:22` | user=`tc` / pass=`tc_pass_123` / `/inbound` |

样本文件（执行 `scripts/data/load-system-test-data.sh` 自动装载 MinIO 部分；SFTP 部分由 `docker/sftp/data/` 通过 volume 挂载自动生效）：

- SFTP：`docker/sftp/data/{ta,tb,tc}/inbound/*.{csv,json}` → 容器内 `/home/{tenant}/inbound/`
- MinIO：`batch-dev/{ta,tb,tc}/inbound/*` 同名文件；`{ta,tb}/outbound/*/.keep` 占位导出目录
- MockServer：POST `/ta/callback` / `/tb/callback` / `/tc/ingest` 均返回 200（定义在 `docker/mockserver/expectations.json`）

宿主机端口默认：SFTP `12222`、MockServer `11080`（由 `.env` 的 `SFTP_HOST_PORT` / `MOCKSERVER_HOST_PORT` 覆盖）。
