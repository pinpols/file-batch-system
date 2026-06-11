# 全库健壮性深扫报告 2026-06-10

> 4 路并行专项审查(安全注入面/凭据 · 并发与生命周期 · 多租威胁模型 · 数据正确性),全库 10 模块 + scripts。
> 每条发现逐项 grep/Read 核实,甄别"真实活跃缺陷"vs"by-design"vs"理论风险"。本次只修**确定真实、低风险、独立**的 6 条;其余按甄别结论列为建议或不修,理由见下。

## 已修复(6 条,全部编译 + 现有单测验证通过)

| # | 类别 | 问题 | 文件 | 修复 |
|---|------|------|------|------|
| 1 | 安全·SSRF | `DispatchReceiptPollScheduler` 的 `receipt_poll_url`(渠道配置自由文本)用裸 OkHttpClient 直连,绕过 SSRF 防护,可打内网/云 metadata(169.254.169.254)。同模块另 3 条 HTTP 出口都有 `DnsResolveGuard` | `DispatchReceiptPollScheduler.java` | 注入 `BatchSecurityProperties`,`@PostConstruct` 构造带 guardedDns(resolve-then-connect IP 校验)的 client,与 `HttpDispatchChannelAdapter` 同款;httpClient 转 `@PostConstruct` 后 `stopHttpClient` 补 null 防护 |
| 2 | 安全·注入 | dry-run SQL 探测直拼 `EXPLAIN`,仅靠 `ANALYZE FALSE` 单层防御(对 PG 个别语句如 CREATE INDEX CONCURRENTLY 有实执行历史) | `DefaultDryRunPlanService.java` | EXPLAIN 前加 SELECT/WITH 首关键字白名单,DML/DDL 直接拒(`EXEC_SQL_NON_SELECT_REJECTED`)。用首关键字而非 AST 解析:合法探测本就是 SELECT,零误伤 |
| 3 | 安全·凭据 | console-api 主/从库密码走 `batch.console.read-replica.*`(默认弱口令 `batch_pass_123`),prod fail-fast 只校验 `spring.datasource.password`,完全错过 | `BatchSecurityProperties.java` | prod 下新增 console read-replica 密码弱口令校验(`validateNotKnownWeakDbPassword`),非 console 模块 property 为 null 自动跳过 |
| 4 | 并发·OOM | OSS dispatch `in.readAllBytes()` 无上限,GB 级文件 + 多路并发 → OOM(import 侧有 `MAX_OBJECT_BYTES` 防护,此处缺) | `RemoteFilesystemDispatchSupport.java` | bounded `readNBytes(limit+1)` 检测超限拒绝(默认 512MiB,jvm property 可调),不把整文件读进堆 |
| 5 | 数据·溢出 | `ceilDiv` 的 `dividend + divisor - 1` 在超大 estimatedFileSize 下溢出为负 → 分区数静默退化为 1(大文件被迫单分区串行,无告警) | `SizeBasedPartitionCountResolver.java` | 改溢出安全式 `(dividend-1)/divisor + 1` + `Integer.MAX_VALUE` 封顶 |
| 6 | 数据·审计损坏 | LoadStep checkpoint:① 幂等跳过分支 `markLoaded(ckpt.startLineNo())`,而 completed 态 positionMarker 为 null → `loaded_count` 被覆写成 **0**;② 续跑初值用行号(含空行)而非记录数 → `loaded_count` 虚增 | `LoadStep.java` | `CheckpointHandle` 增 `processedCount` 字段(从 `pos.processedCount()` 读,该值由 chunk 实际写入行数累加,准确),两处改用它替代 `startLineNo`;`startLineNo` 仍仅用于续跑跳行 |

## 已甄别为 by-design / 已缓解 / 误判(不修,记录防重复误报)

- **威胁模型 C-1(平台调度器全表扫无 tenant_id)**:`TaskTimeoutEnforcer` / stuck workflow reconciler / `BatchDayCutoffScheduler` 等是**平台级后台任务,设计上就跨租户扫**,逐行按 tenant_id 处理。这是合理设计,非缺口。真正危险的子点(`deleteByIds` 等)需单独评估,但当前 id 来自同事务前序查询,非外部可控。
- **并发 I-2(WorkerTaskLeaseRenewer DCL)**:agent 自承认 DCL 实现正确,且 Spring 单线程调度器下两 `@Scheduled` 不真并发。不修。
- **安全 C-2(attachClause 白名单"过宽")**:这是对 #444 已落地白名单的二次审查;PG 分区约束语法本身拒绝子查询,白名单已是纵深防御足够层。不再加。

## 真实但优先级/风险权衡后暂列建议(未在本批改)

- **威胁模型 C-2/I-3(retry_schedule / event_outbox_retry 的 `markXxx`/`selectById` 用全局 id 无 tenant_id)**:`MapperXmlTenantGuardArchTest` 只扫 `<if tenantId>` 可空 select 守护,**不覆盖 id-only 的 update** —— 是真盲区。但 markXxx 入参 id 来自调度器内部 `selectByQuery` 结果(非外部可控),实际越权可利用性低,属纵深防御。改动涉及 mapper 接口签名 + 调用方 + `MarkFailedParam` 多文件。**建议**:① 给这些 update 补 `AND tenant_id = #{tenantId}`(entity 手头有 tenantId,成本可控);② 扩展 ArchTest 覆盖 update/delete 的 id-only 写。
- **并发 C-3(`persistAndForward` PENDING→ACCEPTED 非原子)**:进程在两步间崩溃,trigger_request 滞留 PENDING,而 `TriggerRequestLaunchReconciler` 只扫 ACCEPTED,无自愈。**建议**:reconciler 同时扫 PENDING+ACCEPTED(侵入最小),或把 updateStatus 并入 REQUIRES_NEW 事务。
- **并发 C-2(`NAS_COPY_EXECUTOR` 静态池无 `@PreDestroy`)**:线程已 `daemon=true` 可让 JVM 退出,但 in-flight NAS 复制不保证取消。**建议**:移入 `@Component` 加 `@PreDestroy shutdown`。
- **并发 I-1(SSE dirty publisher `clear()` 全清 → 事件风暴)**:超 1 万 key 触发全清,节流状态丢失致下轮全量推送。**建议**:改 LRU 淘汰。
- **并发 I-4 / I-3 / 数据 I-2**:push 失败无退避日志噪音、`TICK_CACHE` ThreadLocal 契约靠人工保证、COPY 行数不匹配抛 `IllegalStateException` 不符异常契约。低优先级,可随手清理。
- **回归测试**:建议为 #6 checkpoint 审计损坏补针对性回归测试(参照 `LoadStepCheckpointTest`,构造 completed 态 + 含空行文件,断言 loaded_count = 实际记录数)。

## 验证

- `mvn clean compile` 4 模块(common/orchestrator/worker-dispatch/worker-import + 上游)✅
- 相关单测全绿:`DefaultDryRunPlanServiceTest`(7)、`SizeBasedPartition*`、`LoadStepCheckpointTest`(5)/`LoadStepTest`(12)/`LoadStepCheckpointPrecheckTest`(6)、`BatchSecurityPropertiesTest`(21)✅
- 完整 test 留 CI 兜底
