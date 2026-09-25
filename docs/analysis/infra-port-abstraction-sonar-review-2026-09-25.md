# 基础设施端口化与 Sonar 增量复核结论（2026-09-25）

## 结论

当前 `feature/infra-port-abstractions` 分支的 Redis / MQ / JDBC 直连收敛方向是正确的；本轮改动没有改动 launch / dispatch / claim / report / outbox 主链状态机 SQL，也没有改变 Kafka 消费端 offset 语义。已完成的证据支持“代码结构与编译层面未破坏主链”，但未覆盖真实 Kafka / Redis / PostgreSQL 运行时回归。

Sonar 增量复扫已清零：`reports/sonar/2026-09-25_10-08-44/sonar-incremental-report.md` 显示变更行 OPEN Issue 为 `0`，Security Hotspot 为 `0`。

## 本轮修复范围

### 端口化边界

- Console Redis 能力已改为端口 + infrastructure 实现：
  - 配置缓存失效
  - 实时事件发布 / replay
  - 会话与单点登录版本
  - 登录失败滑动窗口
  - token revocation
  - idempotency
  - rate limit
  - workflow design lock
  - SSE ticket
  - system parameter cache
- Orchestrator 数据直连已收敛：
  - dry-run SQL probe 从 application 拆到 `infrastructure/dryrun`
  - DQ SQL rule probe 从 application 拆到 `infrastructure/dataquality`
  - DB row sensor policy 移到 `infrastructure/sensor`
- MQ 发布端已通过 `MqMessagePublisher` 端口隔离 KafkaTemplate；消费端保持 Kafka 直连，不在本轮抽象，避免破坏 offset / rebalance / ack 语义。

### 守护

- 新增 `scripts/ci/check-direct-client-boundaries.py`：
  - 禁止 application / domain / service 业务层直接导入 Redis / Kafka / JDBC / DataSource 客户端。
  - tests、infrastructure、config、support、mapper、Kafka consumer entry 和 ops lag probe 不作为违约。
- 新增 `scripts/ci/check-shell-linux-portability.py`：
  - 阻止新增 macOS/BSD-only shell 写法进入仓库自动化。
  - 当前拦截重点：`sed -i ''`、`date -v`、未带 Linux fallback 的 `stat -f`、`open -a`、`pbcopy/pbpaste`、`osascript`。
- 两个守护均已接入 PR gate 和 full CI。

## Sonar 增量问题处理

本轮 Sonar 首扫命中 10 个变更行 issue：

- `DbRowExistsSensorPolicy` 重复字面量：提取 `SENSOR_TYPE` / `SENSOR_SPEC_INVALID`。
- 三个 `KafkaMqMessagePublisher` 局部变量 `record`：改为 `producerRecord`。
- `LoginFailureStore.record` 受限标识：改为 `recordFailure`。
- `ConsoleJwtService` 嵌套 if：合并为单个条件。
- `TestObjectStoreEndpoint.CheckedRunnable` 泛型异常：改为自定义 checked exception。
- `MinioObjectStoreContainer` 继承 Testcontainers 泛型容器：显式 final 委托 `equals` / `hashCode`。

复扫结果：变更行 OPEN Issue `0`，待审 Security Hotspot `0`。

## 主链影响判断

### 未改动的主链

以下核心链路未被本轮端口化直接改写：

- trigger / console 发起 launch 后的 instance 创建语义
- orchestrator dispatch / claim / report 状态转换
- worker 执行和 report 终态回写
- outbox 事务写入、发布、ack 标记
- Kafka consumer offset 提交策略
- job_instance / job_task CAS 条件

### 有变更但风险可控的链路

- Console Redis 能力：只改变依赖方向，保留原 key、TTL、Lua、异常降级语义。
- dry-run / DQ / DB row sensor：只把 JDBC 细节移到 infrastructure，原校验和异常语义保留。
- MQ publish adapter：仅局部变量重命名，不改变 record 构造、header 写入或 publish result。

## 已执行验证

- `python3 scripts/ci/check-direct-client-boundaries.py`
- `python3 scripts/ci/check-script-governance.py`
- `python3 scripts/ci/check-shell-linux-portability.py`
- `bash scripts/ci/check-shell-scripts.sh`
- `git diff --check`
- `./mvnw -pl batch-console-api,batch-orchestrator,batch-trigger,batch-worker/core,batch-test-support -am spotless:apply`
- `./mvnw -pl batch-console-api,batch-orchestrator,batch-trigger,batch-worker/core,batch-test-support -am -DskipTests -DskipITs -DskipE2E compile`
- `./scripts/dev/sonar-scan.sh --incremental --base-ref origin/main`

此前端口化目标单测已跑通 52 个：

- Console config invalidation
- realtime publisher / replay / pubsub consumer
- ops summary realtime stream
- system parameter service
- session registry
- idempotency interceptor
- workflow design lock
- JWT service
- dry-run plan
- DQ executor

## 未覆盖风险

- 未跑真实 Redis / Kafka / PostgreSQL runtime 回归。
- 未跑 full CI / IT / E2E。
- 未验证多实例 Console Redis pub/sub 下的时序一致性。
- 未验证 dry-run / DQ SQL probe 在真实租户数据源上的业务结果。

上线前如果该分支要合入主线，建议至少补一次 PR gate + full CI；涉及运行时环境的 Redis / Kafka / PostgreSQL 行为再用现有 sim / be-acceptance 覆盖。
