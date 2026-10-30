# 后端代码 Bug 专项审查（2026-10-26）

> 最新续审：确认项已推进至 BUG-20261026-21，分批修复和验证见文末及整改记录。下文初轮结论保留当时证据，不代表当前仍未修复；全工程逐文件范围仍在进行，以 TODO Master 和内容摘要清单为准。

> 本文保留审查时旧实现证据。9 项问题已在后续本地整改中修复, 当前结果与验证边界见[运行复现与整改记录](../verifications/backend-bug-remediation-2026-10-26.md)。

## 结论与基线

本轮确认 9 项代码缺陷：4 项 P1、5 项 P2，均待修复。8 项由当前源码、调用方和持久化条件确认，运行未复现；另 1 项完成 Helm 双 Release 静态渲染与选择器匹配复现，未部署到 Kubernetes。未修改业务代码、配置、SQL、测试或脚本，未提交、未编译、未运行应用测试、未访问真实数据库或发送通知。

- 工作树：配对开发目录中的后端 `wdi-current/file-batch-system`，分支 `fix/wdi-current`。
- HEAD：`f3d5e400d3b5bd737124b57cf0a38e685432aab2`；比较基线：`ee01e97515b04fd167ed17977535ec5cab8572f8`，也是冻结的 `main` 与 `feature/workflow-daily-integration` 当前 HEAD。
- 审查包含相对基线的已提交变更，以及当前暂存、未暂存和未跟踪内容。开始时相对 HEAD 有 2,177 个变更条目：暂存 1,440、未暂存 903、未跟踪 78；前两项存在交集。相对基线有 4,386 个 tracked 差异路径，另含未跟踪文件。
- 当前可版本化 Java 源码共 4,388 个：生产 2,954、测试 1,434。清单盘点不等于逐行审查。
- 主入口：[code-review-and-gates](../../.agents/skills/code-review-and-gates/SKILL.md) 的 Bug 专项及[统一确认标准](../../.agents/skills/code-review-and-gates/references/bug-mechanisms.md)。专项使用 Worker/SDK、调度、数据库、契约、配置与观测技能；报告按文档治理规则留存。
- 历史规约门禁、52 个维护相关用例及规则验证仅作为已有覆盖线索，不计作本轮通过。问题均为代码行为缺陷，未将风格或可读性偏好计入。

配对前端的同轮正确性修复与验证见[前端 Bug 记录](../../../batch-console/docs/reports/2026-10-26-frontend-bug-remediation.md)，两仓审查范围和验证层级分别保留。

## 范围与覆盖边界

| 分区 | 清单分母 | 本轮深入追踪 | 未覆盖边界 |
|---|---|---|---|
| Trigger / Orchestrator / 迁移 | 相对 HEAD 的变更条目分别为 60 / 523 / 12 | readiness 接管与恢复、持久重投、Workflow SLA 与归档、未知外部结果重放 CAS | 未逐文件深读 595 个条目；未进行真实事务/锁竞争或迁移验证 |
| Worker / Common / SDK | 相对基线 tracked 差异分别为 410 / 185 / 147，另有 Worker 1、SDK 4 个未跟踪路径 | 约 25 个生产文件及关键片段，覆盖 claim/report 微批、续租、Outbox、执行围栏、五语言入口与消费适配器；抽查 Import/Dispatch/Process | 未逐行审完 742 个 tracked 路径；五类 Worker 和五语言没有全部运行验证 |
| Console API | 生产 1,162 Java / 90 XML；相对基线加未跟踪的生产变更 728 个 | 通知接入/渠道/回执、终态通知、Push、配置包导入与版本、配置同步、权限与租户、JobExecution 查询 | 未逐行审完；未证明全量授权、租户边界和查询均无问题 |
| 维护与观测部署 | 当前维护实现、测试及 Compose/Helm/Prometheus/Alertmanager 相关文件 | 状态发布与提交点、HTTP 维护标记、有限通知窗口、抑制路由、部署范围标签 | 完成双 Release 渲染匹配；未做真实集群采集、最终通知或多副本交错实验 |

本轮完成变更清单盘点及上述高风险路径审查，**不代表全工程所有代码已逐行审完或不存在其他 Bug**。独立复核后排除了不成立的 deadline 行为候选；所有最终问题都提供具体触发路径。

