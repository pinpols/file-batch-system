# Java 可读性与结构一致性治理路线图（2026-08-12）

> 目标：在不改变业务行为、事务语义和外部契约的前提下，持续提高 BFS Java 代码的可读性、结构一致性与可审查性。
>
> 结论：当前格式化、静态检查和 CI 门禁已达到成熟工程水平；后续重点不是追求“写得像 Spring Boot/Apache”，而是治理自注入、固定 Map 契约、复杂职责和例外漂移。完整治理建议按“基线 + 7 个实施阶段”推进，预计 4～6 周，不做一次性大重构。

## 实施状态（截至 2026-08-20）

本路线图按小批次合入；“已开始”不等于整个阶段已完成。以下记录是后续复扫和排期的唯一进度依据：

| 阶段 | 状态 | 已合入交付 | 仍需完成 |
|---|---|---|---|
| 0 基线与清单 | 已完成 | 分类总账、机器扫描快照与 CI 可读性检查 | 随后续阶段定期更新快照 |
| 1 表达一致性 | 已完成 | #921：安全的 Spring 配置轻量化；本批将文件组请求转换提取为命名边界方法，并补充中文业务原因说明 | 后续新增代码继续遵守同一规则，不再对全仓做机械格式改写 |
| 2 自注入迁移 | 已完成 | #918：低风险自注入；#922：Console 租户初始化事务协作者；#926～#932：Orchestrator 事务边界迁移；#935：retry/dead-letter 显式新事务 | 生产源码自注入复扫为 0；后续新增代理调用须直接使用窄事务协作者或 `TransactionTemplate` |
| 3 固定 Map 契约 | 已完成 | #924：Console/内部 API/Java SDK 首批固定响应 DTO；#933：文件维护投影；#936：batch-day replay 影响投影；本批将文件治理延迟指标固定为 record | 剩余机器候选均已登记为动态 JSON/metadata/插件载荷、通用工具或兼容转换边界；新增固定契约不得回退为 Map |
| 4 复杂类拆分 | 已完成 | #923：Task Outcome 状态策略提取；#940：`TaskOutcomeNodeRunRecorder`；#942：`TaskOutcomeTerminalFinalizer`；#941：`TaskOutcomeDagProgressor`、`TaskOutcomeParentTaskSignaler`；本阶段完成 `TaskOutcomeWorkflowFinalizer`、`DefaultConsoleWorkflowDefinitionApplicationService`、`DefaultTaskOutcomeService`、`PreprocessStep`、Java SDK `TaskDispatcher`、`DefaultRetryGovernanceService`、`AbstractTaskConsumer` 和 `AbstractExportFormat` 的职责拆分 | 阶段验收完成；后续只在出现独立变化原因时继续局部重构，不以文件行数或分层完整度为目标；保持事务入口、Kafka offset、claim/report 顺序、缓存失效和 API wire 字段不变 |
| 5 测试风格 | 已完成 | 已按阶段 4 影响域统一 fixture、契约命名和协作者窄单测；移除 worker 消费测试对受保护业务入口的反射调用，保留服务级契约测试 | 后续新增测试沿用本阶段规则；不对全仓测试做机械重写，动态 Map 和 test-as-spec inline fixture 按既有边界保留 |
| 6 例外治理 | 已完成 | #954：suppression 登记表、精确规则守卫、失效编译守卫清理 | 后续新增例外必须登记并通过 CI |
| 7 最终验收 | 进行中 | 刷新可读性扫描快照；将快照一致性接入 PR/full gate | 完成 Full Gate、关键 sim 与容器启动验收 |
| 8 控制流语义密度 | 批次 0–4 已随 #1342 合入 `main` | 批次 0：文件到达探测与入站扫描周期。批次 1–4：14 处嵌套选择按原短路拆开。原有单测通过 | 嵌套 `if` 不按层数全量改写。未纳入核查清单的跨行选择保持原样 |

近期合入记录：#918 `529b9a319`、#921 `557897f4b`、#922 `c053c4544`、#923 `8e5e1342`、#924 `cfa33e871`、#933 `911fd15d8`、#935 `fb2c7ad59`、#936 `490d02c73`、#938 `a2b5ca682`。#935 后已用 `@Lazy/@Autowired`、`ObjectProvider` 及常见 self/proxy 标识复扫生产源码，结果为 0；#936 保持 batch-day replay 预览 JSON 字段不变，仅将两组固定 SQL 投影移至 record 边界。本批继续保持 wire 字段不变，将文件治理延迟指标的 Controller/调度读取边界改为固定 record，Redis 内部缓存协议保持兼容。

