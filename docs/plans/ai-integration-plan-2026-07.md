# 引入 AI 的计划（2026-07）

> **认知前提(先纠正一个误解)**:fbs 不是「要从零引入 AI」——它已经有一套**建成、接了真 LLM、端到端可用**的 Console AI 助手,只是默认关闭、前端未对接。本计划因此不是「引入」,而是「把已建成但关闭的助手推向生产可用,并有序扩展接入点」。
>
> 全程守已固化的边界:**AI 只是 Console 控制面辅助,不接触 orchestrator/worker/trigger 主链,不直接写库,输出仅建议/草稿**(见 `docs/design/multi-tenant-and-security.md §11`、`ConsoleAiProperties` 类注释)。

## 0. 现状基线（已建成的部分）

`batch-console-api` 内已有完整的 AI 助手链路(默认 `batch.console.ai.enabled=false`):

| 组件 | 现状 |
|---|---|
| REST 入口 | `POST /api/console/ai/chat`(`ConsoleAiController`,`@Idempotent`) |
| 编排 | `DefaultConsoleAiApplicationService`(365 行):授权 → prompt 门禁 → RAG → 绑工具 → 调模型 → 附引用 → 审计 |
| 模型 | Spring AI `ChatClient`,默认 Anthropic `claude-opus-4-8`,OpenAI 回退;开了没配 key 则启动 fail-fast |
| RAG | 内置 5 个 `ai-knowledge/*.md`,OpenAI embedding,**进程内余弦检索**(刻意不引向量库),topK=4 |
| 只读工具 | 3 个 `@Tool`:getJobInstance / getJobExecutionLogs / listRecentFailedJobInstances;租户 id 构造时绑定、**不暴露给模型**,强制当前租户只读 |
| 授权 | 白名单(默认 admin;ADMIN/AUDITOR),匿名 FORBIDDEN |
| prompt 门禁 | 关键词规则:blockedKeywords(密钥/系统提示词…)→ REJECTED_SAFETY;domainKeywords 判 in-scope + 分类 |
| 审计 | 表 `console_ai_audit_log`(V11):**原文不落库**,只存 SHA-256 hash + 512 字符 preview;**拒绝也记** |
| 缺口 | 前端一环缺位(本仓无 FE 模块,未见 chat UI);Spring AI 是里程碑版 `2.0.0-M3` |

**即:后端契约与安全骨架已就绪,离「生产可用」差的是前端、稳定性收口、上线判定——不是差核心功能。**

---

## 1. 定位与边界

- **AI 是辅助,不是自动化执行器**。它读(只读工具 + RAG),它建议(根因/草稿),它不代替人做决策、不直接改状态。
- **不碰主链**。orchestrator/worker/trigger 无任何 LLM 依赖,也不应有。AI 全部在 console 控制面。
- **不直接写库**。任何「AI 生成的配置/规则」都以草稿形式交给人,经现有审批/保存闭环落库。
- **多租户内闭环**。工具的租户 id 构造时绑定、不给模型,body/header 租户不一致直接 FORBIDDEN——这条已实现,扩展时保持。
- **参考 ops-agent 的就绪判定**(独立项目,经验可借):可上「受控只读试生产」,不可无人值守/多租户自治;真 provider 契约测试需 GH secrets、进 nightly CI。

---

## 2. 分阶段计划

### Phase 1 — 把已建成的助手推向「生产可用」(收口,非新建)

优先级最高:核心已在,只差让它真能上。

1. **前端对接**:补 console 前端的聊天入口(后端契约 `POST /api/console/ai/chat` 已定),含流式输出、引用来源展示、门禁拒绝的友好提示。这是当前最大缺口。
2. **依赖稳定化**:Spring AI `2.0.0-M3` 是里程碑版,跟踪其 GA,升到稳定版再谈生产。
3. **provider 契约测试进 CI**:对 Anthropic/OpenAI 的真实调用契约做 nightly 集成测试(需 GH secrets;本地不可验)——防 SDK/API 漂移。参考 agent-ctl 的经验:provider 契约是最容易静默漂移的一环。
4. **成本 / 限流 / 降级**:每租户/每用户的调用预算与限流(复用现有 bucket4j 限流基建);模型不可用/超时的降级(RAG 已有「embedding 不可用降级为仅 primer」的先例,聊天侧也要有明确降级)。
5. **上线判定**:先「受控只读试生产」——白名单用户、单租户、只读工具、审计全开;不无人值守、不多租户放开。

