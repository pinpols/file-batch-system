# 历史测试报告归档（2026-04-09 ~ 04-10 快照）

> 这些是 v1 阶段（4/9-4/10）跑测产物的快照；保留为审计参考，不再维护。
> 当前测试体系见 `docs/testing/` (full-project-test-plan / phase-coverage / e2e-coverage / release-gate / coverage-gap-analysis / load-test-report)。

---

## deployment-verification-report


更新时间：2026-03-28

## 结论

部署升级 / 回滚验证的执行路径已经补齐到统一回归脚本中，支持以独立模式进行 staging 执行：

```bash
BATCH_DEPLOY_VERIFICATION_ENABLE_LIVE=true \
bash scripts/ci/run-full-regression.sh --with-deployment-verification
```

当前已完成的是验证工具链与脚本入口收敛；真实 staging 集群上的 live upgrade / rollback 留档仍需在目标环境执行。

## 已补齐的能力

- `scripts/ci/run-full-regression.sh` 新增 `--with-deployment-verification`
- 通过 Helm release revision 进行升级和回滚验证
- 通过 `podAnnotations.rollbackVerificationRunId` 触发一次可观测的滚动升级
- 回滚后重新检查 readiness，确认回到上一版本
- 部署验证可以使用独立 release / namespace，避免污染普通 deploy smoke

## 验证流程

脚本执行顺序如下：

1. `helm lint`
2. `helm template`
3. 初次 `helm upgrade --install --wait`
4. 部署 readiness 校验
5. 通过 annotation 触发二次升级
6. 再次 readiness 校验
7. `helm rollback` 回到 revision 1
8. 再次 readiness 校验
9. 断言回滚后 annotation 已消失

## 建议的 staging 执行方式

```bash
export BATCH_DEPLOY_VERIFICATION_ENABLE_LIVE=true
export BATCH_DEPLOY_VERIFICATION_RELEASE=batch-platform-verification
export BATCH_DEPLOY_VERIFICATION_NAMESPACE=batch-verification
export BATCH_DEPLOY_VERIFICATION_VALUES_FILE=helm/values-prod.yaml

export BATCH_DEPLOY_VERIFICATION_PLATFORM_DB_PASSWORD='***'
export BATCH_DEPLOY_VERIFICATION_BUSINESS_DB_PASSWORD='***'
export BATCH_DEPLOY_VERIFICATION_MINIO_ACCESS_KEY='***'
export BATCH_DEPLOY_VERIFICATION_MINIO_SECRET_KEY='***'

bash scripts/ci/run-full-regression.sh --with-deployment-verification
```

## 放行证据

执行完成后，应保留：

- 终端日志
- `helm history` 输出
- `kubectl get deploy,pod,svc,ingress,hpa -o wide`
- `kubectl describe` 和关键容器日志
- 使用的 values 文件版本和镜像 tag
- 执行时间、执行人、context、namespace、release 名称

## 当前残余缺口

- 真实 staging 集群上的 live 执行结果尚未回填
- 回滚后的业务链路级验收仍需按发布流程补充
- 数据迁移前后兼容窗口仍需按发布节奏验证

## 相关文档

- `docs/testing/full-project-test-plan.md`
- `docs/testing/phase-1-test-coverage-matrix.md`
- `docs/testing/release-gate.md`
- `docs/testing/staging-live-deploy-smoke-checklist.md`

---

## full-test-run-report


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

### 3. Worker drain / registry 行为收敛

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

---

## e2e-individual-run-report


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

---

## verification-e2e-unit-integration-run


日期：2026-03-29
目标：逐一验证所有测试类（尽量避免重复跑已成功用例），并把失败与修复过程记录下来。
fail-fast：关闭（`-Dsurefire.failFast=false`、`-Djunit.jupiter.execution.fail_fast=false`）
端口：统一设置 `-Dserver.port=0`（尽量避免占用）

---

## 预先修复（为通过之前 E2E 报错）

1. `DispatchPipelineE2eIT` 初次失败：`Unrecognized field "run_mode"`
   - 修复：在 `batch-worker-dispatch` 的 `DispatchPayload` 增加 `run_mode` 字段兼容（`@JsonProperty("run_mode")` / `@JsonAlias("runMode")`）。
