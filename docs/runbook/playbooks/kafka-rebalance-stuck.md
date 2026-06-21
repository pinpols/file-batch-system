# Kafka consumer group lag 飙高 / rebalance 长期停滞

> 优先级 P1 · 最后核对版本:2026-05 · 配套 chaos IT:`KafkaRebalanceStuckChaosIT`(TODO)

## TL;DR

**症状**:某 worker group 的 lag 持续上涨,`kafka-consumer-groups.sh --describe` 显示 `STATE=Rebalancing` 或 `CONSUMER-ID=-`;任务在 Kafka 里积压但没人消费。
**一行修复**:`docker compose restart <stuck-worker-service>`;若 rebalance 反复 → 把 `max.poll.interval.ms` 调大或拆分长消息处理。

---

## 怎么发现

- **Prometheus alert**:TODO(待补 `KafkaConsumerLagHigh`、`KafkaRebalanceTimedOut`)
- **Grafana**:TODO。临时看:
  - `kafka_consumer_records_lag_max` 持续 > 1000
  - `kafka_consumer_coordinator_rebalance_total` 在短时间内陡增 → rebalance 抖
- **日志关键字**(worker / orchestrator 侧):
  - `Member [id] sending LeaveGroup request`
  - `Attempt to heartbeat failed since group is rebalancing`
  - `Offset commit failed on partition ... due to group rebalance`
  - `the group has already rebalanced and assigned the partitions to another member`
- **用户反馈**:任务 dispatch 到 worker 后长时间不动;`job_instance.status=RUNNING` 但 `last_heartbeat_at` 不更新。

---

## 怎么定位

1. **查 lag 实况**
   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server kafka:29092 --list
   # 重点关注 4 个 worker group + orchestrator-trigger-launch:
   #   batch-worker-import / batch-worker-export
   #   batch-worker-process / batch-worker-dispatch
   #   orchestrator-trigger-launch
   docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server kafka:29092 --describe --group batch-worker-import
   ```
   重点列:`LAG`(积压),`CONSUMER-ID`(`-` 表示没人接),`STATE`(`Stable` / `PreparingRebalance` / `CompletingRebalance`)。

2. **确认哪个 topic / partition 卡住**
   - 9 个核心 topic(`docker-compose.yml` → `init-kafka-topics.sh`):
     - `batch.task.dispatch.import` / `.export` / `.process` / `.dispatch`(orchestrator → worker)
     - `batch.task.result`(worker → orchestrator)
     - `batch.task.retry` / `batch.task.dead-letter`
     - `batch.trigger.launch.v1`(trigger → orchestrator)
     - `batch.verifier.failure.v1`
   - LAG 高那条对应的 partition leader 是哪个 broker(单机 dev 只有 broker 1)

3. **是 rebalance 卡,还是没消费者?**
   - 反复 rebalance:同一 group 在短时间内多次 `STATE=Preparing/Completing` → 多半是某 consumer 长时间没回 heartbeat / 超 `max.poll.interval.ms`,被踢出再加入循环
   - 没消费者:`CONSUMER-ID=-` 且 `STATE=Empty` → worker 进程异常退出或没起来,`docker compose ps batch-worker-import` 确认
   - 卡 `CompletingRebalance`:典型 broker 端 `transaction_state` topic 状态机异常,看 `docker logs batch-kafka | grep -i rebalance`

4. **看消费者侧是不是单条消息处理太慢**
   ```bash
   docker compose logs --tail=500 batch-worker-import | grep -E "took [0-9]+ms|TASK_REPORT|poll"
   ```
   单条 process 超 5 min(默认 `max.poll.interval.ms`)就会被踢。

5. **关键决策点**:
   - 单 worker 进程挂 → 方案 A(重启)
   - 反复 rebalance(同一 group 5 分钟内 ≥ 3 次)→ 方案 B(调参 + 排查慢消息)
   - broker 自身故障 / `__consumer_offsets` 损坏 → 方案 C

---

## 怎么恢复

### 方案 A:重启 stuck consumer(2 min)

适用:`CONSUMER-ID=-` 或者某个 worker 实例僵死。

1. 定位是哪个服务:`kafka-consumer-groups.sh --describe` 里 `HOST` 列对得上 worker 容器主机名。
2. 优雅重启(走 Spring graceful shutdown,会等当前 in-flight task 写入数据库 + REPORT)
   ```bash
   docker compose restart batch-worker-import   # 例
   ```
3. 30s 后再 `--describe`,确认 `CONSUMER-ID` 不再是 `-`,`LAG` 开始下降。

### 方案 B:调参 + 排查慢消息(10 min)

适用:rebalance 反复 / lag 缓慢下降但又再涨。

1. **临时**把 group 的 poll 间隔调大(改 worker `application.yml`,需重启):
   ```yaml
   spring:
     kafka:
       consumer:
         properties:
           max.poll.interval.ms: 600000    # 默认 5 min,调到 10 min 容忍长消息
           max.poll.records: 10            # 默认 500,减小批量
   ```
2. **跳过毒消息**(如果某个 offset 反复处理失败拖住整个 partition):
   ```bash
   # 危险操作:跳过当前 offset 一条(只在确认无业务影响时用)
   docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server kafka:29092 --group batch-worker-import \
     --topic batch.task.dispatch.import --reset-offsets --shift-by 1 --execute
   ```
   - **必须先停 worker**:`docker compose stop batch-worker-import`
   - reset 完再 `up -d`
   - 跳过的消息走 dead-letter topic(`batch.task.dead-letter`),后续走 forensic replay 流程(`docs/architecture/forensic-replay.md`,TODO 待 Plan #4)
3. **看 `batch.task.dead-letter` 是不是堆积**:
   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
     --bootstrap-server kafka:29092 --topic batch.task.dead-letter \
     --from-beginning --max-messages 5
   ```

