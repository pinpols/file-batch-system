# 系统深度分析报告：Bug、漏洞与设计缺陷

> 分析范围：全部 8 个模块 | 分析日期：2026-04-07

---

## 一、严重等级统计

| 等级 | 数量 | 说明 |
|------|------|------|
| **CRITICAL** | 8 | 可导致数据丢失、状态损坏、服务不可用 |
| **HIGH** | 10 | 可导致任务重复执行、状态不一致 |
| **MEDIUM** | 14 | 安全隐患、资源泄漏、可观测性缺失 |
| **LOW** | 3 | 代码健壮性、配置灵活性 |
| **合计** | **35** | |

---

## 二、CRITICAL 级别问题（8 项）

### C-1. Workflow Node 状态 TOCTOU 竞态条件
- **文件**: `DefaultTaskOutcomeService.java:116-131`
- **问题**: `recordNodeRunFinish()` 先查询再写入，两个线程可同时判定 `current == null` 并创建重复记录
- **后果**: Node 状态不一致，DAG 推进逻辑断裂，工作流永久挂起

### C-2. 非幂等状态机转换导致并发状态撕裂
- **文件**: `DefaultTaskOutcomeService.java:203-293`
- **问题**: 多个 Worker 同时汇报同一 Task 完成时，partition 计数在两次读取间变化，状态机产生不同的转换结果
- **后果**: Job Instance 状态在 `FAILED → RUNNING → FAILED` 间翻转

### C-3. DAG Node 分发无幂等保护
- **文件**: `DefaultWorkflowNodeDispatchService.java:61-92`
- **问题**: `isNodeAlreadyActivated()` 检查与 `createPartitions()` 之间无原子保证
- **后果**: 同一个 Node 被创建重复的 Partition 和 Task，破坏基数约束

### C-4. 文件路径遍历漏洞（任意文件读取）
- **文件**: `DispatchFileContentResolver.java:50`
- **问题**: `storage_path` 未做路径规范化，攻击者可构造 `../../../../etc/passwd`
- **后果**: 通过 dispatch 流程读取服务器任意文件

### C-5. 大文件 OOM 崩溃
- **文件**: `ReceiveStep.java:77`, `ParseStep.java:147`
- **问题**: `getRawPayload().getBytes().length` 将整个文件加载到内存两次，无大小限制
- **后果**: 1GB 文件 → 2GB 堆内存分配 → 服务不可用

### C-6. Trigger 去重 Check-Then-Act 竞态
- **文件**: `DefaultTriggerService.java:108-135`
- **问题**: SELECT 和 INSERT 不在同一事务中，两个请求可同时通过去重检查
- **后果**: 同一个触发器请求被重复提交，产生重复任务

### C-7. 节假日调整死循环
- **文件**: `CalendarBizDateResolver.java:53-67`
- **问题**: `previousWorkday()` 无最大迭代限制，若日历全部标记为假日则永不退出
- **后果**: 调度线程永久挂起，整个触发器模块瘫痪

### C-8. Partition 状态与 Task 状态原子性断裂
- **文件**: `DefaultTaskOutcomeService.java:156-191`
- **问题**: Task 标记 SUCCESS 后 Partition 更新失败时，Task 已在终态但 Partition 仍在 READY
- **后果**: Job 永久等待 Partition 完成，任务死锁

---

## 三、HIGH 级别问题（10 项）

### H-1. Partition 租约过期与完成的竞态
- **文件**: `DefaultPartitionLifecycleService.java:85-97`
- **问题**: Reclaim 调度器读取过期租约后，Worker 恰好完成任务并更新版本号 → CAS 失败 → Partition 卡在 RUNNING
- **后果**: Partition 既不被回收也不会执行，成为幽灵分区

### H-2. Retry 不校验 Task 终态
- **文件**: `DefaultRetryGovernanceService.java:164-180`
- **问题**: `retryTask()` 未检查 Task 是否在 FAILED/TERMINAL 状态，RUNNING 中的 Task 也可被重置
- **后果**: 同一 Task 并发执行两次

