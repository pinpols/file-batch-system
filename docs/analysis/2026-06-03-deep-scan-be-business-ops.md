# BE 业务 + 运维深度扫描 — 2026-06-03

> 范围:`batch-orchestrator` 核心业务状态机 + 治理 + 运维端点 + worker 协同(lease / outbox / approval / replay / batch-day / dead-letter / alert)。base=origin/main(4d47f332)。
>
> 方法:逐文件读 + 跨文件交叉印证(状态转换 / 事务边界 / 幂等键 / CAS / 锁顺序 / 边界异常),不跑测试,纯静态审计。
>
> 输出:findings 按 P0/P1/P2/P3 分级,P0=可导致生产数据错乱或状态机长期停滞的硬伤,P1=运维补救路径缺失/降级,P2=细粒度退化或潜在 race,P3=可读性/工程一致性。

## 0. 扫描覆盖度

| 维度 | 入口文件 | 覆盖状态 |
|---|---|---|
| Workflow 状态机(派发 / cascade-skip / join / fan-out / cross-day) | `DefaultWorkflowDagService` `DefaultWorkflowNodeDispatchService` `DefaultTaskOutcomeService` `WorkflowRunManagementApplicationService` | 已读完 |
| Sensor 状态机(WAIT 节点 / timeout / ERROR 退避) | `SensorStateMachine` | 已读完 |
| Pipeline 5-6 stage(import/export/dispatch/process) | `DefaultPipelineExecutor` + 6 个 stage 包 | 已读 executor + stage 清单 |
| BatchDay 切换(gate / cutoff / settle / open / waiting / catch-up) | `BatchDayGateService` `BatchDaySettleScheduler` `LaunchBatchDayService` `BatchDayOperationService` | 已读完 |
| Replay(session / entry 状态机 / OUTPUTS_ONLY / dispatcher / reconciler) | `BatchDayReplayService` `BatchDayReplayDispatcher` `BatchDayReplayTerminalReconciler` | 已读完 |
| Outbox 三表分工 + cleanup / republish | `OutboxPollScheduler` `TaskDispatchOutboxService` `OutboxOpsController` + 跨模块 `TriggerOutboxEventMapper` + `EventOutboxRetryMapper` | 已读完 |
| Approval(submit / approve / reject / executed)+ 路由(CATCH_UP / COMPENSATION / DLQ_REPLAY / DOWNLOAD) | `DefaultApprovalWorkflowService` `ApprovalController` `ApprovalType` enum | 已读完 |
| Dead-letter(创建 / replay / errorClass / NON_RETRYABLE) | `DefaultRetryGovernanceService` `DeadLetterController` | 已读完 |
| Worker lease 回收(renewer / reclaim / startup audit) | `WorkerTaskLeaseRenewer` `PartitionLeaseReclaimScheduler` `PartitionReclaimUnit` `OrchestratorStartupLeaseAudit` | 已读完 |
| 运维端点(workflow-run / batch-day / outbox / dry-run / dead-letter) | 5 controller | 已读完 |
| 告警(emit / dedup / metric) | `DefaultAlertEventService` `AlertInternalController` | 已读完 |

## 1. 总体评估

整体业务层已经达到「核心路径有不变量、状态机有明确 CAS、终态有 outbox 事件、调度有 ShedLock + 内存重入双闸、坏点有 reconciler 回退」的成熟度。明显的硬伤已被前几轮(R2/R3/V88/V90/P1-x)修过,残留的高危项更多偏向运维端点的语义缺口和告警侧的薄弱(无 routing / 无 silence / 无 escalation),而不是核心状态机本身。

核心亮点(本次复核确认):

- `DefaultWorkflowDagService.cascadeSkipDownstream` + `matchesIncomingEdge` 对 `SKIPPED` 上游的 ALWAYS 边 fail-closed,P2-5 修复已落地,ALL-mode join 不会因为全 SKIPPED 上游死锁。
- `DefaultWorkflowNodeDispatchService` 把 readiness check 移到 `selectLatestForUpdate` 行锁之后(P1-4 TOCTOU 修复),join 节点并发上报不会重复 dispatch。
- `BatchDaySettleScheduler.finalizeSettling` 用 `TransactionSynchronizationManager.afterCommit` 推迟 catch-up 副作用,batch_day 真正落 FAILED 后才发 catch-up,避免重复 launch。
- `PartitionReclaimUnit` 第二步 CAS 失败显式抛 `ReclaimRetryableException` 触发回滚,消除"partition READY + lease NULL + task RUNNING"死态产生路径。
- `DefaultRetryGovernanceService` 自动 replay 走 `replayTransactionalSelf` 代理调用 + `noRollbackFor = DeadLetterOrphanSourceException`,孤儿源直接 GIVE_UP 不占自动重放预算。
- `TaskDispatchOutboxService.writeDispatchEvent` 用 `Propagation.MANDATORY`,无外层事务直接抛,堵死"DB 状态写了 outbox 没写"的漂移路径。

