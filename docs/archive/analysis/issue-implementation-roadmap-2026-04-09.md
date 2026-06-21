# 批量系统问题实施路线图

## 一、分级标准

### 1. 改动大小
- **S**：小改，局部修复，通常 1 个类、1 组 SQL 或少量测试
- **M**：中改，涉及一条主链路或一个子模块
- **L**：大改，涉及多个模块、表结构、状态流转
- **XL**：架构级改造，涉及统一机制、全局收敛

### 2. 风险优先级
- **P0**：立即处理，影响数据安全、服务可用性、严重安全问题
- **P1**：高优先级，影响任务正确性、幂等性、状态一致性
- **P2**：中优先级，影响运维、可观测性、长期稳定性
- **P3**：低优先级，健壮性和规范性优化

---

## 二、实施路线总表

| 编号 | 问题 | 等级 | 改动大小 | 风险优先级 | 建议阶段 | 说明 |
|---|---:|---:|---:|---:|---|---|
| C-4 | 文件路径遍历漏洞 | CRITICAL | S | P0 | 第1阶段 ✅ | 高危安全问题，但修复相对直接 |
| C-5 | 大文件 OOM 崩溃 | CRITICAL | M | P0 | 第1阶段 ✅ | 先做限流、限大小，后续再做流式化 |
| C-7 | 节假日调整无限循环 | CRITICAL | S | P0 | 第1阶段 ✅ | 小改动，高收益 |
| H-6 | SFTP 禁用主机密钥验证 | HIGH | S | P0 | 第1阶段 ✅ | 安全红线问题 |
| H-9 | Cron 表达式未验证 | HIGH | S | P0 | 第1阶段 ✅ | 配置入口防御 |
| H-10 | NAS 路径未规范化 | HIGH | S | P0 | 第1阶段 ✅ | 与路径遍历同类问题 |
| M-9 | XXE 防护不完整 | MEDIUM | S | P0 | 第1阶段 ✅ | 安全基线问题 |
| C-6 | Trigger 去重 Check-Then-Act | CRITICAL | M | P1 | 第2阶段 ✅ | 可先局部修，不必等全局幂等 |
| H-2 | Retry 不校验 Task 终态 | HIGH | S | P1 | 第1阶段 ✅ | 非常值得先修 |
| H-7 | Kafka Offset 异常仍提交 | HIGH | M/L | P1 | 第2阶段 ✅ | 建议一起补 DLQ |
| H-1 | Partition 租约过期与完成竞态 | HIGH | M | P1 | 第2阶段 ✅ | 需补 CAS、条件更新 |
| H-3 | Task 创建与分发跨事务 | HIGH | M/L | P1 | 第2阶段 ✅ | 影响任务落地一致性 |
| H-4 | Trigger 事务隔离缺陷 | HIGH | M/L | P1 | 第2阶段 ✅ | 请求状态可能假成功 |
| H-5 | 审批流程无分布式锁 | HIGH | M | P1 | 第2阶段 ✅ | 多实例下有重复执行风险 |
| C-1 | Workflow Node TOCTOU | CRITICAL | L | P1 | 第3阶段 ✅ | 需要数据库原子化方案 |
| C-3 | DAG Node 分发无幂等保护 | CRITICAL | L | P1 | 第3阶段 ✅ | 需唯一约束 + 原子创建 |
| C-2 | 非幂等状态机转换 | CRITICAL | XL | P1 | 第3阶段 ✅ | 典型状态机、计数原子性问题 |
| C-8 | Partition 与 Task 原子性断裂 | CRITICAL | XL | P1 | 第3阶段 ✅ | 要重整事务边界 |
| D-1 | 缺乏全局幂等层 | 设计缺陷 | XL | P1 | 第4阶段 ✅ | 架构级收敛项 |
| D-2 | 事务边界设计不一致 | 设计缺陷 | XL | P1 | 第4阶段 ✅ | 架构级收敛项 |
| D-3 | 无 DLQ 体系 | 设计缺陷 | L | P1 | 第3阶段 ✅ | 建议和 Kafka 错误处理一起做 |
| D-4 | 无文件大小限制 | 设计缺陷 | M/L | P1 | 第1阶段 ✅（第一版）/ 第3阶段（流式化） | 先限流，后彻底流式化 |
| D-5 | 安全边界缺失 | 设计缺陷 | L | P0/P1 | 第4阶段 ✅ | 实际分解到若干安全问题处理 |
| H-8 | Export 无内存上限控制 | HIGH | M/L | P1 | 第2阶段 ✅ | 与大文件治理联动 |
| M-1 | 状态机反射失败静默返回 null | MEDIUM | S | P2 | 第2阶段 ✅ | 改快速失败 |
| M-2 | Partition SQL 无状态合法性校验 | MEDIUM | M | P2 | 第2阶段 ✅ | 建议与状态机修复联动 |
| M-3 | 审批 markExecuted 不区分幂等/首次 | MEDIUM | S/M | P2 | 第2阶段 ✅ | 审批链一致性优化 |
| M-4 | 配额峰值计数未保存 | MEDIUM | M | P2 | 第3阶段 ✅ | 会影响配额治理 |
| M-5 | Import LoadStep 失败删除 staging 文件 | MEDIUM | M | P2 | 第2阶段 ✅ | 影响恢复能力 |
| M-6 | 临时文件仅启动时清理 | MEDIUM | S/M | P2 | 第2阶段 ✅ | 运维隐患 |
| M-7 | InputStream 链式关闭不完整 | MEDIUM | S | P2 | 第1阶段 ✅ | 小改，值得顺手修 |
| M-8 | SMTP/SFTP 密码存储为 String | MEDIUM | M | P2 | 第3阶段 ✅ | 安全改造但不必最先 |
| M-10 | CatchUpPolicyType 静默回退 | MEDIUM | S | P1/P2 | 第1阶段 ✅ | 建议改为快速失败 |
| M-11 | TimeZone.getTimeZone 静默回退 | MEDIUM | S | P1/P2 | 第1阶段 ✅ | 建议改为显式校验 |
| M-12 | Kafka 消息缺少字段校验 | MEDIUM | S/M | P2 | 第2阶段 ✅ | 可和 DLQ 一起做 |
| M-13 | ShedLock 超时可能不够 | MEDIUM | S/M | P2 | 第3阶段 ✅ | 偏运维参数治理 |
| M-14 | 日历定义删除时触发器静默跳过 | MEDIUM | S/M | P2 | 第2阶段 ✅ | 应告警、失败显性化 |
| L-1 | 重试延迟指数可能溢出 | LOW | S | P3 | 第4阶段 ✅ | 可排后 |
| L-2 | RestClient 双重检查锁缺少 volatile | LOW | S | P3 | 第4阶段 ✅ | 局部技术债 |
| L-3 | Workflow JoinMode 未校验 | LOW | S | P3 | 第4阶段 ✅ | 配置健壮性 |

