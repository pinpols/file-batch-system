# 固定投影 Map 与长构造后续整改（2026-10-24）

## 范围与结论

本记录承接[模型与构造治理](entity-and-constructor-governance-2026-10-24.md)的 651 项定位快照。本批把两个 `ConsoleJobDefinitionResponse` 的 43 字段位置构造改为具名 Builder；将 `ConsoleFilePipelineResponse`、`ConsoleFilePipelineStepResponse` 和 `ConsoleFileDispatchRecordResponse` 的组装改为具名 Builder；把文件流水线、统计、分发记录、模板安全标记、渠道和模板配置、就绪检查及作业选项的固定列查询改为 record 视图和显式 MyBatis 构造映射。后续另完成集群诊断的6个固定列查询。未修改响应 record 的字段、顺序和 canonical constructor。

定位快照中 145 个长构造候选已处理 78 个调用点，剩余 67 个仍需逐项核对；506 个 Map 返回候选已处理 24 个固定投影，剩余 482 个仍待分类。这些是快照中的定位项，不代表缺陷数或当前全量重新扫描的结果。逐项状态与文件 SHA 见[候选清单](model-next-candidates-2026-10-24.csv)。

## 固定 SQL 投影

`FilePipelineMapper.selectByQuery` 的 SQL 选择列固定为 `id`、`tenant_id`、`job_code`、`pipeline_type`、`file_id`、`related_job_instance_id`、阶段/状态/trace 字段及四个时间字段。对应查询视图 `FilePipelineQueryView` 以 `Long`、`String`、`Instant` 明确表达字段；XML 使用显式 `<constructor>`、`<idArg>` 和有序 `<arg>`，列顺序与 record canonical constructor 对齐。

分页与详情两个调用点改用该视图。API 层 `ConsoleFilePipelineResponse` 的字段和顺序未变；字符串继续经过 `ConsoleQuerySupport.display` 脱敏显示，该方法与原 `stringValue` 共用 `ConsoleTextSanitizer.safeDisplay`。数据库 DDL 中 ID/关联 ID 为 `BIGSERIAL`/`BIGINT`、时间为 `TIMESTAMPTZ`；类型映射遵循仓库现有 MyBatis `Instant` resultMap 用法。SQL 查询体、筛选、排序和分页未改。

`FilePipelineStepRunMapper` 的步骤查询、进度查询和文件信息查询分别固定返回 16、8、2 列，现由 `FilePipelineStepRunQueryView`、`FilePipelineProgressStepView` 和 `FilePipelineInfoView` 承载，并在 XML 中按 SELECT 列顺序显式映射构造参数。`ConsoleFileQueryService` 的步骤查询与实时进度桥接使用这些视图；步骤摘要和错误参数仍按原流程转成字符串、遮罩敏感值，错误键与错误文本继续经显示净化。缺失文件名仍允许为 null。三条查询的 SQL XML 正文、条件、排序和联接均保持不变。

`FileRecordMapper.selectSummary` 的 4 个聚合列改由 `FileRecordSummaryView` 表达；服务继续把空统计值映射为 0。`FileDispatchRecordMapper.selectByQuery` 的 19 个固定列改由 `FileDispatchRecordQueryView` 表达，错误参数 JSON 仍按原字符串化、遮罩和本地化流程处理；17 字段 `ConsoleFileDispatchRecordResponse` 改用具名 Builder。两条查询 SQL 正文保持不变，数据库返回类型和 MyBatis 运行映射未执行验证。

`FileTemplateConfigMapper.selectSecurityFlagsByTemplateCode` 的 7 个安全字段改由 `FileTemplateSecurityFlagsView` 表达，并在 XML 中显式按列序构造。预览脱敏、下载审批与 Excel 配置导入存在性校验三个调用点及对应测试夹具同步改用类型化结果；租户、启用状态、软删除条件、版本排序和限制条数保持不变。查询 SQL 正文未改，MyBatis 运行映射未执行验证。

`FileChannelConfigMapper.selectByQuery` 的 13 列固定投影改由 `FileChannelConfigQueryView` 承载；`configJson` 保留为 `Object`，沿用 PostgreSQL JSONB 驱动对象的既有表示。Console 查询、租户配置复制及 Excel 导出均已切换至类型化结果，导出层仍按现有通用表格接口转换为 Map，并继续清空敏感目标地址和配置 JSON。SQL 选择列、过滤、软删除、排序和分页正文保持不变。

