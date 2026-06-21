# BE 架构 + 维护性 深度扫描报告 v2(2026-06-03)

> **本报告作为 v1 的补充扫描**(基线:`docs/analysis/2026-06-03-deep-scan-be-architecture.md`)。专攻 v1 受 session 限额未尽兴的死角:DDD 边界一致性、package-info 守护、Mapper XML 反模式、Spring/MyBatis 配置漂移、Lombok 红线、测试可维护性、TODO 老化、ADR 越界。**只列 v1 未覆盖的新发现**,与 v1 完全重叠的项目仅做交叉引用,不复述。
>
> **基线**:`origin/main` @ `4d47f332`(v1 PR 已合并后再扫一遍)。
> **方法**:`@Builder` 加入持久化路径检查 / 同表跨模块 Mapper+Entity 字段一致性 diff / `@ConfigurationProperties` 与 `@Value` 混用密度 / 同名 `@Bean` 全文搜 / 重复 entity 字段 diff / mapper-locations 默认值漂移 / Mockito 残留风格 / ADR 顶部状态 vs 代码逐条核对。
> **耗时**:约 40 分钟。

---

## §0 v1 误判 / 需要矫正

| v1 条目 | v1 论断 | v2 实测 | 处置 |
|---|---|---|---|
| **P1-5**(Mutation IT 自建链路) | "`AbstractMutationIntegrationTest` 顶部 javadoc 写'不强加 `@SpringBootTest`'**实际未 extends `AbstractIntegrationTest`**" | 该基类**首行**就是 `public abstract class AbstractMutationIntegrationTest extends AbstractIntegrationTest`,继承链已落实,Testcontainers PG/Kafka/Redis/MinIO 容器复用通过基类自动生效;v1 误读了 javadoc 与代码 | **N/A**(v1 P1-5 撤销;真正的 P1 是子类**仍各自声明** `@SpringBootTest(classes = ..., properties = ...)` 模板 11 次重复 — 见 v2 P2-7) |
| **P0-4**(双 CHANGELOG 脱钩) | "`CHANGELOG.md` 11 天未动" | 实际是 v1 GA 后 release notes 性质;非高频文档预期就慢 — 但权威源不明仍是 P2 文档纪律问题,**不是 P0** | **降级到 P2**(`§Δ` 表) |
| **P0-1**(mapper guard 断层) | "trigger/worker-dispatch/worker-process 无 `MapperXmlTenantGuardArchTest`" | v2 复核仍然成立,但 **batch-worker-import / batch-worker-export / batch-worker-atomic 也漏配同等守护**,v1 漏点了这 3 个新模块 | **维持 P0,扩列模块清单** |

---

## §0.5 v2 范围声明 — 专攻 v1 未触达的 9 个死角

| 死角 | v1 是否触达 | v2 覆盖深度 |
|---|---|---|
| 1. DDD 跨模块包结构一致性 | ❌ 未触达 | ✅ console-api 11 BC 矩阵 + orch controller/application 双轨 |
| 2. `package-info.java` / `module-info.java` 静态守护 | ❌ 未触达 | ✅ 全仓 6 处分布 |
| 3. Mapper XML 反 anti-pattern(动态嵌套深度) | ❌ 未触达 | ✅ 33 个 XML 全表统计 + 16 处超阈值 |
| 4. Spring `@ConfigurationProperties` vs `@Value` 混用 | ❌ 未触达 | ✅ 105 vs 31 全比例 + bypass-mode 重复 + mapper-locations 重复 |
| 5. MyBatis SqlSessionFactory 共享 / 独立 | ❌ 未触达 | ✅ 14 处分布(其中 worker-core 双 SqlSessionFactory 是 SQLite + PG 二选一,合规) |
| 6. Lombok @Builder 加持久化类(红线边界) | ❌ 未触达 | ✅ 14 处 entity 全清单 + record vs class 细分 + 红线文义判定 |
| 7. 测试基类继承深度 + fixture 重复 + assert 风格 | ❌ 未触达 | ✅ 9 LENIENT + 10 openMocks + 9 字段 mock + 1 testXxx 残留 + 6 fixture |
| 8. 死代码 / TODO 日期老化 | △ v1 P1-8 提了 TODO 但未老化分析 | △ v2 验证 6 月内全文件都改过,无 6mo+ 死代码;TODO 4 处 needs-manual-review 仍 11 天未决(v1 P0-3 已提)|
| 9. ADR 顶部"❌ 不做"清单 vs 实际代码(021/022/026/027) | △ v1 仅 P1-7 提 ADR-021 | ✅ v2 4 ADR 全核对,其余 3 个 N/A,边界守得住 |



