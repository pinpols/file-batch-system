# trigger 异步 launch 灰度切换 runbook(ADR-010 Stage 6)

> **⚠️ 已废止（2026-05-02）**：`batch.trigger.async-launch.enabled` 开关已随同步 HTTP 路径（`HttpOrchestratorTriggerAdapter`）一并删除。异步 Kafka 路径现为**唯一**路径，无需切换操作。本文档仅作历史存档，请勿执行其中步骤。

> ~~**状态**:Stage 4 Kafka producer/consumer 已实装,Stage 6 灰度切换待执行(operational)。本文档是 staging → 单租户 → 全量切换的操作步骤。~~

## 0. 前置检查清单

切换前必须确认(checked = ✅):

- [ ] V80 migration 已 apply 到目标环境(`SELECT 1 FROM batch.trigger_outbox_event LIMIT 1` 不报错)
- [ ] Kafka topic `batch.trigger.launch.v1` 已创建(分区数 ≥ tenant 数,replication-factor=3)
- [ ] orchestrator + trigger 镜像版本 ≥ 含 ADR-010 Stage 1-4 的版本（`git log` 含 `9587b8bf` / `087f6b7a` / `1ca3a957` + Stage 4 收尾 commit）
- [ ] orchestrator consumer group `orchestrator-trigger-launch` 不存在或 offset 在最新位置(避免重复消费历史消息)
- [ ] Prometheus 已抓取以下指标(`{__name__=~"batch.trigger.*"}`):
  - `batch.trigger.launch.consumed.total`(orchestrator 端)
  - `batch.trigger.launch.deduped.total`
  - `batch.trigger.launch.failed.total`
  - trigger outbox 监控参考 `batch.outbox.publish.duration`(orchestrator outbox 同款指标命名)

## 0.1 本地 docker-compose 实测验证记录(2026-04-30)

> 切换默认 `enabled=true` 后,在本地 docker-compose 全栈跑了 smoke test,记录在此供运维参考预期行为与已知问题。

### 验证清单

| 环节 | 结果 | 证据 |
|---|---|---|
| V80 migration 自动 apply | ✅ | trigger 启动期 flyway 跑过,`batch.trigger_outbox_event` 表已建 |
| Kafka topic `batch.trigger.launch.v1` 存在 | ✅ | `kafka-topics.sh --list` 命中(由 kafka-init 默认 KAFKA_TOPICS 创建) |
| trigger TriggerOutboxRelay bean 启动 | ✅ | 日志:`TriggerOutboxRelay 已启动:poll=200ms batch=100 backoff_max=60s` |
| trigger Kafka producer config 用容器名 | ✅ | 日志:`bootstrap.servers = [kafka:29092]`(非 `localhost:19092`) |
| orchestrator TriggerLaunchConsumer 订阅 | ✅ | 日志:`Subscribed to topic(s): batch.trigger.launch.v1` + 3 partitions assignment |
| 1 条 manual launch 全链路 | ✅ | trigger HTTP 200 → outbox PUBLISHED → consumer 消费 → job_instance INSERT |

### 端到端延迟实测

| 阶段 | 耗时 |
|---|---|
| trigger HTTP 接收 → trigger_request + outbox INSERT(同事务) | < 100ms |
| outbox INSERT → relay 抢占 → Kafka publish → status=PUBLISHED | **~680ms**(含 200ms relay 轮询周期) |
| Kafka publish → orchestrator consumer 消费 → job_instance CREATED | < 1s |
| **总端到端**(trigger HTTP → job_instance INSERT) | **< 1s** |

> 与 ADR-010 设计预期吻合:同步 HTTP 桥 < 50ms 单次延迟换 outbox ~200ms 轮询 + Kafka send,总体仍亚秒级。

### 已知 race + 自愈机制

