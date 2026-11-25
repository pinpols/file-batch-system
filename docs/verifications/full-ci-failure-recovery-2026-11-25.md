# 全量门禁历史失败修复与验证

核对日期为 2026-11-25，修复基线为主干 `6c83ff833`。核对最近 16 次 Full CI 运行，其中三次失败。
[#1459](https://github.com/pinpols/file-batch-system/actions/runs/45407710536) 和
[#1460](https://github.com/pinpols/file-batch-system/actions/runs/45424417368) 均通过。

| 失败证据 | 根因 | 修复 |
|---|---|---|
| [#1458](https://github.com/pinpols/file-batch-system/actions/runs/45183204098) 的 E2E 5、[#1462](https://github.com/pinpols/file-batch-system/actions/runs/45505227247) 的 E2E 12、15、19 | Maven Central 对安装插件返回 HTTP 429，两个插件版本均出现。#1462 的三个分片在四次尝试后仍无法进入测试步骤 | Full Gate 和手动 Staging Gate 共用一个 Maven 准备任务，分片下载同次工作流的 Maven Wrapper、依赖和已安装上游模块。准备失败由汇总和主干归责记录 |
| [#1461](https://github.com/pinpols/file-batch-system/actions/runs/45501148810) 的 E2E 启动、#1462 的 E2E 2、8、10 | Spring AI 2.1 默认装配 OpenAI 决策模型，缺凭据时阻塞启动。#1462 已覆盖为 E2E 定义的启动类，真实 Console 应用仍缺该排除 | 在 `BatchConsoleApiApplication` 集中排除未使用的模型，应用内和独立 JVM 都使用该入口。新增启动入口约定断言 |
| #1461、#1462 的编排测试及其汇总 | RLS 测试复用其他方法的租户，查询到同租户的多条合法等待记录 | 使用独立租户，断言唯一可见等待项就是本次请求，继续检查上游生效和跨租户不可见 |
| #1462 的 E2E 8 | Quartz 超期用例在共享日历上检查空日结算。同一测试类还有其他日历义务 | 复用独立日历夹具，保留超期、拒绝、跳过、结算及未开跑的断言 |

#1462 的 E2E 13 另有独立 Console 子进程退出。GitHub 上保存的 Surefire 报告记录了进程日志路径，未保留日志文件内容。修复后按相同入口重新启动并运行恢复场景。

## 验证记录

- 编排测试基线同样读到 9 条等待记录，隔离租户后整类 30 个用例通过，使用真实 PostgreSQL 和非 BYPASSRLS 角色。
- `DeadLetterApprovalReplayE2eIT` 5 个、`SelfServiceJobCompensationHttpE2eIT` 10 个、`JobPushProcessRecoveryE2eIT` 1 个用例通过，覆盖应用内真实 Console、独立 JVM 启动及强杀恢复。
- 以两个独立目录模拟门禁交接。准备端执行上游 `install`、`spring-boot:help` 和 `batch-e2e-tests test-compile`。
  消费端从归档恢复 Maven 仓库，离线执行 `OpenAiModelAutoconfigurationConventionTest`，5 个用例通过。
- 消费端恢复 Wrapper 后按 Full Gate 的原 E2E 8 选择器执行，19 个用例通过，涵盖 Quartz 超期结算与真实 Console 审批响应丢失。
- `./mvnw -o -B -ntp -fae -DskipTests test-compile pmd:check spotless:check` 通过。
- `actionlint`、`zizmor --min-severity medium`、Action SHA 固定检查、脚本治理及文档守卫通过。
  安装脚本和分片守卫的 19 个 Python 用例通过。两套工作流仍各覆盖全部 58 个 E2E 类。
- #1463 的 GitHub Full CI 结果见下节。长期限流缓解效果仍需观察后续运行。

## #1463 合入后复核

[Full CI 45514356520](https://github.com/pinpols/file-batch-system/actions/runs/45514356520) 在主干 `0ec523dc9` 运行。
Maven 准备成功，原来失败的 E2E 2、8、10、12、13、15、19 均通过。最终 19 个 E2E 分片通过，E2E 17 和编排重型分片失败。编排汇总 `unit-it-a` 随重型分片失败。

- 编排重型分片的 `JobNodeDispatchIntegrationTest#jobPreparation_shouldRecheckOriginalAfterReadinessGate` 一次读取根状态为 `RUNNING`，预期 `FAILED`。同一用例日志显示后台对账调用共享 spy 时收到 null。用 Mockito 5.24.0 通过两个线程控制调用顺序，复现后台调用抢占配置窗口、启动故障没有注入。见 [Mockito 并发说明](https://github.com/mockito/mockito/wiki/FAQ#is-mockito-thread-safe)。测试用固定拦截器，在当前发起线程的就绪检查成功返回后注入故障，并断言只改动目标请求。对账器和数据库事务照常运行。
- E2E 17 的前 4 个用例通过，第 5 个在注入崩溃之前等待 Spark 运行标记超时。原运行没有归档独立 Worker 日志，因此无法从留存报告确定 Worker 停在哪一步。本地按原分片顺序复跑时，Shell 场景也出现等待无进展。查库确认其任务停在 `READY`，Outbox 指向前序用例残留的 Worker 池，而非已注册的新进程。Shell 与两个 Spark 场景现使用专属资源标签，Kafka 专属 topic 确认分配后清除测试上下文的 Worker 选择缓存，再启动并核对 Outbox 指向。强杀、UNKNOWN 阻止重投和精确业务副作用断言保留。Full CI 与手动 Staging Gate 会在该用例所在分片失败时上传 Worker 和消费组诊断。

### 本地回归

- 原编排重型分片的 235 个用例在本地通过，独立探针复现共享 spy 的并发缺口。修复后 `JobNodeDispatchIntegrationTest` 全类 104 个用例通过。
- 用仓库固定的 Spark 4.0.1、Python 3.12 与原 E2E 17 方法选择器离线复跑。原版 5 个中 2 个超时，分别为 Shell 副作用及 Spark 输出提交后的恢复。修复版 5 个全部通过，`check-e2e-run-completeness.sh` 确认执行了所有选中的方法。
- PMD、Spotless、actionlint、Zizmor、分片覆盖、代码量及文档守卫通过。改动后的 GitHub Full CI 仍待验证。


## #1464 合入后复核

[Full CI 45554701986](https://github.com/pinpols/file-batch-system/actions/runs/45554701986) 在主干 `a7404bf5d` 运行。编排重型分片和 E2E 17 已通过。唯一失败任务是 `unit-it-b2`，Console 报告有 1 个失败、2 个跳过。

- `ConfigCacheInvalidationOutboxIntegrationTest#shouldRequireTableGrantsAndPolicy_whenDeliveryRoleClaims` 给投递角色添加策略后，首次领取返回空。同一轮测试记录显示多个 Console 上下文仍轮询 同一测试 JVM 内共享的队列。单个上下文停止转发器不能隔离其他上下文，清理其他租户行也不能阻止再次领取。测试改用独立 PostgreSQL 容器，保留真实 Flyway、RLS、事务、领取和确认断言，清理仅限本测试租户和角色。
- Full CI 与手动 Staging Gate 统一按失败分片归档 `logs/verification/**/*.log*`。规则同时匹配批日 Console 子进程、Worker 及消费组诊断，产物保留 14 天。静态检查确认 E2E 矩阵与 Surefire 报告步骤保持原配置。
- 本地按 Full Gate 的 Console 分片选测运行 `./mvnw verify -pl batch-console-api -Dtest='!*ArchTest,!*ConventionTest' -DskipITs=false`，共 3034 个用例，0 失败、0 错误、2 跳过。随后 `jacoco:check@check` 通过。原失败类在完整分片中 4 个用例均通过。
