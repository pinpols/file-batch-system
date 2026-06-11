# Citus 运行时最后一公里实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 消除 Citus 上的运行时阻塞(跨分片 FOR UPDATE、连接扇出),达成 **8 服务在 Citus 平台库上 health UP + 真实 import job 全链冒烟**;同时普通 PG 路径零回归(全量回归 + e2e 41 仍绿)。

**Architecture:** 统一采用**模式 A(租户路由)**:全局扫描型语句加 `tenant_id = ?` 等值过滤(Citus 单分片路由后 FOR UPDATE 合法),调度器外层循环活跃租户。该模式在普通 PG 上语义等价(只是多一个等值条件),天然双栈兼容。连接扇出走 coordinator 端 `citus.max_shared_pool_size`(POC 已验证可热载)。

**事实基线(2026-06-11 实测,勿重查):**
- FOR UPDATE 共 4 条:
  - A. `CompensationCommandMapper.markStaleRunningFailed`(UPDATE..WHERE id IN(SELECT..FOR UPDATE SKIP LOCKED),全局 stale 扫)← `StaleCompensationCommandReconciler:49`
  - B. `JobPartitionMapper`:58 — **已带 `tenant_id = #{tenantId}`,单分片路由合法,预计零改动**(W8 复测确认)
  - C. `WorkflowNodeRunMapper.selectLatestForUpdate`(workflow_run_id+node_code,缺 tenant)← `DefaultTaskOutcomeService:201`、`DefaultWorkflowNodeDispatchService:498`
  - D. `WorkflowNodeRunMapper.selectDueWaitNodes`(WAIT 传感器全局扫)← `SensorPollScheduler:98`(MAX_PER_TICK)
- workflow_node_run 已有 tenant_id 列(V177);租户权威表 batch.tenant(reference,列名以 console 的 `TenantMapper.xml` 为准)
- orchestrator 无租户清单 mapper,需新增
- Citus 集群已拆除,W8 手工步骤需脚本化重建;coordinator 必需 GUC:`citus.propagate_set_commands='local'`、`citus.max_shared_pool_size`(POC 实测 25 可活,生产另调)
- e2e/全量回归基线:全绿(c442e7e19)

---

### Task 1: 租户路由基础设施(ActiveTenantProvider)

**Files:**
- Create: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/mapper/TenantRoutingMapper.java`
- Create: `batch-orchestrator/src/main/resources/mapper/TenantRoutingMapper.xml`
- Create: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/tenant/ActiveTenantProvider.java`
- Test: `batch-orchestrator/src/test/java/com/example/batch/orchestrator/infrastructure/tenant/ActiveTenantProviderIntegrationTest.java`

- [ ] Step 1: 看 `batch-console-api/src/main/resources/mapper/TenantMapper.xml` 确认 batch.tenant 的租户标识列与启用列的真实列名(预期类似 tenant_code/code + enabled/status)
- [ ] Step 2: 写失败 IT:`ActiveTenantProviderIntegrationTest extends AbstractIntegrationTest`,@SpringBootTest 注解按仓库 IT 惯例;arrange 直插 2 条 tenant(1 启用 1 停用)→ assert `provider.activeTenantIds()` 只含启用者且带 30s 缓存语义(连续两次调用第二次不打库——用 @SpyBean mapper verify times(1));断言带 .as()
- [ ] Step 3: 实现:mapper `selectActiveTenantIds()`(select 标识列 from batch.tenant where 启用条件 order by 1);Provider 构造器注入 + `volatile` 缓存 30_000ms(System.nanoTime 比较,无外部依赖);CLAUDE.md 红线遵守(构造器注入/无 FQN)
- [ ] Step 4: 跑测试绿 → commit `feat(orchestrator): 租户路由基础设施 ActiveTenantProvider(batch.tenant reference 30s 缓存)`

### Task 2: 语句 C — selectLatestForUpdate 补租户(单分片路由)

**Files:**
- Modify: `batch-orchestrator/src/main/resources/mapper/WorkflowNodeRunMapper.xml:76-84`、`WorkflowNodeRunMapper.java` 接口
- Modify: 调用方 `DefaultTaskOutcomeService:201`、`DefaultWorkflowNodeDispatchService:498`(两处上下文均持有 workflowRun/tenantId,沿现场变量传入)

- [ ] Step 1: 接口+XML 加 `tenantId` 首参,SQL `where tenant_id = #{tenantId} and workflow_run_id = ...`(其余不动)
- [ ] Step 2: 两个调用方传 tenantId(从各自已加载的 workflowRun 实体取;若局部只有 workflowRunId,沿调用链最近的 tenant 上下文取,**禁止**额外查库)
- [ ] Step 3: `mvn -q test -pl batch-orchestrator -Dtest='*Workflow*,*TaskOutcome*' -DfailIfNoTests=false` 绿 → commit