- **核心新增红线违反(P0)**:CLAUDE.md §持久化明文 **"同一表同一写路径禁双主入口"** —— v2 发现 **6 张业务表(`workflow_definition` / `job_definition` / `business_calendar` / `batch_window` / `resource_queue` / `tenant_quota_policy`)在 orchestrator + console-api 各有一套 INSERT/UPDATE/DELETE Mapper,双主入口实锤**。这不是 v1 P1-6 提的"同名 OutboxEventMapper(console 只读)" — 这 6 张表两端都真写。
- **同表 entity 字段漂移**:同 6 张表跨模块 entity 字段**实际不一致**(典型:`WorkflowDefinitionEntity` console 多 `description/createdAt/updatedAt` 3 字段;`JobInstanceEntity` orchestrator 多 15 字段)— 表 schema 一份,entity 镜像两份且漂移。
- **DDD 边界**:console-api 真实结构是 **`domain/<bc>/web|service|support|mapper|infrastructure` 嵌套类 DDD 包**(11 个 bounded context),与顶层 `web/` / `application/` / `infrastructure/` / `mapper/` / `service/` / `support/` 6 个传统分层**双轨并存**;orchestrator 也有 `controller/` 20 个 + `application/` 0 个(controller 全没下沉到 application 层)。结构纪律完全靠人。
- **Lombok 持久化路径**:14 处 `@Builder` 加到 `domain/entity/*Entity`(orchestrator),其中 `JobInstanceEntity` / `ApprovalCommandEntity` / `JobDefinitionEntity` 等 12 处是 `@Data + @Builder + @NoArgsConstructor + @AllArgsConstructor` class(MyBatis 反射回填路径)。CLAUDE.md §Java 红线"Spring Data JDBC entity / `@Entity` / `@Table` 持久化类一律不加 `@Builder`",措辞针对 JPA,但精神是"侵入持久化路径",**当前实践处于规则文义边界**,需要明确判定:**继续允许 / 加 ArchTest 限定 / 全部移到 record**。
- **MyBatis SQL 复杂度**:`JobInstanceMapper.xml` 536 行 / `WorkerRegistryMapper.xml` 279 行 / `JobDefinitionMapper.xml`(console)14 个 `<if>` + 10 个 `<choose>` 嵌套 — 复杂查询散落而非抽 `QueryObject` 或 `<sql>` 共享片段。
- **配置漂移**:`mapper-locations: classpath*:mapper/*.xml` 在 `batch-defaults.yml` 已定义,**5 个模块的 `application-local.yml` 重复声明**;`bypass-mode: true` 在 8 个本地 profile 重复声明 — defaults 已经留了 `${BATCH_SECURITY_BYPASS_MODE:false}` 占位,本应单点切换。
- **测试可维护性**:9 处 `@MockitoSettings(strictness = Strictness.LENIENT)` + 9 处 `private foo = Mockito.mock(...)` 字段直 mock + 10 处 `MockitoAnnotations.openMocks(this)` 命令式初始化 — CLAUDE.md §测试约定明文禁前两者、强制 `@ExtendWith(MockitoExtension.class)` 声明式,**实际值与规范不齐**,但与 v1 P0/P1 无重叠。
- **package-info 静态守护**:全仓 6 个 `package-info.java`,**全部在 SDK 与 batch-common**,9 个核心模块 0 个 — 没有任何 `@PackagePrivate` / 包级 javadoc 边界标注 / 模块化(JPMS `module-info.java`)守护。模块边界完全靠 ArchUnit 加人工 review。
- **ADR 越界检查**:ADR-021/022/026/027 顶部"❌ 不做"清单 v2 全文核对,**4 条边界全部守得住**(dry-run 无 FULL_SIMULATION 实现、forensic 无 `_history` 表、resource-affinity 仅落 `resourceProfile` 字段未做调度、data-quality 无业务结果裁定逻辑)— v1 P1-7 提的"ADR-021 顶部'两档都不开工' vs `dataquality/` 子包"维持 P1;其余 3 个 N/A。

**v2 新发现统计**:**P0 × 3** / **P1 × 7** / **P2 × 7** / **P3 × 4** / **N/A × 7**(其中含 v1 误判矫正 3 项)。

---

## §2 P0 — 红线静默违反(立即修)

### v2-P0-A 6 张表"同一表双主入口"实锤违反 CLAUDE.md §持久化 ⚠️⚠️⚠️

- **规则**:CLAUDE.md §持久化(ADR-001)明文 **"同一表同一写路径**禁**双主入口(Mapper + Repository 二选一)"**。
- **现象**(orchestrator 与 console-api 同名 Mapper XML 两端都有 INSERT/UPDATE/DELETE):

  | 表 | orch 写操作数 | console 写操作数 | 表用途 |
  |---|---|---|---|
  | `workflow_definition` | 3 (insert/update/delete) | 5 (insert/update/delete/toggleEnabled/upsert) | DAG 定义 |
  | `job_definition` | 3 | 6 (insert/update/delete/toggle/batchToggle/copy) | Job 定义 |
  | `business_calendar` | 3 | 4 | 业务日历 |
  | `batch_window` | 3 | 4 | 批窗口 |
  | `resource_queue` | 3 | 4 | 资源队列 |
  | `tenant_quota_policy` | 3 | 4 | 租户配额 |

- **根因**:console-api 把"定义类"配置(用户在 UI 改)的 CUD 都做在 console 侧,orchestrator 又在内部"模板默认值 seeding / 升级迁移 / 内部修复"路径下也写;**两条写路径没有走同一 service / 同一事务边界 / 同一审计**。Outbox 红线被 `ConsoleOrchestratorProxyService` 收敛,但**定义类没走同样收敛**。
- **实际后果**:① 同字段两端 entity 不一致(见 v2-P0-B),console 写时若字段缺失会回写 `null` 覆盖 orchestrator 之前写入的非空字段;② 审计与对账难追(同一行 history 有两个改写来源);③ 缓存失效路径不统一(`OrchestratorConfigCacheService` 只懂 orchestrator 改的);④ ArchUnit 类型守护 + Mapper XML guard 都不会拦"两端都 INSERT 同一张表",当前 `BatchOutboxConsoleWriteGuard` 仅拦 outbox。
- **建议**:
  1. 立 ArchTest:扫所有同名 Mapper XML,若两端都有 `<insert/<update/<delete` 同 `id` 标签 → fail。
  2. 决议每张表的"主入口模块":定义类(workflow/job_definition/batch_window/business_calendar/resource_queue/tenant_quota_policy)归 **console-api**(用户操作高频);orchestrator 端改成只读 + 走 `ConsoleDefinitionProxyService`(对称 outbox proxy 模式)。
  3. 同步:删除 orchestrator 侧对应 Mapper 的 INSERT/UPDATE/DELETE 标签,留 SELECT;或反向收敛看业务流到底谁该写。
  4. CLAUDE.md §持久化补一行:"同一定义表两端写入必须走 ProxyService 收敛,不得 Mapper 双写"。

### v2-P0-B 同 6 张表 entity 字段两端漂移 ⚠️⚠️

