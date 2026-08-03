# Bounded Context 依赖治理计划（2026-08-03）

## 目标

将 `batch-console-api` 当前 1841 条跨 context 直接依赖逐步降到 0，并最终启用严格 ArchUnit 隔离规则。

本治理不改变外部 API、数据库表结构、租户模型、状态机、Outbox 语义或事务边界。所有改动按独立 PR 推进，每批可单独回滚。

## 初始基线

- context：`job`、`workflow`、`file`、`ops`、`governance`、`notification`、`audit`、`rbac`、`observability`
- 当前跨域直接依赖：1841 条（JDK 25 字节码扫描上限）
- 初始门禁：ratchet，依赖数不得超过 1841
- 当前高频依赖边：
  - `job -> ops`：236
  - `ops -> observability`：197
  - `file -> observability`：162
  - `observability -> ops`：110
  - `ops -> job`：98

第一批完成后：

- `ConsoleQuerySupport` 移入 `shared.query`，租户解析抽为 `TenantIdResolver` Port。
- 跨域直接依赖降至 **1480**，减少 **361 条**。
- 当前 ratchet 已下调至 1480。

第二批完成后：

- `SimpleOptionView` 移入 `shared.view`，作为 RBAC 元数据查询与其他只读查询可复用的无业务逻辑投影。
- `ShedLockView`、`DeliveryStatusCountView` 移入 `ops.view.cluster`，明确集群诊断投影由 Ops context 拥有。
- MyBatis result map 与测试引用同步，SQL、事务、租户过滤和外部响应保持不变。
- 跨域直接依赖降至 **1464**，本批减少 **16 条**；ratchet 已下调至 1464。

第三批完成后：

- 纯事件载荷 `ConsoleRealtimeDomainEvent` 移入 `shared.event`，供通知监听器和 observability Bridge 共享。
- `ConsoleRealtimeDomainEventPublisher`、SSE/Redis Hub、游标生成器继续由 observability 持有，避免 shared 承载基础设施或实时业务逻辑。
- 跨域直接依赖降至 **1449**，本批减少 **15 条**；ratchet 已下调至 1449。

第四批完成后：

- Ops 的只读和代理服务改依赖 `TenantIdResolver`，需要读取调用方租户作用域的 Trigger 路径依赖最小 `TenantScopeResolver`。
- `ConsoleTenantGuard` 仍是唯一实现，保留 JWT、请求上下文、全局角色和 fail-closed 校验；本批只改变依赖方向和注入抽象。
- 跨域直接依赖降至 **1374**，本批减少 **75 条**；ratchet 已下调至 1374。

第五批完成后：

- notification 的租户感知 Service 改依赖 `TenantIdResolver`，不再直接绑定 RBAC 的具体守卫类。
- 实时 Controller 暂保留具体守卫，避免只改注入类型却触发 API 文档门禁；不改变端点、权限和事件推送协议。
- 跨域直接依赖降至 **1357**，本批减少 **17 条**；ratchet 已下调至 1357。

第六批完成后：

- file 的文件主服务、Channel、Template、Download 和 Query 服务改依赖 `TenantIdResolver`。
- Pipeline 实时 Controller 暂保留具体守卫，避免只改注入类型却触发 API 文档门禁；文件租户校验语义不变。
- 跨域直接依赖降至 **1329**，本批减少 **28 条**；ratchet 已下调至 1329。

第七批完成后：

- job 的定义、日历、批量窗口、Bundle 和自服务改依赖 `TenantIdResolver`。
- Replay、DryRun、ResultVersion、Realtime Controller 暂保留具体守卫，避免 API 文档门禁误报；Job 状态和重跑语义不变。
- 跨域直接依赖降至 **1295**，本批减少 **34 条**；ratchet 已下调至 1295。

第八批完成后：

- workflow 查询服务改依赖 `TenantIdResolver`，不再直接绑定 RBAC 具体守卫。
- Workflow 定义/运行实时 Controller 暂保留具体守卫，避免无 API 变化的注入调整触发文档门禁。
- 跨域直接依赖降至 **1293**，本批减少 **2 条**；ratchet 已下调至 1293。

第九批完成后：

- audit 查询服务和 observability 的查询应用服务、系统参数服务、Dashboard 查询服务、Ops 摘要实时流改依赖 `TenantIdResolver`。
- 不改变审计租户约束、只读聚合、SSE 推送和 Redis 发布行为。
- 跨域直接依赖降至 **1266**，本批减少 **27 条**；ratchet 已下调至 1266。

