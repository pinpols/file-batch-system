# 静态分析与例外治理

本文是后端仓库静态分析工具、扫描触发、抑制项和债务处理的统一策略入口。各工具的具体命令仍由对应 Runbook 和 workflow 维护；不得把某一工具的结果解释成全仓质量或安全结论。

阶段目标与逐阶段验收见[静态分析治理实施计划](../plans/static-analysis-governance-plan-2026-10-12.md)；本标准只保留规则与证据策略，避免计划和规范重复维护细节。

## 工具与门禁矩阵

| 工具/检查 | 实际职责 | PR | Full Gate / 主干 | 结果边界 |
|---|---|---|---|---|
| Maven 编译、PMD、Spotless | 类型/规则检查与格式 | PR 静态检查阻断 | Full Gate 阻断 | 不覆盖数据流安全分析或运行时正确性 |
| Java 抑制登记 | 阻止生产代码出现未登记、扩散的抑制项 | PR 阻断 | Full Gate 阻断 | 是例外治理，不判断扫描器结论是否正确 |
| CodeQL | Java 污点与安全查询 | PR 分析；纯文档按范围逻辑跳过 | main push、每周扫描 | PR 使用 no-build；主干/定时使用 security-extended 与编译分析 |
| SonarQube Community Build | Bug、漏洞候选、Code Smell、覆盖率与重复率 | **不做 Sonar PR 分支分析**；Java PR 可本地生成变更行报告 | main 相关变更、符合条件的夜间扫描、手动 main；独立于 Full Gate | 临时实例每次从空库开始，门禁针对单次运行，无跨运行 issue 趋势；报告 artifact 保留 7 天 |
| SpotBugs | 当前 profile 仅聚焦 NPE/冗余空检查候选 | 无自动 PR 门禁 | 手动 `-P spotbugs` 报告 | `failOnError=false`，是报告型/软门禁，不得称为阻断扫描 |
| IntelliJ/Qodana 检查 | IDE inspection 与资源配置解析提示 | 未发现仓库 Qodana workflow | 本地 IDE/Qodana 使用 | 对应 `noinspection` 不是 Sonar issue，也不代表 CI 验证通过 |
| 依赖、镜像、IaC 安全扫描 | 依赖漏洞、镜像和配置风险 | 由 PR/Full Gate 相应安全 job 执行 | 由各自 workflow 执行 | 按 `docs/runbook/security-scan.md` 分别报告，不与 Sonar 合并口径 |

CI 的实际覆盖以 `.github/workflows/` 为准，Ruleset 的 required 状态以 GitHub 当前 Ruleset 为准。跳过、未触发、仅本地执行、报告型扫描均不能描述为 PR/Full Gate 通过。Sonar 本地增量模式仍分析完整 Maven reactor，再按 Git 变更行过滤报告，不是只编译变更文件。

### 现行入口

- 扫描命令、Sonar 质量门和报告：[`docs/runbook/sonar.md`](../runbook/sonar.md)
- 全仓门禁与 job 分工：[`docs/runbook/ci.md`](../runbook/ci.md)
- Java 生产代码抑制项规则：[`java-suppression-registry.md`](java-suppression-registry.md)
- 本地静态检查命令与脚本登记：[`scripts/ci/README.md`](../../scripts/ci/README.md)
- Sonar 逐条债务标注：`./scripts/dev/annotate-sonar-report.py`
- Sonar 本地扫描 token 经 `SONAR_TOKEN` 环境变量传给 Maven，避免暴露在命令行参数中，并在正常或异常退出时撤销；分析服务容器单独管理并按开发需要保留。

## 抑制项基线

基于 `origin/main` 快照 `51bf78e70` 的原始登记基线包含 **258 个生产抑制出现项、24 个抑制标识、194 个路径-标识组合**。常见项包括 `unchecked` 88、`java:S2583` 28、`java:S2259` 27、`PMD.ExcessiveParameterList` 38。这个总数混合了 Sonar、PMD、Java 编译器和 IDE inspection；不得把它说成 Sonar 告警数，也不以把它降到零作为目标。

