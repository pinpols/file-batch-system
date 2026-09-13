# 控制面 10 万任务吞吐优化与验证报告（2026-09-12）

## 结论

在同一套本地 Docker 基础环境、2 个 Orchestrator、1 个 Atomic Worker 和真实 PostgreSQL/Kafka
链路上，严格 10 万任务画像最终通过：

| 指标 | 最终结果 |
|---|---:|
| Trigger 请求 | 100000/100000 成功，0 失败 |
| 入口速率 | 200 requests/s |
| HTTP p95 / p99 / max | 67ms / 162ms / 1971ms |
| Job 终态 | 100000 SUCCESS，0 FAILED，0 非终态 |
| 完成窗口 | 741.036s |
| 端到端完成吞吐 | 134.946 tasks/s |
| 端到端平均 / p95 | 211.919s / 289.528s |
| Kafka launch 分区 | 12/12 有流量，最终 lag=0 |
| Atomic dispatch 分区 | 24 个消费者分区最终 lag=0 |
| Kafka 重启 | 0 次 |
| 压测数据残留 | result_version/job_instance/trigger_request 均为 0 |

这证明当前单机环境可以稳定接收 200 requests/s 的 10 万任务洪峰，并以约 135 tasks/s 排空。
入口速率高于完成速率时会形成可控 backlog，因此 200/s 不能表述为持续完成吞吐或生产容量承诺。

在上述 10 万稳定基线之后，又完成了 Worker 双 Orchestrator 端点和结果版本终态写入热路径优化。
同键 1 万任务热态复测达到 `136.517 tasks/s`，10000/10000 成功、零失败、零非终态；该轮用于验证
热点优化效果，不替代上表的 10 万容量验收。后续四轮 10 万 A/B 没有证明这两项改动能提高同键
极限吞吐：最好一轮为 `131.468 tasks/s`，仍比上表低约 2.6%。进一步把结果业务键扩为 100 个的
10 万轮次也只有 `119.898 tasks/s`。因此当前可信的 10 万基线仍是 `134.946 tasks/s`，没有把较小规模
结果或负向实验外推成容量提升。

原始报告位于本机：

- `load-tests/target/p2-capacity-profile-throughput-100k-200rps-final-20260912.md`
- `load-tests/target/control-plane-worker-report-throughput-100k-200rps-final-20260912-10w.md`

## 环境与口径

- Docker Desktop：8 CPU、约 7.75 GiB 内存。
- 基础设施：PostgreSQL 17、Kafka 4.1.2、Valkey 8.1、MinIO。
- 应用：Trigger 1 实例、Orchestrator 2 实例、Atomic Worker 1 实例。
- 业务负载：`atomic_sql_demo`，每个请求独立创建 trigger request、job instance、partition 和 task，
  通过真实 Kafka 派发、Worker claim/execute/report 和 PostgreSQL 终态推进。
- 严格判据：请求零错误、请求数与实例数精确相等、全部实例终态、无失败、Kafka 无重启且最终
  lag 为 0、12 个 launch 分区实际有流量、清理后无 run-scoped 残留。

本报告中的 `effective_completed_per_second` 使用第一个 trigger request 创建时间到最后一个实例完成时间，
不是只统计已经进入 Orchestrator 的实例耗时。旧报告没有该口径时，只能用整个运行窗口做趋势对比。

## 已落地优化

### 1. 消除串行分发瓶颈

- Trigger launch topic 扩为 12 分区，2 个 Orchestrator 各 6 个消费者。
- 压测脚本校验 consumer group 实际持有 12 个分区，并按前后 offset 证明 12/12 分区有流量。
- 解决 Kafka 在线扩分区后 producer/consumer 元数据仍停留在旧 3 分区的问题：容量复验要求生产者和
  全部消费者完成重载，脚本拒绝用 3 分区结果冒充 12 分区结果。

最终 Launch 分工为 50257/49743，接近 50/50；各分区消息数为 8224-8501，最大差异约 3.3%。

### 2. 批量 claim 与 Worker 并发

- Atomic Worker 使用 24 个消费者、24 个 dispatch 分区、`max.poll.records=5`。
- 批量 claim 共 20090 次取得 100000 个任务，有效批大小 4.98。
- 相比逐任务 claim，HTTP claim 调用量减少约 79.9%。
- benchmark overlay 将任务许可、注册容量和执行池统一为 480，等待任务扫描改为每次 1000 条、1 秒间隔。

上述值只属于容量画像，不修改普通本地或生产默认容量。生产值必须按数据库连接预算、Worker 类型和
资源隔离重新标定。

### 3. Trigger 入口预算

- benchmark profile 使用 API admission 80、平台库连接池 88、后台保留 8、Relay 400/s。
- 240 并发轮次出现 186 次队列拒绝，64 并发轮次出现 18 次队列拒绝，均被判无效并舍弃。
- 80/88 是本机 200 RPS 下第一个满足零请求错误的预算，不应直接复制为生产值。

