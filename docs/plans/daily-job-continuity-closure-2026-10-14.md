# 日切与作业持续触发闭环补全方案

> 当前执行范围已由用户于 2026-10-14 修订为全新库与单一新模型；以下原状态、计划和证据按来源时点阅读。

> 状态：实施中。目标是在保留 Orchestrator 状态主机、DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT 主链和现有单上游兼容行为的前提下，补齐日历日、依赖恢复、作业依赖与文件/Workflow 触发间的缺口。

## 当前共同约束（2026-10-14 修订）

用户已确认**不兼容旧系统、全新数据库部署**。当前契约为 `2026-10-14.2`，以 [ADR-048](../architecture/adr/ADR-048-workflow-fresh-database-cutover.md)、[整合计划](./workflow-daily-integration-plan-2026-10-14.md)及[验证矩阵](./workflow-daily-integration-validation-2026-10-14.md)为准。

下文原方案、旧迁移编号和已有测试属于来源分支历史记录；旧 API/Job 根/wrapper/旧消息兼容、旧库升级和历史数据转换均退出当前实施范围。新模型自己的运行快照、版本恢复、归档与审计继续保留。当前实施清单见后文修订表，其余原切片不得直接照搬为新任务。

## 目标与范围

系统每天通过 Quartz 定时 fire、文件到达、事件和 API/人工操作接纳运行请求，依赖就绪释放已有等待。整合目标是这些顶层请求统一进入 Workflow Run，再按节点创建 Job Instance；本分支现有 Job 启动路径属于待迁移实现。`batch_day_instance` 表示业务日生命周期，不代表当天所有 Job 已经创建。此方案让每个触发源都能明确业务日和幂等身份，并让延迟、跨日、重启和重复事件可恢复。

不引入第二套调度事实源，不让 Worker 直接推进 Job/Workflow 状态，不预生成所有 Job 实例，不替换 Quartz、Outbox 或 Kafka，不承诺外部副作用 exactly-once。

## 借鉴成熟调度系统的做法

本方案参考 Apache Airflow 的 data interval / logical date / catch-up / backfill 语义、sensor 的等待/超时语义，以及 AWS EventBridge Scheduler 的失败重试和 DLQ 做法；只采纳适合当前系统的原则，不复制其产品模型或增加新中间件。

| 成熟实践 | 本系统采纳方式 |
|---|---|
| 运行时间不等于处理数据的逻辑日期；每个批次运行有稳定的日期/区间 | 每次触发保留计划 fire 时间、事件发生时间和 `bizDate` 的区别；依赖重试、catch-up/replay 固定原 `bizDate`，不得跨午夜漂移 |
| catch-up/backfill 是显式的历史实例创建策略，重复处理规则和并行度可控 | 迟到/停机期间的缺失日期进入可审计待处理记录；补跑须显式选择 missing/failed/reprocess 范围，先 dry-run，再受并发/日期上限约束 |
| Sensor 只等待条件；长等待不能占用执行 Worker，并且必须有 timeout/soft-fail 语义 | Workflow WAIT 保持控制面轻量轮询或事件唤醒；等待状态持久化，超时明确映射为失败/跳过/人工处理，不能伪装成功 |
| 事件驱动传递至少一次或可能丢失；重试耗尽后要留可检查/可重放记录 | 文件通知做低延迟加速，DB 到达记录是事实来源，周期扫描做 reconciler；事件、扫描和 workflow run 共用稳定去重身份 |
| 多实例调度必须允许重复唤醒，并由业务键/事务状态约束最终实例数 | 顶层 occurrence 使用 ALIGN-3 的 Workflow/事件/文件来源身份，BatchDay 保持 `(tenant, calendar, bizDate)`，节点尝试保持自身关联唯一性；DB 唯一约束/CAS 收敛各层重复，不把这些键混成一个 Job/date 去重键 |
| 依赖应描述数据分区和上游完成条件，不只描述作业名称 | 依赖配置携带对齐规则、期望结果状态/版本及期限；多依赖需全部满足或按显式 trigger rule 决策 |

