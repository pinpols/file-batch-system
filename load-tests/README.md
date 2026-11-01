# 压测模块说明

这里收纳 `load-tests/` 的 Gatling 压测脚本和配置入口。

## 目录职责

- 该模块只负责压测，不参与主工程的常规编译链
- 所有压测参数统一收敛到 [GatlingConfig.java](./src/test/java/io/github/pinpols/batch/loadtest/GatlingConfig.java)
- 所有场景脚本放在 `src/test/java/io/github/pinpols/batch/loadtest/simulations/`

## 场景列表

- [JobLaunchSimulation.java](./src/test/java/io/github/pinpols/batch/loadtest/simulations/JobLaunchSimulation.java) — trigger **`POST /api/triggers/launch`** 写压测
- [ConsoleQuerySimulation.java](./src/test/java/io/github/pinpols/batch/loadtest/simulations/ConsoleQuerySimulation.java) — console 列表类查询读压测
- [CapacityBaselineSimulation.java](./src/test/java/io/github/pinpols/batch/loadtest/simulations/CapacityBaselineSimulation.java) — 混合读写容量基线（阶梯加压）
- [SchedulingSnapshotUnderLoadSimulation.java](./src/test/java/io/github/pinpols/batch/loadtest/simulations/SchedulingSnapshotUnderLoadSimulation.java) — **launch 写压 + orchestrator `GET /internal/scheduler/snapshot`**（调度路径只读可观测性）
- [SchedulingBacklogUnderLoadSimulation.java](./src/test/java/io/github/pinpols/batch/loadtest/simulations/SchedulingBacklogUnderLoadSimulation.java) — 固定 launch RPS + 调度/队列/WAITING/READY 查询，配合 SQL sampler 观测派发滞后
- [LaunchPipelineCompletionSimulation.java](./src/test/java/io/github/pinpols/batch/loadtest/simulations/LaunchPipelineCompletionSimulation.java) — **launch → console batch-status 轮询终态**，统计 Gatling 分组 **`pipeline_completion`**（依赖真实 Worker）
- [WorkerTaskLifecycleSimulation.java](./src/test/java/io/github/pinpols/batch/loadtest/simulations/WorkerTaskLifecycleSimulation.java) — 基于显式 CSV 任务清单压 **CLAIM → REPORT** 内部接口（会修改任务状态，仅限隔离任务）

维度说明见 [docs/testing/load-test-dimensions.md](../docs/testing/load-test-dimensions.md)。

## 运行方式

进入 `load-tests/` 目录后执行：

```bash
mvn gatling:test -Dsimulation=JobLaunchSimulation
mvn gatling:test -Dsimulation=ConsoleQuerySimulation
mvn gatling:test -Dsimulation=CapacityBaselineSimulation
mvn gatling:test -Dsimulation=SchedulingSnapshotUnderLoadSimulation
mvn gatling:test -Dsimulation=SchedulingBacklogUnderLoadSimulation
mvn gatling:test -Dsimulation=LaunchPipelineCompletionSimulation
mvn gatling:test -Dsimulation=WorkerTaskLifecycleSimulation
```

也可以通过 Maven profile 切换目标环境：

- `local`：默认 profile，指向本地联调端点
- `staging`：指向 staging 环境
- `prod-probe`：保守的生产容量探测 profile

示例：

```bash
mvn -Pstaging gatling:test -Dsimulation=JobLaunchSimulation
```

## 常用系统属性

### 端点

- `-Dtrigger.baseUrl=http://localhost:18081`
- `-Dconsole.baseUrl=http://localhost:18080`
- `-Dorchestrator.baseUrl=http://localhost:18082`（`SchedulingSnapshotUnderLoadSimulation`；需与 orchestrator 监听一致）

### 测试数据

- `-DtenantId=t1`
- `-DworkflowDefinitionId=<enabled Workflow ID>` (required for launch simulations; resolve a managed single-Job Workflow through its explicit `single_job_definition_id` relation)
- `ControlPlaneMixedPressureSimulation` requires one Workflow ID per enabled launch population: `control.process.workflowDefinitionId`, `control.dispatch.workflowDefinitionId`, `control.atomic.workflowDefinitionId`, and `control.trigger.workflowDefinitionId`.
- `-DbizDate=2026-01-15`

### 压测参数

- `-Dusers.peak=20`
- `-Dduration.seconds=120`
- `-Dramp.seconds=30`
- `-Dscheduling.launch.rps=5.0`、`-Dscheduling.read.rps=5.0`（固定写压 + 调度积压查询）
- `-Dpipeline.completion.users=10`（端到尾场景并发用户数）
- `-Dpipeline.maxPolls=90`、`-Dpipeline.pollIntervalSec=2`（端到尾轮询上限与间隔）
- `-Dtask.lifecycle.csv=target/task-lifecycle-tasks.csv`（CLAIM → REPORT 场景任务清单，CSV header: `taskId,tenantId,workerId`）
- `-Dtask.lifecycle.executePauseMs=0`（CLAIM 与 REPORT 之间的模拟执行耗时）

### SLO 阈值

- `-Dslo.write.p95ms=500`
- `-Dslo.read.p99ms=300`
- `-Dslo.maxErrorPct=1.0`

### 控制台认证

- `-Dconsole.authToken=Bearer <token>`
- `-Dconsole.accessToken=<jwt>`（推荐，脚本内部拼 `Bearer `，避免 shell/Maven 空格转义问题）

### 调度积压采样

`SchedulingBacklogUnderLoadSimulation` 只负责 HTTP 侧延迟和错误率；WAITING/READY 是否堆积、单位时间完成量，需要并行跑 SQL sampler：

```bash
cd load-tests
TENANT_ID=default-tenant DURATION_SECONDS=300 INTERVAL_SECONDS=5 \
  bash scripts/sample-scheduler-backlog.sh
```

输出 CSV：`target/scheduler-backlog-*.csv`。报告里重点看：

- `jp_waiting` / `jp_ready` / `jt_ready` 是否持续上升
- `oldest_waiting_partition_seconds` 是否持续变大
- `jt_success`、`ji_success` 在稳态窗口内的增量 / 秒，即实际处理吞吐
- `worker_load / worker_capacity` 是否接近 1；若接近 1 且 backlog 增长，瓶颈在 worker；若未接近 1 且 backlog 增长，优先怀疑调度/派发

### 五类 Worker 端到端压测数据

本地业务 worker 成功基线使用脚本准备可执行数据集，并顺序压测 IMPORT / EXPORT / DISPATCH / PROCESS。
ATOMIC 的 1w/10w 严格容量画像使用独立 P2 入口，避免把业务文件/SQL 场景和控制面 task storm 混成
同一个容量结论。

