# GitHub Security 告警处置（2026-11-28）

2026-11-28 查询 `pinpols/file-batch-system` 的开放告警，Code Scanning 13 条，Dependabot 0 条，Secret Scanning 0 条。13 条均来自 `main` 的 `9b0ff2bbe23d582494093b6a547fa43ef2c7cab8`，CodeQL 2.27.6 分析 2267409490。以下结论依据该次 SARIF、对应 Controller、认证过滤器、服务及 Mapper；处置流程见[安全扫描手册](../runbook/security-scan.md)。

## 调度快照的读写边界

[#1471](https://github.com/pinpols/file-batch-system/security/code-scanning/1471) 标记 `GET /internal/scheduler/snapshot`。SARIF 关联了 PostgreSQL 配额状态的两个写入位置 `insert` 和 `updateWithCas`。实际的 `describe` 路径此前传入固定 `false`，两处写入都不可达。现在 `refreshState` 只计算窗口，配额预约与后台对账在各自事务内调用 `persist`。配额窗口到期后的读结果、预约和后台重置规则沿用原有口径。已加用例核对查询过期窗口时，只返回重置后的视图，Mapper 不执行插入或更新。告警是否自动关闭须由合入后的 `main` 复扫确认。

## 建议按编号标记误报的 12 条告警

| 告警 | 当前代码和扫描路径 |
|---|---|
| [#1464](https://github.com/pinpols/file-batch-system/security/code-scanning/1464) | `GET /api/console/jobs/bundle/export` 仅供平台管理员读取并组装配置包，预览事务为只读。 |
| [#1465](https://github.com/pinpols/file-batch-system/security/code-scanning/1465) | `GET /api/console/notifications/terminal-deliveries` 校验 Console 角色和租户，事务内绑定租户后查询投递记录。重试由独立的 `POST` 入口处理。 |
| [#1466](https://github.com/pinpols/file-batch-system/security/code-scanning/1466) | `GET /internal/batch-days/catch-up-preview/historical-gaps` 在只读事务中查询日历与业务日期缺口。 |
| [#1467](https://github.com/pinpols/file-batch-system/security/code-scanning/1467) | `GET /internal/batch-days/catch-up-preview` 在只读事务中查询日历、进度与候选日期。暂停控制由独立的 `POST` 入口处理。 |
| [#1468](https://github.com/pinpols/file-batch-system/security/code-scanning/1468) | `GET /internal/calendar-dependency-waits` 只绑定事务租户上下文并执行 `selectRecent`。人工处置由独立的 `POST` 入口处理。 |
| [#1469](https://github.com/pinpols/file-batch-system/security/code-scanning/1469) | `GET /internal/file-group-expectations/upload-targets` 只绑定事务租户上下文并执行 `selectEnabledUploadTargets`。配置更新由独立的 `PUT` 入口处理。 |
| [#1470](https://github.com/pinpols/file-batch-system/security/code-scanning/1470) | `GET /internal/job-dependency-waits` 只绑定事务租户上下文并执行 `selectRecent`。 |
| [#1472](https://github.com/pinpols/file-batch-system/security/code-scanning/1472) | `GET /api/triggers/events/status` 校验内部共享密钥或受限租户 API Key，按租户、事件来源与幂等键查询事件及其目标状态。 |
| [#1473](https://github.com/pinpols/file-batch-system/security/code-scanning/1473) | SARIF 将确定性的续跑路径连到 Excel 写流。`GenerateStep.resolveCheckpointInstanceId` 对 Excel 直接排除该路径。非 Excel 续跑文件及 Excel 随机输出均使用 `OwnerOnlyFiles` 保护，POI 中间产物使用 `PrivateTempFiles`。POSIX 创建时设置 `0600` / `0700`，其他文件系统需通过属主 ACL 检查。 |
| [#1475](https://github.com/pinpols/file-batch-system/security/code-scanning/1475) | `DefaultWorkflowRunLaunchService` 先核对持久化 TriggerRequest 与请求身份。不匹配即拒绝。命中持久化容量等待快照时，恢复路径校验关联的 RUNNING 重放操作并在准入事务内复核。普通启动仍须通过定义、版本等准入检查。 |
| [#1476](https://github.com/pinpols/file-batch-system/security/code-scanning/1476) | Trigger API Key 只用于精确匹配的 method/path。路由不允许、Key 无效或租户不符时均不进入过滤器链，也不回退至内部共享密钥。 |
| [#1477](https://github.com/pinpols/file-batch-system/security/code-scanning/1477) | Console Push 订阅入口要求身份认证。告警行校验订阅密钥字段，非法载荷被拒绝。有效载荷仍经租户守卫才可写入。 |

上述 8 条 CSRF 告警的 SARIF 在 `RlsTenantContextHolder.runWithTenant` 的 `Supplier.get()` 处转入不相干的 Kafka 通知消费者等写路径。实际每个入口都传入自己的查询回调。事务内 `set_config('app.tenant_id', …, true)` 只设置当前事务的租户上下文。GitHub 上的告警处置尚未写入。

## 本地验证

- `mvn -o -B -pl batch-orchestrator spotless:check` 与 `mvn -o -B -pl batch-orchestrator -am -DskipTests test-compile pmd:check` 通过。
- `git diff --check`、文档结构与日期策略、测试命名与配置来源、空值写法、术语同步、Java 可读性清单与 LOC 快照检查通过。
- 首次关闭网络运行 Maven 时，本机缺少 Surefire 3.6.1 组件，未开始执行用例。依赖就绪后，工作区复跑集成测试又读到了 `OpenTelemetryLogbackBridge.class` 中的旧编译错误，Spring 上下文未启动。随后在只复制 Git 跟踪文件的隔离目录从源码重新编译，逐个对比三份修改后的 Java 文件，内容与工作区一致。
- `QuotaRuntimeStateIntegrationTest` 在 PostgreSQL 17.11 上通过 12 项。新增断言确认已过期窗口的查询结果返回可用突发额度，数据库行完整保留原值。随后运行对账，回读确认峰值已持久化为零。
- GitHub 复核仍显示同一 SHA 的 13 条开放告警。修订版的 CodeQL 复扫、GitHub CI 和目标环境验证未运行。

在隔离源码副本使用 Temurin JDK 21 运行以下命令。260 项定向单测及 12 项 PostgreSQL 集成测试全部通过，无失败、错误或跳过。集成测试用 Testcontainers 创建隔离依赖。

```bash
mvn -B -pl batch-common,batch-trigger,batch-orchestrator,batch-console-api,batch-worker/export -am \
  -Dtest=PrivateTempFilesTest,OwnerOnlyFilesTest,TriggerSecurityFilterTest,TriggerApiKeyVerifierTest,TriggerControllerTest,InternalAuthFilterTest,ConsoleSecurityConfigurationTest,ConsolePushControllerTest,ConsolePushSubscriptionServiceTest,DefaultWorkflowRunLaunchServiceReplayTest,WorkflowLaunchWaitServiceTest,BatchDayCatchupPreviewServiceTest,TenantSchedulerSnapshotServiceTest,JobDependencyWaitingLaunchServiceTest,FileGroupExpectationServiceTest,DefaultConsoleJobBundleApplicationServiceTest,ConsoleJobBundleControllerTest,DatabaseQuotaRuntimeStateServiceTest,RedisQuotaRuntimeStateServiceTest,QuotaRuntimeStateIntegrationTest,GenerateStepTest,ExcelExportFormatTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
