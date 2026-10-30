# 作业与 Workflow 时限告警补齐计划

## 目标与工作边界

用户要求在 `fix/wdi-current` 实际工作树一次性补齐作业和 Workflow 的开始过晚、结束过晚、耗时超限，并修正计时口径、漏扫补报、通知及自动解除，补测试验证。目标保留到全部验收完成，不以其中一层或局部测试通过替代。

基线为 `f3d5e400d` 加当前未提交工作文件；已有改动全部保留，不改 main、冻结分支，不提交。测试使用独立 fixture、本地模拟接收端或测试专属容器，不向实际收件人发送通知，不重建共享数据库或部署服务。

所有新增或修改的说明性注释使用专业中文；Java、SQL 各自保持现有注释格式。每批变更结束复跑注释语言检查，不把英文标识符或协议字段误改为中文。

## 已确认的问题

1. Job 三类监控已接入，但开始过晚只查尚未开始的实例，运行耗时只查 RUNNING；扫描期间已开始或已结束的违约会漏报。
2. Job `started_at` 在可派发时写入，不能作为 Worker 真正执行起点；Job 监控仍依赖旧 schedule/依赖时间字段。
3. Workflow 根无独立三类策略、候选判定及持久化告警。Job 告警的 Workflow 关联字段不能替代根级告警。
4. 无 Run/Job 的计划机会不能由实例扫描覆盖；需要复用 occurrence、TriggerRequest 和节点就绪事实，不能用创建时间伪造计划时间。
5. AM 到渠道失败仍回 HTTP 成功；单条关闭被同组其他告警阻止 resolved；现有扫描缺少自动解除。

## 实施与证据台账

| 项目 | 必须达到的结果 | 当前状态与证据 |
|---|---|---|
| SLA-01 作业计时 | 派发、排队、实际执行起点区分；新编排下计划/依赖基准来自真实持久事实 | V354、冷热字段、服务端及内置 Worker 已实现；Java/Go/Python/TypeScript 默认派发器已接入，Rust 自定义桥提供保护入口，见第三批证据。已建 Job 按真实资格和执行起点计时；Workflow 子作业在 `job_instance` 创建前也按节点触发请求和门禁释放时刻计算资格 |
| SLA-02 作业三类及补报 | 正常、迟启动、迟结束、耗时超限，扫描间隙和重启补报，去重、租户与 dry-run 隔离 | 已覆盖已建 Job 与尚未创建实例的 Workflow 子作业候选、终态补报及规则生效边界；发送前精确复核、请求/实例锁和稳定请求身份已接入；Console 到本地 HTTP 收件端的发送及幂等键已有真实 PostgreSQL 定向集成验证；真实 Alertmanager/Orchestrator 联验仍待补 |
| SLA-03 Workflow 配置与三类 | 根级独立配置、计划/执行/完成基准、实例及尚未建 Run 的机会覆盖，前后端契约同步 | 已实现 V353、配置接口、按租户扫描、三类告警及 11 数据 sheet 租户配置包；配置包校验/应用定向 23 项通过。前端策略弹窗及契约已有 6 个交互用例；真实 PostgreSQL API 集成覆盖租户管理员写读和租户用户拒绝。策略 SQL 使用显式 resultMap 修复了默认 MyBatis 配置下读回默认值的问题；真实 Orchestrator→Alertmanager 联合发送仍待补 |
| SLA-04 通知失败恢复 | 渠道失败产生可重试回执；恢复后投递成功且日志可核对 | 已先红后修：FAILED/SKIPPED 返回 503；补 V356 逐实体、逐渠道成功回执及分批完整投递，回执失败仍可重试。已补 V357 持久关闭意图及按租户恢复重投；本地 HTTP 收件端已验证 503 重试、发生/恢复回执与投递日志；真实 Alertmanager/Orchestrator 联验仍待补 |
| SLA-05 单条与自动解除 | 每个告警按自身指纹解除；违约条件消失后持久化关闭并可靠投递恢复，不误关同组其他实体 | 已接入来源事实复核：Job 实例、Workflow Run、计划机会及 Workflow 子作业触发请求的自然恢复/机会关闭按实体 CAS 关闭；自然恢复须有首次 firing 最终渠道回执，策略停用记录独立撤销原因。历史定向用例 26 项全通过；本轮新增请求身份恢复覆盖见第十批 |
| SLA-06 集成验收 | 真实 PG 判定/事务/并发，真实本地 HTTP 告警投递与恢复，配置/前端/迁移及文档门禁 | Job/Workflow 候选、发射、调度及恢复定向回归 42 项通过，含 29 项真实 PostgreSQL；Console 本地 HTTP 收件端 1 项覆盖 503 重试、幂等键及发生/恢复回执。Workflow 配置包校验/应用 23 项、策略 Service/Mapper 9 项、Console API/RBAC 集成 10 项通过；最后一项从空库迁移至 V358。真实 Orchestrator→Alertmanager→Console 联合发送及 Docker/Helm 目标环境验收仍未完成；定向用例是分层证据，不等同整体验收 |

