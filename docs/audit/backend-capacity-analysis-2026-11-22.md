# 后端性能与容量复核

代码基线 `51c82f49b`。优先校准根 Workflow 的端到端统计，再验证子 Job 绑定是否能省去一次同值 UPDATE。第二个候选是根 Workflow 启动时重复读取图定义。单机增加 launch 消费者的历史对照出现吞吐下降；系统当前版本的可持续生产容量仍缺同构建全链路测量。[既有压测记录](../verifications/load-test-local-2026-11-15.md) · [当前容量待办](../plans/todo-master.md)

## 原始对照与适用条件

重新读取 11 月 21 日的两份容量报告、Worker 报告、终态检查日志和 PostgreSQL 压力采样。运行 ID 分别为 `p2-p1-launch-12-6-20261121` 和 `p2-p1-launch-24-12-20261121`，负载子运行后缀为 `-10w`。两组实际都是单租户、1,500 个 Atomic 请求、25 请求/秒注入 60 秒、100 个业务日、上限 64 槽。Docker 与应用共享 8 核宿主，应用镜像标记为 `eb026c671+fence-shared-dirty`。两份报告均标记 `Standard-baseline comparable: no`。以下是该轮方向性对照。

| 指标 | 12 分区 / 每实例 6 个消费者 | 24 分区 / 每实例 12 个消费者 |
|---|---:|---:|
| 根请求关联运行达到终态 / 应到 | 1,500 / 1,500 | 1,500 / 1,500 |
| 成功 Job 实例 / 总数 | 1,500 / 1,500 | 1,500 / 1,500 |
| 任务完成速率（历史分析口径） | 11.43/s | 8.91/s |
| launch 平均耗时 | 950 ms | 2,327 ms |
| Job 实例创建至结束 p95 | 14.826 s | 45.407 s |
| 任务领取延迟 p95 | 9.520 s | 41.468 s |
| PG 活跃且等待的连接数，采样中位数 / 峰值 | 5 / 28（29 个样本） | 18 / 50（33 个样本） |
| 按根请求统计的端到端耗时汇总样本数 | 0 | 0 |

