# Workflow 整合功能缺陷复核（2026-10-24）

> 本文保留 2026-10-24 历史基线。2026-10-25 在 `fix/wdi-current` 已完成本批 readiness、Kafka、WR-04、普通 Java SDK 故障边界及配置导入的本地修复与定向验证，见[当前收尾证据](../verifications/wdi-functional-closure-2026-10-25.md)。WR-04 后续已真实复现并修复，下面历史表中的“候选／未复现”不是当前结论。

范围：`feature/workflow-daily-integration` 当前暂存、未暂存与未跟踪源码；后端 HEAD `ee01e97515b04fd167ed17977535ec5cab8572f8`，配对前端 HEAD `662d254e2f8b97f93021de548815470d2291c997`。本轮使用项目工程审查及相应业务专项流程，只覆盖审批、触发等待、SDK 执行恢复、清理和归档路径，不代表全仓问题扫描完成。

按用户最新分工，**代码规约扫描、整改及其门禁由另一会话负责**；本会话只处理功能、数据安全和有证据的性能问题。保留既有规约修改，不并发改其规则或重复运行格式、PMD、Arch 检查。Maven 与共享数据库验证串行，先复现失败，再修生产代码，再跑关联完整测试类；未提交、推送或创建 PR。

## 当前功能清单

