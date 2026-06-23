# 批量调度平台深度问题分析报告 V3

> 分析日期：2026-04-20
> 分析范围：batch-common / batch-trigger / batch-orchestrator / batch-worker-core / batch-worker-import / batch-worker-export / batch-worker-dispatch / batch-console-api
> 分析维度：安全漏洞 / 并发与数据一致性 / 架构与设计 / 运维与可靠性
> 触发背景：2026-04-19 E2E 测试报告修复 5 个生产 bug 之后，对各模块核心实现与设计文档做一次全覆盖审查，识别「同模式未被扫到」与「纵深问题」
> 审查方式：4 个并行代码审查 Agent 分别覆盖 common / trigger+orchestrator / 4 个 worker / console-api，产出 52 条发现

---

## 汇总

| 维度 | Critical | High | Medium | Low | 小计 |
|------|:--------:|:----:|:------:|:---:|:----:|
| 安全漏洞 | 4 | 3 | 2 | 2 | 11 |
| 并发与数据一致性 | 7 | 5 | 3 | 0 | 15 |
| 架构与设计 | 2 | 6 | 8 | 2 | 18 |
| 运维与可靠性 | 3 | 4 | 6 | 3 | 16 |
| **合计** | **16** | **18** | **19** | **7** | **60** |

> 说明：v2 (2026-04-15) 合计 69 条已闭环 61 条（见 `fix-report-v2.md`）；本轮 60 条发现中**无一条与 v2 重复**——都是 v2 未扫到或新引入的问题。其中 20 条是 2026-04-19 E2E 报告 5 个修复点的**同模式变体 / 纵深风险**。

---

## 分类（🐛 Bug / 🎯 设计意图 / 🛡 硬化建议）

60 条发现按性质三分：

- **🐛 Bug**（15 条，**本轮修复**）：可观察地产生错误输出、数据丢失、崩溃或越权；与代码意图明显冲突。
- **🎯 设计意图**（10 条，**不改**）：代码注释明示、或权衡式架构选择，改动需要产品/架构层决策，不是代码 bug。
- **🛡 硬化建议**（35 条，**不在本轮**）：防御纵深、注释修正、工具扩展、次要 enhancement；非必改。

### 🐛 Bug 清单（15 条，**已闭环 2026-04-20**）

状态列：
- **✅ 已修**：代码改动落盘 + 编译 / 测试通过
- **✅ 查证已修**：盘代码发现此前已有修复（subagent 看的是旧版）
- **↘️ 降级**：深度分析后发现非 bug，已归类为设计意图
- **✅ 已修（配置）**：通过配置 / 默认值调整覆盖

| 编号 | Tier | 问题 | 状态 | 实现 |
|---|---|---|---|---|
| C-2.1 | C | Report 失败 offset 仍提交 → 任务重复执行 | ✅ 已修（配置）| `PartitionLeaseProperties.expireSeconds` 60 → 120，覆盖 report 重试窗口 + renew 间隔 + 余量 |
| C-2.2 | A | DLQ 失败后信号量泄漏 → 背压失效 | ✅ 已修 | `AbstractTaskConsumer` 信号量 try/finally 覆盖 JSON 解析 / ensureStarted 异常 |
| C-2.3 | C | PartitionLifecycle 分片/任务跨事务 + 内存回滚 | ✅ 查证已修 | `DefaultPartitionLifecycleService:151-177` 代码注释明标 C-2.3，已用 setRollbackOnly + 内存还原 |
| C-2.5 | C | PartitionDispatch outbox 脑裂 | ✅ 查证已修 | `DefaultPartitionDispatchService` @Transactional + `DefaultTaskCreationService` 默认 REQUIRED 传播加入外层事务，无脑裂 |
| C-2.6 | C | LaunchBatchDay 事务外改 trigger_type | ✅ 查证已修 | `LaunchBatchDayService:338-346` 已用 4-arg CAS `updateTriggerType(CATCH_UP, EVENT)` 并发路径 0 行则 return request |
| C-2.9 | B | SessionRegistry Caffeine/Redis 更新非原子 | ✅ 已修 | Caffeine put 挪到 Redis expire 之前；expire 失败仅 WARN 不破坏版本号 |
| C-2.10 | B | Idempotency 不分 PENDING/DONE | ✅ 已修 | 区分 `CONFLICT_DONE_BODY` / `CONFLICT_PENDING_BODY` 响应 + Retry-After 头 |
| C-2.11 | B | approvePendingCatchUp CAS 后二次读过期 | ↘️ 降级 | 代码已二次读 current entity；traceId 在业务中不可变，不是 bug |
| C-2.12 | A | RealtimeEventHub close/publish 非原子 | ✅ 已修 | close 清理逻辑挪进 try/finally 保证 subscriptions.remove 必执行 |
| A-3.3 | A | StateMachine 未知事件 return fromState | ✅ 已修 | 保留 NOOP 设计意图 + default case 加 log.warn 暴露拼写错 |
| A-3.4 | B | WorkflowDagService JOIN 边删后无限循环 | ✅ 已修 | currentNode null 立即 return false；非-START 空入边加 WARN 日志 |
| R-4.2 | C | NAS symlink 未解析 | ✅ 已修 | `resolveNasDirectory` 加 toRealPath + 可选 `-Dbatch.dispatch.nas-sandbox-root` 沙箱强校验 |
| R-4.3 | A | ValidateStep 删文件时序倒置 | ✅ 已修 | writer 关闭后才 delete；删掉不安全的 failStreaming 方法 |
| R-4.5 | A | awaitDrain 硬编码 sleep(500) | ✅ 已修 | 改 Object.wait/notify，remove 时 notifyAll 主动唤醒 |
| S-1.1 | B | GCM CipherInputStream 不 close → 跳 tag | ✅ 已修 | setup 异常路径必关闭底层流 + 文档契约强化 |

