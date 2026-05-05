# 压测模块说明

这里收纳 `load-tests/` 的 Gatling 压测脚本和配置入口。

## 目录职责

- 该模块只负责压测，不参与主工程的常规编译链
- 所有压测参数统一收敛到 [GatlingConfig.java](./src/test/java/com/example/batch/loadtest/GatlingConfig.java)
- 所有场景脚本放在 `src/test/java/com/example/batch/loadtest/simulations/`

## 场景列表

- [JobLaunchSimulation.java](./src/test/java/com/example/batch/loadtest/simulations/JobLaunchSimulation.java) — trigger **`POST /api/triggers/launch`** 写压测
- [ConsoleQuerySimulation.java](./src/test/java/com/example/batch/loadtest/simulations/ConsoleQuerySimulation.java) — console 列表类查询读压测
- [CapacityBaselineSimulation.java](./src/test/java/com/example/batch/loadtest/simulations/CapacityBaselineSimulation.java) — 混合读写容量基线（阶梯加压）
- [SchedulingSnapshotUnderLoadSimulation.java](./src/test/java/com/example/batch/loadtest/simulations/SchedulingSnapshotUnderLoadSimulation.java) — **launch 写压 + orchestrator `GET /internal/scheduler/snapshot`**（调度路径只读可观测性）
- [SchedulingBacklogUnderLoadSimulation.java](./src/test/java/com/example/batch/loadtest/simulations/SchedulingBacklogUnderLoadSimulation.java) — 固定 launch RPS + 调度/队列/WAITING/READY 查询，配合 SQL sampler 观测派发滞后
- [LaunchPipelineCompletionSimulation.java](./src/test/java/com/example/batch/loadtest/simulations/LaunchPipelineCompletionSimulation.java) — **launch → console batch-status 轮询终态**，统计 Gatling 分组 **`pipeline_completion`**（依赖真实 Worker）
- [WorkerTaskLifecycleSimulation.java](./src/test/java/com/example/batch/loadtest/simulations/WorkerTaskLifecycleSimulation.java) — 基于显式 CSV 任务清单压 **CLAIM → REPORT** 内部接口（会修改任务状态，仅限隔离任务）

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

- `-Dtrigger.baseUrl=http://localhost:8081`
- `-Dconsole.baseUrl=http://localhost:8080`
- `-Dorchestrator.baseUrl=http://localhost:18082`（`SchedulingSnapshotUnderLoadSimulation`；需与 orchestrator 监听一致）

### 测试数据

- `-DtenantId=t1`
- `-DjobCode=E2E_IMPORT_LOAD`
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

### 四类 Worker 端到端压测数据

本地四类 worker 成功基线使用脚本准备可执行数据集，并顺序压测 IMPORT / EXPORT / DISPATCH / PROCESS：

```bash
cd ..
USERS_PER_WORKER=1 IMPORT_PROFILE=medium \
  bash load-tests/scripts/run-worker-load-tests.sh
```

脚本会：

- 生成 IMPORT 小/中/大 CSV payload：20 / 1000 / 10000 行
- 准备 EXPORT settlement batch + 5000 detail rows
- 准备 DISPATCH 本地渠道文件和 `lt_dispatch_local_job`
- 准备 PROCESS SQL transform 作业 `lt_process_sql_job` + 5000 source rows
- 临时将本地 `default-tenant/process-node-1` 从 `DECOMMISSIONED` 拉回 `ONLINE`（仅本地压测）
- 每类 launch 后直接查 DB 等待实例进入终态，并输出 `target/worker-load-report-<RUN_ID>.md`

清理本轮数据：

```bash
RUN_ID=<report里的RUN_ID> bash load-tests/scripts/cleanup-worker-load-data.sh
```

注意：当前 EXPORT payload 是静态 fileName / targetPath；`USERS_PER_WORKER>1` 会复用同一输出文件，可能触发 `EXPORT_REGISTER_CHECKSUM_CONFLICT`。要做 EXPORT 并发上限，需要扩展为动态 feeder，为每个虚拟用户生成唯一 fileName。

四类 worker 阶梯加压：

```bash
cd ..
STEPS_CSV=1,2,4,8,16 IMPORT_PROFILE=medium \
  bash load-tests/scripts/run-worker-stress-tests.sh
```

`prepare-worker-load-data.sh` 生成的 IMPORT / EXPORT / PROCESS payload 内置 `#{traceId}` 占位符，Gatling 会为每个虚拟用户替换成唯一值，避免并发下重复文件名、重复 customerNo、重复 process batchKey 影响结论。

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

- [压测种子数据说明](../docs/sql/load-test/README.md)
- [压测维度矩阵](../docs/testing/load-test-dimensions.md)
- [测试文档索引](../docs/testing/README.md)

## 备注

- `GatlingConfig` 负责读取系统属性并提供统一默认值
- 仓库根 `pom.xml` 不包含该模块，需要在 `load-tests/` 下单独执行
