# 架构设计审查：Console、前端与前后端契约

日期：2026-11-13。基线：后端 `main` `bdb94501`，前端 `batch-console` `main` `9d49c304`。只读静态审查，未修改代码或配置，未编译、未运行测试、未连接任何环境。数字来自文本与正则扫描，是近似值。

## 范围与定位

[2026-11-03 架构设计审查](architecture-design-review-2026-11-03.md)覆盖了模块依赖方向、表写入者和 Orchestrator 分层，并明确排除了 Console 内部、前端和 SDK。本次补这三块中的前两块，加上前后端接口契约。

- **纳入**：Console 的接口面与包结构、Console 对其他服务与平台库的依赖方式、幂等机制、OpenAPI 契约维护方式、前端数据层与权限、前端文件规模。
- **排除**：11-03 已结论的模块边界与表写入权限、容量与扩缩容、SDK 协议、安全专项、逐文件缺陷扫描。
- 结论等级：**有当前证据**（本基线源码可定位）、**推断**（由源码与注释推出，未复现）、**建议**。静态证据不代表运行时已发生故障。

## 总体结论

整体方向没有问题，未发现需要推倒重来的设计。问题集中在四处：幂等机制的保证范围、接口契约靠手工维护、Console 与 Orchestrator 共享数据库读取、前端数据层没有统一模式。

## 问题台账

### ARCH-20261113-01 / P1 / 有当前证据（重复执行窗口为推断）：幂等机制只能防连点，不能回答结果未知

- 服务端 `ConsoleIdempotencyInterceptor`：标记存 Redis，绑定 `tenant + method + uri + key`；`preHandle` 写 `PENDING`，`afterCompletion` 在 2xx 时改为 `DONE`（24 小时），与业务事务不在同一次提交。同键再次到达时返回 409，不返回首次响应。Redis 不可达时返回 503（fail-closed），带幂等键的写操作因此硬依赖 Redis。
- 前端 `src/api/interceptors.ts`：幂等键由拦截器按请求签名自动生成，只存内存，完成后 2 秒内复用，进行中最长 60 秒；刷新页面即丢失。
- 后果：请求超时后用户刷新重试会被当作新请求。只有配置批操作提供按原键回查。
- 推断：业务已提交但进程在标记 `DONE` 前退出时，`PENDING` 约 30 秒后过期，重试可能重复执行。未复现。
- 未核对：协议要求全部写接口带幂等键，实际覆盖面没有逐个接口确认。

### ARCH-20261113-02 / P1 / 有当前证据：接口契约手工维护，字段级漂移没有自动拦截

- 后端 `docs/api/console-api.openapi.yaml` 约 2.2 万行、391 个路径，手写，仓库没有从代码生成 OpenAPI 的依赖。
- CI 有路径同步检查（`check-console-openapi-paths.py`）和相对基线的破坏性变更检查（`check-openapi-breaking.sh`，比对范围未核对）。把 Java 响应类与文档逐字段比对的测试只找到 `WorkflowSchedulingOpenApiContractTest`。
- 前端生成类型 `src/types/api.generated.ts` 约 2.6 万行，652 个源文件中 56 个引用；`src/api` 与 `src/types` 另有 351 个手写类型声明。
- 已出现的症状：告警 `detailJson` 被 HTML 转义导致前端解析失败（#1322）；协议文档把 `X-Operator-Id` 写反（#1361），OpenAPI 仍保留没有路径引用的 `OperatorIdHeader`。

### ARCH-20261113-03 / P2 / 有当前证据：Console 直接读 Orchestrator 的运行表

- Console 与 Orchestrator 的 Mapper XML 共同读取 52 张表；`job_instance` 在 Console 中有 28 处引用。Console 的 Mapper XML 含 91 处状态字面量。
- 这是换取查询性能和避免数据同步的取舍。代价是表结构或状态语义变化时 Console 必须同步修改，目前靠集成测试兜底，没有版本化的读模型或视图。11-03 的受限角色（ARCH-20261103-01）只约束写入。

### ARCH-20261113-04 / P2 / 建议（需容量证据）：Orchestrator 定时任务共用调度池

- Orchestrator 有 63 个 `@Scheduled`、53 个 `@SchedulerLock`，其中 48 个是 `fixedDelay` 轮询；默认共用 `batch.scheduling.pool-size`（16）线程的调度池。慢任务可能挤占其他任务。
- 与未处理的 ARCH-20261103-04（热路径与后台治理共用连接池）是同一类问题。
- `FileGovernanceScheduler`、`OutboxPollScheduler` 所在文件未见 `@SchedulerLock`，需逐个确认是否另有防并发手段。

### ARCH-20261113-05 / P2 / 有当前证据：Console 规模最大，包结构迁移未完成

- Console 生产代码约 9.3 万行、100 个控制器、441 个接口，超过 Orchestrator 的约 7.5 万行。
- P1-A Stage 1 之后，`domain.<ctx>` 之外仍有顶层 `application`（147 个文件）与 `infrastructure`（71 个文件）。配置包 Excel 相关 5 个类超过 1000 行；`DefaultConsoleOrchestratorProxyService` 1115 行。

### ARCH-20261113-06 / P2 / 有当前证据：前端数据层没有统一模式

- 已引入 `@tanstack/vue-query`，只有 3 个文件使用。248 个页面文件中 138 个直接导入 `@/api/`，有 84 处手写 `loading.value = true`；缓存、去重和取消没有统一做法。
- `src/api/interceptors.ts` 919 行，承载登录续期、自动幂等键、租户上下文、维护模式和错误提示。

### ARCH-20261113-07 / P3 / 有当前证据：前端大文件贴着门禁线

- 24 个文件不少于 1000 行，其中 8 个在 1150–1200 行之间；`check-maintainability.mjs` 与 `check-ui-complexity.mjs` 的上限是 1200。门禁促成的更像是压线而不是拆分。

### ARCH-20261113-08 / P3 / 有当前证据：前端散落角色字面量

- 页面与组件中有 30 处 `'ROLE_*'` 字面量，另有 36 处权限指令或函数调用。后端是授权权威，这里只影响显示。

## 核对后无需处理

- 响应格式：381 个接口中 342 个返回 `CommonResponse`，其余 39 个为 `ResponseEntity`（下载、登录、AI 流式等）或 `SseEmitter`。
- 分页：除两个等待关系接口使用专用分页类型外，均为 `PageResponse`。
- Console 调 Orchestrator、Trigger 均配置了连接与读取超时。
- 实时推送使用 SSE。

## 建议顺序（未授权实施）

1. ARCH-20261113-02：扩大响应类与 OpenAPI 的字段比对测试，或改为从代码生成 OpenAPI。不改业务逻辑。
2. ARCH-20261113-01：先确定范围（如触发、重跑、审批），再决定提供按原键回查还是回放首次响应。
3. ARCH-20261113-04：先取容量数据。
4. ARCH-20261113-03、05、06、07、08：随功能改动逐步收敛。

## 未覆盖与未验证

- 全部结论来自静态扫描，未运行系统，未连接目标环境。
- 表与状态字面量计数来自正则，可能漏掉动态 SQL。
- 未审查 SDK、部署拓扑、安全专项和移动端页面细节。
