# Citus 下一阶段:sim 多业务 + 真实数据验证(backlog)

> 前置已达成(2026-06-12):atomic worker 端到端 SUCCESS、5 类 Citus SQL 方言约束通用清障模式、
> 双栈零回归 BUILD SUCCESS。本文是"sim 全业务在 Citus 全绿 + 真实数据严格验证"的可执行清单。
> 上游:docs/analysis/citus-w8-runtime-findings-2026-06-11.md(约束模式 + harvest 方法)。

## 一次性环境准备
1. 集群:`bash scripts/local/citus-cluster.sh up && initdb`(3 GUC 已固化)
2. Flyway:起 orchestrator(`-Dspring.datasource.url=...25432/batch_platform -D...password=poc
   -Dspring.datasource.hikari.maximum-pool-size=6`),等 flyway 顶=179
3. distribute:停服务 → `psql < scripts/db/citus/01-distribute.sql`(123 dist + 32 ref)
4. seed:`grep -vE '^BEGIN|^COMMIT' platform_seed.sql | psql`(去事务,reference 配置落地;
   distributed demo 行 INSERT 报缺分片列属预期,不连坐)
5. 业务库:worker 连 batch_business_part(`-Dbatch.datasource.business.url=...15432/batch_business_part`)
6. 连接池:全局 6(8 服务 × 6 < coordinator max_connections 100;打满是真坑)