## 问题清单

| ID | 优先级 | 问题 | 基线归属 | 责任边界 |
|---|---|---|---|---|
| BUG-20261026-01 | P1 | 续租熔断在活跃租约清空后无法关闭，Outbox 停止自恢复 | 基线已有，当前仍存在 | Worker Core |
| BUG-20261026-02 | P1 | Go SDK 背压记录被后续 offset 提交跨过 | 基线已有，当前仍存在 | Go SDK / Kafka |
| BUG-20261026-03 | P1 | Python SDK 在 CLAIM 确认前提交 offset | 基线已有，当前仍存在 | Python SDK / Kafka |
| BUG-20261026-04 | P1 | 正常 Workflow 归档后产生开始/完成时限误告警 | 相对基线新增链路 | Orchestrator 监控 / 归档 |
| BUG-20261026-05 | P2 | ServiceMonitor 跨 Release 选中服务，维护范围隔离失效 | 原选择器缺口影响新增范围标签 | Helm / 观测 |
| BUG-20261026-06 | P2 | readiness 恢复 HTTP 无有限响应超时 | 相对基线新增链路 | Orchestrator → Trigger |
| BUG-20261026-07 | P2 | readiness 固定取最早 100 条，后续已就绪记录饥饿 | 相对基线新增链路 | Orchestrator readiness |
| BUG-20261026-08 | P2 | 停用通知渠道后 Alertmanager 仍发送通知 | 基线已有，当前仍存在 | Console 通知 |
| BUG-20261026-09 | P2 | 配置包更新已有 Workflow 时静默丢失 description | 相对基线回归 | Console 配置导入 |

### BUG-20261026-01：无活跃租约时熔断阻断 Outbox

主位置：[WorkerTaskLeaseRenewer.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerTaskLeaseRenewer.java) 第 115–116 行。关联 [WorkerReportOutboxCoordinator.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxCoordinator.java) 第 62–66 行与 [DefaultTaskExecutionWrapper.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java) 第 300–305 行。

触发顺序：Orchestrator 暂时不可达使续租熔断打开；业务随后完成但回报失败并进入 Outbox；wrapper 清除最后一条租约；Orchestrator 恢复且没有新租约。半开 tick 每次遇到空 active 集合便返回，唯一关闭熔断的第 173 行位于真实 renew 返回后，无法执行。Outbox 的 `pausePollWhenRenewCircuitOpen` 默认开启，所有 poll 持续返回。

已核对 register/heartbeat 没有其他 reset，fastRetry 在 OPEN 时返回；恢复 PUBLISHING 行状态不解除进程熔断。新任务或重启可能解除，但没有后续任务时不能自行补报，可能让实际已完成任务进入超时或人工核对。

修复方向：允许 Outbox 有界独立探测，或为空租约定义有限恢复路径，避免恢复条件依赖已不存在的租约。验收：熔断打开→最后租约删除→服务恢复，在没有新任务或重启时 Outbox 成功提交并更新持久化状态。

证据层级：源码确认；本轮未运行故障恢复测试。

### BUG-20261026-02：Go 背压记录被后续提交跨过

主位置：[lifecycle.go](../../sdk/go/client/lifecycle.go) 第 234–236 行；关联 [kafka_consumer.go](../../sdk/go/kafka/kafka_consumer.go) 第 393、445–456 行。

并发容量已满时，Poll 已读取记录 N 并加入 `pending`，但 pipeline 返回背压后仅调用 Pause，没有保留 N 待重新派发，也没有登记提交上限。恢复后下一次 Poll 直接取 N+1，成功 CLAIM 后 Commit 将 N 与 N+1 一并提交。Pause/Resume 只是取数开关，不能让已经取出的 N 自动重读。

N 从未 CLAIM/执行；READY 且无租约的任务不进入已认领任务租约回收，因此不能以“以后租约超时会补发”作为恢复保证。已有 withheld 保护只覆盖已登记的上限，背压分支未登记。

修复方向：恢复时优先重新处理已拉取的背压记录，且后续提交不能越过它。验收必须覆盖真实适配器的 pending/提交逻辑：N 背压、N+1 到达，N 未确认前提交上限不得越过 N，恢复后 N 执行一次。

