# Test Full Coverage Import Suite

租户：`ta` / `tb` / `tc`、`default-tenant`

本目录下的 `*-tenant-config-package-test.xlsx` 是系统当前主用的整合式配置包（8 sheet：job_definition / file_channel_config / alert_routing_config / pipeline_definition / pipeline_step_definition / workflow_definition / workflow_node / workflow_edge），通过 `POST /api/console/config/tenant-package/excel/{upload,preview,apply}` 导入。

- `ta/tb/tc-tenant-config-package-test.xlsx`：三大业务租户样本。按租户分工覆盖枚举长尾——ta 补 IMPORT FEEDBACK 全链 / EXPORT 全链 / LOCAL channel；tb 补 DISPATCH 全链 / API channel；tc 补 GATEWAY ALL 与 N_OF join + FAILURE / CONDITION 边。幂等追加脚本 `scripts/local/append-tenant-coverage.py`。
- `default-tenant-config-package-test.xlsx`：与 `batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql` 中 v4 硬化批次新增的 `default-tenant` 项对齐（4 条本地化 channel_config + `wf_probe_pipeline` / `wf_probe_gateway` / `wf_probe_mixed` 3 条探针 workflow + 对应 job_definition）。生成脚本 `scripts/local/gen-default-tenant-excel.py`。

> **关于 `file_template_config`**：整合式 Excel 不含该 sheet 是系统设计，不是 gap。`ConsoleFileTemplateExcelController` 已 `@Deprecated`（建租户时从 `default` 租户克隆 + 页面单条维护，不走 Excel 批量）。`tenant-init` JSON 请求（`POST /api/console/config/tenant-init`）支持 `FileTemplateConfigUpsertParam`，需要批量初始化时走那条路；多租户 seed SQL 里的 `IMP-TXN-FIXED` / `IMP-TXN-XML` / `IMP-TRANSACTION-CSV.jdbcMappedImport` / `IMP-RISK-SCORE-JSON.jdbcMappedImport` / `EXP-RISK-ALERT-JSON.sqlTemplateExport` 也是这条路的样本（直接灌 DB 是为了 E2E 省事）。

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