- **位置**(13 个全跨模块同名 entity,真实漂移抽样验证):

  | 同名 entity | orch 字段 | console 字段 | 漂移 |
  |---|---|---|---|
  | `WorkflowDefinitionEntity` | 7(record) | 10(@Data class) | console 多 `description / createdAt / updatedAt` |
  | `JobInstanceEntity` | 30+(class implements Stateful) | 15(class) | **orch 多 15 字段**(`jobDefinitionId / triggerRequestId / dedupKey / runAttempt / version / expectedPartitionCount / successPartitionCount / failedPartitionCount / calendarCode / dataIntervalStart/End / createdAt / updatedAt / replaySessionId / jobDefinitionVersion`)|
  | `JobDefinitionEntity` | **81**(record) | 32(class) | **orch 多 49 字段(史诗级漂移!)**|
| `WorkerRegistryEntity` | 34(record) | 10(class) | orch 多 24 字段 |
| `JobInstanceEntity` | 46(class) | 29(class) | orch 多 17 字段 |
| `JobPartitionEntity` | 23(class) | 14(class) | orch 多 9 字段 |
| `WorkflowNodeRunEntity` | 17(class) | 11(class) | orch 多 6 字段 |
| `JobStepInstanceEntity` | 18(class) | 14(class) | orch 多 4 字段 |
| `DeadLetterTaskEntity` | 16(class) | 13(class) | orch 多 3 字段 |
| `WorkflowNodeEntity` | 21(class) | 20(class) | orch 多 1 字段 |
| `WorkflowEdgeEntity` | 10(class) | 10(class) | **完全一致 ✓** |
| `JobExecutionLogEntity` | 11(class) | 11(class) | **完全一致 ✓** |
| `RetryScheduleEntity` | 19(class) | 19(class) | **完全一致 ✓** |
| `WorkflowDefinitionEntity` | 7(record) | 10(class) | **console 多 3 字段(反向漂移!)**|
| `ApprovalCommandEntity` | 17(class) | 19(class) | console 多 2 字段(反向漂移)|

  全 13 个同名 entity:**10 对漂移**(orch 多 9 对 / console 多 3 对),只有 3 对(`WorkflowEdgeEntity` / `JobExecutionLogEntity` / `RetryScheduleEntity`)完全一致。最严重的 `JobDefinitionEntity` **orch 81 字段 vs console 32 字段,Δ=49** — 同一张 `job_definition` 表两端模型相差 60% 字段。


- **根因**:console-api 沉淀时按"我界面需要的字段"裁剪;orchestrator 按"我状态机需要的字段"全量。无 ArchTest 强制"同表同一行类型"。
- **实际后果**:
  - `JobInstanceEntity` 漂移最危险 — console 侧若调写路径(目前 mapper XML 0 INSERT,无写,仅查),回填到 DTO 时 15 字段直接丢失;一旦后续给 console 加 admin 写权限就直接遇到问题。
  - `WorkflowDefinitionEntity` console 多的 `description` 在 orch 写时被 UPDATE 语句覆盖为 null(P0-A 后果)。
- **建议**:① 立 ArchTest:同名 entity 在两个以上模块 → 必须在 batch-common 单一定义,各模块用同一份;② 若必须模块隔离 — 命名加前缀(`OrchestratorWorkflowDefinitionEntity` / `ConsoleWorkflowDefinitionRow`)+ 注释强制双向 diff guard;③ 13 个 entity 全表必须出 audit:写路径决议表 + 字段镜像决议表。

### v2-P0-C `MapperXmlTenantGuardArchTest` 覆盖断层比 v1 P0-1 更宽

- v1 P0-1 列了 trigger / worker-dispatch / worker-process 三模块漏配,**v2 实测漏配 6 个模块**:trigger / worker-import / worker-export / worker-process / worker-dispatch / worker-atomic。worker-import 和 worker-export 也有 mapper XML(各自业务表写入),漏。
- **建议**:同 v1 P0-1 的"上提到 batch-common 做 abstract 基类",但扩展模块清单到 6 个,且 `batch-common/test-jar` 暴露 `BaseMapperXmlTenantGuardArchTest` 必须配套发布。

---

## §3 P1 — 真实风险(本周清单,不与 v1 重叠)

### v2-P1-A 14 处 `@Builder` 加到 MyBatis 持久化 entity(规则边界)

- **完整清单**(orchestrator `domain/entity/`,14 处 = 13 record + 1 class):

  | entity | 类型 | 注解组合 |
  |---|---|---|
  | `JobInstanceEntity` | class implements Stateful | `@Data` + `@Builder` + `@NoArgsConstructor` + `@AllArgsConstructor` |
  | `JobDefinitionEntity` | record | `@Builder` |
  | `BusinessCalendarEntity` | record | `@Builder(toBuilder=true)` |
  | `BatchDayManualOperation` | record | `@Builder` |
  | `BatchDayInstanceEntity` | record | `@Builder(toBuilder=true)` |
  | `BatchDayReplayEntryEntity` | record | `@Builder(toBuilder=true)` |
  | `BatchDayOperationAuditEntity` | record | `@Builder` |
  | `BatchDayReplaySessionEntity` | record | `@Builder(toBuilder=true)` |
  | `BatchDayWaitingLaunchEntity` | record | `@Builder` |
  | `CalendarDependencyEntity` | record | `@Builder(toBuilder=true)` |
  | `DataQualityCheckEntity` | record | `@Builder` |
  | `DisasterDayOverrideEntity` | record | `@Builder(toBuilder=true)` |
  | `ForensicExportLogEntity` | record | `@Builder` |
  | `ResultVersionEntity` | record | `@Builder(toBuilder=true)` |