下文按等级列具体 findings。

---

## 2. P0 — 阻断 / 数据错乱级

### P0-1 `ApprovalType` 枚举与代码硬编码的审批类型不对齐,登记缺失会触发 console meta 渲染兜空

- 文件:`batch-common/src/main/java/com/example/batch/common/enums/ApprovalType.java`,`batch-console-api/.../DefaultConsoleJobRecoveryService.java:167`,`batch-console-api/.../ConsoleSelfServiceJobService.java:75`
- 现象:`ApprovalType` 仅声明 4 个 code(`CATCH_UP / COMPENSATION / DLQ_REPLAY / DOWNLOAD`),但仓内实际写入 `approval_command.approval_type` 的还有:
  - `"SELF_SERVICE"`(`ConsoleSelfServiceJobService.java:75` body.put)
  - `"WORKFLOW_NODE"` / `"OUTBOX_CLEANUP"` 在文档 `CLAUDE.md` 路由部分被任务点名,但实际代码中**完全找不到**(`grep -rn "WORKFLOW_NODE_ACTION\|OUTBOX_CLEANUP" batch-* --include="*.java"` 0 命中)。
- 影响:
  - `ConsoleMetaQueryService` 把 `ApprovalType` 注册成给 FE 的字典 → 前端 `approvalType` 下拉缺 `SELF_SERVICE`,运维筛选不到该类审批;同时 enum-registration 守护测试只能扫到登记的 4 项,实际 5 种类型,数据库可能出现 enum 之外的字面量却无任何静态守护拦截。
  - 任务描述提到的 OUTBOX_CLEANUP / WORKFLOW_NODE 路由实际**未实现**,console 走 `outboxCleanup(tenantId, retainDays)` 直连 orchestrator 无审批挡板,文档与代码漂移。
- 建议(优先级排序):
  1. 把 `SELF_SERVICE` 写入 `ApprovalType` enum,补 label;
  2. 删 CLAUDE.md / 任务描述里 `WORKFLOW_NODE` / `OUTBOX_CLEANUP` 的 approval 路由要求,或补实现(后者更危险:OUTBOX 删事件无审批);
  3. 加 `ApprovalCommandEntity.approval_type` 写入路径的 DictEnum 校验(insert 前 `ApprovalType.fromCode` 回退)。

### P0-2 `BatchDayReplayTerminalReconciler.findEntry` 只按 (sessionId, tenantId, jobCode) 匹配,同一 session 内同 jobCode 多次重放会回填到第一条 entry

- 文件:`batch-orchestrator/.../BatchDayReplayTerminalReconciler.java:92-106`
- 场景:`SCOPE_ALL` 时,materializeEntries 每个 candidate 一条 entry,如果同 jobCode 有多份 jobInstance(同 bizDate 多次 launch / 多分片),会插入多个 entry。`findEntry` 用 `for ... return entry` 线性扫,第一条命中即返回。
- 影响:第二个相同 jobCode 的 instance 进入终态时被回填到**第一条** entry,真正属于它的那条永远 PENDING,session 永远 inFlight 大于 0,卡 RUNNING 至 cancel。
- 注:虽然 entry 现在带 `sourceInstanceId`,reconciler 拿到的入参里有 `jobInstanceId`,完全可以做 (sessionId, sourceInstanceId) 精确匹配。这是低成本修复的硬 bug。
- 建议:`BatchDayReplayEntryMapper` 加 `selectBySessionAndSourceInstanceId(sessionId, sourceInstanceId)`,reconciler 改成按 sourceInstanceId 反查;sourceInstanceId 为 null(已被运维清理)再降级到 jobCode 线性扫。

### P0-3 `Alert` 子系统**没有 routing / silence / escalation**,生产告警只能靠"看表"

- 文件:`DefaultAlertEventService.java`(63 行)+ `AlertInternalController.java`(27 行)+ `AlertEventMapper.java`(17 行)。
- 现象:整套告警链路止于 `alert_event` 表 UPSERT + Micrometer 计数,无:
  - 路由规则表(severity → channel / receiver),
  - 静默窗口(maintenance / known-issue),
  - 升级链(WARN 持续 N 分钟 → ERROR),
  - 通知投递(Slack / Email / Webhook)。
- 影响:`BatchDayGateService.emitGateAlert` / `BatchDaySettleScheduler` 等点位虽然写得很勤,但生产环境告警只在 DB 里堆,既无通知也无 sla。任务描述里点名的"告警(routing / notification / 去重 / 静默 / 升级)"**只实现了去重**,其它 4 项缺位。
- 建议:
  - 短期:把 `batch.alert.events` Micrometer counter 接到外部 alert manager(Prometheus / Grafana alerting);
  - 中期:补 `alert_route` 表 + 简易订阅(tenant / alertType / severity → webhook URL),DefaultAlertEventService.emit 末尾异步分发;
  - 静默和升级延后,先把"看不到告警"这个核心缺口修复。

