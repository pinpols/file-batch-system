# Workflow 调度缺省值修复与验证

## 目标、范围与基线

- 目标：修复 Daily SIM 的 Stage 22 注册超时和 Stage 24 无 PLANNED occurrence，保留实际 Quartz misfire、审批及恢复断言。
- 基线：`e0271d4c23aa0aa1c0c4e99c5da1c138d1fbc4e8`；失败运行 [41967808774](https://github.com/pinpols/file-batch-system/actions/runs/41967808774)，上次成功 [41906951617](https://github.com/pinpols/file-batch-system/actions/runs/41906951617) 的 SHA 为 `7c5b1039c5804222ec0a6e4d15c81105abfe91f8`。
- 范围：Console Excel 创建及 Workflow INSERT、Trigger 定义校验/记录隔离、SIM 静默、对应测试和只读数据审查入口。不改调度业务日规则，不批量修改已有业务数据，不合并或部署。

## 证据与决定

1. 上述两次运行的 Workflow、SIM 脚本和 Trigger 源码未变。PR #1322 对 CRON 缺省模式推导为 SCHEDULED，暴露了既有持久化问题。
2. Excel Job INSERT 为未填时区写入 Asia/Shanghai，但创建 Workflow 前的 Java 对象未回读默认值；Workflow 得到空时区。Provisioner 明确设 scheduleEnabled=false，Workflow INSERT 却漏列，落为数据库默认 true。
3. 空时区 CRON 进入 Quartz 注册路径时触发 JDK TimeZone 的 NPE。整轮对账被打断，后续健康定义的注册/计划被阻塞。失败日志的 NPE 摘要与最小 JDK 验证一致，但历史日志没有出错 Workflow 身份或完整栈，原失败的全栈因果仍为高置信判断。
4. Excel 改为回读数据库实际 Job 再 provision，保留既有默认语义；不以可配置平台时区替换数据库默认。Workflow INSERT 明确持久化开关；null 保持原 true 默认，避免改变普通 Workflow 创建入口的既有行为。
5. CRON 空/非法时区明确拒绝，不静默换时区；对账逐条隔离定义参数错误，基础设施异常仍保留原处理。坏记录不误删已有 Quartz 注册，也不自动暂停它；历史处置见 [Trigger 运维](../runbook/trigger-operations.md#调度缺省值与坏定义隔离)。
6. SIM 原清理只改 Job，未关闭权威 Workflow 日程。改为仅关闭 ta/tb/tc 的定时 Workflow 开关，保留配置；Stage 22/24 fixture 自行明确启用目标日程，不放宽超时或断言。

## 验收矩阵与当前证据

| 层级 | 验收 | 状态 |
|---|---|---|
| Trigger 定向单测 | 空/空白/非法时区拒绝；坏定义在前而健康定义仍注册、物化、恢复；不误清理；恢复列表隔离；基础设施失败仍终止 | 最终定向 46 tests PASS，零失败/错误/跳过 |
| Console 定向单测 | Excel 使用回读的持久对象；受管调度默认关闭、显式时区保留 | 新回归先在旧实现 FAIL；修复后三类 22 tests PASS（含 MyBatis SQL 绑定 3 项），零失败/错误/跳过 |
| PostgreSQL/MyBatis IT | INSERT false/true/null→true；真实 Excel CRON 导入后回读 Job/Workflow 时区与开关 | 已编译并尝试执行；Testcontainers 因无 Docker 跳过两类共 8 项，真实数据库行为未验证 |
| SIM 脚本契约 | 权威开关、ta/tb/tc 边界、保留表达式/时区、残留查询 | 新增 2 条旧实现先 FAIL 后 PASS；所在套件 25 tests PASS |
| Stage 22/24 + 完整 SIM/strict | 真实 Quartz callback、计划、审批、Root/Job 终态与重启恢复 | 未运行；当前无 Docker/运行栈，不得以单测替代 |
| 现有业务数据 | 单租户只读查空时区活跃日程、受管时区差异、启用状态 | 已提供审计 SQL，未连接业务库执行；无数据回填/停用 |
| CI / Sonar | 精确最终提交的适用门禁 | 未运行；准备 draft PR，待远端运行结果 |

本地构建先遇到环境问题：Maven 缺省缓存不可写、未使用环境代理、预装 Java 缺少完整 JDK 编译库。改用临时可写缓存、现有环境代理/信任库及校验 SHA-256 的官方 Temurin 21.0.13 后，Trigger 编译与单测执行成功；没有降低 release 21 或关闭 TLS 验证。

## 下一步

上述两类 PostgreSQL 回归已纳入 PR Gate 的 `b2-console` 集成分片，选测自测先在旧白名单失败，再补清单通过；避免新增 IT 只编译而未被 PR 实际执行。草稿 PR 的自动 PR Gate 会跳过实质作业，因此使用修复分支的 `workflow_dispatch` 执行完整适用门禁，并手动触发 Daily SIM（`force=true`）。

Console/Trigger 修复、最终定向 68 项单测与独立 diff 复核已完成。已获准推送修复、创建 draft PR 及 CI 验证，不包含合并、部署或业务数据修改。所有适用 pre-commit 门禁已通过，准备交付，并在具备 Docker 的隔离 CI 执行 PostgreSQL IT、Stage 22/24 和完整 SIM/strict；最终状态以该 SHA 的实际 CI 结果更新，不能把排队、跳过或未执行写成通过。

## 本地实际命令

构建使用 Maven Wrapper、完整 JDK 21、临时可写 Maven 缓存和当前环境代理；未更改项目 POM。

```bash
./mvnw -pl batch-console-api,batch-trigger -am \
  -Dtest=WorkflowDefinitionMapperXmlTest,TenantConfigPackageExcelMonitoringPolicyApplyTest,DefaultSingleJobWorkflowProvisionerTest,TriggerSchedulerFacadeTest,TriggerReconcilerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
python3 -m unittest scripts/ci/tests/test_sim_runtime_cleanup.py
bash scripts/local/pre-commit-checks.sh
```

定向测试结果为 Trigger 46 + Console 22，共 68，零失败、错误、跳过；上游没有匹配类不计为额外测试。Python 套件 25 项通过。首次 pre-commit 的环境失败来自临时 Python 环境缺 PyYAML，以及系统同名 `go` 不是 Go 编译器导致 SDK 脚本自测初始化失败；已安装仓库固定 Python 依赖，并按脚本支持的 `GOROOT_HINT` 隔离该无关探测，自测仍实际执行原失败传播断言。

最终 `bash scripts/local/pre-commit-checks.sh` 返回 0，所有适用门禁通过，包含 171 项 CI 治理自测；LOC 快照由仓库工具更新。Sonar 未运行（缺 Docker/Sonar 服务），不视为通过。
