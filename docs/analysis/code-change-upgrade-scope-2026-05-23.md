# 需要改主代码的升级范围评估(BE) — 2026-05-23

> 配套 [`dependency-upgrade-evaluation-2026-05-23.md`](./dependency-upgrade-evaluation-2026-05-23.md) §5.4 "跳过项" 的二次评估。
>
> 评估目的:为每项"需要改主代码"的 BE 升级给出**具体文件清单 / 风险评级 / 工程量预估**,辅助你决定是否分批排期。
>
> 评估口径:数据基于 2026-05-23 当日仓库扫描,不是经验估。
>
> **范围**:本文只覆盖 `file-batch-system` 仓库的 Java 主代码升级。前端 FE 同类评估在 [`../../../batch-console/docs/reports/code-change-upgrade-scope-2026-05-23.md`](../../../batch-console/docs/reports/code-change-upgrade-scope-2026-05-23.md)。

## TL;DR

| 项 | 影响文件数 | 风险 | 工程量 | 推荐 |
|---|---|---|---|---|
| **okhttp 4 → 5** | 4 主 + 2 测试 | 中 | 半天 | 🔴 **决定不做**(2026-05-23 拍板:无 CVE 驱动,4.12.0 稳定;社区主线虽迁 5.x 但 4.x 仍长期维护) |
| **jsqlparser 4 → 5** | 4 主(SQL 注入防护) | 高 | 1 天(含回归) | 🔴 **决定不做**(2026-05-23 拍板:SQL 注入防护主路径,改错代价远大于升级收益;无 CVE 驱动) |
| **Spring AI M3 → GA** | 3 主 | 未知(看 GA API) | 半天到 1 天 | 🟡 一旦 GA 发布就做 |

---

## 1. okhttp 4 → 5(主代码 4 + 测试 2)

| 文件 | 用途 | 主要改动 |
|---|---|---|
| `batch-common/.../MinioAutoConfiguration.java` | MinIO 客户端的 HTTP 通道(`OkHttpClient`) | import 包名(若 5.x 改包)、`Interceptor` 实现签名微调 |
| `batch-worker-dispatch/.../HttpDispatchChannelAdapter.java` | Worker dispatch HTTP 渠道 | 同上 |
| `batch-worker-dispatch/.../RemoteFilesystemDispatchSupport.java` | 远程 FS dispatch | 同上 |
| `batch-worker-dispatch/.../DispatchReceiptPollScheduler.java` | dispatch 回执轮询 | 同上 |
| `batch-worker-core/test/.../WorkerReportOutboxCoordinatorTest.java` | `MockWebServer` 起 mock 服务 | **artifact 重命名:`mockwebserver` → `mockwebserver3` + `mockwebserver3-junit5`** |
| `batch-worker-core/test/.../HttpTaskExecutionClientTest.java` | 同上 | 同上 |

**Breaking 集合**:
1. **Maven 坐标**:`mockwebserver` → `mockwebserver3`(根 pom 的 `<dependencyManagement>` 改)
2. **Kotlin runtime 解绑**:okhttp 5 不再要求 Kotlin stdlib,若间接依赖要补显式 dep
3. **`HttpUrl`、`Headers`、`Interceptor`** 部分 API 微调(具体看 release notes)
4. 测试侧 `MockWebServer` 改成 `mockwebserver3.MockWebServer`,enqueue/dispatcher 用法一致

**风险**:中。dispatch / MinIO / report-outbox 三大主链路都涉及,改坏会影响 IT。但 API 主体兼容,大部分是 import 重命名级别。

**工程量预估**:**半天**(改 6 文件 + 跑 4 模块 IT 回归)

**推荐**:🔴 **决定不做**(2026-05-23 拍板)
- 无 CVE 驱动,okhttp 4.12.0 仍长期维护
- 主链路 dispatch / MinIO / report-outbox 改动风险高于收益
- 若未来 4.x 出 CVE 或 5.x 引入必要功能再启动

---

## 2. jsqlparser 4 → 5(主代码 4)

| 文件 | 用途 |
|---|---|
| `batch-worker-process/.../SqlTransformComputeSqlValidator.java` | SQL 变换计算插件的 SQL 安全校验 |
| `batch-worker-process/.../SqlTransformComputePlugin.java` | 同上插件主体 |
| `batch-worker-export/.../SqlTemplateExportSqlValidator.java` | SQL 模板导出的 SQL 校验 |
| `batch-orchestrator/.../SensorSqlValidator.java` | Sensor SQL 安全校验 |

**Breaking 集合**(基于 jsqlparser 5.x 升级笔记):
1. **AST node 改名 / 拆分**:`PlainSelect`、`SubSelect`、`SelectExpressionItem` 等部分类层级调整
2. **`SelectVisitor` / `StatementVisitor` 接口方法签名变**:旧 visitor 实现要补/改方法
3. **`net.sf.jsqlparser.statement` 包内一些 statement 类型重组**(insert/select/with 等)

**风险**:**高** —— 这 4 个文件是**SQL 注入防护主路径**,Worker 在 import/export/process 数据时用它们校验用户提交的 SQL。改错可能:
- 校验漏一个语法 → 注入风险
- 校验过严 → 业务 SQL 无法执行

**工程量预估**:**1 天**(改 4 文件 + 设计/补充 SQL 注入用例回归测试集 + 跑全套 worker IT)

**推荐**:🔴 **决定不做**(2026-05-23 拍板)
- SQL 注入防护主路径,改坏代价 = 安全漏洞 / 业务 SQL 失效
- 无 CVE 驱动,jsqlparser 4.5 在已知用法下稳定
- 若未来 4.x 出 CVE 才被动启动,启动时同时补 SQL 注入回归用例集

---

## 3. Spring AI 2.0.0-M3 → GA(主代码 3)

| 文件 | 用途 |
|---|---|
| `batch-console-api/.../BatchConsoleApiApplication.java` | 启动类(可能 import / exclude 配置) |
| `batch-console-api/.../config/ConsoleAiConfiguration.java` | AI bean 装配、ChatClient/ChatModel 构造 |
| `batch-console-api/.../infrastructure/ai/DefaultConsoleAiApplicationService.java` | AI 业务调用主路径 |

**Breaking 集合**(基于 Spring AI milestone → GA 一般规律):
1. **`ChatClient` builder API** 在 M3 → M5 / GA 之间有过 2 轮收敛
2. `Prompt` / `Message` / `ChatResponse` 包路径可能微调
3. Auto-configuration property prefix 可能改(如 `spring.ai.openai.chat.options.*`)

**风险**:**未知** —— 取决于 GA 实际 API。如果 GA 已发布,先打个补丁包 import 看类是否还在。

**工程量预估**:**半天到 1 天**(看 GA breaking 多少)

**推荐**:🟡 **GA 一发布就做**。M3 是 milestone,长期不动会越拖越远。先 `mvn versions:display-dependency-updates` 查最新 GA。

---

## 综合排期建议(BE 视角)

### 等 GA 发布就做
- 🟡 **Spring AI M3 → GA**(等 Spring AI 团队正式发布)

### 决定不做(2026-05-23 拍板,等 CVE / 业务驱动再启动)
- 🔴 **okhttp 4 → 5**:无 CVE,4.12.0 仍长期维护;主链路改动风险 > 收益
- 🔴 **jsqlparser 4 → 5**:SQL 注入防护主路径,改坏代价 = 安全漏洞或业务 SQL 失效;无 CVE
