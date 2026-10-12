# 静态分析工程化治理实施计划

> 状态：阶段 0–2 本地实施完成；阶段 3 全量扫描已运行，但 Quality Gate 未通过；阶段 4 存量问题按风险继续分诊。PR CI 与 Sonar Gate 分开记录，不能互相替代。本文记录实施范围和验收状态，工具事实源与例外规则统一维护在[静态分析治理标准](../standards/static-analysis-governance.md)。

## 目标与边界

把 SonarQube、CodeQL、SpotBugs、PMD、Spotless、IDE inspection 和 suppression 的职责、运行入口、CI 触发、结果解释及遗留债务收敛到可追踪的工程流程。目标是保证新增风险可见、例外不扩散、报告有证据，而不是追求历史告警归零。

不新增扫描平台或外部质量数据库，不把 Sonar 变成不稳定的 PR 必需门禁，不为清零 Code Smell 机械重构，不把 IDE 检查误报当作 Sonar issue，也不把本地扫描结果等同 CI/生产验收。

## 阶段与验收

| 阶段 | 工作 | 验收条件 | 状态 |
|---|---|---|---|
| 0. 事实盘点 | 对照 workflows、Runbook、脚本、Ruleset 现状，厘清每项工具真实触发和阻断属性 | 矩阵来源可追到 workflow/脚本；跳过、报告型和本地模式有明确边界 | ✅ 完成，见治理标准“工具与门禁矩阵” |
| 1. 统一入口与文档 | 建立 Sonar SOP、工具矩阵、扫描脚本索引、例外登记规范和 TODO Master 入口 | 新旧文档相互链接；不得存在第二套易漂移的工具清单 | ✅ 完成，文档门禁通过 |
| 2. 抑制项可守护 | 登记生产 Java/YAML 抑制项；覆盖 `@SuppressWarnings`、`@SuppressFBWarnings`、行级 `//NOSONAR` 和 YAML `noinspection`；精确基线及高风险作用域规则 | 正反例单测通过；新增/扩展未登记项失败；生产基线和退出条件有所有者说明 | ✅ 完成，当前检查器盘点 266 个出现项；这是计数守护，不表示全部逐项重新批准 |
| 3. 安全优先复核与全量 Sonar | 复核高风险安全抑制，修复确认的安全问题；运行全量 clean test、JaCoCo、Sonar 分析及真实 Quality Gate | 安全复核有代码/测试证据；Sonar 报告记录 SHA、时间、覆盖率和 Gate；构建失败不继续伪报扫描成功 | 🟡 安全五规则复核、全量构建/测试/JaCoCo/Sonar 已完成；`029a75540aee0d9bf7a149bf8049a905ea9390c3` 的 Quality Gate **失败**：新代码违规 108（20 Bug、88 Code Smell），新代码覆盖率 85.4%、重复率 0%；完整扫描报告与门禁诊断见 Runbook。当前 PR 的后续修正尚需 CI 验证 |
| 4. 存量例外与债务分批治理 | 按 `java-suppression-registry.md` 的优先级复核剩余例外；按 Sonar 风险类型/模块分诊，先修漏洞与可复现 Bug，再选择性处理 Code Smell | 每批有规则范围、前后计数、测试证据、保留理由和触发复审条件；不以全仓零抑制/零 Smell 作为退出条件 | ⏳ 持续项；不能把阶段 2 基线通过说成 266 项都已逐项审计 |
| 5. 持续运行 | 按现有 CI 路径运行；PR 使用适用的确定性硬门禁和按需本地 Sonar 变更行报告 | workflow 触发、Ruleset required 状态及报告型扫描的口径清楚；每次治理变更更新证据 | ✅ 流程已定义；后续随 CI/规则变化维护 |

## 本轮实施记录

