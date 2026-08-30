# 前台运维作业联合验证报告（2026-08-29）

## 验证目标

本轮验证从前台运维入口和既有 sim 场景两条路径同时覆盖系统主链路：

- 前台：通过作业定义页面手动触发运维作业，确认 Console API、Trigger、Orchestrator、Worker、DB 状态闭环正常。
- 场景矩阵：复用 `load-tests/scripts/run-worker-business-scenario-matrix.sh`，覆盖 import / export / process / dispatch / atomic / trigger 的主要业务分支。
- UI：巡检运维概览和作业实例详情页面，确认路由、数据渲染和浏览器控制台无前端错误。

## 验证环境

- 基础环境：Docker infra（PostgreSQL 17、Kafka、Valkey、MinIO、SFTP、MockServer）。
- 应用环境：本地 JVM 运行 Console / Trigger / Orchestrator / 五类 Worker。
- 前端环境：`../batch-console` Vite dev server，地址 `http://127.0.0.1:5173`。
- 端口：Console `18080`、Trigger `18081`、Orchestrator `18082`、Import `18083`、Export `18084`、Dispatch `18085`、Process `18086`、Atomic `18087`。

## 本轮发现并修复

1. `scripts/sim/env-common.sh`
   - 问题：`sim_container_stack_active` 只判断 `batch-console-api` 容器是否存在，已退出容器也会被误认为应用运行在 Compose 网络内。
   - 影响：本地 JVM 应用会错误使用容器内 SFTP 地址 `sftp:22`，导致 Stage 5c SFTP 分发失败。
   - 修复：新增 `sim_container_running`，仅运行中容器才判定为容器栈。

2. `scripts/sim/20-dispatch-stage5c.sh`
   - 问题：SFTP endpoint 判定逻辑在脚本内重复实现，容易和公共环境判定漂移。
   - 修复：统一改为 `sim_sftp_endpoint`。

3. `docs/test-data/sim-stage5c-dispatch-channels-platform.sql`
   - 问题：Stage 5c 每轮会重写通道 endpoint，但旧的 `file_channel_health` 失败退避快照会污染下一轮。
   - 修复：重写 Stage 5c 通道 fixture 后清理同名通道健康快照。

4. `scripts/sim/22-trigger-stage6c.sh`
   - 问题：停止 Trigger 前也只用 `docker inspect batch-trigger` 判断容器存在，已退出容器会误导脚本走 Compose stop 分支，导致本地 JVM Trigger 未停止。
   - 修复：改为 `sim_container_running batch-trigger`。

## 前台作业验证

通过前台作业定义页手动触发 `atomic_sql_demo`：

| 项 | 结果 |
|---|---|
| `job_instance.id` | `233965` |
| `tenant_id` | `ta` |
| `job_code` | `atomic_sql_demo` |
| `instance_status` | `SUCCESS` |
| `instance_no` | `inst-20260829T035548Z-c3d16c64` |
| task 汇总 | `SUCCESS=1` |

该路径验证了前台操作、Console API、Trigger、Orchestrator、Atomic Worker、状态查询的闭环。

## 场景矩阵验证

执行命令：

```bash
RUN_ID=front-ops-joint-full3-20260829121917 PROFILE=smoke \
  bash load-tests/scripts/run-worker-business-scenario-matrix.sh
```

结果报告：

```text
load-tests/target/front-ops-joint-full3-20260829121917/worker-business-scenario-matrix-summary.md
```

覆盖和结果：

| Stage | 场景 | 结果 |
|---|---|---|
| 2 | import XML / FIXED_WIDTH 成功与解析失败、校验失败 | PASS |
| 2b | import UPSERT / LOAD failure / partition guard | PASS |
| 2c | import APPEND / UPSERT / PARTITION_REPLACE_COPY | PASS |
| 3 | export JSON / FIXED_WIDTH / EXCEL / bad SQL | PASS |
| 3b | export keyset 4 分片 | PASS |
| 3c | export 8 分片 / dedup / 多租户 | PASS |
| 4 | process JSONB / DIRECT / validation / empty result | PASS |
| 4b | process 幂等重跑 / 失败恢复 | PASS |
| 4c | process 分片 / cancel 语义 | PASS |
| 5 | dispatch failure compensation + atomic shell/sql/stored-proc | PASS |
| 5c | dispatch LOCAL/NAS/SFTP + atomic HTTP/timeout/cancel | PASS |
| 6 | trigger dedup / storm | PASS |
| 6c | trigger schedule / misfire / replay / storm | PASS |

实例范围快照（`job_instance.id` 234118-234196）：

