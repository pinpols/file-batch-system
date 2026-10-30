# 开发分支参数与长方法收尾增量审查（2026-10-24）

> 截止状态：B191状态写入与跨域收口已定向通过：Replay3类52项、空参守卫/行为2类17项、Console原10类82项已有组合通过证据（首轮1failure/1error，final-record夹具1/1与查询守卫3/3复验覆盖）。架构原30类已执行，后续两类E2E守卫、最新R34 XML与最终PMD/运行包仍需验证。R34真实API首红已确认请求外键阻断，补丁另被复核拦住同ID跨业务日过删风险，正补歧义拒绝和邻居保留；独立归档成本Probe已实现待运行。历史B184–B190 PMD0与本地36/36各自保留原快照范围，不冒充本批最终验收；格式暂缓、CI及EX独立。
> 本文只审查 B184–B190 收尾增量，不表示全仓、全部 408 个文件逐条审查、安全全审或发布验收完成。

## 1. 结论与范围

参数与长方法规约整改已完成本轮代码侧收口，15 个相关模块的 PMD 组合结果从 162 项降至 0；116 类、1029 个定向单元/组件测试通过。原计划数据库定向验证已收齐；本轮已发现的问题均为已核实的测试适配或夹具问题，不能冒充新的生产 Bug 修复，也不能宣布整分支通过。

后端工作树为 feature/workflow-daily-integration，HEAD 为 ee01e97515b04fd167ed17977535ec5cab8572f8；结果包含未提交、暂存及未跟踪内容，HEAD 本身不能表示本轮全部源码。
本轮源码/测试范围以 remaining-conventions/source-manifest.json 的 408 条文件及 SHA 为准；没有提交、推送或创建 PR。

人工复核重点为 TaskDispatchEventCommand、HTTP 治理端口、SQL 绑定与分页、Worker 资源/事务边界、SDK 配置与执行分支。
本轮限定复核未确认新的生产缺陷；这不等于逐条完整审查 408 个文件，也不表示所有安全风险均已排除。

## 2. 已完成的验证与实际边界

| 层级 | 证据与结果 | 边界 |
|---|---|---|
| 编译与 PMD | 首次编译/PMD 的有效错误保留，修复后定向复验；15 模块 XML 组合均为 0 项 | 静态结果不证明运行语义、数据库或生产性能 |
| 单元/组件 | 116 类、1029 项，failures/errors/skipped 均为 0 | 来自按变更选择的用例，不是全量 Maven/SIM |
| 额外定向 | Quartz 10/10；Controller 7/7 | Quartz 同时出现在首轮合并报告，批次间不重复累计 |
| 首轮合并验证 | 7 类、102 项，首次 2 errors；其中包含 Quartz 10 项 | BatchInsert 夹具修复后 3/3；终止竞态定向 2/2；Controller 7/7 |
| 数据库续验 | 7 类、163 项，首次 1 failure、1 error | Calendar 角色上下文方法 1/1；并行配额方法最终复验 1/1、0 skip |
| 数据库最后一批 | 12类83项，首次Alert整类9项中1失败；修复后该整类9/9 | 前两批与本批共26类348不同用例的组合通过证据，含Quartz10，不重复计算复验 |
| 架构/约定入口 | B191首轮在清单脚本exit1，Maven/clean尚未执行 | 忽略目录备份被误算重复FQN；先保留首红并验证守卫正反例，不能记作Java测试失败或通过 |

同一用例的失败、复验及不同批次记录有重叠，不能把行内数字相加当作不同用例总数；首次失败和中断原件保留。

## 3. 失败根因与当前未闭合点

- 单参数收口后，少量 Mockito 回调仍按旧多参索引取值；已改为读取第 0 个具名参数，再取相应字段。复核同时排除了仍合法的多参回调，没有批量替换索引。
- BatchInsert 夹具补足真实父链/业务日并移除 replica 旁路，Controller 的操作UUID误报改用运行期值且保留传递断言；未改变生产 SQL、放宽断言或降低门禁。
- 终止竞态夹具曾形成测试自锁：REPORT 持父行锁等待测试 advisory lock，同步终止又等待 REPORT，而夹具仅在终止返回后释放 advisory lock。线程栈和 PostgreSQL 等待图已保存，修复夹具后定向 2/2 通过。
- Calendar 专用角色仅增加 job_instance.updated_at 的列级 UPDATE 权限，以支持既有 FOR KEY SHARE；NOSUPERUSER/NOBYPASSRLS及租户正反例保留；定向方法已通过，不外推全类或全部 RLS 路径。
- 并行配额方法 directWorkflow_shouldWaitForEveryParallelJobBeforeEnd：先修复配额负缓存夹具，随后暴露启动空扫的 ShedLock 最小持锁 5 秒与 250ms kick 的竞争。该测试使用 600 秒定期扫描，生产仍有 10 秒定期兜底。
- 已补真实 LockProvider 可用性探针，先确认启动锁窗口结束再观测提交后 kick；最终单方法复验 exit0、1/1、0 skip（12:27:15）。这是测试时序修复，不表示生产存在相同停滞。
- Alert前一真实poll保留最小2秒ShedLock，后一方法立即poll会正常跳过；两个真实poll场景共用有界等待本告警通知水位线，再保留outbox、实际Webhook及投递断言。修复后整类9/9通过，未旁路锁或手工造投递。
- B191源码清单直接递归扫描，误包含忽略目录中的验证备份。此问题发生在Maven前，不是业务测试失败；修复需继续发现真实未跟踪源码及重复FQN。同期他会话修改表退役/配置包与部分Worker代码；本报告的PMD与测试仅对原证据快照负责。

