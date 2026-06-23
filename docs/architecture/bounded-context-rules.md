# Bounded Context 边界规则(P1-A Stage 1)

> 守护测试:`batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/BoundedContextDependencyArchTest.java`
> 进度 metric:`BoundedContextMigrationProgressTest.java`
> Roadmap:[`docs/architecture/p0-p1-p2-roadmap.md`](p0-p1-p2-roadmap.md) § P1-A

## 9 个有界上下文

`console-api` 的领域代码按下列 9 个 context + 1 个 shared 组织,根包统一为
`io.github.pinpols.batch.console.domain.<ctx>`:

| Context | 职责一句话 |
|---|---|
| `job` | Job 定义、实例、参数、运行视图 |
| `workflow` | Workflow DAG 定义、运行、节点、补偿、审批 |
| `file` | 文件上传 / 下载 / 模板 / pipeline 文件元数据 |
| `ops` | Trigger 代理 / Outbox 运维 / 集群诊断 / 维护窗口 |
| `governance` | 系统参数、资源标签、归档策略、字典、租户 / 用户 / API key 治理 |
| `notification` | 告警渠道、Webhook、Web Push、模板 |
| `audit` | 操作审计、登录审计、AI prompt 审计 |
| `rbac` | 角色、权限、菜单、登录、JWT、Bypass |
| `observability` | Dashboard、SLA、Kafka lag、监控查询、报表 |
| `shared` | 跨 context 共享 DTO / Entity / 工具,**禁放业务逻辑** |

## 允许的跨 context 通信(共 4 种)

1. **共享类型**:`io.github.pinpols.batch.console.shared.*` 的 DTO / Entity / 工具,任何 context 都可 import。
   - 放进 shared 的门槛:≥ 2 个 context 共用,且本身无业务方法。
2. **应用层接口端口(Application Service)**:跨 context 调用必须经由应用层 service 接口
   (`io.github.pinpols.batch.console.application.<ctx>.XxxService`),通过 Spring 构造器注入。
   ArchUnit 不约束 application 包,等同放行。
3. **Spring 事件**:`ApplicationEventPublisher` 发布事件 + 目标 context 的 `@EventListener` 订阅,
   事件载荷放 `shared.event` 或发布 context 自己的 event 子包。
4. **SpiPort 显式端口**:对外暴露的 SPI 接口放发布方 context,实现由消费方 context 提供
   (反向依赖反转),用 Spring DI 装配。

**禁止**:
- 直接 import `io.github.pinpols.batch.console.domain.<other-ctx>.*` 下的具体类
- 把 shared 当无边界容器塞业务逻辑(代码审查 reject)
- 在 controller / mapper 跨 context(应限制在 application 层)

## 豁免机制

过渡期允许局部豁免,但必须显式:

```java
@SuppressWarnings("BoundedContext")  // TODO(P1-A): 拆 ConsoleXxxService 后清理 — 2026-Q3
public class LegacyCrossContextHelper { ... }
```

- 守护测试看到 `@SuppressWarnings("BoundedContext")` 自动放行
- metric 测试单独统计豁免数,**不计入** violation 总数,但会打印 `suppressed edges: N`
- 评审纪律:每个豁免都要在注释里写「为什么 + 何时清理」

## 当前状态(2026-05-30)

- `domain/<ctx>/` 子包尚未拉齐(当前是 `domain/{command,entity,param,query,view}` 横切布局)
- 守护测试 `@Disabled`,作为目标基线锁定语义
- metric 测试输出 `total cross-context violations: 0`(因 ctx 子包还没建)
- Stage 1 迁移过程中,metric 会先升后降;降到 0 后删 `@Disabled` 启用守护

## 升级路径

1. 拉一个 ctx 子包(例:先迁 `job`)→ metric 输出第一批违规数
2. 逐步把 cross-ctx 直引替换为 application service / event / shared
3. metric 归 0 → 删 `BoundedContextDependencyArchTest` 顶部 `@Disabled`
4. 进入 Stage 2:开始拆独立模块(`console-push-api` / `console-notification-api` 等)
