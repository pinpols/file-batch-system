# E2E 单次执行报告（2026-03-24）

> 说明：这是一次历史执行记录，主要用于保留当时的失败点和修复线索。当前目录中的正式门禁和阶段总结请优先看 `full-project-test-plan.md`、`release-gate.md` 和 `phase-1-test-coverage-matrix.md`。

## 范围

- 模块：`batch-e2e-tests`
- 执行方式：按类单独运行每个 `*E2eIT`，使用 Maven `-Dtest=<Class>`
- 命令：
  - `mvn -q -pl batch-e2e-tests clean test-compile`，先清理，避免旧字节码干扰
  - `mvn -q -pl batch-e2e-tests -Dtest=<Class> test`，逐类执行

## 结果

| Test Class | Result | Surefire Summary |
|---|---|---|
| `OutboxForwarderRetryE2eIT` | **FAIL** | Tests run: 2, Failures: 0, Errors: 2, Skipped: 0 |
| `ImportFailurePipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0（根据退出码推断） |
| `DispatchFailurePipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0（根据退出码推断） |
| `DispatchPipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0（根据退出码推断） |
| `ImportPipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0（根据退出码推断） |
| `ExportFailurePipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0（根据退出码推断） |
| `ExportPipelineE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0（根据退出码推断） |
| `OutboxForwarderE2eIT` | **PASS** | Tests run: 1, Failures: 0, Errors: 0, Skipped: 0（根据退出码推断） |

## 失败分析

### `OutboxForwarderRetryE2eIT`

- 根因：`java.lang.IllegalStateException: Unable to resolve batch.orchestrator.base-url for worker registry client`
- 直接影响：Spring 容器启动失败，这个类里的测试全部报错
- 原因分析：该类使用 `spring.main.web-application-type=none`，但 worker 生命周期启动时仍然需要为 `HttpWorkerRegistryClient` 解析 `batch.orchestrator.base-url`
- 可选修复方式：
  - 增加测试属性 `batch.orchestrator.base-url=http://127.0.0.1:${local.server.port}`
  - 如果 worker 注册 / HTTP 客户端是预期行为，则切换到 servlet web 模式
  - 如果该测试不依赖 worker 自动启动，则在测试里关闭 worker auto-start

## 备注

- 在一次非 clean 运行中，所有类都曾因为 `FileNotFoundException: E2ePlatformDataSourceConfiguration.class` 失败，原因是旧的 / 无效的测试类元数据；执行 `clean test-compile` 后该瞬态问题消失。
- 这份报告记录的是 clean 后的结果，也就是更可靠的信号。