| ID / 级别 | 业务问题与影响 | 当前证据与关闭条件 |
|---|---|---|
| B192-C01 / P1 | 审批 CAS 的实际结果被 HTTP 200 丢弃，反向决策失败仍可推进整 Workflow Replay | 首红 5 个业务断言失败，0 编译/环境错误；已实现实际决策回读、不可变身份核对和后端持久审批绑定，首绿 5/5、关联 5 类 72/72 通过。真实 PostgreSQL 4个不同用例组合通过：反向决策零写入、批准唯一请求/事件/幂等键及原批准人保留、Outbox已插入后抛错全回滚且同审批可重试。本地范围关闭；不是Worker业务执行验收。 |
| WR-01 / P1 | 相同 Worker 并发重复投递，同一任务 handler 执行两次 | 首红实际执行次数 2；执行前增加独立原子占位，避免为未 CLAIM 任务续租。两例首绿及受影响 5 类共 58 项通过，0 失败/错误/跳过。本地范围关闭。 |
| WR-03 / P1 | handler 抛出 Error 后任务上下文未释放，续租器继续延长已退出任务的租约 | 首红观察到退出后 renew；改为仅占位拥有者在最外层 finally 清理，原 Error 继续传播，不伪造终态。与 WR-01 共用上述 58 项回归，本地范围关闭。 |
| B192-S01 / P1 | 保存 Workflow 后 cron 不变，Quartz 旧 version 未刷新，后续正常 fire 被版本守卫拒绝 | B196已修复同计划的JobDetail元数据刷新并保留FIXED_RATE相位；6个组件首红复验通过，关联51个不同组件用例及6个真实Quartz/JDBC恢复场景通过。原版本围栏保留；完整等待所有权仍待，不据局部修复关闭R03。 |
| B192-S02 / P1 | WAITING_READINESS 已落账，替换/清理 Quartz job 会删除唯一重试回调 | B196主调度替换/清理保留readiness辅助回调，恢复trigger冻结原fire元数据；补齐TriggerBuilder参数合并覆盖的真实失败，51组件+6真实Quartz/JDBC场景通过。完整Orch持久等待、原deadline移交与无Quartz回调恢复仍待，不能据此关闭。 |
| B192-S03 / P1 | 等待期间修改日历，原 occurrence 与新 request/root 的业务日可能分裂 | **B194 日期与终态保护已本地验证**：复用既有occurrence读取原业务日/版本/日历；终态普通回调不建请求，最终状态/身份写入零行使同事务请求及Outbox回滚。新增并发超时及5种状态场景首红6例，原日期首红复验通过；关联4类109个不同用例组合通过、0跳过。保留已接受的CLOSED_DAY_REJECTED显式CATCH_UP例外，SQL核实持久请求身份/类型。S01–S02及Orchestrator等待所有权/原快照/截止时间完整移交仍待，R03未据此整体关闭。 |
| WR-02 / P1 | 普通 Java SDK 执行外部副作用前未使用既有 non-idempotent-started 协议，丢失回报可能被自动回收重做 | 实际HTTP首红4例中2失败、2通过：POST未先确认开始，owner已换后仍产生外部动作；GET及带幂等键POST保护例通过。补充首红另确认通用重试对丢响应POST执行3次，以及缺CLAIM当前身份时仍执行；缓存命中保护例通过。修复后7项关键场景全部通过；关联8类84项首轮22 failures、1 error已按实际协议差异收口：仅补真实CLAIM身份及纯逻辑处理器安全声明，原断言全部保留；5个失败类及额外SdkPlatformContract共60项复验通过，结合未改3类30项形成9类90个不同用例组合通过。共享testkit、starter与两套实际接入样例定向通过21项，Mismatch原方法按安全声明修复后1/1复验；按classname+case去重替换后共111个不同用例、0失败/错误/跳过，组合明细在sdk-composite-ledger.json。**B195真实普通SDK恢复2/2通过**：实际Kafka→HTTP CLAIM/START→独立业务PG提交→Error退栈→生产lease reclaim；非幂等动作进入UNKNOWN、重投精确返回不可领取且业务效果1；稳定业务键幂等动作经唯一恢复Outbox、新CLAIM执行至Root SUCCESS，执行2次但效果1。专属数据/消费组/topic清理断言通过。既有stub接入2例也通过；独立SDK子进程跨日caller已复验通过：强制终止首次进程后，新invocation仍消费已固定的原版本（上游原记录已归档），Root成功且业务结果唯一。首轮新IT两处夹具协议断言错误已保留并精确修正（终态NOT_FOUND及RECOVER位于持久task_payload），不改生产使夹具通过。此为同JVM执行线程Error退出、直接调用生产回收单元、共享安全bypass，不能冒充OS kill/网络REPORT丢失/认证验收或整个SDK家族端到端通过。 |
| B192-C02 / P2 | 按租户 ID 清理未覆盖新模型请求及 Outbox 闭包 | **B193 本地关闭**：真实 DELETE API 首红2例均因 `fk_trigger_request_workflow_definition` 返回500；补齐既有新模型清理目标及固定租户限定SQL，按依赖顺序删除。父引用更新补充本行租户围栏，跨租户重号不清空邻居父关系。新增2例首绿，关联 Repository8/XML4/真实HTTP+PG9共21例通过、0跳过；覆盖请求/等待/Outbox闭包、同请求号邻居、跨租户同数字ID父子关系、事务失败全回滚及重试、重复清理为零。范围限现行热测试数据清理目标，不代表冷归档/外部存储全域擦除。 |
| WR-04 / P1 候选 | 非 DISPATCH 的 UNKNOWN 恢复授权可能缺少绑定根请求的一次性消费 | 仅确认准入/阻断与消费写入的静态差距；第二个无关根请求获准并产生外部动作尚未复现。保持候选，不宣称已造成重复执行。 |
| WR-02 消费起点 / P1 | SDK 首次订阅/新路由 topic 无提交位点时 latest 跳过已发布待执行消息 | 独立进程恢复先暴露超时；最小真实Kafka首红确认唯一消息offset0被跳到offset1、30秒无报告。改为earliest，保留已提交位点与服务端CLAIM；原首红、独立进程恢复及消费/位点/背压/重平衡保护通过。 |
| WR-02 终态拒绝 / P1 | 连续历史终态CLAIM 404被当作协议错误，第5条使整个SDK FATAL | 最小首红实际第5条熔断；CLAIM 404按正常不可领取返回，不重试、不累计客户端故障，401/403、400/422及REPORT合同保留。18项重试测试及真实平台连续6次终态重投通过，SDK保持健康、业务副作用仍1。以上消费修复当前专项共43个不同用例组合通过，不与历史111机械累加。 |
| WR-02 后续 / P1 | unsafe handler 抛 Error 后同 invocation 的延迟重投再入 | B196真实Kafka/HTTP/独立业务PG已复现：Error退栈后、reclaim之前重投原持久消息，同owner/invocation再次执行，业务效果实测2次（预期1次）。首红1failure/0error/0skip已保全。最小修复复用SDK既有fatal状态：原Error传播及finally清理保留，后续新派发RETRY_LATER、健康状态失败，等待宿主重启；该真实首红复验通过；另以包可见真实工作入口复现已入队工作项晚于Error开始仍再入，补取得占位后的fatal复查。最终59单测+3真实Kafka/HTTP/业务PG用例共62项通过，0跳过：迟到重投效果1且健康失败；unsafe回收UNKNOWN且6次终态重投效果仍1；safe新SDK恢复执行2次而效果1。该Java SDK实例的Error迟到执行缺陷本地关闭，不外推多进程共享workerCode或其它语言。早先Console编译阻断保留历史记录，所属会话修复后本次完整命令exit0。 |