第一次消费时偶发 `TriggerLaunchConsumer launch 失败`,listener 抛异常 → spring-kafka 默认 SeekToCurrentErrorHandler 重试 → 第二次消费成功。原因:trigger 异步路径下 trigger_request 事务可能在 outbox 已 publish 但还没对 orchestrator 可见(read-after-write race)。orchestrator 端 `uk_job_instance_tenant_dedup` + listener 重试机制兜底,**业务层面不会双跑也不会丢失**,仅在 metric 上看到 `batch.trigger.launch.failed.total{reason="runtime"}` 偶发增量(后随重试 success 抹平)。

### 容器配置必备项(2026-04-30 切换发现)

切默认 `true` 后通过 smoke test 暴露的关键配置 bug,**已修但灰度环境 / 生产部署清单要复核**:

```yaml
# docker/compose/app.yml trigger service 必须有:
trigger:
  environment:
    BATCH_KAFKA_BOOTSTRAP_SERVERS: ${BATCH_KAFKA_BOOTSTRAP_SERVERS:-kafka:29092}  # ← 容器内必须用网络名,不能 localhost
  depends_on:
    kafka:
      condition: service_healthy  # ← trigger 启动时 Kafka 必须先就绪
```

不加这两条 → trigger 启动后 producer 用 batch-defaults.yml fallback `localhost:19092` → 容器内 localhost = trigger 自己 → 连不上 Kafka → outbox 永远卡 PUBLISHING(`last_error` 也为空,因为 publish call 还在 Kafka 客户端内部 retry 没返回失败)。

**生产部署的 K8s manifest / Helm values 必须等价配齐**:`BATCH_KAFKA_BOOTSTRAP_SERVERS` 指向集群内 Kafka service DNS,trigger Pod startup probe / readiness probe 等 Kafka 可达后再放流量。

### 切换后 fallback 路径仍有效

显式 `BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=false` + 重启 trigger + orchestrator → 立刻走原 HTTP 同步桥(`HttpOrchestratorTriggerAdapter`),`DefaultTriggerService.forwardToOrchestrator` 首次调用打 1 条 deprecation WARN(AtomicBoolean 防刷屏)。已落库的 `trigger_outbox_event` 由 relay 继续投递(回滚不丢),orchestrator consumer 仍在跑(由于 `@ConditionalOnProperty matchIfMissing=true`,缺 env 也起 listener,需要显式 `false` 才不起)。

---

## 1. Staging 全量验证(D-7 ~ D-3)

**目标**:在 staging 环境跑通完整 fire→outbox→relay→Kafka→consumer→launch 链路。

```bash
# 1. 在 staging 配置启用开关
kubectl set env deployment/batch-trigger -n batch-staging \
  BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=true

kubectl set env deployment/batch-orchestrator -n batch-staging \
  BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=true

# 2. 滚动重启
kubectl rollout restart deployment/batch-trigger -n batch-staging
kubectl rollout restart deployment/batch-orchestrator -n batch-staging
kubectl rollout status deployment/batch-trigger -n batch-staging --timeout=5m
kubectl rollout status deployment/batch-orchestrator -n batch-staging --timeout=5m

# 3. 触发若干测试 job(覆盖 4 类 worker)
curl -X POST "${STAGING_TRIGGER_HOST}/internal/trigger/launch" \
  -H "X-Internal-Secret: ${STAGING_INTERNAL_SECRET}" \
  -d '{"tenantId":"staging-test","jobCode":"smoke_import","triggerType":"MANUAL","requestId":"staging-async-1"}'
# (重复 4 次,jobCode 替换 smoke_export / smoke_dispatch / smoke_process)

# 4. 验证 outbox → Kafka → consumer 链路
psql ${STAGING_DB} -c "
SELECT publish_status, COUNT(*) FROM batch.trigger_outbox_event
WHERE created_at > NOW() - INTERVAL '5 min'
GROUP BY publish_status;
"
# 期望:全部 PUBLISHED,无 NEW/FAILED 残留

# 5. 验证 job_instance 落库
psql ${STAGING_DB} -c "
SELECT request_id, instance_status FROM batch.job_instance
WHERE request_id LIKE 'staging-async-%' ORDER BY created_at DESC LIMIT 10;
"
# 期望:4 个 staging-async-* 都有对应 job_instance(状态推进中)

# 6. Grafana 看 batch.trigger.launch.consumed.total / failed.total
# 期望:consumed 增加,failed 不增加
```

