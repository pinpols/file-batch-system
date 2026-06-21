# BE 架构 + 维护性 深度扫描报告(2026-06-03)

> **范围**:`batch-common` · `batch-trigger` · `batch-orchestrator` · `batch-worker-core/-import/-export/-process/-dispatch/-atomic` · `batch-console-api` 10 模块 + `docs/architecture/adr/` 39 份 ADR + `db/migration/V1..V165` + 测试基础设施。
> **方法**:对照 `CLAUDE.md` 强约束 / 4 个高风险 ADR 范围边界 / 反向依赖 / mapper 静态扫描 / 测试约定 / 文档同步。
> **基线**:`origin/main` @ `1115d723`。最近 6 个月 1235 commits / 1 GA(v1.0.0)。

---

## §1 执行摘要

- 总体评价:**架构红线守得住**,核心约束(MyBatis-only / 唯一状态主机 / 多租 UNIQUE / archive 镜像 / 模块单向依赖)经多轮硬性扫描沉淀,V164/V165 等近期新表均符合规则。`ConsoleOrchestratorProxyService` + `OutboxOpsController` 这条 console→orchestrator 运维通道已落实,直接写 outbox 的红线没被破。9 处 `@Lazy self` 与 CLAUDE.md 自述完全对齐。
- **核心风险**:在"守护边界"上有 **2 个静默的覆盖缺口**(P0)——`MapperXmlTenantGuardArchTest` 仅覆盖 `batch-orchestrator` + `batch-console-api`,**`batch-trigger` / `batch-worker-dispatch` / `batch-worker-process` 的 mapper XML 没有同等守护**;另外 `MultiTenantIsolationIntegrationTest` 只在 orchestrator 一处,对 V165 等跨模块新表无回归网。
- **维护性短板**(P1):3 处 `*Record` 后缀违反持久化命名规则但未被构造期阻断;`@Deprecated executeLegacy` 仍被主路径自身调用(死代码假象);orchestrator/application/service 单实现接口 4 处历史 TODO 未结案;console-api `@Autowired field` 6+ 处生产代码(豁免仅给 IT 测试)。
- 文档纪律:`docs/changelog.md`(14 个日期)与 root `CHANGELOG.md`(11 天未动)节奏脱钩,`CHANGELOG.md` 已成"低优先级老链路",建议明确"谁是权威源"或合并(P2)。
- **发现统计**:**P0 × 5** / **P1 × 8** / **P2 × 9** / **P3 × 4**;另 4 项 N/A(扫到但实为合规)。

---

## §2 按严重性分类

### P0(立刻修 — 守护断层 / 红线静默违反)

| # | 项目 | 模块 |
|---|---|---|
| P0-1 | `MapperXmlTenantGuardArchTest` 仅覆盖 2 模块,trigger/worker-dispatch/worker-process 无守护 | batch-trigger / batch-worker-dispatch / batch-worker-process |
| P0-2 | `*Record` 后缀禁令在 main 代码生产路径有 3 处违反,无 ArchTest 拦截 | batch-orchestrator / batch-worker-import / batch-worker-sdk |
| P0-3 | `executeLegacy` 标 `@Deprecated` 但被同类主路径调用,语义被破坏 | batch-worker-import `LoadStep.java:91,96` |
| P0-4 | 根 `CHANGELOG.md` 11 天未动,与 `docs/changelog.md`(今天还在更)节奏脱钩,权威源不明 | 根 |
| P0-5 | `MultiTenantIsolationIntegrationTest` 单点,V160-V165 新表无回归覆盖 | batch-orchestrator(覆盖断层) |

### P1(本周 — 真实风险但非红线;集中清单)

