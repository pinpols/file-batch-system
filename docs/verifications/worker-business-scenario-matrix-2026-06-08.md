# Worker 业务场景矩阵验证报告（2026-06-08）

## 结论

Stage 1 已完成：使用现有本地库和本地模拟上下游，通过系统 API 触发，不走前台，覆盖 ta/tb/tc 三租户的 IMPORT / EXPORT / DISPATCH / WORKFLOW 主业务链路。

Stage 2 第一批已完成：补齐 `XML` / `FIXED_WIDTH` import 可触发模板，通过系统 API 覆盖成功态、解析失败、字段校验失败三类分支。

Stage 3 第一批已完成：复用 `TA_EXPORT_REPORT` job，通过不同 `templateCode` 覆盖 `JSON` / `FIXED_WIDTH` / `EXCEL` 导出成功态和 bad SQL 失败态。

Stage 4 第一批已完成：补 PROCESS `sqlTransformCompute` 系统级小矩阵，覆盖 JSONB staging 成功、DIRECT fast path 成功、VALIDATE 失败和 empty result SUCCESS 策略。

Stage 5 第一批已完成：Dispatch HTTP 500 分支覆盖了重试等待和无重试终态失败两种语义；Atomic SQL / shell / stored-proc 已成功，Atomic HTTP 真请求被 SSRF 安全闸拦截。

Stage 6 第一批已完成：Trigger API launch 去重小矩阵通过，同一 `requestId` 连续提交两次只创建 1 个 trigger request / job instance。

Stage 7 已完成：新增统一自动化入口，默认 smoke 已扩展为 Stage 2/2b/3/3b/4/4b/5/6。

本轮第二批增量已完成：Import 补 UPSERT 重跑幂等、LOAD 失败、`PARTITION_REPLACE_COPY` 多分片 fail-fast；Export 补 `partition_keyset_range` 4 分片正确性；Process 补稳定 `batchKey` 幂等重跑和残留 staging 恢复；Dispatch/Atomic 补 no-retry 失败补偿和 atomic shell/sql/stored-proc 终态；Trigger 补 requestId 去重和 30 请求 storm 收敛。

本轮第三批增量已完成：Import 补 APPEND/UPSERT/`PARTITION_REPLACE_COPY` 小矩阵和 bad-record skip 阈值；Export 补 8 分片小规模、多租户混压、requestId replay 幂等；Process 补真正分片 SQL 参数和取消当前语义；Dispatch 补 LOCAL/NAS/SFTP sidecar manifest；Atomic 补非 loopback HTTP 真成功、SQL timeout 分类和 task cancel 信号；Trigger 补 scheduler 定时触发、misfire pending、replay approve 和 60 请求 storm。

本轮第四批收敛已完成：Import checkpoint 真实崩溃续跑 fault-injection profile 通过；Trigger `MANUAL_APPROVAL` misfire pending 自动创建/关联 catch-up request 并可直接按 `pendingId` approve；Process RUNNING cancel 与 Atomic shell cancel 均已从“只置标记”修成 worker 侧中断并以取消失败码终态收敛。

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

第二批增量 run：

- Import Stage 2b：`sim-import-stage2b-20260608121302`，日志 `load-tests/target/import-stage2b-20260608121302/import-stage2b.log`
- Export Stage 3b：`sim-export-stage3b-20260608121250`，日志 `load-tests/target/export-stage3b-20260608121250/export-stage3b.log`
- Process Stage 4b：`sim-process-stage4b-20260608121611`，日志 `load-tests/target/process-stage4b-20260608121611/process-stage4b.log`
- Dispatch Stage 5b：`sim-dispatch-stage5b-20260608121906`，日志 `load-tests/target/dispatch-stage5b-20260608121906/dispatch-stage5b.log`
- Atomic Stage 5b：`atomic-stage5b-20260608122136`，日志 `load-tests/target/atomic-stage5b-20260608122136/atomic-stage5b.log`
- Trigger Stage 6b：`sim-trigger-stage6b-20260608122009`，日志 `load-tests/target/trigger-stage6b-20260608122009/trigger-stage6b.log`
- 总控 Stage 5/6 组合复跑：`worker-biz-matrix-stage5-6-20260608122450`，summary `load-tests/target/worker-biz-matrix-stage5-6-20260608122450/worker-business-scenario-matrix-summary.md`