## 1. 当前基线

以下数据为 2026-08-20 对 `src/main/java` 的词法扫描快照，只用于确定治理范围，不作为质量 KPI；快照由 CI 检查与源码一致：

| 项 | 当前值 | 判断 |
|---|---:|---|
| Spotless | 全模块通过 | 格式已统一，继续作为硬门禁 |
| PMD | 全模块 0 violation | 静态规则已稳定，不为追求规则数量盲目加严 |
| CGLIB 自注入 | 0 个生产类 | 阶段 2 已完成；新增代理事务必须使用显式窄协作者或 `TransactionTemplate`，不得恢复自注入 |
| `Map<String, Object>` | 2037 处、涉及 441 个生产源文件 | 大量属于 metadata、JSONB、插件参数等合理动态结构，不做全局消灭 |
| `@SuppressWarnings` | 209 处、涉及 160 个生产源文件 | 已建立精确规则登记；不按数量机械删除 |
| Spring `@Configuration` | 47 个生产声明 | 逐类核对 bean 间调用后再决定是否设置 `proxyBeanMethods = false` |

当前重点大类快照：

| 类 | 行数 | 初步裁定 |
|---|---:|---|
| `ConfigPackageSheetSpecs` | 1074 | 声明式规格注册，行数高但职责单一；默认不拆 |
| `PreprocessStep` | 1042（本批主类降至 629） | 已将对象归属校验、对象下载、stream/range spool 和分片边界责任提取为 `ImportPreprocessObjectSource`；后续再评估字符集/变换协作是否有独立变化原因 |
| `DefaultTaskOutcomeService` | 921 | 优先按状态推进、结果持久化和 DAG 后处理拆分 |
| Java SDK `TaskDispatcher` | 903 | 已提取 CLAIM/REPORT 重试、4xx 计数和退避职责到 `TaskDispatcherRetryCoordinator`（PR #948） |
| `AbstractExportFormat` | 839（本批主类降至 710） | 已将 cursor 分页、页边界 fsync、checkpoint 推进和进度节流提取为 `ExportPageGenerationCoordinator`；格式列解析与编码策略暂不混入本批 |
| `DefaultRetryGovernanceService` | 811 | 已提取重试重排队与 dispatch outbox 协作到 `RetryRequeueCoordinator`（本批次） |
| `AbstractTaskConsumer` | 810 | 已提取批量解码、租户分组、执行结果归因和逐条 DLQ 协作到 `TaskConsumerBatchExecutionCoordinator`（本批次） |
| `DefaultConsoleWorkflowDefinitionApplicationService` | 755 | 按读取投影、写入编排和校验拆分 |

行数不是拆分类的充分条件。只有当一个类同时承担多个变化原因、事务边界难以辨认或测试必须构造大量无关依赖时，才进入拆分。

## 2. 不变红线

所有阶段都必须遵守以下约束：

1. 结构重构与行为变更分开 PR，不在“顺手重构”中改变状态机、错误码、重试次数或默认配置。
2. Console/内部 API 的路径、JSON 字段、状态码和鉴权语义不变；确需修改时按正式契约变更处理并同步 OpenAPI 与前端。
3. 不移动既有 `@Transactional`、传播级别、锁、CAS、幂等键和 outbox 写入顺序，除非该 PR 的唯一目的就是事务边界迁移且有专项测试。
4. 不把动态业务数据强行 DTO 化。插件参数、用户 metadata、JSONB、动态聚合键和外部扩展字段继续使用 Map。
5. 不因行数机械拆分类，不拆声明式 registry/spec，不引入只有一个实现且没有隔离价值的接口。
6. 不以“减少代码行”为验收目标；验收看职责、依赖、契约和故障语义是否更清晰。
7. 每个 PR 只处理一个模块或一个职责簇，必须可独立回退。

## 3. 实施阶段

### 阶段 0：冻结基线与治理清单（0.5～1 天）

**目的**：先建立可重复扫描的基线，防止治理过程变成主观改风格。

**工作项**：

- 固化自注入、固定 Map API、复杂类、长方法、宽构造器和 suppression 清单。
- 给每个候选标记 `必须改 / 经过即改 / 合理保留`，并记录保留理由。
- 为高风险类列出事务、锁、状态机、外部契约和已有测试所有权。
- 后续脚本只报告候选，不直接批量改写代码。