审批关联回归命令：

```sh
./mvnw -o -ntp -fae -pl batch-orchestrator,batch-console-api -am '-Dtest=DefaultConsoleApprovalApplicationServiceTest,WorkflowRunReplayPreviewServiceTest,OrchestratorApprovalClientTest,ConsoleApprovalRetryDispatchTest,ConsoleRecoveryApprovalBindingTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

首绿与完整关联回归均 exit 0。真实PG新类首轮4例中3过1error，唯一错误是Mockito stubbing经过Spring MANDATORY事务代理；改为对真实AOP目标spy设置故障后，只复跑回滚方法1/1，exit0，形成4个不同用例组合通过。未把两轮累计当5例，未为夹具错误调整生产事务。调度两例 RAM 首红及一例 PG 首红均 exit 1、合计 3 failures / 0 errors / 0 skipped；不是环境或编译失败。未应用局部 Quartz 生产草稿：新 JobData 可能影响旧等待，必须先完成原版本/等待所有权闭合，不能靠三个测试变绿替代设计不变量。

## 本轮已收齐的其他功能证据

- **R34 前缀清理**：真实 DELETE API 首红为 `fk_trigger_request_workflow_definition`；按准确身份保存并删除受管请求和 Outbox 依赖，事务范围内对同数值 ID 的其它租户/业务日及冷表邻居先拒绝、零副作用。Repository 8、XML 4、Controller 7，共 **19 个不同用例组合通过**；末轮只重跑一个受退役表预期影响的失败方法，未重复完整类。真实中途异常回滚、邻居保留及重复清理为零已覆盖。按租户 ID 的真实闭包已在 B193-C02 单独补齐并验证，见上表；前缀历史19例不与本轮21例累加为40。
- **归档诊断成本 Probe**：真实同构数据 3 组交替 A/B，3 批分别为 10/10/9，重复诊断从每组三次降为一次；18 个真实提交事务、174 个实例，热/冷完整实例 JSON 对照及六个专属租户清理为零通过。`ArchiveDiagnosticCadenceProbeIT` **1/1**；原 `ArchiveColdStorageIntegrationTest` 17/17 仅在源码哈希未变范围复用，完整运行树/回滚由原测试证明，不由小 Probe 代替。小样本耗时有噪声，不宣称生产吞吐、SLA 或 EX-03 容量通过。

## 调度修复的所有权约束

B193两个真实Orchestrator/PostgreSQL首红已在B195修复并定向验证：容量等待恢复保留首次version3及冻结图；等待payload整体认证加密，同请求变参不得替换或拒绝原合法等待。复用现有等待表与SecretPayloadProtector，无迁移/明文兼容；普通启动仍实时检查enabled及已有misfire例外，Replay保留原审批合同并复核live operation/targetRequest。首红2例复验通过，关联Replay/等待单测、身份与租户PG、容量恢复/结算竞争共87项通过，合计89个不同用例、0跳过。生产与证据SHA见执行日志B195。**B197容量接纳原子性已本地关闭**：真实PG首红确认准入与等待事务ID不同；仅对V203活跃名额唯一键使用ON CONFLICT，返回0即在原prepare事务持久等待，去除回滚后另起事务defer及内存中转快照。13项定向与82项关联共95个不同用例通过，含真实同请求/不同请求并发、40001后原请求重投、等待写后失败全回滚、其它唯一键/FK错误不吞、Replay/密文首值/租户隔离/日结竞争。完整Trigger readiness/截止时间移交仍开放；未提交prepare在Kafka有限重试耗尽后的持久恢复仅有静态缺口证据，需纳入后续所有权闭环验证，不能据手工重投推断自动恢复已验收。

遵循 ADR-048 ALIGN-5：Trigger 预检；Orchestrator 最终裁决、持久等待并原子启动。修复必须先核对现有等待队列、冻结运行图与版本记录能承接的事实；当前 Orchestrator 尚未完整承接组合 Workflow 的 `depends_on_job_code`，普通 Console 创建原有 v1 历史快照缺口已补：创建聚合与首次快照复用同一事务、持久默认值和现有schema，首红2例后首绿及关联4类28项通过；真实HTTP/PG两例通过，验证实际版本写入后异常使定义/节点/边/版本四表全回滚，原创建意图可重试；测试沿共享bypass及可信头，不宣称JWT/Redis幂等验收。这项必需接线不代表等待所有权已收口，不能简单移除 Trigger gate、删除版本校验或退回当前图。

Quartz 回调只是可重建唤醒。不得为修一个丢回调问题另建一套 Trigger 持久 LaunchRequest/敏感参数快照，或同时由两端推进同一等待。原 fire、业务日、已选版本与终态围栏必须保持；等待不得先占住 Workflow 容量名额导致上游饥饿。局部 Quartz 草稿仅供整合，不是完整修复。

## 验证命令与证据边界

SDK 首红及首绿：

```sh
./mvnw -o -ntp -pl sdk/java/core -am '-Dtest=TaskDispatcherTest#shouldExecuteOnlyOnce_whenSameOwnerReceivesConcurrentDuplicate,LeaseRenewalSchedulerTest#shouldStopRenewingTask_whenHandlerThrowsError' -Dsurefire.failIfNoSpecifiedTests=false test
```

SDK 受影响回归：

```sh
./mvnw -o -ntp -pl sdk/java/core -am '-Dtest=TaskDispatcherTest,LeaseRenewalSchedulerTest,TaskDispatcherP0HardeningTest,TaskDispatcherClaimRetryTest,TaskDispatcherAutoWrapTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

本轮原始报告、读取文件 SHA、SDK 日志/XML 保存于 `tmp/log-artifacts/coordination/20261023-final-closure/remaining-conventions/b192-functional/`；目录名沿用既有协调位置，本批内容属于功能复核。首红审批/SDK 日志和 XML 在同级 `b192-approval-sdk-before-fix/`；R34 与 Probe 见执行日志 B191 后续记录及 `b191-r34-first-complete-results/`、`b191-archive-probe-passed/`。

B192已将R03因三个调度首红重新开放；当前正式23项已验证、13项部分验证，R25另补当前本地功能关闭条件。B172 的本地 36/36 与正式 24 已验证、12 部分验证是历史冻结构建结果；不能覆盖本报告尚未关闭的当前源码功能缺陷。当前运行 JAR 尚未重打包，未新增全量 SIM、CI 或目标环境验收证据。每项完成后按实际命令追加结果，不将尚未执行或被中断视为通过。
