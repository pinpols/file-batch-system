# 面向抽象接口治理快照

## 结论

当前代码已在对象存储、Worker 执行骨架、Dispatch 渠道、Console 跨域端口和 SDK 幂等存储上采用
Port / SPI / Adapter 模式。剩余治理不应一次性重写主链，而应先用 diff-only 守护阻止新代码继续依赖具体
基础设施实现，再按风险分批迁移历史存量。

## 已落地

| 领域 | 当前抽象 | 状态 |
|---|---|---|
| 对象存储 | `BatchObjectStore`、`TestObjectStoreEndpoint` | S3-compatible / filesystem / 测试外部端点均走统一入口 |
| Worker 路由与执行 | `WorkerRouteAdapter`、`StepExecutionAdapter`、`TaskExecutionClient`、`WorkerRegistryClient` | 五类 Worker 共用运行骨架，业务阶段只实现窄接口 |
| Dispatch 渠道 | `DispatchChannelAdapter` | LOCAL / SFTP / HTTP / SMTP / NAS / OSS 等按 adapter 路由 |
| Console 跨域 | `ConsoleOrchestratorPort`、`ConsoleOpsQueryPort`、`ConsoleRealtimeEventPort` 等 | 跨 context 调用已大量通过应用端口 |
| SDK 幂等 | `SdkIdempotencyStore` | SDK 用户可替换存储实现 |

## 本轮新增守护

新增 `scripts/ci/check-infrastructure-abstraction-boundaries.py` 并接入 PR gate。它只检查 PR 新增/修改的
主代码，防止 application、domain、service、web 层直接引用或 import 以下具体基础设施类型：

- AWS SDK / S3 client
- Spring Kafka
- Spring Redis
- Spring JDBC / `java.sql` / `javax.sql`
- Quartz
- Spring `RestClient` / WebClient

允许拥有具体实现的包：`config`、`infrastructure`、`mapper`、`mybatis`、`support`、`shared.client`、
以及 `common` 的底层适配包。该守护是 ratchet，不清历史账，避免无关主链重构。

## 后续分批治理

| 优先级 | 方向 | 建议动作 | 主链风险 |
|---|---|---|---|
| P0 | 测试后端替换入口 | 继续让 E2E 通过 `batch.test.storage.provider=external` 跑外部 S3-compatible endpoint | 低 |
| P0 | 抽象边界守护 | 保持 diff-only，后续逐步缩小允许包 | 低 |
| P1 | Redis 能力抽象 | 盘点 Nonce、限流、配置缓存、SSE bus、设计锁，补缺 Port；实现仍用 Redis | 中 |
| P1 | MQ / Outbox 发布抽象 | 先只定义 `OutboxPublisher` / `TaskMessagePublisher` 边界，Kafka 实现不改语义 | 中 |
| P2 | 调度内核抽象 | 只维护 `TriggerScheduleEngine` 设计边界；没有容量证据前不动 Quartz 主路径 | 高 |
| P2 | Process SQL 计算抽象 | 暂缓。若未来支持外部计算引擎，再引入 `ComputeEngine` / `SqlExecutionPort` | 高 |

## 边界纪律

- 不因抽象治理改 topic、payload、offset、事务、CAS、RLS 或 SQL 校验语义。
- 不把测试替代后端的 POC 结果写成生产容量证明。
- 不把 Quartz、Kafka、Redis 当前生产基线伪装成已经可插拔；先有端口和证据，再切实现。
- 对主链高风险项只做设计和守护，不在同一 PR 内做替换。