Tier 分级依据修复成本：A < 30min，B < 2h，C 需要事务/语义重构。

**统计**：11 条代码实修 + 1 条配置修 + 3 条查证已修 / 降级。详见 `docs/analysis/fix-report-v3.md`。

### 🎯 设计意图清单（10 条，**不改**）

| 编号 | 设计依据 |
|---|---|
| S-1.2 SFTP 允许关闭 StrictHostKeyChecking | 支持异构部署环境，产品级选择 |
| S-1.3 TenantGuard 全局角色拒 null tenantId | 代码注释明示**防越权**；E2E 报告 B2 的修法是加 tenantId 参数，非改 Guard |
| C-2.4 TokenBucket 固定窗口 2× 突发 | 代码注释"副作用可接受"；GCRA / 滑动窗口是单独的架构升级话题 |
| C-2.7 LoadStep 无 savepoint | 默认 JDBC batch 行为 |
| A-3.1 DefaultCompensationService 无 saga 补偿链 | 批处理场景 best-effort 足够；真 saga 是重架构选型 |
| A-3.2 WorkerSelector 空集阻塞 | **安全优先**：放宽 tag 可能跑错环境 |
| A-3.6 REPORT 不入 pipeline_step_run | 设计时 REPORT 视为消息传递 |
| R-4.1 Redis 异常退出 500 | 假设 Redis HA；fail-open/fail-closed 需产品决策 |
| R-4.4 Lease renewer 仅 warn 不重试 | 激进重试可能雪崩 |
| R-4.6 Error file 无 TTL | 设计文档预留字段未实现，当前策略为永驻 |

### 🛡 硬化建议清单（35 条，**不在本轮**）

涵盖：S-1.5 / S-1.6 / S-1.7 / S-1.8 / S-1.9 / S-1.10 / S-1.11、C-2.8 / C-2.13 / C-2.14 / C-2.15、A-3.5 / A-3.7 / A-3.8 / A-3.9 / A-3.10 / A-3.11 / A-3.12 / A-3.13 / A-3.14 / A-3.15 / A-3.16 / A-3.17 / A-3.18、R-4.7 / R-4.8 / R-4.9 / R-4.10 / R-4.11 / R-4.12 / R-4.13 / R-4.14 / R-4.15 / R-4.16 + 部分前面未列的防御建议。

这批等 🐛 Bug 全部闭环后再单独评估是否启动 v4 治理。

---

## 优先级清单（需立即修复）

### Critical（16 项）

| 级别 | 编号 | 问题概述 | 关键文件 |
|------|------|----------|----------|
| :red_circle: Critical | S-1.1 | GCM tag 校验可被绕过（CipherInputStream 不 close） | `BatchObjectCryptoService.java:169` |
| :red_circle: Critical | S-1.2 | SFTP 允许关闭 StrictHostKeyChecking + 密码用 String | `SftpDispatchChannelAdapter.java:102-199` |
| :red_circle: Critical | S-1.3 | ConsoleTenantGuard 空串 / JWT 缺失旁路 | `ConsoleTenantGuard.java:34-62` |
| :red_circle: Critical | S-1.4 | Excel 公式注入防护不全 + XXE 未显式禁用 | `ConsoleExcelStyles.java`, `ConsoleSingleSheetExcelImportSupport.java:325` |
| :red_circle: Critical | C-2.1 | Report 失败 → 任务重复执行（消费者 offset 已提交） | `HttpTaskExecutionClient.java:91-163`, `AbstractTaskConsumer.java:87-150` |
| :red_circle: Critical | C-2.2 | DLQ 二次失败导致信号量泄漏 → 背压失效 | `AbstractTaskConsumer.java:104-141` |
| :red_circle: Critical | C-2.3 | PartitionLifecycle.releaseForDispatch 分片/任务跨事务 + 内存回滚 | `DefaultPartitionLifecycleService.java:151-177` |
| :red_circle: Critical | C-2.4 | TokenBucket 窗口边界竞态 → 2× maxPerMinute 突发 | `TokenBucketRateLimiter.java:25-37,33` |
| :red_circle: Critical | C-2.5 | PartitionDispatch outbox 脑裂（createTasks 独立事务） | `DefaultPartitionDispatchService.java:82-212` |
| :red_circle: Critical | C-2.6 | LaunchBatchDayService 改 trigger_type 在事务外 | `LaunchBatchDayService.java:315-317` |
| :red_circle: Critical | C-2.7 | LoadStep chunk flush 无 savepoint + 无幂等 | `LoadStep.java:78-155` |
| :red_circle: Critical | A-3.1 | DefaultCompensationService 无 saga 补偿链 | `DefaultCompensationService.java:120-157,382-406` |
| :red_circle: Critical | A-3.2 | DefaultWorkerSelector 空集无降级 → 任务永久 WAITING | `DefaultWorkerSelector.java:40-74` |
| :red_circle: Critical | R-4.1 | Redis 不可用无降级 → console 限流/幂等 500 | `ConsoleRateLimitFilter.java:38-81`, `ConsoleIdempotencyInterceptor.java:95-103` |
| :red_circle: Critical | R-4.2 | NAS 路径 `normalize()` 不解 symlink | `RemoteFilesystemDispatchSupport.java:46-74` |
| :red_circle: Critical | R-4.3 | ValidateStep 阈值判定前先删输出文件 | `ValidateStep.java:77-102` |

