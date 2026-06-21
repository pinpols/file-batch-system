# 后端多角度深度审计 — 修复落地 + 交接 spec(2026-05-28)

> 分支:`feature/be-audit-fixes`(off `main`)。本文档既是 PR 正文来源,也是三项"不能在本分支盲改"事项的精确交接 spec。
>
> **验证状态图例**:✅ 编译+逻辑核验(本地磁盘紧张,未跑 testcontainers IT) · 🧪 需集成测验证后再合 · 📋 spec(交接,本分支不落代码)

---

## 一、已修(✅ 随 PR 提交)

| # | 级别 | 文件 | 改动 |
|---|---|---|---|
| HIGH-3 | 高 | `batch-worker-core/.../mapper/PlatformFileRuntimeMapper.xml` | `selectLatestTemplateConfig` 漏 `is_deleted=false`,软删模板仍被 worker 取用 → 补软删过滤 |
| HIGH-4 | 高 | `batch-console-api/.../infrastructure/file/DefaultConsoleFileChannelApplicationService.java` | `create()` 预检 `selectByUniqueKey`(只看活跃)后用纯 `insert`,撞软删残留行的 UNIQUE → 500;改 `upsertFileChannelConfig`(`on conflict … is_deleted=false` 复活),与 notification/webhook/alert 三兄弟表软删复活约定一致 |
| MED | 中 | `batch-console-api/.../mapper/FileChannelConfigMapper.xml` | `updateFileChannelConfig` 的 WHERE 漏 `is_deleted=false` → 补 |
| HIGH-1(部分) | 高 | `batch-orchestrator/.../mapper/TriggerRequestMapper.xml` + `TriggerRequestLaunchReconciler.java` | 见 §三-A:reconciler 扫描 SQL 加 `ji.instance_status <> 'CREATED'`,堵住"滞留实例被谎报 LAUNCHED"的掩盖路径 |
| HIGH-2(业务侧) | 高 | `batch-common/.../config/BatchProfileSupport.java` + `BatchSecurityPropertiesTest.java` | 见 §三-B:`isProductionProfile` fail-open → fail-secure(空/未知 profile 当生产) |
| MED | 中 | `batch-console-api/.../config/ConsoleSecurityProperties.java` | `defaultAuthorities` 兜底从 `[ADMIN, AUDITOR, TENANT_ADMIN]` 收敛到最小权限只读 `[AUDITOR]`,堵"空角色 SSE ticket / bypass 无 role header → 静默拿 admin"提权。**破坏面**:bypass-mode/E2E 中做写操作的请求需显式带 role header,CI staging-gate Playwright 会兜住 |
| LOW 红线 | 低 | `ConsoleAdminTestDataController.java` / `ConsoleJobQueryService.java` | `java.util.Arrays.stream(...)` FQN → `import java.util.Arrays` |
| LOW 红线 | 低 | `batch-worker-dispatch/.../channel/RemoteFilesystemDispatchSupport.java` | Callable 内 `throw new RuntimeException(ioe)` → JDK 语义化 `UncheckedIOException`(仍是 RuntimeException 子类,下游 ExecutionException 解包分支照旧命中) |
| HIGH-5(部分) | 高 | `CLAUDE.md` | 多租隔离守护引用 drift:`TenantIsolationIntegrationTest`(不存在)→ 实际 `MultiTenantIsolationIntegrationTest` + 各模块 `MapperXmlTenantGuardArchTest` |

---

## 二、审计后判定为"误报 / 正当例外 / 已实现"——未改(附理由)

| 标记项 | 判定 | 理由 |
|---|---|---|
| retry `dispatchDueRetries` 缺 `@SchedulerLock` | **已实现** | 触发器 `RetryScheduleScheduler.poll()` 已带 `@SchedulerLock(name="retry_schedule_poll")` |
| `@Autowired` field 注入(`BatchSecurityProperties:38`/`ConsoleSecurityProperties:116`) | **正当例外** | `@ConfigurationProperties` 内注入 `Environment` 的既定写法,`required=false` 保单测兼容;改构造器注入破坏绑定语义 |
| `@Autowired`(`OrchestratorGracefulShutdown` / `HttpTaskExecutionClient` / `AbstractPipelineStepExecutionAdapter`) | **误报** | 分别是 `required=false` 可选依赖、构造器参数注入、以及一条"上次已修"的注释 |
| `ConsoleQuerySupport:241` `java.util.Date` FQN | **正当消歧** | 同文件已 `import java.sql.Date`,两个 Date 类型冲突,此处 FQN 必要 |
| `renewLease` 缺 `partition_status` 守护 | **低价值,跳过** | 危险场景(reclaim 后续他人租约)已被 `current_invocation_id` 强制守护 + reclaim 仅作用于 READY/RUNNING(`JobPartitionMapper.xml` L207)双重覆盖;补该守护需改 Java 调用方+mapper 签名,边际价值低、surface 大 |

