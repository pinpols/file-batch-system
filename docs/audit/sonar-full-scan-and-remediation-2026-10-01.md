# SonarQube 全量扫描与治理记录

日期：2026-10-01

分支：`codex/update-table-ai-usage-degradation-sonar-docs`

代码基线：`054698bfed028f5f3115f57b9b93c43d76bdc682`（本地工作树包含未提交治理改动）

## 扫描范围与执行

- 执行全量 Maven reactor Sonar 分析，排除 `batch-e2e-tests`；Sonar 日志确认完整分析，未跳过未修改 Java 文件。
- 使用本地 SonarQube 固定镜像 digest：`sonarqube@sha256:d4899d380ad9d7b63ebaa751e047f5a4f064f8902cdf7c1a3c3c96f7d71600ed`。
- 全量 `./mvnw clean test org.jacoco:jacoco-maven-plugin:0.8.15:report --projects '!batch-e2e-tests'`：`BUILD SUCCESS`，生成 14 份 JaCoCo XML。
- 修复后运行受影响模块定向测试与 JaCoCo 报告刷新：32 项通过，0 失败、0 错误；随后执行 `bash scripts/dev/sonar-scan.sh --full --skip-build` 完成最终全量静态分析。
- 本机 Sonar 分析任务完成；最终本地报告位于 `reports/sonar/2026-10-01_16-02-13/`（本地产物，不提交）。

## 最终结果

| 指标 | 结果 |
|---|---:|
| Bug | 0 |
| Vulnerability | 0 |
| Security Hotspot | 0 |
| OPEN Code Smell | 1,224 |
| 全局覆盖率 | 65.6% |
| 重复率 | 1.1% |
| 技术债估算 | 185 小时 4 分钟 |

全量报告统计 1,234 条已跟踪 issue，其中 1,224 条仍为 OPEN、10 条 CLOSED。首次扫描发现的 7 条 Bug 均已关闭，包含 1 条 BLOCKER。最终存量 OPEN issue 全部属于 Code Smell：CRITICAL 339、MAJOR 580、MINOR 275、INFO 30。

本机 Quality Gate API 返回 `OK`，但响应同时带有 `ignoredConditions=true`；因此该结果不作为严格 Quality Gate 通过证据。全局覆盖率 65.6% 是当前全仓指标，不等同于新代码覆盖率门槛判定。

## 已治理问题

- `TestKafkaContainers.create()` 明确声明容器生命周期由调用方负责；共享集成测试基类与 Testcontainers 扩展分别管理关闭。
- 对缓存指标注册表、降级响应对象和 AI 提示分类使用 Sonar 可识别的显式空值分支。
- AI 费率计算入口增加非空前置条件，避免空费率进入成本计算。
- 读副本故障测试把 `createStatement()` 移出异常断言 lambda，避免将多个可能抛出同类受检异常的调用混在断言中。
- AI 未配置错误仍使用原国际化 key `error.ai.assistant_not_configured`；仅将重复字面量提取为常量，没有修改任何翻译资源或接口返回语义。

最终定向验证命令覆盖 `ReadReplicaRoutingDataSourceTest`、`ConsoleAiCostServiceTest`、`ConsoleAiPromptGuardTest`，合计 32 项通过。此前另行验证的 `ConsoleQueryCacheServiceTest` 和 `DegradedResponseHeadersTest` 也通过。

## 存量治理顺序

本轮不对 1,224 条历史 Code Smell 做机械批量重构。最高频规则如下，后续按生产代码优先、按模块分批治理并配套测试：

| 规则 | 数量 | 建议处置 |
|---|---:|---|
| `java:S1192` 重复字符串 | 275 | 先处理状态/契约键等语义常量；测试数据和局部文案避免无意义抽常量 |
| `java:S5778` 测试异常断言调用 | 197 | 拆分准备步骤与断言 lambda，避免测试含糊 |
| `java:S5853` 正则表达式风险 | 88 | 优先人工复核可控输入和灾难性回溯风险，再决定改写 |
| `java:S135` 循环控制流 | 68 | 评估是否影响可读性，按需抽取小方法 |
| `java:S3776` 认知复杂度 | 54 | 复杂状态/业务逻辑先补行为测试，再作局部重构 |
| `java:S2925` 测试线程休眠 | 40 | 优先改为条件等待或可控时钟；避免单纯为消除告警引入不稳定同步 |

## 证据边界

- 这是一份本地静态分析快照，不代表外部安全认证、渗透测试或生产环境风险为零。
- `.github/workflows/sonar-gate.yml` 当前默认关闭且不属于 required checks；本次本地运行不能替代未来 PR/CI 上的 Sonar 结果。
- Sonar 增量模式仍会分析完整 Maven 项目并导出变更行问题，不等于只扫描变更文件。
- 版本化 Sonar 仪表板与 issue 生命周期可能在后续分析中变化；引用本记录时需同时核对目标 SHA 和报告日期。
