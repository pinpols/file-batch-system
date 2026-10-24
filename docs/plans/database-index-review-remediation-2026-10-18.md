# 数据库索引逐表审查与整改方案

> 审查日期：2026-10-18。状态：**全表结构与查询路径审查完成，整改待实施，容量收益未验收**。属于系统问题整改，独立于[引用完整性](./database-reference-integrity-remediation-2026-10-18.md)和[分区生命周期](./database-partition-lifecycle-remediation-2026-10-18.md)两个专题；纳入[总方案](./workflow-daily-integration-plan-2026-10-14.md)，唯一当前状态见 [TODO Master](../analysis/todo-master.md) 的 `ALIGN-DB-INDEX`。

## 1. 范围、结果与证据边界

按两个实际数据库的所有非系统 schema 逐表审查，覆盖 **176 张逻辑表、589 个逻辑表索引**；包含分区子索引后共有 **1,634 个索引目录对象**。分区父索引是虚拟对象，不能把目录对象数直接当作磁盘索引数量或膨胀量。

| 分工 | 逻辑表 | 逻辑表索引 | 正文位置 |
|---|---:|---:|---|
| 运行、调度、业务日、告警 | 52 | 213 | 附录 A：逐表矩阵及每个索引定义 |
| 文件、流水线、事件、资产、全部冷表 | 56 | 180 | 附录 B：逐表矩阵及每个索引定义 |
| 配置、身份、通知、Quartz、业务库 | 68 | 196 | 附录 C：逐表索引及判断 |
| 合计 | **176** | **589** | 所有索引的完整实物定义另见 CSV |

- **结构健康**：未发现 invalid / not-ready / not-live；1,045 个子索引与所属父索引逐一挂接完整，没有缺项或额外挂接。详见分区专题。
- **没有完整定义完全相同的重复索引组**。`calendar_holiday` 有一组同键访问重叠，区别为普通索引与唯一约束索引；GIN 谓词重叠、左前缀、正反扫描均单列候选，不混称完全重复。
- **有需要整改的查询形状与索引契合问题**：日期列转换影响分区裁剪、Webhook 重试状态集合超出现有 partial 谓词、冷谱系关联仅有 PK、运行恢复/清理及 FK 子侧的前缀或排序缺口。
- **不是所有候选都必须加索引**。先按真实调用、选择性、索引尺寸、写入量、父键修改/删除频率决定；小配置表和已有选择性前缀不能机械扩索引。

### 1.1 实际基线

环境为本机隔离 PostgreSQL 17.11，平台库 `batch_wdi_s20_20261018_platform` 与业务库 `batch_business`。索引目录时间分别为 **09:06:34.530296 / 09:06:35.086901 +08:00**，该轮实例启动于 **09:02:08.832768**，观测仅约 4 分钟；`stats_reset=NULL` 不表示跨实例累计历史。平台 557、业务 32 个逻辑表索引；含子索引分别为 1,533 / 101 个。

索引定义按数据库/schema/表名逐一与 08:54 的全结构快照比对，一致。期间容器发生外部重建，不能将不同进程的统计拼成连续负载。源码为 feature 分支 HEAD `ee01e97515b04fd167ed17977535ec5cab8572f8` 加未提交工作区，详细源码摘要和 Flyway V291 校验见引用专题；后续实施先复核并行修改后的相关文件。

[索引目录 CSV](../audit/database-index-catalog-2026-10-18.csv) 共 1,634 行，保留定义、索引父子关系、方法、唯一性、排序/NULL options、opclass/collation、INCLUDE 数、表达式/partial 谓词、约束依赖、有效状态、尺寸及短窗口统计。统计是采集值，不是“未使用”结论。SQL 内状态字面量或参数的取舍仍遵守仓库既有业务分类语义，不能为适配 partial 索引改变业务状态集合。

### 1.2 已取得的只读计划证据

[日期裁剪 EXPLAIN 原件](../audit/database-job-date-pruning-explain-2026-10-18.json) 采于 **09:13:53 +08:00**，当时实例启动于 **09:11:29**，再次核实 PostgreSQL 17.11 / V291。使用 `BEGIN READ ONLY`、5 秒 statement timeout、1 秒 lock timeout，仅运行 `EXPLAIN (FORMAT JSON)`，**没有 ANALYZE、业务行读取或写入**。

| 同一表、同一租户常量、同一日期 | 计划包含的关系 | 能证明的内容 |
|---|---:|---|
| `cast(biz_date AS varchar) = '2026-10-18'` | 37 个，包括 DEFAULT | 当前查询形状未按原生日期边界裁剪 |
| `biz_date = '2026-10-18'::date` | 1 个：`job_instance_p_2026_10` | 参数保持 DATE 比较可裁剪到当月 |

对应 [Console JobInstanceMapper](../../batch-console-api/src/main/resources/mapper/JobInstanceMapper.xml) 的列表与计数条件（本次第 89、156 行）。这是简化查询的计划形状证明；尚未修改 Mapper，未测完整分页、通用/自定义预编译计划或实际耗时。其他索引候选尚无代表性负载计划；附录中“未运行 EXPLAIN”指该分工自身，以上总控补充证据除外。

## 2. 实施队列与关闭条件

| ID | 级别 / 状态 | 必须处理的内容 | 责任与验收 |
|---|---|---|---|
| IDX-01 | P2，查询问题已确认，待改 | JobInstance 列侧字符串转换改为 DATE 绑定或参数侧转换；保持非法日期处理、日期口径及列表/count 一致 | Console / 数据库；完整 Mapper 结果、分页、跨日及裁剪计划，关联 PART-10 |
| IDX-02 | P2，谓词漂移已确认，待设计 | Webhook 重试查询含 PENDING+EXHAUSTED，现 partial 仅 EXHAUSTED；按真实队列和计划决定新 partial 或全量键，不缩减待处理状态 | Console 通知；两状态可靠领取、SKIP LOCKED、公平顺序、prepared generic/custom 计划及积压扫描 |
| IDX-03 | P2，热点候选待测 | Workflow stuck/归档、Task 超时、lease 回收、重放等待、告警、补偿、SUPERSEDED、Outbox 等，分别核过滤、排序、LIMIT、锁行数 | Orchestrator / Trigger / Worker；附录 A IX-R3、B M2。正常与最坏允许积压都测，不为少排序改变调度顺序 |
| IDX-04 | P2，关联访问候选待测 | FK 子端、Workflow node 定义查询、file_dispatch/file_audit 清理、冷 pipeline/dispatch 谱系与重放 | 与 REF-01–06 同切片；父删除/更新、冷热查询、并发归档锁耗时与约束语义；避免先加旧单列再加复合键 |
| IDX-05 | P2，分页与清理契约待收敛 | 配置/审计历史无界或排序不一致、时间排序缺唯一后缀、AI 对象清理队列、push TTL | Console；先限定分页/批量契约，再测索引。保留稳定游标、租户隔离、清理进度与失败重试 |
| IDX-06 | P3，访问重叠候选待测 | calendar_holiday 同键普通/唯一、各严格前缀、GIN full/partial、正反扫描与冷镜像索引 | 数据库 / 模块负责人；保留 PK、UNIQUE、FK 目标、ON CONFLICT 和业务幂等语义。证明无回归及写入/空间收益后再删普通索引；Quartz/Flyway 框架默认保留 |
| IDX-07 | P2，专项计划待补 | JSONB 表达式/partial 谓词证明、混合排序、低选择性全局扫描和大/小租户计划偏差 | 按真实参数、NULL 比例、状态倾斜及 prepared plan 核实；不笼统要求 JSONB 建 GIN，不以字段相同认定索引覆盖 |
| IDX-08 | 随实施验收 | 索引上线构建、失败残留、回退、分区扩展继承、统计刷新和写入代价 | 与 PART-08/09 配套；记录构建/锁/WAL/体积/更新开销、invalid 残留处置。真实环境审批和执行另行处理 |

建议顺序：先处理 PART-01 的隔离边界、REF 的数据完整性和 PART-02 的清理安全；同步完成 IDX-01/02 及主链热点测量，再按数据选择索引；最后收敛低风险重叠索引。P2/P3 候选未测不能统统计作发布阻断，也不能统统标为已关闭。

## 3. 性能与迁移验收要求

