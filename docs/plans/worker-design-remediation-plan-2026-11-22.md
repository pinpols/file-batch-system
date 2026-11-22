# 五类 Worker 整改方案（2026-11-22）

状态：第 1、2 批及 PR4 补充项已在工作树实施并完成本地真实 PostgreSQL 验证；第 3 批已形成仓库默认场景盘点，目标环境与外部接收端证据待取得。基线为后端 `main` 的 `51c82f49b` 和[五类 Worker 设计复核](../audit/worker-design-review-2026-11-22.md)。生产发布、历史数据修补、协议升级和跨 Pod 续跑分别决策。

| 顺序 | 范围与责任 | 验收结果 | 状态 |
|---|---|---|---|
| 1 · P1 | Export Worker 修复两种内置插件的分页，Console 配置入口同步校验 | 合法模板逐条对账；有问题的配置明确失败并阻断文件发布 | 已实施并完成真库验证；Console 现将 `query_param_schema` 原样透传，无字段级分页校验，运行期显式失败即入口守卫 |
| 2 · P2 | Orchestrator 修复增量启动水位，Process Worker 验证业务发布和回报 | 空批、并发完成和归档之后延续有效水位；补跑与正式增量互不污染 | 已实施并完成 Mapper 级真库验证；Process 端到端用例待补 |
| 3 · 恢复专项 | Worker Core 和 Orchestrator 复用现有租约、回报与重放合同，各插件负责人提供业务证据 | 已确认的动作补交原结果，能安全重试的动作按幂等合同恢复，结果未知的动作有人工核对入口 | 仓库默认场景已盘点；目标环境开关与外部证据待补 |

## 1. Export 分页与文件完整性

当前 `jdbc_mapped_export` 和 SQL 模板插件按一个列排序，下一页用严格大于该列末值的条件。多分片当前统一走哈希，同值仍可能落在同一分片并跨页。方案沿用现有分片键、插件接口和文件格式，在两种内置插件的一致分页规则上修正。[现行分片口径](export-partition-keyset-range-2026-06-06.md) · [导出契约](../architecture/worker-plugins.md)

1. 先在两个插件补 PostgreSQL 失败用例。使用跨页同值、可空字段和展开重复行的查询，断言业务主键集合及每个主键出现次数。多分片同时核对分片无交集及并集完整。
2. 默认继续接受可证明唯一、非空且稳定的单列。JDBC 映射能从有效主键或唯一约束证明时复用约束，证明范围必须覆盖实际查询的数据。SQL 模板按最终结果集验证，源表有主键也可能被 JOIN 展开。对运行时才能判断的模板，检查对应查询范围内的空值，并用分页边界校验拦住同键跨页。空值检查须覆盖哈希分片谓词会过滤掉的行。比较采用 PostgreSQL 的类型和排序规则。无法确认安全时返回明确配置或生成错误，禁止 STORE、REGISTER 和触发下游派发。对无法用索引验证的大结果集测量开销，出现不可接受的扫描时要求配置可验证的稳定键。
3. 对业务确需用可重复字段排序的模板，增加可选的唯一排序后缀。分页和续跑都使用同一排序元组，例如 `(event_time, detail_id)`，并将两个值按原类型绑定。只允许非空且组合后具有唯一性的排序键。现有单列模板保留相同参数和稳定排序。复合配置由模板配置、导入和运行时统一校验。公共 `ExportDataPlugin.DetailPage` 已允许插件自定义游标类型，先把新游标限制在内置插件及其位点编码内。业务不需要重复排序字段时，可先用已验证的唯一单列收口。
4. 恢复位点与排序、分片和模板快照绑定。原单列位点仅在此次运行身份、排序语义和残文件均可证明一致时继续使用。切换为复合排序、位点来源无法证明或跨 Pod 丢失临时文件时，隔离原半成品，从该分片起点重建。上线前排空受影响的旧生成任务，回退前同样排空新游标任务并保留原文件与登记记录。已登记并分发的历史文件按租户、作业和版本只读对账，需要补发时另走审批。

验收用现有 `ExportKeysetRangeIntegrationTest` 和生成恢复测试夹具扩展，覆盖页大小 1、边界同值、NULL、JOIN 重复、旧配置、哈希多分片、写入临时文件后崩溃以及位点不兼容。用固定或有版本的输入比较源明细和成品业务键，要求差集为零且每键次数一致。文件哈希和非空只能证明传输或产物存在。跨页或跨分片的源数据若可变，需提供稳定输入版本，单靠 keyset 无法保证一致快照。

## 2. Process 增量水位

Orchestrator 在启动时读取成功实例的 OUT。空输入批次没有新 OUT，当前查询按完成时间只取一行，会让空批遮住上次有效值。运行实例已有 IN、OUT 和冻结执行策略，归档表也保存 IN/OUT。修复优先复用这些事实。[启动解析](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultLaunchService.java) · [实例查询](../../batch-orchestrator/src/main/resources/mapper/JobInstanceMapper.xml)