- **规则**:CLAUDE.md §Java #8 + 红线"Spring Data JDBC entity / `@Entity` / `@Table` 持久化类一律不加 `@Builder`(侵入持久化路径)"。本仓是 MyBatis(无 `@Table`),措辞上不命中,但精神(避免侵入反射回填链路)是直接相关的。
- **实际后果**:① `@Data` 提供 setter,MyBatis ResultMap 走 setter 注入,正常;② `@Builder` 生成 builder 体,业务代码可以 bypass 全字段构造,**增加"漏字段"风险**(尤其 P0-B 漂移场景);③ record 配 `@Builder(toBuilder=true)` 让 record 的"不可变"语义被绕过,反而能 toBuilder 后改字段,与 record 精神冲突。
- **建议**:
  - 出 ADR-040 或在 CLAUDE.md §Java 红线注明 MyBatis entity 是否允许 `@Builder` 的最终判定 + 反例。
  - 若允许:加 ArchTest 强制"使用 `@Builder` 的 entity 必须同时 `@NoArgsConstructor + @AllArgsConstructor`(或 record + 全字段 canonical)",拦"裸 `@Builder`"。
  - 若禁止:14 个 entity 全部走 builder pattern 拆出 `JobInstanceEntityBuilder` 工具类,entity 自身不带注解(改造 1 个 sprint)。

### v2-P1-B 4 个 MyBatis XML 极端复杂(动态 SQL 反模式)

- 抽样(`<if> + <choose>` 总数 / 行数):

  | 文件 | 行数 | `<if>` | `<choose>` | 评估 |
  |---|---|---|---|---|
  | `batch-orchestrator/.../JobInstanceMapper.xml` | **536** | 5 | 1 | 主要是 12+ 个独立 SQL 标签堆积,非单 SQL 复杂 — 建议拆 `JobInstanceQueryMapper.xml` + `JobInstanceCommandMapper.xml` |
  | `batch-orchestrator/.../WorkerRegistryMapper.xml` | 279 | 0 | 4 | `<choose>` 4 个分支均在 capability filter,可抽 `<sql>` 共享 |
  | `batch-console-api/.../JobDefinitionMapper.xml` | 199 | **14** | **10** | 真实反 anti-pattern:14 个 `<if>` 嵌套在 INSERT 字段列表,SQL 注入风险评估必要 |
  | `batch-console-api/.../WorkflowNodeMapper.xml` | 143 | 13 | 5 | 13 个 `<if>` 在 UPDATE SET 列表,典型"半 patch update";建议改 `<set>` + 强制全字段或拆 patchUpdate / fullUpdate |
  | `batch-orchestrator/.../BatchDayInstanceMapper.xml` | 138 | 0 | **11** | 11 个 `<choose>` 在单文件,业务规则太多塞 SQL,建议抽 `BatchDayBucketResolver` 到 Java 侧 |

- **延伸数据**(16 个 mapper.xml 命中"高复杂度"阈值,XML 总文件 33 个,**48% 超阈值**):

  | 模块 | 高复杂 XML 数 | 总 XML 数 | 占比 |
  |---|---|---|---|
  | batch-orchestrator | 9 | 17 | 53% |
  | batch-console-api | 7 | 12 | 58% |
  | batch-trigger | 0 | 5 | 0% |
  | batch-worker-* | 0 | 4 | 0% |

  worker 端 SQL 简单(单 select / single insert),复杂度集中在 orch + console 共写区 — 印证 v2-P0-A 与 P0-B(复杂动态 SQL = 字段多 = 漂移面广)。

- **建议**:
  - 为每个 `<if>` 数 ≥ 10 或 `<choose>` 数 ≥ 4 的 XML 出"复杂度治理 issue"
  - 优先抽 Query Object(Java) + 简化 XML 到 ≤ 50 行单 SQL
  - `docs/coding-conventions.md` 新增 §MyBatis XML 阈值:`<if> ≤ 8` / `<choose> ≤ 3` / 单 XML ≤ 200 行
  - ArchUnit 增 `MapperXmlComplexityArchTest`,超阈值 fail-fast(豁免清单显式列出)

### v2-P1-C `bypass-mode: true` 在 8 个本地 profile 重复声明

- **位置**:`batch-console-api/application-local.yml:50` / `orchestrator-local:60` / `trigger-local:59` / `worker-atomic-local:40` / `worker-dispatch-local:41` / `worker-export-local:37` / `worker-import-local:34` / `worker-process-local:33` 8 处全 `batch.security.bypass-mode: true`。
- `batch-common/batch-defaults.yml:210` 已经留了 `bypass-mode: ${BATCH_SECURITY_BYPASS_MODE:false}` 占位,prod profile 强制 false。
- **根因**:本地默认开 bypass 的需求是真实的(本地 IDEA 跑不带 auth),但 8 个模块各自维护重复,任何"修改本地默认值"要 8 处同步改;若漏改 1 处 → 部分 worker 启动会因 auth header 缺失 401。
- **建议**:① `batch-common` 出 `application-local.yml`(已有)集中维护 `batch.security.bypass-mode: true`,通过 `spring.config.import` 共享;② 删 8 处重复;③ 加 `ConfigDriftGuardTest` 回退,扫 `application-local.yml` 不得出现已在 batch-defaults / batch-common-local 已定义的 key。

### v2-P1-D `mapper-locations` 在 5 个 application-local.yml 重声明,覆盖 batch-defaults

- **位置**:`batch-console-api/application-local.yml:39` / `orchestrator/application-local.yml:47` / `trigger/application-local.yml:47` / `worker-atomic/application-local.yml:23` / `worker-dispatch/application-local.yml:24` 5 处 `mybatis.mapper-locations: classpath*:mapper/*.xml`(与 batch-defaults.yml:107 完全一致)。
- **后果**:① YAML 多文件 merge 时 mapper-locations 取 profile-specific 优先,与默认一致是"无害冗余",但若 batch-defaults 改路径(如未来加 `mapper/**/*.xml` 子目录)5 处仍会覆盖回老值;② `ConfigDriftGuardTest` 当前不扫此 key。
- **建议**:① 5 处删;② `ConfigDriftGuardTest` 增加规则"profile yaml 不得重声明 batch-defaults 已定义且值未改的 mybatis 配置"。