- 新增统一标准：[静态分析治理标准](../standards/static-analysis-governance.md)，覆盖工具职责、CI 触发边界、抑制项基线和存量债务语义。
- 扩展 `scripts/ci/check-java-suppression-registry.py`：完整扫描生产 Java/YAML，解析真实 `//NOSONAR` 注释而忽略字符串/文本块；将 SpotBugs suppression 纳入登记；拒绝类级 Sonar `S2077`。
- 收窄四处动态 SQL 类级 `S2077` 抑制到实际 sink 方法，并补充作用域守护测试。
- 修正 Excel 上传临时工作簿创建位置，使用 owner-only 临时文件工具，降低 Sonar `S5443` 风险。
- 强化 Trigger 内部密钥 CSRF 忽略 matcher 的缺失/错误/正确密钥测试。
- 修复 Kafka 故障注入 IT 的时钟竞态夹具：数据库查询以 PostgreSQL `current_timestamp` 为准，测试数据用 `next_publish_at = NULL` 表示立即可投递。
- 本地 `test-compile + PMD + Spotless`、抑制检查器 14 项单测、相关真实依赖定向测试和文档门禁已通过。第一次全量 `clean test` 因上述时钟竞态失败；修正后定向 IT 2/2 通过。随后在 SHA `029a75540aee0d9bf7a149bf8049a905ea9390c3` 上全量 `clean test`、14 份 JaCoCo XML 和 Sonar 分析均成功，Sonar 任务 `57ba30ee-b250-4b9e-8d35-a1d6cb581dc3`；但 Quality Gate 因 `new_violations=108` 返回 `ERROR`，不是扫描失败也不是通过。新代码覆盖率 85.4%、重复率 0%；全局 OPEN 基线为 1,365，其中 Bug 49、Vulnerability 0、Code Smell 1,316、Security Hotspot 0。完整本地报告位于 `reports/sonar/2026-10-12_15-01-53/`（忽略文件，不提交）。
- 108 条新代码违规初步分诊：20 条 Bug 候选中，16 条 `S2259` 经代码路径核对属于 `EmptyChecks` 空值契约未被 Sonar 符号执行识别，2 条 `S2637` 是 `@NullMarked` 下真实缺少 nullable 声明，2 条 `S5841` 是可能空集合上的弱断言。88 条 Code Smell 尚未逐条审完；不得将其整体称为误报或作为机械清零目标。随后已修正 `SecretMasking` 的 nullable 契约并强化 2 个断言，相关定向测试通过；该修正后的完整 Sonar Gate 尚未复跑。
- PR #1226 head `90bc48569b4613694d917fce13e6338731df865f` 的 `pr-gate` 与 CodeQL 曾全部通过；PR Sonar 不运行（该 Sonar 工作流仅在 main 触发）。之后文档更正提交 `029a75540aee0d9bf7a149bf8049a905ea9390c3` 也通过 PR-Gate 和 CodeQL。当前后续代码修正的 CI 结果以 PR 页面最新 head 为准，不能据历史检查推断。
- PR #1226 已合并到 main，merge commit `fa5f53fc75b6adf678ade16945afd47568947ee`；其 required PR checks 全部通过。合并后，本地修复了 SecretMasking 空值处理、Console 权限主体空值、配置包缺省映射/投影、未使用成员和冗余条件等问题，并增加了对应回归测试。跨模块定向 Maven 测试 65 项通过，Spotless 检查通过；以该 main SHA 为基线的最终增量 Sonar 任务 `0c75fdf3-139b-4d97-948c-063412deeb90` 覆盖 14 个 Java 文件，变更行 OPEN issue `0`、待审 hotspot `0`（报告 `reports/sonar/2026-10-12_16-42-45/`）。这些是本地后续工作树证据，尚无该修复分支的 GitHub CI 结果，也未重跑全量 Quality Gate。
- 后续修复的标准本地增量扫描已完成：命令 `bash scripts/dev/sonar-scan.sh --incremental --base-ref origin/main`，Sonar 任务 `bddf2967-f663-4cad-9557-2838db9db5c4` 成功，报告 `reports/sonar/2026-10-12_17-03-28/`。以 `origin/main` SHA `fa5f53fc75b6adf678ade16945afd47568947ee` 为基线，14 个 Java 文件的变更行 OPEN issue `0`、待审 hotspot `0`。该增量项目全局 OPEN issue 从 1,342 降为 1,324；30 天新代码周期 OPEN issue 从 86 降为 68，`java:S2259` 从 18 降为 0。当前扫描未运行 Quality Gate，且本修复 PR 的 GitHub CI 尚待执行。

## 持续追踪

- TODO Master 只保留本计划入口和当前尚未闭环的总体状态；规则级明细、扫描快照和审计理由只维护在本计划引用的治理标准/报告中。
- 阶段 3 的全量质量门禁快照仍为失败（`new_violations=112`）；合并 #1226 后的后续修复分支已重新执行标准增量扫描，14 个 Java 文件新增/修改行上为 0 个 OPEN issue、0 个待审 hotspot（任务 `bddf2967-f663-4cad-9557-2838db9db5c4`，报告 `reports/sonar/2026-10-12_17-03-28/`）。增量项目全局 OPEN issue 为 1,324，30 天新代码周期仍有 68 条待分诊 issue。该结果不替代该分支 PR CI，也不把全量 Gate 改报为通过；若要更新全量 Gate，仍须以新 SHA 重跑全量流程。
- suppression 数量下降并非验收目标；删除 suppression 必须伴随可验证的代码改正或分析器结论变化。新例外只有在局部失败/误报边界明确且有测试证据时才可进入基线。
- 阶段 4 按风险批次持续完成，不将安全复核已完成外推为全部历史 Code Smell 或抑制项均已审完。
