# 运行时配置导入影响审计（2026-10-24）

## 范围与结论

审查租户批量初始化（含配置同步导入、租户复制和直接初始化）及 Excel 配置包 apply 两条配置写入路径，并沿现有配置缓存失效服务追踪到 Orchestrator 本地近缓存。

发现两条导入路径直接写配置 Mapper，没有像常规配置维护服务一样注册事务提交后缓存失效。因此多实例 Orchestrator 可能继续使用修改前的 Job、Workflow、业务日历、批次窗口或租户配额配置。修复后，两条路径按实际写入类别在原事务内注册既有 `ConsoleConfigCacheInvalidationService` 回调；数据库事务提交后删除共享缓存并广播失效事件，由各 Orchestrator 实例清除本地近缓存。

## 写入路径与处理

| 入口 | 写入边界 | 影响与处理 |
|---|---|---|
| 租户批量初始化 | `TenantConfigInitTenantExecutor.execute` 的 `REQUIRES_NEW` 单租户事务；配置同步目标执行另有 `REQUIRES_NEW` 审计/回执事务 | 根据各类 `ItemStats` 中的创建、更新、迁移结果注册失效；Job、Workflow、窗口、日历分别按租户和配置类别失效，配额策略按租户失效；窗口、日历、资源队列变更同时清除 Console meta 选项缓存 |
| Excel 配置包 apply | `DefaultTenantConfigPackageExcelService.apply` 的事务包围 `TenantConfigPackageExcelApplyService.applyAll` | 根据完整 apply 阶段的实际创建/更新统计，每个变更类别只注册一次租户级失效，避免逐行扫描/广播。新增 Job 还会调用托管单作业 Workflow provisioner，故同时清除该租户 Workflow 缓存；日历/窗口/队列同时清除 meta 选项缓存 |

批量初始化的 dry-run 不写库，因此不注册缓存失效。静态调用链复核还发现托管单作业 Workflow provisioner 原先未受 dry-run 判断约束；现已在 dry-run 时跳过 provisioner，避免已有 Job 缺少托管 Workflow 时意外落库。真实初始化若带 Job 配置，provisioner 可能新建托管 Workflow，因此会注册该租户 Workflow 缓存的全量失效。跳过项不会出现在创建/更新/迁移统计中；strict 模式出现失败会抛出异常并回滚，`afterCommit` 回调不会执行。非 strict 部分成功时按实际成功写入类别注册租户级失效。Excel 路径只有 Mapper 阶段成功完成后才注册回调；事务回滚同样不会执行回调。

既有配置发布服务仍在应用目标配置后调用同一缓存失效服务；本次不改变发布版本 CAS、审批状态、快照内容或 API。`config_release` 发布记录不代表所有实例确认采用配置，继续遵守 [配置治理与生效契约](../runbook/config-governance.md) 中的确认状态语义。

## 已有运行与后续启动

Workflow 启动在创建 `WorkflowRun` 时持久化 `workflowVersionSnapshot` 和由当次定义、节点、边及 Job 定义构造的 `executionSnapshot`；执行快照解析会校验 Workflow ID/版本与运行记录一致。配置导入创建新版本/更新当前配置后，不会改写已持久化的 WorkflowRun 快照。常规新启动在加载定义后还会进行版本一致性校验；调度持有旧版本时拒绝以旧版本继续启动。JOB_RERUN 对源实例提供 `USE_ORIGINAL_CONFIG`、`USE_LATEST_CONFIG`、`USE_SPECIFIED_VERSION` 三种明确策略。因此，缓存失效主要影响后续读取和新启动；已运行 Workflow 遵循自己的持久快照，重跑则按显式策略取配置。

此处只核对 Orchestrator Workflow 快照和 Job 重跑解析源码，不宣称所有 Worker/SDK/历史兼容路径均完成了运行时影响验证。

## 证据边界

- 静态跟踪入口：`DefaultConsoleTenantConfigInitApplicationService.batchInit`、`ConfigSyncImportExecutor.execute`、`ConfigSyncTargetExecutor.execute`、`TenantConfigInitTenantExecutor.execute`、`DefaultConsoleTenantConfigCopyService`。
- Excel 路径：`DefaultTenantConfigPackageExcelService.apply` → `TenantConfigPackageExcelApplyService.applyAll`。
- 下游失效：`ConsoleConfigCacheInvalidationService` 的 after-commit Redis 删除与 `batch:config:invalidation` 广播；Orchestrator 订阅并清除本地近缓存，见配置 runbook §5。
- 在途运行与重跑：`DefaultWorkflowRunLaunchService` 持久化 Workflow 版本和执行快照；`WorkflowRunExecutionSnapshot` 校验运行快照身份；`RerunJobDefinitionResolver` 按明确策略选择原始、最新或指定版本 Job 定义。
- 已补充批量初始化 Job 创建缓存失效、dry-run 不失效以及 dry-run 不触发托管 Workflow provisioner 的单元断言源码；当前遵照本任务限制，未编译或执行测试。
- 未运行应用、未操作数据库、未验证真实多实例 Redis/Orchestrator 传播时延。配置发布快照对所有其他运行时消费者的影响仍需按类别继续核对。