第三批增量 run：

- Import Stage 2c：`sim-import-stage2c-20260608125228`，日志 `load-tests/target/import-stage2c-20260608125228/import-stage2c.log`，断言 `2|1|Stage2c Upsert Updated|INACTIVE|2|0`
- Import Stage 2d：`sim-import-stage2d-20260608134108`，日志 `load-tests/target/import-stage2d-20260608134108/import-stage2d.log`，断言 `SUCCESS|SUCCESS||FAILED|FAILED|IMPORT_SKIP_THRESHOLD_EXCEEDED|loaded_ok=1|blocked_loaded=0|errors=1|2`
- Export Stage 3c：`sim-export-stage3c-20260608125522`，日志 `load-tests/target/export-stage3c-20260608125522/export-stage3c.log`，断言 `1|1|8|8|8|80|3|3`
- Process Stage 4c：`sim-process-stage4c-20260608130831`，日志 `load-tests/target/process-stage4c-20260608130831/process-stage4c.log`，断言 `4|4|16|296.00|16|16|SUCCESS|SUCCESS|SUCCESS`
- Dispatch Stage 5c：`sim-dispatch-stage5c-20260608131359`，日志 `load-tests/target/dispatch-stage5c-20260608131359/dispatch-stage5c.log`，断言 `instances=3/3|records=tb_stage5c_local:ACKED:SUCCESS,tb_stage5c_nas:ACKED:SUCCESS,tb_stage5c_sftp:ACKED:SUCCESS|local=1|nas=1:True|sftp=True`
- Atomic Stage 5c：`sim-atomic-stage5c-20260608134703`，日志 `load-tests/target/atomic-stage5c-20260608134703/atomic-stage5c.log`，断言 `SUCCESS|FAILED:TIMEOUT:TIMEOUT|SUCCESS:SUCCESS:true|cancelHttp=200`
- Trigger Stage 6c：`sim-trigger-stage6c-20260608133013`，日志 `load-tests/target/trigger-stage6c-20260608133013/trigger-stage6c.log`，断言 `scheduled=1|misfire=1|replay=LAUNCHED|19350|storm=60/60`

第四批收敛 run：

