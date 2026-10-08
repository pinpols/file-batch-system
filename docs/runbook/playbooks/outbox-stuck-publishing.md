# outbox_event 卡 PUBLISHING 不进 PUBLISHED

> 优先级 P1 · 最后复核：2026-10-08（代码/文档核对；未执行生产故障演练）· 配套 chaos IT:仓内有 stale 回收和 Kafka 故障路径测试，真实长时间故障仍需 staging 演练

## TL;DR

**症状**:`batch.outbox_event` 里大量行长时间停在 `publish_status='PUBLISHING'`,下游 Kafka 看不到对应事件,任务派发停顿。
**一行修复**:正常情况调度会自动调用 `OutboxEventMapper.resetStalePublishing` 把超时(默认 `batch.outbox.publishing-timeout-seconds`)的行拨回可重投状态;若仍有大量 `FAILED`/`GIVE_UP` 残留,**走 orchestrator 治理接口** `POST /internal/outbox/republish`(reset 为 NEW,由 OutboxForwarder 重发;不直接改 DB)。无独立的 reset-stale 接口——stale 行只由上述定时回收自动处理。

---

## 怎么发现

- **Prometheus alert**:`BatchOutboxStalePublishingStuck` 覆盖 stale PUBLISHING 行；`BatchOutboxCircuitBreakerOpen` 覆盖集群级投递暂停；`BatchOutboxCircuitBreakerFailOpen` 覆盖 Redis 不可达时保护状态降级。stale 行本身已是状态型告警，不再重复增加同语义的数量阈值告警。
- **Grafana**:当前使用下列 Prometheus/SQL 查询；专用面板由目标监控环境落地:
  - 自定义 query:`SELECT count(*) FROM batch.outbox_event WHERE publish_status='PUBLISHING' AND updated_at < now() - interval '2 minutes'`
  - `batch_outbox_publish_failures_total`(由 `OutboxPublishCircuitBreaker` 喂),若持续涨 → 上游 Kafka 写失败
- **日志关键字**:
  - `重置 N 条滞留 PUBLISHING 状态的 outbox 事件为 FAILED` — `OutboxPollScheduler` 自愈日志(WARN);**多说明自愈有效**
  - `Outbox 投递熔断已打开:跳过推进(cooldown 中)` — 熔断器 open,需排查 Kafka
  - 长时间没有 `Outbox 下次轮询延迟` debug → poll 长期停滞,看 ShedLock(关联 `redis-shedlock-down.md`)
- **用户反馈**:任务 LAUNCH 接口已返回成功,但 Worker 长时间没看到 dispatch 消息;查 `job_instance.instance_status` 仍在 `CREATED` / `READY` / `RUNNING`,关联 outbox 没有进入 `PUBLISHED`。

---

## 怎么定位

1. **确认积压量级**
   ```sql
   select publish_status, count(*),
          max(updated_at) filter (where publish_status='PUBLISHING') as max_pub_updated
     from batch.outbox_event
    where created_at > now() - interval '1 hour'
    group by publish_status;
   ```
   - `PUBLISHING` 行 > 100 且 `max_pub_updated` > 几分钟前 → 确认积压
   - `FAILED` 行也多 → Kafka 投递在失败,见步骤 3

2. **看 `OutboxPollScheduler` 自愈有没有跑**
   ```bash
   docker compose logs --tail=500 batch-orchestrator | grep -E "重置.*PUBLISHING|Outbox 轮询|熔断"
   ```
   - 完全没日志:scheduler 没在跑,看 ShedLock 锁
     ```sql
     select * from batch.shedlock where name like 'outbox_poll%';
     -- lock_until 远超当前时间 + locked_by 是已挂的 instance → 锁 stuck
     ```
   - 有 "重置 N 条" 但 N 一直涨:自愈在跑但跟不上 → 上游(Kafka)有更深问题

3. **判断 Kafka 是不是写失败**
   ```bash
   docker compose exec kafka "${KAFKA_CONTAINER_BIN_DIR:-/opt/kafka/bin}/kafka-topics.sh" \
     --bootstrap-server kafka:29092 --list | head
   docker compose exec kafka "${KAFKA_CONTAINER_BIN_DIR:-/opt/kafka/bin}/kafka-console-consumer.sh" \
     --bootstrap-server kafka:29092 --topic batch.task.dispatch.import \
     --max-messages 3 --timeout-ms 5000
   ```
   - kafka 不通 → 走 `kafka-rebalance-stuck.md` 先救 Kafka
   - 通但消息少 → 看 orchestrator 是否 `OutboxPublishCircuitBreaker` open

4. **看熔断器状态**(查日志即可,无外部 endpoint)
   - 日志 `Outbox 投递熔断已打开` 持续打印 → open
   - cooldown 期(默认见 `OutboxPublishCircuitBreaker` 的 `cooldown-seconds`)结束自动 half-open 试探