### 4. 热路径日志降噪

以下逐任务成功日志从 INFO 降为 DEBUG，失败和拒绝仍保留 WARN/ERROR：

- Worker 单任务/批任务成功结果。
- 本地共享租户的 worker selection/claim fallback 成功结果。

这避免 10 万任务产生约 20 万条低价值 INFO 日志。定向单测覆盖 Worker consumer、批执行协调器、
任务认领和 Worker 选择逻辑：Orchestrator 33 项、Worker 30 项，共 63 项测试通过。

### 5. 压测数据生命周期

原清理脚本只删除 job instance，没有删除无外键回收的 `result_version`。连续压测后本地已累积
800835 条 `p2capacity` 孤儿版本，持续放大唯一索引维护成本并污染轮次对比。

现已按 run-scoped job instance 先删除 `asset_partition` 指针，再删除 `result_version`。使用 100 个
任务做了造数和自动清理回归，随后 10 万任务清理实际删除 100000 条结果版本；最终三个关键表均为 0。

### 6. 容量画像超时隔离

演示作业原有 300 秒硬超时会把容量 backlog 误判为业务执行超时。首轮 10 万在 300 秒后出现 2 个
`JobInstanceTimeoutEnforcer` 失败，已判无效，没有进入最终结论。

容量画像现在为隔离租户显式写入 `CAPACITY_JOB_TIMEOUT_SECONDS`，默认 3600 秒，并要求该值不小于
“流量持续时间 + 终态等待预算”。生产和普通演示作业的超时语义、默认值均未改变。

### 7. PostgreSQL 压力观测修正

原 `waiting_connections` 把 idle session 的 `ClientRead` 也计入等待。现改为仅统计
`state='active' AND wait_event IS NOT NULL` 的活动等待会话，`lock_waiters` 继续独立统计，避免把连接池
常驻空闲连接误判成数据库锁压力。

### 8. Worker 任务控制端点高可用

- Worker 支持配置多个 Orchestrator task-control base URL，按请求轮转；请求失败后的既有重试会自然切换
  到下一端点，单 URL 配置继续兼容。
- 本地双实例 1 万任务复测中，claim items 为 5056/4944，report calls 为 4984/5016，证明任务控制流量
  已实际分布到两台 Orchestrator，而不只是 Trigger launch 消费均衡。
- 该能力用于 VM、裸机和本地直连场景的高可用与分流。Kubernetes 仍优先配置一个 Service URL，避免在
  应用层重复实现服务发现。

### 9. 结果版本终态写入热路径

压测新增 `BIZ_DATE_CARDINALITY`：默认值 1 保留同一 `jobCode + bizDate` 的并发重跑热点；大于 1 时
轮换 bizDate，用于区分单一结果版本链锁竞争和通用控制面负载。使用 100 个 bizDate 的 1 万轮次为
`111.626 tasks/s`，虽然 claim 延迟下降，但冷批量日初始化使 launch 变慢，因此不能与预热后的同键轮次
直接比较，也不能据此宣称吞吐提升。

同键 1000 任务期间对 `pg_stat_activity` 做 9 次采样，累计观察到 141 个活动会话等待
`pg_advisory_xact_lock(hashtext(tenant_id), hashtext(business_key))`，显著高于其他等待。该锁用于保证
`result_version.version_no` 单调、同一业务键只有一个 EFFECTIVE 版本，以及 `asset_partition` 不被乱序
终态覆盖，不能删除或绕过。

本轮在不放松正确性约束的前提下缩短锁内事务：

- `result_version` 写入改为 PostgreSQL `INSERT ... RETURNING`，直接取得持久化实体，消除插入后的重复回查。
- 对不可变的 `(tenantId, assetCode, assetType) -> data_asset.id` 建立 10 万条、1 小时访问过期的有界缓存；
  数据库唯一键仍是最终权威，首次访问仍执行安全 upsert 和查询。
- 保留 advisory lock、EFFECTIVE 状态切换、资产分区单调 CAS 和全部租户条件。

刚重启后的冷态 1 万轮次为 `99.030 tasks/s`，入口前 10 秒有 JIT/连接池预热积压，不纳入优化对比。
同参数热态复跑结果如下：

| 指标 | 优化前双端点 | 优化后热态 | 变化 |
|---|---:|---:|---:|
| 完成吞吐 | 116.523/s | 136.517/s | +17.2% |
| 完成窗口 | 85.820s | 73.251s | -14.6% |
| 端到端 p95 | 42.991s | 33.635s | -21.8% |
| claim delay p95 | 37.510s | 29.804s | -20.5% |

优化后吞吐也比此前 1 万干净基线 `134.367/s` 高约 1.6%，说明本轮没有以正确性换性能。原始报告：