证据层级：源码确认；本轮未运行 Go 测试或真实 Kafka。

### BUG-20261026-03：Python 在 CLAIM 前确认消费

主位置：[dispatcher.py](../../sdk/python/src/batch_worker_sdk/dispatcher/dispatcher.py) 第 409–418 行；关联同文件第 449–461 行与 [_kafka.py](../../sdk/python/src/batch_worker_sdk/internal/_kafka.py) 第 293–310 行。

`on_message` 仅创建异步 `_process` 就返回 ACCEPTED，poll 循环据此提交 offset；真正 CLAIM 在后台执行。若网络请求始终未到 Orchestrator，重试失败抛出 PlatformError 后直接返回，清理 in-flight。此时没有持久化 claim/lease，但消息已确认，重启也不会重读。

修复方向：可提交处置必须等 CLAIM 得到持久化归属结果；业务执行仍可异步。失败或未知的 CLAIM 不能提前确认消息。验收：全部 CLAIM 请求失败且未落库时 offset 不提交；连接恢复后原消息能够处理且不重复业务副作用。

证据层级：源码确认；本轮未运行 Python 测试或真实 Kafka/HTTP 故障验证。

### BUG-20261026-04：归档使历史成功 Workflow 变成违约候选

主位置：[WorkflowMonitoringMapper.xml](../../batch-orchestrator/src/main/resources/mapper/WorkflowMonitoringMapper.xml) 第 77–100 行。

查询仅关联热表 `batch.workflow_run`，没有业务日/保留期过滤。[WorkflowArchiveService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/archive/WorkflowArchiveService.java) 第 48–54 行正常复制冷表后删除热运行；来源 occurrence/request 仍保留。原来按时开始和完成且从未告警的运行，在热表删除后开始/结束时间变 NULL，SQL 用 CURRENT_TIMESTAMP 兜底，生成 NOT_STARTED / NOT_COMPLETED_BY_DEADLINE。

既往没有告警，NOT EXISTS 去重不拦截；[AlertEventMapper.xml](../../batch-orchestrator/src/main/resources/mapper/AlertEventMapper.xml) 的恢复查询虽读取冷表，却要求已有 firing 送达事实，不能阻止先误通知再解除。

修复方向：按稳定请求身份关联冷热根运行并保持热记录优先，不将已归档成功事实解释成“尚未执行”。验收：真实 PostgreSQL 中，按时完成→归档→再扫描，不能新增开始/完成违约；真实历史超时反例仍按既定策略处理。

证据层级：源码及 SQL 因果确认；本轮未执行 PostgreSQL 集成验证。

### BUG-20261026-05：ServiceMonitor 没有 Release 选择条件

主位置：[servicemonitor.yaml](../../helm/batch-platform/templates/servicemonitor.yaml) 第 25–38 行；关联 [prometheusrule.yaml](../../helm/batch-platform/templates/prometheusrule.yaml) 第 29–35 行。

同命名空间中使用相同 chart/name 的两个 Release，ServiceMonitor 的 `spec.selector.matchLabels` 只有 name/component，缺少 `app.kubernetes.io/instance`。monitor 自身 metadata 有 instance 并不约束被选 Service。新增 relabel 又将全部选中的 target 强制标成该 monitor 的维护范围。

已使用唯一 fullname 分别渲染 `audit-blue`、`audit-green`，确认 blue-console-api 的 selector 同时匹配 blue-console-api 和 green-console-api，并均标为 `audit.audit-blue`；green 同理。

可观察后果（Prometheus 已选中相应 ServiceMonitor）：A 维护停机时，B monitor 产生带 B scope 的 A 故障副本，A 的控制信号无法抑制，造成误告警；A Service 完全消失但 B 存在时，A scope 仍有 B 的目标序列，可能掩盖 A 的 `absent()` 目标缺失告警。不能声称已实现按部署隔离。

修复方向：应用及相同模式的观测 ServiceMonitor selector 加入 Release instance 条件；测试应验证 selector 实际匹配集合，不能只检查 relabel 文本。验收：同 namespace 双 Release，每个 monitor 只匹配本 Release，A 缺失/B 正常时 A 缺失告警仍触发。

