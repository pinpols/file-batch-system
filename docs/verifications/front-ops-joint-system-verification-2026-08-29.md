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
