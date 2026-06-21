# 变更记录（CLAUDE.md 规范条款变化）

> 本文件只记录 **CLAUDE.md 编码规范条款本身** 的变化（命名约定、版本策略、领域字典、模块边界、架构硬约束等文档自身内容变动）。
>
> Feature 完成、bug 修复、运维操作、临时数据动作等项目演进信息**不要**写到这里——那些以 git commit + PR 描述 + 对应模块文档（`docs/architecture/*.md`、`docs/runbook/*.md`、`docs/analysis/*.md`）为权威记录。
>
> 按日期倒序，使用绝对日期（`YYYY-MM-DD`）。

### 2026-06-14
- **CLAUDE.md §分支用途 改写:`citus` 分支冻结为只读参考**。原"2 条常驻活分支(main + citus 并排活跃轨道,定期 main→citus 同步)"改为"唯一常驻活分支 = main;citus 冻结(❄️ reference-only,停止同步,不再开发)"。决策依据:多租洪峰单机压测(`docs/verifications/multitenant-peak-single-node-ceiling-2026-06-13.md`)实测瓶颈在控制面分层并发(launch 消费 + worker 认领,已修 20→62/s),**PG 写有 10-15× 余量、零锁争用 → Citus 解决的是未来才有的写墙,当前非杠杆**;且 biz 分区先于 biz 分片、真要上有 Azure 托管 Citus 路径 B。citus 降级为"时间点 POC + 薄保险",快照 tag `citus-poc-2026-06-14`;耐久学习资产在 `docs/{backlog,analysis,runbook,design}` + `scripts/db/citus/01-distribute.sql`,不在活分支。新增**解冻流程**(重审 main delta Citus 正确性 + 重跑 distribute + 重跑 sim)。`citus → main` 永不合(一直如此,不变)。main 的「新多租大表复合 PK 前瞻」规则继续生效以压低将来解冻成本。