---

## 3. P1 — 运维补救路径 / 状态机降级

### P1-1 `DeadLetterController.replay` 强依赖 `RetryGovernanceService.replayDeadLetter` 但不做 idempotency / audit

- 文件:`DeadLetterController.java:25-28`
- 现象:console 转发 → orchestrator POST `/internal/dead-letters/{id}/replay`,body 只带 `tenantId`,缺幂等 key、操作人 ID、reason。`replayDeadLetter` 自己内部走 `markReplaying CAS`,重复 POST 会被 CAS 挡住,但**审计链条断了** — `BatchDayOperationService.appendAuditLog` / `BatchDayGateService.appendAuditLog` 都写了 `job_execution_log`,死信重放完全没有运维侧审计行。
- 建议:把 `operatorId` / `reason` / `idempotencyKey` 加进 `DeadLetterReplayRequest`;`replayDeadLetter` 入口 append 一条 `job_execution_log`(`message=DEAD_LETTER_REPLAY`, `detailRef=dead_letter_task`, `extraJson` 含 deadLetterId + operatorId + reason)。

### P1-2 `WorkflowRunManagementApplicationService.skipNode` 不写审计、不发 outbox 终态(若被 skip 的是关键节点)

- 文件:`WorkflowRunManagementApplicationService.java:77-103`
- 现象:`skipNode` 只更新 `workflow_node_run.node_status=SKIPPED` + 推进下游,无 `job_execution_log` audit,无 alert,无 outbox 信号。
- 影响:运维通过该接口跳过 FAILED 节点后,SLA / 监控订阅方完全察觉不到"有人介入跳了节点";事后排查只能拿 controller access log 反推。
- 建议:同事务里 append 一条 `job_execution_log` audit(operation=WORKFLOW_NODE_SKIP)+ `alertEventService.emit("WORKFLOW_NODE_MANUAL_SKIP", WARN)`,与 BatchDayGate 的 emitGateAlert 对齐。

### P1-3 `OutboxOpsController.cleanup / republish` 缺操作人 audit + 缺 dry-run 模式

- 文件:`OutboxOpsController.java`
- 现象:`POST /internal/outbox/cleanup?tenantId=X&retainDays=30` 直接执行 DELETE,无 operatorId、无审计、无 dry-run 预演;`republish` 同样裸跑。任务描述要求"运维端点带 dry-run / @AuditAction",当前只有独立的 `DryRunController` 给 launch 走 dry-run plan,outbox 运维这条线**没有 dry-run**。
- 建议:
  - 加 `dryRun=true` query param,true 时只 SELECT COUNT 不 DELETE / UPDATE;
  - 接收 `operatorId` body 字段,`OutboxOpsApplicationService.cleanup` 末尾写 `job_execution_log`(detailRef=outbox_event);
  - 控制台侧已有 `ConsoleOrchestratorProxyService.outboxCleanup` 路径,扩展两个参数即可,无破坏性。

### P1-4 `DefaultWorkflowDagService.canNeverFire` 把 SKIPPED 上游一律视为"未匹配",但 SUCCESS-edge 仍会被算成不可触发 → 与 `matchesIncomingEdge` 已修复的 SKIPPED 语义不完全闭环

- 文件:`DefaultWorkflowDagService.java:301-310`
- 现象:`canNeverFire` 走 `matchesIncomingEdge(edge, pred.getNodeStatus(), null)`,对于 SUCCESS 边 + SKIPPED 上游,该方法返回 false → `canNeverFire` 认为"边匹配失败 + 终态" → 视为永不可 fire → 节点被 cascadeSkip。
- 这在 ALL-mode 下是想要的(全 SKIPPED 上游应该一起 cascade),但 ANY-mode 下,若只有 1 条上游 SKIPPED 而其他还在 RUNNING,`canNeverFire` 会因为 `pred == null || !isTerminal` 退出 false(正确),问题在于 SUCCESS-edge 的语义还是"该上游必须 SUCCESS",对 ANY 节点意味着这条边永远没机会贡献。
- 影响:join=ANY 节点有 N 条 SUCCESS 入边,只要其中一条上游 SKIPPED 就在该上游上"扣 1 个有效贡献者",剩余上游若全部最终 FAILED → matchedCount=0 但 terminalCount=N → 不满足 ANY 的 `matchedCount>=1`,节点正确进入 cascadeSkip 终态。看起来语义正确,但**没有显式覆盖测试用例**,ANY-mode 的 cascadeSkip 行为只在 P2-5 注释里隐式说。
- 建议:补一个 `WorkflowDagServiceTest` 集成测试覆盖 ANY-mode + 1 SKIPPED + N-1 FAILED 场景,锁死该回归。这是注释里宣称"覆盖三种 join"但 canNeverFire 注释只说了 ALL,文档缺口。

