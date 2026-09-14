# 运行参数调优审查与压测计划（2026-09-14）

## 1. 审查结论

本报告审查 JVM、PostgreSQL、Valkey/Redis、Kafka、MinIO 五类运行参数，覆盖本地 Compose、
隔离 benchmark profile、生产 Helm/HA 清单和已有压测证据。

当前结论：**可靠性参数和资源硬边界基本形成，生产性能参数尚未完成目标硬件标定**。现有本地
10 万控制面结果可以证明主链稳定性和本机容量，不能直接换算为生产容量承诺。

| 组件 | 无需压测的边界 | 已有性能证据 | 生产标定状态 | 结论 |
|---|---|---|---|---|
| JVM | 已收口 | G1 本地运行有证据 | G1/ZGC 尚未同口径 A/B | 部分完成 |
| PostgreSQL | 已收口 | 10 万任务 WAL/checkpoint A/B | 需在目标存储复验 | 基本完成 |
| Valkey/Redis | 已收口 | 主链使用稳定 | 内存水位、AOF、failover 未压满 | 部分完成 |
| Kafka | 可靠性已收口 | 10 万任务最终 lag=0 | RF=3 故障与 producer 批量矩阵待测 | 基本完成 |
| MinIO | 资源、EC、生命周期已收口 | 真实 MinIO 大文件已有证据 | 真实生产 S3/分布式 MinIO 待测 | 部分完成 |

### 1.1 上线判断

- **可以保留**：Kafka `acks=all`/幂等、PG `synchronous_commit=on`、Redis `noeviction`、MinIO
  EC:4、对象 checksum、所有超时和资源上限。
- **不能直接复制本机值**：连接池、Worker 并发、Kafka 分区数、PG WAL/checkpoint、JVM heap/direct
  memory、S3 multipart 并发。
- **当前优先级**：先做 JVM G1/ZGC A/B 和生产同等级 PG 存储复验；Kafka 当前无持续 lag，不应先调。

## 2. 审查基线与边界

- 审查日期：2026-09-14。
- Git 起始基线：`origin/main@373960065`；最终实现以本报告对应 PR 的合并提交为准。
- 状态边界：报告包含本轮 JVM GC 冲突修复、Compose 资源边界和 Redis HA 参数。
- 本地容量环境：历史有效基线为 Docker Desktop 8 CPU、约 7.75 GiB；生产清单是独立 K8s 资源模型。
- 本报告是参数审查和执行计划，不是新一轮压测结果。已有结果引用
  [`../verifications/control-plane-100k-throughput-optimization-2026-09-12.md`](../verifications/control-plane-100k-throughput-optimization-2026-09-12.md)。

必须区分三套配置：

| 环境 | 用途 | 是否可作为生产容量证据 |
|---|---|---|
| Compose 默认 | 开发、SIM、功能和基础故障验证 | 否 |
| `deploy/docker/compose/benchmark.yml` | 隔离容量画像，允许放大并发和批量 | 仅本机可比 |
| Helm + `deploy/ha/*` | 生产部署目标 | 需在同等级环境实测 |

## 3. 参数现状

### 3.1 JVM

公共 Helm 参数只负责容器感知、内存边界和取证，不再选择 GC：

- `MaxRAMPercentage=75`、`InitialRAMPercentage=50`。
- direct memory 默认 512 MiB，Import/Export/Process/Dispatch 为 1 GiB。
- metaspace 256 MiB、NMT summary、OOM dump、OOM 退出、滚动 GC 日志、持续 JFR。
- 普通服务显式 G1；Orchestrator 显式 `UseZGC + ZGenerational`。

已修复的缺陷：公共层曾启用 G1，Orchestrator 又追加 ZGC，JDK 21 会以
`Multiple garbage collectors selected` 拒绝启动。当前门禁会检查默认、生产、本地 K8s、重任务池
四套合并配置，并要求每个服务恰好选择一种 GC。

**尚未证明**：Orchestrator ZGC 比 G1 更优。历史 10 万压测主要运行 G1，因此 ZGC 当前只是合法候选，
不是已验收的性能结论。

### 3.2 PostgreSQL

