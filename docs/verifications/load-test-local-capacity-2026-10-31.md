# 本地压测记录：launch 受理链路容量（2026-10-31）

## 结论摘要

本地单机环境下，`POST /api/triggers/launch` 受理链路在 **50 请求/秒注入时 SLO 达标**（p95 216 ms），在 **100 请求/秒注入时吞吐稳定在约 75 rps、p95 超出 500 ms 阈值**。超容部分按准入策略以 **HTTP 429** 拒绝，等待上限 5,000 ms 来自本地环境配置，属设计行为，**未发现代码缺陷**。

首轮 20 rps 的 p95 634 ms 是冷启动预热污染，同条件复测降至 88 ms，不作为基线。

## 实验定义

- **假设**：launch 受理链路的容量拐点位于 50–100 rps 之间；超出后系统应通过准入控制拒绝而非雪崩。
- **目标链路**：Trigger HTTP 受理 → 写 `workflow_run` / `job_instance` → Outbox → Kafka → Worker。
- **负载形状**：`rampUsers(peak).during(20s)` + `constantUsersPerSec(peak).during(60s)`，每用户执行 1 次请求（`JobLaunchSimulation`）。
- **成功标准**：`global p95 < 500 ms` 且错误率 `< 1%`（`GatlingConfig` 默认断言）。

## 环境

| 项 | 值 |
|---|---|
| 宿主机 | macOS，Docker VM 8 vCPU / 8 GB |
| 代码版本 | 分支 `perf/local-loadtest-2026-10-31`，基于 `main` @ `0258697d1` |
| 应用 | 本地 Java 进程：console 18080、trigger 18081、orchestrator 18082 |
| 中间件 | PostgreSQL 17（3 GB 上限）、Kafka（1.5 GB 上限）、MinIO（1 GB）、Valkey（512 MB），均为单实例容器 |
| Worker | 5 个 ONLINE（`default-tenant`），`ta` 租户无注册 Worker |
| 压测租户 | `ta`，`workflowDefinitionId=1`（`JOB-WF-5`，单作业受管 Workflow） |
| 业务日 | `2026-01-15`（固定） |

关键运行时配置（`.env.local`，覆盖 `application.yml` 默认值）：

- `BATCH_TRIGGER_PLATFORM_DB_MAX_POOL_SIZE=40`（默认 10）
- `BATCH_TRIGGER_PLATFORM_DB_CONNECTION_TIMEOUT_MS=5000`
- `BATCH_TRIGGER_API_LAUNCH_QUEUE_CAPACITY=128`（默认 8）
- `BATCH_TRIGGER_API_LAUNCH_QUEUE_WAIT_MILLIS=5000`（默认 1000）

## 命令

```bash
cd load-tests
mvn -o test-compile      # gatling:test 不自动编译测试类，首次必须执行
mvn -o gatling:test -Dsimulation=io.github.pinpols.batch.loadtest.simulations.JobLaunchSimulation \
  -DworkflowDefinitionId=1 -DtenantId=ta \
  -Dusers.peak=<N> -Dduration.seconds=60 -Dramp.seconds=20 \
  -Dload.runId=load-20261031-<a..f>
```

## 测量结果

| 轮次 | RUN_ID | 注入率 | 请求 | OK | KO | p50 | p75 | p95 | p99 | 完成 rps | SLO |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| A（冷启动） | a | 20/s | 1,220 | 1,220 | 0 | 30 | 110 | **634** | 1,420 | 15.25 | 未达标 |
| C（A 复测） | c | 20/s | 1,220 | 1,220 | 0 | 14 | 27 | **88** | 178 | 15.25 | 通过 |
| B | b | 50/s | 3,050 | 3,050 | 0 | 15 | 52 | **216** | 451 | 37.65 | 通过 |
| D | d | 100/s | 6,100 | 6,100 | 0 | 145 | 279 | **554** | 2,313 | 75.31 | 未达标 |
| E | e | 100/s | 6,100 | 5,019 | 1,081 | 411 | 774 | **5,005** | 5,082 | 74.39 | 未达标 |
| F | f | 100/s | 6,100 | 4,713 | 1,387 | 461 | 781 | **5,005** | 5,011 | 74.39 | 未达标 |

响应时间单位 ms。报告目录：`load-tests/target/gatling-results/joblaunchsimulation-2026*`。

## 诊断

**预热效应**：A 与 C 注入率相同（20/s），p95 从 634 ms 降至 88 ms，p50 从 30 ms 降至 14 ms。首轮包含 JVM 解释执行、连接池与缓存预热成本，不能作为容量基线。

