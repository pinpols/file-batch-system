# ADR-010: Trigger → Orchestrator 异步解耦(trigger_outbox + Kafka)

- **状态**: 已实施（Accepted & Implemented，2026-05-02 全量上线 + 同步 HTTP 桥物理删除）
- **日期**: 2026-04-30（提案），2026-05-02（实施收尾），2026-05-15（状态修订 R4-P1-9）
- **实施后记**: 异步路径已固化，`batch.trigger.async-launch.enabled` 开关与 `HttpOrchestratorTriggerAdapter` 已同步删除。下文 §灰度开关 / §验收标准 仅作历史参考。
- **决策人**: 后端平台团队
- **关联**: [ADR-002 transactional-outbox](./ADR-002-transactional-outbox.md)(本 ADR 直接复用其模式)
- **解决问题**: [`docs/analysis/deep-issue-analysis.md` §5.7](../../analysis/deep-issue-analysis.md)

## 背景

`batch-trigger` 是任务调度入口,负责:

- Quartz / Hashed-wheel 触发器到点 fire
- 接收手工触发 / EVENT 触发
- Misfire 处理(应触发但未触发的回填)

触发后,trigger 通过 `OrchestratorTriggerAdapter.sendTrigger(launchRequest)` **同步 HTTP** 调用 orchestrator 的 `/internal/launch/single` 接口落 `job_instance` + 推进主链路。`DefaultTriggerService.java:234-247` 是核心调用点。

### 痛点(deep-issue §5.7)

主链路 `DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT` 已是异步解耦,**但触发器入口这一段仍是同步桥**,产生三类问题:

1. **orchestrator 短暂不可用** → trigger 该次 launch 失败,只能依赖 client retry。**重启 trigger 会丢已 fire 但未下发的 launch**:Quartz 已 `fire` 触发记录,HTTP 没成功,trigger 本地 `trigger_request` 留在 `FORWARD_FAILED`,需要人工介入或定时对账重发。
2. **trigger 重启 + HTTP 在飞中** → Quartz 已 fire,HTTP 请求飞行中,trigger JVM 挂 → 不知道 orchestrator 收没收到 → 只能"宁可错杀重发,靠 idempotencyKey 去重"。但 orchestrator 端实际是 `uk_job_instance_tenant_dedup` 回退,误差被吞但仍占用一次重试预算。
3. **同步阻塞 Quartz worker thread** → orchestrator 慢调用拖累调度吞吐;Quartz 默认线程池 10,慢调用突发时容易把 fire 队列堆积。

主链路其它环节都已通过 outbox + Kafka 解耦,触发器入口这一段是整个系统**最后一处不一致**,与"DB 是事实源,Kafka 仅异步驱动"的架构纲领相悖。

### 备选方案(被否决,见 §5)

1. **HTTP retry-with-backoff 加上对账定时器**:把 trigger_request `FORWARD_FAILED` / `PENDING` 残留靠定时器重试。Band-aid,不解决"trigger 重启时已 fire 但 HTTP 未发"的根因。
2. **CDC(Debezium 监听 trigger_request)**:解耦充分但引入 Kafka Connect 集群,运维超出当前能力,与 ADR-002 选 outbox 不选 CDC 同理。
3. **直接 KafkaTemplate.send + listener**:产生与 ADR-002 同款双写不一致问题。

## 决策

**复用 ADR-002 事务性 Outbox 模式,在 batch-trigger 模块内新建 `trigger_outbox_event` + `TriggerOutboxRelay` + orchestrator 侧 `TriggerLaunchConsumer`,通过 Kafka topic `batch.trigger.launch.v1` 异步驱动 launch。**

### 数据流

```
[Quartz / wheel fires]
        │
        ▼
DefaultTriggerService.persistAndForward()
        │  @Transactional 单事务内:
        │   1) trigger_request INSERT (status=PENDING)
        │   2) trigger_outbox_event INSERT (status=PENDING, payload=LaunchRequest JSON)
        ▼
[事务 commit]
        │
        ▼ (异步, 周期扫描)
TriggerOutboxRelay.@Scheduled(每 200ms)
        │  SELECT * FROM trigger_outbox_event
        │  WHERE status='PENDING' FOR UPDATE SKIP LOCKED LIMIT 100
        │  KafkaTemplate.send(topic='batch.trigger.launch.v1', payload)
        │  UPDATE status='PUBLISHED', published_at=now()
        ▼
Kafka topic: batch.trigger.launch.v1
        │
        ▼
batch-orchestrator
TriggerLaunchConsumer.@KafkaListener
        │  1) 反序列化 LaunchEnvelope (含 LaunchRequest + dedupKey + traceId)
        │  2) 调用 LaunchService.launch(launchRequest)
        │     (复用现有 /internal/launch/single 的内部逻辑,
        │      uk_job_instance_tenant_dedup 防重复消息)
        │  3) ack
        ▼
[原 launch T1/T2 → outbox → 主链路 ...]
```

### Schema(新增 Flyway V80)