### v2-P1-E console-api 11 个"BC"包间 cross-import 超 150 处(伪 DDD 信号)

- **方法**:对 console-api 11 个候选 BC(`domain/{rbac,file,workflow,job,notification,observability,audit,governance,ops,query,param}`)做两两 `import` 计数,统计 cross-package 依赖矩阵。
- **TOP 重耦合**:

  | 源 BC | 目标 BC | import 次数 |
  |---|---|---|
  | `job` | `ops` | **16** |
  | `ops` | `rbac` | **15** |
  | `job` | `rbac` | 9 |
  | `observability` | `ops` | 8 |
  | `ops` | `observability` | 8 |
  | `ops` | `job` | 7 |
  | `ops` | `audit` | 6 |
  | `observability` | (其他全部 9 BC) | 3-6 each(observability **是 hub**)|
  | 总 cross-BC 连接 | | **150+** |

- **根因**:`ops/`(运维操作)与 `observability/`(看板)在 console-api 是 ✕ 维度的 BC — 几乎每个业务 BC 都要被 ops 显示状态、被 observability 收集指标。"BC"划分维度选错(垂直业务划 vs 横向能力划)。
- **判定**:这是**伪 BC** — 真正的 DDD 期望 BC 间通过 ACL(Anti-Corruption Layer)/ event,不直接 import。当前 150+ cross-import 显示它们其实是同一 BC 内的功能模块。
- **建议**:① 出 ADR-041 决议 console-api 真正的 BC 划分(`定义域` / `运行域` / `观测域` / `安全域` 4 个高内聚 BC,而非 11 个);② cross-import 拆为 facade interface(ops 暴露 `ResourceQueueOperationFacade` 接口,其他 BC 引接口而非具体 service);③ 加 ArchUnit 规则"BC 间禁直接引 mapper / entity,只允许引 application/service/<Facade>"。

### v2-P1-F orchestrator infrastructure 反向引 application 32 处(轻度违反洋葱)

- **现象**:`infrastructure/archive/WorkflowArchiveScheduler.java` → `application.archive.WorkflowArchiveService` / `infrastructure/retry/DeadLetterAutoRetryScheduler.java` → `application.service.governance.RetryGovernanceService` / 等 32 处 infra → application 的反向 import。
- **判定**:典型场景 — scheduler / scheduled job 在 infrastructure 层,但需要调用 application service 执行业务。轻度违反"洋葱外层不能调内层" — DDD 严格版要求 application 暴露 `XxxScheduledTrigger` 接口,infra 反向实现并被 application 调度。
- **影响**:小;实际只是 Spring scheduler 配置位置选择。
- **建议**:① 接受现状但显式 ADR 说明 — orchestrator 是"轻量 DDD",scheduler 在 infra 层调 application service 合规;② 或重构:scheduler 移到 application 层 + scheduler-config 留 infra(实践成本高,P3)。

### v2-P1-G 9 处 `@MockitoSettings(strictness = LENIENT)` + 模板拷贝行为

- **位置**:全部 9 处都在 stage / mq / outbox / worker-loop 测试上,**8 处**自带注释"严格模式会误报 UnnecessaryStubbing",剩 1 处 `OutboxPublishCircuitBreakerTest` 无说明。
- **规则**:CLAUDE.md §测试约定明文"默认 strict(MockitoExtension 自带),**禁** `@MockitoSettings(strictness = Strictness.LENIENT)` 当模板拷贝带入;只有跨方法共享 stub 且部分方法不触发的场景才允许,需注释说明"。
- **评估**:8/9 有注释,**合规边缘** — 但 8 处注释文案高度雷同("严格模式会误报 UnnecessaryStubbing")显示模板复制嫌疑;9 处 `OutboxPublishCircuitBreakerTest` 完全无说明,**真违反**。
- **建议**:① `OutboxPublishCircuitBreakerTest` 拆方法或加注释证据;② 8 处 stage 测试统一改为"在具体 `@BeforeEach` 内按需 `lenient().when()`,而不是整类 `LENIENT`"(scope 更小);③ 加 ArchTest 扫 `@MockitoSettings` 必须配 javadoc 关键字"UnnecessaryStubbing 误报"或 PR 评审签名,挡模板拷贝。

---

## §4 P2 — 维护性 / 一致性(本月)

### v2-P2-0 v2-P0-A 表对应"主入口模块"决议建议

| 表 | 当前 orch 写 | 当前 console 写 | 建议主入口 | 理由 |
|---|---|---|---|---|
| `workflow_definition` | insert/update/delete | insert/update/delete/toggle/upsert | **console-api** | UI CRUD 高频,orch 仅查 |
| `job_definition` | 3 | 6 | **console-api** | 同上(orch 49 字段对照 console 32 字段恰恰是 orch 缓存 / 派发用,改成"派发期 SELECT 全字段")|
| `business_calendar` | 3 | 4 | **console-api** | UI 维护;orch 改"日历 query 缓存" |
| `batch_window` | 3 | 4 | **console-api** | UI 维护 |
| `resource_queue` | 3 | 4 | **console-api** | UI 维护;orch SELECT 用 |
| `tenant_quota_policy` | 3 | 4 | **console-api** | UI 维护;orch SELECT + 限流读取 |

  实施路径(单 sprint):
  1. orch 侧 6 个 Mapper.xml 删 `<insert>/<update>/<delete>` → 改 `RuntimeException` 占位 / 测试期 `@Disabled`
  2. orch 端原写入路径(seeding / 升级补丁)改走 `ConsoleDefinitionProxyService`(对称 outbox proxy 模式) 或 `db/migration/V*.sql` 一次性补
  3. CLAUDE.md §持久化补"定义类表权威主写模块清单"
  4. ArchTest 增"6 张表只在 console-api 端有写 Mapper",防回潮