## 4. 安全与性能证据

Gitleaks 首轮扫描当前工作区变更/未跟踪快照 3672 文件，命中 1 个新候选：测试用 operationKey UUID，非认证凭据。
修正测试值表达后，只复扫变化文件；与原快照使用同一配置组合覆盖当前 3687 文件，候选为 0。该结论不是对 3687 文件重新做一次完整扫描，也不是全仓历史或依赖安全审计。
历史 9 项候选已逐项核实，归为两份 E2E 文件中的两个确定性测试值；来源、配置写入与本地 HTTP/子进程消费链一致，旧字面值在当前对应文件中均已不存在。未发现来自生产凭据的证据。历史1746文件零命中仅有文字记录，本轮不把它作为当前扫描原件。
未执行依赖/CVE、镜像、完整 SAST、DAST 或当前 CI/Sonar；不能据此宣布安全验收完成。

DEV-REVIEW-4 的重复诊断代码路径已有修复，历史真实 PostgreSQL 查询计划探针也已执行：PG17.11、50 租户、5 万终态实例、2.5 万父引用，暖缓存后三次为 14.087/15.781/19.382 秒，计划使用父引用分区索引。
当时主机与其他集成测试并发，一次性探针源码已移除；因此只能证明历史样本的结果和索引路径，不能证明当前受控归档吞吐、生产容量、P95/P99或锁等待达标。
不得继续写“真实 EXPLAIN 从未执行”。后续本地需补当前完整构建/迁移清单下可复现的多批归档对照；V334 仅说明索引来源，不代表当前全部迁移基线。无需因此重跑 B171 mixed 或扩展全库索引/分区治理。

## 5. 原始证据索引

本节 E 指 tmp/log-artifacts/coordination/20261023-final-closure/remaining-conventions/，是本机证据目录，不是已提交交付物。

- 范围/静态：E/source-manifest.json、E/b188-pmd-summary.json；首次失败 E/b188-test-compile-first-failure.log、E/b188-pmd-first-failure.log；修复 E/b188-console-test-compile-fixed.log、E/b188-pmd-console-fixed.log、E/b188-pmd-dependency-continuation.log。
- 单元/组件：E/b189-unit-final-results.json；E/b189-focused-unit-first-failure.log、E/b189-two-failed-classes-fixed.log、E/b189-remaining-unit-component-passed.log；实际命令见 E/b189-focused-command.json、E/b189-remaining-command.json。
- 数据库首轮：/tmp/codex-b190-targeted-db.log、/tmp/codex-b190-first-db-results.json；修复复验 E/b190-fix-rerun-command.json、E/b190-fix-rerun-passed.log。
- 数据库续验：E/b190-db-second-run-failed.log、E/b190-second-db-results.json、E/b190-second-fix-command.json、/tmp/codex-b190-second-fix-rerun.log；剩余选择 E/b190-last-12-command.json。
- 配额单方法最终复验：E/b190-quota-final-command.json、/tmp/codex-b190-quota-final-rerun.log；12:27:15 完成、BUILD SUCCESS，1/1、0 skip。
- 线程与数据库等待：/tmp/codex-b190-orchestrator-thread-dump.txt、/tmp/codex-b190-after-term-thread-dump.txt、/tmp/codex-b190-lock-wait-proof.json。
- 密钥扫描：/tmp/codex-b190-secret-scan-summary.json、/tmp/codex-b190-secret-composite-summary.json，含精确命令、配置 SHA 与原始输出目录。
- 历史性能：logs/verification/workflow/dev-review-archive-diagnostic-load-indexed-20261021.log:4586；审查原文 docs/audit/development-branch-code-review-2026-10-21.md；执行日志 B171 只提供独立 mixed 场景证据。

## 6. 后续关闭条件

1. 修复B191清单误报并验证应拦截/应放行案例，再按标准入口运行架构/约定测试；不扩大基线或吞掉错误。
2. 原26类348项已收齐；后续他会话新增源码漂移须另验，原通过报告不能外推为当前分支全部通过。
3. 当前受控归档性能对照与目标环境签收分开记录；EX-01/03/04 保持各自边界，不用本地静态/组件结果替代。
4. 格式检查按用户既定安排后做；CI、完整安全与发布门禁仍未完成。原 R01–R36 的正式24已验证/12部分验证状态与外部例外不因本报告自动改变。