---

## 三、交接 spec(本分支不落代码 / 需协调或 IT)

### A. 🧪 HIGH-1 静默丢作业 — 自动重驱恢复器(已堵掩盖,自动恢复待 IT)

**根因**:`DefaultLaunchService.launch()` 把落地拆成两段独立提交事务:
- **T1** `prepareJobInstance`:建 `job_instance`(=CREATED)/`workflow_run`,提交。
- **T2** `dispatchAndMarkLaunched` → `PartitionDispatchService.dispatch()`(`@Transactional`,原子):建 partition/task + 写 outbox + 推进状态到 RUNNING/WAITING,并把 trigger 标 LAUNCHED。

T2 崩溃(进程被杀等)→ instance 滞留 `CREATED` 且**零 partition**(T2 原子,回滚干净)。`maybeShortCircuitDuplicate` 用无状态过滤的 `existingInstance` 短路,Kafka 重投时直接返回这条滞留实例 → 作业永不真跑,**静默丢失**。

**本 PR 已做(✅)**:`TriggerRequestLaunchReconciler` 的 `selectStaleAcceptedWithJobInstance` 原先不过滤实例状态,会把滞留 CREATED 实例也 JOIN 出来、`reconcileLaunched` 谎报成 LAUNCHED 掩盖丢失。已加 `and ji.instance_status <> 'CREATED'`:滞留实例保留为陈旧 `ACCEPTED` → 进入 ADR-010 既定的"过期 ACCEPTED 告警 + 人工/恢复"路径。

**待做(🧪 需 IT)——二选一,推荐 B**:
- **方案 A(自动重驱 T2)**:新建 `LaunchDispatchRecoveryScheduler`(`@Scheduled` + `@SchedulerLock`),捞 `instance_status='CREATED' AND created_at < now()-阈值(默认 300s)` 且**零 partition** 的实例,从 `paramsSnapshot`/`trigger_request`/`workflow_run` 重建 `PreparedLaunch` + `LaunchRequest` + `effectiveParams`,重入 `dispatchAndMarkLaunched`。
  **风险**:`dispatch()` 非幂等(`createPartitions` 普通 insert + `markRunning` version-CAS);必须严格保证"零 partition"前置,否则部分派发的实例会双重派发。并发(同一实例被两 orchestrator 同时重驱)靠 `@SchedulerLock` + 重建后再查零 partition 兜。**必须有 testcontainers IT 覆盖 Dedup E2E + 崩溃重驱场景才能合。**
- **方案 B(标记可见,推荐,且与 ADR-010 一致)**:同样的捞取查询,但**不重驱**,把滞留实例经 orchestrator 状态机标 `FAILED`(`error.launch.dispatch_stranded`)+ 打 metric。丢失从"静默"变"可见可告警可手动 RERUN",零双重派发风险,契合 ADR-010 "滞留→告警+人工"的既定设计哲学(`TriggerRequestLaunchReconciler` javadoc L33-34 明示)。

> 注:`maybeShortCircuitDuplicate` 本身**不要**改成内联重驱——并发重复请求窗口会双重派发。恢复必须走带时间阈值的独立调度。

### B. ✅/📋 HIGH-2 prod fail-secure — 跨分支协调(业务侧已改,部署分支待补)

**业务侧(✅ 本 PR)**:`BatchProfileSupport.isProductionProfile` 改 fail-secure——
- 含 prod-like profile → 生产;
- 含已登记非生产 profile(`dev/development/local/test/e2e/ci/integration/it/sit`)且无 prod-like → 非生产;
- 其余(空激活集 / 仅未知 profile)→ **按生产对待**。

防"部署到生产却忘配 `SPRING_PROFILES_ACTIVE`"时 fail-open 放行 bypass-mode/弱密钥。已同步更新 `BatchSecurityPropertiesTest`(`unknownProfile`/`emptyProfile` 现期望抛、新增 `e2eProfile` 放行)。

**⚠️ 部署分支待补(📋,在 `feature/docker-deploy` 上做,顺序见下)**:当前 Docker compose(`docker/compose/app.yml`)6 个服务只设 `BATCH_SECURITY_BYPASS_MODE`、**不设 `SPRING_PROFILES_ACTIVE`**,而 `.env.local` 里 `BYPASS_MODE=true`。本 PR 合并并部署后,fail-secure 会判该部署为"生产"→ 启动期守护抛 `IllegalStateException` **拖垮线上部署**。