**验收**：同一 commit 上重复扫描结果稳定；候选均能映射到模块、责任人和验证命令。

**实施总账**：[阶段 0 分类总账](../audit/java-readability-phase-0-classification-2026-08-12.md)；机器快照见 [Java 可读性治理扫描快照](../stats/java-readability-inventory.md)。

### 阶段 1：低风险表达一致性（1～2 天）

**目的**：先收口不触碰业务语义的代码表达。

**工作项**：

- 继续执行命令/上下文对象先提取局部变量再传参的规则。
- 修正无语义变量名、过深括号嵌套、重复小型条件与无理由 magic value。
- 逐类审计 `@Configuration`；仅在不存在 bean 间方法调用依赖时设置 `proxyBeanMethods = false`。
- 补复杂 Service/Executor/Handler 的中文类级注释，说明业务角色、边界和“为什么”。
- 不修改测试 fixture 中有助于 test-as-spec 的 inline 构造。

**验收**：Spotless、PMD、编译和受影响模块单测通过；生产 wire、Bean 数量和条件装配结果不变。

### 阶段 2：消除 CGLIB 自注入（3～5 天）

**目的**：把隐藏在 `self.xxx()` 中的代理/事务调用改为显式依赖，降低维护者误改事务的风险。

当前涉及 Orchestrator 10 类、Console 2 类。按领域分批，不做全仓一次替换。

**推荐做法**：

- 将需要代理语义的方法提取到窄职责的 `XxxTransactionService` / `XxxTransactionalExecutor`。
- 原服务通过构造注入调用协作者，协作者持有明确的 `@Transactional` 和传播级别。
- 保留原调用顺序、异常传播、rollback 规则、锁和 after-commit 行为。
- 每批优先处理低扇出的 scheduler/验证器，再处理 task outcome、dispatch、compensation 等核心状态链路。

**建议顺序**：

1. `ApiKeyVerifier`、`QuotaRuntimeStateSnapshotScheduler`、`SensorPollScheduler`。
2. `BatchDayReplayDispatcher`、`DefaultWorkerRegistryService`。
3. Console 两个 tenant config 初始化类。
4. `TaskDispatchOutboxService`、`DefaultCompensationService`。
5. `TaskControllerApplicationService`、`DefaultWorkflowNodeDispatchService`、`DefaultTaskOutcomeService`。

**验收**：每类至少覆盖代理调用、提交/回滚、重复调用和异常路径；最终生产代码不再出现 `@Lazy @Autowired ... self`。核心状态链路还需通过对应 IT/sim。

### 阶段 3：固定 Map 契约类型化（5～8 天）

**目的**：减少 Console、核心服务和 MyBatis 投影中的字段拼写漂移，同时保留真正动态的数据边界。

**只处理**：

- Controller 固定请求/响应。
- Application Service 固定 command/result。
- Mapper 返回给核心服务的固定列集合。
- 多处通过字符串 key 读取、且 key 集合稳定的内部结构。

**明确保留**：

- 插件/worker 参数和用户自定义 metadata。
- JSONB 原始载荷、兼容旧数据的 `from(Map)` 入口。
- 状态名或租户自定义维度作为 key 的动态聚合。
- 外部系统不可预知扩展字段、测试 fixture。

**实施规则**：一组端点一个 PR；先定义 DTO/view，再改 mapper/service/controller，最后同步 OpenAPI 和 `../batch-console` 生成类型。兼容转换集中在边界层，不让旧 Map 继续向核心传播。

**验收**：JSON snapshot/OpenAPI breaking gate/前端 `gen:api:check` 通过；字段名、nullability、枚举值和错误语义不变。

### 阶段 4：复杂类按职责拆分（2～4 周）

**目的**：降低修改一个业务规则时需要理解的无关上下文，不追求统一文件大小。

**优先顺序**：

1. `DefaultTaskOutcomeService`（当前批次：分区/实例聚合推进）
2. `PreprocessStep`
3. Java SDK `TaskDispatcher`
4. `DefaultRetryGovernanceService`
5. `AbstractTaskConsumer`
6. `DefaultConsoleWorkflowDefinitionApplicationService`（已完成：响应组装、版本/节点写入、DAG 诊断）
7. `AbstractExportFormat`

**拆分方法**：

