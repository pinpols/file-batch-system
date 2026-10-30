# 实体、具名构造与固定审计证据治理（2026-10-24）

## 范围与原目标

继续[全部19项治理](../plans/development-worktree-governance-2026-10-24.md)，本阶段聚焦第2项实体形态、第8项多null构造、第13项固定字段对象，并同步涉及的注释及原文档。不缩小数据库、服务/控制器、事务、配置、测试和运行时导入等总体目标。

工作位置仍为 `feature/workflow-daily-integration` 专用工作树，后端 HEAD `ee01e97515b0`。当前生产 Java 清单2818文件；保留已有暂存/未暂存/未跟踪改动，不新建分支/工作树、不改main、不提交。

**本阶段实际修改30个生产源码文件，整改48处构造调用现场，另将1处固定五字段Map证据改为私有record。**仅源码、AST、差异及字段/求值顺序核对，未编译、未运行项目测试或注解处理。

## 实体形态核对

按实体目录及 `*Entity` 顶层类型定位101个模型，见[逐模型字段及SHA清单](entity-model-governance-2026-10-24.csv)：

| 形态 | 数量 | 裁定 |
|---|---:|---|
| 具体可变实体 `class + @Data` | 67 | 已使用Lombok生成访问器；本轮不改变其相等性、MyBatis写入或可变边界 |
| 不可变record实体 | 33 | 保留原字段/构造映射，不为形式统一改成可变class；本轮Worker、Quota沿用现有record + toBuilder模式 |
| 抽象国际化错误字段载体 | 1 | `@Getter/@Setter`生成访问器；不额外引入对象身份，修正原注释中“默认只基于id”的不准确说明 |

既有14个手写访问器继续保留：6个状态接口桥接、6个国际化错误接口桥接、2个依赖字段解码。它们承载显式行为，不能用Lombok同名访问器替换。实体目录未发现非Entity命名的顶层类型；本轮没有给可变实体新增Builder，不涉及class的无参/全参回退组合。

## 实施内容

| 内容 | 调用现场数 | 行为保持约束 |
|---|---:|---|
| 分发无回执失败工厂 | 13 | `failureWithoutReceipt`固定原七参数值；不将结果改成外部结果未知，不改变原失败消息 |
| 复用步骤响应短构造 | 7 | 原末两项null由已有三参数构造补齐，五字段record契约不变 |
| Worker快照更新与注册/缓存 | 9 | 7个with方法用toBuilder只写变化字段；2个外部宽构造按字段名写入；缓存省略的指纹和任务能力仍为null |
| 审计事件Builder | 3 | 16字段、版本值、时间及请求/操作者求值顺序不变；先构造局部事件再持久化 |
| 私有通知目标模式 | 6 | webhook/notification/unavailable分别具名；保留原字段值、问题文案与Optional延迟回退 |
| 请求元数据回退 | 1 | 上下文缺失时仅承接一次客户端地址，其他五字段继续为空 |
| 其余宽构造及配额更新 | 9 | 覆盖调度上下文、Outbox事件、Replay预览/复制、配额创建/写入与2个with方法；字段与原canonical构造逐项对应 |
| 合计 | 48 | 30个文件去重计数，多个阶段不简单相加 |

七个record新增Builder入口：WorkerRegistryEntity、QuotaRuntimeStateEntity、OperationAuditEvent、SchedulingContext、BatchEventMessage、BatchDayReplayPreviewResponse，以及Console内部WorkflowNodeReplayPreviewCommand。前两个提供toBuilder，其他按具名字段组装；已有公开canonical/兼容构造器保留。没有将受控状态参数对象改成万能Builder。

### 多null构造的完整裁定

整改前123个直接含至少两个null的构造候选逐项见[123项裁定表](nullable-constructor-governance-2026-10-24.csv)：38处整改、85处保留。48处总整改中另外10处是同类宽构造/复制，不属于原多null子集。

整改后仍有90个词法命中：69个具名工厂内部占位，其公开/实际工厂参数为0–5个；21个是短结果/常量、受控状态Details、JDK URI脱敏、数据库默认行或显式存储方言等有确定边界的构造。新增的5个内部工厂构造归入69项，故不是简单的123减38。非工厂的剩余多null构造均少于8个参数。

短构造和工厂内部null保留有明确含义，不把统计清零当目标，也不以Builder新增非法状态组合。此清单覆盖多null构造子项，不代表非null的全部长构造、所有方法参数和测试构造已完成裁定。

## 固定Map证据类型化

`TerminalNotificationRecoveryService` 的 reason、reconciliationReference、previousAttempt、confirmedNotDelivered、terminalEventKey 五个字段改为私有 `RecoveryAuditEvidence`。trim、整数attempt、true值及事件键来源不变，写入JSON仍由原 `JsonUtils` 负责。

幂等核对继续先读取存量JSON为完整Map，再将当前record在该边界转换为字段集合比较：未知额外键、缺少键、null和值的差异仍使请求冲突；没有直接用宽容DTO反序列化后equals而忽略未知字段。保留Map仅属于持久化JSON边界，不再用可变字典承载业务证据。锁、rearm、审计写入和事务顺序不变。此变更没有运行序列化/集成测试，结论限定为字段、JsonUtils配置和分支源码核对。

## 静态证据

