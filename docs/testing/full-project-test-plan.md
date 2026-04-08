# 全项目完整功能与整体测试计划

Phase 1 盘点结果见：`docs/testing/phase-1-test-coverage-matrix.md`
Phase 2 P0 回归范围见：`docs/testing/phase-2-functional-regression.md`
统一回归入口与门禁说明见：`docs/testing/release-gate.md`

## 目标

本计划用于把当前仓库已有的单元测试、集成测试、E2E、压测脚本、部署产物和巡检脚本，收口为一套可执行、可验收、可作为发布门禁的完整测试方案。

目标不是继续零散补测试类，而是形成：

- 功能覆盖矩阵
- 统一回归入口
- 故障演练与恢复验证
- 压测与容量基线
- 部署升级回滚验证
- 发布门禁与测试报告

## 当前基础

仓库内已具备以下基础资产，且其中大部分已落地完成：

- 单元测试、集成测试、E2E 的分层策略文档：`docs/testing/test-strategy.md`
- 三条主链路与失败分支的 E2E 覆盖分析：`docs/testing/e2e-three-flows-coverage.md`
- 本地 E2E 执行脚本：`scripts/local/run-e2e-tests.sh`
- 统一回归入口：`scripts/ci/run-full-regression.sh`
- 门禁与 staging 说明：`docs/testing/release-gate.md`
- 压测模块与容量基线文档：`load-tests/`、`docs/testing/load-test-capacity-baseline.md`
- 本地巡检与自愈脚本：`scripts/local/inspect-*.sh`、`scripts/local/heal-*.sh`
- Helm 生产部署产物：`helm/batch-platform/`
- Phase 1 盘点矩阵：`docs/testing/phase-1-test-coverage-matrix.md`
- Phase 2 P0 回归范围：`docs/testing/phase-2-functional-regression.md`
- console-api 核心 HTTP smoke：`batch-console-api/src/test/java/com/example/batch/console/integration/ConsoleHttpIntegrationIT.java`
- console-api Excel 导入/导出 controller 测试：`ConsoleFileTemplateExcelControllerTest`、`ConsoleFileChannelExcelControllerTest`、`ConsoleWorkflowExcelControllerTest`、`ConsoleJobDefinitionExcelControllerTest`、`ConsoleReportExcelControllerTest`
- console-api Excel 导入/导出 service 负向测试：`DefaultConsoleFileTemplateExcelApplicationServiceTest`、`DefaultConsoleFileChannelExcelApplicationServiceTest`、`DefaultConsoleWorkflowExcelApplicationServiceTest`、`DefaultConsoleJobDefinitionExcelApplicationServiceTest`
- console-api 安全负向：`batch-console-api/src/test/java/com/example/batch/console/config/ConsoleSecurityConfigurationTest.java`
- console-request tenant mismatch：`batch-console-api/src/test/java/com/example/batch/console/support/ConsoleRequestContextFilterTest.java`
- orchestrator 并发 claim：`batch-orchestrator/src/test/java/com/example/batch/orchestrator/integration/ConcurrentTaskClaimIntegrationTest.java`
- orchestrator 外部渠道失败恢复：`batch-orchestrator/src/test/java/com/example/batch/orchestrator/infrastructure/mq/KafkaOutboxPublisherTest.java`

当前统一口径（截至 2026-04-08）：

- **247 个测试相关文件**（146 单元 + 59 集成 + 30 E2E + 支撑类）

这说明项目已经完成了测试基线盘点和首轮 P0 回归收口。当前状态可以拆成两类：

- 已完成：测试基线盘点、统一回归入口、P0 首轮收口、console-api Excel / HTTP smoke 收口、Phase 6 门禁接入 CI / staging workflow
- 未完成：真实 staging 实跑留档、故障演练完整报告、压测基线数值回填、真实发布记录闭环、`helm upgrade --install --atomic` 失败观测

## 仍需补齐的事情

### 1. 测试范围矩阵

已完成，见 `docs/testing/phase-1-test-coverage-matrix.md`。

当前要做的是持续维护矩阵和增量缺口，而不是重新起一份新矩阵。

### 2. 统一全量回归入口

已完成统一脚本收口，且已接入对应说明文档与 workflow。

现有统一脚本 `scripts/ci/run-full-regression.sh` 已包含：

- Maven 单元与集成测试
- E2E 主链路与失败分支
- SQL 一致性守卫
- 压测 smoke
- 部署 smoke

已完成：