- 主类保留用例编排和关键顺序，纯判断下沉 Policy，载荷组装下沉 Builder/Assembler，外部副作用下沉窄协作者。
- 一个 PR 只提取一个职责，优先 package-private/final 协作者，不为了“分层完整”新增空接口。
- 先用 characterization test 锁定旧行为，再移动代码；不要同时改算法、SQL 或状态枚举。

**阶段 4 已完成批次记录**：

- `DefaultTaskOutcomeService`：已完成状态推进、结果持久化和 DAG 后处理拆分（PR #946）。
- `PreprocessStep`：已完成对象读取、大小校验、全量/范围分段职责拆分（PR #947）。
- Java SDK `TaskDispatcher`：已完成 CLAIM/REPORT 重试与退避职责拆分（PR #948）。
- `DefaultRetryGovernanceService`：已完成重排队协调提取，不改变重试策略、死信状态机或事务创建边界（PR #949）。
- `AbstractTaskConsumer`：已完成批量消费协调职责拆分；保留单条/批量 listener、背压、RLS 清理和 offset 决策在主类（PR #950）。
- `AbstractExportFormat`：已完成 cursor 分页、页边界 fsync、checkpoint 推进和进度节流职责拆分；格式列解析与编码策略保持原边界（PR #951）。
- `DefaultConsoleWorkflowDefinitionApplicationService`：已完成响应组装、版本/节点写入和 DAG 诊断协作者拆分（PR #945）。
- `ConfigPackageSheetSpecs` 等声明式规格文件除非出现独立变化原因，否则不进入本阶段。

**验收**：主流程可在一个屏幕内读出阶段顺序；生产协作者全部通过构造器注入，禁止在主服务中手工 `new` 业务协作者（仅历史纯单测可保留明确标注的兼容构造器）；复杂协作者必须有中文类级注释，说明业务原因、事务/锁/副作用边界，而不是只描述代码动作；原测试和新增 characterization test 全绿；状态机、事务和性能基线不退化。

### 阶段 5：测试代码风格统一（3～5 天）

**目的**：让测试表达业务契约，而不是为了构造对象制造噪音。

**工作项**：

- 同一领域统一 fixture builder/object mother，避免每个测试复制超长构造参数。
- happy path、边界、并发/CAS、异常/补偿测试按统一命名表达。
- fixture 允许 Map 和 inline builder；生产代码规则不得机械套到测试。
- 清理只验证实现细节的 reflection/行数/私有方法测试，改为可观察行为断言。
- 对拆分后的协作者补窄单测，同时保留原服务级契约测试。

**验收**：测试失败信息能直接指出业务契约；不得因抽 fixture 降低场景可见性或共享可变状态。

**阶段 5 已完成批次记录**：

- 重试治理域：新增 `RetryRequeueCoordinatorTest`，覆盖 claim 丢失、分区版本冲突、任务/分区重排队、缺失源分区和 retry outbox 写入；测试保留场景数据可见性，未引入共享可变 fixture。
- worker 消费域：新增 `TaskConsumerBatchExecutionCoordinatorTest`，覆盖租户分组、坏消息 DLQ、瞬时失败不提交 offset 和单条业务失败隔离；`AbstractTaskConsumerTest` 改用同包直接调用受保护业务入口，不再用反射调用方法名。
- import/export 域：新增对象来源边界和分页协调器测试，固定大文件直流/范围切片的可用性规则、分页行号单调性和预取页语义。
- Console/Java SDK 域：新增工作流响应组装和 dispatcher retry 协作者窄单测；原应用服务、Generate、Preprocess 和 dispatcher 服务级契约测试继续保留。
- 本阶段定向验证覆盖 `batch-orchestrator`、`batch-worker/core`、`batch-worker/import`、`batch-worker/export`、`batch-console-api` 和 `sdk/java/core`，未修改生产行为。

### 阶段 6：例外与 suppression 治理（2～3 天）

**状态：已完成首批治理（2026-08-20）**

**目的**：让每个静态检查例外可解释、范围最小、不会永久掩盖新问题。

**工作项**：

- 审计 216 处生产 `@SuppressWarnings`，优先移除已失效、范围过大和无说明的项。
- 合理例外缩到字段/方法/局部变量，并在不直观时写明框架或协议原因。
- 检查 PMD/Spotless/ArchTest 白名单，删除已不存在的类和永久失效的豁免。
- 对必须长期保留的框架例外建立集中索引，不复制散落注释。

**本批完成记录**：