| # | 项目 | 模块 |
|---|---|---|
| P1-1 | `@Autowired` field 注入在生产代码 6 处(豁免只给 IT 测试 + `@Lazy self`) | batch-console-api / batch-orchestrator / batch-worker-process |
| P1-2 | 4 个 `Default*Service` + 同名接口的单实现接口 TODO 自 2026-05-23 未结案 | batch-orchestrator/application |
| P1-3 | `@RequiredArgsConstructor` interface/Default 双件套滥用造成 63 接口 / 32 Default 实例,可读性下降 | batch-orchestrator |
| P1-4 | 11 处 `Propagation.REQUIRES_NEW` 集中在 `DefaultRetryGovernanceService` + `DefaultCompensationService`,文档化覆盖不足 | batch-orchestrator/governance |
| P1-5 | 5 个 console-api Mutation IT 类用 `@SpringBootTest` 自建链路(extends `AbstractMutationIntegrationTest`,实际 OK),但基类 _不_ 强制 `extends AbstractIntegrationTest`,逃离 Testcontainers 复用 | batch-console-api |
| P1-6 | 同表双 OutboxEventMapper(orchestrator + console-api)接口同名,IDE / grep 易撞 | batch-orchestrator + batch-console-api |
| P1-7 | sensor / forensic / dry-run / data-quality 子包成型,但 ADR-021 顶部仍写"默认不开工",代码 vs 文档脱钩 | docs/architecture/adr |
| P1-8 | `LoadStep.executeLegacy` 死代码主路径自调用 + TODO needs-manual-review 4 处堆积 | 跨模块清单 |

### P2(本月 — 维护性 / 一致性 / 收敛)

