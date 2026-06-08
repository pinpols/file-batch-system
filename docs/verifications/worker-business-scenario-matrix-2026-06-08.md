# Worker 业务场景矩阵验证报告（2026-06-08）

## 结论

Stage 1 已完成：使用现有本地库和本地模拟上下游，通过系统 API 触发，不走前台，覆盖 ta/tb/tc 三租户的 IMPORT / EXPORT / DISPATCH / WORKFLOW 主业务链路。

Stage 2 第一批已完成：补齐 `XML` / `FIXED_WIDTH` import 可触发模板，通过系统 API 覆盖成功态、解析失败、字段校验失败三类分支。

Stage 3 第一批已完成：复用 `TA_EXPORT_REPORT` job，通过不同 `templateCode` 覆盖 `JSON` / `FIXED_WIDTH` / `EXCEL` 导出成功态和 bad SQL 失败态。

Stage 4 第一批已完成：补 PROCESS `sqlTransformCompute` 系统级小矩阵，覆盖 JSONB staging 成功、DIRECT fast path 成功、VALIDATE 失败和 empty result SUCCESS 策略。

Stage 5 第一批已完成：Dispatch HTTP 500 分支覆盖了重试等待和无重试终态失败两种语义；Atomic SQL / shell / stored-proc 已成功，Atomic HTTP 真请求被 SSRF 安全闸拦截。

Stage 6 第一批已完成：Trigger API launch 去重小矩阵通过，同一 `requestId` 连续提交两次只创建 1 个 trigger request / job instance。

Stage 7 已完成：新增统一自动化入口，并用默认 smoke profile 复跑 Stage 2/3/4 成功。

本轮有效验证 run：

- `RUN_ID`: `bizsim-stage1-20260608111255`
- `batchNo`: `sim-20260608111255`
- 日志：`load-tests/target/bizsim-stage1-20260608111255/stage1-sim-baseline.log`
- 触发方式：`bash scripts/sim/05-load.sh`
- 对账方式：`bash scripts/sim/06-verify.sh` + 按 `created_at >= '2026-06-08 11:12:55+08'` 的 SQL 明细复核

Stage 2 import run：

- `RUN_ID`: `import-stage2-20260608112604`
- `batchNo`: `sim-import-stage2-20260608112604`
- 日志：`load-tests/target/import-stage2-20260608112604/import-stage2.log`
- 触发方式：`bash scripts/sim/08-import-stage2.sh`
- 覆盖 job：`TA_IMPORT_CUSTOMER_XML`、`TA_IMPORT_CUSTOMER_FIXED`

Stage 3 export run：

- `RUN_ID`: `export-stage3-20260608113238`
- `batchNo`: `sim-export-stage3-20260608113238`
- 日志：`load-tests/target/export-stage3-20260608113238/export-stage3.log`
- 触发方式：`bash scripts/sim/09-export-stage3.sh`
- 覆盖 job：`TA_EXPORT_REPORT`
- 覆盖模板：`TA_EXPORT_REPORT_JSON_TPL`、`TA_EXPORT_REPORT_FIXED_TPL`、`TA_EXPORT_REPORT_EXCEL_TPL`、`TA_EXPORT_REPORT_BAD_SQL_TPL`

Stage 4 process run：

- `RUN_ID`: `process-stage4-20260608113632`
- `batchNo`: `sim-process-stage4-20260608113632`
- 日志：`load-tests/target/process-stage4-20260608113632/process-stage4.log`
- 触发方式：`bash scripts/sim/10-process-stage4.sh`
- 覆盖 job：`TA_PROCESS_STAGE4_JSONB`、`TA_PROCESS_STAGE4_DIRECT`、`TA_PROCESS_STAGE4_VALIDATE_FAIL`、`TA_PROCESS_STAGE4_EMPTY_SUCCESS`

Stage 5 dispatch / atomic run：

- Dispatch HTTP 500 batch：`sim-dispatch-stage5-20260608114259`
- Dispatch job instance：`19179`
- Dispatch file id：`6187`
- Dispatch no-retry batch：`sim-dispatch-stage5-none-20260608115105`
- Dispatch no-retry job instance：`19205`
- Dispatch no-retry file id：`6225`
- Atomic run：`scripts/sim/07-atomic-load.sh` `ROUNDS=1`
- Atomic stored-proc 修正后 job instance：`19175`
- Atomic HTTP localhost 覆盖 job instance：`19177` / dry-run attempt `19178`

Stage 6 trigger run：