```bash
cd ..
USERS_PER_WORKER=1 IMPORT_PROFILE=medium \
  bash load-tests/scripts/run-worker-load-tests.sh
```

脚本会：

- 生成 IMPORT 小/中/大 CSV payload：20 / 1000 / 10000 行
- `IMPORT_PROFILE=large` 仍是 inline payload；由于 Trigger/Kafka envelope 和 JSON 转义开销，参数文件达到 800 KB 时压测脚本会提前拒绝，必须改用对象存储导入场景。
- 准备 EXPORT settlement batch + 5000 detail rows
- 准备 DISPATCH 本地渠道文件和 `lt_dispatch_local_job`
- 准备 PROCESS SQL transform 作业 `lt_process_sql_job` + 5000 source rows
- 临时将本地 `default-tenant/process-node-1` 从 `DECOMMISSIONED` 拉回 `ONLINE`（仅本地压测）
- 每类 launch 后直接查 DB 等待实例进入终态，并输出 `target/worker-load-report-<RUN_ID>.md`

清理本轮数据：

```bash
RUN_ID=<report里的RUN_ID> bash load-tests/scripts/cleanup-worker-load-data.sh
```

`cleanup-worker-load-data.sh` 只清理本轮 runId 关联的业务表、平台表和本地临时文件；它不会删除
Kafka topic 日志段、MinIO 压测前缀或回收 PostgreSQL 已膨胀的数据文件。压测后发现 Docker
卷持续变大时，先诊断环境产物：

```bash
bash load-tests/scripts/cleanup-load-test-environment.sh --diagnose
```

确认是可重建的本地开发环境后，再执行推荐清理：

```bash
bash load-tests/scripts/cleanup-load-test-environment.sh --apply --confirm-project batch-platform --all
```

`--all` 会把压测 topic 调整为安全保留（默认 6 小时）、清理本地 MinIO 压测前缀，并对高频
PostgreSQL 表执行 `VACUUM ANALYZE`；本地 MinIO 删除回收站也会一并清理。压测前不要把
`LOAD_TEST_KAFKA_RETENTION_MS` 调到分钟级，否则 trigger 消息可能在 orchestrator 完整消费前被
broker 回收。若只想单独清理 MinIO 回收站，可执行：

```bash
bash load-tests/scripts/cleanup-load-test-environment.sh --apply --confirm-project batch-platform --minio-trash
```

如果需要立刻释放 Kafka topic 日志空间，可在停写的本地环境显式执行：

```bash
bash load-tests/scripts/cleanup-load-test-environment.sh --apply --confirm-project batch-platform --kafka-reset-topics
```

该命令会等待目标 topic 删除完成，再通过 Compose `kafka-init` 统一入口恢复全部平台 topic，
并校验压测 topic 清单全部存在。初始化失败或恢复不完整会明确返回失败，不再在清理脚本中维护
第二套 topic 创建与分区规则。该操作只适合数据可丢弃的开发环境。

注意：IMPORT / EXPORT / PROCESS payload 内置 `#{traceId}` 占位符，Gatling 会为每个虚拟用户生成唯一
文件名或业务键；Dispatch 也会按请求数预建独立 fileId。若新增自定义 payload，必须保留这一约束，
否则并发压测会退化为重复文件/重复业务键冲突。

四类 worker 阶梯加压：

```bash
cd ..
STEPS_CSV=1,2,4,8,16 IMPORT_PROFILE=medium \
  bash load-tests/scripts/run-worker-stress-tests.sh
```

发布前严格复验使用：

```bash
cd ..
STRICT=1 STEPS_CSV=1,2,4,8,16 IMPORT_PROFILE=medium \
  bash load-tests/scripts/run-worker-stress-tests.sh
```

`STRICT=1` 会在任一 Gatling 场景、日志采集、终态等待失败或实例非全量 `SUCCESS` 时返回非零退出码；
未显式设置 `MAX_ERROR_PCT` 时默认使用 `0.0`，并通过失败请求数断言准确表达零失败。非严格模式
仍会在报告中记录每个场景的 `PASS/FAIL` 和总结果，但保留零退出码供容量探索连续执行。

压测夹具准备会调用 Console 缓存失效 API。运行前必须从同一管理员登录会话提供 `BATCH_CONSOLE_TOKEN` 和 `BATCH_CONSOLE_CSRF_TOKEN`（HttpOnly JWT cookie 与 CSRF cookie 的值），脚本发送匹配 cookie/header；不依赖关闭鉴权。缺失凭据在写入夹具前失败，HTTP 或业务响应失败立即中止。只在进程环境传入，不写入日志或提交文件。

所有复用该夹具的 `run-worker-load-tests.sh`、`run-worker-stress-tests.sh` 和控制面混合画像都需要上述会话变量；先准备会话，再启动入口。读接口的 `CONSOLE_ACCESS_TOKEN` 不能替代写接口的 CSRF 会话。准备入口在缺失会话时不会先写业务夹具。

根运行计量可在同一套本地测试库内定向核对（独有 schema，结束后精确删除；不新建数据库）：

```bash
source scripts/lib/env-common.sh
"$PYTHON_BIN" load-tests/scripts/test-control-plane-metrics.py
```

该验证覆盖继承相同 runId 的子 JOB 请求、未终结根、失败根及错租户/定义。继承 metadata 的子请求不计入根接纳数量，根尚未创建的合法请求仍应阻止“全部完成”。


`prepare-worker-load-data.sh` 生成的 IMPORT / EXPORT / PROCESS payload 内置 `#{traceId}` 占位符，Gatling 会为每个虚拟用户替换成唯一值，避免并发下重复文件名、重复 customerNo、重复 process batchKey 影响结论。
Dispatch 压测会按虚拟用户数创建独立文件记录，并为每档压力分配不重叠的 `fileId`，避免共享文件状态竞争污染吞吐和失败率。
每轮 Worker 压测的本地夹具位于 `target/worker-load-data/<RUN_ID>`，不同运行的 payload 和 `run.env` 独立。共享模板和序列的准备事务使用数据库 advisory lock，避免并发 `setval` 回拨序列；该锁仅保护造数，不能隔离 Job/Workflow 定义修改。同一数据库的多个画像串行执行；单次画像内模块可用 `parallel` 模式并发发压。
控制面混合压测会根据各模块速率和持续时间计算期望 launch 数，根据 Dispatch 请求数预建唯一文件记录，并自包含准备只读 Atomic SQL 作业。每个 trigger request 都必须关联实例，且所有实例最终为 `SUCCESS`；仅有部分实例进入终态不再视为通过。
Process 故障画像重启本地 Worker 时会通过公共环境转换函数使用宿主机 PostgreSQL/Redis 地址，避免把 Compose 服务名传给本地 JVM。

