# 常见故障:症状 → 原因 → 处理

## job_instance 卡在 CREATED、不前进、Kafka lag 为零
- 原因:launch 在 T1(写 CREATED)与 T2(建分区/任务转 RUNNING)之间进程崩溃,实例无可执行子项。
- 处理:`StaleCreatedLaunchRecoveryScheduler` 会自动补跑 T2(仅针对仍 CREATED、仍关联 ACCEPTED trigger_request、且分区/任务行为零的非 workflow 实例)。确认调度器开启;必要时核对 trigger_request 是否仍 ACCEPTED。

## worker 反复报 lease_renew_rejected / 任务被重复执行
- 原因:租约过期或任务被改派,过期 worker 仍在跑。每分区 `partitionInvocationId` 用于隔离:只有持有当前 invocation id 的 report 被接受。
- 处理:让 worker 重新 CLAIM;检查 worker 与 orchestrator 时钟、网络与续租间隔;确认没有两个 worker 抢同一分区。

## outbox 事件不投递 / 卡住
- 原因:outbox 写入与状态同事务,投递失败走 `event_outbox_retry` 退避重试;若卡住可能是 Kafka 不可用或重试耗尽。
- 处理:经 `ConsoleOrchestratorProxyService` → orchestrator `/internal/outbox/*` 做 republish;**console-api 不能直接 UPDATE/DELETE outbox_event**。运维脚本 `scripts/ops/heal-stuck-outbox.sh`。

## dispatch(下发)失败 / 渠道熔断
- 原因:下游渠道(API push / 远程文件系统等)连续失败触发熔断;或渠道配置(endpoint/白名单)不对。
- 处理:看渠道健康与熔断计数;核对 channel 配置;SSRF 防护会拦截解析到内网的目标(resolve-then-connect),被拦不是 bug。

## 死信里堆了一批失败消息
- 原因:消费方持续失败累计到上限;也可能是负向测试用例(故意失败)或定时 cron 自我延续刷出来的。
- 处理:区分真实故障 vs 测试污染;真实故障定位 root cause 后重投,测试污染清理即可。脚本 `scripts/ops/heal-dead-letters.sh`。

## 任务卡在 RUNNING 不终结(疑似 worker 崩了)
- 原因:worker 在执行中崩溃,租约未续、未 REPORT。
- 处理:租约超时后由 orchestrator 侧回收/改派;补偿命令遗留 RUNNING 由 `StaleCompensationCommandReconciler` 对账。

## 重要边界
- 这些自愈调度器是回退机制,定位真实 root cause 仍要看执行日志与失败分类(failure class),不要只依赖自愈。