- `requestId`: `sim-trigger-dedup-1780890401379322000`
- 覆盖 job：`TA_PROCESS_STAGE4_EMPTY_SUCCESS`
- 结果：两次 API launch 返回同一个 `instanceNo` / `traceId`

Stage 7 automation run：

- `RUN_ID`: `worker-biz-matrix-20260608114831`
- 入口：`bash load-tests/scripts/run-worker-business-scenario-matrix.sh`
- profile：`smoke`
- 覆盖 Stage：2 / 3 / 4
- summary：`load-tests/target/worker-biz-matrix-20260608114831/worker-business-scenario-matrix-summary.md`
- log：`load-tests/target/worker-biz-matrix-20260608114831/worker-business-scenario-matrix.log`

## 覆盖范围

| 场景 | 覆盖结果 | 证据 |
|---|---:|---|
| Console Excel 配置包上传/应用 | 通过 | `scripts/sim/03-import-tenants.sh` 导入 ta/tb/tc 成功，job 数量 ta=5/tb=4/tc=6 |
| IMPORT 直触发 | 通过 | ta/tb/tc 直接导入均 `SUCCESS`，业务表各新增有效行 |
| EXPORT 直触发 | 通过 | ta/tb/tc 直接导出均 `SUCCESS`，MinIO outbound 生成对象 |
| DISPATCH 直触发 | 通过 | `tb_api_push`、`tb_api_ingest`、`tc_api_risk_push` 均 `ACKED/SUCCESS` |
| WORKFLOW 串行 import -> export | 通过 | `TA_WF_SETTLEMENT`、`TB_WF_RECONCILE` 均 `SUCCESS` |
| WORKFLOW 风控链路 | 通过 | `TC_WF_RISK_PIPELINE` 本轮已完成 import/export/dispatch 入口验证 |
| Outbox 积压 | 通过 | `outbox_event` NEW/FAILED 积压为 0 |
| IMPORT XML 成功态 | 通过 | `TA_IMPORT_CUSTOMER_XML` `SUCCESS`，业务表新增 `customer_no like XML-%` 2 行 |
| IMPORT FIXED_WIDTH 成功态 | 通过 | `TA_IMPORT_CUSTOMER_FIXED` `SUCCESS`，业务表新增 `customer_no like FIX-%` 2 行 |
| IMPORT XML 解析失败 | 通过 | malformed XML 进入 `FAILED`，`error_code=IMPORT_PARSE_FAILED` |
| IMPORT FIXED_WIDTH 解析失败 | 通过 | 短行进入 `FAILED`，`error_code=IMPORT_PARSE_FAILED` |
| IMPORT 校验失败 | 通过 | `status=BLOCKED` 进入 `FAILED`，`error_code=IMPORT_VALIDATE_STATUS_INVALID` |
| EXPORT JSON 成功态 | 通过 | `TA_EXPORT_REPORT_JSON_TPL` `SUCCESS`，file_record `JSON/GENERATED` |
| EXPORT FIXED_WIDTH 成功态 | 通过 | `TA_EXPORT_REPORT_FIXED_TPL` `SUCCESS`，file_record `FIXED_WIDTH/GENERATED` |
| EXPORT EXCEL 成功态 | 通过 | `TA_EXPORT_REPORT_EXCEL_TPL` `SUCCESS`，file_record `EXCEL/GENERATED` |
| EXPORT bad SQL 失败态 | 通过 | `TA_EXPORT_REPORT_BAD_SQL_TPL` `FAILED`，`error_code=EXPORT_GENERATE_FAILED` |
| PROCESS JSONB staging 成功态 | 通过 | `TA_PROCESS_STAGE4_JSONB` `SUCCESS`，target 写入 2 个账户汇总 |
| PROCESS DIRECT fast path 成功态 | 通过 | `TA_PROCESS_STAGE4_DIRECT` `SUCCESS`，target 写入 2 个账户汇总，staging 不落行 |
| PROCESS validation 失败态 | 通过 | `TA_PROCESS_STAGE4_VALIDATE_FAIL` `FAILED`，`error_code=PROCESS_VALIDATION_FAILED` |
| PROCESS empty result SUCCESS | 通过 | `TA_PROCESS_STAGE4_EMPTY_SUCCESS` `SUCCESS`，0 行结果按策略成功 |
| DISPATCH HTTP 500 失败/补偿 | 通过 | `TB_DISPATCH_SETTLE` 按 `EXPONENTIAL/5` 进入 `RETRYING`；`TB_DISPATCH_STAGE5_FAIL_ONCE` 按 `NONE/0` 进入 `FAILED` |
| ATOMIC SQL | 通过 | `atomic_sql_demo` `SUCCESS` |
| ATOMIC shell | 通过 | `atomic_shell_demo` `SUCCESS` |
| ATOMIC stored-proc | 通过 | 补 `batch.refresh_metrics()` procedure 后 `atomic_stored_proc_demo` `SUCCESS` |
| ATOMIC HTTP 真请求 | 安全拦截 | 覆盖到 `http://localhost:11080/tb/ingest` 被 SSRF 闸拒绝：`localhost -> 127.0.0.1` |
| TRIGGER requestId 去重 | 通过 | 同一 requestId 连续 launch 两次，trigger_request=1，job_instance=1 |
| 统一自动化入口 smoke | 通过 | `PROFILE=smoke` 串行复跑 Stage 2/3/4 成功 |

