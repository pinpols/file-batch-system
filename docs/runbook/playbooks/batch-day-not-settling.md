# batch_day_instance 卡 SETTLING 不进 SETTLED/FAILED

> 优先级 P2 · 最后核对版本:2026-05 · 配套 chaos IT:无(P2,优先级低)

## TL;DR

**症状**:`batch.batch_day_instance.day_status='SETTLING'` 长时间不变,batch day 不收口,SLA 告警飘红,下游补跑(catch-up)不触发。
**一行修复**:多数情况是 `metrics.activeCount > 0`(还有 in-flight job)→ 等 job 跑完;若 metrics 永远不归零,人工排查 `job_instance` 状态;最后一招走治理接口 reopen。

---

## 怎么发现

- **Prometheus alert**:TODO(待补 `BatchDayStuckSettling`,阈值 stale 行 ≥ 1 持续 5 min)
- **Grafana**:TODO。临时查询:
  ```sql
  select tenant_id, calendar_code, biz_date, day_status, updated_at
    from batch.batch_day_instance
   where day_status = 'SETTLING'
     and updated_at < now() - interval '5 minutes'
   order by updated_at;
  ```
- **日志关键字**:
  - `batch day settle cas conflict; will retry next tick` — 偶发 OK,频繁出现要查
  - `finalizeSettling invoked outside transaction context` — 不应该在生产出现,说明事务上下文丢了
  - 应当看到的:`batch day settled as SETTLED` / `batch day settled as FAILED`,**长时间没有**就是卡了
- **用户反馈**:"今天 batch day 已 cutoff 半小时还没收口" / "catch-up 没起" / "SLA dashboard 飘红"。

---

## 怎么定位

1. **找出卡住的 batch day**
   ```sql
   select id, tenant_id, calendar_code, biz_date, day_status,
          cutoff_at, settled_at, updated_at, version
     from batch.batch_day_instance
    where day_status in ('SETTLING', 'IN_FLIGHT', 'CUTOFF')
      and updated_at < now() - interval '5 minutes'
    order by updated_at;
   ```

2. **看对应 `job_instance` metrics**(`BatchDaySettleScheduler.finalizeSettling` 的判定来源)
   ```sql
   -- 用步骤 1 拿到的 (tenant_id, calendar_code, biz_date) 替换占位
   select count(*) filter (where status in ('PENDING','READY','DISPATCHED','RUNNING','RETRYING')) as active_count,
          count(*) filter (where status = 'FAILED')                                                as failed_count,
          count(*)                                                                                  as total_count
     from batch.job_instance
    where tenant_id = '<tenant>'
      and calendar_code = '<calendar>'
      and biz_date = '<bizDate>';
   ```
   - `active_count > 0` → 真的还有 job 没跑完,**不是卡**,看那批 job 为什么不动(走相应 job 排查路径)
   - `active_count = 0`,`failed_count >= 0`,`total_count > 0` → 应该进 SETTLED/FAILED 但没进,确实卡
   - `total_count = 0` → 没东西好结算,scheduler 应当回 CUTOFF(`SETTLING_REVERTED_TO_CUTOFF` audit)

3. **看 audit log 上一次状态机动作**
   ```sql
   select log_level, log_type, message, extra_json, created_at
     from batch.job_execution_log
    where detail_ref = 'BATCH_DAY_INSTANCE'
      and extra_json::jsonb->>'calendarCode' = '<calendar>'
      and extra_json::jsonb->>'bizDate'      = '<bizDate>'
    order by created_at desc
    limit 20;
   ```
   关注 `reasonCode`:
   - `BATCH_DAY_SETTLING_CLAIMED` 是最后一条 → tx2(`finalizeSettling`)崩了
   - `SETTLING_REVERTED_TO_IN_FLIGHT` 反复 → metrics 抖,有 job 不停在 active/inactive 切换
   - `IN_FLIGHT_BECAUSE_ACTIVE_INSTANCES` 周期性出 → 一直有 active job 不让进 SETTLING

4. **确认 scheduler 还在跑**
   ```bash
   docker compose logs --tail=200 batch-orchestrator | grep -E "batch_day_settle|BatchDaySettle"
   ```
   - 60s 内有 `Acquired lock 'batch_day_settle'` / 类似日志 → scheduler OK,问题在数据
   - 没日志 → ShedLock 锁卡(关联 `redis-shedlock-down.md`)

5. **frozen 标志位**(scheduler 会跳过 `frozen=true` 的行)
   ```sql
   select id, day_status, frozen from batch.batch_day_instance where day_status='SETTLING';
   ```
   `frozen=true` → 治理上有人故意冻结(例:正在调查),不要解冻直到对接人确认。