2. `DispatchPipelineE2eIT` 后续失败：`ON CONFLICT (tenant_id, event_key)` 无匹配唯一约束
   - 根因（历史）：当时 `platform-init.sql` 手写了 `outbox_event` 表且与 Flyway 不一致。
   - 现状：`platform-init.sql` 仅建 schema；`outbox_event` 及唯一约束完全由 Flyway 迁移定义。
3. `ExportPipelineE2eIT` 失败：`Unrecognized field "run_mode"`
   - 修复：在 `batch-worker-export` 的 `ExportPayload` 增加 `runMode`（`@JsonProperty("run_mode")` / `@JsonAlias("runMode")`），并同步更新 `batch-worker-export` 内相关单测里 `new ExportPayload(...)` 的入参（在 `autoDispatch` 后补 `null`）。

验证结果：
- ✅ `DispatchPipelineE2eIT`：通过

---

## E2E 测试类验证结果（逐个跑）

> 说明：每个条目只在“该类失败”时才会额外记录修复与重跑；其余类仅记录通过/失败。

| 测试类 | 命令（摘要） | 结果 | 备注 |
|---|---|---|---|
| `io.github.pinpols.batch.e2e.DispatchPipelineE2eIT` | `mvn test -pl batch-e2e-tests -am -Dtest=DispatchPipelineE2eIT ...` | PASS | 见上方预先修复 |
| `io.github.pinpols.batch.e2e.ImportPipelineE2eIT` | `mvn test -pl batch-e2e-tests -am -Dtest=ImportPipelineE2eIT ...` | FAIL | 当前错误：`customerNo is required`；前序修复已处理 `run_mode`、`ON CONFLICT`、JSON 解析兼容/宽松转换，但解析出来行仍缺 `customerNo`（继续定位 task_payload/content 形态） |
| `io.github.pinpols.batch.e2e.ExportPipelineE2eIT` | `mvn test -pl batch-e2e-tests -am -Dtest=ExportPipelineE2eIT ...` | PASS | 补齐 `ExportPayload` 的 `run_mode` 兼容字段后通过 |

---

## 单元测试 / 集成测试

（待执行并回填）


---

## failure-drill-report


更新时间：2026-03-28

## 结论

Phase 3 的本地可执行系统联调资产已经收敛，包含静态 deploy smoke、巡检入口和现有系统级测试证据。
但真实 staging kube context 下的 live rollout、故障注入和回滚观测仍需要在目标环境执行，当前环境无法替代。

## 已完成的验证

### 1. 静态 deploy smoke

执行命令：

```bash
bash scripts/ci/run-full-regression.sh --skip-default-tests --skip-it-suite --with-deploy-smoke
```

结果：

- `helm lint` 通过
- `helm template` 通过
- chart 关键对象断言通过
- 统一脚本整体返回 `FULL REGRESSION PASSED`

### 2. 已有系统级测试证据

仓库内已具备并保留的相关证据：

- `batch-orchestrator` 并发 claim 集成测试
- `batch-orchestrator` outbox / Kafka 投递失败分支测试
- `batch-console-api` 安全负向测试
- `batch-console-api` tenant mismatch 负向测试
- `batch-worker-core` backpressure、worker loop、lease/wrapper 的单测基础
- `batch-worker-dispatch` 现有 circuit breaker / health probe 相关测试

### 3. 巡检与自愈入口

可用于 Phase 3 演练后的恢复检查：

- `scripts/ops/inspect-all.sh`
- `scripts/ops/inspect-db.sh`
- `scripts/ops/inspect-workers.sh`
- `scripts/ops/inspect-observability.sh`
- `scripts/ops/heal-stuck-outbox.sh`
- `scripts/ops/heal-dead-letters.sh`
- `scripts/ops/heal-drain-timeout.sh`

## 尚未在当前环境完成的项

以下内容仍需要真实 staging 集群、可用的 kube context 和外部依赖后再执行：

- `helm upgrade --install --wait` 的 live rollout
- `kubectl rollout status`
- `port-forward + /actuator/health/readiness`
- PostgreSQL 短暂不可用注入
- Kafka broker 波动 / backlog 恢复注入
- MinIO 写失败注入
- Worker restart / drain / graceful shutdown 演练
- WireMock / 外部渠道 5xx、超时、重试耗尽演练
- 回滚 smoke 与回滚后业务验收

## 残余风险

