# 后端 Sim 可靠性场景组

> 本文是后端 Sim 可靠性场景组的覆盖事实源和治理入口。它把标准业务 Sim、自动化测试和隔离环境 DR 演练放在同一矩阵中核对，但不把某一层的证据替代另一层。

## 目标与边界

可靠性场景组验证文件、数据库、消息和 Worker 在并发、故障及恢复窗口中的可观察结果：不消费半成品，不丢已确认数据，不重复业务副作用；依赖故障可被识别并限制扩散；故障解除后任务、Outbox 和积压可收敛。

场景组是**覆盖分组**，不是另一个单体模拟器，也不把所有故障注入塞进标准 nightly Sim：

| 验证层 | 承担的证明 | 入口 |
|---|---|---|
| 单测 / 组件测试 | 解析、校验、状态分类、失败关闭等局部规则 | 对应模块 `src/test` |
| PostgreSQL / Kafka 集成测试 | 事务、唯一约束、CAS、并发、重试和依赖故障行为 | `batch-*/src/test` 中 `*IntegrationTest` |
| 标准业务 Sim | 当前 Compose 拓扑中的真实业务链和有限、可清理的故障注入 | `bash scripts/local/sim-harness.sh all`；阶段清单见 [`../../scripts/sim/README.md`](../../scripts/sim/README.md) |
| 隔离环境 DR 演练 | Worker 整队崩溃、PITR、HA failover 及真实恢复目标 | [`../runbook/go-live-staging-execution.md`](../runbook/go-live-staging-execution.md) |

PostgreSQL `READ COMMITTED` 下不存在脏读；对“数据库幻读”的验收关注同一批到达文件被并发扫描/触发时是否漏触发或重复触发，而不是要求用不恰当的脏读术语描述它。存储目录 list 变化与数据库查询集合变化也应分别注入、分别断言。

## 场景覆盖矩阵

状态含义：**已有**表示找到直接相关自动化证据；**部分**表示只覆盖组件、单模块或某个故障窗口；**缺口**表示尚未找到可重复且端到端的验收场景。矩阵按仓库资产和本轮运行记录更新；单测/IT 通过不替代未运行的 Sim 或 staging DR 证据。

