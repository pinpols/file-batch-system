# 批量系统可扩展性与高可用评估

> 初版分析：2026-03-26 | 更新：2026-03-26（6 项改造全部完成）
> 覆盖模块：batch-orchestrator、batch-worker-core、batch-worker-import、batch-worker-export、batch-worker-dispatch、batch-trigger。

---

## 总体评分

| 维度 | 改造前 | 改造后 | 评分 |
|------|--------|--------|:----:|
| Worker 水平扩展 | 基本支持（Claim 幂等） | Semaphore 背压 + 并发配置 | ★★★★★ |
| Orchestrator HA | 不支持多实例 | version CAS 乐观锁 | ★★★★☆ |
| Kafka 吞吐调优 | 走 Spring 默认 | concurrency / max-poll-records 显式配置 | ★★★★☆ |
| 连接池隔离 | HikariCP 默认，未隔离 | 各角色独立 pool，按负载定容 | ★★★★☆ |
| 优雅关闭 | 停消费不等 in-flight | awaitDrain + 可配超时 | ★★★★☆ |
| 限流 / 背压 | 无 | Semaphore + container pause/resume | ★★★★☆ |
| 临时文件治理 | 手动删除，无回退 | 启动时清理孤儿文件 | ★★★★☆ |

---

## 一、Worker 层：Kafka 并发 + Semaphore 背压 ✅

### 已完成

**Kafka 消费并发（Task 2）**

三类 worker 的 `application.yml` 均已显式配置：

```yaml
spring:
  kafka:
    listener:
      concurrency: 4            # import/export/dispatch 各自按负载设置
      ack-mode: manual_immediate
    consumer:
      max-poll-records: 5       # import/export=5，dispatch=10
      fetch-min-size: 1
      fetch-max-wait: 500
```

**Semaphore 背压（Task 5）**

`AbstractTaskConsumer` 引入 `Semaphore(maxConcurrentTasks)`，permits 耗尽时自动 `container.pause()`，任务完成后 `semaphore.release()` + `container.resume()`：

- import：`max-concurrent-tasks=6`
- export：`max-concurrent-tasks=4`
- dispatch：`max-concurrent-tasks=8`

配合 HikariCP pool 上限，形成 DB 连接 → 并发任务数的双重防护。

> Kafka topic partition 数需 ≥ (实例数 × concurrency)，否则多余线程空转。

---

## 二、Orchestrator HA：version CAS 乐观锁 ✅

### 已完成

`job_instance`、`job_partition`、`job_task` 三张表均已有 `version BIGINT DEFAULT 0`（V4 建表时已存在），对应 MyBatis mapper 的所有状态转换 UPDATE 均加入 CAS 保护：

```sql
UPDATE batch.job_partition
SET    status  = 'RUNNING',
       version = version + 1,
       updated_at = now()
WHERE  id      = :id
  AND  version = :expectedVersion
  AND  status  = 'READY';
-- affected rows = 0 → 被其他实例抢占，静默放弃，不抛异常
```

`JobPartitionMapper`、`JobInstanceMapper`、`JobTaskMapper` 的状态推进方法均返回 `int`，调用方判断 `rowsAffected == 0` 时直接 return。

**当前限制**：乐观锁解决了状态竞态，但 Orchestrator 多实例下调度触发器（cron/event）仍可能重复触发分区规划。若需完全避免，可后续迁移至 Quartz JDBC 集群模式（`QRTZ_*` 表已存在，工程成本低）。

---

## 三、连接池：各角色独立配置 ✅

### 已完成

`BusinessDataSourceConfiguration`（import/export）改造为 `@ConfigurationProperties("batch.datasource.business.hikari")` 绑定 `HikariConfig`，各模块 `application.yml` 按负载定容：

| 模块 | Platform DB pool | Business DB pool |
|------|:----------------:|:----------------:|
| orchestrator | max=10, idle=3, timeout=3s | 无 |
| import worker | max=5, idle=2, timeout=3s | max=15, idle=3 |
| export worker | max=5, idle=2, timeout=3s | max=20, idle=5, leak=60s |
| dispatch worker | max=10, idle=3, timeout=3s | 无（dispatch 不用业务库）|

所有参数均支持环境变量覆盖（`${BATCH_WORKER_*_DB_MAX_POOL_SIZE:默认值}`），生产可按实际负载调整。

---

## 四、优雅关闭：awaitDrain ✅

### 已完成

`ActiveTaskLeaseRegistry` 新增 `awaitDrain(Duration timeout)`：每 500ms 检查活跃 lease 是否清空，超时后 log.warn 并返回（不强杀）。

`GracefulKafkaShutdown` 改造为：
1. `registry.stop()` — 停止拉取新任务
2. `activeTaskLeaseRegistry.awaitDrain(Duration.ofSeconds(gracefulShutdownTimeoutSeconds))` — 等 in-flight 任务完成

超时时间通过 `batch.worker.graceful-shutdown.timeout-seconds`（默认 120）配置。

---

## 五、背压机制：Semaphore ✅

见 §一，与 Kafka 并发配置一同落地。

---

## 六、临时文件补充清理 ✅

### 已完成

`batch-worker-core` 新增 `StaleTempFileCleanup` 组件，监听 `ApplicationReadyEvent`，启动时扫描 `java.io.tmpdir` 下超过 `batch.worker.stale-temp-file-hours`（默认 6h）的 `batch-*` 前缀文件并删除，记录清理数量到日志。

---

## 优先级汇总（全部完成）

| # | 问题 | 状态 | 关键证据 |
|---|------|:----:|---------|
| 1 | Orchestrator 无分布式锁 | ✅ | `JobPartitionMapper.xml` / `JobInstanceMapper.xml` / `JobTaskMapper.xml` version CAS |
| 2 | Kafka concurrency 未配置 | ✅ | 三个 worker `application.yml` |
| 3 | 连接池默认值且未隔离 | ✅ | `BusinessDataSourceConfiguration` + `application.yml` |
| 4 | 优雅关闭不等 in-flight | ✅ | `ActiveTaskLeaseRegistry.awaitDrain` + `GracefulKafkaShutdown` |
| 5 | 无背压 | ✅ | `AbstractTaskConsumer` Semaphore + pause/resume |
| 6 | 临时文件无补充清理 | ✅ | `StaleTempFileCleanup` |

> **结论**：Worker 可安全水平扩展，Orchestrator 可多实例部署（乐观锁保护状态竞态）。
> 如需进一步消除触发器重复调度风险，可迁移 Quartz JDBC 集群模式（低成本，`QRTZ_*` 表已存在）。
