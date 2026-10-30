# Worker 本地运行复测与性能分析（2026-09-14）

## 结论

- 应用以本地 JVM 运行，Docker 仅承载 PostgreSQL、Kafka、Kafka UI、MinIO 和 Valkey。
- Worker 严格阶梯测试在 `1/2/4/8` 并发下共创建 60 个实例，Import、Export、Dispatch、
  Process 全部 `SUCCESS`，Gatling 请求失败数为 0。
- 30 秒混合压力共发起 180 个 launch，产生 180 个成功实例；另执行 60 次调度读取，
  240 个 HTTP 请求全部成功，p95 为 64ms，p99 为 102ms。
- Process Worker 故障画像通过：进程硬终止后同一实例恢复成功；PostgreSQL 会话中断后
  当前实例明确失败，同业务键重跑成功，最终 100 万目标行且 staging 无残留。
- 本轮未测到 Worker 容量上限。混合压力下 claim 延迟明显高于执行延迟，继续提高流量时应
  优先观察控制面派发、claim 和数据库事务，不应只调整 Worker 并发数。

## 测试环境

| 项目 | 配置 |
|---|---|
| 源码 | `d6f477397`；本轮应用源码与已构建 JAR 对应源码无差异 |
| 应用 | 8 个 Spring Boot JAR，本地 JVM，`-Xshare:off`，未使用应用 Docker 镜像 |
| 基础设施 | Docker PostgreSQL 17、Kafka 4.1.2、MinIO、Valkey 8.1 |
| JDK | Eclipse Temurin 21.0.12.1, ARM64 |
| 主机 | macOS ARM64，8 logical CPUs |
| 数据库 | 平台库与业务库均使用本机映射端口，业务路径访问主库 |

本报告是单机本地回归证据，不是生产容量承诺。测试期间 1 分钟 load 峰值为 6.96，略高于
项目标准可比基线要求的 6；因此延迟可用于定位瓶颈，不能直接与标准容量报告横向排名。

## 严格阶梯测试

运行入口：

```bash
STRICT=1 STEPS_CSV=1,2,4,8 IMPORT_PROFILE=medium \
  WAIT_TERMINAL_TIMEOUT_SECONDS=300 \
  ./load-tests/scripts/run-worker-stress-tests.sh
```

运行标识：`ltw-stress-local-20260914185331`。每一档都要求 HTTP 失败数为 0、实例全部进入
终态且终态全部为 `SUCCESS`。

| 并发/类型 | Import p95 | Export p95 | Dispatch p95 | Process p95 | 结果 |
|---:|---:|---:|---:|---:|---|
| 1 | 2.574s | 0.994s | 0.620s | 1.244s | 4/4 成功 |
| 2 | 3.547s | 2.348s | 0.782s | 1.265s | 8/8 成功 |
| 4 | 1.399s | 1.006s | 0.598s | 3.353s | 16/16 成功 |
| 8 | 0.959s | 0.903s | 0.454s | 3.430s | 32/32 成功 |

Import/Export 随档位上升反而变快，说明 JVM、SQL 和文件缓存预热对这组小样本影响较大；
这些数字适合验证退化和失败，不适合拟合线性容量。Process 在 4/8 并发下 p95 稳定在约
3.4 秒，是四类业务中最先出现等待增长的路径，但仍无失败或非终态实例。

## 混合压力

有效运行标识：`ctlw-local-mixed-fixed-20260914190412`。

- 30 秒内 Process、Dispatch、Atomic 各 1 launch/s，Trigger Atomic 3 launch/s。
- 调度快照读取 1 user/s，每个 user 执行两个 GET。
- 业务日期基数为 31，避免单一结果版本键把通用吞吐测试退化为同键串行测试。
- 预建 40 个唯一 Dispatch 文件记录，30 个 Dispatch 请求没有复用文件状态。

| 模块 | 实例 | 成功 | 完成 p95 | claim p95 | 执行 p95 |
|---|---:|---:|---:|---:|---:|
| Atomic direct | 30 | 30 | 3.418s | 3.352s | 0.051s |
| Dispatch | 30 | 30 | 3.022s | 2.342s | 0.713s |
| Process aggregate | 30 | 30 | 3.328s | 2.831s | 0.544s |
| Trigger to Atomic | 90 | 90 | 3.412s | 3.381s | 0.042s |