**参考资料（官方文档）**： [Airflow Timetables](https://airflow.apache.org/docs/apache-airflow/stable/authoring-and-scheduling/timetable.html)、[Airflow Backfill](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/backfill.html)、[Airflow Sensors](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/sensors.html)、[AWS EventBridge Scheduler retries and DLQ](https://docs.aws.amazon.com/scheduler/latest/UserGuide/managing-schedule.html)。这些是模式参考，不代表本系统具有同等功能或可靠性。

## 分阶段实施

## 现状核对（2026-10-14）

- `job_definition.depends_on_job_code` 和 `/internal/readiness` 已实现单上游、同业务日、EFFECTIVE 结果版本闸门；缺口是作业定义只表达一个前置作业，Console/Excel/Trigger/监控都消费该字段，扩成多依赖必须作为前后端契约迁移，不能只加后端表。
- Workflow WAIT 已支持持久化 sensor probe 进度及 FILE_ARRIVAL/HTTP/DB/Kafka sensor；FILE_ARRIVAL 可选按显式/Workflow `bizDate` 和 `fileGroupCode` 过滤，旧配置默认保持时间窗口语义。
- 文件治理已实现按 `fileGroupCode + requiredFileSet` 聚合、校验态门槛、超时处置、重试及 `BundleArrivalLauncher` 启动；后续应补同名跨业务日边界和 sensor 与到达组共用身份，不应另建独立文件触发链。
- Workflow 节点已有独立跨日历依赖 resolver/reconciler 与超时失败告警；它和作业定义单上游 readiness 是不同契约。后续扩展需复用各自权威路径，明确触发类型哪些执行依赖闸门。
- 日历等待已能超时告警并在上游完成/配置修复后自动恢复；Console 已提供等待列表、最终状态和人工动作记录，以及人工 RELEASE/SKIP 请求；灾害日 override 治理界面和部署后结果观测仍未完成，因此阶段 2 仍标进行中；恢复不要求运维直接改运行态表。

### 阶段 1：日历日与空批量日语义

**整合边界**：本阶段下列 V236 起编号、Job 字段和 Job fire key 记录的是本分支过渡实现。最终定时预期来源和 occurrence 身份遵循 ALIGN-2～ALIGN-4，不能继续按 Job 日程新增第二套计划；文件/事件规则中的 Job 目标也须显式解析受管 Workflow 或组合 Workflow 目标，并保留业务结果义务的 Job/分区关联。迁移须与另一分支核对后实施，不用更名代替数据对账。

- 让批量日开启与定时 Trigger 使用一致的节假日、工作日覆盖、`SKIP` / `NEXT_WORKDAY` / `PREV_WORKDAY` 口径，并尊重日历组规则和租户时区。
- `SKIP` 日期应形成可审计终态，不留下没有计划作业的 `OPEN/CUTOFF` 空日；节假日滚动后利用现有 `(tenant, calendar, bizDate)` 唯一性避免重复打开。
- 正常工作日只在“没有预期作业”时自动结算：仅 `CUTOFF` 且零实例的日期参与判定；已覆盖的计划 occurrence、未派发 Trigger、未终结的显式事件/文件预期项、跨日历/作业依赖等待及未覆盖的 CRON/FIXED_RATE 定义都会阻止自动结算。EVENT/MIXED 必须配置逐日事件预期规则；未配置预期来源时绑定日历继续失败关闭，保持 `CUTOFF`。自动结算 CAS 与审计在同一短事务；显式 `SETTLE_EMPTY` 仍保留给管理员并要求原因。结算之后的新到达仍按现有 late-arrival/catch-up 规则路由。
- 显式 `SETTLE_EMPTY` 对仍为 EXPECTED/ARRIVED 的文件与事件义务执行事务内逐条 CAS 豁免，并将原状态、来源身份、时间、请求关联、原因和操作者写入同一审计；义务状态、批日终态或审计任一写入失败时整体回滚。预期项相关事务先执行独立 `set_config('app.tenant_id', ..., true)` Mapper，再访问强制 RLS 表。真实 PostgreSQL 场景覆盖 EXPECTED 与 ARRIVED 两种状态被取消及审计可追溯；`NOSUPERUSER NOBYPASSRLS` 角色下的隔离用例验证 Mapper 租户上下文生效和跨租更新被屏蔽。
- 文件到达组不能直接作为“未到文件”的预期来源：`file_record` 只在至少一条文件到达后存在，原数据没有逐日预期定义。V248 建立 RLS 保护的 `expected_work_occurrence` 台账、稳定来源身份幂等键与状态 CAS，settler 读取 EXPECTED/ARRIVED 阻塞项；到达前登记入口校验作业日历，bundle launcher 复用同一来源身份，在 Trigger 接受/依赖等待后结清，失败则保留 ARRIVED 以供重试。V249 新增 RLS 保护的可重复文件组预期规则；批量日首次成功开启时，在同一事务按日历工作日和租户时区生成当日 EXPECTED 项，重复开启依赖唯一身份幂等。规则以 `(tenant, calendar, fileGroupCode)` 唯一，配置指定 Workflow、每日预期本地时间和同日截止时间；文件组元数据中的 fileGroupCode 与 bundleWorkflowCode 必须匹配。V250 新增 RLS 保护的外部事件预期规则，首次开日时按本地预期/截止时间生成 EXTERNAL_EVENT；消费方仅在关联 EVENT trigger_request 已 LAUNCHED 后可调用满足接口 CAS 到 SATISFIED。复核发现单靠 job/date/type 关联会允许不同 eventCode 误满足同一作业的另一事件；V251 为 trigger_request 增加可空 external_event_code，Trigger API 请求贯通并持久化该值，满足接口强制逐事件一致。无预期规则仍对 EVENT/MIXED 失败关闭。截止时间不自动放弃事件预期，缺件继续阻塞结算。整合后预期定义按 `workflow.enabled` 过滤未来物化：禁用 Workflow 不创建新业务日预期，但已生成的历史 occurrence 继续阻塞至履行或受支持处置；仅暂停 `schedule_enabled` 不停掉文件/事件输入义务。
- 预期作业清单不能从当天 `job_instance` 或 `trigger_request` 反推：前者只证明已创建实例，后者只在 Quartz 已执行/登记某次 fire 后存在。当前 Quartz 请求的自然身份已由 `buildScheduledDedupKey` 使用 `tenant + jobCode + fireTime epoch millis` 表达；`LaunchRequest.dataIntervalStart` 和参数 `scheduledAt` 保留原 fire time，misfire 审批单另有 `trigger_misfire_pending.scheduled_fire_time`。不得重新向 `trigger_request` 添加 `scheduled_fire_time`/`trigger_runtime_state_id`：V70 明确撤销了 V68 的双插入方案，运行请求只能由 LaunchService 的单条写入链建立。未来若要自动空日判定，应采用独立的预期 occurrence 记录，与原 fire time、业务日解析和请求 dedup key 对齐；由 Trigger/Quartz 生成 occurrence、实际派发时更新同一 occurrence，Orchestrator 仅读取计划/派发/跳过/待审批状态参与结算。不得在 BatchDayOpenScheduler 中另写 cron、时区、节假日和 misfire 计算器；范围需明确“一个 job 每日一次”与“一日多次 occurrence”的区别。
- occurrence 台账过渡身份为 `(tenant_id, job_code, scheduled_fire_time)`；最终身份采用 ALIGN-3 的 `(tenant_id, workflow_definition_id, original_scheduled_fire_time)`，时间精度保持 epoch millis。旧 key、请求和已发生/待审批记录必须可追溯映射，同一 fire 被重复扫描/重试只能关联原记录，不因切换目标或调度版本重复运行。Quartz `Trigger` 是计划 fire time 的唯一计算器，台账只物化受控前瞻窗口内的 fire time；通过 Quartz `getFireTimeAfter` 枚举，分别验证 CRON 的时区/DST 与 FIXED_RATE 间隔，不解析 cron 字符串生成第二套计划。每条记录保存解析后的 `biz_date`、日历依据及调度定义版本/计划快照，schedule replace 仅对尚未发生的旧版计划标记 superseded；已发生/待审批 occurrence 不得因重注册删除。状态区分计划中、已登记请求、日历跳过、待人工审批、按 misfire 策略放弃和被新计划替代；经既有 Trigger 受理/Launch 链更新并由 reconciler 对账，不另建请求写入链。skip/superseded 只结束相应触发机会；已有业务结果义务仍须履行或显式豁免。PLANNED、待审批及未终结请求/运行/依赖等待继续阻止自动空日结算。迁移须定义租户 RLS/服务账号访问策略、保留期和历史回填边界。
- 验收：节假日、工作日覆盖、三种 roll rule、跨 cutoff、重复扫描以及空工作日均有定向测试；状态迁移和审计符合既有枚举/约束。
- 空日自动结算已落地首版；V248 台账支持显式预期项，V249/V250 分别按启用规则在批量日首次开启时生成文件组/外部事件预期。事件项只有在关联 EVENT 请求到达 LAUNCHED 后才可 CAS 为 SATISFIED；无预期来源的 EVENT/MIXED 日历仍失败关闭。截止时间只记录期限并持续阻塞结算，不自动忽略迟到/缺失工作。真实 PostgreSQL 覆盖文件和事件规则按时区生成与幂等，以及事件请求状态校验和 SATISFIED CAS。

### 阶段 2：跨日历依赖的持久化等待与补偿

- 将“未满足依赖”从仅日志返回改为可查询、可重试的持久化状态，固定原 `bizDate`、依赖规则、首次等待/截止时间和原因。
- 上游状态变化可唤醒下游；周期 reconciler 作为事件丢失和进程重启后的兜底。所有并发唤醒使用唯一键/CAS 幂等。
- 明确超窗后的告警、人工释放/跳过和审计；不因扫描日期跨过 cutoff 而静默丢掉旧业务日。
- Console 已提供管理员等待列表及 RELEASE/SKIP 请求入口；操作者取认证上下文，原因必填，决策持久化后由 Orchestrator 周期 reconciler 执行并记入批量日审计。列表可筛选 WAITING/OPENED/SKIPPED，展示最终状态与人工动作记录；仅 WAITING 可操作。请求不直接写运行态，也不会把已存在批量日改写。
- 验收：上游晚于下游 cutoff 完成、重复事件、多实例竞争、重启、租户隔离、超时以及人工 RELEASE/SKIP 均有真实数据库 IT；Console 控制器验证操作者不可由客户端伪造。

### 阶段 3：作业依赖模型与 readiness 一致性

**整合边界**：本分支按下游 Job 日历解析前一业务日的实现和旧等待快照保留其原始语义。新 Workflow 运行默认继承流程业务日；确需日期转换的节点/依赖边须显式指定映射和日历依据，并在首次解析时冻结。不得在等待恢复时用新 Workflow 日历改写旧快照，也不得把 DAG 执行依赖和 EFFECTIVE_RESULT 数据依赖合并成同一语义。最终放行及实际消费版本遵循 ALIGN-5。

- 已开始兼容迁移：V241 新增 `job_dependency` 关系、迁入现有单依赖，并由数据库触发器将旧标量写入同步为单依赖；Console create/update/detail/list 与作业定义表单已接入最多 32 个上游，验证同租户引用、禁止自依赖/成环；Trigger 用原 readiness API 对每个上游执行同业务日 AND gate，仍为 EFFECTIVE_RESULT。旧字段继续作为第一上游投影。
- Excel/租户配置包支持逗号分隔多依赖字段、跨行前向引用、依赖环校验，并新增按位置对应的 `depends_on_biz_date_alignments`；旧模板未含此列时仍按同日导入。V245 为每条依赖增加 `SAME_DAY` / `PREVIOUS_BIZ_DAY`，存量关系默认同日；依赖恢复现按下游作业业务日历解释节假日和工作日覆盖，并在首次等待快照中固化解析后的上游日期；旧等待快照继续按周一至周五兼容。Console API/响应和作业表单已支持此规则，快捷表单对当前全部上游应用同一选择，混合规则可保留；Excel/API 可逐边配置。结果版本策略已明确为依赖等待只认对齐业务日最新 EFFECTIVE 版本；更高 PENDING 版本阻断等待中的下游，已启动下游不会自动级联，修订需显式 replay。普通 job_instance 尚未持久化实际消费的上游版本快照；人工依赖 override、文件组依赖规则、独立依赖期限，以及跨日历 Workflow 依赖与此模型的契约边界仍待实现。
- 触发类型现状核对：`DefaultTriggerService.launchScheduled` 执行 readiness gate，普通 misfire catch-up 也经过该方法；`createPendingCatchUp`/审批恢复、API 手工 `launch` 没有该 Trigger gate。文件组 `BundleArrivalLauncher` 和 Workflow 子作业虽不经过 Trigger readiness gate，但都进入 Orchestrator `LaunchService`。现已在统一启动入口增加第二道 EFFECTIVE_RESULT AND 检查：首次阻塞保存原业务日、依赖快照和完整 LaunchRequest，RLS reconciler 按租户恢复；Quartz 仍保留 scheduled 的快速重检。手工 override 仍未定义，不把不同触发类型都套 Quartz 重试。
- Trigger 的 scheduled 快速 readiness gate 现在只查询 `SAME_DAY` 边；规范化依赖集合为空时不再回退到旧标量字段，只有没有规范化规则投影的旧定义才使用标量兼容路径。跨日边由 Orchestrator 统一启动入口按其目标上游业务日执行持久化等待，避免在 Trigger 层按下游同日错误阻塞。Console API、表单、响应投影和租户 Excel 的逐边规则已经接通；前端快捷表单提供对全部当前上游统一设置的选择，并能在混合规则时保留现值。
- 后续需实现节假日日历感知的前一业务日、已启动下游实际消费上游版本的 lineage 快照及独立依赖期限；所有入口继续由 Orchestrator 最终裁决依赖。
- **版本 lineage 的实现边界**：当前 `ReadinessResult` 返回 EFFECTIVE 版本明细，但这只证明 gate 检查时命中的版本；ADR-017 的消费者契约按业务键读取当前 EFFECTIVE 版本，启动与执行之间可能发生 promote。不能只把 gate 结果复制进 `job_instance` 就声称记录了“实际消费版本”。完整实现须先定义版本 pinning 在 worker 派发/读取端的契约，再让 Orchestrator 将同一组 `result_version.id` 固化到实例快照，并验证下游读取确实使用这些 ID；无法 pin 的外部业务表消费者需明确标记为未追踪，不能伪报 lineage。历史实例保持 NULL/未知，不按当前 EFFECTIVE 版本回填。
- 按触发类型实现一致且可恢复的 gate：Scheduled 保留 Quartz 延迟重检；Event/Workflow/Catch-up/Replay/Manual/API 由 Orchestrator 持久化等待和 reconciler 兜底，并固定原 bizDate。Manual 是否允许带审计原因的 override 仍待定义；不得复用 Quartz 重试语义处理全部触发类型。
- 没有业务明确语义的依赖类型不实现；事件/依赖状态仍由 Orchestrator 持久化，Trigger 通过既有接口判定。
- 验收：兼容迁移、环检测、多依赖全满足/部分满足、业务日对齐、上游重跑后结果版本变化、各触发类型以及租户隔离测试。

### 阶段 4：文件到达与 Workflow 触发闭环

- 明确“文件发现”“文件组完整”“触发对应 Job”“Workflow WAIT sensor 命中”四种状态，不将单文件存在误认为文件组完整。
- 文件到达组以 `file_record` 持久记录为事实来源，30 秒定时数据库扫描是当前可靠恢复路径；不依赖易丢的进程内通知。扫描必须在重启后继续，并验证迟到/乱序成员最终触发完整组；若未来增加事件通知，它只作加速层，数据库扫描仍作补偿。
- Workflow sensor 使用稳定到达记录/文件组身份，避免只按文件名和最大年龄误命中新旧批次；要求完整组时必须绑定明确的业务日和 fileGroupCode，周期复用组编码不能跨日命中；超时、恢复和重复命中都可审计。
- 通过现有 Trigger/Orchestrator/Outbox 主链启动作业；不由 Worker 或 webhook 直接创建核心运行态。
- WAIT sensor 命中与下游节点派发必须处于同一 `probeOne` 事务；派发异常不能被记录后吞掉，否则 WAIT 会提交成功而下游没有持久激活记录，Workflow 将失去重扫入口。
- 验收：事件丢失/重复/乱序、同名跨业务日文件、部分文件组、manifest/checksum 不完整、扫描并发、workflow 重启与 sensor 超时覆盖。

### 阶段 5：持续运行可观测性与运行手册

- 展示并告警：应有但未打开的业务日、依赖等待年龄/超时、文件组缺件、触发请求到实例延迟、待重放和终态收敛。作业依赖等待现有 Console 租户列表和单请求超时告警之外，本轮新增无租户标签的全局等待数/最老等待秒数指标及 24 小时告警，作为积压趋势信号；不暴露跨租户归属。文件到达组已有候选页等待数不代表全量积压的问题，本轮改由数据库按组身份聚合所有 WAITING 组并测最老等待时间；仍不向指标添加租户/组标签，定位依赖租户作用域 Console 列表和既有 SLA 告警。
- 日切停机 catch-up 已有第一版：`batch_day_open_cursor` 保存原始日期进度，与每个日期的批量日/等待结果同事务推进；默认每轮最多 14 日，重复扫描继续推进。新增低基数积压指标和 Docker/Helm 告警；Console 可按租户/日历只读预览下轮原始日期扫描范围，并可填写原因暂停/恢复单日历的自动补齐。暂停不影响当前业务日开启或依赖等待恢复，且不推进游标；操作有租户 RLS 审计事件。新增 Console 只读历史缺口候选查询，要求显式日期范围且最多 90 日，按租户日历时区/cutoff 拒绝查询未来业务日，只比较批量日记录是否存在；不判断历史节假日/滚动规则、不推断是否应补建、不创建数据或推进游标。运行手册现将该查询纳入部署前人工盘点步骤；它不是自动部署门禁。仍缺审批式历史回填和部署流水线集成。
- **历史回填边界（实施前置）**：现有 `BatchDayReplay` 的 `EXISTING_INSTANCES` 面向已存在实例的重跑；`SCHEDULE_PLAN` 只允许 DRY_RUN，且按当前作业定义生成预览。两者都不会为缺失日期建立 `batch_day_instance`，不能称为“历史日回填”。未来回填应从只读候选开始，审批请求必须固定租户、日历、明确日期集合、原因和预览 hash；请求人与批准人分离，消费幂等且有 RLS 审计。预览至少列出原始日到有效日映射、假日/灾害日处置、依赖阻塞、历史 occurrence coverage 和显式文件/事件预期。执行复用日历日状态机及短事务，按上限分批，不直接插表或改游标；只建日记录不会隐式重放作业。仅当该日存在保留的历史 scheduled occurrence/明确预期身份时，才允许关联作业补跑；没有历史调度快照时，当前 schedule plan 只能用于 dry-run，不能据此推演生产历史运行。无法证明旧日没有预期作业时，保持未结算，禁止用“没有当前实例”推断空日。该流程应继续复用现有 BatchDayReplay 批准链处理具体作业重跑，而不是再造第二套 job replay 状态机。
- **历史回填状态机/恢复矩阵**：`PREVIEWED → PENDING_APPROVAL → APPROVED → RUNNING → SUCCEEDED | PARTIAL_FAILED`，另有审批前 CANCELLED 和审批 REJECTED。预览凭证短时、一次性并绑定 request hash；审批人必须不同于申请人。批准只排队，不在审批 HTTP 事务里批量建日。调度器用租户 RLS、行锁/SKIP LOCKED 和每日期 CAS 逐项执行；请求级并行度与日期上限受配置控制。重复提交/审批返回原聚合状态，重复执行由 (tenant, request, rawBizDate) 唯一键收敛。进程重启后继续 APPROVED/RUNNING 中未终态条目；已存在同日批量日记为 ALREADY_PRESENT，不覆盖状态；holiday roll 命中已有有效日记为 ROLLED_COLLISION，需在预览中呈现并由审批人确认；calendar dependency 阻塞进入 WAITING_DEPENDENCY，由日历恢复扫描推进，不占用执行槽；永久配置/数据库错误标记该日期 FAILED，其他日期继续。只有所有条目终态后请求才终态；绝不因一个日期失败回滚已完成的其他日期。至少需要 PostgreSQL IT 覆盖两人审批、并发重复审批、部分成功后重启、roll collision、依赖等待恢复、RLS 隔离、过期预览 hash 和状态/审计原子性。
- **主链恢复补充**：失败日状态和 catch-up launch 分属提交后的两个动作，进程若在 batch day 写为 `FAILED` 后、`afterCommit` 派发前退出会丢失本次补跑尝试。结算扫描现有独立的有界恢复查询，检查仍缺少补跑请求或 AUTO 请求未进入终态的 FAILED 日，并复用原 dedup key；定向测试覆盖无普通结算候选时的恢复入口。真实 PostgreSQL Mapper/RLS 与多实例恢复竞争仍需集成验证。
- 补 runbook：安全重试/补跑、跳过、释放等待和对账，不通过直接改运行态表恢复。
- 验收：指标/告警标签保持低基数；演练事件丢失、上游延迟、worker 重启和跨日补偿并验证最终业务数据。

## 实施纪律

- 每阶段先补能表达验收条件的测试，再实现，并在该阶段结束时更新本文状态、Changelog、领域文档和索引。
- 数据库/Console/API/SDK 契约按实际变更同步配对仓库，不为后续阶段提前铺设无用 schema。
- 本地单测、PostgreSQL IT、真实消息链路、SIM、staging/production 是不同证据；不互相替代。
- 阶段 1 的正常在线日历语义、Quartz occurrence 连续覆盖和“无预期作业空日”自动结算首版已完成；外部事件逐日预期规则已实现；审批式历史回填和流水线集成仍未完成。跨多日停机 catch-up 已开始实施，但缺口显式治理和老数据发现仍未完成。阶段 2 已提供超时告警、Console 等待状态/人工动作结果可视化和人工处置入口，部署后观测仍未完成；完整多依赖契约、文件事件/Workflow 端到端恢复和阶段 5 仍未完成，不把部分实现描述为全闭环。

## 当前实施清单（契约 2026-10-14.2）

以下为当前目标，尚未按整合版本验收；替代来源分支旧兼容清单。

| ID | 工作 | 验证门槛 |
|---|---|---|
| D1 | occurrence/coverage 使用 Workflow 身份与冻结生效区间 | R03–R06/R23；首次启动覆盖未知不能判空 |
| D2 | 输入预期、业务结果义务、规则版本、截止/迟到终结 | R10–R13；输入到达不等于业务完成 |
| D3 | 请求/节点接纳与 BatchDay 结算共享事务围栏 | R11/R22；两种并发提交顺序均正确 |
| D4 | 新模型依赖恢复、实际版本读取与有界 catch-up | R07/R17/R23；不沿旧 Job key 构建新账本 |

## 当前实施记录

| 阶段 | 状态 | 证据 |
|---|---|---|
| 1 日历日与空批量日 | 🟡 Quartz 作业启动/周期对账、日历日主动开启和无预期作业空日自动结算首版已实现；停机补齐、积压指标/告警、只读预览、按日历暂停/恢复、只读历史缺口候选查询（按日历本地 cutoff 拒绝未来业务日）、手工盘点及管理员显式空日结算已实现；事件预期规则已实现；审批回填和流水线集成待补 | 自动空日结算仅检查零实例的 CUTOFF 日期；检查未处理的 calendar/job dependency wait、batch-day waiting、未派发 Trigger、阻塞 occurrence、定时定义 coverage 以及无逐日预期清单的启用 EVENT/MIXED 定义，只有全部为空才 CAS 到 SETTLED 并审计。管理员 `SETTLE_EMPTY` 继续要求原因。Quartz 主 Trigger 原生枚举 fire 并按 `(tenant, jobCode, fireTime)` 幂等保存，复用共享日历业务日解析，实际请求、calendar skip、readiness 延迟/超时和人工补跑审批更新同一 fire 记录；occurrence 物化与 V247 覆盖区间写入同事务，重启后从上次成功水位续扫，缺水位/旧版本/区间未覆盖 cutoff 均失败关闭，超过枚举上限则保留旧水位；schedule 替换只 supersede 未发生的旧版计划，禁用/孤儿 Quartz 作业对账清理其未来 PLANNED 行；MANUAL_APPROVAL 的统一审批批准/拒绝和到期 reaper 现由 Trigger ShedLock 对账器收敛 pending、未启动请求和 occurrence；Trigger misfire mapper 集成、reconciler 和租户边界测试已通过。审批式历史回填与部署流水线集成仍开放；历史缺口最多 90 日只读盘点，不自动补建 |
| 2 跨日历依赖恢复 | 🟡 后端恢复、Console 人工处置及处置结果可视化已实现；部署验证待补 | PostgreSQL 验证 V236–V240、晚结算恢复、重复恢复、并发去重、等待/扫描游标 RLS、一次性超时告警、人工 RELEASE/SKIP；`MultiCalendarCoordinationIntegrationTest` 历史记录为 12/12；本轮目标用例因本机 Docker 不可用被 Testcontainers 跳过；Console 控制器 3/3；本轮 Console 状态筛选/审计 UI 已修改，配对前端 `npm run typecheck` 与目标文件 ESLint 通过 |
| 3 作业依赖模型 | 🟡 多上游 AND gate、跨日对齐、日历感知的前一业务日、持久化等待/恢复、超时告警和 gate 观察版本快照已实现；人工 override 与扩展规则仍未完成 | V241 normalized relation + scalar synchronization; V242 tenant-RLS wait table stores original LaunchRequest/dependency snapshot; V243 adds CAS timeout-alert claim; V245 defaults existing edges to `SAME_DAY` and adds `PREVIOUS_BIZ_DAY`; Orchestrator snapshots and restores both new rule objects and legacy string arrays. Trigger scheduled fast gate filters to same-day edges and distinguishes empty normalized rules from legacy fallback. Console request/response, job form and tenant package Excel support alignment rules; legacy request fields and old workbooks remain same-day. Ready gate now captures the effective partition observed at launch and persists upstream job/alignment/date/business key/version/result_version/source instance/effective time in the downstream `job_instance.params_snapshot`; CLAIM returns this gate evidence in `EffectiveTaskConfig`, and built-in Worker core exposes it as `dependencyResultSnapshot` in execution attributes. This does not prove the worker later read that same version. Legacy materialized partitions may have a null result_version id while preserving their partition version and source instance. Pre-existing instances without the field remain readable. Worker dispatch now exposes the gate evidence in built-in Worker core attributes and tenant Java SDK `SdkTaskContext.dependencyResultSnapshot()`; neither path forces business handlers to read by ID. External business-table consumers still have no enforced versioned read contract. Self-hosted SDK CLAIM parsing defaults a missing field to an empty list. `TaskDispatcherTest` 11/11; `PlatformHttpClientTest` 13 run / 1 environment-skipped; `SdkTaskContextTest` 7/7. `JobDependencyWaitingLaunchServiceTest` 6/6; latest-result readiness, source result id and snapshot serialization have focused unit coverage; full PostgreSQL integration remains required for current run. Historical real PostgreSQL evidence covers calendar/HOLIDAY/WORKDAY_OVERRIDE behavior and latest PENDING version blocking; Trigger normalized dependency tests, Console mutation/Excel apply PostgreSQL IT, Excel validator/workbook guidance and paired frontend checks remain prior evidence. Old wait snapshots are not re-resolved under new calendars; without a calendar, weekdays remain the compatibility rule. Manual dependency override, file-group dependency rules, independent dependency deadlines, and the contract boundary between cross-calendar Workflow dependencies and this model remain open. Dependencies require the latest EFFECTIVE version: newer PENDING blocks downstream; started downstream does not cascade automatically; revisions require explicit replay |
| 4 文件与 Workflow 触发 | 🟡 WAIT 独立路由、FILE_ARRIVAL 身份过滤、持久化轮询恢复、节点级并发认领、完整上下文重启、Workflow/空实例终态及父虚拟任务回报已补；完整多服务故障演练待补 | `DefaultWorkflowNodeDispatchService` 为 WAIT 创建 RUNNING 节点记录且不创建 worker task/partition；真实 PostgreSQL 用例从 LaunchService 启动含 WAIT→END 的 Workflow，验证完整到达组/业务日绑定命中后 WAIT、END、workflow_run 与空根 job_instance 同步终态；要求完整组的 FILE_ARRIVAL 配置拒绝缺少业务日绑定，避免周期 fileGroupCode 跨日误命中，旧式非完整组配置保持兼容，且写出唯一 `WORKFLOW_TERMINAL` outbox。节点终态按最新 `run_seq` 聚合，空子 Workflow 通过父虚拟任务 outcome 推进上游，dry-run 成功按成功分支传播；另有节点认领竞争、重建轮询器恢复及完整 Orchestrator Spring 上下文关闭/重建后恢复验证；真实 TASK 下游的 PostgreSQL outbox 插入触发器故障注入证明 WAIT 成功态、下游 node_run、task、partition、outbox 在 `REQUIRES_NEW` 事务中整体回滚，撤销故障后探测恢复并只创建一套下游记录；`SensorProbeTransactionExecutorIntegrationTest` 完整类 4/4（真实 PostgreSQL/Testcontainers） |
| 5 可观测性与运维 | 🟡 作业依赖等待只读队列、超时告警、聚合积压指标、文件到达组全量等待数/最老等待时长及日切 catch-up 全局积压指标已提供；触发 ACCEPTED 无实例指标/告警、请求到实例 p95 指标/告警已补；全面恢复演练仍待补 | Console 作业定义页新增租户作用域的 WAITING 列表；安全投影不返回完整 LaunchRequest；V243 CAS 超时告警保持 WAITING；catch-up、作业等待和文件组指标均不暴露租户标签；文件组 SQL 聚合按组身份计数而非候选文件行，并包含人工确认等待态。`FileGovernanceArrivalGroupGuardTest` 13/13 与 PostgreSQL `FileArrivalGroupBacklogIntegrationTest` 1/1 通过；后者验证三条文件记录按组计为 2 组、最老等待年龄约 48 小时；`JobDependencyWaitMetricsSchedulerTest` 2/2 与 PostgreSQL `JobDependencyWaitMetricsIntegrationTest` 1/1 通过；`check-slo-sli-catalog.py`、Docker/Helm 规则同步、Orchestrator Spotless 通过；新增 `TriggerStaleAcceptedWithoutInstance` 与 `TriggerRequestToInstanceLatencyHigh`；Trigger 指标单测 2/2、`TriggerRequestMapperIntegrationTest` 4/4（真实 PostgreSQL）通过；SLO 目录、观测契约、Docker/Helm 规则同步、Orchestrator Spotless 通过。目标环境告警送达和全面恢复演练仍待验证 |