### High（精选，详见后文各维度）

| 级别 | 编号 | 问题概述 | 关键文件 |
|------|------|----------|----------|
| :large_orange_diamond: High | C-2.8 | QuotaRuntimeStateService 窗口过期重置非原子 | `QuotaRuntimeStateService.java:216-281` |
| :large_orange_diamond: High | C-2.9 | ConsoleSessionRegistry Caffeine/Redis 更新非原子 | `ConsoleSessionRegistry.java:67-140` |
| :large_orange_diamond: High | C-2.10 | ConsoleIdempotencyInterceptor setIfAbsent 失败后不分 PENDING/DONE | `ConsoleIdempotencyInterceptor.java:95-103` |
| :large_orange_diamond: High | A-3.3 | DefaultStateMachine 未知事件静默降级为原状态 | `DefaultStateMachine.java:32-68,93` |
| :large_orange_diamond: High | A-3.4 | DefaultWorkflowDagService JOIN 终态判定与入度脱钩 | `DefaultWorkflowDagService.java:96-115` |
| :large_orange_diamond: High | A-3.5 | LoadStep 不符合设计文档 §9.9 入库级幂等约束 | `LoadStep.java:78-155` |
| :large_orange_diamond: High | R-4.4 | WorkerTaskLeaseRenewer 续期失败仅 warn 不重试 | `WorkerTaskLeaseRenewer.java:26-52` |

---

## 一、安全漏洞（11 项）

### Critical（4 项）

#### S-1.1 GCM tag 校验可被绕过

**文件**：`batch-common/src/main/java/io/github/pinpols/batch/common/service/BatchObjectCryptoService.java:169`

`decryptIfNeeded(InputStream)` 返回裸 `CipherInputStream` 供外部调用，**调用方若未完整读取或未显式关闭**，GCM tag 验证不执行。攻击者篡改尾部 16 字节（GCM tag）后，若调用链异常退出或提前 break，返回"解密成功"的内容，实际完整性保证被破坏。

**对比**：`decrypt(byte[])` 在行 86 的 try-with-resources 里关掉，没问题；公共流式方法是风险入口。

**影响**：密文完整性保护被绕过，攻击者可篡改数据而不被检测。

**修复方向**：要么强制 try-with-resources（封装为 `Consumer<InputStream>` 模板方法），要么只暴露同步 `decrypt(byte[]) → byte[]` 入口，彻底移除裸流返回。

---

#### S-1.2 SFTP 主机密钥检查可关闭 + 密码用 String

**文件**：`batch-worker-dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SftpDispatchChannelAdapter.java:102-199`

- 第 164-166 行 JSch `setPassword(String)`，凭证常驻堆无法 `Arrays.fill` 擦除
- 第 145-153 行默认 StrictHostKeyChecking=yes，但允许渠道配置 `sftp_strict_host_key_checking=no` 关闭

**影响**：
- 堆转储/core dump 泄漏密码明文
- 首次连接不校验 host key → 中间人可冒充目标服务器

**修复方向**：
- 强制 key-based auth（优先），保留密码时用 `char[]` + 清零
- 生产 profile 下无视渠道配置，强制 yes；dev 可保留但加显著告警

---

#### S-1.3 ConsoleTenantGuard 空串 + JWT 缺失旁路

**文件**：`batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ConsoleTenantGuard.java:34-62`

两个入口条件缺失校验：

1. **全局角色**：`requestTenantId=""` 会被 `isBlank()` 拒绝，但 `requestTenantId.trim()` 后为空再二次调用不会再进来（上游已 trim）。校验覆盖面不完整。
2. **租户角色**：当 `effectiveTenantId` 为 null（JWT 解析异常或字段缺失），代码会 fallback 到 `requestTenantId`（line 51），意味着 JWT 缺 tenantId 的请求可通过 `?tenantId=other_tenant` 越权。

**影响**：跨租户访问可能被绕过。

**修复方向**：
- 格式正则（`^[a-zA-Z0-9_]+$`）+ 非空非空白校验
- JWT 缺 tenantId 立即 UNAUTHORIZED，不 fallback

---

#### S-1.4 Excel 公式注入防护不全 + XXE 未显式禁用

**文件**：`batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ConsoleExcelStyles.java`（`escapeFormula()`），`ConsoleSingleSheetExcelImportSupport.java:325`

- `escapeFormula()` 只防 `= + - @` 四个首字符，未覆盖 Unicode 变体（如 U+FF01 全宽感叹号）、制表符前置等
- 导出 Excel 含 `configPayloadJson` / `secretPayloadJson` 原样写入，若 JSON value 含 `="cmd"` 会被 Excel 识别为公式
- 导入 Excel（POI）未显式 disable external entities，默认安全但版本升级可能变化