HTTP 共 240 次，失败 0，平均 8 requests/s，p95 64ms、p99 102ms。业务执行 p95 均低于
0.8 秒，而 claim p95 为 2.34 至 3.38 秒，实例完成时间主要由控制面排队、派发和认领决定。
因此下一档性能实验应同步采样 launch Kafka lag、claim/report 数据库等待和 Worker semaphore，
不能仅根据执行器耗时扩大线程池。

PostgreSQL 在约 67 秒观测窗口内：

| 指标 | 增量 |
|---|---:|
| transaction commits | 7,440 |
| transaction rollbacks | 0 |
| WAL bytes | 35,417,366 |
| database size | 3,366,912 bytes |
| lock waiters | 0 |
| active waiting connections | 0 |
| `wal_buffers_full` | 0 |
| requested checkpoints | 0 |

Kafka 相关 Worker 与 Trigger consumer group 的结束快照 lag 均为 0。上述负载没有触发数据库
锁等待或 Kafka 积压，但流量仅为 6 launch/s，不能外推到项目 10 万任务容量基线。

## 故障恢复

运行入口：

```bash
PROCESS_SOURCE_ROWS=1000000 PROCESS_ACCOUNT_COUNT=100000 \
  LOCAL_FAST_JVM_OPTS=-Xshare:off \
  ./load-tests/scripts/run-p2-process-failure-profile.sh
```

有效运行标识：`p2-process-local-fixed-20260914191051`。

| 场景 | 当前实例 | 恢复动作 | 结果 |
|---|---|---|---|
| Worker 在 `RUNNING` 时 `SIGKILL` | 约 53.959s 后 `SUCCESS` | 重启本地 JVM，等待 lease 回收 | 同实例成功 |
| 终止活跃 PostgreSQL backend | 2.767s 后 `FAILED/INFRA_ERROR` | 不伪装业务成功 | 状态符合预期 |
| 相同 batch key 重跑 | 10.943s 后 `SUCCESS` | 新请求重跑 | 100 万行成功 |

最终 `process_event_copy_rows=1000000`、`process_staging_rows=0`，证明本轮没有留下中间表残留。
本地硬终止恢复时间约 54 秒，主要受 lease 失效与重新认领周期约束。

## 复测发现并修复的问题

1. `MAX_ERROR_PCT=0` 原来生成百分比 `< 0` 断言，即使零失败也必然失败；零阈值改为失败数等于 0。
2. Worker 压测缺少真实 Import/Export 模板和 Dispatch channel，且 Dispatch 并发复用文件；
   夹具现自包含配置并按虚拟用户分配唯一文件。
3. 终态等待原来只判断“已终态”，可能把 `FAILED` 当通过；严格入口现在同时要求全量 `SUCCESS`。
4. 多轮压测共享 `target/worker-load-data/run.env`，并发验证会覆盖 payload；现按 `RUN_ID` 隔离目录。
5. 并发造数执行 `setval(max(id))` 可能回拨共享序列；业务库和平台库准备事务增加 advisory lock，
   PROCESS event ID 由 `RUN_ID` 稳定派生。双运行并发造数、断言和清理已通过。
6. 混合压测未注入 Dispatch `fileId`，且只看到部分终态实例就提前成功；现按流量准备文件、
   计算期望 launch 数并校验每个 trigger request 关联的实例全部成功。
7. Process 故障脚本重启 JVM 时未执行本地依赖地址转换，容器服务名 `redis` 被传给宿主机；
   重启前现调用公共本地 JVM 环境转换函数。

## 仍需目标环境验证

- Kubernetes 多副本资源池的 Pod kill、节点迁移、CPU throttling、OOM 和租户公平性。
- 真实 S3/OSS、SFTP、NAS、邮件和 HTTP 下游的限速、断链、超时、重试及 ACK 延迟。
- PostgreSQL 主备切换、Kafka broker 不可用、DLQ 重放、PITR 和 RTO/RPO 演练。
- 持续数小时 soak、接近生产大小的文件以及更高阶梯的容量拐点测试。

这些场景依赖目标网络、存储或编排环境，本地 Docker 基础设施不能提供等价结论。