证据层级：Helm 渲染与匹配复现；未部署、未执行 Prometheus/Alertmanager 联合采集。

### BUG-20261026-06：恢复调用可无限等待响应

主位置：[TriggerReadinessRecoveryClient.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/readiness/TriggerReadinessRecoveryClient.java) 第 43、52–62 行。

直接构造 RestClient，只设置 baseUrl，不走受控客户端或 Spring 注入 builder，也无连接/响应超时。Trigger/代理接受连接但不返回响应时，同步调用可以不返回。[ReadinessWaitService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/readiness/ReadinessWaitService.java) 第 92–95 行逐条调用，后续等待无法处理；fixedDelay 需要当前轮返回，ShedLock 过期也不会中断旧 HTTP 调用。

已检查属性及本地依赖源码默认值，未发现可作用于该静态 builder 的超时定制。修复方向：复用受控内部 HTTP 通道并设置有限连接/响应超时。验收：接受连接但不回包，单条在限定时间内失败，下一条仍可推进且下一轮能执行。

证据层级：源码及本地依赖配置确认；本轮未启动挂起响应端复现。

### BUG-20261026-07：最早 100 条等待阻挡后续记录

主位置：[ReadinessWaitMapper.xml](../../batch-orchestrator/src/main/resources/mapper/ReadinessWaitMapper.xml) 第 35–38 行；关联 ReadinessWaitService 第 133–155 行。

至少 101 条 PENDING，前 100 条 deadline 较早但依赖未就绪，第 101 条已就绪且无 Quartz 恢复回调。reconciler 固定调用 `sweep(100)`，查询每轮按 deadline 取相同前 100 条，deferred 不更新探测水位、下次探测时间或游标，第 101 条不会被检查。默认等待窗口 7,200 秒，这种延迟可接近两小时；窗口接近时机会还可能先被 Trigger 兜底终结。

修复方向：保持单轮上限的同时用探测时间或有界游标轮转，不让未就绪记录永久占据入口。验收：构造超过批次上限的等待，前批持续未就绪时后批已就绪记录仍能在有限轮次恢复。

证据层级：源码/查询确认；本轮未执行数据库或调度测试。

### BUG-20261026-08：渠道 enabled 未控制 AM 投递

主位置：[AlertmanagerNotifyService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertmanagerNotifyService.java) 第 74–85 行。

将 receiver 对应通知渠道设置 enabled=false 后，`selectByCode` 仍返回该行，deliver 只检查 null，继续发送并记录成功。[NotificationChannelMapper.xml](../../batch-console-api/src/main/resources/mapper/NotificationChannelMapper.xml) 第 31 行起仅过滤删除标记；[WebhookDeliverySupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/WebhookDeliverySupport.java) 第 70 行将临时 subscription 强制设为 enabled=true。非 Webhook sender 也未得到 enabled 字段。

同系统 TerminalNotificationDeliveryService 已检查 `!channel.enabled()`，并非“停用只影响某类事件”的已声明契约。

修复方向：AM 编排入口核对渠道启用状态，明确禁用后的处理结果，不能记录成功送达。验收：enabled=false 时底层 sender/HTTP 零调用、无成功回执；重新启用后正常投递。

证据层级：源码及邻近契约确认；本轮未发送真实通知。

### BUG-20261026-09：Workflow 导入更新遗漏说明字段

主位置：[TenantConfigPackageExcelApplyService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigPackageExcelApplyService.java) 第 906–922 行；关联 [WorkflowDefinitionMapper.xml](../../batch-console-api/src/main/resources/mapper/WorkflowDefinitionMapper.xml) 第 215–231 行。

配置包包含 description，解析已取得更新值，但已有 Workflow 分支改走 `updateAndBumpVersion` 后，参数对象和 SQL 都没有该字段。结果 version 加一、updated 计数加一且返回成功，数据库说明仍旧。之后只有节点/边、缓存失效和版本快照，没有补写主定义说明。基线 upsert 原本会更新该字段，因此是回归。

修复方向：保留 CAS，将 description 纳入同一受版本保护的更新。验收：导出现有 Workflow→只修改说明→应用，持久化说明、版本、更新统计和再次导出一致。