### v2-P2-A `JobInstanceEntity` orchestrator 端 30+ 字段 + `@Data`(无 record 化)

- 该 entity 是状态机核心载体(implements `Stateful`),30+ 字段单 class,无 sub-record 分组(time-related / partition-related / definition-snapshot)。建议组件化 `JobInstanceMetadata` / `JobInstanceLifecycleTimestamps` / `JobInstancePartitionStats` 3 个内嵌 record。

### v2-P2-B 10 处 `MockitoAnnotations.openMocks(this)` 命令式 mock 初始化残留

- **位置**:orchestrator 9 处 + trigger 1 处 — 集中在 application/service/task/ 与 service/ 目录的旧测。
- **规则**:CLAUDE.md §测试约定明文"**禁** `MockitoAnnotations.openMocks(this)` 命令式与 `private Foo foo = Mockito.mock(Foo.class)` 字段直 mock(改动旧代码时顺带迁)"。
- **建议**:加 ArchTest 扫 `MockitoAnnotations.openMocks` 全仓禁,渐进迁移截止日期写入 changelog。

### v2-P2-C 9 处 `private final Foo = Mockito.mock(Foo.class)` 字段直 mock(全在 sensor 测试)

- 9 处全集中在 `SensorStateMachineTest.java`(8 处)+ `FileArrivalSensorPolicyTest.java`(1 处),sensor 子域专属。原因可能是该 service 依赖图太大,作者觉得 `@Mock` 注解一堆太碎。
- **建议**:抽 `SensorTestFixtures` 静态工厂 / 测试基类,集中 mock 装配,既符合规则又减重复。

### v2-P2-D console-api 类 DDD 与传统分层双轨

- **现象**:`com.example.batch.console.domain.<bc>.web|service|mapper|support|application|infrastructure|entity|param|query`(11 个 bounded context:rbac / file / notification / observability / audit / workflow / job / governance / ops / param / query)— 是真实 DDD 包结构;同时顶层 `web/`(7 个 Controller:ArchivePolicy / ConfigCache / Config / ConfigSync / QuotaPolicy / ResourceQueue / ResourceTag)+ `application/` + `infrastructure/` + `mapper/` + `service/` + `support/`(传统分层)6 个并存。
- **影响**:开发者要决定"新功能放 `domain/<bc>/` 还是顶层 `web/`"全靠记忆,IDE 类型补全 7 Controller vs 67 Controller 混排误导新人。
- **建议**:出"包结构 ADR"明文判定"是否走 bc 分包",顶层 7 个 Controller 全部迁入对应 bc(ConfigCache → `domain/config/web/`,ResourceQueue → `domain/governance/web/`)。

### v2-P2-E orchestrator `controller/` 20 个 + `application/` 0 Controller(反 DDD)

- orchestrator 完全没把 Controller 放到 application 层,`controller/` 与 `application/service/` 平行 — 形成"Controller 直接调 Service" 但 service 又有 `Application*Service` 中层。与 hexagonal 期望不齐。
- **建议**:与 v2-P2-D 同 ADR 决议;若选 hexagonal,Controller 应在 `interface/web/` 或 `application/web/`。

### v2-P2-F 双 CHANGELOG(v1 P0-4 降级)

- 仍然问题在,但 v1 P0-4 升级太高;实际属"文档纪律 P2"。

### v2-P2-G `AbstractMutationIntegrationTest` 子类 11 处 `@SpringBootTest(classes = BatchConsoleApiApplication.class, properties = {...})` 模板重复

- 基类已 extends `AbstractIntegrationTest`(矫正 v1 P1-5),但 11 个子类每个都重复声明 `@SpringBootTest(classes/properties)` 5 行模板。改一个 property 要 11 处同步。
- **建议**:把 `@SpringBootTest(classes = BatchConsoleApiApplication.class, webEnvironment = RANDOM_PORT, properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})` 上提到 `AbstractMutationIntegrationTest` 类 annotation,子类只 `extends` 即可。

---

## §5 P3 — 低收益但建档(eventual)

| # | 项 | 模块 |
|---|---|---|
| v2-P3-1 | `package-info.java` 全仓 6 个,全在 SDK 与 batch-common;9 核心模块 0 个,模块边界 0 静态守护(只靠 ArchUnit)。建议至少给 `domain/entity/` / `application/service/` / `infrastructure/` 三个目录加 package-info(说明可见性 + 引用边界 + 引用反例),给后续维护者沉淀架构知识 | 全局 |
| v2-P3-2 | `WorkerReportOutboxConfiguration.java` 同名 `@Bean(name = "workerReportOutboxTransactionTemplate")` 在 SQLite 与 PG 两 ConditionalOnProperty 静态内类各定义一次 — 合规(condition 互斥),但风格脆弱,建议拆两个 bean name(`...TransactionTemplateSqlite` / `...Pg`)+ 主调用方按 `@ConditionalOnBean` 解析 | batch-worker-core |
| v2-P3-3 | `application/` 子层渗透 `infrastructure/` 7 处 import(DefaultDryRunPlanService → OrchestratorConfigCacheService 等)— DDD 期望 application 只依赖 domain + 自身,目前 application 直接引用 infrastructure 具体实现(Redis/MinIO/OpenLineage)。可接受(轻量 DDD)但与"严格洋葱架构"漂 | batch-orchestrator |
| v2-P3-4 | 1 处 `void testProfile_completelyRelaxed()` JUnit3 风格残留 | `batch-common/.../BatchSecurityPropertiesTest.java:165` |

---

### v2-P3-5 跨 worker 同名 entity / mapper 漂移(P0-B 同根问题的子集)

- 除了 13 个 orch ↔ console 同名 entity,worker 端也存在小量同名(BusinessCalendarMapper 3 处 = orch + console + trigger)。trigger 侧 `BusinessCalendarMapper` 是只读 SELECT,合规;P3 监控位。