1. 在现有正式增量数据流内选择有效成功水位。以租户和作业定义隔离，核对冻结执行模式及水位字段，字段或源域切换需要明确当前数据流的起点。排除 dry-run、部分失败、历史重放及其它不具备推进资格的成功实例。历史快照字段缺失时按可证明的来源纳入，已运行数据流无法恢复可信水位时进入人工核对。首次运行仍按原先起点处理。
2. 空批保留 IN 代表的有效进度，成功实例仍记录当次实际处理结果。只在完整作业成功时确认候选 OUT；多个分区仍经现有回报聚合，迟到的旧 invocation 按现有围栏处理。同一可比较、单调数据流内，晚完成的较低 OUT 不能回退后续 IN。完整覆盖的前提由源查询契约证明，不能把任意并行窗口的最大值当已覆盖的连续边界。补跑按原运行的冻结范围或明确的业务区间执行，保留自己的结果与审计。
3. 热表查询必须保持水位跨归档可追溯。先核对归档保留的冻结策略和可用查询索引，覆盖热冷交接及已有空批历史；避免在 launch 热路径反复扫大历史。若归档或保留期无法保证事实连续，才评估由 Orchestrator 维护持久化增量水位，在完整成功的同一事务内推进。届时单独设计可证实的历史初始化、版本切换、双读比对和回退，不凭旧空值回填。

验收复用 `SqlTransformComputePluginIntegrationTest` 和 `ProcessPipelineE2eIT`，覆盖 `42 → 空批 → 新输入 43`，第三轮输入水位必须是 `42`，只处理新记录。再覆盖 DIRECT 与暂存模式、并发完成乱序、分区部分失败、归档、既有历史数据、历史重放和跨租隔离。以源事件、目标数据和 IN/OUT 独立对账。改动涉及查询时，附代表性 PostgreSQL 数据上的查询计划与耗时。

## 3. 五类 Worker 的结果确认与恢复

复用 CLAIM 的 invocation、执行前围栏、REPORT Outbox、ADR-049 审批和结果未知保护。Compose 和 Helm 的基础配置默认关闭 REPORT Outbox，先盘点目标环境实际开关、持久化后端与 DB 角色，核对多副本接管以及告警。回报重送和业务副作用重试分别验收。原 invocation 有可信结果时只补报。业务幂等成立时按原业务键重试。结果未知且无法对账时须人工确认，自动重跑保持阻断。[ADR-015](../architecture/adr/ADR-015-worker-side-outbox.md) · [ADR-049](../architecture/adr/ADR-049-replay-operation-scopes.md)

| 类型 | 承接现有机制的整改 | 验收证据 |
|---|---|---|
| Import | 核对平台位点、文件 SHA-256、目标唯一约束和插件实际写入语义。普通 UPSERT 路径复用现有 chunk 恢复。高成本分区续跑或 PGP 有明确业务需求后单独立项。 | 业务 chunk 提交后、位点写入前中断。恢复后源行无漏、目标无重复副作用。文件更换后校验旧位点失效。 |
| Export | 复用已登记的制品身份和摘要确认任务结果。重点验证上传完成、登记或回报中断的窗口。本地临时文件丢失时按固定输入重生。只有重生耗时超过恢复预算才评估跨 Pod 持久位点。 | 同一文件身份与 SHA-256 一致，无第二个逻辑文件、重复登记或重复派发。摘要冲突明确阻断。 |
| Process | 暂存按 generation 回收，核对目标提交证据和重投安全。任意自定义副作用沿用未知状态及审批。 | Worker 强杀后对账目标行、暂存代、已确认水位和最终任务状态，迟到尝试无法污染当前运行。 |
| Dispatch | HTTP 优先凭同一业务幂等键和对方状态接口确认。SFTP、NAS 等按通道合同核对远端文件或回执。SMTP 确认丢失且无查询协议时保留人工核对。 | 对方已接收但响应丢失时，使用原请求身份追踪，交付次数为一。无证据时保持结果未知。 |
| Atomic | 按实际启用执行器登记可查询的外部执行身份和结果证据来源。能查询原数据库提交或外部作业状态的场景优先查询。任意 Shell 或外部程序缺确认协议时保留人工处理。 | 围栏拒绝时无外部动作。开始后断联、旧 Worker 恢复及重复重派时，确认终态和实际副作用次数。 |

复用 `WorkerReportOutboxPgIntegrationTest`、`TaskBatchClaimReportIntegrationTest`、`WorkerProcessRestartRecoveryE2eIT` 及逐类夹具。覆盖执行前中断、业务成功后回报前中断、Outbox 已入队后 Worker 消失、回报已提交但响应丢失、旧 invocation 迟到。每种场景同时核对请求身份、任务终态、Kafka/Outbox 残留和业务副作用次数。真实接收端与部署切换在获准的 staging 上单独验收。