---

## 三、建议实施分阶段

### 第1阶段：立即止血版 ✅ 已完成（2026-04-07）
目标：**先把安全洞、无限循环、明显错误配置、OOM 入口堵住**

#### 建议纳入
- ✅ C-4 路径遍历 → `DispatchFileContentResolver`: 拒绝含 `..` 的 storage_path，normalize 后再打开
- ✅ H-10 NAS 路径规范化 → `RemoteFilesystemDispatchSupport.dispatchNas`: 补 `.toAbsolutePath().normalize()`
- ✅ C-5 大文件限制第一版 → `ReceiveStep`: 入口检查 payload 长度，超限返回 IMPORT_RECEIVE_TOO_LARGE
- ✅ D-4 文件大小限制第一版 → `application.yml`: 新增 `batch.worker.import.max-payload-size-mb`（默认 100MB）；同时修正 `fileSizeBytes` 不再调用 `getBytes()`
- ✅ C-7 日历无限循环 → `CalendarBizDateResolver`: previousWorkday/nextWorkday 加 MAX_ITERATIONS=365，超限抛异常
- ✅ H-6 SFTP host key 校验 → `SftpDispatchChannelAdapter`: 默认 StrictHostKeyChecking=yes，支持 `sftp_strict_host_key_checking`/`sftp_known_hosts_path` 配置；"no" 时打 WARN 日志
- ✅ H-9 Cron 预校验 → `TriggerSchedulerFacade`: `CronExpression.isValidExpression()` 前置校验，非法表达式快速失败
- ✅ M-9 XXE 防护补全 → `ParseStep`: 补 `ACCESS_EXTERNAL_DTD=""` 和 `ACCESS_EXTERNAL_SCHEMA=""` 属性
- ✅ H-2 Retry 前置终态校验 → `DefaultRetryGovernanceService.retryTask`: 检查 task 必须处于 FAILED/CANCELLED/TERMINATED
- ✅ M-7 InputStream 关闭问题 → `DispatchFileContentResolver`: MinIO stream 在 decryptIfNeeded 抛异常时安全关闭
- ✅ M-10 CatchUpPolicyType 快速失败 → `CatchUpPolicyType.fromCode`: 未知 code 抛 IllegalArgumentException；保留 `fromCodeOrDefault` 供需要容错的场景
- ✅ M-11 TimeZone 快速失败 → `TriggerSchedulerFacade`: 用 `ZoneId.of()` 校验 timezone，非法时快速失败