`FileTemplateConfigMapper.selectByQuery` 的 48 列固定投影改由 `FileTemplateConfigQueryView` 承载，JSONB 列同样保留为 `Object`。Console 查询、租户配置复制和 Excel 导出调用方改用类型化字段；通用表格输出仍在导出边界转换为 Map。SQL 正文与筛选、排序、分页保持不变。两条查询的 MyBatis 构造映射及 JSONB 实际驱动类型未运行验证。

租户就绪自检中的渠道和模板查询各有 6 个固定列，分别改由 `FileChannelConfigReadinessView` 与 `FileTemplateConfigReadinessView` 承载。作业就绪检查的 `(job_code, queue_code)` 及下拉数据源的 `(code, name)` 分别改由 `JobDefinitionQueueRefView` 与 `JobDefinitionCodeNameView` 承载。`ConsoleTenantReadinessService` 和作业编码选项映射按字段访问；启用标志仍按 `Boolean.TRUE` 判定，模板 `field_mappings` 仍按 JDBC 对象的 `String.valueOf` 结果执行空占位检查，渠道查询仍使用 SQL 中的 `config_json::text`。四条 SQL 正文、筛选及排序保持不变。

## 长构造

`ConsoleJobDefinitionResponse` 的 43 个 record 组件声明逐字保持。两个映射点均按组件声明顺序迁移为具名 Builder；逐项比较确认 43 个字段名和原参数表达式不变，因此字符串脱敏、空值兜底、监控策略计算及其执行顺序均保持。Builder 仅用于构造；现有 record canonical constructor 保留，未改变 JSON 字段或 Jackson 配置。

同一批将 14 字段 `ConsoleFilePipelineResponse` 的单个映射点改为具名 Builder。其字段、类型和顺序保持；测试中仍可使用原 canonical constructor。

本批再将 14 字段 `ConsoleFilePipelineStepResponse` 的查询映射改为具名 Builder；字段声明、顺序和空行处理保持。相关测试夹具从 Map 改用对应查询/进度视图。

`ConsoleFileChannelResponse` 与 `ConsoleFileTemplateResponse` 的长位置构造改为具名 Builder，保留响应字段和原映射表达式。

文件分发记录的 17 字段响应映射也改为具名 Builder，所有字段仍按原映射表达式对应；响应 record 的字段声明及 JSON 结构不变。

审批查询的 `ConsoleApprovalCommandResponse` 含19个字段，Service 映射与控制器测试夹具均改用具名 Builder；record 组件、canonical constructor 和 JSON 字段顺序未改。Service 仍在原位置执行 payload 脱敏，Builder 链只组装已计算的局部值。

同一 `ConsoleOpsQueryService` 中批次日、告警、死信、重试计划、待补跑和 Worker 注册的6个固定响应映射也改为具名 Builder；字段逐项对应。Worker 测试夹具显式设置空 `taskCapabilities`，保留原12参数兼容构造器默认空列表的序列化语义。

## Map 保留边界抽样

- `ConsoleGovernanceParameterPolicy.defaultValues` 与治理参数接口合并持久化的自定义 `governance.*` 键；Map 键并非固定 DTO 字段，保留。
- `ConsoleMetaQueryService.enums` 按已注册枚举类别返回分组词典；`pipelineStages` 按系统目录类型返回阶段集合。此类按类别索引的接口不因当前类别有限就机械改成位置对象。
- `BatchRuntimeStatusEndpoint.storageStatus` 的可选响应字段随实际后端及配置 Bean 是否存在而变化，当前 Map 表达条件字段集合；本批不更改运维响应形状。
- Mapper 查询投影若列集合固定且进入固定业务视图，应优先采用有序 resultMap 与具名 view；本轮新增渠道/模板就绪查询、作业编码与步骤注册表查询的类型化结果；其余候选继续按列集合、边界和消费契约分类。

### 集群诊断 Mapper 固定投影（2026-10-24 续）

`ConsoleClusterDiagnosticMapper` 的6项稳定投影（实例摘要11列、Worker组容量8列、分区/任务状态各2列、活跃任务Worker异常8列、Outbox状态4列）改用 `JobInstanceDiagnosticView`、`WorkerGroupCapacityView`、`StatusCountView`、`ActiveTaskWorkerIssueView` 和 `OutboxStatusCountView`。XML 为这些 record 增加显式构造映射，SQL正文、过滤、聚合、排序和返回行数限制保持不变。Service 通过具名访问器完成计数与响应组装；Worker异常的 `Finding.evidence` 仍使用 Map，因为该字段是对外定义的动态证据载荷。