| tenant | status | count |
|---|---:|---:|
| default-tenant | FAILED | 2 |
| default-tenant | SUCCESS | 4 |
| ta | FAILED | 8 |
| ta | SUCCESS | 59 |
| tb | FAILED | 1 |
| tb | SUCCESS | 4 |
| tc | SUCCESS | 1 |

任务状态快照：

| task_status | count |
|---|---:|
| SUCCESS | 81 |
| FAILED | 11 |
| READY | 1 |

说明：本轮矩阵包含预期负向用例，`FAILED` 和 1 条 `READY` 均归属预期失败路径。例如 `job_instance.id=234122` 是 import validation blocked 用例，实例终态为 `FAILED`，其未派发 task 保持 `READY`，不是卡 `RUNNING`。

业务库当前数据快照：

| 表 | 行数 |
|---|---:|
| `biz.customer_account` | 102 |
| `biz.transaction` | 30 |
| `biz.risk_score` | 30 |

## 前端页面巡检

| 页面 | 结果 |
|---|---|
| `http://127.0.0.1:5173/ops/summary` | 可打开，SPA 已挂载，浏览器控制台无 error |
| `http://127.0.0.1:5173/monitor/job-instances/234196` | 可打开，实例状态 `SUCCESS`，详情字段渲染正常，浏览器控制台无 error |

作业实例详情页当前采用卡片/详情结构，不再复现之前“宽表列与数据错位”的表现。

## 限制和后续

- 本轮是本地 JVM 应用 + Docker infra 的联合验证，不等同于完整容器化应用部署验收。
- 本轮 `PROFILE=smoke`，覆盖业务分支和联通性，不替代 1000w 级容量 benchmark。
- Stage 6c 包含 Trigger 停启故障注入，验证结束后本地 Trigger / Import 可能需要按本地运行方式重新拉起；这不影响矩阵 PASS 结论。
- 本地数据库曾出现 Flyway 提示：schema 版本 `197` 高于当前代码迁移最新版本 `195`。这是当前本地库状态差异，未阻断本轮验证，但和干净 CI 库对比时需要注意。

## 2026-08-30 收口复验

### 前端真实后端 E2E

执行命令：

```bash
cd ../batch-console
E2E_REAL_BE=1 npx playwright test \
  e2e/config-management-ops.spec.ts \
  e2e/config-sync.spec.ts \
  e2e/excel-import.spec.ts \
  e2e/import-business-ui.spec.ts \
  --workers=1
```

结果：

```text
28 passed (1.6m)
```

复核结论：

- `config-management-ops.spec.ts` 已无 `skip`，配置导出、导入前端校验、导出再导入幂等往返、同步日志新增均已从前端真实后端链路覆盖。
- `import-business-ui.spec.ts` 覆盖 ta/tb/tc 三个典型租户从前台触发 IMPORT，并校验业务表行数。
- 该结果证明前台操作能打到真实后端并得到正确 UI 状态；生产账期语义仍需 sim、数据对账和故障注入共同证明。

### 导入主链路复验

执行命令：

```bash
bash scripts/sim/00-reset-runtime.sh
bash scripts/sim/01-init-biz.sh
bash scripts/sim/02-start-sim.sh
bash scripts/sim/03-import-tenants.sh
ROWS=5 bash scripts/sim/28-import-mainline.sh
```

结果：

| 租户 | 作业 | 终态 | 业务行数 |
|---|---|---:|---:|
| ta | `TA_IMPORT_CUSTOMER` | SUCCESS | 5 |
| tb | `TB_IMPORT_TRANSACTION` | SUCCESS | 5 |
| tc | `TC_IMPORT_RISK_SCORE` | SUCCESS | 5 |

`batch.job_task` 未出现 `FAILED` / `COMPENSATED` / `REJECTED`。

### 普通 sim 主链路复验

本轮修复点：

- `scripts/sim/04-seed-source-data.sh`：tb/tc 种子 CSV 字段与当前导入模板对齐；SFTP 列表输出去掉 `head`，避免 `pipefail` 下 SIGPIPE 误失败。
- `scripts/sim/03-import-tenants.sh`、`scripts/sim/05-load.sh`：导入配置和普通 sim 启动前静默自动定时触发，避免后台 schedule 污染普通 sim 断言。
- `scripts/sim/05-load.sh`：workflow 触发使用按 job 区分的 `batchNo`，避免多个 workflow 共用对象路径导致 export/source_ref 冲突。

执行命令：