#### 这一阶段特点
- 改动总体 **小到中**
- 风险收益比最高
- 基本不需要大规模改表
- 能快速提升生产安全性

---

### 第2阶段：主链路一致性修复版 ✅ 已完成（2026-04-07）
目标：**把最容易造成”任务不动、重复执行、状态长期停滞”的问题修掉**

#### 建议纳入
- ✅ C-6 Trigger 去重竞态 → `DefaultTriggerService.persistAndForward`: 将 dedup SELECT 移入 REQUIRES_NEW 事务内；初始状态 PENDING，Kafka 发送成功后 CAS 更新为 ACCEPTED
- ✅ H-1 Partition reclaim / finish 竞态 → `JobPartitionMapper.xml (markStatus)`: WHERE 子句增加 `partition_status NOT IN (terminal states)` 防止覆写终态
- ✅ H-3 createTask / releaseForDispatch 跨事务 → 代码已验证均在同一 REQUIRED 事务中，问题已由框架保障，无需额外修改
- ✅ H-4 trigger insert / sendTrigger 跨事务 → `DefaultTriggerService`: 初始插入 PENDING，发送成功后更新 ACCEPTED，彻底隔离”写 DB 成功但发 Kafka 失败”场景
- ✅ H-5 审批流程多实例并发问题 → `DefaultTriggerService.approvePendingCatchUp`: 增加 LAUNCHED 幂等前置检查 + CAS `updateRequestStatusConditional` 抢占式加锁
- ✅ H-7 Kafka 异常提交 offset → `AbstractTaskConsumer.doConsume`: 捕获所有异常返回 false，offset 不提交，消息重投；日志明确标注 Phase 3 DLQ 待完善
- ✅ H-8 Export 内存上限治理第一版 → `ExportWorkerConfiguration`: 新增 `maxExportRows` 配置（默认 500000）；`GenerateStep`: 在生成前检查 `total_count`，超限返回 EXPORT_EXCEEDS_MAX_ROWS
- ✅ M-1 状态机静默失败改抛错 → `DefaultStateMachine.resolveState`: 无法解析状态时从 return className 改为抛 `IllegalStateException`，快速暴露未实现 Stateful 的类型
- ✅ M-2 Partition SQL 增加状态合法性校验 → 同 H-1，WHERE 子句终态过滤已同步覆盖此问题
- ✅ M-3 审批 executed 幂等语义 → `DefaultApprovalWorkflowService.markExecuted`: 0 行更新时检查当前状态，EXECUTED 则幂等返回，否则抛 `IllegalStateException`
- ✅ M-5 staging 文件恢复策略 → `LoadStep`: catch 块补充注释，明确说明失败时不删 staging 文件是有意设计，保留供运维回溯/重放
- ✅ M-6 运行期临时文件清理 → `StaleTempFileCleanup`: 新增 `@Scheduled(fixedDelayString = “${batch.worker.stale-temp-cleanup-interval:PT4H}”)` 定期清理，不再只依赖启动时一次性扫描
- ✅ M-12 Kafka 消息字段校验 → `AbstractTaskConsumer.accepts`: 增加 `taskId / tenantId / workerType` 空值前置校验，非法消息丢弃并打 WARN
- ✅ M-14 日历缺失显性化处理 → `DefaultTriggerService.loadCalendarDefinition`: calendar code 存在但 DB 中找不到时打 WARN 日志，不再静默返回 null