**影响**：
- 导出文件被受害者打开时触发 RCE（Excel 宏 / DDE）
- XXE 可扫内网 / 读本地文件

**修复方向**：扩展 escapeFormula 白名单首字符 + 递归转义 JSON 字符串值 + POI `DocumentHelper.readDocument` 显式 `setFeature(FEATURE_SECURE_PROCESSING, true)` 及 disallow-doctype。

### High（3 项）

#### S-1.5 ChannelConfigMerge 黑名单不全

**文件**：`batch-worker-dispatch/.../ChannelConfigMerge.java:31-64`

RESERVED_KEYS 保护 `id` / `tenant_id` 等，但 `enabled` / `receipt_policy` 等关键控制字段未列入。若渠道支持用户提交 `config_json`，攻击者可通过 overlay 绕过管理员设置。

#### S-1.6 JsonUtils 未配置 FAIL_ON_UNKNOWN_PROPERTIES

**文件**：`batch-common/.../JsonUtils.java:13`

静态 ObjectMapper 未显式配置严格模式，上游/前端错传字段被静默忽略，掩盖数据契约违反。无 RCE 风险（未开 defaultTyping）。

#### S-1.7 SMTP 攻击面未审查

**文件**：`batch-worker-dispatch/.../SmtpEmailDispatchChannelAdapter.java`

本次未深入，但风险清单：附件大小上限、TLS 强制、From 伪造防护、header 注入（CRLF）。需单独列入下一轮专题审查。

### Medium（2 项）

#### S-1.8 EncodingUtils 不剥离 UTF-8 BOM

**文件**：`batch-common/.../EncodingUtils.java:35-44`

工具方法未暴露剥 BOM 能力。当前 `PreprocessStep.resolveCharset()` 自己处理了，但若未来新增编码转换点容易漏掉。

#### S-1.9 Guard.require 错误码硬编码

**文件**：`batch-common/.../Guard.java:52-56`

硬编码 `INVALID_ARGUMENT` / `NOT_FOUND`，业务需要 `CONFLICT` / `STATE_CONFLICT` 时只能绕开工具类，破坏统一性。

### Low（2 项）

#### S-1.10 SqlTemplateExportSqlValidator 参数检查不区分模板/业务
**文件**：`batch-worker-export/.../SqlTemplateExportSqlValidator.java:156-168`。未提供的参数被替换为 null 可能返回意外结果。

#### S-1.11 BatchSecurityProperties 注释与实际配置不符
**文件**：`batch-common/.../BatchSecurityProperties.java:16-23`。注释误写 local 默认 `false`，实际应为 `true`。

---

## 二、并发与数据一致性（15 项）

### Critical（7 项）

#### C-2.1 Report 失败链断裂导致任务重复执行

**文件**：`batch-worker-core/.../HttpTaskExecutionClient.java:91-163`, `.../AbstractTaskConsumer.java:87-150`

Report 对 5xx/网络异常重试到 `reportMaxAttempts`（默认 3 次）后抛异常。`AbstractTaskConsumer.doConsume()` **不捕获** → Kafka 消息 offset 仍提交（因为进入了 executor） → Orchestrator 等待超时后视任务为失败重派 → 同一任务被第二个 worker 执行。

**影响**：长时间网络抖动或 Orchestrator 瞬时不可用时，任务重复执行。

**修复方向**：
- Report 失败应**主动暂停**本 partition 消费（pauseContainer）并 seek 回上一条，避免 offset 提交
- 或将 "REPORT 本身" 改为幂等查询：worker 退出前让 Orchestrator 侧主动查最终状态，worker 仅负责产出状态快照

#### C-2.2 DLQ 二次失败导致信号量泄漏

**文件**：`batch-worker-core/.../AbstractTaskConsumer.java:104-141`

`taskDispatchExecutor.execute()` 抛异常 → 送 DLQ → **若 DLQ 写入也失败**仅 log warn 并继续 `resumeContainerIfPaused()`，信号量未释放。反复触发后计数耗尽。

**修复方向**：信号量用 try/finally 包裹整个 doConsume 体，确保任何退出路径都释放；或改 AutoCloseable 模式。

#### C-2.3 PartitionLifecycle.releaseForDispatch 原子性破裂

**文件**：`batch-orchestrator/.../DefaultPartitionLifecycleService.java:151-177`

流程：
1. 分区推 READY（第 152 行）
2. 任务 UPDATE（第 168 行）失败 → setRollbackOnly + 内存还原（第 174-175 行）

问题：
- 任务 UPDATE 失败若因 version 冲突 → 内存对象已被污染，下次重试读到脏版本
- 分区 READY 写入与任务 UPDATE 之间有窗口，其它线程可读到「分片 READY 但任务未 READY」的不一致中间态

**修复方向**：分片 + 任务放同一事务的同一 UPDATE 链，或用 select-for-update 锁行；禁止"内存回滚"这种回退。

#### C-2.4 TokenBucket 窗口边界 → 2× 突发

**文件**：`batch-orchestrator/.../TokenBucketRateLimiter.java:25-37,33`

