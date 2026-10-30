# 后端代码规约与仓库卫生复扫（2026-10-26）

## 范围与基线

- 时间：2026-10-26 17:51 CST；工作树 `fix/wdi-current`，HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`，比较基线 `origin/main` merge-base `ee01e97515b04fd167ed17977535ec5cab8572f8`。
- 扫描时工作树有 1440 个暂存路径、923 个未暂存路径、83 个未跟踪路径。结果属于这次动态工作树快照；先前[门禁整改记录](local-hooks-and-convention-remediation-2026-10-26.md)的全绿结论不覆盖后续增量。
- 本轮使用 `project-engineering-review` 总控、`code-review-and-gates` 的非 CI 可读性清单，并按实际触及的测试、门禁、脚本和文档分别读取 `backend-testing-governance`、`ci-governance`、`script-sql-governance`、`documentation-governance`。复扫 Java 形式规则、测试约定、依赖边界、仓库卫生与格式；人工抽查新增查询、文件配置投影、通知回执、Worker 执行、Trigger 服务及其测试。未开展 25 个工作包的逐项业务语义、数据库与真实环境全量审计，也未运行产品测试或完整 CI。
- 本轮仅登记当前问题及责任边界；这些位置含未跟踪实现或同时暂存/未暂存的在途改动，不覆盖其源码、不调整守卫、基线或豁免。

## 当前可操作发现

| ID / 优先级 | 规约与当前证据 | 整改及关闭条件 |
|---|---|---|
| CONV-20261026-01 / P2 | `./mvnw -q pmd:check` 退出 1，`batch-orchestrator/target/pmd.xml` 报 4 处参数约束：`PersistentWaitReadService.list` 第 21 行、`PersistentWaitReadInternalController.list` 第 21 行、`PersistentWaitReadMapper.selectPage` 第 12 行、`PersistentWaitReadMapper.count` 第 22 行。Mapper 的 5 个及以上参数违反 `coding-conventions.md` §1 的明确边界；Service/Controller 各 8 个参数。 | 按查询职责使用具名 Query/Param，Controller 保留现有 HTTP query 参数及默认值；同步 MyBatis XML 绑定、Service 调用和相关测试。PMD 复跑 0 违规，Mapper XML 解析及真实 PostgreSQL 分页/计数、租户隔离验证通过后关闭。不能仅加 suppression 或宽泛上下文袋。 |
| CONV-20261026-02 / P2 | `python3 scripts/ci/check-empty-checks.py` 退出 1，当前新增生产行 18 处：`DefaultConsoleFileApplicationService` 9、`DefaultFileGovernanceService` 2、`MaintenanceAlertPublisher` 1、`MaintenanceAlertWindow` 5、`MaintenanceHttpObservationConvention` 1。包括 null、集合空值及短路判断；源码核对未发现必须保留原生比较的特殊语义。 | 在原求值位置采用 `EmptyChecks` 的对应 overload；可合并 `null || isEmpty` 为其 null-safe 集合判断。核对短路、异常和下游调用次数不变；复跑守卫，并执行受影响的文件上传/维护窗口定向测试。不要仅为消警报添加 `empty-check: allow`。 |
| CONV-20261026-03 / P3 | `./mvnw -q spotless:check` 退出 1，首次失败模块报告的文件为 `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleFixedProjectionMappingIntegrationTest.java`；新增 PROCESS 摘要映射测试中的 `jdbc.update` 与 AssertJ 链布局不符。后续模块未执行。 | 对该文件按仓库 Spotless 格式化，重跑全 reactor `spotless:check`；格式改动与测试语义分开核对。 |
| CONV-20261026-04 / P2，规范冲突 | `bash scripts/ci/check-required-java-docs.sh` 退出 1：`BatchKafkaNamespaceAutoConfiguration.java` 缺顶层 Javadoc；但 `docs/coding-conventions.md` §19.2 明确写“Spring 配置类不需要 Javadoc”，而 `scripts/ci/README.md` 的该守卫说明要求所有 Spring 配置类型都写。 | 先裁定配置类注释的权威口径，再同步规范、脚本说明及守卫正反例；若保留强制说明，为该类补解释 Kafka Topic 与 Redis key 共享命名空间的中文职责注释。不能仅凭门禁红把当前文件定性为违反书面规约，也不能直接放宽守卫求绿。 |
| CONV-20261026-05 / P3，人工规约 | 新增 `JobExecutionQueryService.java` 第 30–32 行无类级 Javadoc；该类在第 46–124 行承担租户内查询、冻结阶段解释、运行进度合并与响应构造，属于 `coding-conventions.md` §19.2 要求说明职责/边界的复杂 Service。第 75–93 行的 18 位置参数响应构造另有字段错位阅读风险，属于可读性建议，不单凭参数个数定性缺陷。 | 补充职责与冻结/实时事实边界的 WHY 注释；评估为响应构造使用具名映射或局部中间值，保持 JSON 字段、求值和查询次数不变，定向测试验证。 |
| CONV-20261026-06 / P2，人工测试规约 | 新增 `PipelineProgressQueryServiceTest.java` 第 20–22 行与 `JobExecutionQueryServiceTest.java` 第 24–27 行共 6 个字段直接 `mock(...)` 初始化，违反 `coding-conventions.md` §14.2 的新单测声明式 Mock 强约束；`check-test-conventions.py` 的当前范围只管测试名称和 `@DisplayName`，不会拦此项。 | 使用 `MockitoExtension` 的 `@Mock`/`@InjectMocks` 或邻近等价模式，不扩大共享 fixture；两个完整测试类执行通过，并核对默认 strictness。 |
| CONV-20261026-07 / P3，人工测试质量 | `PipelineProgressQueryServiceTest.java` 第 30–37 行与 `JobExecutionQueryServiceTest.java` 第 94–95 行只断言 `RuntimeException`，不能保护明确的 `INVALID_ARGUMENT` / `NOT_FOUND`、错误键契约。 | 对 `BizException` 的 `code` 与稳定 `messageKey` 作独立预期断言；避免依赖当前语言文案。相关负例通过后关闭。 |
| CONV-20261026-08 / P2，验证缺口 | 新增 `PersistentWaitReadMapperXmlTest.java` 第 16–33 行只解析 XML 并检查 statement/resultMap 注册；`PersistentWaitReadServiceTest` 使用 Mock。未找到针对新增 `selectPage`/`count` SQL 参数绑定、分页顺序、RLS/租户条件的真实 PostgreSQL Mapper 集成测试。 | 参数对象整改时增加真实 Mapper 集成测试，覆盖两个等待来源、可选筛选、分页/计数一致性与跨租拒读；XML 解析测试可保留，但不能代替 SQL 运行证据。 |

上述 8 项分属**明确规约违反、规范/守卫冲突、可读性建议和测试验证缺口**；本轮没有证据把它们定性为业务 Bug 或数据损坏。`PersistentWaitRead*` 目前是未跟踪实现，不以旧门禁快照覆盖。

## 非 CI 规约人工核对

候选定位先运行 `python3 scripts/ci/report-java-readability-inventory.py`：当前 2923 个生产 Java、75 个 public Map 契约候选、37 个超过 700 行的类。这些是**词法定位数量，不是违规数**。进一步阅读本轮新建/改动的 `JobExecutionQueryService`、`PipelineProgressQueryService`、`PersistentWaitReadService`/Controller/Mapper、`PipelineProgressReadService`、`AlertNotificationReceiptService`、`MaintenanceAlertWindow`、两个固定文件配置 View、`DefaultTaskExecutionWrapper` 与 `DefaultTriggerService` 的相关片段和调用方；检查职责、数据来源、参数内聚、条件/嵌套、注释 WHY 与测试真实性。

在 26 个未跟踪 Java 测试文件中，定位到上述 2 个文件的 6 个字段直建 Mock，并逐行核对了相关负例；这不是对 1410 个已跟踪测试文件的全量人工判定。`FileChannelConfigQueryView.toMap`、`FileTemplateConfigQueryView.toMap` 的调用方落在配置包/Excel 的 Map 消费边界，属于可保留的转换；`AlertNotificationReceiptService` 的薄 Service 含独立事务和租户绑定，`PipelineProgressReadService` 含只读快照/RLS 责任，不能按“只转调”机械删除。Worker 租约与中断的类注释说明了不能把 Future 取消等同于线程退出的约束。对这些抽样点未另外认定模式误用；其余候选尚未逐项裁定。

## 治理面与验证矩阵

| 控制域 / 责任 | 本轮命令及结果 | 证据边界 |
|---|---|---|
| Java 参数/格式 / 生产与测试源码 | `./mvnw -q pmd:check` 失败 4 处；`./mvnw -q spotless:check` 失败 1 文件 | Maven 到首次失败模块为止；后续模块未宣称通过 |
| 判空 / 新增生产代码 | `python3 scripts/ci/check-empty-checks.py` 失败 18 处 | 默认检查 HEAD 至当前工作树，含未跟踪生产 Java |
| 类型注释 / Spring 配置 | `bash scripts/ci/check-required-java-docs.sh` 失败 1 类 | 当前守卫口径，未据旧报告覆盖 |
| 可读性/日志/注入 | `check-java-readability.py`、`check-java-logging-governance.py`、`check-java-lombok-injection.py --base-ref origin/main` 均退出 0 | 只证明各脚本自身规则和输入范围 |
| 测试约定与守卫清单 | `check-test-conventions.py --check-baseline docs/governance/test-conventions-baseline.txt` 0/0、`check-java-governance-test-coverage.py` 33 项/11 模块、`check-test-config-sources.py`、`check-no-silent-disabled-tests.sh` 均退出 0 | 源码和基线检查；不等于执行 33 个治理测试或产品测试 |
| 固定契约/字符串/抑制项 | `check-java-contract-governance.py --base-ref origin/main`、`check-java-text-block-style.py`、`check-java-structured-string-concat.py --base-ref origin/main`、`check-java-suppression-registry.py`、`check-mapof-null-values.py`、`check-pipeline-summary-keys.py` 均退出 0 | 增量/静态扫描，摘要键脚本自测通过；不等于运行链路验收 |
| 依赖与仓库卫生 | `check-dependency-boundaries.py`、`check-repository-hygiene.py` 均退出 0 | 不涵盖人工模块职责或所有本机生成目录 |
| 注释语言 / Git 输入 | `check-comment-language.py --staged` 退出 0；当前 `git diff HEAD` 的新增注释经同一解析器命中 0 | `--base-ref origin/main` 另报 14 行，但它读取旧 HEAD 提交范围，不读取已修好的工作树；不能把旧提交诊断算作当前源码仍有 14 处英文注释 |
| 脚本入口/可移植/SQL 配置 | `check-script-governance.py`、`check-shell-linux-portability.py`、`check-sql-config-boundaries.py` 均退出 0 | 最后一项本轮显示 `files=0`，不能据此声称独立 SQL 已逐文件审过；脚本人工核对限于 Javadoc 守卫的输入与失败传播 |

审计文档本轮运行 `python3 scripts/ci/check-docs-structure.py`、`python3 scripts/ci/check-doc-timestamp-policy.py`、`python3 scripts/ci/check-code-doc-references.py`，均退出 0；本轮三个文档的工作树 `git diff --check` 退出 0。格式、PMD 等失败项未记为通过。

## 整改顺序与剩余验证

先裁定 §19.2 与 Javadoc 守卫冲突，同时处理参数对象与 Mapper 绑定、判空和新增测试规约，再处理格式与职责说明；每项在改动所属代码任务内收口，保留当前 staged/unstaged 边界。下一次复扫应先固定源快照，重跑失败守卫，再按涉及模块执行定向测试与完整相关检查。CI、真实数据库、真实 HTTP 和部署验证本轮均未执行。

系统问题台账：本范围未确认新的业务/安全/数据缺陷；与规约问题无交叉映射。后续若真实参数绑定或租户回查测试复现行为问题，应另按 Bug 台账记录，不以 PMD 通过替代。
