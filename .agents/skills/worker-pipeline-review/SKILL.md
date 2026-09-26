---
name: worker-pipeline-review
description: 审查五类 Worker、批量日 dry-run、pipeline 进度、claim/report、lease、outbox、dispatch/import/export/process/atomic/trigger 主链路时使用。
---

# Worker 与 Pipeline 链路审查

## 主链路边界

- Orchestrator 是任务状态和调度事实来源；Worker 不应绕过 Orchestrator 直接写 `job_instance`、`workflow_run` 等核心状态表。
- 主链按 DB -> Outbox -> Kafka -> Claim -> Execute -> Report 闭环审查。每一步都要检查租户、幂等键、CAS、防终态复活和事务边界。
- 五类 Worker 的能力差异要显式建模，不用某一类 Worker 的验证结果代表全部 Worker。

## Dry-run 与副作用

- dry-run 必须证明业务库、对象存储、远端渠道、通知和外部 HTTP/Shell/SQL 调用零副作用。
- dry-run 与正式任务混压时，正式任务优先级、幂等键隔离、指标污染和资源配额要有明确策略。
- 批量日级 dry-run 要覆盖 instance/entry dispatch、重启恢复和最终终态，不只验证单条任务。

## 并发与恢复

- 检查 claim/report 是否防双领、双完成、旧 leader 重发和终态复活；必要时使用 `UPDATE ... RETURNING` 或同事务批量操作减少竞态窗口。
- Lease renew circuit、backpressure、outbox backlog、Kafka lag、Hikari/PG 锁等待和 worker CPU 是高压时的关键证据。
- Orchestrator/Worker 重启、Kafka 短暂不可用、下游异常和 DLQ 重放要分别验证无残留 RUNNING、无重复业务副作用。

## 进度与观测

- SSE/pipeline 进度只能展示后端真实步骤事件，不能由前端猜测完成度。
- 指标、日志和 trace 要能串起 tenant、batch day、job instance、task、worker、outbox 和 Kafka offset。
- 报告容量或“全终态”时同时给出 CREATED/READY/RUNNING 残留、HTTP 错误率、重投和业务副作用证据。