### P1-5 `BatchDayReplayDispatcher.dispatchEntry` 跑 `@Transactional(REQUIRES_NEW)`,但调用方法在同一类内 → AOP 失效

- 文件:`BatchDayReplayDispatcher.java:102 / 128`
- 现象:`dispatchSession` 调用 `dispatchEntry(session, entry)`,后者标了 `@Transactional(propagation = REQUIRES_NEW)`。但这是**同类自调用**,Spring AOP 代理不会织入,`REQUIRES_NEW` 失效 → 实际复用外层事务(如果存在)或裸跑无事务(`scheduledDispatch` 无 `@Transactional`,所以是裸跑)。
- 影响:幸运的是 dispatchSession 本身没有 `@Transactional`,dispatchEntry 退化成无事务执行 + 单条 `entryMapper.updateStatus`(MyBatis 自身 autoCommit),不会污染外层 — 但**注释里宣称的"独立 REQUIRES_NEW 短事务避免单条失败 rollback 整批"语义没有兑现**。compensationService.submit 内部有自己的 `@Transactional`,所以 submit 成功后 entry.updateStatus 即使被异常打断也不会回滚 submit,实际表现 ≈ 预期,但只是"刚好不出事"。
- 建议:把 `dispatchEntry` 抽到一个独立 bean(如 `BatchDayReplayDispatchUnit`)走构造注入,与 `PartitionReclaimUnit` 同模式,真正激活 AOP。或注入 `@Lazy self` 自代理。

### P1-6 `BatchDayReplaySessionMapper.selectActiveByCalendarBizDate` 在 `submit` 后立刻重读拿 id,但若 read-after-write 走 replica 会拿空 → 不过 orchestrator 主链严禁读写分离,所以本地路径安全;留作架构边界警告

- 文件:`BatchDayReplayService.java:120-127`
- 现象:`record` 不可变,MyBatis useGeneratedKeys 写不回 id,作者用唯一索引 `(tenant, calendarCode, bizDate, active)` 重 SELECT。CLAUDE.md §读写分离明确说 orchestrator 严禁引入读写分离,所以这里安全。
- 建议:在 mapper XML 里给 insert 加 `<selectKey order="AFTER" keyProperty="id">` 拿 RETURNING id(PG 支持),消除重 SELECT;或把 `BatchDayReplaySessionEntity` 改成可变 `@Data` class(打破 record 习惯),用 useGeneratedKeys 直接回写。当前实现可工作但每次 submit 多一次 round-trip。

---

## 4. P2 — 细粒度退化 / 潜在 race

### P2-1 `DefaultWorkflowNodeDispatchService.nextRunSeq` 与 `recordNodeRunReady` 之间存在窄窗 race

- 文件:`DefaultWorkflowNodeDispatchService.java:561-580`
- 现象:`nextRunSeq` 走 `selectLatestByWorkflowRunIdAndNodeCode`(无锁)拿 max+1,再 insert。两个线程同时跑同节点(理论上已被 `isNodeAlreadyActivated` 的 `selectLatestForUpdate` 拦死),但当 latest_run 行尚未存在时(首次激活),`selectLatestForUpdate` 返回 null → 两个线程都通过,各自算 runSeq=1 → 走到 insert 撞 unique 索引(`workflow_node_run UNIQUE(workflow_run_id, node_code, run_seq)`)抛 `DuplicateKeyException`。
- 影响:第二个线程的 dispatchNode 整事务回滚,outbox 写入也跟着回 — 这其实是想要的语义(同 partition 不重复 dispatch),但**调用方 `DefaultTaskOutcomeService.advanceDagNodes` 没有 catch 这个异常**,会让单条 task outcome 的 commit 失败,worker 那边视为 report 失败重试 → 第二次 report 会发现节点已被对方派发,跳过。整体最终一致,但伴随一次明显的 ERROR 日志和一次额外 worker 重试。
- 建议:在 `recordNodeRunReady` / `recordNodeRunFinish` 插入路径外层 catch DuplicateKeyException 转 INFO 日志 + 让事务正常提交(已有行视为前序对手已落地)。代码里注释行 104 提了"残留 race 由 unique 索引 + DuplicateKeyException catch 回退"但**没有真 catch**。

### P2-2 `SensorStateMachine.advanceDownstream` 失败不写审计 + 不级联 SKIPPED

- 文件:`SensorStateMachine.java:235-281`
- 现象:WAIT 节点 finishFailure 后 advanceDownstream 走 `success=false` 的 resolveNextNodes,FAILURE 边走分支,但**不调 `cascadeSkipDownstream`**(注释里说"失败路径 + cascadeSkipDownstream 不在本类范围,由 WorkflowDagService 在下次 worker 回报时自然推进")。
- 影响:WAIT 是叶子节点经常用作 join 上游,如果整 workflow 后面没有再有 worker 回报来触发 outcome 链路,cascadeSkip 永远不跑 → SKIPPED 不会被正确级联,可能漏掉一些 ALL-mode join 下游长期停滞。当前任务描述说"FAIL_NODE / cascade-skip"是关心点,这里是显式缺口。
- 建议:`finishFailure` 末尾对当前节点显式调 `workflowDagService.cascadeSkipDownstream`(就像 `DefaultTaskOutcomeService.advanceDagNodes:680` 一样),保持失败语义一致。