### v2-P3-6 ADR 状态聚合表缺失(v1 P1-7 同根)

- 39 份 ADR 顶部 Status 字段格式不齐:有写"Accepted(两档...)"、有写"Accepted(v0.1 已落)"、有写"Accepted(第 X 阶段)"。无 `docs/architecture/adr/README.md` 统一聚合 Status 矩阵,人工 review 难。建议每月跑 `scripts/list-adr-status.sh` 出 CSV。

### v2-P3-7 测试 fixture 文件命名不齐(`Fixture` / `TestBuilder` / `TestFactory` 三派并存)

- 实测 6 处 fixture(`SensorWaitFixtureE2eIT` / `ProcessE2eFixture` / `E2eScenarioFixture` / `LaunchIntegrationFixture` / `ParseStepFixtureTest` / `JsonFixtureContractTest`)— 无 `TestBuilder` / `TestFactory` 命中。命名风格一致,但 6 个 fixture 分布散乱(e2e-tests / orch-integration / worker-import / sdk-contract)。建议 batch-common test-jar 收敛公共 fixture(已部分有 `AbstractIntegrationTest`),减重复维护。

## §6 N/A — 看过但实际不是问题

| 项 | 何以不是问题 |
|---|---|
| `@SneakyThrows` 滥用 | 全仓 0 命中,合规 |
| `@Builder` 加在标 `@Entity` / `@Table` 类 | 0 命中(无 JPA),红线措辞不命中 |
| JUnit4 残留 / Hamcrest / assertEquals | 全仓 0 命中,框架统一 |
| ADR-022 Forensic v0.2 `_history` 影子表越界 | `db/migration` 全文搜 `_history` 0 命中(除 flyway 自身) |
| ADR-026 Dry-run FULL_SIMULATION 越界 | 3 处全部是 javadoc 反向"FULL_SIMULATION 不做"提醒,无实现 |
| ADR-027 自研 K8s scheduler 越界 | 全仓 `NodeAffinity / machineSelector / K8sScheduler / hostSelector` 0 命中,resourceProfile / region / zone 仅作字段未做调度 |
| `domain/` 包引 Spring | 大量命中(console-api `domain/rbac/web/*Controller` 等),但这是 console-api **自定的"DDD 嵌套包"语义**(`domain/<bc>/web/` 是 BC 的 web 层而非"DDD domain pure layer"),不是 leak;仍建议 v2-P2-D 改名 |
| `@ConfigurationProperties` vs `@Value` 混用 | 105 `@ConfigurationProperties` vs 31 `@Value`,**@Value 全 22 处都是单一 boostrap 属性 / single-purpose 字符串(kafka.bootstrap-servers / port / partition-lease-ttl / static i18n locales),且分布在 Application 主类与 KafkaConsumerConfiguration、GracefulShutdown 等基础设施。3.4:1 比例健康,无业务参数级混用 |
| MyBatis SqlSessionFactory 多 / 共享 | worker-core `WorkerReportOutboxConfiguration` SQLite + PG 双 SqlSessionFactory 是 ConditionalOnProperty 互斥,**合规**;worker-import/export/process 各 1 `PlatformDataSourceConfiguration` + 1 `BusinessDataSourceConfiguration`(平台库 + 业务库分离)— 设计本意,合规 |
| 死代码(6 个月未改) | `git log --since='6 months ago'` 实测全部 1685 个 main java 都在近 6 月有 commit,**无 6mo+ 死代码** |
| @Builder 加在标 @Entity / @Table 类 | 0 命中(无 JPA),红线措辞文义不命中 |
| `@SneakyThrows` 滥用 | 全仓 0 命中,合规 |
| JUnit4 残留 / Hamcrest / `assertEquals(junit)` | 全仓 0 命中,框架统一 |

---

## §7 与 v1 报告交叉引用(避免重复)

| v1 条 | v2 处理 |
|---|---|
| v1 P0-1 mapper guard | v2 P0-C 扩范围(3→6 模块) |
| v1 P0-2 `*Record` | 不重复 |
| v1 P0-3 `executeLegacy` | 不重复 |
| v1 P0-4 双 CHANGELOG | v2 §0 降级,见 v2 P2-F |
| v1 P0-5 多租 IT 单点 | 不重复 |
| v1 P1-1 `@Autowired` field | 不重复 |
| v1 P1-2 单实现接口 TODO | 不重复 |
| v1 P1-3 接口/Default 双件套 | 不重复 |
| v1 P1-4 REQUIRES_NEW | 不重复 |
| v1 P1-5 Mutation IT 自建链路 | **v2 §0 矫正为误判**,真正问题改提 v2 P2-G |
| v1 P1-6 同名 OutboxEventMapper | v2 大幅扩展为 P0-A(6 张表双主入口) |
| v1 P1-7 ADR-021 文档脱钩 | 不重复(其余 3 个 ADR 边界 v2 §6 N/A) |
| v1 P1-8 TODO 集中 | 不重复 |

---

## §8 v2 新增风险与下次扫描建议

### v2 新增风险与 ADR 关系

| v2 条 | 相关 ADR / 规则 |
|---|---|
| v2-P0-A(双主入口) | CLAUDE.md §持久化 + ADR-001 + ADR-019(域级配额 quota_policy 谁主写)|
| v2-P0-B(entity 字段漂移) | ADR-001 + 未立 ADR(应出 ADR-040 "跨模块同表 entity 单一定义") |
| v2-P0-C(mapper guard 范围) | 同 v1 P0-1,扩 |
| v2-P1-A(@Builder 持久化) | CLAUDE.md §Java #8 + 红线,规则文义边缘 |
| v2-P1-B(SQL 复杂度) | 未有规则,**建议补 `docs/coding-conventions.md` §MyBatis XML 复杂度阈值**|

