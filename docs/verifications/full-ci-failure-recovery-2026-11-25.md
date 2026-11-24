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
- 修复提交的 GitHub Full Gate、Maven Central 限流缓解效果以及真实 runner 上的准备耗时和产物大小待验证。
