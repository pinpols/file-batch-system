# IDEA / Qodana / Sonar 交叉复核报告

日期：2026-08-12
基线：`main` / `1d8d4f7a5`
范围：Java 主工程、worker、SDK 与公共基础设施

## 结论

本轮截图所示的冗余分支、集合首插、资源结束和 Spring 多候选注入均已逐项复核。确认并已修复的运行时问题只有一项：导出严格编码器的线程本地缓存未在长寿命 worker 线程上清理。其余 Sonar 开放 Bug 规则命中均为分析器无法推导项目统一判空工具后的误报，源码已逐处核对，不存在对应的空指针执行路径。

不能把 Sonar 的 1,096 个开放 Code Smell 当成 1,096 个线上缺陷。主要集中在重复字面量、测试断言 lambda、可空注解识别和复杂度建议；需要按领域语义治理，不能批量机械抽常量或改写控制流。

## 工具结果

| 工具 | 结果 | 证据与限制 |
|---|---|---|
| Maven 定向测试 | 通过 | `batch-common` 30 个、worker core 9 个、export generate/checkpoint 26 个测试均为 0 失败、0 错误 |
| Spotless | 通过 | `batch-common`、worker core、worker export 相关 reactor 均通过 |
| Sonar 成功快照 | 已完成 | `reports/sonar/2026-08-12_20-04-36/`：开放 19 Bug、0 Vulnerability、0 Security Hotspot、1,096 Code Smell |
| Sonar 复扫 | 结果无效，脚本已修 | 扫描期间本地并发修改 `DistributedLockAcquireException.java`（17 行变 15 行），服务端任务按旧组件行数失败；旧脚本错误导出了历史快照，现已改为失败即退出、不导出报告 |
| IntelliJ CLI inspection | 未运行 | 用户的 IntelliJ 实例持有单实例锁，未强制关闭 IDE |
| Qodana（IDEA 本地） | 已执行 | 已由用户通过 IDEA 本地 Qodana 执行；IDEA 未导出可供命令行读取的 SARIF/JSON 结果，因此本报告不重算其数量 |
| Qodana Docker CLI | 未形成独立结果 | `jetbrains/qodana-jvm:2026.1` 镜像拉取无进展后停止；不影响 IDEA 本地扫描结论 |

复跑 Sonar 前必须先保持工作树不再并发修改。由于本地 Sonar 项目保存了旧文件行数，若仍报组件行数冲突，应删除仅本机的 `file-batch-system` Sonar 项目后重新扫描，不能引用失败扫描导出的 CSV。

## 截图项复核

| 项目 | 结论 | 已采取措施 |
|---|---|---|
| `decoded.length == 0` | 冗余 | 空/空白密钥在 Base64 前已拒绝；空解码结果仍由后续全零判定返回弱密钥 |
| `profile == null` | 不可达保护 | Spring `Environment#getActiveProfiles()` 返回非空字符串数组；未知 profile 的 fail-secure 语义不变 |
| `List.add(0, value)` | 可读性改进 | 3 处均改用 Java 21 `addFirst(value)` |
| `StartupStep.end()` | 资源结束正确但写法可收敛 | `StartupStep.close()` 的 Spring 实现就是 `end()`；已改 try-with-resources |
| 多 `DataSource` 注入 | IDEA 跨模块误报，但需防御 | 每个 worker 运行时有自己的 `@Primary` 数据源；自动配置改为 `@ConditionalOnSingleCandidate`，无主候选时不注册探针 |
| `ThreadLocal` 严格编码器 | 有效 P2 | 导出格式在一次生成作用域结束时显式 `remove()`，保留任务内编码器复用 |

## Sonar Bug 逐项结论

### 已关闭

- 7 个 `java:S2142`：AI、验证码、短信、企微路径已恢复线程中断状态；Sonar 状态为 `CLOSED`。
- 1 个 `java:S2583`：配置包 Excel 值解析器已有定向抑制，Sonar 状态为 `CLOSED`。

### 已人工确认的误报

18 个开放 `java:S2259` 均受统一工具保护：

- `DefaultTaskAssignmentService`：`row`、`partition` 在使用前经 `EmptyChecks` 短路或三元表达式保护；
- `WorkflowNodePayloadBuilder`：`mergeWhitelistedOutputFields` 本身接受空分区，`isFinishedLater` 只在已有非空候选时调用；
- SQL transform、导入 Parse、启动清理：`Texts.hasText`、`EmptyChecks.isNull` 或模式匹配先完成空值分流；
- `EncodingUtils`、报表 record accessor：空值先返回默认字符集或 null 单元格。

Sonar 不会沿 JetBrains `@Contract` 推导项目自定义空值工具。已补足 `isNull` / `isNotNull` 的双向契约，提升 IDEA/Qodana 的识别能力；不为消除 Sonar 数字而复制分散的原生 `null` 判断。

### Code Smell 治理边界

- `S1192`（308）：只提取领域不变量、协议字段和已有常量；局部一次性 JSON key、错误码或 SQL 列名不做为了凑数的常量化。
- `S5778`、`S4449`、`S5853` 等：优先检查断言 lambda、副作用和可空契约；单纯格式替换进入常规重构批次。
- `S112`、复杂度、重复测试：需要结合 SPI 的受检异常边界和测试可读性评审，不能在本轮静态扫描中批量改写。

## 本轮变更

- 统一判空契约、生产 profile 判定和对象加密弱密钥检查；
- RestClient JSON converter 的首插 API；
- 启动自检 `StartupStep` 关闭语义；
- Hikari 饱和探针的单候选数据源保护与 2 个装配测试；
- 导出严格编码器线程本地缓存的任务作用域清理；
- Sonar 脚本在服务端任务 `FAILED`、`CANCELED` 或超时时失败退出，禁止导出陈旧结果。

## 后续

1. 待工作树稳定后重置本机 Sonar 项目并复跑，得到包含最终线程本地清理的有效快照。
2. 若需机器可比的基线，从 IDEA 导出 Qodana SARIF/JSON；Docker CLI 复跑仅作为独立交叉扫描，不替代本地已完成的 Qodana。
3. 以领域批次治理重复字面量、异常契约和复杂度，优先状态机、事务、安全与外部 I/O 边界。
