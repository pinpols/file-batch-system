# Spring AI 2.1.0 与 Spring Boot 4.2.0 升级评估

日期为 2026-11-23，基线为 `main` 的 `df5a87567`。范围是后端 Maven reactor、Console AI 模型调用与部署回归。当前版本为 Boot 4.1.2、Spring AI 2.0.2、Java 21，见[根 POM](../../pom.xml)。

## 决策建议

目标组合已正式发布，Spring AI 2.1 官方基于 Boot 4.2，JDK 21 符合要求。值得开独立升级分支验证，生产切换取决于下表验收。有近期 Responses API、会话压缩或工具审批需求时可优先安排这次升级。若当前目标是修复提示词日志等安全问题，先用 2.0.3 补丁，减少 Boot 升级涉及的回归面。Console 已有会话持久化与授权边界，版本迁移期间继续使用现有会话和审批流程。

## 风险与验收

| 优先级 | 影响与证据 | 升级验收 |
|---|---|---|
| 高 | [Console AI 配置](../../batch-console-api/src/main/resources/application.yml) 支持自定义 Anthropic 地址；2.1 使用 Anthropic Java SDK 3.8.0 和 API 2026-10-21，认证改为 Bearer，采样参数会被丢弃 | 对实际启用的官方或代理端点验收请求头、API 版本、模型参数、401/429、普通与流式响应，测试工具回合及取消 |
| 高 | Console AI 应用服务把 `Usage.getPromptTokens()` 交给费用估算和租户预算结算。2.0.3 起 Anthropic 输入用量包含缓存读取与写入，而当前配置仅区分普通输入与输出费率 | 用未缓存、缓存写入、缓存命中样本校验用量、供应商费率、月预算台账及无用量回退，确认统计和费用口径 |
| 高 | 根 POM 的 Boot parent 覆盖 Console API、Trigger、Orchestrator、Worker 和 Java SDK 模块。Jackson 2/3、Tomcat、Netty 等已有安全覆盖，OTel Logback appender 版本明确按旧 Boot 的 OTel API 选择 | 对比两套 effective POM、依赖树与 SBOM，逐个决定覆盖的保留条件，确认 MyBatis、数据库驱动、Micrometer/OTel 以及 JDBC、Redis、Kafka 链路。重新生成 SBOM 并走供应链门禁 |
| 中 | Boot 4.2 移除了 4.0 已废弃的 API，调整 RabbitMQ starter 及反向代理头行为。已在仓库 POM、Java、YAML 中检索旧 AMQP starter 和旧转发头键，未命中。部署环境注入配置仍未核对 | Reactor 编译并启动各应用；用实际入口核对认证回跳、转发头和 SSE 连接 |
| 中 | Spring AI 2.1 改用有序消息片段，Anthropic 思考内容和工具结果的响应表示改变 | 验收同步与 SSE 的可见文本、取消后资源释放、工具回合、结束事件和审计。现有 Console API 响应须保持兼容 |

## 实施与发布条件

1. 在单独变更中替换 Boot parent 与 Spring AI BOM，先核对 Maven 解析后的依赖版本。暂缓引入 Responses API、会话存储和新工具执行能力。
2. 运行全 reactor 编译与单测，并使用 Console AI 现有配置、服务、知识库和成本测试验证。真实模型契约按项目约定使用受控环境验证。
3. 执行项目 Full Gate，覆盖 PostgreSQL/Flyway、Redis、Kafka 和批量主链。预发布验证触发、派发、Worker 领取与回报，以及 Console 登录、AI 流式响应和用量。安排回退到原制品并确认数据与消息兼容。

## 本轮证据

2026-11-23 静态评估：已执行 `git status --short --branch`、`git rev-parse --short HEAD`，以及对 POM、配置和调用点的 `rg`/源码检查。第一次获取 Maven Central 时 DNS 解析失败（退出码 6），本地 Maven 缓存也没有目标 Boot parent 和 Spring AI BOM；当日晚些时候网络恢复，目标版本已实际解析，静态结论由下一节取代。

## 实施进展（2026-11-23，独立工作树）