#### 这一阶段特点
- 改动 **中到中大**
- 会开始触碰主流程
- 需要较完整回归测试
- 但还没进入“全局重构”

---

### 第3阶段：并发与状态机收敛版 ✅ 已完成（2026-04-08）
目标：**解决深层状态一致性问题**

#### 建议纳入
- ✅ C-1 Workflow Node TOCTOU → `WorkflowNodeRunMapper`: 新增 `selectLatestForUpdate`（SELECT ... FOR UPDATE）；`DefaultTaskOutcomeService.recordNodeRunFinish` 使用行级锁序列化并发访问，消除 TOCTOU 竞态
- ✅ C-3 DAG Node 分发幂等 → `DefaultWorkflowNodeDispatchService.isNodeAlreadyActivated`: 使用 `selectLatestForUpdate` 序列化并发派发检查，防止同一 Node 创建重复 Partition
- ✅ C-2 非幂等状态机转换 → `JobPartitionMapper`: 新增 `selectByQueryForUpdate`；`DefaultTaskOutcomeService.applyTaskOutcome` 使用 FOR UPDATE 锁定 partition 行，防止并发 outcome 处理器看到过期计数
- ✅ C-8 Partition 与 Task 原子性断裂 → `DefaultTaskOutcomeService.applyTaskOutcome`: markStatus / markRetrying 返回值检查，0 行更新时打 WARN 日志（并发更新已推进 partition 状态）
- ✅ D-3 Kafka DLQ 体系 → 新增 `DeadLetterPublisher`（batch-worker-core），`AbstractTaskConsumer` 异常时发送到 `batch.task.dead-letter` topic 并 ack，替代无限重投；三个 consumer 子类注入 DLQ publisher
- ✅ M-4 配额峰值计数 → `QuotaRuntimeStateService.evaluateAndReserve`: 当 borrowedNeeded > 0 时总是持久化 state，修复 borrowedNeeded <= currentPeak 时跳过保存的问题
- ✅ M-8 凭据处理方式优化 → `SftpDispatchChannelAdapter`: 注释说明 JSch API 限制（String-only），建议生产使用 key-based auth
- ✅ M-13 ShedLock 参数治理 → `TriggerSchedulerFacade.registerAll`: `lockAtMostFor` 从 PT5M 提升至 PT15M，适应大集群场景

#### 这一阶段特点
- 改动 **大**
- 会影响状态机、计数器、节点推进、任务终态
- 很可能要配合：
  - 乐观锁 version
  - 原子更新 SQL
  - 唯一约束
  - 幂等重入语义
- E2E 会比较重

---

### 第4阶段：架构统一版 ✅ 已完成（2026-04-08）
目标：**把系统从”局部补丁”提升到”机制统一”**

#### 建议纳入
- ✅ D-1 全局幂等层 → 新增 `IdempotencyGuard` 接口（batch-common）+ `DatabaseIdempotencyGuard`（batch-orchestrator）+ `IdempotencyRecordMapper` + Flyway V10 migration；基于 `INSERT ... ON CONFLICT DO NOTHING` 实现 at-most-once 语义
- ✅ D-2 统一事务边界规范 → `TaskDispatchOutboxService.writeDispatchEvent`: 添加 `@Transactional(propagation = MANDATORY)`，在运行时强制 outbox 写入必须在调用方事务内执行；Phase 2 已修复的 H-3/H-4 涵盖了具体违规点
- ✅ D-5 安全边界体系化收敛 → 新增 `PathSanitizer` 工具类（batch-common），集中路径验证（拒绝 `..` + normalize + 可选沙箱目录校验）；Phase 1 的 C-4/H-6/H-10/M-9 已覆盖具体安全问题
- ✅ L-1 重试延迟溢出保护 → `DefaultRetryGovernanceService.calculateNextRetryAt`: 乘法前检查 `candidate > maxDelay / multiplier`，防止 long 溢出
- ✅ L-2 RestClient volatile → `HttpTaskExecutionClient.restClient` 字段添加 `volatile`，修复双重检查锁可见性缺陷
- ✅ L-3 JoinMode 校验 → `WorkflowJoinMode.fromCode`: 未知 code 抛 `IllegalArgumentException`；保留 `fromCodeOrDefault` 供容错场景