对应服务与 Mapper 测试夹具已切换为具名投影。未编译或运行测试，也未执行 MyBatis/数据库映射；这些结果类型仍需后续运行验证。

### 批次日 Mapper 固定投影（2026-10-24 续）

`BatchDayMapper` 的批次日列表和窗口共用12列 `BatchDayView`；调度 occurrence 的9列由 `BatchDayScheduledOccurrenceView` 表达；作业汇总的6列由 `BatchDayJobSummaryView` 表达。Mapper XML 使用有序构造 resultMap，Console Ops 服务与对应测试改为具名字段访问。现有 count 到 Console 响应 `Integer` 的窄化仍按 `Number.intValue()` 处理；租户/日历/日期过滤、状态计数、排序和分页 SQL 正文保持不变。三个原有查询与 HEAD 的 XML SQL 正文比对一致；调度 occurrence 查询在当前工作树中早于本次变更存在，本次只替换结果映射声明，查询正文未编辑。Mapper XML 运行映射及数据库返回类型未执行验证。

## 静态核对与未执行项

- 四十五个长构造调用点均逐字段核对；`ConsoleJobDefinitionResponse` 两处 Builder 的 43 个实参表达式与原表达式按顺序逐项相同，五个文件响应、审批/审计/Outbox响应、六个 Ops 响应及作业实例/步骤实例响应的具名 Builder 保留字段映射关系。

`ConsoleJobInstanceResponse` 的27字段映射及三处测试夹具改用具名 Builder；`ConsoleJobStepInstanceResponse` 的16字段映射和测试夹具同样改为具名 Builder。Orchestrator 的 `EffectiveTaskConfig` 34字段映射及 `JobTaskResponse` 28字段 Entity 映射改为具名 Builder；其 record 规范构造器中的依赖快照归一与防御性复制仍会执行，`empty()` 的兼容占位工厂保持原样。组件声明、空值字段、脱敏后的快照值、错误消息处理顺序及字段值均保持原映射。测试 helper 对时间值仍逐次调用 `Instant.now()`，没有合并取样。
- 二十四个固定投影的 SELECT 列、resultMap 参数和 record 组件均按序对应；文件查询、配置复制、导出、脱敏、下载审批、配置校验、租户就绪自检、作业编码选项、集群诊断及批次日查询调用方使用类型化视图。原查询 SQL 正文与各自修改前快照一致。
- 此前 35 个 Java 文件的 JDK Parser-only AST 语法解析错误为 0；本次固定投影扩展涉及的 13 个 Java 文件 Parser-only 解析错误为 0。五条新增/整改查询的 SELECT 列、resultMap 参数和 record 组件按序对应；SQL 正文与 HEAD 一致。未做类型分析。
- 未编译、未运行测试、未运行 MyBatis 集成映射或数据库，也未改 Flyway/表结构。实际 MyBatis 构造映射与 PostgreSQL 类型处理仍待后续运行验证。

本轮累计关闭上述四十五个长构造调用点和二十四个固定 Mapper Map 项。其余长构造、Map 行映射、运行时配置导入、Controller/Service 职责、事务/锁/资源、Flyway 与全量文档影响分析继续保留在总台账。

本次续改后，固定 Mapper Map 项累计为24个；其余482个候选仍须按固定结构、动态键集合及调用边界逐项判断。长构造仍有100项未完成语义裁定，整体治理范围没有销项。


### 跨日历依赖响应与批次日实体复制收敛（2026-10-24 续）

`CalendarDependencyWaitResponse.from` 将 22 个位置参数映射改为具名 Builder；record 组件、顺序和每个实体访问器映射保持。`BatchDayInstanceEntity` 已有 `toBuilder()`，其 8 个复制方法改为只列出变化字段的 builder 更新，保留所有未变化字段、`CUTOFF` / `IN_FLIGHT` 状态值、`settledAt` 清空和 `frozen=false` 行为。未调整兼容构造器和 MyBatis 映射。只做源码语义核对，未编译、未运行测试。


本轮继续将 18 个固定响应映射改为具名 Builder，并补齐执行进度 Map 重载与 SLA 行的两处具名映射；对应候选状态及源码 SHA 已更新。


继续收敛 3 类嵌套响应映射：审批行、实例诊断摘要、租户初始化结果。审批/诊断 Map 的 null 处理注解不变；初始化失败结果仍写入原来的 8 个空统计对象，成功结果保持原值映射。执行器的成功构造同步改为具名 Builder。