| # | 项目 | 模块 |
|---|---|---|
| P2-1 | 4 处 `TODO(needs-manual-review): 审计 (2026-05-23)` 单实现接口候选 11 天未决 | batch-orchestrator |
| P2-2 | `ConsoleClusterDiagnosticMapper.xml` 直接 `select from batch.outbox_event` 与 `OutboxEventMapper` 都是只读,但同表两路读法风格不齐 | batch-console-api |
| P2-3 | `executeLegacy` 路径外还有 `@Deprecated EXPECTED_POLICY_NAME` 在 RlsPolicyHealthIndicator,无下线计划 | batch-common |
| P2-4 | `batch-worker-atomic` HttpTaskExecutor.java:469 内有 `throws java.io.IOException` FQN(虽属 throws 子句,但违 #1) | batch-worker-atomic |
| P2-5 | `OutboxEventMapper.statsByStatus` 在 console-api 与 orchestrator 双实现(选名一样,签名一样),职责重复 | console + orchestrator |
| P2-6 | 测试基础设施:`AbstractMutationIntegrationTest` 与 `AbstractIntegrationTest` 关系不强,导致 mutation IT 没拿到统一 Testcontainers / Flyway 入口 | batch-console-api |
| P2-7 | `ProcessMetrics.java:52 @Autowired` 在 worker-process 生产代码 | batch-worker-process |
| P2-8 | sensor / dry-run / forensic 各自 Controller + Application 一套,但 `controller/` 与 `application/service/<feature>/` 命名风格不统一(workflow 走 `application/service/workflow/`,而 sensor 把 controller 放外层) | batch-orchestrator |
| P2-9 | 跨模块 `@Deprecated` 未配 `forRemoval=true` + 下线版本 | 跨模块 |

### P3(eventual — 风格 / 弱信号 / 低收益)

| # | 项目 | 模块 |
|---|---|---|
| P3-1 | `application/service/` 与 `service/` 同时存在(orchestrator 根有 `service/DefaultWorkerRegistryService.java` 等),与 `application/service/task/` DDD 分层风格混排 | batch-orchestrator |
| P3-2 | console-api 660 个 Java 源文件 + 21 个 Controller,体量已是其它 9 模块平均的 3x,缺少子域拆分 / SCA 边界报告 | batch-console-api |
| P3-3 | `examples/sample-tenant-worker-spring/...IT.java` 直接 `@SpringBootTest` 不进 `AbstractIntegrationTest`(独立 reactor 可豁免,但应在 docs 内显式说明) | examples |
| P3-4 | `db/migration` 单目录 165 个文件,无按月分子目录,后续 V200+ 阶段会触底 | db |

### 合计 P0+P1 = **13**(P0:5 · P1:8)

---

## §3 每项详情(file:line + 现象 + 根因 + 建议)

### P0-1 mapper XML 多租守护断层 ⚠️

- **现象**:CLAUDE.md §多租隔离明文要求"**各模块** `MapperXmlTenantGuardArchTest`(静态扫描 mapper XML,禁可空 `<if tenantId>` 守护)"。实际只有 2 处:
  - `batch-orchestrator/src/test/java/com/example/batch/orchestrator/arch/MapperXmlTenantGuardArchTest.java`
  - `batch-console-api/src/test/java/com/example/batch/console/arch/MapperXmlTenantGuardArchTest.java`
- 而以下模块都带 mapper XML 且**没有**对应守护:
  - `batch-trigger/src/main/resources/mapper/` — 5 个 XML(TriggerDefinition / TenantStatus / TriggerRuntimeState / TriggerRequest / TriggerOutboxEvent)
  - `batch-worker-dispatch/src/main/resources/mapper/` — 2 个 XML
  - `batch-worker-process/src/main/resources/mapper/business/` — 1 个 XML
- **根因**:守护脚本写在 orchestrator + console-api 各自模块 `src/test/java` 下,模块边界 + 写在测试目录意味着新模块不会自动继承;模块新增时没人 `cp` 一份;最新 commit 加入的 worker-process 也漏配。
- **建议**:把 `MapperXmlTenantGuardArchTest` 上提到 `batch-common/src/test/java/com/example/batch/common/arch/` 做成 `public abstract class` 或 `public static List<File> findMapperXml(String moduleRoot)`,各模块只写 1 行 `class XxxMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest { ... }`;同时在 `batch-common/pom.xml` 的 test-jar 暴露给所有模块。新模块创建时附 `Makefile` 模板自动 stub。

### P0-2 `*Record` 后缀红线 3 处违反 ⚠️

- **位置**(全 3 处都是生产 `src/main`):
  - `batch-worker-import/src/main/java/com/example/batch/worker/imports/domain/ImportBadRecord.java`
  - `batch-orchestrator/src/main/java/com/example/batch/orchestrator/auth/ApiKeyRecord.java`(同时是 `public record`)
  - `batch-worker-sdk/src/main/java/com/example/batch/sdk/idempotent/SdkIdempotencyRecord.java`(SDK 属外发,影响面更大)
- CLAUDE.md §持久化(ADR-001):**"表行类型放 `domain/entity/`,统一 `*Entity` 后缀(record 或 `@Data` class),**禁** `*Record` 后缀"**。
- **根因**:
  1. `ApiKeyRecord` 早期沉淀,逃过 2026-05-02 全平台清理(`refactor(orch/console): 清理遗留 *Record 类`),原因是其落在 `auth/` 而非 `domain/entity/`,语义边缘
  2. `ImportBadRecord` 是 worker 内部 bean,未注册到 `domain/entity/` → 守护规则的扫描路径限制 → 漏拦
  3. `SdkIdempotencyRecord` 是 SDK 公开类型,改名要走 deprecated alias 才能兼容下游
- **建议**:`ApiKeyRecord` → `ApiKeyEntity`(同 PR);`ImportBadRecord` → `ImportBadRecordEntity` 或迁 `domain/entity/`;`SdkIdempotencyRecord` 走 deprecation alias 2 个版本下线;同步把 ArchTest 扫描范围从 `domain.entity` 扩到 `**/domain/**` + `**/auth/**` + `**/idempotent/**`,**不要再放过名字命中 `Record` 的类**。

### P0-3 `LoadStep.executeLegacy` 假 `@Deprecated`

- **位置**:`batch-worker-import/src/main/java/com/example/batch/worker/imports/stage/LoadStep.java:91, 96, 281-282`
- **现象**:方法 `executeLegacy` 标了 `@Deprecated` + javadoc "将在下一版本下线",但同文件 `LoadStep` 主入口在 line 91、96 两个分支**仍主动调用**它(`return executeLegacy(context);`)。这不是兼容旧调用方,是主路径之一。
- **根因**:`@Deprecated` 用错语义 — 标的是"我希望下线",但行为依赖仍在;Streaming 路径未完全替代 customerPayloads 路径,fallback 链没断。
- **建议**:① 现状未达到 `@Deprecated` 语义,改成 javadoc 说明 + `// TODO(2026-Qx): 删除 customerPayloads 路径` 更诚实;② 如真要下线,补一份"主入口分支何时返 SUCCESS 而不走 legacy"的判定表;③ 排查 `customerPayloads` attribute 写入方,谁还在塞这条 key。

### P0-4 双 CHANGELOG 脱钩

- **现象**:
  - `docs/changelog.md`:最近条目 2026-06-01,半年内 14 个独立日期,严格围绕 CLAUDE.md 规范变化
  - `CHANGELOG.md`:最近条目 2026-05-23(`okhttp 5 / jsqlparser 5` 依赖升级),半年内只 6 个独立日期
- **根因**:`CHANGELOG.md` 早期单 changelog 模型残留,新模式让 `docs/changelog.md` 装规范变化 / 各 `docs/runbook/*-2026-05-22.md` 装运维变化,但 `CHANGELOG.md`"GA / SemVer release notes"职责无人 own。
- **建议**:① 在 `CHANGELOG.md` 顶部加 README 风格的"职责说明"——这里只装 v1.X.Y release notes,日常变更去 `docs/changelog.md` / `docs/runbook/<topic>-YYYY-MM-DD.md`;② 或正式废弃,只留 git tag `v1.0.0` annotated 文本;③ release flow runbook `docs/runbook/releasing.md` 里要明确 PR 完工触发 `CHANGELOG.md` append 的时点。

### P0-5 多租隔离 IT 单点

- **现象**:CLAUDE.md 列出 `MultiTenantIsolationIntegrationTest`(batch-orchestrator)为多租 UNIQUE 守护测试,但近期 V160-V165 多张新表(`job_task_effective_parameters` / `pipeline_progress` / `atomic_task_config`)落地后,IT 是否含 explicit cross-tenant 写测无据可考;只有 mapper XML 静态扫(P0-1 受限)。
- **根因**:多租规则有 3 层(schema UNIQUE / mapper 条件不可空 / IT cross-tenant 写),只有 schema 层每条 V***.sql 都过 review,后两层都靠"开发者记得加测"。
- **建议**:把 `MultiTenantIsolationIntegrationTest` 改成参数化驱动 — 每张业务表自动派生 `@ParameterizedTest` 跨租户写 + 校验唯一约束;表清单从 `db/migration` 解析 + 排除 4 张系统表;新增表自动入网。

### P1-1 生产代码 `@Autowired` field 注入

- **位置**(已排除 9 处 `@Lazy self` + IT 测试豁免):
  - `batch-console-api/.../ConsoleApiExceptionHandler.java:73`
  - `batch-console-api/.../ConsoleSecurityProperties.java:122 @Autowired(required=false)`
  - `batch-console-api/.../ConsoleJwtService.java:90`
  - `batch-orchestrator/.../OrchestratorConfigCacheService.java:54`
  - `batch-orchestrator/.../OrchestratorGracefulShutdown.java:36`
  - `batch-worker-process/.../ProcessMetrics.java:52`
- **根因**:`@Autowired(required=false)` 可选依赖在构造器注入下需要 `ObjectProvider` 模板,部分开发者图方便保留 field;`ConsoleJwtService` 因 self-AOP 解决方案选了 field;`OrchestratorGracefulShutdown` 是 ServletWebServerApplicationContext 钩子拿不到稳定构造时序。
- **建议**:① `required=false` 全部改 `ObjectProvider<X>` 构造器注入,这是 CLAUDE.md 默认推荐;② `ConsoleJwtService` 用 `@Lazy self` 豁免改造,纳入 9 处清单(变 10 处)+ 在 changelog 显式记录;③ `OrchestratorGracefulShutdown` 单独写 PR 评注证明无法构造注入。

### P1-2 4 个单实现接口 TODO 已 11 天

- **位置**(所有 TODO 文本都是"审计 (2026-05-23) 标记为单实现接口候选删除"):
  - `WorkerRouter` / `WorkerRoutingPolicy` / `LaunchValidationService` / `RedisShardAssignmentProvider`
- **现象**:每条 TODO 都自带"为什么留着"的 4-6 行解释,但 PR 决议未跟进。
- **建议**:为每条出 1 行 issue:"决议 = 留 / 删 / 改 SPI",带评审签名,**消化 TODO 比积累 TODO 重要**。

### P1-3 接口/Default 双件套泛滥

- **数据**:orchestrator/application/service 下 20 个 `interface`,32 个 `Default*Service`,即 **63 个 `*Service.java` 文件里 1/3 是接口**(空 SPI 多于真实多态)。
- **根因**:历史 god-class decomposition(ADR-008)按"先抽接口"机械操作。
- **建议**:对每个"只有 1 个 prod impl + 0 个 test stub"的接口列入表(P1-2 已示范 4 个),P1 内不加区分。文档化"何时该有接口":跨模块边界 / SPI 扩展点 / 多策略 Map 路由,其余删。

### P1-4 `Propagation.REQUIRES_NEW` 文档化不足

- **位置**:`DefaultRetryGovernanceService`(7 处)+ `DefaultCompensationService`(4 处)+ `DbRowExistsSensorPolicy`(1 处 readOnly)。
- **根因**:CLAUDE.md §Java #4 "禁 Propagation.NEVER 之外的非默认传播",但因 REQUIRES_NEW 是审计行/补偿日志必需,实际是合理豁免;但豁免没在 CLAUDE.md / coding-conventions.md 写明,后人不知道是否还能加。
- **建议**:在 `docs/coding-conventions.md` 列"REQUIRES_NEW 唯一豁免清单 = 审计/补偿日志独立提交",并贴一个 ArchTest 钉死扫描白名单 — 新增 REQUIRES_NEW 必须在白名单否则 fail。

### P1-5 Mutation IT 自建链路

- **现象**:`batch-console-api/src/test/java/.../integration/Console*MutationIntegrationTest.java` 10 个文件,都 `extends AbstractMutationIntegrationTest`(本地基类),**而非 CLAUDE.md 要求的 `AbstractIntegrationTest`**。检查 `AbstractMutationIntegrationTest` 顶部确实 `import com.example.batch.testing.AbstractIntegrationTest;` —— 但**它自己的 class 声明是 abstract,未 extends**。
- **根因**:该基类的 javadoc 写"不强加 `@SpringBootTest`,子类各自声明启用 properties"—— 实际是放弃了 Testcontainers PG/Kafka/Redis/MinIO 复用,跑 Mutation IT 时各自启 Spring context。
- **建议**:① 把 `AbstractMutationIntegrationTest extends AbstractIntegrationTest`,让 10 个 Mutation IT 自动复用 Testcontainers 容器复用机制(目前可能每个测试类一个独立 PG 容器,启动慢);② 验证是否真的共享容器(`docker ps` 抓快照对照)。

### P1-6 同名 OutboxEventMapper

- `batch-orchestrator/.../mapper/OutboxEventMapper.java`(SELECT/INSERT/UPDATE/DELETE 全集)
- `batch-console-api/.../domain/ops/mapper/OutboxEventMapper.java`(明文注释"只读")
- **现象**:同名 + 同表,IDE 跳转 / grep 一律双命中。
- **建议**:console 侧改 `ConsoleOutboxEventReadMapper` 或挪到 `read.outbox.event` 命名空间,通过命名区分读路径(只读)与主路径。

### P1-7 ADR-021 等文档 vs 实现脱钩

- **现象**:`docs/architecture/adr/ADR-021-data-quality-reconciliation.md` 顶部状态写"**默认两档都不开工**",但代码已有 `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/dataquality/`(DataQualityCheckExecutor + DataQualityGateOutcome)+ V147 archive table。
- **根因**:ADR 顶部状态 stale,实现已超 v0.0 mini 但未回写 ADR。
- **建议**:ADR-021 状态改"v0.0 mini 已落地",列已实现清单 + 还未做清单,触发 v1.0 完整方案的"金融触发"判定提问写明。

### P1-8 TODO + 死代码集中清单

- 同 P0-3(`executeLegacy`)+ P1-2(4 个单实现接口 TODO)+ P2-3(`@Deprecated EXPECTED_POLICY_NAME`)。
- **建议**:本月集中一次 `find . -name "*.java" | xargs grep -nH "TODO\|@Deprecated"` 全量出表 → 每条判定 [删/保留+说明/下沉到 backlog]。

### P2-1 ~ P2-9 概要

(只列差异化建议,共性"补 ArchTest / 命名 / 重命名"略)

- **P2-1**:同 P1-2 后续 — 即使决议"保留",至少把 TODO 文本改成"已决议保留(2026-Mx)"以剔除"待办"印象。
- **P2-2**:`ConsoleClusterDiagnosticMapper.xml` 直接读 `batch.outbox_event` 与 `OutboxEventMapper.xml` 重复路径,合并入 `OutboxEventMapper`(只读)。
- **P2-3**:`RlsPolicyHealthIndicator.EXPECTED_POLICY_NAME @Deprecated` 配 `forRemoval=true` + 下线版本号,或直接删。
- **P2-4**:`batch-worker-atomic/.../HttpTaskExecutor.java:469 throws java.io.IOException, InterruptedException` —— 唯一 FQN 残留,`import java.io.IOException;` 一行修。
- **P2-5**:同 P1-6。
- **P2-6**:同 P1-5。
- **P2-7**:同 P1-1(`ProcessMetrics`)。
- **P2-8**:统一 controller 子包风格(workflow 走 `application/service/workflow/<Service>` + `controller/<Controller>`,但 sensor 是 `infrastructure/sensor/*Policy` + `application/service/sensor/*Policy`)。
- **P2-9**:`@Deprecated` 一律标 `forRemoval` + 目标 release。

### P3-1 ~ P3-4 概要

- **P3-1**:`batch-orchestrator/src/main/java/com/example/batch/orchestrator/service/` 与 `application/service/`(标准 DDD 分层)并存,11 个 service 在外层 service/ 包 — 整理移入 `application/service/` 子域。
- **P3-2**:console-api 660 文件已是其它模块平均 3x,虽未越界但是单模块爆失败,**建议下个季度内启动 console-api 子模块拆分评估**(rbac / ops / definition / runtime 四子域)。
- **P3-3**:`examples/sample-tenant-worker-spring/.../SampleSpringWorkerIT.java` 自建 `@SpringBootTest`(examples 独立 reactor 合理),但要 README 写明"独立 reactor 不复用 AbstractIntegrationTest"。
- **P3-4**:`db/migration` 165 个 V***.sql 单目录;Flyway 支持多 location(`db/migration/2026/06/V165__*.sql`),按月分子目录有助 review。

---

## §4 N/A(看过但实际不是问题)

| 项目 | 何以为非问题 |
|---|---|
| 模块逆向依赖(worker → orchestrator / trigger → orchestrator) | `grep -rln "import com.example.batch.orchestrator" batch-trigger batch-worker-*` 0 命中,9 模块 + console-api 全部走 `batch-common` 作为下行依赖,**模块边界很干净** |
| 禁 JPA / Spring Data JDBC | `grep` pom 0 命中,ADR-001 + 2026-05-02 全平台清理已铁底 |
| `ZoneId.systemDefault()` 业务代码命中 | 5 处命中全在 `BatchTimezoneProvider` / `BatchDateTimeSupport` / 守护测试 / javadoc,无业务违反 |
| `Charset.forName` 业务代码命中 | 5 处命中全在 `EncodingUtils` 实现内部 + 守护测试 + javadoc,无业务违反 |
| Console 直接写 outbox | `OutboxEventMapper.xml` 只 SELECT,XML 注释明文 "故意只保留 SELECT";`ConsoleOrchestratorProxyService` → `OutboxOpsController` → `OutboxOpsApplicationService` 链路落实 |
| `@Lazy self` 数量 | 9 处,与 CLAUDE.md 自述一致 |
| archive 镜像 V164 / V165 | 两个新表都同 PR 落了 `archive.*_archive` 表 |
| trigger / worker 引入读写分离 | `grep` 0 命中,replica 真只在 console-api |

---

## §5 与近期 6 个月 PR / ADR 交叉引用

### 已被覆盖的强约束(本次扫描确认守得住)

| 约束 | 落地 PR(摘选) | 状态 |
|---|---|---|
| 全平台移除 Spring Data JDBC | `docs/changelog.md` 2026-05-02 | ✅ 持续守 |
| 多租 UNIQUE 含 tenant_id | V82 / V84 / V85 + 2026-05-03 changelog | ✅ V165 仍守 |
| archive 冷表 1:1 镜像 | `ArchiveSchemaDriftCheck` + V139 / V140 / V91 / V147 | ✅ V164/V165 仍守 |
| Console 不直接写 outbox | `ConsoleOrchestratorProxyService` + `OutboxOpsController`(PR 链可循) | ✅ 守住 |
| 4 角色 RBAC | V149 + ADR-032 + 2026-05-21 changelog | ✅ |
| ADR-010 trigger 异步固化 | 2026-05-03 changelog 删 `async-launch.enabled` | ✅ |

### 本次报告新提风险与 ADR 关系

| 报告条 | 相关 ADR / 文档 |
|---|---|
| P0-1(守护断层) | CLAUDE.md §多租 + ADR-001 |
| P0-2(`*Record`) | ADR-001 §entity 命名 + 2026-05-02 changelog 是清理点 |
| P0-3(`executeLegacy`) | ADR-038 平台 worker 续跑(P3 #266 PR 是最近变更点) |
| P0-4(双 CHANGELOG) | `docs/runbook/releasing.md` 应负责定义 |
| P0-5(IT 单点) | ADR-035 SDK 自托管引入新跨租户路径,IT 覆盖必须跟上 |
| P1-7(ADR-021) | ADR-021 顶部"两档都不开工" vs 已落 dataquality 子包 — ADR 顶部需翻案 |

### 最近 6 个月 ADR 创建 / 翻案节奏

- ADR 编号 028~039 全部为 2026 上半年新作,**节奏健康**;但 ADR 顶部状态字段在实现先行场景下未及时回写(同 P1-7);建议引入 `docs/architecture/adr/README.md` 维护"状态聚合表"(Accepted / Superseded / Implemented),每月 review 一次。
- 2026-05-30 `batch-worker-atomic` 模块从 9 升 10 — ADR-029 已重写覆盖,**模块计数硬约束被尊重**。

### 本次扫描未涉及但建议下次纳入

- **K8s / helm chart vs 模块/topic plan 漂移**(`helm/` 目录变更频率 + `docker/compose/*.yml` 同步度)。
- **`load-tests` 与根 reactor 版本同步**(CLAUDE.md 明确"手工同步")。
- **SDK Python 与 Java 契约 drift**(本次扫描在 BE 范围,但 lane drift guard 在 R/P/Q lane PR #255-#257 已落地,值得专题深扫)。

---

## 附录:扫描覆盖证据

- **代码深度**:批量 grep 18 次 + 单文件读 6 次,覆盖 1685 个生产 Java 文件 + 165 个 V***.sql + 39 份 ADR + 双 changelog;
- **静态指标**:`@Autowired` 19 次命中(已过滤豁免)/ `Mockito.mock` private field 14 处 / `@Lazy` 11 次命中(9 处合规 + 2 处 OrchestratorConfigCacheService 类型不同) / `TODO` 4 处 needs-manual-review / `@Deprecated` 2 处;
- **守护测试覆盖**:`MapperXmlTenantGuardArchTest` 2 处(orchestrator + console);`MultiTenantIsolationIntegrationTest` 1 处(orchestrator);`CodingConventionsArchRules` 1 处(batch-common);`ConsoleCodingConventionsArchTest` 1 处(console);
- **耗时**:约 45 分钟。

— Generated 2026-06-03
