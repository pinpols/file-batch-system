# 单例与资源生命周期复核报告（2026-10-04）

## 1. 范围与结论

本轮复核覆盖后端 Spring 单例、静态可变状态、线程池、数据库连接池、Kafka
`AdminClient`、HTTP 客户端、调度任务以及 Worker Pipeline 进度缓存。目标不是机械套用单例模式，
而是确保资源只有一个明确所有者，启动失败能够回滚，运行期间能够复用，应用停止时能够及时释放，
且停止后不会被并发请求重新创建。

本轮确认的问题已完成代码修复。未把无状态工具类强制改成 Spring Bean，也未为不可关闭的轻量对象增加
无意义的生命周期包装。

## 2. 已修复问题

| 类别 | 原问题 | 修复后的约束 |
| --- | --- | --- |
| 业务数据源路由 | 路由对象不拥有子连接池生命周期；多分片构建中途失败会泄漏已创建连接池 | `BusinessRoutingDataSource` 明确拥有并幂等关闭唯一子数据源；Builder 先校验配置，后创建连接池，失败时回滚全部已创建资源 |
| Console 内部 HTTP | 多处调用按请求重复构建 `RestClient` | 三类内部客户端均在构造时创建并复用一个不可变客户端 |
| 外部 HTTP | OkHttp dispatcher/connection pool 缺少统一关闭 | Console、Orchestrator、Dispatch、Atomic 的所有权类增加停止状态和销毁释放；Dispatch 健康检查与远程文件系统共用根客户端 |
| Kafka 管理客户端 | Lag/offset 查询按调用创建或停止后可能再次创建 `AdminClient` | 改为线程安全惰性单例，`@PreDestroy` 关闭，停止后禁止复活 |
| NAS 复制线程池 | 静态可变线程池按超时重建，所有权和关闭时机不清晰 | 提取为 Spring 单例 `NasCopyExecutor`，使用有界队列和显式停止生命周期 |
| Worker 执行池 | 固定线程池使用无界队列；取消后的 `Future` 不能证明插件线程已经退出 | 改为有界执行池并显式拒绝过载；watchdog 观察真实执行标记，取消排队任务时立即移出队列 |
| Atomic Shell 输出 | 单例 Map 暂存异步 stdout/stderr，异常或取消路径可能留下结果 | 结果引用下沉到单次调用，超时先终止进程树再回收 reader，不再跨任务共享 |
| Export 加密临时文件 | 加密副本成功上传或加密中途失败后可能遗留在临时目录 | 创建者在成功、上传失败和加密失败路径统一删除加密副本；独立旁路锁保护仍在使用的文件，定时清理只删除过期且未加锁的崩溃残留 |
| Import ObjectMapper | 静态初始化器和可变全局引用改变运行时行为 | 删除静态可变状态；生产路径显式注入 ObjectMapper，独立测试仅使用不可变默认实例 |
| Pipeline 进度 | 静态进度槽按 workerCode 覆盖，并发 task/partition 会互相污染 | 改为 Spring 单例 Registry，以 `taskId + pipelineInstanceId + stageCode` 为身份；心跳携带结构化进度，Orchestrator 聚合并按 TTL 清理任务和 worker 索引 |
| Outbox 调度 | draining/关闭阶段仍可能继续注册延迟轮询，导致容器停止等待 | 保存待执行 future，停止时取消；draining 和 stopping 状态禁止继续调度 |
| 控制面并发 | 终态收敛与 report 锁序不一致；配额窗口刷新和预约拆成两次 CAS | 统一先获取实例 advisory lock；配额窗口刷新与本次预约合并为一次版本更新 |
| Console 写请求幂等 | 固定 `PENDING` 无所有权，超时后的旧请求可能覆盖或删除新请求占位；长请求会自然过期 | 每次请求使用唯一 owner token，定时续租并通过 Redis Lua CAS 完成或删除；失去所有权后立即停止续租 |
| 批量开户预览 | 版本检查与写入、Apply 冻结与读取分离，并发编辑或重复 Apply 可能同时通过 | 编辑和 Apply 均对完整快照执行 Redis CAS；Apply 期间冻结预览，事务提交后消费，回滚后恢复原快照 |
| Export/Import 构造注入 | 为新增运行时协作者继续扩张构造参数 | 使用 `GenerateRuntimeSupport`、`PreprocessRuntime` 聚合同一生命周期的协作者，不引入 Service Locator |

## 3. 关键行为不变量

1. 路由数据源只能关闭自己创建并拥有的连接池，同一实例最多关闭一次。
2. 分片配置缺少 `shard-0`、分片键重复或后续构建失败时，应用启动失败且不遗留连接池。
3. 共享客户端在运行期间复用；进入停止阶段后不得重新创建网络客户端或后台线程。
4. Pipeline 进度不得按 workerCode 作为唯一身份；同一 worker 上的并发 task/partition 必须可区分和聚合。
5. 终态步骤不继承已经过期的 live 进度；TTL 淘汰必须同时移除 task、owner 和 worker 反向索引。
6. Outbox draining 期间不安排新的延迟轮询，关闭不能被尚未执行的 delayed task 阻塞。
7. 幂等请求只能续租、完成或删除自己持有的占位；旧请求恢复后不得修改新 owner 的状态。
8. 批量开户的同版本并发编辑只能有一个成功，Apply 冻结必须与事务提交或回滚结果一致。
9. 加密临时数据文件不得被自身锁阻止写入或读取；旁路锁仅用于区分活跃文件与崩溃残留。

## 4. 验证证据

### 4.1 已完成

- Java 可读性清单重新生成并通过一致性检查。
- 全 Reactor（17 模块）通过 `test-compile`、PMD 和 Spotless 检查。
- Orchestrator：1593 个测试通过，0 failure / 0 error。
- Worker Core：202 个测试通过，0 failure / 0 error。
- Import Worker：303 个测试通过，0 failure / 0 error。
- Atomic Worker：225 个测试通过，0 failure / 0 error。
- Pipeline 进度、数据源启动回滚、Outbox 停止生命周期均增加了定向测试。
- 幂等 owner-CAS、批量开户预览 CAS、Worker 执行池过载、忽略中断的插件检测、加密临时文件清理均增加了定向测试。
- 测试过程中实际启动 PostgreSQL、Kafka、MinIO、Valkey Testcontainers，覆盖连接池、消息和对象存储相关路径。
- 最终变更完成后，受影响模块及其依赖通过 `test-compile` 与 Spotless；新增测试的执行结果以 PR CI 为准。

### 4.2 尚待 CI 给出最终结论

本地受影响模块 Reactor 测试执行到 `batch-console-api` 真实容器集成测试阶段时，按要求停止并改由
CI 验证。中止产生的 Surefire exit code 143 是人工终止结果，不是测试断言失败。因此本报告不声称
本地完整 Reactor 测试通过。

PR 合入前必须满足：

- PR required checks 全部成功；
- 不以 cancelled、skipped 或 pending 代替成功；
- 若 CI 出现真实失败，先定位并修复，再重跑原失败检查；
- 本轮不跳过门禁、不强制合并。

## 5. 未扩展事项

- 本轮没有执行完整 `sim-harness all`、性能压测、DAST 或预发布部署演练。
- 本轮没有改变业务状态机、Kafka topic、数据库 schema、外部 API wire contract。
- Python SDK 仅修正文档性注释，没有运行时行为变化。
- 本轮结论限定为单例、资源所有权、停止生命周期和 Pipeline 进度隔离，不替代系统级容灾或容量验收。