- 当前报告覆盖的是本地可执行的系统联调资产，不等价于真实 staging 验收。
- 回滚 smoke 和故障注入必须在目标环境做最终确认，不能仅依赖单测或静态 deploy smoke。

## 相关入口

- `docs/testing/full-project-test-plan.md`
- `docs/testing/phase-1-test-coverage-matrix.md`
- `docs/testing/release-gate.md`
- `docs/testing/staging-live-deploy-smoke-checklist.md`

---

## load-test-capacity-baseline


## 概述

| 项目 | 说明 |
|---|---|
| 工具 | Gatling 3.12 Java DSL |
| 模块 | `load-tests/`（独立 Maven 模块，不加入主构建） |
| 目标 | 获取上线前容量基线，为 K8s 资源规格、HPA 阈值、数据库连接池上限提供实测数据 |

## 当前状态

- `load-tests` 已通过 `mvn -q -f load-tests test-compile`
- 三个核心 simulation 已修复为可编译状态
- 当前文档中的容量数字仍需在 staging 或 prod-like 环境实测后回填

---

## 测试场景

| 模拟类 | 目标接口 | 用途 |
|---|---|---|
| `JobLaunchSimulation` | `POST /api/triggers/launch`（trigger:18081） | 写入路径吞吐/延迟基线 |
| `ConsoleQuerySimulation` | `GET /api/console/query/instances` 等（console:18080） | 查询路径吞吐/延迟基线 |
| `CapacityBaselineSimulation` | 写入 30 % + 查询 70 % 混合 | **生产前容量基线**（分级爬坡找饱和点） |

---

## 前置条件

### 1. 种子数据

目标环境必须预先存在以下数据（否则 launch 请求会因找不到 job_definition 而返回 404）：

```sql
-- 在 batch_platform 库中插入专用压测作业定义
INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type,
    schedule_type, timezone, priority, queue_code, worker_group, trigger_mode,
    dag_enabled, shard_strategy, retry_policy, retry_max_count, timeout_seconds,
    enabled, version)
VALUES ('t1', 'E2E_IMPORT_LOAD', 'Load Test Import Job', 'IMPORT', 'LOAD_TEST',
    'MANUAL', 'UTC', 5, 'load-q', 'import', 'API', false, 'NONE',
    'NONE', 0, 0, true, 1);

INSERT INTO batch.workflow_definition (tenant_id, workflow_code, workflow_name,
    workflow_type, version, enabled)
VALUES ('t1', 'E2E_IMPORT_LOAD', 'Load Test WF', 'DAG', 1, true);

INSERT INTO batch.trigger_request (tenant_id, request_id, trigger_type, job_code,
    biz_date, dedup_key, request_status, trace_id)
VALUES ('t1', 'load-seed-001', 'API', 'E2E_IMPORT_LOAD',
    '2026-01-15', 'load-dedup-seed', 'ACCEPTED', 'load-trace');
```

SQL 文件：`docs/sql/load-test/load-test-seed.sql`

### 2. 依赖服务

- PostgreSQL、Kafka、MinIO 均已就绪
- batch-trigger（8081）和 batch-console-api（8080）已启动且 `/actuator/health` 返回 UP

---

## 快速执行

```bash
cd load-tests

# 单场景：写入路径（本地，20 并发，120 秒）
mvn gatling:test -Dsimulation=JobLaunchSimulation

# 单场景：查询路径
mvn gatling:test -Dsimulation=ConsoleQuerySimulation

# 容量基线（5 阶段爬坡，25→200 并发，每阶段 60 秒）
mvn gatling:test -Dsimulation=CapacityBaselineSimulation \
    -DjobCode=E2E_IMPORT_LOAD -DtenantId=t1

# Staging 环境
mvn gatling:test -Pstaging -Dsimulation=JobLaunchSimulation \
    -Dusers.peak=50 -Dduration.seconds=300

# 查看报告
open target/gatling-results/*/index.html
```

---

## 关键参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `trigger.baseUrl` | `http://localhost:18081` | batch-trigger 地址 |
| `console.baseUrl` | `http://localhost:18080` | batch-console-api 地址 |
| `tenantId` | `t1` | 压测租户 ID |
| `jobCode` | `E2E_IMPORT_LOAD` | 压测作业 code |
| `bizDate` | `2026-01-15` | 业务日期 |
| `users.peak` | `20` | 峰值并发用户数 |
| `duration.seconds` | `120` | 稳定压测时长（秒） |
| `ramp.seconds` | `30` | 爬坡时长（秒） |
| `slo.write.p95ms` | `500` | 写入 p95 阈值（ms） |
| `slo.read.p99ms` | `300` | 读取 p99 阈值（ms） |
| `slo.maxErrorPct` | `1.0` | 最大错误率（%） |
| `console.authToken` | `Bearer load-test-token` | console-api 鉴权 token |

