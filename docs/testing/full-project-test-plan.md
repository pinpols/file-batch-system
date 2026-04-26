# 全项目完整功能与整体测试计划

> 包含原 full-project-full-project-test-plan.md 的测试分层建议（2026-04-26 合并）

## 0. 测试分层建议（来自旧 full-project-full-project-test-plan.md）


完整功能与整体测试实施计划见：`docs/testing/full-project-full-project-test-plan.md`
Phase 1 测试覆盖矩阵见：`docs/testing/phase-coverage.md`
统一回归入口与门禁说明见：`docs/testing/release-gate.md`

目标是覆盖核心链路和典型失败场景，但不把测试数量扩成全组合穷举。建议按三层拆分：单元测试负责纯逻辑，集成测试负责模块协作，端到端测试只保留少量主路径。

## 单元测试

只测不依赖外部系统的逻辑，重点放在分支多、规则密、回归风险高的地方。

- 状态机和枚举流转：`job_instance`、`job_partition`、`job_task`、`pipeline_instance`、`file_record`、`approval_command`、`dead_letter_task`、`retry_schedule`
- 调度规则：窗口、日历、catch-up、`quota_reset_policy`、租约回收、WAITING 出队、Worker 排空判定
- 文件链规则：模板解析、字段映射、脱敏开关、加密开关、导出格式选择、`cursor / keyset` 翻页
- 分发规则：通道路由、`config_json` 合并保护、registry 重复 id、回执策略判定、健康状态切换
- 工具类：idempotency、dedup、错误码转换、掩码规则、分页游标推进

## 集成测试

只测真实依赖下的模块协作，建议用 Postgres、Kafka、MinIO，再加少量 HTTP mock。

### 推荐基建

这个仓库的集成测试，建议统一收口到一套基础测试类：

- `AbstractIntegrationTest`
- `PostgreSQLContainer`
- `KafkaContainer`
- `MinIOContainer`
- `DynamicPropertySource`

这套基建的目标不是替代所有测试，而是把“真实依赖怎么起、连接串怎么注入、bucket/topic 怎么初始化”统一掉，避免每个模块各写一套测试启动逻辑。

建议约定如下：

- `AbstractIntegrationTest` 负责容器生命周期、公共测试工具和通用断言
- `PostgreSQLContainer` 提供平台库和业务库所需的真实 PostgreSQL
- `KafkaContainer` 提供真实 broker，验证 producer/consumer、序列化、topic 配置和消费位点
- `MinIOContainer` 提供对象存储，验证上传、下载、copy、delete 和 bucket 初始化
- `DynamicPropertySource` 负责把容器地址注入 Spring 环境，避免测试代码硬编码端口

落地方式建议：

- `batch-orchestrator`、`batch-worker-dispatch` 这类同时依赖 DB / Kafka / MinIO 的模块，直接继承 `AbstractIntegrationTest`
- 只依赖其中一部分基础设施的模块，也优先复用同一个基类，再按需启用对应断言
- 集成测试中尽量不 mock 数据库、Kafka 和 MinIO，本地的 fake 只保留给纯逻辑单元测试

测试初始化上，建议在基类里统一做这几件事：

- 为 PostgreSQL 执行 Flyway 或最小 schema 初始化
- 为 Kafka 预创建必要 topic
- 为 MinIO 预创建 bucket，例如开发桶和测试桶
- 提供通用清理方法，避免测试之间的对象、消息和表数据互相污染

### 使用方式

新增集成测试时，建议直接继承 `AbstractIntegrationTest`，并在测试类上使用统一注解 `@BatchIntegrationTest`。

推荐写法如下：

```java
@BatchIntegrationTest
@SpringBootTest(classes = BatchOrchestratorApplication.class)
class OutboxPublishIntegrationTest extends AbstractIntegrationTest {

    @Test
    void shouldPublishOutboxEventToKafka() {
        // test body
    }
}
```

约束建议：

- 子类不要重复声明 `@Testcontainers`、`@Tag("integration")`、`@ActiveProfiles("test")`
- 子类不要自己 new `PostgreSQLContainer`、`KafkaContainer`、`MinIOContainer`
- 子类只关注业务断言，不要在每个类里重复写 `DynamicPropertySource`
- 如果某个模块只需要部分依赖，也仍然优先复用这套基类，再按需覆盖业务断言

如果测试类需要额外的 topic 或 bucket，建议在测试方法里显式创建，或者补到对应模块的测试辅助类里，不要把这些初始化逻辑散落在每个测试类内部

- `batch-orchestrator`
  - Outbox -> Kafka
  - Retry scheduler -> retry / dead letter 推进
  - Partition lease reclaim
  - Quota runtime reset
  - Worker drain timeout
  - SLA / alert 落库
- `batch-worker-import`
  - 入口扫描 -> parse -> validate -> load
  - 成功样本、校验失败样本、流式大文件样本
- `batch-worker-export`
  - business table -> generate -> store -> register
  - 成功样本、加密样本、大分页样本
- `batch-worker-dispatch`
  - API / API_PUSH
  - NAS / OSS
  - EMAIL / SFTP
  - receipt poll、health probe、circuit breaker
- `batch-console-api`
  - 查询、下载、审批、DLQ replay

## 端到端测试