### H-3. Task 创建与分发跨事务
- **文件**: `DefaultPartitionDispatchService.java:164-186`
- **问题**: `createTask()` 和 `releaseForDispatch()` 在不同事务中，前者提交后者失败时 Task 成为孤儿
- **后果**: Task 永久卡在 CREATED 状态

### H-4. Trigger 事务隔离缺陷
- **文件**: `DefaultTriggerService.java:114-135`
- **问题**: INSERT 用 `REQUIRES_NEW` 提交，但 `sendTrigger()` 在外部失败时实体已持久化为 ACCEPTED
- **后果**: 请求永远不会到达 LAUNCHED，无自动重试

### H-5. 审批流程无分布式锁
- **文件**: `DefaultTriggerService.java:73-106`
- **问题**: 多实例部署下，同一 requestId 的审批可以被两个实例同时处理
- **后果**: 重复触发下游编排

### H-6. SFTP 禁用主机密钥验证
- **文件**: `SftpDispatchChannelAdapter.java:74`
- **代码**: `session.setConfig("StrictHostKeyChecking", "no")`
- **后果**: 中间人攻击可截获凭据和文件内容

### H-7. Kafka Offset 在异常时仍提交
- **文件**: `AbstractTaskConsumer.java:72-111`
- **问题**: `taskDispatchExecutor.execute()` 抛异常时 `doConsume()` 仍返回 `true`，offset 被推进
- **后果**: 投毒消息（poison pill）被静默跳过，消息永久丢失，无 DLQ

### H-8. Export 无内存上限控制
- **文件**: `GenerateStep.java:64-70`
- **问题**: 数据库查询返回结果集无大小限制，`DetailPage.rows()` 可任意大
- **后果**: 百万行导出时堆内存耗尽

### H-9. Cron 表达式未验证即调度
- **文件**: `TriggerSchedulerFacade.java:183-189`
- **问题**: 直接传入 `CronScheduleBuilder.cronSchedule()` 无预校验
- **后果**: 畸形表达式导致调度器运行时异常

### H-10. NAS 目录路径未规范化
- **文件**: `RemoteFilesystemDispatchSupport.java:47`
- **问题**: `dispatchNas()` 中 `Path.of(remoteDir)` 未调用 `.normalize()`（而 `dispatchSftp()` line 108 有）
- **后果**: `nas_remote_directory = "/mnt/nas/../../../etc"` 可写入任意目录

---

## 四、MEDIUM 级别问题（14 项）

| # | 问题 | 文件 | 影响 |
|---|------|------|------|
| M-1 | 状态机反射失败静默返回 null，状态变成类名 | `DefaultStateMachine.java:54-62` | 工作流状态损坏 |
| M-2 | Partition SQL 无状态合法性校验 | `JobPartitionMapper.xml:201-210` | 允许非法状态转换 |
| M-3 | 审批 `markExecuted` 不区分幂等与首次 | `DefaultApprovalWorkflowService.java:68-75` | 审批重复执行 |
| M-4 | 配额评估返回时未保存峰值计数 | `QuotaRuntimeStateService.java:87-107` | 配额超限 |
| M-5 | Import LoadStep 失败时删除 staging 文件 | `LoadStep.java:105-106` | 部分加载无法恢复 |
| M-6 | 临时文件仅启动时清理 | `StaleTempFileCleanup.java:22` | 运行期磁盘泄漏 |
| M-7 | InputStream 链式关闭不完整 | `DispatchFileContentResolver.java:44-68` | FD 耗尽 |
| M-8 | SMTP/SFTP 密码存储为 String | 多处 | 凭据无法安全擦除 |
| M-9 | XXE 防护不完整 | `ParseStep.java:298-303` | 缺少 DTD/Schema 外部访问限制 |
| M-10 | `CatchUpPolicyType.fromCode` 静默回退 | `CatchUpPolicyType.java:24-34` | 错误配置被掩盖 |
| M-11 | `TimeZone.getTimeZone` 静默回退 UTC | `TriggerSchedulerFacade.java:187` | 触发时间错位 |
| M-12 | Kafka 消息缺少字段校验 | `AbstractTaskConsumer.java:232-239` | 畸形消息引发下游 NPE |
| M-13 | ShedLock 超时 5 分钟可能不够 | `TriggerSchedulerFacade.java:35` | 大集群重复注册 |
| M-14 | 日历定义被删除时触发器静默跳过 | `DefaultTriggerService.java:174-208` | 触发器无感知失效 |