---

## SLO 定义

| 接口 | 指标 | 目标 | 上线门禁 |
|---|---|---|---|
| `POST /api/triggers/launch` | p95 | < 500 ms | ✅ |
| `GET /api/console/query/instances` | p99 | < 300 ms | ✅ |
| 全局 | 错误率 | < 1 % | ✅ |
| `POST /api/triggers/launch` | 吞吐 | ≥ 50 req/s @ 100 并发 | ⚠️ 待实测填写 |
| DB 连接池 | 队列等待 | < 10 ms | ⚠️ 待实测填写 |

---

## 容量基线记录表（待回填）

运行 `CapacityBaselineSimulation` 后，将各阶段数据记录于此：

| 并发用户数 | 写 p50 | 写 p95 | 读 p50 | 读 p99 | 错误率 | 吞吐 (req/s) | 结论 |
|---|---|---|---|---|---|---|---|
| 25 | — | — | — | — | — | — | 待测 |
| 50 | — | — | — | — | — | — | 待测 |
| **100** | — | — | — | — | — | — | **基线目标** |
| 150 | — | — | — | — | — | — | 待测 |
| 200 | — | — | — | — | — | — | 待测 |

**饱和点**：\_\_\_ 并发用户（p95 首次超过 500 ms 或错误率首次超过 1 %）

---

## 资源规格建议（基线完成后填写）

| 服务 | CPU request | CPU limit | Memory request | Memory limit | 最大副本数（HPA） |
|---|---|---|---|---|---|
| batch-trigger | — | — | — | — | — |
| batch-orchestrator | — | — | — | — | — |
| batch-worker-import | — | — | — | — | — |
| batch-worker-export | — | — | — | — | — |
| batch-worker-dispatch | — | — | — | — | — |
| batch-console-api | — | — | — | — | — |
| PostgreSQL 连接池上限 | — | — | — | — | — |
| Kafka 分区数建议 | — | — | — | — | — |

---

## CI 集成建议

当前仓库已通过 `.github/workflows/staging-gate.yml` + `scripts/ci/run-full-regression.sh --with-load-smoke` 接入 staging gate。

如果需要提升 staging 压测强度，可把 `JobLaunchSimulation`（20 并发，120 秒）扩展为更高参数，例如：

```yaml
# .github/workflows/staging-gate.yml 片段
- name: Stronger load test gate
  run: |
    bash scripts/ci/run-full-regression.sh --with-load-smoke
```

并通过环境变量覆盖默认 smoke 参数：

```yaml
env:
  BATCH_LOAD_SMOKE_USERS_PEAK: "50"
  BATCH_LOAD_SMOKE_DURATION_SECONDS: "120"
  BATCH_LOAD_SMOKE_RAMP_SECONDS: "20"
```

构建结果中的 Gatling HTML 报告应继续作为 artifact 保留，供 review 使用。

## 备注

当前阶段已完成的是压测工具链和运行入口收敛，不代表已经获得最终容量基线。
正式门禁仍要求在真实 staging 或 prod-like 环境回填 p95 / p99 / 吞吐 / 错误率 / 饱和点数据。

---

## frontend-backend-integration-issue


日期：2026-03-29

## 现象

- 前端收到 `SYSTEM_ERROR`，但后端最初没有统一打印异常堆栈，定位较慢。
- `batch-trigger` 启动时报 Quartz 表不存在，触发链路无法稳定启动。
- `batch-console-api` 侧还能看到数据库参数绑定失败、下游 500、409 等异常。

## 日志证据

```text
ERROR ... ConsoleApiExceptionHandler - console unexpected exception
org.springframework.dao.DataIntegrityViolationException:
### Error querying database.  Cause: org.postgresql.util.PSQLException: 未设定参数值 5 的内容。
```

```text
ERROR ... ConsoleApiExceptionHandler - console unexpected exception
org.springframework.web.client.HttpServerErrorException$InternalServerError: 500 : "{"code":"SYSTEM_ERROR","message":"system error","data":null,"meta":null}"
```