两组任务完成速率相差约 22%，并发较高时，子 Job 校验、准备事务和派发事务的平均耗时都上升。12/6 下三项分别为 70、139、217 ms，共 426 ms。与 launch 总均值的 524 ms 差值含根运行准入、START 派发外层、回写等工作；尚不能归给某一段 SQL。PG 等待数包含所有 wait event，两组的 advisory lock 等待者在这些离散采样点均为 0。另一场单业务键画像测到的 880 ms 锁等待均值，仅适用于热点场景。[阶段计时与业务键对照](../verifications/load-test-local-2026-11-15.md#2026-11-21-launch-126-vs-2412-对照业务键基数-100)

## 三个优先事项

### 1. 修准根请求完成数，再衡量优化收益

两份原始报告同时出现“1,500 个 Job 成功”和“端到端 completed=0”。负载生成器给根请求加运行 ID 前缀，而派发 JOB 节点会生成独立 child 请求 ID。端到端 SQL 仅用子实例的 trigger_request_id 连接请求，再筛根请求前缀，漏掉 Workflow 入口。独立终态检查已经按根请求关联 workflow_run，因此两份数据并不矛盾。[端到端汇总](../../load-tests/sql/p2-run-end-to-end-summary.sql) · [根运行终态检查](../../load-tests/sql/control-run-terminal-counts.sql) · [子请求构造](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/ChildJobLaunchSupport.java)

建议沿用终态检查的租户、运行 ID 和业务日范围，按根请求到根运行 finished_at 计算完成吞吐和端到端 p95，分列成功、失败和未终态；子 Job 与任务继续独立计数。若支持直接 Job 入口，保留该路径的单独统计。用一个根派生多个子 Job 的用例验证根只计一次；预期根数非零却无样本时，将报告标为无效。

### 2. 减少子 Job 与 Workflow 节点的重复绑定

正常子 Job 启动时，DefaultLaunchService 在准备阶段关联节点和实例。ChildJobLaunchSupport 在启动返回后，又按期望节点核验并写同一实例 ID。当前 Mapper 允许已有相同 ID 的行再次 UPDATE。历史 100 请求热点画像中，该 SQL 执行 200 次、命中 200 行，累计 3,733 ms。SQL 总时间包含并发等候和首次必要写入，不能拿一半直接算作可节省的请求延迟。[准备阶段绑定](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultLaunchService.java) · [返回后的绑定](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/ChildJobLaunchSupport.java) · [绑定 SQL](../../batch-orchestrator/src/main/resources/mapper/WorkflowNodeRunMapper.xml)

建议先在完整调用链上区分“首次绑定”“已绑定为同一实例”“绑定冲突”。首次写入继续原子校验租户和 JOB 节点；返回后确认精确的 node run 已绑定到预期实例即可。保留拆分事务、幂等重入和补绑定路径的校验，尤其区分最新节点执行记录与本次预期记录。验收比较每个成功 JOB 的实际 UPDATE 次数、WAL、launch p95，同时验证并发重试、等待恢复、跨租户与不同实例冲突。

### 3. 在根运行准入事务内复用同一份图定义

普通新启动进入 admitFromLiveDefinition 后，校验器读取全图节点和边，START 路由再取出边及命中的节点，构建冻结快照时又读取全图节点和边。成功的常见路径有 6 次图相关 Mapper 调用。候选改法是在同一事务取一次节点、一次边，传给校验、START 选择和快照构建。先按实际 JDBC 语句计数核对 MyBatis 会话缓存，6 次 Mapper 调用不一定发生 6 次数据库往返。[根运行准入和冻结快照](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowRunLaunchService.java) · [全图校验](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowGraphValidator.java) · [START 路由](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java)

比较小图和多节点扇出图的实际 SQL 次数及根准入 p95。保持定义版本复核、启用边过滤、顺序、条件路由和错误语义；等待恢复与历史重放仍消费原冻结快照。先分段计量根准入、START 派发和触发回写，确认这项改动对 524 ms 差值的实际贡献。

## 容量判断和下一轮验收

- 先用 12/6、1 Worker、相同配置和资源、100 个业务日重跑当前基线，再单独启用每项优化。每组同样预热、交替重复，固定数据规模和镜像身份；记录宿主预检、根请求 p95、任务完成速率、注入结束后排空时间、PG 等待类型和 WAL。业务门槛为预期 1,500 个根请求全部对账、相应子作业成功、无重复、无残留。原压测在环境超标时仅用于诊断。
- `51c82f49b` 已有多槽位准入优化。在隔离数据库预占 32/64 槽时，单次准入从 33 个 SQL 往返降到 1 个；全满时为 64 → 65。旧镜像的 950 ms 不能当成该提交的成绩。以 12 个有效消费者、一请求占用一个消费者的简化模型，500 ms 对应理论 24 请求/s；持续接 25 请求/s 需要平均占用最多 480 ms，实际还需余量，并以队列不持续增长确认。[准入微基准](../verifications/load-test-local-2026-11-15.md#2026-11-21-workflow-多槽位准入优化)
- 业务键基数 1 与 100 的历史对照，任务速率分别为 8.96/s 和 15.74/s。同一租户、作业、业务日的结果版本必须有序。如果生产热点集中，分别采获锁等待和获得锁至外层事务提交的时长，再评估把与版本无关的准备工作移出持锁区。保留版本递增、生效版本唯一及重复回报幂等。[结果版本写入](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java)
- Atomic 单任务场景优先优化 launch。文件 Worker 另用实际行/s、字节/s、文件 p95 和控制总数验收。历史 10 万行导入试验中，COPY + merge 相对批量 UPSERT 为 0.97–1.02 倍；直接 COPY 适用于整批替换的业务语义。Dispatch 并发须区分独立文件正常吞吐与同一文件的 ACK 竞争。历史单机试验不足以调整所有 Worker 默认批量或推出生产容量承诺。[文件 Worker 压测](../verifications/import-export-dispatch-scenario-performance-2026-09-01.md)

## 本次验证

复核了上述两个运行 ID 的 `p2-capacity-profile`、`control-plane-worker-report`、终态日志和 `pg-pressure-samples.csv`，以 Python csv 逐列重算等待数的中位数与峰值；绑定次数取自 `p2-p1-launch-prof-20261121` 的 `pg-statement-profile.txt`。原始报告保存在生成它们的 checkout 的 load-tests/target 下。

用已有 PostgreSQL 17.11 镜像在禁网、临时文件系统的单用户数据库执行当前 Mapper 的绑定 UPDATE 及当前端到端汇总 SQL。最小样本为 1 个已完成根运行和 2 个已完成子 Job。首次与同值重复绑定分别产生物理行位置 (0,3) 和 (0,4)，不同实例尝试覆盖更新 0 行。现行汇总返回 0，沿根运行和节点关联的对照返回 1 个根、2 个子 Job。这验证了计数和同值写入行为。本轮未对应用施压，也没有修改应用、数据库结构或部署参数。

## 本轮实施与验证（2026-11-22）

三项优先级已在独立工作树落地。本轮没有重新压测应用，也没有部署；证据层级为单测、编译/格式检查和隔离 PostgreSQL。压测收益与生产容量仍未验证。

### 已实施

1. **根请求统计**：`load-tests/sql/p2-run-end-to-end-summary.sql` 改为按根请求 `LEFT JOIN workflow_run` 计算终态、吞吐和 p95，子 Job 通过 `trigger_request.parent_workflow_run_id` 单独汇总，直接 Job 入口保留独立行。租户、运行 ID 与业务日范围沿用终态检查口径；`sample_status` 在根请求非零但没有匹配运行或没有完成样本时输出 `INVALID`。容量脚本同步传入 `tenant_id`、`biz_date`、`biz_date_cardinality`。
2. **子 Job 绑定**：`ChildJobLaunchSupport` 在 launch 返回后改用新增的 `WorkflowNodeRunMapper.selectByTenantAndId` 核验本次预期的节点尝试；已绑定到同一实例时不再执行 UPDATE，绑定为空才补绑，绑定到其他实例或跨租户读取失败时按冲突拒绝。首次原子绑定仍在 `DefaultLaunchService` 的准备事务内完成，拆分事务、幂等重入和冲突语义不变。
3. **图定义复用**：`DefaultWorkflowRunLaunchService.admitFromLiveDefinition` 在准入事务内一次读取节点和边，`WorkflowGraphValidator.validate(nodes, edges)`、`resolveInitialNodes(nodes, edges, payload)` 与冻结快照共用同一份图。仍然执行定义版本复核和计划版本校验；START 路由按 `enabled = true` 过滤并保持 id 升序，条件边和错误语义不变。容量等待快照与整 Workflow 重放路径继续消费原冻结快照。

### 验证证据

- 定向单测：`ChildJobLaunchSupportTest` 7/7、`DefaultWorkflowRunLaunchServiceReplayTest` 11/11、`DefaultWorkflowDagServiceTest` 32/32、`WorkflowGraphValidatorTest` 35/35。绑定用例覆盖“已绑定只核验且不 UPDATE”“未绑定补绑一次”“绑定其他实例拒绝”；启动用例断言准入事务内节点、边各读取一次；DAG 用例断言预加载图不回落 Mapper。
- 格式与编译：`spotless:check` 通过；全仓 `-DskipTests compile` 通过。
- 隔离 PostgreSQL 17.11（单用户临时库）：
  - 旧汇总 SQL 在仅含 Workflow 根请求的样本上返回 `completed=0`；新 SQL 在同一批数据返回 `workflow_root` requests=3、samples=2、success=2，`child_job` 2（1 成功 / 1 失败），`direct_job_root` 1。跨租户、其他业务日和其他根运行的子 Job 均未计入。
  - 只有根请求、没有 `workflow_run` 时，`workflow_root` 行输出 `INVALID: no matched entry`。
  - 首次绑定 UPDATE 1 行，ctid `(0,2)`、xmin 771；同值重复 UPDATE 仍为 1 行，但变为 ctid `(0,3)`、xmin 772，确认产生新行版本。随后只读核验 ctid/xmin 不变；跨租户读取 0 行；换成不同实例 UPDATE 0 行。

### 剩余验收

- 在 `51c82f49b` 及本改动上按 12 分区 / 每实例 6 消费者、1 Worker、基数 100 重新建立基线，再单独启用绑定优化。每组记录每个成功 Job 的节点绑定 UPDATE 次数、根请求 p95、任务完成速率、注入结束后的排空时间、PG 等待类型和 WAL；1,500 个根请求需全部对账且无重复、无残留。
- 图定义复用的单测证明图相关 Mapper 调用由 6 次降为 2 次（每次一份节点和边）。MyBatis 默认 SESSION 级一级缓存可能已把同一事务内重复的全图节点/边查询合并，实际 JDBC 往返预计约为 4 → 2 次（全图节点、全图边、START 出边、命中节点 → 全图节点、全图边）；仍需在同一构建上按 JDBC 语句计数确认，并用根准入 p95 和整体 A/B 衡量收益，不能把本轮结果当作容量承诺。
