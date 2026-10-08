# 固定契约与协议值治理本地验收记录

> 日期：2026-10-07
> 状态核查（2026-10-08）：[后端 PR #1160](https://github.com/pinpols/file-batch-system/pull/1160) 与 [前端 PR #284](https://github.com/pinpols/batch-console/pull/284) 已于 2026-10-07 合并。PR 检查均已完成；Full CI Gate [run 37736284461](https://github.com/pinpols/file-batch-system/actions/runs/37736284461) 与 SIM/strict [run 37752043644](https://github.com/pinpols/file-batch-system/actions/runs/37752043644) 在 main SHA `b5e5cbdf3e4700b87a5af35f73b5e683e569501f` 均成功。后者包含 nightly image build。该证据不代表生产环境验收或容量/灾备验证完成。
> 计划：[固定契约、有限域与常量治理计划](../plans/typed-contract-enum-constant-governance-plan-2026-10-07.md)。

## 范围与基线

后端、配对前端均在独立 `codex/typed-contract-governance` 工作树实现，未改动共享主检出目录中的既有内容。后端开发基线为 `88a96bd0a`，提交前已同步主线 `332956cb3`，前端为 `bdeb7ee`；Worker count 弃用 OpenAPI 注释及后端待办收口改动均保留。按“前后端所有内容提交 PR”的授权，本分支另外收录共享主检出目录的依赖安全、许可证与 E2E 注解治理增量，前端同时收录 CodeQL HTML 原型精确排除改动。

本轮只治理确认清单及高置信回退，不改业务状态机、配额、事务、锁、重试、发布或租户权限。

## 实施矩阵

| 对象 | 改造与保护 |
|---|---|
| Pipeline definition | Service 直接返回现有列表项 DTO；基础设施层转换 Mapper Map；非空结果验证 snake_case、分页和租户条件 |
| Import event | 固定 triggered/reason record；成功省略 reason，未触发保留原因，真实 JSON 测试 |
| Trigger / Console proxy | 固定状态、列表、动作 record；真实 RestClient JSON 转换和时间字段测试；租户作用域丢弃缺少 tenantId 的行，读降级/写失败传播不变 |
| Job trigger | sealed 联合替代 Object；普通响应保持字符串，dry-run 保持校验对象；MockMvc 覆盖两分支，OpenAPI 补录已有 wire 而非新增行为 |
| Lineage | typed 顶层和证据行，MyBatis 显式 constructor resultMap；保留热表/归档回退、排序、租户条件和空值规则；metadata_json 是动态对象 |
| 枚举 | instance/partition/workflow/outbox/tenant/notification/asset/file/import/export 复用既有 code；未将业务子集替换为全部枚举值 |
| 常量 | 复用已存在的 KEY/PARAM/MDC；另收敛 8 个类内稳定协议键；固定 API 路由常量不伪装成环境配置 |
| 前端 | 生成响应类型、Trigger 列表和运维消费点收敛；不再发送后端忽略的列表 tenantId query；无布局和文案行为改造 |

## 守卫与例外

`check-java-contract-governance.py`：

- JCON-1：Controller / Service 接口的固定返回值不得退回 Map/Object。
- JCON-2：只扫描 31 个已确认类与权威枚举 code；枚举修改会扩展复扫消费者。
- JCON-3：同类已有 KEY/PARAM/MDC 常量时，真实 key 消费点不得重新写字面量；注解、日志文案和值参数不参与。
- 8 个动态例外精确到路径、方法签名和返回类型，附业务理由；空违规基线，数量不准增长；通配例外、负数或布尔基线均拒绝。
- PR/local 增量；注册表/扫描器变化及 Full Gate 全量；注册表变更不再被视为纯文档。

Sonar S1075 对固定 Trigger API 路由的误报只允许该代理文件一次 suppression；新增文件或增加次数由 suppression 守卫拒绝。部署 host/port 仍来自内部客户端配置。该例外不允许硬编码部署地址。

## 已执行验证

| 层级 | 命令/证据 | 结果 |
|---|---|---|
| 扩展后端定向测试 | 受影响 Console、Orchestrator、Trigger、Import/Export/Dispatch/Atomic、Java SDK reactor（`-am`），末轮日志 `/tmp/bfs-typed-governance-tests-final.log` | 345 tests / 57 个报告类，0 failure/error/skip |
| 末轮契约补测 | PipelineDefinitionListContract、ImportEventArrivalController、LineageEvidenceResponseContract、TriggerProxyJsonContract 与 Console Trigger/Scheduler tests | 15 tests，0 failure/error/skip；包含与上行重复的定向复验，不累加为独立覆盖数 |
| 真实数据库/存储 IT | LineageEvidenceArchiveFallback、ImportIngressScanner、DispatchChannelHealthServiceIntegrationTest | 2 + 4 + 7 tests 通过；属于上述 345 tests |
| Java 构建质量 | 格式化后末轮 `./mvnw -DskipTests test-compile pmd:check spotless:check -fae -B` | 全 17 reactor 模块通过；未执行全量单测 |
| 守卫自测 | JCON、scope detector、suppression registry | 19 + 12 + 4 tests 通过 |
| JCON 增量与全量 | 相对 origin/main 与省略 base-ref 两种模式 | candidates=0，regressions=0 |
| OpenAPI | 路由一致性、breaking、前端 gen:api:check | 路由 400 一致，breaking 和生成漂移检查通过 |
| 前端 | typecheck、lint:check、job/triggers API 单测 | 24 tests、类型与 lint 通过 |
| 其他静态守卫 | Java logging、test conventions、Lombok/injection、readability、脚本治理、Shell、workflow lint | 已通过；Shell 按官方 warning 严重度门禁，不修改既有 info 提示 |
| 提交预检入口 | 使用独立临时 Git index 执行 `bash scripts/local/pre-commit-checks.sh` | 所有适用项通过；真实暂存区为空。测试约定守卫自测的 1 个历史基线用例按既有条件跳过，不计入后端 345 tests |
| 生成快照 | 独立 index 的 Lean LOC 与 Java 可读性清单生成及一致性检查 | 两者通过；包含新文件，不遗漏未跟踪的 DTO 和测试 |
| Sonar 首轮 | `reports/sonar/2026-10-07_14-52-03/` 增量报告 | 2 个 MINOR、0 hotspot；已修月份写法并按精确例外处理契约路由 |
| Sonar 末轮 | 独立项目 `file-batch-system-contract-governance-20261007`，`reports/sonar/2026-10-07_15-21-00/`，服务端 task `37daa7f7-24dc-4c9d-8ffa-d53556a79155` 完成 | 74 个 Java 变更文件的新增/修改行：0 OPEN issue、0 待审 hotspot；静态模式未采集覆盖率 |
| 交付合并复核 | 合并 suppression 精确例外与数量棘轮后，自测与全量扫描；许可证精确豁免测试；E2E shard 清单 | 7 个 suppression 自测通过，全量 243 条已登记；许可证测试通过，28 个 E2eIT 清单无漂移 |
| 新 E2E 注解测试 | `./mvnw -pl batch-e2e-tests -am -Dtest=E2eTestAnnotationTest -Dsurefire.failIfNoSpecifiedTests=false test -B` | 最新主线与依赖升级后的 16 个 reactor 模块编译通过，1 个注解契约测试通过；不是 28 个真实 E2E 的执行证据 |
| 前端提交预检复核 | `npm run preflight:changed`，显式检查本次全部变更路径 | 编码、注释、lint、类型、i18n、契约漂移、架构、维护性和 changelog 通过 |

测试运行日志和 Sonar 原始 CSV 在本机临时目录/忽略的 reports 中，不将日志、凭据或构建产物入库。

## PR CI 发现的边界回归

后端 PR #1160 的 `pr-gate` run `37592317281`、`unit-it-b2` job `112697081350` 在两个 Console 架构测试中失败：`BoundedContextDependencyArchTest` 与 `BoundedContextMigrationProgressTest` 均发现 23 条 `ops -> job` 直接依赖。此前 345 个定向用例未包含这两个全包架构扫描，不能作为跨域边界通过的证据。

本地复现使用 `-pl batch-console-api -am` 和 `boundedContext.report` 导出逐类清单，确认全部违规来自 `DefaultConsoleTriggerProxyService` 及其三个响应类型引用。`ConsoleSchedulerCommandResponse`、`ConsoleTriggerActionResponse`、`ConsoleTriggerStatusResponse` 同时由 Job Controller 与 Ops 代理消费，应归属于既有顶层 `application.contract.response.ops`，而非 Job 域的内部契约。

修复只迁移三个 DTO 及所有生产、测试引用，不新增旧包兼容别名，不调整架构测试、不增加豁免、不抬高基线；JSON 字段、时间类型、租户过滤、权限、读降级与写错误传播保持不变。

修复复验：`./mvnw clean test -pl batch-console-api -am -DskipITs=true -DboundedContext.report=/tmp/bfs-pr1160-boundary-after.tsv -B` 已完成并返回 0，日志 `/tmp/bfs-pr1160-console-after.log`。Console 的 1543 个用例、上游模块的 1091 个用例均无 failure/error，合计 7 个既有条件跳过，不计为通过用例；两个原失败的架构测试均通过，逐类 TSV 无违规记录。clean 构建排除旧包残留 `.class`；该命令不是全量 IT / E2E 验收。退出阶段出现 Surefire 等待 30 秒后回收 fork 的日志，未将其作为应用正常关闭的证据。固定契约全量守卫、模块依赖与 400 条 OpenAPI 路由一致性检查同时通过；当时新提交的远端 CI 尚待运行，后续合并及主线验证状态见本文状态核查。

## 合入后的安全与空值分析收尾

PR #1160 已合入。后续核查 CodeQL #268 的 SARIF（主线分析 `1906730816`）显示数据流从临时根目录经 GenerateStep 到 Excel 写流。生产入口已经预创建私有文件，且 Excel 不启用确定路径 checkpoint，因此不能将报告路径直接等同于已复现的生产泄露。格式策略自身仍允许默认权限创建及跟随符号链接；补测在修复前复现了新建/既存文件权限与符号链接三项失败，修复后全部通过。

同时检查 POI 5.5.1 实现，发现 SXSSF 的 sheet XML 和模板 XLSX 使用独立默认临时策略，并不继承最终输出的权限边界。默认 `poifiles` 目录若被其他本地用户抢占，文件创建后的重新打开存在条件性风险；未声称本机已遭攻击。现在以 `TempFile.withStrategy` 将整个工作簿生成、写入和关闭纳入线程局部私有策略，不修改全局 POI 策略或 `java.io.tmpdir`。输出创建经过 OwnerOnlyFiles，写流不带 CREATE 并使用 NOFOLLOW_LINKS；正常输出路径、格式和非 Excel checkpoint 不变。

Trigger 代理的两条新增 Sonar S2259 属于分析器不能识别自定义空值谓词的告警；改用 JDK 明确的响应信封归一化，不添加 suppression。真实 RestClient JSON 测试区分无响应、已有响应但 data=null、正常数据，保持原运维返回语义。

本轮定向 reactor 测试（`-pl batch-worker/export,batch-console-api -am`）共 54 个用例，0 failure/error/skip，日志 `/tmp/bfs-codeql268-final-tests.log`；覆盖 11 个 Excel 格式用例、21 个 GenerateStep 用例、7 个私有文件用例、13 个 Trigger 代理用例和 2 个架构用例。POI 用例调用真实创建方法，确认 XML/模板权限、删除、分页失败后的中间文件清理以及成功/失败后的线程策略恢复；不等同于完整 sim 或本地攻击竞态复现。本机未安装 CodeQL CLI，告警关闭需由新提交的 GitHub CodeQL 分析及主线扫描确认，未手工 dismiss。

## 未声称完成的验证

本轮全 reactor Sonar 分析已完成，服务端 task `9dfce324-6de0-458a-a76a-99edecf646c1`，报告 `reports/sonar/2026-10-07_17-05-10/`。4 个 Java 变更文件中，生产代码无 OPEN issue，之前两条 S2259 已消失，待审 hotspot 为零；测试文件发现 3 条 MINOR S5838。随后将三处 Path 父级断言改为 `hasParentRaw`，Excel 的 11 个用例再次通过（`/tmp/bfs-codeql268-assertions.log`），没有重复计入上述 54 个用例。修正后未再执行第二次全 reactor Sonar，因此不把该报告描述为增量零告警；GitHub Sonar gate 默认跳过，也不视为通过证据。PMD、Spotless、完整提交预检及正常推送 hook 均已通过。

- 当时撰写本节时，GitHub PR 检查尚未全部完成；截至 2026-10-08，PR 检查及本文顶部记录的 main Full CI、SIM/strict 已成功。仍未由这些记录证明生产环境验收、真实浏览器全流程或 staging 集群部署/故障演练。
- MockMvc/RestClient JSON 与 Testcontainers IT 是契约和投影证据，不等于真实长任务或生产高可用验收。
- 首轮全量 Sonar 的 OPEN S1192 候选为 236 个，末轮为 228 个；其中含文案、样例、SQL 和正常标签重复，不等于同数量的确认缺陷，不在本轮作为强制清零指标。末轮全量报告仍有 1076 个 OPEN issue，本轮只报告变更行增量为零，不声称全仓 Sonar 清零。新增代码的高置信同类协议键回退由 JCON-3 阻断，其他候选按原计划逐处人工判定。
- JCON 零候选仅指规则覆盖范围；它不证明所有嵌套 DTO、跨类常量或未登记有限域都不存在问题。

## 风险与交付约束

重点风险是序列化和 MyBatis 投影而不是调度状态机，已增加真实 Jackson/PG 保护。metadata 的驱动封装修正单独登记为 wire 规范化，不掩称全量 wire 完全相同。普通触发字符串与 dry-run 对象都保留，breaking gate 只对既有端点的该历史分支做精确澄清，不全局禁用类型/union 检查。

按主题分批提交，后端集中一个 PR，配对前端单独 PR。两批 suppression 守卫合并时保留精确路径例外与存量数量棘轮，并增加增量文件参数的回归测试；新增 E2E 组合注解测试补齐中文 DisplayName 和方法命名。实际 CI 通过前不把计划标为 Implemented，不自动合并。
