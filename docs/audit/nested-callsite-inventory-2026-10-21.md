# 嵌套调用可读性治理进度（2026-10-21）

## 当前范围与状态

本报告记录当前专用工作树中的可验证进度，不代表配对仓库全量治理完成。目标是识别生产代码及测试代码的方法/构造实参和返回表达式中遮蔽业务阶段的嵌套调用，按语义决定是否提取；短小直观映射和声明式配置保留 inline。所有提取保持调用顺序、次数、副作用、短路、异常时机及对外契约。

当前后端生产源码共 2,649 个 Java 文件。扫描器基于 JDK AST，统计方法调用/构造调用实参与 `return` 表达式中的嵌套调用；参数深度包含其所属的外层调用，因此 `a(x, b(y))` 会计为两层。一个表达式可能在多个实参/返回位置重复命中，扫描数是候选位置，不是缺陷数。

此前扫描器在方法/构造参数深度计算中漏计外层调用，旧的按模块数量及改造前后比较均不可用于完成度判断。修正口径的当前根目录 AST 扫描为 2,649 个生产 Java 文件、25,818 个深度至少为 2 的命中、2,571 个深层命中；此前的 25,820/2,572、25,822/2,573 是改动前快照，25,826/2,575、25,830/2,577、25,832/2,578 与 25,847/2,579 是更早快照，4,362/328、26,086/2,701 是旧快照或错误口径，均不代表当前值。以下模块计数从本次根目录扫描按源码路径分组，彼此相加等于根目录总数：

| 模块 | Java 文件 | 嵌套位置（深度≥2） | 深层位置（深度≥3） |
|---|---:|---:|---:|
| Worker Core | 119 | 1,042 | 59 |
| Worker Dispatch | 75 | 787 | 68 |
| Worker Process | 49 | 374 | 16 |
| Worker Import | 74 | 1,301 | 109 |
| Worker Export | 56 | 813 | 74 |
| Worker Atomic | 28 | 250 | 19 |
| Common | 344 | 772 | 52 |
| Trigger | 99 | 1,017 | 29 |
| Orchestrator | 640 | 7,637 | 475 |
| Console API | 1,031 | 11,076 | 1,601 |
| Test Support | 20 | 79 | 11 |
| Security Scan | 9 | 73 | 20 |
| Java SDK Core / Testkit / Spring | 94 | 517 | 30 |
| Java examples | 11 | 80 | 8 |

扫描范围不含 `logs/verification` 历史快照。旧版模块段落中的扫描前后数量均为错误口径；以本表为当前候选规模，模块的逐项分类仍未完成。不能把扫描位置总数视为待整改数量或完成证明。

## Worker Core 本轮改造

- `TaskDispatchExecutor.execute` 将已认领消息构造的 `PulledTask` 命名后交给执行包装器。
- `PlatformPipelineDefinitionRepository.loadPipelineSteps` 将 MyBatis 查询参数、返回行和领域定义映射分为明确阶段。
- `AbstractPipelineStepExecutionAdapter.populateCommonFields` 将 job code 的属性/fallback 读取与字符串化分开。
- `TaskConsumerRoutingPolicy.resolveNodeDirectSuffix` 将直接 topic 后缀提取与正则转义分开。
- `PlatformPipelineRunRepository.finishStepRun` 将输出摘要 JSON 与限长后的错误文本命名后放入 Mapper 参数。
- `DefaultTaskExecutionWrapper` 将响应摘要 Map 与 JSON 文本分阶段构造。
- `WorkerReportOutboxRepository.stats` 在 PostgreSQL 与 SQLite 后端中按原顺序逐项读取各状态计数和过期发布数，再构造统计对象。
- `WorkerTaskLeaseRenewer` 将字符串 task id 转为数值 ID 后交给批量/单项续租请求。
- `DefaultTaskExecutionWrapper.scheduleThreadLeakWatchdog` 依次命名 watchdog 回调、计划时刻、宽限时长和截止时刻，再提交调度器；保持先构造回调、再读取时钟并计算截止时间的顺序。
- `GracefulKafkaShutdown.awaitDrainAndRecord` 将关闭超时秒数钳制、Duration 构造和 lease drain 调用分开，并保留在原异常捕获范围内。
- `ActiveTaskLeaseRegistry.beginExecution` 将单条 CLAIM 首次登记时的单调时钟采样命名为 `executionStartedNanos`，再构造租约，保留原采样点、单次读取及锁内注册顺序。

上述拆分保留原参数求值次序和调用次数；topic 规则、Mapper 查询顺序与参数、错误摘要字段及续租语义不变。本轮将 Kafka listener concurrency、watchdog pool/shutdown 配置的读取与下限钳制分开；claim batch 的 subList 分片和 HTTP connect/read timeout 的 Duration 转换命名为独立阶段。

## 深层候选保留项

以下是旧扫描器识别并复核过的深层组合，只记录这些调用的保留理由；此前扫描记录的 70 个深层命中已过时；当前为 60 个深层命中。下表保留此前确认的声明式/直接映射类别，本轮进一步处理配置边界和批量分片输入；其余当前深层命中仍待逐项分类：

| 位置 | 保留理由 |
|---|---|
| `KafkaConsumerConfiguration.kafkaProducerFactory` 的 `DefaultKafkaProducerFactory` / producer config 组合 | Spring bean 声明式工厂配置；producer 设置由共享配置帮助器集中提供。 |
| `PlatformFileAuditRepository.appendAudit` 的 `params(...)` 参数组装 | Mapper 命名参数 Map 保持列名与 DTO 字段相邻；抽取所有字段会产生大量单用途变量并拆散 SQL 投影。 |
| `WorkerTaskLeaseRenewer` 的 `TaskLeaseRenewItem` 构造 | Worker 租约快照到请求 record 的直接字段映射，单个 task ID 类型适配不构成隐藏的业务决策。 |
| `HttpWorkerRegistryClient` 的 request factory / timeout builder | HTTP 客户端配置的声明式 builder 组合。 |

## Worker Dispatch 深层候选复核

Worker Dispatch 校正扫描覆盖 75 个生产 Java 文件，当前为 787 个嵌套位置、68 个深层位置；此前的 811/79、791/70、181/14 与 171/9 使用了漏计外层调用的旧扫描口径或改动前快照。Dispatch ID fallback/字符串化、digest bytes/hex 编码、SMTP 发件人与收件人清理/解析分阶段命名；SMTP header 清理、收件人分隔符归一化及 digest 计算次数与字段顺序保持不变。`FileDispatchRepository` 另将状态文本转换与多处 Mapper 参数 Map 提前命名，保留参数值顺序；`beginAttempt` 的短 Map 保留 inline。`DispatchTaskExecutor.capability` 现分别命名资源集合、timeout 秒数和 Duration，再构造 TaskCapability。

以下保留理由来自旧扫描器的候选复核，不表示修正扫描后的 79 个深层命中已全部分类：

| 位置 | 保留理由 |
|---|---|
| `CompensateDispatchStep` 的 nullable channel code 默认值 | 单字段空值回退后直接放入结果 Map，表达式短且字段语义明确。 |
| `DispatchChannelHealthRepository.toSnapshot` 与 `DispatchChannelHealthService.channelIdentity` | MyBatis/配置 Map 到 record 的字段投影；每个转换与目标字段相邻。 |
| `DispatchChannelSafetyProfile.toAuditMap` 的 stream/map/sorted/toList | 枚举名投影和排序是声明式集合转换，链路直观，保留 inline。 |
| `DispatchTaskExecutor` 的 `TaskCapability` 剩余构造 | 资源集合、能力标志和已命名的超时共同声明插件能力，属于配置对象构造；不再将超时转换嵌在构造参数中。 |
| `FileDispatchRepository` 中尚未抽取的 `beginAttempt` 参数组装 | 调用只传租户、文件、渠道及固定未知结果码；当前 Map 在 Mapper 调用附近，字段名和值一一相邻，继续拆为多个状态变量不会增加阶段信息。 |

## Worker Process 候选复核

`SqlTransformComputePlugin` 的查询超时配置现依次命名 security 配置读取、默认值选择、下限钳制，再设置 `JdbcTemplate`；原空配置默认 60 秒、最小 1 秒和配置 getter 调用次数不变。当前扫描更新为 374 个嵌套位置、16 个深层位置。

Worker Process 校正扫描覆盖 49 个生产 Java 文件，基线为 381 个嵌套位置、19 个深层位置；本轮调整后为 374/16。此前的 52/1 与 50/1 使用旧扫描口径。启动审计分别命名要展示的有效批次大小与实际清理批次大小，保持两次 properties getter 调用；发布数校验先命名去空白后的文本再解析 Long，转换次数和失败回退不变。

其余候选按用途分类保留：

- `ProcessStageResult.success(stage())` 与失败结果工厂是短结果包装；错误码、阶段、消息 key 和参数在工厂现场对应。
- `SqlTransformComputeSpec` 中的 `firstNonNull(root.get(...), root.get(alias))`、`firstNonNull(m.get(...), m.get(alias))` 及 `firstNonNull(values(...))` 是兼容配置键优先级读取；它们在当前扫描的深层命中均属于直接的主键/旧别名配对读取，保留这一显式优先级表达式。`ColumnMapping` 与 Optional 构造是直接映射。
- `SqlTransformComputeSqlBuilder` 的 Stream 链直接表达列投影、引用转义及 SQL 片段拼接；SQL 文本模板也保留为声明式结构。
- `ProcessTaskExecutor` 先命名 CPU/DB 资源集合、timeout 秒数和 `Duration`，再构造 `TaskCapability`；配置值与资源集合仍按原构造参数顺序计算。
- 当前修正口径的 16 个深层命中全部分类：两个 stage registry `putIfAbsent` 是索引写入；`List.of(new BeanTypeBinding...)` 是声明式注册；pipeline invocation 的 `context.getBatchKey()` 是单一上下文字段；其余为配置主键/兼容别名读取。租户 RLS transaction wrapper、AST row-count 解析及其它 pipeline invocation 参数均是短边界调用或直接字段适配；任务属性到 pipeline task ID 的读取与 Long 转换已分阶段命名。

旧扫描器报告的 50 个位置与 1 个深层候选均不作为当前覆盖证据。修正扫描后本模块有 374 个嵌套位置、16 个深层位置；以上分类只覆盖已检查的调用群组，其余候选仍待逐项复核。

## Worker Import 本轮改造与深层候选复核