## 语义与兼容边界

- 软时限违约只产生告警，不改变运行结果或触发重跑；硬超时保持独立。
- Job 和 Workflow 分别使用独立资源身份、三类违约身份和去重键，保留 Workflow/节点关联。
- 迟启动从计划或实际就绪资格起算；没有计划的手工运行不从任意创建时间推导“计划迟启动”。
- 运行耗时使用实际执行起点；Workflow 编排中的 WAIT/SENSOR/依赖等待计入根级运行耗时，Job 派发后尚未执行的等待不伪装成纯执行耗时。
- 已发生违约保留历史事实，恢复后告警状态关闭；开始、完成或策略关闭后的解除必须按单实体判定。补报与恢复需明确先后及可靠投递，不能让同一事务内关闭吞掉尚未发出的告警。
- 当前 Job 动态监控策略语义不在本次悄悄改为创建时快照；新 Workflow 策略的保存、更新生效点和回溯边界必须有对应测试。
- 默认不开启任何实际通知目的地；本地完整验证使用自有接收端，部署开关和真实渠道配置由环境管理。

## 验证记录

### 第一批：通知失败、单条解除与 Workflow 根级基础能力

- 首红：`./mvnw -o -ntp -l /tmp/codex-sla-notification-red-20261025.log -pl batch-console-api -am '-Dtest=AlertmanagerNotifyControllerTest,DefaultConsoleAlertApplicationServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test`，退出 1，9 项中 3 项失败、0 errors/skips。两个未送达场景错误地确认成功，同组另一活跃告警阻止单条解除。
- 首次修复复跑虽报告 BUILD SUCCESS，但没有目标用例的新 XML，不计为测试通过。另一次误在原输出目录启动的自有验证进程已停止，退出 143，不计通过。当前工作树有其它构建使用同一 target，因此改为源文件带 SHA 清单的临时验证导出；不是新 Git 工作树，所有修改仍在实际分支。
- 当前验证导出：`/tmp/codex-sla-validation-20261025`，`source-manifest.json` 保存来源摘要。联合命令为 `./mvnw -o -ntp -l /tmp/codex-sla-root-monitoring-20261025.log -pl batch-orchestrator,batch-console-api -am '-Dtest=WorkflowMonitoringMapperIntegrationTest,WorkflowMonitoringAlertEmissionServiceTest,WorkflowMonitoringSchedulerTest,WorkflowMonitoringPolicyMapperIntegrationTest,WorkflowMonitoringPolicyServiceTest,AlertmanagerNotifyControllerTest,DefaultConsoleAlertApplicationServiceTest,AlertmanagerNotifyServiceTest,AlertLabelsTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`。
- 命令退出 0、BUILD SUCCESS；最新 XML 为 Common 9、Orchestrator 17、Console 25，共 51 个不同用例，0 failures/errors/skips。包含 Workflow 规则 SQL 9 项和策略 SQL/强制 RLS 5 项真实 PostgreSQL；其余为组件或 MVC 测试，不替代真实通知渠道验收。
- 验证后核对本批 26 个代码/SQL文件，工作树与验证清单 SHA 全部一致；其它会话并发改动未由本批代验。前后重复运行的 17/24 项不重复累加。
- 定向 Spotless 已执行成功；本批首次注释语言检查通过。后续新增代码与剩余实现需继续执行完整范围的语言、结构和语义检查。
- 仍开放：真实本地 HTTP 最终收件联验、Workflow 策略配置包/RBAC 验收以及 Docker/Helm 目标环境故障和 firing/resolved 验收。已建 Job 与未建实例 Workflow 子作业的监控候选、来源恢复及定向 PostgreSQL 覆盖已补；不据此宣称业务全链路或生产验收完成。

### 第二批：实际执行入口、前端配置及中文注释复核