- Import Stage 2e checkpoint crash-resume：`import-stage2e-checkpoint-crash-20260608163051`，日志 `load-tests/target/import-stage2e-checkpoint-crash-20260608163051/import-stage2e-checkpoint-crash.log`，断言 `requestId=sim-stage2e-ckpt-7452142|instance=30293|partition=21817|pipeline=12792|markerBeforeKill=350|processedFinal=20000|rows=20000|status=SUCCESS`
- Process Stage 4c cancel 复验：`process-stage4c-20260608163544`，日志 `load-tests/target/process-stage4c-20260608163544/process-stage4c.log`，断言 `4|4|16|296.00|16|16|FAILED|FAILED|FAILED`，取消任务 `error_code=WORKER_EXECUTION_CANCELLED`
- Atomic Stage 5c cancel 复验：`atomic-stage5c-20260608163608`，日志 `load-tests/target/atomic-stage5c-20260608163608/atomic-stage5c.log`，断言 `SUCCESS|FAILED:TIMEOUT:TIMEOUT|FAILED:FAILED:true|cancelHttp=200`
- Trigger Stage 6d auto-link 复验：`trigger-stage6d-20260608163850`，日志 `load-tests/target/trigger-stage6d-20260608163850/trigger-stage6d.log`，断言 `misfire=2|replay=LAUNCHED|30302|storm_terminal=10/10|pending_outbox=0|non_terminal=0`，其中 `pending=185 request=29315` 为自动关联 catch-up request。

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
| IMPORT UPSERT 重跑幂等 | 通过 | `S2BUPS000001` 两次导入后仅 1 行，`customer_name=Stage2b Updated` |
| IMPORT LOAD 目标配置错误 | 通过 | `TA_IMPORT_CUSTOMER_XML_LOAD_BAD` `FAILED`，`error_code=IMPORT_LOAD_FAILED` |
| IMPORT 分区 COPY 多分片保护 | 通过 | `TA_IMPORT_CUSTOMER_XML_PARTITION_COPY` `partitionCount=2` 直接 `IMPORT_LOAD_CONFIG_INVALID` |
| IMPORT APPEND/no-conflict | 通过 | Stage 2c 同内容同 batchNo 跑两次产生 2 行 |
| IMPORT `PARTITION_REPLACE_COPY` 正向 | 通过 | Stage 2c 先清 stale 再 COPY，断言新 2 行、stale 0 行 |
| IMPORT bad-record skip 阈值内 | 通过 | Stage 2d `maxSkipCount=1`，1 坏 1 好最终 `SUCCESS`，有效行 LOAD=1，坏记录=1 |
| IMPORT bad-record skip 超阈值 | 通过 | Stage 2d 2 坏 1 好最终 `FAILED/IMPORT_SKIP_THRESHOLD_EXCEEDED`，有效行未 LOAD，坏记录=2 |
| EXPORT JSON 成功态 | 通过 | `TA_EXPORT_REPORT_JSON_TPL` `SUCCESS`，file_record `JSON/GENERATED` |
| EXPORT FIXED_WIDTH 成功态 | 通过 | `TA_EXPORT_REPORT_FIXED_TPL` `SUCCESS`，file_record `FIXED_WIDTH/GENERATED` |
| EXPORT EXCEL 成功态 | 通过 | `TA_EXPORT_REPORT_EXCEL_TPL` `SUCCESS`，file_record `EXCEL/GENERATED` |
| EXPORT bad SQL 失败态 | 通过 | `TA_EXPORT_REPORT_BAD_SQL_TPL` `FAILED`，`error_code=EXPORT_GENERATE_FAILED` |
| EXPORT keyset-range 4 分片 | 通过 | `TA_EXPORT_REPORT_STATIC` 4 个 task 均 `SUCCESS`，4 个 `_pNof4.json`，recordCount 汇总 40 |
| EXPORT keyset-range 8 分片 | 通过 | Stage 3c 8 个 task 均 `SUCCESS`，8 个文件、80 行 |
| EXPORT requestId replay 幂等 | 通过 | Stage 3c 同 requestId 只创建 1 个 `trigger_request` / `job_instance` |
| EXPORT 多租户小混压 | 通过 | Stage 3c ta/tb/tc 均生成 file_record |
| PROCESS JSONB staging 成功态 | 通过 | `TA_PROCESS_STAGE4_JSONB` `SUCCESS`，target 写入 2 个账户汇总 |
| PROCESS DIRECT fast path 成功态 | 通过 | `TA_PROCESS_STAGE4_DIRECT` `SUCCESS`，target 写入 2 个账户汇总，staging 不落行 |
| PROCESS validation 失败态 | 通过 | `TA_PROCESS_STAGE4_VALIDATE_FAIL` `FAILED`，`error_code=PROCESS_VALIDATION_FAILED` |
| PROCESS empty result SUCCESS | 通过 | `TA_PROCESS_STAGE4_EMPTY_SUCCESS` `SUCCESS`，0 行结果按策略成功 |
| PROCESS 稳定 batchKey 幂等重跑 | 通过 | 同 batchKey 两次 `TA_PROCESS_STAGE4_JSONB`，target 保持 2 个业务键并更新到第二轮金额 |
| PROCESS 残留 staging 恢复 | 通过 | 首跑前预置同 batchKey stale staging，COMPUTE pre-clean 后最终 staging_leftover=0 |
| PROCESS 分片 SQL 参数 | 通过 | Stage 4c 4 个 partition/task 均 `SUCCESS`，target 16 行，事件/水位对账 16 |
| PROCESS 运行中取消 | 通过 | Stage 4c 复验 RUNNING job cancel HTTP 200，worker 收到 renew `cancelRequested` 后中断，任务终态 `FAILED/WORKER_EXECUTION_CANCELLED` |
| DISPATCH HTTP 500 失败/补偿 | 通过 | `TB_DISPATCH_SETTLE` 按 `EXPONENTIAL/5` 进入 `RETRYING`；`TB_DISPATCH_STAGE5_FAIL_ONCE` 按 `NONE/0` 进入 `FAILED` |
| DISPATCH LOCAL sidecar/envelope | 通过 | Stage 5c `tb_stage5c_local` `ACKED/SUCCESS`，本地产物存在 |
| DISPATCH NAS sidecar/envelope | 通过 | Stage 5c `tb_stage5c_nas` `ACKED/SUCCESS`，local-profile NAS stub + sidecar |
| DISPATCH SFTP sidecar manifest | 通过 | Stage 5c `tb_stage5c_sftp` `ACKED/SUCCESS`，SFTP `.chk` manifest 存在 |
| ATOMIC SQL | 通过 | `atomic_sql_demo` `SUCCESS` |
| ATOMIC shell | 通过 | `atomic_shell_demo` `SUCCESS` |
| ATOMIC stored-proc | 通过 | 补 `batch.refresh_metrics()` procedure 后 `atomic_stored_proc_demo` `SUCCESS` |
| ATOMIC HTTP 真请求 | 安全拦截 | 覆盖到 `http://localhost:11080/tb/ingest` 被 SSRF 闸拒绝：`localhost -> 127.0.0.1` |
| ATOMIC HTTP 非 loopback 真成功 | 通过 | Stage 5c `https://example.com` 真请求 `SUCCESS` |
| ATOMIC SQL timeout 分类 | 通过 | Stage 5c `pg_sleep(5)` + `statementTimeoutSeconds=1`，`FAILED/TIMEOUT`，`failure_class=TIMEOUT` |
| ATOMIC shell task cancel | 通过 | Stage 5c 复验 task cancel API HTTP 200，`cancel_requested=true`，shell 任务终态 `FAILED/WORKER_EXECUTION_CANCELLED` |
| TRIGGER requestId 去重 | 通过 | 同一 requestId 连续 launch 两次，trigger_request=1，job_instance=1 |
| TRIGGER 小规模 storm | 通过 | `STORM_COUNT=30` + 1 个 dedup 实例全部 `SUCCESS` |
| TRIGGER scheduler 定时触发 | 通过 | Stage 6c wheel scheduled fire count=1 |
| TRIGGER misfire pending | 通过 | Stage 6c `MANUAL_APPROVAL` misfire pending count=1；Stage 6d 复验 pending 自动关联 `catch_up_request_id` |
| TRIGGER replay approve | 通过 | Stage 6d 按 `pendingId` approve 自动创建的 catch-up request 后 `LAUNCHED` |
| TRIGGER storm 60 | 通过 | Stage 6c 60/60 `SUCCESS` |
| 统一自动化入口 smoke | 通过 | 默认 stage 列表已扩到 `2,2b,2c,3,3b,3c,4,4b,4c,5,5c,6,6c`；Stage 2d 因需 skip profile 显式运行 |

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
| Atomic Stage 5b shell | 19262 | SUCCESS | `atomic_shell_demo` 终态 `SUCCESS/SUCCESS` |
| Atomic Stage 5b SQL | 19263 | SUCCESS | `atomic_sql_demo` 终态 `SUCCESS/SUCCESS` |
| Atomic Stage 5b stored-proc | 19264 | SUCCESS | `atomic_stored_proc_demo` 终态 `SUCCESS/SUCCESS` |

