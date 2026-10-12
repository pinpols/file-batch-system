# 静态分析工程化治理实施计划

> 状态：阶段 0–2 本地实施完成；阶段 3 的安全复核和 PR CI 已完成，但本地全量 Sonar 未完成；阶段 4 存量复核持续推进。本文记录实施范围和验收状态，工具事实源与例外规则统一维护在[静态分析治理标准](../standards/static-analysis-governance.md)。

## 目标与边界

把 SonarQube、CodeQL、SpotBugs、PMD、Spotless、IDE inspection 和 suppression 的职责、运行入口、CI 触发、结果解释及遗留债务收敛到可追踪的工程流程。目标是保证新增风险可见、例外不扩散、报告有证据，而不是追求历史告警归零。

不新增扫描平台或外部质量数据库，不把 Sonar 变成不稳定的 PR 必需门禁，不为清零 Code Smell 机械重构，不把 IDE 检查误报当作 Sonar issue，也不把本地扫描结果等同 CI/生产验收。

## 阶段与验收

| 阶段 | 工作 | 验收条件 | 状态 |
|---|---|---|---|
| 0. 事实盘点 | 对照 workflows、Runbook、脚本、Ruleset 现状，厘清每项工具真实触发和阻断属性 | 矩阵来源可追到 workflow/脚本；跳过、报告型和本地模式有明确边界 | ✅ 完成，见治理标准“工具与门禁矩阵” |
| 1. 统一入口与文档 | 建立 Sonar SOP、工具矩阵、扫描脚本索引、例外登记规范和 TODO Master 入口 | 新旧文档相互链接；不得存在第二套易漂移的工具清单 | ✅ 完成，文档门禁通过 |
| 2. 抑制项可守护 | 登记生产 Java/YAML 抑制项；覆盖 `@SuppressWarnings`、`@SuppressFBWarnings`、行级 `//NOSONAR` 和 YAML `noinspection`；精确基线及高风险作用域规则 | 正反例单测通过；新增/扩展未登记项失败；生产基线和退出条件有所有者说明 | ✅ 完成，当前检查器盘点 266 个出现项；这是计数守护，不表示全部逐项重新批准 |
| 3. 安全优先复核与全量 Sonar | 复核高风险安全抑制，修复确认的安全问题；运行全量 clean test、JaCoCo、Sonar 分析及真实 Quality Gate | 安全复核有代码/测试证据；Sonar 报告记录 SHA、时间、覆盖率和 Gate；构建失败不继续伪报扫描成功 | 🟡 安全五规则复核及 PR CI 完成；本地全量流水线按请求中断于 Orchestrator 测试阶段，Sonar 分析未启动，无全量 Sonar/Quality Gate 结果 |
| 4. 存量例外与债务分批治理 | 按 `java-suppression-registry.md` 的优先级复核剩余例外；按 Sonar 风险类型/模块分诊，先修漏洞与可复现 Bug，再选择性处理 Code Smell | 每批有规则范围、前后计数、测试证据、保留理由和触发复审条件；不以全仓零抑制/零 Smell 作为退出条件 | ⏳ 持续项；不能把阶段 2 基线通过说成 266 项都已逐项审计 |
| 5. 持续运行 | 按现有 CI 路径运行；PR 使用适用的确定性硬门禁和按需本地 Sonar 变更行报告 | workflow 触发、Ruleset required 状态及报告型扫描的口径清楚；每次治理变更更新证据 | ✅ 流程已定义；后续随 CI/规则变化维护 |

## 本轮实施记录

- 新增统一标准：[静态分析治理标准](../standards/static-analysis-governance.md)，覆盖工具职责、CI 触发边界、抑制项基线和存量债务语义。
- 扩展 `scripts/ci/check-java-suppression-registry.py`：完整扫描生产 Java/YAML，解析真实 `//NOSONAR` 注释而忽略字符串/文本块；将 SpotBugs suppression 纳入登记；拒绝类级 Sonar `S2077`。
- 收窄四处动态 SQL 类级 `S2077` 抑制到实际 sink 方法，并补充作用域守护测试。
- 修正 Excel 上传临时工作簿创建位置，使用 owner-only 临时文件工具，降低 Sonar `S5443` 风险。
- 强化 Trigger 内部密钥 CSRF 忽略 matcher 的缺失/错误/正确密钥测试。
- 修复 Kafka 故障注入 IT 的时钟竞态夹具：数据库查询以 PostgreSQL `current_timestamp` 为准，测试数据用 `next_publish_at = NULL` 表示立即可投递。
- 本地 `test-compile + PMD + Spotless`、抑制检查器 14 项单测、相关真实依赖定向测试和文档门禁已通过。第一次全量 `clean test` 因上述时钟竞态失败；修正后定向 IT 2/2 通过。随后应请求中断本地全量 Sonar 流水线，未完成 `clean test`，未生成 JaCoCo/Sonar 分析或 Quality Gate 结果。
- PR #1226 head `90bc48569b4613694d917fce13e6338731df865f` 的 `pr-gate` 与 CodeQL 检查全部通过；PR Sonar 不运行（该 Sonar 工作流仅在 main 触发），因此不能据此声称全量 Sonar Gate 通过。

## 持续追踪

- TODO Master 只保留本计划入口和当前尚未闭环的总体状态；规则级明细、扫描快照和审计理由只维护在本计划引用的治理标准/报告中。
- 后续如需完成阶段 3 全量 Sonar，运行全量 clean test、JaCoCo 和 Sonar，并补录实际扫描 SHA、扫描任务、问题类型/数量、覆盖率与 Quality Gate；不得用 PR CI 或 CodeQL 结果替代 Sonar 结果。
- suppression 数量下降并非验收目标；删除 suppression 必须伴随可验证的代码改正或分析器结论变化。新例外只有在局部失败/误报边界明确且有测试证据时才可进入基线。
- 阶段 4 按风险批次持续完成，不将安全复核已完成外推为全部历史 Code Smell 或抑制项均已审完。