### P2-3 `BatchDayGateService.evaluateAndApply` CATCH_UP / RERUN 强制绕过 frozen,但**不检查 day_status=SKIPPED 的回放**

- 文件:`BatchDayGateService.java:60-70`
- 现象:`isFrozenBypassTrigger` 让 CATCH_UP / RERUN 不看 frozen 标志。但 batch_day_instance 可能处于 `SKIPPED`(运维手动跳过)或 `MANUAL_RELEASED`(强制放行),两者业务上"不应该再来 CATCH_UP",但当前代码不拦。
- 影响:运维已经标 SKIPPED 的批量日,后续 CATCH_UP 仍能起 job → 数据二次写入。
- 建议:`isFrozenBypassTrigger` 通过后,再加一层 `dayStatus IN (SKIPPED) && triggerType=CATCH_UP` 时拒绝;或加 audit 警告但放行(决策权交业务)。

### P2-4 `PartitionLeaseReclaimScheduler.LOCK_AT_MOST_MILLIS = 120s` 是字面常量,与 properties 不联动

- 文件:`PartitionLeaseReclaimScheduler.java:59`
- 现象:`@SchedulerLock(lockAtMostFor = "PT2M")` 与代码常量 `120_000L` 都写死,但 `governance.partitionLease().getReclaimBatchSize` 等其它策略走 properties。若运维调大 batchSize 同时不能改 lockAtMost,LOOP_BUDGET_MILLIS 95% 跑 batch 用不完。
- 建议:把 lockAtMost 抽到 `BatchPartitionLeaseProperties` 一并配置;现状只能改代码重发版。

### P2-5 `OutboxPollScheduler.scheduleNext` 在 `executor.getScheduledExecutor().isShutdown()` 时直接 return → 错过 graceful shutdown 后无人接力

- 文件:`OutboxPollScheduler.java:277-281`
- 现象:shutdown 状态下 return,本进程的轮询循环就此停止。生产是多实例 + ShedLock,其它实例会接,所以业务上能继续。但单实例部署(开发 / 测试)若 executor 提前 shutdown(比如 SIGTERM 处理顺序不对),outbox 会卡到下次启动。
- 建议:加 INFO 日志"polling loop terminated due to executor shutdown",避免静默退出。

### P2-6 `BatchDayReplayService.submit` 的 `SCOPE_OUTPUTS_ONLY` 在 entries.isEmpty 时报 `no_candidates`,但 `materializeOutputsOnlyEntries` 是先全部解析再插入 — 漏掉 versionIds 为空的语义校验

- 文件:`BatchDayReplayService.java:336-348`
- 现象:versionIds 为 null/empty 在 materialize 入口已经抛 `outputs_only_version_ids_required`,但 `deriveJobCode` 在 businessKey 不合规时也抛异常,导致**部分合规 versionIds + 部分不合规**时,整批失败,合规的也没插入。
- 建议:把不合规的 versionIds 收集到 `failedVersionIds` 列表,在异常里返回详细列表,运维好排查。当前直接抛字符串,信息有限。

### P2-7 `DefaultRetryGovernanceService.classifyErrorClass` 用 `NON_RETRYABLE_ERROR_CODES` 做 BUSINESS 分类,但该集合是 hardcoded final Set,新增硬错码必须改代码重发版

- 文件:`DefaultRetryGovernanceService.java:77-88`
- 现象:9 个硬错码写死,运维新加 stage / step 后扩硬错码需要发版。
- 建议:抽到 `BatchOrchestratorGovernanceProperties.retry.nonRetryableErrorCodes`,运维可热加载;保留代码 default 作 fallback。

---

## 5. P3 — 一致性 / 可读性

### P3-1 `DefaultPipelineExecutor` 内联 `Slf4j` 而非用 `SwallowedExceptionLogger`,与 orchestrator 大量类不一致

- 文件:`DefaultPipelineExecutor.java:62`
- 现象:step 找不到时 `log.warn`,但 orchestrator 大量类(`DefaultWorkflowDagService` / `TaskDispatchOutboxService`)统一用 `SwallowedExceptionLogger.info` / `.warn`,本类不对齐。

### P3-2 `BatchDayGateService.appendAuditLog` 与 `BatchDaySettleScheduler.appendBatchDayAuditLog` 重复实现 90% 相同结构

- 建议:抽 `BatchDayAuditLogger` 共用工具。

