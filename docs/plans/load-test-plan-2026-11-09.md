# 本地压测方案（2026-11-09）

> 目标：在已启动的本地联调环境上，重新建立可比较的容量基线，并补齐上一轮（2026-10-31）未覆盖的端到端与调度积压维度。
> 本文件是**执行方案**，不是验收记录；实际结果另写 `docs/verifications/load-test-local-capacity-2026-11-09.md`。

## 1. 上一轮基线与遗留问题

来源：`docs/verifications/load-test-local-capacity-2026-10-31.md`。

| 注入率 | p95 | 完成吞吐 | 结论 |
|---:|---:|---:|---|
| 20/s（预热后） | 88 ms | 15.25 rps | 通过 |
| 50/s | 216 ms | 37.65 rps | 通过 |
| 100/s | 554 ms | 75.31 rps | 未达标；超容 429 |

遗留问题直接决定本轮设计：

1. **业务日固定**导致 4,270 次请求仅创建 508 个 `workflow_run`，绝大多数走“同日同 Workflow 已存在”去重路径，测到的是**受理+去重**而非**新建实例**吞吐。
2. **压测租户无 Worker**（`ta`），端到端完成率不由 launch 场景证明。
3. **单机争用**：Gatling、8 个应用、5 个 Worker、PG/Kafka 同跑在 8 vCPU / 8 GiB。
4. 429 与慢请求混在同一分母，容易把“排队 5s 后拒绝”误读为性能劣化。

## 2. 本轮目标与假设

### 目标

- G1：建立**新建实例路径**的受理容量基线（排除去重稀释）。
- G2：测量**调度积压**：固定写入下 `jp_waiting`/`jt_ready` 斜率与 `oldest_waiting_partition_seconds` 趋势。
- G3：测量**端到端完成**：launch → 控制台可见终态的耗时分布（依赖真实 Worker）。
- G4：记录足以复跑的环境、参数与清理范围。

### 假设

- H1：新建实例路径容量低于“同业务日去重”路径；拐点仍在 30–100 rps。
- H2：积压先出现在 `job_partition` 的 WAITING→READY 派发环节，而非 Kafka 消费。
- H3：5 个单实例 Worker 的业务完成吞吐在个位数到十几 rps 量级。

## 3. 环境与前置

| 项 | 当前值 |
|---|---|
| 宿主机 | macOS，Docker VM 8 vCPU / 8 GiB |
| 应用 | 本地 JVM：console 18080 / trigger 18081 / orchestrator 18082 / 5 Worker 18083–18087 |
| 中间件 | PG 17 primary+replica、Kafka、Valkey、MinIO 单实例 |
| Worker | `default-tenant` 下 5 个 ONLINE（IMPORT/EXPORT/PROCESS/DISPATCH/ATOMIC 各 1） |
| 可用租户 | `ta`、`tb`、`tc`、`tx` |
| 可用 Workflow | `ta`：JOB-WF-1～19（单作业 MANUAL）；`tb`：JOB-WF-5～25 |

前置检查（每次发压前）：

```bash
bash scripts/local/health-check-infra.sh
# 8 个应用 /actuator/health 均 200
for p in 18080 18081 18082 18083 18084 18085 18086 18087; do
  curl -s -o /dev/null -w "$p:%{http_code}\n" "http://localhost:$p/actuator/health"
done
uptime   # 记录 load1；> 8 的轮次标注“不可与历史基线直接比较”
```

## 4. 测试数据设计（解决去重稀释）

### 4.1 业务日轮转

为每个请求生成**不同的 `bizDate`**，从 `2026-02-01` 起按天递增，保证落到“新建运行”路径。
需要改动 `load-tests` 的 launch 场景，新增 feeder 提供 `bizDate`，请求体改用 `#{bizDate}`。

- 备选（不改代码）：使用多个 `workflowDefinitionId` 轮转 + 固定业务日；但同一业务日下每个 Workflow 只能有一个活跃运行，仍会被去重限制，效果弱于业务日轮转。
- 倾向：**改代码**，新增 `LaunchNewInstanceSimulation`（复用 `JobLaunchSimulation` 结构，仅替换 bizDate feeder）。

### 4.2 租户与 Worker

- 端到端场景（G3）必须使用**有 Worker 消费**的路径。当前 Worker 注册在 `default-tenant`，但该租户无 Workflow；需先确认 Worker 是否跨租户消费，否则在 `ta` 或 `tb` 注册一个对应 group 的 Worker。
- 若无法注册：G3 降级为“launch → 受理终态”，并在报告中明确标注“未验证业务完成”。

### 4.3 数据隔离

- 使用专用业务日区间与 `load.runId` 前缀（如 `lt-20261109-*`），便于按运行标识回收。
- 压测前记录各租户 `workflow_run` / `job_instance` 计数快照；结束后对比增量。

## 5. 分层场景与参数