## 交付与关闭

- 第一批和第二批分别先复现、再修复、再跑相关模块与真实依赖测试。实现后同步模板约定、核心流程及受影响手册。确认正常触发、业务日期、快照、DAG、分片和租户隔离行为。
- 第三批先形成实际启用场景的证据矩阵。只有实际合同或故障证据出现缺口的路径进入后续修复。跨语言 SDK 合同和历史补发各走已有流程。
- 每批记录构建版本、命令、失败与恢复证据、性能约束和目标环境待验项。状态维护见 [TODO Master](todo-master.md)。

## 实施记录 · 2026-11-22 第 1 批

### Export 分页（P1）

实施内容：

- `GenerateCursorCodec` 新增复合游标编码 `K|<len>:<part>...`：长度前缀保证 String 值内的 `:`/`|` 可往返，保留单值类型标签和完成哨兵 `C` 的“不是合法游标”语义；任一元素不可编码时整体不可续跑（沿用既有降级）。
- `JdbcMappedExportSpec.detailTiebreakerColumn` / `SqlTemplateExportSpec.cursorTiebreakerColumn`（可选）。复合模式分页谓词为 `col > ? OR (col = ? AND tie > ?)`，排序键为 `(col, tie)`，续跑位点使用同一元组；两列都必须出现在结果集（SQL 模板）或由插件自动附加投影（JDBC 映射）。
- 单列模式每页多取一行：越界行与末行排序键相同即抛 `WorkerConfigException`（GenerateStep 映射为 `EXPORT_GENERATE_CONFIG_INVALID`），不再产出静默缺行的文件。
- 首屏检查排序键（含唯一后缀）的 NULL：命中即失败，检查不受哈希分片谓词限制。
- 位点形状与冻结配置不一致（旧单列位点遇到复合配置）时明确失败，要求另起运行；未做自动迁移。
- 契约文档同步：[worker-plugins.md](../architecture/worker-plugins.md) 导出 GENERATE 段。

验证证据（真实 PostgreSQL / Testcontainers，2026-11-22）：

- `./mvnw -o -pl batch-worker/export -Dtest=ExportKeysetRangeIT test` → 12/12 通过（107s）。新增 7 例：`10、10、20` 每页 1 条时无后缀明确失败；有后缀逐条导出且每键一次；NULL 排序值失败；复合排序 4 分片无重漏。
- `GenerateCursorCodecTest` 与既有 SQL 构造/分片单测共 6 个类通过。

Console 入口：配置包校验只检查 `jdbcMappedExport` 的 batchTable/detailTable 等浅层结构，模板保存路径原样透传 `query_param_schema`，因此新增的可选键无需 Console 改动；不安全配置由运行期明确失败兜底。

未覆盖：JOIN 展开重复行与页大小 1 以外的边界组合；崩溃续跑切换游标；滚动升级时旧配置的行为；开销实测——边界校验每页多取 1 行，首屏 NULL 预检对每个分片各执行一次 `IS NULL LIMIT 1` 探测，大结果集上尚未测量扫描成本。

### Process 增量水位（P2）

实施内容：

- `JobInstanceMapper.selectMaxEffectiveHighWaterMarkOut` 取代 `selectLastSuccessByJobDefinition`：热表与 `archive.job_instance_archive` 各取数值最大的有效成功 OUT，再合并取最大。排除历史重放（`replay_session_id` 非空）、补跑（`rerun_flag=true`）、部分失败、dry-run 以及 NULL/空串/非数值 OUT；较晚完成的较低 OUT 不再回退后续 IN。
- `DefaultLaunchService.resolveHighWaterMarkIn` 使用新查询，空串按无水位处理。
- V388 为热表与归档表新增同形状部分索引（租户 + 作业定义 + `finished_at DESC NULLS LAST` + id 倒序，仅覆盖有效成功实例）。索引排序方向与启动查询的 `ORDER BY finished_at DESC NULLS LAST, id DESC` 对齐，查询可直接取索引首行而不必排序。
- 文档同步：[system-flow-overview.md](../architecture/system-flow-overview.md) §7.8.3 不变量与 §7.9.6。

验证证据（真实 PostgreSQL / Testcontainers，2026-11-22）：