- Job 保留 `started_at` 的派发语义，V354 在冷热表新增 `execution_started_at`。Worker 执行池取得线程后，通过原续租请求的 `details.executionStarted=true` 确认入口；服务端先取得实例锁，再校验当前租约所有者、invocation 与取消状态，首次写入起点。该信号不设置非幂等副作用标记，普通批量续租不携带该标记。
- 服务端首红命令：`./mvnw -o -ntp -l /tmp/codex-sla-execution-start-red-20261025.log -pl batch-orchestrator -am '-Dtest=DefaultTaskAssignmentExecutionStartTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`，5 项中 2 项目标失败。补充实现和用例后，服务端 6 项及真实 PostgreSQL 5 项通过；SQL 覆盖首次值、派发时间保持、生命周期状态集合、完成时间围栏、跨租户与冷热精度。
- Worker 首红命令：`./mvnw -o -ntp -l /tmp/codex-sla-worker-start-red-20261025.log -pl batch-worker/core -am '-Dtest=DefaultTaskExecutionWrapperTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`，新增入口顺序和拒绝后禁止执行用例失败。实现后发现既有取消测试依赖 getter 调用次数，改为真实可切换的取消状态，保留业务已启动后取消的验收条件。
- Worker 复跑：`./mvnw -o -ntp -l /tmp/codex-sla-execution-start-green-2-20261025.log -pl batch-worker/core -am '-Dtest=DefaultTaskExecutionWrapperTest,DefaultTaskExecutionWrapperClaimLeaseTest,QueuedBatchClaimLeaseFenceTest,HttpTaskExecutionClientTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`，66 项通过、0 failures/errors/skips。覆盖执行池线程内先确认再执行、确认拒绝不执行不上报、标记不丢失、普通批量报文不变、过期租约和取消边界。仅为当前定向证据，不证明所有 SDK 已接入。
- 前端在配对 `fix/wdi-current` 工作树运行 `npm run gen:api`、`npm run typecheck`、`npm run gen:api:check`、`npm run check:i18n`、定向 ESLint，均成功；`npm run test:unit -- src/components/workflow/WorkflowMonitoringPolicyDialog.test.ts` 6 项通过，覆盖只读权限、读取失败、过期响应、租户切换及规则禁用。未部署共享服务、未作浏览器最终联验。
- 用户再次强调中文注释后，复核本轮 47 个后端相关文件，运行 `python3 scripts/ci/check-comment-language.py --paths <本轮文件列表>` 通过；前端本轮 6 个文件同步核对。说明注释采用专业中文，并修正“执行完成必定上报”“取消 Future 即线程已退出”等不符合实际实现的旧说明；协议字段、标准类型与机器指令保持原名。
- 大范围全路径检查因当前分支已有删除文件而中止，不能记为全工程通过；只读过滤不存在文件后的辅助盘点发现其他模块/SDK 仍有现存英文说明，尚未完成逐条语义治理。本轮范围通过不代表全工程清零。
- V354 Squawk 检查 0 问题；定向 Spotless 成功。注释收尾相对验证导出的 Java 差异已核对为注释/排版变化，代码标记序列一致。原总体目标继续开放，Job 规则补报、SDK 接线、自动解除及可靠通知等仍须完成后才能收口。
- 两批后端联合复跑命令：`./mvnw -o -ntp -l /tmp/codex-sla-scoped-regression-20261025.log -pl batch-orchestrator,batch-console-api,batch-worker/core -am '-Dtest=WorkflowMonitoringMapperIntegrationTest,WorkflowMonitoringAlertEmissionServiceTest,WorkflowMonitoringSchedulerTest,WorkflowMonitoringPolicyMapperIntegrationTest,WorkflowMonitoringPolicyServiceTest,AlertmanagerNotifyControllerTest,DefaultConsoleAlertApplicationServiceTest,AlertmanagerNotifyServiceTest,AlertLabelsTest,DefaultTaskAssignmentExecutionStartTest,JobExecutionStartMapperIntegrationTest,DefaultTaskExecutionWrapperTest,DefaultTaskExecutionWrapperClaimLeaseTest,QueuedBatchClaimLeaseFenceTest,HttpTaskExecutionClientTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`。退出 0、BUILD SUCCESS，Common 9、Orchestrator 28、Worker 66、Console 25，共 128 个不同用例，0 failures/errors/skips；不重复累加前述 51/77/66 项。真实 PG 为 Workflow 候选 9、策略/RLS 5、Job 执行起点 5，共 19 项。

### 第三批：SDK 执行入口与租约身份