```text
ERROR ... LocalDataSourceJobStore - ClusterManager: Error managing cluster: Failure obtaining db row lock: ERROR: relation "quartz.qrtz_locks" does not exist
ERROR ... SpringApplication - Application run failed
java.lang.IllegalStateException: failed to register quartz trigger: export_settlement_job
```

## 定位

1. `batch-console-api` 和 `batch-trigger` 的 REST 异常处理器之前只返回统一错误响应，没有统一 `log.error(...)`，所以前端拿到 500，但后端日志不完整。
2. 本地数据库虽然有 `batch` / `quartz` schema，但 Quartz 表没有完整初始化，导致 `batch-trigger` 不能正常启动。
3. 系统测试 seed 在重复执行时还不够幂等，曾出现主键/外键冲突。

## 影响

- 前端只能看到 `SYSTEM_ERROR`，无法快速定位后端根因。
- 触发、调度相关接口不可用或不稳定。
- 重复灌数、重启环境时容易把联调链路打断。

## 已处理

- 已给 `batch-console-api` 和 `batch-trigger` 的异常处理补上日志输出。
- 本地基础数据已重新灌入，数据库基础数据可用。

## 总结

这次联调问题主要是三件事叠加：

- 异常响应有了，但日志没跟上
- 本地环境 Quartz 表缺失
- seed 脚本幂等性不足

如果后续要继续压低联调成本，建议把 REST 全局异常日志、Quartz 初始化、seed 幂等性一起补齐。
## Flyway 与数据库现状核查

### 真实情况

- `batch_platform.flyway_schema_history` 目前只有 `V30`、`V31`、`V32` 三条记录，说明本地库是以 `V30` 基线接管后再继续增量迁移的。
- 目前 `batch` schema 下已经存在一批核心表，包括 `alert_event`、`job_definition`、`job_instance`、`job_task`、`workflow_definition`、`outbox_event`、`batch_day_instance`、`quartz.*` 等。
- `batch_business` 下目前只有示例业务表 `biz.customer_account`、`biz.settlement_batch`、`biz.settlement_detail`。

### 需要对齐的范围

后续前后端联调，建议把“系统所有用到的表”分成三类对齐：

1. `batch` 平台主表和运行表：配置、定义、运行、文件、告警、补偿、outbox、配额、批量日等。
2. `quartz` 调度元数据表：`qrtz_*` 全量表，保证 `batch-trigger` 能稳定注册和扫描触发器。
3. `biz` 业务示例表：`customer_account`、`settlement_batch`、`settlement_detail`，用于导入/导出联调。

### 为什么启动时没及时报错

- 启动自检是存在的，位于 `batch-orchestrator` 的 `StartupSelfCheck`。
- 它在 `ApplicationReadyEvent` 之后执行，只做日志输出，不会阻断启动。
- 当前自检只检查少量关键项：`batch`/`quartz` schema、`batch.batch_day_instance`、`batch.business_calendar`、以及 `V31` 相关列，不覆盖全部业务表，也不覆盖 `batch-trigger`、`batch-console-api` 的完整依赖。
- 因此，即使自检通过，也不代表系统所有联调用表都已经齐全。

### 优化建议

- 把联调前置校验扩展为“全量表存在性检查 + 关键列检查 + Quartz 初始化检查”。
- 对 `batch-trigger` 增加独立启动自检，不要只依赖 `orchestrator` 的自检。
- 把自检结果升级为可配置的启动门禁：本地可只 warn，联调/测试环境可 fail-fast。
- 统一 seed 和 migration 的幂等策略，避免重复执行时被主键/外键打断。

## 改成完整迁移一次

历史上曾用 `baseline-on-migrate`（例如基线到 `V30`）接管旧数据卷，容易导致「缺早期迁移表」的半残库。

当前约定：

- `application-local.yml` 保留 `baseline-on-migrate: true` 与 **`baseline-version: 1`（固定不变）**：只表示「V1 建 schema 已由 Docker init 预置」，**不要**把该数字改成当前最新迁移号（如 V34）
- Docker init 与 Flyway `V1` / `V30` 等 `IF NOT EXISTS` 兼容；Testcontainers 新库仍走完整迁移，与本地 baseline 语义独立
- 若旧数据卷状态混乱，仍建议 `docker compose down -v` 后重建