```sql
-- V80__create_trigger_outbox_event.sql
CREATE TABLE batch.trigger_outbox_event (
  id              BIGSERIAL PRIMARY KEY,
  tenant_id       VARCHAR(64) NOT NULL,
  request_id      VARCHAR(128) NOT NULL,           -- 关联 trigger_request.request_id
  topic           VARCHAR(128) NOT NULL,           -- 默认 'batch.trigger.launch.v1',留扩展
  payload         JSONB NOT NULL,                  -- LaunchEnvelope 序列化
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING / PUBLISHING / PUBLISHED / FAILED / GIVE_UP
  retry_count     INT NOT NULL DEFAULT 0,
  last_error      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  published_at    TIMESTAMPTZ,
  next_retry_at   TIMESTAMPTZ DEFAULT NOW()        -- 退避用,初始 = NOW() 即可立即被扫
);

CREATE INDEX idx_trigger_outbox_pending
  ON batch.trigger_outbox_event (status, next_retry_at)
  WHERE status IN ('PENDING', 'FAILED');

CREATE UNIQUE INDEX uk_trigger_outbox_request_id
  ON batch.trigger_outbox_event (tenant_id, request_id);
```

复用现有 `OutboxPublishStatus` 枚举(`OutboxPublishStatus.NEW/PUBLISHING/PUBLISHED/FAILED/GIVE_UP`),保持术语一致。

### Topic 协议

| 字段 | 含义 |
|---|---|
| topic | `batch.trigger.launch.v1`(版本化,未来协议改 → v2) |
| key | `tenantId:requestId`(同 request 多分区幂等) |
| value | JSON `LaunchEnvelope { launchRequest, dedupKey, traceId, sourceFireTime }` |
| Headers | `X-Trace-Id`, `X-Tenant-Id`(便于 Kafka 查询) |

`LaunchEnvelope` 是新加的 wrapper,把 trigger 端**已经做过的 dedup 计算**和 trace 上下文传给 orchestrator,避免 orchestrator 重复算 dedup。

### 实现位置

| 模块 | 新增 / 修改 |
|---|---|
| `batch-trigger` | `mapper/TriggerOutboxEventMapper`(MyBatis); `application/engine/TriggerOutboxRelay`(@Scheduled);`DefaultTriggerService.persistAndForward` 改为同事务写 outbox |
| `batch-orchestrator` | `application/trigger/TriggerLaunchConsumer`(@KafkaListener);复用 `LaunchApplicationService.launch` 的现有内部 API |
| `batch-common` | `LaunchEnvelope` record + Jackson serializer |
| `db/migration` | `V80__create_trigger_outbox_event.sql` |

### 灰度开关与回滚

- 配置项 `batch.trigger.async-launch.enabled`(默认 `false`)
  - `false`:走原同步 HTTP 路径(完全无变化)
  - `true`:走 outbox + Kafka 路径
- 切换策略:**先 staging E2E 跑通 → 单租户灰度 → 全租户切换**。每阶段保留 24h 观察 `trigger.outbox.publish.lag` 指标。
- 回滚:配置项改回 `false`,trigger 立刻走 HTTP;在飞 outbox 记录由 relay 继续投递,**不回滚已写入数据库的 trigger_outbox_event**(投出去就投出去,orchestrator dedup 回退)。
- 双写期(可选,1-2 周):同时写 trigger_outbox **并** HTTP,双轨观察一致性,回退信心后关 HTTP。这步可选,不强制。

### 守护测试

- `TriggerOutboxRelayTest` 单测:relay 扫描 + 状态推进
- `TriggerAsyncLaunchE2eIT` 端到端:Testcontainers Kafka + Postgres,trigger fire → trigger_outbox PENDING → relay publish → orchestrator 消费 → job_instance INSERT
- `TriggerOutboxRetryE2eIT`:模拟 Kafka 不可用,验证 retry_count 递增 + last_error 写入 + 最终 GIVE_UP 告警
- `TriggerAsyncLaunchIdempotencyE2eIT`:同 requestId 重复消费验证 `uk_job_instance_tenant_dedup` 回退

## 后果

### 正面

- **trigger 重启不丢 launch**:Quartz fire 后第一时间落 trigger_outbox(同事务,与 trigger_request 一起),trigger JVM 异常退出 outbox 已写入数据库,relay 重启后继续投递。
- **orchestrator 短暂宕机不阻塞 trigger**:HTTP 同步桥下,orchestrator 重启期间 Quartz worker thread 阻塞;outbox 模式下,trigger 写完 outbox 立即返回,orchestrator 起来后 Kafka 消费跟上。
- **架构一致**:整条主链路 + trigger 入口都是 DB+outbox+Kafka 模式,运维心智模型统一,可观测性指标统一(`*.outbox.publish.lag` 系列)。
- **可对账**:trigger_outbox_event + trigger_request 双表,出问题任何一边都能反查。

### 负面