```bash
bash scripts/sim/00-reset-runtime.sh
bash scripts/sim/03-import-tenants.sh
bash scripts/sim/04-seed-source-data.sh
CLEAN_SIM_OUTPUTS=true ROUNDS=5 bash scripts/sim/05-load.sh
sleep 120
SIM_VERIFY_LOOKBACK_MINUTES=30 bash scripts/sim/06-verify.sh
```

结果：

| 项 | 结果 |
|---|---:|
| 触发作业 | 14/14 |
| `batch.job_instance` | `SUCCESS=27` |
| 非终态 / 失败 | 0 |
| outbox backlog | 0 |
| ta `biz.customer_account` | 6 |
| tb `biz.transaction` | 6 |
| tc `biz.risk_score` | 6 |
| MockServer `/tb/callback` | 3 |
| MockServer `/tb/ingest` | 3 |
| MockServer `/tc/ingest` | 10 |

### sim-4day 小规模多租复验

执行命令：

```bash
bash scripts/sim-4day/00-clean.sh
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 < scripts/sim-4day/10-clone-tenants.sql
WAIT=90 ROWS_BIG=1000 bash scripts/sim-4day/41-run-4days.sh 2026-06-06 5
bash scripts/sim-4day/50-watch.sh
```

结果：

| 项 | 结果 |
|---|---:|
| 租户数 | 10 |
| 账期天数 | 4 |
| `pipeline_instance` IMPORT SUCCESS | 80 |
| `pipeline_instance` EXPORT SUCCESS | 80 |
| `pipeline_instance` DISPATCH SUCCESS | 42 |
| `biz.customer_account` | 204 |
| `biz.transaction` | 153 |
| `biz.risk_score` | 153 |
| MinIO outbound 文件 | 24 |
| outbox pending | 0 |
| dead letter | 0 |

日志目录：

```text
logs/runs/sim-4day/sim-4day-4days-20260606-20260830-205917-797cde07e
```

### Worker 业务场景矩阵复验

本轮修复点：

- `docs/test-data/sim-stage6c-trigger-fixtures.sql`：补齐 `trigger_runtime_state` 与 `trigger_misfire_pending` fixture，使 catch-up approve 能绑定真实 pending 记录。
- `scripts/sim/22-trigger-stage6c.sh`：approve 请求显式传 `pendingId`；misfire 断言改为“pending 或运行时成功”两种真实行为均可证明链路有效，避免依赖直接改 Quartz `next_fire_time` 的不稳定时序。

执行命令：

```bash
RUN_ID=front-ops-joint-closure-202608302137 PROFILE=smoke \
  bash load-tests/scripts/run-worker-business-scenario-matrix.sh
```

结果报告：

```text
load-tests/target/front-ops-joint-closure-202608302137/worker-business-scenario-matrix-summary.md
```

覆盖和结果：

| Stage | 场景 | 结果 |
|---|---|---|
| 2 | import XML / FIXED_WIDTH 成功与解析失败、校验失败 | PASS |
| 2b | import UPSERT / LOAD failure / partition guard | PASS |
| 2c | import APPEND / UPSERT / PARTITION_REPLACE_COPY | PASS |
| 3 | export JSON / FIXED_WIDTH / EXCEL / bad SQL | PASS |
| 3b | export keyset 4 分片 | PASS |
| 3c | export 8 分片 / dedup / 多租户 | PASS |
| 4 | process JSONB / DIRECT / validation / empty result | PASS |
| 4b | process 幂等重跑 / 失败恢复 | PASS |
| 4c | process 分片 / cancel 语义 | PASS |
| 5 | dispatch failure compensation + atomic shell/sql/stored-proc | PASS |
| 5c | dispatch LOCAL/NAS/SFTP + atomic HTTP/timeout/cancel | PASS |
| 6 | trigger dedup / storm | PASS |
| 6c | trigger schedule / misfire / replay / storm | PASS |

Stage 6c 关键断言：

```text
scheduled=1|misfirePending=1|misfireSuccess=0|replay=LAUNCHED|458|storm=60/60
```

### 边界说明

- 本轮验证环境为本地 JVM 应用 + Docker 基础环境，适合证明前台操作、真实后端、worker 链路和 sim 业务数据闭环。
- 本轮未替代生产级 HA/DR 演练，也未覆盖 1000w 级容量基线。
- 故障注入已覆盖部分本地场景：导入解析/校验/LOAD 失败、导出 bad SQL、process validation/cancel、dispatch 失败补偿、atomic timeout/cancel、trigger misfire/replay/storm。
- 生产账期最终签字仍需要在同构 staging 上补齐：长任务 worker、账期补跑、上游晚到、文件内容对账、外部 MinIO/Kafka/PG 故障、多用户并发审批/运维冲突。