6. **关键决策点**:
   - active_count > 0 → 真有 in-flight job 没跑完 → **方案 A**(等 + 排查 job)
   - active_count = 0 但 scheduler 不动 → **方案 B**(ShedLock 释锁 + 重启)
   - 数据一致性怀疑(metrics 与 job_instance 矛盾)→ **方案 C**(治理接口 reopen)

---

## 怎么恢复

### 方案 A:等 + 排查阻塞 job(5-30 min)

适用:`active_count > 0`,真有 job 还在跑或卡住。

1. 查具体哪些 job 在 active:
   ```sql
   select id, job_code, status, dispatched_at, last_heartbeat_at
     from batch.job_instance
    where tenant_id='<tenant>' and calendar_code='<calendar>' and biz_date='<bizDate>'
      and status in ('PENDING','READY','DISPATCHED','RUNNING','RETRYING')
    order by dispatched_at;
   ```
2. 单条 stuck → 按通用 job stuck 流程(`incident-response.md`),可走治理接口标记 FAILED 或重试。
3. 所有 stuck job 进终态后,下一个 `BatchDaySettleScheduler` tick(默认 60s,见 `batch.batch-day.settle-scan-interval-millis`)自动收口。

### 方案 B:scheduler 释锁 + 重启(5 min)

适用:`active_count = 0` 但 batch day 不动,且 scheduler 日志缺失。

1. 看 ShedLock 锁:
   - jdbc provider:
     ```sql
     select name, lock_until, locked_by from batch.shedlock where name='batch_day_settle';
     ```
     `lock_until > now()` 且 `locked_by` 指向已挂的 instance → stuck,可 delete:
     ```sql
     delete from batch.shedlock where name='batch_day_settle' and lock_until < now() + interval '5 minutes';
     -- 谨慎:确认没有活的 instance 持锁
     ```
   - Redis provider:
     ```bash
     redis-cli -h localhost -p ${REDIS_PORT:-16379} \
       --scan --pattern 'shedlock:*:batch_day_settle' | xargs -r redis-cli del
     ```
2. 重启 orchestrator:`docker compose restart batch-orchestrator`
3. 等 60s,看 audit log 有没有新的 `BATCH_DAY_SETTLED` / `BATCH_DAY_FAILED`。

### 方案 C:治理接口 reopen / 强制 finalize(10 min)

适用:metrics 与 job_instance 矛盾、CAS version 反复冲突、scheduler 看不到这一行。

1. **优先**走 console-api 的 batch day 治理接口(`/api/console/batch-day/*` 系列,具体路径以本仓 controller 为准 — TODO 校对):
   - `reopen` → 回 OPEN(允许补录 job)
   - `force-settle` → 强推到 SETTLED/FAILED(走 `ConsoleOrchestratorProxyService`,**禁** console-api 直 UPDATE)
2. 实操前必须:
   - 在 incident channel 公告 + 取审批(ADR-021 数据对账边界)
   - 保留 `traceId` / `approvalId`
3. **绝不**直接 `UPDATE batch.batch_day_instance SET day_status=...`:CAS version 不对会被 scheduler 反复回滚,且没有 audit。

---

## 事后

- **写 incident-response 关联本剧本**:`incident-response.md` 追加 P3 行(单 batch day 卡通常不算平台级)。
- **alert 缺失**:补 `BatchDayStuckSettling`、`BatchDaySettleSchedulerSilent`(60s 没 tick 就告)。
- **判断要不要调阈值**:
  - `settle-scan-interval-millis` 默认 60s,SLA 紧的业务可调 30s
  - CAS 冲突频繁 → metrics 查询 + finalize 之间窗口太大,考虑 SELECT FOR UPDATE 保护(代码改动,出 plan)
- **剧本走不通**:metrics SQL 与 `selectBatchDayMetrics` 实现不一致 → 补 `batch-day-metrics-drift.md`;catch-up dedup key 撞 → 补 `catch-up-dedup-collision.md`。

## 关联

- 代码:`batch-orchestrator/.../infrastructure/scheduler/BatchDaySettleScheduler.java`(`claimSettling` / `finalizeSettling` / `driveCatchUp`)
- schema:`db/migration/V32__add_batch_day_support.sql`(`batch.batch_day_instance`)
- 状态机:`OPEN → CUTOFF → IN_FLIGHT → SETTLING → SETTLED|FAILED`,详见 `docs/design/batch-day-state-machine.md`(若不存在则 TODO 补)
- 上一级:[`docs/runbook/incident-response.md`](../incident-response.md)