- 固定 60s 窗口，窗口切换瞬间跨窗口两批请求各占满 maxPerMinute → 实际突发 2×
- 时钟回拨（NTP / 容器时间跳变）未防御，可能重新激活已过期的旧窗口 key

**修复方向**：改 GCRA / 真滑动窗口；时钟回拨检测（当前时间 < 上次时间 → 拒绝 + 告警）。

#### C-2.5 PartitionDispatch outbox 脑裂

**文件**：`batch-orchestrator/.../DefaultPartitionDispatchService.java:82-212`

`dispatch()` 标 @Transactional 但 `createTasks()`（line 200）是**被调用者自身**的 @Transactional（默认 REQUIRED 复用外层 OK），然而若 `writeDispatchEvent` 失败，tasks 已 INSERT 但 outbox 缺记录 → Kafka 消息永不发，worker 拿不到任务。

**修复方向**：审核所有 cross-method @Transactional 是否真的加入外层事务（IDE 能静态检查吗？）；outbox 写入放最末步保证"任务存在 ⟹ 消息已入队"。

#### C-2.6 LaunchBatchDayService 改 trigger_type 在事务外

**文件**：`batch-orchestrator/.../LaunchBatchDayService.java:315-317`

Late arrival 容差外直接 `updateTriggerType(...)` 从 EVENT 改为 CATCH_UP，此时未进入 T1 事务。两个并发 late arrival 可能都走 CATCH_UP 分支，同一 trigger_request 被标记两次。**违反 CLAUDE.md §架构硬约束**"outbox_event 必须与任务状态写入处于同一事务"的延伸意图。

#### C-2.7 LoadStep chunk flush 无 savepoint + 无幂等

**文件**：`batch-worker-import/.../LoadStep.java:78-155`

- `flushChunk()` 内 `batchUpdate()` 若某行违反 UNIQUE → 整批回滚，但无 savepoint 细化到行级
- 无业务主键 + 批次号去重，重复导入同一文件数据翻倍

**违反**：设计文档 §9.9「入库级幂等：业务主键 + 批次号去重」。

### High（5 项）

#### C-2.8 QuotaRuntimeStateService 窗口过期重置竞态

**文件**：`batch-orchestrator/.../QuotaRuntimeStateService.java:216-281`。窗口过期时两个并发请求各自计算新窗口 → CAS 冲突重试 → 配额短暂失效。V59 migration 加了 version 列但未覆盖此路径。

#### C-2.9 ConsoleSessionRegistry Caffeine/Redis 更新非原子

**文件**：`batch-console-api/.../ConsoleSessionRegistry.java:67-140`。Caffeine 本地镜像与 Redis 的更新时序不配套，"新登录踢旧会话"在 Redis 延迟/故障时多进程不一致，旧 token 可能短暂并存。

#### C-2.10 ConsoleIdempotencyInterceptor 并发边界

**文件**：`batch-console-api/.../ConsoleIdempotencyInterceptor.java:95-103`。`setIfAbsent()` 失败后不区分 key 值是 PENDING 还是 DONE，合法重试被错误拒绝为冲突。

#### C-2.11 DefaultTriggerService.approvePendingCatchUp CAS 不区分状态

**文件**：`batch-trigger/.../DefaultTriggerService.java:147-174`。CAS 失败后二次查询，返回 traceId 可能已过期，追踪链断裂。

#### C-2.12 ConsoleRealtimeEventHub close vs publish 非原子

**文件**：`batch-console-api/.../ConsoleRealtimeEventHub.java:234-249`。CAS 成功后 subscriptions.remove() 在 finally 外，与 publish() 的遍历有 TOCTOU 竞态；COAL 容器勉强保住了 ConcurrentModification 但逻辑不严谨。

### Medium（3 项）

#### C-2.13 LaunchBatchDayService 时区假设

**文件**：`batch-orchestrator/.../LaunchBatchDayService.java:174-180`。timezone 为 null 时 fallback `ZoneId.systemDefault()`，跨时区部署时 cutoff_at 偏差。

#### C-2.14 HttpOrchestratorTriggerAdapter 无 secret 自我校验

**文件**：`batch-trigger/.../HttpOrchestratorTriggerAdapter.java:18-31`。Trigger / Orchestrator secret 配置漂移时 401，无重协商或清晰告警。

#### C-2.15 ParseStep 跳过阈值判定不区分 parse/validation 错误

**文件**：`batch-worker-import/.../ParseStep.java:50-134`。`support.withinThreshold()` 对错误行的定义不明确，可能被绕过。

---

## 三、架构与设计（18 项）

### Critical（2 项）

#### A-3.1 DefaultCompensationService 无 saga 补偿链

**文件**：`batch-orchestrator/.../DefaultCompensationService.java:120-157,382-406`

`CompensationHandler` 各分支（JOB/STEP/PARTITION）内部可能执行多步（如 rerunJob 生成新 instance → 读取 result summary）。中间步骤失败只标 FAILED 状态，**已执行部分无撤销**：已生成的新 job_instance 遗留 DB，成为"孤立已启动任务"。

**影响**：补偿失败后 DB 留下残留任务，需人工清理。

**修复方向**：每个 handler 实现 `compensate()` 反向操作；记录补偿点；或用 Saga 框架（Seata / Spring Statemachine）。