### Phase 2 — 扩展接入点(按现有确定性能力的天然缺口排序)

每个都符合「读+建议、不写库、不碰主链」的边界,且都有现成的确定性基座可挂:

1. **失败根因诊断增强**(收益最高):`FailureClassifier` 只给粗类(TIMEOUT/DATA_QUALITY/INFRA/CONFIG/UNKNOWN),大量落 UNKNOWN。AI 已有 `getJobExecutionLogs` 工具,天然可做「读日志 → 给根因 + 修复建议」。把诊断纳入助手的一个引导场景即可,几乎零新基建。
2. **集群 stuck 诊断解读**:`ConsoleClusterDiagnosticService.diagnose()` 输出结构化 Map(ShedLock 租约/worker 一致性/outbox 健康),AI 做自然语言解读 + 处置建议。新增一个只读工具接入。
3. **数据质量规则草稿**:DQ 的 `ruleType/expression/threshold` 目前手写;AI 从表结构/样例数据建议规则**草稿**(不直接写库,交人保存)。符合已固化的「输出草稿」边界。
4. **告警分诊 / 降噪**:当前 SLA→webhook 无优先级判断;AI 对 AlertEvent 做分诊/去重/摘要(与 Alertmanager 接通后,AI 可做告警摘要层)。
5. **配置向导**:`ConsoleAiProperties` 注释已明示「输入=元数据+脱敏日志+配置草稿,输出=建议/草稿」——配置向导是既定方向,把 job/channel/template 配置的自然语言辅助做成引导。
6. **RAG 语料扩展**:目前只 5 个内置 md;把 `docs/`(设计/runbook/ADR)挂进 `rag.locations`(配置项已支持追加目录),覆盖面立刻扩大,零代码。

### Phase 3 — 谨慎/后置（明确划出以防扩张）

- **HITL 写操作**:若未来要让 AI 触发操作(如「重试这批失败分区」),必须走**现有审批闭环 + dry-run + 人确认**,AI 只发起建议,不自执行。这是 ops-agent 的 HITL 模式(cancel/savepoint 带 dry-run + prod 双闸)的思路,可借,但优先级低。
- **不做**:无人值守自动执行、AI 直接写库/改状态、跨租户 AI、把 AI 变成通用运维自治体——这些越过边界。

---

## 3. 安全 / 成本 / 运维（贯穿所有阶段）

- **prompt 门禁**:现为关键词规则(blockedKeywords/domainKeywords)。够用作第一道闸,但关键词易绕;评估是否需要更强的 in-scope 判定(注意:别为此引入另一个模型调用把成本翻倍)。
- **审计**:已做对(hash+preview 不落原文、拒绝也记)。扩展工具时保持每次工具调用可审计。
- **成本可观测**:token 用量、每租户成本、被门禁拒绝率——接入指标(和这两轮做的可观测同一套 Micrometer),让「AI 花了多少、挡了多少」可见。
- **provider 契约与降级**:真 provider 进 CI(需 secrets);任一 provider 故障走回退链;全故障降级为「仅 RAG primer」或明确不可用提示,不 fail-closed 成 500。
- **数据脱敏**:进模型的日志/元数据要脱敏(密钥/PII);审计不落原文的原则延伸到所有新接入点。

---

## 4. 有意不做的边界

- ❌ AI 接触 orchestrator/worker/trigger 主链;
- ❌ AI 直接写库 / 改任务状态 / 自执行高危操作;
- ❌ 引入独立向量库把 RAG 重型化(进程内余弦 + 内置语料对当前规模够用,YAGNI);
- ❌ 把 fbs 变成「AI 运维平台」——AI 是控制面的一个辅助面,不是新产品方向;
- ❌ 与独立的 ops-agent/agent-ctl 强耦合(它们是独立项目,fbs 内 AI 自带 Spring AI,零耦合,保持)。

---

## 5. 一句话方向

**fbs 的 AI 不缺骨架,缺的是「让已建成的助手真能上、并沿现有确定性能力有序扩展」——读+建议、不写库、不碰主链、租户内闭环。** 先把 Phase 1 收口到能「受控只读试生产」,再按 Phase 2 的天然缺口一个个挂,而不是另起一个 AI 平台。

---

*依据:2026-07 对 origin/main 的 AI 现状勘查(console AI 助手已接 Spring AI + RAG + 只读工具 + 审计,默认关闭),及既有安全/边界设计文档。*