Worker Import 当前修正扫描覆盖 74 个生产 Java 文件，1,302 个嵌套位置、109 个深层位置；此前的 269/23、238/8 与 1,323/117 使用旧扫描口径。局部变量提取覆盖 JDBC 行值到转换、导入上下文参数、列映射文本规范化、行号解析、Excel sheet 索引解析、流式校验失败结果、规则集优先键选择、控制总额字段别名解析及脱敏 Issue/Outcome 构造。改造保留默认值的求值时机、字段优先顺序、转换次数、校验结果和异常传播。

此前清单列出的 8 个深层候选仅是旧口径摘要，不能代表当前覆盖。当前复扫有 109 个深层位置，本轮抽查并处理下述有业务阶段收益的候选；摘要 Map/直接行值构造、配置键别名读取、声明式格式映射及转换链先按语义分类保留；当前其余深层位置仍待逐项复核。

| 位置 | 保留理由 |
|---|---|
| `GenericJdbcMappedImportLoadPlugin` 的 SQL 占位符 `.replace` 链 | 有序模板替换流水线；拆开会把连续替换散布到多组变量而没有增加业务判断信息。 |
| `JdbcMappedImportSpec` 的 `ColumnMapping` 构造 | 由单个字段映射定义直接构造目标记录，字段和转换保持相邻。 |
| `LoadStep` 两处、`ParseStep` 与 `ValidateStep` 的摘要 `Map.of` | 状态摘要的字段名与对应计数直接配对，是结果投影；拆开会制造临时变量并分离键值。 |
| `FixedWidthFormatParser` 的 `Optional.of(new FixedWidthField(...))` | 对已解析并校验的固定宽度字段作直接 Optional/record 包装。 |
| `ImportTaskExecutor` 的 `TaskCapability` | Worker 插件能力、资源和超时的声明式配置。 |

本轮新拆分算法选择、每列 CSV 值转换、文件记录存储路径回退与 pipeline instance id 转换；保持原字段顺序、方法调用次数和 getter 求值顺序。深度至少为 2 的其余候选尚未逐项复核，不能视为 Import 全量候选已结案。

## Worker Export 本轮改造与深层候选复核

Worker Export 当前修正扫描覆盖 56 个生产 Java 文件，813 个嵌套位置、74 个深层位置；此前的 838/82、837/82、817/76、185/17 与 159/4 属于旧扫描口径或改动前快照。改造为列解析阶段分别命名模板列、插件描述列和解析规格；固定宽度写行时将源值解析、格式化和追加分开；另将模板/插件开关来源、Excel 背景配置、控制总额、批次号默认值、编码选项和校验和类型的解析阶段显式命名。顺序与短路行为保持：模板列仍先于插件/首页推断，插件方法仍只调用一次，配置别名优先级不变，编码选项仍按字段原顺序求值。

本轮在 `SqlTemplateExportDataPlugin` 中将查询超时配置读取、默认值选择、下限钳制和 `JdbcTemplate` 设置分开；getter 调用次数、默认 30 秒和最小 1 秒语义不变。`ExportTaskExecutor.capability` 将资源集合、配置秒数、Duration 转换和能力对象分开；保持资源集合在前、配置读取和转换随后、Capability 最后构造的求值顺序。当前扫描为 813 个嵌套位置、74 个深层位置。

后续复核中将 `ColumnType`、`QuotePolicy`、`EscapePolicy` 的输入文本归一化与 enum 查找分开；模板整数配置分开展示文本存在判断、整数解析和最小值钳制；`ExcelSheetWriter` 将文本回退选择、公式转义和单元格写入分开，保留原异常捕获、默认值和调用顺序。

旧扫描器报告的 4 个深层候选只作为历史记录，不表示修正口径下已完成分类。当前修正扫描为 813 个嵌套位置、74 个深层位置；配置边界和模板/Excel 的声明式转换、直接字段映射按上下文保留；其余深层及普通两层候选仍需逐项复核。

## Trigger 本轮改造与深层候选复核

Trigger 当前修正扫描覆盖 99 个生产 Java 文件，1,017 个嵌套位置、29 个深层位置；此前的 1,029/38、1,020/32、1,016/30、121/8 与 115/5 属于不同历史扫描或改动前快照。状态 Controller 将查询、状态 DTO 和公共响应分开；Misfire 待办过期时间将当前时刻、保留天数、保留窗口和截止时间分开，按原顺序读取时钟与配置；Outbox DomainEvent 发布将各字段和 payload JSON 在 Mapper 调用前按原参数求值顺序命名；Quartz group 查询、lag sampling interval 与 Outbox poll interval 均已提取为命名阶段。`TriggerDescriptor.setDependsOnJobCodesCsv` 明确区分 null、空白和可解析 CSV，再命名单位分隔符；规划器缩小覆盖窗口时先计算半窗口毫秒，再应用至少 1ms 的下限。

修正扫描后的 29 个深层位置均已逐项复核：`TriggerSecurityConfiguration` 的角色 authority 列表、Outbox NEW/FAILED 状态筛选、LagSnapshot 与 batch param 构造、Mapper 的状态/租户/事件标识查询参数、日志脱敏字段、布尔状态比较、workflowVersion 解析、Quartz group 截取、TopicPartition/header 构造均是声明式权限/筛选配置或直接字段映射，保留 inline。除上述 CSV separator 与 half-window 下限两个阶段外，其余深层表达式未发现额外可命名的业务转换阶段。

## Worker Atomic 候选复核

Worker Atomic 当前校正扫描覆盖 28 个生产 Java 文件，250 个嵌套位置、19 个深层位置。本轮将三个运行状态 DTO 中的配置集合计数先命名（allowlist/schema count），再构造状态 DTO，使配置读取/计数与返回对象字段一一对应；`ShellTaskExecutor.startReaderThread` 将读取缓冲区转文本与构造/发布 `ReaderResult` 分步命名，使输出转换阶段和并发结果发布边界清晰，保持仍在原 reader 线程中执行、转换只调用一次且在结果发布前完成。其余候选集中在声明式配置、单字段输出 Map 包装、简单字符串适配、SQL/HTTP 类型转换、字符转义、空值回退及异常工厂。剩余深层位置经复核属于短转换、固定输出结构或直接映射，拆分不会揭示新的业务阶段，予以保留。计数不是待整改项数。

## 测试支撑代码候选复核

`batch-test-support` 校正扫描覆盖 20 个生产 Java 文件，当前为 79 个嵌套位置、11 个深层位置；此前的 13/2 使用旧扫描口径。当前深层候选为 AWS Basic Credentials 包装进 Static Credentials Provider，以及测试对象存储 endpoint record 从必填测试属性直接构造；两者是测试 fixture 的声明式依赖配置和字段投影。其余候选属于测试数据/错误结果包装、容器属性读取和直接类型适配，不含可单独命名的生产业务阶段。该模块无代码修改，候选按测试支撑用途分类保留。

## Orchestrator 本轮增量

Orchestrator 当前按根目录 AST 输出分组为 640 个生产 Java 文件、7,642 个嵌套位置、475 个深层位置；此前的 7,691/497、639/898/94 与 842/64 属于旧扫描口径。已在 HTTP 边界将 Task 请求规范化、Command 转换、应用服务执行和响应映射分开；File Governance 端点分离 command/service/response；Approval 端点分离审批记录与响应；Capacity Profile 分离 groupBy 解析和查询；异常映射按原调用次序命名响应 builder、消息及 failure body。另将 task 完成时的成功分区筛选、输出聚合、活跃 node 查询/解析分阶段命名；拆分 Workflow 跨日 payload 合并、Replay snapshot fingerprint 序列化/哈希、业务日类型读取，以及 rerun/config policy 的用户值与默认值选择。参数求值顺序、短路和调用次数保持。

本轮继续将文件治理审计命令中的操作者类型解析从审计 Actor 构造实参中提取为具名阶段，覆盖下载、上传、到达确认、重派及状态变更成功/失败分支；操作者判定仍使用同一纯函数，审计命令字段保持不变。`DefaultFileGovernanceServiceTest` 的历史测试结果保留作既有证据；本轮按 compile-only 约定未运行测试。

本轮又将日历定义查询的 tenant/calendar code 输入、Quartz previous/next fire time 转换分阶段命名；Kafka/DB sensor 异常映射先构造具名标准错误结果，日志时机与异常消息回退顺序不变。当前 Orchestrator 仍有大量嵌套候选未逐项复核；本轮所做仅是增量分类，不能视为全量结案。

## Common 本轮改造与深层候选复核

Common 当前修正扫描覆盖 344 个生产 Java 文件，778 个嵌套位置、53 个深层位置；此前的 799/67、790/59、193/20 与 170/7 属于旧扫描口径。公共异常映射现在显式呈现 HTTP 状态 builder、业务错误码/消息、failure body 的构造顺序；JSON 凭据掩码流程将解析、脱敏/过滤和序列化分开；Secret Payload 保护流程将 UTF-8 bytes、加密结果、Base64 envelope 和 JSON 分阶段命名；HMAC 流程将 key 规范化、UTF-8 key bytes、SecretKeySpec、签名输入、摘要和 hex 编码拆开，保留初始化和签名顺序。AES-GCM 加解密分别将密钥 bytes、密钥对象、GCM 参数和 Cipher 初始化分阶段命名，保持加密/解密模式、keyRef、IV 和初始化顺序。Hikari 的 work_mem 配置将 Duration 转 bytes、PostgreSQL 内存文本格式化和 SQL 追加拆开，同时保留条件判断和 getter 调用次数。HTTP 请求体 Reader 现命名 byte stream 和 input stream reader，再构造 buffered reader，保持编码和包装顺序。异常响应 builder 仍先于消息/错误体求值，其它数据转换保持原先次序。

以下是此前已复核过的保留类别，不代表当前候选已全部分类：S3 凭据 Provider 的声明式构造、ObjectStore backend 的配置组合、StatefulBackendGuard 的验证后值对象构造、FileTime 到 Instant 的直接摘要字段映射，以及 LocalizedError 的简单截断字段包装。文件存储候选位于已有工作树改动文件，本轮没有对该项做格式或代码调整。本轮将 DownstreamFallback 的 CircuitBreaker 各配置值依次命名后按原 setter 顺序配置，并将共享 scheduler 的 pool size 与 shutdown wait 值的读取、下限钳制分阶段处理；原配置读取顺序与 clamp 值不变。修正扫描当前为 778 个嵌套位置、53 个深层位置；其余仍需逐项分类。