证据层级：源码、SQL 与基线比较确认；本轮未运行配置包集成测试。

## 已排除与未升级的候选

- 依赖已就绪后的 deadline 重试：现行 Runbook 明确仅“依赖未就绪且到期”终结，HTTP 失败保留等待，不单独计 Bug。
- 同一 source task 的多 dead letter：已看到任务版本 CAS 与回滚路径，不能仅按数量认定重复执行；真实并发场景未复现。
- 微批 report 的逐项关联、重复/错项保护以及失败进入原 Outbox 已追踪，未把批量传输当作共享业务事务。
- Go 结束任务的 defer 恢复判断早于 registry.Remove，心跳可能缓解恢复延迟；作为 BUG-02 的相关验证点，不另凑一项主缺陷。
- 多副本维护发布与本地刷新有短暂传播延迟；本轮未证实超出既定一致性边界的独立缺陷，不以假设计入汇总。

## 实际检查与验证限制

1. `git worktree list --porcelain`、`git status --porcelain=v1 -uall`、`git merge-base`、`git diff ee01e975` 和 `git ls-files`：确认分支、基线、完整本地清单；只读。
2. `rg`、源码/Mapper/调用方/测试阅读与基线 `git show` 比较：完成上列因果核对；没有执行测试，不写成单测通过。
3. Helm 本地渲染：对 audit-blue/audit-green 分别执行下列命令，使用 PyYAML 解析输出，并按 matchLabels 检查两份 Service 集合；命令退出码均为 0，复现交叉匹配。

```sh
helm template audit-blue helm/batch-platform --namespace audit \
  -f helm/batch-platform/examples/values-local-k8s.yaml \
  --set fullnameOverride=audit-blue \
  --set serviceMonitor.enabled=true --set prometheusRule.enabled=true
helm template audit-green helm/batch-platform --namespace audit \
  -f helm/batch-platform/examples/values-local-k8s.yaml \
  --set fullnameOverride=audit-green \
  --set serviceMonitor.enabled=true --set prometheusRule.enabled=true
```

报告落盘后的实际文档检查：

- `python3 scripts/ci/check-docs-structure.py`：PASS。
- `python3 scripts/ci/check-doc-timestamp-policy.py`：PASS。
- `python3 scripts/ci/check-changelog-sync.py`：PASS。
- `git diff --check -- docs/review/README.md docs/plans/todo-master.md docs/changelog.md`：PASS；新报告单独检查无尾随空白。
- 收尾比对 11 个关键证据文件 SHA-256：未变化；Git 暂存差异摘要未变化。独立只读观察者复核计数、证据分层、Console 两项与 Helm 因果，无实质异议。

没有重新运行既有编译、Java 治理、业务测试、SDK conformance、真实 PostgreSQL/Kafka、容器启停、通知或前端验收。未执行全套静态扫描器或 CI。修复阶段应按各项验收点先复现，再做定向回归；不能用本报告代替发布验收。

当前待办见 [TODO Master](../plans/todo-master.md)。本报告保留本轮发现，之后的修复状态与新验证记录从待办索引追踪，不覆盖历史证据。

## 全工程续审：补偿与死信事务链