### P3-3 `WorkflowRunManagementApplicationService` 与 `SensorStateMachine.advanceDownstream` 各自实现"END 节点 → recordNodeRunFinish"路径

- 文件:相关三处。
- 建议:抽 `WorkflowEndNodeFinisher` 单一实现,避免后续修 END 语义要改 3 处。

### P3-4 `ApprovalController` 5 个 record 全部裸放在 controller 同文件,字段冗长

- 文件:`ApprovalController.java`
- 建议:迁到 `controller/request/` 与项目其它 controller 一致(`AlertEmitRequest` / `TaskLeaseRenewBatchRequest` 都已外移)。

### P3-5 `BatchDayReplayDispatcher` 注释里宣称 "ENTRY_BATCH_SIZE 双控防止压垮",但**没有 metric 反映实际 throughput**

- 建议:加 `batch.replay.dispatch.entries_total` Counter,运维能看到 backlog。

### P3-6 `OrchestratorStartupLeaseAudit` 的 if 链长度 7 个条件,可读性差

- 建议:抽 `AuditFindings` record 用一行布尔 OR 短路。

---

## 6. 跨域观察(不算 finding,记一笔)

### 6.1 三 outbox 表分工清晰

`outbox_event`(通用业务) / `event_outbox_retry`(发布者级 I/O 重试) / `trigger_outbox_event`(trigger 模块独立)分工与 CLAUDE.md 一致。`OutboxDomainEventPublisher` 明确不处理 trigger_outbox_event。无误用。

### 6.2 Audit 双写策略统一

`BatchDayOperationService` 写 `batch_day_operation_audit` + `job_execution_log` 双表(V105),其它运维路径单写 `job_execution_log`。设计上一致,但 dead-letter replay / outbox cleanup / workflow skip-node 几条路径缺 audit(P1-1 / P1-2 / P1-3 已列)。

### 6.3 状态机 CAS 全部走 `expected_status` 守护

`WorkflowRunMapper.updateStatus` `JobInstanceMapper.updateStatus` `BatchDayInstanceMapper.updateWithCas` 都带 expected 前态守护,无裸写;`ApprovalCommandMapper` 用 (fromStatus, toStatus) CAS 也对齐。模式统一。

### 6.4 Worker 侧 lease renewer 有熔断 + 半开探测,与 orchestrator 侧 OutboxPublishCircuitBreaker 是同思路

`WorkerTaskLeaseRenewer` 实现了"100% renew 失败 OPEN → 周期半开 → 任一成功 CLOSE",与 OutboxPoll 端的熔断器结构对称。这是少见的双向自愈设计,值得保留。

### 6.5 `DefaultRetryGovernanceService` 把"任务级重试" + "死信自动重放"两条链统一在一个 service

虽然两者都用 `TaskDispatchOutboxService` 收敛,合并到一处可读性偏差。建议长远拆分(retry / dead-letter)两个 service,但功能上无 bug。

---

## 7. 修复优先级建议(供 P0/P1 排期)

| 等级 | 编号 | 简述 | 修复成本 | 业务影响 |
|---|---|---|---|---|
| P0 | P0-1 | ApprovalType enum 与代码硬编码漂移 | S(加 enum + 守护) | 中(FE 字典 + 静态守护) |
| P0 | P0-2 | Replay reconciler 同 jobCode 多 entry 错回填 | S(加 mapper + 改 find) | 高(session 长期停滞) |
| P0 | P0-3 | Alert 子系统无 routing/notification | M(加 route 表 + 异步分发) | 高(生产告警瞎子) |
| P1 | P1-1 | dead-letter replay 缺 audit + operatorId | S | 中 |
| P1 | P1-2 | workflow skipNode 缺 audit + alert | S | 中 |
| P1 | P1-3 | outbox cleanup/republish 缺 dry-run + audit | S | 高(误删) |
| P1 | P1-4 | canNeverFire 缺 ANY-mode 显式测试 | S | 低(语义已对) |
| P1 | P1-5 | BatchDayReplayDispatcher.dispatchEntry AOP 失效 | S(抽 bean) | 低(刚好不出事) |
| P1 | P1-6 | session id 走重 SELECT | S(用 RETURNING) | 低(perf) |

**P0 = 3 项**(P0-1 / P0-2 / P0-3),**P1 = 6 项**(P1-1 ~ P1-6),P2 = 7 项,P3 = 6 项。

---

## 8. 高风险路径详解(补充)

### 8.1 Workflow DAG 派发完整时序(以 join=ALL + cascade-skip 为例)

