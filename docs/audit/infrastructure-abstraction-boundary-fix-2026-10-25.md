# 基础设施抽象边界六项修复（2026-10-25）

## 结果与范围

用户列出的 **6处新增基础设施SDK引用已修复**，`check-infrastructure-abstraction-boundaries.py --base origin/main` 当前退出0；相关Java的Spotless通过；**152个定向测试全部通过，0失败/错误/跳过**。这是本地范围的结果，不等同完整PR/Full Gate、全部测试规范或目标环境验收完成。

开发工作树：分支 `fix/wdi-current`，HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`。保留已有暂存/未暂存/未跟踪工作；按用户授权修复六项，不改门禁、基线或豁免，不用全限定类名旁路。沿用 `module-config-boundaries`、`code-review-and-gates` 和 `backend-testing-governance`。

## 逐项处理

| 原命中 | 修复及所有权 | 保持的行为 |
|---|---|---|
| OutboxChildRecordService：SQLException | JDBC异常构造下沉 `batch-common.jdbc.JdbcExceptionSupport` | 原DataIntegrityViolationException类型、租户/事件消息、SQLException原因及23503均保留；热冷父引用仍按原Mapper路径判断 |
| BatchDayCatchupControlService：SQLException | 应用只调用公共因果链分类支持 | 23503可在底层、约束名可在包装消息；仍需两者同时具备，维持409映射 |
| ExpectationCalendarConstraintConflict：SQLException | 两个消费者直接复用公共精确分类支持，删除无剩余职责的转调类 | SQLState和目标约束名必须同时出现在同一SQLException；外部事件/文件组预期的原错误映射不变 |
| DefaultPartitionLifecycleService：SQLException | 分区业务调用公共精确外键分类，不再遍历驱动类型 | 来源文件约束仍映射NOT_FOUND，父实例约束仍映射CONFLICT；其他失败原样抛出 |
| DefaultTriggerService：SQLException | BDA01的底层识别由公共支持负责 | API、外部事件与计划触发的关闭业务日拒绝保持原业务错误；计划触发仍写原拒绝证据 |
| TriggerScheduledOccurrencePlanner：org.quartz.Trigger | 入参改为项目 `ScheduledFireTimeSource`；Quartz适配仅在基础设施入口 `QuartzScheduledFireEnumerator.fireTimeSource` 发生 | 适配为惰性时间源，不提前枚举。计划版本、租户配置revision锁、事务内物化、起点排除/终点包含、数量上限和缩窗覆盖口径保留 |

公共异常支持是纯分类/异常构造工具，不新增Spring bean、远程依赖或配置。现有唯一约束检测端口仍处理自己的唯一冲突口径，没有拿其宽松逻辑替代精确外键判断。静态检查以origin/main增量判定，退出0不意味着全仓历史依赖都已清零。

主要入口：[JdbcExceptionSupport](../../batch-common/src/main/java/io/github/pinpols/batch/common/jdbc/JdbcExceptionSupport.java)、[ScheduledFireTimeSource](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/support/ScheduledFireTimeSource.java)、[Quartz适配器](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzScheduledFireEnumerator.java)、[物化服务](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/TriggerScheduledOccurrencePlanner.java)。生产Reconciler及已有集成/重启探针调用方同步适配，已转换的时间源直接传入，避免重复包装。

## 验证

| 命令 / 层级 | 结果和证明范围 |
|---|---|
| python3 scripts/ci/check-infrastructure-abstraction-boundaries.py --base origin/main | **通过，6→0**，检查源码中的当前新增引用；规则/白名单未改 |
| python3 scripts/ci/check-test-conventions.py --check-baseline docs/governance/test-conventions-baseline.txt | **通过**，新增中文展示名和方法命名守卫无新增缺口 |
| ./mvnw -ntp -pl batch-common,batch-orchestrator,batch-trigger -am -DspotlessFiles=... spotless:apply spotless:check | **通过**，精确文件名正则见本地汇总；不宣称全仓格式已通过 |
| ./mvnw -ntp -pl batch-orchestrator,batch-trigger -am -Dtest=JdbcExceptionSupportTest,OutboxReferenceIntegrityIntegrationTest,BatchDayCatchupControlServiceTest,FileGroupExpectationServiceTest,ExternalEventExpectationServiceTest,DefaultPartitionLifecycleServiceTest,DefaultTriggerServiceTest,TriggerScheduledOccurrencePlannerTest,QuartzScheduledFireEnumeratorTest,ScheduledOccurrenceMapperIntegrationTest,TriggerServiceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test | **Maven退出0；Common8 + Trigger106 + Orchestrator38 = 152项，全部0失败/错误/跳过**；相关生产和测试源码编译完成 |

新增10个回归：8个JDBC分类/原因保持用例，2个项目时间源与不推进失败用例。现有真实PostgreSQL测试覆盖Outbox热冷父引用、触发计划覆盖/配置竞态和业务日关闭；Quartz原有CRON时区/DST、间隔边界及超限测试继续执行。重启/子进程探针调用源码已编译，本轮没有执行完整QuartzMisfireApplicationRestartIntegrationTest或其崩溃恢复子进程场景。

首个有效失败是新增测试直接用AssertJ断言SQLException（该类型同时实现Iterable）产生重载歧义；改为断言消息/SQLState。随后发现一处已转换时间源被再次包装的调用类型错误，现直接传入时间源。两份失败日志均保留，最终152项独立重跑成功，不以局部成功行或跳过冒充通过。

精确命令、退出码、清单及源码SHA-256见本地 `logs/verification/workflow/infrastructure-boundary-fix-20261025-summary.json`，同目录保存before、guards、format、tests和两份compile-failure日志。日志是本地忽略资产，不作为随仓库分发的Markdown链接。

本轮未改变数据库结构或运行库、未重启/部署、未提交/推送/合并；已冻结的前后端main基线和标签保持。前一项[测试编码约定审查](test-code-conventions-review-2026-10-25.md)已经单独留档，其SQL/Mock/AssertJ等待办仍独立保留；本轮不将六项边界修复或152项通过推广为测试编码规范已全部遵守。
