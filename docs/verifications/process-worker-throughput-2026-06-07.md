# Process Worker Throughput Report - 2026-06-07

> 范围: process worker 成功路径,真实系统链路触发
> 主 RUN_ID: `prcw-20260607215904`
> 自动报告: `load-tests/target/process-worker-report-prcw-20260607215904.md`

## 结论

1. `lt_process_sql_job` 聚合路径可用: 1000w source rows -> 10w summary rows,端到端 40.966s。
2. `lt_process_copy_job` 直通复制路径功能成功,但性能不可接受: 1000w source rows -> 1000w target rows,端到端 867.606s。
3. 瓶颈不是 trigger/orchestrator/Kafka,而是 process worker 当前 JSONB staging 设计: 先把 1000w 行写入 `batch.process_staging.payload jsonb`,再从 JSONB 反序列化写回业务表。
4. 1000w copy 初次暴露两个配置问题,已修:
   - SQL transform 60s timeout 不适合大批量 process copy,默认放宽到 900s。
   - Kafka `max.poll.interval.ms=300000` 会在长任务中触发 rebalance 和同 task 重投,process worker 默认放宽到 1200000ms。
5. 后续不要继续靠单纯调大 timeout 硬扛 copy 场景。P1 应做 typed staging / direct insert-select / 分片 copy 方案,再重测。

## 执行命令

```bash
SKIP_AUTO_CLEANUP=1 \
PROCESS_SOURCE_ROWS=10000000 \
PROCESS_ACCOUNT_COUNT=100000 \
PROCESS_SCENARIOS=aggregate,copy \
PROCESS_USERS=1 \
WAIT_TERMINAL_TIMEOUT_SECONDS=3600 \
KAFKA_LAG_GROUP_REGEX='batch-worker-process-bench-20260607215802|orchestrator-trigger-launch' \
bash load-tests/scripts/run-process-worker-benchmark.sh
```

本轮 worker 使用临时 consumer group `batch-worker-process-bench-20260607215802`,并设置 `auto.offset.reset=latest`,用于隔离历史失败消息。

## 关键结果

| Job | 输入行 | 输出行 | 端到端 | 结果 |
|---|---:|---:|---:|---|
| `lt_process_sql_job` | 10,000,000 | 100,000 | 40.966s | SUCCESS |
| `lt_process_copy_job` | 10,000,000 | 10,000,000 | 867.606s | SUCCESS |

阶段耗时:

| Job | 阶段 | 耗时 | 估算吞吐 |
|---|---|---:|---:|
| `lt_process_sql_job` | COMPUTE | 35.280s | 283,446.7 input rows/s |
| `lt_process_sql_job` | COMMIT | 1.860s | 53,763.4 output rows/s |
| `lt_process_copy_job` | COMPUTE | 440.191s | 22,717.4 rows/s |
| `lt_process_copy_job` | VALIDATE | 29.310s | - |
| `lt_process_copy_job` | COMMIT | 348.704s | 28,677.6 rows/s |
| `lt_process_copy_job` | FEEDBACK | 44.577s | - |

资源变化:

| 指标 | Before | After | 观察 |
|---|---:|---:|---|
| `biz.process_event_copy` size | 243 MB | 1,732 MB | 1000w 目标行写入成功 |
| WAL bytes | 56 GB | 75 GB | 本轮增加约 19 GB |
| Postgres primary Block IO | 72.6GB / 159GB | 113GB / 210GB | IO 写放大明显 |
| Kafka lag | 0 | 0 | Kafka 不是瓶颈 |

## 发现的问题

### P0: timeout 与 max.poll 不匹配

1000w copy 首轮在 60s 被 PostgreSQL statement timeout 取消。修复后又暴露 Kafka `max.poll.interval.ms=300000` 不够,长任务超过 5 分钟会触发 consumer rebalance,同一 task 被重投,出现并发执行同 batchKey 的风险。

已修:

- `batch.worker.process.sql-transform.query-timeout-seconds` 默认 900s。
- local profile 同步改为可配置默认 900s。
- process worker `spring.kafka.consumer.max-poll-interval-ms` 默认 1200000ms。
- local profile `BATCH_WORKER_PROCESS_CONSUMER_GROUP_ID` 可覆盖,便于隔离 benchmark group。
- `KafkaConsumerTriangleValidator` 改为优先读取 Boot 标准 `spring.kafka.consumer.max-poll-interval-ms`,避免审计日志误报 300000。

### P0: 本地构建脚本可能复制旧 exec jar

本轮发现 `scripts/local/restart.sh BUILD=1` 会优先复制 `target/*-exec.jar`;如果 exec jar 是历史残留,运行包可能不是最新代码。已修为构建前删除旧 exec jar,构建后必须找到新的 exec jar,不再 fallback 到普通小 jar。

### P1: process copy 不适合继续走 JSONB staging

copy 场景耗时 867.606s 的主因是双写放大:

- COMPUTE: 1000w 行业务数据 -> `batch.process_staging.payload jsonb`,440.191s。
- COMMIT: `jsonb_populate_record` -> `biz.process_event_copy`,348.704s。
- FEEDBACK cleanup: 44.577s。

建议下一轮只做 P1 方案验证,不要继续调 timeout:

- typed staging: 为 copy 类 process 生成 typed temp/unlogged staging 表,避免 JSONB payload。
- direct insert-select: 对无跨阶段校验需求的纯 copy,允许 `INSERT INTO target SELECT ... ON CONFLICT ...` 直接发布。
- shard/chunk execution: 按 account/event_id range 拆分多个 task,避免单 task 长事务和大事务 WAL 峰值。
- cleanup 批量化: 大 batchKey staging 清理应避免单 DELETE 长扫。

## 已清理

`RUN_ID=prcw-20260607215904 bash load-tests/scripts/cleanup-worker-load-data.sh` 已删除本轮 1000w source、1000w copy target、10w summary 和相关平台元数据。

## 未覆盖

| 项 | 是否能做 | 本轮不做原因 |
|---|---|---|
| 故障注入恢复 | 能 | 成功路径容量基线先完成;故障恢复应单独跑 |
| typed staging/direct insert-select POC | 能 | 需要改实现,本轮先用 baseline 证明瓶颈 |
| 多分片 process copy | 能 | 需要 pipeline/task 拆分方案,不应在单 task JSONB staging 上继续压 |
| PG session 参数矩阵 | 能 | 当前瓶颈已足够明确,先做结构性优化再调 session 参数 |