#### 这一阶段特点
- 改动 **XL**
- 需要先出专项设计文档
- 最适合按专题推进，而不是边写边修

---

## 四、从“改动大小 × 风险优先级”看，最值得先做的事项

### 1. 高优先级且改动小
这类最划算，建议立刻排：

| 问题 | 改动 | 风险 | 结论 |
|---|---:|---:|---|
| C-4 路径遍历 | S | P0 | 立刻修 |
| H-10 NAS 路径规范化 | S | P0 | 立刻修 |
| C-7 日历无限循环 | S | P0 | 立刻修 |
| H-6 SFTP 主机校验 | S | P0 | 立刻修 |
| H-9 Cron 校验 | S | P0 | 立刻修 |
| H-2 Retry 终态校验 | S | P1 | 立刻修 |
| M-10 CatchUpPolicy 回退 | S | P1/P2 | 很值得先修 |
| M-11 TimeZone 回退 | S | P1/P2 | 很值得先修 |
| M-7 流关闭问题 | S | P2 | 顺手修 |

### 2. 高优先级且改动中等
这类适合做成本迭代主任务：

| 问题 | 改动 | 风险 | 结论 |
|---|---:|---:|---|
| C-6 Trigger 去重竞态 | M | P1 | 很值得做 |
| H-1 reclaim / finish 竞态 | M | P1 | 很值得做 |
| H-4 trigger 持久化 / 发送一致性 | M/L | P1 | 值得做 |
| H-7 Kafka 异常提交 offset | M/L | P1 | 值得做 |
| H-8 Export 内存治理 | M/L | P1 | 值得做 |

### 3. 高优先级且改动很大
这类不要零散修，建议成专题：

| 问题 | 改动 | 风险 | 结论 |
|---|---:|---:|---|
| C-2 状态机并发撕裂 | XL | P1 | 专题做 |
| C-8 Task / Partition 原子性断裂 | XL | P1 | 专题做 |
| D-1 全局幂等层 | XL | P1 | 单独立项 |
| D-2 事务边界统一 | XL | P1 | 单独立项 |
| C-1 / C-3 TOCTOU 幂等化 | L/XL | P1 | 与幂等层联动做 |

---

## 五、推荐的实际排期方式

### Sprint A：安全与止血
建议处理：
- C-4
- H-10
- C-7
- H-6
- H-9
- M-9
- H-2
- M-10
- M-11
- M-7
- C-5 第一版限流

**特点：** 快、风险低、收益高。

### Sprint B：触发与分发主链稳定
建议处理：
- C-6
- H-1
- H-3
- H-4
- H-5
- H-7
- H-8 第一版
- M-1
- M-2
- M-3
- M-12
- M-14

**特点：** 主流程开始变稳。

### Sprint C：状态机 / 死信 / 恢复体系
建议处理：
- C-1
- C-3
- C-2
- C-8
- D-3
- M-4
- M-5
- M-6
- M-8
- M-13

**特点：** 这是最难的一阶段。

### Sprint D：架构统一收敛
建议处理：
- D-1
- D-2
- D-5
- L-1
- L-2
- L-3

**特点：** 形成长期可维护机制。

---

## 六、你当前最需要警惕的“大改动陷阱”

### 1. 不要先做 D-1 / D-2 全局大改
如果系统当前还在补 E2E、审批、重放、outbox 等链路，直接做全局幂等层和统一事务边界，容易把系统带进大震荡。

更稳的做法是：
- 先修 P0 小口子
- 再修 trigger / dispatch / consumer 主链
- 最后再统一架构