- `scripts/ci/run-full-regression.sh` 的统一入口已成型
- `docs/testing/release-gate.md`、`.github/workflows/staging-gate.yml` 已接入门禁

未完成：

- 真实 staging kube context 的 live deploy smoke 留档
- 将执行结果固定为发布记录的一部分

### 3. P0 功能缺口补齐

首轮已补齐批量回归范围，剩余仍需持续强化最容易出线上事故的场景：

- 同 task 并发 claim 竞争
- dedup key 幂等冲突
- Kafka 重复投递与重试耗尽
- 补偿审批链路
- 租户越权与跨租户串数据
- 文件加解密与敏感数据开关
- 外部渠道失败与恢复
- Flyway 升级兼容

已完成的补强项：

- `batch-console-api` 权限/租户负向测试
- 同 task 并发 claim 竞争专项
- Kafka 外部渠道失败与恢复专项（补了 `KafkaOutboxPublisher` 失败分支）

### 4. 整体故障演练

要从“功能测试”扩展到“系统测试”，仍需覆盖：

- PostgreSQL 短暂不可用
- Kafka broker 波动 / backlog 恢复
- MinIO 不可用或写失败
- Worker drain / restart / graceful shutdown
- Outbox 卡住后的恢复
- 死信重放与人工补偿
- 定时任务在多实例下的行为

已完成的本地可执行项：

- `docs/testing/failure-drill-report.md`
- `scripts/ci/run-full-regression.sh --skip-default-tests --skip-it-suite --with-deploy-smoke`

未完成：

- 真实 staging 故障注入
- 回滚观测与组合故障验收

### 5. 压测与容量基线实测

`load-tests/` 已有 Gatling Simulation，但还缺 staging 或 prod-like 的实测结果回填。

需要完成：

- `JobLaunchSimulation`
- `ConsoleQuerySimulation`
- `CapacityBaselineSimulation`

并将结果回填到 `docs/testing/load-test-capacity-baseline.md`。

### 6. 部署、升级与回滚验证

完整测试必须覆盖交付物本身，而不只是业务代码。

当前已落地：

- Helm `lint + template` 静态 deploy smoke
- 可选 live `helm upgrade --install --wait`
- `kubectl rollout status`
- `port-forward + /actuator/health/readiness`

仍需验证：

- Helm 安装
- 配置加载与 Secret 注入
- readiness / liveness / startup probes
- 滚动升级
- Flyway 升级顺序
- 新旧版本兼容窗口
- 回滚步骤与恢复手册

### 7. 报告与门禁

完整测试最终要有发布结论，而不是只看命令 exit code。

需要输出：

- 测试报告
- 缺陷清单
- 残余风险清单
- 压测基线
- 发布建议

## 实施计划

### Phase 1：测试基线盘点（已完成）

输出功能矩阵，标出：

- 已覆盖
- 部分覆盖
- 未覆盖
- P0 / P1 / P2 优先级

交付物：

- `docs/testing/phase-1-test-coverage-matrix.md`

### Phase 2：P0 功能回归补齐（已完成首轮）

Phase 2 的详细收口内容已拆到 `docs/testing/phase-2-functional-regression.md`。

本节只保留结论：

- 已完成首轮 P0 收口
- 已补齐 `batch-trigger` 首批门禁、接口层校验和 Quartz catch-up 修正
- 已补齐 `batch-console-api` 权限/租户负向测试、并发 claim、Kafka 外部渠道失败恢复
- 后续增量项仍是 `batch-worker-core` 协作测试、dedup 幂等冲突、Kafka 重试耗尽和外部渠道进一步验证

当前可执行入口：

- `scripts/ci/run-full-regression.sh`
- `docs/testing/phase-2-functional-regression.md`

### Phase 3：系统联调与故障演练（待完成）

目标不是再补一层普通功能测试，而是用真实依赖把主链路、失败分支和恢复路径跑通，确认仓库里已有的单测 / 集成测试 / E2E 在系统层也成立。

建议覆盖的演练面：

- PostgreSQL 短暂不可用、重连、事务回滚
- Kafka broker 波动、消息积压、Outbox 恢复
- MinIO 不可用、对象写失败、重试恢复
- Worker restart、drain、graceful shutdown、租约回收
- WireMock / 外部渠道 mock 的超时、5xx、重试耗尽
- 回滚路径上的业务可恢复性

推荐执行顺序：

1. 先跑 `docs/testing/staging-live-deploy-smoke-checklist.md`，确认环境与部署链路正常
2. 再按单故障注入顺序演练数据库、Kafka、MinIO、外部渠道
3. 最后补组合故障和恢复验收，确认 backlog 能回收、任务能继续推进、告警和日志可定位