- `load-tests/target/p2-capacity-profile-result-version-hotpath-warm-10k-20260912.md`
- `load-tests/target/control-plane-worker-report-result-version-hotpath-warm-10k-20260912-10w.md`

### 10. 10 万 A/B、压测账本清理与负向实验

继续按同一 `100000 requests @ 200 RPS` 口径复测后，发现压测夹具自身存在长期状态污染：
`job_instance_dedup_key` 和 `outbox_event_dedup_key` 没有随 run-scoped 数据清理。专用租户一度分别累积
约 118 万行和 117 万行历史键，后跑轮次需要维护更大的唯一索引，不能直接与早期轮次比较。

现已补齐两层守护：

- 清理事务在源业务行尚存在时，按 `(tenant_id, dedup_key, run_attempt)` 和
  `(tenant_id, event_key)` 精确删除本轮账本，不按时间或租户模糊清理。
- 隔离容量画像在造数前要求 job instance、trigger request、outbox、result version 和两张幂等账本
  全部为 0；发现上一轮残留就拒绝启动。

使用 100 个任务完成真实造数、终态和自动清理回归，两张账本各精确删除 100 行；之后每轮 10 万清理
均精确删除 10 万行，最终六类运行数据计数均为 0。

清理后的端点和限流 A/B 如下：

| 轮次 | task-control 端点 | report 限流 | 完成窗口 | 完成吞吐 | launch queue 平均 / p95 | 结论 |
|---|---|---:|---:|---:|---:|---|
| 热路径优化后，历史账本未清 | 双端点 | 12000/min | 874.019s | 114.414/s | 30.748s / 77.429s | 状态污染，不作为基线 |
| 清账本后 | 双端点 | 12000/min | 813.407s | 122.940/s | 10.081s / 26.315s | 较上一轮回升 7.5%，但受 autovacuum 并发影响 |
| 清账本、单端点 | 单端点 | 12000/min | 760.639s | 131.468/s | 4.271s / 11.342s | 比双端点高 6.9%，仍低于既有基线 2.6% |
| 清账本、单端点、限流实验 | 单端点 | 30000/min | 775.283s | 128.985/s | 26.893s / 41.855s | 429 清零但总吞吐下降，参数舍弃 |

单端点 12000/min 轮次出现 2698 次 `TASK_REPORT` 429 重试，说明入口停止后的合法排空峰值超过
200 reports/s。实验将隔离 benchmark 提高到 30000/min 后，尾部一度达到约 400 reports/s，10 万任务
仍全部成功且没有 429；但更激进的 report 写入与 launch 在同一 PostgreSQL 上竞争，使 launch queue
显著上升，最终吞吐反而比单端点 12000/min 低约 1.9%。因此没有合入该参数，也没有修改普通/生产
默认的 12000/min 安全水位。

这组证据还否定了“只要把 Worker HTTP 流量均摊到两台 Orchestrator 就会提高总吞吐”的假设。双端点
确实接近 50/50 分流，但两台实例共享同一 PostgreSQL 和同键结果版本锁，总吞吐没有随 HTTP 入口扩展。
下一步应优化共享数据库事务与真实业务键分散场景，而不是继续增加 Orchestrator HTTP 端点或放宽限流。

本节原始报告：

- `load-tests/target/p2-capacity-profile-result-version-hotpath-warm-100k-20260912.md`
- `load-tests/target/p2-capacity-profile-result-version-hotpath-clean-ledger-100k-20260912.md`
- `load-tests/target/p2-capacity-profile-rvhot-single-100k-0912.md`
- `load-tests/target/p2-capacity-profile-rvhot-rl30k-100k-0912.md`

### 11. 真实业务键分散场景复验

为验证同一 `jobCode + bizDate` 的结果版本锁是否仍是 10 万规模主瓶颈，新增一轮
`100000 requests @ 200 RPS`、100 个轮换 bizDate 的严格容量画像。运行前已清空隔离租户数据，并对控制面
热表执行 `VACUUM (ANALYZE)`；运行后相关表 `n_dead_tup=0`，排除了历史账本和死元组污染。

| 指标 | 结果 |
|---|---:|
| Trigger 请求 | 100000/100000 成功，0 失败 |
| HTTP p95 / p99 / max | 135ms / 720ms / 4007ms |
| Job 终态 | 100000 SUCCESS，0 FAILED，0 非终态 |
| 完成窗口 | 834.045s |
| 完成吞吐 | 119.898 tasks/s |
| launch queue 平均 / p95 | 208.479s / 320.023s |
| claim delay 平均 / p95 | 0.752s / 1.731s |
| Worker 执行平均 / p95 | 0.228s / 0.569s |
| report 429 | 0 |
| Kafka 重启 / 最终 lag | 0 / 0 |

该轮两个 Orchestrator 分别处理 49731/50269 个 launch，任务 claim 和 report 也接近 50/50，说明实例间
分工有效；但吞吐仍比既有严格基线低约 11.2%，也比清账本后的同键单端点轮次低约 8.8%。业务键分散
没有带来容量提升，因此结果版本 advisory lock 不是当前 10 万持续负载的主导约束。