| ID | 风险场景 | 当前证据 | 可靠性组验收断言 | 推荐验证层 / 优先级 |
|---|---|---|---|---|
| R-01 | 入站文件半写、缺 manifest、manifest 截断、size/checksum 错误 | **实现已有；本轮真实对象存储 IT 通过**：scanner 校验 schema、文件名、长度、checksum 后才创建 VERIFIED 记录；真实 S3 兼容后端与 PG 测试覆盖缺失/截断/长度错/摘要错后修复重扫 | 不创建 VERIFIED 文件记录、不触发 Arrival Group/Import；保留可诊断失败原因；修复文件和 manifest 后可重扫 | Import scanner 集成 + PG 集成，P0 |
| R-02 | 登记后对象丢失、替换或读失败 | **本地 MinIO + PreprocessStep 集成通过**：对象临时丢失时失败关闭、对象恢复后同一载荷重试成功；对象内容被替换时 checksum 复核失败且无 normalized payload；仍未覆盖业务库完整导入行数端到端对账 | 处理前复核失败关闭，不误报成功；重新上传/从版本恢复后可受控重试，业务行数与摘要匹配 | Import pipeline 集成 + 存储环境 Sim，P0 |
| R-03 | 出站半写、数据先发布而 manifest 未发布、远端已写但 DB 未提交 | **本地组件测试通过**：NAS manifest 与原子 move 失败关闭由 `RemoteFilesystemNasPathTest` 覆盖；完整发布/恢复顺序见[文件恢复设计](../design/file-integrity-recovery.md)；尚无真实进程中断窗口的标准 Sim 证据 | 正式路径不暴露部分内容；下游不消费无 manifest 对象；重试以稳定目标键收敛，不重复外部副作用 | Dispatch 集成 + S3/NAS/SFTP 目标环境演练，P0 |
| R-04 | 到达组并发扫描/重复触发（集合变化和 DB phantom 风险） | **真实 PG 并发 launch IT 已新增并通过**：两个调用并发经过生产 `BundleArrivalLauncher` 和 `LaunchService`，检查唯一 trigger request、job instance、完整分区及每任务一条 dispatch Outbox；调度器 PG IT 覆盖 checksum 列存在但未 VERIFIED 时保持等待，以及 VERIFIED 后触发 | 同一租户、业务日和文件组并发到达时，必需文件齐全后恰好一个有效 launch；缺文件/未 VERIFIED 时不 launch；无遗漏、重复实例或重复 Outbox | PG 并发 IT；标准 Sim，P0 |
| R-05 | Import/Process 在 checkpoint 或业务写入窗口发生硬崩溃 | **本地集成测试通过**：`LoadStepCheckpointCrashResumeIntegrationTest`、`ProcessStageSkipCrashResumeIntegrationTest` 验证阶段续跑；标准 Sim Stage 25 另测 Import checkpoint 故障注入，本轮未运行 | 恢复后最终状态成功；业务记录不重不漏；同一实例/分区恢复；临时尾部不会被当成正式输出 | 标准 Sim + 模块集成测试，P0 |
| R-06 | 全 Worker 组硬崩溃、租约回收与消息重投 | **本地 PG/Kafka 集成测试通过，整队脚本未运行**：`ExactlyOnceCrashRecoveryIntegrationTest` 验证报告 CAS、陈旧写和事件去重；`dr-drill-fleet-crash.sh` 仍需在隔离应用栈执行 | 所有在飞工作回收并到终态；无双领/陈旧终态覆盖/业务副作用重复；恢复容器并留存结果证据 | 隔离环境 DR；自动化 IT，P0 |
| R-07 | Kafka 暂时不可用、恢复后积压收敛 | **本地 Kafka 故障集成测试通过**：`OutboxPublishCircuitBreakerKafkaFailureIntegrationTest` 停止并恢复 Testcontainers Kafka 后验证重试；go-live staging 清单仍要求额外做短时目标集群演练 | 故障被识别，Outbox 不丢；熔断/退避限制重试扩散；恢复后 backlog 与 lag 收敛，无重复消费 | Kafka IT + staging 故障演练，P1 |
| R-08 | PostgreSQL 连接故障 / failover / 锁等待 | **缺口（目标拓扑证据）**：本地 Testcontainers/组件测试不能证明真实 HA failover；staging 清单明确列为额外演练 | 写入失败边界明确；不将不确定提交盲目重做成重复副作用；切主后连接恢复，任务/outbox 收敛，记录 RTO | staging HA 演练，P1 |
| R-09 | 对象存储 / SFTP 超时、不可用、恢复瞬间抖动 | **本地 NAS 组件测试通过，端到端故障仍部分**：`RemoteFilesystemNasPathTest` 验证落盘、manifest 与失败关闭；未找到标准 Sim 的 S3/SFTP 短暂中断→恢复闭环 | 健康/错误分类可见；有界重试与反压有效；不产半文件；依赖恢复后重试成功并对账一致 | Worker 故障注入 IT + S3/NAS/SFTP 目标环境演练，P1 |
| R-10 | 故障扩散：快速重试、消费风暴、容量耗尽、恢复回涌 | **本地机制测试通过**：`AbstractTaskConsumerBackpressureTest` 与 Kafka 熔断 IT 覆盖暂停消费/熔断恢复；Gatling 覆盖容量；尚未统一测量故障和恢复阶段的 backlog/lag/锁等待收敛预算 | 退避、熔断、暂停 poll / admission 生效；队列和连接池有界；恢复时 lag、Outbox、锁等待在预算内下降 | IT + staging 受控负载，P1 |
| R-11 | Outbox / DLQ / 人工重放闭环 | **已有（E2E 主路径）**：`OutboxForwarderE2eIT`、`DeadLetterApprovalReplayE2eIT`；`ImportFailurePipelineE2eIT` 覆盖失败回滚及重试 | 记录重放来源与幂等键；重放不重复业务写；成功后 backlog/DLQ 状态收敛；失败保留可再次处理证据 | E2E + 真实依赖故障补充，P1 |
| R-12 | PITR 与数据库级数据丢失恢复 | **演练脚本已实现，目标环境结果未核实**：`dr-drill-pitr.sh` 对比 T0 前实例指纹并测 RTO；真实性取决于配置的备份工具、备份链和隔离目标库；本轮未执行 | T0 前已提交数据完整、T0 后数据按恢复点消失、无重复/悬挂；实测 RPO/RTO 达到目标；校验 business/platform 两库及应用恢复 | 仅隔离 staging/DR，禁止纳入普通 `sim-harness all`，P0 上线门槛 |