```
worker report task terminal
  → DefaultTaskOutcomeService.recordTaskTerminal
    → advanceDagNodes(ctx)
      1. recordNodeRunFinish(currentNode, success/fail)  [self.recordNodeRunFinish 走 AOP]
      2. resolveNextNodes(success)                       [outgoing edges, SUCCESS/ALWAYS/CONDITION]
      3. for each next:
         - END:  recordNodeRunStart + recordNodeRunFinish (内联,无 worker)
         - 其它: dispatchNode(jobInstance, run, next, payload, traceId)
                  ├ isNodeAlreadyActivated (FOR UPDATE)  [P1-4 修复]
                  ├ isNodeReadyForDispatch (batch 上游 join check)
                  ├ evaluateCrossDayDependencies (ADR-018, WAITING/FAILED/RESOLVED 三态)
                  ├ GATEWAY / START → 合成 RUNNING→SUCCESS + 递归 dispatch
                  ├ JOB             → ChildJobLaunchSupport (虚拟 partition + 子 launch)
                  └ TASK / FILE_STEP → SchedulePlan + ResourceScheduler + outbox 派发事件
      4. if currentNode FAILED: cascadeSkipDownstream(currentNode)
```

`cascadeSkipDownstream` 走 BFS,对每个下游用 `canNeverFire` 谓词判断,谓词要求所有入边对应前驱已终态 AND 没有任何入边能 match。matchesIncomingEdge 对 SKIPPED 前驱在 ALWAYS 边返 false(P2-5 fail-closed)。BFS 写入 SKIPPED node_run 行(`insertSkippedNodeRunIfAbsent` 用 selectLatest 防重)。

### 8.2 Sensor WAIT 节点状态机

```
scheduler 每 N 秒扫到期 WAIT node_run
  → SensorStateMachine.probeAndAdvance
    ├ 校验 wfRun / nodeMeta / SensorConfig
    ├ elapsed >= timeout_seconds → finishFailure (on_timeout=FAIL / SKIP_DOWNSTREAM)
    ├ SensorPolicy.probe(ctx)
    │   ├ MATCHED      → finishSuccess + advanceDownstream(success=true)
    │   ├ NOT_YET      → updateSensorProbeState(next=now+poll, errors=0)
    │   └ ERROR        → newErrors >= 3 ? finishFailure : 指数退避(2/4/5x)
```

注:`advanceDownstream` 失败路径不调 cascadeSkipDownstream(P2-2)。

### 8.3 BatchDay 状态机完整流转

```
OPEN (BatchDayOpenScheduler 写入 IN_FLIGHT/CUTOFF 初始)
  → IN_FLIGHT (有 active job_instance)
  → CUTOFF   (BatchDayCutoffScheduler 按 calendar 切换到截止)
  → SETTLING (BatchDaySettleScheduler.claimSettling CAS)
  → 终态(REQUIRES_NEW tx2):
      ├ activeCount>0  → 回 IN_FLIGHT
      ├ totalCount<=0  → 回 CUTOFF
      ├ failedCount>0  → FAILED + afterCommit driveCatchUp
      └ default        → SETTLED
  
  人工 op (BatchDayOperationService): FREEZE / RELEASE / SKIP / REOPEN / CLOSE
  各 op 双写 batch_day_operation_audit + job_execution_log(V105)
  RELEASE 触发 BatchDayWaitingReleaseScheduler 重放 waiting_launch
```

### 8.4 Replay session 状态机

```
submit
  ├ validate scope / autoApprove
  ├ materializeEntries (按 scope: ALL / ALL_FAILED / SUBSET / OUTPUTS_ONLY)
  │   └ deriveJobCode (R2-P1-5 fail-fast,business_key 不合规直接抛)
  ├ insert session (autoApprove ? RUNNING : PENDING_APPROVAL)
  └ batch insert entries(PENDING)
  
RUNNING 路径:
  ├ OUTPUTS_ONLY: BatchDayReplayService.executeOutputsOnly (同步 promote)
  └ 其它:  BatchDayReplayDispatcher.scheduledDispatch (30s 周期)
            ├ selectByStatus(RUNNING) 取 N 个 session
            └ 每 session 拉 PENDING entries → compensationService.submit
                  ├ 成功 → entry=RUNNING
                  └ 失败 → entry=FAILED + 失败原因
  
job_instance 终态 → DefaultTaskOutcomeService 钩子
  → BatchDayReplayTerminalReconciler.reconcileOnTerminal (REQUIRES_NEW)
    ├ findEntry(sessionId, tenantId, jobCode)  [P0-2: 线性扫第一条,同 jobCode 多 entry 错乱]
    ├ mapInstanceTerminalToEntryStatus
    └ advanceSessionCounts → inFlight==0 → SUCCEEDED / PARTIAL_FAILED
```

### 8.5 Outbox 三表 + 重试 + 熔断 + 分片