本批沿 Controller → 补偿命令 → 事务执行器 → 重放治理 → Mapper/Outbox 核对实际副作用，发现下列两项。前文九项保留初轮快照；当前验证见[整改验证](../verifications/backend-bug-remediation-2026-10-26.md#全工程续审补偿与死信)。全工程覆盖仍以[逐文件清单](backend-bug-file-coverage-2026-10-26.csv)为准，未审文件不因回归通过而销项。

| ID | 级别 | 触发、错误结果及根因 | 当前修复 |
|---|---|---|---|
| BUG-20261026-10 | P1 | 普通 DLQ 补偿中，业务重放在内层 REQUIRES_NEW 提交，随后补偿 SUCCESS 记账失败；外层回滚后命令 FAILED，但任务/分区/步骤已重置、Outbox 已增加 | 补偿专用重放入口强制 MANDATORY 加入既有业务事务；业务重放与 SUCCESS 同提交/回滚。直接/后台重放仍逐项独立事务 |
| BUG-20261026-11 | P2 | 自动扫描读到旧 NEW/FAILED 快照，人工重放随后成功；旧扫描的无状态条件 GIVE_UP 更新又覆盖 SUCCESS | `updateGiveUp` 限制可转换状态，允许本次事务内 REPLAYING 的孤儿处理，拒绝覆盖 SUCCESS；受影响行为日志依实际更新行数判断 |

关键源码为 [DefaultCompensationService](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java)、[DefaultRetryGovernanceService](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java)、[DeadLetterTaskMapper XML](../../batch-orchestrator/src/main/resources/mapper/DeadLetterTaskMapper.xml)。均已用真实 PostgreSQL 先复现失败，再验证修复；本轮没有结构迁移。

历史待验的“同一 source task 两条 dead letter 并发提交核实重放”已通过公共 `CompensationService.submit` 在真实 PostgreSQL 复验：恰好一个命令成功、另一个 CONFLICT，一个新触发请求和一个 Outbox；源任务版本只增加一次。现有源任务 CAS 有效，该候选不计新增 Bug，不以 private 方法不可直接调用作为未验证理由。

## 全工程续审：Outbox 写权限

**BUG-20261026-12 / P1**：`ConsoleOpsController` 的 Outbox 清理和重投没有方法级写权限，继承类上的 ADMIN/AUDITOR/TENANT_ADMIN 读取权限。安全过滤链只要求有效角色，租户解析允许全局审计员指定租户，因而 AUDITOR 能进入真实写服务。不是只读按钮显示或注解风格问题。

已在两写方法增加已有 `ADMIN_OR_TENANT_ADMIN` 表达式。统计读取与 TENANT_USER 的概览读取保持原契约；租户管理员仍由原租户守卫限域。`ConsoleOpsControllerTest` 通过实际 Spring 方法安全代理调用两端点：审计员首红没有抛拒绝异常；修复后 AUDITOR/TENANT_USER 均在服务调用前被拒绝，ADMIN/TENANT_ADMIN 保留调用。未修改安全默认角色或全局过滤配置。验证及配对前端行为见[续审验证](../verifications/backend-bug-remediation-2026-10-26.md#全工程续审outbox-写权限)。

## 全工程续审：Worker 批次退出与遥测输入

| ID | 级别 | 触发、差异与根因 | 修复与实际验证 |
|---|---|---|---|
| BUG-20261026-13 | P2 | 开启内置 Worker 批量认领后，首项业务抛出 Error，批次立即退出；后续已 CLAIM、未进入执行的项仍永久保留在本地注册表，续租器持续续租，服务端不能等到租约过期回收 | 批次 finally 只交接尚未遍历的认领项；包装器按 tenant/worker/invocation 与尚未启动状态原子移除本地登记。已执行项和其他 invocation 不清理，原 Error 继续传播。真实包装器/线程池首红留下一个租约，修复后为空；正常执行、重复、续租和消息协调回归通过 |
| BUG-20261026-14 | P2 | 遥测事件列表中的 null 元素不被级联 @Valid 拒绝，Controller 直接解引用，合法格式的 `[null]` 或混合数组返回 500 | 元素增加 @NotNull；两个 HTTP 首红均为期望 400、实际 500，修复后按原参数错误路径返回 400，合法遥测和真实写路径保持可用 |

关键实现：[批量派发](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java)、[租约登记](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java)、[执行包装器](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java)、[遥测请求](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/application/contract/request/FrontendTelemetryRequest.java)。批量认领仍默认关闭，没有更改协议、生产开关或任务状态主机。

另对 95 个 Controller 文件、其中 58 个包含 POST/PUT/PATCH/DELETE 的文件做权限初筛，12 个候选沿实际用途排除：登录/退出、遥测、受共享密钥保护的 AM 接入、只读预览/校验/演练计划、AI 能力授权及 tenant/operator 所有权操作。POST 或缺方法级注解不自动计 Bug，见[候选处置](backend-console-write-auth-review-2026-10-26.csv)。该专项不表示这些 Controller 的全部逻辑已逐行完成审查。


来源范围变动：`scripts/fix-fixture-xlsx.py` 在续审期间已不存在；本任务未删除该来源。旧摘要 `d364e770096f74b0f951c6e5dcc9a44ad06ec7c02768ee66dcf714d4b654eb1a` 保留于此，当前清单按现存入口更新。fixture 生成器当前入口为 `scripts/sim/fix-fixture-xlsx.py`，仅核对路径，不把其运行结果或业务语义记为通过。

## 全工程续审：夏令时回拨业务日

**BUG-20261026-15 / P2**：`CalendarBizDateResolver` 用本地时分判断是否到达日切。纽约日切为 01:30 时，2026-11-01T05:40Z（第一次 01:40）已进入 11 月 1 日，2026-11-01T06:10Z（回拨后的 01:10）却退回 10 月 31 日。一次业务日已被接纳后再次落回旧日，可能错误选择业务区间或关闭日。

改为比较当日日切的实际 Instant：重复日切沿用现有测试约定的第一次；不存在的日切沿用跳时后首个有效时刻。未改无日历、节假日、补工作日及滚动规则。新增连续触发回归先失败后通过，连同已有 DST/日切/节假日与 readiness 恢复测试共 14 项通过；含真实 PostgreSQL，未进行真实 Quartz 定时器的跨小时等待。权威运行规则见 [Trigger 手册](../runbook/trigger-operations.md#业务日与夏令时日切)。

## 全工程续审：Workflow 集合空元素

**BUG-20261026-16 / P2**：Workflow 创建和全量替换共用的请求只对节点/边元素标注 `@Valid`，空元素不会触发字段校验。真实 DAG 校验器会解引用空节点或空边，HTTP 参数合法 JSON 仍返回 500。请求元素增加 `@NotNull`，在进入写服务前以现有参数错误拒绝；列表缺省语义、合法图、版本和编辑锁保持不变。

创建 HTTP 的两个用例首红均为期望 400、实际 500；修复后验证服务零调用，全量替换嵌套定义的两个反例也通过。Workflow HTTP/拓扑/写入与调度关联回归共 101 项通过，其中 4 项 Workflow 和 2 项 readiness 为真实 PostgreSQL 集成；没有修改 Flyway 或线上接口字段。详细命令见[整改验证](../verifications/backend-bug-remediation-2026-10-26.md#全工程续审workflow-空元素与调度关联回归)。


## 全工程续审：批量日状态机唯一所有权

**BUG-20261026-17 / P1，已修复并定向验证。** Trigger 的 `BatchDayCutoffScheduler` 是实际扫描注册的定时组件，与 Orchestrator 使用不同 ShedLock 同时写批量日。Trigger 仅筛选 OPEN 且 `cutoff_at is null` 的记录，以当前本地时分判断截止，未核对业务日期、冻结标志及 schedule/DST 策略；更新未递增版本或写审计。合法历史空截止记录可被提前推进，且并发版本 CAS 无法感知这次写入。

修复为移除 Trigger 重复写入链及其专用 Mapper、参数和投影；原 Mapper 状态测试中的其他用例保留。Orchestrator 继续负责生命周期，没有增加另一套策略。真实组件扫描回归先失败于 `BatchDayCutoffScheduler#scheduledCutoff`，移除后通过；真实 PostgreSQL 验证到期推进、未来和冻结不变、版本递增及重复扫描仅一次审计。当前设计与 Trigger 运维文档同步所有权。

退役来源保留摘要，避免删除后丢失审查证据：

| 原路径 | 退役前 SHA-256 |
|---|---|
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/scheduler/BatchDayCutoffScheduler.java` | `c44f9059eb757455046cb835bb08a776b7bd2bbd4a2d60a84614a040ae58bc65` |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/mapper/BatchDayInstanceMapper.java` | `43d6e847b5683a98af6a8212d885c4b6e77e2755a64a1ed4efdc0bdc41ef922f` |
| `batch-trigger/src/main/resources/mapper/BatchDayInstanceMapper.xml` | `fa9490c6ea79d9c937eda2a9608d88c371def2a7cf7fb676d55250bbbef37897` |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/domain/param/BatchDayInstanceMarkCutoffParam.java` | `8d335c975fb151c4d8c270056f4ff643133e90a5ef786d7f2b9594c0f30e3fe0` |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/domain/param/BatchDayInstanceTransitionToCutoffParam.java` | `ef30d8523618772b984632c7e6319f8620aff7cea4b5cdffcf155db111614175` |
| `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/support/BatchDayCutoffCandidate.java` | `a7b0249828a72809a99b29db19d2ab4824fd36b7347f5aa4b0c5a2220de51ca2` |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/infrastructure/scheduler/BatchDayCutoffSchedulerTest.java` | `729405c23aa0be12360594e96edaabff3bbae794128fb6fcb91599a6c48c9359` |


## 全工程续审：Trigger 准入排队与排水

**BUG-20261026-18 / P2，已修复并定向验证。** `TriggerController` 的 launch、events 与 scheduled-occurrences/catch-up 在进入本地准入队列前检查 draining；队列等待期间开始排水后，获得许可的请求直接调用业务服务，仍被受理。实际交错回归用真实准入闸门、HTTP 请求绑定和排水状态，三入口均先返回 200，违反排水后不再启动排队写入的约束。

保留队列前快速拒绝，再在获得许可后、调用业务服务前统一复核 draining；已开始执行的业务仍按原事务和持久化语义收敛，不以本地开关撤销已受理请求。六个正反例覆盖三入口的排水拒绝与正常排队放行。关联内部密钥、受限事件来源、状态查询、连接预算和 Quartz 排水测试保留。


## 全工程续审：配置同步目标身份与外部事件数字目标

**BUG-20261026-19 / P2，已修复并验证。** `ConfigSyncImportRequest` 与 `TenantConfigBatchInitRequest` 的列表元素只有长度约束，null 和空白可通过；同步应用服务也未检查这些身份。真实认证 HTTP 复现 null 返回 500、空白返回 200，均已创建一行同步审计。现为元素增加非空白约束，并在同步应用层创建审计前再次拒绝，保留合法目标、部分失败与事务边界。

**BUG-20261026-20 / P2，已修复并验证。** `ExternalEventDeliveryMapper.containsWorkflowTarget` 用 `jsonb_exists(..., varchar)` 查询目标，但接纳代码以 `List<Long>` 序列化为数字 JSON 数组，合法成员被判为不在集合。真实 API Key/HTTP/PostgreSQL 先红：对已冻结成员重投得到 409。改为数字 JSON 数组包含判断；现在同一目标复用原请求和 Outbox，未冻结目标仍 409，不扩大事件投递范围。

配置同步还增加原请求身份查询契约，支撑前端 FE-BUG-37；后端原有未知结果保留 RUNNING/收据核对规则未改成重新执行。查询为管理员专用、租户作用域内的读取，未找到不释放未知命令。

本段追完 `DefaultTriggerService` 各入口与持久化分支，继续核对所需 Mapper/调度回调。曾怀疑的 MANUAL_APPROVAL 插入竞争已追到 occurrence 原请求身份 CAS：请求和 occurrence 同事务，错误身份会拒绝并回滚，未把该候选计为 Bug。外部事件状态的归档读取口径及其余调度回调仍待后续链路核对，不用本段定向通过覆盖剩余来源。


## 全工程续审：租户初始化未知提交结果

**BUG-20261026-21 / P2，已修复并分层验证。** `DefaultConsoleTenantConfigInitApplicationService.batchInit` 把执行器抛出的所有异常转换为该租户失败并继续后续目标。事务提交已经成功、确认返回异常时，响应会把已生效配置记为失败，调用者可能据此重做。旧实现的最小边界用例先失败；修复为仅对明确的严格包回滚生成失败结果，一般事务/连接异常向上传播并停止后续租户。

真实 PostgreSQL 边界用例使用单独见证表，在真实 commit 后注入确认异常；独立连接可见首个目标已提交，第二个目标没有执行，原异常保留。该测试不替代真实网络断连或完整初始化 HTTP 演练。原严格回滚继续逐租户隔离、逐条目部分失败计数、dry-run 及提交后缓存失效回归保留。

本修复没有给旧初始化/复制入口增加持久原键或目标收据；这些入口发生未知结果时仍须人工核对，不可套用配置同步页面的恢复保证。配置审批调用方已追到事务传播点，但发布单和复制服务的全部行为仍在清单中保留待审/部分审查。
