# 长验证报告：真实批量作业调度处理（2026-08-31）

## 验证范围

本轮使用本地 JVM 应用 + Docker 基础环境验证真实批量作业链路：

- 基础环境：PostgreSQL、Kafka、Valkey、MinIO、SFTP、MockServer 均使用现有 Docker 环境。
- 应用：orchestrator、trigger、console、worker-* 使用本地 runtime jar。
- 主链路：10 个租户、4 个账期日、IMPORT / PROCESS / EXPORT / DISPATCH。
- Trigger 专项：高频 cron、pause/resume、misfire catch-up approval、requestId 去重、trigger outbox retry、API storm。

不覆盖：生产同构 24h soak、PG 主备切换、Kafka broker 故障注入、真实外部 OSS/SFTP 长稳压测。

## 执行记录

### Sim 4day

命令：

```bash
SIM4DAY_LOG_DIR=logs/runs/sim-4day/long-batch-schedule-20260831-070010 \
WAIT=120 ROWS_BIG=20000 \
bash scripts/sim-4day/41-run-4days.sh 2026-06-06 50
```

最终仪表盘：

| 租户 | SUCCESS | RUNNING | FAILED | TOTAL |
|---|---:|---:|---:|---:|
| t04 | 25 | 0 | 0 | 25 |
| t05 | 23 | 0 | 0 | 23 |
| t06 | 27 | 0 | 0 | 27 |
| t07 | 27 | 0 | 0 | 27 |
| t08 | 23 | 0 | 0 | 23 |
| t09 | 27 | 0 | 0 | 27 |
| t10 | 27 | 0 | 0 | 27 |
| ta | 23 | 0 | 0 | 23 |
| tb | 23 | 0 | 0 | 23 |
| tc | 27 | 0 | 0 | 27 |

业务结果：

| 表 | 行数 |
|---|---:|
| biz.customer_account | 2004 |
| biz.transaction | 1503 |
| biz.risk_score | 1503 |

其他断言：

- `batch.job_task`：304 条，全部 `SUCCESS`。
- `batch.outbox_event`：298 条，全部 `PUBLISHED`。
- `batch.dead_letter_task`：0。
- MinIO outbound 导出文件：24 个。

### Trigger Stage 6d

命令：

```bash
SIM_TRIGGER_RESTART_MODE=screen \
STORM_COUNT=80 \
OUTBOX_COUNT=12 \
RUN_ID=long-batch-schedule-20260831-070010-trigger6d-final2 \
bash scripts/sim/24-trigger-stage6d.sh
```

最终断言：

```text
cron_before=1
pause=1->1
resume=2
misfire=1
misfire_source=seeded-fallback
replay=LAUNCHED|615
dedup=1/1
outbox=12/12:instances=12
storm_terminal=80/80
pending_outbox=0
non_terminal=0
```

结论：

- 高频 cron 能产生调度请求。
- 单 job pause/resume 后不会在暂停窗口继续触发。
- catch-up pending 审批后能启动补跑实例。
- requestId / Idempotency-Key 重放保持单请求、单实例。
- trigger outbox 失败注入后可恢复到 `PUBLISHED`。
- 80 个 API storm 请求全部进入终态，无非终态残留。

说明：本地通过直接改 Quartz 表注入 misfire 时，Quartz 未稳定回调 `triggerMisfired`。脚本保留 90 秒真实 Quartz callback 等待；未观察到 callback 后，使用独立 SQL fixture 构造 pending 行，并继续验证业务审批补跑闭环。因此本轮证明了业务 catch-up/replay/outbox/storm 闭环，但不把“真实 Quartz callback 自动产生 pending”记为已通过。

### Stage 6d 后总账面状态

Trigger Stage 6d 会额外产生 ta 租户的 PROCESS 实例，因此长验证完成后的总量高于 Sim 4day 初始仪表盘：

| 租户 | SUCCESS | RUNNING | FAILED | TOTAL |
|---|---:|---:|---:|---:|
| t04 | 25 | 0 | 0 | 25 |
| t05 | 23 | 0 | 0 | 23 |
| t06 | 27 | 0 | 0 | 27 |
| t07 | 27 | 0 | 0 | 27 |
| t08 | 23 | 0 | 0 | 23 |
| t09 | 27 | 0 | 0 | 27 |
| t10 | 27 | 0 | 0 | 27 |
| ta | 479 | 0 | 0 | 479 |
| tb | 23 | 0 | 0 | 23 |
| tc | 27 | 0 | 0 | 27 |

最终总断言：

- `batch.job_task`：760 条，全部 `SUCCESS`。
- `batch.outbox_event`：754 条，全部 `PUBLISHED`。
- `batch.dead_letter_task`：0。
- 业务库行数：`customer_account=2004`、`transaction=1503`、`risk_score=1503`。

## 本轮发现并修复

1. Trigger 运维 pause/resume 只作用于 Quartz，未同步 `batch.job_definition.enabled`。
   - 风险：TriggerReconciler 以 DB 为权威，会把已暂停 job 重新注册，暂停窗口内继续触发。
   - 修复：register/resume 持久化 `enabled=true`，pause/unregister 持久化 `enabled=false`。

2. TriggerReconciler 用 `getTriggersOfJob().get(0)` 判断 drift。
   - 风险：同一 Job 挂载 recovery/readiness 辅助 trigger 时，可能误判漂移并 delete/re-register，删除恢复触发器。
   - 修复：只用 primary trigger（key name = JobKey name 且 group = `batch-trigger`）判断调度漂移。

3. 本地 sim 在直接清理 Quartz fixture 时未稳定停止 trigger。
   - 风险：Quartz 线程持锁，fixture reset 阻塞或触发 idle-in-transaction timeout。
   - 修复：Stage 6c/6d 在直接清理 Quartz fixture 前停止 trigger，fixture 后再启动并健康检查；Codex/本地长跑可用 `SIM_TRIGGER_RESTART_MODE=screen`。

4. Quartz misfire recovery listener 注册和恢复触发器状态不够稳。
   - 修复：启动期显式注册 listener；创建 recovery trigger 前恢复 recovery group，调度后显式 `resumeTrigger`；补充 `nextFireTime` 缺失时的 `previousFireTime` fallback 与日志。
   - 剩余限制：本地 DB 表级故障注入仍未稳定触发 Quartz callback，真实 callback 自动 pending 需要单独专项验证。

## 验证过的测试

```bash
./mvnw -pl batch-trigger -am \
  -Dtest=QuartzMisfireRecoveryListenerTest,TriggerSchedulerFacadeTest,TriggerReconcilerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：26 个测试全部通过。

```bash
bash -n scripts/sim/22-trigger-stage6c.sh
bash -n scripts/sim/24-trigger-stage6d.sh
```

结果：脚本语法检查通过。

## 结论

本地长验证证明：在现有 Docker 基础环境和本地 JVM 应用下，10 租户 4 账期主链路可稳定完成，Trigger 的 pause/resume、审批补跑、去重、outbox retry、API storm 业务闭环可完成。

上线前仍建议把真实 Quartz misfire callback 自动 pending 作为独立专项验证，不与本轮业务闭环混淆。