- Java 在 `TaskDispatcher` 业务调用前使用独立的 `confirmExecutionStarted`；Go 在业务协程内发送带详情的单次续租；Python 抽取认领后的业务执行和入口确认，保持取消及回报路径；TypeScript 使用 CLAIM 当前 invocation，并在处理器前确认开始。开始计时不替代非幂等副作用标记，不改变处理器参数和业务批处理逻辑。
- Java 新增 3 项入口用例首红为 3 failures、0 errors/skips；TypeScript 4 项场景首红均缺开始确认；Go 修正测试夹具的布尔指针类型后，4 个场景均因未发开始确认而失败；Python 新增 4 个场景首红同样未发开始请求。工具安装及夹具自身错误不作为业务首红证据。
- Java 复跑 `./mvnw -o -ntp -l /tmp/codex-sla-sdk-java-regression-2-20261025.log -pl sdk/java/core -am '-Dtest=TaskDispatcher*Test,PlatformHttpClientTest,SdkPlatformContractTest,LeaseRenewalSchedulerTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`，退出 0、BUILD SUCCESS，107 项中 106 通过、1 环境跳过，0 failures/errors。新增真实 HTTP 核对详情布尔值、当前身份、取消及 409 拒绝，不把 Mockito 调用记录等同于网络验证。
- Go `go test ./...` 退出 0（`/tmp/codex-sla-sdk-go-regression-2-20261025.log`），包含生命周期、共享契约和真实 HTTP 开始载荷；现有 contract/protocol 的缓存结果保留原标识，不伪称新执行。
- 补充 `go test -race ./client -count=1` 首次在既有 `TestWorker_FatalHeartbeatStopsWorker` 中发现测试直接读取异步写入的 `DeactivateCalls`，未据此报告普通测试等同于竞争检测通过。修复为复用 `FakePlatform.Counts()` 的加锁快照，复跑同一命令退出 0（`/tmp/codex-sla-sdk-go-race-2-20261025.log`）；未跳过检查或放宽断言。
- Python `uv run --frozen --extra dev pytest -q` 退出 0（`/tmp/codex-sla-sdk-python-full-2-20261025.log`），499 通过、2 环境跳过，分别为 Happy Eyeballs 矩阵文件及真实 Kafka 未配置；定向 Ruff 通过。新增确认测试证明原 `renew` 将 409 当成幂等成功，现已在传输边界明确拒绝，禁止进入业务。
- TypeScript `node --test --experimental-strip-types 'tests/**/*.test.ts'` 退出 0（`/tmp/codex-sla-sdk-typescript-regression-20261025.log`），156 通过、1 Happy Eyeballs 环境跳过；`npm run build` 退出 0。新增场景覆盖当前身份、租约撤销、响应未知和取消，不误用消息中的旧身份。
- Rust 提供 `execute_after_start_confirmation`，`http`/`kafka` 特性经 `serde` 启用；自定义执行桥应用 CLAIM 载荷后包裹业务调用。入口明确区分执行结果、取消和确认错误，包含无效响应、租约拒绝、预先取消、JSON 转义及真实 HTTP 确认测试。Rust 没有统一的自动派发器，不能把公开保护入口可用写成所有外部宿主已自动升级。
- Rust `cargo test --offline --features http` 退出 0（`/tmp/codex-sla-sdk-rust-http-final-20261025.log`），110 单元、1 常量、4 契约、9 运行时用例全部通过，共 124 项；`cargo test --offline` 另验证默认构建的 96 项通过，不与前述重叠用例累加。未配置真实 Kafka，未将 HTTP/默认构建结果写成真实 broker 验收。
- 协议、对齐矩阵、SDK 总入口和五语言 README 已更新。新增说明性注释检查 0 问题；Python 保持仓库 Ruff 要求的半角标点，注释正文仍为专业中文。当前目标仍未完成，后续继续处理 Job 监控资格/补报、自动解除及可靠通知。

## 第四批：Job 资格、计时与配置链路

- V355 增加运行时长及启动宽限的独立规则更新时间, 原规则沿用可证明的旧更新时间。Job 查询改为实际执行确认时间, 覆盖终态迟启动/耗时补报及规则边界。
- 当前 Workflow 子作业以父节点激活请求和可信依赖快照计算资格, 不继承根运行的计划时间；补充异常日期和不完整快照隔离。已建实例的资格计算已落地, 未建实例机会监控仍待实现。
- MANUAL 子作业的启动/完成策略已贯通 DTO、服务、配置包导入、列表分类和前端编辑/摘要；相对窗口与绝对截止时间互斥。
- 隔离源码导出目录执行 Maven 联合回归, 日志 `/tmp/codex-sla-job-policy-combined-regression-20261025.log`：300 个用例, 299 通过、1 个环境跳过, BUILD SUCCESS。命令范围为 orchestrator、console-api、worker/core、sdk/java/core 及所需依赖, 指定 Workflow/Job 监控、通知、执行起点及 SDK 派发相关测试；不是全仓测试。
- 前端运行 `npm run test:unit -- src/views/job/components/JobMonitoringFields.test.ts src/views/observability/components/ScheduledPolicySummary.test.ts src/components/workflow/WorkflowMonitoringPolicyDialog.test.ts`：3 个文件、15 个交互用例通过。
- 事件和可用性监控静态复核发现依赖实际健康、依赖目标缺失、消费组积压、Workflow 扫描器自监控、首次低频失败及可靠解除仍有缺口, 详见 [SLO/SLI 目录](../runbook/slo-sli-catalog.md)。本目标仍未完成, 不将已有定向回归替代自动解除、可靠通知及最终联合验收。

## 第五批：Job 发送前复核与事务竞争