1. 为每项记录调用 SQL、参数分布、数据与分区数量、构建版本、数据库参数、统计时间窗；大/小租户、正常/积压、近期/历史日期分别覆盖。
2. 完整查询核 `EXPLAIN (ANALYZE, BUFFERS)` 的实际行数、过滤行、Sort/临时写、扫描分区、候选与锁定行比例；含写入/锁/删除的语句须在授权的隔离数据环境验证，不能把 EXPLAIN ANALYZE 当无副作用查看计划。
3. 比较新增/替代/保留方案的读收益与写入/WAL/空间成本。参数化 partial 谓词须同时检查 generic/custom 计划；无收益则记录保留现状的依据。
4. 分区父索引与子索引按 PostgreSQL 17 支持的构建/挂接流程处理，不直接对分区父表套普通表的并发创建脚本；FK/唯一目标的依赖必须完整核查。[PostgreSQL 17 分区维护说明](https://www.postgresql.org/docs/17/ddl-partitioning.html#DDL-PARTITIONING-DECLARATIVE-MAINTENANCE)
5. 与分区及引用整改合并验收数据完整性、跨租户隔离、归档/重放、业务日、调度公平性、长事务与恢复，不把“用了索引”作为通过标准。

本轮没有改 SQL/Java/迁移，没有建删索引，没有运行测试、ANALYZE、VACUUM 或容量压测；已执行的检查仅目录核对、只读计划和文档检查。以下三个附录为全量清单，所有未列为候选的索引按现状保留，并不代表生产规模下已证明最优。


## 附录 A：运行、调度、业务日与治理表索引审查

### 证据范围

- 基于 `/tmp/fk-audit-20261018/platform-index.json` 最新 09:06:34 catalog（新重建独立 PostgreSQL 17.11），与旧 `platform.json` 的列/FK形状对照。
- 覆盖 **52 张 batch 逻辑表、213 个逻辑索引**，没有把分区子索引重复计数。有效性检查：`valid/ready/live` 异常 **0**。数据与 idx_scan 仅新实例约4分钟窗口，大多数业务表为0行；**不能据此判断无用索引或生产性能**。
- 已读取 `sql-query-performance`、`database-migration-safety`；只做结构与代码对照，未执行查询/EXPLAIN/测试/DDL/DML。下列新增/删除都只是待验证候选，不是本轮执行授权。
- `O/`=`batch-orchestrator/src/main/resources/mapper/`；`T/`=`batch-trigger/src/main/resources/mapper/`；`C/`=`batch-console-api/src/main/resources/mapper/`。行号对应当前 feature 工作区。
- PK/UNIQUE/排他约束、被FK引用的目标索引、ON CONFLICT依赖的唯一索引必须优先保留；`indexes.constraints` 已包含引用方FK，不能只看自身是否创建为constraint。
- tenant前导适合同租户查询；全局调度/归档扫描有合法跨租户入口，不能机械给所有索引加tenant前缀。范围过滤和业务规定排序冲突时，不能为命中索引擅自改公平性、优先级、按日推进或锁顺序。

### 逐表结论

| 表 | 索引数 | 关键访问路径、当前匹配 | 处置/待验证 |
|---|---:|---|---|
| `alert_escalation_notification_outbox` | 3 | C/AlertEscalationNotificationOutboxMapper.xml:47–62：全局 status IN + next_publish_at范围，按next_publish_at,id；PUBLISHING恢复按updated_at,id。现poll为(status,next_publish_at,id)。 | 保留PK/唯一去重；双状态范围不能直接保证全局时间排序，stale恢复无updated_at路径。候选pending partial(next_publish_at,id)、publishing partial(updated_at,id)，需负载+generic/custom计划后择优，不能盲加2个。 |
| `alert_event` | 6 | C/AlertEventMapper.xml:34–69：tenant条件，游标id降序/普通last_seen_at降序；O/AlertEventMapper.xml:74–93为全局OPEN超时；C:129–135按escalated_at通知。tenant,last_seen与OPEN,last_seen partial已覆盖部分。 | 保留dedup唯一、OPEN扫描；候选tenant,id游标路径；升级通知escalation_tier>notified_tier不等于现双列键可定位范围，需评估partial(escalated_at,id) predicate含未通知条件。普通分页仅last_seen无id tie-breaker另列稳定分页整改，索引不能独自修正。 |
| `alert_routing_config` | 6 | C/AlertRoutingConfigMapper.xml:4–46：tenant+未删除+可选team/filter，按id DESC；已有tenant,id DESC，tenant,team和unique tenant,route。 | 保留唯一与列表索引。`idx_alert_routing_config_tenant`是多个tenant前导索引的前缀重复候选；active partial更小不直接当重复。%keyword%不由普通btree解决；小配置量暂不加GIN，按规模计划决定。 |
| `approval_command` | 3 | C/ApprovalCommandMapper.xml:27–53：tenant+可选status，created_at DESC；现(tenant,status,created_at DESC)。 | 指定status时匹配；不指定status需排序，候选(tenant,created_at DESC,id DESC)只有在列表实际大时评估。分页需增加唯一tie-breaker后验证，不为所有可选字段建索引。 |
| `archive_policy` | 2 | C/ConsoleArchivePolicyMapper.xml:20–29 tenant按target_table及tenant+table点查；unique(tenant,target_table)直接匹配。 | 保留现状，无新增依据。 |
| `batch_day_catchup_control` | 1 | O/BatchDayCatchupControlMapper.xml:20–30 tenant+calendar点查/FOR UPDATE，复合PK完全匹配。 | 保留现状，避免重复tenant/calendar索引。 |
| `batch_day_catchup_control_event` | 2 | O/BatchDayCatchupControlMapper.xml:50写事件；recent索引(tenant,calendar,occurred_at DESC,id DESC)。当前生产Mapper未检出独立读入口。 | 保留PK和有明确审计用途的recent索引；无查询命中不是删除依据，待审计API/运维查询清单及长期统计确认。 |
| `batch_day_instance` | 4 | O/BatchDayInstanceMapper.xml:84–106 tenant/calendar/date范围、max(date)；unique完全匹配。全局status/day扫描有status索引；150–182失败补日按settled_at,id。 | 保留unique和status；FAILED补日候选partial(settled_at,id)但先测FAILED基数与EXISTS子查询成本，不能只凭排序差异加索引。 |
| `batch_day_open_cursor` | 1 | O/BatchDayInstanceMapper.xml:115–128 tenant+calendar FOR UPDATE，PK完全匹配。 | 保留现状。 |
| `batch_day_operation_audit` | 3 | O/BatchDayOperationAuditMapper.xml:62–71 tenant+date，可选calendar，按created_at DESC,id DESC；现tenant,calendar,date,created_at及tenant,operation_type,created_at。 | 指定calendar能过滤，id tie-breaker仍可能sort；不指定calendar/date被中间列隔断。若跨日历审计频繁/量大，候选tenant,biz_date,created_at DESC,id DESC；低量保留排序。 |
| `batch_day_replay_entry` | 5 | O/BatchDayReplayEntryMapper.xml:58–72 session(+tenant/status)按id；103–114 rerun/source定位；130–133 session,status count。session,status索引覆盖筛选/计数，两个partial唯一保证来源幂等。 | 保留2个partial UNIQUE，不能按读统计删。候选session,status,id分页；source查询没有 `result_version_id IS NULL`，不能保证用source partial，需EXPLAIN/补合法谓词而非盲加全量index。 |
| `batch_day_replay_preview_token` | 3 | O/BatchDayReplayPreviewTokenMapper.xml:27–32 tenant+hash锁匹配PK；52–64过期/consumed OR筛选，coalesce时间排序。两个时间索引有过滤价值。 | 保留；批量清理OR+coalesce排序待EXPLAIN，若热点可分两路有界候选再合并排序，先保持删除语义。不据OR就盲加表达式索引。 |
| `batch_day_replay_session` | 4 | active partial UNIQUE匹配O/BatchDayReplaySessionMapper.xml:66–72；100–104全局status按created_at,id，107–114 tenant可选status。 | 全局扫描不匹配tenant前导；候选(status,created_at,id)，若PENDING/RUNNING低比例再评估partial。tenant全状态recent也不能跳过现status键排序。active唯一保护并发重放必须保留。 |
| `batch_day_waiting_launch` | 3 | O/BatchDayWaitingLaunchMapper.xml:56–83 request点查匹配unique；status(+tenant)按id；status+tenant+calendar+date按id。现状态索引(status,tenant,calendar,date)。 | 筛选可用但排序不完整；全局/租户释放候选(status,id)或tenant范围优化，需要真实扫描策略和公平性数据，不能都加。 |
| `batch_window` | 4 | C/BatchWindowMapper.xml:4–40 tenant+可选enabled，id DESC；tenant,id DESC、tenant,enabled及tenant,window唯一均有用途。 | 保留；配置小表不因可选enabled+排序再机械增加组合索引。 |
| `calendar_dependency` | 3 | O/CalendarDependencyMapper.xml:39–52 tenant+downstream+enabled、tenant全量按id；现downstream索引匹配。 | 保留，关系小集合排序无新增依据；upstream/downstream unique是关系语义。 |
| `calendar_dependency_wait` | 3 | O/CalendarDependencyWaitMapper.xml:74–83 tenant+calendar+WAITING+last_checked_at范围，但按biz_date,id锁定；due索引(tenant,calendar,status,last_checked_at,id)。 | 范围筛选有用、按biz_date仍可能sort。候选按biz_date/id的WAITING partial与当前due索引比较扫描行数/锁等待；不能擅改按业务日推进顺序。recent CASE排序117–129优先保留小集排序。 |
| `calendar_group` | 2 | PK和unique tenant,group_code；当前Java/Mapper只有group配置引用，未检出独立批量查询。 | 保留业务唯一；无需猜测新增索引。 |
| `calendar_holiday` | 5 | C/CalendarHolidayMapper.xml:5–12按calendar_id,date，无tenant；现idx_tenant与uk均(tenant,calendar_id,biz_date)；另date/type和GROUP partial支撑全局/分组节假日。 | **确证同键访问重叠候选：删除候选仅idx_calendar_holiday_tenant，保留约束uk_calendar_holiday。**运行calendar-only路径/FK calendar_id缺左前缀：优先把已解析tenant传入，或评估(calendar_id,biz_date)。绝不删unique换普通索引。 |
| `disaster_day_override` | 3 | O/DisasterDayOverrideMapper.xml:35–44 tenant+calendar+date，再检查生效/TTL；uk_disaster_active为该三列**无partial**唯一，最多1行。 | 保留业务唯一；idx_disaster_lookup三列再加ttl的读覆盖很可能冗余，列为前缀候选而非直接删除；需核对运维TTL批扫与计划。 |
| `expected_work_occurrence` | 3 | O/ExpectedWorkOccurrenceMapper.xml:53–83身份+generation更新/最大代次，tenant+calendar+date+status结日计数；现identity unique和day_status匹配。 | 保留；FK(tenant,workflow_definition_id)被calendar隔断，无精准前缀，若定义删除/改关键键路径被使用再评估配套索引；不以低频配置删除机械加热写索引。 |
| `external_event_delivery` | 4 | T/ExternalEventDeliveryMapper.xml:49–55,82–88 tenant+source+event点查/锁、request点查；3个unique分别身份、request、FK目标tenant+id。 | 全保留，tenant+id被alias FK引用；JSON冻结目标列表为单行点查后成员判定，当前无全库JSON检索，不加GIN。 |
| `external_event_delivery_alias` | 2 | PK(tenant,idempotency_key)匹配alias查；(tenant,delivery_id)匹配父删除及连接。 | 全保留，子FK配套完整。 |
| `external_event_expectation_definition` | 3 | O/ExternalEventExpectationDefinitionMapper.xml:30–43 tenant+calendar+enabled、workflow连接；已有enabled组合和identity UNIQUE。 | 保留；FK tenant+workflow无前缀索引，删除定义性能待验证；按事件找跨日历目标（T/TriggerRequestMapper.xml:59–70）也需实际计划，不能把现按calendar索引认作所有路径全覆盖。 |
| `file_group_expectation_definition` | 3 | O/FileGroupExpectationDefinitionMapper.xml:30–42 tenant+calendar+enabled，与索引匹配，identity UNIQUE维持配置幂等。 | 保留；FK tenant+workflow配套待低频父变更负载确认，暂无必加证据。 |
| `job_definition` | 8 | tenant+code唯一支撑配置点查；tenant+id支撑复合FK；enabled/queue/group列表和worker_group全局扫描有专用路径。 | 保留PK/两个unique，tenant+id **被大量Job分区FK引用**不可判冗余。tenant,id DESC与unique tenant,id在固定tenant下可反向扫描，列为可合并候选，但需所有排序/计划和读写权衡；不删FK目标。 |
| `job_dependency` | 3 | O/JobDependencyMapper.xml按tenant+downstream读上游/顺序；PK与order UNIQUE；tenant,upstream,downstream供反向依赖。 | 全保留，排序unique和关系unique语义不同，upstream索引为反向查询。 |
| `job_dependency_waiting_launch` | 3 | O/JobDependencyWaitingLaunchMapper.xml:68–74 tenant+WAITING按updated_at,id匹配due；request唯一；76–85日历/日期阻塞count；88–98可选status/job recent。 | 主轮询已匹配；可选status未给时不能越过索引status排序，待UI规模EXPLAIN。按biz_date/calendar计数如高频再评估，不一口气加全部筛选列。 |
| `job_execution_log` | 4 | O/JobExecutionLogMapper.xml:43起按tenant+instance；C/JobExecutionLogMapper.xml实例/分区/trace筛选。实例时间、分区时间、trace索引各覆盖主要定位，ID PK点查。 | 保留；tenant单独全量日志分页或同一trace跨tenant大时才评估tenant前导组合；实例/分区全局ID高选择性，不能机械给每个索引加tenant。 |
| `job_instance` | 19 | 各tenant/job/date/status、租户dedup/instanceNo唯一、trigger_request子侧、SLA/结束/failed partial具用途；C/JobInstanceMapper.xml:36–123 tenant+id降序列表；biz_date被cast为varchar。 | **优先修参数侧日期比较**，再验证分区裁剪（89,156）；tenant,id游标候选待多租户/多分区EXPLAIN。idx_job_instance_p_job_code是batch_lookup的左前缀重复候选；不等于直接删除，需写放大/页数/实际查询证明。tenant+job_definition FK缺配套精准前缀。19个父索引会放大到每个分区，分区生命周期由总报告评估。 |
| `job_instance_dedup_key` | 1 | JobInstanceMapper.xml:158–174 ON CONFLICT(tenant,dedup,attempt)，PK即全局跨分区去重。 | 保留，不能按归档/扫描0删除；无需first_instance_id索引（当前写入不填该列，未发现反查路径）。 |
| `job_monitoring_alert_claim` | 1 | O/JobMonitoringMapper.xml:273–277 ON CONFLICT；候选NOT EXISTS按tenant+instance+violation；PK精准匹配。 | 保留；归档按job_instance_id无tenant时无直接前缀，需整合归档传tenant或测批量清理，再决定(instance)索引；不是主告警热路径缺失。 |
| `job_monitoring_policy` | 2 | tenant+job_definition UNIQUE匹配服务配置查/claim JOIN；原单列FK job_definition_id没有精准前缀。 | 保留；随FK升级为tenant复合后现unique即可配套，**不要先加job_definition_id单列再马上废弃**。 |
| `job_partition` | 7 | instance+partition_no UNIQUE；tenant+instance+status聚合，lease与status/worker扫描；O/JobPartitionMapper.xml:459–480过期lease、506–517全局WAITING按id。 | 保留幂等/分区号唯一；全局state+lease筛选/按id排序与现独立索引可能sort/filter，候选active partial(lease_expire_at,id)或WAITING(id)，要分别测并保持既定锁顺序。tenant+instance下id降序兄弟锁不匹配status中间键，先量化分片数。 |
| `job_step_instance` | 5 | tenant+instance+step/status查询，partition、task子FK反查分别已有索引；unique(tenant,task)保持一task一步。 | 全保留；task_id单列与tenant+task非重复（当前FK父删按task_id）；待复合FK升级后再评估合并。 |
| `job_task` | 6 | instance/partition seq唯一，tenant+status+priority DESC,id，status+worker；O/JobTaskMapper.xml:321–328 ready按id ASC SKIP LOCKED。 | ready查询**未按priority排序**，现priority中间键不能提供全局id序；候选tenant,status,id或修明确的调度排序契约，不能擅改优先级语义。455–465全局任务超时按started_at,id，现无匹配partial；高频候选RUNNING且可超时partial(started_at,id)，需检测动态超时时间表达式剩余过滤。 |
| `scheduled_occurrence` | 4 | tenant/workflow/fire UNIQUE精确幂等和FK目标；workflow/version/fire用于版本替换；day/status结日计数。 | 全保留；C/BatchDayMapper.xml:105–125按日历日scheduled_fire DESC,workflow ASC需过滤后排序，若日内高频数量大再评估相应组合；不能用0行判排序免费。 |
| `scheduled_occurrence_catch_up_audit` | 2 | identity UNIQUE用于manual catch-up最多一次，与occurrence FK匹配。请求FK(tenant,request_id)无子侧前缀。 | 两个现索引保留；请求清理/FK检查可补tenant,request配套候选，避免请求删除扫全审计。按量/保留策略后实施。 |
| `scheduled_occurrence_coverage` | 2 | PK tenant+workflow覆盖UPSERT与每workflow水位查；tenant+calendar+coverage_through辅助按日历覆盖。 | 保留；比较当前configuration_revision为业务失效逻辑，不为每个水位字段加索引。 |
| `scheduled_occurrence_supersession_history` | 2 | identity+changed_at审计回溯，id PK。 | 保留；若API要求created/changed_at+id稳定分页应同步索引尾列，当前未检出高频独立查询，不臆增。 |
| `trigger_misfire_pending` | 6 | tenant+workflow+fire唯一新幂等；旧runtime/fire唯一兼容；tenant/catch-up、PENDING/expires、tenant/workflow/detected各支持路径。 | 保留；T/TriggerMisfirePendingMapper.xml:63–69 tenant+status按detected DESC不匹配workflow中间键，量大可评估tenant,status,detected DESC,id。旧唯一属兼容约束，退役模型确认后再清，不从未用推导DROP。 |
| `trigger_outbox_event` | 5 | T/TriggerOutboxEventMapper.xml:48–55状态IN、next时间范围、next/created/id排序锁；pending_order partial匹配全局顺序；publishing stale有updated_at partial。 | 保留唯一和stale；pending(status,next)与pending_order不是完全重复，前者可服务状态count（118–125），后者服务调度顺序。需EXPLAIN与计数频率才决定合并；参数化状态需核对generic plan能否蕴含partial predicate。 |
| `trigger_request` | 8 | request唯一、tenant+id FK目标、external delivery partial唯一；dedup、job/date和stale ACCEPTED covering索引支撑重放/补回。 | 全保留语义索引；无biz_date的id点查PK足够。idx_created_at与stale partial用途不同不可直接归并；external identity跨workflow查询先只按tenant可扩扫，需按真实批量事件形态测而非盲加。 |
| `trigger_request_idempotency_alias` | 1 | PK tenant+idempotency匹配查别名（T/TriggerRequestMapper.xml:202）；FK tenant+request及请求回查无前缀。 | **缺失候选tenant,request_id**，为父删CASCADE/请求回查服务；不会替换PK。考虑保留期后评估批删锁时间。 |
| `trigger_runtime_state` | 5 | 旧next_fire/marker-stale/tenant+job与jobDefinition唯一；当前批模块src/main未检出此表SQL。 | 不新增；现索引随旧调度模型一并评估退役。仍被misfire旧FK引用PK，不能按idx_scan0清理。 |
| `workflow_definition` | 7 | C/WorkflowDefinitionMapper.xml:60按workflow_code ASC,version DESC,id DESC，专用混合顺序索引匹配；租户code/version UNIQUE与FK目标tenant/id；scheduled partial。 | 保留；混合排序专用index与UNIQUE非简单可互换，不能只看字段相似删。scheduled partial谓词未含schedule_enabled，属于额外过滤不自动要求另建。 |
| `workflow_definition_version` | 3 | C/WorkflowDefinitionVersionMapper.xml:76–102 tenant+definition按version DESC,id DESC；unique(tenant,def,version)在等值前缀下可反向读，version唯一使id无tie。 | `idx_workflow_definition_version_def_ver_desc` 可合并候选（所有生产查均带tenant时）；若存在按全局def不带tenant读取/清理则保留。先全调用方与计划验证。 |
| `workflow_edge` | 5 | O按definition/from或to执行图查询；C tenant+def读；from/to单列def前导可支持运行与父FK。unique tenant/def/端点/type保证边身份。 | `idx_workflow_edge_tenant_def` 是unique左前缀候选；from/to不是相同顺序，不得互删。边集有限时不为每个order再加。 |
| `workflow_launch_wait` | 3 | O/WorkflowLaunchWaitMapper.xml:30–67 tenant+WAITING+due，再DISTINCT工作流/日期/dry-run，最后created_at,id公平选取及SKIP LOCKED；due partial用于筛选。 | 保留；due键next_attempt范围不能完成后续分组/created排序，复杂队列需真实积压EXPLAIN。单列definition FK无子侧前缀，若升级tenant FK可联合新索引与公平选择方案；不盲加一个“万能索引”。 |
| `workflow_node` | 4 | O/WorkflowNodeMapper.xml:27–52仅workflow_definition_id及node_code/ORDER node_order,id；唯一键tenant前导。C有tenant+definition查询。 | **明确访问路径缺口候选**：(workflow_definition_id,node_order,id)及点查code择一/合并，或把tenant传到执行查询复用现索引并评估排序。PG17不能把tenant未知当精准左前缀；亦影响父FK删除检查。节点表全局规模与单图上限200不同，不能以每图小就忽略全表。 |
| `workflow_node_run` | 6 | O/WorkflowNodeRunMapper.xml:111–118精确run+node取最新、154–160 DISTINCT ON(node_code)按node ASC,run_seq DESC；专用混合序索引；job_instance partial UNIQUE作绑定幂等；sensor partial调度。 | 全保留：run/node/run_seq DESC索引虽与UNIQUE字段相同，但DISTINCT ON混合排序需专用顺序，**不可仅以反向扫描认为冗余**；状态/started用于超时，sensor partial保留。 |
| `workflow_run` | 10 | tenant+def/date、tenant/status/date或started、trigger_request唯一、active partial unique维持并发运行；O/WorkflowRunMapper.xml:194–213全局RUNNING updated_at及终态finished_at归档按id。 | 现tenant前导不能直接支撑全局stuck；候选RUNNING partial(updated_at,id)。归档finished_at范围+id顺序需真实保留量比较partial(finished_at,id) vs终态id扫描，不能擅改归档顺序。single_job FK缺配套tenant/singleJob前缀，如低频父删可与历史保留一起决定。 |

### 汇总整改项（均未执行）

#### IX-R1：先修查询形状，再决定索引

1. **JobInstance日期条件**：C/JobInstanceMapper.xml:89,156 对列做`cast(biz_date as varchar)`；改为DATE绑定或参数侧cast，确保结果语义不变，以EXPLAIN的分区裁剪、扫描分区数证明改善。不要先给分区键表达式建索引掩盖问题。
2. **Workflow节点执行查询**：O/WorkflowNodeMapper.xml:27–52没有tenant条件，现tenant前导唯一索引不能精准定位definition。选择传tenant或增加def前导索引，并覆盖node_order/id查询、节点code点查和FK父侧扫描。仅看单个图200节点上限不能证明全表扫描成本可接受。
3. **公平队列排序**：JobTask ready按id、CalendarDependencyWait按biz_date、WorkflowLaunchWait按created；现有索引各有priority/last_checked/next_attempt中间键。先维持明确业务顺序，比较滤后排序与备选索引，禁止为了省排序擅改调度语义。
4. **分页稳定性**：Alert普通列表仅last_seen、Approval仅created_at降序；相同时间多行无唯一tie-breaker。修SQL/API排序契约再匹配索引；本轮只记问题不改代码。

#### IX-R2：一组确证同键访问重叠与若干前缀/覆盖候选

- **确证同键访问重叠**：`calendar_holiday.idx_calendar_holiday_tenant`与`uk_calendar_holiday`的键、顺序、方法、collation、opclass、谓词、表达式、INCLUDE形状相同，仅unique不同。保留约束唯一索引；普通索引是删除候选，需要部署兼容、引用依赖、回退和写入/读取计划复核后实施。
- **仅为候选、不是重复已证实**：alert_routing_config.idx_*tenant；disaster_day_override.idx_disaster_lookup；job_instance.idx_job_instance_p_job_code vs batch_lookup；job_definition.idx_*tenant_id_desc vs FK目标unique tenant,id；workflow_definition_version.idx_*def_ver_desc；workflow_edge.idx_*tenant_def。
- **明确不能按相似字段删除**：workflow_node_run专用混合排序与UNIQUE；workflow_definition列表混合排序与UNIQUE；TriggerOutbox按状态索引与全局公平排序索引；PK(id)和tenant+id FK目标；业务幂等partial UNIQUE。

#### IX-R3：高频运行/恢复候选优先实测

| 路径 | 候选方向 | 必须保留的语义/验证 |
|---|---|---|
| Workflow stuck扫描 | updated_at,id WHERE RUNNING | 全局跨tenant；检查长期RUNNING比例、LIMIT候选、重试与锁耗时。 |
| Workflow终态归档 | finished_at,id的终态partial或终态id | 当前按id排序与finished范围冲突；比较扫描/排序/锁时长，不能只看有无index。 |
| Task超时扫描 | started_at,id WHERE RUNNING且cancel_requested=false且timeout>0 | task_timeout_seconds为逐行值，仍有残余过滤；扫描比例及超时阈值分布。 |
| 分区租约过期/WAITING重派 | 活跃lease/id、WAITING id择需 | 不改变既有锁顺序，压力场景测试SKIP LOCKED、死锁/饥饿和扫描行数。 |
| Alert escalation通知及outbox stale | eligible partial按escalated_at/id、publishing updated_at/id | 无/少待通知时也要快；校验状态参数化和计划缓存。 |
| Replay session全局推进、BatchDay waiting释放 | status/created_at/id 或 status/id | 多租户公平性、同状态大量积压、终态保留多时的过滤效果。 |
| tenant+id的大表游标 | job_instance与alert_event按tenant/id | 大小租户混合、跨分区、稀疏tenant参数；验证分页完整性及稳定排序。 |

#### IX-R4：FK子侧配套检查（不等于全部必须马上加）

实际52表共有以下 **11组** FK列未找到完整同序左前缀子侧索引：

1. calendar_holiday(calendar_id)
2. expected_work_occurrence(tenant_id,workflow_definition_id)
3. external_event_expectation_definition(tenant_id,workflow_definition_id)
4. file_group_expectation_definition(tenant_id,workflow_definition_id)
5. job_instance(tenant_id,job_definition_id)
6. job_monitoring_policy(job_definition_id)
7. scheduled_occurrence_catch_up_audit(tenant_id,request_id)
8. trigger_request_idempotency_alias(tenant_id,request_id)
9. workflow_launch_wait(workflow_definition_id)
10. workflow_node(workflow_definition_id)
11. workflow_run(tenant_id,single_job_definition_id)

其中已有其他前导索引可以扫描更大范围、表很小或父行很少删除，不能仅从“无精准前缀”断言线上慢。优先请求别名清理、Workflow节点执行、热Job与配置删除的长事务风险。若FK整改升级tenant复合，**与索引方案一起做**，如job_monitoring_policy现tenant+definition唯一已可服务升级后的FK，不要先补旧单列再删。

#### IX-R5：部署与验收要求

- 代表性环境至少含小/大tenant、多业务日、真实终态保留、待执行/失败占比、积压和大fanout；低数据量计划可以说明计划形状及裁剪，不能证明真实负载收益。
- EXPLAIN记录自定义与通用参数计划、估计/实际行数、BUFFERS、过滤行、排序与临时写、分区裁剪、候选LIMIT及锁等待；读路径明确可运行ANALYZE，变更语句只在另行授权的隔离环境验证。
- 对新增索引记录体积、写入/WAL/构建耗时；PG17分区父与子索引构建/attach方式、并发构建事务限制由分区专题统一处理，不能对父分区表直接套普通CONCURRENTLY脚本。
- 本次所有判断保持“结构确认/查询对照/候选待EXPLAIN”。没有真实负载收益，不报告性能优化已完成，也不从idx_scan=0移除任何索引。

### 完整索引资产附录（213项）

本附录覆盖每个实际索引；未在上文列候选的默认保留。物理子分区由分区专题独立盘点。

#### alert_escalation_notification_outbox（3）

- `alert_escalation_notification_outbox_pkey`：`btree (id)`；自身约束:p。
- `idx_alert_escalation_notification_outbox_poll`：`btree (publish_status, next_publish_at, id)`。
- `uk_alert_escalation_notification_outbox`：`btree (tenant_id, alert_event_id, escalation_tier)`；自身约束:u。

#### alert_event（6）

- `alert_event_pkey`：`btree (id)`；自身约束:p。
- `idx_alert_event_escalation_notify`：`btree (escalation_tier, escalation_notified_tier) WHERE ((status)::text = 'OPEN'::text)`。
- `idx_alert_event_escalation_scan`：`btree (last_seen_at) WHERE ((status)::text = 'OPEN'::text)`。
- `idx_alert_event_tenant_last_seen`：`btree (tenant_id, last_seen_at DESC)`。
- `idx_alert_event_type_severity`：`btree (tenant_id, alert_type, severity)`。
- `uk_alert_event_dedup`：`btree (tenant_id, dedup_fingerprint)`；自身约束:u。

#### alert_routing_config（6）

- `alert_routing_config_pkey`：`btree (id)`；自身约束:p。
- `idx_alert_routing_config_active`：`btree (tenant_id, route_code) WHERE (is_deleted = false)`。
- `idx_alert_routing_config_team`：`btree (tenant_id, team)`。
- `idx_alert_routing_config_tenant`：`btree (tenant_id)`。
- `idx_alert_routing_tenant_id_desc`：`btree (tenant_id, id DESC)`。
- `uk_alert_routing_config`：`btree (tenant_id, route_code)`；自身约束:u。

#### approval_command（3）

- `approval_command_pkey`：`btree (id)`；自身约束:p。
- `idx_approval_command_status`：`btree (tenant_id, approval_status, created_at DESC)`。
- `uk_approval_command_tenant_no`：`btree (tenant_id, approval_no)`；自身约束:u。

#### archive_policy（2）

- `archive_policy_pkey`：`btree (id)`；自身约束:p。
- `uk_archive_policy_tenant_table`：`btree (tenant_id, target_table)`；自身约束:u。

#### batch_day_catchup_control（1）

- `pk_batch_day_catchup_control`：`btree (tenant_id, calendar_code)`；自身约束:p。

#### batch_day_catchup_control_event（2）

- `batch_day_catchup_control_event_pkey`：`btree (id)`；自身约束:p。
- `idx_batch_day_catchup_control_event_recent`：`btree (tenant_id, calendar_code, occurred_at DESC, id DESC)`。

#### batch_day_instance（4）

- `batch_day_instance_pkey`：`btree (id)`；自身约束:p。
- `idx_batch_day_instance_frozen`：`btree (tenant_id, frozen, biz_date DESC) WHERE (frozen = true)`。
- `idx_batch_day_instance_status`：`btree (day_status, biz_date DESC)`。
- `uk_batch_day_instance`：`btree (tenant_id, calendar_code, biz_date)`；自身约束:u。

#### batch_day_open_cursor（1）

- `pk_batch_day_open_cursor`：`btree (tenant_id, calendar_code)`；自身约束:p。

#### batch_day_operation_audit（3）

- `batch_day_operation_audit_pkey`：`btree (id)`；自身约束:p。
- `idx_batch_day_op_audit_tenant_calendar_date`：`btree (tenant_id, calendar_code, biz_date, created_at DESC)`。
- `idx_batch_day_op_audit_tenant_op_date`：`btree (tenant_id, operation_type, created_at DESC)`。

#### batch_day_replay_entry（5）

- `batch_day_replay_entry_pkey`：`btree (id)`；自身约束:p。
- `idx_replay_entry_rerun_instance`：`btree (rerun_instance_id) WHERE (rerun_instance_id IS NOT NULL)`。
- `idx_replay_entry_session_status`：`btree (session_id, status)`。
- `uk_replay_entry_session_result_version`：`btree (session_id, tenant_id, result_version_id) WHERE (result_version_id IS NOT NULL)`；唯一索引/幂等语义须保留。
- `uk_replay_entry_session_source_instance`：`btree (session_id, tenant_id, source_instance_id) WHERE ((source_instance_id IS NOT NULL) AND (result_version_id IS NULL))`；唯一索引/幂等语义须保留。

#### batch_day_replay_preview_token（3）

- `batch_day_replay_preview_token_pkey`：`btree (tenant_id, token_hash)`；自身约束:p。
- `idx_replay_preview_token_consumed_at`：`btree (consumed_at) WHERE (consumed_at IS NOT NULL)`。
- `idx_replay_preview_token_expires_at`：`btree (expires_at)`。

#### batch_day_replay_session（4）

- `batch_day_replay_session_pkey`：`btree (id)`；自身约束:p；被FK引用:1。
- `idx_replay_session_tenant_bizdate`：`btree (tenant_id, calendar_code, biz_date, created_at DESC)`。
- `idx_replay_session_tenant_status`：`btree (tenant_id, status, created_at DESC)`。
- `uk_replay_session_active`：`btree (tenant_id, calendar_code, biz_date) WHERE ((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'RUNNING'::character varying])::text[]))`；唯一索引/幂等语义须保留。

#### batch_day_waiting_launch（3）

- `batch_day_waiting_launch_pkey`：`btree (id)`；自身约束:p。
- `idx_batch_day_waiting_launch_status`：`btree (wait_status, tenant_id, calendar_code, biz_date)`。
- `uk_batch_day_waiting_launch_request`：`btree (tenant_id, request_id)`；自身约束:u。

#### batch_window（4）

- `batch_window_pkey`：`btree (id)`；自身约束:p。
- `idx_batch_window_enabled`：`btree (tenant_id, enabled)`。
- `idx_batch_window_tenant_id_desc`：`btree (tenant_id, id DESC)`。
- `uk_batch_window_tenant_code`：`btree (tenant_id, window_code)`；自身约束:u。

#### calendar_dependency（3）

- `calendar_dependency_pkey`：`btree (id)`；自身约束:p。
- `idx_calendar_dependency_downstream`：`btree (tenant_id, downstream_code, enabled)`。
- `uk_calendar_dependency`：`btree (tenant_id, upstream_code, downstream_code)`；自身约束:u。

#### calendar_dependency_wait（3）

- `calendar_dependency_wait_pkey`：`btree (id)`；自身约束:p。
- `idx_calendar_dependency_wait_due`：`btree (tenant_id, calendar_code, wait_status, last_checked_at, id)`。
- `uk_calendar_dependency_wait`：`btree (tenant_id, calendar_code, biz_date)`；自身约束:u。

#### calendar_group（2）

- `calendar_group_pkey`：`btree (id)`；自身约束:p。
- `uk_calendar_group_tenant_code`：`btree (tenant_id, group_code)`；自身约束:u。

#### calendar_holiday（5）

- `calendar_holiday_pkey`：`btree (id)`；自身约束:p。
- `idx_calendar_holiday_biz_date`：`btree (biz_date, day_type)`。
- `idx_calendar_holiday_scope_group`：`btree (scope, group_code, biz_date) WHERE ((scope)::text = 'GROUP'::text)`。
- `idx_calendar_holiday_tenant`：`btree (tenant_id, calendar_id, biz_date)`。
- `uk_calendar_holiday`：`btree (tenant_id, calendar_id, biz_date)`；自身约束:u。

#### disaster_day_override（3）

- `disaster_day_override_pkey`：`btree (id)`；自身约束:p。
- `idx_disaster_lookup`：`btree (tenant_id, calendar_code, biz_date, ttl_until)`。
- `uk_disaster_active`：`btree (tenant_id, calendar_code, biz_date)`；唯一索引/幂等语义须保留。

#### expected_work_occurrence（3）

- `expected_work_occurrence_pkey`：`btree (id)`；自身约束:p。
- `idx_expected_work_day_status`：`btree (tenant_id, calendar_code, biz_date, occurrence_status)`。
- `uk_expected_work_identity`：`btree (tenant_id, calendar_code, workflow_definition_id, source_type, source_key, biz_date, generation)`；自身约束:u。

#### external_event_delivery（4）

- `external_event_delivery_pkey`：`btree (id)`；自身约束:p。
- `uk_external_event_delivery_identity`：`btree (tenant_id, event_source, event_id)`；自身约束:u。
- `uk_external_event_delivery_request`：`btree (tenant_id, request_id)`；自身约束:u。
- `uk_external_event_delivery_tenant_id`：`btree (tenant_id, id)`；自身约束:u；被FK引用:1。

#### external_event_delivery_alias（2）

- `idx_external_event_delivery_alias_delivery`：`btree (tenant_id, delivery_id)`。
- `pk_external_event_delivery_alias`：`btree (tenant_id, idempotency_key)`；自身约束:p。

#### external_event_expectation_definition（3）

- `external_event_expectation_definition_pkey`：`btree (id)`；自身约束:p。
- `idx_external_event_expectation_enabled`：`btree (tenant_id, calendar_code, enabled, workflow_definition_id)`。
- `uk_external_event_expectation_definition`：`btree (tenant_id, calendar_code, workflow_definition_id, event_code)`；自身约束:u。

#### file_group_expectation_definition（3）

- `file_group_expectation_definition_pkey`：`btree (id)`；自身约束:p。
- `idx_file_group_expectation_enabled`：`btree (tenant_id, calendar_code, enabled, workflow_definition_id)`。
- `uk_file_group_expectation_definition`：`btree (tenant_id, calendar_code, workflow_definition_id, file_group_code)`；自身约束:u。

#### job_definition（8）

- `idx_job_definition_enabled`：`btree (tenant_id, enabled)`。
- `idx_job_definition_queue`：`btree (tenant_id, queue_code, enabled)`。
- `idx_job_definition_tenant_group`：`btree (tenant_id, job_group_code) WHERE (job_group_code IS NOT NULL)`。
- `idx_job_definition_tenant_id_desc`：`btree (tenant_id, id DESC)`。
- `idx_job_definition_worker_group`：`btree (worker_group, enabled)`。
- `job_definition_pkey`：`btree (id)`；自身约束:p；被FK引用:2。
- `uk_job_definition_tenant_code`：`btree (tenant_id, job_code)`；自身约束:u；被FK引用:1。
- `uk_job_definition_tenant_id`：`btree (tenant_id, id)`；被FK引用:40；唯一索引/幂等语义须保留。

#### job_dependency（3）

- `idx_job_dependency_upstream`：`btree (tenant_id, upstream_job_code, downstream_job_code)`。
- `pk_job_dependency`：`btree (tenant_id, downstream_job_code, upstream_job_code)`；自身约束:p。
- `uk_job_dependency_order`：`btree (tenant_id, downstream_job_code, dependency_order)`；自身约束:u。

#### job_dependency_waiting_launch（3）

- `idx_job_dependency_wait_due`：`btree (tenant_id, wait_status, updated_at, id)`。
- `job_dependency_waiting_launch_pkey`：`btree (id)`；自身约束:p。
- `uk_job_dependency_wait_request`：`btree (tenant_id, request_id)`；自身约束:u。

#### job_execution_log（4）

- `idx_job_execution_log_instance_created`：`btree (job_instance_id, created_at)`。
- `idx_job_execution_log_partition_created`：`btree (job_partition_id, created_at)`。
- `idx_job_execution_log_trace_id`：`btree (trace_id)`。
- `job_execution_log_pkey`：`btree (id)`；自身约束:p。

#### job_instance（19）

- `idx_job_instance_failed_partition_monitoring`：`btree (finished_at, tenant_id, id) WHERE ((failed_partition_count > 0) AND ((instance_status)::text = ANY ((ARRAY['FAILED'::character varying, 'PARTIAL_FAILED'::character varying])::text[])) AND (dry_run = false))`。
- `idx_job_instance_p_active_tenant_created`：`btree (tenant_id, created_at) WHERE ((instance_status)::text = ANY ((ARRAY['CREATED'::character varying, 'WAITING'::character varying, 'READY'::character varying, 'RUNNING'::character varying])::text[]))`。
- `idx_job_instance_p_batch_lookup`：`btree (tenant_id, job_code, biz_date, batch_no)`。
- `idx_job_instance_p_calendar_bizdate`：`btree (tenant_id, calendar_code, biz_date) WHERE (calendar_code IS NOT NULL)`。
- `idx_job_instance_p_created_at`：`btree (created_at)`。
- `idx_job_instance_p_failure_class`：`btree (tenant_id, failure_class, finished_at DESC) WHERE (failure_class IS NOT NULL)`。
- `idx_job_instance_p_finished_at`：`btree (finished_at) WHERE (finished_at IS NOT NULL)`。
- `idx_job_instance_p_job_code`：`btree (tenant_id, job_code, biz_date)`。
- `idx_job_instance_p_job_status`：`btree (tenant_id, job_code, instance_status)`。
- `idx_job_instance_p_related_file`：`btree (tenant_id, related_file_id)`。
- `idx_job_instance_p_sla_tracking`：`btree (instance_status, deadline_at, sla_alerted_at)`。
- `idx_job_instance_p_status`：`btree (tenant_id, instance_status, biz_date)`。
- `idx_job_instance_p_tenant_bizdate_status`：`btree (tenant_id, biz_date DESC, instance_status)`。
- `idx_job_instance_p_tenant_status_started`：`btree (tenant_id, instance_status, started_at DESC)`。
- `idx_job_instance_p_trace_id`：`btree (trace_id)`。
- `idx_job_instance_trigger_request_id`：`btree (trigger_request_id) WHERE (trigger_request_id IS NOT NULL)`。
- `job_instance_p_pkey`：`btree (id, biz_date)`；自身约束:p。
- `uk_job_instance_tenant_dedup_attempt`：`btree (tenant_id, dedup_key, run_attempt, biz_date)`；自身约束:u。
- `uk_job_instance_tenant_instance_no`：`btree (tenant_id, instance_no, biz_date)`；自身约束:u。

#### job_instance_dedup_key（1）

- `job_instance_dedup_key_pkey`：`btree (tenant_id, dedup_key, run_attempt)`；自身约束:p。

#### job_monitoring_alert_claim（1）

- `pk_job_monitoring_alert_claim`：`btree (tenant_id, job_instance_id, violation_type)`；自身约束:p。

#### job_monitoring_policy（2）

- `job_monitoring_policy_pkey`：`btree (id)`；自身约束:p。
- `uk_job_monitoring_policy_tenant_definition`：`btree (tenant_id, job_definition_id)`；自身约束:u。

#### job_partition（7）

- `idx_job_partition_business_key`：`btree (business_key)`。
- `idx_job_partition_instance_status`：`btree (tenant_id, job_instance_id, partition_status)`。
- `idx_job_partition_lease_expire_at`：`btree (lease_expire_at)`。
- `idx_job_partition_status_worker_group`：`btree (partition_status, worker_group)`。
- `job_partition_pkey`：`btree (id)`；自身约束:p；被FK引用:3。
- `uk_job_partition_idempotency_key`：`btree (tenant_id, idempotency_key) WHERE (idempotency_key IS NOT NULL)`；唯一索引/幂等语义须保留。
- `uk_job_partition_instance_no`：`btree (job_instance_id, partition_no)`；自身约束:u。

#### job_step_instance（5）

- `idx_job_step_instance_lookup`：`btree (tenant_id, job_instance_id, step_code, step_status)`。
- `idx_job_step_instance_partition_id`：`btree (job_partition_id) WHERE (job_partition_id IS NOT NULL)`。
- `idx_job_step_instance_task_id`：`btree (job_task_id)`。
- `job_step_instance_pkey`：`btree (id)`；自身约束:p。
- `uk_job_step_instance_task`：`btree (tenant_id, job_task_id)`；自身约束:u。

#### job_task（6）

- `idx_job_task_instance`：`btree (job_instance_id, created_at)`。
- `idx_job_task_priority`：`btree (tenant_id, task_status, priority DESC, id)`。
- `idx_job_task_status_worker`：`btree (task_status, assigned_worker_code)`。
- `job_task_pkey`：`btree (id)`；自身约束:p；被FK引用:1。
- `uk_job_task_no_partition_seq`：`btree (job_instance_id, task_seq) WHERE (job_partition_id IS NULL)`；唯一索引/幂等语义须保留。
- `uk_job_task_partition_seq`：`btree (job_partition_id, task_seq) WHERE (job_partition_id IS NOT NULL)`；唯一索引/幂等语义须保留。

#### scheduled_occurrence（4）

- `idx_scheduled_occurrence_day_status`：`btree (tenant_id, calendar_code, biz_date, occurrence_status)`。
- `idx_scheduled_occurrence_workflow_version`：`btree (tenant_id, workflow_definition_id, schedule_version, scheduled_fire_time)`。
- `scheduled_occurrence_pkey`：`btree (id)`；自身约束:p。
- `uk_scheduled_occurrence_fire`：`btree (tenant_id, workflow_definition_id, scheduled_fire_time)`；自身约束:u；被FK引用:1。

#### scheduled_occurrence_catch_up_audit（2）

- `scheduled_occurrence_catch_up_audit_pkey`：`btree (id)`；自身约束:p。
- `uk_scheduled_occurrence_catch_up`：`btree (tenant_id, workflow_definition_id, scheduled_fire_time)`；自身约束:u。

#### scheduled_occurrence_coverage（2）

- `idx_scheduled_occurrence_coverage_calendar`：`btree (tenant_id, calendar_code, coverage_through_at)`。
- `pk_scheduled_occurrence_coverage`：`btree (tenant_id, workflow_definition_id)`；自身约束:p。

#### scheduled_occurrence_supersession_history（2）

- `idx_scheduled_occurrence_supersession_history_identity`：`btree (tenant_id, workflow_definition_id, scheduled_fire_time, changed_at)`。
- `scheduled_occurrence_supersession_history_pkey`：`btree (id)`；自身约束:p。

#### trigger_misfire_pending（6）

- `idx_trigger_misfire_pending_catch_up_request`：`btree (tenant_id, catch_up_request_id) WHERE (catch_up_request_id IS NOT NULL)`。
- `idx_trigger_misfire_pending_status`：`btree (status, expires_at) WHERE ((status)::text = 'PENDING'::text)`。
- `idx_trigger_misfire_pending_tenant_workflow`：`btree (tenant_id, workflow_definition_id, detected_at DESC)`。
- `trigger_misfire_pending_pkey`：`btree (id)`；自身约束:p。
- `uk_trigger_misfire_pending`：`btree (trigger_runtime_state_id, scheduled_fire_time)`；自身约束:u。
- `uk_trigger_misfire_pending_workflow_fire`：`btree (tenant_id, workflow_definition_id, scheduled_fire_time)`；唯一索引/幂等语义须保留。

#### trigger_outbox_event（5）

- `idx_trigger_outbox_event_pending`：`btree (publish_status, next_publish_at) WHERE ((publish_status)::text = ANY ((ARRAY['NEW'::character varying, 'FAILED'::character varying])::text[]))`。
- `idx_trigger_outbox_event_pending_order`：`btree (next_publish_at, created_at, id) WHERE ((publish_status)::text = ANY ((ARRAY['NEW'::character varying, 'FAILED'::character varying])::text[]))`。
- `idx_trigger_outbox_event_publishing_stale`：`btree (updated_at) WHERE ((publish_status)::text = 'PUBLISHING'::text)`。
- `trigger_outbox_event_pkey`：`btree (id)`；自身约束:p。
- `uk_trigger_outbox_event_tenant_request`：`btree (tenant_id, request_id)`；自身约束:u。

#### trigger_request（8）

- `idx_trigger_request_created_at`：`btree (created_at)`。
- `idx_trigger_request_job_biz_date`：`btree (tenant_id, job_code, biz_date)`。
- `idx_trigger_request_stale_accepted`：`btree (created_at, id) INCLUDE (tenant_id, request_id) WHERE (((request_status)::text = 'ACCEPTED'::text) AND (related_job_instance_id IS NULL))`。
- `idx_trigger_request_tenant_dedup`：`btree (tenant_id, dedup_key)`。
- `trigger_request_pkey`：`btree (id)`；自身约束:p；被FK引用:38。
- `uk_trigger_request_external_event_delivery`：`btree (tenant_id, workflow_definition_id, external_event_source, external_event_id) WHERE (external_event_source IS NOT NULL)`；唯一索引/幂等语义须保留。
- `uk_trigger_request_tenant_id`：`btree (tenant_id, id)`；被FK引用:1；唯一索引/幂等语义须保留。
- `uk_trigger_request_tenant_request`：`btree (tenant_id, request_id)`；自身约束:u；被FK引用:3。

#### trigger_request_idempotency_alias（1）

- `pk_trigger_request_idempotency_alias`：`btree (tenant_id, idempotency_key)`；自身约束:p。

#### trigger_runtime_state（5）

- `idx_trigger_runtime_state_marker_stale`：`btree (scheduled_at) WHERE (scheduled_fire_marker IS NOT NULL)`。
- `idx_trigger_runtime_state_next_fire`：`btree (next_fire_time) WHERE (scheduled_fire_marker IS NULL)`。
- `idx_trigger_runtime_state_tenant_job`：`btree (tenant_id, job_code)`。
- `trigger_runtime_state_pkey`：`btree (id)`；自身约束:p；被FK引用:1。
- `uk_trigger_runtime_state_job_def`：`btree (job_definition_id)`；自身约束:u。

#### workflow_definition（7）

- `idx_workflow_definition_enabled`：`btree (tenant_id, enabled)`。
- `idx_workflow_definition_scheduled`：`btree (tenant_id, schedule_type, trigger_mode, workflow_code) WHERE ((enabled = true) AND ((schedule_type)::text = ANY ((ARRAY['CRON'::character varying, 'FIXED_RATE'::character varying])::text[])))`。
- `idx_workflow_definition_tenant_code_version`：`btree (tenant_id, workflow_code, version DESC, id DESC)`。
- `uk_workflow_definition_tenant_code_version`：`btree (tenant_id, workflow_code, version)`；自身约束:u。
- `uk_workflow_definition_tenant_id`：`btree (tenant_id, id)`；被FK引用:8；唯一索引/幂等语义须保留。
- `uk_workflow_single_job_definition`：`btree (tenant_id, single_job_definition_id) WHERE (single_job_definition_id IS NOT NULL)`；唯一索引/幂等语义须保留。
- `workflow_definition_pkey`：`btree (id)`；自身约束:p；被FK引用:3。

#### workflow_definition_version（3）

- `idx_workflow_definition_version_def_ver_desc`：`btree (workflow_definition_id, version DESC)`。
- `uk_workflow_definition_version_tenant_def_ver`：`btree (tenant_id, workflow_definition_id, version)`；自身约束:u。
- `workflow_definition_version_pkey`：`btree (id)`；自身约束:p。

#### workflow_edge（5）

- `idx_workflow_edge_from`：`btree (workflow_definition_id, from_node_code)`。
- `idx_workflow_edge_tenant_def`：`btree (tenant_id, workflow_definition_id)`。
- `idx_workflow_edge_to`：`btree (workflow_definition_id, to_node_code)`。
- `uk_workflow_edge`：`btree (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type)`；自身约束:u。
- `workflow_edge_pkey`：`btree (id)`；自身约束:p。

#### workflow_launch_wait（3）

- `idx_workflow_launch_wait_due`：`btree (tenant_id, next_attempt_at, created_at, id) WHERE ((wait_status)::text = 'WAITING'::text)`。
- `uk_workflow_launch_wait_request`：`btree (tenant_id, request_id)`；自身约束:u。
- `workflow_launch_wait_pkey`：`btree (id)`；自身约束:p。

#### workflow_node（4）

- `idx_workflow_node_tenant_code`：`btree (tenant_id, node_code)`。
- `idx_workflow_node_type_enabled`：`btree (node_type, enabled)`。
- `uk_workflow_node_def_code`：`btree (tenant_id, workflow_definition_id, node_code)`；自身约束:u。
- `workflow_node_pkey`：`btree (id)`；自身约束:p。

#### workflow_node_run（6）

- `idx_workflow_node_run_run_id_node_code`：`btree (workflow_run_id, node_code, run_seq DESC)`。
- `idx_workflow_node_run_sensor_next_probe`：`btree (sensor_next_probe_at) WHERE (((node_type)::text = 'WAIT'::text) AND ((node_status)::text = 'RUNNING'::text))`。
- `idx_workflow_node_run_status_started`：`btree (node_status, started_at)`。
- `uk_workflow_node_run`：`btree (workflow_run_id, node_code, run_seq)`；自身约束:u。
- `uk_workflow_node_run_job_instance`：`btree (job_instance_id) WHERE (job_instance_id IS NOT NULL)`；唯一索引/幂等语义须保留。
- `workflow_node_run_pkey`：`btree (id)`；自身约束:p。

#### workflow_run（10）

- `idx_workflow_run_def_bizdate`：`btree (tenant_id, workflow_definition_id, biz_date)`。
- `idx_workflow_run_job_instance`：`btree (related_job_instance_id)`。
- `idx_workflow_run_job_version_lookup`：`btree (tenant_id, created_at DESC, id DESC) WHERE (execution_snapshot IS NOT NULL)`。
- `idx_workflow_run_status`：`btree (tenant_id, run_status, started_at)`。
- `idx_workflow_run_tenant_status_bizdate`：`btree (tenant_id, run_status, biz_date DESC)`。
- `idx_workflow_run_trace_id`：`btree (trace_id)`。
- `uk_workflow_run_active`：`btree (tenant_id, workflow_definition_id, biz_date, dry_run) WHERE ((run_status)::text = ANY ((ARRAY['CREATED'::character varying, 'RUNNING'::character varying])::text[]))`；唯一索引/幂等语义须保留。
- `uk_workflow_run_tenant_id`：`btree (tenant_id, id)`；自身约束:u。
- `uk_workflow_run_trigger_request`：`btree (tenant_id, trigger_request_id) WHERE (trigger_request_id IS NOT NULL)`；唯一索引/幂等语义须保留。
- `workflow_run_pkey`：`btree (id)`；自身约束:p；被FK引用:1。

## 附录 B：文件、流水线、事件、资产及冷表索引逐表审查

### 基线与边界

- 实际catalog：`platform-index.json`，PostgreSQL 17.11，2026-10-18 09:06:34 +08:00；数据库进程09:02:08启动，观测窗口约4分26秒，`stats_reset=null`不表示跨重建累计历史。统计中本组仅file_template_config与worker_registry有少量live_rows，不能代表业务规模。
- 覆盖56张逻辑表（batch30、archive26）共180个逻辑索引（batch130、archive50）。物理分区/分区保留扩展由总报告负责。本组逐个查看索引键、方法、方向/null顺序、include/attribute count、opclass/collation、partial谓词、unique/PK以及constraint依赖。
- 完整结构签名没有完全重复索引组；未发现invalid、not-ready或not-live。此结论是catalog结构核对，不是执行计划或性能验收。
- 没有运行测试、业务查询、EXPLAIN/ANALYZE或DDL；所有增删是整改候选，必须先有代表性数据/参数/实际计划及读写成本比较。所有`idx_scan=0`仅为短窗口观测，不作为删除理由。

### 优先结论与整改候选

#### M1：FK子端与当前关联清理缺配套索引，随引用整改统一设计

`file_dispatch_record`只有PK/external_request/status三个索引，却有file_id与pipeline_instance_id两个FK；Worker按tenant/file/channel更新（FD:50–69），归档按pipeline删（SI:436–449/546–552），lineage按file/pipeline回查（LE:263–297）。`file_audit_log`也没有file_id定位索引，而真实FK会在父删除时SET NULL。建议将child关联索引作为租户复合FK整改验收项，不独立堆重复单列+复合索引。`asset_partition`与`data_quality_check`现有tenant前导关联索引适配拟议复合FK；对当前id-only FK父删除不要误判为完全配套。

#### M2：跨租户周期扫描与tenant前导索引不同

- `result_version`的SUPERSEDED保留扫描（RV:314–321）按deactivated_at、id限批，无对应索引；ARCHIVED和DRY_RUN已有专用partial。候选`(deactivated_at,id) WHERE status='SUPERSEDED'`需结合实际保留量、计划和绑定谓词验证。
- `compensation_command` stale RUNNING扫描（CM:117–125）按全局status/created_at/id，现tenant+type+status+created键不完整匹配；按related_job_instance_id的归档也缺配套。先量化扫入候选比与锁等待。
- `pipeline_instance`超时扫描（FG:383–398）按tenant/status/started_at，不传索引中间pipeline_type。候选需考虑超时状态分布与回收频率。
- `outbox_event`待投递按status IN、due范围、priority/id排序（OB:62–84）；`worker_report_outbox`按due但ORDER BY id（WRO:24–30）；`dead_letter_task`多status后按due/id（DL:103–115）。现索引能过滤，不等于全排序都被覆盖。不得为省sort擅改优先级/到期顺序；需代表性积压计划。

#### M3：已有真实冷谱系查询，但冷关联索引明显少于热表

`archive.pipeline_instance_archive`与`archive.file_dispatch_record_archive`均只有PK，LE:153–172/299–332和RE:195–203已经按tenant/job/file/pipeline查询冷谱系与重放影响。优先纳入容量验证：pipeline候选`(tenant_id,related_job_instance_id,id)`；dispatch候选`(tenant_id,pipeline_instance_id,id)`、`(tenant_id,file_id,id)`。OR联查/去重/排序是否需要两条，应由计划决定。当前空库没有证据证明已慢，但不能把“有PK”写成冷谱系索引完整。

#### C1—C5：收敛候选不是立即DROP清单

| 编号 | 关系 | 分类/注意 |
|---|---|---|
| C1 | atomic `idx_atomic_task_config_tenant_type` vs UNIQUE `(tenant_id,task_type,name)` | 左前缀候选；二者都不覆盖created/id排序；比较尺寸/扫描后保留长唯一键，评估短键 |
| C2 | event log `idx_event_delivery_log_outbox_event_id` vs `idx_event_delivery_log_outbox_tenant` | 左前缀候选；短键无constraint依赖，长键含归档tenant守卫；仍需真实计划与写放大证据 |
| C3 | progress `idx_pipeline_progress_tenant_instance` vs UNIQUE `(tenant_id,pipeline_instance_id,stage)` | 左前缀候选；point/stage及按parent删除已有长键，不能删除唯一键 |
| C4 | file `idx_file_record_metadata_json_gin` vs `..._gin_partial WHERE metadata_json IS NOT NULL` | 相同opclass但谓词不同，属于重叠GIN候选；要核NULL比例、查询谓词、尺寸/写放大，不能称结构完全重复 |
| C5 | result `idx_result_version_tenant_business_version` DESC vs UNIQUE `uk_result_version_business_version` ASC | tenant/business等值时版本反扫候选；必须保留版本unique，核所有列表参数后决定非unique能否删 |

反例：**file_template的DESC索引不能据此删除**。FT:55–81要求tenant内template_code ASC、version DESC，混合排序方向有实际调用；ASC unique的整体反扫同时倒转template_code，不能覆盖同一排序。active soft-delete partial与full unique也不是完全重复。

#### J1：JSON与partial谓词必须核实际计划

file_record两个arrival表达式索引的partial谓词为`metadata_json ? 'fileGroupCode'`；FG:143/190使用`jsonb_exists(...)`，FG:225–230仅`->>`等值，没有显式相同partial条件。本轮只能确认形式差异，不能宣称规划器必定识别，也不能宣称已经失效。应核实际参数计划，必要时让业务等价谓词与索引一致。全局到达队列按JSON文本转timestamptz再排序，GIN不能承担排序；先评估typed字段/读模型，不能随意新增受时间解析影响的表达式索引。file_name ILIKE已由trgm服务，不要与metadata GIN合并讨论。

#### L1：冷表镜像的热生命周期索引单独裁定

8张archive镜像缺生产搬运路径，现有二级索引不能代表已用。replay_session冷表保留“活跃会话唯一”partial、result_version冷表保留“EFFECTIVE唯一”partial，来自热表镜像；当前生产写法倾向终态/ARCHIVED。应先明确冷历史允许状态及完整写入/恢复路径，再决定保留/换为历史身份约束，不能见空表就删unique。

### 代码证据简称（均为当前feature worktree相对路径）

- OB/CM/RV/AP/AF/DL/RS/WRG/DQM/SI：`batch-orchestrator/src/main/resources/mapper/` 下依次 `OutboxEventMapper.xml`、`CompensationCommandMapper.xml`、`ResultVersionMapper.xml`、`AssetPartitionMapper.xml`、`AssetFreshnessPolicyMapper.xml`、`DeadLetterTaskMapper.xml`、`RetryScheduleMapper.xml`、`WorkerRegistryMapper.xml`、`DataQualityCheckMapper.xml`、`SuccessInstanceArchiveMapper.xml`。
- LE/RE/FG：同一目录的 `LineageEvidenceMapper.xml`、`BatchDayReplayEntryMapper.xml`、`FileGovernanceMapper.xml`。
- FD：`batch-worker/dispatch/src/main/resources/mapper/FileDispatchMapper.xml`；PP/WRO：`batch-worker/core/src/main/resources/mapper/PipelineProgressMapper.xml`、`WorkerReportOutboxPgMapper.xml`。
- AC/FR/FER/FT/FPI/PSR：`batch-console-api/src/main/resources/mapper/` 下 `AtomicTaskConfigMapper.xml`、`FileRecordMapper.xml`、`FileErrorRecordMapper.xml`、`FileTemplateConfigMapper.xml`、`FilePipelineMapper.xml`、`FilePipelineStepRunMapper.xml`；注明Console RS等均指该目录同名文件。

### 逐表处置矩阵（56 / 56）

“保留”表示没有本轮证据支持立即改变，不等于已在真实负载证明最优；“候选”需要实施前计划。除明确列出的候选，附录全部索引保持现状。

| 逻辑表 | 索引数 | 查询、保留、收敛或缺配套候选 |
|---|---:|---|
| `batch.asset_freshness_policy` | 3 | 保留PK/tenant+asset+type唯一键及enabled扫描。AF:34–37 为跨租户 enabled/JOB、tenant+asset排序，partial(enabled=true)可适配；asset_type不是索引键，当前配置表规模无证据加宽。 |
| `batch.asset_partition` | 7 | 保留7项；AP:113–144 tenant+asset+partition_key定位和result_version join；回放按business_key读取已有tenant+business索引。tenant+asset_id/result_version索引适配拟议复合FK，但不应假设高效支持现有id-only FK的无tenant父删除；先联动约束模型，不再重复加一套。 |
| `batch.atomic_task_config` | 3 | C1：idx_atomic_task_config_tenant_type 是唯一键tenant+type+name左前缀候选。AC:16–22 实际按created_at DESC,id DESC排序，二者均不覆盖该排序；小配置表先保留，不能为排序无证据加索引。 |
| `batch.compensation_checkpoint` | 3 | 保留现状3项：PK、tenant+compensation_id+step_order、status+created。未找到生产Mapper，不以idx_scan=0移除，也不再补JSON/热路径索引；先裁定预留表生命周期。 |
| `batch.compensation_command` | 4 | 保留命令唯一、RUNNING目标partial unique（状态互斥语义）。M2：CM:117–125全局RUNNING+created_at cutoff、created_at/id排序无配套前缀；SI:500–513按related_job_instance_id归档也无配套键。建议代表性量级评估运行中partial(created_at,id)与related_job索引，不能把现tenant+type+status+created视作等价。 |
| `batch.custom_task_type_registry` | 3 | 保留PK、tenant+type唯一、tenant+status。Console CustomTaskTypeMapper:19–26按tenant/status筛选，last_declared_at DESC NULLS LAST,id DESC排序需要额外排序，但属于小配置表，无证据不加；descriptor无需全文GIN。 |
| `batch.data_asset` | 3 | 保留PK（asset_partition FK依赖）及tenant+code+type唯一。tenant+type+code与唯一键列序不同，AP:46–51三个等值都可用，但其他type列表需计划后才评估收敛，不能算完全重复。 |
| `batch.data_quality_check` | 3 | 保留tenant+instance和tenant+rule+status+checked，DQM:45–50 按tenant/instance查并id排序；大量检查记录场景可评估id后缀，当前无量级证据。rule简单FK删除与前导tenant差异应随复合FK一并解决，不盲补单列。 |
| `batch.data_quality_rule` | 3 | 保留tenant+rule唯一、scope索引和被DQ子FK依赖PK。DataQualityRuleMapper:22–27是参数businessKey LIKE (scope列||百分号)，不是scope列匹配常量前缀；不能宣称普通Btree已高效匹配该反向模式。规则配置量小，无证据不加trgm/GIN。 |
| `batch.dead_letter_task` | 4 | 保留status/created、trace及SYSTEM自动重放partial。DL:103–115多status+due范围且next_replay_at/id排序，现index(status,error_class,next_replay_at,replay_count)不完整覆盖排序；Console tenant/id页也缺tenant/id路径。先按活跃队列和历史分页分别取计划，再选候选，不增加重复全状态索引。 |
| `batch.event_delivery_log` | 6 | C2：outbox_event_id单列是outbox_event_id+tenant左前缀候选，无约束依赖；OB:269–280、320–360用父id/tenant。保留tenant/id倒序页、tenant/status/created、trace；短索引是否节省读I/O应先测再删。 |
| `batch.event_outbox_retry` | 4 | 保留4项；OB:275–280/338–369 按outbox_id+tenant归档已有键；Console OutboxRetryLogMapper:17–32 tenant/id分页有配套。status创建时间与id排序不完全一样，但当前没有热点证据加组合。 |
| `batch.file_audit_log` | 3 | M1：真实file_id FK/SET NULL无任何以file_id或tenant+file_id为首的子索引。保留PK/tenant+operation+created/trace；随FK改造评估(tenant_id,file_id)子索引（若保留id-only FK，需单file_id可有效定位的路径）。AuditLogMapper按tenant列表可根据实测决定id排序后缀。 |
| `batch.file_channel_config` | 5 | 保留PK、tenant/channel唯一、type/enabled和tenant/id页。active partial与唯一键相同列但不同谓词，软删除比例可能改变体积/成本，列为重叠评估，非完全重复，不因当前0行删除。 |
| `batch.file_channel_health` | 3 | 保留PK、tenant/channel唯一与health_status/next_probe；DispatchChannelHealthMapper按到期健康及tenant/channel点查，未来自然键FK子查可复用唯一键，无需另加相同复合索引。 |
| `batch.file_dispatch_record` | 3 | M1/M3：仅PK、external_request、tenant/status/dispatched三项，file_id和pipeline_instance_id两个FK均缺子关联索引。FD:50–69按tenant/file/channel更新；SI:436–449/546–552按pipeline归档/删除；LE:263–297按tenant/file或pipeline反查。优先评估(tenant,file_id,channel_code)及(tenant,pipeline_instance_id)，与FK租户化及分页计划统一选择。 |
| `batch.file_error_record` | 3 | 保留tenant/file/stage/code和created_at。FER:22–44可不传fileId，tenant+id DESC分页缺排序配套；按具体租户行数评估(tenant,id)，不要为每个错误filter分别加。strpos(lower(concat_ws(...)))关键词不是现Btree可匹配形式，先控制搜索范围，不能泛加多列trgm宣称解决。 |
| `batch.file_record` | 14 | C4/J1：14项包含全量/NOT NULL两条相同opclass GIN，predicate不同属重叠候选；文件名ILIKE有trgm不要混删。FR:27–56 tenant/id页已有键；FG:143/190用jsonb_exists，而expr partial用?，FG:225–230仅->> equality未显式partial条件；应验证计划/predicate蕴含与绑定参数，不能认定当前两个expr索引必用。全局到达队列还按JSON时间转换表达式排序，普通GIN不承担排序；候选先规范时间字段/读模型，不直接建依赖时区转换的表达式索引。 |
| `batch.file_template_config` | 6 | 保留6项。FT:55–81排序template_code ASC,version DESC；idx_file_template_tenant_code_version有混合方向，唯一键ASC版本的简单反向扫描不能同时维持code ASC，不能判为重复。active partial与唯一键谓词不同，是否收敛须代表性软删比例计划。关键词多字段LIKE属于小配置列表，无证据不加GIN。 |
| `batch.outbox_event` | 6 | 保留6个父逻辑索引，分区物理继承由总报告核查。OB:62–84状态IN/next_publish_at OR范围再priority DESC,id ASC，priority_pending索引前置status与due，不能宣称完全消除全局排序；prepared plan需核partial是否适用。OB:264–282归档status+created cutoff按id限批，现索引也非直接全覆盖；待产能/参数计划后选择队列索引或归档索引，不盲加三套。 |
| `batch.outbox_event_dedup_key` | 1 | 保留(tenant,event_key)PK，它是OB:23–33 ON CONFLICT幂等账本。无当前first_event_id点查/清理访问证据，不加first_event_id/created_at索引；不得因零扫描移除幂等键。 |
| `batch.pipeline_definition` | 4 | 保留4项；PK被step_definition FK引用，tenant/job/version唯一支撑版本配置；tenant/id页与tenant/type/enabled分别服务列表过滤。系统Pipeline迁移后需先确认旧配置实际访问频率，不能因新主链不用就删索引。 |
| `batch.pipeline_instance` | 5 | 保留PK（两个子FK依赖）、file_id、related_job唯一、trace、tenant/type/status。FG:383–398按tenant+run_status+started_at超时筛选，不指定pipeline_type，现复合键中间type产生缺口，列M2计划候选(tenant,run_status,started_at,id)；Console FPI:22–50 tenant/id分页同样先取代表性计划。 |
| `batch.pipeline_progress` | 4 | C3：tenant+instance短索引是tenant+instance+stage唯一键左前缀。PP:25–33点查stage、:102–106按tenant/instance删均可由长键定位；无FK依赖，计划验证后可收敛短索引。completed partial暂保留；补归档生命周期时再按真实批次查询选键。 |
| `batch.pipeline_step_definition` | 3 | 保留3项；parent+step_code唯一承担去重，parent+step_order支持排序，二者不能互替；同时都能以parent前缀支持FK父删除检查。impl/params JSON没有检索证据不加GIN。 |
| `batch.pipeline_step_run` | 3 | 保留PK、parent+step+run_seq唯一与status+started。PSR:26–28按单parent、run_seq/id排序，现唯一键中间step_code不能全覆盖该排序；若单pipeline步骤少保留排序即可，不机械加。父FK检查/归档delete已可用parent前缀。 |
| `batch.result_version` | 9 | C5：tenant+business+version DESC非unique索引与ASC unique在tenant/business等值时可反向等价访问（RV:177–204），评估收敛非unique；保留unique的版本/有效唯一语义。M2：RV:314–321 SUPERSEDED全局deactivated_at/id限批缺对应partial；ARCHIVED和DRY_RUN已有匹配partial不要重复加。asset_partition引用PK必须保留。 |
| `batch.retry_schedule` | 3 | 保留status+next_retry与tenant/dedup唯一。RS:62–77 due批次next_retry_at/id排序缺id尾键；Console RS:26–45 tenant+id游标与next_retry/id方向不同。需对真实队列/分页量级分别计划，不能为弥补所有组合盲加。 |
| `batch.worker_registry` | 6 | 保留6项。WRG:128–130/164–166 lower(worker_group)与普通worker_group键不完全等价，现tenant前缀仍能限域；先核写入大小写规范/计划，不能直接添lower表达式重复索引。heartbeat索引支持失联扫描；pool/load与group/load语义不同；JSON能力检查是小注册表扫描，无证据不加GIN。 |
| `batch.worker_report_outbox` | 3 | 保留tenant/task唯一（upsert契约）、poll(status,next_attempt)与PK。WRO:24–30 due范围后ORDER BY id LIMIT1 SKIP LOCKED，poll不完全覆盖id排序；选择先到期还是先id是业务排序，不能为索引擅改。积压量级下比对(status,id)过滤和现due扫描，暂无数据不加。 |
| `archive.atomic_task_config_archive` | 1 | 仅PK，未找到生产搬运/查询；保留主键幂等，先明确冷存生命周期，无证据不复制热表二级索引。 |
| `archive.batch_day_replay_entry_archive` | 4 | 4项含session/tenant/job unique、session/status、rerun非空partial，均保留候选；冷表当前无生产搬运，不以空表删继承的历史身份约束，未来查询明确后再收敛。 |
| `archive.batch_day_replay_session_archive` | 4 | 4项含历史tenant/calendar/date/created与status/created，以及活动会话partial unique；后者是热生命周期语义的镜像，列L1待生命周期决策，不能自动删unique或声称适合冷数据。 |
| `archive.compensation_command_archive` | 1 | 仅PK；SI:500–513按id copy/ON CONFLICT足够；尚未见冷命令列表消费者，不无证据加tenant/target索引；未来单独命令归档应带查询设计。 |
| `archive.custom_task_type_registry_archive` | 1 | 仅PK；生产搬运/查询未实现，保留身份，不加descriptor GIN。 |
| `archive.data_quality_check_archive` | 1 | 仅PK；生产搬运/查询未实现，若补按tenant/job回查再评估(tenant,job_instance_id,id)，当前不直接加。 |
| `archive.data_quality_rule_archive` | 1 | 仅PK；先确定规则版本归档/读取，scope表达式不是普通外键，无证据不加。 |
| `archive.dead_letter_task_archive` | 2 | PK+replay_status/created，cleanup-dead-letter-task.sql:69–100用id存在性守卫；保留。没有已查证冷分页消费者，不提前加全部tenant过滤组合。 |
| `archive.event_delivery_log_archive` | 2 | PK+outbox_id，OB:320–335幂等copy，历史按事件回查有前缀；当前无tenant冷列表热点证据，保留。 |
| `archive.event_outbox_retry_archive` | 2 | PK+outbox_id，同OB:338–351历史父id路径；保留，不用热表idx_scan替代冷查询量评估。 |
| `archive.file_dispatch_record_archive` | 1 | M3：仅PK，LE:299–332及RE:195–203真实冷查询按tenant/file或pipeline关联，缺配套。候选(tenant,pipeline_instance_id,id)与(tenant,file_id,id)，两条是否都必要由OR/UNION查询计划及数据分布决定。 |
| `archive.file_record_archive` | 4 | PK+tenant/biz_date/created、tenant/status/updated、trace。LE:239–260通过关联得到file id，PK可点查，优先补上游pipeline冷关联索引；不因archive有JSON就复制热GIN。 |
| `archive.job_execution_log_archive` | 1 | 仅PK；cleanup-job-execution-log.sql:51起copy+PK存在性足够；未发现本组生产冷运行日志列表，若将来按tenant/instance分页再加，不能宣称当前已满足所有查询。 |
| `archive.job_instance_archive` | 2 | PK+tenant/status/finished；LE:126–128按tenant/id，PK足够选择；保留。ID冷热身份契约另见引用报告。 |
| `archive.job_partition_archive` | 2 | PK+instance_id；copy幂等和运行树归属定位可用；尚无本组冷分页热点，不加多种tenant键。 |
| `archive.job_step_instance_archive` | 2 | PK+instance_id，保留；未来需要partition/task反查时再根据冷热调用方设计，不把所有逻辑引用都建索引。 |
| `archive.job_task_archive` | 2 | PK+instance_id，保留；worker_code是历史标签，无冷worker队列查询证据，不复制热派发索引。 |
| `archive.outbox_event_archive` | 2 | PK+publish_status/created，复制ON CONFLICT和按保留窗检索可用；无冷pending队列，不复制热priority索引。 |
| `archive.pipeline_instance_archive` | 1 | M3：仅PK；LE:153–172/251–254、RE:195–203实际按tenant+related_job查冷谱系，缺配套。候选(tenant,related_job_instance_id,id)（非空partial需验证参数谓词）优先做代表性计划。 |
| `archive.pipeline_progress_archive` | 1 | 仅PK且生产搬运缺失；先修引用报告中的归档遗漏，再依实际按tenant/pipeline/stage查询补唯一或普通键，暂不基于空镜像先堆索引。 |
| `archive.pipeline_step_run_archive` | 1 | 仅PK；暂无已查证冷step查询路径。补历史详情时评估(pipeline_instance_id,run_seq,id)，先明确tenant从冷父解析和排序契约。 |
| `archive.result_version_archive` | 6 | 6项保留历史版本唯一与PK。RV:216–218冷get按tenant/id有PK；镜像EFFECTIVE partial unique列L1：当前保留路径写ARCHIVED/DRY_RUN，是否冗余待确认全部写入/历史数据，不凭当前0行删除；同理其它继承二级键等真实冷消费者证据再收敛。 |
| `archive.trigger_outbox_event_archive` | 2 | PK+publish_status/created；cleanup-trigger-outbox-events.sql:52–82 copy+id存在性后delete保留；没有冷pending发布查询，不复制热发送索引。 |
| `archive.workflow_definition_version_archive` | 1 | 仅PK；生产搬运/查询未实现，明确版本cold fallback后才增tenant/definition/version键；目前保持不加。 |
| `archive.workflow_node_run_archive` | 1 | 仅PK；当前归档copy按源workflow筛选，冷写ON CONFLICT由PK支撑。未来冷节点详情需workflow_run_id前缀，先修漏job_instance_id并落真实冷查询；不以预计查询冒充现有热点。 |
| `archive.workflow_run_archive` | 2 | PK和tenant/id UNIQUE均保留；二者不完全重复（列序/约束不同），不因id全局唯一就随意删声明的tenant identity，需核跨schema引用与恢复契约；冷详情PK可用。 |

### 逐索引实物清单（180 / 180）

下面键定义来自实际catalog；PK/UQ表示索引本身唯一性质，依赖列同时记录引用它的FK。无`pg_constraint`记录的unique/partial仍可能承担ON CONFLICT或状态互斥语义，不能视作普通可删索引。全部valid/ready/live为true；不重复列短窗口idx_scan零值，避免误导。

| 表 | 索引 | 方式/键/谓词 | 唯一性 | 约束依赖 |
|---|---|---|---|---|
| `batch.asset_freshness_policy` | `asset_freshness_policy_pkey` | `btree (id)` | PK | p:batch.asset_freshness_policy.asset_freshness_policy_pkey |
| `batch.asset_freshness_policy` | `idx_asset_freshness_policy_enabled` | `btree (enabled, tenant_id, asset_code) WHERE (enabled = true)` | 普通 | 无catalog constraint依赖 |
| `batch.asset_freshness_policy` | `uk_asset_freshness_policy_tenant_asset` | `btree (tenant_id, asset_code, asset_type)` | UQ | u:batch.asset_freshness_policy.uk_asset_freshness_policy_tenant_asset |
| `batch.asset_partition` | `asset_partition_pkey` | `btree (id)` | PK | p:batch.asset_partition.asset_partition_pkey |
| `batch.asset_partition` | `idx_asset_partition_asset_id` | `btree (tenant_id, asset_id)` | 普通 | 无catalog constraint依赖 |
| `batch.asset_partition` | `idx_asset_partition_tenant_asset_biz_date` | `btree (tenant_id, asset_code, biz_date)` | 普通 | 无catalog constraint依赖 |
| `batch.asset_partition` | `idx_asset_partition_tenant_business_key` | `btree (tenant_id, business_key)` | 普通 | 无catalog constraint依赖 |
| `batch.asset_partition` | `idx_asset_partition_tenant_freshness_updated` | `btree (tenant_id, freshness_status, updated_at DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.asset_partition` | `idx_asset_partition_tenant_result_version` | `btree (tenant_id, result_version_id) WHERE (result_version_id IS NOT NULL)` | 普通 | 无catalog constraint依赖 |
| `batch.asset_partition` | `uk_asset_partition_tenant_asset_partition` | `btree (tenant_id, asset_code, partition_key)` | UQ | u:batch.asset_partition.uk_asset_partition_tenant_asset_partition |
| `batch.atomic_task_config` | `atomic_task_config_pkey` | `btree (id)` | PK | p:batch.atomic_task_config.atomic_task_config_pkey |
| `batch.atomic_task_config` | `idx_atomic_task_config_tenant_type` | `btree (tenant_id, task_type)` | 普通 | 无catalog constraint依赖 |
| `batch.atomic_task_config` | `uk_atomic_task_config_tenant_type_name` | `btree (tenant_id, task_type, name)` | UQ | u:batch.atomic_task_config.uk_atomic_task_config_tenant_type_name |
| `batch.compensation_checkpoint` | `compensation_checkpoint_pkey` | `btree (id)` | PK | p:batch.compensation_checkpoint.compensation_checkpoint_pkey |
| `batch.compensation_checkpoint` | `idx_compensation_checkpoint_status_created` | `btree (status, created_at)` | 普通 | 无catalog constraint依赖 |
| `batch.compensation_checkpoint` | `idx_compensation_checkpoint_tenant_comp` | `btree (tenant_id, compensation_id, step_order)` | 普通 | 无catalog constraint依赖 |
| `batch.compensation_command` | `compensation_command_pkey` | `btree (id)` | PK | p:batch.compensation_command.compensation_command_pkey |
| `batch.compensation_command` | `idx_compensation_command_lookup` | `btree (tenant_id, compensation_type, command_status, created_at DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.compensation_command` | `uk_compensation_command_running_target` | `btree (tenant_id, compensation_type, target_id) WHERE (((command_status)::text = 'RUNNING'::text) AND (target_id IS NOT NULL))` | UQ | 无catalog constraint依赖 |
| `batch.compensation_command` | `uk_compensation_command_tenant_no` | `btree (tenant_id, command_no)` | UQ | u:batch.compensation_command.uk_compensation_command_tenant_no |
| `batch.custom_task_type_registry` | `custom_task_type_registry_pkey` | `btree (id)` | PK | p:batch.custom_task_type_registry.custom_task_type_registry_pkey |
| `batch.custom_task_type_registry` | `idx_custom_task_type_registry_tenant_status` | `btree (tenant_id, status)` | 普通 | 无catalog constraint依赖 |
| `batch.custom_task_type_registry` | `uk_custom_task_type_registry_tenant_code` | `btree (tenant_id, task_type_code)` | UQ | u:batch.custom_task_type_registry.uk_custom_task_type_registry_tenant_code |
| `batch.data_asset` | `data_asset_pkey` | `btree (id)` | PK | p:batch.data_asset.data_asset_pkey；f:batch.asset_partition.asset_partition_asset_id_fkey |
| `batch.data_asset` | `idx_data_asset_tenant_type_code` | `btree (tenant_id, asset_type, asset_code)` | 普通 | 无catalog constraint依赖 |
| `batch.data_asset` | `uk_data_asset_tenant_code_type` | `btree (tenant_id, asset_code, asset_type)` | UQ | u:batch.data_asset.uk_data_asset_tenant_code_type |
| `batch.data_quality_check` | `data_quality_check_pkey` | `btree (id)` | PK | p:batch.data_quality_check.data_quality_check_pkey |
| `batch.data_quality_check` | `idx_dq_check_instance` | `btree (tenant_id, job_instance_id)` | 普通 | 无catalog constraint依赖 |
| `batch.data_quality_check` | `idx_dq_check_rule_status` | `btree (tenant_id, rule_id, status, checked_at DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.data_quality_rule` | `data_quality_rule_pkey` | `btree (id)` | PK | p:batch.data_quality_rule.data_quality_rule_pkey；f:batch.data_quality_check.fk_dq_check_rule_id |
| `batch.data_quality_rule` | `idx_dq_rule_scope` | `btree (tenant_id, scope_business_key, enabled)` | 普通 | 无catalog constraint依赖 |
| `batch.data_quality_rule` | `uk_dq_rule_tenant_code` | `btree (tenant_id, rule_code)` | UQ | u:batch.data_quality_rule.uk_dq_rule_tenant_code |
| `batch.dead_letter_task` | `dead_letter_task_pkey` | `btree (id)` | PK | p:batch.dead_letter_task.dead_letter_task_pkey |
| `batch.dead_letter_task` | `idx_dead_letter_auto_retry` | `btree (replay_status, error_class, next_replay_at, replay_count) WHERE (((replay_status)::text = ANY ((ARRAY['NEW'::character varying, 'FAILED'::character varying])::text[])) AND ((error_class)::text = 'SYSTEM'::text))` | 普通 | 无catalog constraint依赖 |
| `batch.dead_letter_task` | `idx_dead_letter_replay_status` | `btree (replay_status, created_at)` | 普通 | 无catalog constraint依赖 |
| `batch.dead_letter_task` | `idx_dead_letter_trace_id` | `btree (trace_id)` | 普通 | 无catalog constraint依赖 |
| `batch.event_delivery_log` | `event_delivery_log_pkey` | `btree (id)` | PK | p:batch.event_delivery_log.event_delivery_log_pkey |
| `batch.event_delivery_log` | `idx_event_delivery_log_outbox_event_id` | `btree (outbox_event_id)` | 普通 | 无catalog constraint依赖 |
| `batch.event_delivery_log` | `idx_event_delivery_log_outbox_tenant` | `btree (outbox_event_id, tenant_id)` | 普通 | 无catalog constraint依赖 |
| `batch.event_delivery_log` | `idx_event_delivery_log_tenant_id_desc` | `btree (tenant_id, id DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.event_delivery_log` | `idx_event_delivery_log_tenant_status` | `btree (tenant_id, delivery_status, created_at DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.event_delivery_log` | `idx_event_delivery_log_trace_id` | `btree (trace_id)` | 普通 | 无catalog constraint依赖 |
| `batch.event_outbox_retry` | `event_outbox_retry_pkey` | `btree (id)` | PK | p:batch.event_outbox_retry.event_outbox_retry_pkey |
| `batch.event_outbox_retry` | `idx_event_outbox_retry_outbox_tenant` | `btree (outbox_event_id, tenant_id)` | 普通 | 无catalog constraint依赖 |
| `batch.event_outbox_retry` | `idx_event_outbox_retry_tenant_id_desc` | `btree (tenant_id, id DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.event_outbox_retry` | `idx_event_outbox_retry_tenant_status` | `btree (tenant_id, retry_status, created_at DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.file_audit_log` | `file_audit_log_pkey` | `btree (id)` | PK | p:batch.file_audit_log.file_audit_log_pkey |
| `batch.file_audit_log` | `idx_file_audit_operation` | `btree (tenant_id, operation_type, created_at)` | 普通 | 无catalog constraint依赖 |
| `batch.file_audit_log` | `idx_file_audit_trace_id` | `btree (trace_id)` | 普通 | 无catalog constraint依赖 |
| `batch.file_channel_config` | `file_channel_config_pkey` | `btree (id)` | PK | p:batch.file_channel_config.file_channel_config_pkey |
| `batch.file_channel_config` | `idx_file_channel_config_active` | `btree (tenant_id, channel_code) WHERE (is_deleted = false)` | 普通 | 无catalog constraint依赖 |
| `batch.file_channel_config` | `idx_file_channel_enabled` | `btree (tenant_id, channel_type, enabled)` | 普通 | 无catalog constraint依赖 |
| `batch.file_channel_config` | `idx_file_channel_tenant_id_desc` | `btree (tenant_id, id DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.file_channel_config` | `uk_file_channel_config` | `btree (tenant_id, channel_code)` | UQ | u:batch.file_channel_config.uk_file_channel_config |
| `batch.file_channel_health` | `file_channel_health_pkey` | `btree (id)` | PK | p:batch.file_channel_health.file_channel_health_pkey |
| `batch.file_channel_health` | `idx_file_channel_health_next_probe` | `btree (health_status, next_probe_at)` | 普通 | 无catalog constraint依赖 |
| `batch.file_channel_health` | `uk_file_channel_health` | `btree (tenant_id, channel_code)` | UQ | u:batch.file_channel_health.uk_file_channel_health |
| `batch.file_dispatch_record` | `file_dispatch_record_pkey` | `btree (id)` | PK | p:batch.file_dispatch_record.file_dispatch_record_pkey |
| `batch.file_dispatch_record` | `idx_file_dispatch_external_request` | `btree (external_request_id)` | 普通 | 无catalog constraint依赖 |
| `batch.file_dispatch_record` | `idx_file_dispatch_status` | `btree (tenant_id, dispatch_status, dispatched_at)` | 普通 | 无catalog constraint依赖 |
| `batch.file_error_record` | `file_error_record_pkey` | `btree (id)` | PK | p:batch.file_error_record.file_error_record_pkey |
| `batch.file_error_record` | `idx_file_error_record_created_at` | `btree (created_at DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.file_error_record` | `idx_file_error_record_tenant_file` | `btree (tenant_id, file_id, error_stage, error_code)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `file_record_pkey` | `btree (id)` | PK | p:batch.file_record.file_record_pkey；f:batch.pipeline_instance.pipeline_instance_file_id_fkey；f:batch.file_dispatch_record.file_dispatch_record_file_id_fkey；f:batch.file_audit_log.file_audit_log_file_id_fkey |
| `batch.file_record` | `idx_file_record_active_tenant_bizdate` | `btree (tenant_id, biz_date) WHERE ((file_status)::text <> ALL ((ARRAY['DELETED'::character varying, 'ARCHIVED'::character varying])::text[]))` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `idx_file_record_arrival_group_code_expr` | `btree (tenant_id, ((metadata_json ->> 'fileGroupCode'::text))) WHERE (metadata_json ? 'fileGroupCode'::text)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `idx_file_record_arrival_state_expr` | `btree (tenant_id, COALESCE((metadata_json ->> 'arrivalState'::text), 'WAITING_ARRIVAL'::text)) WHERE (metadata_json ? 'fileGroupCode'::text)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `idx_file_record_biz_date` | `btree (tenant_id, biz_date, created_at)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `idx_file_record_biz_type_status` | `btree (tenant_id, biz_type, file_status)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `idx_file_record_file_name_trgm` | `gin (file_name batch.gin_trgm_ops)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `idx_file_record_latest` | `btree (tenant_id, is_latest, file_name)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `idx_file_record_metadata_json_gin` | `gin (metadata_json)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `idx_file_record_metadata_json_gin_partial` | `gin (metadata_json) WHERE (metadata_json IS NOT NULL)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `idx_file_record_tenant_id_desc` | `btree (tenant_id, id DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `idx_file_record_trace_id` | `btree (trace_id)` | 普通 | 无catalog constraint依赖 |
| `batch.file_record` | `uk_file_record_no_checksum` | `btree (tenant_id, storage_path) WHERE (checksum_value IS NULL)` | UQ | 无catalog constraint依赖 |
| `batch.file_record` | `uk_file_record_with_checksum` | `btree (tenant_id, checksum_value, storage_path) WHERE (checksum_value IS NOT NULL)` | UQ | 无catalog constraint依赖 |
| `batch.file_template_config` | `file_template_config_pkey` | `btree (id)` | PK | p:batch.file_template_config.file_template_config_pkey |
| `batch.file_template_config` | `idx_file_template_config_active` | `btree (tenant_id, template_code) WHERE (is_deleted = false)` | 普通 | 无catalog constraint依赖 |
| `batch.file_template_config` | `idx_file_template_enabled_updated` | `btree (enabled, updated_at)` | 普通 | 无catalog constraint依赖 |
| `batch.file_template_config` | `idx_file_template_tenant_code_version` | `btree (tenant_id, template_code, version DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.file_template_config` | `idx_file_template_type_biz` | `btree (tenant_id, template_type, biz_type)` | 普通 | 无catalog constraint依赖 |
| `batch.file_template_config` | `uk_file_template_tenant_code_version` | `btree (tenant_id, template_code, version)` | UQ | u:batch.file_template_config.uk_file_template_tenant_code_version |
| `batch.outbox_event` | `idx_outbox_p_aggregate` | `btree (aggregate_type, aggregate_id)` | 普通 | 无catalog constraint依赖 |
| `batch.outbox_event` | `idx_outbox_p_priority_pending` | `btree (publish_status, next_publish_at NULLS FIRST, priority DESC, id) WHERE ((publish_status)::text = ANY ((ARRAY['NEW'::character varying, 'FAILED'::character varying])::text[]))` | 普通 | 无catalog constraint依赖 |
| `batch.outbox_event` | `idx_outbox_p_publish_status` | `btree (publish_status, next_publish_at)` | 普通 | 无catalog constraint依赖 |
| `batch.outbox_event` | `idx_outbox_p_tenant_status_scheduled` | `btree (tenant_id, publish_status, next_publish_at)` | 普通 | 无catalog constraint依赖 |
| `batch.outbox_event` | `outbox_event_p_pkey` | `btree (id, created_at)` | PK | p:batch.outbox_event.outbox_event_p_pkey |
| `batch.outbox_event` | `uk_outbox_event_key` | `btree (tenant_id, event_key, created_at)` | UQ | u:batch.outbox_event.uk_outbox_event_key |
| `batch.outbox_event_dedup_key` | `outbox_event_dedup_key_pkey` | `btree (tenant_id, event_key)` | PK | p:batch.outbox_event_dedup_key.outbox_event_dedup_key_pkey |
| `batch.pipeline_definition` | `idx_pipeline_definition_tenant_id_desc` | `btree (tenant_id, id DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.pipeline_definition` | `idx_pipeline_definition_type_enabled` | `btree (tenant_id, pipeline_type, enabled)` | 普通 | 无catalog constraint依赖 |
| `batch.pipeline_definition` | `pipeline_definition_pkey` | `btree (id)` | PK | p:batch.pipeline_definition.pipeline_definition_pkey；f:batch.pipeline_step_definition.pipeline_step_definition_pipeline_definition_id_fkey |
| `batch.pipeline_definition` | `uk_pipeline_definition_tenant_code_version` | `btree (tenant_id, job_code, version)` | UQ | u:batch.pipeline_definition.uk_pipeline_definition_tenant_code_version |
| `batch.pipeline_instance` | `idx_pipeline_instance_file_id` | `btree (file_id)` | 普通 | 无catalog constraint依赖 |
| `batch.pipeline_instance` | `idx_pipeline_instance_trace_id` | `btree (trace_id)` | 普通 | 无catalog constraint依赖 |
| `batch.pipeline_instance` | `idx_pipeline_instance_type_status` | `btree (tenant_id, pipeline_type, run_status)` | 普通 | 无catalog constraint依赖 |
| `batch.pipeline_instance` | `pipeline_instance_pkey` | `btree (id)` | PK | p:batch.pipeline_instance.pipeline_instance_pkey；f:batch.pipeline_step_run.pipeline_step_run_pipeline_instance_id_fkey；f:batch.file_dispatch_record.file_dispatch_record_pipeline_instance_id_fkey |
| `batch.pipeline_instance` | `uk_pipeline_instance_job_instance` | `btree (related_job_instance_id) WHERE (related_job_instance_id IS NOT NULL)` | UQ | 无catalog constraint依赖 |
| `batch.pipeline_progress` | `idx_pipeline_progress_completed_at` | `btree (completed_at) WHERE (completed = true)` | 普通 | 无catalog constraint依赖 |
| `batch.pipeline_progress` | `idx_pipeline_progress_tenant_instance` | `btree (tenant_id, pipeline_instance_id)` | 普通 | 无catalog constraint依赖 |
| `batch.pipeline_progress` | `pipeline_progress_pkey` | `btree (id)` | PK | p:batch.pipeline_progress.pipeline_progress_pkey |
| `batch.pipeline_progress` | `uk_pipeline_progress_tenant_instance_stage` | `btree (tenant_id, pipeline_instance_id, stage)` | UQ | u:batch.pipeline_progress.uk_pipeline_progress_tenant_instance_stage |
| `batch.pipeline_step_definition` | `idx_pipeline_step_definition_order` | `btree (pipeline_definition_id, step_order)` | 普通 | 无catalog constraint依赖 |
| `batch.pipeline_step_definition` | `pipeline_step_definition_pkey` | `btree (id)` | PK | p:batch.pipeline_step_definition.pipeline_step_definition_pkey |
| `batch.pipeline_step_definition` | `uk_pipeline_step_definition` | `btree (pipeline_definition_id, step_code)` | UQ | u:batch.pipeline_step_definition.uk_pipeline_step_definition |
| `batch.pipeline_step_run` | `idx_pipeline_step_run_status_started` | `btree (step_status, started_at)` | 普通 | 无catalog constraint依赖 |
| `batch.pipeline_step_run` | `pipeline_step_run_pkey` | `btree (id)` | PK | p:batch.pipeline_step_run.pipeline_step_run_pkey |
| `batch.pipeline_step_run` | `uk_pipeline_step_run` | `btree (pipeline_instance_id, step_code, run_seq)` | UQ | u:batch.pipeline_step_run.uk_pipeline_step_run |
| `batch.result_version` | `idx_result_version_archived_cleanup` | `btree (updated_at, id) WHERE ((status)::text = 'ARCHIVED'::text)` | 普通 | 无catalog constraint依赖 |
| `batch.result_version` | `idx_result_version_business_status` | `btree (tenant_id, business_key, status)` | 普通 | 无catalog constraint依赖 |
| `batch.result_version` | `idx_result_version_dry_run_retention` | `btree (generated_at, id) WHERE ((status)::text = 'DRY_RUN'::text)` | 普通 | 无catalog constraint依赖 |
| `batch.result_version` | `idx_result_version_job_instance` | `btree (tenant_id, job_instance_id)` | 普通 | 无catalog constraint依赖 |
| `batch.result_version` | `idx_result_version_status_generated` | `btree (tenant_id, status, generated_at DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.result_version` | `idx_result_version_tenant_business_version` | `btree (tenant_id, business_key, version_no DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.result_version` | `result_version_pkey` | `btree (id)` | PK | p:batch.result_version.result_version_pkey；f:batch.asset_partition.asset_partition_result_version_id_fkey |
| `batch.result_version` | `uk_result_version_business_version` | `btree (tenant_id, business_key, version_no)` | UQ | 无catalog constraint依赖 |
| `batch.result_version` | `uk_result_version_effective` | `btree (tenant_id, business_key) WHERE ((status)::text = 'EFFECTIVE'::text)` | UQ | 无catalog constraint依赖 |
| `batch.retry_schedule` | `idx_retry_schedule_next_retry` | `btree (retry_status, next_retry_at)` | 普通 | 无catalog constraint依赖 |
| `batch.retry_schedule` | `retry_schedule_pkey` | `btree (id)` | PK | p:batch.retry_schedule.retry_schedule_pkey |
| `batch.retry_schedule` | `uk_retry_schedule_tenant_dedup` | `btree (tenant_id, dedup_key)` | UQ | u:batch.retry_schedule.uk_retry_schedule_tenant_dedup |
| `batch.worker_registry` | `idx_worker_registry_group_status` | `btree (worker_group, status)` | 普通 | 无catalog constraint依赖 |
| `batch.worker_registry` | `idx_worker_registry_heartbeat_at` | `btree (heartbeat_at)` | 普通 | 无catalog constraint依赖 |
| `batch.worker_registry` | `idx_worker_registry_load` | `btree (tenant_id, worker_group, status, current_load)` | 普通 | 无catalog constraint依赖 |
| `batch.worker_registry` | `idx_worker_registry_pool_status_load` | `btree (tenant_id, worker_pool_code, status, current_load, heartbeat_at DESC)` | 普通 | 无catalog constraint依赖 |
| `batch.worker_registry` | `uk_worker_registry_tenant_worker` | `btree (tenant_id, worker_code)` | UQ | u:batch.worker_registry.uk_worker_registry_tenant_worker |
| `batch.worker_registry` | `worker_registry_pkey` | `btree (id)` | PK | p:batch.worker_registry.worker_registry_pkey |
| `batch.worker_report_outbox` | `idx_worker_report_outbox_poll` | `btree (publish_status, next_attempt_at)` | 普通 | 无catalog constraint依赖 |
| `batch.worker_report_outbox` | `uq_worker_report_outbox_tenant_task` | `btree (tenant_id, task_id)` | UQ | u:batch.worker_report_outbox.uq_worker_report_outbox_tenant_task |
| `batch.worker_report_outbox` | `worker_report_outbox_pkey` | `btree (id)` | PK | p:batch.worker_report_outbox.worker_report_outbox_pkey |
| `archive.atomic_task_config_archive` | `pk_atomic_task_config_archive` | `btree (id)` | PK | p:archive.atomic_task_config_archive.pk_atomic_task_config_archive |
| `archive.batch_day_replay_entry_archive` | `batch_day_replay_entry_archiv_session_id_tenant_id_job_code_idx` | `btree (session_id, tenant_id, job_code)` | UQ | 无catalog constraint依赖 |
| `archive.batch_day_replay_entry_archive` | `batch_day_replay_entry_archive_pkey` | `btree (id)` | PK | p:archive.batch_day_replay_entry_archive.batch_day_replay_entry_archive_pkey |
| `archive.batch_day_replay_entry_archive` | `batch_day_replay_entry_archive_rerun_instance_id_idx` | `btree (rerun_instance_id) WHERE (rerun_instance_id IS NOT NULL)` | 普通 | 无catalog constraint依赖 |
| `archive.batch_day_replay_entry_archive` | `batch_day_replay_entry_archive_session_id_status_idx` | `btree (session_id, status)` | 普通 | 无catalog constraint依赖 |
| `archive.batch_day_replay_session_archive` | `batch_day_replay_session_arch_tenant_id_calendar_code_biz__idx1` | `btree (tenant_id, calendar_code, biz_date, created_at DESC)` | 普通 | 无catalog constraint依赖 |
| `archive.batch_day_replay_session_archive` | `batch_day_replay_session_arch_tenant_id_calendar_code_biz_d_idx` | `btree (tenant_id, calendar_code, biz_date) WHERE ((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'RUNNING'::character varying])::text[]))` | UQ | 无catalog constraint依赖 |
| `archive.batch_day_replay_session_archive` | `batch_day_replay_session_archiv_tenant_id_status_created_at_idx` | `btree (tenant_id, status, created_at DESC)` | 普通 | 无catalog constraint依赖 |
| `archive.batch_day_replay_session_archive` | `batch_day_replay_session_archive_pkey` | `btree (id)` | PK | p:archive.batch_day_replay_session_archive.batch_day_replay_session_archive_pkey |
| `archive.compensation_command_archive` | `pk_compensation_command_archive` | `btree (id)` | PK | p:archive.compensation_command_archive.pk_compensation_command_archive |
| `archive.custom_task_type_registry_archive` | `pk_custom_task_type_registry_archive` | `btree (id)` | PK | p:archive.custom_task_type_registry_archive.pk_custom_task_type_registry_archive |
| `archive.data_quality_check_archive` | `pk_data_quality_check_archive` | `btree (id)` | PK | p:archive.data_quality_check_archive.pk_data_quality_check_archive |
| `archive.data_quality_rule_archive` | `pk_data_quality_rule_archive` | `btree (id)` | PK | p:archive.data_quality_rule_archive.pk_data_quality_rule_archive |
| `archive.dead_letter_task_archive` | `idx_dead_letter_task_archive_replay_status_created` | `btree (replay_status, created_at)` | 普通 | 无catalog constraint依赖 |
| `archive.dead_letter_task_archive` | `pk_dead_letter_task_archive` | `btree (id)` | PK | p:archive.dead_letter_task_archive.pk_dead_letter_task_archive |
| `archive.event_delivery_log_archive` | `idx_event_delivery_log_archive_outbox` | `btree (outbox_event_id)` | 普通 | 无catalog constraint依赖 |
| `archive.event_delivery_log_archive` | `pk_event_delivery_log_archive` | `btree (id)` | PK | p:archive.event_delivery_log_archive.pk_event_delivery_log_archive |
| `archive.event_outbox_retry_archive` | `idx_event_outbox_retry_archive_outbox` | `btree (outbox_event_id)` | 普通 | 无catalog constraint依赖 |
| `archive.event_outbox_retry_archive` | `pk_event_outbox_retry_archive` | `btree (id)` | PK | p:archive.event_outbox_retry_archive.pk_event_outbox_retry_archive |
| `archive.file_dispatch_record_archive` | `pk_file_dispatch_record_archive` | `btree (id)` | PK | p:archive.file_dispatch_record_archive.pk_file_dispatch_record_archive |
| `archive.file_record_archive` | `idx_file_record_archive_biz_date` | `btree (tenant_id, biz_date, created_at)` | 普通 | 无catalog constraint依赖 |
| `archive.file_record_archive` | `idx_file_record_archive_status` | `btree (tenant_id, file_status, updated_at)` | 普通 | 无catalog constraint依赖 |
| `archive.file_record_archive` | `idx_file_record_archive_trace_id` | `btree (trace_id)` | 普通 | 无catalog constraint依赖 |
| `archive.file_record_archive` | `pk_file_record_archive` | `btree (id)` | PK | p:archive.file_record_archive.pk_file_record_archive |
| `archive.job_execution_log_archive` | `pk_job_execution_log_archive` | `btree (id)` | PK | p:archive.job_execution_log_archive.pk_job_execution_log_archive |
| `archive.job_instance_archive` | `idx_job_instance_archive_finished` | `btree (tenant_id, instance_status, finished_at)` | 普通 | 无catalog constraint依赖 |
| `archive.job_instance_archive` | `pk_job_instance_archive` | `btree (id)` | PK | p:archive.job_instance_archive.pk_job_instance_archive |
| `archive.job_partition_archive` | `idx_job_partition_archive_instance` | `btree (job_instance_id)` | 普通 | 无catalog constraint依赖 |
| `archive.job_partition_archive` | `pk_job_partition_archive` | `btree (id)` | PK | p:archive.job_partition_archive.pk_job_partition_archive |
| `archive.job_step_instance_archive` | `idx_job_step_instance_archive_instance` | `btree (job_instance_id)` | 普通 | 无catalog constraint依赖 |
| `archive.job_step_instance_archive` | `pk_job_step_instance_archive` | `btree (id)` | PK | p:archive.job_step_instance_archive.pk_job_step_instance_archive |
| `archive.job_task_archive` | `idx_job_task_archive_instance` | `btree (job_instance_id)` | 普通 | 无catalog constraint依赖 |
| `archive.job_task_archive` | `pk_job_task_archive` | `btree (id)` | PK | p:archive.job_task_archive.pk_job_task_archive |
| `archive.outbox_event_archive` | `idx_outbox_event_archive_created` | `btree (publish_status, created_at)` | 普通 | 无catalog constraint依赖 |
| `archive.outbox_event_archive` | `pk_outbox_event_archive` | `btree (id)` | PK | p:archive.outbox_event_archive.pk_outbox_event_archive |
| `archive.pipeline_instance_archive` | `pk_pipeline_instance_archive` | `btree (id)` | PK | p:archive.pipeline_instance_archive.pk_pipeline_instance_archive |
| `archive.pipeline_progress_archive` | `pk_pipeline_progress_archive` | `btree (id)` | PK | p:archive.pipeline_progress_archive.pk_pipeline_progress_archive |
| `archive.pipeline_step_run_archive` | `pk_pipeline_step_run_archive` | `btree (id)` | PK | p:archive.pipeline_step_run_archive.pk_pipeline_step_run_archive |
| `archive.result_version_archive` | `result_version_archive_pkey` | `btree (id)` | PK | p:archive.result_version_archive.result_version_archive_pkey |
| `archive.result_version_archive` | `result_version_archive_tenant_id_business_key_idx` | `btree (tenant_id, business_key) WHERE ((status)::text = 'EFFECTIVE'::text)` | UQ | 无catalog constraint依赖 |
| `archive.result_version_archive` | `result_version_archive_tenant_id_business_key_status_idx` | `btree (tenant_id, business_key, status)` | 普通 | 无catalog constraint依赖 |
| `archive.result_version_archive` | `result_version_archive_tenant_id_business_key_version_no_idx` | `btree (tenant_id, business_key, version_no)` | UQ | 无catalog constraint依赖 |
| `archive.result_version_archive` | `result_version_archive_tenant_id_job_instance_id_idx` | `btree (tenant_id, job_instance_id)` | 普通 | 无catalog constraint依赖 |
| `archive.result_version_archive` | `result_version_archive_tenant_id_status_generated_at_idx` | `btree (tenant_id, status, generated_at DESC)` | 普通 | 无catalog constraint依赖 |
| `archive.trigger_outbox_event_archive` | `idx_trigger_outbox_event_archive_created` | `btree (publish_status, created_at)` | 普通 | 无catalog constraint依赖 |
| `archive.trigger_outbox_event_archive` | `pk_trigger_outbox_event_archive` | `btree (id)` | PK | p:archive.trigger_outbox_event_archive.pk_trigger_outbox_event_archive |
| `archive.workflow_definition_version_archive` | `pk_workflow_definition_version_archive` | `btree (id)` | PK | p:archive.workflow_definition_version_archive.pk_workflow_definition_version_archive |
| `archive.workflow_node_run_archive` | `pk_workflow_node_run_archive` | `btree (id)` | PK | p:archive.workflow_node_run_archive.pk_workflow_node_run_archive |
| `archive.workflow_run_archive` | `pk_workflow_run_archive` | `btree (id)` | PK | p:archive.workflow_run_archive.pk_workflow_run_archive |
| `archive.workflow_run_archive` | `uk_workflow_run_archive_tenant_id` | `btree (tenant_id, id)` | UQ | u:archive.workflow_run_archive.uk_workflow_run_archive_tenant_id |

### 整改实施与验收顺序

1. 先处理FK/归档缺口的索引配套（M1/M3），并落实正确查询、tenant和归档生命周期；查询逻辑错误不能靠索引遮盖。
2. 取代表性业务数据与真实调用参数：大小租户、近期/历史、空/积压队列、不同状态分布、冷热谱系、软删除比例；记录EXPLAIN、BUFFERS（经授权的可控环境）、延迟、扫描/返回比例、索引大小、写入成本。
3. 对M2与分页/JSON候选逐条验证，决定保留、改键、加partial或收敛查询；所有索引变动走migration-safety，对分区父子、约束依赖、构建锁与失败清理分别设计。
4. C1–C5只有在保留索引确实覆盖全部调用且无完整性依赖后才移除；本报告没有授权也没有执行DROP。
5. 验证结果集/稳定分页/顺序/tenant隔离不变，在线写入和归档无新锁退化；用完整业务周期统计复核，而不是新启动4分钟idx_scan。

## 附录 C：配置、身份、通知、Quartz 与业务库索引逐表审查

### 基线与结论边界

本轮按 `sql-query-performance` 与 `database-migration-safety` 只读核查 `/tmp/fk-audit-20261018/platform-index.json`、`business-index.json`，快照为 **2026-10-18 09:06:34 +08:00** 左右，PostgreSQL 17.11，postmaster 09:02 启动。该实例已经重建，不能与08:54外键目录OID/统计混用。统计只覆盖约4分钟，多表空/只有种子数据；**idx_scan=0不构成删索引理由，分区父idx_scan=NULL不等于没有使用叶子索引**。

覆盖 **68/68 逻辑表、196 个逻辑表索引**：平台batch 47、Quartz 11、业务库10（分区子索引另由主报告核对）。索引表扫描包含method、keys、ASC/DESC/NULL排序options、opclass、collation、predicate、expression、INCLUDE(attribute_count与key_count)、unique/nulls_not_distinct以及约束依赖。未运行EXPLAIN、业务行查询、DDL/DML或测试；以下匹配/不匹配均为结构与SQL证据，不能表述为已测性能收益。

- 这68表范围未发现完整定义一致的重复索引。仅相同列名但排序/表达式/谓词/唯一性不同不列为精确重复。
- **9个非框架严格左前缀候选**（平台5、业务4）；它们没有自己的PK/UNIQUE约束依赖，但删除仍需代表规模计划、体积/写放大与其他调用证据。较短索引可能更小更快，不自动删除。
- **Quartz另4个短索引、5条前缀配对**；框架恢复/集群模型暂保留；不以通用去重算法直接改官方模型。
- AI turn另有1组固定等值前缀下正反扫描等效候选，和严格同方向前缀计数分开。

### 需进入整改/测量清单

| ID | 级别 | 事实、建议与验收 |
|---|---|---|
| IC01 | P2，优先核实并收敛 | webhook_delivery_log现partial只`EXHAUSTED AND next_retry_at IS NOT NULL`，而Relay读取`IN(PENDING,EXHAUSTED)`；查询整体不蕴含索引谓词，现索引不能完整支持该候选集合。检查PENDING可靠投递新增之后的索引漂移。候选以`next_retry_at,id`为键、两状态+非空为partial；保留状态一致性与FOR UPDATE SKIP LOCKED。状态经参数传入时需验证prepared custom/generic计划能证明谓词，必要时选能稳定生效的索引/SQL结构。代码：ConsoleWebhookDeliveryLogMapper.java:35–39，XML:65–76；具体收益/扫描量待代表规模EXPLAIN。 |
| IC02 | P2，测量后决定索引/查询修改 | config_approval最新审批、config_change_log与config_sync_log倒序历史、forensic_export_log租户最近、console_operation_audit双分页模式与当前索引排序不完全一致。逐表行列出候选；先固化排序/分页接口契约，不为了索引改变结果顺序。不要新建所有可选筛选排列，不将小配置表的Sort直接定性慢查询。 |
| IC03 | P2，积压/保留期量级验证 | console_ai_object_cleanup仅(tenant,object_key)PK但按created_at/object_key取批；push_subscription按last_seen_at全局清理无时间索引。二者按最坏允许积压/订阅量验证，考虑有界批量清理和合适时间索引。现对象队列PK必须保留去重；不能把补索引当作无限批量DELETE资源问题的完整修复。 |
| IC04 | P3/容量治理候选，非发布阻断 | 以下9个非框架前缀候选与AI turn正反扫描候选先评读写比/尺寸/计划，验证查询结果和FK/ON CONFLICT/唯一性不变再决定删除。没有已测节省，不写“可直接删除”。Quartz框架配对单列保留。 |
| IC05 | 与外键整改配套 | 新增tenant复合FK时审查支持索引，但不为每条FK自动增加一个完全同列索引。已有短索引/其他选择性前缀可能足够；settlement_detail.batch_id单列明确可服务tenant+batch双等值，需计划而非机械“缺租户前缀”判无索引。 |

查询证据路径：除特别注明，Console XML在 `batch-console-api/src/main/resources/mapper/`，Orchestrator XML在 `batch-orchestrator/src/main/resources/mapper/`；Webhook Mapper Java在 `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/mapper/`；业务DDL在 `scripts/db/business/create_biz_tables.sql`。Quartz依据 `db/migration/V2__create_quartz_tables_postgres_2_5_2.sql:153–187` 及当前目录，不声称本轮执行过框架恢复负载。

### 左前缀候选清单

| 库/表 | 短索引 | 覆盖其相同排序/操作类/谓词前缀的长索引 | 决定 |
|---|---|---|---|
| platform.batch.biz_table_schema | `idx_biz_table_schema` | `uk_biz_table_schema` | 仅候选，IC04代表规模验证后决定 |
| platform.batch.console_push_subscription | `idx_console_push_sub_tenant_user` | `uq_console_push_subscription` | 仅候选，IC04代表规模验证后决定 |
| platform.batch.process_staging | `idx_process_staging_batch_key` | `process_staging_pkey` | 仅候选，IC04代表规模验证后决定 |
| platform.batch.resource_tag | `idx_resource_tag_tenant_type` | `uk_resource_tag` | 仅候选，IC04代表规模验证后决定 |
| platform.batch.step_registry | `idx_step_registry_module` | `uk_step_registry_module_impl` | 仅候选，IC04代表规模验证后决定 |
| platform.quartz.qrtz_fired_triggers | `idx_qrtz_ft_trig_inst_name` | `idx_qrtz_ft_inst_job_req_rcvry` | Quartz框架暂保留 |
| platform.quartz.qrtz_triggers | `idx_qrtz_t_g` | `idx_qrtz_t_n_g_state` | Quartz框架暂保留 |
| platform.quartz.qrtz_triggers | `idx_qrtz_t_nft_misfire` | `idx_qrtz_t_nft_st_misfire` | Quartz框架暂保留 |
| platform.quartz.qrtz_triggers | `idx_qrtz_t_nft_misfire` | `idx_qrtz_t_nft_st_misfire_grp` | Quartz框架暂保留 |
| platform.quartz.qrtz_triggers | `idx_qrtz_t_state` | `idx_qrtz_t_nft_st` | Quartz框架暂保留 |
| business.batch.process_staging | `idx_process_staging_batch_key` | `process_staging_pkey` | 仅候选，IC04代表规模验证后决定 |
| business.biz.risk_alert | `idx_risk_alert_tenant_date` | `risk_alert_pkey` | 仅候选，IC04代表规模验证后决定 |
| business.biz.risk_score | `idx_risk_score_tenant_date` | `risk_score_pkey` | 仅候选，IC04代表规模验证后决定 |
| business.biz.transaction | `idx_transaction_tenant_date` | `transaction_pkey` | 仅候选，IC04代表规模验证后决定 |

AI turn单列候选：`idx_console_ai_turn_history(tenant_id,conversation_id,turn_no DESC)` 对比约束索引 `uk_console_ai_turn_sequence(...,turn_no ASC)`。查询等值固定tenant/conversation且turn_no非空时反扫可以提供降序；不同查询若不固定前缀则混合顺序不等价。不能删除UNIQUE，且需验证所有history/分页SQL。

### 68表逐表审查

每行列出该逻辑表全部索引（括号U表示UNIQUE，P表示PK）；完整DDL/大小/统计由主报告索引目录保存。

| 库.表 | 索引数 / 全部索引 | 查询与处置结论 |
|---|---|---|
| platform.batch.api_key | 5：`api_key_pkey` P btree (id)<br>`idx_api_key_prefix` btree (key_prefix)<br>`idx_api_key_tenant` btree (tenant_id, enabled)<br>`uk_api_key_hash` U btree (key_hash)<br>`uk_api_key_tenant_name` U btree (tenant_id, key_name) | 鉴权按key_prefix/key_hash、管理按tenant/name均有对应索引；tenant列表ORDER BY created_at未由tenant/enabled提供排序，配置规模下暂保留，不为小表补索引。ConsoleApiKeyMapper.xml:27–46。 |
| platform.batch.batch_runtime_default_parameter | 2：`batch_runtime_default_parameter_pkey` P btree (id)<br>`uk_batch_runtime_default_parameter` U btree (module, parameter_key) | 模块+参数唯一键覆盖配置点查/upsert；参数字典小表全量载入合理，不补额外索引。V24:8。 |
| platform.batch.biz_table_schema | 3：`biz_table_schema_pkey` P btree (id)<br>`idx_biz_table_schema` btree (schema_name)<br>`uk_biz_table_schema` U btree (schema_name, table_name) | schema索引是UNIQUE(schema_name,table_name)严格左前缀候选；并非直接删结论。结构注册/按schema读取能走唯一键，先比较现有尺寸和计划。BizTableSchemaMapper.xml。 |
| platform.batch.business_calendar | 4：`business_calendar_pkey` P btree (id)<br>`idx_business_calendar_enabled` btree (tenant_id, enabled)<br>`idx_business_calendar_tenant_id_desc` btree (tenant_id, id DESC)<br>`uk_business_calendar_tenant_code` U btree (tenant_id, calendar_code) | tenant+code点查、tenant+id倒序分页、tenant+enabled枚举均有对应入口；三种键不互相替代，保留。BusinessCalendarMapper.xml（Console/Orchestrator）。 |
| platform.batch.business_shard_catalog | 1：`business_shard_catalog_pkey` P btree (placement_key) | PK覆盖placement点查/排序；enabled全量目录读取很小，单独boolean索引没有证据需求。ConsoleBusinessShardCatalogMapper.xml:19–27。 |
| platform.batch.business_tenant_placement | 1：`business_tenant_placement_pkey` P btree (tenant_id) | PK覆盖tenant路由点查；管理列表按placement,tenant排序需sort，但配置小表可接受。C04目录删除保护如引入按placement引用查，届时测(placement_key,tenant_id)，不因FK计划预先堆索引。ConsoleBusinessTenantPlacementMapper.xml:14–17。 |
| platform.batch.config_approval | 4：`config_approval_pkey` P btree (id)<br>`idx_config_approval_release` btree (release_id)<br>`idx_config_approval_tenant_status` btree (tenant_id, approval_status, created_at DESC)<br>`uk_config_approval_pending_release` U btree (tenant_id, release_id) WHERE ((approval_status)::text = 'PENDING'::text) | IC02：当前按tenant+release取最大id，只有release单列和仅PENDING的tenant/release唯一键；后者不覆盖全状态最新审批，前者过滤后需sort。候选(tenant_id,release_id,id DESC)，与复合FK落地合并评估。ConfigApprovalMapper.xml:5–22。 |
| platform.batch.config_change_log | 2：`config_change_log_pkey` P btree (id)<br>`idx_config_change_log_tenant_action` btree (tenant_id, change_action, created_at DESC) | IC02：tenant必选、config_type/key/action可选，最终ORDER BY id DESC；现tenant/action/created_at索引与默认排序不一致，且selectByQuery未LIMIT。先限定查询分页契约，再测tenant/id或tenant/config_type/config_key/id；不添加所有排列。ConfigChangeLogMapper.xml:19–32。 |
| platform.batch.config_release | 3：`config_release_pkey` P btree (id)<br>`idx_config_release_tenant_status` btree (tenant_id, config_status, created_at DESC)<br>`uk_config_release_tenant_type_key_version` U btree (tenant_id, config_type, config_key, version_no) | 版本唯一键覆盖tenant/type/key/version点查和max(version)；默认列表按key ASC,version DESC,id DESC与UNIQUE正序不完全一致。配置低量暂保留，不能仅为ORDER BY新建大复合索引。ConfigReleaseMapper.xml:25–40,68–73。 |
| platform.batch.config_sync_log | 2：`config_sync_log_pkey` P btree (id)<br>`idx_config_sync_log_tenant` btree (tenant_id, created_at DESC) | IC02：SQL tenant过滤ORDER BY id DESC LIMIT，现tenant/created_at索引只帮过滤、不能提供id顺序；候选替换为tenant/id须先查保留期/其他调用。ConfigSyncLogMapper.xml:5–24。 |
| platform.batch.console_ai_attachment | 5：`idx_console_ai_attachment_expiry` btree (tenant_id, expires_at)<br>`idx_console_ai_attachment_turn` btree (tenant_id, conversation_id, turn_no)<br>`pk_console_ai_attachment` P btree (tenant_id, id)<br>`uk_console_ai_attachment_client` U btree (tenant_id, owner_user_id, client_attachment_id)<br>`uk_console_ai_attachment_object` U btree (object_key) | tenant/expires清理、tenant/conversation/turn关联、tenant/owner/client幂等均有索引；同owner的COUNT/SUM可用client唯一键前缀再过滤状态/过期，大附件配额聚合需代表规模，不直接新增宽INCLUDE。ConsoleAiAttachmentMapper.xml:35–55,94–98,136–138。 |
| platform.batch.console_ai_audit_log | 4：`console_ai_audit_log_pkey` P btree (id)<br>`idx_console_ai_audit_created_at` btree (created_at)<br>`idx_console_ai_audit_tenant_id_desc` btree (tenant_id, id DESC)<br>`uk_console_ai_audit_request` U btree (tenant_id, request_id) | tenant/id倒序覆盖keyset、created_at覆盖全局TTL、tenant/request唯一覆盖幂等；时间过滤与id排序混合时需计划，但不是缺失时间索引。ConsoleAiAuditLogMapper.xml:60–82,115–118。 |
| platform.batch.console_ai_conversation | 3：`idx_console_ai_conversation_expiry` btree (expires_at)<br>`idx_console_ai_conversation_owner_updated` btree (tenant_id, owner_user_id, updated_at DESC, id DESC)<br>`pk_console_ai_conversation` P btree (tenant_id, id) | tenant/owner/updated_at DESC/id DESC与复合游标一致；expires索引支持全局到期，按tenant清理可能走PK前缀或expires过滤，实际规模再判。ConsoleAiConversationMapper.xml:127–148,180–183。 |
| platform.batch.console_ai_monthly_usage | 1：`pk_console_ai_monthly_usage` P btree (tenant_id, billing_month) | PK(tenant,billing_month)覆盖单月预算/累加CAS；无另一固定读模式证据，保留。V218:53及AI预算Mapper。 |
| platform.batch.console_ai_object_cleanup | 1：`pk_console_ai_object_cleanup` P btree (tenant_id, object_key) | IC03：只PK(tenant,object_key)，消费按tenant ORDER BY created_at,object_key LIMIT，故队列积压要sort；候选(tenant,created_at,object_key)，需要积压规模计划证明。ConsoleAiAttachmentMapper.xml:141–149。 |
| platform.batch.console_ai_turn | 4：`console_ai_turn_pkey` P btree (id)<br>`idx_console_ai_turn_history` btree (tenant_id, conversation_id, turn_no DESC)<br>`uk_console_ai_turn_client_id` U btree (tenant_id, client_turn_id) WHERE (client_turn_id IS NOT NULL)<br>`uk_console_ai_turn_sequence` U btree (tenant_id, conversation_id, turn_no) | IC04反向扫描候选：UNIQUE(tenant,conversation,turn_no ASC)在tenant/conversation等值时反扫可满足turn_no DESC；history降序索引定义非完全相同但当前history/turn查询存在访问重叠。必须保留UNIQUE及client非空partial唯一。ConsoleAiConversationMapper.xml:74–86,150–166。 |
| platform.batch.console_external_identity | 3：`console_external_identity_pkey` P btree (id)<br>`uk_console_external_identity_account` U btree (tenant_id, account_id)<br>`uk_console_external_identity_subject` U btree (tenant_id, issuer, subject) | 账号复合唯一和issuer/subject复合唯一承担不同身份不变量，两者均保留；FK/account点查已有前缀覆盖。V222:8。 |
| platform.batch.console_maintenance_state | 1：`console_maintenance_state_pkey` P btree (id) | 单行平台状态PK，增加enabled/version索引无依据，保留。V215:3。 |
| platform.batch.console_operation_audit | 5：`console_operation_audit_pkey` P btree (id)<br>`idx_console_operation_audit_action_time` btree (action, created_at DESC)<br>`idx_console_operation_audit_operator_time` btree (operator_id, created_at DESC) WHERE (operator_id IS NOT NULL)<br>`idx_console_operation_audit_tenant_time` btree (tenant_id, created_at DESC)<br>`idx_console_operation_audit_trace` btree (trace_id) WHERE (trace_id IS NOT NULL) | IC02：默认按created_at DESC,id DESC，tenant/time索引无id可增量sort；cursor分支按id DESC且tenant过滤，没有tenant/id索引。先确认两种分页契约后测；trace/operator的partial条件由等值非NULL隐含，不能称重复。OperationAuditMapper.xml:51–84。 |
| platform.batch.console_push_approval_notification | 3：`console_push_approval_notification_pkey` P btree (id)<br>`idx_console_push_approval_notification_notified_at` btree (notified_at)<br>`uq_console_push_approval_notification` U btree (tenant_id, approval_no) | tenant/approval唯一覆盖NOT EXISTS去重，notified_at支持保留期；是否存在使用清理入口另核，不以当前0扫描删。ConsolePushApprovalNotificationMapper.xml:34–47。 |
| platform.batch.console_push_job_notification | 2：`console_push_job_notification_pkey` P btree (id)<br>`uq_console_push_job_notification` U btree (tenant_id, job_instance_id) | tenant/job唯一覆盖反查去重；主查询排序在job_instance侧，不能给本表notified_at索引假装解决候选扫描。若新增TTL清理才评notified_at索引。ConsolePushJobNotificationMapper.xml:15–41。 |
| platform.batch.console_push_subscription | 4：`console_push_subscription_pkey` P btree (id)<br>`idx_console_push_sub_endpoint` btree (endpoint)<br>`idx_console_push_sub_tenant_user` btree (tenant_id, username)<br>`uq_console_push_subscription` U btree (tenant_id, username, endpoint) | IC04：tenant/user非唯一是UNIQUE(tenant,user,endpoint)左前缀候选；endpoint全局移除独立有用。IC03：deleteIfStaleSince全局last_seen_at没有对应索引，按积压和清理频率评last_seen_at，不能挪用endpoint索引。ConsolePushSubscriptionMapper.xml:23–26,54–67。 |
| platform.batch.console_usage_daily | 3：`idx_console_usage_daily_metric_date` btree (metric_code, stat_date DESC)<br>`idx_console_usage_daily_tenant_date` btree (tenant_id, stat_date DESC)<br>`pk_console_usage_daily` P btree (stat_date, tenant_id, source, metric_code, page_code, app_version) | 日期分区+tenant/date、metric/date覆盖时间范围聚合；PK含stat_date不能替代tenant-leading任意时间查询。逐分区index附件由主任务核实；小总量不加page维度索引。ConsoleUsageDailyMapper.xml:42–54。 |
| platform.batch.console_user_account | 4：`console_user_account_pkey` P btree (id)<br>`uk_console_user_account_tenant_id_id` U btree (tenant_id, id)<br>`uk_console_user_account_username` U btree (username)<br>`uk_console_user_account_username_ci` U btree (lower((username)::text)) | lower(username) UNIQUE支撑大小写无关登录；原username UNIQUE是不同表达式/约束，不能作为精确重复删除；tenant/id支撑租户列表并被FK引用。ILIKE %keyword%不由普通btree保证，需规模再决定trgm而不是盲装扩展。ConsoleUserAccountMapper.xml:20–22,39–53,82–85。 |
| platform.batch.console_user_batch_operation | 3：`console_user_batch_operation_pkey` P btree (operation_id)<br>`idx_console_user_batch_actor_created` btree (actor_username, created_at DESC)<br>`uk_console_user_batch_actor_request` U btree (actor_username, request_id) | operation_id PK、actor/request唯一防重、actor/created_at历史排序三者语义不同；request_id不为单独全局查询加索引。V220:5。 |
| platform.batch.flyway_schema_history | 2：`flyway_schema_history_pk` P btree (installed_rank)<br>`flyway_schema_history_s_idx` btree (success) | PK/成功状态索引属Flyway框架，只有286行且生命周期特殊，不删成功布尔索引以追求数量。 |
| platform.batch.forensic_export_log | 4：`forensic_export_log_pkey` P btree (id)<br>`forensic_export_log_tenant_id_export_id_key` U btree (tenant_id, export_id)<br>`idx_forensic_export_log_tenant_bizdate` btree (tenant_id, biz_date_from, biz_date_to, requested_at DESC)<br>`idx_forensic_export_log_tenant_status` btree (tenant_id, status, requested_at DESC) | IC02：tenant/export唯一覆盖点查；tenant/status/requested、tenant/bizdate范围用于筛选，但默认tenant ORDER BY requested_at DESC,id DESC无匹配完整前缀。候选tenant/requested_at/id，先测历史规模/访问频率。ForensicExportLogMapper.xml:70–82。 |
| platform.batch.idempotency_record | 2：`idempotency_record_pkey` P btree (id)<br>`uq_idempotency_key` U btree (tenant_id, idempotency_key) | tenant/key UNIQUE覆盖查重、结果读写；当前Mapper没有TTL DELETE，不能先给created_at建索引或用低扫描删幂等唯一。IdempotencyRecordMapper.xml:19–43、ConsoleIdempotencyMapper.xml:5–16。 |
| platform.batch.notification_channel | 3：`idx_notification_channel_active` btree (tenant_id, channel_code) WHERE (is_deleted = false)<br>`notification_channel_pkey` P btree (id)<br>`uk_notification_channel_tenant_code` U btree (tenant_id, channel_code) | active partial和全量UNIQUE有不同谓词；一个小活跃子集访问、一个身份/复活ON CONFLICT，不是精确重复。当前空表不能认定partial有收益，也不能无证据删。NotificationChannelMapper.xml:14–39。 |
| platform.batch.notification_delivery_log | 2：`idx_notification_delivery_log_tenant` btree (tenant_id, created_at DESC)<br>`notification_delivery_log_pkey` P btree (id) | tenant/created_at与最近LIMIT匹配；高并发同时间戳若需要稳定分页要先加id排序，再评索引后缀。rule/channel/alert无高频查例证据，不机械各加索引。NotificationDeliveryLogMapper.xml:5–12。 |
| platform.batch.process_staging | 3：`idx_process_staging_batch_key` btree (batch_key)<br>`idx_process_staging_staged_at` btree (staged_at)<br>`process_staging_pkey` P btree (batch_key, row_seq) | 平台兼容表：batch_key非唯一索引是PK(batch_key,row_seq)左前缀候选；staged_at清理独立。业务实际运行使用另一数据库，不因平台空表就删表/索引；先明确是否退役。 |
| platform.batch.quota_runtime_state | 3：`idx_quota_runtime_state_expire` btree (window_expires_at)<br>`quota_runtime_state_pkey` P btree (id)<br>`uk_quota_runtime_state` U btree (tenant_id, quota_scope, owner_code) | tenant/scope/owner UNIQUE用于状态点查/CAS；window_expires全局期限扫描独立。PK+unique+expiry用途不同，保留。QuotaRuntimeStateMapper.xml:30–80。 |
| platform.batch.resource_queue | 5：`idx_resource_queue_resource_tag` btree (resource_tag)<br>`idx_resource_queue_type_enabled` btree (queue_type, enabled)<br>`idx_resource_queue_worker_group` btree (worker_group)<br>`resource_queue_pkey` P btree (id)<br>`uk_resource_queue_tenant_code` U btree (tenant_id, queue_code) | tenant/code UNIQUE定位配置；worker_group/tag/type-enabled为全局调度候选索引；不能仅以Console tenant/id排序不匹配否定运行索引。小配置表列表sort可接受。ResourceQueueMapper.xml:4–35与Orchestrator调度Mapper。 |
| platform.batch.resource_tag | 4：`idx_resource_tag_tenant_key` btree (tenant_id, tag_key)<br>`idx_resource_tag_tenant_type` btree (tenant_id, resource_type)<br>`resource_tag_pkey` P btree (id)<br>`uk_resource_tag` U btree (tenant_id, resource_type, resource_code, tag_key) | tenant/type索引为UNIQUE(tenant,type,code,tag_key)严格左前缀候选；tenant/tag_key另支持跨资源筛选，不能删。tag_value过滤和resource排序现需过滤/sort，但规模未知。ConsoleResourceTagMapper.xml:20–50。 |
| platform.batch.secret_version | 4：`idx_secret_version_tenant_status` btree (tenant_id, secret_status, created_at DESC)<br>`secret_version_pkey` P btree (id)<br>`uk_secret_version_current` U btree (tenant_id, secret_ref) WHERE (current_version = true)<br>`uk_secret_version_tenant_ref_version` U btree (tenant_id, secret_ref, version_no) | current partial UNIQUE承担每ref仅一current不变量；全版本唯一另有意义；tenant/status/time用状态筛选。默认ref ASC/version DESC混序需sort但配置量小，不自动新增。SecretVersionMapper.xml:27–61。 |
| platform.batch.shedlock | 1：`shedlock_pkey` P btree (name) | 按锁name原子访问，PK足够；lock_until不是每次通过时间扫描调度业务，不泛化加到期索引。 |
| platform.batch.stateful_backend_binding | 1：`stateful_backend_binding_pkey` P btree (feature_key) | 按feature_key点查/锁，PK足够。StatefulBackendGuard.java:151–158。 |
| platform.batch.stateful_backend_cutover_history | 3：`idx_stateful_backend_cutover_history_feature` btree (feature_key, changed_at DESC)<br>`stateful_backend_cutover_history_pkey` P btree (id)<br>`uk_stateful_backend_cutover_token` U btree (feature_key, cutover_id) WHERE (cutover_id IS NOT NULL) | feature/time历史与feature/cutover非空token去重是不同模式，保留二者及PK。StatefulBackendGuard.java:172–178、V193:31–36。 |
| platform.batch.step_registry | 3：`idx_step_registry_module` btree (module)<br>`step_registry_pkey` P btree (id)<br>`uk_step_registry_module_impl` U btree (module, impl_code) | module单列是UNIQUE(module,impl_code)左前缀候选；按模块枚举/配置检查可用unique，但小字典删收益未知。StepRegistryMapper.xml:5–9。 |
| platform.batch.subscription_rule | 2：`subscription_rule_pkey` P btree (id)<br>`uk_subscription_rule_tenant_name` U btree (tenant_id, rule_name) | UNIQUE tenant/name可缩小tenant范围，event_types LIKE %event%不因该索引变成索引查词；小规则表可接受。未来channel复合FK须评tenant/channel支持索引，不能误称无tenant索引。SubscriptionRuleMapper.xml:14–22。 |
| platform.batch.system_parameter | 2：`system_parameter_pkey` P btree (id)<br>`uk_system_parameter_tenant_key` U btree (tenant_id, param_key) | tenant/param_key UNIQUE覆盖get/upsert，PK用于管理；不为动态param_value任意字符串建索引。 |
| platform.batch.tenant | 2：`tenant_pkey` P btree (id)<br>`uk_tenant_tenant_id` U btree (tenant_id) | tenant_id UNIQUE覆盖准入查找，PK管理分页；名称ILIKE属于小根字典检索，先保留。TenantMapper.xml:5–18,35–49。 |
| platform.batch.tenant_configuration_revision | 1：`tenant_configuration_revision_pkey` P btree (tenant_id) | 按tenant_id单行CAS/锁，PK足够；触发器更新revision不需要额外索引。 |
| platform.batch.tenant_quota_policy | 4：`idx_tenant_quota_enabled` btree (tenant_id, enabled)<br>`idx_tenant_quota_tenant_id_desc` btree (tenant_id, id DESC)<br>`tenant_quota_policy_pkey` P btree (id)<br>`uk_tenant_quota_policy` U btree (tenant_id, policy_code) | tenant/policy UNIQUE、tenant/enabled候选、tenant/id DESC分页各有用途；保留。TenantQuotaPolicyMapper.xml:18–26及运行Mapper。 |
| platform.batch.tenant_scheduler_snapshot | 2：`idx_tenant_scheduler_snapshot_tenant_time` btree (tenant_id, snapshot_at DESC)<br>`tenant_scheduler_snapshot_pkey` P btree (id) | tenant/snapshot_at与租户历史时间范围匹配；若有全局TTL清理需单列时间索引与分批策略，但先确认入口，不仅凭字段新增。TenantSchedulerSnapshotMapper.xml。 |
| platform.batch.webhook_delivery_log | 4：`idx_webhook_delivery_log_retry_eligible` btree (delivery_status, next_retry_at) WHERE (((delivery_status)::text = 'EXHAUSTED'::text) AND (next_retry_at IS NOT NULL))<br>`idx_webhook_delivery_log_sub` btree (subscription_id)<br>`idx_webhook_delivery_log_tenant` btree (tenant_id, created_at DESC)<br>`webhook_delivery_log_pkey` P btree (id) | IC01：partial仅EXHAUSTED，实际relay IN(PENDING,EXHAUSTED)不蕴含该谓词；必须评覆盖两状态的poll索引与prepared/generic计划。tenant/time覆盖租户最近；subscription_id帮助子引用查与按订阅查，仍需tenant过滤+sort，不是完全无索引。ConsoleWebhookDeliveryLogMapper.java:35–39、XML:32–37,65–76。 |
| platform.batch.webhook_subscription | 3：`idx_webhook_subscription_active` btree (tenant_id, name) WHERE (is_deleted = false)<br>`uk_webhook_subscription_tenant_name` U btree (tenant_id, name)<br>`webhook_subscription_pkey` P btree (id) | 全量tenant/name UNIQUE和active partial不同谓词；软删复活依赖全量唯一。active索引收益需真实活跃比例，不作为重复删除。 |
| platform.quartz.qrtz_blob_triggers | 1：`qrtz_blob_triggers_pkey` P btree (sched_name, trigger_name, trigger_group) | PK即三列子FK支持，框架数据Blob不建推测JSON索引。 |
| platform.quartz.qrtz_calendars | 1：`qrtz_calendars_pkey` P btree (sched_name, calendar_name) | PK完整日历键点查，保留。 |
| platform.quartz.qrtz_cron_triggers | 1：`qrtz_cron_triggers_pkey` P btree (sched_name, trigger_name, trigger_group) | PK完整trigger键子FK支持，保留。 |
| platform.quartz.qrtz_fired_triggers | 7：`idx_qrtz_ft_inst_job_req_rcvry` btree (sched_name, instance_name, requests_recovery)<br>`idx_qrtz_ft_j_g` btree (sched_name, job_name, job_group)<br>`idx_qrtz_ft_jg` btree (sched_name, job_group)<br>`idx_qrtz_ft_t_g` btree (sched_name, trigger_name, trigger_group)<br>`idx_qrtz_ft_tg` btree (sched_name, trigger_group)<br>`idx_qrtz_ft_trig_inst_name` btree (sched_name, instance_name)<br>`qrtz_fired_triggers_pkey` P btree (sched_name, entry_id) | 实例名短索引是( sched_name,instance_name,requests_recovery )前缀候选；框架原型暂保留。job/trigger与group独立恢复路径不能互删。 |
| platform.quartz.qrtz_job_details | 3：`idx_qrtz_j_grp` btree (sched_name, job_group)<br>`idx_qrtz_j_req_recovery` btree (sched_name, requests_recovery)<br>`qrtz_job_details_pkey` P btree (sched_name, job_name, job_group) | PK、job_group、requests_recovery分别对应具体任务、组操作和故障恢复，保留。 |
| platform.quartz.qrtz_locks | 1：`qrtz_locks_pkey` P btree (sched_name, lock_name) | 调度器+锁名PK，保留。 |
| platform.quartz.qrtz_paused_trigger_grps | 1：`qrtz_paused_trigger_grps_pkey` P btree (sched_name, trigger_group) | 调度器+组PK，保留。 |
| platform.quartz.qrtz_scheduler_state | 1：`qrtz_scheduler_state_pkey` P btree (sched_name, instance_name) | 调度器+实例PK，保留。 |
| platform.quartz.qrtz_simple_triggers | 1：`qrtz_simple_triggers_pkey` P btree (sched_name, trigger_name, trigger_group) | PK完整trigger键子FK支持，保留。 |
| platform.quartz.qrtz_simprop_triggers | 1：`qrtz_simprop_triggers_pkey` P btree (sched_name, trigger_name, trigger_group) | PK完整trigger键子FK支持，保留。 |
| platform.quartz.qrtz_triggers | 13：`idx_qrtz_t_c` btree (sched_name, calendar_name)<br>`idx_qrtz_t_g` btree (sched_name, trigger_group)<br>`idx_qrtz_t_j` btree (sched_name, job_name, job_group)<br>`idx_qrtz_t_jg` btree (sched_name, job_group)<br>`idx_qrtz_t_n_g_state` btree (sched_name, trigger_group, trigger_state)<br>`idx_qrtz_t_n_state` btree (sched_name, trigger_name, trigger_group, trigger_state)<br>`idx_qrtz_t_next_fire_time` btree (sched_name, next_fire_time)<br>`idx_qrtz_t_nft_misfire` btree (sched_name, misfire_instr, next_fire_time)<br>`idx_qrtz_t_nft_st` btree (sched_name, trigger_state, next_fire_time)<br>`idx_qrtz_t_nft_st_misfire` btree (sched_name, misfire_instr, next_fire_time, trigger_state)<br>`idx_qrtz_t_nft_st_misfire_grp` btree (sched_name, misfire_instr, next_fire_time, trigger_group, trigger_state)<br>`idx_qrtz_t_state` btree (sched_name, trigger_state)<br>`qrtz_triggers_pkey` P btree (sched_name, trigger_name, trigger_group) | 3个短索引/4条前缀配对（group、misfire、state）见下表；misfire+next_fire_time后的state/group位置不同，不等价。PK对完整trigger身份唯一，含state的非唯一长索引不能替代约束。遵循Quartz框架先保留，真实misfire/集群故障计划再决策。 |
| business.batch.process_staging | 5：`idx_process_staging_batch_key` btree (batch_key)<br>`idx_process_staging_staged_at` btree (staged_at)<br>`idx_process_staging_target_batch` btree (tenant_id, target_schema, target_table, batch_key)<br>`idx_process_staging_tenant_batch` btree (tenant_id, batch_key)<br>`process_staging_pkey` P btree (batch_key, row_seq, staged_at) | PK(batch_key,row_seq,staged_at)与batch_key单列存在前缀重叠；tenant/batch和tenant/target_schema/target_table/batch并非互相前缀，分别覆盖只知批次与已知目标的WAP路径。staged_at按时间清理另需保留。SqlTransformComputeSqlBuilder、ProcessStagingMapper.xml:11–20。 |
| business.biz.customer_account | 4：`customer_account_pkey` P btree (tenant_id, id)<br>`idx_customer_account_name` btree (tenant_id, customer_name)<br>`idx_customer_account_status` btree (tenant_id, status)<br>`uk_customer_account_tenant_no` U btree (tenant_id, customer_no) | tenant/customer_no唯一承载导入ON CONFLICT；tenant/id为PK；tenant/status、tenant/customer_name各自筛选。普通btree不保证%name%检索；业务配置查询实际形态再评，不凭示例数据删。create_biz_tables.sql:102–136。 |
| business.biz.process_account_summary | 1：`process_account_summary_pkey` P btree (tenant_id, account_id, biz_date) | E2E transform示例聚合输出，PK(tenant,account,date)承载幂等；无生产热点证据，不增加biz_date单列。create_biz_tables.sql:316–323。 |
| business.biz.process_event_copy | 1：`process_event_copy_pkey` P btree (tenant_id, event_id) | E2E复制输出，PK(tenant,event_id)承载upsert；保留，account_id不是明确查询入口就不加。create_biz_tables.sql:328–337。 |
| business.biz.process_order_event | 0：无 | 0索引，DDL明确测试transform源输入；不是应立即为生产事件模型补PK/索引的证据。若用于真实生产，另定义去重/tenant-date事件范围查询再设计。create_biz_tables.sql:306–315。 |
| business.biz.risk_alert | 4：`idx_risk_alert_entity` btree (tenant_id, entity_id, alert_date)<br>`idx_risk_alert_tenant_date` btree (tenant_id, alert_date)<br>`risk_alert_pkey` P btree (tenant_id, alert_date, id)<br>`uk_risk_alert_tenant_alert` U btree (tenant_id, alert_id, alert_date) | tenant/date单列组合是PK(tenant,date,id)左前缀候选；tenant/entity/date用于实体历史，不由PK覆盖；tenant/alert/date唯一保业务身份。日期分区叶子全覆盖由主任务核对。create_biz_tables.sql:282–303。 |
| business.biz.risk_score | 4：`idx_risk_score_band` btree (tenant_id, score_band, score_date)<br>`idx_risk_score_tenant_date` btree (tenant_id, score_date)<br>`risk_score_pkey` P btree (tenant_id, score_date, id)<br>`uk_risk_score_tenant_entity_date` U btree (tenant_id, entity_id, score_date) | tenant/date索引是PK(tenant,date,id)左前缀候选；tenant/entity/date唯一、tenant/band/date不同用法，不删除。create_biz_tables.sql:253–279。 |
| business.biz.settlement_batch | 4：`idx_settlement_batch_biz_date` btree (tenant_id, biz_date, batch_status)<br>`idx_settlement_batch_period` btree (tenant_id, accounting_period)<br>`settlement_batch_pkey` P btree (tenant_id, id)<br>`uk_settlement_batch_tenant_batch_no` U btree (tenant_id, batch_no) | tenant/batch_no唯一支撑业务身份，tenant/date/status和tenant/period是不同冻结/导出选择；没有精确重复。create_biz_tables.sql:139–171。 |
| business.biz.settlement_detail | 5：`idx_settlement_detail_batch` btree (batch_id)<br>`idx_settlement_detail_biz_date` btree (tenant_id, biz_date, settlement_status)<br>`idx_settlement_detail_customer` btree (tenant_id, customer_no, accounting_period)<br>`settlement_detail_pkey` P btree (tenant_id, id)<br>`uk_settlement_detail_tenant_no` U btree (tenant_id, settlement_no) | batch_id单列虽然不以tenant开头，父FK删除检查的tenant+batch_id双等值仍可用batch_id再过滤tenant；不是“FK完全无索引”。是否改tenant/batch_id需真实批次分布和查询计划。tenant/date/status和tenant/customer/period用于不同导出范围，保留。create_biz_tables.sql:174–216。 |
| business.biz.transaction | 4：`idx_transaction_account` btree (tenant_id, account_no)<br>`idx_transaction_tenant_date` btree (tenant_id, txn_date)<br>`transaction_pkey` P btree (tenant_id, txn_date, id)<br>`uk_transaction_tenant_txn` U btree (tenant_id, txn_no, txn_date) | tenant/txn_date是PK(tenant,txn_date,id)左前缀候选；tenant/account_no与tenant/txn_no/date唯一承担独立账户查询/去重。日期分区剪枝不替代账户索引。create_biz_tables.sql:224–246。 |

### 未验证与关闭条件

没有索引删除/添加获准结论；没有通过测试、热查询计划、p95/p99或写入成本结论。后续按同PG版本和代表租户基数/热冷比例/队列积压/分区跨度，收集custom与generic执行计划、BUFFERS与稳定分页结果；比较新增与删减候选的查询延迟、扫描行数、索引体积、更新写放大及清理锁等待。业务process三表是测试示例，不能直接套生产模型；Quartz改动另需misfire、故障恢复与集群验收。现有PK、UNIQUE、partial唯一与FK引用依赖必须先保留。

## 证据文件摘要

| 文件 | SHA256 |
|---|---|
| `database-index-catalog-2026-10-18.csv` | `b1a5193cef09b1bdd74464f70e1663d94298f719997e07474c3015fa1e4aa806` |
| `database-partition-catalog-2026-10-18.csv` | `8a72355e98642a80fcdc1335b10d3cfdf3d20555fb890e5d02da3b479ec7ff9f` |
| `database-job-date-pruning-explain-2026-10-18.json` | `c1c889fe6c2bea1c59547c7543cdfa5c60089ed46d6ecd94eb2c97b7d57fa6da` |

## 本轮文档检查

- `python3 scripts/ci/check-docs-structure.py`：通过（退出码 0）。
- `python3 scripts/ci/check-doc-timestamp-policy.py`：通过（退出码 0）。
- 本轮文档范围的 `git diff --check`：通过（退出码 0）。
- 176表/589索引覆盖、117分区关系/1,045子索引挂接及目录有效状态已静态核对；没有运行实现测试或性能验收。
