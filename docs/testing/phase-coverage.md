# Phase 1/2 测试覆盖矩阵（按阶段分章合并）

## Phase 1：P1 功能回归补齐

# 全项目测试覆盖矩阵（Phase 1）

更新时间：2026-04-08

## 目标

本文件是 Phase 1 的正式交付物，用于回答三个问题：

- 当前仓库到底有哪些真实测试资产
- 哪些模块和能力已经有门禁，哪些还没有
- Phase 2 应该先补哪里，优先级怎么排

盘点范围：

- `src/test/java` 下的单元测试、集成测试、E2E 测试和测试支撑类
- `load-tests/` 压测模块
- `scripts/local/` 本地回归、巡检、自愈脚本
- `helm/batch-platform/` 部署产物
- 根 `pom.xml` 的 reactor 模块

## 盘点结论

### 结论 1：核心链路已有较强覆盖，但覆盖分布明显不均衡

`batch-orchestrator`、`batch-worker-import`、`batch-worker-export`、`batch-worker-dispatch`、`batch-console-api`、`batch-e2e-tests` 已形成可用的测试骨架，核心主链路不是空白状态。

### 结论 2：`batch-trigger` 的 Phase 2 P0 缺口已补齐第一轮门禁

`batch-trigger` 现有 14 个测试文件，已覆盖请求校验、dedup 写入数据库、Quartz misfire/catch-up、调度元数据透传、启动加载和基础 DB 协作。剩余缺口主要是 trigger 专属 E2E 与多实例 Quartz 集群行为验证。

### 结论 3：项目已有统一回归入口，并已落地三层 GitHub Actions workflow

仓库里现已具备 `scripts/ci/run-full-regression.sh`、`docs/testing/release-gate.md`、`.github/workflows/pr-gate.yml`、`.github/workflows/full-ci-gate.yml` 和 `.github/workflows/staging-gate.yml`。三层门禁已经成型并完成接入：PR Gate 跑受影响模块默认测试，Full CI Gate 跑仓库级默认测试 + `*IT`，Staging Gate 跑 deploy smoke + deployment verification + load smoke + 巡检。当前未完成项转为：真实 staging 集群实跑留档和 `helm upgrade --install --atomic` 失败观测。

### 结论 4：压测和部署验证资产已形成最小自动化，但 staging 实跑证据仍缺

`load-tests/` 已有 3 个 Gatling Simulation，但不在根 reactor 中，容量基线表也仍是待填写状态。部署侧已落地 Helm `lint + template` 静态 smoke，以及可选 live rollout / readiness / rollback 逻辑；当前已完成门禁入口，未完成项是真实 staging 集群实跑留档和 atomic 失败观测。

### 结论 5：测试口径已统一到当前仓库基线

当前统一口径为：**247 个测试相关文件**（146 单元 + 59 集成 + 30 E2E + 支撑类）。后续文档引用应以本文件和 `docs/testing/full-project-full-project-test-plan.md` 为准。

## 当前测试资产总览

| 模块 | 真实测试资产 | 当前判断 |
|---|---:|---|
| `batch-trigger` | 14 个测试文件 | P0 首批门禁已具备 |
| `batch-common` | 27 个测试相关文件 | 基础能力覆盖较好，PathSanitizer / CatchUpPolicyType / WorkflowJoinMode 已补测试 |
| `batch-orchestrator` | 66 个测试相关文件 | 覆盖最完整，DatabaseIdempotencyGuard / TaskDispatchOutboxService / DefaultTaskOutcomeService 已补测试 |
| `batch-worker-core` | 10 个测试文件 | 纯逻辑覆盖，DeadLetterPublisher 已补测试 |
| `batch-worker-import` | 12 个测试文件 | 主链路覆盖较好，ReceiveStepPayloadSizeLimit 已补测试 |
| `batch-worker-export` | 13 个测试文件 | 主链路覆盖较好 |
| `batch-worker-dispatch` | 11 个测试文件 | 主链路可用，DispatchFileContentResolverPath / RemoteFilesystemNasPath 已补测试 |
| `batch-console-api` | 56 个测试相关文件 | 查询、审计与 Excel 维护较强，HTTP smoke + 安全负向 + Excel 维护与报表导出已补齐 |
| `batch-e2e-tests` | 32 个测试相关文件 | 端到端骨架已成型，DeadLetterApprovalReplay E2E 已补齐 |
| `load-tests` | 5 个文件（3 个 Gatling Simulation + 2 个支撑/配置文件） | smoke 与 CI 编译门禁已接入 |

## 模块覆盖矩阵

