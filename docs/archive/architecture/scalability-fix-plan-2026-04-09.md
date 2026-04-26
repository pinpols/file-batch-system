# 可扩展性 & 高可用改造计划

> 对应评估文档：`scalability-ha-assessment.md`
> 状态：**6 / 6 已完成**（2026-03-26）

---

## 完成状态总览

| Task | 内容 | 状态 | 完成日期 |
|------|------|:----:|---------|
| 1 | Orchestrator 乐观锁（version CAS） | ✅ | 2026-03-26 |
| 2 | Kafka 消费并发配置 | ✅ | 2026-03-26 |
| 3 | 连接池显式配置与隔离 | ✅ | 2026-03-26 |
| 4 | 优雅关闭等待 in-flight 任务 | ✅ | 2026-03-26 |
| 5 | Kafka 消费背压（Semaphore） | ✅ | 2026-03-26 |
| 6 | 临时文件兜底清理 | ✅ | 2026-03-26 |

---

## Task 1 — Orchestrator 乐观锁 ✅

**关键证据**
- `version BIGINT DEFAULT 0` 已在 `V4__create_runtime_tables.sql` 建表时存在，无需额外迁移
- `JobPartitionMapper.xml`、`JobInstanceMapper.xml`、`JobTaskMapper.xml`：所有状态转换 UPDATE 均含 `AND version = #{expectedVersion}` + `SET version = version + 1`
- Mapper 方法返回 `int`（affected rows），调用方判断 0 则静默放弃，不抛异常

**残余风险**：乐观锁解决状态竞态，但 cron 触发器在多实例下仍可能重复规划分区。后续可迁移 Quartz JDBC 集群模式（`QRTZ_*` 表已存在，工程成本低）。

---

## Task 2 — Kafka 消费并发配置 ✅

**关键证据**（三个 worker `application.yml`）

| 配置项 | import | export | dispatch |
|--------|:------:|:------:|:--------:|
| `listener.concurrency` | 4 | 4 | 4 |
| `listener.ack-mode` | manual_immediate | manual_immediate | manual_immediate |
| `consumer.max-poll-records` | 5 | 5 | 10 |
| `consumer.fetch-min-size` | 1 | 1 | 1 |
| `consumer.fetch-max-wait` | 500 | 500 | 500 |

所有参数支持环境变量覆盖（`${BATCH_WORKER_*_KAFKA_CONCURRENCY:4}`）。

---

## Task 3 — 连接池显式配置与隔离 ✅

**关键证据**

- `BatchDataSourceConfiguration`（import/export）：`@ConfigurationProperties("batch.datasource.business.hikari")` 绑定 `HikariConfig`，`new HikariDataSource(config)` 建池
- 各模块 `application.yml` 按角色定容：

| 模块 | Platform pool (max) | Business pool (max) |
|------|:-------------------:|:-------------------:|
| orchestrator | 10 | — |
| import | 5 | 15 |
| export | 5 | 20（含 leak-detection） |
| dispatch | 10 | — |

---

## Task 4 — 优雅关闭等待 in-flight ✅

**关键证据**
- `ActiveTaskLeaseRegistry.awaitDrain(Duration)`: 每 500ms 轮询 `snapshot().isEmpty()`，超时后 log.warn 返回
- `GracefulKafkaShutdown`: `registry.stop()` → `activeTaskLeaseRegistry.awaitDrain(...)`
- 超时时间：`batch.worker.graceful-shutdown.timeout-seconds`（默认 120s）
- 测试：`ActiveTaskLeaseRegistryTest`（awaitDrain 正常返回 + 超时返回两个场景）

---

## Task 5 — Kafka 消费背压 ✅

**关键证据**
- `AbstractTaskConsumer`: `Semaphore(maxConcurrentTasks)` 控制实例内并发上限
- permits 耗尽 → `container.pause()`；任务完成（finally 块）→ `semaphore.release()` + `container.resume()`
- 各 worker 默认值：import=6 / export=4 / dispatch=8（`batch.worker.max-concurrent-tasks`）
- 测试：`AbstractTaskConsumerBackpressureTest`

---

## Task 6 — 临时文件兜底清理 ✅

**关键证据**
- `StaleTempFileCleanup`（`batch-worker-core/support/`）：`@EventListener(ApplicationReadyEvent.class)`
- 扫描 `java.io.tmpdir` 下 `batch-*` 前缀、超过 `batch.worker.stale-temp-file-hours`（默认 6h）的文件
- 删除失败单条 log.warn，不影响启动
- 测试：`StaleTempFileCleanupTest`

---

## 后续可选项（非紧急）

| 项目 | 价值 | 成本 |
|------|------|------|
| Quartz JDBC 集群模式 | 彻底消除触发器重复规划分区的风险 | 低（`QRTZ_*` 表已存在） |
| Kafka lag 驱动 HPA | 比 CPU 指标更精准的 Worker 自动扩缩容 | 中（需 KEDA 或自定义 metrics） |
| 分渠道 dispatch worker 池 | SFTP/API/EMAIL 互不干扰 | 中（拆配置或拆进程） |