`RUN_ID` 最长 53 个字符；夹具会追加最长的 `-SETTLEMENT` 后缀并写入 `varchar(64)`。所有造数入口会在写文件或数据库前校验，P2 容量画像还会校验追加 `-10w` 后的派生标识。

### Process / Dispatch / Atomic / Trigger 控制面压测

#### Workflow 身份与验收口径

混合入口按启用模块解析受管单作业 Workflow ID；目标缺失或不唯一时在采样和发压前失败。运行标识同时传入 Gatling，用于请求、运行查询和清理。

`BIZ_DATE_CARDINALITY=1` 是同 Workflow/date 的去重画像。要验证独立执行吞吐，业务日基数应覆盖本轮启动数；日期跨模块分配，避免 Atomic 与 Trigger 共享 Workflow 时互相去重。分发文件序号仍按模块独立计算。parallel 入口按该日期范围准备 Process 输入（`PROCESS_BIZ_DATE_CARDINALITY`），混合 Process 载荷使用当次根业务日；sequential 夹具默认仍只有一天。

历史本地 30 秒负载在新模型中的入口为：

```bash
RUN_ID=ctlw-workflow-mixed-unique \
  CONTROL_PLANE_MODE=parallel MODULES_CSV=process,dispatch,atomic,trigger \
  TRIGGER_DURATION_SECONDS=30 BIZ_DATE_CARDINALITY=180 \
  PROCESS_LAUNCH_RPS=1 DISPATCH_LAUNCH_RPS=1 ATOMIC_LAUNCH_RPS=1 \
  TRIGGER_LAUNCH_RPS=3 TRIGGER_READ_RPS=1 MAX_ERROR_PCT=0 \
  WAIT_TERMINAL_EXPECTED_TRIGGER_REQUESTS=180 \
  bash load-tests/scripts/run-control-plane-worker-benchmark.sh
```

该画像要求 180 个请求、180 个正确关联的 WorkflowRun 及 180 个 JOB 节点/子 Job，并且根、节点、Job 都成功。终态等待按 WorkflowRun 计数，业务成功沿 request→WorkflowRun→JOB→JobInstance 核对；子集成功、缺节点、跨租户/定义引用及根失败均不能通过。它沿用历史请求速率和每次 Process 的输入规模；新模型的日期分布和数据集总量不同，不能直接把新旧延迟差当作性能收益。默认每日期 5,000 条 Process 输入，180 日期共 900,000 条，执行前应核对该造数规模和磁盘预算。

指标 SQL 的最小 fixture 为 `sql/test-control-plane-metrics-fixture.sql`，用 `metrics_case` 选择 `success`、`root-running`、`child-running`、`missing-job`、`missing-node`、`foreign-root`、`wrong-definition`、`foreign-job`、`root-failed` 或 `node-failed`。每个场景必须使用独立空库，已有 `batch` schema 会使初始化失败；准备后对 `control-run-terminal-counts.sql` / `control-run-instance-success-counts.sql` 分别传入 tenant/run/date 变量查询。`test-process-load-fixture.sql` / `test-process-load-counts.sql` 则用于隔离验证日期输入覆盖与 event ID 唯一性。这些是 SQL 行为验证，不是业务链路或容量通过证据。

清理后可用 `control-run-cleanup-residue-counts.sql` 回查本轮 request、冻结请求标识对应的根运行、Job 参数对应的子实例及孤立节点。查询应在移除隔离基础设施前执行；销毁数据库不能替代本轮清理断言。

平台清理会先删除本 runId 已选 Pipeline 的 `pipeline_progress`，再删父实例，满足 V292 的租户复合 FK；另一轮运行的位点不属于目标。这是隔离压测数据销毁，不提供业务冷归档，正式运行历史应交由 Orchestrator 归档服务。相同数据库中的不同压测 profile 应串行：独立 runId 不能隔离共享 Job/Workflow 定义的修改；`parallel` 仅表示单次画像内模块并发，多个画像并发须使用独立环境。

导入/导出收敛后，其他 worker 默认用独立脚本跑，避免每轮夹带 IMPORT/EXPORT：

```bash
cd ..
USERS=4 MODULES_CSV=process,dispatch,atomic,trigger \
  bash load-tests/scripts/run-control-plane-worker-benchmark.sh
```

脚本会：

- 复用 `prepare-worker-load-data.sh` 准备 PROCESS / DISPATCH 数据
- 通过 `LaunchPipelineCompletionSimulation` 只发 launch，随后用 DB 等终态，跑 `lt_process_sql_job`、`lt_dispatch_local_job`、`atomic_sql_demo`
- 通过 `SchedulingBacklogUnderLoadSimulation` 跑 trigger launch 压力，并并行采样 scheduler backlog
- 输出 `target/control-plane-worker-report-<RUN_ID>.md`
- 默认自动清理本轮 job/task/outbox/trigger/file/process seed；排查现场可设 `SKIP_AUTO_CLEANUP=1`
- 自动清理的任一步失败均返回非零；若压测主流程已经失败，保留主流程退出码，并分别报告清理错误。
- 默认不登录 console；只有 `PIPELINE_MAX_POLLS>0` 或 `SCHEDULING_CONSOLE_READS=true` 时才需要 console token

默认 atomic 只跑 `atomic_sql_demo`。shell 执行器默认关闭；stored-proc/http 需要本地过程或出口 endpoint 就绪，可显式打开：

```bash
ATOMIC_JOBS_CSV=atomic_sql_demo,atomic_stored_proc_demo,atomic_http_demo \
  bash load-tests/scripts/run-control-plane-worker-benchmark.sh
```

常用参数：

- `USERS=1|4|8`：process/dispatch/atomic 端到端并发
- `TRIGGER_LAUNCH_RPS=5.0`、`TRIGGER_DURATION_SECONDS=120`：trigger 写压
- `CONTROL_PLANE_MODE=parallel`：process / dispatch / atomic / trigger 在同一 Gatling run 内同时发压
- `PROCESS_LAUNCH_RPS=1.0`、`DISPATCH_LAUNCH_RPS=1.0`、`ATOMIC_LAUNCH_RPS=1.0`：并行模式下三类 worker 的 launch 速率
- `SCHEDULING_CONSOLE_READS=true`：调度压测时额外打 console 读接口，默认关闭
- `MODULES_CSV=process,dispatch`：只跑部分模块
- `SKIP_AUTO_CLEANUP=1`：保留现场数据和报告引用