**容量拐点**：B（50/s）p95 216 ms 达标；D（100/s）吞吐 75.31 rps、p95 554 ms。注入率翻倍而吞吐仅增至约 75 rps，说明本地单机受理容量约 **75 rps**。

**超容行为**：E/F 轮 KO 全部为 `status.find.in(200,201), found 429`（1,081 / 1,387 条，100%），响应时间集中在 5,003 ms，与 `BATCH_TRIGGER_API_LAUNCH_QUEUE_WAIT_MILLIS=5000` 一致。即请求在准入队列等待上限后被限流拒绝，**不是超时、异常或崩溃**。

**排除项**（压测中采样）：

- Outbox 1,409 条全部 `PUBLISHED`，无积压。
- PostgreSQL `active` 连接 1–2，`idle` 103；Hikari `active=1`、`pending=0`。
- Kafka 容器 CPU 峰值 166%（100 rps 期间），压测后回落 8.67%；未观察到消费滞后。
- JVM：minor GC 累计 2,797 次（含服务多日运行），heap 约 108 MB，无 GC 压力迹象。

## 未验证与局限

1. **压测语义偏窄**：`bizDate` 固定为 `2026-01-15`，4,270 次请求仅创建 508 个 `workflow_run`，绝大多数走"同日同 Workflow 已存在"的受理路径。本结果度量的是**受理+去重**吞吐，不等于**新建实例**吞吐。要测后者需每次使用不同业务日或不同 Workflow，属压测脚本变更，本次未做。
2. **租户无 Worker**：`ta` 无注册 Worker，端到端完成率不由本场景证明；任务最终由 `default-tenant` 的 5 个 Worker 消费（142 SUCCESS / 6 FAILED / 1 CANCELLED）。
3. **单机资源竞争**：Gatling、3 个 Java 应用进程、5 个 Worker、PostgreSQL、Kafka 全部运行在同一 8 vCPU 宿主机，CPU 争用未单独隔离测量（`ps` 在沙箱内不可用）。
4. **D 与 E/F 差异未定因**：同为 100 rps 注入，D 轮 0 失败而 E/F 轮约 20% 429。可能与数据累积或瞬时资源竞争有关，未做单变量验证。
5. **本地基准不作为生产能力保证**；生产容量须结合实例数、连接池预算与 PostgreSQL 连接上限另行验证。

## 清理

本次未执行数据清理。压测产生 `ta` 租户 `2026-01-15` 业务日 508 个 `workflow_run` 及对应任务，仍保留在本地库中。如需回收，按 `load-tests/scripts/cleanup-load-test-environment.sh` 的既有范围执行，并先核对 `RUN_ID` 标识。

## 结论与后续建议

- **不需要代码修改**：未发现受理链路缺陷；429 是准入策略的预期输出。
- 若要提升本地容量，属配置与资源问题（队列容量、连接池、实例数、宿主机资源），不属于代码缺陷。
- 若要获得有意义的容量数字，建议下一轮改为**每次请求使用不同业务日**或**多 Workflow 轮转**，避免去重路径稀释负载；同时将 429 与慢请求分开统计，避免"排队 5 s 后拒绝"被误读为性能劣化。

## 2026-11-01 P2 画像失败排查

基线 `0258697d1-dirty`，运行标识 `p2-verify-20261101-g-10w`；本次仅复核现场数据、脚本和已有 Gatling 报告，未重新施压。宿主机负载偏高，环境不可与历史标准基线比较，以下数据不代表生产容量。

- 注入采用 `constantUsersPerSec` 开放模型，目标 100 次/秒、200 秒。Gatling 在约 202 秒完成 20,000 次请求，其中 15,204 次成功、4,796 次为 HTTP 429；总吞吐 98.52 次/秒，成功吞吐 74.9 次/秒，整体 p95 6,546 ms。原先将排空时间计入后得出 8.8 次/秒的说法有误。断言失败，压测未通过。
- 本轮根请求共 15,204 条、均使用同一个业务日；准备脚本的 `CAPACITY_BIZ_DATE_CARDINALITY` 默认值为 1。数据库中的 Workflow 活跃运行唯一约束按租户、定义、业务日和 dry-run 限定，单业务日场景无法代表独立业务键并发执行。该约束与积压有关，但各等待阶段耗时仍需单独测量。
- 08:34 左右只读查询发现已成功实例持续增加；曾观察到 RUNNING 的实例 6756 后来成功，后续实例继续运行，任务的 `assigned_worker_code` 为 `atomic-node-1`。因此此前“因该租户没有注册 Worker，单个实例永远无法执行”的判断不成立；注册表与任务认领之间的具体机制未在本轮验证。
- 成功受理的请求在本轮注入结束后仍有大量 `PROCESSING`；终态等待要求 20,000 个请求全部落库并关联终态，而 4,796 次已经被 429 拒绝，故本轮无法满足该成功条件。当前脚本仅在已受理请求全部关联终态且数量稳定不足时提前退出，可能继续等待积压处理。中断运行不等于已排空。
- 原始证据：`load-tests/target/p2-capacity-profile-logs/p2-verify-20261101-g/10w-storm.log` 和 `load-tests/target/gatling-results/controlplanemixedpressuresimulation-20261031164135846/`；数据库快照会随后台继续处理而变化。