PostgreSQL 5 秒采样峰值为 98 个 active session、95 个 active waiting session；采样窗口内约产生
184 万次事务提交和 7.39 GB WAL。压测过程中主要活动等待为 WAL 写入/同步及提交，锁等待接近 0。
与此同时 Worker claim 与执行 p95 均低于 2 秒。证据指向 launch 建模、任务终态写入和结果落库共享
PostgreSQL 时的提交/WAL 竞争，而不是 Worker、Kafka 分区或单一业务键锁。

当前本地 PostgreSQL 未预加载 `pg_stat_statements`，`track_io_timing` 也为 `off`，因此本轮结论只定位到
数据库提交/WAL 层，尚不能把耗时可靠归因到某一条 SQL。没有基于不完整采样直接修改事务主链。

本轮原始报告：

- `load-tests/target/p2-capacity-profile-rvhot-card100-100k-0912.md`
- `load-tests/target/control-plane-worker-report-rvhot-card100-100k-0912-10w.md`

## 2026-09-13 PostgreSQL SQL/WAL 画像与复验

### 1. 10 万 SQL 画像结论

在相同的 2 个 Orchestrator、1 个 Atomic Worker、`100000 requests @ 200 RPS`、100 个轮换 bizDate
口径下，开启 `pg_stat_statements.track=top` 和 `track_io_timing=on` 完成了全链路画像：

| 指标 | 结果 |
|---|---:|
| Trigger 请求 | 100000/100000，0 失败，HTTP p95 613ms |
| Job 终态 | 100000 SUCCESS，0 FAILED，0 非终态 |
| 完成窗口 / 吞吐 | 1829.607s / 54.657 tasks/s |
| launch queue 平均 / p95 | 721.600s / 1268.481s |
| 事务提交增量 | 1893494，约 18.9 次/task |
| WAL 增量 | 6743511380 bytes，约 67.4 KiB/task |
| requested checkpoint | 12 次 |
| Kafka | 12/12 launch 分区有流量，最终 lag=0，0 次重启 |

累计耗时最高的 SQL 集中在分区终态推进、`trigger_outbox`/`trigger_request` 插入、实例 T1/T2 状态推进
和结果/outbox 幂等账本写入。Worker 执行 p95 为 3.641 秒，Kafka 无积压，主要约束仍是 Orchestrator
控制面事务与 PostgreSQL WAL/提交链路。该画像用于定位 SQL，不作为无观测容量基线。

画像还发现启动恢复查询在 10 万积压下平均耗时 3.365 秒。已将 `ACCEPTED` 请求与实例的关联从
`tenant_id + dedup_key` 改为真实来源键 `job_instance.trigger_request_id = trigger_request.id`，并新增前向
迁移 `V205__trigger_request_stale_accepted_index.sql`。本地执行计划使用部分索引
`idx_trigger_request_stale_accepted`，测试候选的执行时间为 1.204ms；定向
`TriggerRequestLaunchReconcilerTest` 5 项通过。该修复降低恢复扫描成本，不改变 launch T1/T2 事务边界。

### 2. A/B 边界与回退判断

最初将 `CAPACITY_PG_STATEMENTS_PROFILE_ENABLED=0` 误解为关闭 SQL 跟踪；实际上它只停止重置和导出，
数据库仍保持 `pg_stat_statements.track=top`。该轮 10 万虽然 100000/100000 成功，但完成吞吐只有
49.070 tasks/s，不能作为“无 pg_stat_statements”对照。

后续使用角色级设置让连接池重连，分别完成了 1 万小矩阵：

| 轮次 | SQL track / I/O timing | 完成吞吐 | HTTP p95 | 判定 |
|---|---|---:|---:|---|
| 画像口径 | top / on | 49.554/s | 3781ms | 有效 SQL 画像 |
| 关闭 SQL track | none / on | 62.661/s | 6778ms | 只隔离 SQL 采集，入口 SLO 未过 |
| 关闭 SQL track 与 I/O timing | none / off | 67.651/s | 2640ms | 参数生效，但主机负载污染 |

最后一轮比历史 100 业务键 1 万结果 111.626/s 仍低约 39.4%。同期主机 1 分钟 load 一度为 16.92，
8 个逻辑 CPU；`mediaanalysisd` 约占 91% CPU，Docker VM 约占 62% CPU，且空闲内存很低。因此当前证据
只能确认“该时段本机完成吞吐低于历史”，不能证明 2026-09-12 后的代码产生性能回归，也不能据此回退
正确性或可观测性改动。需要在主机负载受控后复跑同一 1 万矩阵；1 万恢复到历史容差范围后才允许再跑
10 万无画像基线。

