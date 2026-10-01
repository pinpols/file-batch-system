# 资源、锁、线程、缓存与事务审计（2026-10-01）

## 结论

本轮覆盖 Console、Orchestrator、Worker Core 的资源池、并发锁、线程生命周期、缓存一致性、幂等和事务边界。未发现 P0；已修复 3 个 P1 风险，并将剩余容量类事项转为可执行的运行约束。

| 领域 | 结论 | 处理状态 |
|---|---|---|
| 资源与连接池 | 池均有界，但多副本总连接数需要按部署拓扑预算 | 代码不改默认值，补容量门禁与运行手册 |
| 分布式锁与并发 | CAS、租约、invocation fence、advisory lock 和 owner 校验完整 | 未发现锁安全缺陷 |
| 线程生命周期 | Worker、Console、Outbox 使用有界池；Outbox 已改为优雅等待 | 已修 |
| 缓存 | tenant key、TTL、Pub/Sub、revision reconciliation 已具备 | 保持回源 DB，补失败观测 |
| 幂等 | Redis 负责快速占位，平台库保存成功完成态 | 已修 Redis 完成写失败窗口 |
| 事务 | 主链路和 outbox 事务边界正确；文件对象存储调用已移出 DB 事务 | 已修 |

## 已修复

### 1. Console HTTP 幂等完成态持久化

`ConsoleIdempotencyInterceptor` 之前在请求完成后直接写 Redis，写失败时可能只留下短 TTL 的 `PENDING`，过期后重复请求再次进入业务。

现在的策略：

1. Redis 继续负责低延迟 `PENDING/DONE` 和并发拦截。
2. 成功完成后，将带 tenant 的哈希键写入 `batch.idempotency_record`。
3. Redis miss 时检查平台库完成态，阻止 Redis 完成标记丢失后的重复请求。
4. Redis 或数据库完成态写失败均记录 error 日志；失败请求不会静默宣称幂等已完成。

平台库表由既有 V38 创建，Console 只使用 `CONSOLE_HTTP_DONE` 结果，不改变 Orchestrator 其他幂等记录。

### 2. 读副本执行期故障隔离

读副本路由不再只观察 `getConnection()`。返回的 JDBC Connection、Statement 和 ResultSet 会观察 SQLState `08*` / `57*` 连接性异常，并立即进入 quarantine。当前请求仍返回原始数据库异常，后续请求切到主库；不在已绑定的只读事务内强行替换连接，避免事务上下文被破坏。

### 3. 文件治理事务缩短

预签名 URL 生成和对象 `stat` 属于外部 I/O，已移到数据库事务之外。新增 `FileGovernanceCommitService`，只在外部探测完成后用短事务提交状态和审计，避免 MinIO/S3 延迟占住 Hikari 连接。

### 4. Outbox 优雅关闭

Outbox poll scheduler 改为等待当前任务完成再关闭。未投递事件始终保留在数据库，由下一副本继续扫描；不会依赖内存队列作为事实源。

## 设计约束

- 读副本故障的“fail-open”定义为：连接/执行期发现故障后隔离副本，后续请求走主库；当前已经绑定副本连接的事务不做中途换连接。
- Console 查询缓存故障继续回源数据库，不把缓存当作事实源；配置缓存通过 TTL、发布通知和 revision reconciliation 收敛。
- `DatabaseIdempotencyGuard` 的 action 只允许数据库写入或 outbox 写入；外部副作用必须在 outbox 之后执行，不能把远端 HTTP/SFTP 调用放进数据库事务回调。
- 连接池默认值不因单次审计擅自改小或改大；生产必须按副本数、每 Pod 池上限和 PostgreSQL/PgBouncer 上限计算总预算。

## 验证清单

已补定向测试：

- Redis 完成态写失败后仍写平台库完成态。
- Redis 缓存过期但平台库已有完成态时拒绝重复请求。
- 读副本 Statement 执行连接性异常后进入 quarantine。
- 文件治理对象存储探测和短事务提交职责分离。

仍需在 staging/容器环境执行：

- 读副本执行中断、主库切换和连续恢复。
- Redis 完成写失败与请求重试组合。
- MinIO 延迟/断连下 Hikari pending、连接超时和事务耗时。
- 按实际副本拓扑计算连接池总量并压测到预算上限。

本地 Maven 定向测试通过前，不得将上述 staging 项标为“已验证”。