Stage 2b import 复核：

| item | result |
|---|---|
| UPSERT rerun | `S2BUPS000001` 只有 1 行，字段更新到第二次导入 |
| LOAD bad target | job/task `FAILED`，`IMPORT_LOAD_FAILED` |
| partition copy guard | `expected_partition_count=2`，两个 task 均 `IMPORT_LOAD_CONFIG_INVALID` |

Stage 3b export 复核：

| item | result |
|---|---|
| task | partition 1/2/3/4 均 `SUCCESS` |
| file_record | 4 个 `GENERATED` 文件，文件名含 `_p1of4` 到 `_p4of4` |
| recordCount | 每片 10 行，总计 40 行 |

Stage 4b process 复核：

| item | result |
|---|---|
| rerun | 同 batchKey 首跑 + 二次重跑均 `SUCCESS` |
| target | `A001=300.00/1/201`，`A002=100.00/2/203` |
| staging | 同 batchKey 残留行最终 0 |

Stage 5b dispatch 复核：

| item | result |
|---|---|
| job_instance | `FAILED` |
| partition/task | `FAILED/FAILED` |
| dispatch_record | `COMPENSATED`，`dispatch_error=DISPATCH_COMPENSATED` |

Stage 6 trigger 去重复核：

| request_id | launch_count | trigger_requests | job_instances | result |
|---|---:|---:|---:|---|
| sim-trigger-dedup-1780890401379322000 | 2 | 1 | 1 | PASS |
| sim-stage6b-dedup-* | 2 | 1 | 1 | PASS |
| sim-stage6b-storm-* | 30 | 30 | 30 | PASS，全 `SUCCESS` |

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
| Process 分片 SQL 拿不到 runtime partition 参数 | `SqlTransformComputePlugin` 补 `partitionNo` / `partitionCount` / `partitionKey` 命名参数，Stage 4c 4 分片复验通过 |
| Atomic timeout 失败被统一抹成 `TASK_FAILED` | `DefaultStepExecutionAdapter` 改为透传 `TaskResult.output().error_code`，Stage 5c timeout 复验为 `TIMEOUT` |
| Atomic/worker 报告缺失败分类 | `TaskExecutionReport` 增加 `failureClass`，`DefaultTaskExecutionWrapper` 将 worker timeout 映射为 `TIMEOUT` |
| Import skip 超阈值 metadata 未置位 | `ImportRecordGovernanceService.markThresholdExceeded` + Parse/Validate 阈值失败路径补标记，Stage 2d 复验 `skipThresholdExceeded=true` |
| Dispatch Stage 5c fixture impl_code 大小写不匹配 | fixture 改为 `DISPATCH_*`，Stage 5c LOCAL/NAS/SFTP 复验通过 |
| Import checkpoint crash-resume 卡 step RUNNING | `PartitionReclaimUnit` 回收时同步 reset `job_step_instance`，并要求 reset 行数 > 0；Stage 2e 复验回收后可重新 claim |
| Import RECOVER 重放 file_status 回退冲突 | Import stage 状态更新在 `RECOVER` 模式下对已前进状态做幂等跳过；Stage 2e 复验 `processedFinal=20000` |
| `PipelineProgressEntity` MyBatis record 映射失败 | `processedCount/completed` 改为 boxed 类型匹配 MyBatis 反射构造；Stage 2e LOAD checkpoint 续跑复验通过 |
| Process/Atomic cancel 只置标记不中断 | worker-core batch renew 返回 `cancelRequested`，active registry 触发 `Future.cancel(true)`；Stage 4c/5c 复验取消终态为 `WORKER_EXECUTION_CANCELLED` |
| Trigger pending 未自动关联 catch-up request | trigger wheel 写 pending 时同步创建 `trigger_request` 并回填 `catch_up_request_id`；Stage 6d 按 `pendingId` approve 复验通过 |

