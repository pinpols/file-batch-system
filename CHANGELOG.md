# 变更日志

本文件记录仓库的主要变更，按日期倒序（最新在上）。分类遵循 [Keep a Changelog](https://keepachangelog.com/) 约定：**Added**（新增）/ **Changed**（调整）/ **Fixed**（修复）/ **Removed**（移除）/ **Notes**（说明）。

日期基于提交时间（`Asia/Shanghai`）。版本号采用 Maven CI-friendly `${revision}`，默认 `1.0.0`（非 SNAPSHOT），构建期可通过 `-Drevision=X.Y.Z` 覆盖。

---

## [Unreleased]

### Fixed

- 两种内置导出数据插件的多分片统一使用哈希，避免各分片独立范围探测失败时混用策略导致重复或遗漏；旧区间配置暂不启用范围优化，性能容量后续验证。

- 通知投递日志查询先解析 JSONB，再遮罩载荷和错误参数中的敏感字段，避免凭据随响应暴露。

- 等待分片按固定扫描轮次先评估正式任务，再评估演练任务，避免跨批次顺序颠倒。

- 等待分片公平分的老化加分安全封顶，非正步长按 60 秒回退。

- 持续新增等待分片时按固定周期回绕，避免旧候选长期无法重新评估。

- 等待分片扫描水位由调度器共享，切换实例后可继续轮转。

- 等待分片按批轮转扫描，避免前一批长期无法派发时后续租户一直得不到评估。

- 暂停 Workflow 后，等待中的子作业分片不再被重新派发；恢复后可继续。

- 普通 Workflow 节点的显式汇聚模式及阈值也在保存和启动时校验，保留未配置时的默认行为。

- 网关显式配置无效汇聚模式或多入边未配置模式时，在保存或启动图校验中拒绝。

- Workflow N_OF 汇聚阈值在保存、图校验和运行时采用一致的整数语义，拒绝小数及溢出值。

- Kafka 偏移等待拒绝超范围、小数和负数的分区或偏移，避免截断后命中错误分区。

- 英文请求不再因 JVM 默认中文而错误回退到中文错误消息。

- 等待传感器的失败参数遮蔽可识别凭据，避免异常原文进入节点结果。

- 文件到达传感器只将星号视为通配符，避免下划线、百分号或反斜线误匹配其他文件。

- 减少作业关联文件总数查询在稀疏关联场景下的文件扫描，保留原分页顺序和语义。

- 作业关联文件加载失败时展示错误和重试提示，不再误报没有文件。

- 作业详情只显示该作业关联的热表文件，并防止切换作业时旧请求覆盖当前结果。

- 文件列表按业务日期正确筛选，今日到达按服务端时区的文件创建时间统计和筛选。

- 出箱重试日志返回关联事件类型，并正确按事件类型筛选列表和总数。

- 出箱投递列表的目标主题筛选现在同时作用于结果、总数和游标分页，并按字面子串匹配。

- 出箱投递错误写入及历史记录查询遮蔽可识别的凭据，保留普通错误信息。

- 补跑列表对同一请求的多张审批只关联最新一张，避免分页重复和遗漏；V362 增加查询索引。

- 作业分区和 Workflow 节点游标分页从首页起按 ID 降序，避免与后续页排序不一致而漏记录；普通分页保留原有排序。

- 告警和重试计划游标分页从首页起按 ID 降序，避免时间与 ID 顺序不一致时漏记录；普通分页原有排序保持不变。

- 操作审计游标首页与后续页统一按 ID 降序，避免记录时间与 ID 顺序不一致时漏行；普通分页排序不变。

- Console 列表游标拒绝浮点及超出整数范围的 ID，避免截断或溢出后查询错误位置；操作审计接口契约补充游标参数。

- 业务日历维护、配置初始化及 Excel 配置包拒绝无法解析的时区，避免错误配置在后续业务日期计算时失败。

- 业务通知按统一规则识别小写事件类型及通配符订阅，避免数据库预筛选漏掉应发送的通知。

- 订阅规则创建和更新拒绝清洗后为空的名称及事件类型，避免保存无法匹配正常事件的规则。

- 通知渠道创建统一清洗后的编码身份；活动渠道重复或并发创建返回409，避免创建动作覆盖已有配置。软删除后仍可按原主键恢复。

- 日历普通保存保留请求无法编辑的既有说明，避免将Excel导入的说明误清空；节假日写入参数具名化并移除无调用方入口，原日期、租户与集合更新语义保持。

- 有状态后端定位保留资源名大小写，并跟随Boot生效Redis连接信息识别URL、列表与拓扑变化；对象存储/Quota后端标识不受默认语言影响。致命产物校验以失败事实裁决，可空错误码不再使Pipeline返回成功。运行范围与首红见[当日验证](docs/review/frontend-backend-bug-review-2026-10-27.md#backend-validation-后端定位与致命验收结果验证)。

- Worker回报发件箱按执行身份保存独立结果，并以领取凭据保护PG/SQLite确认、重试与放弃，避免迟到回包丢失新结果；放弃指标仅计实际状态变更。V360仅用于全新数据库，旧SQLite结构保留并拒绝启动。存储后端身份不再受JVM默认语言影响；Worker Core397项及全新PG迁移4项通过，环境/全工程剩余范围见[当日验证](docs/review/frontend-backend-bug-review-2026-10-27.md#backend-validation-回报队列的领取cas与执行身份验证)。

- Worker执行池强停时取消已移出的未启动Future，避免监听方继续等待完整任务超时；运行中插件仍沿原协作中断和看门狗处理。
- Worker心跳按原注册实例更新本地缓存，迟到回包不再恢复下线项、覆盖同标识新注册或下发过期指令。

- 配置复制在读取源包前拒绝源目标重合，避免去除环境字段后的包回写并清空源渠道；复制/预览/矩阵及作业包的目标/作业元素拒绝null或空白，初始化在首个租户事务前校验整组身份，配置类型解析不再依赖JVM默认语言。
- Workflow配置导入按精确编码定位，避免误更新相似编码；创建去重覆盖升版后的定义，配置包每个编码只导出当前最高版本及其图，不回退旧的启用版本。
- 配置复制、同步导出和预览按完整租户集合读取，移除误用交互分页造成的200条截断；Workflow边集合完整保留。作业配置矩阵会读取展示范围外的显式基准，不再把未比较显示为无差异。
- 租户初始化遇到未知事务提交结果时停止后续目标并向上传播，不再把已提交配置报告为失败；已确认严格回滚仍逐租户隔离。
- 配置同步增加按原请求身份查询审计的管理员接口，支撑响应丢失后的回执核对；目标列表在写入前拒绝 null 和空白，避免留下无效审计。
- 修复外部事件数字 JSON 目标被按字符串查询而误拒合法重投的问题；冻结成员复用原请求，集合外成员仍拒绝。

- Trigger 启动、外部事件与 occurrence 补跑在获得准入许可后复核排水状态，拒绝排水期间才离开队列的写请求；保留正常排队、幂等和已受理请求收敛语义。

- 移除 Trigger 重复的批量日日切写入链，统一由 Orchestrator 按业务日时间策略、冻结标志和版本 CAS 推进并原子记录审计；真实组件扫描及 PostgreSQL 定向回归通过。

- 作业终态收尾历史 Pipeline 步骤时，由 Java 领域状态枚举传入 `FAILED` 目标状态，修复 Mapper 更新语句写死状态码导致的全模块测试规范失败；中断判定与持久化结果保持一致。

- 跨进程临时文件测试等待子进程真正持锁后再检查锁文件，消除锁文件创建前读取造成的偶发失败。

- 修复 SFTP 断连线程卡住时，超时取消的任务仍滞留在无界排队队列的问题；断连等待与实际远端释放的验收边界保持独立。

- 修复 Console Pipeline 进度 dirty 事件在发布失败时不重试、同时间戳更新超过单页上限时遗漏后续实例的问题；失败日志改为净化摘要，事件仍只提示订阅者读取快照。

- Workflow 创建与全量替换拒绝节点、边数组中的空元素，修复 DAG 校验空指针导致的 500；保留合法图、版本及编辑锁语义。Workflow 与调度关联 101 项定向回归通过。

- 修复夏令时回拨后业务日可能倒退的问题；日切按实际时间点只生效一次，保留跳时缺口、节假日及无日历规则。调度与真实 PostgreSQL 恢复定向 14 项通过。

- 修复内置 Worker 批次因致命错误退出后，未执行认领项仍被永久续租的问题；退出清理核对 invocation 和启动状态，保持原错误传播。遥测事件数组中的空元素改为参数错误，避免返回 500。

- Outbox 清理和手动重投仅允许管理员、租户管理员，修复审计员继承读取权限后可执行写操作的问题；统计查询权限保持不变。

- 修复 DLQ 补偿业务先于成功记账独立提交的问题，失败时统一回滚任务、步骤、分区和 Outbox；旧自动扫描的 GIVE_UP 更新不再覆盖已成功重放的死信。真实 PostgreSQL 证据见[续审验证](docs/verifications/backend-bug-remediation-2026-10-26.md#全工程续审补偿与死信)。

- 修复 Worker 续租熔断在最后租约移除后阻断补报的问题; Go 背压保留已拉取记录, Python 在认领确认后才提交消费, 避免未执行任务被跳过。
- 修复 Workflow 归档后的时限误告警、readiness 恢复无超时及扫描饥饿、Helm 跨 Release 监控范围、停用通知渠道仍发送及配置包遗漏 Workflow 说明字段的问题。

- 修复PROCESS已完成阶段有正式处理／发布计数而Job详情与Pipeline进度显示空白的问题。没有文件checkpoint时按成功尝试的安全数值摘要补值；COMMIT使用实际发布条数，保留真实0和未知，不暴露输出摘要或套用旧尝试计数。

- Workflow 进度完整快照补充根运行持久化的开始／结束时间，并在时间变化时推送，修复运行已成功而详情结束时间仍空白的问题；时间未知时保持空值，不以采样时间或节点计数推算。

- 对象存储孤儿对账排除平台保留的 `uploads/` 目录，防止其他租户的托管上传对象被重复登记到默认租户或在确认到达前参与入站；普通入站孤儿对象补登记保持可用。

- 修复文件Pipeline中途丢失Worker后，续跑已成功而旧步骤尝试仍显示RUNNING的问题。Job终态事务将残留活跃尝试记为FAILED及`PIPELINE_ATTEMPT_INTERRUPTED`，保留最新尝试的执行结果；步骤完成写入拒绝覆盖既有终态，防止迟到Worker改写历史中断事实。

- Worker 已认领任务诊断收窄为12字段安全摘要，Orchestrator 不再把完整任务实体序列化到诊断响应；Console DTO 忽略上游未知字段，OpenAPI同步移除租户ID、载荷、有效参数、心跳、结果和错误正文。相关序列化、代理DTO及控制器边界测试共6项通过。

- Console 结果版本接口改为安全元数据投影，不再公开原始 `payload_json` 或内部 `payload_ref`；保留版本治理动作，并为提升/拒绝写入结果版本操作审计。Controller 定向测试6项通过；配对 WDI 前端真实认证浏览器验收通过，核对唯一 EFFECTIVE、角色边界、审计及零残留。

- 修复旧实例级作业 SLA 只检查活跃实例、导致两次扫描之间已结束的违约实例永久漏报的问题。扫描器现在按可信 `finished_at` 补报非演练终态的旧 deadline/运行时长违约，并在发告警前重新读取最新实例状态；Job 监控 Mapper、处理器及扫描竞态定向验证共 47 项通过。该变更不回溯缺少结束时间的记录，也不替代目标环境通知验收。

- Workflow 根级监控策略加入租户配置包，支持随业务定义导出、预览、校验和应用；补真实 PostgreSQL 的接口读写及角色权限覆盖。修复 MyBatis 默认驼峰映射关闭时策略查询返回默认值的问题，改用显式列映射并禁止读回缺失时假报成功；定向配置包、Mapper/RLS、Service 和 HTTP/RBAC 用例通过。真实 Orchestrator/Alertmanager 及 Docker/Helm 环境联验仍待完成。

- Alertmanager 告警的最终 Webhook 投递现传递稳定 `Idempotency-Key`；发生重试复用同键，恢复通知使用不同键。新增真实 PostgreSQL 与本地 HTTP 收件端集成用例，覆盖接入 HTTP 的 503 重试、发生/恢复回执及投递日志；真实 Alertmanager/部署联验仍未完成。

- 新增 `GET /api/console/ops/persistent-waits`，按租户分页查询 readiness 与 Workflow 容量准入等待；Console 经租户守卫代理到 Orchestrator，固定安全投影不返回 `launch_payload`。Controller/OpenAPI 430路由对齐，相关模块编译和服务分页单测通过；Mapper真实PostgreSQL查询及跨服务持久化验收待执行。

- 事件与可用性告警补充依赖服务健康指标、Trigger/Console 终态消费组 lag 和 Workflow 监控器失效信号。另增 Prometheus 自抓取、配置重载与规则计算监控、Alertmanager 目标缺失，以及 Docker 固定依赖和 Loki/Tempo 目标缺失告警，并检测 Job/Workflow 扫描器静默停摆。Job/Workflow 监控定向回归 42 项通过，含 29 项真实 PostgreSQL 测试；promtool 夹具尚未执行。Kubernetes 动态目标清单、Kafka 部分 Broker 阈值、栈外监控和最终通知送达仍需环境验收。

- 作业与 Workflow 软时限告警按来源事实自动解除：运行恢复、机会明确关闭及策略停用分别记录原因；自然恢复须已有发生通知最终回执。V358 增加归档 Run 按触发请求查找索引；Workflow 子作业在实例创建前即按触发请求纳入启动/完成时限监控，创建后继续沿用同一去重身份。最新 42 项定向用例全通过（29 项真实 PostgreSQL），Flyway 至 V358；真实 HTTP 最终收件联验仍待完成。

- Console批次日窗口返回持久冻结标志（无记录为null），支持前端治理动作后独立回读；补扫预览/历史缺口及六种治理操作复用原权限、状态机和审计契约。

- V357 保存告警关闭意图，按租户公平轮询活动与待确认恢复通知；旧回执、多渠道未恢复和晚到发生回执均保留补发资格。恢复发送保留原始身份与时间，持久关闭行的重复输入不重发 firing，并发确认不能重开数据库关闭状态。60 个定向联合用例通过，含真实 PostgreSQL 和完整迁移；自动恢复判定及真实 HTTP 最终送达仍按[专项计划](docs/plans/job-workflow-sla-completion-plan-2026-10-25.md)推进。

- 告警最终通知新增 V356 逐实体/逐渠道成功回执；Console 按正文上限分批发送全部已接收实体，发生与恢复使用独立稳定通知身份，回执持久化失败仍返回可重试失败，并拒绝混合租户分组。36 个定向联合用例通过，含真实 Spring 事务回滚/恢复、RLS 及全量迁移到 V356；自动解除和 CLOSED 恢复补发仍按[专项计划](docs/plans/job-workflow-sla-completion-plan-2026-10-25.md)推进。

- 实时阶段进度携带原始分区认领与阶段执行ID，查询按有效租约、任务归属、运行状态及最新阶段尝试过滤；跨服务再次核对阶段ID，避免旧心跳和新旧尝试串数。Workflow 文件节点展示实际 Pipeline 运行阶段，无活跃阶段时不回退技术任务别名。持久 checkpoint 与业务恢复语义不变。

- Job 软时限监控使用真实执行入口, 补报可证实的终态违约；当前 Workflow 子作业按节点激活及可信依赖快照计算执行资格, MANUAL 策略贯通配置包与 Console。发送前按租户和实例重新核对四类条件, 实例事务繁忙时跳过本轮且保留补报资格；claim 与告警写入失败一起回滚。发送复核及相关定向回归 50 个用例通过, 完整自动解除和可靠通知仍见[专项计划](docs/plans/job-workflow-sla-completion-plan-2026-10-25.md)。

- Job 执行入口计时扩展到 Java、Go、Python、TypeScript SDK 的默认派发器；复用带 `details.executionStarted` 的续租请求，确认当前身份后才进入业务，取消和确认失败各自保持原恢复边界。Rust 提供 `execute_after_start_confirmation` 供自定义执行桥接入；这不代表外部宿主已经升级。Python 续租 409 现按租约失效处理，TypeScript 开始/续租/回报优先使用 CLAIM 当前 invocation。该计时信号与非幂等副作用开始证据独立，完整告警补报、通知和自动解除仍按专项计划推进。

- 配对前端SSE票据发行使用独立请求键，修复新连接复用旧键而被拒绝；不改变Workflow启动/重放原键核对。共享续租服务的执行开始计时增量已补62个当前定向用例；B201完成归档5万条×6臂对照和49个不同归档用例；归档事务局部关闭JIT，提交/回滚恢复原设置。吞吐波动及尾延迟未证明改善，目标容量仍不签收。

- Console新增运行冻结DAG/完整进度快照与业务日五层进度查询、SSE推送；计划分母校验当前调度版本、配置修订及批次截止覆盖，明确区分真实执行、演练和已创建任务。Workflow 节点响应保留 nodeStatus 并新增 jobInstanceStatus，单独表示关联 Job 实例生命周期，不改写 Workflow 状态机；投影单测与真实 PostgreSQL Mapper 用例共 9 项通过。

- 告警通知渠道失败或缺少配置时返回可重试的 HTTP 503；关闭告警按单条指纹解除，不再被同组其他活跃告警阻止。作业与 Workflow 六类时限告警的其余补齐工作见[专项计划](docs/plans/job-workflow-sla-completion-plan-2026-10-25.md)，尚未整体验收。

- Worker批量认领及续租按taskId匹配响应并校验身份，异常响应沿既有单条链路核对；接通同步有界批量回报，保留逐项事务、原重试预算和Outbox交接。回报开关独立、生产默认关闭，Compose/Helm及配置说明同步；批内仍串行执行。定向用例、真实SIM及开关验证分别见[本地收尾记录](docs/verifications/wdi-local-closure-2026-10-25.md)。
- SIM导入前生成权限受限的原生地址夹具副本，上传内容与Apply指纹一致，修复旧API配置键及宿主机访问Docker地址造成的告警；不覆盖已导入ledger。补充成功/失败/中断清理与CI覆盖，保留首次失败审计；核心服务恢复后重新应用阶段Worker模式，失败阻断场景。流程图同步当前认领、续租、报告恢复边界。

- 修复 readiness 移交的数据库依赖列名及恢复地址接线；Orchestrator 沿用统一 Trigger 地址。Kafka 耗尽时恢复锚点写入失败不再跳过消息。非 DISPATCH 外部动作恢复在开始前按实际根请求和源任务版本消费核实授权，与开始标记同事务，阻止另一根请求盗用授权；定向证据见 [功能收尾记录](docs/verifications/wdi-functional-closure-2026-10-25.md)。

- Kafka 消费有限重试耗尽后新增 V348 `batch.trigger_launch_recovery` 持久恢复锚点与周期重投（幂等、退避、上限、业务拒绝终态），消费端 recoverer 不再只记日志就跳过 offset。

- readiness 等待移交：V350 给 `scheduled_occurrence` 落 `readiness_deferred_since/readiness_deadline` 并由 Trigger 扫尾器兜底终结；V351 `batch.readiness_wait` 由 Orchestrator 接管，`ReadinessWaitReconciler` 在无 Quartz 回调时按**依赖集合**判定就绪并驱动恢复，超窗标记 `TIMED_OUT` 解除批日结算阻塞。

- 实体及构造治理：盘点101个实体，保留14个行为访问器；30个生产文件整改48处构造调用，以具名工厂、Builder/toBuilder和已有短构造表达字段。固定通知重试审计证据改record，JSON完整字段比较保持；123多null候选逐项裁定为38整改/85保留。只做源码与语义核对，未编译或测试，见[专项记录](docs/audit/entity-and-constructor-governance-2026-10-24.md)。

- 测试 SQL/断言集中整理：102 个 Java 测试文件提取 24 个 SQL 实参、1504 个 JDBC 结果，62 个轮询查询仍在原回调执行；SDK 子进程普通业务写入统一 JdbcTemplate。修复 Shell 兼容守卫默认枚举删除路径缺口，补 3 个回归源码；只静态核对，未编译或运行测试。见[专项记录](docs/audit/test-sql-and-assertion-governance-2026-10-24.md)。

- 移除租户Pipeline定义API、兼容别名/页面、执行DB回退及旧定义ID；固定阶段来自系统目录，参数归属Job。以V347增量退役5张旧平台表及4张无独立用途冷镜像，历史迁移内容保持本轮开始状态，补齐保留版本号位；仅支持空库部署。
- DQ执行证据随Job运行树原子归档，完整行校验冲突时回滚，支持同租户冷热回查；独立保留期清理在删除前拒绝携带DQ证据的实例。

- ADR 目录外的脚本、文件束迁移和重放验证日志改用业务主题命名，同步索引、回归、文档引用及原危险操作预算；V183/V184 保留版本与 SQL 字节，按空库重新部署，不增加旧名或历史兼容。

- 质量规则迁至 `config/quality/`，同步 Maven、PMD 参数回归、CI 路由及仓库卫生检查；`build/` 统一存放生成物。补齐配置与部署目录入口。迁移本身保持规则内容；相对 main，PMD 参数规则另有阈值与 Mapper / Service 分档调整，SpotBugs 过滤规则内容未变。

- **脚本与 CI 索引治理**：统一 Shell/Python 头部用途与用法，校正说明性英文注释；零散 tools/observability/staging 入口归 dev/ops，scripts/db 与 Flyway 分工保持。补齐 workflow/action 及脚本/SQL 索引，现有门禁检查真实跳转、用途、脚本头与语言；CI 基线集中到 baselines，Java 抑制预算由265收紧为256，SQL零预算保持且兼容目标分支旧路径。

- 本地日志新增统一留存与压缩归档入口，运行/应用历史原文默认保存 7 天、验证日志 30 天、归档包 90 天；默认预览，保护占用、当前日志和验收证据。旧磁盘清理日志选项改为委托压缩归档，过期包销毁需显式启用。

- Mapper查询与写入改为单次操作的类型输入，保持原SQL字段、状态入口和事务回调；恢复派发保留MANDATORY事务要求，补偿与AI私有链路输入同步整理。当前集中整改增量仍待统一验证。

- 资源队列与Pipeline列表、治理操作端口、HTTP POST载荷及批量日/审批/资产/补偿Mapper的宽参数集中改为具名输入，调用方与既有断言同步；当前增量仍待统一验证。

- 开日恢复以同一时间快照构造记录，补跑控制复用归一化命令；清除数据质量检查恒为空的详情参数。Console定义与导入映射、版本快照命令、节点分区身份及短信发送阶段集中整理，后续增量仍待统一验证。

- 修复 Workflow 的 START 直接到 END 时误入旧任务分派并停留 RUNNING；终态检查核对冻结图的未派发分支，保留跳过分支时 END 不激活的收口语义，START 级联跳过后再次核对终态。

- Replay预览与恢复按已批准配置聚合参数并拆分快照组装阶段，移除无调用的通用进度覆盖Mapper；启动消费和依赖等待按业务阶段拆分。补齐终态对账测试的身份读取与父子锁夹具，防止负例提前返回造成假通过。

- Workflow 批量发起沿用逐项独立启动与稳定幂等身份；Console 提供原请求只读核对，按认证租户、Workflow及原键隔离，定义停用后仍可核对已受理请求。未知或暂时查无结果不作为允许重新发起的证据；预检保持独立操作。

- Workflow/Worker 新链路的增量 null 比较统一到既有 EmptyChecks，保留操作数求值顺序；通知 JSON 解码显式表达泛型边界，并清理无必要的静态检查抑制。

- 独立 Job 预检只检查明确 Job 目标，不再按同名编码误查 Workflow 或用 Job 旧日程字段判断流程配置；响应与 Console 提示明确未校验 Workflow 日程和 DAG。

- 压测夹具缓存失效调用补齐真实管理员会话、CSRF 和幂等键，缺少会话时在写入前拒绝；根终态计量排除继承同一 runId 的子 Job 请求，并新增同库独有 schema 回归。
- 取证回放分析统一 JSON 文本与对象中的实际错误字段，避免空 lastError 字段被误报为错误改判；真实错误变化继续报告。

- Config Sync 导入以冻结目标清单和逐目标事务收据恢复结果；重复幂等请求返回原审计，管理员可在 Console 核对 RUNNING 日志，避免审计故障后重复应用配置。
- Job/Workflow 终态通知按租户既有审计保留策略整族迁移回执、投递和人工审计；冷热历史去重查询，冷回执继续阻止迟到事实重复投递。事件目录仅展示五个已支持编码。
- 取证导出取消向不可变记录回写生成 ID，并以 ISO 文本输出日期时间；回放脚本将启动失败、查询失败、超时和缺失结果作为失败，临时 schema 按本轮独占身份清理。
- 四个恢复运维入口传播数据库查询错误、校验 dry-run 值，并使用实际内部密钥或 JWT/CSRF 合同；分区维护文档明确失败时整个 DO 事务回滚。

- DISPATCH 旁挂清单将 JDBC 业务日期固定为 ISO 文本，避免外部清单出现 epoch 数字；补充独立 Worker 崩溃后的 OSS/Spark 等恢复验证。真实 Spark E2E 现在有固定版本、校验摘要和隔离 Python 的标准准备/执行入口。
- EXPORT 默认最终与临时对象键现在按 Job 实例隔离，修复重放新实例静默复用来源成功文件并丢弃新内容的问题。同一实例重领仍复用已登记且校验通过的产物；显式固定键命中其它实例或归属不明的记录时拒绝，登记归属不接受用户 metadata 覆盖。
- 整 Workflow Replay 现在具备一次性预览凭证、加密执行快照、待审批 Replay Operation、批准后的 TriggerRequest/Outbox/新 RootRun 链路及 Console 审批入口。JOB 节点在 Worker CLAIM 后按冻结配置复核重放能力，UNKNOWN 或漂移时失败关闭；已声明的 jdbc_mapped_export 路径具备独立 Worker 执行证据，其它插件按验收矩阵跟踪。
- IMPORT/EXPORT 及文件束现在在 Job 创建事务中捕获并加密有效模板，写入运行参数快照后随现有分发链传给 Worker；原配置重放从来源实例读取并校验实际快照。文件束以分区模板为准，仅发送本分区密文；整图 operation 将选定模板、通道与实际依赖版本绑定到审批执行快照。
- Replay Operation 使用独立 `REJECTED` 终态；待审批的 Workflow 节点和整 Workflow 操作都可经 Orchestrator 条件更新拒绝并幂等读取结果。Console 整图入口使用认证上下文记录申请人/审批人，并展示完整预览及阻塞原因；阻塞项存在时不允许提交。
- 日期区间 Replay Operation 的逐日 session 明细可直接打开；仅在确认未创建子运行且无既存 TriggerRequest 时，允许带操作者审计与幂等键恢复失败的提交项。
- Console 恢复操作执行前核对批准动作和完整业务载荷，拒绝替换实例、业务日、Workflow 根/节点、策略及外部动作核实证据；补偿指令的操作者取认证上下文。文件审批不再提供关闭目标绑定的入口。
- 任务和分区重放的 `RETRY` 审批现在会派发到批准的原目标；不支持的目标被拒绝，业务未成功时不标记审批已执行。
- WAITING 分区现在在释放事务内重新校验资源容量，候选排序不预留突发额度或重复扣派发令牌。新启动与等待释放共用全局、共享组及租户准入锁；同一活跃 Job 的后续分区不再重复占 Job 配额，父状态 CAS 冲突会回滚分区、任务与派发事件。
- Console 创建、查重和查询资源队列时统一配置码，与 Job 的队列引用保持一致，避免带连字符或混合大小写的队列编码失配后绕过队列上限。
- Job 的 T2 分区计划现在沿用接纳时选定的执行定义，避免晚派发或重放时混入当前版本的分片策略和路由。CREATED 恢复复用父 Workflow 快照并核对原实例的定义 ID/版本；dry-run 和批日计划构建也使用各自已选定的定义。
- Orchestrator 现在只接受当前版本且显式携带 `workflowDefinitionId` 的根启动消息；旧版 envelope 和 Job-only 根消息会被确认并拒绝，不再回退到 JobInstance 根启动。Trigger 与 Orchestrator 必须同步部署到 Workflow-only 契约。
- Java、Go、Python、TypeScript Worker SDK 现在以 Orchestrator CLAIM 返回的持久化任务载荷作为业务参数权威来源，并保留 Workflow 跨日依赖等嵌套 JSON；缺失时兼容旧字段，格式错误时安全失败。Rust SDK 新增 CLAIM 成功回包解码、载荷和依赖门禁快照上下文绑定入口；FakePlatform 在 serde feature 下按 CLAIM 回包驱动 handler，并拒绝无效回包。
- Import `BATCH_UPSERT` 现在会在写入前始终拒绝缺少 `conflictColumns` 的模板；已移除过时的运行时开关及其目录种子数据，配置因此无法关闭检查点重放安全保护。
- JobInstance 归档完整性诊断现在通过分区父引用索引检查非空父实例关系，避免逐候选扫描所有月份分区。
- 本地 `start-all.sh` 现在会将选定的 `.env.local` 同时加载到宿主机 JVM 和 Docker Compose，使 Console 使用已配置的 JWT 密钥，而不再回退到弱密钥。
- SIM harness 现在会在阶段成功后、Import Worker 恢复默认扫描前删除 Stage 25/26 输入对象，并在成功退出前再次核验清理结果。清理失败会使 SIM 运行失败；失败或中断的运行会保留诊断证据，下一次 `all` 运行会在应用启动前清除残留。
- 从本地 PostgreSQL 集群中移除了未使用的 `workflow-daily-integration` Compose 堆栈及其卷，以及 27 个不再活跃的 SIM/验收数据库；保留当前 JAR 数据库对和基线开发数据库。
- 声明为非幂等的 ATOMIC 执行器现在会在运行前持久化执行开始围栏。如果 Worker 在 REPORT 前消失，租约回收会记录 `EXTERNAL_OUTCOME_UNKNOWN` 并阻止自动重放；V332 也会将该标记镜像到归档任务行。
- EXPORT 的 `onRegistered` 回调现在必须声明重放安全性。未知或非幂等回调必须在调用前持久化执行开始围栏；若无法确认围栏已写入，EXPORT 将返回 `EXTERNAL_OUTCOME_UNKNOWN`，不会运行可能重复产生业务副作用的回调。

- 租户配置复制现在按 Mapper 的实际别名读取 BusinessCalendar 和假期值。若未提供端点凭证，经过脱敏的 FileChannel 副本会持久化空 JSON 对象，以满足全新数据库的非空约束，同时避免复制环境凭证。

- JOB_RERUN 现在会复用源 JobInstance 已固定的跨日依赖结果。如果源 Workflow 快照不可用或依赖定义已变化，则安全失败，不会悄悄解析更新后的 EFFECTIVE 结果。

- JOB/BATCH 补偿现在会在异步启动前，以原子方式提交冻结的 Workflow 启动请求和 Outbox。重放完成会绑定当前实际执行、加入 REPORT 事务并串行化会话计数；被拒绝的准入会关闭未绑定条目。历史重放和追赶会选择各 JobInstance 冻结的日历。补偿摘要会公开 `launchRequestId`；子 Job 实例号可能要等启动后才生成。

- Web Push 现在会在 VAPID audience 中保留非默认端点端口，并负责 HTTP 客户端生命周期。已关闭隐式 HTTP 重试和重定向，确保一次队列尝试不会发送多个请求。关闭时会断开连接、保留结果不确定的在途请求，并阻止新发送；本地传输测试会独立验证签名并解密原始设备载荷。

- Job 终态事实现在会冻结提交操作人；Console 会将首次 Push 决策和设备意图与终态回执一并提交。终态接收、投递和人工恢复事务会显式绑定 PostgreSQL 租户。持久化的逐设备领取记录取代回看式轮询；结果不确定时停止自动重试，经过审计的 PUSH 恢复继续使用原设备目标。全新数据库切换会拒绝存在旧账本数据的情况。

- Web Push 现在会公开有界的逐设备服务商结果；本地记账失败时仍保留已观察到的接纳结果。只有原租户、用户和设备凭证仍匹配时，服务商回调才会更新或删除订阅；已移除跨租户端点删除 API。Job Push 持久化恢复仍在推进中。

- Job SLA 告警不再作为业务事件订阅对外提供。规则和 Webhook 写入会拒绝尚未产生的 SLA 别名，并提示使用告警通道；Kafka 目录现包含 Job/Workflow 终态和原子派发主题。

- Alertmanager 应用告警现在会在标签中保留已持久化的实体指纹。单条告警静默和严重级别抑制都会遵循该身份，避免一个 Job 静默或解决另一个 Job 的 SLA 告警，同时保留通知分组。

- Job 终态事实现在会在 REPORT 和直接终态事务中通过 Outbox 提交，发布到独立的版本化 Kafka 主题，并复用 Console 的持久化订阅、投递和协调流程。Job 与 Workflow 身份保持区分；重复关闭为相同状态时保留首次快照，失败修正或 RERUN 则保留不同的终态事实。

- Job SLA 扫描与最终告警领取现在共用 SLA 专属状态和期限策略，并排除 dry-run 实例。升级告警发出前会重新检查并锁定当前 Job，避免过期候选在 Job 完成后仍触发告警。

- 终态通知恢复现在会公开租户范围内的投递元数据和管理员协调/重试 API。恢复操作与操作人证据会一同提交；持久化请求身份可避免重复重置预算，配对 Console 会冻结租户和收件人目标并链接审计记录。

- Workflow 终态业务订阅现在会在 Kafka 确认前提交回执和选定的收件人意图。V328 为现有投递记录增加持久化身份、租户范围内的回执引用和过期领取声明；Webhook 重试复用稳定的 Idempotency-Key，结果不确定的非幂等服务商响应则保持 EXHAUSTED，等待协调处理。
- Workflow 终态谱系消费现在会在外部投递前拒绝不支持的载荷 schema 版本；当前生产者发送 `v2`。
- 租约回收现在会在写入恢复派发事件前清除过期的 Worker 实例路由目标，使替换 Worker 实例可以领取任务。定向单测、PostgreSQL 回归和双进程 Import Worker E2E 覆盖 Outbox 目标、替换实例领取、单次业务副作用及过期报告围栏。
- JDBC 自动创建的 ShedLock 表现在使用与 Flyway 相同的 UTC 墙上时钟 `TIMESTAMP WITHOUT TIME ZONE` 列，避免会话时区转换导致持久化锁截止时间偏移；自动建表路径和运维查询均已按 PostgreSQL UTC 墙上时钟语义覆盖。
- 当已接纳的顶层或子 Job Trigger 请求尚未启动时，人工执行 batch-day `SKIP`、`CLOSE` 和 `RELEASE` 现在会返回 `STATE_CONFLICT`。操作会保留请求和开放的 batch-day 状态；未增加数据库迁移。
- 当终态 WorkflowRun 仍关联活跃 JobInstance 时，batch-day 结算现在保持 `IN_FLIGHT`；只有子 Job 进入终态后才会完成结算。未增加数据库迁移。
- 父 WorkflowRun 进入终态后，WAIT sensor 现在会停止探测；batch-day 准入围栏会拒绝该父流程发起的迟到子 Job 请求。迁移 V326 增加此数据库保护；定向 PostgreSQL 回归覆盖开放日拒绝，并保留既有关闭日 `BDA01` 结果。
- 已运行子 Job 的 Worker 成功报告现在可以在父 Workflow 终止后提交，但不会继续派发 DAG 中的下一个 Job。终态围栏位于共享节点派发入口。

- 数据质量检查写入现在必须关联一个同租户 JobInstance。应用锁和 PostgreSQL 触发器会串行化关联与父记录归档，拒绝直接 SQL 创建孤儿或跨租户关联，并阻止重设被引用父记录的键，同时保留归档删除和历史 ID；未增加热表外键或生产冷归档搬迁器。

- 热 Job 分区现在会将源文件引用绑定到同一租户，并在分区仍使用文件时阻止删除。分区创建只会将匹配的并发外键冲突映射为 `NOT_FOUND`；租户清理和 SIM 清理遵循先子后父的生命周期。冷分区归档会保留 `source_file_id`、`template_code` 和 `target_ref`，不增加热表外键。

- TriggerRequest 的 JobInstance 和父 WorkflowRun 历史链接现在会保留弱归档语义，同时强制同租户关联和父身份不可变。V323 会锁定 JobInstance 关联，并阻止被引用 JobInstance/WorkflowRun 的身份变更或 TriggerRequest 父/节点快照重绑；V286 已在业务日围栏后串行化父 WorkflowRun 准入。仍允许归档删除并保留历史 ID；已移除无保护的 TriggerRequest Mapper 写入方法。

- 资产新鲜度策略现在通过同租户 `(tenant_id, asset_code)` 键引用 Job 定义。Console 会在写入前检查并锁定该 Job；数据库约束仍是最终保护，并会在 Job 删除时移除策略。

- Step 现在通过原生复合外键引用准确的 Task 租户、JobInstance 和可选分区。数据库生成的空值安全键会保留无分区任务并支持级联清理；冷镜像会重新计算这些键。任务创建仅将指定的所有权冲突映射为稳定的冲突响应。

- Job 分区现在会携带父 JobInstance 的业务日期和租户范围复合外键，防止跨租户或缺失父记录的引用，并阻止父日期变更或删除。分区创建和冷归档副本使用相同身份；并发父记录变更只会将对应外键竞争映射为稳定冲突。

- Job Task 现在会确保任何分区引用都属于同一租户和 JobInstance。无分区 Task 仍然有效；删除分区时继续沿用现有 Task 级联行为。
- 周期性外部事件和文件组预期现在会在 PostgreSQL 中引用精确的租户范围 Workflow 日历身份。仍有定义时，Console 日历变更会以稳定冲突失败；变更日历前可先显式删除定义，并发 SQL 写入则由复合外键围栏阻止。
- 派发通道健康记录现在会在 PostgreSQL 中强制同租户通道身份，并随通道配置的物理删除一并清理。适配器已返回后若健康状态持久化失败，网关会保留实际投递结果并记录辅助状态失败，避免因此重复执行外部投递。
- Config Sync 现在会在目标写入前提交初始审计，并在单独的租户绑定事务中完成统计。瞬时审计失败只会重试幂等日志更新；持续失败会保留未解决审计，不会把已提交目标错误标记为全部失败。应用结果不确定时保留未确认计数、初始关联元数据并隐藏数据库诊断；审计更新零行时安全失败。
- 配置复制、Bundle 导出和预览现在通过独立的只读 repeatable-read 事务读取源数据。即使调用方已有 read-committed 事务，多查询包也会观察同一源快照；目标租户仍使用各自独立的写事务。
- 租户初始化现在会将精确的 PostgreSQL Job 依赖上游/环路约束映射为稳定的条目错误键，并隐藏未知数据库诊断信息，包括被包装的异常。严格回滚和非严格 savepoint 行为保持不变。Console 约束错误处理会先检查 SQLSTATE，再匹配文本；删除被引用的父记录仍返回 HTTP 409。
- 租户初始化现在会先应用 Bundle 日历，再处理 Jobs 和托管 Workflows。租户级后处理失败时，无论严格或非严格模式，都会回滚该租户此前的写入，避免初始化失败后留下没有托管 Workflow 的 Jobs；其它成功租户仍会提交。
- 租户配置初始化和 Config Sync Bundle 现在会保留全部 Job 依赖规则及每条边的业务日期对齐方式。批量导入会按 Bundle 内所有上游关系对 Jobs 排序，并在写入前校验租户依赖图。导出会绑定源租户数据库上下文，各目标则在自身租户事务中写入，避免源只读状态或其它租户回滚影响操作。Console OpenAPI 和生成类型保持同步。
- PROCESS staging 现在会在 COMPUTE 实际运行时分配持久化 generation，并在检查点恢复时保留该值。业务暂存行通过租户/generation/batch-key `NO ACTION` 外键引用 generation 账本；应用使用相同身份，数据库触发器负责强制 OPEN 状态。全新数据库迁移和 PROCESS PostgreSQL 测试通过。跨库 tombstone 和恢复协议完成前，自动 TTL/DROP 清理仍保持禁用。
- PROCESS staging 清理现在只考虑平台实例围栏证明已不再是当前实例的保留 generation。generation 账本和暂存行都会强制租户范围 RLS；暂存访问也会绑定 generation。业务事务会封存账本，并以有界分块方式删除精确的租户/generation/batch-key 行，保留 tombstone 供重试。最早行指标会在定时后台任务中按租户/generation RLS 扫描并缓存，避免无租户抓取查询误报为零。已移除按日历年龄 DROP 分区和旧的仅按年龄删行逻辑。当前 generation 和整个分区仍保留；暂停/重试/归档、多数据库、崩溃恢复、容量及 SIM 验收完成前，清理保持禁用。
- 默认仍禁用 PROCESS staging 清理，等待长任务、暂停、重试、崩溃恢复、多数据库和容量验收。已移除按日历年龄删除行和整分区 DROP；租户范围启动审计和分区预创建仍可用。每个 PROCESS stage 现在会持有与租约回收相同的平台实例 advisory fence，并在进入业务数据库阶段前检查当前任务、分区、Worker 和调用身份；真实 PostgreSQL 竞争测试验证过期调用无法进入，且回收会等待在途 stage 完成。此保护不覆盖 PROCESS stage 入口之外的直接写入。
- Catch-up 控制现在通过复合外键引用同租户业务日历。应用会预检查相同的 `(tenant_id, calendar_code)`，并将匹配的外键竞争映射为 HTTP 409 后回滚事务；租户清理会先删除控制和审计行，再删除日历。
- Batch-day 等待启动现在要求同租户 TriggerRequest 复合外键；启动闸门会在应用层检查相同的请求身份和状态，保留期及租户清理会在父请求前删除符合条件的等待行。

- Replay 预览令牌现在通过可空的同租户复合外键关联 replay session；Mapper 会执行相同的租户/session 检查，SIM 清理会在截断 session 前明确纳入过期令牌。
- Batch-day replay 条目现在会由 `BatchDayReplayService` 和 PostgreSQL 复合外键共同确保 session 属于同一租户；现有 dry-run 和 OUTPUTS_ONLY replay 路径在定向回归覆盖下仍有效。
- Catch-up 审计现在通过五列复合外键引用精确的 scheduled occurrence 请求和业务日期。应用的条件式 occurrence 更新与审计插入使用相同身份。PostgreSQL 负例会拒绝不匹配的请求 ID 和业务日期；有效 Trigger catch-up 路径通过定向回归。
- Workflow JOB 实例引用现在会在子记录关联与父记录删除之间串行化；热 Workflow 节点仍有引用时，会拒绝删除或重设 JobInstance 键。若独立或其它 Workflow run 引用了成功实例，归档会暂缓处理；Workflow 冷副本保留 Job ID 后才归档 Job。旧 wrapper 仍在一个事务内归档。PostgreSQL 归档和清理回归通过。
- 日历组现在会强制日历和 GROUP 假期属于同一租户；假期代表日历必须属于所引用的组。数据库集成测试类 23/23 通过。
- 日历假期现在使用租户范围复合外键。Console 会按请求租户解析日历，PostgreSQL 则通过相同的 `(tenant_id, calendar_id)` 身份约束直接 SQL 和竞争写入；定向 PostgreSQL 与 Console 集成测试类通过。
- 日历依赖现在使用同租户复合外键和按租户串行化的 PostgreSQL 环路保护；全新 schema 的 PostgreSQL 集成覆盖 21/21 通过。目前 Mapper 没有生产应用写入调用方，因此未来新增写端点时仍须补齐应用层等价保护。
- Job 依赖图写入现在会在 Console 校验和 PostgreSQL 触发器中按租户串行化，拒绝并发 INSERT、直接 UPDATE 和旧标量同步产生的环路；全新 PostgreSQL 集成测试类 19/19 通过。
- Job 实例现在通过复合外键强制 TriggerRequest 同租户关联；请求查找索引保留 request ID 前缀。PostgreSQL 17.11 全新 schema 覆盖有效、跨租户、孤儿、NULL 和父记录删除场景。
- Trigger 集成 fixture 现在只清理自身 Workflow 和请求行，并在父请求前删除 Outbox 行，遵循 NO ACTION 外键拓扑。

- Misfire catch-up request 引用与应用清理拓扑对齐：V296 为 `trigger_misfire_pending` 增加同租户 `NO ACTION` 复合外键；真实 PostgreSQL Mapper 定向回归 13/13，Quartz misfire 集成类随后 8/8 通过。

- Trigger Outbox request 引用与应用事务对齐：V297 为 `trigger_outbox_event` 增加同租户 `NO ACTION` 复合外键；Outbox/Quartz 定向 PostgreSQL 回归 18/18，通过修正孤儿 fixture、日历编码漂移和真实 Scheduler 重启测试生命周期。

- relatedFileId 补偿入口现在会在写入补偿命令前，通过真实 PostgreSQL 应用层保护拒绝引用其它租户的文件；文件生命周期和直接 SQL 防护仍未完成。

- Job 依赖边现在通过 NO ACTION 复合外键强制上游作业在同租户内存在；Console 作业定义集成覆盖 15/15 通过，应用层环路校验仍是必需项。

- Job monitoring policy 现在通过租户范围复合外键引用作业定义，同时保留同一父记录的级联清理；PostgreSQL Console 作业定义集成测试类 16/16 通过。

- 已调度 occurrence 和预期 occurrence 账本现在通过租户范围 NO ACTION 外键引用原始 Trigger 请求。应用层保护继续检查 Workflow、业务日期、事件类型和请求状态；定向 PostgreSQL 回归覆盖有效写入、拒绝孤儿、阻止删除父记录及 fixture 顺序。Misfire/Outbox 账本及完整冷热恢复仍未完成。

- 等待账本现在通过租户范围 NO ACTION 外键引用原始请求。保留清理会保留活跃/可恢复以及仍被运行态或审计引用的记录，以有界批次在请求前删除过期终态等待，并拒绝直接清理仍关联检查点或 Workflow 的实例；自有 fixture 清理遵循相同外键顺序。

- 内部子请求现在会冻结其确切的 Workflow 节点尝试；被拒绝的延迟 Job 会推进现有 DAG 失败路径和终态 Outbox 事务，并在取消提交后定期恢复，同时防护暂停/恢复和过期尝试。原始请求插入失败时会按既有子请求拒绝错误契约停止启动。

- Job 依赖恢复现在会在 readiness 检查前校验持久化身份，在租户绑定事务中读取原始请求，并原子拒绝损坏且尚未准备的等待；已准备实例、终态请求和已修复等待记录仍可恢复，单条等待失败也不会阻塞租户扫描。

- Job 和 Workflow 等待准入现在会锁定并校验原始请求身份与状态，回滚必须成功但影响行数为零的状态变更，保留首次载荷，并在容量恢复前复查持久化槽位元数据；创建 Job 实例前也会通过未缓存的锁定读取重新校验请求。

- Workflow 图节点、边和容量等待现在通过经过校验的租户复合外键引用定义，阻止跨租户 SQL 写入，同时保留显式的先清理子记录、再删除父记录顺序。

- Workflow 写入现在会在发布、启用定义或日程、编辑活动日程以及应用配置导入时检查租户范围内已启用的 JOB 引用；草稿和禁用操作仍可用，启动校验遇到未解析引用或查询失败时会安全拒绝。

- 多态 `resource_tag` 写入现在会在 Console 和 PostgreSQL 同时校验同一租户/类型/代码身份。V314 拒绝直接 SQL 创建孤儿引用，串行化关联与父记录删除/改键，并且只在最后一个匹配父身份消失后删除标签。定向 PostgreSQL 覆盖四类父记录、禁用/软删除元数据、Workflow 多版本改名、关联与改名/删除竞争，以及并发新增版本/删除最后版本的两种提交顺序。完整维护执行、更广泛多版本交错和锁容量仍未验收。
- 业务 RLS 安装和策略变更现在会原子提交角色/授权以及父表/叶子表更新，拒绝不安全的写入角色，并在健康检查中报告可绕过限制的宽松策略；DR 演练会传播 RLS 重建失败，并保留恢复后的数据库供诊断。
- Pipeline 检查点现在通过租户范围父外键关联，并随归档运行树一起锁定、复制、核验和删除；零行写入被拒绝时会报告失败，只做保留期清理的流程会在变更前拒绝包含检查点的候选记录。
- 独立 Workflow 归档现在会保留 JOB 实例链接，并在删除热记录前核验根节点和节点冷副本完整；副本冲突会失败并回滚，不会悄悄丢失历史。
- Dispatch sidecar manifest 现在会将 JDBC Date 和 LocalDate 输入的源业务日期序列化为 ISO 文本，保留字节长度及 SHA-256 完整性元数据。
- 本地 JVM 启动、健康检查、重启和停止现在共用实际应用端口键及控制面 URL；端口冲突会在修改进程前失败。
- 控制面基准测试清理失败时现在会返回非零退出码，并保留此前已发生的基准测试失败；本地、PR 和 Full Gate 脚本检查均有定向回归。
- WARN 审计现在会在日志目录缺失、没有应用日志或不可读取时失败；已有且不含警告的应用日志仍有效。上下文规则使用实际源路径（包括空格和冒号），已有 UNKNOWN 分类仍会阻断。
- SIM 现在会在重置、清理或重启前验证基础设施的 Compose 所有权和已发布端口；应用与 SDK 的 Kafka 目标必须解析到选定 broker。运行时检测遵循显式的 host/docker 模式和项目所有权；SFTP 主机密钥文件按项目隔离，避免跨环境覆盖。
- SIM 重启现在会在 profile 变化期间保留明确选定的环境；过期资源清理采用安全拒绝策略，Stage 26 会校验 Workflow 范围的到达请求 ID，并且只删除本次运行拥有的三个 MinIO 输入对象。
- 迟到到达的 batch-day 重路由现在只会更改 trigger type 和合并参数，并保留已接纳启动请求中的 dry-run、data-interval、replay 和 external-event 字段。
- Workflow 和 Job 启动路径现在会在创建运行态行之前，拒绝缺失或跨租户的重跑父 JobInstance 引用；冷归档也会阻止跨租户谱系及错误挂到另一实例的同租户运行子项，完整性告警覆盖租户和运行身份两类所有权违规。
- 成功实例归档发现的引用完整性问题现在会在独立事务中生成租户范围、去重的持久化告警事件，记录发生次数与示例实例 ID，并沿用现有 Console/Alertmanager 投递路径。
- WorkflowRun 现在通过复合租户外键关联 WorkflowDefinition，即使绕过启动服务插入，也能阻止跨租户运行态引用。
- JobInstance 现在通过复合租户外键关联 JobDefinition，在数据库边界阻止跨租户运行态引用。
- TriggerRequest 接纳和恢复 CAS 更新现在会核验关联的 JobInstance 是否属于该请求租户。
- WorkflowNodeRun 关联分区 JobInstance 时，现在通过所属 WorkflowRun 强制租户归属。
- Workflow 启动现在会在持久化根运行前验证 Bundle 源文件的租户归属，避免被拒绝的启动留下孤立 WorkflowRun 或节点执行记录。
- Bundle 预检现在使用所选执行快照中的 Job 定义，并遵循与派发相同的参数优先级：Job 默认值、节点默认值、显式请求参数。节点默认值不再掩盖无效请求或拒绝有效覆盖值。
- Bundle 子流程启动现在会在创建 JobInstance 前校验 DSL 解析出的源文件归属；明确被拒绝的子请求会使 Workflow JOB 节点失败、沿失败边继续处理，并让根 Workflow 到达持久化终态。
- 配额运行态 CAS 更新现在除行 ID 和版本外，还必须匹配租户 ID，以防止全局过期协调期间发生跨租户更新。
- 成功实例冷归档现在会保留 Workflow JOB 子项身份，以及 WorkflowRun 的 trigger、版本、data-interval 和执行快照，供归档审计和重放使用。
- JobInstance 超时现在会在超时事务中关闭所属 Workflow 节点，并沿 DAG 传播终态；dry-run 终态 Task/partition 也会正确协调，且每个扫描实例均绑定各自租户上下文。
- Workflow 卡死协调现在会将 DAG 恢复、节点聚合和终态更新限定在每个候选运行的租户范围内。
- 过期 CREATED 启动恢复现在会在候选 JobInstance 的租户上下文中执行 TriggerRequest 查询和 T2 派发。
- 全局扫描期间，Task 超时取消现在会在候选 Task 的租户上下文中执行。
- 分区租约回收和孤儿清扫现在会在独立回收事务前，进入每个候选分区的租户上下文。
- SLA 违规处理和升级告警现在会在事务开始前进入候选 JobInstance 的租户上下文。
- Job 监控告警发出前，现在会进入候选租户上下文，再执行独立的幂等事务。
- 到期重试和死信恢复现在会在候选租户上下文中执行各自事务和终态更新。
- Trigger 启动协调和资产新鲜度告警写入，现在会在每个候选操作的租户上下文中执行。
- 文件归档和孤儿上传清理现在会在全局扫描执行状态检查、更新和审计写入前绑定候选对象的租户；对象存储协调则为每个对象使用已配置的租户上下文。
- 依赖等待积压指标现在会枚举所有租户，并在每个事务级 PostgreSQL RLS 租户设置下执行计数查询，包括已暂停租户。
- 外部事件准入、投递别名查询和状态读取现在会在访问强制 RLS 投递表前设置租户 RLS 上下文；定向 PostgreSQL 和服务回归覆盖该路径。
- batch-day 开启现在有一项不可绕过的 PostgreSQL 集成回归，覆盖生产调度器事务、强制 RLS 游标初始化/推进及持久化的 OPEN 状态。
- Workflow WAIT 的 `on_timeout=SKIP_DOWNSTREAM` 现以 `SKIPPED` 结束，并在同一事务内级联跳过不再可触发的下游节点；没有其他失败时 Workflow 成功收口。`FAIL` 超时语义保持 FAILED。
- Workflow Sensor 命中后，rootless 单作业 Workflow 可按冻结图继续创建下游 JOB；仅当明确关联的父 JobInstance 缺失时才停止推进，避免 `single_job_definition_id` 被误判为已有父实例。
- Worker heartbeat 调度现在会等待 `ApplicationReadyEvent`；共享维护状态刷新先运行，避免启动时自注册收到 fail-closed 503，并在 Worker 审计前遵循 60 秒 `Retry-After`。
- 后端单测验证现在会先重建 batch-test-support，再构建依赖模块，避免使用过期安装的测试 fixture；Mapper 状态写入守护会区分实际 SET 赋值和 CASE 表达式中的状态谓词。
- 外部事件按租户与事件编码自动路由到启用的预期 Workflow；事务内冻结目标快照并为每个目标写入 Trigger 请求与 Outbox，支持并发重投、幂等别名和事件级状态查询。
- SIM 预检现在会在上传 fixture 前，检查选定平台数据库中五类 Worker 组的新鲜心跳注册；Worker 就绪等待共用一个超时，环境/数据库不匹配时会尽早失败，而不再为每组分别等待。
- Worker 领取已不可领取的 Task 时，现在仍返回相同的 404 契约并附带具体错误键；Orchestrator 会以 INFO 记录预期的过期领取，其它业务 404 仍记为 WARN。
- Trigger occurrence planner 现在会将超限扫描缩短为完整枚举的连续窗口，并逐步推进覆盖范围，使密集日程能在多轮协调中恢复，而不是持续回滚；租户配置版本更新时会重置旧版本未来覆盖水位。
- Trigger outbox 延迟计时拒绝负单调时长样本并递增 `batch.trigger.outbox.publish.latency.negative_duration`，避免 Micrometer WARN；有效样本仍按原 ok/fail 分布记录。
- 移除无版本、无设计锁的 `PUT /api/console/workflow-definitions/{id}` 图写入口，统一由 `/full` 执行必填版本 CAS、设计锁校验与版本快照；同步移除未使用的前端调用和不带版本的 Mapper 更新 SQL。
- Workflow 全量保存强制提交并比较 `expectedVersion`，缺失版本在写入前拒绝；定义/调度 SQL 使用具名参数并保留版本、租户和受管 Job 归属。补齐节点跳过原因的 OpenAPI 响应字段及生成契约守护。
- 配对前端修复空根项目类型检查：应用 TS/Vue 与 Vite 配置均显式检查，构建复用入口，增加独立临时项目正反例；本轮暴露的源码类型错误继续阻断联合验收。

- 作业依赖等待对缺失、损坏和旧字符串快照失败关闭，保留原快照并记录阻断原因，避免被当作无依赖放行或阻断同租户后续等待；前一业务日恢复只使用已冻结日期。
- 资产分区 Mapper 直接返回持久化 `resultVersionId`，服务按稳定版本身份核对物化快照；移除资产快照和 Workflow 定义的旧构造入口。
- Java 文本块与结构化字符串门禁覆盖未跟踪的新源码，并跳过工作区已删除路径；保留显式文件/基线范围和原检查规则，补充临时 Git 仓正反例。

- SIM SFTP 新库夹具只接受消费者使用的 `sftp_user` / `sftp_remote_directory`，移除旧字段转换；准备脚本锁定三个自有通道，字段不合法、通道缺失或部分更新时整批回滚。

- 资产 ID 共享缓存只在数据库事务成功提交后发布，防止并发 REPORT 引用尚未提交或已经回滚的资产主键而触发外键错误；提交后仍复用缓存。

- 分发健康恢复统一使用类型属性的六类默认探针，移除 YAML 只探测 NAS/OSS 的覆盖，修复 API 失败后永久 WAITING；SIM 明确 HEAD/GET 健康与 POST 业务响应，真实校验 HTTP 500、补偿及重复运行。

- SIM 的 23/25 场景由 harness 统一管理初始/恢复重启，独立场景恢复时等待健康；checkpoint 夹具比较并恢复自有 chunk 参数，保留其它请求进度。新增显式阶段复验入口和失败路径守护。
- Workflow 结果聚合在 CAS 未命中时核对持久状态；相同终态和人工暂停不再误报取消竞争，真正状态冲突继续告警，终态事件仍只随成功状态变更写入。

- 启动 WARN 审计支持同日志上下文约束；Atomic 固定隔离提醒仅对明确的本地 SIM 识别，空白名单放行改为需处理，保留未知告警和生产上下文阻断。

- 失败运行的自动重试仅消耗实例冻结预算；NONE、零预算或预算耗尽后的死信不再开启第二轮自动执行。重排队与 CLAIM 增加父 Job/Workflow 终态围栏，暂停仍允许在途 CLAIM 并保留待派发重试。
- 死信重放的业务复位失败时回滚 task/partition/step/Outbox，保留重放失败次数和原因；到期重试失败的状态更新按实际回滚后的 WAITING 前态执行。
- SIM Stage 5b 的故障渠道正常、失败或信号退出后停用，下一轮初始化也回收带场景所有权的残留配置；健康审计仅统计仍启用且未删除的渠道，保留原故障历史。

- 连续性核对按冻结图显式声明的数据关系、已持久化执行身份和相同范围/单位比较，保留可比差异告警并单独记录未验证；计数拒绝非法数值，重试事件独立去重，日志在提交后记录。
- 批次清单开关关闭后仍保留协议文件归属，避免 `.batch.json` 被当成普通输入误登记或反复报业务日缺失。
- SIM SFTP 通道以受控容器公钥生成共享 `known_hosts` 并启用严格主机校验；公钥读取/发布失败保留原配置，清理本次创建的临时文件。
- 本地 Java 判空、可读性及抑制项检查覆盖未跟踪的新源码；判空检查不再因同一行已有工具调用而漏掉其它原生判断。补齐临时 Git 仓回归用例并接入现有 PR/Full 自测组；严格规则和基线保持原范围。
- Worker 私有临时目录清理补齐导入阶段文件；导入期间保持跨阶段文件锁，成功统一删除，失败保留取证并释放锁；数据已删的过期孤立锁仅在未被持有时回收，避免超长运行误删及成功/崩溃路径残留。
- 生产抑制项扫描在重构删除旧 Java/YAML 文件时按工作区存在性枚举，修复读取已删除索引路径的崩溃；继续扫描未跟踪新文件并拦截未登记抑制，不扩大规则或基线。
- Worker 在单条或批量 CLAIM 请求发出前记录单调时钟，并以该时刻登记成功租约；远程调用或 JVM 暂停已超过续租窗口时，执行前同步确认 Orchestrator 所有权，避免把过期租约当成新鲜租约启动业务。
- 分区取消在一个事务内结束待执行 task、更新分区与 Job 实例的取消计数，并推进 Job/Workflow 终态；取消与并发 CLAIM 按实例锁和 task CAS 收敛。归档保留取消/终止分区计数。
- 已关闭业务日的普通 Trigger API 请求返回 409 状态冲突，并保持请求/Outbox 零写入；SIM 审批改用真实 Quartz callback 保存的原请求，压力日期与历史结算隔离，逐项核对同租户 Workflow/Job 成功。

- 补齐新库未发布迁移的关键字段说明和整合测试的中文展示/命名约定，保持测试断言与夹具不变。

- SIM misfire 审批从原待办关联读取业务 requestId，并按同一业务标识回查，避免将数字外键误传为 API 请求标识导致 404；完整停用后恢复仍须满足原快照和最终业务成功断言。

- 普通定时接纳与首次 readiness 观察在数据库事务内锁定 Workflow 调度状态，关闭暂停与 Quartz 并发注册之间仍接纳新请求的窗口；已有等待、已接纳队列继续按原身份推进。

- Console 按 requestId 审批时，与原 misfire 待办、occurrence 和 Outbox 同事务推进，避免停用恢复消费先于待办批准而误拒绝；并发驳回时不再发布补跑消息。

- Workflow JOB 节点继承 Root 冻结的数据区间，避免审批/恢复后的 Worker 回退到整日处理；非 Quartz 补跑的触发事件时刻与数据区间保持分离。真实 misfire SIM 同时校验 Root/Job 区间、业务日和 Workflow 版本。

- 人工补跑审批与失败 BatchDay 恢复复用首次完整启动快照，保留原业务日、数据区间和参数；快照缺失、损坏或身份不符时拒绝。Workflow 完整停用后，已有 misfire 通过持久来源与精确请求关联继续恢复，人工策略仍要求原审批通过，不开放普通 CATCH_UP 绕过。
- SIM checkpoint 状态查询限定 JOB 节点，避免选中控制节点的空关联而错报等待超时。

- 定时 occurrence 物化按最多 1000 个 fire 批写，保留原有版本/终态 fencing 和 coverage 原子事务；启动对账与周期对账使用同名 ShedLock，避免高频日程初始化时重复物化占满数据库连接池。

- Root 初始派发在 RUNNING CAS 未命中时停止，避免终止抢占后继续创建子 Job；持久等待在根已建立、完成标记前中断时可按同一根运行幂等补收尾。
- Workflow 同业务日活跃运行占位时，独立请求进入 Orchestrator 持久等待，提交后确认 Kafka 消息；按租户和可用槽位恢复，避免丢补跑或因一个 Workflow 暂停整个消息分区。人工审批在真正创建根运行前保持 PROCESSING。调度暂停后已登记的 misfire 继续恢复；恢复任务使用独立定义身份，不开启主日程，也不借用同编码新定义的参数。
- SIM Stage 24 使用管理 API 对真实 Quartz fire 制造暂停/恢复，移除任意时间回拨与自动 pending fallback；补跑需验证 occurrence、WorkflowRun、JobInstance 的最终结果。
- TriggerReconciler 对账不再调用会修改持久启用状态的注册入口；它在调度门面锁内重载最新 Workflow 状态，禁用时移除 Quartz identity，启用时仅同步 Quartz，避免暂停与旧对账快照竞争后意外重新启用日程。

- Trigger 结算围栏 Mapper 在无业务日/日历条件时仍返回显式无操作值，避免零行结果映射到 primitive `long` 并让有效请求异常；受管单作业 Workflow 在 Job 未指定业务日历时使用并验证租户 `default_calendar`，缺失时在配置创建阶段失败关闭。新增 PostgreSQL Mapper 与 Provisioner 回归覆盖。
- Orchestrator 的 Worker 取消结果识别已与 Worker 实际返回码 `WORKER_EXECUTION_CANCELLED` 对齐；仅在平台已请求取消时将任务落为 CANCELLED，并跳过自动重试。结果/重试服务定向测试 35/35、Orchestrator 全量 1875/1875 通过；跨 Worker HTTP/Kafka 报告到无重派的真实闭环仍待验收。
- JOB_RERUN 在创建新 WorkflowRun 时按 `USE_ORIGINAL_CONFIG`、`USE_LATEST_CONFIG` 或 `USE_SPECIFIED_VERSION` 固定完整 JobDefinition 快照；原配置和指定版本只从不可变 WorkflowRun 快照读取，不存在、身份不符或版本无效时拒绝启动。历史版本查询覆盖单作业和组合 Workflow，按租户隔离并使用最新优先索引；定向解析与 PostgreSQL 查询、Orchestrator 全量 1875 项及空库迁移验证通过。实际 CLAIM 有效配置的端到端断言仍待完成。
- Export STORE 在 REGISTER 后的完整链路重试时，先按租户/存储桶/对象路径读取既有 file_record，并校验对象实际 SHA-256 与登记摘要一致；吻合则复用原对象、摘要和加密元数据，不再随机重加密或覆盖；缺失/漂移则失败关闭。复用/漂移单测及 PostgreSQL 17.11 + MinIO 真实 AES-GCM、对象/记录摘要和安全元数据复验通过；全阶段 worker 重派演练仍待完成。
- PROCESS staging 孤儿清理按 `(tableoid, ctid)` 定位物理行并使用 `FOR UPDATE SKIP LOCKED`，避免跨分区误删及与分区迁移并发时清理报错；WAP COMMIT 校验发布行数与 COMPUTE 快照一致，不一致时事务失败并回滚，避免 staging 在校验后丢失仍报告成功。PostgreSQL 17.11 Testcontainers 回归覆盖同 `ctid`、批量上限、行锁下分区迁移和提交前 staging 丢失；PROCESS 阶段记录将提交失败写入步骤失败结果。
- Trigger 外部 EVENT 来源接入租户 API Key：key_name 绑定 externalEventSource，要求 tenant 匹配及显式 `trigger.event` scope；仅授权 EVENT 启动和按 source 隔离的状态查询。租户 API Key 的验证缓存 1 分钟，撤销生效最长延迟 1 分钟。
- Quartz 恢复扫描现在跳过仍处于 `PENDING` 的人工 misfire 审批 occurrence，避免审批等待被重新注册为 Quartz 执行；已持久化但未绑定审批记录的 misfire 仍由原恢复扫描接管。PostgreSQL 回归先复现待审批 occurrence 被错误选中，再验证恢复项筛选和 100 条分页推进。
- 已结算批量日收到普通 Workflow 定时 fire 时，数据库以专用 SQLSTATE 拒绝准入；Trigger 回滚原请求事务后将原 fire occurrence 记为 `CLOSED_DAY_REJECTED`，保存显式要求 `CATCH_UP`/`RERUN` 的原因，不重开批量日且不生成 TriggerRequest。同一 fire 后续显式 `CATCH_UP` 可被接受，occurrence 转为 `REQUESTED` 并保留先前拒绝原因；同一已结算日的 Workflow `RERUN` 请求也能通过准入且不改写原计划 fire 的 occurrence。V273 加入状态/理由约束；TriggerServiceIntegrationTest 在 PostgreSQL 17.11 空库上 8/8 通过。批量日详情新增 occurrence 查询与中英文状态/拒绝原因展示，并由真实 PostgreSQL mapper、服务映射和前端组件测试覆盖；完整 Console HTTP/权限操作及节点执行链仍需联合验收。
- 关闭日 occurrence 的 Console catch-up 现按业务日历策略处理：AUTO 同事务接纳 Trigger/outbox；MANUAL_APPROVAL 建立关联 `trigger_misfire_pending` 的 ACCEPTED 请求并将 occurrence 持久化为 `MISFIRE_PENDING`，Console 提交集中审批，审批单幂等身份绑定到该 request；NONE 明确拒绝。V274 增加 request/operator/reason 审计；PostgreSQL 集成覆盖两策略并发重复请求，Console 服务单测覆盖 MANUAL_APPROVAL 审批提交。真实认证 HTTP、审批通过后完整 Trigger→WorkflowRun→JOB 节点执行仍待验收。
- `TriggerStaleAcceptedWithoutInstance` 指标现在排除关联 PENDING misfire/catch-up 审批的请求，避免把审批等待报成 outbox/Kafka 投递故障；PostgreSQL `TriggerRequestMapperIntegrationTest#shouldMeasureStaleAcceptedRequestsAndRecentLaunchLatency` 验证审批待办不计入 stale 计数，SLO 目录、Trigger runbook 与 Docker/Helm Prometheus 告警说明同步。
- `SETTLE_EMPTY` 现在会在同一业务日结算围栏内锁定并取消未履行的文件/事件预期，再与批量日终态、双份审计原子提交；审计记录每项被豁免义务的 Workflow、来源、原状态、预期/截止时间及请求关联。预期项相关服务在事务内先设置租户 GUC，以满足强制 RLS；新增 PostgreSQL 用例以 `NOSUPERUSER NOBYPASSRLS` 角色验证跨租更新被屏蔽及人工结算成功。
- 批量日 `REOPEN` 现在与结算共享业务日围栏；重开时为已豁免义务建立新的 occurrence generation，保留旧 `CANCELLED` 事实，并在审计中记录重建数量。人工 SKIP/REOPEN 必须提供理由。generation 与唯一身份直接定义在未发布的 V263 建表迁移中；PostgreSQL 17.11 空库迁移并以强制 RLS 角色验证 SETTLE_EMPTY 后 REOPEN 可生成新一代义务；R26 的跨日依赖、迟到接纳和节点派发时序仍待联合验收。
- 作业依赖门禁及等待恢复现在在访问强制 RLS 的 `job_dependency`、`job_dependency_waiting_launch` 前于事务内设置租户 GUC；reconciler 通过事务化服务执行等待扫描、释放/取消、检查记录和超时告警 claim。新增 `NOSUPERUSER NOBYPASSRLS` PostgreSQL 负例先复现“看不到依赖而错误放行”，再验证请求进入 WAITING；`TriggerTypeLaunchIntegrationTest` 21/21、相关依赖服务与指标单测 58/58 通过。完整 SIM/部署验收仍待完成。
- 日切补齐控制与审计强制 RLS 路径现于控制服务、预览服务和开日调度器事务开始时写入租户 GUC。`NOSUPERUSER NOBYPASSRLS` PostgreSQL 用例先复现暂停控制 insert 被 RLS 拒绝，再验证变更、当前状态读取、预览与审计读取；批日调度器、控制服务和预览服务单测 22/22，多日历集成类 18/18 通过。R20 其它扫描/关联隔离验收仍开放。
- 跨日历依赖等待治理服务的 list 与人工 RELEASE/SKIP 请求也在强制 RLS 表读写前设置事务级租户 GUC；隔离事务的非绕过角色用例复现无 GUC 时列表错误为空，再验证列表、人工动作及审计字段回读。包含该路径的多日历 PostgreSQL 集成类复跑 18/18 通过。
- 开日物化文件组/外部事件预期时过滤已禁用 Workflow，防止不可接纳的未来预期永久阻塞结算；Workflow 禁用前已生成的义务保留，单独暂停定时日程不影响文件/事件预期。
- 文件 bundle manifest 根目标改为 `workflowCode`，扫描器以 `bundleWorkflowCode` 传递；到达启动器按租户解析启用 Workflow 并用其日历/ID写预期与根运行。旧 `jobCode` manifest 不再触发 bundle 语义，配置指南、SIM fixture 查询和每日文件预期 runbook 同步到 Workflow 身份。
- Quartz misfire 恢复先将已计划 occurrence 持久转为 `MISFIRE_PENDING`，再异步注册以 Workflow ID + fireTime 确定身份的一次性恢复 Trigger；Trigger 对账可从持久台账重建尚未写入 Quartz JobStore 的恢复项。真实 Quartz/PostgreSQL 集成验证 callback，并在关闭原 Scheduler 后由新 Scheduler 实例恢复 pending；完整应用进程 kill/restart 和积压容量验收仍未完成。
- 配置包导出、模板、字段指南和 SIM 样本统一移除租户 Pipeline 工作表；导入仍明确拒绝旧 Pipeline sheet。当前四份配置包 fixture、生成/追加脚本及漂移检查已同步到 10-sheet 契约，并以 Console HTTP 集成验证上传通过与旧表拒绝。
- 空日结算现在拒绝使用租户配置变更前的 Quartz coverage；planner 按数据库配置更新时间重扫，并恢复重启用后与当前计划重合的未来 SUPERSEDED fire。V272 将 supersede/reactivate 转换写入不可变历史；禁用/再启用定向 PostgreSQL 场景确认该 fire 重新阻塞结算。首次开日、节假日 SKIP 义务及完整日历演练仍未验收。
- BatchDay catch-up 找不到受管单作业 Workflow 时，V269 门禁仍拒绝可执行的 Job 根请求，但允许保存已终态 `CATCH_UP/REJECTED` 审计记录，使恢复扫描不会因拒绝审计本身失败；空库 PostgreSQL 用例覆盖两种路径。压测结果/清理 SQL 也改为从 WorkflowRun 与节点执行关系定位 Job。
- Trigger occurrence 对账现在对旧扫描实施版本 fencing：较旧 Workflow schedule version 不能覆盖较新版本的未来 `PLANNED` occurrence，也不能回退连续 coverage 水位；同版本 coverage 只向已验证区间前进。新增 PostgreSQL 空库集成回归复现旧扫描迟到并验证水位保持。
- Workflow 跨日依赖的解析输入现在携带精确 `resultVersionId`；`SPECIFIC_VERSION` 改为精确版本查询，不受最近 50 条列表分页影响；未知策略、无效指定版本和非法 scope 在定义校验及运行期失败关闭。Orchestrator 定向测试及真实 PostgreSQL 集成覆盖见整合验证矩阵；真实业务消费者按版本 ID 读取及跨服务恢复仍未验收。
- Console 已完成的重复幂等请求仍返回 409 `CONFLICT`，并增加 `idempotentReplay: true` 机器标记；进行中的重复请求不带该标记，避免前端将业务 `STATE_CONFLICT` 静默处理。
- 自助补偿 API 仅接受 JOB 实例补偿；必填目标实例号经租户、作业和业务日复核，审批目标与 payload 均绑定解析出的 job_instance ID。未知、错配及缺少目标的请求在创建审批前拒绝；服务/Controller 回归测试 8/8。
- Job 编辑现将 `executionHandler`、`paramSchema`、`priority` 从请求映射到维护参数及 SQL；未提交字段继续保留已存值。Priority 输入、创建/更新 DTO、OpenAPI 和数据库约束统一为 1–9。Service 回归、请求校验及 PostgreSQL Mapper 实测覆盖持久化。
- Console 标签行保存/删除固定使用查询结果对应的租户与资源身份；筛选草稿变化时禁用旧行操作，且丢弃迟到查询响应，避免对另一资源执行写入。身份单测、相关前端定向测试、ESLint 与 typecheck 通过；真实组件/浏览器回读验收仍待完成。
- Trigger 顶层 launch API 现只接受 `workflowDefinitionId`，移除公开请求 DTO 的 `jobCode`；受理前按租户核验 Workflow 存在且启用，Workflow 根运行中的 Job 由 Orchestrator 内部节点派发创建。Workflow-only 服务/适配及 Mapper 租户条件测试 30/30 通过；`validate-seed-scenarios.sh` 增加旧 Job 根目标拒绝断言、根 WorkflowRun 派生清理，常规 `05-load.sh` 使用本地模拟 fixture provisioner 为静态 Job 准备受管 Workflow 后再触发。
- Console 已移除 `/api/console/jobs/trigger` 与 `/batch-trigger`，不再以 JobCode 暴露顶层触发契约；作业列表与详情改用返回的 `defaultWorkflowDefinitionId` 调用 Workflow trigger，并在缺少受管 Workflow 时失败关闭。OpenAPI/生成类型和 API 协议同步；Console Controller/Workflow trigger 定向后端测试 3/3、前端 Job API 单测 21/21、typecheck、API 漂移和目标 ESLint 通过。Docker 不可用，真实 HTTP 集成链路未运行。
- Console Trigger 运维入口和 Trigger 内部管理代理统一改用 `workflowCode`，并调用 Workflow 管理路由；同步更新 OpenAPI、协议文档、生成类型和真实 JSON 代理契约测试，防止 Workflow 状态被解析为缺失的 Job 字段。定向后端测试 17/17、前端全量单测 891/891、类型/i18n/API 漂移、目标 ESLint、生产构建和 OpenAPI 412 路由检查通过；Docker 不可用，真实 HTTP 集成未运行。
- 待审批补跑列表改为按 Workflow 根身份筛选和展示 `workflowCode`，查询从 `trigger_request.workflow_definition_id` 关联 Workflow 定义；同步桌面/移动 Console、OpenAPI 和生成类型，避免新 Workflow-only TriggerRequest 依赖已退出的 `job_code`。PostgreSQL 17.11 空库集成测试 4/4、前端 typecheck/i18n/API 漂移通过；其余 Console 全量集成问题仍在处理。
- 配置包 Excel 创建 Job 时显式使用默认 `priority=5`；稀疏合并更新回填数据库中已有的 priority，避免省略该字段时写入 NULL。全量 `ConsoleTenantConfigPackageExcelUploadIntegrationTest` 在 PostgreSQL 17.11 空库 32/32 通过，覆盖新建默认值及 priority=8 经合并导入保持不变。
- 修正整合后 Console 验收夹具：Job 定义清理先删除其托管单作业 Workflow 的定义子项；Pipeline 历史查询测试构造系统 Pipeline 身份；租户 Pipeline 写入测试验证退役契约为 409 且零写入。认证旅程、Job 定义、Pipeline 退役/查询三个集成类合计 23/23 通过。
- Trigger 运维手册改为全新数据库与 Workflow-only 操作说明，移除 JobCode 管理兼容描述，修正 CLI 命令和 misfire 验证对象；管理脚本四种 Workflow 操作的 dry-run URL 均已核对。
- Quartz 状态读取只识别 `tenant:WF:workflowCode`，忽略旧 JobCode/格式错误 identity，避免运维状态查询重新接纳旧调度身份。`TriggerStatusQueryFacadeTest` 3/3，Trigger Spotless 检查通过。
- SDK 自托管 E2E fixture 现在显式 provision 单作业 Workflow，以 `workflowDefinitionId` launch 并通过 root request 关联 Job 实例；settlement 等待与清理 SQL 同步更新，避免读取 Job 根关联字段。ShellCheck、SQL 边界和安全静态门禁通过；真实 SDK E2E 未运行。
- 将剩余仿真 launch 收敛到受管 Workflow ID，并把 stage2/3/4、stage6、原子负载、文件束与 SDK Worker 的结果查询统一沿 `workflow_run → workflow_node_run → job_instance` 读取；待 Docker 可用后执行真实脚本验收。
- 新整合契约不再把 `WORKFLOW` 暴露为可创建的 Job 类型；同步更新 Console OpenAPI 描述和生成类型，Job 类型选项只读后端枚举。
- Workflow/每日连续性整合中，文件束到达已从 Job 根请求切换为显式受管 Workflow 根请求，并校验 Job 与 Workflow 日历一致；Trigger Mapper 保存 Workflow 目标。根 JOB 节点首次派发增加唯一键原子认领，修复真实 PostgreSQL 并发扫描导致重复 node_run/实例派发；单测 17/17、并发集成 1/1 通过。完整 Quartz 恢复、预期身份统一和部署验收仍未完成。
- Pipeline 阶段改为系统按 Job 类型提供；租户 Pipeline 定义退出配置包、跨租户复制、ConfigSync、Job Bundle 与公开初始化契约，Batch Init 明确拒绝旧 Pipeline 载荷。配置包旧 Pipeline sheet 现在只作为拒绝项，不再做参数转换；预览不返回迁移提示，apply 不返回 Pipeline 写入计数。后端对应回归测试通过；空日结算、Quartz 恢复和整个新运行模型仍未完成验收。
- Workflow 新 schema 移除 `workflow_node.related_pipeline_code` 并禁止 `FILE_STEP`，Console pipeline-code 查询入口、Excel/配置包字段和前端设计器旧节点一并退出；`FILE_STEP` 写入明确失败，前端图解码 fail-closed。更新有效测试种子与默认 Excel 样本为 TASK。全仓 Spotless、相关编译、107 项后端定向测试（1 项空库迁移因 Docker 不可用而跳过）、前端 890 项单测与生产构建通过；真实 Playwright 旅程因登录服务 fetch failed 未执行。
- 安全加固：入站 MANIFEST 仅接受 SHA-256，拒绝 MD5 sidecar，避免弱摘要文件被标记为 `integrityState=VERIFIED`；SDK 与自托管示例锁定 rustls 0.23.46，修复当前 RustSec 公告中的漏洞。
- 固化四类系统 Pipeline 的完整阶段顺序为公共类型契约；Worker 在执行前校验 Job 类型对应的系统定义，业务参数保留在 Job 参数快照中。
- Workflow/每日连续性整合的目标部署边界调整为全新数据库、停旧启新；旧 API、旧运行/消息和旧库升级兼容退出计划。当前实现仍在收敛，新增 ADR-048 与回归/切换验收矩阵；此条记录计划范围，未宣称入口退役或新库验收已完成。

- Workflow 与每日连续性进入隔离整合实施：scheduled occurrence、coverage 和 misfire pending 改由 Workflow ID 归属，Quartz identity 去除 Job fallback；空库迁移至 V267 可重复 validate/migrate，相关 Trigger 与 BatchDay PostgreSQL 测试通过。复合图/并发恢复、Workflow Run 终态结算、文件/事件预期和配对前端仍未联合验收，不能作为可部署切换版本；具体状态见整合计划与 ADR-048。
- Workflow/每日结算围栏开始落地：空库新增按 tenant/calendar/bizDate 的 PostgreSQL 事务围栏，批日开日、结算和预期/Trigger 写入串行化；子 Job Trigger 继承根 Workflow 冻结日历，结算后普通触发失败关闭，仅显式 CATCH_UP/RERUN 可继续。删除未发布的 V238/V242/V243/V244 旧数据转换及其专属迁移测试。PostgreSQL 定向测试通过；R11 两种提交顺序、全量复合图/Quartz 恢复及配对 Console 验收仍未完成。
- R11 PostgreSQL 并发回归改为第一种顺序真实调用 `BatchDaySettleScheduler`；该用例发现 `countUnlaunchedForCalendarBizDate` 仍通过 `job_code` 关联 Job，导致只有 Workflow ID 的 ACCEPTED 请求被空日结算遗漏。查询现按 Workflow ID + 日历识别未接纳请求，并断言结算保留 CUTOFF、请求阻塞计数为 1；第二种顺序验证终态提交后普通 Trigger 被数据库拒绝。Workflow settlement/request PostgreSQL 定向用例通过；完整结算矩阵仍待验收。

- FAILED 批量日补跑增加有界恢复扫描；若失败状态已提交但进程未执行 afterCommit 派发，后续扫描会按幂等请求恢复 AUTO 补跑，或补登记缺失的人工审批请求。
- 新增可重复文件组预期规则配置 `/internal/file-group-expectations`；批量日首次成功开启时，按日历工作日、业务日和时区生成 EXPECTED occurrence，与日实例插入同事务提交，重复开启依赖唯一身份幂等。
- 新增租户隔离的可重复外部事件预期规则 `/internal/event-expectations`；批量日开启时按日历业务日生成 `EXTERNAL_EVENT` 预期项，只有关联的 EVENT `trigger_request` 已到 LAUNCHED 才能标记满足，未到事件持续阻止空日自动结算。
- Workflow FILE_ARRIVAL 在要求完整到达组时，必须绑定显式或 Workflow 业务日；防止每日复用的 fileGroupCode 将其他业务日的完整组误命中。旧式非完整组配置保持原时间窗口行为。
- Console 跨日历依赖等待面板现可筛选 WAITING/OPENED/SKIPPED 并查看最终状态与人工动作审计；只有 WAITING 记录显示可执行处置。
- 历史批量日缺口只读盘点现在按租户日历时区和 cutoff 计算当前业务日，并拒绝包含未来业务日的查询，避免把尚未到期的日期报告成历史缺口。
- 澄清运维边界：现有 `BatchDayReplay` 审批用于已有实例重跑，`SCHEDULE_PLAN` 仍为 dry-run；缺失批量日的生产历史回填流程尚未交付。
- 外部 EVENT Trigger 请求可携带 `externalEventCode`，值会持久化至 `trigger_request`；满足回调按该编码校验，避免同一作业同一业务日的不同事件互相结清预期项。
- 下游实例的 `params_snapshot` 记录依赖 gate 启动时观察到的每个上游对齐规则、业务日、business key、版本号、result_version id 和来源实例，便于审计 gate 判定依据；这不证明 Worker 后续实际读取了同一版本。旧实例仍无该字段时按历史快照兼容读取。旧版物化分区若没有可回溯的 result_version 行，result_version id 允许为空并保留分区版本及来源实例信息。该快照现随 Worker CLAIM 配置透传至内置 Worker core 的执行属性，供自定义步骤读取；实际消费版本 lineage 仍需 Worker 按 ID 读取或校验，并覆盖自托管 SDK 与外部业务表消费者。
- 新增 `/internal/expected-work-occurrences/file-groups` 幂等登记入口，供上游文件清单在到达前声明租户/日历/作业/业务日预期；校验作业日历一致及 expected/due 时间顺序，bundle arrival 后续复用同一来源身份推进状态。
- 文件束到达按 job_definition 的日历身份登记/推进逐日预期项；组已完整时进入 ARRIVED，Trigger 请求确认已接受或依赖等待时转 SATISFIED，失败仍保留可重试的未完成项。扫描按租户绑定 RLS 上下文。
- 新增租户隔离的逐日事件/文件预期台账，并将仍处于 EXPECTED/ARRIVED 的预期项纳入自动空日结算阻塞；稳定来源身份重复写入幂等，状态推进使用条件更新。
- 作业依赖 `PREVIOUS_BIZ_DAY` 接入下游作业业务日历，跳过 `HOLIDAY` 并尊重 `WORKDAY_OVERRIDE`；新等待记录固定解析后的上游业务日，日历后续调整不改变既有等待请求，未配置日历的作业保留周一至周五兼容语义。
- Trigger 新增补跑审批状态对账：统一审批批准/拒绝和 pending 到期会幂等收敛 misfire pending、仍未启动的 trigger_request 与 scheduled occurrence；已批准请求不会被过期任务误关闭，多实例由 ShedLock 协调。
- Quartz 预期触发台账新增调度版本连续覆盖区间：occurrence 全窗口物化与水位同事务提交，重启后从最后成功位置续扫；超出枚举容量时保留旧水位。`SETTLE_EMPTY` 要求每个当前启用的定时定义覆盖该业务日 cutoff，缺失/过期/旧版本/区间缺口时失败关闭。
- CUTOFF 且没有任何预期作业的业务日现在自动结算并写审计；未派发/计划中的定时工作、依赖等待或未覆盖调度仍阻止结算。EVENT/MIXED 的逐日预期由明确配置的事件规则建立；未配置事件预期来源的启用 EVENT/MIXED 作业仍失败关闭，避免把“暂时没收到事件”误判为空日。
- `SETTLE_EMPTY` 现在会在仍存在同租户、同日历、同业务日且状态为 PENDING/PROCESSING/ACCEPTED/WAITING 的 Trigger 请求时拒绝结算；这封住了 `job_instance` 创建前的异步请求窗口。结算后的请求仍由迟到到达/追赶流程处理。
- WAIT 与 START/GATEWAY 推进完整个 DAG 后，同步 CAS 收口无 Worker WorkflowRun 与无 partition/task 的 JobInstance，并在同一事务写入终态 outbox；子 Workflow 会复用父虚拟任务 outcome 主链推进上游；新增完整 Orchestrator 上下文重建后的 FILE_ARRIVAL WAIT 恢复集成验证。
- 子作业以 `SUCCESS_DRY_RUN` 终态完成时，父 Workflow 的虚拟任务按成功分支推进，与正式成功保持一致。
- Workflow DAG 现为 WAIT 节点创建独立 RUNNING sensor 记录，不再将其误当普通 TASK 建立 READY 节点和 worker 派发任务；只有 WAIT 结果命中后才推进下游节点。
- WAIT sensor 在逐节点事务中增加到期状态条件认领，避免候选扫描锁释放后、ShedLock 租期重叠时同一节点被重复探测；下游派发失败仍回滚状态推进并保留重试入口。
- 明确作业依赖的结果版本语义：仅最新 EFFECTIVE 结果可放行等待中的下游；更高 PENDING 版本阻断旧 EFFECTIVE，已启动下游不会因上游修订自动级联重跑，修订消费需显式 replay；增加真实 PostgreSQL 回归用例并修正文档中的旧依赖能力描述。
- 增加 Trigger Reconciler 启动就绪事件的立即注册回归测试，并更新批量日能力设计文档，区分 Quartz 调度初始化、日切批量日初始化和事件触发时作业实例初始化。
- 为 Trigger ACCEPTED 请求新增全局陈旧无实例数/最老年龄及请求到实例创建 p95 指标，并配置 Prometheus 告警和排查手册；指标 SQL 通过真实 PostgreSQL 集成用例验证，不暴露租户或请求标签。
- 修正文件到达组等待 gauge 从限量候选页计数造成的积压低估；改为 PostgreSQL 按租户、业务日、组编码及组策略聚合所有 WAITING 组，并发布最老等待时长，采样失败保留前值且不增加高基数标签。
- 为作业依赖等待补充无租户/作业标签的聚合等待数与最老等待时长指标，并新增 24 小时告警和 SLO 映射；采样失败保留最近完整快照。
- 作业依赖边新增 `SAME_DAY` / `PREVIOUS_BIZ_DAY` 对齐策略，存量边默认同日；Orchestrator 持久化等待快照保留对齐方式并按目标上游业务日恢复，Trigger scheduled 快速 gate 仅预检同日边。前日算法当前按周一至周五计算，不感知假日；依赖只认可最新 EFFECTIVE 版本，更高 PENDING 会阻塞等待中的下游，已启动下游需显式 replay 才消费上游修订。
- Console 作业定义 API、表单和租户配置包 Excel 现在可逐依赖边配置日期对齐；旧 API 字段、旧 Excel 模板仍按 SAME_DAY 解释，导出保留各边规则。作业表单的快捷选择应用于全部已列上游，已有混合规则编辑时可保留各边设置。
- 修复仅有跨日依赖的 scheduled 作业被 Trigger 旧标量 fallback 误判为同日依赖的问题；规范化关系存在但没有 SAME_DAY 边时，Trigger 显式收到空集合，跨日检查由 Orchestrator 执行。
- 作业依赖等待进入 Orchestrator 主启动链：任何入口的 LaunchRequest 在上游结果未生效时持久化原业务日、依赖快照和完整请求，租户 RLS reconciler 在全部上游对齐日期的 EFFECTIVE 结果就绪后重放；超时仅告警，不会自动取消等待。
- 作业依赖等待现在可在 Console 作业定义页按租户只读查询；列表仅暴露请求/业务日/上游快照/阻塞原因和告警时间，不返回完整 LaunchRequest 参数，也不开放人工绕过依赖。
- 作业依赖等待超过 `BATCH_JOB_DEPENDENCY_WAIT_TIMEOUT_SECONDS` 后通过 CAS 发出一次告警并在 Console 展示告警时间；等待持续保留并自动恢复，不改变依赖执行语义。
- 日切 catch-up 新增无租户标签的积压日数、落后日历数 gauge 和失败扫描计数，并加入 Docker/Helm Prometheus backlog/failure 告警；仅完整扫描成功后更新积压快照。
- Console 批量日页新增租户/日历作用域的只读 catch-up 预览，列出下一轮原始日期范围及游标锚点边界，不执行建日或游标写入。
- Console 批量日 catch-up 预览支持管理员按租户日历暂停/恢复自动历史补齐；操作原因与认证操作者写入 RLS 审计事件，暂停保留游标和 backlog 指标，不影响当前业务日开启及依赖等待恢复。
- Console 批量日 catch-up 抽屉新增最多 90 天的历史缺口候选查询；仅列出无批量日记录日期，明确提示历史日历规则需人工核对，不自动补建。
- 文件到达组新增集成覆盖：缺少成员时保持等待，成员乱序迟到且未收到通知后，下一轮持久化数据库扫描仍能触发完整组；数据库扫描继续作为到达事实来源。
- Workflow WAIT sensor 命中后的下游派发异常不再被吞掉；异常向上冒泡使单节点探测事务回滚，保留 WAIT 节点供后续持久化扫描重试，避免出现 WAIT 已成功但无下游激活记录。
- PostgreSQL 集成用例验证 WAIT 探测事务抛出下游异常后数据库写入回滚，节点仍是 RUNNING 且仍会被下一轮 due 扫描选中。
- PostgreSQL 故障注入验证 WAIT 命中后真实 TASK 的 outbox 写入失败时，WAIT、下游 node_run、task、partition 与 outbox 同事务回滚；移除故障后重新探测只创建一套下游任务和派发事件。
- Console「批量日」新增跨日历依赖等待列表及管理员 RELEASE/SKIP 请求；操作者从认证上下文读取、原因必填，决策持久化后由 Orchestrator 调度器执行并写批量日审计；同步 OpenAPI、生成类型和前端操作入口。
- 批量日日切现在遵循日历假日、工作日覆盖及 SKIP/NEXT_WORKDAY/PREV_WORKDAY 规则；SKIP 假日持久化为带审计的终态，避免和定时触发器的跳过语义不一致。
- 空工作日不再只能永久停留在 CUTOFF：管理员可显式确认 `SETTLE_EMPTY`；Orchestrator 事务内要求已 cutoff、作业实例总数仍为零、无跨日历或作业依赖等待且原因必填，操作人从 Console 认证上下文读取并写双份审计。不会把零实例自动推断为成功，预期作业清单仍是自动空日结算的后续缺口。
- 跨日历依赖阻塞现在持久化原业务日、截止/SLA 与时区策略快照；上游晚结算或 Orchestrator 重启后由开启调度器重检并补开原业务日。
- 日切扫描现在通过独立原始日期游标按序补建停机期间缺失日期；避免节假日滚动到未来日期或依赖延迟时卡住，并通过 `BATCH_BATCH_DAY_CATCHUP_DAYS_PER_SCAN` 限制单轮工作量，长时间停机由后续扫描继续推进。
- 跨日历依赖等待记录首次规则快照，并在超过可配置等待阈值时通过 CAS 发出一次去重告警；等待继续保留，修复上游/依赖配置后仍可自动恢复。
- Workflow 文件到达 sensor 现在可按 Workflow 业务日和到达组编码精确匹配；要求完整到达组时仅在组治理状态进入 TRIGGERED 后命中，旧式时间窗口配置保持兼容。
- 修复线上 Sonar 报告中的上传文件名空值路径、AI 图片字节数组 record 值语义、异常凭据脱敏正则冗余分支及 OIDC 注册缓存的并发初始化实现；增加相应回归测试。
- 修复 strict 验证用 HTTP 成功码判断 Console API 探活、导致依赖降级时聚合健康端点 503 被误报为服务未启动的问题；现在要求 Actuator 返回有效 JSON 和状态字段，并覆盖 200、503 与无效响应。
- 修复在线 strict 验证对角色保护的 cron-preview 接口未提供认证、把 401 错误体误报为 Quartz 与 OpenAPI 漂移失败的问题；隔离 CI 栈启用测试专用 bypass 身份并由验证请求显式携带管理员角色，不影响生产认证。
- 明确作业调度类型、定义级触发模式、实例级触发来源、文件事件来源及作业上游依赖的边界；修复作业定义更新时 `triggerMode` 未持久化，补齐调度/触发/依赖服务端分页筛选，并同步 OpenAPI 契约。
- Workflow 节点运行现在持久化跳过原因类别和人工说明；条件未命中、上游失败/跳过与人工跳过在运行详情中可区分。rootless Workflow 的人工跳过现在按冻结图继续派发后继节点。
- 修复无根组合 Workflow 并行 Job 任务载荷缺少节点身份的问题；每个子 Job 任务都携带所属 `workflowNodeCode`，并行节点报告结果不再依赖 Workflow 只有一个活跃节点。
- Workflow Run v2 快照新增冻结 Workflow 业务日历；子 Job 的批次日分组、闸门、迟到判定、SLA 和实例日历快照统一使用该冻结值，旧快照/兼容入口仍回退 Job 日历。
- 受管单作业 Workflow 的固定 JOB 节点现在承载迁入的默认批次窗口，并允许只修改该节点窗口而不开放图结构编辑；组合流程节点窗口可由设计器配置。
- Console 作业手动触发、批量日 AUTO catch-up 与审批后 MANUAL catch-up 均解析 Job 的显式受管单作业 Workflow 并以 Workflow ID 发起；无受管关联时 fail-closed，不再新增独立 Job Launch。scheduler 创建的 Trigger pending catch-up 仍沿用审批请求，但其目标必须已固定为 Workflow ID。
- Compensation 驱动的 BatchDay Replay 现在同样按 Job 显式关联解析受管 Workflow，以 Workflow ID 写 Trigger 并经可信队列 Launch；Replay session ID 固定在 Workflow 执行快照，并传至实际子 Job Instance，确保现有 Job 终态回填可收敛回放条目。
- Workflow 调度暂停状态独立存储于 `workflow_definition.schedule_enabled`；Trigger 管理启停不会再误改 Job 的执行启用状态，Quartz 对账清理也不再反向更改数据库中的权威调度开关。
- Workflow 定义版本快照与冷归档现覆盖 `schedule_enabled`；新 Excel 配置包携带单作业关联编码和 Workflow 调度字段，导入按目标租户解析并复用受管关联，覆盖时使用版本 CAS 并记录快照。
- 修复 Workflow 全量 CAS 更新遗漏写回 `schedule_enabled` 的 Mapper SQL；真实 PostgreSQL 集成测试覆盖定义开关和版本快照读写。
- Worker 不再提供按 `tenantId + jobCode` 查询最新租户 Pipeline 定义的接口；新执行通过系统 Pipeline 目录和 Job 类型决定固定阶段。Pipeline 定义、实例与阶段历史保留；旧队列消息仍可按已固定的 legacy definition ID 读取步骤。Worker core 定向 8 项通过。
- Console 为 Workflow 定时调度增加独立暂停/恢复 API 和列表操作，使用 `schedule_enabled` 而不改 Workflow/Job 执行启用状态；前端展示仅对 CRON/FIXED_RATE 且 SCHEDULED/MIXED 的定义开放此操作。
- 增加 Workflow schedule-only 配置 API：按 expectedVersion CAS 更新调度字段、递增定义版本并在同一事务写入包含原 DAG 的版本快照；不经过全量图保存、不覆盖节点/边。
- JSON 租户初始化/复制中的 Workflow 定义现在携带自身调度配置，并以单作业 Job 编码作为可移植关联；导入按目标租户解析 Job，覆盖更新推进 Workflow 版本并保存定义快照。作业导入后会幂等补齐缺失的受管单作业 Workflow，默认关闭定时调度，避免导入过程中意外注册旧计划。旧 Job 调度字段不再由新导出重复输出；Excel 配置包与旧 Job 调度值的显式转换仍在迁移中。
- Workflow 调度依赖作业在创建和更新前按当前租户校验存在性；受管单作业 Workflow 禁止依赖自身作业，避免无效依赖进入 Trigger 后永久 fail-closed 重试。
- Job 定义更新 API 对 Workflow 所有的旧调度字段增加只读保护；请求变更 schedule type/expression、时区、触发模式、日历或窗口时返回 `STATE_CONFLICT`，历史值保留读取。
- 定时 Workflow Launch 校验 Quartz 捕获的 Workflow 版本与当前定义一致，过期触发失败关闭；已有 Workflow Run 的同请求重放仍保持幂等。
- 新建 Workflow Run 在 PostgreSQL `REPEATABLE READ` 事务内核对 Workflow 版本、校验图并生成图快照；若事务前读到的定义版本已变更则拒绝启动。已有 Run 的重放不再被后来编辑成无效的当前图阻断。
- Quartz 以 `tenantId:WF:workflowCode` 注册调度，支持无合成 JobCode 的组合 Workflow；Launch 传递 Workflow ID/version，新增 WorkflowCode 管理 API，旧 JobCode 接口通过显式关联兼容并清理旧 Quartz identity。
- 退役 Job 对租户 Pipeline 定义的手工关联，以及租户 Pipeline 定义的 POST/PUT/启停写 API（返回 `STATE_CONFLICT`）；Pipeline 定义/阶段/运行模型和历史查询仍保留，新运行按 Job 类型选择系统 Pipeline。旧配置包、批量初始化与配置复制中的兼容数据按类型转换为 Job 默认参数；转换冲突失败关闭，不再写租户 Pipeline 定义表。
- 新生成的租户配置包导出、完整模板和场景模板不再包含可编辑的 `pipeline_definition` / `pipeline_step_definition` sheet；旧包上传预览仍保留两张旧 sheet 的校验与转换能力，Pipeline 定义/运行模型和历史查询不受影响。
- 跨租户 Config Sync/Job Bundle 不再读取或传输租户 Pipeline 定义，配置矩阵不再暴露 Job-Pipeline 关联；模板和通道引用从 Job 默认参数（含已迁移阶段参数）解析。旧版初始化请求仍保留 Pipeline 参数转换入口。
- 将新建、复制和配置包导入作业生成受管的单作业 Workflow，并通过显式唯一关联呈现两个独立定义菜单（作业定义、工作流定义）的双向联动；定义对象和各自编辑入口保持分立，限制受管图偏离 `START → JOB → END`，支持按单作业/组合工作流及关联作业查询。Pipeline 执行模型与阶段运行记录保留，旧 Pipeline 定义表尚未退役。
- 增加显式 Workflow 顶层触发：Trigger Request 以 Workflow ID 作为唯一目标，Orchestrator 创建无父 Job Instance 的 Workflow Run 并分派入口 Job 节点；Console 新增权限/租户/DAG 校验后的 Workflow trigger API，保持既有 Job 触发兼容。根启动与节点分派有真实 PostgreSQL/Kafka 集成验证；端到端浏览器旅程和不可变完整执行快照仍待完成。
- 新类型 Job 的 Launch 按显式 Job 定义关联进入单作业 Workflow；根 JOB 节点直接派发业务分区，避免创建同一 Job 的递归子实例；Workflow Run 记录启动时的单作业关联快照并同步归档。历史 WORKFLOW 包装 Job 保留读取和运行兼容，但新建 Job API 拒绝创建该包装类型；V244 已迁移可安全转换的旧 FILE_STEP 节点，legacy WORKFLOW 包装 Job 转换仍未完成。
- Workflow 定义写 API 现拒绝新 FILE_STEP 节点（`error.workflow.dag.file_step_retired`），要求改为引用 Job 定义的 JOB；不再以租户 `pipeline_definition` 存在性批准新的 Job-Pipeline 关联。保留 V244 存量转换、历史字段和旧配置包的显式迁移能力。
- 组合 Workflow 的 JOB 节点运行记录关联其独立 Job Instance；任务回报可由节点关联回找无根 Job Instance 的 Workflow 并推进节点/整体状态，legacy wrapper 才使用父虚拟任务回调。内部 Trigger Request 携带并校验 Workflow 节点上下文；Pipeline 模型和历史定义仍保留。
- 无根 Workflow 的 JOB 节点现在可不依赖合成父 Job Instance 直接派发：节点 Job Instance 作为独立执行明细关联到 WorkflowNodeRun，Trigger Request 以 Workflow Run+节点校验；现有 wrapper 的父虚拟任务路径保留。真实 PostgreSQL 17.11/Kafka/MinIO/Valkey `JobNodeDispatchIntegrationTest` 5 项通过；顶层 Workflow Launch 和完整执行快照仍未完成。
- 修复 v2 Workflow 执行快照路径仍使用当前 `workflow_node` 进行子 Job 关系校验的问题；新 Run 以已校验快照确认 Job 身份，节点运行记录确认所属节点，历史 v1/无快照 Run 保留实时定义校验。真实 PostgreSQL 集成验证覆盖定义变更后的快照放行和实时路径拒绝，并补充无根双 Job 顺序执行及整体收敛验证。
- 修复 Workflow JOB 子任务遇到 BatchDay readiness 延迟时的处理：等待中的 TriggerRequest 不再导致节点派发事务回滚，节点保持 READY 供既有释放流程重试；延迟释放后创建的 Job Instance 在 Launch 事务中自动关联回 WorkflowNodeRun。定向单测 13 项通过，`JobNodeDispatchIntegrationTest` 在真实 PostgreSQL 17.11/Kafka/MinIO/Valkey 7 项通过；BatchDay 延迟释放的专用真实链路验收仍待补充。
- Workflow Run Mapper 已接入 V247 的 Trigger Request 唯一关联、Workflow 版本、data interval 与 JSON 执行快照字段，并同步显式归档投影；现有 Job-originated 单作业运行写入受管 Workflow/Job 版本、系统 Pipeline 版本和固定 `START → JOB → END` 摘要。遗留 wrapper 不伪造单 Job 图。真实 PG 全迁移后节点派发集成测试 5 项通过，启动与 Mapper 测试 8 项通过，Spotless 通过；组合 Workflow 根 Launch/完整图及未派发节点配置快照仍未完成。
- Workflow 节点派发将系统 Pipeline 编码与语义版本固定到文件类任务载荷，Worker 对已固定但不匹配的任务拒绝执行；升级前排队且未带身份的消息保留迁移期兼容。
- V240 为 Workflow 定义增加调度字段并按 Job→单作业 Workflow 的显式关联回填现有日程，作为 Trigger 切换的兼容扩展。
- Trigger 定时定义查询改为从受管单作业 Workflow 读取 schedule/timezone/calendar/catch-up 字段；保留显式关联 Job 作为当前 Launch 兼容执行目标，并把 Workflow ID/code/version 固定进 Quartz 数据和 scheduled Launch params。V242 将 legacy WORKFLOW wrapper Job 的既有日程迁到同租户同编码的 Workflow；V242 真实 PG 迁移与 Trigger Testcontainers 查询用例通过。组合 Workflow 的根 Launch 尚未接入，故尚不由 Quartz 调度。
- Workflow create/update/full detail API 增加 Workflow 级调度读写字段；全量编辑历史快照通过 V241 保存调度配置，旧版本快照保持 null，不回填伪历史。V241 同步热表/归档列；单作业 Trigger 已读取 Workflow source，前端编辑入口仍等待组合 Workflow 根 Launch 与统一触发链完成。
- 修复 SDK 发布校验的独立 Java 消费工程错误启用 Maven 离线模式、因 runner 未缓存默认编译插件而误报发布包失败的问题；SDK 仍从前一步安装的本地仓库解析。
- 修复 SDK 发布兼容性门禁对响应侧可扩展枚举的误判；在线 strict 验证只启动唯一的 Console API 可执行 fat jar，并在启动 JVM 前加载本地环境、先通过 reactor 安装干净 runner 缺少的内部依赖再执行正式 Flyway 迁移、关闭未启动的读副本、启动必需的 Valkey、禁用范围外的对象存储启动探针，并以 Actuator JSON 响应而非范围外依赖造成的聚合健康状态判断应用已启动，避免误选普通库产物或因最小验证栈缺少 schema/运行依赖而误判；同时修正本地 pre-push 对 SDK reactor 模块的错误选择。
- 更新自托管 Rust Worker 示例的 `quinn-proto` 锁定版本至 0.11.18，并同步其传递依赖，修复依赖安全告警。
- 修复后端 pre-push clean compile 与 Java 治理测试使用不同 Maven 运行时、共享构建产物竞态及陈旧测试类导致的类缺失误报；治理测试改为 clean test，两类构建均使用仓库 Maven Wrapper 并在各自的一次性 detached worktree 中隔离运行，临时目录清理已按高风险操作治理登记。
- 新增隔离的后端可靠性回归套件及每日 CI，覆盖 manifest 修复重扫、对象丢失恢复与替换检测、到达组并发去重、checkpoint 续跑、Outbox/Kafka 恢复和消费者反压，并明确本地测试、标准 Sim 与 staging DR 的证据边界。
- 修复全量 CI 与 Sonar 中文件到达组集成测试仍把 checksum 期望值当作已校验证据的旧契约；测试现写入持久化 `integrityState=VERIFIED`，并同步配置注释与失败关闭语义。
- 入站 MANIFEST 模式在登记前校验 manifest 文件名、协议字段、长度和原始对象 SHA-256；到达组 `require-verified` 改为要求实际字节验证状态，并由公共完整性状态枚举定义 `VERIFIED` 契约。文件系统对象写入/复制与 NAS dispatch 不支持原子 move 时失败关闭，避免退化为可观察到中间态的普通覆盖移动。
- 新增文件完整性与故障恢复方案，明确入站/出站发布、到达组、重试和文件系统原子性边界。
- 为 Outbox、Workflow、作业实例和文件治理归档调度增加最近成功时间指标，并按 Orchestrator 副本汇总后告警首次运行失败及超出预期周期未成功，避免 ShedLock 未持有副本误报。
- 观测运行证据脚本改为从公共环境入口读取 Console 与 Orchestrator 地址，并扩展硬编码运行配置门禁，防止平台服务地址在脚本中再次分叉。
- Console 维护状态新增计划开始时间与服务端 `active` 判定；开始前只展示公告，达到 `startsAt` 后才进入只读或全站拦截，并补计划前放行/到点拦截测试。前端补充重点写操作按钮禁用；全站按钮视觉覆盖仍待穷尽式验收。
- 作业定义查询新增精确作业编码、直接前置作业过滤和包含停用定义选项，供工作流设计器分页查看作业定义的前置/后置关系；原有查询仍默认只返回启用作业。
- 新增手动触发的前后端联合版本准备 workflow，按 patch/minor/major 校验版本并创建两仓 Release PR；不自动合并或发布。
- 治理 Orchestrator 与 Console 的实体、服务和控制层边界：迁移非表投影模型，Controller 使用独立请求/响应 DTO 并保持 JSON 字段兼容，新增零存量架构门禁和分层约定文档。
- 收敛批次日回放条目状态更新的长位置参数与空值组合，改用具名参数对象和状态入口；AI 无用量结算改为明确入口，治理基线当前版本与基准版本统一执行同一套结构校验；同步前后端可读性联合审计，不改变 SQL、API、事务或业务状态语义。
- 修复临时 Sonar Quality Gate 在小变更条件已省略但 `ignoredConditions=false` 时误报；仍要求 `new_lines < 20` 的明确证据，其他未解释的缺失条件继续失败。
- 修复文件束启动参数缺失导致 `CREATED` 实例被周期性恢复并持续告警：新请求在实例落库前校验绑定，历史确定性错误收敛为业务失败；将导出 checksum 冲突归为不可重试业务错误，避免死信自动重放风暴；读写分离关闭时跳过无效主从库凭据告警。
- 允许审计员读取租户列表，供跨租户切换器使用；租户详情及租户管理写操作权限不变。
- 修正文件错误行原始记录脱敏条件：只有预览脱敏或错误行脱敏策略启用时才处理 `rawRecord`，关闭两项策略时尊重模板配置。
- Sim 29 对配置包模板下载、上传和预览中的 HTTP 429 增加有界等待重试，不调整服务端限流配置。
- Dispatch 渠道可通过非敏感 `secret_ref` 绑定 SecretVersion；实例创建时固定版本，Worker 外发前检查撤销/有效期并从加密载荷取凭据。紧急撤销新增租户隔离的管理员 API 与审计；被撤销/失效的重试不会切换到新版本。路由和队列模型不变。
- 收敛日志与运行时配置的敏感信息边界：遮盖遥测 MDC、Push endpoint、SDK 外部错误响应与配置 URL 日志中的敏感值；全量动态数据分类和真实 Loki 查询验收仍待完成。
- 为 Console 下游错误响应收敛敏感输出：日志不再记录原始响应体；无法解析的响应和下游 5xx 使用安全默认文案，避免内部错误细节进入日志或 API 响应。
- 为后端机器可读治理契约补齐 PR 变更范围路由，并阻止治理基线未经审批扩张；普通 Markdown 仍按文档变更处理。
- 配置包 SQL 预览改用单语句 SELECT/WITH AST 校验，避免注释和字符串常量中的 DML/DDL 单词误报，同时拒绝真实写语句、多语句和写入型 CTE。
- Dispatch 缺省外部请求号改为按租户、流水线实例、文件和渠道稳定生成；HTTP/API 重试携带相同 `Idempotency-Key`，并记录平台无法替下游承诺 exactly-once 的接收端契约边界。
- 启用导入笔数/金额对账后，缺少声明依据或对账超差会阻断交付；trailer、manifest 和数据集行数对账不受坏记录跳过策略影响，金额容差缺省为零，未启用的作业保持原行为。
- 统一 Sim 两个初始化入口的平台运行态清理，补齐触发请求、任务和审计记录；运行中配置编辑场景在终止信号下也尝试恢复原配置，上传预览会话仍按短期 TTL 回收。
- 修复 TypeScript 自托管 Worker E2E 因样例缺少锁文件导致 `npm ci` 必然失败的问题，并保留依赖安装错误日志。
- 修复临时 SonarQube 仅执行一次分析导致 Quality Gate 未计算、Sonar CI 恒失败的问题；先建立分析基线，再严格校验第二次分析结果。
- 修复 SonarQube 10+ 新代码指标使用 `period.value`、而门禁仍只读取旧 `periods[]` 导致忽略证据缺失；显式请求 period 元数据并保留严格缺项校验。
- 告警路由查询支持关键词服务端筛选；资产新鲜度策略和配置同步日志改为返回带总数的分页响应，避免列表只取固定数量或无分页加载。
- 新增幂等 Worker 能力列补偿迁移，修复历史版本号漂移导致 `worker_registry.task_capabilities` 缺失、Console Worker 查询返回 HTTP 500 的问题。
- 修复 Compose CD 调用前端 reusable build workflow 时引用错误提交的问题，并将 Sentry DSN secret 与前端 `workflow_call` 契约对齐。
- 新增受控的前后端 Compose CD：同一 release manifest 固定两仓 commit 与全部镜像 digest；可手动部署 staging 并执行 health/version、Playwright、视觉/跨浏览器 smoke 与 Lighthouse 验收，失败时恢复上一稳定 release。真实主机配置和 production 晋级仍待外部接入。
- 修复临时 Sonar 首次扫描无法设置 30 天新代码基线的问题：为扫描管理账号补齐项目级管理权限，并按项目实际主分支回读验证，避免 Sonar API 空响应掩盖配置未生效。
- 修复批次日回放详情默认“全部”筛选因空状态被 SQL 等值过滤而显示空列表的问题；保留指定状态筛选及租户隔离。
### Security
- 配置包 Excel 上传的工作簿暂存改为使用 owner-only 私有临时目录，避免在共享系统临时根目录落盘；成功和解析失败路径均验证清理。
- Console 文件错误记录的 `rawRecord` 响应现按模板 `preview_masking_enabled` 执行脱敏；`error_line_masking_enabled` 仍负责错误消息和原始行，两项策略可独立配置。
- 配置包 Excel 解析失败时不再将 POI 原始异常文本回显给调用方；服务端保留经过清理的诊断摘要。
- 作业/步骤结果、实例快照及执行日志扩展字段的查询响应递归掩码凭据类 JSON 键；告警、审批、文件流水线、配置审计、通知投递和结构化错误载荷在响应投影/渲染时同样掩码，不改持久化值或 Worker/投递输入。自由文本中的任意凭据不在本次覆盖范围内。
- 文件通道、通知渠道响应递归掩码动态 JSON 敏感字段；文件 endpoint 同时遮盖 URI 凭据。更新请求回传掩码值时保留数据库原值，显式删除仍可清除。
- JSON Config Sync、Job Bundle 和 Excel 配置包导出不再回传渠道凭据及环境专属 endpoint；跨租户复制保留渠道业务定义但不复制来源环境连接信息。
- Excel 配置包上传会话绑定操作者和权限集合；跨账号或权限变化后拒绝复用。预览、行内修正与 Apply 使用随机确认令牌、跨副本 CAS 和原子占用，避免旧预览覆盖、重复 Apply 或未经确认写入。

### Changed

- 审批详情直接映射具名查询行和响应对象，保持既有 JSON 字段及空值语义。
- 租户配置包 Excel 模板、导出和预览工作簿新增可筛选的枚举字典说明；数据区使用文本格式，下拉与必填校验覆盖全部可用行，筛选范围覆盖实际内容。
- 前端 staging E2E 必须显式配置前后端 origin 与测试账号；发布必验场景被跳过、未发现或缺少必要 seed 时失败，可选跳过汇总到场景报告。
- 前后端治理基线扩张需要匹配的限期审批记录和 CODEOWNERS 审核，降低通过同 PR 同步抬高基线来掩盖新增问题的风险。
- 配置包按作业场景和文件方向/格式生成精简模板；旧模板与旧包解析保持兼容，未选择场景时不再回退到全量模板。
- 作业、Pipeline、Workflow REST 与配置包 Apply 的租户写入口拒绝修改物理队列、Worker 分组和平台运行阈值；租户角色导出隐藏平台资源字段，平台角色保留完整维护视图。
- 配置包 MERGE 对未提供的可选值保留现值；省略 Pipeline steps / 日历 holidays 不再隐式删除已有项。Apply 前用租户配置修订栅栏拒绝预览后的并发变更。
- 租户配置向导要求先选择场景，Apply 按角色显示；作业配置表单不向租户管理员展示或发送物理队列和 Worker 分组字段。
- 批量日回放改为“先预览、再确认提交”：预览生成短时一次性凭证，提交时重新核对候选快照并原子消费，防止候选变化或重复提交造成预览与执行不一致；补充回放预览令牌迁移及并发/过期测试。
- 集中多个后端集成测试重复的测试 profile 默认配置，并增加 CI/pre-push 守卫，避免模块测试 YAML 重新复制共享配置；场景专属属性覆盖保持不变。

- 补齐平台能力的代码基础：独立 SHA-256 `BatchTaskExecutor` 插件样例、Worker 注册 capability 持久化与 Console 展示；Helm 支持外部共享 Secret 引用和轮换触发滚动；GitOps 增加 ops 仓模板及从 release manifest 生成逐镜像 digest values。真实 ops 仓、密钥控制器和 Kubernetes staging 验收仍待外部接入。
- Docker/Helm 部署配置不再静默绑定特定集群拓扑：Compose 容器网络 Redis 地址统一由 `COMPOSE_REDIS_HOST/PORT` 提供，与裸 JVM 宿主映射端口分离；生产 Helm overlay 不含集群 DNS、域名、namespace selector、监控标签或镜像仓库，关键拓扑缺失时 fail-fast，站点私有 values 负责注入；内部 Service URL 按 release/namespace/cluster domain 推导。前端部署 Compose 要求显式设置 Console API 上游，Docker 镜像不再携带本地后端地址兜底。
- 修正本地观测栈的应用抓取端口和可选指标发现；观测端口默认仅绑定 loopback、关闭 Grafana 匿名访问，并用共用 Docker secret 为 Alertmanager/Console API 配置同一 bearer token。高权限主机/容器 exporter 改为显式 profile。
- 供应链与安全治理改为持续发现、季度集中升级：依赖机器人不再自动创建 PR，新增只读季度盘点、不可变工具链锁定和用户输入边界 Jazzer fuzzing。
- 收口后端待办：Console Telemetry 纳入用户限流并保留有界扩展类型兼容性，ShedLock provider 增加故障计数/健康指标和告警，并校正 SDK、租户 Worker、JVM/Helm 与 CD 文档中的陈旧待办状态。
- 修复 Web Push 传递依赖 jose4j 0.7.0 的两个 HIGH CVE：统一管理并显式选择 0.9.6，删除已无当前漏洞命中的过期 Trivy CVE 忽略项。

滚动合并中的变更入口；有发布影响的 PR 合入 `main` 时更新，冻结发版时整段下移到正式版本标题下。

### Added

- 统一 CI 门禁的职责分组、开关和执行模式：`enforce` 真实阻断、`report` 执行后仅报告、`ignore` 明确不执行；固定状态行新增 `group` / `mode`，报告与忽略例外继续受 owner、期限和升级目标治理。
- 补齐平台告警闭环：新增核心控制面 target 消失检测、Alertmanager 停机/通知失败/配置重载失败告警，以及 Console 实际渠道投递失败计数；Docker 与 Helm 统一服务 `job` 标签并增加规则正反例验证。Prometheus / Alertmanager 自身故障仍需集群外独立监控，真实通知渠道送达需按目标环境验收。
- 新增作业级耗时过久、启动过晚和完成过晚三类旁路告警；完整配置包导入与导出统一包含必需的 `job_monitoring_policy` sheet，每个作业一行集中维护阈值和告警等级；不兼容缺少该 sheet 的旧工作簿，并提供 Console 独立维护页。
- Console 增加默认关闭的单试点 OIDC 企业登录：使用 Authorization Code + PKCE、Redis 一次性 state、显式外部身份绑定和本地 RBAC；登录成功复用平台 HttpOnly Cookie。IdP discovery 延迟到首次 OIDC 请求，服务启动与密码登录不依赖 IdP 可用性；Security 链过滤器不再被 Servlet 容器重复注册。同步提供条件展示的 Console 登录入口、配置/Secret 注入、迁移及隔离 IdP 协议集成测试；真实 IdP 联测仍待试点配置。
- 新增开源工程治理规范、轻量 BEP 与生产就绪评审模板、稳定安全模型和 OpenSSF Scorecard 周期报告；GitHub 启用私密漏洞报告、Secret Scanning/Push Protection 与 Dependabot 安全更新，main ruleset 强制讨论解决并移除永久 bypass；CodeQL 补齐纯文档 PR 的稳定检查后再纳入 required checks。
- 新增后端不可变镜像发布输出、统一 release manifest schema / 校验器，以及 Compose 按 digest 部署、健康校验、失败快照、稳定版本回滚和留存审计脚本；生产自动部署、审批和 SSH 凭据仍由后续环境接入完成。
- 补齐 Process Worker 业务库路由、Console 真实 HTTP 限流、批量开户真实 PG/Valkey 事务、JDBC ShedLock、Worker drain、对象存储加密装配和 S3 自动建桶的集成或专项验证。
- 新增 PostgreSQL、Kafka、Valkey/Redis、MinIO 四环境基础设施治理 profile，支持本地、场景测试、压测和生产基线参数统一落地；Kafka topic retention 与 MinIO lifecycle 提供显式应用脚本，PostgreSQL/Valkey 保持重启生效的可审计配置边界。
- 扩展四环境运行时治理 profile，覆盖 LOCAL / NAS / SFTP / OSS / API / API_PUSH / EMAIL 文件通道、Worker Report Outbox、Quota / ShedLock、读副本、业务分片、Quartz、观测和外部端点，并新增只读巡检入口防止治理项漂移。
- Console 账号管理新增批量开户：提供 Excel 模板、预检与修正、租户权限校验、原子入库及一次性初始密码交付；新增开户操作记录与用户名大小写无关唯一约束。
- Console AI 增加受控图片附件输入：上传内容经解码、尺寸限制、重编码和加密后存入独立 AI 附件桶，按租户与操作者授权绑定会话轮次；能力默认关闭，配置已对齐本地、Compose、Helm 与生产覆盖入口，并在启动期拒绝复用批量文件桶。
- Console AI 流式完成响应增加去重后的结构化来源标识；仅返回来源名称，不暴露检索片段、提示词或相似度分数。
- Console AI 对话统一为 SSE 流式端点并新增同操作者取消入口，复用原有权限、速率/成本、会话和审计链路；移除旧 JSON 对话端点。
- SDK 跨语言共享常量新增 `report_error_codes`（report body `errorCode`，权威源 Java `SdkErrorCode` → `docs/api/sdk-shared-constants.yaml`）；Java / Python / Go / TypeScript / Rust 五门语言的 parity 测试均覆盖该列表，各语言常量从单一来源派生（Go / Rust / TS 不再复制字面量，Python `dispatcher.DEFAULT_ERROR_CODE` 改引 `constants.ERROR_CODE_EXECUTION_FAILED`）；BYO 契约同步登记尚未纳管的 wire 字段名与 `BATCH_SDK_*` 配置 key 缺口。

### Changed

- 静态分析例外治理新增对生产 SpotBugs 抑制、Sonar `//NOSONAR` 与 YAML IDE inspection 的覆盖，并将动态 SQL `S2077` 抑制收窄到已校验的 sink 方法；新增类级 `S2077` 会被静态门禁拒绝。
- 作业监控矩阵收敛为：Cron 与依赖作业支持开始过晚、完成过晚、运行耗时；独立固定频率及手动/API/外部触发仅支持运行耗时。依赖完成窗口以 EFFECTIVE 就绪时刻计算，运行耗时不统一启用默认值。

- Testcontainers 跨 Maven 运行复用改为本地显式 opt-in：默认清理 PG、Kafka、Valkey 和 MinIO 测试容器；`local-testcontainers-reuse` profile 仅复用带专属 Docker label 的 Valkey/MinIO，避免 CI 或个人全局配置造成测试数据残留。
- 本地 Docker 清理改为按测试残留、BuildKit 缓存分批预览/执行；测试容器增加仓库归属标签，业务分片仅按显式测试所有权标签识别，磁盘清理脚本不删除应用或基础环境镜像，也不执行全局 system prune。
- Java 设计表达收口：Import 与 Task Outcome 的纯测试装配迁入 test fixture，生产直接注入已有窄协作者；未发布的 SDK 注册 Map/构造入口收敛为 typed 请求，按当前平台 wire 协议发送。内部区分执行成功/失败与 verifier 软告警，保留动态输出及事务/锁/Outbox 顺序，补齐真实装配、序列化和 PG 回滚/幂等验证。
- 依赖治理改为持续告警、季度集中盘点和单个人工 PR：关闭 Dependabot 自动版本/安全修复 PR，季度任务只生成多生态 artifact 与 Actions Summary，不再自动创建 Issue/PR；紧急可达漏洞仍走独立快速修复。同步升级本批依赖、固定 CI/镜像供应链输入并刷新 SBOM。
- 修复独立 `load-tests` 未继承根依赖治理导致的安全版本漂移；压测模块继续保持独立 reactor，但统一复用根 POM 的 Netty、Jackson 与 Logback 修复版本。
- PR 单元测试按模块边界选择分片并拆分 Worker/Console 长尾；静态门禁拆分 policy、供应链和 Java quality 三路并由稳定 context 聚合；PR CodeQL 使用 Java 无构建与默认高精度查询，main/定时保留手工全量构建和扩展查询；Full/Staging E2E 依据最新实测重排为六片，CI runbook 同步登记耗时基线与回退标准。
- Console、Trigger 与 Import 通知的固定响应改用具名类型，Pipeline 列表不再在应用层传播原始 Map；Job trigger 保留普通实例号/dry-run 对象两种 wire 形态，OpenAPI 与配对前端类型同步。Lineage 热表/归档证据使用显式构造映射，文件 metadata 解析为 JSON 对象，不透传 JDBC 驱动封装。
- 已有生命周期、Outbox、文件格式、分发策略和通知枚举统一复用稳定 code，保留各运维操作原有状态集合与配置 DSL。新增低误报 Java 契约守卫，按固定边界、确认的有限域和已有协议键复用阻断新增退化；动态契约按方法签名登记例外。
- E2E 测试类将重复的 Spring Boot、profile 与标签声明收敛为可配置组合注解；各类独立应用上下文、属性、业务库 schema 和特殊上下文生命周期配置保持不变。
- 后端异常日志统一使用安全摘要或 SLF4J `Throwable` 堆栈：摘要会移除控制字符、遮蔽常见凭据并限制长度；PR、Full Gate 和本地提交门禁阻止生产日志重新直接打印异常 message。
- 核心术语文档按 Java enum 和现行运行模型统一校准，并新增 PR / Full Gate 只读同步门禁，防止实例、工作流、节点、分片、步骤、任务等状态及调度类型再次与代码事实源漂移。
- Docker 镜像构建 CI 纳入 `ops-toolbox` 运维工具箱镜像，实际构建校验 psql、Kafka CLI、MinIO mc、redis-cli 与非 root 运行时约束；该镜像仍不进入业务服务基础镜像。
- nightly 镜像构建接入 `daily-sim-strict-validation`：当天有代码或配置变更时，必须先通过完整 sim + strict，再构建全部应用镜像；无变更时跳过，保留手动和 reusable 调用入口。
- CI 镜像构建改用 Maven Central 配置并增加依赖下载重试，降低区域 Maven 镜像短暂 502 导致整组镜像失败的概率。
- PR 与 Full Gate 的静态检查改为执行全部独立门禁后统一汇总失败；基础环境前置仍立即失败，本地提交钩子保持首错即停；POM 对应 SBOM/许可证结果与 `@ConfigurationProperties` 配置治理目录在提交前自动同步；许可证门禁同时阻断 source-available 与未知许可证。
- Sonar 本地扫描统一使用仓库 Maven Wrapper，并与 CI 对齐到 JaCoCo 0.8.15；补充全量扫描证据及高风险静态分析问题的定向修复，不将本地 Sonar Quality Gate 作为远端必过门禁。
- 统一文档站支持通过仓库级 `docs/.docsignore` 排除技术试验、测试数据和规模统计；现行架构、API、设计、测试、SDK、运维、合规及其可追溯证据继续发布。
- Console 文件 Pipeline、步骤、分发与错误查询增加服务端 `keyword` 筛选，步骤查询增加 Pipeline 实例和阶段筛选；OpenAPI 同步补齐 Job Step 与 Workflow 列表已有筛选参数，供前端直接使用服务端分页。
- Console 权限契约新增动态 capability，下发的 `AI_ASSISTANT_USE` 同时受 AI 开关和用户/角色白名单控制；使用统计查询收紧为平台管理员、审计员和租户管理员，并在运行监控菜单提供独立入口。
- Console AI 新增保持旧列表兼容的会话游标分页接口，按租户/所有者及过期时间过滤；旧列表同步补显式租户谓词，避免仅依赖连接级 RLS。
- 维护状态响应补充共享状态更新时间，时间戳来自数据库维护状态记录，便于控制台判断版本与更新时间，不改变 #1102 的数据库事实源和 CAS 语义。
- Sim 与本地 MinIO 初始化统一使用独立运行、固定版本的 CLI 容器，不在 MinIO 服务容器内执行客户端；镜像版本及 Compose/helper fallback 纳入版本对齐门禁。
- 依赖注入统一为构造器注入并新增 Lombok/logger 增量门禁；移除 9 个单构造器上的冗余 `@Autowired`，多构造器注入与 logger 例外均按精确规则检查。
- 仓库文本编码门禁覆盖 PR 变更文件、提交暂存文件与 Full Gate 全仓；应用容器、基础设施及 Testcontainers 统一 UTF-8 locale，SDK 协议文本约定为 UTF-8，含 NUL 的文本仍严格检查，外部文件字符集继续显式配置。
- Jackson 2.x 与 3.x 升级至已修复 CVE-2026-68497、CVE-2026-91776 和 CVE-2026-91777 的补丁版本，覆盖应用和 Java SDK 的受影响依赖链；PR 与 Full Gate 的 Trivy 升至 `0.74.0`，并将配置扫描器参数更新为 `misconfig`。
- 修复控制台 AI 图片附件的 Web 层越界依赖，并为精简 Spring 测试上下文显式注册 PostgreSQL UUID TypeHandler；Trivy 配置误报改为按规则和 Helm 模板路径精确豁免。
- PR 与 Full Gate 的 Trivy 供应链扫描前缓存外部 Maven 依赖并安装本地 reactor 产物，减少扫描器远端解析依赖造成的 Maven Central 请求限流；静态门禁超时相应调整为 30 分钟。
- Docker 前后端应用镜像统一以固定 `batch:batch`（UID/GID 10001）运行，仅日志和缓存等必要目录可写；PostgreSQL、Valkey、MinIO、Tempo 等长驻进程非 root，MinIO 数据具名卷改挂实际数据目录；观测卷改由非 root 一次性任务预备，Prometheus 动态 targets 移到数据卷。
- Console AI 限制外发上下文为有界 JSON 并递归拒绝凭据字段；跨 Provider 故障切换需显式配置启用，默认关闭；响应与审计模型标识使用实际 provider 响应 metadata。
- Cron 预览移出匿名白名单，仅允许四类正式 Console 角色调用。
- 控制台 RBAC 彻底收敛为 `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` 四类正式角色；V213 一次性迁移旧 `ROLE_USER` 并增加数据库约束，运行时、菜单、JWT 和账号 API 不再兼容旧角色。
- 审批写操作收口为平台管理员和租户管理员，审计员维持跨租户只读职责。
- 本地测试并行执行改为显式 `ALLOW_PARALLEL_TESTS=1` opt-in，并要求生产 Docker Compose overlay 显式传入已验证 `IMAGE_TAG`；Sim SFTP/MockServer 容器名统一加 `batch-sim-*` 前缀且纳入版本对齐检查。

### Fixed

- 维护全冻结期间收窄管理员旁路为只读诊断，普通业务写请求不再因 `ROLE_ADMIN` 放行；Orchestrator 手动 drain 暂停 Trigger Launch Kafka listener，并将当前 poll 中尚未开始的消息延迟重投，恢复 drain 时再继续消费。
- 修复作业实例创建后重试策略被作业定义变更覆盖的问题；重试排程优先使用实例执行参数快照，历史实例缺少快照时保留原定义回退行为。
- 修复 SDK 真 Orchestrator E2E 冷启动时把 PostgreSQL initdb 临时 Unix socket 误判为数据库初始化完成，以及 Java、Python、TypeScript、Rust 样例准备失败被后台 PID 掩盖、诊断日志缺失的问题。
- 修复四份 Sim 租户配置包 Excel 的作业监控策略表头仍使用旧字段、导致线上 `daily-sim-strict-validation` 在场景执行前失败的问题；PR、Full Gate 与本地提交预检新增配置包 schema 防漂移检查。
- 修复 SLA 告警标记与审计/执行日志、告警事件分步提交导致的静默漏报；改为每个候选独立事务，单个持久化失败回滚该候选并允许后续扫描重试，不阻塞同批其他候选。
- 防止 Alertmanager 跨租户分组/抑制，并避免关闭一条事件时解决仍有活跃成员的分组；记录告警保持、去重、重复通知和恢复语义。
- 修复 Trivy 报告的 AsyncHttpClient WebSocket 解压 DoS/连接上下文复用及 Kafka LZ4 临时文件代码执行高危依赖；统一升级到修复版本并刷新入库 SBOM。
- 补齐本地容器生命周期并发保护及 PG/Valkey Sentinel/MinIO HA 隔离演练；维护 SQL 从 shell 内联迁至独立文件并扩展边界守护，避免演练误触现有数据卷或共享环境；Sim SFTP/MockServer 也统一 UTF-8 locale 并纳入守护。
- 修复 Worker 实际绑定端口校验收紧后两个 E2E 的运行形态漂移：Outbox 重试测试和 Worker 进程重启测试改用随机真实 HTTP 端口，不再以无 Web Server 的上下文触发 Worker 自注册。
- 修复文件束导入把独立文件再次当作单文件分片进行行取模和范围下载、导致分区成功但业务漏数的问题；补齐真实对象存储与端到端逐文件对账，SIM 26 按本轮请求、来源文件和业务键隔离验证。Nightly 保留 SIM 后执行 strict 的顺序，明确 SIM 失败仍采集 strict 结果，并限制 SIM 步骤超时以保留后续验证时间。
- 修复预推送编译把 `batch-e2e-tests` 截成不存在的 `batch-e`、以及改动模块超过五个时静默漏编译的问题；补充模块选择回归守护。

- 修复 Console 异步线程池重复初始化，交由 Spring 初始化和销毁；副本采样、Worker 并发与续租配置增加绑定期校验。五类 Worker 删除固定端口兜底，仅注册主服务实际绑定端口；Kafka consumer 改为类型化绑定，保留原配置键、默认值、手动确认和批量背压校验。
- 修复压测环境清理后 Kafka 仅重建首个 topic 的问题：清理脚本等待删除收敛后调用统一初始化入口并校验完整 topic 集；同时治理 shell `pipefail` 下 `head`、`grep -q` 和提前退出 AWK 造成的 SIGPIPE 误失败。修复每日 Sim 调用 reusable 镜像构建时缺少 `packages: write` 权限，以及 CodeQL 同一 category 重复上传 SARIF 导致的 CI 假失败。
- 升级独立 Java 租户 Worker 示例的 AssertJ 测试依赖至 3.27.7，修复恶意 XML 比较输入可触发的高危拒绝服务漏洞（CVE-2026-24400）；示例现有测试未使用受影响的 XML 比较 API。
- Excel 导出将 POI 中间 XML 和模板 ZIP 纳入线程局部私有临时文件策略，成功和失败后恢复原策略；输出文件创建时限制为所有者权限，写入拒绝符号链接且不隐式重建文件，保持原输出路径和内容。
- Trigger 运维代理显式归一化缺失响应信封，消除新增 Sonar 空值告警，同时保留已有信封的空 data 语义。
- 修复 Java SDK 在 WARN 时序模式下接受零或负调度周期、注册成功后启动失败时可能遗留后台资源和 Worker 注册的问题；不可执行周期始终拒绝，启动中途失败统一执行有界回滚。
- 为 Java、Go、Python、TypeScript、Rust 自托管 Worker 补齐真实服务控制故障注入验证：检查 pause/resume、取消、drain 与进程退出期间的任务领取和终态；修复 Go 心跳未控制 Kafka consumer、TypeScript 丢弃 `none` 恢复指令及 Rust 示例未将心跳状态接入消费循环的问题。暂停状态由本地代理注入，不代表平台提供原生 pause 管理 API。
- 修复 Console Trigger 类型化响应的跨域归属错误：Job Controller 与 Ops 代理共用的传输 DTO 移入顶层应用契约，恢复跨上下文直接依赖为零，JSON 字段与运维行为保持不变。
- 修复内置 Worker 忽略心跳排空指令的问题：收到 `DRAINING` 后暂停单条与批量 Kafka listener，未 claim 消息回退重投；平台恢复 `NORMAL` 且本地有执行许可后再恢复消费。
- 修复对象存储 raw backend 导致加密装饰 Bean 永远退避的问题；raw 实现改由内部持有者隔离，业务注入只暴露外层装饰器，并保留按类型提供完整 `BatchObjectStore` 的覆盖能力。修复 Console 真实认证主体被按整个对象字符串构造限流键、角色变化可绕开用户级限流的问题。
- 取证 ZIP 与 Atomic shell 工作目录接入共享私有权限边界,不再依赖默认 umask;验证码日志覆盖 Unicode 换行并保留 null 归空语义,同步修正 CodeQL 净化模型与取证默认开关说明。

- 修复束文件到达组长编码请求号溢出、完整性查询遗漏校验类型及等待/拒绝误报触发。
- 补齐 Trigger、启动服务、失败分类与结果版本操作的日志字段净化；私有临时目录、导出暂存、默认分发 Outbox 和本地对象存储统一在创建时设置私有权限，不支持 POSIX 的挂载点验证 owner-only ACL 后才允许写入。

- 修复文件束到达组（ADR-046 `BUNDLE_IMPORT` / `BUNDLE_EXPORT` / `BUNDLE_DISPATCH`）到达后永不 launch 的两个缺陷：到达组文件元数据的 `metadata_json` 经 JDBC 返回 `PGobject` 时不再被当作空表（否则读不到 `bundleJobCode` 后静默跳过），并且 launch 前按同仓库其它内部 launcher 的既有模式先落 `trigger_request`（否则按 requestId 查不到行抛 `error.trigger.request_not_found`，到达组每轮 sweep 重试而束永不落地）。
- 修复 Console 内部 RestClient 在 Spring 随机端口确定前提前解析 `${local.server.port}`、导致 E2E 应用上下文启动失败的问题；Orchestrator、Trigger 与可选 Atomic 状态客户端改为首次请求时构建并在组件生命周期内复用。
- 修复 `ops-toolbox` Dockerfile 因未声明 Debian/PGDG 包版本策略触发 Hadolint DL3008、阻断 Full Gate 的问题；沿用应用镜像约定，显式记录从受控软件源获取最新安全补丁的窄范围例外。PR Gate 在 Dockerfile 等配置输入变化时同步初始化 JDK 21 构建环境，避免 Trivy 依赖预热误用 Runner 默认 JDK 17 后回源触发限流。
- 批量开户事务收尾日志改用请求 ID，不再输出预览令牌或原始存储异常；幂等占位失败日志仅记录服务端生成的所有者标识和异常类型，避免外部输入注入日志。

- 修复 Orchestrator Outbox 自调度循环在关闭阶段继续安排延迟轮询、导致优雅停机额外等待的问题；销毁阶段会停止调度并取消尚未执行的轮询任务。
- 修复内置 Worker 以进程级单槽上报 Pipeline 进度导致并发任务互相覆盖的问题，改为 task/pipeline/stage 隔离并在控制面按 stage 聚合分片；删除旧 SDK 标量心跳、workerCode 查询兼容路径及旧参数构造器；修复 Controller 标准化心跳时遗漏结构化进度字段。
- 收口长生命周期资源所有权：租户业务路由数据源关闭全部子连接池，并在多分片构建失败时回滚已创建连接池；Console 内部 RestClient 与 Kafka AdminClient 复用并随组件关闭，Dispatch/Atomic/通知 HTTP 客户端和 NAS 线程池增加确定性销毁；Import 预处理不再通过静态可变 ObjectMapper 跨应用上下文共享状态。
- Worker 任务执行池改为有界排队并在饱和或关闭时明确拒绝，线程泄漏 watchdog 改为观察插件实际执行状态；Shell 输出结果不再保存在单例 Map，Export 加密临时文件在成功和失败路径均由创建者清理。
- Console 写请求幂等占位增加请求所有权、续租和 compare-and-set 结算，旧请求不能覆盖或删除新请求的占位；批量开户预览的并发编辑和 Apply 冻结改为 Redis CAS，事务回滚后恢复原快照。
- 修复 Worker SQL transform / JDBC mapped import 在部分列映射和空值处理下的边界行为，并为控制面 10w 压测脚本增加大请求量前置校验，避免本地低 relay 或 demo 作业超时被误判为容量回退。
- Console 批量开户补齐单账号创建的租户状态终态守卫，并允许按目标租户过滤批量操作查询；`user.batchCreate` 审计以 `requestId` 作为批次操作聚合键，便于和批次记录关联。
- 修复根目录 `.node-version` 与 `.nvmrc` 分别选择 Node 24/22 的漂移，运行时对齐门禁现同时校验后端仓库和配对前端的 Node 24 本地入口；移除已被现有 Java 质量门禁替代但意外回流的 `qodana.yaml`，仓库卫生检查阻止该废弃配置再次提交，并正确跳过工作区中尚未暂存的删除项；范围探测器将已知根目录工具配置归入 `config`，不再因误判 `unknown` 启动无关 Java 单测。
- 修复每日 Sim 的 Trigger stage 6c/6d 夹具依赖默认业务日历节假日数据，导致 Quartz 正常触发但 scheduled request 被业务日历跳过的问题；为定时触发断言增加无节假日专用日历。
- Kafka 启动时重复输出的 ConsumerConfig/ProducerConfig 完整配置块默认抑制；Worker 启动审计 INFO/WARN 仅保留状态与摘要，完整细节仅在 DEBUG 输出。
- 修复线上 sim-strict 启动时 `minio/mc` 镜像仓库拒绝拉取的问题：CLI 容器改用已验证内置 `mc` 的固定 Bitnami Legacy 镜像；失败诊断在没有应用日志时不再额外报错。
- 重新生成 Jackson 2.21.7 / 3.1.7 升级后的 CycloneDX SBOM，并在 POM 变更的 PR 与 Full Gate 中校验入库 SBOM 和 Maven 依赖图一致，避免依赖升级后合规快照继续停留在旧版本。
- 修复 E2E 自建 MyBatis 工厂未加载并应用统一 UUID `ConfigurationCustomizer`、导致 Console AI 映射阻断全链路测试启动的问题。
- SDK 幂等键与签名 nonce 随机源收敛到系统 CSPRNG，补齐 Go 随机读取失败处理；服务端业务号随机后缀扩容，并对 KMS key、请求签名 header 增加启动期和输入边界校验。
- Console 写请求幂等在 Redis 短暂不可用时增加数据库完成态兜底；读副本执行阶段连接异常会触发 quarantine，避免后续只读请求持续打到故障副本。
- 文件治理的预签名审计和到达确认写入收敛到短事务提交边界，避免对象存储探测期间长时间占用数据库事务。
- Console AI 失败响应的 OpenAPI 契约允许 `modelName` 为空，与模型调用失败时的真实响应一致。
- Console AI 会话续写对非所有者或并发失效的会话统一返回 `NOT_FOUND`，与历史读取一致，不暴露会话 ID 是否存在。
- Console 无状态 JWT 请求不再因每次认证触发 Spring Security 会话策略而清除 `XSRF-TOKEN`；已认证读请求后写操作仍需 `X-XSRF-TOKEN`，缺少请求头继续返回 403。
- Console AI 会话续写与历史读取拒绝不存在或过期的会话，避免保留期内的过期记录被继续读取、续写或隐式重建；按 ID 锁定、读取和轮次分配均显式按租户过滤，不只依赖连接级 RLS。
- 集群诊断的终态子节点检查与批次日汇总分类由 Mapper XML 测试对照 `JobInstanceStatus` 生命周期集合，防止新增实例状态后终态诊断、成功/失败/进行中统计发生漂移；保留 SLA 等业务专用状态口径。
- 实例诊断改用 `JobInstanceStatus` 派生的活跃态与终态集合；`PARTIAL_FAILED` 存在活跃子节点时报告终态一致性异常，不再误报活跃实例缺少 Worker。
- AI 会话主动删除与过期清理 SQL 均显式限制 tenant_id，避免依赖连接级租户上下文作为唯一隔离条件；重构轮次完成参数及对话编排以满足架构和 PMD 检查。明确会话/轮次表按用户删除与保留期清理、不进入 archive 的隐私边界。
- Console AI 控制器通过应用服务访问会话与成本用例；成本摘要 GET 不再触发过期预算清理，清理移入预算预留事务，确保查询无写副作用并满足 CSRF 静态分析。
- 撤销未完成的 Workflow `APPROVAL` 节点运行链与模板接口，并在定义校验时明确拒绝该节点类型；不影响补跑、配置发布等独立审批流程。
- 去除 Console AI 阻断词列表中重复的 `system prompt`，不改变匹配语义。
- 修正 Console 使用率汇总 MyBatis 对 primitive `long` record 参数的构造映射，并新增真实 PostgreSQL 集成测试覆盖并发累加、租户 RLS 和事务内租户上下文。

> 当前唯一 GA tag 为 `v1.0.0`（2026-09-02）。历史 `1.1.0` / `1.2.0` 仅为 GA 前开发里程碑，未形成 release tag，已在下文明确标注，避免被误认为正式发布。

### Added

- Console AI 增加版本化最小页面上下文、租户/操作者隔离且 AES-GCM/KMS 加密的服务端会话与轮次恢复/删除/过期清理，以及基于 provider token usage 的月度估算台账、原子租户预算预留和费用摘要 API；审计预览默认关闭。

- **控制面治理能力**：新增共享维护状态与版本 CAS、维护响应版本头、按租户日使用率聚合、AI 成本/留存治理、容量告警面板，以及运行证据和 Worker 滚动升级验证脚本；后端接口、迁移和运维文档同步更新。

- **CI 范围与门禁治理**：统一 PR 和 SDK workflow 的变更范围 composite action，改用稳定且不重名的 scope checks；SDK 五语言契约矩阵通过稳定聚合检查纳入主干 Ruleset required checks；Full Gate 增加每周无条件巡检，防止 `paths-ignore` 和分类规则漂移。
- **CI 集成测试覆盖守护**：新增主 reactor 集成测试模块发现检查，要求含 `*IntegrationTest` / 非 E2E `*IT` 的模块必须进入 `full-ci-gate` 的 `mvn verify -DskipITs=false` shard；`full-ci-gate` 同步执行 E2E shard 静态覆盖检查，防直推 main 时测试清单漂移。
- **Console AI OpenAI-compatible 接入**：新增 `openai-compatible` provider 模式,支持 DeepSeek、千问、智谱、Kimi、MiniMax 或私有兼容代理通过统一配置接入;聊天端点与 RAG embedding 配置分离,兼容模式禁止自动跨 Provider failover。
- **控制台运维工具契约**：导出文件命名预览与 Worker 运行时共用同一解析器，支持业务日、租户、批次号、区域和版本占位符，避免前端复制命名规则。
- **构建与运行告警治理**：Java 主源码和测试源码启用 deprecation/unchecked warning 零容忍；新增当前应用日志 WARN 策略审计，区分预期本地告警、需处理状态和未知回归，并在本地全量启动后自动报告。
- **用户账号状态筛选**：Console 用户账号列表 API 支持按启用状态筛选，前端分页查询改为服务端过滤，避免仅过滤当前页造成结果遗漏。
- **每日仿真与严格数据验证**：新增按北京时间当天代码变更触发的 GitHub Actions 定时验证，顺序运行完整 `sim-harness` 和 BE-ACC 严格真实数据步骤；新增按暂存文件域运行的提交前检查。
- **本地开发收尾守护**：业务数据源默认账号统一为 `batch_business_writer`，新增 SUPERUSER / BYPASSRLS 启动检查、健康检查和真实 PostgreSQL 集成测试；Trigger E2E 从真实 `TriggerService` 入口覆盖 Outbox→Kafka→Orchestrator，Kafka Outbox 增加故障恢复后重投验证；五语言 SDK 统一 live Kafka 环境变量，Java/Python 补齐重试与严格时序环境配置。
- **脚本与文档治理入口**：新增仓库 Python 统一入口和治理聚合命令，避免系统 Python 缺包导致门禁漂移；校准 Chaos、运维剧本、Forensic Replay、测试覆盖快照和当前待办状态。
- **租户配置包 Excel 易用性**：新增 11-Sheet 字段填写说明 API，返回必填、只读、类型、枚举、默认/留空行为、示例和适用范围；新增 `ALL / IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC / WORKFLOW` 场景化示例模板下载，同时保留完整 11-Sheet 全量导入契约。
- **重任务执行保障**：新增平台全局活跃作业事务级硬上限、租户/资源队列共享派发 QPS、Dispatch 下游健康准入，以及 CPU/内存/IO 专用 Worker 资源池；稳定池代码与 Pod 实例身份分离，资源画像、WAITING 重派和 claim CAS 保持同一契约。
- **整批量日 Dry-run**：批量日重放 session 支持历史实例与调度计划两类演练候选，完整透传 dry-run、计划快照、独立幂等键和 Worker capability；Console、OpenAPI、五语言 SDK、Compose/Helm 开关同步完成，默认关闭。
- **Spring Boot 运行时治理**：补充启动失败诊断（FailureAnalyzer）、配置边界校验、自动装配条件测试、生命周期 phase、readiness/drain 状态和脱敏 `batchruntime` 诊断端点；Feature Switch registry 成为配置登记与 CI 校验入口。
- **控制面可观测性**：新增执行时间线读模型与 trace snapshot `timeline`，补齐 Redis 限流连续失败短路、Worker lease 熔断状态、消费背压 pause/resume、outbox/限流/租约告警及对应运维文档。
- **容量治理与自适应流控**：补齐 Trigger admission、Outbox 有界释放、Kafka lag 反馈和冷启动保护；增加 PostgreSQL 生产容量 profile、干净环境 preflight 与 10 万任务容量验证入口。

### Changed

- **控制面运维与可观测性**：维护状态改为数据库共享事实源，增加降级来源响应头、状态指标、容量告警和滚动升级操作说明；使用率写入与业务审计提交顺序保持一致。

- **本地启动与日志安全**：`start-all.sh` 只停止由本仓 runtime jar 占用的端口，优先 TERM 并仅在显式 `FORCE_KILL=1` 时强杀；当前日志目录自动归档压缩包和手工调试日志，重复业务告警增加时间窗聚合计数。
- **Compose 应用镜像与端口暴露**：本地应用镜像默认 tag 从 `local` 调整为 `dev`，避免与 Spring `local` profile 混淆；Console、Trigger、Orchestrator 与五类 Worker 默认统一绑定 `127.0.0.1`，需局域网访问时显式设置 `APP_BIND_IP=0.0.0.0`。
- **Console 启停 REST 契约**：资源队列、批次窗口、业务日历、配额策略、告警路由和 Pipeline 定义统一改为 `PATCH /{id}/enabled`，通过请求体显式提交租户与目标状态；前端及测试已同步切换，旧 `POST /{id}/toggle` 已删除，权限、审计、幂等和持久化语义保持不变。
- **SDK 与前端运行时及依赖治理**：Go SDK 最低版本升至 1.26，CI 覆盖 Go 1.26/1.27；Python 覆盖 3.12/3.14；TypeScript SDK 覆盖 Node 22/24；Rust、Go、Python SDK 锁文件依兼容范围刷新。前端默认构建运行时改为 Node 24，保留 Node 22 消费兼容，并增加声明与 CI 矩阵对齐门禁。

- **Maven 依赖与构建工具版本核查**：升级 Spring Boot、Spring AI、MyBatis starter、AWS SDK、OkHttp、PostgreSQL JDBC、POI、Commons Compress 及同主版本依赖补丁；Resilience4j 2.4 切换至 Boot 4 专用模块。同步 Maven 插件、Gatling 与 ArchUnit 版本，修复 SFTP SPI 示例基线，并更新技术栈与许可证基线。

- **基础设施镜像版本与部署说明**：PostgreSQL 固定到 `17.11`、Valkey 固定到 `8.1.10`，同步 Testcontainers、CI 预拉取、Sim、HA 清单及 Compose 默认值；基础服务手册改正 Redis/Valkey 漂移，并明确 MinIO 授权审查和替代后端生产验证尚未完成。Kafka `4.1.2` 仍在官方支持列表，本次不做跨次版本升级。
- **对象存储测试镜像来源**：本地 Compose、CI 预拉取和 Testcontainers 的 MinIO 测试镜像改为可匿名拉取的 `bitnamilegacy/minio:2025.7.23-debian-12-r1`，并将 repository 与 tag 一并纳入版本对齐守护；该镜像仅作为本地/集成测试过渡来源，不代表生产对象存储选型。
- **组合部署出站 HTTP 隔离**：Console 与 Orchestrator 的 `OutboundHttpTransport` 使用模块级 qualifier 显式绑定，避免 E2E 组合应用或未来同进程部署因存在多个实现而启动失败，并确保通知、验证码、治理、血缘和 Sensor 不会跨模块误用传输策略。
- **1.0.0 迁移基线重发**：在确认无共享或生产数据库使用旧 checksum 后，将 11 个历史迁移注释统一指向权威 `docs/agent-baseline.md`；基线重发后，CI 已恢复严格禁止修改历史迁移。部署必须重建空库后重新执行 Flyway，禁止对已有库执行 `flyway repair` 迁移该基线。
- **测试模块依赖边界**：多模块共享的多租户 SQL 种子迁入 `batch-test-support` 资源包，消费模块统一通过测试 classpath 加载；移除 Orchestrator 对 E2E 源码目录的反向引用，并将 `batch-e2e-tests` 的应用模块依赖明确限制为 test scope。
- **Trace 诊断与异步传播边界**：Trigger 和 Task Dispatch 的既有 Outbox 信封保存最小 W3C `traceparent` / `tracestate`，Relay 在 Kafka send 时恢复父上下文；业务 `traceId` 仍是最长 128 字符的持久化精确检索键，不参与幂等、租户或状态机语义。Console 401/403 同步返回关联 ID，Trace 快照补充截断领域和工作流节点运行数据；OTLP 仅承载 traces/logs，metrics 继续由 Prometheus 拉取。
- **工作流终态血缘可靠投递**：工作流状态机仍仅在业务事务内写 Outbox，Relay 将终态 payload 投递到独立 Kafka topic，OpenLineage 消费者仅在 HTTP 2xx 后提交 offset；端点故障只形成血缘积压，不回压或改写业务状态。
- **数据质量门禁运行模式**：新增 `ENFORCE`、`SHADOW`、`OFF` 三档配置；默认保持既有阻断语义，灰度模式继续执行并留痕但不阻断结果生效，关闭模式跳过规则查询与执行。跨表、跨日标量规则复用受限 SQL 校验路径。
- **Console 菜单与角色边界**：菜单注册表支持精确 `authorities` 白名单，前后端统一 ADMIN、TENANT_ADMIN、AUDITOR、TENANT_USER 和兼容 USER 的入口及写操作边界；未配置白名单的既有菜单继续按 `minRole` 回退。
- **密码更新提示语义**：`mustChangePassword` 从强制改密守卫收敛为非阻断提醒；账号可继续访问页面和执行已授权操作，自助改密仍清除提示并使旧会话失效，生产环境仍禁止内置默认密码启动。
- **Console 契约补齐**：认证画像与 token 响应补充首次登录改密标识，批次日重放增加 session 历史列表，租户配置包示例模板支持多场景组合；Webhook、自定义任务类型等既有响应改用精确 OpenAPI schema，保持原路径、权限和运行语义不变。
- **运行配置与测试基础设施统一**：PostgreSQL、Kafka、MinIO、Valkey 的 Testcontainers 创建入口和镜像版本统一管理，Shell 运行默认值集中维护并由 CI 阻止漂移；Worker 并发配置键和 Trigger/Worker 状态常量改为单一来源。密钥历史载荷迁移下沉为共享 Flyway callback，并收紧持久化约束和启动校验。
- **容量承诺口径**：容量画像新增并行任务墙钟耗时，记录/字节吞吐统一以最早开始到最晚结束的时间跨度计算；重任务容量承诺必须结合数据量、窗口、失败率和基础设施指标，不能只按控制面 tasks/s 外推。
- **Worker report 排空预算**：严格 10 万同代码 A/B 证明 `TASK_REPORT` 30000/min 可消除合法峰值下的 429 重试并提高 5.57% 完成吞吐；默认 report 高水位升至 30000/min，claim 保持 12000/min，Bucket4j 配置版本同步递增至 2。
- **容量画像配置签名**：P2 preflight 同时校验 Orchestrator 限流阈值/桶版本和 PostgreSQL checkpoint completion 参数；Compose 可显式注入 WAL/checkpoint 参数，普通本地与 Sim 默认值不变。
- **10 万容量基线口径固化**：隔离 benchmark 的 Atomic task-control 默认恢复为单一 Service 入口，双 Orchestrator 直连保留为显式 HA 分流实验；报告记录实际端点拓扑。最新严格轮次 100000/100000 成功，完成吞吐 `152.168 tasks/s`，生产配置不受影响。
- **控制面 REPORT 与压测基线收敛**：worker 回报入口合并 task/partition 点查，终态分区更新直接返回实例快照；移除无运行时查询命中的 Outbox payload GIN 索引，并将 benchmark Trigger admission/连接池恢复到已验证的 `32/40` 预算，降低数据库往返和写放大。
- **Console 热列表查询性能**：文件记录、操作审计、AI 审计等高频列表支持游标分页，收窄查询投影并补充精确 trace、模糊文件名、JSONB 到达组及游标排序索引；压测数据清理改用结构化运行标识过滤。
- **容量画像环境可比性**：P2 压测在发压前连续采样宿主负载，并在报告中固化负载发生器硬件/JDK与实际运行容器签名；8 核环境允许 `load1 < 15` 做稳定性复验，但只有 `load1 <= 6` 的轮次可与标准基线比较。入口请求稳定短缺时提前终止无意义的终态等待。
- **容量基线镜像来源约束**：标准 Compose/Bake 构建为应用镜像写入 OCI `org.opencontainers.image.revision`；P2 容量画像要求干净工作树，并校验 Trigger、双 Orchestrator 和 Atomic Worker 镜像与声明 revision 一致，禁止旧镜像或未提交代码生成可比较基线。
- **Java 镜像构建提速**：Dockerfile 增加全 reactor/单模块依赖闭包双路径；共享测试基础设施拆为 `batch-test-support`，镜像打包不再编译测试源码；补充 Buildx Bake 构建 DAG 与 GitHub Actions 远程缓存。
- **容器环境标识统一**：普通本地 Compose 栈固定使用 `batch-platform`，Docker 观测标签和 Kafka UI 集群名按环境注入，生产 Helm overlay 显式使用 `production`。
- **配置与运行参数治理**：运行时超时、SQL/配置边界和安全开关进一步外置；Compose、Helm、应用默认值、Feature Switch registry 和 CI 同步检查保持一致。
- **S3 兼容对象存储治理**：对象存储运行配置从本地 MinIO 绑定收敛为通用 S3-compatible 后端契约，补齐 endpoint、path-style、checksum、重试、桶自动创建和生产 fail-close 的 Compose/Helm/env 对齐；集成测试默认仍使用 MinIO Testcontainers，同时支持外部 S3 兼容端点，并新增 RustFS、SeaweedFS 和迁移一致性 POC 脚本。
- **安全门禁与依赖治理**：强化 Helm workload 安全检查、生产凭据 fail-close、SQL 解析边界、文件完整性校验和依赖许可证/安全扫描；示例项目与 SDK 依赖同步安全修复。
- **Worker 与控制面稳定性**：收紧 Pipeline 定义只读访问，修复 orchestrator HA 协调和 retry schedule 状态转换，稳定 Docker Sim、E2E fixture 与多模块构建链路。
- **Orchestrator 持久化与吞吐**：claim/report 主路径采用批量 CAS、`UPDATE ... RETURNING`、实例增量计数和按任务规模分级持久化，降低控制面数据库往返与重复聚合。
- **日志生命周期治理**：本地应用日志统一归入 `logs/app`，启动前归档到 `logs/archive/app`；清理脚本覆盖实时日志、归档和观测卷，并输出实际回收空间。
- **脚本与 SQL 边界治理**：批量抽离 CI、Sim、压测、DR 和本地验收脚本中的内联 SQL，建立递减预算守卫，运行时策略由 Java/配置层持有。
- **防漂移门禁闭环**：模块依赖、Shell 语法/ShellCheck warning 零容忍、脚本登记、仓库卫生和 changelog 同步进入 PR/full gate；文档检查扩展到图片、锚点、版本化目标和目录索引完整性；Zizmor 改为扫描全部 workflow/composite action。
- **CI 按需路由**：PR required workflow 保持稳定上报，统一由 `scripts/ci/detect-change-scope.py` 输出 Java、SQL、脚本、文档、配置、API、SDK、CI 和测试等变更域；各 workflow 按域执行专项检查，本地 pre-commit 同步采用暂存文件域路由，重型验证继续留在 pre-push / CI。
- **代码和文档规范**：统一后端错误/告警消息为英文，收口运行时常量、Java 格式化、FQN 违约和代码规模统计；README、工程计划、Runbook 和架构边界文档同步更新。
- **迁移与脚本注释规范**：统一历史 Flyway 迁移及 SH/PY/SQL 脚本说明注释的中文表达和头部格式，新增可手动运行的增量/存量扫描器。历史迁移内容变化会导致 Flyway checksum 改变；部署前须核对目标库迁移状态，并安排重建空库或经审查的 checksum repair。PR CI 持续执行迁移 checksum 校验和新增/修改迁移的安全 lint。

### Fixed

- 修复操作审计外层事务回滚后仍可能产生使用率幽灵计数的问题；派生聚合改为业务事务提交后写入，并补充真实提交时机测试。
- 修复控制台新增 Mapper 更新、清理 SQL 的租户守卫审计误报；AI 审计保留索引改为并发创建且保持 Flyway 单一非事务语义。

- 修复 Trigger Outbox 锁最长持有时间短于发布超时、以及 Orchestrator Outbox publisher future 永不完成时阻塞 ACK 等待的问题；发布超时现由业务配置兜底并按失败重试路径收敛。
- 修复告警升级通知在状态 CAS 前直接调用外部通知通道的问题；新增告警升级通知 outbox 表和发布服务，将升级状态写入与通知信封落库放入同一事务，避免单实例 at-least-once 和租约过期跨实例重复破坏通知契约。
- Dispatch 渠道适配器选择改为构造期 `channelType -> adapter` 注册表；同一官方渠道被多个 adapter 支持时启动失败，避免插件或 stub 依赖 Spring 顺序静默接管真实分发渠道。
- 显式管理 `okio-jvm` 版本，避免 `okhttp-jvm` 的传递依赖将 JVM 实现降级至与已管理的 Okio 版本不一致。
- 修复非旁路环境下 Spring Security 默认 XOR CSRF 解析与前端 Axios 明文 double-submit 协议不一致、导致已登录用户写请求统一返回 403 的问题；保留 cookie/header 双提交校验及现有忽略路径。
- 修复控制台纯日期结束筛选只精确到毫秒、可能遗漏业务日最后 999 个微秒内记录的问题；结束边界现取 PostgreSQL 可表达的最后一个微秒，并保持既有包含式查询语义。
- 修复 RLS 导入 COPY 临时表继承目标表生成列 NOT NULL 约束、导致省略自增 ID 时导入失败的问题；临时表现只包含映射列，并补充 PostgreSQL 集成回归验证。
- 修复每日定时验证延迟跨越北京时间午夜后按执行日期判断变更、导致漏跑前一计划日验证的问题；定时触发按最近计划时刻确定验证日期，手动触发仍使用当前日期。
- 修复 Import 在启用 RLS 的业务表上无法使用 PostgreSQL `COPY` 的问题：先 COPY 到事务内临时表，再通过目标表 `INSERT ... SELECT` 保留真实 RLS 策略校验；Process SQL 的 compute、validate、feedback 在同一业务事务中设置租户 GUC，并补充非超级用户/不可绕过 RLS 角色的 PostgreSQL 集成验证。Atomic Stage 5c 改为验证 loopback SSRF 拒绝及错误分类，避免依赖公网 endpoint。
- 修复内部接口认证与请求体限制可被 URL 规范化绕过、Dispatch/回执轮询及 Webhook 出站请求可触达受限地址、Webhook 重定向 SSRF 和超大错误响应占用内存的问题；限制 Kafka lag 元数据访问角色，修正登录失败计数清理键，并移除会泄露答案的自建验证码。
- 修复租户用户已开放运营概览菜单但摘要接口拒绝访问的问题；Outbox 重试日志响应补充关联事件 ID，控制台重投不再误用重试日志自身 ID。
- 修复操作审计参数可能持久化并返回密码、令牌或密钥的问题：写入和查询路径统一递归脱敏，V212 清理历史密码字段；同时将 V174 恢复为误改前内容，并收紧迁移门禁为仅允许精确恢复基线父版本，避免 Flyway checksum 漂移。
- 修复无效 `batch.timezone.default-zone` 静默回退的问题，启动期改为 fail-fast；`SecretPayloadProtector` 改由 common auto-configuration 注册并补装配测试，避免公共模块 Bean 扫描边界漂移；稳定 launch lifecycle IT 的 worker claim 前置状态等待和 worker 选择假设。
- 修复配置发布审批只更新发布单、不落运行时配置的问题；发布与回滚现复用严格配置应用事务，禁止同人审批和直接发布，版本分配使用规范化类型锁。密钥版本载荷改为加密存储并从查询/导出中脱敏，补充历史明文迁移、唯一约束及多节点迁移兼容；Redis 配置失效事件检测到版本缺口时执行全量本地缓存协调。
- 修复 Gatling 百分比严格比较无法表达零错误率的问题；Worker 严格压力测试现使用失败数断言并以 `0%` 为默认门槛，Dispatch 并发场景为每个虚拟用户分配独立文件夹具。压测数据按 `RUN_ID` 隔离，共享造数事务防止序列回拨；混合压力要求所有入口请求关联实例并全量成功，Process 故障画像重启本地 JVM 时统一转换依赖地址。
- 为本地 MinIO/Valkey 和生产 Redis HA 增加内存、持久化与 `noeviction` 边界，避免依赖进程无界占用宿主资源或静默淘汰正确性键。
- 修复 Helm 公共 JVM 参数固定 G1、同时为 Orchestrator 追加 ZGC 导致 JVM 选择多个收集器而无法启动的问题；GC 现按服务显式选择，并由生产 overlay 守护校验。
- 修复 DAG 节点在资源调度完成后才合并 `channelCode/resourceProfile`、从而可能绕过下游健康和资源画像门禁的问题；修复 Kafka topic 初始化变量替换内置 direct topic 的问题，自定义 topic 现在只追加且分区可显式扩容。
- 修复 Bucket4j 已持久化令牌桶不会随环境阈值更新的问题；限流配置现使用单调版本原子升级，按比例继承剩余令牌，并阻止滚动发布中的旧副本回写旧配置。
- 修复同一实例的并发 Worker 回报先锁 task、后等待实例 advisory lock，导致终态收敛与 partition reclaim 交错时形成 PostgreSQL `40P01` 锁环的问题；状态写入现统一遵循 `instance lock -> task -> partition` 顺序，并强化真 PG 并发回归守护。
- 修复关闭 `MANAGEMENT_OPENTELEMETRY_ENABLED` 后 Micrometer OTLP metrics 仍后台连接 Collector 的配置漂移；metrics、traces、logs exporter 现统一跟随总开关，未部署观测栈的本地与容量环境不再产生重试噪音。
- 修复 P2 容量 fixture 隐式依赖演示 seed、缺少源定义时仍继续发压的问题；fixture 现自包含创建 Atomic 作业并在发压前核验，同时避免不同 worktree 的应用定向启动误重建 PostgreSQL 容器，并拒绝非被测 Worker 污染 Atomic 容量基线；异常轮次统一清理入口按引用顺序回收控制面数据和专用租户孤儿记录。
- 修复 PostgreSQL Docker 客户端 fallback 将宿主机 `-f` 路径直接传入容器、导致无宿主机 `psql` 环境无法执行 SQL 文件的问题；公共入口现通过 stdin 传输文件并保留其余参数。
- 修复 replay service 手写兼容构造导致 Spring 无法创建 Bean、V202 候选约束遗漏 `OUTPUTS_ONLY` 形态及未 VALIDATE、DryRunGuard 架构测试扫描旧目录造成假绿；补齐 DRY_RUN 结果 7 天后先归档再清理的执行链，并禁止 Dispatch 演练写投递记录或推进正式文件状态。
- 修复 Docker 应用栈在全新数据卷上未先应用业务表 DDL/RLS，导致 `worker-import` 被闭世界检查反复重启的问题；启动脚本现在复用统一的业务库 bootstrap。
- 修复 Trigger 高压下 admission/relay 失衡、冷启动释放停顿和恢复残留；收紧批量日生命周期、Worker 状态更新及 dry-run/dispatch backoff 的租户 CAS 条件。
- 升级存在已知漏洞的嵌入式 Tomcat，并强化生产配置、凭据和 CI 安全门禁的 fail-close 行为。
- 修复日志归档路径、Grafana provisioning、XFF MDC 透传和观测脚本路径漂移。

### Docs

- 建立文档目录索引、历史快照与当前权威入口分离规则，并将链接完整性和文档结构检查纳入 CI。

---

## [1.0.0] - 2026-09-02

> 首个正式 GA tag。包含下方 1.1.0 / 1.2.0 开发里程碑已完成的能力；里程碑编号不代表独立发布。

### Added

- 完成 Console AI 助手、Checkpoint 断点续跑、五语言 Worker SDK、Worker 执行可观测性及工程能力对照与上线计划。

### Changed

- 统一后端、压测模块、OpenAPI、Helm 生产镜像和前端发布版本。
- 固化应用运行时治理、生产配置安全门禁和兼容性复扫入口。
- 拆分配置包 Excel 超大类并补特征测试；调度器统一为 Quartz JDBC JobStore。
- 精简 CI 安全扫描重复步骤，保留 Trivy 等实际门禁并缩短流水线耗时。

### Fixed

- 修复文件接收/分发完整性、对象存储长度边界及下游异常处理，阻止不完整文件继续流转。
- 修复 retry schedule、HA 协调、Pipeline 写权限、SDK direct-dispatch topic 及五语言 SDK offset/协议一致性问题。
- 修复静态分析、Spring Bean 装配、依赖许可证、安全门禁和本地 Sim/E2E 环境回归。

### Removed

- 前端停用 `/api/console/my-workers/count` 计数链路；后端端点标记为 deprecated 并暂保留兼容，待后续版本化弃用周期完成后再移除。
- 移除 Wheel scheduler 代码路径及双引擎切换逻辑，生产调度统一使用 Quartz；未来仅达到 ADR-033 阈值后重新评估成熟开源实现。

### Docs

- 新增工程化计划、能力对照、Feature Switch registry、观测栈、checkpoint、上线 readiness 和代码规模统计，并校正 Wheel 历史文档。

---

## 2026-07-13 — 1.2.0 开发里程碑（未发布）

> 自 1.1.0 开发里程碑（2026-06-24）累计 1,700+ commits；当时 BE 全链路验收 12/12 PASS（strict-verify 20/20）。该版本号未形成 Git release tag。

### Added
- **Console AI 助手生产收口**:只读诊断/告警分诊/DQ 草稿工具 + 诊断 RAG 语料;token 成本计量、每租户+用户限流(`ai:chat:tenant:{t}:user:{u}`)、模型故障优雅降级(#786-800)。
- **Checkpoint 断点续跑**:P0 默认启用 LOAD 行号/GENERATE 字节偏移续跑(补观测+幂等守护 IT,#806/#810);P1 阶段级续跑能力(PROCESS COMPUTE/VALIDATE 跳过,多分区降级守卫,开关默认关,#812-814)。
- **开源组件替换**:resilience4j 熔断、Bucket4j 分布式限流、Alertmanager 通知 sink(#774-777)。
- Worker 执行可观测性:实时行数/当前文件/分区失败/作业跳转 + checkpoint 命中指标(#806/#811/#816+)。

### Changed
- **console Map 响应收敛**:105 处 `CommonResponse<Map>` 换类型化 record + OpenAPI 真 schema(4 批 #801-804),前端 gen:api 契约漂移类 bug 根治;16 处真动态 key 保留并注明。
- REPORT 分区计数改轻量投影,消除 O(N²) report choke(#820);launch 消费扩容等控制面吞吐优化。
- ADR-038/V164 文档措辞校正:checkpoint 为「业务先 commit→位点后 advance+插件幂等」补偿式最终一致。
- 完成 `1.2.0` 开发里程碑的应用版本、部署镜像标签、OpenAPI 与 SDK 文档对齐；未形成正式 release tag。

### Fixed
- **安全**:dry-run 分号堆叠 RCE、租户 IDOR×3、SSRF 编码变体焊死(IPv6/十进制/IPv4-mapped)、通知限流补 tenant key、IM/SMS SSRF pin(#779-790)。
- **正确性**:outbox 任一 send 失败卡整批(B2)、outcome-vs-reclaim 残留死锁(NOWAIT 断环)、trigger outbox 守卫、apikey 缓存过期旁路(#783/#792 等)。
- **SDK 五语言**:offset withhold 失效(Go/TS/Rust)、TS 生产接线永不提交 offset、schemaVersion 畸形值统一 reject(#796-799)。
- `Map.of`/`List.of` 可空值 NPE 类定向清扫 + CI 守护(唯一反复复发的生产 NPE 模式,#822)。
- AiChat OpenAPI 契约漂移(sessionId 会话断/拒绝原因丢,#794)。

### Docs
- Checkpoint/断点续跑设计与分期施工、Alertmanager 直切迁移方案(#807);LOC 快照 2026-07-13(529k 行,test/main 0.77,#815/#823)。

## 2026-06-24 — 1.1.0 开发里程碑（未发布）

### Added
- 本地 Docker Compose 增加 Kafka UI 服务，默认端口 `18090`，连接同一 compose 网络内的 Kafka `kafka:29092`。
- MinIO 运维文档补充 `mc` 常用命令，覆盖 alias、bucket/object 查看、上传下载、同步、查找、删除和临时分享链接。

### Changed
- 应用版本从 `1.1.0-SNAPSHOT` 切到 `1.1.0` 开发里程碑，曾同步 Maven revision、load-tests、Helm appVersion、镜像 tag、OpenAPI 与 SDK 文档；未形成正式 release tag。
- 基础环境镜像版本对齐到同一模板入口；保留 `REDIS_IMAGE_TAG` 兼容旧命名，同时补 `VALKEY_IMAGE_TAG` 表达实际镜像。
- 版本升级脚本与 CI 对齐检查扩展为统一守护：后续 GA 发布必须同步应用版本、部署 tag、OpenAPI、SDK 文档、load-tests、基础镜像 tag 与 `CHANGELOG.md`。

---

## 2026-05-23 — 跨仓库版本引用对齐（infra/dep bump 收尾）

### Changed
- **`.github/actions/setup-build-env/action.yml`**：预拉镜像列表对齐 `postgres:16` → `postgres:17`、`minio/minio:RELEASE.2025-04-03T14-56-28Z` → `minio/minio:RELEASE.2025-09-07T16-13-09Z`
- **`batch-common/src/test/java/io/github/pinpols/batch/testing/MinIOContainer.java`**：Testcontainers MinIO image 字面量同步到 `RELEASE.2025-09-07T16-13-09Z`
- **`batch-orchestrator` / `batch-console-api` 集成测 3 处 `PostgreSQLContainer` 字面量**：`postgres:16` → `postgres:17`（`BatchDaySqlMigrationsIntegrationTest`、`LocalFlywayPlatformMigrationsIntegrationTest`、`ReadReplicaHappyPathIntegrationTest`；`SqlConsistencyIntegrationTest` 已先前对齐）
- **`docs/runbook/base-services-deployment.md`**：裸机 PG 安装提示 `brew install postgresql@16` → `postgresql@17`
- **`docs/runbook/partition-cutover-2026-05.md`**：cutover 演练 `docker run … postgres:16` → `postgres:17`
- **`docs/compliance/THIRD-PARTY-LICENSES.md`**：Okio 版本字段 `3.16.1` → `3.17.0`

### Notes
- 上游 commit：`66d074cf`（postgres 16→17 / minio bump）+ `f64be608`（okio 3.16.1→3.17.0）
- `docs/compliance/sbom.json` 本次**未重生成**：`mvn -P compliance cyclonedx:makeAggregateBom` 因 `batch-trigger` / `batch-worker-core` 的 `mockwebserver` dependency 缺 `version` 字段而 build 失败（预存遗留问题，非本次引入），手改 10 处 okio bom-ref / purl / version 不能同步 hash 字段，按 "JSON 不要手改大文件" 约束跳过，留待 sbom 单独修补 PR
- `docs/analysis/dependency-upgrade-evaluation-2026-05-23.md` 中描述 `RELEASE.2025-04-03 → 2026-Q1 release` 是当时评估记录，保留不改

---

## 2026-04-26 — 文档体系重构 + dict 自动化 + compliance 重生成

### Added
- **Diátaxis Reference dict**（`docs/dict/`）：错误码 dict（脚本生成自 `ResultCode.java`）、配置键 dict（Spring Boot configuration-processor 编译期自动生成 metadata.json）、业务术语 glossary（41 词手写上限）
- **`spring-boot-configuration-processor`** 加入根 pom annotationProcessorPaths：8 模块共 440 个 `@ConfigurationProperties` 字段编译期生成 IDE 可消费的 metadata.json；其中 5 个高频类（`BusinessDataSourceProperties` / `RetryGovernanceProperties` / `FileGovernanceProperties` / `ConsoleSecurityProperties` / `ConsoleAiProperties`）补全 javadoc → 156 个 keys 带 description
- **`scripts/codegen/gen-error-codes-dict.py`**（90 行 Python，零依赖）：解析 ResultCode 枚举生成 `docs/dict/error-codes.md`；CI（`pr-gate.yml`）加 `--check` 步骤，dict 与源不同步即 fail
- **mega 设计文档拆分剩余 3 章**：`design/tech-stack-and-principles.md`（ch.3）、`design/sla-and-quality.md`（ch.11）、`design/multi-tenant-and-security.md`（ch.15）—— 按当前代码状态校正（角色名 / 表名 / 类名以代码为准）
- **`archive/design/legacy-design-chapter-map.md`**：mega 8005 行 20 章 → 当前位置精确导航表（含 ✅ 12 / 🟢 3 / 🟡 5 状态标注）
- **9 个子目录 README**：每个子目录有编号化文件清单 + 角色路径 + 与其他目录分工，按 Diátaxis 分类
- **`docs/README.md`**：顶层文档导航入口

### Changed
- **mega 设计文档拆分与导航收敛**：原 8005 行正文拆分到当前 design、architecture 和 runbook 文档；章节映射由 `docs/design/legacy-design-chapter-map.md` 维护，不保留重复存根，原始正文可从 Git 历史追溯。
- **docs/ 100+ 文档按 5 个 Stage 整理**（A 临时产物清理 / B 重复对合并 / C 8000 行 mega 归档 / D scalability 历史归档 / E archive 命名规范）：128 → 100 .md
- **docs/ 全英文命名 + .md 强制后缀**：`改造分类.md` → `rework-classification.md`；6 文件 10 处引用同步
- **docs/ 根 5 → 4 文件**：`AGENT.md` → `agent-baseline.md`、`ci.md` → `runbook/ci.md`、`code-patterns.md` 合并到 `coding-conventions.md` §22
- **`docs/compliance/THIRD-PARTY-LICENSES.md`** 重生成：版本字段从 `1.0.0-SNAPSHOT` 改为 `${revision}`，加 `spring-boot-configuration-processor` + Netty HashedWheelTimer 条目，generated 日期 → `2026-04-26`
- **`docs/compliance/sbom.json`** 重生成：30 → 266 个 transitive 依赖（CycloneDX v1.6 完整版）
- **`design/tech-stack-and-principles.md` §3.4** 交付物表：NOTICE 状态 🟡待补 → ✅
- **`load-test-report-2026-04-25.md`** → `load-test-report.md`（去日期后缀，遵循"主干无日期 / archive 带日期"约定）

### Removed
- **`.flattened-pom.xml`** 从 git 跟踪移除并 gitignore（flatten-maven-plugin build artifact，每次 install 重生成）
- **本地 `.env`**（与 `.env.local` byte-identical 的误生成副本）+ 5 个 `.DS_Store`（macOS Finder 临时文件，已 gitignore 但本地残留）
- **15 个 deprecated 文档**（v1/v2/v3 老版本、临时跑测产物、对话日志归档）从主干进 archive/
- **`qodana.yaml`** 删除：JetBrains Qodana 静态分析配置，CI / Makefile / scripts 0 引用，已被 PMD + Spotless + spring-boot-configuration-processor 覆盖

### Notes
- 文档体系按业界中团队成熟度（Diátaxis + ADR + 自动 dict）落地，13 个 commit 覆盖
- 维护策略：dict 自动生成（CI 校验同步）+ 手写 glossary（≤50 词上限）+ design 散文档（Diátaxis Explanation 类）
- 业界对照：mega 不反更新（Linux / Spring / Kubernetes 都走过这条路），存根模式保留 anchor 兼容历史链接

---

## 2026-04-18 — 版本控制 & 构建脚本

### Changed
- 所有 pom 改用 Maven CI-friendly `${revision}`，根 pom 单点定义（默认 `1.0.0`），11 个子模块（`batch-common` / `batch-trigger` / `batch-orchestrator` / `batch-worker-*` / `batch-console-api` / `batch-e2e-tests` / `security-scan` / `load-tests`）统一引用
- 根 pom 装配 `flatten-maven-plugin`，install/deploy 前展开 `${revision}`，消除下游消费者拿到未解析 `${revision}` 的风险
- `scripts/local/build-apps.sh` 和 `docker/Dockerfile.app`：`-Dmaven.test.skip=true` → `-DskipTests`。前者会阻断 `batch-common:tests` 依赖链，后者只跳过测试执行、保留 test-jar 产物
- `scripts/ci/security-scan.sh` 硬编码 jar 路径改为 glob 匹配 `security-scan-*.jar`

### Fixed
- 设计文档（`docs/design/*.md`）、运行手册（`docs/runbook/security-scan.md`）、模块 README（`security-scan/README.md`）、docs/agent-baseline.md 中 `1.0.0-SNAPSHOT` 残留引用一并更新

### Notes
- `load-tests` 是独立模块（未纳入根 reactor），`${revision}` 无法继承；版本使用字面量，需与根版本手工同步

---

## 2026-04-17 — 枚举契约统一（DictEnum）

### Added
- **`DictEnum` 接口**（`batch-common/.../enums/DictEnum.java`）：统一 60 个业务枚举的 `code()` / `label()` 契约，附带静态工具 `fromCode(Class, String)` / `codes(Class)` / `labels(Class)`
- **`ConsoleMetaQueryService.buildEnums` 扩充 20 个枚举 key**：`priorityLevel` / `aiPromptDecision` / `checksumType` / `workflowJoinMode` / `fileDispatchRunStatus` / `fileDispatchStatus` / `fileReceiptStatus` / `pipelineRunStatus` / `compensationStatus` / `retryScheduleStatus` / `encryptType` / `compressType` / `errorSinkType` / `priorityBand` / `stepInstanceStatus` / `runMode` / `skipAction` / `workflowNodeRunStatus` / `deadLetterReplayStatus` / `skipThresholdMode`
- **守护测试 `ConsoleMetaEnumRegistrationTest`**：扫描枚举包，强制每个公共枚举二选一 —— 要么在 `REGISTRATIONS` 注册暴露给前端，要么加入 `EXCLUDED` 白名单并注明原因；同时断言所有公共枚举实现 `DictEnum`

### Changed
- 60 个枚举迁移到 Lombok 样式（`@RequiredArgsConstructor` + `@Accessors(fluent = true)` + `@Getter`），删除手写构造器和 `code()` / `label()` accessor；每个枚举从 20+ 行样板降到 ~10 行
- `ErrorSinkType` / `SkipAction` / `SkipThresholdMode` 的 bean 风格 `getCode()` / `getLabel()` 统一为 `code()` / `label()`
- `FileDispatchStatus` / `FileReceiptStatus` / `PipelineRunStatus` 三个裸枚举补齐 `code` / `label` 字段
- 5 个有特殊语义的 `fromCode`（`CatchUpPolicyType` / `WorkflowJoinMode` / `ShardStrategy` / `RunMode` / `FileStatus`）改为 `DictEnum.fromCode` 的薄包装，保留各自的"抛异常 / 默认值 / Optional"行为
- `ConsoleMetaQueryService.EnumReg` record 精简到 `(key, enumClass)` 两字段
- OpenAPI `CommonResponseMetaEnums` schema 同步补齐 20 个新 key
- `docs/agent-baseline.md` §领域数据字典 重写，追加 Lombok 样板 + 工具说明；新增 §版本管理、§变更记录两节

### Removed
- 35 个枚举各自的 `public static Set<String> codes()` 副本
- 3 个枚举各自的 `fromCode`（`ErrorSinkType` / `SkipAction` / `SkipThresholdMode`）

---

## 2026-04-16 — v6：安全防御 & 租户配置合包

### Added
- **`DnsResolveGuard` / `BlockedAddressException`**：阻断 webhook / callback 向内网地址（含 metadata 服务、loopback、link-local）外发，防 SSRF
- **`CallbackUrlValidator`**：校验 webhook URL 仅允许 http(s) 协议 + 非黑名单 IP
- **`ConfigPackageExcelValidator` / `ConfigPackageExcelWorkbookWriter`**：租户配置包 Excel 合包导入（8-Sheet：job_definition / file_channel / alert_routing / pipeline / pipeline_step / workflow_definition / workflow_node / workflow_edge）
- `GET /api/console/config/tenant-package/excel/export`：导出当前租户全量配置包
- `POST /api/console/config/tenant-package/excel/upload` / `preview/{token}` / `apply/{token}`：单事务导入 + 跨 Sheet 依赖校验
- `RequiresNewTransactionBoundaryIntegrationTest`：`@Transactional(REQUIRES_NEW)` 隔离语义的端到端验证
- `DispatchChannelHealthService` / `HttpDispatchChannelAdapter`：分发通道健康检查 + HTTP 通道适配器

### Changed
- 6 个 Excel 控制器（file-templates / file-channels / alert-routings / batch-windows / quota-policies / resource-queues）的 upload/apply 请求响应统一为共享 `ExcelUploadResponse` / `ExcelApplyResponse` / `ExcelApplyRequest` / `ExcelRowIssue`
- `ExcelApplyResponse` 新增 `skippedRows` 字段，`ExcelPreviewResponse` 新增 `previewWorkbookUrl`
- `ConsoleJobApplicationService` 拆分为 `ConsoleJobApprovalService` / `ConsoleJobRecoveryService` / `ConsoleJobTriggerService` 三个独立服务（`ConsoleJobOpsSupport` 持共享逻辑）
- 软删除改 `PATCH`：`POST /{id}/toggle?enabled=` 及 `POST /batch-toggle` 统一改为 `PATCH /{id}` / `PATCH /batch`，适用于 job-definitions / workflow-definitions / file-channels / file-templates
- `POST /api/console/files/delete` → `DELETE /api/console/files/{fileId}`
- list 查询 `enabled` 参数默认 `true`：四类列表接口不传 `enabled` 时只返回已启用记录
- 10 个独立 Excel 导入 Controller 的 upload / preview / apply 端点标注 `deprecated`，推荐改用 `/api/console/config/tenant-package/excel` 系列

### Added (Meta)
- `GET /api/console/meta/enums` 新增 3 个 key：`operationType` / `operationResult` / `fileStatus`
- `GET /api/console/meta/enums` 再新增 6 个 key：`taskStatus` / `partitionStatus` / `workflowRunStatus` / `approvalType` / `outboxPublishStatus` / `aiPromptCategory`；OpenAPI schema 由 `CommonResponseObject` 改为精确的 `CommonResponseMetaEnums`
- `GET /api/console/meta/biz-types?tenantId=`：按租户动态返回 `file_record` 中 distinct `biz_type`，用于文件列表下拉筛选
- `POST /api/console/tenants/batch`：批量建租户 + `initConfigFrom` 复制源租户全部配置；批量密码最低长度 12 位

### Removed
- `DELETE job-definitions/{id}` / `workflow-definitions/{id}` / `file-channels/{id}` / `file-templates/{id}`（软删除统一走 PATCH）
- `DELETE /api/console/users/{id}`（账号不可物理删除，走 disable / tenant suspend）
- `POST /api/console/users`（独立创建账号，改由建租户接口统一创建）

---

## 2026-04-15 — v5：Excel 应用服务批量重构

### Changed
- 10+ Excel Application Service 迁移到 `AbstractSingleSheetExcelService` 抽象基类，消除 upload/preview/apply 三段重复代码
- 涉及：`AlertRouting` / `BatchWindow` / `BusinessCalendar` / `FileChannel` / `FileTemplate` / `JobDefinition` / `Notification` / `PipelineDefinition` / `ResourceQueue` / `TenantQuotaPolicy` / `Workflow`
- `WorkflowRunEntity` / `BatchSecurityProperties` / `Stateful` 持久化基类细化

---

## 2026-04-11 ～ 04-12 — Spotless 格式化 & PMD 规则集 & 大规模清理

### Changed
- 全项目 Spotless 格式化 + PMD 规则集（`build/pmd-ruleset.xml`）入库，CI 阶段强制检查
- 大规模重排（单次提交 1466 文件 / 87k 行变更）：代码风格统一、注释补齐
- `org.springframework.boot:spring-boot-starter-parent` 升级链维护
- `.env.local` / `.env.test` / `.env.prod` 模板分离

---

## 2026-04-08 ～ 04-11 — 系统优化（36 次提交，跨 4 天）

### Added
- 通知订阅中心（`ConsoleNotificationController` + V49 迁移）：channel CRUD（EMAIL / DINGTALK / WECOM / WEBHOOK / SMS）、subscription rule CRUD、delivery logs、测试通知
- 配置审批流（`ConsoleConfigApprovalController`）：submit → approve / reject 状态机 + 审计轨迹
- 跨环境配置同步（`ConsoleConfigSyncController`）：export / preview / import 配置包 + 同步日志
- `ConsoleOutboxOpsController`：stats / cleanup / republish
- SLA 报告端点 `GET /api/console/dashboard/sla-report`
- 文件错误记录 CSV 导出 `GET /api/console/files/{fileId}/errors/export`
- Job 定义克隆 `POST /api/console/job-definitions/{id}/clone`
- Excel 导入跨引用校验（queue / calendar / window code 存在性检查）
- OpenAPI SDK 生成 profile（`openapi-codegen`）+ `scripts/ci/generate-sdk.sh`
- `.github/actions/setup-build-env`：复用构建环境（JDK / Maven 缓存）的 composite action

### Changed
- `62bc3fb` — 全仓 FQN → import 替换（39 文件）：禁止 `java.util.concurrent.TimeUnit.SECONDS` 这类写法
- PMD `ExcessiveParameterList` / `NcssCount` / `AvoidDuplicateLiterals` 违规基线治理

---

## 2026-04-05 ～ 04-07 — SSE & 观测性 & Docker

### Added
- Console 实时推送体系（`ConsoleRealtimeProperties` / `ConsoleRealtimeRedisPubSubConsumer` / `ConsoleOpsSummaryRealtimeStream` / `ConsoleWebhookDomainEventListener`）基于 Redis Pub/Sub + SSE
- `ConsoleRealtimeReplayStore`：重连后补发最近 N 条事件
- `BatchConsoleApiApplication` 装配 + Docker Compose 重组（`scripts/docker/`）
- Makefile 入口 `dev-restart` / `dev-up` / `dev-down`

### Changed
- `DefaultConsoleAlertApplicationService` / `DefaultConsoleFileTemplateApplicationService` 拆 Excel 专职服务
- `batch-defaults.yml` 新增 SSE / realtime 相关配置
- README 重组，指向测试和 API 文档索引

### Fixed
- 2026-04-06 ～ 04-07 "测试类修复" 波次：7 个测试类断言修正 + 环境变量 / fixture 稳定性

---

## 2026-04-01 ～ 04-03 — API & 参数重构

### Changed
- 179 文件 / 11648 行变更（一次提交）：Query record 工厂方法规约落地（`ofTenant` / `ofDefinition` 等），禁止调用处写 `null` 参数
- `BatchKmsProperties` / `BatchSchedulingProperties` / `BatchSecurityProperties` 等 config properties 拆分
- `docs/agent-baseline.md` 首版：方法参数约束（≤ 6）、FQN 禁令、分支消除规则、API 文档同步约束

---

## 2026-03-29 ～ 03-31 — 调度引擎 & 补偿 & 重试治理

### Added
- `DefaultScheduleForwarder` / `ScheduleForwarder` / `ScheduleForwarderResult`：调度决策 → Forwarder 链路
- `TokenBucketRateLimiter` + `TenantActionRateLimiter`：租户级动作限流
- `DefaultCompensationService` / `DefaultRetryGovernanceService`：补偿 & 重试治理主服务
- E2E 测试矩阵：`DedupJobLaunchE2eIT` / `DispatchPipelineE2eIT` / `ExportContentVerificationE2eIT` / `ImportFailureE2eIT` / `MultiTenantConcurrentE2eIT` / `OutboxForwarderE2eIT` / `WorkerDrainE2eIT` 等 10+

### Changed
- Flyway 迁移整理（29 文件）：多版本合并 + 测试夹具 SQL 收敛到 `PlatformTestdataSql`
- 注释 / 命名统一整理：`RunMode`、`BatchDeadLetterMessage`、`BatchRetryMessage`、`WorkerRouteModel`、`StructuredLogField` 等

### Fixed
- M-10 `CatchUpPolicyType.fromCode` 未知 code 抛 `IllegalArgumentException`（保留 `fromCodeOrDefault` 兼容容错场景）
- L-3 `WorkflowJoinMode.fromCode` 同语义对齐

---

## 2026-03-28 — 安全扫描与 GitHub CI

### Added
- **`security-scan` 独立模块**：`SecurityScanApplication` + `ScanMode` / `ScanStep` / `ScanReport` / `ExternalCommand` / `ProcessCommandExecutor`，调度现有扫描工具（不自研扫描器）
- `scripts/ci/security-scan.sh` + `docs/runbook/security-scan.md`
- GitHub Actions workflows：`pr-gate.yml` / `staging-gate.yml` / `full-ci-gate.yml`
- 127 文件 / 8375 行 `test& docs` 大批量测试和文档补齐

---

## 2026-03-26 ～ 03-27 — 导出插件体系 & SQL 任务

### Added
- `ExportDataPlugin` / `WorkerPluginIds` / `ExportDataPluginRegistry`：导出数据插件化
- `SettlementExportDataPlugin`（清算导出内置）+ `SqlTemplateExportDataPlugin`（SQL 模板导出）
- `SqlTemplateExportSpec` / `SqlTemplateExportSqlValidator`：SQL 模板校验
- E2E 测试夹具 `export-template-config-seed.sql` / `import-template-config-seed.sql`
- 6 个 SQL 结构任务（sql & task1→6）：schema 调整 + 索引

---

## 2026-03-25 — P1-P3：审批台账 & 优雅启停 & 设计模式

### Added
- **审批台账产品化**：`ApprovalCommandEntity` / `ApprovalCommandQuery` / `ApprovalCommandMapper` + `ConsoleOpsController` 审批列表 / 详情接口
- **优雅启停**：Spring `SmartLifecycle` + `DisposableBean` 双保险，保证 Worker drain → Orchestrator stop → Trigger stop 的有序退出
- `ContentMaskingUtils`：错误记录 / 错误文件 / 日志的敏感内容脱敏
- `@ValidBizDate` / `@ValidTenantId` JSR-380 自定义校验
- Query / Request record 全家桶：`DeadLetterQueryRequest` / `OutboxDeliveryLogQueryRequest` / `JobDefinitionQueryRequest` / `CompensateRequest` / `CompensationCommandRequest` 等

---

## 2026-03-24 — P0 & E2E 测试框架

### Added
- `ApprovalWorkflowIntegrationTest` / `MultiTenantIsolationIntegrationTest` / `OutboxPublishIntegrationTest` / `SchedulingDecisionLaunchIntegrationTest`：Orchestrator 核心集成测试
- `OutboxForwarderRetryE2eIT`：Outbox → Kafka 重试全链路
- `StructuredLogField`：结构化日志字段（traceId / spanId / tenantId / jobInstanceId）
- `AbstractPipelineStepExecutionAdapter` / `AbstractTaskConsumer` / `StageExecutionContext`：Worker 执行阶段抽象
- `AbstractIntegrationTest` + `OrchestratorWireMockSupport`：集成测试基础设施
- Platform init SQL + test application.yml

---

## 2026-03-23 — 结构重组

### Changed
- 139 文件重组：module 间 package 边界校正
- 枚举补齐：`AlertStatus` / `ApprovalCommandStatus` / `CompensationCommandStatus` / `FileDispatchRunStatus` / `FileDispatchStatus` / `FileReceiptStatus` / `PipelineRunStatus` / `StepInstanceStatus`
- `BusinessDataSourceProperties` / `MinioStorageProperties` / `OrchestratorApiPaths`

### Fixed
- 3 个端到端测试 bug：事务边界、数据隔离、异步等待

---

## 2026-03-22 — v1-v4：核心业务骨架

### Added
- **Console 应用服务族**：`ConsoleAiApplicationService` / `ConsoleConfigApplicationService` / `ConsoleFileApplicationService` / `ConsoleJobApplicationService` / `ConsoleQueryApplicationService` 及各自 `Default*` 实现（281 文件，10911 行）
- 枚举补齐首轮：`AiPromptCategory` / `AiPromptDecision` / `ConfigLifecycleStatus` / `ErrorSinkType` / `ShardStrategy` / `SkipAction` / `SkipThresholdMode`
- `META-INF/spring-configuration-metadata.json`：IDE 配置提示
- 测试补齐（`add tests` 波次）：单元测试 + 部分集成测试骨架

---

## 2026-03-21 — 项目初始化（init）

### Added
- 多模块 Maven 骨架：`batch-common` / `batch-trigger` / `batch-orchestrator` / `batch-worker-core` / `batch-worker-import` / `batch-worker-export` / `batch-worker-dispatch` / `batch-console-api` / `batch-e2e-tests`
- 通用基础设施：
  - `context` — `AuditContext` / `ExecutionContext` / `TenantContext` / `TraceContext`
  - `dto` — `CommonResponse` / `LaunchRequest` / `LaunchResponse` / `ResponseMeta` / `TaskMessage`
  - `logging` — 结构化日志锚点
  - `plugin` — 插件接口族
  - `constants` — `BatchStatusConstants` / `CommonConstants`
- 技术选型：Spring Boot 4.0.3 + MyBatis + Flyway + Kafka + Redis + MinIO（首版骨架曾含 Spring Data JDBC，已于后续版本移除，现全平台统一 MyBatis）
- 架构主链：`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`（文档化）
- `AGENT.md` + `.env.example` + `.gitignore`
- 477 文件 / 30430 行，奠定整体形状

---

## Notes

- 版本通过 Maven CI-friendly `${revision}` 在根 pom 统一控制（默认 `1.0.0`）。构建期覆盖：`mvn -Drevision=X.Y.Z ...`
- 更细颗粒度的 API 层变更详见 `docs/api/console-api-protocol.md` 的 Changelog 表
- 更细颗粒度的编码规范 / 架构变化详见 `docs/agent-baseline.md` 的 §变更记录