本轮把 4 个类级 `java:S2077` 抑制收窄到具体 SQL sink 方法，规则登记出现数由 7 增至 15；当前工作树合计 **266 个出现项**。这是同一批例外的作用域拆分，不代表新增了 8 个独立安全例外。`java-suppression-baseline.tsv` 记录当前精确出现数；检查器现在阻止新增类级 `S2077`。

当前生产范围内已发现 8 个 YAML IDE `noinspection`；未发现 Java `//NOSONAR` 或 `@SuppressFBWarnings` 用法。门禁现扫描生产 Java 的 `@SuppressWarnings`、`@SuppressFBWarnings`、Sonar 行级 `//NOSONAR`，以及模块 `src/main/resources` 下所有 YAML 的 `noinspection`。测试 fixture 的编译器抑制仍不进入生产基线。

登记文件 `scripts/ci/java-suppression-baseline.tsv` 按“路径 + 标识 + 数量”阻止例外增长；该形式易审查，但无法识别同一文件中抑制位置的替换，也不能单独证明每个存量用法的理由仍成立。`docs/standards/java-suppression-registry.md` 记录规则级理由和退出条件；增量变更仍需人工审查具体代码位置。

### 新增或修改抑制项

1. 先确认不是修正代码、类型、控制流或扫描配置即可消除的问题；不得只为清零 issue 添加抑制。
2. 选最窄的目标和作用域，优先局部变量/方法；禁止新加文件级、包级或类级宽泛抑制来绕过整改。
3. 注释需说明具体误报/契约原因与移除条件。安全规则还需指出相邻的正向校验或安全测试。
4. Sonar、PMD、SpotBugs、IDE inspection 和 Java 编译器抑制分别登记，不得互相冒充。`//NOSONAR` 默认禁止；必须改为规则级抑制，确无替代时单独评审。
5. 更新基线前检查源码 diff、`docs/standards/java-suppression-registry.md` 中规则归属，并运行检查器及其测试。基线更新不是自动批准。
6. 修改安全抑制时，按受影响模块运行测试和对应静态分析；无法执行的工具要明确列为未验证。

### 安全敏感抑制复核记录（2026-10-12）

本轮按路径复核了 `S1313`、`S2068`、`S2077`、`S3330`、`S4502`：未发现可据此判定的保护缺失；确认并收窄了 4 处类级 `S2077` 作用域。

| 规则 | 保护边界与核对证据 | 结论 |
|---|---|---|
| `S1313` | `HttpExecutorProperties` 中的 IP/metadata 主机是 SSRF 拒绝项；`HttpTaskExecutorIpBlockTest` 覆盖回环、私网、链路本地、IPv4-mapped IPv6、公网正例，`HttpTaskExecutorTest` 覆盖 metadata 请求拒绝和重定向不跟随 | 保留局部配置抑制；未替代安全校验 |
| `S2068` | `ConsoleDefaultPasswordGuard` 对出厂口令做 profile 感知校验；`ConsoleDefaultPasswordGuardTest` 覆盖 prod fail-fast、非 prod 告警、改密放行和空账号集 | 保留常量抑制；常量由启动期安全守卫消费 |
| `S2077` | 7 个类中的 15 个方法级抑制只覆盖动态 SQL sink；schema/表/列/过程标识符在拼接前按白名单、格式或解析规则验证，数据值走 JDBC 参数绑定。对应测试包括 `SqlTemplateExportSqlValidatorTest`、`GenericJdbcMappedImportLoadPluginTest`、`JdbcMappedImportCompensatorIntegrationTest`、Atomic SQL/StoredProc hardening tests 与 dry-run 验证 | 保留 sink 方法级抑制；禁止类级范围。检查器正反例测试守住该范围 |
| `S3330` / `S4502` | Console 的 CSRF cookie 必须对前端脚本可读以实现 double-submit；真实 `ConsoleCsrfCookieIntegrationTest` 验证写请求缺 header 被拒、带匹配 header 才通过。Trigger `TriggerSecurityFilterTest` 验证 CSRF 忽略 matcher 只接受正确内部密钥（另覆盖显式 bypass），过滤器测试覆盖密钥缺失/错误拒绝、匹配放行和 actuator 豁免 | 保留安全链方法级抑制；保护逻辑与负向路径均有测试 |