本轮精确状态复核：

| tenant | job | status | count |
|---|---|---:|---:|
| ta | TA_IMPORT_CUSTOMER | SUCCESS | 2 |
| ta | TA_EXPORT_REPORT | SUCCESS | 2 |
| ta | TA_WF_SETTLEMENT | SUCCESS | 1 |
| tb | TB_IMPORT_TRANSACTION | SUCCESS | 1 |
| tb | TB_EXPORT_STATEMENT | SUCCESS | 1 |
| tb | TB_DISPATCH_SETTLE | SUCCESS | 2 |
| tc | TC_IMPORT_RISK_SCORE | SUCCESS | 2 |
| tc | TC_EXPORT_RISK_ALERT | SUCCESS | 1 |
| tc | TC_DISPATCH_REVIEW | SUCCESS | 1 |

非成功明细：0 行。

Stage 2 import 精确状态复核：

| job_instance_id | job | instance_status | task_status | error_code |
|---:|---|---:|---:|---|
| 19151 | TA_IMPORT_CUSTOMER_XML | SUCCESS | SUCCESS | |
| 19152 | TA_IMPORT_CUSTOMER_FIXED | SUCCESS | SUCCESS | |
| 19153 | TA_IMPORT_CUSTOMER_XML | FAILED | FAILED | IMPORT_VALIDATE_STATUS_INVALID |
| 19154 | TA_IMPORT_CUSTOMER_XML | FAILED | FAILED | IMPORT_PARSE_FAILED |
| 19155 | TA_IMPORT_CUSTOMER_FIXED | FAILED | FAILED | IMPORT_PARSE_FAILED |

Stage 2 import 文件状态复核：

| biz_type | file_format_type | file_status | count |
|---|---|---:|---:|
| TA_IMPORT_CUSTOMER_XML | XML | LOADED | 1 |
| TA_IMPORT_CUSTOMER_FIXED | FIXED_WIDTH | LOADED | 1 |
| TA_IMPORT_CUSTOMER_XML | XML | FAILED | 2 |
| TA_IMPORT_CUSTOMER_FIXED | FIXED_WIDTH | FAILED | 1 |

Stage 3 export 精确状态复核：

| job_instance_id | template | instance_status | task_status | error_code |
|---:|---|---:|---:|---|
| 19162 | TA_EXPORT_REPORT_JSON_TPL | SUCCESS | SUCCESS | |
| 19163 | TA_EXPORT_REPORT_FIXED_TPL | SUCCESS | SUCCESS | |
| 19164 | TA_EXPORT_REPORT_EXCEL_TPL | SUCCESS | SUCCESS | |
| 19165 | TA_EXPORT_REPORT_BAD_SQL_TPL | FAILED | FAILED | EXPORT_GENERATE_FAILED |

Stage 3 export 文件状态复核：

| biz_type | file_format_type | file_status | files | bytes |
|---|---|---:|---:|---:|
| TA_EXPORT_REPORT_JSON | JSON | GENERATED | 1 | 1106 |
| TA_EXPORT_REPORT_FIXED | FIXED_WIDTH | GENERATED | 1 | 445 |
| TA_EXPORT_REPORT_EXCEL | EXCEL | GENERATED | 1 | 3699 |

Stage 4 process 精确状态复核：

| job_instance_id | job | instance_status | task_status | error_code |
|---:|---|---:|---:|---|
| 19167 | TA_PROCESS_STAGE4_JSONB | SUCCESS | SUCCESS | |
| 19168 | TA_PROCESS_STAGE4_DIRECT | SUCCESS | SUCCESS | |
| 19169 | TA_PROCESS_STAGE4_VALIDATE_FAIL | FAILED | FAILED | PROCESS_VALIDATION_FAILED |
| 19170 | TA_PROCESS_STAGE4_EMPTY_SUCCESS | SUCCESS | SUCCESS | |

