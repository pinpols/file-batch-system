# Test Full Coverage Import Suite

租户：`ta` / `tb` / `tc`、`default-tenant`

本目录下的 `*-tenant-config-package-test.xlsx` 是当前配置包样本（10 sheet：resource_queue / business_calendar / batch_window / job_definition / job_monitoring_policy / file_channel_config / file_template_config / workflow_definition / workflow_node / workflow_edge），通过 `POST /api/console/config/tenant-package/excel/{upload,preview,apply}` 导入。作业监控阈值和告警级别只在 `job_monitoring_policy` 维护，不与作业定义重复。租户 Pipeline 配置表已退役；上传包含 `pipeline_definition` 或 `pipeline_step_definition` 的工作簿会被拒绝。

`job_monitoring_policy` 中无依赖 Cron 使用 `completion_deadline_local_time` / `completion_deadline_day_offset`；依赖作业使用 `dependency_completion_window_seconds`，从满足执行资格时刻起算。固定频率独立作业仅适用运行耗时监控；运行耗时阈值不统一默认开启。

修改工作簿、生成脚本或配置包导入 schema 后，必须执行 `python3 scripts/fix-fixture-xlsx.py --check`。该检查要求样本不含已退役 Pipeline sheet；本地修复命令会移除这些旧 sheet 并按当前模板修复其余字段，运行后需审查工作簿差异并再次执行检查模式。

- `ta/tb/tc-tenant-config-package-test.xlsx`：三大业务租户样本。按租户分工覆盖配置枚举和 Workflow 拓扑——ta 补 IMPORT Job / LOCAL channel；tb 补 DISPATCH Job / API channel；tc 补 GATEWAY ALL 与 N_OF join + FAILURE / CONDITION 边。阶段由系统 Pipeline 按 Job 类型固定提供，不再由租户 Excel 定义。幂等追加脚本 `scripts/local/append-tenant-coverage.py`。
- `default-tenant-config-package-test.xlsx`：与 `batch-test-support/src/main/resources/db/testdata/multi-tenant-seed.sql` 中 v4 硬化批次新增的 `default-tenant` 项对齐（4 条本地化 channel_config + `wf_probe_pipeline` / `wf_probe_gateway` / `wf_probe_mixed` 3 条探针 workflow + 对应 job_definition）。生成脚本 `scripts/local/gen-default-tenant-excel.py`。

> **关于基础依赖**：配置包可携带 `resource_queue`、`business_calendar`、`batch_window`、`file_template_config` 和独立的 `job_monitoring_policy`。Import 目标表、字段映射和 Export SQL 由 `file_template_config` 承载。含已退役租户 Pipeline sheet 的旧 11/12-sheet 工作簿会被拒绝，需从当前 10-sheet 模板重新导出或整理。

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

样本文件（执行 `scripts/data/load-system-test-data.sh` 自动装载 MinIO 部分；SFTP 部分由 `deploy/docker/sftp/data/` 通过 volume 挂载自动生效）：

- SFTP：`deploy/docker/sftp/data/{ta,tb,tc}/inbound/*.{csv,json}` → 容器内 `/home/{tenant}/inbound/`
- MinIO：`batch-dev/{ta,tb,tc}/inbound/*` 同名文件；`{ta,tb}/outbound/*/.keep` 占位导出目录
- MockServer：POST `/ta/callback` / `/tb/callback` / `/tc/ingest` 均返回 200（定义在 `deploy/docker/mockserver/expectations.json`）

宿主机端口默认：SFTP `12222`、MockServer `11080`（由 `.env` 的 `SFTP_HOST_PORT` / `MOCKSERVER_HOST_PORT` 覆盖）。
标准本地 JVM SIM 会由 `sim-e2e-bootstrap.sql` 将 API channel 改写为宿主机映射端口；不改动 Excel 包中面向 Compose 应用的容器网络地址。