### 方案 C:核武器 — broker 重启 / group 重置(15+ min)

触发条件:broker 自身异常、`__consumer_offsets` 损坏、整个 group 状态机错乱。

1. 停所有 consumer:
   ```bash
   docker compose stop batch-orchestrator batch-trigger \
     batch-worker-import batch-worker-export batch-worker-process batch-worker-dispatch
   ```
2. 重启 broker:`docker compose restart kafka`,等 healthcheck 通过(`docker compose ps kafka` STATUS=healthy)
3. (极端)删 group offset,从最新开始消费 — **会丢未完成任务,只在 dev / 演练环境用**:
   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server kafka:29092 --group batch-worker-import --delete
   ```
4. 起所有 consumer,人工排查 `batch.job_instance` 与 Kafka 失联期间是否有 ghost(状态 RUNNING 但 worker 不知道)→ 走 `CompensationCommand` 走治理接口重置。

---

## 事后

- **写 incident-response 关联本剧本**:`incident-response.md` 表里追加 P2 行(单 group lag 通常不算全平台 P1)。
- **alert 缺失**:必须补 `KafkaConsumerLagHigh{group=...}`、`KafkaRebalanceFrequency`(单位时间内 rebalance 次数)。
- **判断要不要调阈值**:
  - 业务消息处理时长 P99 接近 `max.poll.interval.ms` 默认 5 min → 拆分消息或调大该参数
  - 单 partition 反复出毒消息 → 检查 producer 端序列化,排查上游(`OutboxPollScheduler` 投递的 payload)
- **剧本走不通**:`__consumer_offsets` 真的损坏 → 补 `kafka-offsets-corruption.md`;dead-letter 处理流程明显缺失 → 与 Plan #4 forensic-replay 联动补 `dead-letter-replay.md`。

## 关联

- 代码:`batch-orchestrator/.../application/trigger/TriggerLaunchConsumer.java`,`OrchestratorKafkaConsumerConfiguration.java`,worker 端 `application.yml` 的 `consumer-group-id`
- topic 清单:`scripts/data/init-kafka-topics.sh` + `.env.example` 的 `KAFKA_TOPICS`
- 上一级:[`docs/runbook/incident-response.md`](../incident-response.md)
