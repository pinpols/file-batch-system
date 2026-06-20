# 后端深度扫描审核报告 2026-06-19

> 5 路并行专项深扫(并发与状态机 · 多租隔离 · 安全注入/RCE 隔离 · 资源与错误处理 · console-api 与架构边界),
> 覆盖全 10 个平台模块 + scripts。每条候选发现逐项 `grep`/`Read` 核实,甄别
> **真实活跃缺陷 vs by-design vs 误判**。方法对齐上一轮 `docs/analysis/robustness-deep-scan-2026-06-10.md`。

## 审查基线

- 时间:2026-06-19。
- 基线:`main` @ `05748d7`(fix(console): MustChangePasswordGuard 尊重 bypass-mode #567)。
- 分支:`claude/backend-deep-scan-audit-qwucou`。
- 方法:静态代码审查(并行 5 个只读探查 agent)+ 人工逐条复核。**未运行 IT**;受控修复 2 条经
  人工复核与标准 API 判定,**本机无法编译**(项目 `maven.compiler.release=25`,本环境仅 JDK 21),
  依赖 CI(temurin 25)编译与全量测试兜底。

## 总体结论

后端**架构纪律与安全姿态稳健**,本轮未发现新的上线阻断级(P0)缺陷。

- **架构硬约束全部成立**(console-api agent 逐项核实):worker 未直写 `job_instance`/`workflow_run`/
  `workflow_node_run`;console-api 未直写 `outbox_event`(只读 mapper,运维走 `ConsoleOrchestratorProxyService`);
  读写分离未泄漏到 trigger/orchestrator/worker;无第 4 张同义 outbox 表;无 `pipeline_instance UNION workflow_run`;
  Controller 一律 `CommonResponse<T>`,无 Controller 上 `@Transactional`,无实体直绑(mass-assignment)。
- **batch-worker-atomic RCE 隔离边界硬**(security agent):shell 直 `execve`(非 `sh -c`)+ 命令白名单 +
  `..` 拒绝 + workdir 隔离 + `env.clear()`;SQL/StoredProc 三层闸(dataSource 白名单 + OS-capable 角色门 +
  schema 白名单);HTTP 出口 resolve-then-connect + IP 黑名单 + 禁重定向(对齐 #444/2026-06-10 SSRF 加固);
  无 `enableDefaultTyping`/`ObjectInputStream`/不安全 YAML;加密 AES/GCM + Argon2id。
- **CLAUDE.md 红线静态扫描干净**:无 `ZoneId.systemDefault()` / `Charset.forName("UTF-8")` 业务用法;
  无生产 `@Autowired` field/setter 注入(命中项均为构造器参数或 `*Aware` 框架回调豁免);无 `maven.test.skip`。

实际净化后的**真实缺陷集合很小**,均为低风险、局部、纵深防御类。

## 真实发现与处置

| # | 类别 | 严重度 | 文件:行 | 结论 |
|---|------|--------|---------|------|
| 1 | 数据·精度 | LOW-MED | `ValidationCoercions.java:74` | **本 PR 已修** |
| 2 | API·入参边界 | LOW | `ConsoleNotificationController.java:132` | **本 PR 已修** |
| 3 | 性能·N+1 | MED | `TenantConfigPackageRowProjections.java:105/139/174` | 记录建议(见下) |
| 4 | 多租·纵深防御 | MED | `RetryScheduleMapper.xml` 的 `markRunning/markSuccess/markFailed/resetToWaiting` | 记录建议(见下) |

### #1(已修)decimalValue 经 double 丢精度,range 校验边界误判

`ValidationCoercions.decimalValue` 对 `Number` 入参用 `BigDecimal.valueOf(number.doubleValue())`,
当值为超过 double 53 位尾数精度的 `Long`/`BigInteger`(如大整数金额、或 JSON 配置里的大数 min/max 边界)时,
先经 double 静默丢精度,导致 import 数据质量 range 校验(`RecordRuleEvaluator` 159–168 的实际值与 min/max 边界)
在边界处可能误判通过/拒绝。**修复**:改 `new BigDecimal(number.toString())`(`Long`/`BigInteger` 精确,`Double`
路径与原行为等价)。补 `ValidationCoercionsTest`(原无单测)覆盖大整数精度、BigDecimal 原样、文本/空白/非数字分支。

> 相邻的 `integerValue` 第 49–50 行 `number.intValue()` 同属静默窄化(`Long`→`int` 截断),但仅用于
> length/count 边界(现实取值小),改动可能影响既有行为,**本批不动**,仅记录为潜在项。

### #2(已修)delivery-logs 分页 limit 缺上界注解

`ConsoleNotificationController.deliveryLogs` 的 `limit` 仅 `@Positive` 无上界;service 层
`Math.min(limit, 500)` 已兜底,但入口契约校验缺失。**修复**:加 `@Max(500)`(控制器已 `@Validated`,
对齐 `PageQueryRequest` 既有 `@Max(500)` 约定),fail-fast 且与既有分页策略一致。

### #3(建议,未改)config package 导出 N+1

`TenantConfigPackageRowProjections.collectPipelineSteps/collectWorkflowNodes/collectWorkflowEdges` 在
pipeline/workflow 列表上逐条 `selectByXxxId`,N 条定义触发 1+N 查询。属**管理面低频导出**操作,
非热路径,影响有限;修复需新增批量 mapper 方法(`selectByPipelineDefinitionIds(...)`)+ 调用方分组,
触及 mapper 接口与 XML 多文件。**建议**:租户定义量大时改批量 JOIN 一次取出后内存分组,并补 100+ 定义的回归测试。

### #4(建议,未改)retry_schedule 写更新 id-only 缺 tenant_id(纵深防御盲区)

`RetryScheduleMapper` 的 `markRunning/markSuccess/markFailed/resetToWaiting` 均 `where id = #{id}`,无
`tenant_id` 谓词;`MapperXmlTenantGuardArchTest` 只扫可空 `<if tenantId>` select 守护,**不覆盖 id-only update**。
与 `docs/analysis/robustness-deep-scan-2026-06-10.md` 「威胁模型 C-2/I-3」为**同一已记录盲区**,至今未闭。
实际可利用性低:id 来自调度器内部 `selectByQuery` 结果(WAITING + 到期),非外部可控,属纵深防御而非活跃越权。
**建议**(沿用上轮结论):① 这些 update 补 `and tenant_id = #{tenantId}`(entity 手头有 tenantId,成本可控,
但需改 mapper 签名 + 调用方);② 扩展 `MapperXmlTenantGuardArchTest` 覆盖 id-only update/delete。

## 甄别为 by-design / 误判(记录防重复误报)

并行 agent 报出若干"CRITICAL",经核实**均非新缺陷**,逐条留痕:

- **outbox `insert` 用 `NOT EXISTS` 而非 `ON CONFLICT`**(`OutboxEventMapper.xml:36-54`):`V172` 将
  `outbox_event` 按月分区后全局 `(tenant_id,event_key)` 唯一无法用单一 DB 约束表达,竞态可接受性已在
  `docs/design/partition-idempotency-decision.md` 论证(幂等承重在 `OutboxDomainEventPublisher` 单入口)。**by-design,已记录**。
- **平台调度器全表跨租户扫**(`OutboxPollScheduler`/`selectPending` 可空 tenant 守护、`resetStalePublishing`、
  `countByStatuses`、`selectArchivableIds`、SLA `selectSlaViolation/EscalationCandidates`):orchestrator 是
  **唯一状态主机**,这些是平台级后台任务,设计上就跨租户处理,逐行按 tenant_id 推进,无外部攻击者入口。
  与 2026-06-10「威胁模型 C-1」同结论。**by-design**。
- **trigger 端点从请求体/参数取 tenantId 未校验主体**(`TriggerController`/`TriggerManagementController`):
  trigger API 为**内部运维接口,X-Internal-Secret 网关鉴权,不对外暴露**(controller javadoc 明示),
  调用方为可信内部组件(同 orchestrator `/internal/*`),无 per-tenant 主体可校验。**by-design**。
- **retry_schedule 失败卡 RUNNING**(并发 agent 报):`requeueOneRetry` 为 `REQUIRES_NEW`,
  `markRunning + requeuePartition + markSuccess` 同事务,异常整体回滚 → markRunning 撤销 → 留 WAITING
  下轮重试(R2-P0-2 显式设计)。**误判**。
- **`copyPartitionRange` 的 `BufferedInputStream` 未关 → OOM**(资源 agent 报):wrapper 是局部堆对象,
  方法返回即可被 GC;关闭 wrapper 只会连带关底层流(调用方已关)。**误判**(非泄漏)。
- **job_partition 锁顺序 asc/desc 不一致**:`SKIP LOCKED` 是有意设计;真实死锁已于 `#565`
  (5a9b083 fix(orchestrator): 修 job_partition 锁顺序反转死锁)修复。**已修/by-design**。
- **bypass-mode profile 单点失效 / HTTP 响应体落 JSONB**(security agent 2 个 MEDIUM):前者为部署配置
  (prod 强制 `SPRING_PROFILES_ACTIVE`,代码 fail-secure 正确),后者为既有已记录项(P2-3)。**非本轮新缺陷**。

## 本 PR 改动

- `batch-worker-import/.../quality/ValidationCoercions.java`:`decimalValue` 精度修复。
- `batch-worker-import/.../quality/ValidationCoercionsTest.java`:新增单测(4 例)。
- `batch-console-api/.../notification/web/ConsoleNotificationController.java`:`limit` 加 `@Max(500)`。

## 验证说明

本环境 JDK 21、项目 `maven.compiler.release=25`,**无法本机编译/跑测**;改动均为标准 API(`new BigDecimal(String)`、
jakarta `@Max`、AssertJ/JUnit5),依赖 CI(temurin 25)`pr-gate` + full-ci 编译与全量测试兜底。