### Task 3: 语句 A — 补偿命令 stale 扫改租户路由

**Files:**
- Modify: `CompensationCommandMapper.xml`(markStaleRunningFailed 加 `and tenant_id = #{tenantId}`)+ 接口加参
- Modify: `StaleCompensationCommandReconciler`(注入 ActiveTenantProvider,tick 内 for 循环租户调用;单租户异常 catch 住记 WARN 继续下一租户,不让一个租户拖死全 tick)
- Test: 该 reconciler 既有测试同步;若无,补一个 IT:两租户各造 1 条 stale RUNNING,tick 后两条都 FAILED

- [ ] Step 1: 改 SQL+接口 → Step 2: reconciler 循环+隔离 catch → Step 3: 测试绿 → commit

### Task 4: 语句 D — WAIT 传感器扫改租户路由

**Files:**
- Modify: `WorkflowNodeRunMapper.xml`(selectDueWaitNodes 加 `and tenant_id = #{tenantId}`)+ 接口
- Modify: `SensorPollScheduler:98`(同 Task 3 模式:循环租户;MAX_PER_TICK 语义改为**每租户**上限,javadoc 注明)

- [ ] 同 Task 3 三步 → commit `feat(orchestrator): 传感器/补偿全局扫改租户路由(Citus 单分片 FOR UPDATE 合法化)`

### Task 5: Citus 集群脚本化 + 连接扇出配置

**Files:**
- Create: `scripts/local/citus-cluster.sh`(up/init/down/status;固化 W8 手工步骤:3 容器+network、per-db CREATE EXTENSION+citus_add_node、ALTER SYSTEM propagate_set_commands='local' + max_shared_pool_size=25 + reload、batch/quartz schema 预建)
- Modify: `docs/analysis/citus-w8-runtime-findings-2026-06-11.md`(追加"连接三元组建议":app Hikari max-pool ≤10/服务 × 8 服务 ≤ max_shared_pool_size×节点 ≤ worker max_connections)

- [ ] Step 1: 写脚本(bash -n 过)→ Step 2: `citus-cluster.sh up && init` 实跑,3 节点就绪 + GUC 校验(SHOW 两项)→ commit

### Task 6: 真集群迭代验证(harvest 循环,我=controller 亲自驱动)

- [ ] Step 1: worktree 重打包 8 jar(含全部新提交)
- [ ] Step 2: coordinator 建 batch_platform(Flyway 经 orchestrator 首启自动跑 V1..V179)→ 跑 `scripts/db/citus/01-distribute.sql` → 重启 orchestrator
- [ ] Step 3: **harvest 循环**:观察 logs/app/orchestrator.log 5 分钟;出现新 Citus 运行时错误 → 按"租户路由/配置/降级"三分法修 → 重启复测;直到 orchestrator 稳定 UP 且调度器 tick 无 ERROR
- [ ] Step 4: 扩到 trigger+console+5 worker(business 库连分支专用库 batch_business_part;停主服务窗口内做,完毕恢复)→ 8/8 health
- [ ] Step 5: 真链冒烟:console/API 触发一个 import job(或直插 trigger_request 走调度),验证 job_instance/outbox 落分布式表、Kafka 流转、worker CLAIM 执行终态 SUCCESS
- [ ] Step 6: guard:共享库零污染核验;成果追加进 w8-findings 文档 → commit

### Task 7: 双栈零回归终审 + 收尾

- [ ] Step 1: 普通 PG 全量:`mvn -B clean test` 全绿(15 模块)
- [ ] Step 2: e2e:install 上游 + `run-tests.sh --e2e --skip-build` 41/41
- [ ] Step 3: 计划勾选、推分支(不合 main)、主环境恢复(8 服务+m2)

---

## 验收定义(DoD)

1. 4 条 FOR UPDATE:1 条确认合法保留,3 条租户路由化,Citus 上零 `FOR UPDATE/SHARE` 错误
2. 8 服务在 Citus 平台库 health UP,真实 import job 全链 SUCCESS
3. 普通 PG:全量回归 + e2e 41/41 全绿(双栈兼容)
4. 共享库全程零污染;主环境最终恢复

## 已踩坑提醒(执行者必读)

- 约束名/索引列集 = 隐性契约,动前 grep on conflict(c442e7e19 血的教训)
- `-pl` 单模块测试有 stale-m2 假绿,跨模块签名变更后必全 reactor 验证
- "pre-existing failure"声明必须 T7 基线比对 + 单跑复现取证
- application-local.yml 硬编码 URL,DB 指向只认 `-D` 系统属性
- AssertJ 断言一律 .as()