`run-control-plane-worker-benchmark.sh` 是通用控制面压测入口，不是 10w 严格容量画像入口。
当请求数达到 `LARGE_LOAD_REQUESTS`（默认 100000）时，脚本会在发压前校验 Trigger relay
预算和顺序模式下的作业 timeout，避免把本地 demo 超时或 40/s relay backlog 误判为系统容量回退。
确需故意测低 relay 下的排队恢复，可显式设置 `ALLOW_LOW_RELAY_FOR_LARGE_LOAD=1`；发布前
Atomic 10w 严格复验仍使用 `run-p2-capacity-profile.sh`。

真并行小基线示例：

```bash
CONTROL_PLANE_MODE=parallel \
MODULES_CSV=process,dispatch,atomic,trigger \
PROCESS_LAUNCH_RPS=1.0 DISPATCH_LAUNCH_RPS=1.0 ATOMIC_LAUNCH_RPS=1.0 \
TRIGGER_LAUNCH_RPS=1.0 TRIGGER_READ_RPS=1.0 TRIGGER_DURATION_SECONDS=30 \
SKIP_AUTO_CLEANUP=1 \
  bash load-tests/scripts/run-control-plane-worker-benchmark.sh
```

Kafka lag 默认优先通过 `batch-kafka` 容器内 `${KAFKA_CONTAINER_BIN_DIR}/kafka-consumer-groups.sh` 采样；目录默认值见
`.env.example`，可随 Kafka 镜像布局覆盖；找不到容器时会尝试本机
`kafka-consumer-groups.sh`。非容器环境需安装 PostgreSQL client 和 Kafka CLI：

自适应 Relay 容量轮次必须显式声明，preflight 会同时校验 Trigger benchmark profile、入口准入、Hikari、
Relay min/max、当前有效速率和 consumer lag 指标：

```bash
PREFLIGHT_ONLY=1 CAPACITY_EXPECT_TRIGGER_ADAPTIVE_RELEASE=1 \
  bash load-tests/scripts/run-p2-capacity-profile.sh
```

Relay 上限 A/B 必须同步声明预期值。例如容器以 `80 events/s` 启动时，设置
`CAPACITY_EXPECT_TRIGGER_RELAY_RATE=80`；未设置时仍严格校验 benchmark 基线 `400 events/s`，避免误测旧容器。

`run-p2-capacity-profile.sh` 只支持仓库 `local-docker` benchmark 拓扑，包括固定的 Trigger、双
Orchestrator、Atomic Worker、Kafka 和 PostgreSQL compose 服务。Linux Docker 与 macOS Docker 使用
同一 compose 均可运行；staging、生产探测和非 Docker 环境应使用上文的 Maven `-Pstaging` 或
`-Pprod-probe` Gatling profile，不应绕过容器拓扑检查。

标准吞吐基线中，Atomic Worker 默认通过单一 `http://orchestrator:18082` Service 入口访问 task-control；
两个 Orchestrator 仍共同消费 12 个 launch 分区。该口径与普通 Compose、Helm 以及 2026-09-12 的严格
10 万基线一致。直接轮转两个 Orchestrator URL 属于 HA 分流 A/B，必须显式启动和声明：

```bash
BATCH_WORKER_TASK_CLIENT_BASE_URLS=http://orchestrator:18082,http://orchestrator-benchmark-replica:18082 \
  COMPOSE_BENCHMARK=1 ./scripts/docker/up-apps.sh worker-atomic
CAPACITY_ATOMIC_TASK_CLIENT_BASE_URLS=http://orchestrator:18082,http://orchestrator-benchmark-replica:18082 \
  bash load-tests/scripts/run-p2-capacity-profile.sh
```

双直连结果用于验证 claim、renew、report 的端点切换与流量分布，不得与单 Service 标准吞吐基线直接
比较。脚本会校验 Worker 容器中的实际端点值，并将端点口径写入报告。

Atomic 容量画像要求 `batch-worker-import/export/process/dispatch` 停止运行；这些容器不参与被测链路，却会
占用 Docker 内存、CPU 和 PostgreSQL 连接。preflight 默认对此 fail-fast，可按提示执行 `docker stop`。
`CAPACITY_REQUIRE_ISOLATED_APP_TOPOLOGY=0` 仅用于故障诊断，生成的结果不得与隔离基线比较。

从普通 local 切换到 benchmark 时应使用 `COMPOSE_BENCHMARK=1 ./scripts/docker/up-apps.sh ...`。
即使只指定部分应用服务，启动脚本也会先重跑 `kafka-init`，将存量 Kafka topic 扩展到 benchmark
声明的分区数，再重启 producer/consumer。Kafka 分区不可缩减；切回普通 local 后可以继续使用已扩展的
topic，但不能把切换前后的结果视为同一环境基线。

应用镜像必须通过 `./scripts/docker/build-apps.sh` 构建。该入口会把当前 Git revision 写入 OCI
`org.opencontainers.image.revision` 标签；存在未提交文件时标签追加 `-dirty`。P2 preflight 默认要求工作树
干净，并校验 Trigger、双 Orchestrator 与 Atomic Worker 镜像标签等于当前 Git revision，防止换分支或
换机器后误用旧的 `:local` 镜像。仅复验历史镜像时可显式设置
`CAPACITY_EXPECT_APP_IMAGE_REVISION=<sha>`，报告会保留该声明，不能与当前代码基线混称。

容量对比必须先通过环境同构门禁。脚本默认按 2026-09-12 无画像基线校验：

- Docker Linux engine 固定为 8 CPU，内存处于 7.5-9 GiB 容量等级；Trigger、双 Orchestrator、
  Atomic Worker、Kafka 必须健康且设置非零内存上限，具体预算进入环境签名
- PostgreSQL 数据卷至少保留 20 GiB 空间
- `pg_stat_statements.track=none`、`track_io_timing=off`
- `synchronous_commit=on`、`wal_compression=off`
- `max_wal_size=1GiB`、`checkpoint_timeout=300s`
- 发压前主机 1 分钟 load/CPU 不超过 `0.75`
- benchmark 容器拓扑、Kafka 分区/lag、隔离租户数据均符合脚本声明

压测开始和结束时会比较 Trigger、双 Orchestrator、Atomic Worker、PostgreSQL 与 Kafka 的容器重启计数；
任一容器在测量窗口内重启都会将该轮判为失败，不能把恢复后的请求结果冒充稳定吞吐。

SQL 画像使用另一套明确口径：

```bash
CAPACITY_PG_STATEMENTS_PROFILE_ENABLED=1 \
  bash load-tests/scripts/run-p2-capacity-profile.sh
```