## Console API 与配对前端本轮增量

Console API 校正扫描覆盖 1,031 个生产 Java 文件；配置审计整理后为 11,097 个嵌套位置、1,613 个深层位置；本轮 AI 文档/响应组装整理后为 11,089/1,606。此前的 2,319/209 与 2,370/235 使用旧扫描口径。这些数量包含同一表达式在参数与返回位置的重复命中，不代表深层位置都是缺陷。Web/support 层此前已完成的 Controller、Redis 参数、错误响应、HTTP 请求体和 Excel 指引提取仍保留在同一专用工作树。

本轮将配置创建和密钥轮换的审计 context、target、change details 与 command 逐步构造；审计写入则拆分原因脱敏、详情 Map、JSON 摘要和 Mapper 写入。操作者/TraceId读取、脱敏调用、字段顺序及审计内容保持原先求值顺序。

本轮另将 AI 知识库文档的 metadata、Document、待拆分列表和 splitter 输出分开，并将流式回答文本、AssistantMessage、Generation 和 ChatResponse 划分为具名构造阶段；输入顺序及最终响应/分块数据保持不变。

本轮继续整理 application/query 链路：租户读取拆成 Mapper 查询、存在性校验和响应映射；Trace Snapshot 的 11 个分域分页按原先求值顺序逐项查询，再构建快照；审计时间范围拆开优先级文本、时区和时间解析；批次窗口拆成 calendar code、Mapper 查询和响应映射；游标分页命名末行与末行 ID；运维摘要依原 Mapper 调用顺序分别读取审批、告警、作业、Worker、Outbox 指标；集群诊断将状态集合、Mapper 计数、空值归零和告警原因码分开；OIDC Redis 读取将 key 生成、Redis 读取和 JSON 解码分开。保留时间 fallback 次序、所有 Mapper 调用顺序、OFFLINE 与 DECOMMISSIONED 的相加口径和原异常传播。

配对前端此前的 AST 扫描覆盖 `src` 中 503 个生产 `.ts`/`.vue` 文件，排除测试及生成 API 类型，只统计连续至少 3 层调用，未覆盖普通 2 层候选；修改前 29、之后 24 是该旧扫描口径的历史结果，不能与下方扫描器直接比较。Axios 拦截器的维护模式、限流和业务错误由“建错误对象—附上下文—reject”改为具名错误阶段，三类拒绝都保留原消息求值、警告调用和元数据字段。旧扫描中已归类为保留的表达式包括 Element Plus 批量通知的 Vue `h()` 树、Job/日历/窗口/租户表单的 `callback(new Error(t(...)))` 校验包装，以及 Ops Diagnostic 表格的声明式 `h()` 节点树。这些调用保持消息与错误回调相邻，或直接表达渲染节点结构，逐层提取会增加临时变量而不揭示新业务阶段。

本轮继续拆分前端生产代码中的主题输入读取/解析/应用、Job Code 的过滤与去重、批量 worker 数量与 worker 列表、回放版本 ID 的解析过滤与去重、时区列表的合并去重排序、Scheduler Snapshot 负载值归一化，以及登录加密中明文 JSON 序列化与 UTF-8 编码。 `src/api/job.ts` 的手动触发参数将业务日期计算和 payload JSON 序列化分步命名，保留日期先于序列化、二者都先于 trigger API 调用的顺序；`src/utils/structuredSummary.ts` 将拼接摘要与最终长度裁剪分步命名；`src/utils/loginCrypto.ts` 将缓存时间戳读取与缓存 JSON 序列化分步命名，均保持原调用次数和顺序。无副作用的数组顺序、Set 首次出现顺序、错误回调与调度并发数保持不变。覆盖 `src` 下 504 个生产 `.ts`/`.tsx`/`.vue` 文件（排除测试和生成 API 类型）的 AST 扫描记录 226 个嵌套表达式命中，其中 19 个达到 3 层；外层参数/返回表达式可能重复覆盖内层调用，不能视为唯一调用点或缺陷数。此计数与前段旧扫描口径不可直接比较，前端候选仍需逐项分类。
前端生产代码的历史深层扫描曾记录 22 个连续至少 3 层的表达式命中（外层表达式会重复覆盖内层，非唯一调用点数）。当前基于 TypeScript AST 的全量实参/return 位置扫描覆盖 500 个生产文件，记录 158 个深度至少 2 的候选位置、17 个深度至少 3 的候选位置；此前的 161/17、164/17、168/17、176/22 嵌套/深层数为前序快照，不能与当前结果混用。逐个查看命中上下文后，Job/日历/窗口/租户表单验证中的 `callback(new Error(t(...)))` 是紧邻的校验回调和错误消息；GeneralApprovalsTab 与 OpsDiagnostic 的命中均位于 Vue `h()` 虚拟节点树，直接对应渲染结构。它们没有可命名的新业务阶段，本轮分类保留，未改代码。终态通知表格的状态键解析和翻译回退则存在重复转换，既有改造已改为具名展示函数。该深层扫描不覆盖 2 层候选，前端全量逐项分类仍未完成。

`OpsDiagnostic.runAll` 与 mounted 预热中的 `Promise.all(cards.map(card.run().catch(...)))` 保留为声明式并发扇出：map 表示逐卡启动，catch 表示单卡失败隔离，Promise.all 表示等待全部卡片；拆成局部 Promise 列表不会揭示新的业务阶段。

`ReportExportHub.loadTrend` 中两个独立统计请求的 `Promise.all` 保留为并行取数后按位置解构；趋势行到 Map 的单字段投影，以及 `ElMessage.success(t(messageKey))` 的本地化 UI 消息构造也保留 inline，表达式直接对应声明式映射或“翻译后展示”动作。

同批复核的 `CapacityProfile.load`（主报告 Promise.all + 桶查询 Promise.allSettled）、`RunsOverview.loadStatCounts`（并发查询并逐项容错）、`BatchDayReplay`（按 job 并发读取版本并保序合并）均保留为声明式并发；映射/聚合直接表达请求扇出和结果对应关系。`TenantPackageImportWizard.issueTableMaxHeight` 则把估算高度、最低值钳制和最高值钳制命名为独立阶段。

随后在 `JobMonitoringPolicies.vue` 将保存失败的 fallback 翻译、错误消息归一化和通知展示拆开，并将监控分类映射到 message key 显式命名；调用时机及错误回退值不变。前端类型检查再次通过。最新生产源扫描为 500 个文件、163 个恰为两层的实参/返回表达式位置；3 层以上仍以仓库候选扫描器 22 个位置为准。

本轮在 `TenantPackageImportWizard.saveRow` 将 upload token、patch request 构造和 API 调用依序命名，显式呈现 `draftFor(row)` 延迟创建可编辑草稿的阶段；保存失败则分开翻译 fallback、归一化错误并展示。字段顺序、draft 初始化时机和错误回退不变。当前恰为两层的候选扫描为 162 个表达式位置，三层以上仍为 22 个。

`JobDefinitionWizard.submit` 现在先读取 tenant ID，再调用 `buildBundle()` 完成 JSON payload 构造，最后调用 API；保留 tenant ID 与 bundle 构造的先后顺序，并保留在原 try/catch 内解析 JSON 的异常路径。类型检查通过；前端两层扫描总数维持 162，未因该一层实参提取发生计数变化。

本轮前端两层候选复核覆盖 `loginCrypto.pemToArrayBuffer` 的 PEM 包装剥离与空白清理、`datetime` 的时区选择/日期字段提取/格式化，以及 `structuredSummary` 的原始文本转换与 HTML entity 解码；步骤分别命名，调用次数和输入顺序不变。按相同 AST 嵌套实参/返回表达式口径复扫 500 个生产文件，恰为两层的候选从 168 降至 162；该计数包含外层表达式重复命中，不代表唯一调用点或剩余缺陷数。全部 2 层候选仍需继续逐项分类。

本轮新增配置审批、租户矩阵和告警路由拆分后，`DefaultConsoleConfigApprovalApplicationServiceTest,TenantConfigMatrixBuilderTest,DefaultConsoleAlertRoutingApplicationServiceTest`：8 项通过。另将租户初始化配置查询中的编码归一化拆为具名阶段，保持 Mapper 查询和编码归一化语义。测试结果为 25 项通过、1 项失败：失败测试断言节假日行采用 `holiday_date/calendar_id/created_by`，而工作树当前实现写入 `bizDate/calendarId` 等字段。该实现差异已存在于开始本轮前的工作树改动中，本轮未修改。另将批量审批失败结果的异常消息获取、安全展示和响应构造拆为顺序明确的阶段，原审批调用、异常记录及响应字段不变；`DefaultConsoleApprovalApplicationServiceTest` 1 项通过。

本轮 Console API 定向测试 `ConsoleTenantApplicationServiceTest,DefaultConsoleQueryApplicationServiceTest`：16 项通过；`ConsoleOpsQueryServiceTest,DefaultConsoleOpsApplicationServiceTest,ConsoleQuerySupportTest,ConsoleTenantApplicationServiceTest`：20 项通过；`ConsoleClusterDiagnosticServiceTest` 与 `RedisOidcAuthorizationRequestRepositoryTest`：13 项通过。前端 `interceptors.maintenance.test.ts` 与 `interceptors.integration.test.ts`：19 项通过；针对 `interceptors.ts` 的 Prettier、ESLint 与全项目 `npm run typecheck` 通过。Console API 本轮 7 个改动 Java 文件的定向 Spotless、`git diff --check` 通过。

## Java SDK 与示例插件本轮增量

Java SDK 校正扫描覆盖 Core、Testkit、Spring 共 94 个生产 Java 文件，当前为 520/30（嵌套/深层位置）；此前的 529/34、96/11、87/6、562/51 与 535/37 使用旧扫描口径。既有改造显式呈现 Atomic handler 执行、JDBC checkpoint 状态组装、HMAC 签名和 TaskDispatcher 失败诊断阶段。本轮把环境字符串 trim/parse/Duration 构造分开，StoredProc 输出参数依次呈现索引计算、JDBC 读取、值截断和 Map 写入；HeartbeatDirective 先投影原始字段再构造 record；TaskDispatcher 先解包 claim payload 并命名 context 字段，再构造 SdkTaskContext；Kafka commit 显式计算 next offset 后构造 metadata 和 Map；幂等占位符先字符串化再做 regex replacement 转义；DelimitedCodec 先构造 quote/doubled-quote 文本再替换。原 Map 读取次数、字段次序、JDBC 输出索引、offset 公式、异常发生范围和签名/report 字段保持不变。余下深层候选主要是单一配置值解析、直接 Getter 包装、测试 fixture 字段映射及声明式数据结构；逐项按直观投影/单阶段转换/测试数据/描述式结构复核后保留。本轮将 report failure 的诊断文案与 JSON summary 分阶段命名、JDBC 输出参数类型转换拆为索引/源类型/SQL 类型步骤、HMAC key 字符串/UTF-8 bytes/SecretKeySpec 依次命名，并将请求 timeout 上限选择分步计算。原参数顺序、调用次数和异常边界不变。