## 暴露问题

| 编号 | 问题 | 影响 | 当前证据 | 下一步 |
|---|---|---|---|---|
| S4-PROCESS-CANCEL-01 | 已关闭：RUNNING job cancel 可请求 worker 中断 | Process 长任务取消不再默默成功 | Stage 4c 复验 cancel HTTP 200，task `FAILED/WORKER_EXECUTION_CANCELLED` | 后续只需补 PG 断链 / kill worker failure profile |
| S5-ATOMIC-CANCEL-01 | 已关闭：Atomic shell cancel 可中断本地 shell task | 用户不再看到 cancel 后 shell SUCCESS | Stage 5c 复验 cancel HTTP 200，task `FAILED/WORKER_EXECUTION_CANCELLED` | 后续补超时/取消/重试组合矩阵 |
| S5-ATOMIC-HTTP-01 | Atomic HTTP localhost MockServer 被 SSRF 闸拒绝 | 本地 loopback 不能证明 HTTP 真发送成功 | `host resolves to blocked address: localhost -> 127.0.0.1` | 已用 Stage 5c `https://example.com` 覆盖非 loopback 真成功；localhost 安全闸保持 |
| S6-TRIGGER-MISFIRE-01 | 已关闭：pending 自动创建并关联 catch-up request | 控制台可从 pending 直接 approve replay | Stage 6d `pending=185 request=29315 status=LAUNCHED|30302` | 高频 cron / 1w storm 仍归容量 profile |
| S2-IMPORT-CHECKPOINT-01 | 已关闭：真实 checkpoint crash-resume profile 通过 | 已证明 chunk 后 kill worker 可同 instance 续跑 | Stage 2e `markerBeforeKill=350`，最终 `processedFinal=20000 rows=20000 status=SUCCESS` | 后续补 PG/Kafka 断链等更广故障注入 |
| S7-AUTO-01 | 已关闭：Stage 5/6 已纳入自动化入口 | 默认 smoke 已包含第三批稳定阶段 | `load-tests/scripts/run-worker-business-scenario-matrix.sh` 默认 `2,2b,2c,3,3b,3c,4,4b,4c,5,5c,6,6c`，Stage 2d 需显式 skip profile | 后续只需按环境扩展故障注入 profile |