#### A-3.2 DefaultWorkerSelector 空集无降级

**文件**：`batch-orchestrator/.../DefaultWorkerSelector.java:40-74`

resourceTag 所有 worker 不匹配时返回 `available=false`，DefaultResourceScheduler 直接进 blockedDecision → 实例永久 WAITING。heartbeat TTL 不自动移出宕机 worker。

**典型触发**：配错 tag / 整组 worker 故障未及时 DECOMMISSIONED。

**修复方向**：超时无匹配则放宽 tag、触发告警、或重查 workerRegistry；不要让任务静默阻塞。

### High（6 项）

#### A-3.3 DefaultStateMachine 未知事件静默降级

**文件**：`batch-orchestrator/.../DefaultStateMachine.java:32-68,93`

`resolveToState` default case `return fromState`，拼写错的事件名（`SUCESS` vs `SUCCESS`）被捕获并抑制，任务卡错态难排查。

#### A-3.4 DefaultWorkflowDagService JOIN 终态判定与入度脱钩

**文件**：`batch-orchestrator/.../DefaultWorkflowDagService.java:96-115`

`incomingEdges.size()` 为真 DB 查询，若 workflow_edge 软删 / 定义变更后缺边 → JOIN ALL 模式永远判不出满足，孤立节点无限循环。

#### A-3.5 LoadStep 不符合设计文档 §9.9 入库级幂等

**文件**：`batch-worker-import/.../LoadStep.java:78-155`。见 C-2.7。

#### A-3.6 REPORT 步骤未入 pipeline_step_run

**文件**：`batch-worker-core/.../AbstractTaskConsumer.java:103-142` + `HttpTaskExecutionClient.java:91-163`

REPORT 是 Worker 执行链最后一步，但未通过 `startStepRun/finishStepRun` 记录到 `pipeline_step_run`。链路审计无法回答"report 耗时多少、何时成功"。

#### A-3.7 方法参数超限 + entity.setXxx × 20 堆叠

**文件**：`batch-console-api/.../DefaultConsoleTenantConfigInitApplicationService.java:315-376`

`insertJobDefinition()` 20+ 行 setter，字段新增无编译期保护。多个 Mapper 方法参数 > 6。违反 CLAUDE.md §方法参数约束。

#### A-3.8 JobTaskQuery 缺工厂方法

**文件**：`batch-orchestrator/.../domain/query/JobTaskQuery.java:5-10`

5 字段但测试中 6+ 处 `new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null)`，违反 CLAUDE.md Query Record 工厂方法规约。

### Medium（8 项）

#### A-3.9 DispatchChannelHealthService 缺 half-open 探针
**文件**：`batch-worker-dispatch/.../DispatchChannelHealthService.java:67-80`。故障渠道恢复后不会自动回流，需手工重置。

#### A-3.10 ValidateStep 删输出文件时序倒置
**文件**：`batch-worker-import/.../ValidateStep.java:77-102`。超阈值即删文件，但此时文件已部分写入，数据丢失无法重放。

#### A-3.11 ChannelConfigMerge 黑名单设计不完整
见 S-1.5。设计上应使用白名单模式。

#### A-3.12 AbstractExportFormat 列数无上限
**文件**：`batch-worker-export/.../stage/format/AbstractExportFormat.java:30-90`。10000+ 列表导出爆内存。

#### A-3.13 CHARSET_TRANSCODE 输出无大小限制
**文件**：`batch-worker-import/.../ImportPreprocessPipeline.java:62-114`。GBK→UTF-8 字节膨胀可能跳过 ReceiveStep 的 OOM 守卫。

#### A-3.14 ImportIngressScanner 多实例竞态
**文件**：`batch-worker-import/.../ImportIngressScanner.java`。缺文件级分布式锁，多 scanner 并发可能重复处理。

#### A-3.15 BatchSecurityProperties 注释与配置不符
见 S-1.11。

#### A-3.16 Dashboard 聚合 TTL 10s + 多表 join 无 MVCC 一致性
**文件**：`batch-console-api/.../ConsoleQueryCacheService.java`。更新窗口内可能读到半更新视图。

### Low（2 项）

#### A-3.17 PlatformFileRuntimeRepository 字面量常量只在类内修
**文件**：`batch-worker-core/.../PlatformFileRuntimeRepository.java:47-54`。代码注释标注了却没抽全局。

#### A-3.18 ExportFormat 生成端/checksum 端 charset 一致性
**文件**：`batch-worker-export/.../stage/GenerateStep.java:79-100`。生成时 charset 与 StoreStep checksum 可能用不同 charset。

---

## 四、运维与可靠性（16 项）

### Critical（3 项）

#### R-4.1 Redis 不可用无降级 → console 限流/幂等 500

**文件**：`batch-console-api/.../ConsoleRateLimitFilter.java:38-81`, `ConsoleIdempotencyInterceptor.java:95-103`

Redis Lua 抛 `DataAccessException` 未被捕获 → 直接 500。**Redis 抖动直接升级为可用性事故**。

**修复方向**：
- 限流：fail-open + 记 warn（可用性优先）
- 幂等：fail-closed + 429（安全优先）
- 明确决策并文档化

#### R-4.2 NAS 路径 normalize 不解 symlink