这样做的好处是：

- 避免“库里只有 `V30+`，但缺 `V1-V29` 表”的半残状态
- 让本地、测试、文档中的迁移链路保持一致
- 便于排查“到底是哪一个版本创建了哪一张表”

## 日志报错分析

### Console API 的报错

`batch-console-api` 的日志里主要有四类异常：

1. **数据库参数绑定失败**
   - 典型日志：`DataIntegrityViolationException` / `未设定参数值 5 的内容。`
   - 现象：控制台某些查询接口在组装 SQL 参数时缺参，最终被统一包装成 `SYSTEM_ERROR`。
   - 结论：这不是前端传参格式问题，而是后端 mapper 或调用链参数构造不完整，优先查 `FileArrivalGroupMapper.xml` 等相关查询。

2. **下游服务返回 500，但被上层重新包装成系统错误**
   - 典型日志：`HttpServerErrorException$InternalServerError: 500`，响应体为 `{"code":"SYSTEM_ERROR","message":"system error"...}`。
   - 现象：console 调用下游 REST 接口时，下游已经返回错误码，但 console 没有把下游响应语义透传给前端，而是统一转成了系统错误。
   - 结论：前端看到的是“统一 500”，但真实根因在 downstream，需要结合请求路径和 `requestId/traceId` 反查。

3. **下游业务冲突被转成 500**
   - 典型日志：`HttpClientErrorException$Conflict: 409`，message 是 `worker is decommissioned`。
   - 现象：这是可预期的业务冲突，但 console 当前仍以 `SYSTEM_ERROR` 形式返回。
   - 结论：建议把这类 409 保持为业务错误码，不要向前端降级成系统错误。

4. **不支持的方法被当成系统错误**
   - 典型日志：`HttpRequestMethodNotSupportedException: Request method 'GET' is not supported`。
   - 现象：前端调用了错误的 HTTP method，后端在统一异常处理里返回了系统错误。
   - 结论：应区分参数错误 / 方法不匹配 / 系统异常，前端联调时也要核对接口方法。

### Trigger 服务的报错

`batch-trigger` 的日志主要是 Quartz 初始化失败：

- `quartz.qrtz_locks`、`quartz.qrtz_triggers`、`quartz.qrtz_job_details` 不存在
- 随后触发器注册失败：`failed to register quartz trigger: export_settlement_job`

这说明触发服务启动时强依赖 Quartz 元数据表，而当前本地环境的表结构并没有按它需要的完整状态初始化。

### 为什么启动自检没有提前拦住

- `StartupSelfCheck` 确实会在 `ApplicationReadyEvent` 之后执行。
- 它只做日志输出，不会阻断启动。
- 它只检查少量关键项，未覆盖 `trigger` 的 Quartz 全量依赖，也没覆盖所有联调用表。

### 结论

当前日志暴露的问题不是单一接口报错，而是三层叠加：

- `console-api` 的异常处理缺少足够细的错误分流，导致很多下游问题都变成 `SYSTEM_ERROR`
- `trigger` 对 Quartz 表的依赖在本地没有被完整满足
- 启动自检存在，但它不是强校验门禁，覆盖范围也不够全

## 修复进展

已修复的联调问题：

- `console-api` 的 REST 异常处理补齐了 `log.error` / `log.warn`，系统异常会打印堆栈。
- `batch-console-api` 的 `file arrival group` 查询从 `metadata_json ? 'fileGroupCode'` 改为 `jsonb_exists(...)`，避免 MyBatis 把 JSONB `?` 运算符误判成参数占位符。
- `batch-orchestrator` 的启动自检扩展为检查 `quartz.QRTZ_*` 关键表，不再只看 schema。
- Quartz 官方 JDBC JobStore 建表脚本已纳入 `db/migration/V2__create_quartz_tables_postgres_2_5_2.sql`，本地空库启动会走全量迁移。
- 本地 `batch-orchestrator` / `batch-trigger` 的 `application-local.yml` 保留 `baseline-on-migrate`，`baseline-version` 固定为 `1`（勿随新迁移递增）。

仍需继续保持的事项：

- 新增迁移必须继续按版本号递增，不要回写旧版本。
- 业务 seed 和测试 seed 仍要尽量保持幂等，避免重复执行中断联调。