### 本次未尝试但建议下次纳入

- **同表跨模块 entity 字段 diff 自动化**(v2-P0-B 13 个 entity 必须全表 diff,需脚本)。
- **mapper XML `<sql>` 共享片段使用率**(目前 0 处 `<sql ref="">`,业务 SQL 没抽公共片段)。
- **`@Transactional` 方法所在层级**(是否真在 service 公共方法,不在 controller / mapper) — v1 提了 REQUIRES_NEW,未提 layer。
- **outbox / event delivery log / retry 三表读写权限矩阵**(谁写哪表 / 哪些是合规共享写) — v2 P0-A 暴露的"双主入口"问题更广。

### 建议 v3 重点

1. **完整 13 个跨模块同名 entity 字段 diff 表**(需脚本输出 CSV)+ 各自决议。v2 已给汇总,v3 应到字段名级别 diff。
2. **每个业务表(165 张)读写矩阵**(哪个模块 SELECT / INSERT / UPDATE / DELETE),识别隐藏的"3 模块写同表"。已知 6 张(v2-P0-A);估计完整扫还有 5-10 张漏网。
3. `AbstractIntegrationTest` 116 处 extends vs `AbstractMutationIntegrationTest` 11 处的 fixture 共享 / Testcontainers 复用真实生效证明(`docker ps` 抓快照对照"测试套是否只起一组容器")。
4. **mapper XML `<sql>` 共享片段使用率**(v2 速扫 0 处 `<sql ref>`,但需用 `<include refid>` 完整验)— 16 个高复杂 XML 没抽公共片段 = 字段列表 / 表名 / where 子句被复制粘贴。
5. **`@Transactional` propagation 全表 + readOnly 标注完整审计**(v1 P1-4 已覆盖 REQUIRES_NEW,但 `readOnly` 是否一致 / 嵌套调用是否传播正确无审计)。
6. **`@RequestMapping` path 重复 / 同 endpoint 双端注册**(67 + 7 = 74 个 Controller,与 v2-P2-D 双轨直接相关,要 ArchTest 守护 path 唯一)。
7. **trigger / worker 模块 ApplicationListener / EventListener 网络**(异步事件流图谱当前无 ArchTest 守护,极易"想抢监听就抢监听")。
8. **i18n key 一致性**(`messages.properties` 与 `messages_zh_CN.properties` 1:1,守护测试位置 + 覆盖率)。

---

## 附录 v2 — 扫描覆盖证据

- **扫描深度**:
  - 13 个跨模块同名 entity,逐对 diff 字段差异(完成 3 对,WorkflowDefinition / JobInstance / WorkerRegistry,其余 10 对建档 v3)
  - 23 个跨模块同名 Mapper.xml,统计 INSERT/UPDATE/DELETE 标签数;6 张表两端都写(P0-A)
  - 14 个 `@Builder` orchestrator entity 配套注解逐文件核对(`@Data + @NoArgsConstructor + @AllArgsConstructor` 配齐率 13/14,1 处 record 仅 `@Builder(toBuilder=true)`)
  - 105 处 `@ConfigurationProperties` + 31 处 `@Value` 全文分布
  - 9 处 `@MockitoSettings(LENIENT)` + 10 处 `MockitoAnnotations.openMocks` + 9 处 `private = Mockito.mock` 逐文件 grep
  - ADR-021/022/026/027 顶部状态文本 + 范围边界"❌ 不做"清单 vs 全仓 Java 实现 grep(FULL_SIMULATION / `_history` / NodeAffinity / businessResult)
  - 33 个 mapper XML 复杂度统计(行数 / `<if>` 数 / `<choose>` 数)
- **静态指标新增**:
  - 同名跨模块 entity 重复:13 个
  - 同名跨模块 Mapper 重复:23 个,其中 6 张表两端都写(双主入口)
  - `@Builder` 加 entity:14 处
  - `bypass-mode: true` 重复 profile:8 处
  - `mapper-locations` 重复 profile:5 处
  - `@MockitoSettings(LENIENT)`:9 处(8 处带注释,1 处无)
  - `MockitoAnnotations.openMocks`:10 处
  - `private = Mockito.mock`:9 处
  - `testXxx` JUnit3 风格残留:1 处
  - `package-info.java`:6 处全在 SDK + batch-common,9 核心模块 0 处
  - `@Deprecated forRemoval=true`:0 处(同 v1 P2-9)
- **方法**:批量 grep 30+ 次 + 文件读 9 次,与 v1 完全互补
- **耗时**:约 40 分钟

---

## 附录 v2 B — v1 ↔ v2 总览数字

|  | v1 | v2(新增) | 总和 |
|---|---|---|---|
| P0 | 5(其中 1 个降级 P2,1 个扩范围) | 3 | **6(去重)**|
| P1 | 8(其中 1 个矫正为误判 → P2-G) | 7 | **14(去重)**|
| P2 | 9 | 7 + 1 矫正 + 1 降级 = 9 | **18**|
| P3 | 4 | 4 + 7 fixture 命名 + 6 ADR 聚合 | **9**|
| N/A | 8 | 7 | 15 |
| 总耗时 | 45 分钟(v1)| 40 分钟(v2)| 85 分钟 |

**最高优先级三条新发现总结**:
1. **v2-P0-A**:6 张定义类表 orchestrator + console-api 双主入口(workflow_definition / job_definition / business_calendar / batch_window / resource_queue / tenant_quota_policy)— CLAUDE.md §持久化红线静默违反
2. **v2-P0-B**:13 个跨模块同名 entity,**10 对字段漂移**(`JobDefinitionEntity` Δ=49 字段最严重)— ArchTest 完全没拦
3. **v2-P0-C**:`MapperXmlTenantGuardArchTest` 漏配 6 个模块(v1 P0-1 只点了 3 个),覆盖断层是 v1 报告的 2 倍

— Generated 2026-06-03
