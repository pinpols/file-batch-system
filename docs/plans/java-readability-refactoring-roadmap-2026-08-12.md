# Java 可读性与结构一致性治理路线图（2026-08-12）

> 目标：在不改变业务行为、事务语义和外部契约的前提下，持续提高 BFS Java 代码的可读性、结构一致性与可审查性。
>
> 结论：当前格式化、静态检查和 CI 门禁已达到成熟工程水平；后续重点不是追求“写得像 Spring Boot/Apache”，而是治理自注入、固定 Map 契约、复杂职责和例外漂移。完整治理建议按“基线 + 7 个实施阶段”推进，预计 4～6 周，不做一次性大重构。

## 实施状态（截至 2026-08-13）

本路线图按小批次合入；“已开始”不等于整个阶段已完成。以下记录是后续复扫和排期的唯一进度依据：

| 阶段 | 状态 | 已合入交付 | 仍需完成 |
|---|---|---|---|
| 0 基线与清单 | 已完成 | 分类总账、机器扫描快照与 CI 可读性检查 | 随后续阶段定期更新快照 |
| 1 表达一致性 | 已完成 | #921：安全的 Spring 配置轻量化；本批将文件组请求转换提取为命名边界方法，并补充中文业务原因说明 | 后续新增代码继续遵守同一规则，不再对全仓做机械格式改写 |
| 2 自注入迁移 | 已完成 | #918：低风险自注入；#922：Console 租户初始化事务协作者；#926～#932：Orchestrator 事务边界迁移；#935：retry/dead-letter 显式新事务 | 生产源码自注入复扫为 0；后续新增代理调用须直接使用窄事务协作者或 `TransactionTemplate` |
| 3 固定 Map 契约 | 已完成 | #924：Console/内部 API/Java SDK 首批固定响应 DTO；#933：文件维护投影；#936：batch-day replay 影响投影；本批将文件治理延迟指标固定为 record | 剩余机器候选均已登记为动态 JSON/metadata/插件载荷、通用工具或兼容转换边界；新增固定契约不得回退为 Map |
| 4 复杂类拆分 | 进行中 | #923：Task Outcome 状态策略提取；#940：`TaskOutcomeNodeRunRecorder`；#942：`TaskOutcomeTerminalFinalizer`；#941：`TaskOutcomeDagProgressor`、`TaskOutcomeParentTaskSignaler`；本批提取 `TaskOutcomeWorkflowFinalizer`，集中 workflow 状态 CAS 与 terminal outbox 收口；`AbstractTaskConsumer` 批次提取 routing、transient failure、批量解码、租户分组、结果归因和 backpressure 协作者；当前批次提取 `DefaultConsoleWorkflowDefinitionApplicationService` 的响应组装、版本/节点写入和 DAG 诊断协作者，以及 `DefaultTaskOutcomeService` 的分区/实例聚合推进协作者 | 继续复扫 `DefaultTaskOutcomeService`、`AbstractTaskConsumer` 及同阶段复杂类，确认只保留有业务边界的协调入口；不改变事务入口、Kafka offset、claim/report 顺序、缓存失效和 API wire 字段 |
| 5 测试风格 | 未开始 | — | 在相应生产类拆分后就近治理 fixture 与命名 |
| 6 例外治理 | 未开始 | — | 审计 suppression、白名单与长期理由 |
| 7 最终验收 | 未开始 | — | 前述阶段结束后跑 Full Gate、关键 sim 与容器启动验收 |

近期合入记录：#918 `529b9a319`、#921 `557897f4b`、#922 `c053c4544`、#923 `8e5e1342`、#924 `cfa33e871`、#933 `911fd15d8`、#935 `fb2c7ad59`、#936 `490d02c73`、#938 `a2b5ca682`。#935 后已用 `@Lazy/@Autowired`、`ObjectProvider` 及常见 self/proxy 标识复扫生产源码，结果为 0；#936 保持 batch-day replay 预览 JSON 字段不变，仅将两组固定 SQL 投影移至 record 边界。本批继续保持 wire 字段不变，将文件治理延迟指标的 Controller/调度读取边界改为固定 record，Redis 内部缓存协议保持兼容。

## 1. 当前基线

以下数据为 2026-08-12 对 `src/main/java` 的词法扫描快照，只用于确定治理范围，不作为质量 KPI：

| 项 | 当前值 | 判断 |
|---|---:|---|
| Spotless | 全模块通过 | 格式已统一，继续作为硬门禁 |
| PMD | 全模块 0 violation | 静态规则已稳定，不为追求规则数量盲目加严 |
| CGLIB 自注入 | 0 个生产类 | 阶段 2 已完成；新增代理事务必须使用显式窄协作者或 `TransactionTemplate`，不得恢复自注入 |
| `Map<String, Object>` | 2236 处、涉及 463 个生产源文件 | 大量属于 metadata、JSONB、插件参数等合理动态结构，不做全局消灭 |
| `@SuppressWarnings` | 215 处、涉及 162 个生产源文件 | 逐个核对范围与理由，不按数量机械删除 |
| Spring `@Configuration` | 47 个生产声明 | 逐类核对 bean 间调用后再决定是否设置 `proxyBeanMethods = false` |

当前重点大类快照：

| 类 | 行数 | 初步裁定 |
|---|---:|---|
| `ConfigPackageSheetSpecs` | 1074 | 声明式规格注册，行数高但职责单一；默认不拆 |
| `PreprocessStep` | 1042（已降至 629） | 已将对象归属校验、对象下载、stream/range spool 和分片边界责任提取为 `ImportPreprocessObjectSource` |
| `DefaultTaskOutcomeService` | 921 | 优先按状态推进、结果持久化和 DAG 后处理拆分 |
| Java SDK `TaskDispatcher` | 903（已降至 657） | 已将 CLAIM/REPORT 重试、4xx 计数和退避策略提取为 `TaskDispatcherRetryCoordinator` |
| `AbstractExportFormat` | 832 | 按格式无关流程与格式策略拆分 |
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

**实施总账**：[阶段 0 分类总账](../analysis/java-readability-phase-0-classification-2026-08-12.md)；机器快照见 [Java 可读性治理扫描快照](../analysis/java-readability-inventory-2026-08-12.md)。

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
- `AbstractTaskConsumer`：已完成批量消费协调职责拆分；保留单条/批量 listener、背压、RLS 清理和 offset 决策在主类。
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

### 阶段 6：例外与 suppression 治理（2～3 天）

**目的**：让每个静态检查例外可解释、范围最小、不会永久掩盖新问题。

**工作项**：

- 审计 216 处生产 `@SuppressWarnings`，优先移除已失效、范围过大和无说明的项。
- 合理例外缩到字段/方法/局部变量，并在不直观时写明框架或协议原因。
- 检查 PMD/Spotless/ArchTest 白名单，删除已不存在的类和永久失效的豁免。
- 对必须长期保留的框架例外建立集中索引，不复制散落注释。

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