**通过标准**:5 分钟内 `trigger_outbox_event` 全部 `PUBLISHED`,4 个 staging-async-* request 都有对应 `job_instance`,`batch.trigger.launch.failed.total` 0 增长。

## 2. 单租户灰度(D-3 ~ D-2,24h 观察)

**目标**:在生产环境只对 1 个低风险租户开启异步路径,观察 24h。

> ADR-010 设计上是**全局开关**(`batch.trigger.async-launch.enabled`),没有租户级灰度能力。**单租户灰度的实现方式**:
>
> 1. 在 staging 准生产副本(prod-canary)上 100% 启用,观察 24h
> 2. 不在 prod 主集群单独按租户切换(开关粒度不允许)

```bash
# 1. 在 prod-canary 启用
kubectl set env deployment/batch-trigger -n batch-prod-canary \
  BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=true
kubectl set env deployment/batch-orchestrator -n batch-prod-canary \
  BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=true
kubectl rollout restart deployment/batch-trigger deployment/batch-orchestrator -n batch-prod-canary

# 2. 24h 内每小时检查
psql ${PROD_CANARY_DB} -c "
SELECT
  date_trunc('hour', created_at) AS hour,
  publish_status,
  COUNT(*) AS cnt
FROM batch.trigger_outbox_event
WHERE created_at > NOW() - INTERVAL '24 hour'
GROUP BY hour, publish_status
ORDER BY hour DESC, publish_status;
"
# 期望:每小时 PUBLISHED 占 ≥99%,FAILED ≤1%(短暂 broker 抖动可接受,GIVE_UP 0)
```

**通过标准**(24h):
- `trigger_outbox_event.publish_status='PUBLISHED'` 占比 ≥99%
- `GIVE_UP` 0 条
- `trigger_request.request_status='LAUNCHED'` 占比与历史同步桥模式一致(±1%)
- Grafana `batch.trigger.launch.failed.total{reason!="rate_limited"}` 0 增长
- `trigger.outbox.publish.lag` (next_publish_at - now @ NEW status) p99 < 5s

## 3. 全量切换(D-1)

```bash
# prod 主集群启用
kubectl set env deployment/batch-trigger -n batch-prod \
  BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=true
kubectl set env deployment/batch-orchestrator -n batch-prod \
  BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=true
kubectl rollout restart deployment/batch-trigger deployment/batch-orchestrator -n batch-prod
kubectl rollout status deployment/batch-trigger -n batch-prod --timeout=10m
kubectl rollout status deployment/batch-orchestrator -n batch-prod --timeout=10m

# 切换后立即验证
psql ${PROD_DB} -c "
SELECT publish_status, COUNT(*) FROM batch.trigger_outbox_event
WHERE created_at > NOW() - INTERVAL '5 min' GROUP BY publish_status;
"
```

## 4. 回滚预案

若任一环节出现以下情况,**立即回滚**:

- `batch.trigger.launch.failed.total{reason="runtime"}` 5 分钟内 > 10
- `trigger_outbox_event.publish_status='GIVE_UP'` 出现非 0
- `trigger_request.request_status='FORWARD_FAILED'` 增量 > 历史均值 3σ
- Kafka consumer lag > 1000 条

```bash
# 把开关切回 false(走原同步 HTTP 路径)
kubectl set env deployment/batch-trigger -n batch-prod \
  BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=false
kubectl set env deployment/batch-orchestrator -n batch-prod \
  BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=false
kubectl rollout restart deployment/batch-trigger deployment/batch-orchestrator -n batch-prod

# 已写库的 outbox 事件由 relay 继续投递(开关只控制 trigger 端是否新写 outbox + relay 是否启动)
# 注意:已 PUBLISHED 的事件 orchestrator 仍会消费,uk_job_instance_tenant_dedup 兜底重复
```

## 5. 切换后 24h 对账