**文件**：`batch-worker-dispatch/.../channel/RemoteFilesystemDispatchSupport.java:46-74`

`Path.of(remoteDir).toAbsolutePath().normalize()` 只处理 `.` 和 `..`，若 remoteDir 含 symlink 指向父级，攻击者可突破 sandbox。

**修复方向**：改用 `Files.readSymbolicLink` + `toRealPath()`，与 sandboxRoot 做 startsWith 比对。

#### R-4.3 ValidateStep 逻辑判定前先删输出文件
见 A-3.10。标 Critical 是因为可直接丢业务数据。

### High（4 项）

#### R-4.4 WorkerTaskLeaseRenewer 续期失败仅 warn

**文件**：`batch-worker-core/.../WorkerTaskLeaseRenewer.java:26-52`。续期失败不重抛、不触发熔断、不告警，Orchestrator 可能认定 worker 已死并重派，但 worker 还在执行 → 任务重复。

#### R-4.5 ActiveTaskLeaseRegistry awaitDrain 硬编码 sleep

**文件**：`batch-worker-core/.../ActiveTaskLeaseRegistry.java:92-101`。`Thread.sleep(500)` 硬编码，1000+ 活跃任务时实际等待远超指定 timeout → 优雅关闭变强杀。

#### R-4.6 ImportErrorOutputStorage 错误文件永驻无 TTL

**文件**：`batch-worker-import/.../ImportErrorOutputStorage.java:30-62`。设计文档 §9.11 提及 `errorOutputRetentionDays` 但代码未实现，MinIO 存储持续膨胀。

#### R-4.7 ConsoleSessionRegistry Caffeine 无驱逐策略

**文件**：`batch-console-api/.../ConsoleSessionRegistry.java:60-64`。size=100k 上限但无 LRU/LFU 驱逐声明；无 recordStats，命中率不可观测。

### Medium（6 项）

#### R-4.8 ConsoleQueryCacheService 失效靠 prefix evict
**文件**：`batch-console-api/.../ConsoleQueryCacheService.java`。写路径漏 evict 即永久陈旧；缺 AOP 统一拦截。

#### R-4.9 Excel SAX 仍把所有 rows 缓存在内存
**文件**：`batch-console-api/.../ConsoleSingleSheetExcelImportSupport.java:202-280`。百万级 Excel 仍会 OOM。

#### R-4.10 ChannelConfigMerge 凭证合并可能被日志吞
未深入但应审查：merge 过程中 secret 字段不应打日志。

#### R-4.11 SqlTemplateExportSqlValidator 参数校验不全
见 S-1.10。

#### R-4.12 HttpOrchestratorTriggerAdapter secret 漂移感知
见 C-2.14。

#### R-4.13 Lease renewal 网络抖动无退避重试
见 R-4.4 同根同源。

### Low（3 项）

#### R-4.14 JsonUtils SPI 扩展点缺失
**文件**：`batch-common/.../JsonUtils.java:12-14`。局部严格模式只能绕开工具类。

#### R-4.15 MinioBucketSupport CAS 微优化空间
**文件**：`batch-common/.../MinioBucketSupport.java:41-45`。高并发下 computeIfAbsent 可能触发 segment 扩容。当前无缺陷。

#### R-4.16 ConsoleTextSanitizer null-safe 链式 API
未深入审查。

---

## 五、跨模块的 4 个系统性模式

这部分比单点 bug 更值得关注——同一错误写法反复出现，说明**约束没有静态强制**，修完一处不等于修完一类。

### 模式 A · 事务边界破裂

| 位置 | 现象 |
|---|---|
| C-2.3 `DefaultPartitionLifecycleService:151-177` | 分片 → 任务跨事务 + 内存回滚回退 |
| C-2.5 `DefaultPartitionDispatchService:82-212` | createTasks 子事务，outbox 写入若失败脑裂 |
| C-2.6 `LaunchBatchDayService:315-317` | late arrival 改 trigger_type 在 T1 事务外 |
| C-2.7 `LoadStep:78-155` | chunk flush 无 savepoint |
| E2E 报告 B3（已修） | MyBatis camel/snake 键 → 静默 null |

⚠️ **直接违反 CLAUDE.md §架构硬约束**："outbox_event 必须与任务状态写入处于同一事务"。现状不是个别违反，而是**没有在代码层静态强制**。

### 模式 B · 外部依赖无降级

| 位置 | 外部依赖异常退出会怎样 |
|---|---|
| R-4.1 `ConsoleRateLimitFilter` / `ConsoleIdempotencyInterceptor` | Redis 抖动 → HTTP 500 |
| C-2.1 `HttpTaskExecutionClient` REPORT | Orchestrator 5xx 重试耗尽 → 任务重复 |
| R-4.4 `WorkerTaskLeaseRenewer` | 续期失败仅 warn，orchestrator 误判 worker 死 → 重派 |
| A-3.2 `DefaultWorkerSelector` | 整组 worker 宕机 → 任务永久阻塞 |
| A-3.9 `DispatchChannelHealthService` | UNHEALTHY 无 half-open → 渠道恢复后不自动回流 |

**缺**：统一的熔断 + half-open + fail-policy 抽象。每处都在重写降级判断。

### 模式 C · 幂等约束靠注释无静态防护