- 修复旁路扫描与实际写告警之间的陈旧候选问题：独立事务中设置租户上下文, 非阻塞尝试获取执行起点/回报共用的实例锁, 再按租户、实例、违约类型重新查询。使用复核后策略和上下文发送；忙碌、已去重或条件失效均不消耗新 claim。
- 四类发送前查询复用原候选 SQL 片段, 保留各自终态补报、规则生效时间、失败分区回看窗口及 dry-run 语义。
- 先红证据：`/tmp/codex-sla-job-recheck-red-20261025.log`, 5 个单测中 2 个断言失败、0 个执行错误, 分别证明旧实现发送失效候选及使用旧级别。
- 修正事务恢复集成测试的虚构实例准备, 改为真实定义、策略和实际执行实例；验证失败回滚、幂等恢复及同实例锁竞争。首次联合运行因隔离验证目录遗漏 `scripts/db/business/create_biz_tables.sql` 发生初始化错误；补齐原仓资源后重跑, 未跳过测试或修改门禁。
- 后续自动解除、可靠恢复投递、未建 Job 的机会监控和完整联合验收仍未完成。

- 第五批最终联合命令：`./mvnw -o -ntp -l /tmp/codex-sla-job-recheck-concurrency-final-20261025.log -pl batch-orchestrator -am '-Dtest=JobMonitoringAlertEmissionServiceTest,JobMonitoringMapperIntegrationTest,JobMonitoringAlertEmissionRecoveryIntegrationTest,JobMonitoringSchedulerTest,WorkflowMonitoringMapperIntegrationTest,WorkflowMonitoringAlertEmissionServiceTest,WorkflowMonitoringSchedulerTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`。在独立构建目录 `/tmp/codex-sla-validation-20261025` 运行, 50 个用例全通过、0 跳过, 其中 31 个使用真实 PostgreSQL；7 个变更源码与工作树 SHA-256 一致。事务竞争用例证明持锁期间不阻塞且不写 claim, 释放后可补报。
- 第五批定向 Spotless、注释语言、文档结构及 SLO/SLI 目录检查通过；未做生产通知或部署环境验收。

## 第六批：逐实体通知回执与完整分批投递

- V356 新增 `alert_notification_receipt`：按租户、平台去重指纹、接收渠道保存发生首次成功和恢复最近成功回执。复合外键跟随原告警清理，强制 RLS；不将 Alertmanager 自身指纹误作平台身份，不从旧摘要日志猜测逐实体送达。
- Console 将已接收告警按 `max-alerts` 分批完整发送，结构化 `alerts` 对应实际本批；不把尾部摘要当作逐条送达证据。发送失败不写成功回执，回执持久化失败返回可重试失败；同批写入在独立真实事务中一起提交/回滚。
- 同批同阶段重试保持 cursor，发生与恢复使用不同身份；渠道投递为至少一次，外部接收端仍需按稳定 cursor 幂等。混合租户分组在渠道查询前拒绝。逐条自动解除与 CLOSED 补发尚未接线，不能将本批回执基础设施视作目标完成。
- 先红：`/tmp/codex-sla-notify-receipt-red-20261025.log`，10 个单测中 3 个目标断言失败、0 执行错误；覆盖尾部实体遗漏、发生/恢复身份混用、回执未持久化却确认成功。
- 最终联合命令：`./mvnw -o -ntp -l /tmp/codex-sla-notify-receipt-transaction-final-20261025.log -pl batch-console-api,batch-orchestrator -am '-Dtest=AlertmanagerNotifyServiceTest,AlertNotificationReceiptServiceTest,AlertNotificationReceiptMapperIntegrationTest,AlertmanagerAlertRendererTest,AlertmanagerNotifyControllerTest,DefaultConsoleAlertApplicationServiceTest,JobMonitoringAlertEmissionRecoveryIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`，在独立构建目录 `/tmp/codex-sla-validation-20261025` 运行。36 个用例全通过、0 跳过，其中 7 个使用真实 PostgreSQL；包含真实 Spring 回执事务失败整批回滚/恢复和 V1–V356 全部迁移成功。11 个变更源码/迁移与当前工作树 SHA-256 一致；不与第五批重叠用例累加。
- 定向 Spotless、注释语言、V356 Squawk、数据库注释覆盖、可观测性契约、文档结构和新增表登记检查通过。新增表登记首次发现既有 `readiness_wait`、`trigger_launch_recovery` 未登记，已按实际迁移补职责及链接后重跑通过；未放宽检查。
- 相关说明已同步数据库结构指南、通知/审计边界与观测栈 Runbook。本批未启动真实监控栈、向实际收件人发送通知或宣告整个告警链路完成；后续继续补解除判定、可靠恢复重投及未建 Job 机会覆盖。

## 第七批：关闭意图与可靠恢复补发

