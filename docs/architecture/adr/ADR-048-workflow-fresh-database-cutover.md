# ADR-048：全新数据库下的 Workflow 与每日连续性统一模型

- 决策日期：2026-10-14；契约版本：`2026-10-14.2`。
- 状态：已批准；Workflow 新根、固定系统 Pipeline、日期/配置快照、机会/义务与恢复主链已有开发分支实现。代码实现、本地验证、CI 与目标环境签收分开；当前余项以验收矩阵为准，不沿用初版“整体尚未实现”的判断。
- **Supersedes ADR-047**：用户明确“不需要兼容旧的”，并确认“全新数据库部署”。ADR-047 保留为历史决策，本 ADR 是当前权威契约。
- 实施入口：[整合计划](../../plans/workflow-daily-integration-plan-2026-10-14.md)；验收入口：[验证矩阵](../../plans/workflow-daily-integration-validation-2026-10-14.md)。

## 背景与范围

两项改造共同建立新的运行模型。部署到全新数据库，同步部署相匹配的 Trigger、Orchestrator、Worker/SDK 和 Console；停旧启新，不连接旧运行队列。不交付旧 Job 顶层启动、旧 wrapper/虚拟父任务、旧 FILE_STEP 图转换、旧 Pipeline 载荷、旧配置包导入或旧数据库升级兼容。

已有来源分支和数据的保全属于交付资料管理；本决策不要求删除旧环境，也不把旧历史导入新运行库。新系统投入使用后的重试、恢复、版本快照及归档查询仍是必需能力。

## 决策

### ALIGN-1：运行层级与写入责任

- 唯一顶层运行是 `WorkflowRun → WorkflowNodeRun → JobInstance`；START/END/GATEWAY/WAIT 等控制节点按其语义不创建 Job Instance。
- Job 是可复用执行配置；Job 页面运行先定位受管 Workflow，再使用同一顶层入口。图内 Job 节点只创建该节点的执行实例，不再启动另一份受管 Workflow。
- 同一图两次引用同一 Job、并行分支各自拥有节点身份；不能用 jobCode 或 bizDate 单独合并这些合法执行。当前版本不支持 Workflow 嵌套/子 Workflow 节点：现有业务契约由同图 JOB/GATEWAY/WAIT/DAG 和持久化跨运行依赖表达，尚无需要独立子流程生命周期的已确认用例。不得把旧 `job_type=WORKFLOW` wrapper 转译成子 Workflow；若出现共享子流程、独立版本/重试/取消或独立日历等真实需求，须另立 ADR 定义身份、参数/日期继承、状态传播、循环检测与恢复语义后再纳入。
- Orchestrator 独占运行状态推进；状态与 Outbox 同事务，保留 `DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`。Kafka、Quartz 和 ShedLock 均不能代替持久化唯一性、CAS 与终态保护。

### ALIGN-2：日期与配置冻结

Workflow 日程是顶层计划、日历和时区的唯一来源。接纳运行时冻结原计划/事件时间、bizDate、半开数据区间 `[start,end)`、日期解析依据、图和有效执行配置。子节点默认继承日期；跨日依赖和节点日期映射须显式声明并保存解析结果。延期、重试和进程恢复不得用当前时间重算。

手动运行须校验日期/区间一致性；同一业务日的多次计划 fire 保持独立身份。DST 重复本地时刻以不同 UTC instant 区分；不存在的本地时刻按明确的日程策略处理。批次窗口是节点资源准入条件，不能扩张为整个 Workflow 的统一运行窗口。实时停机/熔断等运维策略继续在执行边界生效。

凡携带 `bizDate` 的 Trigger Request 都必须能解析到日历。顶层请求使用其目标 Workflow 的日历；Workflow 子 Job 请求继承根 Workflow Run 冻结的日历，不能改用子 Job 当前日历。已结算/跳过/人工放行日期只接受带审计语义的显式 `CATCH_UP` 或 `RERUN`，普通迟到请求必须失败关闭。

### ALIGN-3：机会、投递与执行身份