- 复扫生产 Java `@SuppressWarnings`：209 个注解、212 个规则项、18 个精确规则，未发现 PMD/Spotless/ArchTest 中引用已删除类的白名单。
- 删除 `DistributedLockAspect` 中没有运行时用途的 `SimpleLock` 编译守卫及其 `unused` suppression；锁切面执行逻辑不变。
- 新增 [Java Suppression 登记表](../standards/java-suppression-registry.md) 和 `check-java-suppression-registry.py`，将新增 suppression 变成需要登记的 CI 守卫。
- 保留的例外按 JSON/Map、SPI 异常契约、动态 SQL 安全边界、Spring 配置误报和 PMD 结构例外分类；不以 suppression 数量归零为目标。

**验收**：不以 suppression 数量归零为目标；每个保留项都有明确所有权和原因，新增无理由例外由 CI/review 阻断。

### 阶段 7：全量验收与防漂移（2 天）

**目的**：证明治理没有改变系统行为，并把可自动判断的规则固化为门禁。

**验证分层**：

1. 每个 PR：编译、Spotless、PMD、受影响模块单测、现有静态门禁。
2. API/DTO 批次：OpenAPI breaking gate、前端类型生成与页面 smoke。
3. 事务/核心状态批次：真 PG/Kafka IT、对应 E2E shard、关键 sim。
4. 大类拆分阶段结束：Full Gate、完整 sim、容器启动/健康检查。
5. 最终复扫：自注入归零；固定 API Map 候选归零；合理动态 Map、声明式大类和 suppression 例外均有登记。

只有稳定、低误报且能自动判断的规则才进入 CI。主观可读性判断继续由 review checklist 承担，避免门禁驱动机械代码。

## 4. PR 与回退策略

| 变更类型 | 单 PR 上限 | 必须验证 | 回退条件 |
|---|---|---|---|
| 表达/注释/局部变量 | 一个模块或一个规则簇 | 编译、Spotless、PMD、模块单测 | Bean 装配、序列化或日志字段变化 |
| 自注入迁移 | 1～2 个低风险类；核心类一次 1 个 | 事务单测、IT、异常回滚 | propagation、锁、after-commit 任一不一致 |
| Map → DTO | 一组相关端点/一个投影簇 | OpenAPI、前端 codegen、JSON 契约 | wire/nullability/枚举发生非计划变化 |
| 复杂类拆分 | 一次提取一个职责 | characterization + 模块测试；核心链路加 IT/sim | 状态顺序、SQL 次数、并发或性能退化 |
| suppression 清理 | 一个规则或一个模块 | PMD/编译/对应测试 | 为通过规则引入复杂绕写 |

所有 PR 都应保持可独立回退，不把格式、架构拆分、功能修复和依赖升级混在同一提交。

## 5. 完成定义

本路线图完成不等于“所有类都很短”或“所有 Map 都消失”，而是满足：

- 生产代码无 CGLIB 自注入，事务协作者职责和传播语义可直接定位。
- Console 和核心服务的固定契约均为 typed DTO/view；动态数据边界有明确理由。
- 重点复杂类的主流程、策略判断和副作用职责可分开测试与审查。
- 配置类、注释、命令对象调用现场和测试 fixture 风格一致且不过度形式化。
- 所有 suppression、白名单和声明式大类均有可追溯例外。
- Full Gate、关键 E2E/sim 和容器启动证据无回归。

达到以上状态即可视为“符合成熟 Spring/Apache 项目的工程纪律”，无需复制其源码组织或为了风格一致牺牲 BFS 的批处理领域表达。

<a id="control-flow-density-2026-11-10"></a>

## 6. 2026-11-10 控制流语义密度