---

## 五、LOW 级别问题（3 项）

| # | 问题 | 文件 |
|---|------|------|
| L-1 | 重试延迟指数计算可能整数溢出 | `DefaultRetryGovernanceService.java:353-371` |
| L-2 | `RestClient` 双重检查锁缺少 `volatile` | `HttpTaskExecutionClient.java:39` |
| L-3 | Workflow JoinMode 未校验合法性 | `DefaultWorkflowDagService.java:140-160` |

---

## 六、系统性设计缺陷

### D-1. 缺乏全局幂等层
Task Outcome、Node Dispatch、Trigger Request 三个关键路径都依赖"先查后写"模式，没有统一的幂等键 + 数据库唯一约束机制。应在数据库层面强制 `UNIQUE(tenant_id, idempotency_key)`。

### D-2. 事务边界设计不一致
`CLAUDE.md` 要求 outbox_event 与状态写入同事务，但实际代码中存在多处跨事务操作：
- `createTask()` vs `releaseForDispatch()`（H-3）
- `INSERT request` vs `sendTrigger()`（H-4）
- `markTaskFinish()` vs `markPartitionStatus()`（C-8）

### D-3. 无 Dead Letter Queue
Kafka 消费失败后消息被静默跳过，无 DLQ 兜底。应实现 `ErrorHandler` + DLQ topic + 人工审查接口。

### D-4. 无文件大小限制
Import 和 Export 均无文件/数据大小上限配置，系统完全暴露于 OOM 攻击。

### D-5. 安全边界缺失
- 文件路径未做沙箱限制（C-4, H-10）
- SFTP 禁用主机校验（H-6）
- XXE 防护不完整（M-9）
- 凭据管理无安全擦除机制（M-8）

---

## 七、修复优先级建议

**P0 — 立即修复（影响数据安全/服务可用性）：**
1. **C-4** 路径遍历 → 加 `Path.normalize()` + 白名单目录校验
2. **C-5** OOM → 加 `max.file.size.mb` 配置，ReceiveStep 入口拦截
3. **C-1/C-3** TOCTOU → 数据库唯一约束 + `INSERT ... ON CONFLICT DO NOTHING`
4. **C-7** 死循环 → 加 `MAX_ITERATIONS = 365` 防护

**P1 — 本迭代修复（影响任务正确性）：**
5. **C-2/C-8** 状态原子性 → 合并到同一事务，CAS 版本号
6. **H-2** Retry 前置校验 → 检查 Task 必须在终态
7. **H-7** Kafka DLQ → 实现 `DeadLetterPublishingRecoverer`
8. **H-6** SFTP → 启用 `StrictHostKeyChecking`，配置 known_hosts

**P2 — 下个迭代（影响运维/可观测性）：**
9. M-1 状态机静默失败 → 改为抛异常
10. M-6 临时文件 → 定时清理 + 文件注册表
11. M-9 XXE → 补全 `ACCESS_EXTERNAL_DTD`/`ACCESS_EXTERNAL_SCHEMA`
12. M-10/M-11 枚举/时区静默回退 → 改为异常快速失败

---

共发现 **35 个问题**，其中 **8 个 CRITICAL**、**10 个 HIGH**、**5 个系统性设计缺陷**。最核心的风险模式是 **TOCTOU 竞态 + 跨事务状态更新**，建议优先建立统一的幂等层和事务边界规范。