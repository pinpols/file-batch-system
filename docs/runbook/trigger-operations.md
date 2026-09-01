# Trigger 运维操作手册

## 适用范围

本文覆盖 Trigger 的状态查询、注册、暂停、恢复、排空和 misfire 验证。生产操作统一经
Trigger 管理 API 完成，禁止直接改 Quartz 表或 `batch.job_definition`。

## 前置条件

```bash
export BATCH_TRIGGER_URL="https://trigger.example.internal"
export BATCH_INTERNAL_SECRET="$SECRET_FROM_SECRET_MANAGER"
```

确认密钥不是默认占位符后，先执行 dry-run：

```bash
bash scripts/ops/manage-trigger.sh status
bash scripts/ops/manage-trigger.sh drain-status
```

## 状态变更

真实执行必须显式关闭 dry-run，并保留命令输出和操作审计：

```bash
BATCH_TRIGGER_MANAGEMENT_DRY_RUN=false \
  bash scripts/ops/manage-trigger.sh pause-job TENANT_ID JOB_CODE

BATCH_TRIGGER_MANAGEMENT_DRY_RUN=false \
  bash scripts/ops/manage-trigger.sh resume-job TENANT_ID JOB_CODE
```

优先使用单 Job 或单租户操作。`pause-all`、`resume-all` 和 drain 属于平台级操作，执行前要
确认维护窗口、当前运行实例和回滚负责人。

## Misfire 验证纪律

验证必须区分真实 Quartz callback 和业务 fallback：

1. 在 staging 或隔离环境创建带明确 misfire 策略的临时 cron job，并记录 job、trigger、tenant 和
   fire time。
2. 让 Quartz 在维护窗口内真实发生 misfire，观察 `triggerMisfired` 日志、`batch.trigger_misfire_pending`
   和 `batch.trigger.quartz.misfire.total` 指标。
3. 对 `MANUAL_APPROVAL` 只通过审批/replay API 继续，不直接更新 pending 行。
4. 验证 requestId/dedup、outbox、job instance 最终状态以及无重复实例。
5. 若 Quartz callback 未出现，可运行 `scripts/sim/24-trigger-stage6d.sh` 的 fallback 分支验证业务
   catch-up 闭环，但报告必须标注 `misfire_source=seeded-fallback`，不能宣称真实 callback 已通过。

## 回滚与故障处理

- 暂停错误：使用同租户 `resume-tenant` 或同 Job `resume-job`，然后查询 scheduler status。
- 排空超时：先运行 `scripts/ops/inspect-workers.sh`，再按 worker runbook 处理；不要通过 Trigger
  脚本强行清理 Quartz 锁。
- outbox 或 replay 异常：使用对应 `scripts/ops/heal-*.sh`，保留 dry-run 输出和幂等键。
- API 返回失败：保留响应、traceId 和 requestId，先检查 Trigger 日志及数据库状态，再决定重试。

## 验收记录

每次专项至少记录：

| 字段 | 说明 |
|---|---|
| 环境 | staging / 隔离环境标识 |
| misfire_source | `quartz` 或 `seeded-fallback` |
| trigger/job | 临时对象标识 |
| pending / replay | pending 数量、审批和补跑结果 |
| dedup | requestId、幂等键及实例数量 |
| 最终状态 | job instance、task、outbox 是否终态 |
| 证据 | 日志、指标和查询结果路径 |
