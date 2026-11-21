# ADR-050 · 同一业务日允许多个活跃 Workflow 运行（可配置上限）

- **Status**: Accepted（2026-11-18）
- **Date**: 2026-11-18
- **Supersedes**: 局部修订 [ADR-047](ADR-047-workflow-scheduling-and-daily-obligations.md) ALIGN-1/A1 中“同一业务日只允许一个非终态根运行”的排他性；触发机会身份（ALIGN-3）不变
- **Related**: [批次日与结算](../core-model.md)、[负载验证记录](../../verifications/load-test-local-2026-11-15.md)、`docs/plans/todo-master.md` BF-20261118-01

## 背景

`batch.workflow_run` 上有一条唯一部分索引：

```sql
create unique index uk_workflow_run_active
    on batch.workflow_run (tenant_id, workflow_definition_id, biz_date, dry_run)
    where run_status in ('CREATED', 'RUNNING');
```

它让“同租户 + 同 Workflow + 同业务日 + 同执行模式”同时只能有一个活跃根运行，第二个 launch 进入
`workflow_launch_wait` 排队。容量因此被钉死为：

```text
吞吐 ≈ 业务日数量 × 1 / 单次运行时长
```

2026-11-18 的 1w 验证（10,000 请求 / 100 业务日）里，峰值 25–28 完成/s，收敛时间主要由日槽数量和
运行时长决定；放开同日并发是唯一能成倍提高同口径容量的手段。

## 决策

1. `batch.workflow_definition` 新增 `max_active_runs_per_biz_date`（integer，`1..64`，默认 `1`）。
   - 默认 1 时行为与决策前完全一致。
   - 定时触发和手工触发共用该值；需要保护“一天一次”的业务 Workflow 保持默认即可。
2. 删除 `uk_workflow_run_active`，改为**活跃槽位 + 唯一约束**：
   - `batch.workflow_run.active_slot`（1..64，默认 1）标记该运行占用的槽位；
   - 上限为 1 时直接尝试槽 1。上限大于 1 时，在同一条 SQL 内选择事务快照中第一个可见空槽并尝试插入。
     插入仍由 `on conflict (…, active_slot) do nothing` 和部分唯一索引裁决。
     没有可见空槽或插入返回零时，从槽 1 开始逐槽复查，覆盖快照建立后才释放的槽位。
     确认全部被占用后进入 `workflow_launch_wait`，由等待恢复流程重新准入；
   - 运行离开 `CREATED/RUNNING` 后不再命中部分唯一索引，槽位自动释放，无需清理。
3. 同一 `trigger_request` 的幂等性不变：仍由 `trigger_request` 行锁（`FOR UPDATE NOWAIT`）、
   `selectByTenantAndTriggerRequestId` 和唯一约束保证，不依赖被删除的索引。
4. 保留非唯一的活跃运行查询索引 `idx_workflow_run_active_lookup`，供计数与排空查询使用。

## 理由

- 上限放在 Workflow 定义上，能区分“一天一次”的定时作业和允许并发的接口型作业；全局开关无法表达这种差异。
- 唯一约束在任何隔离级别下都由数据库裁决。准入事务是 `REPEATABLE READ`，普通计数看不到并发事务已提交的插入，
  用唯一槽位可以避免改成 `SERIALIZABLE` 或额外连接带来的代价。
- 默认 1 意味着存量 Workflow 不需要迁移数据，也不需要前端或契约变更即可保持行为。

## 后果

**正面**

- 容量上限从“业务日数”变成“业务日数 × 上限”；容量画像可按需设置（例如 8）。
- 结算门槛本来就等待该业务日全部活跃根运行结束，上限提高只会让结算稍晚，不会改变正确性。
- 不需要修改触发身份、Outbox 或前端契约；限流、配额、重试路径不变。

**负面 / 约束**

- 同一业务日可能出现多个并行运行，业务作业必须自己保证同日并发的数据隔离（例如按 requestId 分片）。
  默认 1 的 Workflow 不受影响。
- 多槽位有可见空槽时通常一次 INSERT 即可准入。满槽时先执行一次探测，再逐槽复查，仍在同一准入事务内。
  槽号只用于数据库并发裁决。较低槽位在查询快照建立后才释放时，本次可使用快照中已空闲的较高槽位。
- `active_slot` 成为运行快照的一部分；归档表不保留该列，恢复查询不依赖它。
- 上限调高后 `workflow_launch_wait` 的排队深度会下降，运行时长重新成为主要瓶颈（见 BF-20261118-03）。

## 替代方案

| 方案 | 不选原因 |
|---|---|
| 保留唯一索引，只优化运行时长 | 容量仍被业务日数限制，无法应对同日请求洪峰 |
| 全局配置（不分 Workflow） | 无法保护“一天一次”的定时作业，容易误开 |
| 单独槽位表（1..N 行） | 需要额外的槽位生命周期与回收；把槽号直接放在运行行上，终态天然释放 |
| 直接删索引、不做准入计数 | 同一天可无上限并发，结算等待时间和队列深度不可控 |

## 验收

- 单元：上限 1 时第二个 launch 进入容量等待；上限 2 时两个准入、第三个等待；定义缺失时按 1 处理。
- 集成（真实 PostgreSQL）：并发 launch 同一 Workflow + 业务日，活跃运行数不超过上限；相同
  `trigger_request` 重复投递不产生第二个运行。
- 容量画像：把上限设为 8 后复跑 1w，记录收敛时间与峰值完成速率，与默认 1 的基线对比。