Compose 当前边界：

| 参数 | 当前值 |
|---|---:|
| 容器内存上限 | 3 GiB/实例 |
| `max_connections` | 300 |
| `work_mem` | 4 MiB（PG 默认） |
| `maintenance_work_mem` | 64 MiB（PG 默认） |
| `max_wal_size` | 1 GiB |
| `checkpoint_timeout` | 5 min |
| `checkpoint_completion_target` | 0.9 |
| `synchronous_commit` | on |
| `wal_compression` | off |
| `track_io_timing` | off（按需画像） |

应用侧已启用 `reWriteBatchedInserts=true`，并统一配置连接生命周期、validation timeout、会话级
statement timeout、idle-in-transaction timeout 和 `ApplicationName`。benchmark profile 将 Trigger
准入固定为 32、平台池 40、预留 8；Orchestrator 每实例平台池 50。

生产 HA 候选为 4 GiB Pod、`shared_buffers=1GiB`、`effective_cache_size=3GiB`、
`max_wal_size=16GiB`、`checkpoint_timeout=15min`、`work_mem=4MiB`。这些是部署候选，不是已在生产
磁盘验收的最终值。

已有 A/B 结论：本机 `8GiB/15min` 相比 `1GiB/5min` 消除了 requested checkpoint 并减少 WAL，
但吞吐从约 152.168/s 降到 143.551/s，因此没有提升为默认值。该结论证明 WAL 参数必须按目标存储
实测，不能凭经验放大。

### 3.3 Valkey/Redis

Compose 当前配置：

- 容器上限 512 MiB，`maxmemory=384MiB`，保留 25% 给连接缓冲、allocator、AOF/复制。
- `maxmemory-policy=noeviction`：达到上限时显式失败，不能静默淘汰 ShedLock、quota 等正确性键。
- AOF 开启，`appendfsync=everysec`；保留现有 RDB 周期快照。
- Lettuce 命令超时 10s、shutdown 200ms，连接池 `32/16/4`，获取连接最多等待 1s。

生产 HA 清单为 1 master + 2 replica + 3 sentinel，每个 Redis Pod 上限 1 GiB、数据上限 768 MiB，
同样使用 `noeviction` 和 AOF everysec。

现有 `BatchRedisMemoryUsageHigh` 告警在 `maxmemory>0` 后才真正有效。尚需验证 70%-90% 水位下延迟、
AOF rewrite 峰值、主从切换期间 quota/ShedLock/SSE 的行为。