```
业务事务
  → TaskDispatchOutboxService.writeDispatchEvent (Propagation.MANDATORY)
    → DomainEventPublisher.publish → outbox_event INSERT
  
OutboxPollScheduler (自适应 200ms - pollInterval)
  ├ resetStalePublishing (PUBLISHING > publishingTimeout → FAILED)
  ├ ShardAssignment (DYNAMIC / STATIC)
  ├ ShedLock outbox_poll[_shard_N] (lockAtMost = publishingTimeout + 10s 缓冲)
  ├ OutboxPublishCircuitBreaker.allowNow (整轮 100% 失败 OPEN)
  └ DefaultScheduleForwarder.advance
        ├ Kafka send (KafkaOutboxPublisher)
        ├ 成功 → outbox_event=PUBLISHED
        ├ 失败 → outbox_event=FAILED + event_outbox_retry 行
        └ 重试耗尽 → outbox_event=GIVE_UP
  
OutboxOpsController (console proxy 转发)
  ├ cleanup: 删 PUBLISHED+GIVE_UP & older than retainDays
  └ republish: FAILED/GIVE_UP → NEW (重派)
```

### 8.6 Dead-letter / 重试 双链

```
task FAIL report
  → DefaultRetryGovernanceService.scheduleRetryIfNecessary
    ├ NON_RETRYABLE_ERROR_CODES.contains(errorCode) → createDeadLetter (BUSINESS)
    ├ retryPolicy=NONE / maxRetryCount<=0 → createDeadLetter (SYSTEM)
    ├ nextRetryCount > maxRetryCount → createDeadLetter
    └ insert retry_schedule (WAITING, next_retry_at = backoff(policy, count))
  
DeadLetterAutoRetryScheduler
  → autoRetryDueDeadLetters (SYSTEM 类才走自动,BUSINESS max=0)
    └ replayDeadLetter(REQUIRES_NEW, noRollbackFor=OrphanSourceException)
        ├ markReplaying CAS
        ├ requeuePartition (resetForDispatch + resetForRetry + outbox 派发)
        └ orphan(partition / instance 缺失) → markGiveUp + 不占预算
  
DeadLetterController.replay  [P1-1: 缺 operatorId / audit]
  → 同 replayDeadLetter,人工触发
```

### 8.7 Lease 回收双闸 + worker 端续期熔断

```
Worker:
  WorkerTaskLeaseRenewer (10s 周期, fast-retry 2s)
    ├ snapshot active leases
    ├ batch renew → orchestrator POST /tasks/leases/renew-batch
    ├ 整轮 100% 失败 → 进程级 OPEN
    └ halfOpenTickInterval 周期半开探测
  
Orchestrator:
  PartitionLeaseReclaimScheduler (15s 周期, ShedLock lockAtMost=2m)
    ├ LOOP_BUDGET_MILLIS = 75% lockAtMost = 90s 主动让出
    ├ selectExpiredLeasesGlobal (lease_expire_at < now AND status IN READY/RUNNING)
    └ PartitionReclaimUnit.reclaim (REQUIRES_NEW)
          ├ partition CAS resetForDispatch
          ├ 第二步 task CAS 失败 → 抛 ReclaimRetryableException → 回滚 partition
          └ 成功 → writeDispatchEvent(eventKey 含 version, RunMode.RECOVER)
  
  Sweeper (5min 周期):
    └ sweepOrphanRunningTasks (升级前残留 partition READY + lease NULL + task RUNNING)
  
  OrchestratorStartupLeaseAudit (ApplicationReadyEvent)
    └ 仅告警,不修复:drainingStale + leasesExpired + outboxStuck
```

## 9. 不在本扫描覆盖范围(声明)

- Worker 侧 stage 实现细节(只读了文件清单,未逐 step 审 IMPORT/EXPORT/DISPATCH 业务对错);
- Trigger 模块(`batch-trigger`)只在跨域引用处带过;
- `batch-worker-atomic` SPI(ADR-029 隔离);
- 性能 / 压测层(已有 `load-tests` 独立 reactor,不在本静态审计范围);
- SQL 索引 / DBA 维度(2026-05-20 已有专项 review);
- 前端 / FE 渲染层(本任务限定 BE 业务 + 运维);
- 安全 / 加解密路径(`batch.security.bypass-mode` 总开关已 audit 过,详见 `docs/runbook/feature-switches.md`)。

## 10. 复核建议(下一轮 scan)

- **架构级缺口扫描**(对标 memory `feedback_audit_vs_architecture_review`):本次扫到的 P0-3(告警 routing 缺失)就是典型架构级缺口,全部通过的状态机审计掩盖了运维体感的真实缺位。下轮建议:
  - 明确"运维告警"威胁模型,列必须能 reach 人的 channel,反推系统侧缺什么;
  - 把 RLS / 多租隔离的"实施一致性"重审一次(MultiTenantIsolationIntegrationTest 守护够不够);
  - `@AuditAction` 注解模式:本仓只有零散的 `appendAuditLog` 方法,缺统一切面;若引入注解切面可一并扫所有运维端点。
- **dry-run 边界**:ADR-026 dry-run 只在 launch 链路有专 controller,outbox / dead-letter / batch-day op 一律无 dry-run。建议引入运维端点 dry-run 注解标准。
