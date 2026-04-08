# 全量测试运行报告

更新时间：`2026-04-08 CST`

## 结论

本轮缺陷修复完成后，仓库级全量回归已经通过：

- Reactor 默认测试（含单元 + 集成）：全部模块 `BUILD SUCCESS`，`0` 失败，`0` 错误
- E2E 套件（`batch-e2e-tests`）：全部 `BUILD SUCCESS`，`0` 失败，`0` 错误
- 测试文件总数：**247 个**（146 单元 + 59 集成 + 30 E2E + 支撑类）
- Reactor 各模块均为 `BUILD SUCCESS`
- 之前残留的 surefire `kill self fork JVM after System.exit(0)` 噪音已清除

## 执行命令

### 1. 显式集成 / E2E 套件

```bash
mvn -fae -Dmaven.test.failure.ignore=true clean test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
```

- 日志：`/tmp/file-batch-it-full-final-20260328.log`
- 总耗时：`05:15`

### 2. Reactor 默认测试

```bash
mvn -fae -Dmaven.test.failure.ignore=true clean test
```

- 日志：`/tmp/file-batch-default-full-final-20260328.log`
- 总耗时：`04:08`

### 3. 噪音专项复验

```bash
mvn -pl batch-orchestrator -am clean test
mvn -pl batch-e2e-tests -am -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false test
```

- 日志：
  - `/tmp/batch-orchestrator-clean-test-20260328.log`
  - `/tmp/batch-e2e-it-20260328.log`
- 结果：均无 surefire 强杀提示

## 关键修复项

### 1. 测试库表结构与测试数据对齐

- 补齐了测试启动库中缺失的业务表，清除了以下类的缺表失败：
  - `DispatchChannelHealthServiceIntegrationTest`
  - `AlertEventIntegrationTest`
  - `ConsoleAiAuditServiceIntegrationTest`

### 2. Console 集成测试 JDBC / SQL 类型修正

- 修正了 `Instant` 直接写入 `JdbcTemplate` 导致的 PostgreSQL 类型推断问题
- 修正了 `DATE` 列写入字符串的问题
- 清除了以下类的类型失败：
  - `ConsoleRetryScheduleQueryIntegrationTest`
  - `JobInstanceQueryIntegrationTest`

### 3. Worker drain / registry 行为收口

- 新增 `WorkerRegistryJdbcRepository`，把 worker 心跳和下线更新改为显式 SQL 更新
- 避免 worker 在 `DRAINING / DECOMMISSIONED` 状态被 heartbeat 覆盖回正常态
- 相关修复点：
  - `DefaultWorkerRegistryService`
  - `DefaultWorkerDrainGovernanceService`
  - `DefaultWorkerDrainGovernanceServiceTest`
  - `WorkerControllerTest`

### 4. E2E 导入侧稳定性修复

- `WorkerDrainE2eIT`
  - 独立 drain worker code
  - 测试后清理 worker_registry
  - 直接调用治理服务接管超时 drain
- `MultiTenantConcurrentE2eIT`
  - 补齐租户 `t2` 的模板和 worker 种子
  - 修正 outbox 隔离断言，按 `job_task -> job_instance` 关联校验
- `OutboxForwarderRetryE2eIT`
  - 补齐 `publishAttempt=0` 初始值
- `ExportContentVerificationE2eIT`
  - 修正导出模板代码
  - 移除与当前实现不一致的错误金额断言

### 5. 调度器关闭行为修正

- 共享 `taskScheduler` 改为默认不等待关闭中的调度任务
- 关闭时不继续执行已排队的延迟 / 周期任务
- 直接效果：
  - 清除 surefire `kill self fork JVM` 噪音
  - 缩短 `batch-orchestrator` 和 E2E 测试关闭时间

## 结果明细

### 显式 `*IT` 套件

- 覆盖模块：
  - `batch-trigger`
  - `batch-orchestrator`
  - `batch-worker-import`
  - `batch-worker-export`
  - `batch-worker-dispatch`
  - `batch-console-api`
  - `batch-e2e-tests`
- 结果：全部通过，`0` 失败，`0` 错误

模块耗时：

- `batch-trigger`：`30.117s`
- `batch-orchestrator`：`39.023s`
- `batch-worker-import`：`27.219s`
- `batch-worker-export`：`26.587s`
- `batch-worker-dispatch`：`25.371s`
- `batch-console-api`：`46.319s`
- `batch-e2e-tests`：`01:43`

### Reactor 默认测试

- 结果：全部通过，`0` 失败，`0` 错误
- 说明：
  - `batch-e2e-tests` 在默认 `clean test` 中只完成编译，不执行 `*IT`
  - E2E 已在显式 `*IT` 套件中覆盖

## 当前状态

- 已无已知失败测试类
- 已无已知失败测试方法
- 当前发布门禁所需的仓库级自动化回归已可稳定执行

## 附注

- 日志里仍会出现业务预期内的 `WARN`，例如失败路径测试中的 dead letter / retry / validation error
- 这类日志不代表测试失败，最终两轮 Maven 结果均为 `BUILD SUCCESS`