该模式要求 `pg_stat_statements.track=top`、`track_io_timing=on`。仅将
`CAPACITY_PG_STATEMENTS_PROFILE_ENABLED` 设为 `0` 只会停止画像报告，不能关闭数据库运行时采集；修改角色、
数据库或容器参数后必须让应用连接池全部重连。实验使用其他 WAL/checkpoint 参数时，必须同时显式传入
`CAPACITY_EXPECT_*` 期望值并使用独立 `RUN_ID`，不得与历史基线直接混合比较。报告会记录 Git SHA、
容器镜像 ID、Docker CPU/内存/架构/Engine/Compose 组成的环境签名、数据库参数和运行期间主机 load。
换机器或升级 Docker 后可以继续运行，但环境签名不同的结果只能建立新基线，不能直接用于宣称相对旧基线
的性能提升或回退。确需使用其他 Docker 容量等级时，显式覆盖 `CAPACITY_EXPECT_DOCKER_CPUS`、
`CAPACITY_MIN_DOCKER_MEMORY_BYTES` 和 `CAPACITY_MAX_DOCKER_MEMORY_BYTES`，并使用独立验证报告。
运行期间的 load 峰值包含被测 Docker/JVM/PostgreSQL 自身压力，只记录到报告中用于跨轮次归因，不作为
硬失败条件；外部后台进程是否抢占 CPU 仍需结合主机进程采样判断。

P2 容量与多租户公平性 fixture 会自包含创建所需的 Atomic 作业定义，不依赖可选的演示 seed；准备完成后
脚本会核验作业类型、worker group 和启用状态，fixture 不完整时在发压前失败，避免生成入口成功但终态无效的报告。
严格 P2 画像的 Gatling 失败时会保留运行数据并返回原始失败码，不再等待全部预期请求达到终态；这不表示已受理请求完成，后续须先核对并排空再清理。普通混压仍按原有逻辑等待并采集终态。
异常轮次排空 Kafka 后使用 `RUN_ID=<profile-run-id>-10w bash load-tests/scripts/cleanup-worker-load-data.sh`
统一清理；该入口会先清控制面引用，再清通用平台和业务 fixture，不要手工颠倒 SQL 顺序。

五类 worker 的 1w/10w 复测要拆开记录口径：

- Atomic 10w 严格容量：使用 `run-p2-capacity-profile.sh`，它会启动/校验 benchmark 拓扑、
  隔离租户、容量作业 timeout、Trigger relay、Kafka 分区、Docker 资源和残留数据。
- Import / Export / Dispatch / Process 1w/10w：使用 `run-worker-load-tests.sh`
  按 `WORKER_MODULES_CSV=<module>` 单类运行，必要时放宽 `WAIT_TERMINAL_TIMEOUT_SECONDS`，
  并保留每类报告。它验证业务 worker 链路，不得和 Atomic 控制面容量基线直接比较。

权威命令矩阵如下。`RUN_ID` 建议使用可追溯前缀，报告落在 `load-tests/target/`，正式验收结论再摘要到
`docs/verifications/worker-five-load-validation-<YYYY-MM-DD>.md`。

| Worker | 1w 命令 | 10w 命令 | 口径 |
|---|---|---|---|
| Import | `RUN_ID=ltw-import-1w-<ts> WORKER_MODULES_CSV=import USERS_PER_WORKER=10000 IMPORT_PROFILE=medium WAIT_TERMINAL_TIMEOUT_SECONDS=3600 bash load-tests/scripts/run-worker-load-tests.sh` | `RUN_ID=ltw-import-10w-<ts> WORKER_MODULES_CSV=import USERS_PER_WORKER=100000 IMPORT_PROFILE=medium WAIT_TERMINAL_TIMEOUT_SECONDS=7200 bash load-tests/scripts/run-worker-load-tests.sh` | inline import 容量；大文件对象存储导入另建专项，不用 inline large 冒充 |
| Export | `RUN_ID=ltw-export-1w-<ts> WORKER_MODULES_CSV=export USERS_PER_WORKER=10000 WAIT_TERMINAL_TIMEOUT_SECONDS=3600 bash load-tests/scripts/run-worker-load-tests.sh` | `RUN_ID=ltw-export-10w-<ts> WORKER_MODULES_CSV=export USERS_PER_WORKER=100000 WAIT_TERMINAL_TIMEOUT_SECONDS=7200 bash load-tests/scripts/run-worker-load-tests.sh` | 导出链路和文件登记；关注 checksum 冲突、对象/本地文件膨胀 |
| Dispatch | `RUN_ID=ltw-dispatch-1w-<ts> WORKER_MODULES_CSV=dispatch USERS_PER_WORKER=10000 WAIT_TERMINAL_TIMEOUT_SECONDS=3600 bash load-tests/scripts/run-worker-load-tests.sh` | `RUN_ID=ltw-dispatch-10w-<ts> WORKER_MODULES_CSV=dispatch USERS_PER_WORKER=100000 WAIT_TERMINAL_TIMEOUT_SECONDS=7200 bash load-tests/scripts/run-worker-load-tests.sh` | 本地 LOCAL channel；真实 SFTP/NAS/EMAIL/OSS 属于外部依赖专项 |
| Process | `RUN_ID=ltw-process-1w-<ts> WORKER_MODULES_CSV=process USERS_PER_WORKER=10000 WAIT_TERMINAL_TIMEOUT_SECONDS=3600 bash load-tests/scripts/run-worker-load-tests.sh` | `RUN_ID=ltw-process-10w-<ts> WORKER_MODULES_CSV=process USERS_PER_WORKER=100000 WAIT_TERMINAL_TIMEOUT_SECONDS=7200 bash load-tests/scripts/run-worker-load-tests.sh` | launch 数容量；千万行计算吞吐用 `run-process-worker-benchmark.sh` 单独记录 |
| Atomic | `RUN_ID=p2-atomic-1w-<ts> STORM_TOTAL_REQUESTS=10000 STORM_RPS=100 RUN_10W_STORM=1 RUN_FAIRNESS=0 bash load-tests/scripts/run-p2-capacity-profile.sh` | `RUN_ID=p2-atomic-10w-<ts> STORM_TOTAL_REQUESTS=100000 STORM_RPS=200 RUN_10W_STORM=1 RUN_FAIRNESS=0 bash load-tests/scripts/run-p2-capacity-profile.sh` | 隔离 benchmark 拓扑下的控制面 task storm，不能和四类业务 worker 横向比较 |

正式报告必须记录：