说明：

- `有`：仓库内已存在直接测试资产
- `部分`：存在间接覆盖，或只覆盖了主路径
- `无`：未发现对应层级资产

| 模块 | 关键能力 | 单测 | 集成测试 | E2E | 压测/演练 | 当前判断 | 主要缺口 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| `batch-trigger` | `launch API`、dedup、catch-up、Quartz 注册、misfire、Orchestrator 适配 | 有 | 有 | 部分 | 无 | 主入口已有首批门禁 | 仍缺 trigger 专属 E2E；缺 Quartz 多实例/集群行为验证 | P1 |
| `batch-common` | 枚举、状态机工具、SQL 校验、内容脱敏、对象加解密、测试基建 | 有 | 无 | 间接 | 无 | 基础层较稳定 | 公共测试基建本身缺少自检；ShedLock 工厂等新公共能力仍可补最小回归 | P2 |
| `batch-orchestrator` | 调度、DAG、审批、补偿、重试、Outbox、配额、SLA、多租户、ShedLock | 有 | 有 | 有 | 部分 | 当前覆盖最强 | 仍缺故障演练、升级/回滚验证、容量实测 | P1 |
| `batch-worker-core` | consumer loop、背压、租约、drain、执行包装、临时文件清理 | 有 | 无 | 部分 | 无 | 逻辑层覆盖够，系统层偏弱 | 缺真实 Kafka listener/backpressure/drain/restart 协作测试 | P1 |
| `batch-worker-import` | scanner、preprocess、parse、validate、load、KMS/RSA、ShedLock | 有 | 有 | 有 | 部分 | 主链路覆盖较好 | 缺更强的失败恢复和大文件容量边界验证 | P1 |
| `batch-worker-export` | prepare、generate、store、register、MinIO、ShedLock | 有 | 有 | 有 | 部分 | 主链路覆盖较好 | 缺容量基线、部署后存储路径 smoke、更多异常恢复演练 | P1 |
| `batch-worker-dispatch` | gateway、circuit breaker、health probe、receipt poll、stage executor、ShedLock | 有 | 有 | 有 | 部分 | 主链路可用 | 缺更贴近真实外部渠道的集成验证；SFTP/EMAIL/OSS 类场景仍偏 mock 化 | P1 |
| `batch-console-api` | 查询、审批、AI 审计、DLQ、retry schedule、告警、Excel 维护、报表导出 | 有 | 有 | 部分 | 无 | 查询面覆盖不错，HTTP smoke + 权限/租户负向 + Excel 维护与报表导出已补齐 | 仍可继续补少量查询负向与异常边界 | P1 |
| `batch-e2e-tests` | Import/Export/Dispatch 主链路、失败分支、Outbox、dedup、多租户 | 不适用 | 不适用 | 有 | 无 | 可以承担核心回归 | 仍缺部署后 smoke 场景 | P1 |
| `load-tests` | trigger 写入、console 查询、混合基线压测 | 不适用 | 不适用 | 不适用 | 有 | smoke 与 CI 编译门禁已接入 | 仍不在 root reactor；容量基线表未填写 | P1 |
| 部署与发布门禁 | Helm、启动脚本、巡检、自愈、升级、回滚 | 无 | 无 | 无 | 有 | 三层 workflow 已形成，部署验证已接入 | 已完成门禁接入；未完成真实 staging 实跑留档和 `--atomic` 失败观测 | P0 |

## 跨模块门禁盘点

| 主题 | 当前证据 | 现状 | 结论 |
|---|---|---|---|
| 根 reactor | 根 `pom.xml` 已纳入 8 个业务模块 + `batch-e2e-tests` | `load-tests` 不在 reactor 内 | 完整回归不会自动覆盖压测 |
| E2E 执行入口 | `scripts/local/run-tests.sh --e2e` + `scripts/ci/run-full-regression.sh` | 本地 E2E 与完整回归入口都已存在 | 可以承担本地和 CI/staging 两类场景 |
| 巡检/自愈 | `inspect-*.sh`、`heal-*.sh` 已存在 | 偏运维辅助，不是测试报告 | 可作为 Phase 3 演练资产 |
| 部署产物 | `helm/batch-platform/`、`helm/values-prod.yaml`、`scripts/ci/run-full-regression.sh --with-deploy-smoke` | 静态 deploy smoke 已自动化，live rollout/readiness 逻辑已具备 | 仍缺真实 staging 实跑与回滚验证 |
| 部署验证 | `scripts/ci/run-full-regression.sh --with-deployment-verification`、`docs/testing/deployment-verification-report.md` | 升级 / 回滚执行链路已接入 | 仍缺真实 staging 留档和 `--atomic` 失败观测 |
| 压测资产 | `JobLaunchSimulation`、`ConsoleQuerySimulation`、`CapacityBaselineSimulation` | 仅有脚本和空白基线表 | 仍缺实测数据和流水线接入 |
| CI 工作流 | `.github/workflows/pr-gate.yml`、`.github/workflows/full-ci-gate.yml`、`.github/workflows/staging-gate.yml` 已存在；未发现 `.gitlab-ci.yml`、`Jenkinsfile` | 三层门禁已落地 | 下一步重点转为真实 staging 执行与回滚验证 |
| 文档一致性 | 本轮已统一到 156 / 76 / 27 / 42 口径 | 以本文件、`full-project-full-project-test-plan.md`、`release-gate.md` 为主 | 后续新增测试时需同步更新 |