- V357 保存新关闭意图的 `closed_at`、`close_reason` 与公平轮询尝试时间；人工关闭保持原时间且不能被并发确认重开。历史 CLOSED 不回填，避免制造历史恢复通知；来源判定已在第八批接线。
- 维持 OPEN/ACKED/SUPPRESSED，按全部租户进入独立 RLS 事务；单租户故障隔离。CLOSED 在最终渠道回执不足时持续补发，尝试水位不代表送达；已发生渠道分别确认，旧恢复回执不抵消新关闭，晚到发生成功回执会恢复补发资格。按原始标签、首次时间和持久关闭时间发送，事务提交前不入队。
- emit 在持久化后重新读取原始身份，已关闭行不再发 firing。先红 `/tmp/codex-sla-resolution-closed-red-20261025.log`：3 个用例中 1 个目标断言失败、0 执行错误。
- 最终联合命令：`./mvnw -o -ntp -l /tmp/codex-sla-resolution-final-20261025.log -pl batch-orchestrator,batch-console-api -am '-Dtest=DefaultAlertEventServiceEmitTest,DefaultAlertEventServiceEscalationTest,AlertmanagerEmitPublisherTest,AlertmanagerReemitServiceTest,AlertmanagerReemitSchedulerTest,AlertmanagerReemitMapperIntegrationTest,JobMonitoringAlertEmissionRecoveryIntegrationTest,DefaultConsoleAlertApplicationServiceTest,AlertEventActionIntegrationTest,AlertNotificationReceiptMapperIntegrationTest,AlertNotificationReceiptServiceTest,AlertmanagerNotifyServiceTest,AlertmanagerNotifyControllerTest,AlertmanagerAlertRendererTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`，隔离构建目录执行，60 个用例全通过、0 跳过，其中 15 个使用真实 PostgreSQL；完整迁移到 V357。16 个本批源码/迁移与当前工作树 SHA-256 一致，不与前批重叠用例累加。
- 定向 Spotless、V357 Squawk、注释语言、数据库注释覆盖及可观测性契约通过。通知载荷与重试的断言目前来自映射/事务及真实 PG，尚无真实 HTTP AM/最终渠道联合送达证据；不得宣称端到端完成。
- 后续仍需未建 Job 的机会监控、真实本地 HTTP 发生/恢复收件联验、根策略配置包/权限/部署对齐及 Docker/Helm 故障注入与最终送达验收。


## 第八批：软时限自动解除来源判定

- `AlertmanagerReemitService` 在每租户事务中先读取活动告警，再由 `AlertEventMapper.closeEligibleMonitoringAlert` 使用租户、行状态、`updated_at` CAS 与来源事实复核关闭条件；未解除的实体继续发生通知轮询，关闭实体在同轮进入 CLOSED 恢复回执补发。
- Job 启动告警只在实际执行开始或可信终态证实机会结束时解除；完成/耗时告警要求来源进入明确终态并有结束时间。Workflow Run 和 occurrence/expected-work 机会分别核对根策略、租户、definition、资源身份、dry-run 及明确关闭状态。未知、缺行、跨租户、陈旧快照与未完成机会均不自动关闭。
- 自然恢复要求该实体至少存在发生通知最终渠道成功回执，避免先关闭再漏掉迟到的首次 firing；策略撤销以 `POLICY_DISABLED` 单独记录，不伪称业务恢复。机会被明确取消/超时/跳过时记录 `OPPORTUNITY_CLOSED`。运行事实本身保持不变。
- V358 增加归档 Workflow Run 按租户和 `trigger_request_id` 的部分索引，支持候选 Run 归档后的来源核对。
- 首轮真实 PostgreSQL 用例暴露测试夹具缺少 `biz_date` 与有效业务日历，修正夹具后重跑成功；这两次夹具失败不计为逻辑测试失败，也不作为通过证据。
- 最终定向命令：`./mvnw -o -ntp -l /tmp/codex-sla-auto-resolution-green3-20261025.log -pl batch-orchestrator -am '-Dtest=AlertmanagerReemitServiceTest,MonitoringAlertRecoveryIntegrationTest,AlertmanagerReemitMapperIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`，在 `/tmp/codex-sla-validation-20261025` 隔离源码/构建目录执行；单测 5、回执 mapper PostgreSQL 4、自动解除 PostgreSQL 17，共 26 项全通过、0 跳过；Flyway V1–V358 成功。此项不包含真实 HTTP 最终收件端、未建 Job 机会、生产部署或全仓完整测试。
- 事件与可用性覆盖审查已记录于 SLO/SLI 目录；第九批补入可由仓库维护的规则和指标信号。目标环境 exporter 目标是否存在、依赖故障触发/恢复和最终收件仍未验收。

## 第九批：事件与可用性监控缺口