- Git revision、工作树是否 dirty、Docker CPU/内存、PostgreSQL/Kafka/Worker 容器拓扑。
- 每类 `RUN_ID`、原始报告路径、HTTP OK/KO、实例总数、终态数、`SUCCESS` 数、非终态数。
- Kafka lag、PostgreSQL 容量/保留计划、磁盘水位和自动清理结果。
- 是否使用默认 `SKIP_AUTO_CLEANUP=0`；如果保留现场，必须写清后续清理命令。
- 哪些轮次只是本地容量边界，不得写成生产容量承诺。

压测入口会先检查本机磁盘余量。普通 worker/control-plane 默认要求 `5GiB` 可用空间；P2 10w
容量画像默认要求 `20GiB`。可通过 `LOAD_TEST_MIN_FREE_GIB=<n>` 显式覆盖，但低磁盘环境下得到的
延迟和吞吐不可作为容量基线。

- PostgreSQL 客户端：统一入口默认依次尝试宿主机 `psql`、Python `psycopg`、运行中的
  `batch-postgres-primary` 容器；可用 `BATCH_PG_CLIENT_MODE=host|python|docker` 固定模式。本地 Docker
  容量画像无需额外安装宿主机 `psql`。
- `kafka-consumer-groups.sh` / `kafka-topics.sh`：Kafka lag 和 topic 初始化使用；设置 `KAFKA_BIN_DIR=/path/to/kafka/bin`。
- Python 3：默认优先找 `python3`，也可通过 `PYTHON_BIN=/path/to/python3` 或 `PYTHON=/path/to/python3` 指定。

可用 `BATCH_SCRIPT_RUNTIME=host` 强制走本机客户端，`BATCH_SCRIPT_RUNTIME=docker` 强制走容器客户端，默认 `auto`。可用
`KAFKA_LAG_GROUP_REGEX` 收窄 consumer group。

### Process Worker 1000w 专项压测

Process worker 大数据压测使用独立脚本,走正常系统链路:

```bash
cd ..
SKIP_AUTO_CLEANUP=1 \
PROCESS_SOURCE_ROWS=10000000 \
PROCESS_ACCOUNT_COUNT=100000 \
PROCESS_SCENARIOS=aggregate,copy \
PROCESS_USERS=1 \
WAIT_TERMINAL_TIMEOUT_SECONDS=3600 \
bash load-tests/scripts/run-process-worker-benchmark.sh
```

脚本会准备两类 process job:

- `lt_process_sql_job`:1000w source rows 聚合到 `biz.process_account_summary`,用于测计算/聚合吞吐。
- `lt_process_copy_job`:1000w source rows 直通写到 `biz.process_event_copy`,用于测 staging 写入、JSONB 反序列化发布和 cleanup 压力。

本地大数据 run 前确认 worker 配置:

- `BATCH_WORKER_PROCESS_SQL_TRANSFORM_TIMEOUT_SECONDS` 默认 900s。
- `BATCH_WORKER_PROCESS_KAFKA_MAX_POLL_INTERVAL_MS` 默认 1200000ms。
- 若历史 Kafka 消息会干扰,可给 worker 临时设置 `BATCH_WORKER_PROCESS_CONSUMER_GROUP_ID=batch-worker-process-bench-<ts>` 和 `SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=latest`。

报告输出:

- `load-tests/target/process-worker-report-<RUN_ID>.md`
- `load-tests/target/process-worker-logs/<RUN_ID>/`

清理:

```bash
RUN_ID=<report里的RUN_ID> bash load-tests/scripts/cleanup-worker-load-data.sh
```

当前 1000w baseline 结论见 `docs/verifications/process-worker-throughput-2026-06-07.md`:aggregate 路径可用,copy 路径功能成功但耗时过长,后续应优先做 typed staging/direct insert-select/分片 copy,不要继续只调 timeout。

### CLAIM → REPORT 清单

`WorkerTaskLifecycleSimulation` 会真实调用 `/internal/tasks/{taskId}/claim` 与 `/report` 并推进状态，只能用于隔离任务。CSV 例子：

```csv
taskId,tenantId,workerId
12345,default-tenant,synthetic-worker-1
12346,default-tenant,synthetic-worker-1
```

可用 SQL 导出 READY 任务清单，但请先确保这些任务不会被真实 Worker 同时消费：

```sql
select id as taskId, tenant_id as tenantId, 'synthetic-worker-1' as workerId
  from batch.job_task
 where tenant_id = 'default-tenant'
   and task_status = 'READY'
 order by id
 limit 1000;
```

## 与文档的对应关系

- [压测种子数据说明](../scripts/db/load-test-seed/README.md)
- [压测维度矩阵](../docs/testing/load-test-dimensions.md)
- [测试文档索引](../docs/testing/README.md)

## 备注