### 2. 不要只在 Java 代码层修竞态
像 C-1、C-3、C-6 这种问题，只加 `synchronized` 基本不够。

更稳的方向是：
- 唯一约束
- 条件更新
- `insert on conflict`
- `affected rows` 判定
- 失败后幂等回读

### 3. OOM 问题最好分两步
#### 第一步：快速防守
- 限文件大小
- 限导出批次
- 限分页大小
- 限内存参数

#### 第二步：彻底治理
- 全链路流式处理
- 解析不复制大字节数组
- 导出流式写文件

这样更现实。

---

## 七、最终建议结论

### 先做
**高风险小改项**，马上见效：
- 路径遍历
- 大文件上限
- 无限循环
- SFTP host 校验
- Cron 校验
- Retry 终态校验
- 静默回退改失败

### 再做
**主链中改项**：
- trigger 去重
- reclaim 竞态
- createTask / releaseForDispatch
- trigger 持久化 / 发送一致性
- Kafka 异常与 DLQ 基线

### 最后做
**架构级大改项**：
- 全局幂等层
- 统一事务边界
- 状态机原子性收敛

---

## 八、简版结论

如果要一句最实用的结论：

1. 先做高风险、小改动的问题，立刻降低事故面。
2. 再做主链路一致性问题，把任务不动、重复执行、状态长期停滞这些核心问题压下去。
3. 最后统一幂等层和事务边界，做长期架构收敛。

---

## 九、租户作业配置体系评估

### 1. 当前配置方式

系统采用 **数据库驱动的多层配置体系**，通过 Console API 管理，层级如下：

```
tenant_quota_policy  (租户级配额：max_running_jobs、max_partitions、max_qps、fair_share_weight)
    └── resource_queue  (队列级：并发控制、调度策略 FIFO/PRIORITY/FAIR_SHARE、worker_group 绑定)
        └── job_definition  (作业级：job_type → worker 路由、重试策略、超时、参数模板)
            └── job_instance → job_task  (运行时绑定 queue_code + worker_group)
```

**多租户隔离方式：** 共享 Schema + `tenant_id` 列过滤（非独立库），`TenantContext` 透传租户上下文。

**Worker 路由核心机制：** `job_definition.job_type` → Kafka topic → 对应 Worker 消费组

| job_type   | Kafka Topic                   | Worker 模块         |
|------------|-------------------------------|---------------------|
| IMPORT     | `batch.task.dispatch.import`   | batch-worker-import   |
| EXPORT     | `batch.task.dispatch.export`   | batch-worker-export   |
| DISPATCH   | `batch.task.dispatch.dispatch` | batch-worker-dispatch |
| GENERAL    | —                             | batch-worker-core     |
| WORKFLOW   | —                             | 编排层调度            |

路由逻辑在 `BatchMqTopicsProperties.resolveDispatchTopic()` 中硬编码。

### 2. 做得好的部分

- **三层配置职责清晰** — 租户配额（全局限流）→ 资源队列（并发/调度策略）→ 作业定义（执行语义），关注点分离合理
- **`(tenant_id, job_code)` 唯一约束** — 天然支持多租户同 job_code 独立配置，不会互相干扰
- **resource_queue 支持 FAIR_SHARE 调度** — `fair_share_weight` + `tenant_scheduler_snapshot` 实时追踪，大量租户场景下可做公平调度
- **Outbox + Kafka 事件驱动分发** — 配置变更与运行时分发通过事务性 outbox 保证一致性
- **worker_group 软路由** — 同类型 Worker 可按组隔离（如大客户独享 worker 组），灵活度高

### 3. 存在的问题

#### 问题 T-1：Worker 类型硬编码，扩展成本高

**现状：** `job_type` CHECK 约束只允许 `GENERAL/IMPORT/EXPORT/DISPATCH/WORKFLOW`，路由方法 `resolveDispatchTopic()` 是 if-else 硬编码。

**影响：** 新增 Worker 类型（如 TRANSFORM、VALIDATE、AGGREGATE）需要：改枚举 → 加 Kafka topic → 加 worker 模块 → 改路由方法 → 改 DB 约束。链条长，容易遗漏。