- 新增 PostgreSQL `pg_up`、Redis `redis_up`、Kafka 零 Broker 和 MinIO 离线节点/磁盘告警；Docker 规则同步到 Helm。Kafka 部分 Broker 数量需要站点拓扑基准，Kubernetes exporter 目标由集群平台负责登记与缺失告警。
- Kafka 消费滞后规则现在覆盖 Worker、Trigger launch、Console Job/Workflow 终态通知及可选 OpenLineage 消费组，支持 `BatchKafkaTopicNamespace` 添加的命名空间前缀。
- Job 监控按违规类型和阶段暴露最后失败/最后成功时间戳；Workflow 监控按扫描阶段暴露最后失败/整轮成功时间戳。规则比较失败与成功时点，使首次采样前发生的低频失败继续保持 firing，完整成功扫描后解除；原失败 counter 保留供频次分析。另登记监控启用状态和扫描间隔，对从未成功或长期未成功的调度器告警；禁用时不误报。
- Job/Workflow scheduler 定向单测在隔离源码/构建目录运行通过：Job 6 项、Workflow 5 项，共 11 项，0 跳过。用例覆盖首次故障保持、后续成功解除、逐租户完整扫描恢复及启动期监控配置指标登记。promtool fixture 已补，当前环境没有 `promtool`，规则 fixture 尚未执行。
- 补充 Prometheus 自抓取、配置重载与规则计算失败监控，并修复 Alertmanager target 消失时没有 `up` 序列而漏报的问题；两类时限扫描器新增静默停摆告警。对应 Prometheus fixture 已补，等待 promtool 验证。
- 本批未启动观测栈、未注入真实依赖故障、未发送真实通知。Alertmanager/Prometheus 栈外独立监控、Kubernetes 依赖 target 清单、Kafka 部分故障阈值、通知最终收件联验及未建 Job 节点机会监控仍未完成。

## 第十批：Workflow 子作业未建实例机会监控

- 修复开始/完成时限扫描仅查询 `job_instance` 的覆盖缺口：父 Workflow 活跃且非演练时，按 `trigger_request` 查找尚无关联实例的子作业机会；门禁等待期间不计时，资格时刻取请求创建与已释放门禁时间的较晚值，完成时限沿用作业时区、业务日和相对窗口口径。
- 子作业告警在实例创建前后统一使用 `workflow_job_request:<trigger_request_id>` 去重身份。请求锁和发送前复核阻止陈旧扫描误报；实例创建后候选不重复发送。自动解除只接受实际 Worker 执行开始、作业终态、请求明确拒绝/取消或父 Workflow 终态，并继续要求自然恢复已有最终 firing 回执。
- 首轮候选 mapper 真实 PostgreSQL 集成 20 项通过；自动解除用例补充父 WorkflowRun `biz_date` 后，9 项真实 PostgreSQL 用例通过。最终联合命令：`./mvnw -o -ntp -pl batch-orchestrator -am '-Dtest=JobMonitoringMapperIntegrationTest,MonitoringAlertRecoveryIntegrationTest,JobMonitoringAlertEmissionServiceTest,JobMonitoringSchedulerTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`；42 项通过、0 失败、0 错误、0 跳过，其中 29 项真实 PostgreSQL。Flyway 从空库执行 V1–V358。
- 规则层仍有环境责任：Alertmanager/Prometheus 栈外独立探测、Kubernetes 依赖目标清单、Kafka 部分 Broker 拓扑阈值、Docker/Helm 实际故障 firing/resolved 与最终接收端联验。本轮没有启动监控栈、注入依赖故障或发送真实通知；本机没有 `promtool`，Prometheus fixture 未执行。

## 第十一批：告警最终收件幂等与恢复联验

- WebhookDispatcher 现对 Alertmanager 告警通知传递稳定 `Idempotency-Key`。同一发生通知的 HTTP 失败重试复用键；resolved 通知因阶段身份不同使用另一键。普通业务变更事件仍不借用该键，Job/Workflow 终态事件行为保持不变。
- 新增 `JobWorkflowSlaAlertDeliveryIntegrationTest`：真实 Console Controller/Service/Mapper、Testcontainers PostgreSQL、真实 WebhookDispatcher 与本地 HTTP 收件端串联。收件端先返回 503，断言 API 不确认成功且不写 firing 回执；随后重试发生通知并发送 resolved，断言三次实际 HTTP 状态、租户/事件头、稳定/区分的幂等键、两阶段数据库回执及 FAILED/SUCCESS 投递日志。
- 定向验证命令：`./mvnw -o -ntp -pl batch-console-api -am '-Dtest=WebhookDispatcherTest,JobWorkflowSlaAlertDeliveryIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`，BUILD SUCCESS；发送器测试 10 项、真实 PostgreSQL/本地 HTTP 集成 1 项通过，0 failures/errors/skips。定向 Spotless 检查通过。
- 第十一批时，该测试验证 Console 内部接入到最终 HTTP 收件端这一段；没有运行真实 Alertmanager 进程或 Orchestrator 与 Alertmanager 的规则触发联合链路，也没有连接真实外部通知提供商。配置包/RBAC 后续闭环见第十二批，Docker/Helm 环境验收仍开放。