风险：`deploy/ha/30-redis-failover.yaml` 依赖 Spotahome Redis Operator；
[上游仓库](https://github.com/spotahome/redis-operator)已归档。参数清单可以继续作为当前部署契约，
但正式投产前应选定受维护的 Operator 或托管 Redis，并执行迁移/failover 演练。

### 3.4 Kafka

应用侧可靠性参数已明确：

- producer：`acks=all`、幂等开启、`max.in.flight=5`、delivery timeout 30s、request timeout 10s、
  buffer 64 MiB、buffer 满最多阻塞 5s。
- consumer：手工立即确认、earliest、session 45s、heartbeat 15s、max poll 300s、
  CooperativeStickyAssignor。
- 本地默认单 broker、3 分区、RF=1，只用于开发。
- benchmark：launch 12 分区、Atomic dispatch 24 分区、Atomic listener 24、`max.poll.records=5`。
- 生产：3 broker、RF=3、`min.insync.replicas=2`，内部 offset/transaction topic 同样 RF=3。

已有 10 万结果中 launch 12/12 分区有流量，最终 lag=0，Kafka 零重启。因此当前证据不支持把 Kafka
列为首要瓶颈。`linger.ms`、`batch.size`、compression 和 broker I/O/thread 参数必须在真实消息体、
RF=3 和故障条件下比较后再改。

### 3.5 MinIO/S3

- Compose MinIO 内存上限 1 GiB；生产 Tenant 为 4 server x 4 volume、EC:4，每 Pod 4 GiB limit。
- S3 客户端 connect/read timeout 为 5s/30s。
- 64 MiB 起启用 multipart，part 默认 16 MiB。
- 错误文件、导入 staging、导出 draft 和 dispatch archive 已有生命周期策略。
- 对象精确长度、bounded read、checksum/manifest 和 multipart abort 已有代码保护。

已有证据：845 MiB Import 对象 spool 约 17s、约 50 MiB/s；800 MiB 级文件处理时 Worker RSS 低于
200 MiB，证明流式设计成立。该历史轮次使用 Serial GC，不能作为当前 G1 或生产 S3 的吞吐基准。

尚缺：真实外部 S3/OSS、分布式 MinIO、8/16/32 并发、不同 part size，以及限速、连接重置和单节点
故障时的 multipart 重试/清理证据。

## 4. 已有容量结论

最新有效 10 万控制面基线：

| 指标 | 结果 |
|---|---:|
| Trigger 请求 | 100000/100000，0 失败 |
| HTTP p95/p99/max | 90ms / 175ms / 996ms |
| Job 终态 | 100000 SUCCESS，0 非终态 |
| 完成窗口 | 657.167s |
| 端到端完成吞吐 | 152.168 tasks/s |
| Kafka | 最终 lag=0，零重启 |
| 清理 | run-scoped 关键表无残留 |

该结果证明可接收 200 requests/s 洪峰并以约 152 tasks/s 排空，不代表能持续完成 200 tasks/s。
主要约束是 Orchestrator 控制面事务与 PostgreSQL WAL/提交；Kafka、Atomic 执行和 HTTP Service 入口
不是该轮主瓶颈。双 Orchestrator task-control 直连曾降至 96.494/s，说明增加写入入口可能放大共享
PG 竞争，不能把多实例数量直接换算为吞吐。

## 5. 压测实施顺序

### 阶段 0：冻结可比基线

1. 使用干净 runner、固定 Git SHA 和同 SHA 镜像；工作树必须无未提交运行代码。
2. 固定 CPU、内存、磁盘类型、Docker/K8s 版本和所有依赖镜像 digest。
3. 每组先预热，再执行至少 3 轮，报告中位数和离散度；单轮最快值不能作为结论。
4. 发压前要求所有容器 healthy、零残留任务、Kafka lag=0、主机 `load1/CPU <= 0.75`。
5. 一轮只调整一类变量；正确性、持久性和安全参数不得作为吞吐旋钮。

### 阶段 1：JVM A/B（P0）

固定 PG/Kafka/并发，只比较 Orchestrator：

| 组 | GC | heap/direct | 目的 |
|---|---|---|---|
| A | G1 | 同一容器 limit 和百分比 | 复现当前有效基线 |
| B | Generational ZGC | 与 A 相同 | 比较尾延迟和 CPU 代价 |

先跑 1 万筛选，再跑 10 万确认。采集完成吞吐、HTTP/任务 p95/p99、CPU、RSS、allocation rate、GC
暂停总量/最大值、Safepoint、JFR。只有 B 显著改善尾延迟且吞吐回退在可接受范围内，才保留 ZGC；否则
Orchestrator 回到显式 G1。

Worker 另做大文件 heap/direct memory 验证，不能用 Atomic 小任务替代。关注 native RSS 与 direct OOM，
不要先扩大 1 GiB direct memory。

### 阶段 2：PostgreSQL 存储矩阵（P0）

在生产同等级 SSD/云盘执行，保持 `synchronous_commit=on`：

1. 基线 `1GiB/5min`。
2. 中间档 `4GiB/10min`。
3. HA 清单候选 `16GiB/15min`。

采集 TPS、完成吞吐、WAL bytes/task、wal write/sync、requested/timed checkpoint、fsync 延迟、磁盘
IOPS/吞吐/queue depth、CPU、active wait、锁等待、连接池等待、autovacuum 和死元组。随后才单独评估
`shared_buffers/effective_cache_size`；`work_mem` 必须按并发算总预算，禁止全局盲目放大。

### 阶段 3：Redis 容量与故障（P1）

1. 10 万控制面 + SSE replay/quota/ShedLock 混合负载，观察 50%/70%/80%/90% 内存水位。
2. 负载中触发 AOF rewrite，采集 RSS 峰值、fork 延迟和命令 p99。
3. kill master，验证 Sentinel 切换、连接恢复、quota fail-closed 和调度锁不重复执行。
4. 写到 `maxmemory`，确认明确拒绝且告警触发，不发生关键键淘汰。

`noeviction` 不做 A/B；如果缓存增长影响正确性服务，应拆缓存实例，而不是改成 LRU。

### 阶段 4：Kafka RF=3（P1）

先验证 3 broker 基线和滚动宕 1 broker，再做 producer 小矩阵：默认值、`linger=5ms + lz4`、
`linger=10ms + lz4`。每组固定 batch size 或单独成轮，避免多个变量同时变化。

采集 producer record queue/request latency、batch size、broker CPU/磁盘/network、ISR、under-replicated
partition、rebalance 时间和 consumer lag。验收要求零消息丢失、重复由既有幂等路径消化、最终 lag=0。

### 阶段 5：MinIO/S3（P1）

按对象大小 `1MiB/64MiB/1GiB/10GiB`、并发 `1/4/8/16`、part `8/16/32/64MiB` 做矩阵；
分别在分布式 MinIO和真实目标 S3/OSS执行。采集首字节、总耗时、吞吐、CPU/RSS、连接数、重试、
abort 数、残留 multipart 和 checksum。必须加入单节点故障、连接 reset、限速和磁盘接近水位场景。

### 阶段 6：五类 Worker 与混压（P1）

1. 五类 Worker 单独找稳定并发，不先混压。
2. Import/Export/Process/Dispatch/Atomic 按生产占比混压。
3. dry-run 与正式任务并发，正式任务 SLA 和幂等键必须保持隔离。
4. 10 万稳定后才进入 100 万容量测试；100 万必须使用独占 runner，不能在开发机边工作边测。

## 6. 统一验收条件

- HTTP 错误率 0；若业务设计允许限流，429 必须在独立过载实验中统计，不能混入容量通过轮次。
- 全部实例进入预期终态，无 `CREATED/READY/RUNNING` 残留。
- Kafka 最终 lag=0、无消息丢失、无 broker/应用/数据库意外重启。
- 无连接池耗尽、Redis 关键键淘汰、multipart 永久残留、checksum 不一致。
- PG 锁等待不发散，WAL/checkpoint 和磁盘水位有明确余量。
- 清理后 run-scoped 数据、对象和结果版本无残留。
- 报告必须记录 Git SHA、镜像 revision、全部参数、资源 limit、硬件、预检负载和原始证据路径。

## 7. 禁止以性能名义调整

- 不关闭 PG `synchronous_commit`，除非业务正式接受扩大 RPO，并单独走架构审批。
- 不降低 Kafka `acks=all`、RF=3、`min.insync.replicas=2` 或关闭 producer idempotence。
- 不把 Redis 改为会淘汰正确性键的 LRU/LFU；缓存压力应通过 TTL、容量或实例拆分解决。
- 不降低 MinIO EC parity、checksum、精确长度校验和 manifest 完整性检查。
- 不通过无限连接池、无限队列、无限内存或提高超时掩盖背压。
- 不用本地单 broker、单盘 MinIO 或共享开发机结果宣称生产 HA/容量达标。

## 8. 后续交付物

| 优先级 | 交付物 | 完成判据 |
|---|---|---|
| P0 | JVM G1/ZGC A/B 报告 | 1 万筛选 + 10 万三轮，GC/JFR 证据完整 |
| P0 | PG 目标存储矩阵 | WAL/checkpoint/IO/吞吐三轮中位数 |
| P1 | Redis 水位与 failover 报告 | AOF rewrite、写满、主切换均通过 |
| P1 | Kafka RF=3 故障与 producer 矩阵 | 宕 1 broker 零丢、最终 lag=0 |
| P1 | MinIO/S3 大小文件矩阵 | multipart 故障无残留、checksum 一致 |
| P1 | 五类 Worker 生产占比混压 | 全终态、正式任务 SLA 不受演练污染 |

完成以上 P0 后，才可以冻结第一版生产参数；完成 P1 后，才能形成完整的生产容量与扩容曲线。