## 说明

本文件只保留覆盖盘点和缺口分布。具体执行顺序、阶段推进、已完成 / 未完成状态和后续输入清单见 `docs/testing/full-project-full-project-test-plan.md`。

---

## Phase 2：P0 功能回归补齐


更新时间：2026-03-28

## 当前状态

Phase 2 已收敛完成。

收敛结果：

- `batch-trigger` 首批 P0 门禁已补齐
- 统一回归入口 `scripts/ci/run-full-regression.sh` 已落地
- `batch-console-api` 历史集成测试失败已清零
- `batch-e2e-tests` 历史失败已清零
- 仓库级最终复跑结果见 `docs/testing/full-test-run-report.md`

## 目标

本文件用于保留 Phase 2 的 P0 收敛清单和后续增量项，便于单独查阅和维护。

## 已完成的首轮收敛

### `batch-trigger` 首批门禁

已落地：

- `DefaultTriggerServiceTest`
- `DefaultLaunchAdapterServiceTest`
- `QuartzLaunchJobTest`
- `TriggerSchedulerFacadeTest`
- `TriggerControllerTest`
- `TriggerServiceIntegrationIT`

覆盖点：

- API 请求校验、缺少幂等键、自动生成 `requestId/traceId`
- trigger request 幂等写入数据库与重复提交去重
- catch-up 审批通过后的状态推进
- Quartz misfire 后的自动补跑 / 人工审批分支
- Quartz 注册时 `catchUpPolicy` / `catchUpMaxDays` 元数据透传
- `batch-trigger` 与真实 PostgreSQL 的基本协作

### 接口层校验补强

已修正：

- `TriggerLaunchRequest` 补齐字段校验注解
- `TriggerApiExceptionHandler` 补齐 `MethodArgumentNotValidException`
- `TriggerApiExceptionHandler` 统一处理 `ConstraintViolationException` 和回退 500

### Quartz catch-up 策略修正

已修正：

- `TriggerSchedulerFacade` 注册任务时补充 `calendarCode`、`catchUpPolicy`、`catchUpMaxDays`
- `QuartzLaunchJob` 执行时恢复这些元数据并参与 misfire 决策

### 统一回归入口

已落地：

- `scripts/ci/run-full-regression.sh`

当前脚本能力：

- reactor 默认测试（`*Test` / `*IntegrationTest`）
- reactor 显式 `*IT` 套件，包含需要单独触发的集成测试和 E2E
- 可选 Gatling load smoke
- 可选 Helm deploy smoke
- 可选巡检脚本收尾
- 可选 live deploy smoke：`helm upgrade --install --wait` + rollout + readiness

## 仍建议继续补强的 P0

- `batch-worker-core` 真实 listener / backpressure / drain 协作测试
- dedup key 幂等冲突专项
- Kafka 重复投递与重试耗尽专项
- 外部渠道失败与恢复专项

## 本轮新增补强

- `batch-console-api` 权限/租户负向测试
- 同 task 并发 claim 竞争专项
- Kafka 外部渠道失败与恢复专项（补了 `KafkaOutboxPublisher` 失败分支）
- Worker drain / worker registry 状态保持修正
- 多租户并发 E2E 隔离补种子与断言修正
- 导出 / outbox E2E 断言与种子修正
- 共享调度器关闭策略调整，清除 surefire 强杀噪音

## 补充说明

- `batch-worker-core` 已有 backpressure、worker loop、lease/wrapper 的单测基础，仍可继续补真实 listener 级协作测
- `dedup key` 和 retry exhausted 已有 service/mapper 级覆盖，后续若继续扩展，优先补 E2E 或真实依赖链路