### 2026-06-10
- **CLAUDE.md §架构硬约束 新增「UNIQUE = upsert 幂等契约承重墙」**:全仓 56 处 `ON CONFLICT` 把幂等承重在全局 UNIQUE 上,改任何 UNIQUE 列集(分区/分片/重建/迁移)= 语义变更而非运维操作,动手前必须 `grep 'on conflict'` 全量核对 + 幂等语义评审。背景:2026-06-10 分区脚本实跑,分区键被迫进 UNIQUE 打破 `ON CONFLICT (tenant_id,event_key)`,orchestrator outbox 写入全失败、主链中断后回滚(PR #448)。此前该假设是隐性的——本条款将其显式化为权威约束。
- **CLAUDE.md §多租隔离 新增「新表 PK 前瞻」**:新建多租大表 PK 一律复合 `(tenant_id, id)`(或含分区键),禁单列 `id` PK。理由:Citus 可行性实扫(`docs/backlog/citus-introduction-plan-2026-06-06.md` §0.5,2026-06-10 复核仍成立)确认存量 23 张表单列 PK 是最大迁移阻塞(复合化重构估 12-20 周,`useGeneratedKeys` 已从 43 涨到 49 处),新表止血控制阻塞面增速。小字典/配置/系统表豁免。存量表**不迁移**(等 Citus 触发门槛满足后按 POC 推进)。

### 2026-06-01
- **CLAUDE.md §Java 编码细则 #3 添测试豁免**:`@Autowired` field 注入除了原有的 `@Lazy self` AOP workaround 例外,新增第二类豁免 — `@SpringBootTest` IT 测试。理由:全仓 IT 测继承 `AbstractIntegrationTest` 走 Spring 测试惯例,77 处 `@Autowired private Foo foo;` 已是事实标准,Spring `@SpringBootTest` 下构造器注入需要额外 ParameterResolver 协调且生态不偏好。本规则只豁免 IT 测试,生产代码继续严格执行构造器注入。最近 2 天扫到 17 处看似违反实为这条豁免覆盖,不予迁移。

### 2026-05-21
- **ADR-032 控制台 4 角色 RBAC 重设计 落地**:`ROLE_CONFIG_ADMIN` 合并升级为 `ROLE_ADMIN`(V149 自动迁移),新增 `ROLE_TENANT_ADMIN`(本租户管理员,租户自服务发放员工)。新矩阵:平台 ADMIN/AUDITOR × 租户 TENANT_ADMIN/TENANT_USER。`ConsoleUserAccountService` 增 4 层守卫(Service 强制注入 tenantId / 跨租户拒绝 / 角色越授拒绝)。`canSwitchTenant` 收敛为 ADMIN+AUDITOR,TENANT_ADMIN 绑定自己租户。详见 `docs/architecture/adr/ADR-032-four-role-rbac-redesign.md`。
- **CLAUDE.md §测试约定 新增**:扫 454 单测 + 23 IT(最近 3 天 diff)归纳已成事实的统一项 + 新代码标准:JUnit5 + AssertJ + Mockito 三件套已 100% 统一(无 JUnit4 / Hamcrest / EasyMock 混入);新单测一律 `@ExtendWith(MockitoExtension.class)` 声明式 mock 初始化(避免命令式 `openMocks`);方法命名首选 `shouldX_whenY()`,接受现存 `xxx_when_yyy()` 下划线风格;集成测必须 `extends AbstractIntegrationTest` 复用 Testcontainers base;`@DisplayName` / `@Nested` / AAA 注释推荐但不强制。**不做大规模 rename**(跟平行会话冲突风险高),只锁约定止血。
- **`docs/coding-conventions.md` §1 新增子节 1.1「调用方约束(inline build 提取)」**:CLAUDE.md §Java 编码细则 #2「方法参数 ≤ 6」长期只覆盖签名,调用现场 `f(X.builder().a()...build())` 长链让"参数臃肿从签名搬到调用处"的隐患由 `PositionalArgsConventionTest` 拦白名单 51 个类型,但 return 位置和非白名单类型一直靠 review 隐性把关。本次沉淀判定线为 chain 长度表(≤3 单行禁提取 / 4-6 单行 fluent 禁提取 / 7-9 看场景 / ≥10 必须提取),并明确豁免(SDK args / 短工厂方法 / 声明式注册 / test fixture)。配套治理基线落 commit `76582aef`(11 处 return chain≥10)。守护边界:`PositionalArgsConventionTest` 不变(继续只拦白名单方法实参 inline build),return 位置因每项目语义不同,靠 review 按判定线手工把关。

### 2026-05-20
- **CLAUDE.md §Java 编码细则补 quick-ref 表**(116 → 128 行):上一轮瘦身把太多规则下沉到 `docs/coding-conventions.md`,但 Claude 只 baseline 加载 CLAUDE.md → 下沉的规则不会自动遵守(实际见到 FQN / 构造器注入 / CommonResponse 包装等高频违反)。本次把**最常被违的 10 条**改成 10 行表格回放(每条 1 行 + 反例片段),细则仍指针化到 `docs/coding-conventions.md`。同时把时区 / 编码 2 条"禁用"红线从分散段落合并到 §Java 编码细则末尾,统一一处可扫。
- **CLAUDE.md 整体瘦身**(337 → 116 行,-65%):按 Anthropic CLAUDE.md 最佳实践重构 —— 本文件只装「不能从代码推断的约束」+「高频违反的红线」+「关键路径指针」,其余细节(详细规则表 / 反例 / 完整字典清单 / ADR 三阶段优先级表)下沉到 `docs/coding-conventions.md` / `docs/architecture/` / `docs/runbook/` / `docs/design/` 子文档。新增 §模块 / §构建 顶部 2 节(原先缺失关键运行环境信息)。**没有规范变化**,仅收纳位置调整 —— 所有规则的权威源仍是各对应 `docs/*` 子文档(本文件相应章节末以「详见 …」指引)。

### 2026-05-16
- **CLAUDE.md §模块边界**:`batch-config-defaults` 从固定模块列表中**移除**(ADR-029 修订版反向重构,业界主流不为单个 yml 单建模块)。共享配置基线 `batch-defaults.yml` 回到 `batch-common/src/main/resources/`,由 `ConfigDriftGuardTest` 守护 classpath 存在性 + OWNED_KEYS 漂移。9 模块 pom 全部移除显式 dep,reactor 不再含 `batch-config-defaults`。

### 2026-05-15
- **CLAUDE.md §模块边界**：补 `batch-config-defaults` 到固定模块列表（ADR-029 新增的 resources-only 共享配置基线模块，R2 已落地但 CLAUDE.md 漏更）。R4-P1-10 修复。

### 2026-05-07
- **CLAUDE.md §版本管理重写**：从"默认 1.0.0 非 SNAPSHOT"改为完整 SemVer 2.0.0 + `${revision}` 模型；约定 `MAJOR.MINOR.PATCH[-PRERELEASE]` / `-SNAPSHOT` 是 main 分支默认形态 / git tag 用 annotated `v<version>` / 描述性 tag 与版本 tag 共存。新增 [`docs/runbook/releasing.md`](runbook/releasing.md) 落地完整 release flow（标准 / hotfix / RC / patch / Maven 命令速查 / SemVer 判定提问 / FAQ）。明确**不抄 Spring Cloud Release Train / CalVer**：单 repo 单 PR 单部署 → 9 模块共 `${revision}` 已是主流；BOM 模块也不引入。当前 GA = `v1.0.0` @ commit `525e60f0`，main 默认 `1.1.0-SNAPSHOT`。
- **CLAUDE.md 新增 §ADR 实施范围纪律（防越界）**：写死系统定位"批量运行控制面 + 文件 / 任务交付闭环"，**不**扩张为数据治理 / K8s scheduler / 合规审计平台；列三阶段优先级（P0 ADR-012/023/025；P1 ADR-021/022/026；P2 ADR-024/027 暂缓）；列 4 个最高越界风险 ADR（021/022/026/027）的判定提问 + 一句话越界红线；PR 评审硬规则要求实施方答判定提问 + 引用 ❌ 不做清单。权威源 = 各 ADR 顶部"范围边界（Scope Discipline）"小节 + `docs/analysis/adr-012-021-027-priority-scope-2026-05-06.md` §5。
- **CLAUDE.md §archive 冷表对齐**：覆盖范围由 "14 张" 修正为 17 张（V108 加 result_version；V110 加 batch_day_replay_session + batch_day_replay_entry）；新增 V116 forensic_export_log / V118 data_quality_rule / V118 data_quality_check 暂未入 `ArchiveSchemaDriftCheck.ARCHIVED_TABLES` 的事实说明（运维域 + 时间点决定是否归档）。

### 2026-05-04
- **CLAUDE.md §时区策略**：控制台 `ConsoleQuerySupport.parseFlexibleInstant*` 与 `BatchTimezoneProvider.defaultZone()` 对齐，删除「可保留 `ZoneId.systemDefault()`」豁免表述；实现上为解析方法增加 `ZoneId` 参数，`ConsoleJobQueryService` / `ConsoleOpsQueryService` 注入 provider 传入默认区。
- **CLAUDE.md §时区策略 / §字符编码 + 运维样例 env**：`.env.example` 以 **`BATCH_TIMEZONE_DEFAULT_ZONE`** 为唯一时区变量（`docker-compose` 的 `TZ`/`PGTZ` 从该变量插值，兼容显式 `TZ=`）；**`BATCH_LOCALE`** 为进程/中间件 locale 唯一变量；本地 `start-all.sh`/`restart.sh` 导出 `TZ`←`BATCH_TIMEZONE`、`LANG`/`LC_ALL`←`BATCH_LOCALE`；`.env.prod` / `check-env-prod-sync` 白名单同步。

### 2026-05-03
- **CLAUDE.md 新增 4 条硬约束 + V82-V85 schema 落地（PG schema 审计 2026-05-03 收尾）**：
  - **§多租隔离**：所有业务表必须含 `tenant_id`，所有 UNIQUE/PRIMARY 约束必须含 `tenant_id`（4 张系统表豁免：`batch_runtime_default_parameter` / `step_registry` / `shedlock` / `biz_table_schema`）。配套 V84/V85 给 `workflow_node` / `workflow_edge` 加 `tenant_id` 列 + 改唯一约束 + 索引；同 PR 同步 entity / mapper.xml / 4 个 production callers / 4 个 test seed。
  - **§archive 冷表对齐**：`ArchiveSchemaDriftCheck` 启动期双向 diff 14 张归档对照表，差异即 fail-fast；任何 `ALTER TABLE batch.* ADD COLUMN` 必须同 PR 补 archive 镜像。
  - **§异步事件路由政策**：三张异步表（`outbox_event` / `event_outbox_retry` / `trigger_outbox_event`）职责边界固化，禁止相互复用，禁止新建第 4 张同义表；新事件类型按决策树选型。配套：`docs/architecture/event-routing-policy.md`。
  - **§Pipeline vs Workflow vs Job 边界**：三套体系职责切分清楚，禁止 UNION 跨表查询，pipeline_instance 只读，workflow_run 支持人工干预，job 是调度最小单元。配套：`docs/design/pipeline-vs-workflow-definition.md`。
  - 配套：V82 `job_step_instance.uk_job_step_instance_task` 加 tenant_id（纯约束变更）；V83 `trigger_outbox_event` UNIQUE INDEX → CONSTRAINT（对齐 SQL 标准）；新增 `docs/design/status-state-machines.md` 汇总 13 个状态机；审计报告 `docs/analysis/pg-schema-audit-2026-05-03.md`（13 维评分卡 + P0/P1/P2 + 修复草案）。
- **CLAUDE.md 配置开关规范移除 `batch.trigger.async-launch.enabled`**（ADR-010 异步链路固化）：同步 HTTP 桥（`HttpOrchestratorTriggerAdapter`、`OrchestratorTriggerAdapter` 接口、`TriggerForwardRetryScheduler`）已删除，异步路径成为唯一链路，开关下线。CLAUDE.md "配置开关规范" 段落更新为"ADR-010 trigger 异步链路（已固化，无开关）"。

### 2026-05-02
- **全平台移除 Spring Data JDBC + CLAUDE.md 架构硬约束同步**：所有业务模块（含 `batch-orchestrator`）禁止 `spring-boot-starter-data-jdbc` 与 `@EnableJdbcRepositories`；orchestrator 删除 `WorkerRegistryJdbcConfiguration` 及实体上 SDJ 映射注解，配置表与运行态一律 MyBatis；E2E 入口去掉 JDBC 仓库扫描；CI `check-dependency-boundaries.py` 将 orchestrator 纳入禁止 data-jdbc 列表。ADR-001 改为「仅 MyBatis + JdbcTemplate」。**追加**：CLAUDE.md 明确 `domain/entity` 统一 `*Entity`、禁止 `*Record` 栈分工与同表双写；README / `docs/coding-conventions.md` §9 / `docs/design/tech-stack-and-principles.md` / `docs/architecture/architecture-truth.md` / `docs/agent-baseline.md` §7 / `docs/design/project-structure-pom.md` / `scripts/ci/README.md` / `docs/design/README.md` / `docs/design/capability-assessment.md` / `docs/architecture/rework-classification.md` / `docs/compliance/THIRD-PARTY-LICENSES.md` / 根 `CHANGELOG.md` 与 `docs/changelog.md` 历史条勘误等活文档同步剔除双 ORM 与 `*Record` 规约。

### 2026-05-01
- **CLAUDE.md §方法参数约束 追加"调用方约束"子节**（V6-P2-POSITIONAL-ARGS 治理方案 v3）。第一阶段"方法参数 ≥7 必须封装"落地后，参数臃肿从方法签名搬到 `new XxxParam(a,b,...,n)` inline 调用，main 遗留 61 处反例（① 方法签名 argc=7 共 7 处 / ② inline argc>6 共 54 处）。新增 2 条规约 + 2 条豁免：(1) 调用方按构造参数数 `argc` 分档治理——`argc>6` 必须 `@Builder` + 提取引用变量 + 默认值不显式 set；`argc≤6` **不约束**（业界无依据：Effective Java / Google Style / Oracle Conventions 都未禁止 `f(new Foo(a,b,c,d,e,f))`）。(2) 加 `@Builder` 时**禁止降级**到"仅提取引用"——class 隐式空参用 `@NoArgsConstructor` + `@AllArgsConstructor` 三连或 `@Tolerate` 注解兜底，不破坏反射构造路径（Jackson / MyBatis / Spring `@ModelAttribute`）；Spring Data JDBC entity / `@Entity` / `@Table` 持久化类一律不加 `@Builder`。(3) **豁免 1**：声明式注册类（`*Registry` / `*SchemaRegistry` / Spring `@Configuration` 列表 / `List.of(new Foo(...))` 等）的 inline new 是业界鼓励的可读写法，不算反例。(4) **豁免 2**：**Spring Data JDBC / JPA `@Modifying @Query` 接口方法多 `@Param`** 是框架契约（`:paramName` 命名参数解析依赖位置 `@Param`，bean property 引用未原生支持），保留多 `@Param` 形式即可；MyBatis mapper 可封装（原生支持 `#{p.field}`），但不强制。配套：`docs/analysis/positional-args-cleanup-plan.md` v3 治理方案 + `PositionalArgsConventionTest` 守护测试白名单方式拦回潮 + hardening-backlog `V6-P2-POSITIONAL-ARGS` 索引同步。

### 2026-04-30
- **`batch.trigger.async-launch.enabled` 默认从 `false` 切换为 `true`**（CLAUDE.md "配置开关规范" 同步）。新部署默认走 ADR-010 trigger → outbox → Kafka → orchestrator 异步链路，不再走同步 HTTP 桥。配套：`DefaultTriggerService.java` `@Value` fallback 改 `true`；trigger / orchestrator 5 处 `@ConditionalOnProperty` 加 `matchIfMissing=true`；`docker/compose/app.yml` trigger 服务加 `BATCH_KAFKA_BOOTSTRAP_SERVERS=kafka:29092` env + `depends_on: kafka`；`.env.local` / `.env.example` / `init-kafka-topics.sh` 默认 `KAFKA_TOPICS` 加 `batch.trigger.launch.v1`。**回退路径**：显式 `BATCH_TRIGGER_ASYNC_LAUNCH_ENABLED=false` 重启 trigger + orchestrator（必须两侧一致）。**容器 smoke 发现的关键 bug**：原 docker-compose trigger 段没有 `BATCH_KAFKA_BOOTSTRAP_SERVERS` env，trigger 用 batch-defaults.yml fallback `localhost:19092` 在容器网络里连不到 Kafka — 已修复。
- **CLAUDE.md 加 i18n 错误码规范**：业务异常一律走 `BizException.of(ResultCode.XXX, "error.<scope>.<reason>", args...)`，旧 literal 构造器 `new BizException(code, message)` 仅 Guard 等工具类签名豁免；key 命名 `error.<scope>.<reason>` 全小写 snake_case；占位符 `{0}`/`{1}` 与 args 顺序一一对应；双语强制（`messages.properties` + `messages_zh_CN.properties` 1:1 对齐）；持久化层实体实现 `LocalizedErrorCarrier` 接口，11 表的 `error_key`/`error_args` 列由 `BizExceptionUtils.toLocalizedError` 自动填充，console 读路径过 `LocalizedErrorRenderer` 按 Locale 重渲染。配套：worker step plugin 73 处 failure 迁三元组（`c74a9644`），V78 八表的 errorKey/errorArgs 列在 step 失败路径上真正生效；business 路径 56 文件横扫迁 i18n key（`23137b2c`）。详见 `docs/design/i18n.md` §5.1 落地阶段历史 + §6 已知 gap。
- **CLAUDE.md 加 Workflow 节点参数 DSL 规范**（ADR-009）：`workflow_node.node_params` JSONB value 支持受限 JSONPath 引用上游节点产出，由 `WorkflowParamResolver` 在派发前解析。语法白名单仅 `$.nodes.<nodeCode>.output.<key>`（嵌套 `.` 下钻）+ `$.workflowRun.<key>`，**不支持** 通配符 `*` / 过滤 `[?]` / 函数 / 表达式。Worker 暴露 output key 按业务领域固定（IMPORT: fileId/recordCount/...; EXPORT: fileId/objectName/...; PROCESS: processedCount/batchKey/...; DISPATCH: receiptCode/channelCode/...）。Fail-mode：未知 nodeCode / 路径语法非法 → `BizException(error.workflow.param_ref_invalid)` 拒绝节点启动；output 字段缺失 → null fallback。配套 schema：V72 `workflow_node_run.output JSONB` 列。详见 `docs/architecture/workflow-dependency-guide.md §10` + ADR-009。
- **CLAUDE.md 配置开关规范加 `batch.trigger.async-launch.enabled`**（ADR-010）：默认 `false` 走原同步 HTTP 路径（`HttpOrchestratorTriggerAdapter`，已标 `@Deprecated forRemoval=true`）；切到 `true` 走 trigger_outbox + Kafka 异步路径（trigger fire 同事务写 `trigger_outbox_event` → `TriggerOutboxRelay` 周期发到 `batch.trigger.launch.v1` topic → orchestrator `TriggerLaunchConsumer` 消费触发 launch）。**两边开关必须一致**避免单边激活。配套：V80 `trigger_outbox_event` 表，trigger 加 `spring-kafka` 依赖。灰度切换 / 回滚 / 24h 对账步骤见 `docs/runbook/trigger-async-launch-rollout.md`。

### 2026-04-28
- **模块边界新增 `batch-worker-process`**(CLAUDE.md "## 模块边界" 同步加)。配合 P2 PROCESS 一等公民化:`JobType`/`PipelineType` 各加 `PROCESS` 枚举值,新增独立 worker 模块,与 import/export/dispatch 完全对称。
- **`job_type` 字典加 `PROCESS`**(CLAUDE.md "核心字典" 同步)。完整列表:`GENERAL / IMPORT / EXPORT / PROCESS / DISPATCH / WORKFLOW`。`PipelineType` 同步加 `PROCESS`。`BatchTopics.TASK_DISPATCH_PROCESS` 路由到 `batch.task.dispatch.process` topic。
- **PROCESS pipeline stage_code 字典扩展**(`pipeline_step_definition.stage_code` CHECK 约束):新增 `COMPUTE / COMMIT` 两个值,V74 migration 把 `pipeline_step_definition.stage_code` CHECK 重建为 superset。`ConfigPackageExcelValidator` 同步识别 PROCESS 5 stage:`PREPARE / COMPUTE / VALIDATE / COMMIT / FEEDBACK`。

### 2026-04-26
- **PMD 基线 0 violation 达成 + CI 真正阻断**（maturity-assessment §6 P1 #2 收尾）。本次集中收尾后 `mvn pmd:check -fae` 全模块 BUILD SUCCESS：DispatchChannelHealthRepository 提 7 个 mapper param key 常量；ConsoleMenuRegistry 提 ROLE_VIEWER/OPERATOR/ADMIN 常量替换 53 处；ConsoleDashboardQueryService 提 UNKNOWN 常量替换 13 处；ConfigPackageExcelValidator.validateStepRows 抽出 validateImplCode 子方法（NcssCount 解除）；AbstractSingleSheetExcelService.logImportAudit 8 参数改 ImportAuditContext record；ExcelPreviewResponse secondary constructor 加 @SuppressWarnings 豁免（CLAUDE.md record 豁免规则）；9 处 FQN 改短名。Ruleset 调整：`AvoidDuplicateLiterals.maxDuplicateLiterals` 4 → 7（4-6 次局部重复属可接受），加 `exceptionList=true,false,null,UTF-8,utf-8`（语义弱字面量提常量丑）。`scripts/ci/run-full-regression.sh` 删 `\|\| true`，PMD 失败真正阻断 CI（同 jacoco 同模式）；提供 `BATCH_CI_SKIP_PMD_GATE=1` escape hatch。
- **jacoco 覆盖率门控启用**（maturity-assessment §6 P1 第 3 步）。`pom.xml` jacoco 阈值从 60%（不可达）降到 25%（起步基线，实测各模块 30-44%）；`scripts/ci/run-full-regression.sh` 删 `|| true` 让 `jacoco:check@check` 真正阻断 CI（之前覆盖率不达标完全静默）。调用语法注意：必须用 `jacoco:check@check` 而非裸 `jacoco:check`（后者走 default-cli execution 拿不到 pom 配的 rules）。提供 `BATCH_CI_SKIP_COVERAGE_GATE=1` env escape hatch（dev 本地 debug 用，CI 不应设）。提升节奏：6 个月内到 40% / 1 年内到 60%。
- **默认调度引擎切换：Quartz → HashedWheelTimer**（phase 1 收尾）。`application.yml` 的 `BATCH_TRIGGER_SCHEDULER_IMPL` fallback 从 `quartz` 改为 `wheel`，`QuartzPauseWhenWheelEnabledCustomizer` 的 `@Value` fallback 同步切到 `wheel`，`TriggerReconciler` 的 `@ConditionalOnProperty` 去掉 `matchIfMissing=true`（原本是 quartz 默认时的兜底语义，现已不需要）。Quartz 仍保留作 opt-in incident 回退路径（显式 `BATCH_TRIGGER_SCHEDULER_IMPL=quartz`）。代码层面 wheel 已通过 phase 1 实施期 4 周 + 57 IT 全过的验证（commit a9b38d17 等）；本次仅切换默认值，未删 Quartz codepath；删 codepath 按 `docs/runbook/wheel-scheduler-rollout.md` §6 完成判定的"灰度全量 30 天无回归"节奏走。配套同步 `docs/architecture/system-flow-overview.md` §1 trigger 层视觉权重（QZ→TR 边降级为粗虚线，WHEEL→WS 升级为粗实主路径）+ label 表达。
- **架构硬约束扩展（2 条）**：
  - **读写分离仅 console-api 启用**；主链路（trigger / orchestrator / worker）严禁引入。原因：状态机依赖 read-after-write 强一致性，PG 异步流复制秒级延迟会引入 race condition；且这些模块写为主，读路径分离也无收益。详见 `docs/runbook/read-replica.md` §六。
  - **模块不得覆盖 batch-common AutoConfiguration 的基础设施 bean**（`taskScheduler` / `lockProvider` 等）。要定制行为就提供扩展点 bean 让 AutoConfiguration 通过 `ObjectProvider` 注入（例：`SchedulerErrorHandlerConfiguration` 只暴露 `ErrorHandler` bean，不重新定义 `taskScheduler`）。重复 `@Bean` 同名定义会触发 `BeanDefinitionOverrideException` 启动失败（实际事故：commit 34bd6cbf 引入此 bug，5a564d9f 修复）。
- **架构硬约束扩展：Console-api 不能直接 UPDATE/DELETE outbox_event**。在模块边界审计中发现 console-api 的 `DefaultConsoleOutboxOpsApplicationService` 直接通过 MyBatis 写 outbox_event（cleanup 删 PUBLISHED/GIVE_UP，republish reset FAILED/GIVE_UP → NEW），等价于绕过 orchestrator 触发任务重新分发。`outbox_event` 是分发主链核心环节，应纳入「Orchestrator 是唯一状态主机」的保护范围。修复：orchestrator 加 `OutboxOpsApplicationService` + `OutboxOpsController`（`/internal/outbox/cleanup` + `/internal/outbox/republish`），console 通过 `ConsoleOrchestratorProxyService` HTTP 转发；console 端 `OutboxEventMapper` 删除 3 个写方法，仅保留 SELECT。
- **持久化路径说明（历史勘误）**：曾记述 console-api「豁免」自 orchestrator 的「MyBatis（运行态）/ Spring Data JDBC（配置态）」分层。**现已全平台仅 MyBatis**（见 2026-05-02 条）；console-api 与 orchestrator 同为 Mapper 落位，console-api 仍因读 runtime、写配置与复杂检索而大量使用 Mapper（`secret_version` / `workflow_node` / `tenant_quota_policy` / `file_channel_config` / `pipeline_step_definition` / `alert_routing_config` / `calendar_holiday` / `config_change_log` 等），与「双 ORM」无关。

### 2026-04-25
- **`ensurePipelineDefinition` 跨 worker 错位污染 pipeline_step_definition**：worker 启动一次任务时调 `runtimeRepository.ensurePipelineDefinition(tenantId, jobCode, pipelineType, defaultSteps)`，发现已有 pipeline 时仍**会追加** default steps（`ensurePipelineStepDefinitions` 用 step_code Set 判存在但**不检查 pipeline_type 是否一致**）。结果：当 sourcePayload 跨节点继承泄露字段（昨日修过），EXPORT worker 拿到带 `targetJobCode=TC_IMPORT_RISK_SCORE` 的 task → resolveJobCode 用错 jobCode → ensurePipelineDefinition 把 EXPORT_* 默认 steps 写入 IMPORT pipeline；DISPATCH worker 同理把 DISPATCH_PREPARE 写进 EXPORT pipeline。多次跨 worker 错位调用后，TC_EXPORT_RISK_ALERT 的 pipeline_step 长成 IMPORT_*+EXPORT_*+DISPATCH_* 大杂烩，跑起来报"找不到步骤实现: DISPATCH_PREPARE"。
  - **代码层**：`ensurePipelineDefinition` 仅在首次创建 pipeline_definition 时才调 `ensurePipelineStepDefinitions`；已存在则只读不写，避免跨 worker 错位再污染。
  - **数据层**：一次性 SQL 删 `pipeline_step_definition` 中所有 step_code 与 pipeline_type 不匹配的行（EXPORT pipeline 删 IMPORT_/DISPATCH_，IMPORT pipeline 删 EXPORT_/DISPATCH_，DISPATCH pipeline 删 IMPORT_/EXPORT_）。
  - **`batch_business` 库 vs `batch_platform.biz` schema 易混**：worker-export 的 `exportBusinessDataSource` 在 `application-local.yml` 配 `jdbc:postgresql://...:15432/batch_business`，而 `scripts/db/business/create_biz_tables.sql` 默认在当前数据库执行；运维灌种子时若不显式 `psql -d batch_business`，会落到 `batch_platform.biz` 不被 export worker 看到。本日发现的 recordCount=0 即此故障。
  - **TC_EXPORT_RISK_ALERT 端到端验证通过**：5 条 tc 风险告警从 biz.risk_alert 查出 → 1073 字节 GZIP JSON → file_record GENERATED。

### 2026-04-24
- **JOB 节点 workflow node_params 合并 + 跨节点字段泄露**（在 tc workflow 全链验证过程中挖出的两条连锁 bug）：`DefaultWorkflowNodeDispatchService.dispatchJobNode` 的 `buildChildLaunchRequest` 对 JOB 节点走独立子作业路径，和 TASK 节点的 `buildTaskPayload` 是两条完全分开的 payload 构造。历史上只有后者调 `mergeNodeParams`，所以 TASK 节点用 workflow_node.node_params 工作，JOB 节点形同虚设。同时前一个节点 buildTaskPayload 写进 sourcePayload 的 workflow 内部字段（`workflowNodeCode / workflowNodeType / targetJobCode`）会随 sourcePayload 继承到下一个节点的子作业 launch params，导致 EXPORT 节点的子 job 看到 IMPORT 节点的 `targetJobCode` → `AbstractPipelineStepExecutionAdapter.resolveJobCode` 优先用它 → 加载错 pipeline → worker 报 `unsupported export stage code: RECEIVE`。两处一并修：
  - **合并 node_params**：`buildChildLaunchRequest` 调 `mergeNodeParams(childParams, ctx.workflowNode())`。`ChildLaunchContext` 加 `WorkflowNodeEntity workflowNode` 字段，dispatchJobNode 透传。
  - **过滤 workflow 内部字段**：定义 `WORKFLOW_INTERNAL_PAYLOAD_KEYS = {workflowNodeCode, workflowNodeType, targetJobCode, _parentNodeCode, _parentVirtualTaskId, _parentWorkflowRunId, parentInstanceId}`，sourcePayload 继承进 childParams 时跳过这些 key，由当前节点重新提供。
- **Quartz cron 表达式误用修复**（重启后 reconciler 会自动同步 Quartz）：日志分析发现 `TA_IMPORT_CUSTOMER` 每小时 02 分触发、`TB_IMPORT_TRANSACTION` 每小时 01 分触发，产生 48 次/天的噪声 + 一批 `customerNo is required` ERROR。根因：DB 里 5 条 job_definition 的 `schedule_expr` 写成了 Linux 5 字段 cron（`分 时 日 月 星期`），Quartz 按 6 字段 `秒 分 时 日 月 星期` 解析 → `"0 1 * * *"`（原意每日 01:00）变成"每小时 01 分 00 秒"。3 处收敛：
  - **`TriggerSchedulerFacade.scheduleCronDescriptor` 加字段数硬校验**：`CronExpression.isValidExpression()` 之后断言字段数 ∈ {6, 7}，否则拒绝注册并在异常信息里明确提示"add a leading '0 ' for seconds"。防止未来任何 Linux cron 误用再次悄悄污染 Quartz。
  - **`TriggerReconciler` 扩展 schedule drift detection**（DB 有+Quartz 有 → 一致性检查）：原先文档明确"不做 schedule 变更检测"，用户改 DB 必须走 `toggleEnabled` 反复切或调 ops 接口。新增 `hasScheduleDrift(JobKey, TriggerDescriptor)`：CRON 比对 cronExpression + timezone，FIXED_RATE 比对 repeatInterval。不一致直接触发 `registerByJobCode` 走 `scheduleWithReplace` 的 delete-and-add 替换。保守策略：descriptor.scheduleType 为 null 或 Quartz triggers 为 empty 时不判 drift（避免 mock/异常数据误判）。守护测试 `scheduleDrift_triggersReRegister` 覆盖 CRON drift 路径。
  - **DB 数据修正**：5 条 `schedule_expr` 从 Linux 5 字段改为 Quartz 6 字段 —— `TA_IMPORT_CUSTOMER`/`TA_WF_SETTLEMENT`/`TB_EXPORT_STATEMENT`/`TB_IMPORT_TRANSACTION`/`TB_WF_RECONCILE`。重启 trigger 进程后 `TriggerReconciler` 会通过 drift 检测自动把 Quartz JobStore 里残留的旧表达式覆盖成新值。
- **ImportDataQualityService 去 customer 硬编码默认规则**：`defaultRuleSet()` 硬塞了 customer 专属的 `customerNo/customerName required` + `customerType/status 枚举` + `uniqueFields=[customerNo]`，非 customer schema（如 `IMP-TRANSACTION-CSV` 的 `txnNo/accountNo/txnType`）只要没显式配 `validation_rule_set` 就会回退到这套规则，集体报 `customerNo is required` 进死信。改为 `mergedRuleSet` 从 `field_mappings` 自动派生必填规则：遍历每条 mapping，若 `required=true` 就生成 `{fieldName: {required: true, errorCode: IMPORT_VALIDATE_REQUIRED}}` 加入 fieldRules；再叠加 `template_config.validation_rule_set` 的显式规则（覆盖派生）。各 template 按自身 schema 各司其职：IMP-CUSTOMER-CSV 继续校验 customerNo/customerName，IMP-TRANSACTION-CSV 改为校验 txnNo/accountNo。顺手删掉已无引用的 `usesGenericJdbcMapped()` 私有方法 + 空 import。复杂规则（枚举、长度、unique）仍需在 `validation_rule_set` 里显式声明，不再自动来源。
- **shutdown 期 Redis 调用收敛**（重启日志复查发现）：orchestrator graceful shutdown 时 Lettuce 先关，OutboxPollScheduler 继续 tick 抢 ShedLock → `setIfAbsent` 抛 `java.lang.IllegalStateException: LettuceConnectionFactory has been STOPPED` → 冒泡到 OutboxPollScheduler `catch (Throwable t)` 打 `ERROR Outbox 轮询异常（非数据库类）`。两处补丁：
  - `RedisShedLockProvider.lock/unlock` 的 catch 追加 `IllegalStateException`（与 `DataAccessException` 并列），统一 `rootReason()` 抽取消息格式，关闭期 return Optional.empty 视为未拿到锁。
  - `OutboxPollScheduler.pollAndReschedule` 在 `executeWithLock` 之前加前置 `gracefulShutdown.isDraining()` 短路（原先的 check 在 `executeAdvance` 里，发生在拿锁之后）；这样 shutdown 期间根本不会调用 Redis，也不会产生 WARN。
- **运行日志长期噪声 + 真错误一锅端**（跟进 2026-04-22 "收敛运行日志长期噪声" 批次；这轮抓的是日志里剩下的 ERROR/WARN 噪声大头）：
  - **`LaunchBatchDayService.upsertBatchDayInstance` 加 `DuplicateKeyException` 重试**：03:00 等整点时多个触发同时落 `default-tenant/default_calendar/<bizDate>` 的 batch_day_instance，两个线程 `findFirstByTenantIdAndCalendarCodeAndBizDate` 都返回 null，都走 INSERT 分支，第二个撞 `uk_batch_day_instance` → 500 → trigger 当 SYSTEM_ERROR 重试。外层 retry 循环现在同时抓 `OptimisticLockingFailureException`（update 分支 CAS 失败）和 `DuplicateKeyException`（INSERT 分支并发撞键），`last` 容器类型升到 `DataAccessException` 兼容两者；重试后 SELECT 已能看到对方的记录，走 update 分支收敛。
  - **`DefaultTriggerService.handleHttpClientError` 区分 422/409/404**：
    - 422 Unprocessable = 业务拒绝（典型：`current execution time is outside batch window`、tenant closed、validation 失败）→ REJECTED + WARN + 不抛异常，避免 Quartz 打 `Job threw an unhandled Exception` ERROR 并进 misfire retry；下次 cron fire 仍是同样结果，重试无意义。
    - 409 Conflict = 瞬时并发冲突（乐观锁 / 唯一键 / 幂等撞键）→ FORWARD_FAILED（trigger-retry-scheduler 自动下次 tick 再试），不抛。
    - 404 / 其他 4xx 行为不变。
  - **`AbstractApiExceptionHandler` 新增 2 个 handler**：`OptimisticLockingFailureException` / `DuplicateKeyException` 统一映射为 409 CONFLICT + WARN（而不是落到通用 `Exception` handler 打 ERROR + 500）。所有模块（trigger/orchestrator/console）的 ApiExceptionHandler 一次受益，因为它们都继承这个基类。
  - **`RedisShedLockProvider.lock` 捕获 `DataAccessException`**：Redis 瞬时故障（`QueryTimeoutException` / 连接拒绝 / 节点切主）时 `return Optional.empty()`（视为没拿到锁），下 tick 自然重试，不再冒泡到 Spring scheduler 打 ERROR。`unlock` 同理捕获并抑制（key TTL 自动释放）。
  - **`DefaultStateMachine` 自回边 NOOP 降 DEBUG**：`case "START", "CLAIM", ..., "RUNNING" -> "RUNNING"` 把 RUNNING 加进合法事件；TERMINATE/CANCEL 补 TERMINATED/CANCELLED；WAITING/CREATED/PENDING/NOOP 合并为 noop；default 分支判断 `event.equalsIgnoreCase(fromState)` → 幂等重复事件 DEBUG，其他真未知事件（如 `SUCESS` 拼错）保留 WARN。
  - **`RemoteFilesystemDispatchSupport` NAS symlink WARN 首次抑制**：加 `ConcurrentHashMap<String, Boolean> NAS_SYMLINK_WARNED`，同一 configured path 只报一次；macOS 本地 `/tmp → /private/tmp` 这种恒定 symlink 不再每次 dispatch 都刷一条。
- **异常数据排查：孤儿 job_definition 通用清理**：最初发现 `default-tenant/gen_data_cleanup` 的 `worker_group=GENERAL` 在系统里没有任何 `ONLINE/DRAINING` worker 匹配（只有 IMPORT/EXPORT/DISPATCH 三组），导致 `WaitingPartitionDispatchScheduler` 每 10s 选一次 → `DefaultWorkerSelector` WARN `no_online_workers_in_group`（累计 1073 条）。进一步调研发现同类孤儿还有 `gen_archive_purge` / `gen_index_rebuild`，以及 6 条长期 CREATED 卡住的 job_instance（launch 中断残留）。
  - `scripts/db/cleanup-orphan-general-job.sql` 重写为**通用脚本**（不再硬编码 `gen_data_cleanup`）：PART A 只读诊断（4 个 SELECT 列出将被改的范围），PART B 写事务（4 条 UPDATE 级联 CANCEL partition → instance → disable definition → 附带清 stale CREATED），PART C 事后验证。判定逻辑：`enabled=true AND worker_group <> '' AND worker_group NOT IN (online worker_groups)`；**WORKFLOW 类型的 `worker_group=''` 合法**（workflow 本身不挂 worker，节点才分），脚本已排除。
  - 脚本幂等：`UPDATE ... WHERE status IN (active states)` 只改当前活跃行，重复跑无副作用。
  - 执行记录（2026-04-24 14:01）：B-1 CANCEL 2 partition / B-2 CANCEL 3 instance / B-3 禁用 3 definition / B-4 CANCEL 5 stale CREATED；事后 orchestrator `waiting dispatch tick` INFO 立即静默（下沉到 `log.debug("no WAITING partitions this tick")` 分支）。
  - 脚本不入 Flyway，属运维一次性动作；将来新发现的孤儿组合直接再跑同脚本即可。

### 2026-04-23
- **新增 `GET /api/console/queries/partitions`**：按作业实例分页查询 `job_partition` 列表。前端 `PartitionView.vue` 此前打的 `instanceApi.partitions(instanceId)` 后端没有对应路由，只能本地裁切；补上后走服务端分页，避免大实例（分区数 > 1000）拉全量。
  - 新建 `JobPartitionQueryRequest`（extends `PageQueryRequest`，字段 `tenantId / jobInstanceId / partitionStatus`）+ `ConsoleJobPartitionResponse`（13 字段，含 `partitionNo / partitionKey / partitionStatus / workerGroup / workerCode / retryCount / businessKey / leaseExpireAt / startedAt / finishedAt`）。
  - 新建 console 侧只读 `JobPartitionMapper` + XML（MyBatis）独立于 orchestrator 的同名可写 mapper；`selectByQuery` 走 `<include refid="filters"/>` 消除 count/list 的 where 重复。
  - `ConsoleJobQueryMappers` / `ConsoleJobQueryService` / `ConsoleQueryApplicationService` / `DefaultConsoleQueryApplicationService` / `ConsoleQueryController` 接线。
  - OpenAPI 加 `/api/console/queries/partitions` path + `ConsoleJobPartitionResponse` schema + `CommonResponseJobPartitionList` wrapper；`console-api-protocol.md` 补 Changelog、endpoint 清单、过滤参数表。
  - 备注：「partition 粒度」与既有「step 粒度」（`/job-step-instances`）并存，前者 = `job_partition`，后者 = `job_step_instance`。

### 2026-04-22
- **清理 `docs/test-data/deprecated-single-sheet-imports/`**：10 份单 sheet Excel 样本（`01..10-*-full-coverage-test.xlsx`，绑定孤立租户 `test-full-coverage`）已全量删除。3 个对应控制器已 `@Deprecated`（FileTemplate / ResourceQueue / AlertRouting），其余 7 个虽未标注但流程上被整合式 Excel 和 tenant-init JSON 取代，样本无人装载。同步修 README：明确 `file_template_config` 不进整合式 Excel 是系统设计（建租户时从 `default` 克隆 + 页面单条维护），不是"gap"；批量初始化走 `tenant-init` 的 `FileTemplateConfigUpsertParam`。
- **前端整合式 Excel 按租户分工补齐枚举覆盖**（在 default-tenant 之外）：
  - **ta（零售）**：新 `TA_IMPORT_ORDER` IMPORT 6-stage 流水线（`RECEIVE→PREPROCESS→PARSE→VALIDATE→LOAD→FEEDBACK` 全链）+ 既有 `TA_EXPORT_REPORT` 补 5-stage EXPORT 流水线（`PREPARE→GENERATE→STORE→REGISTER→COMPLETE`）+ `ta_local_archive` LOCAL channel。
  - **tb（金融）**：新 `TB_DISPATCH_SETTLE` DISPATCH 6-stage 流水线（`PREPARE→DISPATCH→ACK→RETRY→COMPENSATE→COMPLETE` 全链）+ `tb_api_ingest` API channel（区别于已有 API_PUSH）。
  - **tc（风控）**：两个新 GATEWAY workflow —— `TC_WF_GATEWAY_ALL`（`joinMode=ALL`，3 branch）和 `TC_WF_GATEWAY_N_OF`（`joinMode=N_OF, joinThreshold=2`，带 FAILURE / CONDITION 边的 fallback 子路径）覆盖 `WorkflowJoinMode` 全部三值 + `workflow_edge.edge_type` 全部四值。
  - 追加脚本 `scripts/local/append-tenant-coverage.py` 幂等处理（按主键查重跳过），重复跑不会产生重复行；源于 v4 审计发现"ta/tb/tc excel 场景长尾缺口"（FEEDBACK / STORE / REGISTER / DISPATCH 全链 / API / LOCAL / ALL / N_OF / FAILURE / CONDITION）。
- **前端整合式 Excel 补齐 default-tenant 样本**：v4 硬化批次在 `multi-tenant-seed.sql` 给 `default-tenant` 新增了 4 条本地化 dispatch channel_config + 3 条探针 workflow（`wf_probe_pipeline` / `wf_probe_gateway` / `wf_probe_mixed`）+ 对应 job_definition，但 `test-full-coverage-import-suite/` 只有 ta/tb/tc 的 `*-tenant-config-package-test.xlsx`，前端 `/api/console/config/tenant-package/excel/*` 入口无法一键重放这批 seed。
  - 新增 `docs/test-data/test-full-coverage-import-suite/default-tenant-config-package-test.xlsx`（用生成脚本 `scripts/local/gen-default-tenant-excel.py` 产出，整合式 8 sheet 结构，按需修改脚本重生）。
  - 更新同目录 `README.md`：声明四个租户样本的用途、生成脚本指针、以及已知 gap——整合式 Excel 暂不含 `file_template_config` sheet，因此 tb/tc 本轮新增的 `IMP-TXN-FIXED` / `IMP-TXN-XML` / `IMP-TRANSACTION-CSV.jdbcMappedImport` / `IMP-RISK-SCORE-JSON.jdbcMappedImport` / `EXP-RISK-ALERT-JSON.sqlTemplateExport` 目前仅落地 seed SQL，等后续整合式 Excel 增加该 sheet 后再同步。
- **`capability_tags` 数据质量审计调度器**（审计发现的唯一"异常数据妥协"兜底）：`DefaultWorkerSelector.capabilityTagsContain` 为防畸形 JSON 拖垮 selector，catch 后返回 false（WARN 一条即过）——数据源头可能长期无人发现。新增 `WorkerCapabilityTagsAuditScheduler` 默认每 5 min（`batch.worker.audit.capability-tags-scan-interval-millis`）扫描 ONLINE/DRAINING worker：
  - DB 侧 `WorkerRegistryMapper.selectInvalidCapabilityTags` 用 `jsonb_typeof(capability_tags) <> 'array'` + 含非字符串元素过滤，O(activeWorkers) 扫表。
  - App 侧用 `JsonNode` 而非 `String[].class` 做二次严格校验——Jackson 默认会把 `[1,2]` 这种数值元素静默强转成字符串（这恰恰是要审计的异常数据），必须按元素 `isTextual()` 判定才能暴露。
  - 命中时 WARN 日志（采样前 10 条，`capability-tags-log-sample-limit` 可覆盖）+ `batch.worker.capability_tags.invalid.count` gauge，Grafana 可设"> 0 持续 2 周期"告警。
  - ShedLock(`worker_capability_tags_audit`, PT2M)；`OrchestratorGracefulShutdown.isDraining()` 时跳过。
  - 新增 `InvalidCapabilityTagsRecord` DTO（tenant/code/raw）+ `WorkerCapabilityTagsAuditSchedulerTest` 7 case（draining/empty/对象/标量/含数值数组/合法数组/mixed/null-blank）。
  - 顺手修 `DefaultWorkflowNodeDispatchServiceIdempotencyTest` 对 `DefaultWorkflowNodeDispatchService` 构造器的过期调用（缺 `NamedParameterJdbcTemplate` 入参，早先补 upstream partition output 查询时遗漏）。
- **v4 P0/P1/P3 批量闭环**（仅 P2 验证型场景保留）：
  - **P3-1 calendar WARN**：`DefaultTriggerService.resolveCalendar` 加 `CodeNormalizer.toConfigFormOrNull` 归一 `strict-calendar` → `strict_calendar`，消除每 30min 刷的「calendar definition not found」WARN。
  - **P3-3 P3-4 数据清理**：新增 `scripts/db/cleanup-historical-failures.sql` 按保留窗口级联清理 `job_instance`/`job_partition`/`job_step_instance`/`job_task`/`pipeline_instance`/`pipeline_step_run`/`file_dispatch_record`/`workflow_run`/`workflow_node_run`/`dead_letter_task`/`trigger_request`/`outbox_event` 一条龙。本轮跑完：146 FAILED + 4 CANCELLED 清零；146 DL 剩 3 条活跃。
  - **P1-4 EXPORT SQL 占位符白名单**：`SqlTemplateExportSecurityProperties.allowedExtraParams` 默认加入 `bizDate`，不再因「常见业务日期过滤」被拒。
  - **P1-5 DISPATCH 硬错不可重试**：`DefaultRetryGovernanceService` 加 `NON_RETRYABLE_ERROR_CODES` 集合（`DISPATCH_PREPARE_FILE_MISSING` / `DISPATCH_PREPARE_FILE_NOT_FOUND` / `DISPATCH_PREPARE_CHANNEL_NOT_FOUND` / `DISPATCH_PREPARE_INVALID` / `DISPATCH_PREPARE_PARSE_FAILED` / `EXPORT_GENERATE_NO_PAYLOAD` / `STEP_NOT_FOUND`），这类一次性硬错直接进死信，不再 EXPONENTIAL 重试 3 次浪费指数 backoff 与 DL 空间。
  - **P1-3 EXPORT 包装层校验 `id` 列**：`SqlTemplateExportSpec.parse` 在加载模板时用词界正则预检 `cursorColumn`（默认 `id`）是否在 SQL 出现，不在就直接抛 `IllegalArgumentException` 明示「either add to SELECT, or set sqlTemplateExport.cursorColumn」，避免运行期 PostgreSQL `bad SQL grammar` 难调试。
  - **P0-3 空壳 workflow 清理**：删掉 24 条 0-node 的 `workflow_definition`（跨 4 租户 × 6 workflow_code：`wf_archive_flow / wf_compliance_check / wf_data_migration / wf_full_pipeline / wf_onboarding / wf_settle_dispatch`），同时把 default-tenant 3 条指向这些空壳的 `job_definition` 禁用。
  - **P1-1 Workflow 节点 payload 串联**：`DefaultWorkflowNodeDispatchService.buildTaskPayload` 新增两层合并：
    - `mergeNodeParams`：把当前 `workflow_node.node_params`（用户在设计器配的 `templateCode` / `channelCode` 等静态字段）合并进下游 task payload。
    - `mergeUpstreamPartitionOutputs`：扫描同一 job_instance 下已 SUCCESS 的兄弟分区 `output_summary`，按保守白名单（`fileId / fileCode / batchNo / recordCount / bizDate`）抽取后塞进 payload；SETTLE 生成的文件自动流向 DISPATCH 节点。
    验证：触发 `wf_eod_process` 后观察 SETTLE 分区的 `task_payload`，5 个 node_params 字段（`step / batchNo / bizType / fileCode / templateCode`）正确注入，与 `sourcePayload` 字段共存。
    跟进：经查 `WaitingPartitionDispatchScheduler` 本身没 bug。早先误判「不 release」的根因是 worker 进程在长时间运行中异常退出、心跳超时被 `WorkerHeartbeatTimeoutScheduler` 打 OFFLINE，selector 自然 `candidates=0 / no_online_workers_in_group` 静默重试。重启 workers 后 WAITING partition 立即释放。顺手给该 scheduler 加了 `waiting dispatch tick` 和 `skip partitionId=... reason=...` 两级 INFO 日志，今后此类长期停滞一目了然。
- **Workflow→worker step executor 协议错位修复**（上段副发现的根因落地）：`STEP_NOT_FOUND: DISPATCH_PREPARE` 不是 worker 把 payload.steps 当执行链解读的问题，而是 worker 拿 `request.jobCode()`（= workflow 自己的 `wf_eod_process`）去查 `pipeline_definition` → 命中的是跨 worker 的复合 pipeline（混着 EXPORT_* 和 DISPATCH_* 两类 impl_code）→ EXPORT worker 在 DISPATCH_PREPARE 上自然报错。三处修法一起上：
  - `AbstractPipelineStepExecutionAdapter.resolveJobCode` 优先读 task payload JSON 的 `targetJobCode`（orchestrator 派发 workflow TASK 节点时已经写入），确保 worker 加载本域独立 pipeline（`exp_settlement_daily` / `disp_sftp_bank` 这种纯 EXPORT / DISPATCH 的 pipeline，而不是 `wf_eod_process` 的复合 pipeline）。
  - `DefaultWorkflowNodeDispatchService.mergeUpstreamPartitionOutputs` 加两级兜底查 fileId：先按 `jobInstance.traceId` 查 `file_record.trace_id`（本轮产出的文件），没有时再按 `batchNo → file_record.source_ref` 查（按业务键幂等复用的文件，如 `settlement-2026-04-22`）；抽到后注入下游 payload。DISPATCH 节点从此能看到 SETTLE 上游生成的 fileId。
  - `WaitingPartitionDispatchScheduler.buildRequest` 优先读 `partition.input_snapshot` 里 sub-job 专用的 `queueCode / windowCode`，否则才回退到 `jobInstance` 的 workflow 级值。避免 DISPATCH partition 按 `workflow_queue.resource_tag=workflow` 去找 DISPATCH worker → capability_tags=[delivery] 永远不匹配的无限循环。
  - 端到端验证：wf_eod_process（instance 219）的 SETTLE SUCCESS → DISPATCH partition 的 `task_payload` 含 `fileId=470 / channelCode=sftp_bank / targetJobCode=disp_sftp_bank` → DISPATCH worker 加载正确 pipeline 跑到 DISPATCH_SEND 阶段。最后的 `sftp_host missing` 是 channel config 种子数据硬伤，与代码无关。
- **P2 场景真实数据验证**（除压测）：逐条走通 —— drain enable/disable（orchestrator+trigger 双边，trigger 同步切 Quartz STANDBY/STARTED）、worker drain 生命周期（ONLINE→DRAINING→DECOMMISSIONED）、file archive/redispatch（file 470 `GENERATED→ARCHIVED`）、compensation 独立（`cmp-...afe1e065` JOB type SUCCESS）、LOCAL dispatch 真文件落盘、OSS dispatch 真上传、日历 holiday 插入验证。未覆盖：FIXED_WIDTH/XML（无种子模板）、GATEWAY 节点 + PIPELINE/MIXED 类型 workflow + join 模式（ALL/ANY/ANY_N）（无种子实例）、API/API_PUSH/EMAIL/NAS/SFTP 最后一公里（endpoint 占位或 channel config 种子问题）。
- **P2 未通过项种子补齐**（沉到 `batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql` 末尾）：
  - default-tenant 4 条 dispatch channel config 重写：`sftp_bank`→`localhost:12222`（docker SFTP 映射）、`email_ops`→`localhost:1025`（MailHog-ready）、`nas_archive`→`/tmp/batch/nas-probe`、`oss_backup`→`http://localhost:19000`（本地 MinIO），全部对齐 `ChannelConfigMerge` 白名单的 key（sftp_host/smtp_host/oss_bucket/nas_remote_directory 等）。验证：SFTP 真上传到 `/home/ta/inbound/settlement-2026-04-22`；NAS 真写到 `/tmp/batch/nas-probe/settlement-{bizDate}.csv`；LOCAL + OSS 之前已绿。
  - tb 两条文件格式模板：`IMP-TXN-FIXED`（FIXED_WIDTH，record_length=70，6 定宽字段；2 行真实数据落 `biz.transaction`）+ `IMP-TXN-XML`（XML，`parseHints.xmlRecordElement=txn`；2 行真实数据落 `biz.transaction`）。XML 模板必须把 `xmlRecordElement` 放在 `query_param_schema.parseHints` 下（被 `ParseSupport.parseHints` 取），顶层或 `xml_record_element` 作为后备路径；这条遇到问题经验也记在种子注释里。
  - 两条探针 workflow：`wf_probe_pipeline`（workflow_type=PIPELINE，START→TASK→END）+ `wf_probe_gateway`（DAG，含 GATEWAY fork、并行两 TASK 分支、`joinMode=ANY` 的 MERGE gateway）。验证：instance 239 真走完 START→FORK(GATEWAY→SUCCESS)→BRANCH_A + BRANCH_B 并行派发（`failed_partition_count=2` 证明两条分支都真实执行），PIPELINE 类型 workflow 派发链路同样走通。MIXED 类型未加种子（code 路径支持，样板需另给）。
- **`ParseSupport.writeParsedRecord` 去硬编码 `CustomerImportPayload`（P1-2 修完）**：`preserveLogicalRow=false` 分支把 row 强转 `CustomerImportPayload` 的代码删除，改为原样 NDJSON 输出。主链路 I/O 形态不变（LoadStep/ValidateStep 本来就按 Map 走），但后续非 customer schema 的 IMPORT 模板即便忘配 `jdbc_mapped_import` 也不会被默默吞字段。`CustomerImportPayload` 类和 LoadStep/DataQuality 的 legacy 重载保留，与硬编码问题无关、等后续统一下线 legacy 路径时再清。同场修掉 `ImportIngressScannerTest` / `GenerateStepTest` 两个旧版构造器调用。

### 2026-04-21
- **Worker `capability_tags` 心跳上报闭环（P0-2 修完）**：让 V4-BUG-2 的 selector 代码修复真正生效。
  - `WorkerConfiguration` 接口加 `default List<String> capabilityTags()`（默认空列表，向后兼容）
  - 3 个 `@ConfigurationProperties` record（`ImportWorkerConfiguration` / `ExportWorkerConfiguration` / `DispatchWorkerConfiguration`）加 `List<String> capabilityTags` 字段 + `@Override` 把 null 归一成 `List.of()`
  - `WorkerRegistration` domain 加 `List<String> capabilityTags`；`AbstractWorkerLoop.ensureStarted` 把 `cfg.capabilityTags()` 塞进 registration；`HttpWorkerRegistryClient.toHeartbeatDto` 用这个值替代原 `null`
  - 3 个 worker 的 `application-local.yml` 声明具体 tag（import=`[ingest]` / export=`[report, workflow]` / dispatch=`[delivery]`）
  - 遇到问题提示：只改 `batch-worker-core` 源码后，直接 `mvn package` 下游 worker 模块会用本地 m2 缓存的旧 jar 打包。必须先 `mvn -pl batch-worker-core install -DskipTests`，否则下游 jar 里没有新的 setter 调用。
  - 恢复 `default-tenant` 2 条 queue 的 `resource_tag`（`export_queue=report` / `workflow_queue=workflow`），之前 V4-P0-1 临时清空的状态回滚为正常。
- **多租户业务链路验证批次 v4**：详见 `docs/analysis/fix-report-v4.md` / `docs/analysis/hardening-backlog-v4.md`。修了 2 条代码 bug：
  - `TriggerSecurityConfiguration.InternalSecretFilter.setAuthenticated` 用 `AnonymousAuthenticationToken` 被 Spring Security `.authenticated()` 拒绝（trustResolver 判 anonymous）→ 换 `UsernamePasswordAuthenticationToken.authenticated(...)`。原因：`/api/triggers/management/*` 全线 403 / bypass-mode 失效。
  - `DefaultWorkerSelector.matchesResourceTag` 只比对 `worker.resource_tag` 单值，忽略 `capability_tags` JSONB 数组 → 扩展为"单值等于 OR 数组命中"，同时畸形 JSON 降级为 WARN 不抛。新增 `DefaultWorkerSelectorTest` 6 case。
- **worker-import `business` 数据源 URL 加 `?stringtype=unspecified`**（`application-local.yml`）：`GenericJdbcMappedImportLoadPlugin.setObject(String)` 绑定到 NUMERIC/DATE 列时 Postgres 不隐式 cast，导致 tb TRANSACTION / tc RISK_SCORE 批 LOAD 失败。加参数后服务端按列类型自动转换。
- **DB 改动种子化（Flyway 不承接这类；详见 hardening-backlog-v4 的 V4-P0-1）**：
  - ✅ `biz.transaction` / `biz.risk_score` / `biz.risk_alert` 三张业务表 DDL 已落 `scripts/db/business/create_biz_tables.sql`（含索引 + CHECK）
  - ✅ `tb/IMP-TRANSACTION-CSV`、`tc/IMP-RISK-SCORE-JSON`、`tc/EXP-RISK-ALERT-JSON` 的 `jdbcMappedImport` / `sqlTemplateExport` / `default_query_sql` 已落 `batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql`（UPDATE 块在原 INSERT 下方）
  - ❓ `default-tenant/exp_settlement_csv_v1` 源头暂未定位（live DB created_by='system'，可能来自 Console 上传 / 租户初始化服务），留到 P1-1 批次
  - ❌ `default-tenant` `export_queue` / `workflow_queue` `resource_tag` 清空、2 条 `job_definition.enabled=false`（TA_DISPATCH_ORDER / gen_reconcile）不入种子 — 属运维临时动作 / 待 P0-2 根治

### 2026-04-20
- **异常数据入口治理**：新增 `CodeNormalizer` 工具（batch-common/utils），定义两类归一规则：
  - **分组码**（`worker_group` / `tenant_id`）→ UPPER + `^[A-Z][A-Z0-9_]*$` 校验
  - **配置码**（`window_code` / `calendar_code` / `queue_code`）→ LOWER + `-` 替换为 `_` + `^[a-z0-9][a-z0-9_]*$` 校验
  入口侧在 7 个写路径统一调用 `toUpperOrNull` / `toConfigFormOrNull`（宽松版，供 Excel 预览使用）：
  `DefaultConsoleJobDefinitionExcelApplicationService` / `DefaultConsoleWorkflowExcelApplicationService` /
  `DefaultConsoleTenantConfigPackageExcelApplicationService` / `DefaultConsoleJobDefinitionApplicationService` /
  `DefaultConsoleTenantConfigInitApplicationService` / `AbstractWorkerLoop.ensureStarted`（worker 注册源头）/
  `DefaultWorkerSelector.select`（读路径兜底）。
  V64 migration (`V64__normalize_code_conventions.sql`) 一次性归一 5 张表（worker_registry / job_definition /
  job_instance / job_partition / resource_queue / workflow_node / batch_window / business_calendar）的历史存量。
  修的 bug：`IMPORT` vs `import`、`always_open` vs `always-open` 并存导致 ResourceScheduler worker 匹配等值
  比较失配，WAITING 分片永久卡住。
- **`job_instance` / `job_partition` quota check 去除 WAITING 分母**：`countActiveByTenant` / `countActiveAll` /
  `countActiveByFairShareGroup` / `countActiveByTenantAndQueueCode` / `job_partition.countActive*`
  6 个 SQL 把 WAITING 也算作 "active"，而 WAITING 正是被 quota 裁决的候选集——分母含分子 → 死锁
  （WAITING 超过配额就永久卡）。改为仅统计 READY/RUNNING（job_partition 多一个 RETRYING）。
  非 quota 路径（SLA 扫描、UI 活跃计数快照）保留原语义。
- **Worker 心跳超时降级**：新增 `WorkerHeartbeatTimeoutScheduler`（batch-orchestrator），
  30s 周期 + ShedLock(PT2M)；超时阈值 `batch.worker.drain.heartbeat-timeout-seconds` 默认 90s。
  把 `status IN ('ONLINE','DRAINING') AND heartbeat_at < cutoff` 的 worker 批量降为 OFFLINE；
  不动 DECOMMISSIONED。修的 bug：系统之前没有心跳超时清理，worker 进程 crash 后 DB 里 status 仍为
  ONLINE，WorkerSelector 选中它 release partition → partition 永远不会被 claim。
- **触发器控制面收敛：`job_definition.enabled` 作为权威，Quartz 异步对账**（修 "job 被 toggle=false 但 Quartz 还 fire → orchestrator 404 刷日志" 的长期 bug）：
  - 新增 `TriggerReconciler`（batch-trigger/infrastructure/scheduler）：`@Scheduled(fixedDelay=30s)` + `ShedLock PT5M` + `@EventListener(ApplicationReadyEvent)` 首轮立扫；比较 `job_definition` 权威集合与 Quartz `GroupMatcher.jobGroupEquals(JOB_GROUP)` 列表，DB-有-Quartz-无→`registerByJobCode`，DB-无-Quartz-有→`unregisterByJobCode`；`TriggerGracefulShutdown.isDraining()` 时跳过。
  - 删除 `TriggerRegistrationStartup`（`ApplicationRunner` 启动注册）+ 配套 IT `TriggerRegistrationStartupIntegrationTest`，由 reconciler 的启动期首轮覆盖同等语义。
  - `TriggerSchedulerFacade.JOB_GROUP` 可见性从 package-private 提升为 `public`（reconciler 跨包引用）。
  - **控制面职责重划**：console `ConsoleTriggerController` 的 5 条路由 `/api/console/triggers/**` → `/api/console/ops/triggers/**`（list/{jobCode}/register/unregister/pause/resume），定位为 **Ops 救急入口**（DB 与 Quartz 漂移时的强制修复），日常业务走 `toggleEnabled`；控制器 javadoc + OpenAPI tag 加警告："在此注销 enabled=true 的 job 会被下一次 reconcile 重建"。`ConsoleRateLimitFilter.TRIGGER_PATH_PREFIX` 同步更新。
  - 对账周期配置项 `batch.trigger.reconcile-interval-millis`（默认 30000）。
  - 守护：`TriggerReconcilerTest` 6 个 case 覆盖 DB-only / Quartz-only / 对齐 / 禁用 / draining / 畸形 JobKey。
- **新增 §时区策略 + 全局时区 provider**：`BatchTimezoneProperties` (`batch.timezone.default-zone`，默认 `Asia/Shanghai`) + `BatchTimezoneProvider` bean；业务路径上的 `ZoneId.systemDefault()` 统一替换为 `provider.defaultZone()` / `provider.resolveOrDefault(tz)`，9 处调用点完成迁移（`LaunchBatchDayService` / `LaunchParamResolver` / `CalendarBizDateResolver` / `DefaultLaunchAdapterService` / `DefaultResourceScheduler` / `BatchDayCutoffScheduler`×2 / `QuotaRuntimeStateService`）。`ConsoleQuerySupport` 宽松日期解析作为明确豁免保留。`batch-defaults.yml` 补 `spring.jackson.time-zone`。
- **V62 迁移：重跑语义 + 批次日并发 + 时区快照**（对齐 `docs/analysis/deep-issue-analysis-v3.md` 的五点设计灰色地带）：
  - `job_instance` 新增 `run_attempt INT NOT NULL DEFAULT 1` + `ck_run_attempt >= 1`；唯一键由 `(tenant_id, dedup_key)` 改为 `(tenant_id, dedup_key, run_attempt)`，同一 (job, biz_date) 可持有多次 attempts。
  - `TriggerType` 新增 `RERUN`（重跑触发）；`job_instance` / `trigger_request` 的 `ck_*_trigger_type` CHECK 同步扩展；console `/meta/enums.triggerType` 由反射自动下发不用改 schema。
  - `batch_day_instance` 加 `version BIGINT NOT NULL DEFAULT 0`（Spring Data JDBC `@Version` 乐观锁）+ `timezone_snapshot VARCHAR(64) NOT NULL DEFAULT 'UTC'`（创建时从日历 timezone 抓快照，后续回放不受日历改 tz 影响）。
- **`DefaultLaunchService.launch` 承认 RERUN 语义**：triggerType=RERUN 时不走"existing-instance=duplicate"短路；新建实例时 run_attempt = MAX+1，parent_instance_id 指回上次 attempt；RERUN 并发撞 23505 直接抛，不再误判为幂等重复。
- **`BatchDaySettleScheduler` 状态机竞态收口**：`settle()` 去掉 @Transactional，改为循环内 `selfProvider.getObject().settleOne(candidate, now)`（`REQUIRES_NEW`），单条 CAS 冲突只跳过该条，其他候选不受影响；捕获 `OptimisticLockingFailureException` 日志后下 tick 重扫。
- **`LaunchBatchDayService.routeLateArrivalIfNeeded` 原子化**：late-rejected 路径从"先改内存 → 再 UPDATE DB"改为 `updateTriggerType(..., expectedTriggerType='EVENT')` CAS；ROW=0（并发已改走）直接返回原 request，不再覆盖内存。`TriggerRequestMapper.updateTriggerType` 签名加 `expectedTriggerType` 参数，XML WHERE 带 `and trigger_type = #{expectedTriggerType}`。

### 2026-04-19
- **新增 §字符编码 + §配置开关规范**：两节详细落地清单移到 `docs/coding-conventions.md §20 / §21`，CLAUDE.md 仅保留核心规则指针。
- **`testing-open` → `bypass-mode`**：`BatchSecurityProperties` 字段/方法重命名，16 处业务 + 9 处测试全部迁移；保留 `setTestingOpen` setter + `@DeprecatedConfigurationProperty` 兼容旧键一个版本。默认值矩阵：IDE local=`true` / docker-compose=`false` / prod=`false` + @PostConstruct 守护。
- **全栈 UTF-8 契约**：新建 `EncodingUtils`（10 处 `Charset.forName(...)` 迁移），`Dockerfile.app` + `batch-defaults.yml` + 根 pom 补 UTF-8 配置；`docker-compose.yml` 四个中间件统一 `BATCH_LOCALE=C.UTF-8`。导出硬编码 UTF-8，导入保留 `file_template_config.charset` 透传。
- **§领域数据字典 `schedule_type` 与枚举对齐**：删除文档中多出的 `EVENT` / `ONE_TIME`（实际枚举仅 3 值）；起因是 tc Excel 按旧文档填 `EVENT` 被 validator 判 invalid 导致整包 apply 回退。

### 2026-04-18
- **版本管理改 Maven CI-friendly `${revision}`**：根 pom 统一入口（默认 `1.0.0`，非 SNAPSHOT），11 个子模块改用 `${revision}`；根 pom 加 `flatten-maven-plugin` 保证 install/deploy 后下游能解析；`build-apps.sh`、`docker/Dockerfile.app` 由 `-Dmaven.test.skip=true` 改为 `-DskipTests`（前者会阻断 `batch-common:tests` 依赖链）；`scripts/ci/security-scan.sh` 硬编码 jar 路径改为 glob 匹配。文档示例（`docs/design/*.md` / `docs/runbook/security-scan.md` / `security-scan/README.md`）同步。
- 新增 **§版本管理** 和 **§变更记录** 两节；§Java 代码风格 提示 Lombok 枚举样式。

### 2026-04-17
- **`CodedEnum` 接口重命名为 `DictEnum`**：与项目术语"字典"对齐；76 个文件的 `implements` / `import` / 调用处同步，跨 60 个枚举 + 10 个 Excel 应用层 + 守护测试 + 协议文档。
- **60 个枚举统一 Lombok 样式**：`@RequiredArgsConstructor` + `@Accessors(fluent = true)` + `@Getter`，删除所有手写构造器和 `code()` / `label()` accessor；每个枚举从 20+ 行样板降至 ~10 行。
- **抽取 `DictEnum.fromCode` / `codes` / `labels` 静态工具**：消除 35 个枚举各自的 `public static Set<String> codes()` 副本、5 个枚举各自的 `fromCode` 循环体；5 个有特殊语义的 `fromCode`（`CatchUpPolicyType` / `WorkflowJoinMode` / `ShardStrategy` / `RunMode` / `FileStatus`）改为薄包装。§领域数据字典 更新 Lombok 样板说明 + 通用工具说明。
- **`ConsoleMetaQueryService.buildEnums` 新增 20 个枚举 key**：`priorityLevel` / `aiPromptDecision` / `checksumType` / `workflowJoinMode` / `fileDispatchRunStatus` / `fileDispatchStatus` / `fileReceiptStatus` / `pipelineRunStatus` / `compensationStatus` / `retryScheduleStatus` / `encryptType` / `compressType` / `errorSinkType` / `priorityBand` / `stepInstanceStatus` / `runMode` / `skipAction` / `workflowNodeRunStatus` / `deadLetterReplayStatus` / `skipThresholdMode`；同步 OpenAPI `CommonResponseMetaEnums` schema。
- **新增守护测试 `ConsoleMetaEnumRegistrationTest`**：扫描 `com.example.batch.common.enums` 包，强制每个公共枚举二选一 —— 要么在 `REGISTRATIONS` 里注册暴露给前端，要么加入 `EXCLUDED` 白名单并注明原因；同时断言所有公共枚举必须实现 `DictEnum`。
- `ConsoleMetaQueryService.EnumReg` 精简为 `(key, enumClass)` 两字段；委托 `DictEnum::code`、`DictEnum::label`。