Java examples 校正扫描覆盖 11 个生产文件，当前为 80/8（嵌套/深层位置）；此前的 21/6 与 18/4 使用旧扫描口径。文件校验和示例将 digest、hex 文本与输出 Map 分步命名，并保持摘要只计算一次；其余深层候选是 `Map.copyOf` 后包装到 SDK 结果的 echo 示例，以及 Import SPI JSON schema 的声明式 Map/List 定义，按简单包装/描述式结构保留。

## 验证

| 范围 | 命令 | 结果 |
|---|---|---|
| 后端生产 Java 全量 AST 复扫 | `java /tmp/NestedCallsCorrected.java .` | 2,649 个文件，26,086 个深度至少为 2 的命中、2,701 个深层命中；命中计数有表达式位置重叠，不等同唯一调用点或待整改数。 |
| Worker Core | `./mvnw -pl batch-worker/core -am -Dtest=TaskDispatchExecutorTest,PlatformRepositoryTest,AbstractPipelineStepExecutionAdapterDefinitionTest,DefaultTaskExecutionWrapperTest -Dsurefire.failIfNoSpecifiedTests=false test` | 39 tests passed；Core 重编译 119 个生产源码、41 个测试源码通过。 |
| Worker Core 格式 | `./mvnw -pl batch-worker/core spotless:apply`、`./mvnw -pl batch-worker/core spotless:check`、`git diff --check` | 均通过。 |
| Worker Core 本轮可读性改造编译 | `./mvnw -pl batch-worker/core -am -DskipTests test-compile` | BUILD SUCCESS；Core 生产源码和 41 个测试源码编译通过，未运行测试。 |
| Worker Core 时间配置可读性改造编译 | `./mvnw -pl batch-worker/core -am -DskipTests test-compile` | BUILD SUCCESS；119 个生产源码和 41 个测试源码编译通过，未运行测试。 |
| Worker Core 格式 | 定向 Spotless 检查 `DefaultTaskExecutionWrapper.java`、`GracefulKafkaShutdown.java` 与文件级 `git diff --check` | 均通过。 |
| Worker Core 本轮格式 | 定向 Spotless 检查 `WorkerReportOutboxRepository.java`、`WorkerTaskLeaseRenewer.java` 和 `git diff --check` | 均通过。 |
| Worker Dispatch 本轮编译 | `./mvnw -pl batch-worker/dispatch -am -DskipTests test-compile` | BUILD SUCCESS；Dispatch 生产源码和 39 个测试源码编译通过，未运行测试。 |
| Worker Dispatch 本轮格式 | 定向 Spotless 检查 `FileDispatchRepository.java` 和 `git diff --check` | 均通过。 |
| Worker Process 查询超时可读性改造 | `./mvnw -pl batch-worker/process -am -DskipTests test-compile` | BUILD SUCCESS；Process 49 个生产源码、30 个测试源码编译通过，未运行测试。 |
| Worker Process 查询超时格式 | 定向 Spotless 检查 `SqlTransformComputePlugin.java` 和文件级 `git diff --check` | 均通过。 |
| Worker Export 查询超时可读性改造 | `./mvnw -pl batch-worker/export -am -DskipTests test-compile` | BUILD SUCCESS；Export 56 个生产源码、34 个测试源码编译通过，未运行测试。 |
| Worker Export 查询超时格式 | 定向 Spotless 检查 `SqlTemplateExportDataPlugin.java` 和文件级 `git diff --check` | 均通过。 |
| Worker Dispatch | `./mvnw -pl batch-worker/dispatch -am -Dtest=DispatchStepExecutionAdapterTest,DispatchManifestSupportTest,SmtpEmailDispatchChannelAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test` | 12 tests passed；Reactor 编译 75 个 Dispatch 生产源码、39 个测试源码通过。 |
| Worker Dispatch 格式 | `./mvnw -pl batch-worker/dispatch spotless:apply`、`./mvnw -pl batch-worker/dispatch spotless:check`、`git diff --check` | 均通过。 |
| Worker Process | `./mvnw -pl batch-worker/process -am -Dtest=ProcessStagingStartupAuditContributorTest,ProcessPublishedCountVerifierTest -Dsurefire.failIfNoSpecifiedTests=false test` | 7 tests passed；Reactor 编译 49 个 Process 生产源码、30 个测试源码通过。 |
| Worker Process 格式 | `./mvnw -pl batch-worker/process spotless:check`、`git diff --check` | 均通过；Spotless 首次检查发现同模块既有测试文件 2 处格式差异，按输出修正后复查通过。 |
| Worker Import | `./mvnw -pl batch-worker/import -am -Dtest=GenericJdbcMappedImportLoadPluginTest,LoadStepTest,JdbcMappedImportSpecTest,ValidateStepTest,ExcelFormatParserTest -Dsurefire.failIfNoSpecifiedTests=false test` | 67 tests passed；Reactor 编译 74 个 Import 生产源码、56 个测试源码通过。 |
| Worker Import 补充用例 | `./mvnw -pl batch-worker/import -am -Dtest=ValidationRuleSetMergerTest -Dsurefire.failIfNoSpecifiedTests=false test`；`./mvnw -pl batch-worker/import -am -Dtest=ControlTotalEvaluatorTest,ValidationIssueMaskerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 分别 1、10 tests passed。 |
| Worker Import 格式 | `./mvnw -pl batch-worker/import spotless:check`、`git diff --check` | 均通过；模块格式检查通过。 |
| Worker Export | `./mvnw -pl batch-worker/export -am -Dtest=GenerateStepTest,GenerateStepCheckpointTest,RegisterStepTest,ExportKeysetRangePlannerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 42 tests passed；Reactor 编译 56 个 Export 生产源码、34 个测试源码通过。 |
| Worker Export 格式 | `./mvnw -pl batch-worker/export spotless:check`、`git diff --check` | 均通过；Spotless 仅对本轮 4 个文件定向 apply 后复查通过。 |
| Trigger 定向测试 | `./mvnw -pl batch-trigger -am '-Dtest=TriggerStatusControllerTest,TriggerOutboxDomainEventPublisherTest,DefaultTriggerServiceTest#createPendingCatchUp_linksMisfirePendingToCatchUpRequest' -Dsurefire.failIfNoSpecifiedTests=false test` | 4 tests passed；Reactor 编译 99 个 Trigger 生产源码、49 个测试源码通过。 |
| Trigger 候选测试复核 | `./mvnw -pl batch-trigger -am -Dtest=TriggerStatusControllerTest,TriggerOutboxDomainEventPublisherTest,DefaultTriggerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | Controller 2 项和 Outbox 1 项通过；`DefaultTriggerServiceTest` 中 32 项有 2 项失败，失败断言期望英文 `suspended`，实际返回当前错误参数 `[t1]`；与本轮提取位置无关，保留失败证据。 |
| Trigger 格式 | 对 `TriggerStatusController`、`TriggerOutboxDomainEventPublisher`、`TriggerStatusControllerTest` 运行定向 `spotless:check -DspotlessFiles=...`，及 `git diff --check` | 定向格式和 diff 检查通过。模块全量 Spotless 因 `DefaultTriggerService.java` 中既有业务改动的两处格式差异失败；未格式化该文件的其它业务 diff。 |
| Orchestrator HTTP 边界 | `./mvnw -pl batch-orchestrator -am -Dtest=TaskControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 16 tests passed；Reactor 编译 639 个生产源码、343 个测试源码通过。 |
| Orchestrator Controller/异常映射 | `./mvnw -pl batch-orchestrator -am -Dtest=TaskControllerTest,ApprovalControllerTest,OrchestratorApiExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 25 tests passed。 |
| Orchestrator workflow/replay/launch | `./mvnw -pl batch-orchestrator -am -Dtest=TaskControllerApplicationServiceTest,DefaultWorkflowNodeDispatchServiceTest,BatchDayReplayServiceTest,ApprovalControllerTest,OrchestratorApiExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test`；`./mvnw -pl batch-orchestrator -am -Dtest=DefaultLaunchServiceTest,TaskOutcomeSummaryBuilderTest,BatchDayReplayServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 分别 78、46 tests passed。 |
| Orchestrator 格式 | 对本轮 10 个 Orchestrator Java 文件运行定向 Spotless 检查并执行 `git diff --check` | 均通过；只对 Spotless 报出的本轮文件定向格式化。 |
| Worker Atomic 扫描 | `java /tmp/NestedCallsAllLarge.java batch-worker/atomic` | 28 个生产文件、50 个嵌套位置、5 个深层位置；全候选已按用途分类，无明确收益的拆分。 |
| 测试支撑扫描 | `java /tmp/NestedCallsAllLarge.java batch-test-support` | 20 个生产文件、13 个嵌套位置、2 个深层位置；按测试 fixture/直接映射分类保留。 |
| Common | `./mvnw -pl batch-orchestrator -am -Dtest=RequestSignaturesTest,SecretMaskingTest,BatchObjectCryptoServiceTest,OrchestratorApiExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 36 tests passed；Reactor 编译 344 个 Common 生产源码、116 个测试源码通过。 |
| Common 格式 | 对 `AbstractApiExceptionHandler`、`SecretMasking`、`SecretPayloadProtector`、`RequestSignatures` 运行定向 Spotless 检查，及 `git diff --check` | 均通过。 |
| Common HMAC 可读性改造编译 | `./mvnw -pl batch-common -am -DskipTests test-compile` | BUILD SUCCESS；344 个生产源码、116 个测试源码编译通过，未运行测试。 |
| Common filesystem presign HMAC 与 Base64URL 阶段可读性改造编译 | `./mvnw -pl batch-common -DskipTests test-compile` | BUILD SUCCESS；Common 344 个生产源码、116 个测试源码编译通过，未运行测试。密钥文本先编码 UTF-8 再构造 SecretKeySpec；payload 再编码 UTF-8 后执行 HMAC；摘要最后交给无 padding 的 Base64URL 编码器。算法、字节、调用顺序和原异常边界不变。 |
| Common SHA-256 输入编码与 Cursor JSON/Base64URL 边界可读性改造编译 | `./mvnw -pl batch-common -DskipTests test-compile` | BUILD SUCCESS；Common 344 个生产源码、116 个测试源码编译通过，未运行测试。Hashes 先获取算法实例、再编码 UTF-8 输入、最后摘要；CursorCodec 先获取无 padding URL encoder、再把 JSON 编码 UTF-8、最后编码 token。返回格式及原调用顺序不变。 |
| Common HMAC 格式 | 定向 Spotless 检查 `RequestSignatures.java` 与文件级 `git diff --check` | 均通过。 |
| Common AES-GCM 可读性改造编译 | `./mvnw -pl batch-common -am -DskipTests test-compile` | BUILD SUCCESS；344 个生产源码、116 个测试源码编译通过，未运行测试。 |
| Common AES-GCM 格式 | 定向 Spotless 检查 `BatchObjectCryptoService.java` 与文件级 `git diff --check` | 均通过。 |
| Common Hikari 内存配置可读性改造编译 | `./mvnw -pl batch-common -am -DskipTests test-compile` | BUILD SUCCESS；344 个生产源码、116 个测试源码编译通过，未运行测试。 |
| Common Hikari 内存配置格式 | 定向 Spotless 检查 `HikariPgSessionSupport.java` 与文件级 `git diff --check` | 均通过。 |
| Common HTTP Reader 可读性改造编译 | `./mvnw -pl batch-common -am -DskipTests test-compile` | BUILD SUCCESS；344 个生产源码、116 个测试源码编译通过，未运行测试。 |
| Common HTTP Reader 格式 | 定向 Spotless 检查 `CachedBodyHttpServletRequest.java` 与文件级 `git diff --check` | 均通过。 |
| Console API application | `./mvnw -pl batch-console-api -am -Dtest=ConsoleTenantApplicationServiceTest,DefaultConsoleQueryApplicationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 16 tests passed；Console API 1,031 个生产源码与 394 个测试源码编译通过。 |
| Console API 配置审计可读性改造编译 | `./mvnw -pl batch-console-api -am -DskipTests test-compile` | BUILD SUCCESS；Console API 1,031 个生产源码、394 个测试源码及所需 reactor 编译通过，未运行测试。 |
| Console API 配置审计格式 | 定向 Spotless 检查 `DefaultConsoleConfigApplicationService.java` 与文件级 `git diff --check` | 均通过。 |
| Console API AI 组装可读性改造编译 | `./mvnw -pl batch-console-api -am -DskipTests test-compile` | BUILD SUCCESS；Console API 1,031 个生产源码、394 个测试源码及所需 reactor 编译通过，未运行测试。 |
| Console API AI 组装格式 | 定向 Spotless 检查 `ConsoleAiKnowledgeBase.java`、`DefaultConsoleAiApplicationService.java` 与文件级 `git diff --check` | 均通过。 |
| Console API query/ops/support | `./mvnw -pl batch-console-api -am -Dtest=ConsoleOpsQueryServiceTest,DefaultConsoleOpsApplicationServiceTest,ConsoleQuerySupportTest,ConsoleTenantApplicationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 20 tests passed。 |
| Console API 集群诊断 | `./mvnw -pl batch-console-api -am -Dtest=ConsoleClusterDiagnosticServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 10 tests passed；指标 Mapper 顺序、状态集合和诊断结果由现有测试覆盖。 |
| Console API OIDC Redis | `./mvnw -pl batch-console-api -am -Dtest=ConsoleClusterDiagnosticServiceTest,RedisOidcAuthorizationRequestRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test` | 13 tests passed；覆盖短期 state 的保存与原子消费。 |
| Java SDK | `./mvnw -pl sdk/java/core -am -Dtest=SdkAbstractAtomicHandlerTest,RequestSignerConformanceTest,TaskDispatcherTest -Dsurefire.failIfNoSpecifiedTests=false test` | 25 tests passed；SDK 模块及所需 reactor 编译通过。`JdbcSdkCheckpoint` 没有专属测试类，本轮由编译覆盖。 |
| Java SDK 格式 | `./mvnw -pl sdk/java/core spotless:check '-DspotlessFiles=src/main/java/io/github/pinpols/batch/sdk/handler/SdkAbstractAtomicHandler.java,src/main/java/io/github/pinpols/batch/sdk/checkpoint/JdbcSdkCheckpoint.java,src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java,src/main/java/io/github/pinpols/batch/sdk/internal/RequestSigner.java'`；`git diff --check HEAD -- sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/SdkAbstractAtomicHandler.java sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/checkpoint/JdbcSdkCheckpoint.java sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/RequestSigner.java` | 均通过。 |
| 文件校验和示例 | `./mvnw -f examples/task-spi-plugin/file-checksum-executor/pom.xml -Dtest=FileChecksumTaskExecutorTest test` | 3 tests passed；独立示例编译通过。 |
| 文件校验和示例格式 | 临时 Spotless POM 对 `src/main/java/io/github/pinpols/batch/ext/checksum/FileChecksumTaskExecutor.java` 使用 Palantir Java Format 2.92.0（GOOGLE 风格）定向 `spotless:apply`、`spotless:check`；`git diff --check HEAD -- examples/task-spi-plugin/file-checksum-executor/src/main/java/io/github/pinpols/batch/ext/checksum/FileChecksumTaskExecutor.java` | 生产类检查通过；临时 POM 已移除。首次全项目检查还发现示例测试文件的既存格式差异，未改动测试文件。 |
| Console API 格式（application/query） | `./mvnw -pl batch-console-api spotless:check '-DspotlessFiles=src/main/java/io/github/pinpols/batch/console/application/rbac/ConsoleTenantApplicationService.java,src/main/java/io/github/pinpols/batch/console/application/observability/DefaultConsoleQueryApplicationService.java,src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleOpsQueryService.java,src/main/java/io/github/pinpols/batch/console/application/ops/DefaultConsoleOpsApplicationService.java,src/main/java/io/github/pinpols/batch/console/shared/query/ConsoleQuerySupport.java'`；`git diff --check` | 均通过。 |
| Console API 格式（诊断/OIDC） | `./mvnw -pl batch-console-api spotless:check '-DspotlessFiles=src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java,src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/RedisOidcAuthorizationRequestRepository.java'`；`git diff --check` | 均通过；按文件定向检查，未格式化其它已有业务改动。 |
| Console 前端拦截器 | `npm run test:unit -- src/api/interceptors.maintenance.test.ts src/api/interceptors.integration.test.ts`、`npm run typecheck`、`npx prettier --check src/api/interceptors.ts`、`npx eslint src/api/interceptors.ts` | 19 tests passed；类型检查、Prettier 与 ESLint 通过。 |
| Console 前端生产深层扫描 | 对 `src` 中生产 `.ts`/`.vue`（排除测试、生成 API 类型）的 AST 扫描 | 修改前 503 文件/29 个深层命中；修改后 503 文件/24 个。扫描只覆盖连续 3 层及以上。 |
| Console 前端本轮生产代码编译与格式 | `npm run typecheck`；对 8 个改动文件运行 Prettier 检查及 `git diff --check` | 类型检查、格式检查、差异检查均通过；未运行测试。 |
| Console 前端业务参数转换与摘要阶段可读性改造编译 | `npm run typecheck`（`batch-console`） | exit code 0；vue-tsc 与 Node TypeScript 配置编译通过，未运行测试。作业触发的业务日期、payload JSON 序列化，结构摘要的 join/最终裁剪，公钥缓存时间戳/JSON 序列化，Meta API 的响应读取/toObject 或 toOptions 转换，Usage Summary 的时区读取/日期范围构造，诊断摘要的生成/HTML 净化，工作流快照克隆/序列化，移动详情 JSON 解析/格式化，批量创建失败租户摘要拼接，流程保存文案选择，Worker 负载百分比计算/上限裁剪，文件名字符清理/URI 解码，错误 key 去空白/格式校验与业务建议回退，实例状态归一化/可操作状态判断均分阶段命名，保留原调用次数、顺序及回退行为。 |
| Console API 测试源码编译 | `./mvnw -pl batch-console-api -am -DskipTests test-compile` | BUILD SUCCESS；394 个测试源码编译通过，未运行测试。 |
| Orchestrator 测试源码编译 | `./mvnw -pl batch-orchestrator -am -DskipTests test-compile` | BUILD SUCCESS；345 个测试源码及 640 个生产源码编译通过，未运行测试。首次编译发现局部变量类型写为 `String`，已修正为 `Long` 后整模块编译通过。 |
| Orchestrator JDBC 测试整理格式 | 对本轮 9 个集成测试类定向运行 Spotless 与 `git diff --check` | 定向 Spotless 与 `git diff --check` 均通过。 |
| Worker Atomic 运行状态配置计数可读性改造编译（本轮） | `./mvnw -pl batch-worker/atomic -am -DskipTests test-compile` | BUILD SUCCESS；Atomic 28 个生产源码和 30 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Worker Atomic Shell 工作目录可读性改造编译 | `./mvnw -pl batch-worker/atomic -am -DskipTests test-compile` | BUILD SUCCESS；Atomic 28 个生产源码和 30 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Worker Atomic Shell 输出读取结果构造可读性改造编译 | `./mvnw -pl batch-worker/atomic -am -DskipTests test-compile` | BUILD SUCCESS；Atomic 28 个生产源码、30 个测试源码及 reactor 依赖编译通过，未运行测试。输出缓冲区文本化、ReaderResult 构造和 AtomicReference 发布阶段分开命名。 |
| Worker Atomic Shell 格式与扫描 | 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 Atomic 与后端根目录 | 均通过；该次扫描口径的历史计数已过时，以当前表格为准。 |
| Console 租户批量创建校验可读性改造编译 | `npm run typecheck` | Vue/TypeScript 应用与 Node 配置类型检查通过；未运行测试。 |
| Console 租户批量创建校验格式与扫描 | `npx prettier --check src/views/system/components/TenantBatchCreateDialog.vue`、文件级 `git diff --check`；修正 AST 扫描器复扫 `src` | 格式与差异检查通过；504 个生产文件/223 个嵌套位置/17 个深层位置。 |
| Worker Dispatch 阶段参数可读性改造编译（本轮） | `./mvnw -pl batch-worker/dispatch -am -DskipTests test-compile` | BUILD SUCCESS；Dispatch 75 个生产源码、39 个测试源码及 reactor 依赖编译通过，未运行测试。准备阶段先命名回执策略和 pipeline instance id；补偿阶段先命名默认 channel code；审计/派发命令先显式取 trace id 并字符串化，按原有调用顺序传入。 |
| Worker Dispatch 本地 Outbox 路径可读性改造编译 | `./mvnw -pl batch-worker/dispatch -am -DskipTests test-compile` | BUILD SUCCESS；Dispatch 75 个生产源码和 39 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Worker Dispatch 本地 Outbox 格式与扫描 | 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 `batch-worker/dispatch` | 均通过；该次扫描口径的历史计数已过时，以当前表格为准。本轮具体复核：outbox 目录准备流程有明确路径阶段并已拆分；HTTP 客户端 fluent builder 是顺序声明配置，常规字符串/枚举转换保持 inline；模块其它候选仍待逐项分类。 |
| Console 深层候选分类 | 对 `src/views/job` 的表单校验回调与 `src/views/ops/OpsDiagnostic.vue` 的 `h()` 渲染树复核 | 简短单条错误翻译和 `h()` 虚拟节点嵌套为自然的校验/声明式渲染形式，保留；租户批量校验中包含行号回退和动态错误消息的长表达式已在上一轮拆分。 |
| Trigger 并发/日历/状态投影可读性改造编译（本轮） | `./mvnw -pl batch-trigger -am -DskipTests test-compile` | BUILD SUCCESS；Trigger 99 个生产源码、49 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Console JobDefinitionWizard bundle assembly 类型检查（本轮） | `npm run typecheck`（batch-console） | Vue/TypeScript 应用与 Node 配置类型检查通过，未运行测试。 |
| Console tenant package row patch/readable error handling 类型检查（本轮） | `npm run typecheck`（batch-console） | Vue/TypeScript 应用与 Node 配置类型检查通过，未运行测试。 |
| Console Job Monitoring fallback/category 映射类型检查（本轮续） | `npm run typecheck`（batch-console） | Vue/TypeScript 应用与 Node 配置类型检查通过，未运行测试。 |
| Console datetime/登录 PEM/结构摘要可读性改造类型检查（本轮） | `npm run typecheck`（batch-console） | Vue/TypeScript 应用与 Node 配置类型检查通过，未运行测试。 |
| Java SDK HMAC/JDBC/timeout/report 参数可读性改造编译（本轮） | `./mvnw -pl sdk/java/core -am -DskipTests test-compile` | BUILD SUCCESS；Core 86 个生产源码、68 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Worker Core listener/scheduler/HTTP 配置与 claim chunk 可读性改造编译（本轮） | `./mvnw -pl batch-worker/core -am -DskipTests test-compile` | BUILD SUCCESS；Core 119 个生产源码、41 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Common resilience/scheduler 配置值可读性改造编译（本轮） | `./mvnw -pl batch-common -am -DskipTests test-compile` | BUILD SUCCESS；Common 344 个生产源码、116 个测试源码及 reactor 依赖编译通过，未运行测试。CircuitBreaker 与 scheduler 配置读取、值钳制、setter 顺序按原顺序保留。 |
| Orchestrator HTTP/传感器错误映射可读性改造编译（本轮） | `./mvnw -pl batch-orchestrator -am -DskipTests test-compile` | BUILD SUCCESS；Orchestrator 640 个生产源码、345 个测试源码及 reactor 依赖编译通过，未运行测试。Kafka/DB sensor 异常先生成具名标准错误结果；日志调用和异常详情回退保持原顺序。 |
| Trigger admission/配置夹限可读性改造编译（本轮续） | `./mvnw -pl batch-trigger -am -DskipTests test-compile` | BUILD SUCCESS；Trigger 99 个生产源码和 49 个测试源码及 reactor 依赖编译通过，未运行测试。两个 scheduler 的等待秒数均先读取、再钳制到非负值。 |
| Worker Export 分片边界可读性改造编译（本轮） | `./mvnw -pl batch-worker/export -am -DskipTests test-compile` | BUILD SUCCESS；Export 56 个生产源码、34 个测试源码及 reactor 依赖编译通过，未运行测试。等宽游标切分先命名分片偏移、乘积分子和分片宽度，末片仍直接使用原始上界，DECIMAL64 舍入及分支行为不变。 |
| Worker Import 阶段参数可读性改造编译（本轮） | `./mvnw -pl batch-worker/import -am -DskipTests test-compile` | BUILD SUCCESS；Import 74 个生产源码、56 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Worker Import 校验规则回退可读性改造编译 | `./mvnw -pl batch-worker/import -am -DskipTests test-compile` | BUILD SUCCESS；Import 74 个生产源码和 56 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Worker Import 校验规则回退格式与扫描 | 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 `batch-worker/import` | 均通过；74 个生产 Java 文件/1,321 个嵌套位置/115 个深层位置。算法与期望值仍按既有字段优先级回退，本轮只将 `firstNonNull` 的结果命名后再进行现有字符串转换。 |
| Trigger Quartz Job group 查询可读性改造编译 | `./mvnw -pl batch-trigger -am -DskipTests test-compile` | BUILD SUCCESS；Trigger 99 个生产源码和 49 个测试源码及依赖模块编译通过，未运行测试。 |
| Trigger Quartz Job group 查询格式与扫描 | 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 `batch-trigger` | 均通过；99 个生产 Java 文件/1,031 个嵌套位置/42 个深层位置。 |
| Trigger 固定间隔注册可读性改造编译 | `./mvnw -pl batch-trigger -am -DskipTests test-compile` | BUILD SUCCESS；Trigger 99 个生产源码和 49 个测试源码及依赖模块编译通过，未运行测试。 |
| Trigger 固定间隔格式与扫描 | 对 `TriggerLaunchLagMonitor.java`、`TriggerOutboxRelay.java` 和 `TriggerReconciler.java` 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 `batch-trigger` | 均通过；Trigger 99 个生产 Java 文件/1,029 个嵌套位置/38 个深层位置。 |
| Worker Export 快照优先级可读性改造编译 | `./mvnw -pl batch-worker/export -am -DskipTests test-compile` | BUILD SUCCESS；Export 56 个生产源码和 34 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Worker Export 快照优先级格式与扫描 | 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 `batch-worker/export` | 均通过；56 个生产 Java 文件/830 个嵌套位置/80 个深层位置。模板、metadata、默认值的优先级不变，当前时刻仍在原有求值阶段读取。 |
| Worker Core Topic MDC 可读性改造编译 | `./mvnw -pl batch-worker/core -am -DskipTests test-compile` | BUILD SUCCESS；Core 119 个生产源码和 41 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Worker Core Topic MDC 格式与扫描 | 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 `batch-worker/core` | 均通过；119 个生产 Java 文件/1,061 个嵌套位置/67 个深层位置。两个 topic MDC 写入仍在原处调用解析策略并写入结果。 |
| Console 文件流水线进度 ID 可读性改造编译 | `npm run typecheck` | Vue/TypeScript 应用与 Node 配置类型检查通过；未运行测试。 |
| Console 文件流水线进度 ID 格式与扫描 | AST 扫描、`git diff --check`；`npx prettier --check src/views/file-center/FilePipelineObservability.vue` | 扫描为 504 个生产文件/222 个嵌套位置/17 个深层位置，差异检查通过。整文件 Prettier 仍报告既有第 557 行类型注解的换行差异；Prettier 与原文件 diff 表明本轮新增代码没有格式差异。 |
| Common 文件摘要字段可读性改造编译 | `./mvnw -pl batch-common -am -DskipTests test-compile` | BUILD SUCCESS；Common 344 个生产源码和 116 个测试源码编译通过，未运行测试。 |
| Common 文件摘要字段格式与扫描 | 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 `batch-common` | 均通过；344 个生产 Java 文件/796 个嵌套位置/65 个深层位置。最后修改时间与 ETag 在摘要构造前分别命名。 |
| Common S3 凭据装配可读性改造编译 | `./mvnw -pl batch-common -am -DskipTests test-compile` | BUILD SUCCESS；Common 344 个生产源码和 116 个测试源码编译通过，未运行测试。 |
| Common S3 凭据装配格式与扫描 | 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 `batch-common` | 均通过；该次计数已过时，以当前根目录扫描表格为准。Client 与 presigner 的 endpoint、基础凭据和凭据提供器都按原构造顺序分步命名。 |
| Worker Atomic HTTP 响应头脱敏可读性改造编译 | `./mvnw -pl batch-worker/atomic -am -DskipTests test-compile` | BUILD SUCCESS；Atomic 28 个生产源码和 30 个测试源码及依赖模块编译通过，未运行测试。 |
| Worker Atomic HTTP 响应头脱敏格式与扫描 | 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 `batch-worker/atomic` | 均通过；该次扫描口径的历史计数已过时，以当前表格为准。原始响应头先取出、再脱敏，随后写入输出。 |
| Orchestrator after-commit 指标快照可读性改造编译 | `./mvnw -pl batch-orchestrator -am -DskipTests test-compile` | BUILD SUCCESS；Orchestrator 640 个生产源码和 345 个测试源码及 reactor 依赖编译通过，未运行测试。 |
| Orchestrator after-commit 指标快照格式与扫描 | 定向 Spotless、文件级 `git diff --check`；修正 AST 扫描器复扫 `batch-orchestrator` | Spotless 与差异检查通过；640 个生产 Java 文件/7,685 个嵌套位置/495 个深层位置。 |
| Worker Process 深层候选分类 | 复核 `SqlTransformComputeSpec`、`DefaultProcessStageExecutor`、`ProcessTaskExecutor`、`ProcessStepBeanRegistrar` | 配置别名回退与字段解析、注册器固定 binding 列表、插件按键索引和传输上下文 getter 保留为直观映射/声明式配置；Process task capability timeout 与 staging task ID 已拆分为有名阶段。未对该模块其他嵌套位置作全量分类结论。 |
| Console 文件模板类型选项可读性改造编译 | `npm run typecheck` | Vue/TypeScript 应用与 Node 配置类型检查通过；未运行测试。 |
| Console 文件模板类型选项格式与扫描 | AST 扫描、`git diff --check`；`npx prettier --check src/views/file-center/FileTemplateList.vue` | 扫描为 504 个生产文件/221 个嵌套位置/17 个深层位置，差异检查通过。整文件 Prettier 报告工作树原有模板属性缩进及长属性换行差异；Prettier 与原文件 diff 表明本轮新增代码没有格式差异。 |
| Console 批日补跑 API envelope 解包可读性改造编译 | `npm run typecheck` | Vue/TypeScript 应用与 Node 配置类型检查通过；未运行测试。 |
| Console 批日补跑 API envelope 解包格式与扫描 | 定向 Prettier、`git diff --check`；AST 扫描 `src` | 格式和差异检查通过；504 个生产文件/213 个嵌套位置/17 个深层位置。8 个 API 方法均先完成请求，再将响应交给 envelope 解包，调用顺序保持不变；`post` 包装器类型声明接受 `null` 请求体，cancel 保留原 `null` 值。 |
| Worker Atomic 存储过程输出值可读性改造编译 | `./mvnw -pl batch-worker/atomic -am -DskipTests test-compile` | BUILD SUCCESS；Atomic 28 个生产源码、30 个测试源码及 reactor 依赖编译通过，未运行测试。输出参数先读取，再截断，最后写入 map；数据库读取、截断和写入顺序保持不变。 |
| Console 事件目录、阶段/关键词归一化、分隔符采样、文件摘要和 AI 请求路径可读性改造编译 | `npm run typecheck` | Vue/TypeScript 应用与 Node 配置类型检查通过；未运行测试。事件行序列化后转小写再匹配，pipeline type/阶段码分步归一化，检索关键词先去空白再转小写，分隔符采样先截取最多 8 行再逐行计数，SHA-256 先读取文件字节再计算摘要并逐字节补齐十六进制文本，AI 取消请求路径先编码 requestId 再组 endpoint 和完整 URL；顺序和次数保持不变。 |
| Console asset freshness 可选筛选参数组装可读性改造编译 | `npm run typecheck` | 首次类型检查发现 `query.assetCode` 在局部判断后未被 TS 收窄；增加仅影响类型检查的非空断言后重跑通过。空白判断与实际参数值仍各自执行原有 trim，属性读取/调用次数和筛选参数顺序保持不变；未运行测试。 |
| Console JobDefinitionList 租户导入目标归一化与 JSON 格式化可读性改造类型检查 | `npm run typecheck` | Vue/TypeScript 应用和 Node 配置类型检查通过；目标值依次 trim、过滤空值、按首次出现顺序去重，JSON 先 parse 再 stringify；异常回退仍在原 try/catch 范围内，未运行测试。 |
| Console ReportExportHub 趋势刻度与耗时舍入阶段可读性改造类型检查 | `npm run typecheck` | Vue/TypeScript 应用和 Node 配置类型检查通过；秒数先四舍五入再钳制非负；趋势刻度先向上取 5 的倍数再保证最小为 1，并由两处共同使用；Y 轴最大值先映射日总数再求最大值。公式和图表输出不变，未运行测试。 |
| Console TenantPackageImportWizard issue 表格高度钳制阶段可读性改造类型检查 | `npm run typecheck` | Vue/TypeScript 应用和 Node 配置类型检查通过；按 base、行数和行高估算后，先应用最低高度，再限制最高高度；160/480 边界和最终计算值不变，未运行测试。 |
| Console BatchDayReplay 版本 ID 归一化重复链可读性改造类型检查 | `npm run typecheck` | Vue/TypeScript 应用和 Node 配置类型检查通过；选中 ID 先转文本，再追加手工输入、过滤空值并拼接；watch 依赖和提交参数仍在原求值位置调用，保持顺序和重复调用次数，未运行测试。 |
| Orchestrator 请求签名拒绝日志参数脱敏可读性改造编译 | `./mvnw -pl batch-orchestrator -am -DskipTests test-compile` | BUILD SUCCESS；Orchestrator 640 个生产源码、345 个测试源码及 reactor 依赖编译通过，未运行测试。租户、请求方法、URI 的脱敏结果先命名再传给日志；方法读取/脱敏仍先于 URI 读取/脱敏，保留原调用顺序。 |
| Orchestrator 分区参数解析与传感器失败结果可读性改造编译 | `./mvnw -pl batch-orchestrator -am -DskipTests test-compile` | BUILD SUCCESS；Orchestrator 640 个生产源码、345 个测试源码及 reactor 依赖编译通过，未运行测试。PartitionCountResolverSupport、WorkerBasedPartitionCountResolver、DefaultSchedulePlanBuilder 和 BundlePlanParams 将字符串化/去空白与数值解析分步命名；SensorStateMachine 将探针类型码、异常消息/类型和 fallback 消息分步命名；异常捕获范围、调用顺序、尝试顺序和回退值不变。 |
| Orchestrator dry-run HEAD 请求构造与发送阶段可读性改造编译 | `./mvnw -pl batch-orchestrator -am -DskipTests test-compile` | BUILD SUCCESS；Orchestrator 640 个生产源码、345 个测试源码及 reactor 依赖编译通过，未运行测试。受保护 URI、空请求头、双超时和 guarded 地址策略先构造为 probeRequest，再交给 transport；DNS 校验仍在构造和发送之前，参数值和执行顺序不变。 |
| Orchestrator deadline 时间解析与时区阶段可读性改造编译 | `./mvnw -pl batch-orchestrator -am -DskipTests test-compile` | BUILD SUCCESS；Orchestrator 640 个生产源码、345 个测试源码及 reactor 依赖编译通过，未运行测试。`LaunchParamResolver.parseDeadlineInstant` 将文本解析、业务日期与本地时间组合、时区应用和 Instant 转换分步命名；原有各自的 `try/catch` 边界、默认时区读取次数、解析回退顺序和返回值保持不变。 |
| Worker Dispatch 执行结果消息选择可读性改造编译 | `./mvnw -pl batch-worker/dispatch -am -DskipTests test-compile` | BUILD SUCCESS；Dispatch 75 个生产源码、39 个测试源码及 reactor 依赖编译通过，未运行测试。成功和失败消息先按原 nullable 条件选出，再传给 `TaskResult` 工厂；保留原 getter 的条件调用次数和先后顺序，不改回退值。 |
| Worker Dispatch 临时清单路径与整数配置解析可读性改造编译 | `./mvnw -pl batch-worker/dispatch -am -DskipTests test-compile` | BUILD SUCCESS；Dispatch 75 个生产源码、39 个测试源码及 reactor 依赖编译通过，未运行测试。先命名带随机后缀的临时清单文件名，再解析其目录路径；两个通道配置解析器分开执行文本化/去空白与整数解析。保留随机数生成、`toString` 调用次数、空白判断、异常传播和默认值。 |
| Worker Core 补偿开关与 pipeline type 归一化可读性改造编译 | `./mvnw -pl batch-worker/core -am -DskipTests test-compile` | BUILD SUCCESS；Core 119 个生产源码、41 个测试源码及 reactor 依赖编译通过，未运行测试。布尔配置值先转文本/去空白，再比较 true；compensator 和 verifier 的 pipeline type 先归一化，再查找/映射；保留候选 accessor 次数、null/blank 短路和命中顺序。 |
| Worker Core lease execution 起始单调时间可读性改造编译 | `./mvnw -pl batch-worker/core -am -DskipTests test-compile` | BUILD SUCCESS；Core 119 个生产源码、41 个测试源码及 reactor 依赖编译通过，未运行测试。先在原锁内、创建 lease 前采样一次 nanoTime，再用该值构造 lease 并注册；时间采样次数、位置和注册顺序保持不变。 |
| Worker Export 配置解析与 Excel 文本安全处理可读性改造编译 | `./mvnw -pl batch-worker/export -am -DskipTests test-compile` | BUILD SUCCESS；Export 56 个生产源码、34 个测试源码及 reactor 依赖编译通过，未运行测试。列类型、引号策略、转义策略先归一化文本再做 enum 查找；模板整数配置分开展示文本存在判断、数字解析和最小值钳制；Excel 文本先选回退值，再做公式转义并写入单元格。保留异常捕获范围、默认值、调用次数及输出顺序。 |
| Worker Export TaskCapability 配置秒数到 Duration 可读性改造编译 | `./mvnw -pl batch-worker/export -am -DskipTests test-compile` | BUILD SUCCESS；Export 56 个生产源码、34 个测试源码及 reactor 依赖编译通过，未运行测试。资源集合先构造，随后读取秒数配置、转为 Duration，最后构造 TaskCapability；保持原参数求值次序和资源/超时值。 |
| Worker Import 清单记录对账参数解析可读性改造编译 | `./mvnw -pl batch-worker/import -am -DskipTests test-compile` | BUILD SUCCESS；Import 74 个生产源码、56 个测试源码及 reactor 依赖编译通过，未运行测试。file ID 先去空白再数值解析后查询；metadata 先转为文本再 JSON 解析；保持租户读取、转换、查询的顺序和 NumberFormat/JSON 异常捕获范围。 |
| Worker Import sidecar manifest 校验阶段可读性改造编译 | `./mvnw -pl batch-worker/import -am -DskipTests test-compile` | BUILD SUCCESS；Import 74 个生产源码、56 个测试源码及 reactor 依赖编译通过，未运行测试。schema、对象名、大小、checksum 类型/值/scope 改为按原顺序逐段拒绝；类型文本归一化与支持集匹配显式分开。保留所有 getter 次数、短路点、默认 scope 和最终 checksum 格式验证。 |
| Worker Import JDBC 配置映射归一化可读性改造编译 | `./mvnw -pl batch-worker/import -am -DskipTests test-compile` | BUILD SUCCESS；Import 74 个生产源码、56 个测试源码及 reactor 依赖编译通过，未运行测试。system binding key/value 先分别字符串化、去空白，再写入 Map；StageSwap 的 partition table 和 attach clause 先分别归一化，再构造 record。保留 key/value 求值顺序、null 分支、转换次数和字段次序。 |
| Worker Import recover-aware 文件状态更新参数解析可读性改造编译 | `./mvnw -pl batch-worker/import -am -DskipTests test-compile` | BUILD SUCCESS；Import 74 个生产源码、56 个测试源码及 reactor 依赖编译通过，未运行测试。先读取 context attributes，再读取 FILE_ID 值并转换为 Long；该解析仍在原 try/catch 之前，后续状态更新、异常分支和租户读取顺序不变。 |
| Console API 动态响应 Long 列表/Map 转换可读性改造编译 | `./mvnw -pl batch-console-api -am -DskipTests test-compile` | BUILD SUCCESS；Console API 1,031 个生产源码、394 个测试源码及 reactor 依赖编译通过，未运行测试。Number 转 long 与文本转 long 分支显式展开；map null 仍保留 null、list null 仍沿用原异常行为，保持遍历顺序和每个元素的转换/插入时机。 |
| Console API trigger type 参数归一化可读性改造编译 | `./mvnw -pl batch-console-api -am -DskipTests test-compile` | BUILD SUCCESS；Console API 1,031 个生产源码、394 个测试源码及 reactor 依赖编译通过，未运行测试。trigger type 文本先去空白并转大写，再查找 enum；null/blank 默认分支与 `IllegalArgumentException` 捕获范围保持不变。 |
| Console API trigger type 与 AI 会话游标构造可读性改造编译 | `./mvnw -pl batch-console-api -am -DskipTests test-compile` | 首次编译因游标 ID 局部变量误标为 Long 失败；按实体 String 类型修正后重跑 BUILD SUCCESS。Console API 1,031 个生产源码、394 个测试源码及 reactor 依赖编译通过，未运行测试。trigger type 归一化后查找 enum；AI 会话分页在 `hasMore` 分支内提取最后可见行和游标字段，再编码 cursor，保留短路条件和输出字段。 |
| Console API 配置审批状态与自审字段解析可读性改造编译 | `./mvnw -pl batch-console-api -am -DskipTests test-compile` | BUILD SUCCESS；Console API 1,031 个生产源码、394 个测试源码及 reactor 依赖编译通过，未运行测试。latest/approval map 的状态文本先命名再比较；自审校验先读取操作人，再读取申请人并比较，保持原调用顺序、null 字符串转换行为和冲突分支。 |
| Console API 通知测试投递 JSON 与事件字段构造可读性改造编译 | `./mvnw -pl batch-console-api -am -DskipTests test-compile` | BUILD SUCCESS；Console API 1,031 个生产源码、394 个测试源码及 reactor 依赖编译通过，未运行测试。payload map、JSON 序列化、事件时间及事件字段 map 分步命名，保持原计算次序及两次独立 UTC 时间读取；未运行测试。 |
| Console 前端终态通知状态展示可读性改造类型检查 | `npm run typecheck` | vue-tsc 与 node tsc 均通过；将状态键解析、翻译和原状态回退集中到具名展示函数，模板不再重复解析状态键；未运行测试。 |
| Rust SDK 签名阶段与 HTTP 幂等键构造可读性改造编译 | `cargo check --lib --features http`（`sdk/rust`） | Finished `dev` profile；规范化 method、计算 body SHA-256、HMAC 签名摘要及 hex 编码分步命名；claim/report 先生成独立幂等键再调用 post。保留字段顺序、签名输入、摘要/随机键生成次数及请求路径；未运行测试。 |
| Go SDK HMAC 签名输入/输出阶段可读性改造编译 | `go build ./...`（`sdk/go`） | exit code 0；先构造 canonical string 和字节，再写入 HMAC，最后读取 digest 并编码 hex；保持签名输入字节、写入次数及输出格式不变；未运行测试。 |
| Go SDK Kafka task ID 反序列化文本归一化可读性改造编译 | `go build ./...`（`sdk/go`） | exit code 0；Kafka task ID 先从原始字节转文本，再去首尾空白，最后去除首尾双引号；保留原转换顺序及 Trim 两侧字符集合，未运行测试。 |
| TypeScript SDK signing、transport payload、capacity 与 Kafka offset 可读性改造编译 | `npm run build`（`sdk/typescript`） | `tsc -p tsconfig.json` 通过；先构造 HMAC 对象再生成 canonical text，保留 crypto 调用次序；HTTP response 先读取 status 再聚合/解码 body；deactivate 先编码路径再读取时间戳并构造 body；capacity floor/max 与 offset BigInt/+1/string 分阶段命名。未运行测试。首轮因缺本地编译器失败，按 lockfile `npm ci --ignore-scripts` 后重跑成功。 |
| TypeScript SDK in-flight drain 轮询等待阶段可读性改造编译 | `npm run build`（`sdk/typescript`） | `tsc -p tsconfig.json` 通过；先启动原有 Promise.allSettled，再计算相同上限的轮询间隔、创建并 unref 定时器，最后 race 两个 Promise；每轮定时、promise 快照和等待行为保持不变，未运行测试。 |
| Python SDK handler、校验错误、签名与 scheduler stop 阶段可读性改造编译 | `python -m compileall -q src`（`sdk/python`） | exit code 0；先 await atomic invocation 再映射 output；Pydantic 错误逐项格式化路径再拼接；签名先归一化方法/计算 body digest，再编码 HMAC 输入，epoch millis 分步命名；stop coroutine 先创建再交给 wait_for；tenant fallback 选择与字符串化分步命名。异常捕获、错误字段顺序、签名字节、时钟/stop 调用次数和超时仍保持不变；未运行测试。 |
| Python SDK Kafka 与 dispatcher 停机 coroutine、超时阶段可读性改造编译 | `python -m compileall -q src`（`sdk/python`） | exit code 0；先按原顺序创建 stop/shutdown coroutine，再计算原下限钳制后的 wait timeout，最后交给 wait_for；两个 try/except 边界、异常处理及 coroutine/timeout 调用次数不变，未运行测试。 |
| Worker Dispatch pipeline instance ID 参数构造可读性改造编译 | `./mvnw -pl batch-worker/dispatch -am -DskipTests test-compile` | BUILD SUCCESS；Dispatch 75 个生产源码、39 个测试源码及 reactor 依赖编译通过，未运行测试。先读取 tenant，再从 attributes 读取并转换 pipeline instance ID，随后构造 insert parameter；保留原参数求值顺序。 |
| Worker Dispatch TaskCapability 配置秒数到 Duration 可读性改造编译 | `./mvnw -pl batch-worker/dispatch -am -DskipTests test-compile` | BUILD SUCCESS；Dispatch 75 个生产源码、39 个测试源码及 reactor 依赖编译通过，未运行测试。资源集合先构造，随后读取秒数配置并转为 Duration，最后构造 TaskCapability；原求值顺序、资源和超时值不变。 |
| Java SDK 配置、heartbeat/claim 映射、JDBC 输出、offset 提交与文本转义可读性改造编译 | `./mvnw -pl sdk/java/core -am -DskipTests test-compile` | BUILD SUCCESS；Java SDK Core 86 个生产源码、68 个测试源码及 reactor 依赖编译通过，未运行测试。环境 Duration 输入、heartbeat/claim 字段、JDBC 输出值、Kafka next offset、regex replacement 与 CSV 引号转义分阶段命名，保持读取次数、求值顺序和异常边界。 |
| Java SDK REPORT body 与精确 Long 数值转换可读性改造编译 | `./mvnw -pl sdk/java/core -am -DskipTests test-compile` | BUILD SUCCESS；Core 86 个生产源码、68 个测试源码及 reactor 依赖编译通过，未运行测试。REPORT 的 task ID 读取和 body 构造在原调用顺序及 try/catch 内分步命名；跨日 ID 的 Number 文本化、BigDecimal 构造及 exact long 转换仍在原异常捕获范围内。 |
| Java SDK heartbeat/lease shutdown timeout 默认值与下限阶段可读性改造编译 | `./mvnw -pl sdk/java/core -am -DskipTests test-compile` | BUILD SUCCESS；Core 86 个生产源码、68 个测试源码及 reactor 依赖编译通过，未运行测试。两个 scheduler 先按 null 选择 0 或 Duration 毫秒值，再执行非负钳制；转换和等待仍在原 try/catch 内。 |
| Worker Process task capability 与 staging task ID 参数可读性改造编译 | `./mvnw -pl batch-worker/process -am -DskipTests test-compile` | BUILD SUCCESS；Process 49 个生产源码、30 个测试源码及 reactor 依赖编译通过，未运行测试。资源集合、超时秒数/Duration 分步命名；staging task ID 先从 attributes 读取再转换为 Long；参数顺序与 SQL 参数键保持不变。 |