5. **关键决策点**:
   - `OutboxPollScheduler` 在跑 + 自愈日志出现 + Kafka 通 → **方案 A**(等自愈 1-2 轮即可)
   - scheduler 不在跑(ShedLock 锁卡)→ **方案 B**(释锁 + 手动 reset)
   - Kafka 实际 down,outbox 堆但救不出 → 先按 `kafka-rebalance-stuck.md`,本剧本暂缓

---

## 怎么恢复

### 方案 A:让 scheduler 自愈(2-5 min)

适用:scheduler 健康、Kafka 健康、只是恰好积压。

1. 什么都不做,等 1-2 个 `publishing-timeout-seconds` 周期。看代码默认值(`OutboxProperties.publishingTimeoutSeconds`),典型 30-60s。
2. 监控:
   ```sql
   -- 每 30s 跑一次,行数应当下降
   select count(*) from batch.outbox_event
    where publish_status='PUBLISHING'
      and updated_at < current_timestamp - interval '60 seconds';
   ```
3. 若 10 min 内归零 → 收尾。

### 方案 B:手动触发 reset(5 min)

适用:stale 回收持续未运行或阈值经配置核查确实过大；仅凭无日志不能认定是 ShedLock 锁残留。

1. **stale 行(卡 PUBLISHING)无独立手动接口**:回收只由调度自动调 `OutboxEventMapper.resetStalePublishing` 完成。先核实调度、provider 和实例健康，不要手工删除 ShedLock。**对已转 `FAILED`/`GIVE_UP` 的行**,走 orchestrator 治理接口重投(docs/agent-baseline.md 红线:console-api / 运维**不能**直接 `UPDATE batch.outbox_event`):
   ```bash
   curl -X POST "http://localhost:18082/internal/outbox/republish?tenantId=${TENANT_ID}&dryRun=true" \
     -H "X-Internal-Secret: ${INTERNAL_SECRET}" \
     -H "Content-Type: application/json" \
     -d '{"ids": [12345], "operatorId": "<operator>", "reason": "<incident-or-ticket>"}'
   # dryRun=true 只返回符合条件的候选数，不改变状态。确认 tenant、event IDs 和审批后，再将 dryRun 改为 false 执行。
   # republish 将指定 tenant 下 FAILED/GIVE_UP 重置为 NEW，由 OutboxForwarder 重发。
   ```

2. 不要手工删除 JDBC ShedLock 行或 Redis key。锁应在租约到期后自然回收；强删可能造成仍运行的实例与另一实例并发执行。Redis 故障按 [`redis-shedlock-down.md`](redis-shedlock-down.md) 处理。

3. 仅在确认调度进程异常且通过变更审批后，才受控重启 orchestrator；之后核对 provider 健康指标、调度周期与 outbox 状态变化。

### 方案 C:自动回收仍不收敛

不要直接修改 `batch.outbox_event` 或手工伪造审计记录。保留 `tenant_id`、event key、状态时间线、scheduler 日志与 Kafka 结果，升级到 incident commander 和后端维护者；先确认锁、租约与事务是否仍被活实例持有，再决定是否开发经审计的治理接口或修复路径。生产数据修复须单独审批、备份并记录可回滚方案。

---

## 事后

- **写 incident-response 关联本剧本**:`incident-response.md` 追加 P2 行,链接本文件。
- **alert 覆盖**:`BatchOutboxStalePublishingStuck`、`BatchOutboxCircuitBreakerOpen`、`BatchOutboxCircuitBreakerFailOpen` 已回填到 Compose 与 Helm 规则文件；若需区分业务规模，再基于真实基线调整现有阈值，不复制规则。
- **判断要不要调阈值**:
  - 经常自愈 → `publishing-timeout-seconds` 默认值可能偏大,缩短可更快回收
  - 熔断器 cooldown 期内积压暴涨 → `failure-threshold` 太低,容易误触
- **剧本走不通**:若是 schema drift(`outbox_event.publish_status` 多了新状态)→ 补 schema 校验 alert + 一篇 `outbox-schema-drift.md`。
- **联动 Plan #1 chaos**:`OutboxStuckPublishingChaosIT` 应当模拟 "scheduler 跑 + Kafka 断 30s + 恢复" 验证自愈生效。

## 关联

- 代码:`batch-orchestrator/.../infrastructure/mq/OutboxPollScheduler.java`(`resetStalePublishingEvents`),`OutboxEventMapper.xml#resetStalePublishing`
- schema:`db/migration/V7__create_ops_tables.sql`(`batch.outbox_event` 定义)
- 架构原则:docs/agent-baseline.md §异步事件路由 / §架构硬约束(console-api 禁直接改 outbox)
- 上一级:[`docs/runbook/incident-response.md`](../incident-response.md)