必须在 `feature/docker-deploy` 上补(本业务分支**不能**碰 compose,CLAUDE.md 两分支绝不互 PR):
```yaml
# docker/compose/app.yml —— 6 个 batch-* 服务的 environment 各加一行
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-local}
```
```dotenv
# .env.local 与 .env.example 各加
SPRING_PROFILES_ACTIVE=local
```
**合并顺序**:先把上面 compose 改 sync/部署到位 → 再合本业务 PR。否则中间态会让在跑的 Docker 部署启动失败。

### C. 📋 tenant_id 加列迁移 — 你做 Flyway,以下是 spec + 配套 Java

**先厘清:这不是"缺少鉴权保护无隔离"的正确性 bug。** 三张表是**经父表 FK 间接租户隔离**的子表,CLAUDE.md "所有业务表必带 tenant_id" 的绝对表述,这三张很可能正是隐含的"经父表 scope"例外:

| 子表(无 tenant_id) | 建表 | 父表(有 tenant_id) | 关联列 |
|---|---|---|---|
| `workflow_node_run` | V5 | `workflow_run` | `workflow_run_id` |
| `pipeline_step_run` | V6 | `pipeline_instance` | `pipeline_instance_id` |
| `pipeline_step_definition` | V6 | `pipeline_definition` | `pipeline_definition_id` |

直接加 `tenant_id` 是**反范式纵深防御**(查询不再依赖父表 join 才能保证隔离)。是否做、何时做由你定;若做,迁移 spec 如下(最新版本 V153,新迁移从 **V154** 起):

1. **加列 + 回填(每张表一支迁移,从父表回填)**:
   ```sql
   -- 例:workflow_node_run(V154)
   alter table batch.workflow_node_run add column tenant_id varchar(64);
   update batch.workflow_node_run wnr
      set tenant_id = wr.tenant_id
     from batch.workflow_run wr
    where wnr.workflow_run_id = wr.id and wnr.tenant_id is null;
   alter table batch.workflow_node_run alter column tenant_id set not null;
   create index ix_workflow_node_run_tenant on batch.workflow_node_run(tenant_id);
   ```
   `pipeline_step_run`(从 `pipeline_instance` 回填)、`pipeline_step_definition`(从 `pipeline_definition` 回填)同构。
2. **UNIQUE 约束含 tenant_id**:若三表上有不含 tenant_id 的 UNIQUE(如 `pipeline_step_definition` 的 `(pipeline_definition_id, step_code)`),按 CLAUDE.md 改为 `(tenant_id, …)`。
3. **归档镜像同 PR 补**(CLAUDE.md「archive 冷表对齐」+ 启动期 `ArchiveSchemaDriftCheck` fail-fast):`archive.workflow_node_run_archive` / `archive.pipeline_step_run_archive` / `archive.pipeline_step_definition_archive`(在 V71 系列创建)各补同名 `tenant_id` 列 + 回填。**漏补归档列会导致全系统启动 fail-fast。**
4. **配套 Java(迁移落库后再随后续 PR 改,本分支不动)**:
   - entity 加字段:`batch-orchestrator/.../domain/entity/WorkflowNodeRunEntity.java`、`batch-console-api/.../domain/entity/WorkflowNodeRunEntity.java`、`batch-worker-core/.../domain/PipelineStepDefinition.java`(+ `PipelineStepDefinitionParam`)、以及 pipeline_step_run 对应 entity。
   - mapper:`PlatformFileRuntimeMapper.xml`(worker-core)`insertStepRun`/`insertPipelineStepDefinition`/`selectPipelineStepDefinitions`、`PipelineStepDefinitionMapper`(console-api)等的 INSERT 补列、SELECT/UPDATE 谓词补 `tenant_id`。
   - 守护:`MultiTenantIsolationIntegrationTest` + 各 `MapperXmlTenantGuardArchTest` 相应更新。
5. **风险**:大改 + 回填 + 归档对齐 + 约束变更,**必须 IT**(本地磁盘跑不了 testcontainers)。建议单独 PR、单独 review。

---

## 四、HIGH-5 arch-test 盲点(📋 需迭代跑测,本分支未收紧)

`MapperXmlTenantGuardArchTest`(console-api + orchestrator 各一份)只静态抓"可空 `<if tenantId>AND tenant_id=#{tenantId}`"模式。两处盲点:
1. **抓不到"完全不带 tenant_id"的 DML**(`if(!content.contains("tenant_id")) return` 直接跳过)。收紧需解析 SQL + 大量豁免(join/聚合视图/4 张系统表),盲改必让 CI 变红 → 需能跑测迭代豁免名单。
2. **覆盖缺 worker/trigger 模块**(已确认这些模块 mapper 当前都不用危险模式,故现状无漏,仅缺回退防护)。可给 worker-core/trigger 各加一份 arch test(零违规、立即通过),但属低 ROI 的纯防回退。