## 分组执行策略

### 每次标准 Sim

当前 `sim-harness` 自动发现 `scripts/sim/[0-3][0-9]-*.sh`，会运行 04–30。Stage 25 是现有硬崩溃恢复场景。后续标准 Sim 扩展只允许使用专属租户/对象/业务键，具备可重复基线和失败清理；故障注入不得停用或改写用户已有服务，不得做数据库重建、PITR 或 HA 切换。

### PR / Full CI 自动化

并发、事务和故障窗口先在模块级真实依赖集成测试中稳定复现，再由标准 Sim 证明跨服务行为。不要用 sleep + 串行状态查询伪造并发证据；并发场景需由独立连接/线程同步屏障制造真实竞争，并检查数据库终态、业务副作用和 Outbox。

### Staging DR

整队 Worker crash、PITR、PG failover 和真实存储故障必须在隔离 staging/DR 执行。分别留存环境版本、目标点、故障时间、恢复动作、RPO/RTO、数据库指纹、重复/悬挂任务、业务文件 checksum、积压收敛和未覆盖项。Testcontainers、本地 Compose 和脚本退出码不能作为生产 HA/备份链路通过的证据。

## 建议补齐顺序

1. **文件完整性端到端（R-01、R-02）**：用隔离前缀/测试租户注入缺失、截断、摘要不符和登记后对象替换；断言无 VERIFIED、无 launch、修复后可重扫恢复。
2. **到达组真实并发（R-04）**：PG 屏障同步两个扫描/launch 并发请求，核对唯一实例、Outbox 和完整文件集合；另测扫描期间对象集合增长，区分存储 list 非快照与 DB 事务集合。
3. **Dispatch 发布窗口（R-03、R-09）**：在数据上传完成、manifest 发布和状态提交之间分别中断；验证无半成品可见及重放幂等。按生产实际 NAS/SFTP/S3 后端跑目标环境矩阵。
4. **依赖故障和防扩散（R-07 至 R-10）**：逐一短暂阻断 Kafka、PG、MinIO/SFTP；记录告警/健康信号、重试速率、Outbox、lag、锁等待与恢复时间，不混成单个“所有依赖都 down”场景。
5. **保持 DR 独立（R-06、R-08、R-12）**：强化并持续执行 staging 演练；PITR 与 HA 切换只对明确隔离目标执行。

## 本地自动化命令

```bash
# 本地和每日 CI 共用同一个隔离故障回归入口；使用 Testcontainers，不连接本地共享 Sim 服务。
bash scripts/local/backend-reliability-tests.sh

# staging 上的整队 Worker 崩溃与 PITR 命令见 go-live staging 执行清单；禁止在开发/生产共享栈运行。
```

## 通过证据要求

每个场景需记录场景 ID、代码 revision、执行入口、依赖版本、故障注入点、期望值、实际数据库/文件读回、恢复动作、最终业务对账和清理结果。仅有 HTTP 200、容器重启成功、脚本存在或无异常日志，不构成场景通过。未运行、SKIP、被中断以及仅有单测的场景应分别报告。

当前核查基线：2026-10-13，基于 `origin/main` 的分支 `chore/backend-sim-reliability`。本地可靠性回归入口 `scripts/local/backend-reliability-tests.sh` 涉及 13 个测试类；完整执行结果为 72 用例通过，失败、错误、跳过均为 0。每日 workflow 与该入口共用同一测试集合。未运行标准 Compose Sim、整队容器崩溃脚本、E2E 全集、DR 或 staging 演练；新 workflow 尚未推送，未有 GitHub Actions 运行记录。