Stage 4 process target 复核：

| scenario | rows | total_amount_sum | high_water_mark_max |
|---|---:|---:|---:|
| JSONB | 2 | 175.50 | 3 |
| DIRECT | 2 | 500.00 | 5 |

Stage 4 validation fail 后 staging 残留：

| target_table | batch_key | rows |
|---|---|---:|
| process_stage4_target | sim-process-stage4-20260608113632-validate-fail | 1 |

Stage 5 dispatch / atomic 复核：

| item | instance_id | status | evidence |
|---|---:|---:|---|
| Dispatch HTTP 500 with retry | 19179 | RUNNING / RETRYING | `TB_DISPATCH_SETTLE` retry_policy=`EXPONENTIAL`, retry_count=3/5, retry_schedule WAITING |
| Dispatch HTTP 500 no retry | 19205 | FAILED | `TB_DISPATCH_STAGE5_FAIL_ONCE` retry_policy=`NONE`, partition `FAILED`, dispatch record `COMPENSATED` |
| Atomic shell | 19171 | SUCCESS | `atomic_shell_demo` |
| Atomic SQL | 19172 | SUCCESS | `atomic_sql_demo` |
| Atomic stored-proc 初次 | 19174 | FAILED | 缺 `batch.refresh_metrics()` |
| Atomic stored-proc 复验 | 19175 | SUCCESS | `CREATE PROCEDURE batch.refresh_metrics()` 后成功 |
| Atomic HTTP external demo | 19173 | FAILED | `example.internal` TLS handshake 失败 |
| Atomic HTTP localhost override | 19177 | blocked | `host resolves to blocked address: localhost -> 127.0.0.1` |

Stage 6 trigger 去重复核：

| request_id | launch_count | trigger_requests | job_instances | result |
|---|---:|---:|---:|---|
| sim-trigger-dedup-1780890401379322000 | 2 | 1 | 1 | PASS |

## 本轮修复

| 问题 | 修复 |
|---|---|
| Excel apply 更新旧 job 时 `execution_mode` 为空，触发 NOT NULL/校验失败 | Console Excel apply 默认补 `FULL`，fixture xlsx 同步补 `execution_mode` |
| `03-import-tenants.sh` 不能稳定复跑 | 自动登录、补 `tenantId`、`Idempotency-Key`、apply body，并自动应用 bootstrap |
| MockServer Docker health 假失败 | `02-start-sim.sh` 改为 host HTTP readiness，不再依赖镜像内 `/bin/sh` |
| 导入/导出模板只有占位 spec，worker 无法真实执行 | `sim-e2e-bootstrap.sql` 注入 jdbcMappedImport / sqlTemplateExport runtime config |
| workflow 子 job 缺默认 template/content | `job_definition.default_params` 补 templateCode 和最小 content |
| IMPORT/EXPORT/DISPATCH step_order 旧 fixture 存在并列顺序 | bootstrap 统一规范 step_order |
| DISPATCH ACK 成功后继续落入 RETRY/COMPENSATE | bootstrap 补 DISPATCH step_params：ACK 成功跳 COMPLETE，COMPENSATE/COMPLETE 成功终止 |
| 同一天重复导出撞旧 file_record checksum | `05-load.sh` 每轮生成唯一 `batchNo`，导出对象路径自然隔离 |
| dispatch 误拿 INPUT 文件做派发 | `05-load.sh` 只选择 `OUTPUT/GENERATED` 且 `source_ref=batchNo` 的 file_record |
| MinIO 计数误把目录/`.keep` 算文件 | `06-verify.sh` 修正递归文件计数 |
| 当前 seed 无 XML/FIXED_WIDTH 可触发模板 | `sim-e2e-bootstrap.sql` 新增 `TA_IMPORT_CUSTOMER_XML_TPL` / `TA_IMPORT_CUSTOMER_FIXED_TPL`、对应 job/pipeline/step clone |
| XML/FIXED_WIDTH 缺系统 API 复跑入口 | 新增 `scripts/sim/08-import-stage2.sh`，统一造数、触发、等待终态、输出 job/file/biz 对账 |
| Export 格式矩阵缺系统 API 复跑入口 | `sim-e2e-bootstrap.sql` 新增 Stage 3 export 模板，`scripts/sim/09-export-stage3.sh` 统一 seed 小数据、触发、等待终态、输出 job/file/object 对账 |
| Process 业务分支缺系统 API 复跑入口 | 新增 `scripts/sim/10-process-stage4.sh`，统一 seed source/target、平台 job/pipeline、触发、等待终态、输出 job/target/staging 对账 |
| Atomic stored-proc demo 缺本地过程 | 本地补 `batch.refresh_metrics()` procedure 后复验成功 |

