# 测试分层建议

完整功能与整体测试实施计划见：`docs/testing/full-project-test-plan.md`
Phase 1 测试覆盖矩阵见：`docs/testing/phase-1-test-coverage-matrix.md`
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