## sim 脚本的 Citus 适配(待做)
- scripts/sim/* 默认连主库 15432;需参数化/覆盖为 coordinator 25432 + business part
- sim seed(01-init-biz 等)同样去事务 + distributed 表 INSERT 带分片列

## 逐业务 harvest:静态撞击面扫描结果(2026-06-12,好消息——比预想小)
**4 类 worker 的专属 mapper 静态 SQL 撞击面已扫,大部分已清或本就不撞**:

| worker | 专属 mapper | 静态撞击点 | 状态 |
|---|---|---|---|
| **atomic** | (共享) | CLAIM/REPORT 通用链路 | ✅ 已通(真跑 SUCCESS) |
| **import** | 无专属 mapper | 走 worker-core/orchestrator 共享(已清);process_staging 是 local 表不分布 | ✅ 静态就绪 |
| **export** | 无专属 mapper | 走共享;keyset 分片查询(PR #393)需运行时验跨分片行为 | 🟡 静态就绪,运行时待验 |
| **process** | 无专属 mapper | 共享 + PlatformFileRuntime(pipeline_progress markCompleted 已清) | ✅ 静态就绪 |
| **dispatch** | DispatchChannelHealth / FileDispatch | ChannelHealth upsert 已清;FileDispatch 全是普通 UPDATE SET current_timestamp(Citus 允许)+ WHERE now()(允许),**不撞** | ✅ 静态就绪 |

**结论:静态 SQL 层面 4 worker 基本就绪**(共享 CLAIM/REPORT 已清 + 专属 mapper 已清/不撞)。

## 仍需 sim 运行时实证的(静态扫不到的)
逐业务跑(按 harvest 模式:发任务→轮询 worker SUCCESS→抓错→清→重试),重点验:
- [ ] **export 跨分片聚合**:统计/导出查询若无 tenant_id 过滤 → multi-shard,需走 replica 或加 hint
- [ ] **process SqlTransformCompute**:动态拼的业务 SQL(INSERT..SELECT 到 staging)在 Citus 的行为
- [ ] **service 层动态 SQL**:mapper XML 静态扫不到的运行时拼接(各业务 service)
- [ ] **业务库 part 的 biz.* 写入**:worker 写业务库(batch_business_part),该库是否也要 Citus 化(当前是普通 PG part 库,业务表未分布——多租户洪峰才需要)
- 每撞一个新约束,按 5 类模式(FOR UPDATE/complex join/IMMUTABLE upsert/CASE-COALESCE/partition col)清,双栈验证后提交

## 已知待清(subagent 误判 + 未现形)
- pipeline_progress 已清(b833b1717);FileGovernance maxProcessingDelay/staleSteps 已清
- selectProcessingDelaySamples(FileGovernanceMapper SELECT 列表 extract(current_timestamp)):
  非 CASE/COALESCE,Citus 可能允许,harvest 验;不行则同样传参
- 其他业务专属 upsert/CASE-COALESCE:跑到才暴露,逐个清

## 真实数据严格验证(worker SUCCESS 后)
- strict-verify.sh 适配 Citus(PG_CONTAINER/PG_PORT 指 coordinator):cursor/offset 一致性、
  审计落表、cron-preview、maintenance、§3b outbox 重复事件——在分布式表上复验
- 多租户洪峰(citus-introduction-plan 门槛③):N 租户并发各跑批,观测分片均衡 + SLA

## sim 实测发现:import 撞第 6 类约束(设计级,2026-06-12)
import launch→CLAIM→pipeline_instance(RUNNING)全通(共享链路已清),task FAILED 卡在
`PlatformFileRuntimeMapper.insertStepRun`(import/process 共享):
```
column "pg_advisory_xact_lock" has pseudo-type void
```
**第 6 类约束:advisory-lock CTE 在 Citus 分布式 planner 不允许(void 函数作 CTE select 列)。**
该 CTE 用 pg_advisory_xact_lock 串行化生成 run_seq(防同 (instance,step) 并发重复)。
**这是并发设计级改动,非一行 SQL**——留白天清醒时做,方案选项:
- A. 去 advisory lock,靠 uk_pipeline_step_run(V179 已加) + 应用层 DuplicateKey 重试(同 step 并发概率低,实际安全;推荐)
- B. advisory lock 拆独立语句(INSERT 前 SELECT lock);Citus 单分片下 lock 生效需验
- C. run_seq 应用层生成传入(需保证唯一)
做完 import + process 都受益(共享此 mapper)。export/dispatch 未跑,同 harvest 模式逐个验。

### 第 6 类约束已清(2026-06-12,方案 A 落地)
`insertStepRun` 去 advisory-lock CTE 改纯 VALUES;run_seq 复用已有 `selectNextStepRunSeq`(补 tenant_id 过滤,
原是 W4 盲区)在 `PlatformFileRuntimeRepository.startStepRun` 的 5 次 DuplicateKey 重试循环内取。
**真集群实证**:import RECEIVE→SUCCESS、PREPROCESS→SUCCESS,5 个 step_run 在 distributed 表插入成功,
`pseudo-type void`/`subqueries not supported` 报错数=0。第 6 类彻底清除。
(commit 30d986b46 inline-subquery 版被 Citus 拒"subqueries within INSERT",最终 fed2bcf90 纯 VALUES 版通过。)

## sim 实测发现:orchestrator 撞第 7 类约束(complex join 共址,2026-06-12)
import 推进后,orchestrator **调度 sweep 线程**(batch-scheduler)反复报:
```
ERROR: complex joins are only supported when all distributed tables are co-located and joined on their distribution columns
```
并连坐打满连接池(active=6/6,Connection is not available)→ heartbeat 500 / launch 空响应 / 任务 Kafka 毒丸重投。
**根因**:`FileGovernanceMapper` 两个 stale-sweep UPDATE 把两张 distributed 表按**非分布列**关联:
- `markRunningPipelineStepsFailedForInstances`:`update pipeline_step_run psr ... exists(select 1 from pipeline_instance pi where pi.id = psr.pipeline_instance_id)`——join 在 id 上、外层 psr 无 tenant_id 路由。
- `markStaleRunningPipelineInstancesFailed`:`update pipeline_instance pi where pi.id in (select id ...)`——外层无 tenant_id 路由。

**已清(第 7 类,同 complex-join 模式)**:外层补 `tenant_id = #{tenantId}` 路由到单租户分片 +
EXISTS 子查询补 `pi.tenant_id = psr.tenant_id` 共址条件,让关联落在分布列上。

### ⚠️ 仍待清(设计级,latent 未现形):SuccessInstanceArchiveMapper 归档清扫
`selectArchivableInstanceIds` 返回**全局 id 列表(无 tenant)**,随后跨表归档/级联删:
`deletePipelineStepRunsByInstanceIds` / `deleteFileDispatchRecordsByInstanceIds` / `deleteWorkflowNodeRunsByInstanceIds` /
`deleteJobStepInstancesByInstanceIds` 都是 `xxx_id in (select id from <另一 distributed 表> where ...)` 的**非共址子查询删**,
`archivePipelineStepRunsByInstanceIds` 等 INSERT..SELECT 同样跨分片关联。Citus 上会同样报 complex-join。
**当前未现形**:归档按 retentionDays cutoff 触发,新集群无 finished_at 够老的实例,故不 fire。
**改造代价大(设计级)**:整个归档流程需**租户分片化**——selectArchivable 返回 (tenant_id, id) 对,
所有 archive/delete 语句带 tenant_id 路由 + 共址(改 mapper 签名 + SuccessInstanceArchiveService 线程化 tenant)。
留作真实分库分表启用前的专项。方案:按 tenant 分组批量,每组单租户路由清扫。

## 收尾
- 全部 worker SUCCESS + 真实数据绿 → 完整 e2e 在 Citus 上(可选,需 e2e app 连 coordinator)
- 双栈零回归终审(普通 PG)+ 推分支(不合 main)