| 层 | 场景 | 目的 | 关键参数 |
|---|---|---|---|
| L1 受理 | `JobLaunchSimulation`（新建实例版） | G1 新建实例容量 | 20/50/80/100 rps，各 60s，ramp 20s |
| L2 调度积压 | `SchedulingBacklogUnderLoadSimulation` + `sample-scheduler-backlog.sh` | G2 | launch 20 rps × 300s，采样 5s |
| L3 端到端 | `LaunchPipelineCompletionSimulation` | G3 | 10 users，最大 90 轮询 × 2s |
| L4 查询读 | `ConsoleQuerySimulation` | 写入背景下的读延迟 | 20 users × 120s |

执行顺序：L1 → L4 → L2 → L3；每层之间等待积压收敛（`jp_waiting` 回到基线 ±10%）再进入下一层。

### SLO 阈值

- L1 写：`p95 < 500 ms`，错误率 `< 1%`；429 单独统计，不计入错误分母。
- L2：`jp_waiting` 稳态斜率 ≤ 0，`oldest_waiting_partition_seconds` 不持续增长。
- L3：`pipeline_completion` p95 记录实际值，不设硬阈值（首轮基线）。
- L4 读：`p99 < 300 ms`，错误率 `< 1%`。

## 6. 观测指标

| 类别 | 指标 | 来源 |
|---|---|---|
| 入口 | rps、p50/p95/p99、200/429/5xx 分布 | Gatling report |
| 受理 | `trigger_request` 增量、`workflow_run` 增量 | PG 快照 |
| 调度 | `jp_waiting`/`jp_ready`/`jt_ready`、`oldest_waiting_partition_seconds` | `sample-scheduler-backlog.sh` CSV |
| 执行 | `jt_success`/`ji_success` 增量、Worker load/capacity | 同上 + Prometheus |
| 资源 | 进程 CPU/内存、PG active/idle 连接、Kafka lag、GC | `ps`、PG 查询、Kafka describe、jcmd |
| 端到端 | `pipeline_completion` 分组 | Gatling report |

## 7. 验收标准

- **A1**：L1 在 50 rps 下 p95 与错误率达标，且 `workflow_run` 增量 ≈ 成功请求数（证明未走重复路径）。
- **A2**：L2 稳态窗口内 `jp_waiting` 无持续正斜率；若为正，给出瓶颈归属（Worker/调度/Kafka/PG）。
- **A3**：L3 有真实 Worker 消费，终态样本占比 ≥ 80%；否则明确标注未验证。
- **A4**：完整记录环境、源码快照、参数、原始数据路径与清理范围。

## 8. 执行步骤（草案）

```bash
# 0. 记录基线
cd load-tests && mvn -o test-compile

# 1. L1 新建实例受理（80 rps 示例）
mvn -o gatling:test -Dsimulation=...LaunchNewInstanceSimulation \
  -DtenantId=ta -DworkflowDefinitionId=1 \
  -Dusers.peak=80 -Dduration.seconds=60 -Dramp.seconds=20 \
  -Dload.runId=lt-20261109-l1-80

# 2. L4 读压
mvn -o gatling:test -Dsimulation=...ConsoleQuerySimulation \
  -Dusers.peak=20 -Dduration.seconds=120 -Dload.runId=lt-20261109-l4

# 3. L2 积压（并行采样）
mvn -o gatling:test -Dsimulation=...SchedulingBacklogUnderLoadSimulation \
  -Dscheduling.launch.rps=20 -Dduration.seconds=300 -Dload.runId=lt-20261109-l2 &
TENANT_ID=ta DURATION_SECONDS=300 INTERVAL_SECONDS=5 \
  bash scripts/sample-scheduler-backlog.sh

# 4. L3 端到端
mvn -o gatling:test -Dsimulation=...LaunchPipelineCompletionSimulation \
  -Dpipeline.completion.users=10 -Dload.runId=lt-20261109-l3
```

## 9. 清理

- 压测后按 `load.runId` 核对 `trigger_request` / `workflow_run` / `job_instance` 增量。
- 使用 `load-tests/scripts/cleanup-load-test-environment.sh`，先 `DRY_RUN` 预览范围，再执行。
- 不删除仍在 `RUNNING` 的运行；等其收敛或显式标记为遗留。

## 10. 风险与限制

- 宿主机 load 当前 4.8–7.3，高于历史基线窗口；本轮数字标注“同机相对比较”，不直接对比 2026-10-31 绝对值。
- 单机拓扑无法区分应用 CPU 争用与 Worker 处理能力。
- 端到端依赖 Worker 跨租户消费能力，若不具备则 G3 只能部分验证。
- 100 rps 以上出现 429 属准入设计行为，需在报告中与“慢请求”分开呈现。

## 11. 待确认项

1. Worker 是否可跨租户消费 `ta`/`tb` 的任务？若否，是否允许在 `ta` 注册临时 Worker？
2. 是否接受改动 `load-tests` 新增业务日轮转场景（仅压测代码，不影响生产代码）？
3. 本轮是否要求端到端硬验收，还是首轮只采基线数字？