## 未完成范围

当前后端生产 Java 全量 AST 扫描为 2,649 个文件、25,818/2,571 个嵌套/深层候选位置；模块计数已按同次根目录输出分组对齐。Worker Core、Dispatch、Process、Import、Export、Atomic、Common、Trigger、Orchestrator、Console API、SDK 和 examples 仍需按修正口径逐项分类。配对前端较早 AST 扫描记录 504 个生产文件、213 个嵌套位置/17 个深层位置；当前按生产排除规则的深层复扫为 500 个文件、22 个深层表达式命中，已逐个复核并按表单错误回调、虚拟节点树分类保留；终态通知状态展示表达式已有具名函数改造。该复扫不覆盖两层候选，前端其余调用现场仍待逐项分类。扫描器重复覆盖的命中数不能当作唯一调用点或缺陷数。测试源码的 53,695/6,473 是另一个盘点范围，不属于本目标的生产代码完成证明；最近几轮对后端 Atomic、Dispatch、Import、Trigger、Export、Core、Common、Orchestrator 做过源码编译，前端做过类型检查，均未运行测试。Rust SDK 本轮复核 signing 与 HTTP transport 的签名/幂等键阶段，简单字符串、Option/Result 转换和 fluent 配置仍属直接映射；Go SDK 本轮复核 signing 的 HMAC 输入/输出阶段；Rust/Go 其余生产文件、Python SDK 本轮复核 Atomic handler、typed validation、signing、scheduler stop 与 tenant context 阶段（其余 Python 文件待审）、TypeScript SDK 其余生产文件、Java SDK 剩余候选及 Java CLI/examples 仍待按生产代码候选逐模块复核。Python `src/batch_worker_sdk` 覆盖 68 个生产 `.py` 文件的 AST 参数扫描当前记录 22 个嵌套表达式命中（非唯一调用点）；简短错误回退、简单字典/属性读取、生成器和标准转换链仍需逐文件分类。测试支撑的旧分类仍需按修正口径重新逐项审查。仅对具有明确阶段/参数来源可读性收益的表达式实施拆分，保留 inline 的候选需记录理由；不能把 AST 命中数当作待改数量或完成证明。