阶段 0–6 已把格式、自注入、固定契约、复杂类和 suppression 收口。2026-11-10 复核看到的阅读成本来自方法内部：校验、查询和状态推进写在一起，互斥阶段用嵌套分支或嵌套三元表达，同一失败翻译边界包住整段调用。`if` 个数、`catch` 个数和行数不作为清理指标。长期规则见 [`coding-conventions.md` §1.3–§1.7](../coding-conventions.md#17-控制流阶段与异常边界)；调度器三层 catch 继续按 [§11.4](../coding-conventions.md#114-分布式锁的两种用法) 保留。

本阶段按已核查的嵌套选择分批，不按文件数或 `if` 数量推进。批次 0–4 已随 #1342 合入 `main`。日期化结论见 [控制流可读性审查](../audit/control-flow-readability-2026-11-10.md)。

### 6.1 已完成批次（#1342 合入）

| 位置 | 拆分 | 保持不变 |
|---|---|---|
| `FileArrivalSensorPolicy.probe` | 按原顺序完成规格、日期、LIKE 转义和渠道校验；有效规格交给 `selectLatestArrival` 查询与命中 | 日期解析失败仍在查询之外返回。查询、存储身份和 legacy 警告仍在原 `catch (Exception)` 内，失败码仍是 `sensor_probe_failed` |
| `ImportIngressScanner` 分页 | `recordRevisitLane`、`recordForwardLane`、`nextPhaseAfterApply` | 回访预算翻倍与 `Long.MAX_VALUE` 饱和、列举结束后的游标清零、`DISCOVER` / `APPLY` / `CLEAN` 的选择条件与原表达式一致 |
| `ImportIngressScanner.scanCycle` | `CLEAN` 收成 `cleanPage` | 删除数量、周期加一和进度字段与原围栏内代码一致 |
| `ImportIngressScanner.indexPage` | 单对象写入收成 `persistIndexedObject` | 进度保存仍在围栏内、对象写入之后。清单等待原因和导出触发仍只在版本未变时执行 |
| `ImportIngressScanner.applyObject` | `prepareObject` 完成观察，`commitPreparedObject` 写入 | 文件存在性、清单读取和 sidecar 校验仍在原短路链内。登记条件是局部布尔，只在版本确认分支里决定是否登记 |
| `DataQualityCheckExecutor` | `gateStatus` | `BLOCKED` / `WARN` / `PASS` 的优先级不变。SHADOW 模式仍在这次选择之后把 `BLOCKED` 降为 `WARN` |
| `LineageEvidenceService` | `evidenceSource`、`dispatchEvidenceSource` | 热表、归档和合并来源的选择条件不变。查询仍在选择之前完成 |
| `WorkflowNodeTerminalFinalizer` | `terminalRunStatus` | 四种终态编码不变，仍在状态写库之前算完 |
| `DispatchChannelSnapshotService` | `selectedReplayParams` | 指定原配置时，空参数仍换成空 Map；其他策略仍用当前参数 |
| `DefaultTaskCreationService.resolveStepCode` | 序号缺省提前成一次纯空判断 | 有任务类型时仍用 `类型:序号`，否则仍用 `EXECUTION:序号` |
| `TriggerGracefulShutdown.status` | `schedulerStatus` | 仍按停机、待机、已启动、停止的顺序询问调度器，命中即返回 |
| `TaskDispatcher.diagnosticMessage` | 提前返回 | 空消息仍先变成空串。类名已在文案中则原样返回，空文案只返回类名，否则仍是 `文案 (类名)` |
| `DispatchDeliveryMetrics` | `deliveryResult` | 熔断拒绝仍优先于成功，得到 `circuit_open` / `success` / `failure` |
| `LocalOutboxDispatchSupport` | 出箱结果文案按是否传输桩分支 | 传输桩才读取明细并把 null 收成空串 |
| `ExportRegionResolver.firstNonBlank` | 提前返回 | 第一项有文本则不再看第二项 |
| `DatasetRuleEvaluator` | `expectedRowCount` | 规则上的精确行数优先；否则才在清单参与时用清单期望行数 |
| `ConsoleApiExceptionHandler.handleBizException` | `bizExceptionMessage` | 没有文案解析器时，空 message key 仍返回异常原文，否则返回错误码标签。有解析器时才调用 `resolve` |
| `TenantConfigPackageExcelApplyService` | `singleJobDefinitionId` | 有单个作业时只取它的标识；没有作业且已有定义时才读取定义上的标识 |

批次 2–4 验证：`TriggerGracefulShutdownTest` 7、`TaskDispatcherTest` 15、`DispatchDeliveryMetricsTest` 6、`LocalOutboxDispatchSupportTest` 4、`ExportRegionResolverTest` 8、`DatasetRuleEvaluatorManifestRowCountTest` 4、`DatasetRuleEvaluatorControlRecordTest` 4、`TenantConfigPackageExcelMonitoringPolicyApplyTest` 15、`ConsoleApiExceptionHandlerTest` 25，合计 88 项失败数为 0。`LocalOutboxDispatchSupport` Spotless apply 为 clean。这是原有契约测试，不代表全仓或真实存储验收。

批次 1 验证：`DataQualityCheckExecutorTest` 10、`LineageEvidenceServiceTest` 8、`WorkflowNodeTerminalFinalizerTest` 7、`DispatchChannelSnapshotServiceTest` 28、`DefaultTaskCreationServiceTest` 25，合计 78 项失败数为 0。对应 5 个生产文件 Spotless 为 clean。`DispatchChannelSnapshotIntegrationTest` 未跑。

验证命令与结果：

```bash
./mvnw -o -ntp -pl batch-orchestrator -am -Dtest=FileArrivalSensorPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -o -ntp -pl batch-worker/import -am -Dtest=ImportIngressScannerTest,ImportIngressScannerPaginationTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -o -ntp -pl batch-orchestrator,batch-worker/import -DspotlessFiles='.*(FileArrivalSensorPolicy|ImportIngressScanner)[.]java' spotless:check
```

`FileArrivalSensorPolicyTest` 13 项、扫描器两类共 16 项失败数为 0；两个文件 Spotless 为 clean。扫描器测试在本批次拆分后重跑，仍是 16 项失败数为 0。这是原有契约测试，不代表全仓或真实存储验收。集成测试未在本批次运行。

### 6.2 准入

进入本阶段需要同时满足三条：方法里能指出至少两个业务阶段，或用嵌套分支/嵌套三元选择下一阶段；提取后主方法能按阶段读完；该路径已有契约测试，或先补一条锁定现有分支的测试再移动代码。

随手改到该方法时，可以把重复的纯空判断收成一个局部变量，或把已有三项业务条件按 §1.5 命名。空值防御、两项简单条件、声明式注册、调度器三层 catch，以及已经把一组调用翻译成同一错误码的 `catch`，保持原样。

持久化、跨进程或写入租约的阶段字符串保持原字面量。可读性提交不把它们改成枚举，也不调整写入顺序。含外部调用或可变状态的条件保持短路顺序、调用次数和异常时机。

### 6.3 一个批次怎么做

1. 一个提交只处理一个方法簇，并写明等价点：阶段字符串、错误码、短路、日志字段和 SQL。
2. 行为缺陷另案修改。渠道匹配、状态机和错误码修正不进入可读性提交。
3. 主方法保留阶段顺序。守卫条件直接返回；同一阶段内的互斥状态再用分支表达。
4. 选择下一阶段的嵌套三元收成提前返回的私有方法。新代码的 `try` 包住会抛出的那次调用；既有失败翻译边界按 §1.7 保持。
5. 扫描脚本只列出候选。`check-java-readability.py` 继续只检查显式类型和配置代理，不增加 `if` / `catch` 计数门禁。
6. 验收使用该类型已有测试、Spotless，以及差异审查。缺少能观察分支结果的测试时，先补测试，确认它锁住当前行为，再提取。

### 6.4 完成定义

- 被纳入批次的主方法可以按阶段读完，失败路径和成功路径分开。
- 原契约测试通过，阶段字符串、错误码、异常类型和副作用次数与提取前一致。
- 未纳入批次的大类、调度器 catch 和动态 Map 保持原登记结论。
- 全仓文件数、`if` 数或 `catch` 数下降不构成完成。

### 6.5 2026-11-10 核查后的批次

> 追加复核：批次 2 的 `TriggerGracefulShutdown.status` 曾改变调度器状态与排水状态的读取顺序，现已恢复；重放提交的 8 参数方法已收敛为 6 参数。见[控制流可读性审查的追加复核](../audit/control-flow-readability-2026-11-10.md#追加复核与整改方案2026-11-10)。以下批次已随 #1342 合入 `main`，验证结果以追加记录和该 PR 的门禁为准。

生产 Java 3100 个。去掉注释、字符串和泛型 `?` 后，同一语句里出现两个三元运算符的有 16 处：3 处是并列缺省，保留；13 处是同一行里的嵌套选择。另有 1 处三元分跨两行，是 `ConsoleApiExceptionHandler.handleBizException`。嵌套选择合计 14 处，批次 1–4 已全部按原短路改完。调度器三层 catch、既有失败翻译边界、持久化阶段字符串和大类按行数拆分不进入这些批次。并列缺省保留在 `ConsoleTriggerStatsResponse`、`ConsoleWorkerLoadResponse` 和 `ConfigPackageExcelWorkbookWriter`。

| 批次 | 状态 | 范围 |
|---|---|---|
| 0 | 已合入（#1342） | `FileArrivalSensorPolicy` 与 `ImportIngressScanner` 的扫描阶段 |
| 1 | 已合入（#1342） | 编排器 6 处状态选择，见 §6.1 |
| 2 | 已合入（#1342） | `TriggerGracefulShutdown.status` 的调度器状态短路；`TaskDispatcher.diagnosticMessage` |
| 3 | 已合入（#1342） | `DispatchDeliveryMetrics`、`LocalOutboxDispatchSupport`、`ExportRegionResolver.firstNonBlank`、`DatasetRuleEvaluator` 的期望行数 |
| 4 | 已合入（#1342） | `ConsoleApiExceptionHandler.handleBizException` 的消息选择；`TenantConfigPackageExcelApplyService` 的单作业定义标识 |

### 6.6 因素裁定

业界做法是让读者看出业务决定，并用格式化工具管住排版。下面这些因素这次不再单独立项。

| 因素 | 裁定 |
|---|---|
| 格式、换行、import | Spotless / Palantir 已是门禁，手改不增加可读性 |
| 嵌套三元选择 | 闭合清单 14 处已全部落地。同一行并列缺省不改。`DefaultWorkflowRunLaunchService` 另有的三处跨行选择已收成 `preferLaunchSnapshot`，`buildExecutionSnapshot` 仍只在容量和重放都为空时调用 |
| 薄壳转发、一行封装和辅助类 | 阶段 8 里的空串拼接和只用一次的登记条件已收回调用点。全仓纯转调已收回调用点。命名谓词、声明式登记和 `writeHeaderBlock` 留下。`GenerateStep.execute`、重放提交和补偿提交已按原顺序拆阶段。私有方法个数不是拆类门槛。`ImportIngressScanner` 的登记簇仍留在扫描器里 |
| 嵌套 `if` | 空值防御、类型判断和同一阶段内的互斥分支保持原样。不按嵌套层数开清单 |
| 三项以上复合条件 | 2026-10-24 已从生产 387 处提取 251 处，其余按 §1.5 保留。不再按 `&&` / `||` 数量提取 |
| `try/catch` | 新代码只包住会抛出的调用。调度器三层 catch 和已经翻译成同一错误码的 `catch` 保持原边界 |
| 一个方法里的多个阶段 | 批次 0 已处理文件到达和扫描周期。35 个 700 行以上的类维持阶段 4 的裁定，行数不是拆分理由 |
| 长参数、嵌套调用、动态 Map、自注入 | 参数例外、增量调用门禁、固定契约和自注入归零都已有现成规则。历史候选不回扫清零 |
| 阶段字符串、注释和泛化命名 | 已持久化的阶段字符串保持字面量。命名和注释只在改到该行时调整 |

## 7. 2026-11-11 阶段 9：复评整改

阶段 8 已由 #1342 合入 `main`。2026-11-11 在 `29cede911` 基线上做了六轮复评，确认 R1–R27 共 27 项，结论见 [Java 可读性复评](../audit/java-readability-reassessment-2026-11-11.md)，逐处候选见[候选清单](../audit/java-readability-reassessment-2026-11-11-candidates.csv)。

| 范围 | 状态 |
|---|---|
| R1–R3、R5–R10、R12–R23、R25–R27 与次要候选（计划 Task 1–17、19–24） | 计划已定；Task 1–3（R5、R6、R10，PR-1）已在分支 `chore/java-readability-p9-batch1` 实施并评审，将作为草稿 PR 叠在文档 PR #1353 之上，其余未开工；执行方式为子代理驱动（每任务一名实现者加一名评审者），已授权提交、推送与开草稿 PR，合并仍需用户确认 |
| R4 空值判断写法 | 已裁定：对象判空用 `== null`，字符串/集合用 `EmptyChecks`；规则写入 [`coding-conventions.md`](../coding-conventions.md) §1.8 后按模块替换（计划 Task 18，最后执行） |
| R11 大类按领域拆分 | 暂缓，登记在 [todo-master](todo-master.md) |
| R24 用字符串表达有限域的公共参数 | 只登记，由[有限域与常量计划](typed-contract-enum-constant-governance-plan-2026-10-07.md)统一处理 |

实施步骤、行为保持约束和验收见 [阶段 9 实施计划](java-readability-phase-9-plan-2026-11-11.md)。本阶段仍遵守 §2 的不变红线和 §6 的控制流规则；行数、`if` 和 `catch` 数量不作为清理指标。