下一步先隔离并对账现存积压，再确定是否调整画像的业务键、失败退出和 Worker 观察方式；在共享环境仍有其他测试活动时不直接清理或重跑。429 的准入具体拒绝原因、排队耗时及目标环境容量仍待验证。

### 准入与等待队列复核

- Trigger 当前进程的 admission 计数器显示队列满 4,736、等待超时 60、中断 0，总计 4,796，与 Gatling 本轮 429 总数一致；这是进程累计计数，本轮没有保留发压前快照，不能排除其他请求贡献。队列满和超时都由 `TriggerApiAdmissionGuard` 返回 `RATE_LIMITED`。排队等待时间的分布及底层处理耗时尚未归因。
- 08:35 左右只读查询 `workflow_launch_wait`，本轮约 9,533 条 `WAITING`、5,556 条 `RESUMED`，等待项对应 `PROCESSING` 请求。同租户、Workflow、业务日和 dry-run 只有一个活跃运行，恢复查询每次为每个业务键选一个等待项；这是本轮积压逐条推进的直接原因。观察到的慢速不能直接外推独立业务键场景。
- 严格 P2 画像在 Gatling 失败时现在保留数据并返回原始失败码，不再先等待完整目标；普通混压仍采集终态。定向测试先复现原行为，再验证失败退出、数据保留及普通混压行为。此修改不表示积压已排空，也未改变服务端准入策略。
- 小规模真实发压未运行：同一数据库仍有该轮积压及其他测试活动，按仓库规则不能并行画像。完成隔离后应使用独立租户和适当的业务键覆盖，分别验证成功链路与故意触发拒绝的失败链路。

### 隔离发压环境尝试

2026-11-01 使用独立 Docker 网络、数据卷和端口启动 `capacity-isolated` 环境，未修改原环境的数据或容器。独立 PostgreSQL 完成平台迁移，初始化业务表及角色；独立 Orchestrator 一度健康。Trigger 和 Atomic Worker 启动期间，Docker 与原数据库响应明显变慢，原 Mockserver 健康检查出现超时。宿主 Docker 虚拟机只有 8 CPU、约 7.75 GiB 内存，两套拓扑仍共享计算资源；为避免进一步干扰，停止了本轮新建容器，数据卷暂留，未发起测试请求。原 Mockserver 随后恢复健康。

停止独立容器后，原批次查询为 5,769 条根请求 `LAUNCHED`、9,435 条 `PROCESSING`；其中 9,435 条等待记录为 `WAITING`，仍在自然推进。没有取消、删除或手工修改未完成请求。成功与 429 的隔离真实发压均未完成；需资源足够的隔离机器，或在原环境不与其他任务竞争且可暂时停止时再执行。

### 后续发压顺序与门槛

1. 旧批次按根请求对账至终态；确认对应等待、任务和 Kafka 消费积压已收敛，再按运行标识检查清理范围。不得用删除等待记录代替执行完成。
2. 主线涉及发压链路的修改及相关定向回归完成后，记录固定源码快照及构建标识；按该快照构建镜像，核对所有被测容器的镜像身份。共享工作区持续修改时不能以 `-dirty` 标签证明构建与源码一致。
3. 在资源独立或无其他测试竞争的环境先做小规模真实验证：低速独立业务键请求逐项核对 HTTP、根 Workflow、子 Job 和任务成功；再通过受控准入上限触发 429，核对拒绝原因指标、未接纳请求无业务副作用，以及已接纳请求最终收敛。分别保存运行标识、负载参数、计数器前后快照和退出码。
4. 两条真实链路都通过并完成残留核对后，固定环境资源、业务键分布、预热、规模和阈值，再进行正式压测。涉及目标环境容量的结论仍需目标环境验证。