`wal_compression=lz4` 的 1 万单轮将 WAL 从 464977881 bytes 降至 425726507 bytes（约 8.4%），吞吐
从 49.554/s 升至 54.854/s，但 HTTP p95 升至 5859ms 并超过门槛。单轮结果不稳定，参数已恢复为
`off`，不进入默认配置。

### 3. task report 主键回查消除

10 万 SQL 画像中，`job_task` 按租户和主键读取累计约 30 万次。其中每个成功 task report 在终态 CAS
更新后都会固定回查一次完整任务行，占 10 万次数据库往返。当前将该 CAS 改为仓库既有的 PostgreSQL
`UPDATE ... RETURNING` 映射：更新命中时直接返回终态行，未命中仍以 `null` 表示状态或版本冲突。

该改动没有调整租户、前置状态、版本号谓词、事务边界或同步提交语义，也不减少终态 UPDATE 的 WAL；
它只确定性消除 10 万任务口径下的 10 万次主键 SELECT。服务测试 13 项通过，真实 PostgreSQL 的并发
CAS、重复 report 和崩溃恢复测试 4 项通过。吞吐收益必须等宿主机负载恢复且环境签名一致后，以同一
100 业务键 1 万 A/B 复验确认；在此之前不宣称性能提升。

### 4. 压测环境硬约束

容量脚本现已在发压前校验并记录以下口径：

- P2 画像脚本显式限定为仓库 `local-docker` benchmark 拓扑；远程、staging、生产探测使用独立 Gatling
  profile，不与本机容量基线混跑。
- 无画像容量基线默认要求 `pg_stat_statements.track=none`、`track_io_timing=off`；SQL 画像默认要求
  `top/on`。仅关闭报告开关不再被视为无观测。
- 默认要求 `synchronous_commit=on`、`wal_compression=off`、`max_wal_size=1GiB`、
  `checkpoint_timeout=300s`；实验参数必须显式覆盖期望值并单独命名 run。
- 记录 Git SHA、数据库观测参数、主机 CPU 数和 load；Docker CPU/内存/架构、Engine/Compose 版本和
  被测容器内存上限组成环境签名；运行中 PostgreSQL 压力采样同步记录主机 1 分钟 load。
- 发压前使用两级主机负载判据：默认执行门槛为 load/CPU 不超过 2.5（8 核即 `load1 <= 20`），只决定
  是否允许稳定性或过载取证继续；标准基线可比门槛仍为 load/CPU 不超过 0.75（8 核即 `load1 <= 6`）。
  提高执行门槛不改变性能结论资格。运行中的 load 包含被测系统自身产生的有效压力，因此记录峰值用于
  归因，不将运行期峰值错误地作为发压前硬失败条件。
- 历史本机基线要求 Docker 为 8 CPU、7.5-9 GiB；被测容器必须健康并设置非零内存上限，PostgreSQL
  数据卷至少保留 20 GiB。换机器可通过显式容量等级建立新基线，但环境签名不同的结果不得直接比较。
- 隔离租户六类运行数据必须为 0，Kafka launch lag 必须清零，容器预算和分区数必须与 benchmark profile
  一致；任何不一致均不得进入容量对比表。

### 5. 宿主机回稳复验与生成键回传优化

在应用镜像、Docker 容量等级、PostgreSQL 持久性参数和业务键基数均不变的前提下，完成两轮
`10000 requests @ 200 RPS` 复验。第二轮入口和终态均为 `10000/10000`、零失败，完成窗口
`111.634s`，吞吐 `89.578 tasks/s`；相较首轮 `79.244 tasks/s` 回升约 13%。两轮 WAL 均约
`412MB`，比 2026-09-12 历史轮次约 `576MB` 少 28.5%，且无 requested checkpoint，锁等待峰值
分别为 1 和 3。当前吞吐仍低于历史 `111.626 tasks/s`，差距集中在 launch T1/T2；同期 macOS
后台存储扫描和 Docker VM 持续占用 CPU，因此不能把差距归因于 task report 改动。

SQL 画像同时确认 MyBatis 的 `useGeneratedKeys=true` 在未指定生成列时会让 PostgreSQL JDBC 使用
`RETURNING *`。这会使 `trigger_request`、`trigger_outbox_event` 以及 job/partition/task/step 等热路径
插入把完整行重新传回 JVM，JSONB payload 和运行快照也被无意义解码。现统一声明
`keyColumn="id"`，保留原有实体主键回填和多行顺序回填语义，只把返回列收窄为 `id`；该优化不改变
INSERT 数据、事务边界、WAL、CAS、幂等约束或 Outbox 原子性。静态门禁阻止后续 mapper 再引入无列名
generated keys，真 PostgreSQL 生成键 IT 继续验证单行和批量回填。

复验原始证据：

- `load-tests/target/p2-capacity-profile-task-report-returning-card100-10k-warm2-20260913.md`
- `load-tests/target/p2-capacity-profile-task-report-returning-card100-10k-warm3-20260913.md`
- `load-tests/target/control-plane-worker-report-task-report-returning-card100-10k-warm3-20260913-10w.md`

