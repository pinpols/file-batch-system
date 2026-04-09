# file-batch-system 项目编码规范

## 方法参数约束

**方法参数数量不能超过 6 个（含 6 个）。**

- 参数 ≥ 7：必须封装为参数对象（Command / Context / Request / Param）
- 参数 6：建议封装，Mapper 公共方法和 Service 公共接口必须封装
- 构造器（record、DTO、Response、data holder、Spring DI 注入）不受此约束
- 封装类型优先选用 `private record`（私有方法）或独立 Command/Param 类（公共接口）

## Java 代码风格

- **禁止在代码中使用全限定类名（FQN）**——必须通过 `import` 导入后使用短名。例如写 `TimeUnit.SECONDS` 而非 `java.util.concurrent.TimeUnit.SECONDS`
- 注解同理：写 `@MockitoSettings` 而非 `@org.mockito.junit.jupiter.MockitoSettings`

## 架构硬约束

- 任务分发主链：`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
- Orchestrator 是唯一状态主机；Worker 不能直接改写 job_instance / workflow_run / workflow_node_run
- outbox_event 必须与任务状态写入处于同一事务
- Worker 执行前必须先 CLAIM，不能绕过
- 禁止 JPA/Hibernate；持久层 MyBatis（运行态）/ Spring Data JDBC（配置态）不混用

## 模块边界

模块结构固定，不可擅自增删：
`batch-common` / `batch-trigger` / `batch-orchestrator` /
`batch-worker-core` / `batch-worker-import` / `batch-worker-export` /
`batch-worker-dispatch` / `batch-console-api`