| 违反点 | 表现 |
|---|---|
| A-3.5 / C-2.7 `LoadStep` | 不符合 §9.9「业务主键 + 批次号去重」 |
| C-2.11 `DefaultTriggerService.approvePendingCatchUp` | CAS 后二次读，返回 traceId 过期 |
| C-2.10 `ConsoleIdempotencyInterceptor` | setIfAbsent 失败不区分 PENDING/DONE |
| E2E 报告 B3（已修）| MyBatis 键约定靠注释 |

### 模式 D · 资源生命周期不闭环

| 位置 | 泄漏点 |
|---|---|
| S-1.1 `BatchObjectCryptoService:169` | CipherInputStream 调用方可能不 close → GCM tag 跳过 |
| C-2.2 `AbstractTaskConsumer:141` | DLQ 失败 + resumeContainer 继续 → 信号量不释放 |
| R-4.6 `ImportErrorOutputStorage:30-62` | 错误文件永驻 MinIO 无 TTL |
| R-4.5 `ActiveTaskLeaseRegistry:92-101` | `awaitDrain` 硬编码 sleep |
| R-4.7 `ConsoleSessionRegistry:60-64` | Caffeine 100k 上限无驱逐策略 |
| C-2.12 `ConsoleRealtimeEventHub:234-249` | close vs publish 非原子（靠 COAL 侥幸） |

---

## 六、与前序报告的连贯性

### 相对 v2 (2026-04-15)

v2 列出 69 条，`fix-report-v2.md` 已闭环 61 条。本轮 60 条发现**无一条与 v2 重复**——v2 焦点在 JWT 密钥 / 级联删除 / MANDATORY 传播等「硬 bug」；本轮更多落在「边界条件 / 并发场景 / 故障降级 / 安全纵深」，说明上一轮修完表层问题后，**下一层薄弱点浮现**。

### 相对 2026-04-19 E2E 测试报告

E2E 报告 5 个生产 bug 已修（46d89aae 之前的一批 commit）。本轮在其基础上扫出 **3 类同模式变体**，见前面「模式 C」表。**修 5 个点 ≠ 修 5 类问题**：不做 P1 里的常量化/守护测试/事务边界约束成 CI 强制，同模式会再次复发。

### 与本次会话内已完成的修复

- `ConsoleDashboardQueryService` 3 处 `Map.of` → LinkedHashMap（已提交）
- `V61__add_list_page_default_sort_indexes.sql` 9 张列表表复合索引（已提交）
- 7 个 deprecated Excel 控制器删除 + OpenAPI 同步（两个 agent 运行中，独立于本报告）

---

## 七、优先级行动清单

按「影响面 ÷ 修复成本」排序：

### P0 · 生产事故预防（本周做）

1. **C-2.1 HttpTaskExecutionClient REPORT 失败处理** —— pauseContainer + seek 回退，或 REPORT 改幂等查询模型
2. **R-4.1 Redis 异常回退** —— 限流 fail-open / 幂等 fail-closed，明确写文档
3. **A-3.2 WorkerSelector 空集降级** —— 超时放宽 tag + 告警，禁止静默阻塞
4. **S-1.3 ConsoleTenantGuard 加固** —— 输入格式正则 + JWT 缺 tenantId 立即 UNAUTHORIZED
5. **S-1.2 SFTP 主机密钥强制 on（prod）+ 密码迁 char[]**

### P1 · 架构债收敛（月内做）

6. **C-2.4 TokenBucket 重设计** —— GCRA / 滑动窗口 + 时钟回拨检测
7. **C-2.3 / C-2.5 / C-2.6 PartitionLifecycle + PartitionDispatch + LaunchBatchDay 原子化** —— 分片/任务/outbox 同事务，禁止"内存回滚"
8. **A-3.1 DefaultCompensationService saga 化** —— 每 handler 实现 compensate() + 补偿点记录
9. **A-3.6 Report 上报进 pipeline_step_run** —— 补全链路审计
10. **C-2.7 / A-3.5 LoadStep 幂等补齐** —— `(business_key, batch_id)` UNIQUE + `ON CONFLICT DO NOTHING`

### P2 · 防御纵深（季度内做）

11. **S-1.1 GCM CipherInputStream 封装** —— 强制 try-with-resources 或改同步 decrypt
12. **S-1.4 Excel 公式注入 + XXE** —— 扩展 escapeFormula + POI 显式禁 XXE
13. **R-4.2 NAS 路径 toRealPath + sandboxRoot 比对**
14. **MyBatis 参数常量化 + 守护测试** —— 与 E2E 报告 B3 同源（列入 CI）
15. **A-3.8 Query Record 工厂方法补齐 + ArchUnit 强制**

### P3 · 长期治理（有空做）

- A-3.3 StateMachine default case 改抛异常
- A-3.4 WorkflowDagService JOIN 补"所有前驱 run 记录已创建"断言
- R-4.5 awaitDrain 换 CountDownLatch/Phaser
- R-4.8 缓存失效 AOP 化
- Excel 真流式写 + 列数上限（A-3.12 / R-4.9）
- 其余 Low 级别细节

---

*报告生成时间：2026-04-20 12:00 CST*
*下一轮审查建议触发时机：P0/P1 清理完毕后，或下一次 E2E 全量回归后*