| 对象 | 新模型身份及规则 |
|---|---|
| 定时 occurrence | 当前一套日程/Workflow：tenant + workflowDefinitionId + 原 scheduled fire（epoch millis）；schedule version 是属性，不制造第二次运行资格 |
| 外部事件 | tenant + producer/source + 稳定 event ID 标识一次来源事件；一个事件到一个目标 Workflow 的投递另外包含目标 ID。同一来源允许声明式路由多个目标；`externalEventCode` 仅是规则类别 |
| 文件到达 | 已持久化到达组 ID + 明确业务日/批次 + 目标 Workflow；扫描、通知与 sensor 复用来源关联，不各建一次运行 |
| 顶层请求/运行 | 同一 occurrence/目标投递的一次接纳只产生一个根运行；调用方 requestId 重试不得新增运行 |
| 节点执行 | Workflow Run + Node Run/节点身份 + 合法执行尝试；同一投递不新增尝试，显式 retry/replay 保留来源关系 |

运行内自动重试统一消耗 JobInstance 冻结的 executionPolicy 预算；NONE/零预算/耗尽后进入死信，死信不得重置自动预算并复活同一终态子执行。重排队与 CLAIM 核对父 Job/Workflow，暂停按 ADR-044 保留在途任务并等待恢复。再次处理终态需使用可追溯来源的新 Workflow Run/attempt。现场复现及验证见[整合计划 §3.2.1](../../plans/workflow-daily-integration-plan-2026-10-14.md#321-运行内重试与死信的收口2026-10-16-现场根因修正)。

实际唯一约束必须带租户域，并与 API、Mapper、消息及归档查询一致。重投不能改写已发生机会的原日期、版本和请求关联。配置修改只改变生效点之后、尚未发生的计划；终态不可复活。显式重新处理创建有审计来源的新请求/运行。

已持久化为 `MISFIRE_PENDING` 的定时 occurrence 是 Workflow 暂停前已经发生的业务义务。用户已选择 Workflow 停用时“保留并继续恢复执行”，既有 misfire 义务不因停用而被删除或放弃。`schedule_enabled=false` 只停止新的常规定时 fire，既有 `MISFIRE_PENDING` 仍由 Trigger 对账器按原 fire time 重建恢复 trigger，通过既有策略（包括人工审批）与幂等接纳链最多创建一个 `CATCH_UP` 请求，无须重新启用主日程。恢复注册不得把 `schedule_enabled` 改为 true，也不得重建常规 cron；租户及 Job/Workflow 的执行准入仍按各自权限检查。完整停用 `workflow_definition.enabled=false` 的恢复已加入受持久既有义务身份约束的准入：V282 保留首次 `misfire_detected_at`，Trigger 只重建已有待恢复 fire；Orchestrator 仅接纳 tenant/Workflow/fire/bizDate/request/version 相符的已绑定 CATCH_UP，请求自报类型不构成授权。人工策略还须关联已通过的原 pending 审批；租户暂停及 Job 执行准入继续有效。已有定向正负例通过；2026-10-16 同一八应用构建的真实 SIM 已验证 callback 后经 Console 完整停用、按业务 requestId 审批至 Root/Job 双 SUCCESS，保持原日期/区间/版本及停用状态。详细证据见验证矩阵，最终全量回归与其它发布门槛仍未关闭。

### ALIGN-4：覆盖证据与业务义务

Trigger 复用 Quartz 枚举计划，事务性保存 occurrence 与覆盖进度；Orchestrator 引用这些证据，不再实现另一套 cron。覆盖绑定日程生效区间及日期解析依据，分页/失败不能把未扫描区间标为已覆盖。首次启动没有历史覆盖证明，不能从“现在没有记录”推断过去无工作。

BatchDay 引用冻结的日程/预期规则与其覆盖证据。文件组和外部事件预期由 Workflow 拥有，规则键包含 tenant、calendar 和 `workflow_definition_id`；逐日 occurrence 的唯一身份包含 Workflow ID、来源类型、稳定来源键和业务日。配置及逐日注册内部请求均使用 Workflow ID，不能由 Job code 推导归属；到达适配器可按输入元数据解析其受管 Workflow，但最终预期记账必须使用解析后的 Workflow ID。区分输入到达义务和最终业务结果义务；ARRIVED、SATISFIED 或请求 LAUNCHED 只按本对象定义解释，不能替代 Workflow/Job 的业务成功。

自动空日结算要求覆盖完整、无待履行义务、无在途请求/运行/等待、无未处理失败，并满足既有 CUTOFF/零实例条件。未选分支记录原因，不强制所有 DAG 节点每天执行。人工豁免记录权限、原因和影响范围，不能伪造输入或业务结果。迟到在明确的结算边界之后走受控 catch-up/replay，不能悄悄修改已结算事实。

### ALIGN-5：依赖与实际消费

DAG 控制边只表达同一 Workflow Run 内的节点顺序/分支；它不是跨运行依赖。跨运行 Job 产物依赖以 Job 结果 EFFECTIVE、租户、业务日和冻结的结果版本为条件。等待上游整条 Workflow 成功则是独立依赖类型，必须持久化目标 Workflow 身份、业务日/Run 选择策略、终态条件、等待/超时状态及 replay 来源；只有上游 WorkflowRun 的 SUCCESS 才能放行，不能从某个 Job 结果有效推断。若下游还需读取整流程产物，另行定义 Workflow 结果版本契约，不复用 Job `result_version` 冒充。

Trigger 只做预检；Orchestrator 最终裁决、持久化等待并原子启动节点。等待期间按声明策略选择候选结果；放行时冻结选择，新模型可控消费者按选定版本读取，版本缺失时失败。仅透传 `dependencyResultSnapshot` 仍属于 gate 观察证据，未追踪的外部消费者须明确标识。上游修订不隐式重跑已启动下游；replay 保留新旧消费来源。`origin/main` 既有依赖逐项归类为 DAG 控制、Job 结果依赖或 Workflow 整体成功依赖；语义未归类或缺少对应持久化状态机前，不得关闭 I6 或进入 D4。

### ALIGN-6：系统 Pipeline 与新模型恢复

Pipeline 保留执行阶段与运行观测模型，由 Job 类型映射系统定义。PROCESS 插件/SQL、业务参数、重试/超时等有效配置按类型校验并冻结；不可表达的配置在创建/发布时拒绝，不能静默丢弃。运行固定 Pipeline code/version 和有效参数。

新模型运行的恢复必须使用该运行的冻结版本。发布前声明支持的运行载荷版本；不支持的版本显式拒绝，并在升级前排空或准备支持该版本的执行器。此次不实现旧系统载荷兼容；未来升级也不能把已启动的新模型运行按最新模板重新解释。

人工节点跳过沿整合计划 I6/R35 的既有审计要求，在 Console `POST /api/console/workflow-runs/{id}/skip-node` 必填 query `reason`（非空、最长 1024），操作者来自认证上下文。该字段相对旧 API 是真实收紧；配对 Console 输入、API 和生成类型同步切换。OpenAPI 门禁仅登记此次路径/方法/参数的差异，其他新增必填参数继续阻断；该登记在比较基线纳入本次切换后自然不再匹配。验证与授权依据见执行日志 B31/B32，不将其误称为历史 spec 漏录。

### ALIGN-7：单次切换与发布边界

部署使用新库和隔离的消息/调度/缓存命名空间；旧服务及其回调不得进入新环境。前后端、Worker/SDK、配置包作为明确版本集合验证。旧接口/载荷应明确拒绝，不能静默降级到 Job 根路径。

源分支 SQL 是整合输入，最终新库结构与初始化链另行收敛；不为旧数据转换保留额外运行分支。新库初始化必须可重复执行到相同 schema，种子数据幂等，失败可定位。不得通过关闭约束、绕过失败迁移或放宽测试满足发布门槛。

## 理由、代价与替代方案

这次选择减少双写、旧 key 映射和混合执行器带来的复杂度，代价是一次性停旧启新及客户端同步更新；旧配置包和历史运行不能直接导入或恢复。已有业务副作用不能依靠重建数据库撤销。

未采用同时支持新旧顶层入口、旧库就地升级或直接重放旧消息的方案；它们均超出用户确认范围。成熟系统的借鉴点是日期、执行身份、持久化恢复和明确策略，具体边界按本系统确定；不引入新的调度引擎或通用义务框架。

## 联合验收

A1 唯一运行与重投；A2 日期/区间固定；A3 业务义务与结算；A4 实际消费版本；A5 新模型故障恢复及旧入口拒绝；A6 全新库、配置/API/前端和停旧启新。逐项场景与退出条件见验证矩阵。当前均未完成联合验收。