这轮是安全优先级复核，不等同于对全部 266 个出现项逐项审计。其余空值/可达性、类型契约、PMD、编译器及 IDE inspection 抑制按模块分批复核；不得因为计数守护通过而视为逐项理由已重新验证。

### 复查优先级

1. 安全敏感抑制：`S1313`、`S2068`、`S2077`、`S3330`、`S4502`。逐项验证保护逻辑仍存在且测试覆盖正/负路径；`S2077` 必须限定在 SQL sink 方法，禁止类级抑制。
2. 分析器空值/可达性误报：`S2259`、`S2583`、`S2589`。检查类级作用域能否缩小，以及当前分析器版本是否已识别对应控制流；不要求机械移除。
3. 稳定契约抑制：`S112`、`S6218`、PMD 参数规则等。仅在契约变化或替代实现能降低误用时重评。
4. `unchecked`、`rawtypes` 等边界转换：核对动态数据来源和消费端校验；不为了数量好看而把真实动态边界改成不真实的静态类型。

## Sonar 债务口径

Sonar issue 是候选，不是缺陷判决；`SKIP_FP`、`DEFERRED` 等标注是分诊结论，不等于源码 suppression 或告警已关闭。先确认漏洞、可复现 Bug、安全热点和新代码问题，再按规则/模块处理存量 Code Smell。每份扫描快照必须记录 commit、时间、扫描命令、测试/coverage 状态、真实 Quality Gate 返回及未覆盖范围。

2026-10-12 对 `51bf78e70` 的全量报告曾记录 1,173 个 OPEN issues（12 Bug、1 Vulnerability、1,160 Code Smell，覆盖率 68.9%）。唯一 Vulnerability 是 `java:S5443`：Excel 上传临时文件落在公共系统临时根目录；本轮已改用 owner-only `PrivateTempFiles` 并增加成功/失败路径测试。`./scripts/dev/sonar-scan.sh --incremental --base-ref origin/main` 的全 reactor 扫描运行超过 17 分钟未产出最终报告，已中断，不能算全量扫描通过。随后对同一工作树内容执行单文件范围 Sonar 分析（临时项目 `file-batch-system-console-review-20261012`、配置 `Batch Platform Sonar Way`、只纳入 `DefaultTenantConfigPackageExcelService.java`）；分析任务 `63939472-6d92-4f2a-8550-d52f98eccc0b` 为 `SUCCESS`，服务端查询该文件的 `java:S5443` OPEN 数为 0。此结果只验证指定文件和规则，不代表全 reactor 分析或 Quality Gate 通过。历史全量快照不代表当前最新 main；后续报告以最新 main 的实际扫描和 Quality Gate 校验为准。旧的带日期分析留作历史快照，不应继续作为当前待办源。

## 分阶段完成状态

| 阶段 | 交付 | 状态/验收 |
|---|---|---|
| A. 事实源收敛 | 本文矩阵、Sonar SOP 与脚本索引一致 | 已落实；由 `check-docs-structure.py` 和人工核对 workflow 维护 |
| B. 抑制形式覆盖 | 扩展现有注册检查器覆盖生产 `NOSONAR`、SpotBugs 注解和全部生产 YAML IDE inspection | 已落实；测试覆盖正反例，生产基线未增加现有抑制 |
| C. 存量例外复核 | 按上方优先级逐项核验安全抑制与宽作用域误报 | 安全敏感五规则已完成本轮复核；其余 246 个当前出现项仍需按规则/模块排期复核，不把计数基线当逐项审计 |
| D. Sonar 债务处置 | 最新 main 快照中确认漏洞/高风险 issue，按规则分批修复 | S5443 临时文件问题已修并完成单文件规则复核；当前分支全 reactor Sonar 与最新 main 扫描仍未完成，Code Smell 继续分批治理 |
| E. PR Sonar 策略 | 评估是否需要变更行 PR 级 Sonar 检查 | 暂不新增全量 PR Sonar；只有明确的 PR 阻断需求和可持续运行成本后另行决策 |

当前 PR/Full Gate 对确定性规则继续硬阻断；复杂误报、作用域是否合理和是否值得重构保留人工判断。该治理不另建质量平台，不引入外部质量数据库，也不改变现有模块架构。