- 对Builder/toBuilder、工厂及已有短构造展开canonical字段：第一批48个相关对象、第二批9个相关对象的字段表达式及实参内部调用顺序与改前一致，共57个；包含未修改的同类对象用于核对。比较不把新增Builder分配本身当成业务副作用，不涵盖JVM内存耗尽等分配故障。
- 第一批16文件的11个既有record字段及已声明构造器签名不变，新增私有RecoveryAuditEvidence单列；第二批10文件的22个既有record字段/构造器签名不变。规范化只针对本轮明确的工厂/Builder等价，不改存量协议。
- 全30文件AST解析错误0；没有运行符号分析、Lombok注解处理、项目编译或应用测试。源码采用仓库已有record Builder模式，实际生成代码/装配仍待后续统一验证。
- 新增/修改构造表达式只在临时片段上使用仓库Palantir 2.92.0 GOOGLE风格整理，没有整文件格式重排。
- 30文件注入/logger形式与Java可读性静态规则、当前注释增量检查通过。固定契约全量扫描候选0；该扫描仅证明已登记范围，不代表全工程Map全部裁定。

## 逐文件实施

| 文件 | 内容 |
|---|---|
| [batch-common/src/main/java/io/github/pinpols/batch/common/i18n/AbstractLocalizedErrorEntity.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/i18n/AbstractLocalizedErrorEntity.java) | 澄清Lombok相等性与仅生成访问器的原因 |
| [batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchEventMessage.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchEventMessage.java) | Builder声明与字段契约保持 |
| [batch-common/src/main/java/io/github/pinpols/batch/common/kafka/SchedulingContext.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/SchedulingContext.java) | Builder声明与字段契约保持 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/notification/TerminalNotificationRecoveryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/notification/TerminalNotificationRecoveryService.java) | 审计事件具名Builder；固定审计证据record与JSON边界比较 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/support/AuditAspect.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/support/AuditAspect.java) | 审计事件具名Builder |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/support/ConsoleOidcLoginAudit.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/support/ConsoleOidcLoginAudit.java) | 审计事件具名Builder |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/support/OperationAuditEvent.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/support/OperationAuditEvent.java) | Builder声明与字段契约保持 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TerminalNotificationDeliveryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TerminalNotificationDeliveryService.java) | 私有Destination的3种具名模式，6处调用 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/ConsoleJobOpsSupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/ConsoleJobOpsSupport.java) | 宽构造/不可变更新Builder |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleRequestMetadata.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleRequestMetadata.java) | 请求上下文缺失的具名回退 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleRequestMetadataResolver.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleRequestMetadataResolver.java) | 请求上下文缺失的具名回退 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/TaskDispatchOutboxService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/TaskDispatchOutboxService.java) | 宽构造/不可变更新Builder |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayPreviewResponse.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayPreviewResponse.java) | Builder声明与字段契约保持 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java) | 宽构造/不可变更新Builder |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/QuotaRuntimeStateEntity.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/QuotaRuntimeStateEntity.java) | 宽构造/不可变更新Builder |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/WorkerRegistryEntity.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/entity/WorkerRegistryEntity.java) | 不可变快照toBuilder更新 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mq/KafkaOutboxPublisher.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mq/KafkaOutboxPublisher.java) | 宽构造/不可变更新Builder |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java) | 宽构造/不可变更新Builder |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaRuntimeStateSnapshotWriter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaRuntimeStateSnapshotWriter.java) | 宽构造/不可变更新Builder |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/WorkerRegistryCache.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/WorkerRegistryCache.java) | 具名Builder替代宽位置构造 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java) | 具名Builder替代宽位置构造 |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/domain/StepExecutionResponse.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/domain/StepExecutionResponse.java) | 复用三参数构造 |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java) | 复用三参数构造 |
| [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelGateway.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelGateway.java) | 无回执失败具名工厂 |
| [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchResult.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchResult.java) | Builder声明与字段契约保持 |
| [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/HttpDispatchChannelAdapter.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/HttpDispatchChannelAdapter.java) | 无回执失败具名工厂 |
| [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/LocalOutboxDispatchSupport.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/LocalOutboxDispatchSupport.java) | 无回执失败具名工厂 |
| [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/RemoteFilesystemDispatchSupport.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/RemoteFilesystemDispatchSupport.java) | 无回执失败具名工厂 |
| [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SftpDispatchChannelAdapter.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SftpDispatchChannelAdapter.java) | 无回执失败具名工厂 |
| [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapter.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapter.java) | 无回执失败具名工厂 |

## 后续范围

多null构造子项已形成逐项保留/整改依据，实体访问器形态已核对。非null的其他长构造、开放Map与固定投影的全工程分类、运行时配置导入、Controller/Service职责、事务资源及其他总体工作包继续推进；不以本批静态检查代替整体完成。

## 下一阶段定位快照

为继续原目标，对2818个生产Java另定位145个“至少10参数且直接null少于2个”的构造位置，以及506个Map相关返回类型，见[651项候选](model-next-candidates-2026-10-24.csv)。其中包括DTO投影、兼容构造、动态配置、元数据注册表等；它们是待语义分类的定位数据，不能直接写成145或506个缺陷，也不能由当前已登记规则候选0推导这些位置全部合规。

## 收尾快照核对

修改源码与文档差异空白检查通过，101模型、123多null构造、651后续候选及已有生产/条件清单的路径与哈希已复核。收尾发现非本批的Workflow启动文件在审查期间另有变化，仅刷新清单哈希，未回写其源码；覆盖计数仍按各报告阶段快照解释。本批30文件的字段与实参调用顺序证据保持有效，不将共享工作区其他改动据此宣布验证通过。