## 本轮未覆盖

| 项 | 状态 | 原因 / 下一步 |
|---|---|---|
| `TA_DISPATCH_ORDER` HTTP 下游 | 未覆盖 | 当前 fixture 没有 ta 独立 HTTP channel；脚本显式跳过，避免占位 channel 造成假失败 |
| XML / FIXED_WIDTH import | 已覆盖 Stage 2/2b/2c/2d/2e | 已覆盖成功态、解析失败、字段校验失败、APPEND、UPSERT、LOAD failure、分区 COPY 正/负、bad-record skip 阈值、checkpoint 真实崩溃续跑 |
| JSON / FIXED_WIDTH / EXCEL export | 已覆盖 Stage 3/3b/3c | 已覆盖小规模系统链路成功态、bad SQL、keyset-range 4/8 分片、replay 幂等、多租户小混压；真实 S3、multipart abort/retry、导出失败恢复仍待故障注入 |
| Excel multipart 配置包上传 | 已覆盖配置包链路 | 属于 Console `/tenant-package/excel/upload`，不属于 worker-import trigger；本轮通过 `03-import-tenants.sh` 覆盖 |
| PROCESS worker 业务场景 | 已覆盖 Stage 4/4b/4c | 已覆盖 JSONB staging、DIRECT、validation failure、empty success、幂等重跑、残留 staging 恢复、分片 process、RUNNING cancel 中断 |
| DISPATCH failure/channel terminal | 已覆盖 Stage 5/5b/5c | HTTP 500 + retry/no-retry/补偿、LOCAL/NAS/SFTP sidecar 已覆盖；EMAIL/OSS 真实远端、失败重试风暴待外部 profile |
| ATOMIC HTTP 真成功 | 已覆盖 Stage 5c | 非 loopback `https://example.com` 成功；localhost MockServer 仍按 SSRF 安全闸拒绝 |
| TRIGGER cron/misfire/replay/storm | 已覆盖 Stage 6/6b/6c/6d | API 去重、30/60 storm、scheduled fire、misfire pending、pending->catch-up 自动关联、replay approve 已覆盖；高频 cron/1w storm 待容量 profile |
| external OSS/NAS/SFTP/EMAIL 真实远端 | 未覆盖 | 本轮使用本地 MinIO/SFTP/MockServer；真实远端放外部依赖矩阵 |
| DAST application-level | 未覆盖 | 不属于本轮 worker 业务场景 sim |

## 运行约束

- 本轮未跑 Maven 单测/IT，按要求只做系统 API 触发和数据库/对象存储/MockServer 对账。
- SQL/config 分离已执行边界检查：`bash scripts/ci/check-sql-config-boundaries.sh` 通过。新增造数/配置 fixture 放在 `docs/test-data/*.sql` 或 `docs/test-data/*.json`；shell 脚本只负责加载 fixture、API 编排和动态断言查询。
- 本地 dispatch 到 `localhost:11080` 需要 `BATCH_SECURITY_BYPASS_MODE=true`，本轮仅对 `worker-dispatch` 本地进程开启，用于本地 sim。
- `06-verify.sh` 的“近 10 分钟全局状态”可能混入探索失败记录；结论以本报告的精确时间过滤 SQL 为准。

## 下一阶段

1. Import：checkpoint 真实崩溃续跑已通过 Stage 2e；后续只补 PG/Kafka 断链等更广故障注入。
2. Export：真实 S3 / multipart abort-retry / 导出失败恢复仍需对象存储故障注入或外部 profile。
3. Process：RUNNING cancel 已通过；timeout/PG 断链/kill worker 恢复仍需 failure profile。
4. Dispatch：EMAIL/OSS 真实远端、失败重试风暴和幂等投递矩阵放外部依赖 profile。
5. Atomic：shell cancel 已通过；取消/超时/重试组合矩阵仍待扩展。
6. Trigger：misfire pending 到 catch-up request 自动关联已通过；高频 cron/1w storm 放容量 profile。