- `./mvnw -o -pl batch-orchestrator -Dtest=IncrementalWatermarkMapperIntegrationTest test` → 6/6 通过（39s）。覆盖首次无水位、`42 → 空批 → 43`（第三轮仍是 42）、空串、PARTIAL_FAILED/dry-run/重放/补跑不推进、归档后可追溯、热冷合并取最新、租户与作业定义隔离；V388 随 Flyway 全量迁移应用成功。
- 查询计划（合成对照，非生产数据）：在一次性 PostgreSQL 17 容器按 V388 索引定义建表，热表 15,000 行（每 3 行 1 行空 OUT）、归档 5,000 行，`ANALYZE` 后执行同一 SQL。计划为两支各自 `Index Scan using idx_*_effective_watermark` + `Limit 1`，总耗时 0.148 ms（Planning 0.392 ms）。对照：若索引写成 `finished_at DESC`（默认 NULLS FIRST），查询的 `NULLS LAST` 无法复用索引顺序，规划器退回 Seq Scan + Sort（15.7 ms）。分区父表沿用同一索引定义，分区裁剪后的单分区行为与此一致。

未覆盖：`ProcessPipelineE2eIT` 端到端（Process 插件发布与回报）、真实归档调度跨月后的连续运行、目标环境大表上的实测计划（最大 OUT 查询需要确认索引与扫描成本）。

### PR4 补充（2026-11-22）

- 水位乱序：新增 `lateLowerOut_doesNotRegressWatermark`（50 先完成、40 后完成仍取 50）与 `takesMaxAcrossHotAndArchive` 真库用例。
- 准入上限 1：`JobNodeDispatchIntegrationTest` 新增满载不领取、释放唯一槽后原请求恢复的用例。
- 派发 Outbox：`DefaultScheduleForwarderSetBasedTest` 新增 Kafka ACK 与 DB 回写交错用例，旧发布者领取 1 成功回写 0 行、新发布者领取 2 回写 1 行。
- 汇总 SQL：新增 `EndToEndSummarySqlIntegrationTest`，在真实 PostgreSQL 上执行 `p2-run-end-to-end-summary.sql`，覆盖根 Workflow/子 Job/直接 Job 分行计数与 `INVALID` 样本状态。
- 验证：`IncrementalWatermarkMapperIntegrationTest` 8/8、`EndToEndSummarySqlIntegrationTest` 2/2、准入上限 1 用例 1/1、`DefaultScheduleForwarderSetBasedTest` 7/7 通过。
- 仍未覆盖：Process 端到端、Export 崩溃续跑的文件对账扩展、12/6 同构建压测、JDBC 往返实测、双 Pod 取证与混版旧发布器退出顺序。

### 第 3 批（恢复合同）

未实施。Import 分块提交与位点推进窗口、Export 制品摘要确认、Process 暂存代回收、Dispatch 渠道确认、Atomic 外部执行身份，仍按第 3 批的证据矩阵逐项取证。

#### 当前启用场景盘点（仓库默认，2026-11-22）

| 范围 | 仓库默认 | 因此可本地验证 | 仍需目标环境/外部证据 |
|---|---|---|---|
| Report Outbox | Helm `orchestrator.workerReportOutbox.enabled` 默认 `false`，Compose `BATCH_WORKER_REPORT_OUTBOX_ENABLED` 默认 `false`；存储默认 `PLATFORM_PG` | 关闭态下回报走直连、失败重试边界；开启态的 Mapper/Outbox 单测与 PG 集成 | 目标环境实际开关、开启后的多副本接管、Kafka 残留与重送 |
| Export | 内置 `jdbc_mapped_export`、`sql_template_export`；GENERATE 空位点续跑默认开启（Excel 与多分区除外） | 已由第 1 批真库用例覆盖分页与恢复位点 | 登记回调 `onRegistered` 的业务副作用、上传完成到登记中断窗口 |
| Import | 内置 `jdbc_mapped` 加载；LOAD 先提交业务 chunk 再推进平台位点；多分区降级为分区内重跑 | 现有 `ADR-038` 夹具与 chunk 恢复用例 | 目标表幂等策略、插件自定义副作用、分区级续跑需求 |
| Process | 内置 SQL 转换；DIRECT 与暂存按 generation 隔离 | 已由第 2 批 Mapper 用例覆盖水位；暂存回收另有既有用例 | Process 端到端发布/回报与冻结范围补跑 |
| Dispatch | `API`/`API_PUSH` 仅在 `receiver_idempotency_supported=true` 时自动重试；其余按 `EXTERNAL_OUTCOME_UNKNOWN`；渠道含 LOCAL/NAS/OSS/SFTP/EMAIL | 渠道适配器单测与未知结果保护 | 接收端是否真正兑现幂等键、SMTP 投递查询证据、真实远端文件/回执 |
| Atomic | 执行器含 shell/sql/spark/storedproc/http；非幂等执行前已有持久化围栏 | 围栏拒绝回归用例 | 各外部系统可查询的执行身份（SQL 提交、Spark application、Shell 副作用）与人工核对入口 |

盘点只覆盖仓库默认与代码能力，不代表任何站点已启用这些组合；第 3 批的关闭仍需按上表第三列逐项取得目标环境或外部系统证据。