第十批完成后：

- 将无状态、跨领域复用的 `AuditAction` 注解移入 `shared.audit`；`AuditAspect` 仍由 audit context 持有，切点、审计事务、租户解析和 HTTP 契约不变。
- 同步所有 Controller 与审计切面引用，OpenAPI 仅更新内部变更记录，不改变路由或 schema。
- 跨域直接依赖降至 **1213**，本批减少 **53 条**；ratchet 已下调至 1213。

第十一批完成后：

- 将仅负责租户作用域非空 fail-fast 断言的 `TenantScope` 移入 `shared.query`，保留 `BizException(FORBIDDEN)` 错误契约；全局管理员和按父 ID 反查的合法 null 路径不使用该工具。
- 跨域直接依赖降至 **1204**，本批减少 **9 条**；ratchet 已下调至 1204。

第十二批完成后：

- 将仅承载 `username/tenantId/authorities` 的不可变认证身份载荷 `ConsolePrincipal` 移入 `shared.security`；JWT 签发/解析、认证过滤器、授权策略和租户守卫仍归 rbac。
- 跨域直接依赖降至 **1196**，本批减少 **8 条**；ratchet 已下调至 1196。

第十三批完成后：

- 新增顶层应用端口 `ConsoleOpsQueryPort`，由 Ops 查询服务实现；observability 聚合门面不再直接注入 Ops 基础设施实现，查询方法、DTO、分页、SQL 和租户过滤保持不变。
- 跨域直接依赖降至 **1181**，本批减少 **15 条**；ratchet 已下调至 1181。

## 第一阶段：清单与分类

执行：

```bash
bash scripts/ci/report-bounded-context-dependencies.sh
```

默认输出到 `target/bounded-context/dependencies.tsv`，也可以显式指定路径：

```bash
bash scripts/ci/report-bounded-context-dependencies.sh logs/bounded-context/dependencies.tsv
```

每条记录包含 source/target context、类名、包层级、依赖类别和豁免状态。依赖类别：

| 类别 | 含义 | 首批处理策略 |
|---|---|---|
| `PERSISTENCE` | Entity / Mapper 直接依赖 | 最高风险，先确认数据所有权 |
| `APPLICATION` | Application Service / Service | 优先抽 Port 或受控 Facade |
| `CONTRACT` | Query / DTO / View / Param | 优先治理只读聚合 |
| `WEB_OR_REALTIME` | Controller / SSE 依赖 | 不直接跨域引用，改用应用层查询 |
| `ADAPTER_OR_SUPPORT` | Infrastructure / Support | 按实际业务归属拆分 |

## 第二阶段：低风险只读依赖

优先处理 `CONTRACT` 和只读 `APPLICATION` 依赖，顺序如下：

1. `observability` 聚合查询对 `ops`、`file`、`job` 的直接引用。
2. `ops` 的 Dashboard / 健康摘要查询对其他领域的直接引用。
3. `audit` 的告警只读查询依赖。

改法：

- 由数据拥有 context 暴露只读 Application Service / Query Port。
- 返回专用 DTO，不跨域暴露 Entity、Mapper 或内部 Query。
- 不改变 SQL 结果、分页、租户条件和读写分离策略。

## 第三阶段：写路径与事务依赖

处理配置复制、审批、补偿、任务状态推进等写路径：

- 每张表和状态机只保留一个拥有 context。
- 跨域写入通过 Application Service、领域事件或受控编排入口完成。
- 保持同事务 Outbox、CAS、幂等键、租户校验和终态防复活语义。

## 第四阶段：包与模块收口

只在依赖关系已经收敛后整理包结构、Mapper XML namespace、Spring Bean 和测试包路径。禁止先搬包再用大量白名单掩盖依赖。

## 每批验收

- ratchet 数量下降，且没有新增豁免。
- `./mvnw -pl batch-console-api -am test`
- 全量 `ArchTest`、`ConventionTest`、`GuardTest`。
- OpenAPI 路径和响应契约无漂移。
- 租户隔离、事务、Outbox、CAS、幂等和关键链路回归通过。
- 变更只涉及当前批次，不混入无关重构。

## 最终收口

当依赖数为 0 时，删除 ratchet 预算，恢复严格 ArchUnit 规则，并开始评估是否拆分独立 Console 模块。