## 暴露问题

| 编号 | 问题 | 影响 | 当前证据 | 下一步 |
|---|---|---|---|---|
| S5-ATOMIC-HTTP-01 | Atomic HTTP 本地 MockServer 真请求被 SSRF 闸拒绝 | 本地不能用 localhost 证明 atomic HTTP 真发送成功 | `host resolves to blocked address: localhost -> 127.0.0.1` | 保持安全闸；若要系统级真成功，需提供允许的非 loopback 测试域名或专门测试 profile |
| S7-AUTO-01 | Stage 5/6 暂未纳入默认自动化入口 | 默认 smoke 只跑高确定性矩阵；Stage 5 含外部失败/安全闸语义，Stage 6 当前仍是 one-shot 去重场景 | `load-tests/scripts/run-worker-business-scenario-matrix.sh` 默认 Stage 2/3/4 | 后续沉淀 `failure` profile 纳入 Stage 5；Stage 6 单独脚本化 |

## 本轮未覆盖

| 项 | 状态 | 原因 / 下一步 |
|---|---|---|
| `TA_DISPATCH_ORDER` HTTP 下游 | 未覆盖 | 当前 fixture 没有 ta 独立 HTTP channel；脚本显式跳过，避免占位 channel 造成假失败 |
| XML / FIXED_WIDTH import | 已覆盖 Stage 2 第一批 | 已覆盖成功态、解析失败、字段校验失败；bad record skip 阈值、LOAD failure、断点续跑、load mode 组合仍待补 |
| JSON / FIXED_WIDTH / EXCEL export | 已覆盖 Stage 3 第一批 | 已覆盖小规模系统链路成功态；DELIMITED 已在 Stage 1 主链路覆盖；更高分片数、真实 S3、multipart abort/retry、导出续跑仍待补 |
| Excel multipart 配置包上传 | 已覆盖配置包链路 | 属于 Console `/tenant-package/excel/upload`，不属于 worker-import trigger；本轮通过 `03-import-tenants.sh` 覆盖 |
| PROCESS worker 业务场景 | 已覆盖 Stage 4 第一批 | 已覆盖 JSONB staging、DIRECT、validation failure、empty success；幂等重跑、取消/超时、分片 process、失败恢复仍待补 |
| DISPATCH failure terminal | 已覆盖 Stage 5 第一批 | HTTP 500 + retry job 进入 RETRYING；HTTP 500 + no-retry job 进入 FAILED；SFTP/NAS/local sidecar 仍待补 |
| ATOMIC HTTP 真成功 | 未覆盖 | 本地 loopback 被安全闸拒绝；需要非 loopback allowlisted 测试端点或测试 profile |
| TRIGGER 高频 cron/misfire | 未覆盖 | 本轮先补 API launch 去重；cron/misfire/task storm 仍是容量 profile |
| external OSS/NAS/SFTP/EMAIL 真实远端 | 未覆盖 | 本轮使用本地 MinIO/SFTP/MockServer；真实远端放外部依赖矩阵 |
| DAST application-level | 未覆盖 | 不属于本轮 worker 业务场景 sim |

## 运行约束

- 本轮未跑 Maven 单测/IT，按要求只做系统 API 触发和数据库/对象存储/MockServer 对账。
- 本地 dispatch 到 `localhost:11080` 需要 `BATCH_SECURITY_BYPASS_MODE=true`，本轮仅对 `worker-dispatch` 本地进程开启，用于本地 sim。
- `06-verify.sh` 的“近 10 分钟全局状态”可能混入探索失败记录；结论以本报告的精确时间过滤 SQL 为准。

## 下一阶段

1. Stage 2 继续：补 import bad record skip 阈值、LOAD failure、断点续跑、APPEND/UPSERT/REPLACE/partition load mode 小矩阵。
2. Stage 3 继续：补 export 更高分片数小规模、multipart abort/retry、失败恢复、多租户混压。
3. Stage 4 继续：补 PROCESS 幂等重跑、取消/超时、分片 process、失败恢复。
4. Stage 5 继续：补 SFTP/NAS/local sidecar；如有非 loopback 测试域名再补 Atomic HTTP 真成功。
5. Stage 6 继续：补高频 cron/misfire/replay/task storm。
6. Stage 7 继续：把 Stage 5 dispatch/atomic 纳入 failure profile；把 Stage 6 trigger 去重沉淀为脚本。