交付物：

- `docs/testing/failure-drill-report.md`
- 演练记录中的故障时间线
- 恢复验证结果和残余风险清单
- 必要时回填 `docs/testing/release-gate.md` 的边界说明

当前与 Phase 3 直接相关的现有资产：

- `docs/testing/staging-live-deploy-smoke-checklist.md`
- `scripts/ci/run-full-regression.sh --with-deploy-smoke`
- `docs/testing/release-gate.md`
- `docs/testing/phase-1-test-coverage-matrix.md`

当前已完成的 Phase 3 本地可执行项：

- `docs/testing/failure-drill-report.md`
- `scripts/ci/run-full-regression.sh --skip-default-tests --skip-it-suite --with-deploy-smoke`

说明：这部分已经落成，但真实 staging 故障注入与回滚观测仍需在目标环境执行，因此 Phase 3 仍然保留为“待完成”的 staging 门禁项。

### Phase 4：压测与容量基线（工具链已完成，基线待回填）

在 staging 或 prod-like 环境执行 Gatling 压测，回填：

- p50 / p95 / p99
- 吞吐
- 错误率
- DB 连接池等待
- backlog 恢复时长

交付物：

- 更新 `docs/testing/load-test-capacity-baseline.md`

当前状态：

- `load-tests` 模块已通过 `test-compile`
- `JobLaunchSimulation`、`ConsoleQuerySimulation`、`CapacityBaselineSimulation` 已可编译
- 已完成压测工具链收口
- 未完成真实 staging / prod-like 的容量数值回填与门禁留档

### Phase 5：部署升级回滚验证（工具链已完成，staging 待回填）

验证 Helm、Flyway、滚动升级、回滚、恢复。

交付物：

- `docs/testing/deployment-verification-report.md`

当前状态：

- `scripts/ci/run-full-regression.sh` 已新增 `--with-deployment-verification`
- 已补齐升级 / 回滚验证的执行路径
- 已完成本地和静态验证入口
- 未完成真实 staging 执行结果、回滚留档和业务验收

### Phase 6：门禁收口（已完成收口，staging 留档待回填）

把完整回归接入 CI / staging gate，形成发布前固定流程。

交付物：

- `scripts/ci/run-full-regression.sh`，已完成
- `docs/testing/release-gate.md`，已完成
- `.github/workflows/staging-gate.yml`，已完成

已完成：

- 脚本、workflow 与说明已经接通
- 门禁链路已经落到 CI / staging workflow

未完成：

- 真实 staging 结果回填
- 发布记录闭环

## 推荐执行顺序

### 本地开发阶段

- Maven 单元测试与集成测试
- E2E 主链路
- SQL 一致性守卫

### 每次合并前

- 受影响模块单元测试
- 受影响模块集成测试
- 至少一轮 E2E smoke

### Staging 发布前

- 全量回归
- 故障演练
- 压测 smoke
- Helm 部署 smoke
- 真实 staging live rollout / readiness 校验

### 正式发布前

- 容量基线
- 升级与回滚验证
- 放行评审

## 发布门禁建议

满足以下条件才允许正式发布：

1. P0 功能测试 100% 通过
2. 无 blocker / critical 缺陷
3. E2E 主链路、失败分支、多租户隔离全部通过
4. 压测达到约定阈值，容量基线已填写
5. 升级、回滚、恢复演练通过
6. 关键日志、审计、告警、指标可观测

## 最终交付物清单

- 已完成：`phase-1-test-coverage-matrix.md`
- 已完成：`full-project-test-plan.md` 的 Phase 2 节
- 已完成（staging 留档待回填）：`failure-drill-report.md`
- 已完成（staging 留档待回填）：`load-test-capacity-baseline.md`
- 已完成（staging 留档待回填）：`deployment-verification-report.md`
- 已完成：`release-gate.md`
- 已完成：`staging-live-deploy-smoke-checklist.md`
- 已完成：统一执行脚本与流水线配置

## 一句话结论

当前项目已经完成测试基础建设、首轮 P0 收口，以及 Phase 3/4/5 的工具链与报告框架，Phase 6 门禁收口也已经接到 CI / staging workflow。

下一阶段的重点不是再零散补测试类，而是把故障演练、压测基线、部署验证和发布门禁在 staging 里真正跑通并留档。当前未完成项主要集中在 staging 证据和最终发布留痕，不是测试骨架缺失。