- 分支/基线：工作树 `spring-boot-4-2-upgrade-2026-11-23`，分支 `chore/spring-boot-4.2-spring-ai-2.1`，基线 `origin/main` 的 `df5a87567`。本次只做框架迁移，保留现有会话、审批和 Console API 契约。
- 版本变更：根 POM 的 Boot parent `4.1.2 → 4.2.0`，`spring-ai.version 2.0.2 → 2.1.0`。三个目标 POM 在 Maven Central 均返回 200，`help:effective-pom` 解析成功。
- 覆盖项核对（Boot 4.2.0 管理版本 vs 仓库原固定值）：netty `4.2.17 → 4.2.20.Final`、Jackson 3 `3.1.7 → 3.1.8`、Jackson 2 `2.21.7 → 2.21.8`、Tomcat `11.0.25 → 11.0.27`、Mockito `5.20.0 → 5.24.0`，原先的覆盖全部旧于 Boot 管理值，已移除对应属性并保留 CVE 历史注释。
- OTel：Boot 4.2 管理 API `1.68.0`；`opentelemetry-instrumentation` `2.28.0-alpha`（API 1.62）无法编译，升到 `2.34.0-alpha`（POM 依赖 API 1.68.0）。该 artifact 不在 Boot BOM 内，仍需显式固定。
- 迁移代码：`OpenTelemetryLogbackBridge` 改用 `setMdcAttributesIncluded(...)` 与 `setKeyValuePairAttributes(IncludeExclude)`；`OtelTracePropagation` 删除已废弃的 `TextMapGetter.keys` 覆写（默认实现返回空，W3C 提取只走 `get`/`getAll`）；测试改用 `DefaultMeterObservationHandler.builder(...)`。已核对 appender 2.34 字节码：`mdcAttributesIncluded` 与旧 `captureMdcAttributes` 共用 `AttributeSelectors.split` 解析 CSV，空 `IncludeExclude` 走 `isEmpty()` 分支并被当作“不采集 key-value pair”，与旧布尔 `false` 等价。
- Spring AI API：`Usage` 在 2.1 仍提供 `getPromptTokens()/getCompletionTokens()`，并新增 `getCacheReadInputTokens()/getCacheWriteInputTokens()`；现有 Console 代码只读前两者，编译无契约破坏。`spring-ai-anthropic 2.1.0` 传递 `anthropic-java-core 3.8.0`（其 POM 依赖 okhttp 4.12.0），仓库 `dependencyManagement` 仍统一为 okhttp 5.5.1；OkHttp 5 与 4.x 源码/二进制兼容，但真实请求链路需在受控 provider 验证时一并确认。
- 配置契约：`spring-ai-autoconfigure-model-anthropic 2.1.0` 仍暴露 `spring.ai.anthropic.api-key` 与 `base-url`，自定义网关地址继续有效；options 不再提供 `temperature`/`top-p`，仓库原本未设置采样参数，无需配置迁移。
- 验证：全 reactor `test-compile` 通过（17 个模块）；`batch-common` 81/81（3 个真实多分片环境用例按门控跳过）、`batch-test-support` 81/81、`batch-trigger` 434/434（含 Quartz JDBC misfire 恢复、完整上下文重启与异步投递集成）、`batch-orchestrator` 2115 个用例（1 个顺序依赖失败已修）、五类 Worker 全部通过（`batch-worker-core/import/export/process/dispatch` 均 0 失败）。
- `batch-console-api` 首轮全模块单独运行 579 个测试类，2 个失败均在 `ConsoleTenantConfigPackageExcelUploadIntegrationTest`；根因是 Boot 4.2 将 JUnit 从 5.x 升到 **6.1.3**、surefire 升到 3.6.1，默认测试方法顺序变化，暴露了该类依赖同类共享租户数据顺序的用例（apply 幂等后 `jobInserted`/`fileTemplateInserted` 为 0）。已按“只断言恰好应用一次（insert 或 update）”改造该类；全模块复跑 **3061 个用例、0 失败、2 跳过、BUILD SUCCESS**。
- 运行时（本机，2026-11-24）：`scripts/local/build-apps.sh` 生成 8 个可执行 jar；`start-all.sh` 后 18080–18087 全部 `/actuator/health` UP，Console 的 `db` 组件 UP；ShedLock 自动装配为 Redis（`RedisLockProvider`），Kafka 消费者正常启动；Flyway 从 schema 387 应用 2 个迁移到 **389**。
- `start-all.sh` 的运行时 WARN 审计在本地模式返回非零：`total=37 expected=13 action_required=2 unknown=22`。主要来源是 Kafka 客户端 `connections.max.idle.ms` 告警（17 条，库级配置提示）、本地 profile 的 JWT/Atomic 双用途守卫、遗留数据导致 `workflow_validator invalid=3`，以及一次 `dispatch-channel-health` 启动期审计（`issues=[]`）。这些不是启动失败；随后用主工作树的 Boot 4.1 栈复跑同一脚本，同样返回非零（`total=20 expected=13 action_required=2 unknown=5`），确认该审计结果不是 4.2 升级引起的。
- 两个首次失败已定性，均不是升级引起的产品回归：
  - `ConsolePushSenderTransportTest`（429/503/400 分类）单独 13/13 通过，套件高负载下 `future.get(5s)` 抖动导致 `UNKNOWN`，属瞬时环境失败。
  - `SensorProbeTransactionExecutorIntegrationTest` 单方法运行时 12/12 通过；失败类内其它用例遗留 `RUNNING` 且 `sensor_next_probe_at` 为空的 WAIT 行，同批扫描共用 mock 的 `attempts` 计数，其它节点的探测把目标节点的 `next_probe_at` 推到 +60s，使断言依赖执行顺序。已把 mock 改为只对目标节点计数/推进（`batch-orchestrator` 测试代码），整类复跑 12/12 通过；未改生产代码。