- `GatlingConfig` 负责读取系统属性并提供统一默认值
- 仓库根 `pom.xml` 不包含该模块，需要在 `load-tests/` 下单独执行

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [scripts/analyze-soak.sh](scripts/analyze-soak.sh) | 解析 JFR + jcmd 输出,生成 markdown soak 报告。 |
| [scripts/cleanup-load-test-environment.sh](scripts/cleanup-load-test-environment.sh) | 按所选范围清理本地压测环境与运行数据。 |
| [scripts/cleanup-worker-load-data.sh](scripts/cleanup-worker-load-data.sh) | 清理所选 Worker 压测运行产生的隔离数据。 |
| [scripts/env.sh](scripts/env.sh) | load-tests 公共变量。只能被 source。 |
| [scripts/monitor-soak.sh](scripts/monitor-soak.sh) | 后台周期 jcmd heap dump + 健康检查 + 退出条件评估。 |
| [scripts/p2_multitenant_fairness.py](scripts/p2_multitenant_fairness.py) | 分析多租户压测结果的资源公平性。 |
| [scripts/peak_ceiling.py](scripts/peak_ceiling.py) | 多租户并发峰值流量 — 单机控制面吞吐天花板压测。 |
| [scripts/prepare-worker-load-data.sh](scripts/prepare-worker-load-data.sh) | 准备 Worker 压测场景需要的业务数据。 |
| [scripts/run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) | 对 Worker 与调度控制面执行联动压力基准。 |
| [scripts/run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) | 执行 P2 容量画像并采集吞吐与资源证据。 |
| [scripts/run-p2-process-failure-profile.sh](scripts/run-p2-process-failure-profile.sh) | 运行 Process Worker 故障恢复容量画像。 |
| [scripts/run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) | 执行 Process Worker 吞吐与处理结果基准。 |
| [scripts/run-worker-business-scenario-matrix.sh](scripts/run-worker-business-scenario-matrix.sh) | 本地真实上下游 worker 业务场景矩阵统一入口。 |
| [scripts/run-worker-load-tests.sh](scripts/run-worker-load-tests.sh) | 调用 Gatling 执行 Worker 负载测试。 |
| [scripts/run-worker-soak-tests.sh](scripts/run-worker-soak-tests.sh) | 24h+ 持续 soak/longevity 压测。 |
| [scripts/run-worker-stress-tests.sh](scripts/run-worker-stress-tests.sh) | 执行 Worker 阶梯压力测试并采集容量结果。 |
| [scripts/sample-scheduler-backlog.sh](scripts/sample-scheduler-backlog.sh) | 在 Gatling 运行期间采样 scheduler / dispatch / worker 的积压情况。 |
| [scripts/start-soak.sh](scripts/start-soak.sh) | 启动 console-api + 4 worker(JFR + clock offset)。 |
| [scripts/test-control-plane-metrics.py](scripts/test-control-plane-metrics.py) | 在目标测试库的隔离 schema 中验证控制面根运行与子作业指标。 |
| [sql/analyze-soak-batch-days.sql](sql/analyze-soak-batch-days.sql) | 供 [analyze-soak.sh](scripts/analyze-soak.sh) 使用的配套 SQL。 |
| [sql/analyze-soak-job-latency.sql](sql/analyze-soak-job-latency.sql) | 供 [analyze-soak.sh](scripts/analyze-soak.sh) 使用的配套 SQL。 |
| [sql/capture-pg-capacity-settings.sql](sql/capture-pg-capacity-settings.sql) | 记录容量压测报告中的 PostgreSQL 运行参数。 |
| [sql/checkpoint.sql](sql/checkpoint.sql) | 供 [25-import-stage2e-checkpoint-crash.sh](../scripts/sim/25-import-stage2e-checkpoint-crash.sh) 使用的配套 SQL。 |
| [sql/cleanup-control-plane-worker.sql](sql/cleanup-control-plane-worker.sql) | DANGER: 删除本轮压测控制面数据，仅限专用隔离压测库。 |
| [sql/cleanup-p2-capacity-policy.sql](sql/cleanup-p2-capacity-policy.sql) | DANGER: 删除 P2 capacity 压测策略夹具。仅限专用本地压测库；不得连接生产或共享验收库。 |
| [sql/cleanup-p2-multitenant-fairness-policy.sql](sql/cleanup-p2-multitenant-fairness-policy.sql) | DANGER: 删除 P2 多租户公平性压测策略夹具。仅限专用本地压测库；不得连接生产或共享验收库。 |
| [sql/cleanup-worker-load-business.sql](sql/cleanup-worker-load-business.sql) | DANGER: 删除压测业务数据。仅限专用本地压测库；先核对业务库连接和 RUN_ID/业务日期筛选条件。 |
| [sql/cleanup-worker-load-platform.sql](sql/cleanup-worker-load-platform.sql) | DANGER: 删除压测平台运行数据。仅限专用本地压测库；先核对平台库连接和 RUN_ID 筛选条件。 |
| [sql/control-dispatch-counters.sql](sql/control-dispatch-counters.sql) | 供 [run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/control-instance-completion.sql](sql/control-instance-completion.sql) | 供 [run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/control-pg-pressure-sample.sql](sql/control-pg-pressure-sample.sql) | 供 [run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/control-pg-pressure-snapshot.sql](sql/control-pg-pressure-snapshot.sql) | 供 [run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/control-pg-statement-profile.sql](sql/control-pg-statement-profile.sql) | 仅展示当前平台库的业务 SQL；截断并压平 SQL 文本，保证报告可读。 |
| [sql/control-process-staging.sql](sql/control-process-staging.sql) | 供 [run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/control-run-cleanup-residue-counts.sql](sql/control-run-cleanup-residue-counts.sql) | 使用清理前保存的根 ID 清单独立核对；不能在请求已删除后靠请求号前缀猜测归属。 |
| [sql/control-run-instance-success-counts.sql](sql/control-run-instance-success-counts.sql) | 本画像使用每个请求一个 JOB 的受管 Workflow；必须同时核对根、节点和真实 Job 实例。 |
| [sql/control-run-terminal-counts.sql](sql/control-run-terminal-counts.sql) | 入口接纳/终态以 WorkflowRun 为准；子 Job 的执行结果另由 success-counts 查询对账。 |
| [sql/control-selected-job-timeout-min.sql](sql/control-selected-job-timeout-min.sql) | 供 [run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/control-stage-duration.sql](sql/control-stage-duration.sql) | 供 [run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/control-task-latency.sql](sql/control-task-latency.sql) | 供 [run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/control-worker-load.sql](sql/control-worker-load.sql) | 供 [run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/diagnose-load-test-hot-relations.sql](sql/diagnose-load-test-hot-relations.sql) | 供 [cleanup-load-test-environment.sh](scripts/cleanup-load-test-environment.sh) 使用的配套 SQL。 |
| [sql/drop-control-plane-metrics-fixture.sql](sql/drop-control-plane-metrics-fixture.sql) | 仅允许传入本次定向指标验证器创建的独有 schema。 |
| [sql/explain-process-load-source.sql](sql/explain-process-load-source.sql) | 对应目录的运行与验证的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/p2-active-process-copy-backend.sql](sql/p2-active-process-copy-backend.sql) | 供 [run-p2-process-failure-profile.sh](scripts/run-p2-process-failure-profile.sh) 使用的配套 SQL。 |
| [sql/p2-capacity-tenant-runtime-counts.sql](sql/p2-capacity-tenant-runtime-counts.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/p2-online-worker-capacity.sql](sql/p2-online-worker-capacity.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/p2-process-business-summary.sql](sql/p2-process-business-summary.sql) | 供 [run-p2-process-failure-profile.sh](scripts/run-p2-process-failure-profile.sh) 使用的配套 SQL。 |
| [sql/p2-process-failure-summary.sql](sql/p2-process-failure-summary.sql) | 供 [run-p2-process-failure-profile.sh](scripts/run-p2-process-failure-profile.sh) 使用的配套 SQL。 |
| [sql/p2-request-instance-task-status.sql](sql/p2-request-instance-task-status.sql) | 供 [run-p2-process-failure-profile.sh](scripts/run-p2-process-failure-profile.sh) 使用的配套 SQL。 |
| [sql/p2-run-end-to-end-summary.sql](sql/p2-run-end-to-end-summary.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/p2-run-instance-residue-count.sql](sql/p2-run-instance-residue-count.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/p2-run-instance-summary.sql](sql/p2-run-instance-summary.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/p2-run-task-summary.sql](sql/p2-run-task-summary.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/p2-terminate-backend.sql](sql/p2-terminate-backend.sql) | 供 [run-p2-process-failure-profile.sh](scripts/run-p2-process-failure-profile.sh) 使用的配套 SQL。 |
| [sql/prepare-p2-capacity-atomic.sql](sql/prepare-p2-capacity-atomic.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/prepare-p2-multitenant-atomic.sql](sql/prepare-p2-multitenant-atomic.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/prepare-worker-load-business.sql](sql/prepare-worker-load-business.sql) | DANGER: 执行前会清理/重建压测业务夹具。仅限专用本地压测库；禁止连接生产或共享验收库。 |
| [sql/prepare-worker-load-platform.sql](sql/prepare-worker-load-platform.sql) | DANGER: 执行前会清理/重建压测平台夹具。仅限专用本地压测库；禁止连接生产或共享验收库。 |
| [sql/process-business-rows.sql](sql/process-business-rows.sql) | 供 [run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/process-instance-completion.sql](sql/process-instance-completion.sql) | 供 [run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/process-pg-stat-database.sql](sql/process-pg-stat-database.sql) | 供 [run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/process-pg-stat-wal.sql](sql/process-pg-stat-wal.sql) | 供 [run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/process-relation-sizes.sql](sql/process-relation-sizes.sql) | 供 [run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/process-run-count.sql](sql/process-run-count.sql) | 供 [run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/process-stage-duration.sql](sql/process-stage-duration.sql) | 供 [run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/process-table-stats.sql](sql/process-table-stats.sql) | 供 [run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/process-task-latency.sql](sql/process-task-latency.sql) | 供 [run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/process-throughput.sql](sql/process-throughput.sql) | 供 [run-process-worker-benchmark.sh](scripts/run-process-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/reset-control-pg-statement-profile.sql](sql/reset-control-pg-statement-profile.sql) | 容量轮次必须从空统计开始，避免历史 SQL 调用污染本轮热点排序。 |
| [sql/sample-scheduler-backlog.sql](sql/sample-scheduler-backlog.sql) | 供 [sample-scheduler-backlog.sh](scripts/sample-scheduler-backlog.sh) 使用的配套 SQL。 |
| [sql/select-control-plane-root-ids.sql](sql/select-control-plane-root-ids.sql) | 供 [run-control-plane-worker-benchmark.sh](scripts/run-control-plane-worker-benchmark.sh) 使用的配套 SQL。 |
| [sql/select-worker-load-dispatch-file-id.sql](sql/select-worker-load-dispatch-file-id.sql) | 供 [prepare-worker-load-data.sh](scripts/prepare-worker-load-data.sh) 使用的配套 SQL。 |
| [sql/test-control-plane-cleanup-scope-counts.sql](sql/test-control-plane-cleanup-scope-counts.sql) | 对应目录的运行与验证的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/test-control-plane-cleanup-scope-fixture.sql](sql/test-control-plane-cleanup-scope-fixture.sql) | 先准备 metrics fixture。暂存在实际系统中位于业务库；这里放同名诱饵表， |
| [sql/test-control-plane-metrics-fixture.sql](sql/test-control-plane-metrics-fixture.sql) | 控制面指标查询的最小 PostgreSQL fixture，仅在独立空数据库执行。 |
| [sql/test-process-cleanup-counts.sql](sql/test-process-cleanup-counts.sql) | 对应目录的运行与验证的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/test-process-cleanup-fixture.sql](sql/test-process-cleanup-fixture.sql) | 隔离空库的清理范围反例：同前缀运行、其它租户及旧固定编号均须保留。 |
| [sql/test-process-load-counts.sql](sql/test-process-load-counts.sql) | 对应目录的运行与验证的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/test-process-load-fixture.sql](sql/test-process-load-fixture.sql) | 仅在独立空数据库验证 Process 压测日期夹具，不替代业务库迁移验收。 |
| [sql/test-process-source-candidate-index.sql](sql/test-process-source-candidate-index.sql) | 仅隔离性能夹具：先保存无索引计划，再比较按 tenant/date 读取的访问路径。 |
| [sql/vacuum-analyze-table.sql](sql/vacuum-analyze-table.sql) | 供 [cleanup-load-test-environment.sh](scripts/cleanup-load-test-environment.sh) 使用的配套 SQL。 |
| [sql/vacuum-full-analyze-table.sql](sql/vacuum-full-analyze-table.sql) | 供 [cleanup-load-test-environment.sh](scripts/cleanup-load-test-environment.sh) 使用的配套 SQL。 |
| [sql/verify-control-pg-statement-profile.sql](sql/verify-control-pg-statement-profile.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/verify-p2-capacity-job.sql](sql/verify-p2-capacity-job.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/verify-p2-fairness-jobs.sql](sql/verify-p2-fairness-jobs.sql) | 供 [run-p2-capacity-profile.sh](scripts/run-p2-capacity-profile.sh) 使用的配套 SQL。 |
| [sql/verify-pg-capacity-environment.sql](sql/verify-pg-capacity-environment.sql) | 返回容量压测启动前必须匹配的 PostgreSQL 运行参数。 |
| [sql/worker-business-throughput.sql](sql/worker-business-throughput.sql) | 供 [run-worker-load-tests.sh](scripts/run-worker-load-tests.sh) 使用的配套 SQL。 |
| [sql/worker-dispatch-counters.sql](sql/worker-dispatch-counters.sql) | 供 [run-worker-load-tests.sh](scripts/run-worker-load-tests.sh) 使用的配套 SQL。 |
| [sql/worker-instance-completion.sql](sql/worker-instance-completion.sql) | 供 [run-worker-load-tests.sh](scripts/run-worker-load-tests.sh) 使用的配套 SQL。 |
| [sql/worker-run-terminal-counts.sql](sql/worker-run-terminal-counts.sql) | 供 [run-worker-load-tests.sh](scripts/run-worker-load-tests.sh) 使用的配套 SQL。 |
| [sql/worker-stress-instance-summary.sql](sql/worker-stress-instance-summary.sql) | 供 [run-worker-stress-tests.sh](scripts/run-worker-stress-tests.sh) 使用的配套 SQL。 |
| [sql/worker-stress-task-summary.sql](sql/worker-stress-task-summary.sql) | 供 [run-worker-stress-tests.sh](scripts/run-worker-stress-tests.sh) 使用的配套 SQL。 |
| [sql/worker-stress-terminal-counts.sql](sql/worker-stress-terminal-counts.sql) | 供 [run-worker-stress-tests.sh](scripts/run-worker-stress-tests.sh) 使用的配套 SQL。 |
| [sql/worker-task-status.sql](sql/worker-task-status.sql) | 供 [run-worker-load-tests.sh](scripts/run-worker-load-tests.sh) 使用的配套 SQL。 |