## 第十二批：Workflow 策略配置包与接口读回修复

- 租户配置包新增 workflow_monitoring_policy 数据 sheet，覆盖导出、预览、校验和应用；按租户及 Workflow 编码/版本引用，应用时缺省策略使用关闭值，不跨租户复制告警路由等环境资产。
- Console 策略接口补充真实 HTTP 权限/持久化用例：租户管理员可写入并读回阈值，租户用户写入返回拒绝；测试同时查询数据库验证保存结果。
- 首次 HTTP 集成发现写入成功但 GET 返回默认值。根因是生产 MyBatis 默认未启用下划线转驼峰，Mapper 集成测试此前显式开启而掩盖了配置差异。Mapper XML 增加完整显式 resultMap，测试改为关闭驼峰自动映射；服务读回为空时抛出失败，不再以请求对象伪装持久化成功。
- Mapper/RLS 与 Service 定向用例：`./mvnw -o -ntp -l /tmp/codex-wdi-workflow-monitoring-mapper-final-r2-20261026.log -pl batch-console-api -am '-Dtest=WorkflowMonitoringPolicyMapperIntegrationTest,WorkflowMonitoringPolicyServiceTest,MessagesPropertiesParityTest' '-Dmaven.compiler.testIncludes=**/WorkflowMonitoringPolicyMapperIntegrationTest.java,**/WorkflowMonitoringPolicyServiceTest.java,**/MessagesPropertiesParityTest.java' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`，11 项通过、0 失败/错误/跳过，其中 Service 4 项、Mapper PostgreSQL/RLS 5 项、中英文消息键对称 2 项。
- HTTP/RBAC 集成：`./mvnw -o -ntp -l /tmp/codex-wdi-workflow-monitoring-rbac-final-r2-20261026.log -pl batch-console-api -am '-Dtest=ConsoleWorkflowDefinitionMutationIntegrationTest' '-Dmaven.compiler.testIncludes=**/ConsoleWorkflowDefinitionMutationIntegrationTest.java,**/AbstractMutationIntegrationTest.java,**/AbstractIntegrationTest.java' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test`，10 项通过、0 失败/错误/跳过；Flyway 从空库执行 V1–V358。配置包校验/应用 23 项通过；最终 API 定向 test 命令完成当前主源码与目标测试源码编译。
- 以上为定向组件、数据库和接口证据。真实 Alertmanager/Orchestrator 联合发送、Docker/Helm 环境 firing/resolved、栈外监控、Kubernetes 依赖目标清单及 Kafka 部分 Broker 阈值仍需环境所有者验收；本批未启动监控栈或发送真实通知。


## 第十三批：旧实例级 Job SLA 终态补报与监控缺口复核

- 修复旧 `deadline_at` / `expected_duration_seconds` SLA 仅扫描 WAITING/READY/RUNNING 的漏报：新增对有 `finished_at` 的非 dry-run 成功、失败、部分失败、取消及终止实例的候选查询，按实际结束时刻判断是否曾越过阈值；扫描时仍运行、处理前转入终态的实例会在成功 CAS 后重新读取当前行，再生成告警详情。未知状态、无结束时刻及 dry-run 记录排除。
- 首红证据：真实 PostgreSQL Mapper 回归曾在期望补报两条历史终态实例时返回空候选，记录于 `/tmp/codex-active-goal-sla-terminal-red-20261026.log`。修复后验证命令 `./mvnw -o -ntp -l /tmp/codex-active-goal-sla-terminal-green2-20261026.log -pl batch-orchestrator -am '-Dtest=JobMonitoringMapperIntegrationTest,JobSlaViolationProcessorTest,JobSlaSchedulerRecoveryIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test` 通过：47 项通过、0 失败、0 错误、0 跳过，其中 Mapper PostgreSQL 集成 22 项、处理器单测 7 项、扫描恢复集成 18 项；Flyway 从空库执行 V1–V358。
- 事件与可用性静态复核确认 Import 到达端点缺少扫描结果/新鲜度/登记时延指标，且事件开关与扫描器总开关不一致时会误报触发成功；服务级 5xx 规则之外尚无用户访问路径黑盒、通用 HTTP 延迟告警或量化 SLO。新增 OBS-ENV-05/06 跟踪，目标环境依赖 target、栈外监控、Kafka 部分 Broker 阈值及 Docker/Helm 端到端通知验收仍开放。
- 本批 47 项是定向验证，不代表全仓测试或目标环境告警验收；本轮事件/可用性盘点仅静态审查，未运行 promtool、未启动监控栈、未注入故障、未验证真实收件渠道。
