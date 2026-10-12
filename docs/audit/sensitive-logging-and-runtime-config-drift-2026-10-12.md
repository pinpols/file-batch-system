# 敏感日志与运行配置漂移核查（2026-10-12）

## 结论

- **敏感日志**：确认若干日志会写入调用方可控文本或凭据型 endpoint。当前分支已对这些明确路径做字段级收敛并补回归测试；这不等于全仓所有动态 JSON/自由文本出口均已完成数据分类。
- **Loki 投递**：代码和 Collector 配置存在 OTLP Logs → Loki 路径，但本次没有启动观测栈并查询真实日志，故投递成功、标签和留存均未验收。
- **配置语义漂移**：静态代码确认 Pipeline、文件模板和分发渠道在执行阶段读取当前配置。Pipeline 已有实例定义 ID，但重试入口仍先按 jobCode 读取最新版并加载其步骤。尚未构造真实实例证明已经发生错误结果，因此记录为可复现的设计风险，不扩建通用配置快照/注册表。

## 日志核查与处理

| 路径 | 风险 | 本分支处理 |
|---|---|---|
| `ConsoleTelemetryController` | 请求中的事件名、页面值和 MDC 字段由调用方控制；内容可含敏感信息并放大日志 | 只记录白名单事件类型、名称长度、页面是否存在和属性数量；不把原始值放入 MDC 或日志 |
| `OpenTelemetryLogbackBridge` | `captureMdcAttributes="*"` 会把任意 MDC 键值送入 OTLP；Collector 按属性键名脱敏无法识别任意消息正文 | 改为显式可信字段列表，排除页面、用户输入和 XFF 等字段 |
| ECS 控制台日志 | Spring Boot ECS 格式会将 MDC 写入 JSON；仅过滤 OTel 不能限制 stdout 中的非可信字段 | 共用日志配置排除原始请求租户头、XFF、操作者 ID、前端用户/页面/事件字段 |
| `ConsolePushSender` | Web Push endpoint 是能力型 URL，包含订阅密钥 | 日志保留状态和订阅记录 ID，不输出 endpoint |
| `HttpPollSensorPolicy`、Alertmanager emit/silence | 配置 URL 可能包含 userinfo 或 query 凭据 | 不再记录原始 URL；Alertmanager 日志只输出经脱敏 endpoint |
| Java SDK `PlatformHttpClient` | DEBUG 曾记录完整错误响应正文；异常文本包含完整 URL | DEBUG 仅记录状态码和正文长度；异常保留请求路径，不输出响应正文和完整 URL |
| Self-hosted SDK 示例 | 将租户参数、行数据或 dispatch 请求/响应写入示例日志，容易被复制到生产 | 示例改为不输出动态载荷，仅保留安全摘要或移除数据日志 |
| Console 下游错误边界 | 原始响应正文写入 warn 日志；解析失败时原文作为 API 文案返回；下游 5xx 的内部 message 可能含连接/凭据细节 | 只记录 HTTP 状态和正文长度；无法解析或 5xx 使用业务码默认文案；合法下游 4xx CommonResponse 文案继续按既有契约透传 |

Collector 当前使用 `allow_all_keys` 并按键名模式拦截属性。该规则只能作为第二道防线，不能替代业务代码对消息正文、URL 和动态字段的限制。当前还未通过本地 Docker 观测栈确认 Console API 日志能否在 Loki 查询到；参见 `docs/runbook/observability-stack.md` 的运行验收步骤。

## 配置漂移核查

| 配置对象 | 执行时行为 | 判定 |
|---|---|---|
| 作业参数 | 实例保存 `params_snapshot`，任务载荷由实例执行参数生成 | 该类参数已有实例级快照，不需要另建一套 |
| Pipeline | Worker 按租户和 jobCode 取最新定义及步骤；`pipeline_instance` 保存定义 ID，但同一 job instance 重试会再次先取最新版 | 已确认读取路径存在漂移窗口。更新服务还会原位删除并重建同一定义 ID 下的步骤，故只复用定义 ID不足以冻结步骤语义 |
| 文件模板 | Worker 在模板消费点按模板代码选当前 enabled 版本 | 配置更新可影响尚未消费或重试的实例；本次未证明已出现错误业务结果 |
| 分发渠道 | Worker 在发送前按租户和渠道代码读取当前 endpoint、认证与配置 | 该动态读取有助于密钥轮换/紧急改 endpoint；静态 pin 可能妨碍轮换和撤销，需先决定执行中轮换语义 |

### 复杂度与建议

若要求每个已创建任务永久使用创建时的 Pipeline、模板、渠道和凭据，必须统一定义冻结时点，并覆盖排队、重试、子任务、工作流、配置删除/禁用、渠道凭据轮换、旧任务兼容和保留清理。把完整配置复制进 task payload 会扩大消息并复制敏感值；新增泛化版本注册表也超出当前证据支持的必要范围。

当前建议不做通用快照基础设施。后续只有在业务明确“实例配置何时冻结”并能复现具体对象的错误语义后，按对象做最小版本引用或拒绝变更保护。渠道凭据要单独定义轮换和撤销规则，不能与 Pipeline/模板版本简单同一处理。

## 验收边界

- 本地定向测试通过：`OpenTelemetryLogbackBridgeTest` 2 项、`ConsoleTelemetryControllerTest` 5 项、`ConsolePushSenderTest` 5 项、`HttpPollSensorPolicyTest` 9 项、`AlertmanagerEmitPublisherTest` 2 项、`PlatformHttpClientTest` 11 项（1 项跳过）。命令分别为 `./mvnw -pl batch-common,batch-console-api,batch-orchestrator,sdk/java/core -am -Dtest=OpenTelemetryLogbackBridgeTest,ConsoleTelemetryControllerTest,ConsolePushSenderTest,AlertmanagerEmitPublisherTest,HttpPollSensorPolicyTest,PlatformHttpClientTest -Dsurefire.failIfNoSpecifiedTests=false test` 和 `./mvnw -pl batch-console-api -am -Dtest=ConsolePushSenderTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- `./mvnw -q spotless:check`、Ruby YAML 解析、`python3 scripts/ci/check-docs-structure.py`、`python3 scripts/ci/check-changelog-sync.py --base origin/main` 和 `git diff --check` 均通过（记录的是此前敏感日志批次的验证，不覆盖本轮新增 Console 异常处理修改）。
- 本轮新增 Console 下游错误边界测试覆盖无法解析响应体和已解析 5xx 的安全默认文案；测试执行结果单独记录在本次交付，不据此宣称导出/诊断全量分级完成。
- 本次没有执行真实 Loki 查询、生产 profile 配置核实、全量 API/导出响应数据分级，也没有执行配置变更与运行实例并发的真实 PostgreSQL 端到端复现。
- 因而敏感日志改动是针对已确认路径的修正，不代表全仓敏感数据审计完成；配置漂移结论是源代码行为证据，不代表已观测到生产数据损坏。
