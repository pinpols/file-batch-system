# batch_day_instance 卡 SETTLING 不进 SETTLED/FAILED

> 优先级 P2 · 最后复核：2026-10-08（代码/文档核对；未执行生产故障演练）· 配套 chaos IT:无(P2,优先级低)

## TL;DR

**症状**:`batch.batch_day_instance.day_status='SETTLING'` 长时间不变,batch day 不收敛,SLA 告警飘红,下游补跑(catch-up)不触发。
**处置原则**:先核对实例指标、冻结标记、调度运行状态和审计记录。当前没有把 `SETTLING` 强制终结的治理动作；`REOPEN` 仅适用于已处于终态的批量日，不能用于解除卡住的 `SETTLING`。

---

## 怎么发现

- **Prometheus alert**:当前没有专用 `BatchDayStuckSettling` 规则，不能把业务表扫描伪装成已接入告警。
- **Grafana**:使用 Prometheus Explore 或数据库面板执行下方查询；专用规则待有稳定指标后再补:
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
- **用户反馈**:"今天 batch day 已 cutoff 半小时还没收敛" / "catch-up 没起" / "SLA dashboard 飘红"。

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
   select count(*) as total_count,
          count(*) filter (where ji.instance_status in ('CREATED','WAITING','READY','RUNNING','PAUSED')) as active_count,
          count(*) filter (where ji.instance_status = 'SUCCESS') as success_count,
          count(*) filter (where ji.instance_status in ('PARTIAL_FAILED','FAILED','CANCELLED','TERMINATED')) as failed_count
     from batch.job_instance ji
     join batch.job_definition jd on jd.id = ji.job_definition_id
    where ji.tenant_id = '<tenant>'
      and jd.calendar_code = '<calendar>'
      and ji.biz_date = '<bizDate>'
      and ji.dry_run = false;
   ```
   此查询按当前 `JobInstanceMapper.selectBatchDayMetrics` 实现使用 `job_definition.calendar_code`。表中另有创建时快照 `job_instance.calendar_code`；若日历配置曾变更且两者不一致，应先记录差异并升级处理，不能把手册查询结果当作独立于当前 mapper 的权威口径。
   - `active_count > 0` → 真的还有 job 没跑完,**不是卡**,看那批 job 为什么不动(走相应 job 排查路径)
   - `active_count = 0`,`total_count > 0` → 按 `failed_count` 判定应进入 FAILED 或 SETTLED；仍为 SETTLING 时再查 scheduler 与审计记录
   - `total_count = 0` → 没东西好结算,scheduler 应当回 CUTOFF(`SETTLING_REVERTED_TO_CUTOFF` audit)

3. **看 audit log 上一次状态机动作**
   ```sql
   select log_level, log_type, message, extra_json, created_at
     from batch.job_execution_log
    where detail_ref = 'batch_day_instance'
      and extra_json::jsonb->>'calendarCode' = '<calendar>'
      and extra_json::jsonb->>'bizDate'      = '<bizDate>'
    order by created_at desc
    limit 20;
   ```
   关注 `reasonCode`:
   - `BATCH_DAY_SETTLING_CLAIMED` 是最后一条 → 只说明已 claim；下一阶段可能尚未执行、失败或未写入审计，需结合后续 tick、应用异常和指标继续判断
   - `SETTLING_REVERTED_TO_IN_FLIGHT` 反复 → metrics 抖,有 job 不停在 active/inactive 切换
   - `IN_FLIGHT_BECAUSE_ACTIVE_INSTANCES` 周期性出 → 一直有 active job 不让进 SETTLING

4. **确认 scheduler 还在跑**
   ```bash
   docker compose logs --tail=200 batch-orchestrator | grep -E "batch_day_settle|BatchDaySettle"
   ```
   - 该调度器不会保证每次成功获取锁都输出日志；业务状态日志只能证明执行到相应分支，不能单独证明锁健康
   - 结合 `batch_shedlock_provider_healthy` 指标和 provider 实际状态判断；Redis 故障按 `redis-shedlock-down.md` 处理

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
   select ji.id, ji.job_code, ji.instance_status, ji.started_at, ji.updated_at
     from batch.job_instance ji
     join batch.job_definition jd on jd.id = ji.job_definition_id
    where ji.tenant_id = '<tenant>'
      and jd.calendar_code = '<calendar>'
      and ji.biz_date = '<bizDate>'
      and ji.dry_run = false
      and ji.instance_status in ('CREATED','WAITING','READY','RUNNING','PAUSED')
    order by ji.created_at;
   ```
2. 单条 stuck → 按通用 job stuck 流程(`incident-response.md`),可走治理接口标记 FAILED 或重试。
3. 所有 stuck job 进终态后,下一个 `BatchDaySettleScheduler` tick(默认 60s,见 `batch.batch-day.settle-scan-interval-millis`)自动收敛。

### 方案 B:核实 scheduler 与 ShedLock(按需)

适用:`active_count = 0` 且连续多个扫描周期状态仍不变化；单纯缺少成功获取锁的日志不能作为锁故障证据。

1. 看 ShedLock 锁:
   - jdbc provider:
     ```sql
     select name, lock_until, locked_by from batch.shedlock where name='batch_day_settle';
     ```
     仅用于观察锁状态。不要删除该行；锁由 ShedLock 在 `lock_until` 后自然过期，手工删除可能导致仍运行的实例与新实例并发执行。
   - Redis provider:
     ```bash
     redis-cli -h localhost -p ${REDIS_PORT:-16379} \
       --scan --pattern '*shedlock:*:batch_day_settle'
     # 只列出 key；不要手工 DEL。确认 provider 和服务状态后按 redis-shedlock-down.md 处理。
     ```
2. 先按 [`redis-shedlock-down.md`](redis-shedlock-down.md) 核实当前 provider、持锁实例和 Redis/PG 健康；provider 切换必须停止所有相关服务后统一切换，禁止双 provider 并行。
3. 只有确认调度进程失效且恢复流程要求重启时，才按变更流程重启 orchestrator；不要把重启或删锁当作通用恢复动作。
4. 等待后续调度周期，核对 audit log 是否出现新的 `BATCH_DAY_SETTLED` / `BATCH_DAY_FAILED`。

### 方案 C:终态批量日经审计重新打开(仅适用终态)

仅当批量日已处于 `SETTLED`、`FAILED` 等终态，且业务审批要求重新处理时使用。该动作不能修复仍为 `SETTLING` 的记录，也不能解决 metrics 不一致或 scheduler 故障。

1. 仅使用已实现并经授权的 Console 治理接口 `POST /api/console/batch-days/operate`，通过 `action=REOPEN` 重新打开终态批量日。Orchestrator 会校验终态、执行 CAS 并双写审计。当前没有 `force-settle` 动作。
2. 实操前必须:
   - 在 incident channel 公告 + 取审批(ADR-021 数据对账边界)
   - 保留 `traceId` / `approvalId`
3. **绝不**直接 `UPDATE batch.batch_day_instance SET day_status=...`；直接写库会绕过状态机、CAS 和审计。

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
- 状态机:`OPEN → CUTOFF → IN_FLIGHT → SETTLING → SETTLED|FAILED`,详见 [`batch-day-capability-design.md`](../../design/batch-day-capability-design.md)
- 上一级:[`docs/runbook/incident-response.md`](../incident-response.md)
