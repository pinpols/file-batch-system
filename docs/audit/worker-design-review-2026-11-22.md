# 五类 Worker 设计复核（2026-11-22）

基线为后端 `origin/main` 的 `51c82f49b09bcfb5823aaf53e6976c171d500144`，通过 `git ls-remote origin refs/heads/main` 核对。抽查共享执行、租约、回报和恢复边界，以及五类 Worker 各自的业务效果。不涵盖 SDK、容量或目标环境验收。

## 当前缺陷

| 项目 | 触发条件与影响 | 关闭条件 |
|---|---|---|
| P1 · Export 分页在同值边界漏行 | JDBC 映射接受配置排序列，SQL 模板接受配置游标列。两条路径都用单列 `ORDER BY` 加严格大于上一页末值的条件，无法继续读取跨页的同值明细。非空检查只检查行数或文件大小是否大于零，无法识别部分漏行。[JDBC 映射分页](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java) · [SQL 模板分页](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java) · [导出非空检查](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/infrastructure/verifier/ExportFileNonEmptyVerifier.java) | 两类模板有唯一、稳定且非空的分页顺序。用真实 PostgreSQL 测同值跨页、分片和崩溃续跑，逐条对账源明细和输出文件。 |
| P2 · Process 空成功批次使增量水位回落 | 内置 SQL 转换在无输入时不报告新水位。Orchestrator 只读取同租户同作业最新成功实例的 OUT。若该行为空，上一轮有效水位无法传入下一轮。按空水位从头扫描的模板会重读历史，写入后果取决于目标的幂等策略。[增量执行口径](../architecture/system-flow-overview.md#796-与-executionmodeincremental78的协作) · [实例查询](../../batch-orchestrator/src/main/resources/mapper/JobInstanceMapper.xml) · [启动水位解析](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultLaunchService.java) | 用真实 PostgreSQL 覆盖有数据成功 → 空批成功 → 新数据到达，确认后一轮沿用有效水位且只处理新数据。并发完成、部分失败和历史重放也需核对水位作用域及单调性。 |

## 设计边界与验证缺口

| 范围 | 当前实现及限制 |
|---|---|
| 共享 CLAIM / REPORT | 回报使用当前 invocation 校验，Outbox 可以重送已持久化的结果。Worker 线程超时靠协作中断，平台拒绝旧回报无法撤销已提交的业务动作。业务提交到回报持久化之间的恢复，取决于插件幂等或下游结果查询能力。[回报校验](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskOutcomeService.java) · [ADR-015](../architecture/adr/ADR-015-worker-side-outbox.md) · [执行与取消](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java) |
| Import | LOAD 先提交业务 chunk，再推进平台位点，崩溃窗口靠插件和目标约束吸收重放。多分区时降级为分区内重跑。预处理内置解密为 AES，PGP 仍取决于业务接入范围。[LOAD 实现](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/LoadStep.java) · [ADR-038](../architecture/adr/ADR-038-platform-worker-checkpoint-resume.md) · [现行待办](../plans/todo-master.md) |
| Export | 除分页缺陷外，生成位点还依赖本地临时文件。默认 Helm 将 `/tmp` 挂为 Pod 的 `emptyDir`，换 Pod 后需要重新生成。Excel 和多分区按现有设计在分区内从头生成。[生成阶段](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java) · [Helm 模板](../../helm/batch-platform/templates/worker-export.yaml) |
| Process | 本轮缺陷在增量水位交接。内置暂存模式使用按 generation 隔离的暂存数据，DIRECT 水位和发布走同一语句的路径已有实现。[SQL 转换实现](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java) |
| Dispatch | HTTP 自动重试依赖接收端兑现幂等键契约。SMTP 等接收方已执行但确认链断开的场景按 `EXTERNAL_OUTCOME_UNKNOWN` 保护。没有接收端证据时需要人工核对。[通道契约](../architecture/worker-plugins.md) · [SMTP 适配器](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapter.java) |
| Atomic | 非幂等执行前已有持久化启动围栏和拒绝执行的回归用例。围栏为重派提供保护，外部程序是否已经生效仍需对应系统确认。仓库生产 Helm overlay 已打开入站和 Atomic 出站 NetworkPolicy，真实站点的有效规则与最小权限仍需验证。[围栏测试](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/NonReplayableAtomicExecutorFenceTest.java) · [生产配置](../../helm/values-prod.yaml) · [ADR-049](../architecture/adr/ADR-049-replay-operation-scopes.md) |

## 验证记录

本轮用 Python `sqlite3` 内存库运行两个 SQL 语义对照。三个排序值为 `10、10、20` 的明细以每页一条读取，按当前比较谓词遍历只得到两条。两个成功实例依次写入水位 `42` 和 `NULL`，以当前排序取最新行返回 `NULL`。结合当前 Java、Mapper 和 Helm 源码核对了触发路径。本轮未运行应用、真实 PostgreSQL 集成用例及 staging 回归。现行状态见 [TODO Master](../plans/todo-master.md)。