本轮原始报告：

- `load-tests/target/p2-capacity-profile-pgprofile-stabilized-card100-100k-20260913.md`
- `load-tests/target/control-plane-worker-report-pgprofile-stabilized-card100-100k-20260913-10w.md`
- `load-tests/target/p2-capacity-profile-stabilized-card100-100k-nopgss-20260913.md`
- `load-tests/target/p2-capacity-profile-tracknone-iooff-card100-10k-20260913.md`

### 6. 最新 main 热表回查验证与高负载负向轮次

在最新主分支 `9b0c876671e31543e98cb509e1a27328c53a1b5c` 上重新构建 17 个 Maven 模块和
Trigger、双 Orchestrator、Atomic Worker 镜像；Full Gate `34745088389` 通过。先以
`1000 requests @ 100 RPS`、100 个业务键开启 `pg_stat_statements.track=top` 与
`track_io_timing=on` 做调用次数验证：

| 热表主键读取 | 旧 10 万画像 | 最新 1 千画像 | 每任务变化 |
|---|---:|---:|---:|
| `job_instance` | 400000（4.0/task） | 2000（2.0/task） | -2.0 |
| `job_task` | 300000（3.0/task） | 2000（2.0/task） | -1.0 |

两项优化合计确定性减少 3 次热表主键读取/任务。`job_task` 剩余两次读取分别服务于认领前的状态、版本和
路由校验，以及回报前的 RUNNING、worker 归属和 partition invocation fence 校验；继续合并会把简单
CAS 改造成跨表更新并扩大正确性风险，本轮明确不做。画像轮 1000/1000 全部 SUCCESS；画像开启后
完成吞吐仅 `12.331 tasks/s`，且运行期 `load1` 峰值 17.90，因此只用于调用次数证明，不用于吞吐对比。

同一最新代码的无画像、低负载 1 万轮次 `tor10k-w1-0913` 为 10000/10000 SUCCESS、零请求失败、零非终态，
完成窗口 `103.006s`，完成吞吐 `97.081 tasks/s`；预检 load 样本为
`4.15,3.81,3.91,3.76,3.94`，具备标准基线可比性。该结果比同日较早的 `89.578 tasks/s` 高约 8.4%，
但仍低于历史热态最佳值，不能宣称已经恢复到历史峰值。

把执行门槛放宽后又执行两轮高负载取证。`tor10k-w2-0913` 在预检样本部分超过可比上限后继续执行，
服务端接收并完成 9549/9549，但出现 425 个连接提前关闭和 26 个 HTTP 429，完成吞吐仅
`30.824 tasks/s`。`tor10k-w3-0913` 预检 load 为
`8.68,8.47,7.95,7.63,7.66`，运行期峰值 40.81；Trigger 约 248% CPU 且触达 768 MiB 容器内存上限，
PostgreSQL 约 295% CPU。最终服务端接收并完成 9814/9814，全部 SUCCESS、Kafka lag 归零、所有容器
零重启，但 10000 个目标请求中有 163 个连接提前关闭、23 个 admission 拒绝，另有客户端超时；该轮
完成吞吐 `25.109 tasks/s`，按规则判为无效饱和轮次。提高执行门槛只让负向实验能完整取证，并没有
提高系统容量，也不得用来绕过基线可比性和零错误门槛。

切换 SQL 画像参数时必须先重建 PostgreSQL 主从并等待角色稳定，再重启所有持有数据库连接池的应用。
如果同时重建主从后不重启应用，Docker DNS/IP 复用可能让旧连接目标落到只读副本，表现为
`cannot execute UPDATE in a read-only transaction`。正确顺序为“数据库主从健康且
`pg_is_in_recovery()` 分别为 false/true -> 重建应用连接池 -> 检查最近日志无只读事务错误 -> 发压”。
这属于本地 Docker 运维顺序约束，不是业务层主从自动降级机制。

本节原始证据：

- `load-tests/target/p2-capacity-profile-torpg1kb-0913.md`
- `load-tests/target/p2-capacity-profile-tor10k-w1-0913.md`
- `load-tests/target/p2-capacity-profile-tor10k-w2-0913.md`
- `load-tests/target/p2-capacity-profile-tor10k-w3-0913.md`

### 7. Report 往返、Trigger 并发预算与 outbox 写放大收口

SQL 画像显示成功 report 会重复读取 task、partition 和 terminal instance。当前将 task 与 partition
持久化上下文合并为一次查询，并让分区终态 CAS 在同一 SQL 中返回更新后的 job instance；重试、虚拟
task 和 CAS miss 仍走原有回退路径。改动不调整租户谓词、invocation fence、事务边界、终态聚合或
补偿语义。Orchestrator 编译通过，真实 PostgreSQL 的 `WorkerClaimProgressCompleteIntegrationTest`
2 项通过。