只保留 4 条主路径，避免测试爆炸。

- 导入主链路：上游文件 -> 扫描 -> parse -> validate -> load -> 业务表落库
- 导出主链路：业务表 -> 生成文件 -> 加密/存储 -> 注册 -> 分发 -> 回执
- 补偿审批链路：失败任务 -> 审批 -> replay / compensation 成功
- 治理闭环链路：失败分发 / DLQ / alert / retry / health probe

## 建议配比

- 单元测试：最多，覆盖约 60% 的逻辑面
- 集成测试：中等，覆盖约 25% 的模块协作
- 端到端测试：少而精，覆盖约 15% 的核心链路

## 当前状态（截至 2026-04-08）

三层测试体系和统一回归入口已落地：

1. ✅ 单元测试：146 个（覆盖状态机、调度规则、文件链、安全、加解密、触发链路与 Worker 纯逻辑；含 PathSanitizer、DatabaseIdempotencyGuard、DeadLetterPublisher 等新增类）
2. ✅ 集成测试：59 个（含 Testcontainers 主链路协作、ShedLock 配置校验、应用启动 smoke）
3. ✅ 端到端测试：15 个 E2E（主链路、失败分支、内容验证、Outbox 轮询与重试、多租户并发、dedup 幂等、Worker 排空、死信审批重放）
4. ✅ SQL 一致性守卫：`SqlConsistencyIT`（批量调度器门禁）
5. ✅ 统一回归入口：`scripts/ci/run-full-regression.sh`
6. ✅ 部署 smoke：Helm `lint + template`，并已具备可选 live rollout / readiness 校验逻辑

下一步建议方向：真实 staging 集群 live deploy smoke、回滚 smoke、压测基线实测与回填。

---


Phase 1 盘点结果见：`docs/testing/phase-coverage.md`
Phase 2 P0 回归范围见：`docs/testing/phase-coverage.md`
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

- 单元测试、集成测试、E2E 的分层策略文档：`docs/testing/full-project-full-project-test-plan.md`
- 三条主链路与失败分支的 E2E 覆盖分析：`docs/testing/e2e-coverage.md`
- 本地 E2E 执行脚本：`scripts/local/run-tests.sh --e2e`
- 统一回归入口：`scripts/ci/run-full-regression.sh`
- 门禁与 staging 说明：`docs/testing/release-gate.md`
- 压测模块与容量基线文档：`load-tests/`、`docs/testing/load-test-capacity-baseline.md`
- 本地巡检与自愈脚本：`scripts/ops/inspect-*.sh`、`scripts/ops/heal-*.sh`
- Helm 生产部署产物：`helm/batch-platform/`
- Phase 1 盘点矩阵：`docs/testing/phase-coverage.md`
- Phase 2 P0 回归范围：`docs/testing/phase-coverage.md`
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

已完成，见 `docs/testing/phase-coverage.md`。

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

- `docs/testing/phase-coverage.md`

### Phase 2：P0 功能回归补齐（已完成首轮）

Phase 2 的详细收口内容已拆到 `docs/testing/phase-coverage.md`。

本节只保留结论：

- 已完成首轮 P0 收口
- 已补齐 `batch-trigger` 首批门禁、接口层校验和 Quartz catch-up 修正
- 已补齐 `batch-console-api` 权限/租户负向测试、并发 claim、Kafka 外部渠道失败恢复
- 后续增量项仍是 `batch-worker-core` 协作测试、dedup 幂等冲突、Kafka 重试耗尽和外部渠道进一步验证

当前可执行入口：

- `scripts/ci/run-full-regression.sh`
- `docs/testing/phase-coverage.md`

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

1. 先跑 `docs/testing/release-gate.md`，确认环境与部署链路正常
2. 再按单故障注入顺序演练数据库、Kafka、MinIO、外部渠道
3. 最后补组合故障和恢复验收，确认 backlog 能回收、任务能继续推进、告警和日志可定位

交付物：

- `docs/testing/failure-drill-report.md`
- 演练记录中的故障时间线
- 恢复验证结果和残余风险清单
- 必要时回填 `docs/testing/release-gate.md` 的边界说明

当前与 Phase 3 直接相关的现有资产：

- `docs/testing/release-gate.md`
- `scripts/ci/run-full-regression.sh --with-deploy-smoke`
- `docs/testing/release-gate.md`
- `docs/testing/phase-coverage.md`

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

- 已完成：`phase-coverage.md`
- 已完成：`full-project-full-project-test-plan.md` 的 Phase 2 节
- 已完成（staging 留档待回填）：`failure-drill-report.md`
- 已完成（staging 留档待回填）：`load-test-capacity-baseline.md`
- 已完成（staging 留档待回填）：`deployment-verification-report.md`
- 已完成：`release-gate.md`
- 已完成：`release-gate.md`
- 已完成：统一执行脚本与流水线配置

## 一句话结论

当前项目已经完成测试基础建设、首轮 P0 收口，以及 Phase 3/4/5 的工具链与报告框架，Phase 6 门禁收口也已经接到 CI / staging workflow。

下一阶段的重点不是再零散补测试类，而是把故障演练、压测基线、部署验证和发布门禁在 staging 里真正跑通并留档。当前未完成项主要集中在 staging 证据和最终发布留痕，不是测试骨架缺失。