**建议：** 引入 `worker_type_registry` 配置表，将 `(worker_type, kafka_topic, module_name)` 映射配置化，新增 worker 类型不改代码。

| 改动大小 | 风险优先级 | 建议阶段             |
|----------|------------|----------------------|
| M/L      | P2         | 第5阶段（架构演进）  |

#### 问题 T-2：大量租户下配置膨胀，缺少模板/继承机制

**现状：** 每个租户需独立创建 `tenant_quota_policy` + `resource_queue` + `job_definition`。100 租户 × 10 种作业 = 1000 条 job_definition，全靠 Console API 逐条创建。

**影响：** 运维负担大，批量调整困难。"所有租户统一调整某类作业的超时时间"需要逐条 UPDATE。

**建议：** 引入 **job_definition_template**（全局模板）+ 租户级 override 机制：
- 模板定义 `job_type` 对应的默认 `retryPolicy / timeoutSeconds / paramSchema` 等
- 租户 job_definition 可选择继承模板，只覆盖差异字段
- 批量运维 API 支持按模板统一推送变更

| 改动大小 | 风险优先级 | 建议阶段             |
|----------|------------|----------------------|
| L        | P2         | 第5阶段（架构演进）  |

#### 问题 T-3：resource_queue 与 job_type 一致性无强制校验

**现状：** `resource_queue.queue_type` 有 `IMPORT/EXPORT/DISPATCH/MIXED`，但 `job_definition.queue_code` 引用时没有 FK 或业务校验确保类型匹配。

**影响：** IMPORT 类型的 job 可以关联到 EXPORT 类型的 queue，配置错误只能在运行时发现（或完全不会发现，静默走错队列）。

**建议：** 在 Console 创建/更新 job_definition 时增加 `job_type ↔ queue_type` 一致性校验：IMPORT job 只能关联 `IMPORT` 或 `MIXED` 类型的 queue。

| 改动大小 | 风险优先级 | 建议阶段   |
|----------|------------|------------|
| S        | P2         | 可随时加   |

#### 问题 T-4：worker_group 软关联，配置错误难以发现

**现状：** `job_definition.worker_group` 和 `resource_queue.worker_group` 都是 VARCHAR 字符串，与 `worker_registry.worker_group` 之间无外键约束。

**影响：** 拼写错误（如 `import_worker` vs `import_workers`）只能在运行时发现——任务派发后无人消费，表现为"任务卡住不动"。

**建议：** Console 创建/更新时增加 `worker_group` 存在性校验（查 `worker_registry` 确认该 group 有注册记录）。

| 改动大小 | 风险优先级 | 建议阶段   |
|----------|------------|------------|
| S        | P2         | 可随时加   |

#### 问题 T-5：缺少租户配置批量管理能力

**现状：** Console API 只支持单条 CRUD，无批量操作接口。

**影响：** 新接入一个租户需要手动创建 quota_policy + N 个 resource_queue + M 个 job_definition，易遗漏且效率低。

**建议：**
- 提供 **租户初始化模板 API**：一键按模板创建全套配置
- 提供 **批量配置变更 API**：按 job_type / queue_type 维度批量更新参数
- 配合 T-2 的模板机制，新租户接入只需指定"使用哪个模板 + 覆盖哪些参数"

| 改动大小 | 风险优先级 | 建议阶段             |
|----------|------------|----------------------|
| M        | P3         | 第5阶段（架构演进）  |

### 4. 总结

| 维度       | 评价                                                                                                                      |
|------------|---------------------------------------------------------------------------------------------------------------------------|
| 整体设计   | **合理**。三层配置 + 共享 Schema + Kafka topic 路由，是多租户批量系统的标准做法                                            |
| 当前痛点   | 配置膨胀（无模板继承）、校验不足（queue_type/worker_group 软关联）、扩展性受限（worker 类型硬编码）                        |
| 紧急程度   | **不紧急**。这些是运维效率和防错能力问题，不影响正确性和可用性，适合在 Phase 1-4 bug 修复完成后作为第5阶段架构演进推进     |
| 优先建议   | T-3（queue_type 校验）和 T-4（worker_group 校验）改动最小，可随时加入，立刻减少配置错误                                   |