复核历史基线发现，`134.946 tasks/s` 严格轮次使用 Trigger admission `32`、平台连接池 `40`。后续将
该容量预算提高到 `80/88` 后，在同一 8 核共享 PostgreSQL 上出现更高的 WAL/连接争用，launch T1/T2
平均耗时由历史十几至二十余毫秒放大到近百至二百余毫秒，入口队列最终产生 429 或连接提前关闭。
benchmark profile 已恢复为 `32/40`，并保留 8 条数据库连接给 relay、健康检查和管理路径。该裁定只
影响隔离容量 profile，不改变 local/prod 默认值；后续不得以“并发值更大”为理由直接放大连接池，必须
通过同容量等级 A/B 证明吞吐收益。

同时确认 `outbox_event.payload_json` GIN 索引在仓库运行时查询中没有消费路径，实库父子索引扫描次数为
0；反复容量测试后，空的当月 outbox 分区仍占 91 MiB，其中 GIN 子索引占 79 MiB。V208 前向迁移删除
该父索引及已挂接子索引，并兼容删除旧非分区索引；event key、aggregate 和 publish status 等实际查询
使用的 B-tree 索引全部保留。本地删除索引并执行普通 `VACUUM (ANALYZE)` 后，当月空分区降为约
4.1 MiB，热表死元组归零，空闲 PostgreSQL CPU 从约 42% 回落到约 5%。容量脚本对应的手工分区迁移
也不再创建该 GIN 索引。

在宿主机仍高于可比门槛的情况下，完成 `1000 requests @ 100 RPS` 无回归轮次：入口和终态均为
`1000/1000`、HTTP p95 `354ms`、任务执行 p95 `1.935s`、Kafka 最终 lag 为 0、所有容器零重启，完成
吞吐 `51.186 tasks/s`。该轮预检 load 为 `7.88-9.17`，只证明主链正确，不作为历史吞吐对比。当前
性能基线仍是 `134.946 tasks/s`；只有宿主机连续预检 `load1 <= 6` 后的同口径 1 万三轮中位数，才可
用于判断本轮优化是否提升峰值。

最终提交 `178e56527` 重新构建并部署后又执行了预热和严格轮次。预热轮次在预检 load
`7.74-7.83` 下接收并完成 `990/990`，10 个拒绝全部由 admission queue full 触发；严格 1 万轮次的
预检样本为 `5.84,5.70,5.56,6.15,5.98`，其中一个样本超过可比门槛，因此脚本正确标记为不可比较。
运行期宿主机 load1 峰值达到 `22.77`，现场采样为 `0% idle / 46.61% system CPU`；Trigger 使用
`720.1/768 MiB`，G1 young GC 暂停累计 `17.343s`、单次最大 `1.706s`，PostgreSQL 同时有 5 个会话
等待 `WALWrite`。入口最终返回 3056 个成功响应、2992 个 429、3819 个客户端超时和 133 个连接提前
关闭；客户端超时后仍有请求完成落库，服务端实际创建的 `4789/4789` 个实例全部进入 SUCCESS，Kafka
lag 归零、应用与基础容器均无重启。该轮完成吞吐 `21.302 tasks/s` 只是共享宿主机饱和时的稳定性证据，
既不能证明本次 SQL 优化提升，也不能据此判定为代码性能回退。不得通过提高 admission、连接池或
可比负载阈值把该轮包装成有效容量结果。

本节原始证据：

- `load-tests/target/p2-capacity-profile-report-roundtrip-card100-dual-10k-20260913.md`
- `load-tests/target/p2-capacity-profile-t32-rpt-10k-0913.md`
- `load-tests/target/p2-capacity-profile-gin0-t32-10k.md`
- `load-tests/target/p2-capacity-profile-opt-1k-100.md`
- `load-tests/target/p2-capacity-profile-opt-final-warmup-1k-0913.md`
- `load-tests/target/p2-capacity-profile-opt-final-strict-10k-0913.md`

## 对比结果