```sql
-- A. trigger_request 状态分布对比(切换前 7 天 vs 切换后 1 天)
WITH baseline AS (
  SELECT request_status, COUNT(*) * 1.0 / 7 AS daily_avg
  FROM batch.trigger_request
  WHERE created_at BETWEEN NOW() - INTERVAL '8 day' AND NOW() - INTERVAL '1 day'
  GROUP BY request_status
), today AS (
  SELECT request_status, COUNT(*) AS today_cnt
  FROM batch.trigger_request
  WHERE created_at > NOW() - INTERVAL '1 day'
  GROUP BY request_status
)
SELECT
  COALESCE(baseline.request_status, today.request_status) AS status,
  ROUND(baseline.daily_avg) AS baseline_avg,
  today.today_cnt,
  ROUND((today.today_cnt - baseline.daily_avg) / baseline.daily_avg * 100, 2) AS pct_change
FROM baseline FULL OUTER JOIN today USING (request_status)
ORDER BY status;
-- 期望:LAUNCHED 占比稳定,FORWARD_FAILED 不再产生(异步路径下没有 forward 失败概念)

-- B. trigger_outbox_event 健康度
SELECT publish_status,
       COUNT(*) AS cnt,
       MAX(publish_attempt) AS max_attempts,
       MAX(EXTRACT(EPOCH FROM (NOW() - created_at))) AS max_age_seconds
FROM batch.trigger_outbox_event
WHERE created_at > NOW() - INTERVAL '24 hour'
GROUP BY publish_status;
```

## 6. Stage 7 物理删除(下一个 minor 版本)

切换后稳定运行 1 个 minor 版本周期(通常 2-4 周),无回滚记录,执行 Stage 7:

1. 物理删除 `HttpOrchestratorTriggerAdapter` 类(已在 Stage 7 commit 标 `@Deprecated`)
2. `DefaultTriggerService.persistAndForward` 移除"异步 vs 同步"分支判断,只留 outbox 写入
3. 移除 `OrchestratorClientProperties.baseUrl`(若 trigger 不再有 HTTP 调 orchestrator 的需求)
4. `application.yml` 删除 `BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED` 默认值(强制开启)

> 物理删除前再做一次 24h 验证(disable 开关 → 立即应失败启动 / 异常),确认确实没有任何代码依赖 HTTP 路径。

## 7. 监控告警建议

Prometheus rules 加入:

```yaml
- alert: TriggerOutboxBacklogGrowing
  expr: rate(batch.trigger.launch.consumed.total[5m]) <
        rate(batch.trigger.launch.published.total[5m]) - 5
  for: 10m
  labels:
    severity: warning

- alert: TriggerLaunchFailureSpike
  expr: rate(batch.trigger.launch.failed.total{reason!="rate_limited"}[5m]) > 1
  for: 5m
  labels:
    severity: critical

- alert: TriggerOutboxGiveUp
  expr: increase(batch.trigger.outbox.give_up.total[1h]) > 0
  for: 1m
  labels:
    severity: critical
```

## 8. 已知风险与缓解

| 风险 | 缓解 |
|---|---|
| 双写期 trigger_request 同步桥 + outbox 异步桥并存 | ADR-010 设计已规避——开关只走一条路径,无双轨。回滚靠开关切回,不留双写 |
| Kafka consumer rebalance 抖动 | `max-poll-interval-ms=300000`(5min)留足处理时间;launch 通常 < 1s |
| orchestrator 启动时 consume 历史积压 | `auto-offset-reset=earliest` + `uk_job_instance_tenant_dedup` 兜底重复消息 |
| 低延迟敏感 job 受 200ms relay 间隔影响 | 配置 `batch.trigger.outbox.poll-interval-millis=50` 降到 50ms 换更高 DB 负载;通常不需要 |

## 9. 相关文档

- [ADR-010](../architecture/adr/ADR-010-trigger-async-decoupling.md) — 异步解耦决策与不变量
- [ADR-002](../architecture/adr/ADR-002-transactional-outbox.md) — 本 ADR 复用的事务 outbox 模式
- [`docs/runbook/orchestrator-statefulset-migration.md`](./orchestrator-statefulset-migration.md) — orchestrator 滚动升级模板