- 主链：`coverage-harness.sh all --operator admin --allow-local-bypass` **36/36 场景 SUCCESS**（ta/tb/tc 的 IMPORT/EXPORT/PROCESS/ATOMIC/DISPATCH/BUNDLE_* 与 workflow SERIAL/BRANCH/WAIT），覆盖触发→派发→Worker 领取/回报；`after` 清理完成（SFTP 6 文件、MinIO 12 对象）。
- 调度矩阵：单跑 `trigger-matrix` 时 ta/tb/tc 三个 `trigger:API` 场景 SUCCESS，随后在 `POST /api/console/workflow-definitions/20/trigger` 收到 HTTP 409 并停止。该定义在同一环境的 36 场景运行中已有活跃/近期运行，属单活跃运行与幂等准入的正常拒绝，不是升级回归；要完整跑矩阵需先静止该工作流的在途运行。

## 子集构建注意事项

- `scripts/local/run-tests.sh` 会先执行 `clean install -DskipTests -pl <13 个模块>`，不包含根 POM。若本机 `~/.m2` 中的 `batch-platform:1.0.0` 仍是旧父 POM，子模块会解析到旧的 `opentelemetry-instrumentation.version=2.28.0-alpha`，与 Boot 4.2 管理的 API 1.68 混用，报 `NoClassDefFoundError: io/opentelemetry/api/internal/ConfigUtil`（2.28 的 `opentelemetry-api-incubator 1.62.0-alpha` 引用该内部类）。升级根 POM 后须先在仓库根执行一次 `mvn install -DskipTests` 更新父 POM，再运行测试脚本；这是升级分支的复现前提，不是产品代码缺陷。执行根安装后 `dependency:tree` 已确认解析为 `opentelemetry-logback-appender 2.34.0-alpha` + `opentelemetry-api 1.68.0` + `opentelemetry-api-incubator 1.68.0-alpha`。

## 仍需外部条件或后续变更
- **Anthropic 网关**：`spring-ai-anthropic 2.1.0` 传递 `anthropic-java-core 3.8.0`。反编译该 jar 可确认存在 `Authorization` + `Bearer ` 头、`anthropic-version` 头与 API 版本字面量 `2026-10-21`（同时保留 `x-api-key` 兼容路径）；Bearer 认证、API 版本、采样参数丢弃与流式/工具回合仍必须用实际启用的端点验证，本机没有可用凭据，未执行。
- **缓存计费**：`Usage` 已暴露缓存读/写 token，但 `ConsoleAiCostService` 仍只按输入/输出费率结算；是否需要拆费率取决于供应商实际账单，属于后续结论，本批未改费率模型。
- **运行时回归**：本地全栈已验证（8 应用启动、Flyway 389、Redis ShedLock、Kafka 消费、36/36 覆盖场景）；目标环境的四类应用、PostgreSQL/Flyway、Redis、Kafka 与主链仍需按发布流程复核。
- **供应链**：依赖变化后必须在同一分支重生成 `docs/compliance/sbom.json` 并通过 SBOM 与许可证门禁。

- [Spring AI 2.1.0 与 2.0.3 GA](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/)
- [Spring AI 2.1 迁移说明](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/upgrading/spring-ai-2-1.html)
- [Spring AI 2.0.3 迁移说明](https://docs.spring.io/spring-ai/reference/2.1-SNAPSHOT/upgrading/spring-ai-2-0.html)
- [Spring Boot 4.2.0 发布说明](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.2-Release-Notes)