| 轮次 | 可信度 | HTTP 结果 | 端到端完成吞吐 | 结论 |
|---|---|---|---:|---|
| 1 万，12 分区、旧等待扫描预算 | 有效 | 10000/10000 | 15.434/s | 等待扫描/并发许可是主瓶颈 |
| 1 万，容量预算完成后 | 有效 | 10000/10000，p95 332ms | 128.714/s | 首次达到稳定高吞吐 |
| 1 万，清理与日志优化后 | 有效 | 10000/10000，p95 559ms | 134.367/s | 较上一有效基线提升约 4.4% |
| 1 万，Worker 双端点，优化前 | 有效 | 10000/10000，零失败 | 116.523/s | claim/report 已双实例均衡，但共享 PG 热点仍在 |
| 1 万，结果版本热路径优化后（热态） | 有效 | 10000/10000，p95 1112ms | 136.517/s | 保留正确性锁，完成窗口较上一行缩短 14.6% |
| 1 万，最新 main、低负载复验 | 有效 | 10000/10000，p95 858ms | 97.081/s | 比同日较早轮次回升 8.4%，尚未恢复历史热态峰值 |
| 1 万，最新 main、高负载取证 | 无效负向实验 | 9814/10000 入库，入口超时/拒绝 | 25.109/s | 运行期 load1 40.81，证明提高执行门槛不等于提高容量 |
| 1 万，最终 report 优化 SHA、高负载取证 | 无效负向实验 | 4789/4789 入库实例成功，入口超时/拒绝 | 21.302/s | 预检未满足可比门槛，运行期 0% CPU idle、GC 与 WALWrite 同时放大，不用于判断性能增减 |
| 10 万，旧 3 分区缓存状态 | 趋势参考 | 100000/100000 | 约 117/s | 拓扑证据无效，不作为最终验收 |
| 10 万，最终严格轮次 | 有效 | 100000/100000，p95 67ms | 134.946/s | 全终态、零失败、零残留 |
| 10 万，热路径优化、清账本、单端点 | 有效 | 100000/100000，零失败 | 131.468/s | 未超过最终严格基线，不宣称提升 |
| 10 万，report 限流 30000/min | 负向实验 | 100000/100000，p95 128ms | 128.985/s | 429 清零但 PG 竞争上升，参数不保留 |
| 10 万，100 个业务键、双端点 | 有效负向实验 | 100000/100000，p95 135ms | 119.898/s | 锁分散后仍退化，主瓶颈转为 PG 提交/WAL 竞争 |

从有真实 12 分区证据的旧 1 万轮次到当前 1 万轮次，完成吞吐约提升 8.7 倍。旧 10 万轮次受 3 分区
元数据缓存影响，只能作为趋势参考；与其运行窗口相比，最终轮次约提升 15%、完成窗口缩短约 13%。

## 当前瓶颈与边界

1. PostgreSQL 仍是当前主要容量约束。持续阶段观测到 PostgreSQL 使用约 2.6-3.0 核；入口停止后，
   backlog 排空速度明显提高，说明入口建模写入与 claim/report/终态写入仍竞争共享数据库资源。
2. 同一结果业务键的终态必须经过 advisory lock 串行化。这是结果版本正确性边界；100 个业务键的
   10 万复验没有提升吞吐，说明该锁会影响同键修正风暴，但不是当前持续负载的主导容量约束。
3. Worker 多端点已经证明 claim/report 可在双 Orchestrator 间接近 50/50，但清账本后的双端点轮次
   只有 `122.940/s`，低于单端点的 `131.468/s`。单 Orchestrator HTTP 入口不是主瓶颈，双实例反而会
   增加共享 PG 的并发竞争；多端点应作为高可用能力，不作为当前单库吞吐参数。
4. Kubernetes Service 可在连接层分发请求，但长连接复用可能产生粘连。上线前仍应在真实 Service、多个
   Worker 实例下验证分布，不根据本地应用层轮转结果推断生产网络行为。
5. Orchestrator 已有 report-batch API，但当前仍逐项开启独立事务；Worker 执行包装器也会在单任务完成时
   立即 report。直接接入需要重构 lease、invocation fence、report outbox、部分失败回退和终态时序。
   本轮放宽 report 限流没有提高总吞吐，现有证据不支持承担该改造风险。
6. Launch 的 T1/T2 独立提交承载崩溃恢复与 Outbox 原子性。把两段简单合并为一个长事务会扩大锁持有时间，
   并破坏 T1 已提交、T2 可恢复的故障语义，不作为性能优化方案。
7. 当前 134.946/s 是本机 Atomic SQL 场景的 10 万完成吞吐，不代表 Import/Export/Process 或真实外部 HTTP、
   SFTP、对象存储场景的容量。

## 后续建议

SQL 画像和两项低风险主键回查优化已经完成。当前没有证据支持继续提高 Trigger admission，也不支持把
认领/回报前剩余的 task 读取强行并入复杂跨表 UPDATE。下一次可信容量推进应在安静宿主机或独占 runner
上完成同口径 1 万三轮复验并取中位数；恢复到历史容差后，再运行 10 万标准基线。主机预检超过可比门槛
时只做故障与稳定性取证，不继续消耗数十分钟生成不可比较的吞吐数字。

后续可单独 A/B PostgreSQL `max_wal_size`、`checkpoint_timeout` 和存储 I/O，但必须保持
`synchronous_commit=on`，记录恢复时间与磁盘余量，并在实验结束后恢复基线。report batching 只有在
独占环境 profiling 证明 report 事务仍是主导热点时才立项，并必须覆盖部分失败回退、旧 invocation fence、
重复批次、单项超时和 Orchestrator 切换测试。不得为了单机数字合并 launch T1/T2、弱化 Outbox 原子性、
关闭同步提交或删除终态 CAS。