- **延迟增加 ~200ms**:同步 HTTP 通常 < 50ms,outbox 引入轮询间隔(默认 200ms)。这个延迟在 SLA 内可接受(用户消息确认过)。
- **新加 1 张表 + 1 个 @Scheduled bean + 1 个 KafkaListener**,运维资产增加。
- **多实例 trigger 部署需要行级锁**:`SELECT ... FOR UPDATE SKIP LOCKED` 防止重复发布;复用 ADR-002 已验证模式。
- **double-publish 风险**:Kafka send 成功但 status 未及时更新到 PUBLISHED → 进程崩溃 → 重启后 relay 再次发同一条。orchestrator 端 `uk_job_instance_tenant_dedup` 回退,不会真正双跑,但会消耗一次 dedup 检查。
- **Topic 协议演进成本**:加字段需要 v1 → v2,过渡期 consumer 兼容两版。

## 实施分阶段

| Stage | 内容 | 估时 | 阻塞 |
|---|---|---|---|
| **Stage 1** | V80 migration + `LaunchEnvelope` DTO + `TriggerOutboxEventMapper`(纯 schema + bean,不挂主链路) | 0.5d | 否 |
| **Stage 2** | `TriggerOutboxRelay` 实现 + 单测(参照 `OutboxPublisher`) | 1d | 否 |
| **Stage 3** | `DefaultTriggerService.persistAndForward` 加 outbox 写入分支(开关默认 false) | 0.5d | Stage 2 |
| **Stage 4** | orchestrator `TriggerLaunchConsumer` + `LaunchApplicationService` 内部 API 暴露 | 1d | Stage 1 |
| **Stage 5** | E2E 守护:`TriggerAsyncLaunchE2eIT` / `TriggerOutboxRetryE2eIT` / `TriggerAsyncLaunchIdempotencyE2eIT` | 2d | Stage 2-4 |
| **Stage 6** | 灰度切换:staging 全跑 → 单租户开 `enabled=true` 24h → 全量开 | 2d(含等待) | Stage 5 |
| **Stage 7** | 旧 HTTP 同步路径 deprecation:加 deprecation log,1 个 minor 版本后物理删除 | 0.5d | Stage 6 + 1 minor 版本 |

合计 **~7-8 人天**(扣灰度等待),实施期建议 2-3 周(留 staging 观察窗口)。

## 替代方案(被拒绝)

### A. HTTP retry + 对账定时器

**否决理由**:band-aid。trigger 重启 + HTTP 在飞期间发出的请求,无法判断 orchestrator 是否已收到 → 只能"全部重发,靠 dedup 去重",对账定时器只是把这个语义从同步搬到异步。不解决"事务边界与外部调用 + JVM 生命周期"的根本耦合。

### B. CDC(Debezium 监听 trigger_request)

**否决理由**:同 ADR-002 §备选方案 2,Kafka Connect 运维成本超出当前团队能力;trigger_request 字段语义与 launch payload 字段语义不完全一致,CDC 通用化成本反而高于专用 outbox。

### C. 直接 KafkaTemplate.send

**否决理由**:同 ADR-002 §备选方案 1,产生双写不一致(trigger_request commit 但 Kafka send 失败 → trigger_request 留 PENDING 永远没人重试 → 客户报"任务未触发")。

## 不变量

- **trigger_outbox_event INSERT 必须与 trigger_request INSERT 同事务**(ADR-002 §同事务约束)
- **orchestrator 不直写 trigger_outbox_event**(模块边界:trigger 模块独占)
- **Kafka 消费失败不修改源数据库状态**:消费失败 → ack 失败 → Kafka rebalance / DLQ 回退,不能反向改 trigger_outbox.status
- **同 requestId 多次消费必须 idempotent**:依赖 orchestrator 端 `uk_job_instance_tenant_dedup`,不能通过 trigger_outbox 端去重

## 验收标准

- 配置 `batch.trigger.async-launch.enabled=true` 后,主链路 E2E(Import / Export / Dispatch / Process 4 类 worker)全部通过
- `TriggerAsyncLaunchE2eIT` 模拟 trigger 进程崩溃 → 重启 → 恢复发送的场景通过
- `TriggerAsyncLaunchIdempotencyE2eIT` 验证同 requestId 5 次消费只产生 1 个 job_instance
- `trigger.outbox.publish.lag` Prometheus 指标可在 Grafana 面板查询,p99 < 5s
- 灰度全量切换后 24h 内,`trigger_request` 状态分布与切换前同构(无 FORWARD_FAILED 残留增长)

## 相关 ADR

- [ADR-002](./ADR-002-transactional-outbox.md):本 ADR 直接复用 outbox 模式,术语 / 状态枚举 / 守护测试结构完全对齐
- [ADR-003](./ADR-003-launch-t1-t2-split.md):`LaunchApplicationService.launch` 已经是 T1/T2 拆分,本 ADR 不改 launch 内部事务边界
- [ADR-006](./ADR-006-compensation-requires-new.md):重试 / 补偿走 `REQUIRES_NEW`,`TriggerOutboxRelay` 的失败 retry 路径需遵循
