# SonarQube Scan Report — File Batch System

扫描时间：2026-08-05 21:38   |   SonarQube: http://localhost:9001/dashboard?id=file-batch-system

## 整体指标

| 指标 | 数值 | 评级 |
|---|---|---|
| 代码行数（NCLOC） | 139197 | — |
| Bug | 137 | D |
| Vulnerability | 27 | D |
| Security Hotspot | 0 | 待审查 |
| Code Smell | 2636 | A |
| 技术债 | 334h 21m | — |
| 重复率 | 1.1% | — |
| 覆盖率 | 63.8% | — |

## 各模块 Issue 分布

| 模块                                            | BLOCKER | CRITICAL | MAJOR | MINOR | INFO | 合计 |
|-----------------------------------------------|------|------|------|------|------|-------|
| batch-common                                  |      0 |     17 |    115 |     55 |      1 |   188 |
| batch-console-api                             |      3 |    330 |    180 |    298 |      8 |   819 |
| batch-orchestrator                            |      4 |    140 |    190 |    592 |      4 |   930 |
| batch-trigger                                 |      0 |     13 |     12 |     46 |      2 |    73 |
| batch-worker                                  |      4 |    154 |    281 |    223 |     11 |   673 |
| sdk                                           |      3 |     21 |    105 |     34 |      2 |   165 |
| **历史合计**                                    | **14** | **675** | **883** | **1248** | **28** | **2848** |

## BLOCKER 明细

| 类型 | 文件 | 行 | 描述 |
|---|---|---|---|
| CODE_SMELL | `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/application/WorkflowDesignLockServiceTest.java` | 111 | Add at least one assertion to this test case. |
| CODE_SMELL | `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/task/SdkCommitCoordinatorTest.java` | 111 | Add at least one assertion to this test case. |
| BUG | `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/storedproc/StoredProcTaskExecutor.java` |  | This "PreparedStatement" only has 1 parameters. |
| CODE_SMELL | `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/LoginProtectionServiceTest.java` | 122 | Add at least one assertion to this test case. |
| CODE_SMELL | `batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicConnectionManager.java` | 174 | Rename method "readOnly" to prevent any misunderstanding/clash with field "readOnly". |
| CODE_SMELL | `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/lineage/OpenLineageEmitterTest.java` | 55 | Add at least one assertion to this test case. |
| CODE_SMELL | `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/runtime/HttpExecutorProdDefaultsTest.java` | 89 | Add at least one assertion to this test case. |
| CODE_SMELL | `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherTest.java` | 317 | Add at least one assertion to this test case. |
| BUG | `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunManagementApplicationService.java` |  | "skipNode's" @Transactional requirement is incompatible with the one for this method. |
| CODE_SMELL | `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifierTest.java` | 348 | Add at least one assertion to this test case. |
| CODE_SMELL | `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleJwtServiceTest.java` | 177 | Add at least one assertion to this test case. |
| CODE_SMELL | `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/CrossDayDependencyResolver.java` | 264 | Rename method "resolved" to prevent any misunderstanding/clash with field "resolved". |
| CODE_SMELL | `batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/support/PipelineVerifierHookTest.java` | 81 | Add at least one assertion to this test case. |
| CODE_SMELL | `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/scheduler/HeartbeatSchedulerTest.java` | 117 | Add at least one assertion to this test case. |

---
*详细明细见 `sonar-report.csv`（2840 条）*

## 分批治理记录

### 第一批：临时文件与高风险边界（2026-08-05）

- 新增 `PrivateTempFiles`，import/export 中间文件统一落到应用私有临时目录，并设置 owner-only 权限。
- SDK Shell handler 使用独立的私有工作目录，避免 SDK 对运行时模块产生反向依赖。
- stored procedure 元数据查询拆成固定 SQL 分支；workflow skipNode 的事务入口拆出内部实现，避免同类事务方法自调用。
- 修复 Sonar 扫描脚本吞错问题，Maven Sonar 插件改为全限定坐标，构建或分析失败会返回非零状态。

### 第二批：空值边界与可维护性（2026-08-05）

- import Parse/Load/Feedback 阶段对空执行上下文统一返回结构化失败结果，不再在非 dry-run 路径裸抛 `NullPointerException`。
- trailer 控制记录解析对空字段显式短路；Preprocess 配置先归一化为空 Map，再处理格式和容量边界。
- 解密缓冲区容量计算改为 long 边界表达式；PG 故障注入测试抽出单一 SQL 调用，消除 checked exception lambda 歧义。
- Spring AI 响应使用 `Optional` 逐层解包，避免重复 getter 和外部返回值为空时的异常。

## 第三批：SQL 解析与存储边界（2026-08-05）

- `SqlTemplateExportSqlValidator` 改为词法扫描：跳过字符串、标识符、行/块注释、PostgreSQL dollar quote 和 `::` 类型转换，避免把 SQL 文本中的伪参数误判为绑定参数。
- 同时校验缺失参数和未知参数；新增 41 个 SQL 校验用例覆盖注释、字面量、转义、类型转换、未闭合文本等边界。
- `PrivateTempFiles`、导入错误输出存储、导出加密上传、workflow skipNode 兼容入口、stored procedure 元数据查询均补充边界测试。

## 本轮验证结论

- 本地完整非 E2E Maven 门禁：15 个模块 `BUILD SUCCESS`；`4206` 个测试，失败 `0`、错误 `0`、跳过 `30`。
- 本轮生成 13 份 JaCoCo XML，`git diff --check` 通过。
- 本地 Sonar 最新扫描：新代码问题 `0` 个、新重复率 `0.0%`、新代码覆盖率 `79.9%`（178 行中 38 行未覆盖）。
- 本地质量门禁状态为 `ERROR`，唯一失败项是新代码覆盖率 `79.9% < 80%`；不是静态问题或安全问题导致。
- GitHub Actions `full-ci-gate` 运行 [31004570779](https://github.com/pinpols/file-batch-system/actions/runs/31004570779) 全部通过，包括静态检查、安全扫描、单测/集成测试和 4 个 E2E 分片；该运行对应远端 `main` SHA `b20fc118778166e2c6e3885df8ed8e7ebc088b95`，不包含本地未提交改动。
- 历史总量 2848 条是 Sonar 基线问题，不能等同于本轮新增缺陷。当前仍需补足至少 0.1 个百分点的新代码覆盖率，才能让本地质量门禁全绿。
