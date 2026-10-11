# Console 产品用户旅程改造方案

> 状态（2026-10-12）：TCFG-S1 与用户旅程阶段 1–4 的本地代码切片均已落地，并完成本地定向验证；这不代表 PR/Full Gate、真实四角色浏览器旅程或 staging 验收通过。代码仍在隔离工作树，未合并或发布。
> 当前实施基线：后端 `origin/main` @ `2c45899539f944088772bd6430ff0c359af6ee45`；前端 `origin/main` @ `2bacbdf29e589b171b80627cb176e724de1d67bd`。旧审计快照中的基线仅用于追溯，不代表当前代码。
> 关联审计：[产品用户旅程对抗审计](../audit/product-journey-adversarial-audit-2026-10-10.md)。

## 实施进度

| 切片 | 状态 | 已落地/已验证 | 尚未覆盖 |
|---|---|---|---|
| 实例取消反馈 | 本地实现 | 运行中实例显示“请求取消”，队列中实例显示“取消”；前端单测、类型检查与国际化检查通过 | 四角色浏览器实操、真实 API 状态回读和审计结果核对 |
| 重放预览绑定与超时恢复 | 本地实现 | 后端以 5 分钟、租户绑定、单次执行 token 固定规范化请求及完整候选/影响快照；提交与 token->session 关联同事务，24 小时内相同请求重试返回原 session；过期、参数变化、候选漂移、缺 token 和并发消费有测试；前端提交前校验 token/过期时间并分页展示候选；提交响应丢失时锁定原请求，使用同一 token 和新 HTTP 幂等键查询结果；4 项 Chromium mocked-API E2E 覆盖提交前预览、21 项分页/参数失效、结果版本选择及超时恢复 | 真实 API/DB 浏览器联测、四角色浏览器验收；其他批量运维入口及部分审批/失败路径仍未验收 |
| 配置包 SQL 预览 | 本地实现 | JSqlParser AST 限定单语句只读 SELECT/WITH；字符串/注释关键字、DML/DDL、多语句、写入型 CTE、SELECT INTO 和 FOR UPDATE 回归已覆盖，单元与 HTTP/Testcontainers 上传/预览集成测试通过 | PR/CI；仍需验证真实业务配置样例和角色授权，不以 API 测试替代四角色验收 |
| 文件笔数/金额对账 | 本地实现，主要本地故障恢复路径已验证 | trailer、manifest/数据集行数与金额超差均按硬门禁阻断；金额容差默认零；新增 ValidateStep 回归验证硬门禁不受坏记录跳过策略影响；同一唯一导入记录经真实 Import 写入业务 PG、Export 查询并写入 MinIO，再由 Dispatch 投递至隔离本地 outbox；另在连续链路中注入不可用目标，验证失败不误标交付、修正渠道后同一实例重试失败分片且只生成一个信封；缺省外部请求号现在按租户/流水线实例/文件/渠道稳定生成，HTTP 重试发送相同 `Idempotency-Key` | HTTP 接收端必须实际持久化并执行该键去重；邮件等不提供幂等协议的外部渠道仍不能保证 exactly-once；外部渠道成功后的响应丢失真实联测、NAS/HTTP 和多文件/大文件端到端交付仍未完成 |
| 配置包场景模板 sheet 投影 | 本地实现 | HTTP/Testcontainers 测试对 IMPORT/EXPORT/PROCESS/DISPATCH/ATOMIC/WORKFLOW/SCHEDULE/IMPORT+EXPORT/ALL 验证 sheet 纳入/排除，并逐 sheet 核对全部列名及顺序；同一场景矩阵工作簿继续上传并通过预览。另以规格单测锁定 IMPORT/EXPORT 文件模板字段顺序 | 真实浏览器下的登录角色、下载交互和用户帮助文案仍未验收；不把 API 工作簿验证等同于浏览器角色旅程 |
| 任务式侧栏导航 | 本地实现 | 保留 7 个既有组、路由和权限，仅调整中英文组名；Vitest 守护分组和关键入口，Chromium mocked-API 验证运行与告警跳转 | 四角色真实账号任务发现性、窄视口与可访问性验证 |
| 作业创建向导 | 本地实现 | 六步流程：基本信息、调度、资源、触发、可选关联、确认；文件模板/工作流/告警关联收在同一步的分区中，保留专家 JSON 配置与原创建 bundle 契约；18/18 Chromium mocked-API 旅程通过 | 真实登录角色浏览器旅程和生产资源策略验收 |

此前本地复跑结果：Maven reactor 定向运行 `BatchDayReplayServiceTest`、`BatchDayReplayOutputsOnlyIntegrationTest`、`BatchDayDryRunSchemaIntegrationTest` 共 33 项，0 失败/错误/跳过；集成测试使用 Testcontainers PostgreSQL 17.11、Kafka、MinIO、Valkey，从空库执行 Flyway 至 V231，并验证响应丢失后用相同凭证重试会返回原 session、不重复创建 entries。`check-migration-safety.sh origin/main`、`check-db-comment-coverage.sh origin/main`、OpenAPI 路径与破坏性变更检查、后端 `spotless:check`、`git diff --check` 均通过。该证据不等同于 PR/Full Gate、四角色浏览器验收或生产环境验证。

## 目标

改造 Console 的用户旅程，使四类授权角色能按其职责完成批量作业配置、触发、运行监控、异常处置和结果核对。系统负责可推断的默认选择、重复操作和安全校验；专业操作人员仍能理解状态机、业务日、影响范围和数据副作用，并在授权范围内精确控制。

本方案针对的是“系统选择、用户确认和结果反馈”的设计质量，不以减少页面、字段、按钮或代码量作为成功指标。不得压缩已有合法业务场景，不得用模糊自动化隐藏风险，也不通过前端显隐代替后端授权。

本地增量验证记录（2026-10-12，后续进展见下）：重放预览 Chromium mocked-API E2E 最初 2 项通过；该结果仅验证 UI 对预览前置、清单分页和参数变化失效的响应，不替代真实 API/DB 和角色授权验收。SQL 预览 AST 校验 20 项及导入对账门禁定向测试通过，Spotless 与 `git diff --check` 通过。该组结果尚未推送，且不代表 PR/Full Gate、完整 SIM、真实文件交付或四角色旅程验收。

**当前验收边界（2026-10-12）**：阶段 1–4 和 TCFG-S1 的本地实现及本地可执行验证已完成；不是完整产品发布签收。Console Chromium 使用 API stub；后端配置包 HTTP/Testcontainers 和 Import→Export→Dispatch E2E 使用真实服务容器。尚未完成真实登录的四角色全旅程、目标环境外部下游持久化幂等验证、NAS/HTTP 生产渠道和 staging 发布验收。需要依赖真实租户账号或目标环境的项目列为后续环境验收，不再作为本地编码缺口。

**独立工作树进展（2026-10-12）**：

**最新复验结果（2026-10-12）**：后端 SQL AST、JWT/CSRF 创建权限和配置包 HTTP/Testcontainers 上传预览共 51/51 通过；真实 worker 故障路径 `ImportFailurePipelineE2eIT` 2/2、`DispatchFailurePipelineE2eIT` 1/1，以及 Import→Export→Dispatch 连续链路 `ImportToExportDeliveryE2eIT` 1/1 通过。前端 Chromium mocked-API 旅程 13/13、相关 Vitest 8/8、typecheck、i18n、变更文件 ESLint/Prettier、生产构建通过。运行后 Testcontainers 容器已退出。Console 实例写请求的前端自动幂等键与后端类级 `@Idempotent` 已核实。该证据仅证明这些定向测试；真实四角色完整浏览器旅程、移动/键盘可访问性和 staging 发布验收仍未完成。

- 阶段 1：作业实例取消反馈、当前请求重放预览绑定/分页、批量重试逐项结果已有本地实现；配对前端 Chromium mocked-API 旅程 E2E 最新单文件结果 13/13 通过，覆盖分页/预览失效、请求构造、取消反馈、批量部分失败和 TENANT_ADMIN 平台资源字段隐藏。真实 API 的重放/取消结果回读、真实租户角色和审计仍未覆盖。
- 阶段 2：作业定义列表以单作业向导为主入口，熟练用户快速表单仍可从次级菜单进入；向导收敛为六步，将文件、工作流、告警放入可选关联步骤的分区，同时保留高级 JSON 配置和原 bundle 契约。Chromium mocked-API E2E 验证步骤顺序、关联配置和五类 Worker 类型均可提交并保留选择结果。后端 HTTP/Testcontainers 覆盖场景 sheet 投影、五类 Worker 创建及资源字段/只读角色拒绝；JWT/CSRF IT 覆盖创建权限。真实登录四角色浏览器旅程及生产资源策略仍属于目标环境验收。
- 阶段 3：批量重试逐项反馈成功/失败及错误原因；取消提示继续区分“请求受理”和最终状态；重放范围/动作显示业务文案。尚缺多角色真实 API、并发运维冲突及真实审计闭环证据。
- 阶段 4：配置包 SQL 预览 AST 校验和启用式记录数/金额硬对账门禁已实现；容差默认严格为零，缺少核对依据或超差阻断。连续链路真实容器 E2E 覆盖 Import 写入业务 PG、Export 生成并校验 MinIO 对象、Dispatch 更新同一 `file_record`、回执/审计和本地 outbox；又通过故障注入验证 Dispatch 本地目标失败不误报交付、配置修复后在同一实例重试失败分片，最终成功且仅生成一个信封。缺省外部请求号现按租户/流水线实例/文件/渠道稳定生成，HTTP 重试发送相同 `Idempotency-Key`；这依赖下游实现该协议，SMTP 等渠道仍是至少一次/结果需人工核对的边界，不宣称 exactly-once。真实外部下游丢响应、NAS/HTTP 部署环境及大/多文件连续交付仍未验收。
- UX-07：配置包直接菜单仍隐藏，但工作台新租户引导和作业定义页已有情境入口；前端保留 7 组既有路径/权限并将组名改为任务语义，Vitest 和 Chromium mocked-API E2E 检查入口、工作流/告警跳转。四角色首次/日常任务的发现性、窄视口和可访问性尚未验收，不新增重复菜单。UX-10：批次日重放已提供作业与结果版本搜索选择；只展示可提升的 `PENDING` 版本，手工 ID 保留在高级区，重复版本 ID 在请求构造时去重。配置包场景测试现已断言各场景 sheet 纳入/排除，但实际浏览器下载后的列集合与用户说明仍需核对；阶段 5 的真实服务、四角色、审计/持久化结果验收未执行。

本轮验证证据（2026-10-12）：前端 `npm run typecheck`、变更文件 ESLint、`npm run check:i18n`、`npm run build`、`npx vitest run src/utils/batchDayReplayForm.test.ts`（8 项）通过；`batch-day-replay-journey.spec.ts` 后续增量扩至 9/9，使用 API stub 且跳过全局 seed/teardown。工作台到配置包入口额外断言页面标题可见，且无缺失 i18n key 或 `Download` 未定义警告。后端 `./mvnw -pl batch-console-api,batch-worker/import -am -Dtest=ConfigPackageExcelValidatorTest,ControlTotalEvaluatorTest,DatasetRuleEvaluatorControlRecordTest,DatasetRuleEvaluatorManifestRowCountTest,ValidateStepTest -Dsurefire.failIfNoSpecifiedTests=false test -ntp` 通过。以上均为本地隔离工作树证据，不等同于 PR/CI、真实服务联测或阶段验收。

**前端向导结构回归（2026-10-12，已被后续六步实现取代）**：较早的 mocked-API Chromium 结果验证了旧八步向导，13/13；之后为降低不适用步骤的认知负担，向导收敛为六步。最新 E2E 18/18 覆盖六步结构、五类 Worker 请求构造和专家 JSON 配置进入 bundle。均为页面交互证据，不替代真实 API/RBAC 验收。

**独立工作树复验（2026-10-12）**：后端曾运行 `./mvnw -ntp -pl batch-console-api -am -Dtest=ConfigPackageExcelValidatorTest,ConsoleTenantConfigPackageExcelUploadIntegrationTest,ConsoleJobDefinitionAuthenticatedJourneyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，51/51 通过（SQL AST 单测 20、真实认证 JWT/CSRF 四角色创建授权 1、Excel 上传/预览 HTTP/Testcontainers 30；0 失败、0 错误、0 跳过）。随后扩展配置包 Apply 授权测试：`./mvnw -ntp -pl batch-console-api -am -Dtest=ConsoleTenantConfigPackageAuthenticatedIdempotencyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，1/1 通过；真实 JWT/CSRF 请求验证 `TENANT_ADMIN` 可 Apply，`AUDITOR`、`TENANT_USER` 和其他租户的 `TENANT_ADMIN` 被拒绝，拒绝请求没有写入配置，且成功请求的同键重放仍被幂等保护。Testcontainers PostgreSQL/Kafka/MinIO/Valkey 启动并通过该测试。前端 `E2E_SKIP_GLOBAL_SETUP=1 E2E_BASE_URL=http://127.0.0.1:5175 npm run test:e2e -- e2e/batch-day-replay-journey.spec.ts --project=chromium --workers=1 --retries=0`，16/16 通过；另有 `navigation.spec.ts` 和该旅程文件合计 Chromium mocked-API 16/16、相关 Vitest 17/17，以及 `npm run typecheck`、`npm run build`、`npm run check:i18n`、相关 ESLint/Prettier 通过。后端 `./mvnw -ntp -pl batch-e2e-tests -am -Dtest=ImportToExportDeliveryE2eIT -Dsurefire.failIfNoSpecifiedTests=false test` 也通过（Testcontainers 1/1，reactor BUILD SUCCESS）。未运行带有共享 ta/tb/tc seed/teardown 的前端真实登录 suite；该隔离浏览器验收和 staging 发布验收仍不在本轮本地通过证据内。

**真实服务定向 E2E（2026-10-12）**：在后端独立工作树运行 `./mvnw -pl batch-e2e-tests -am -Dtest=ImportPipelineE2eIT,ExportContentVerificationE2eIT,DispatchPipelineE2eIT -Dsurefire.failIfNoSpecifiedTests=false test -ntp`，Testcontainers 使用 PostgreSQL 17.11、Kafka、MinIO、Valkey，5/5 通过（Dispatch 2、Import 2、Export 内容校验 1）；运行结束后 Testcontainers 容器数为 0。该结果证明三类 worker 的独立真实 E2E 成功，不证明同一输入文件跨 Import→Export/Dispatch 的连续业务旅程，也未覆盖真实 Console 四角色授权、失败恢复或 staging。

**重放提交未知结果恢复（2026-10-12）**：补齐前端真实网络故障语义：提交无 HTTP 响应时保存不可变请求快照并锁定编辑/关闭入口，提示服务端可能已受理；“查询提交结果”对现有 submit API 重发同一业务请求和 preview token，但显式使用新的 `Idempotency-Key`，让后端单次 preview-token 消费逻辑返回已有 session，或在首次请求未到达服务端时完成唯一提交。连续网络错误保留查询状态；成功后打开会话详情。前端 Chromium mocked-API 回归已验证首次响应丢失、同请求体/token、幂等键变化及会话结果展示。后端 `BatchDayReplayOutputsOnlyIntegrationTest` 已有真实 PG 集成断言：同一提交命令重复执行返回同一 session 且 entry 数不增加；新加的 Console JWT/CSRF 幂等授权 IT 1/1 通过。当前完整前端旅程 E2E 为 14/14，typecheck、i18n、变更文件 ESLint、build 和相关 Vitest 17/17 通过。真实 Console/Orchestrator API 联测、浏览器重载期间的不确定结果恢复和四角色验收仍属未验证项；不得将 mocked 浏览器测试描述为真实服务验收。

**Import→Export→Dispatch 连续交付 E2E（2026-10-12）**：在同一 Testcontainers 环境启动真实 Import、独立 Export 和 Dispatch worker；Import 将唯一业务记录写入 PostgreSQL 业务库，Export 按批次条件查询并生成 MinIO 对象，下载校验内容后将同一 `file_record` 交给 Dispatch。验证任务 SUCCESS、文件状态 DISPATCHED、回执、审计记录和 `@TempDir` 隔离 outbox 信封。最终 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，Maven reactor `BUILD SUCCESS`。同轮另复跑 `DispatchFailurePipelineE2eIT`，缺失渠道时任务 FAILED 且文件未标记 DISPATCHED。两条证据分别验证连续成功和独立失败收敛，不代表真实 SFTP/NAS/HTTP 外部投递、成功链路中的下游不确定结果恢复或重复投递；四角色真实授权/审计和 staging 仍未验证。

**外部渠道真实投递复验（2026-10-12）**：在后端隔离工作树运行 `./mvnw -pl batch-worker/dispatch -am -Dtest=DispatchExternalChannelIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test -ntp`，Testcontainers PostgreSQL/Kafka/Valkey/MinIO/SFTP 与 GreenMail SMTP 均启动；MinIO、SFTP、SMTP 三条真实渠道分别验证投递结果和远端/对象/邮件内容，`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`，Maven reactor `BUILD SUCCESS`。SFTP 使用 `atmoz/sftp:alpine` amd64 镜像，在 Apple Silicon Docker 上通过仿真运行并有性能警告。本测试是 Dispatch 渠道适配器级证据，不代表 Import→Export 连续旅程直接投递到外部 SFTP/NAS/HTTP，也未验证外部成功结果未知后的安全重试/重复投递；NAS 与 HTTP 渠道仍无本轮实测证据。

**对账失败不落业务数据复验（2026-10-12）**：后端独立工作树运行 `./mvnw -pl batch-e2e-tests -am -Dtest=ImportFailurePipelineE2eIT -Dsurefire.failIfNoSpecifiedTests=false test -ntp`，2/2 通过。该 E2E 使用真实 PostgreSQL/Kafka/MinIO/Valkey 链路，在声明笔数/金额与实际记录不一致时验证实例失败，并直接查询业务表确认拒绝记录没有写入；这只证明导入侧对账门禁及失败路径，不等同于 Import→Export/Dispatch 连续交付联测。

**前端旅程增量复验（2026-10-12）**：在配对前端独立工作树运行 `E2E_SKIP_GLOBAL_SETUP=1 E2E_BASE_URL=http://127.0.0.1:5175 npm run test:e2e -- e2e/batch-day-replay-journey.spec.ts --project=chromium --workers=1 --retries=0`，9/9 通过。新增覆盖 TENANT_ADMIN 不展示平台队列/Worker 池字段；该测试使用 mocked API，不替代真实后端 RBAC。此前 8/8、7/7 均为新增用例前的历史结果，最新结果以 9/9 为准。

**阶段 1–3 与导航复验（2026-10-12）**：前端独立工作树运行 `E2E_SKIP_GLOBAL_SETUP=1 E2E_BASE_URL=http://127.0.0.1:5175 npm run test:e2e -- e2e/navigation.spec.ts e2e/batch-day-replay-journey.spec.ts --project=chromium --workers=1 --retries=0`，14/14 通过。导航和旅程测试使用隔离 API mock，不依赖真实登录文件或全局 seed；覆盖工作流/告警侧栏跳转、命令面板、五类 Worker 类型提交、重放、批量重试部分失败、取消/终止反馈和配置包多场景模板下载交互。该证据仍是 Chromium 单视口/API mock，TENANT_ADMIN 仅在前端模拟，不能替代真实后端 RBAC、审计和可访问性验收。

**取消/终止反馈补充（2026-10-12）**：新增真实 Chromium mocked-API 用例验证 RUNNING 实例强制终止的不可逆提示、确认请求、重新读取 `TERMINATED` 终态；与取消受理和既有导航/旅程用例一并复跑为 13/13。前端 `npm run typecheck` 与本次变更文件 ESLint 通过。API 使用 mock，因此真实 Worker 中断、真实租户授权和审计记录仍未验收。

**配置包场景 sheet 集成复验（2026-10-12）**：后端独立工作树运行 `./mvnw -pl batch-console-api -am -Dtest=ConfigPackageWorkbookGuidanceTest,ConsoleTenantConfigPackageExcelUploadIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test -ntp`，49/49 通过（规格单测 19、真实 HTTP/Testcontainers IT 30）。测试对 IMPORT、EXPORT、PROCESS、DISPATCH、ATOMIC、WORKFLOW、SCHEDULE、IMPORT+EXPORT 与 ALL 样例模板断言场景 sheet 的包含/排除；规格单测核对 IMPORT/EXPORT 文件模板精确字段顺序，HTTP 下载用例验证 IMPORT+EXPORT 工作簿中的关键配置列且不暴露平台资源列。仍未验证所有场景的全列矩阵、浏览器端下载体验或四角色权限。

**最终本地定向复验（2026-10-12）**：后端在独立工作树运行配置包校验/模板/HTTP 上传预览、作业创建与实例操作权限、幂等集成测试合集，85/85 通过，覆盖真实 Testcontainers PostgreSQL 17.11、Kafka、MinIO、Valkey；另扩展 Apply 权限/幂等 IT 1/1 通过。前端独立工作树运行重放/向导/导航 Chromium mocked-API E2E 18/18、Vitest 17/17、typecheck、变更文件 ESLint、i18n 检查、Prettier 和 `npm run build` 全部通过。新增定向验收覆盖 390px 移动端的桌面端提示与移动导航、820px 下向导和重放页无页面级横向溢出、指定 WCAG axe 规则无 critical/serious 问题，以及侧栏键盘 Enter 导航；修复共享作业编码组件中领域选择框缺少可访问名称。`git diff --check` 两个工作树均通过。上述结果证明本地代码切片和有限的视口/键盘/可访问性测试，不代表全 Console 无障碍验收、真实登录浏览器旅程、完整 SIM、PR/Full Gate 或 staging 签收；跨租户审计全旅程、外部渠道未知结果恢复及大/多文件真实交付仍未验证。

**Dispatch 幂等键补强复验（2026-10-12）**：`PrepareDispatchStepTest`、`HttpDispatchChannelIdempotencyTest`、`DeliverDispatchStepTest`、`RetryDispatchStepTest` 合计 32/32 通过；本地 HTTP 接收端先按幂等键记录处理结果，再故意延迟首个响应，重试使用相同 `Idempotency-Key` 且业务副作用计数仍为 1；另验证含 CR/LF 的业务请求号不会注入请求头。该接收端状态仅保存在测试内存，不代表真实下游持久化能力。随后重跑 `ImportToExportDeliveryE2eIT`、`ImportFailurePipelineE2eIT`、`DispatchFailurePipelineE2eIT`，4/4 通过，Maven reactor `BUILD SUCCESS`，Testcontainers 容器退出。稳定请求号只支持下游实现该协议后的去重，不等于远端 exactly-once；本轮没有真实外部下游持久去重验收。

## 角色画像与责任边界

以下画像以现有 `ConsoleRoles` 和 [Console API 角色矩阵](../api/console-api-protocol.md#role-permission-matrix) 为授权基线。角色可以由同一自然人兼任，但任务和权限仍按当前认证上下文执行。第一阶段必须核对前端菜单、页面动作、后端授权和审计是否一致；未经业务/安全决策，不扩大权限。

| 角色 | 产品画像 | 主要目标 | 当前权限边界与产品责任 |
|---|---|---|---|
| `ROLE_ADMIN` | 平台管理员/平台批量运维 | 跨租户治理、资源和账号管理、审批及高危运行处置 | 跨租户写操作需显式目标租户、影响预览与审计；不把平台物理资源设置交给租户 |
| `ROLE_AUDITOR` | 跨租户只读审计员 | 追溯配置、操作、执行结果及责任主体 | 全程只读；提供完整筛选、关联、时间线和导出证据，不渲染可执行写操作 |
| `ROLE_TENANT_ADMIN` | 租户作业管理员/配置负责人 | 维护本租户获准的作业配置、用户/通知配置，理解依赖与运行结果 | 只能管理租户业务语义和获准设置；平台资源归平台；流程需在入口处说明审批/交接边界 |
| `ROLE_TENANT_USER` | 租户批量作业操作员 | 触发获准作业、跟踪状态、查看文件和执行结果 | 当前可触发作业并查看状态，不能修改配置或执行多数运维动作；页面须给出可执行的下一步或责任角色，不暗示无权能力 |

四角色不是四个互斥的用户性格模型。租户管理员和作业操作员都可能是熟悉账期、补数、重跑及文件交付的专业人员。界面应按权限与当前任务提供上下文，不使用“普通用户/专家用户”简单二分，也不把专家选项藏到不可发现的深层入口。

## 设计原则

1. **系统代办可推断选择**：根据作业类型、现有配置、权限、业务日和运行状态提供默认值。显示默认来源、适用范围和覆盖条件。
2. **保留完整场景表达力**：Import、Export、Process、Dispatch、Atomic、工作流、账期补跑、坏行策略、恢复策略及精确目标选择等合法能力不能因 UI 简化而删除。
3. **只对业务结果有歧义的选项询问用户**：例如账期/时区/冲突策略/结果版本若无法从上下文可靠推断，应明确询问；普通格式默认值、平台队列、Worker 分组不应要求租户猜选。
4. **风险随操作显示**：在执行前说明对象、范围、当前/目标状态、是否可撤销、中间数据副作用、重复执行风险、系统代选项及其依据。
5. **请求受理不等于业务完成**：提交、取消请求、重试受理、实例终态和数据核对使用不同反馈状态；从 API 返回内容和状态事件驱动 UI，不用乐观 toast 伪造完成。
6. **专业效率优先于表面简洁**：提供可搜索的业务键/作业名/账期过滤、批量处理、键盘可达、部分失败逐项结果和直接到对象的链接。渐进展示可减少噪声，但不能降低可发现性。
7. **所有角色路径都以后端授权为准**：菜单、按钮、路由、API、审计和错误反馈一致；未授权动作在入口前解释限制，不靠最后一步 403 教用户权限模型。

## 已确认的产品决策

- 2026-10-11：保留当前配置包发布权限，`TENANT_ADMIN` 可对本租户配置包直接 Apply，`ADMIN` 仍可执行平台侧操作；本轮不增加平台审批步骤。租户资源和平台运行参数仍由服务端授权边界隔离。
- 2026-10-11：文件/账期对账超过适用容差时阻断交付，不以“仅告警”代替业务门禁。金额容差按作业配置；未配置时采用严格零容差，只有金额和笔数均完全一致才允许交付。必须在配置预览中展示实际生效值，并在服务端执行同一规则；本地测试不能替代目标环境的真实文件到交付联测。

## 当前证据与范围边界

- 原始用户旅程审计记录 UX-01 至 UX-11；其中固定八步向导、重放预览截断和取消/终止文案等发现已在本工作树更新或修复，历史问题描述以审计文件中的“已修复”标记为准。
- “四角色是否能完成每项业务任务”尚未通过四角色账号逐项浏览器验证；RBAC 协议矩阵是权限证据，不等同于任务体验验收。
- 配置包字段投影、资源归属、Excel 兼容、Job/Template/SLA 所有权及迁移风险，继续以[租户作业配置与资源边界收敛计划](./tenant-job-configuration-simplification-plan-2026-10-09.md)为权威。本方案不得重复定义配置 schema，也不得先行改数据库宽表。
- 运行页面本地浏览器观察曾遇到 Console API 连接失败，不能把缓存/残留页面数据当成联测成功。运行环境可用后再执行行为验证。
- 本轮没有证明所有 Worker 场景或完整生产日批结果正确；之前的 E2E、SIM、API 测试需逐项对照用户任务和业务数据结果，不得直接等同于产品旅程通过。

## 实施阶段

阶段按依赖顺序推进；每个切片一个可审查、可回滚目标，不要求一阶段一个大 PR。阶段 0 角色/契约盘点完成后，可并行开展不冲突的原型和测试数据准备；行为实现仍按阶段门推进。

### 阶段 0：角色、任务与权限事实源

**交付**

- 固化四角色的任务清单和页面/API/权限矩阵，覆盖查看、创建/配置、触发、暂停/取消、重试/重放、审批、导出、审计。
- 每项任务标注责任角色、租户范围、危险等级、前置条件、系统可推断信息、必须由人确认的信息、失败责任人。
- 以现有 OpenAPI、后端注解/服务校验、`/auth/me` 菜单和前端实际页面进行对照，列出权限漂移和入口不可达项。
- 确定主要业务用户熟悉批量作业概念这一画像约束；不能以“新手引导”作为删减高级能力的理由。

**验收门**

- 每个当前正式角色至少有一个可实操的核心任务旅程；所有角色权限均可追溯到权威后端规则。
- 未授权、角色交接、跨租户目标和混合角色四种情况有明确预期，不存在“UI 看得到但 API 必拒绝且无交接”的断点。
- 任何权限扩张都单独列为决策，不在 UX 重构中顺便开放。

### 阶段 1：高风险操作与真实状态闭环（P0）

**范围**：实例取消/强制终止、重放/补跑预览、批量重试/取消、长请求超时和部分成功反馈。

**改造**

- 校正“请求取消”“强制终止”“已取消”“已终止”的术语和状态反馈。运行中 cancel 显示请求受理、取消任务数和等待最终态；terminate 显示不可逆副作用和最终状态。
- 重放/恢复提交必须绑定当前完整请求的有效预览，参数变化、数据版本变化、预览过期或权限变化都必须重新预览/授权。
- 影响清单显示完整对象，可分页、筛选或导出；摘要明确包含、排除、重复结果风险和截断数量。
- 批量操作逐项显示成功、冲突、拒绝和失败原因；总成功数不能掩盖部分失败。请求超时后提供“查询处理结果”而不是鼓励盲目重复提交。
- 强制核对 API 响应、审计事件、实例/分区终态和用户可见提示语义一致。

**验收门**

- 状态机每一合法/非法前置状态都有角色化行为测试；cancel 请求受理不被展示成已停止。
- 重放缺少有效预览、预览后参数变化、实例并发变化、重复提交、部分审批/部分失败均被覆盖。
- 影响数量和清单可对账；用户可以在页面中检查完整目标，而不是只看前几条样例。

### 阶段 2：配置与作业创建的系统决策（P1）

**范围**：单作业创建、多个场景组合配置、资源路由、配置预览和权限交接。

**改造**

- 以作业意图选择场景；系统按场景生成最小必需输入和安全默认，并解释默认来源。
- 单作业快速创建与多场景批量维护是用户任务差异，不让用户先选择内部实现（表单/Excel/API）；熟练操作人员仍可快速打开全部适用字段。
- 队列、Worker 池、容量、QPS、物理分片资源由平台负责。若后续确认租户需要 QoS 选择，应提供有限、可解释的服务等级，而不是任意资源编码。
- 配置预览按业务对象展示新增/变更/保留/清除、引用解析、版本和影响；MERGE 与恢复/替换表达清楚，不暗示未出现即删除。
- 租户无权完成的平台审批或发布，应在开始前说明由谁接手、当前进度如何保留、如何追踪；不能让用户上传并修正完才遇到拒绝。
- 完整配置快照与按场景模板分别满足备份迁移和日常维护；不为了模板短而损失导出/恢复能力。

**验收门**

- 按既有配置简化计划的字段矩阵运行五类 Worker 及文件格式组合；每个合法场景都能配置，当前任务无关字段不要求阅读/填写。
- 新作业可由系统推导的值具有服务端事实源、前端解释和契约测试；不能安全推断的业务语义有明确用户确认。
- 上传、预览、修复、权限交接、apply、发布至少按 `ADMIN`、`TENANT_ADMIN` 和只读角色验收；`TENANT_USER` 的可见/可触发路径按现有矩阵验证。
- Excel/API/前端字段生成一致，旧包行为和回滚以配置简化计划的兼容门槛为准。

### 阶段 3：日常批量运行与异常处置（P1）

**范围**：作业触发、批次日、任务/分区监控、失败诊断、重试/恢复、审计。

**改造**

- 从“按技术模块找页面”转向以任务入口串联：准备/校验、运行、处理异常、核对结果、查看审计；保留适用于资深人员的直接对象列表和筛选。
- 操作台展示租户、job、bizDate、attempt、依赖/等待原因、任务分片、最新有效结果和关键时间线；筛选与排序支持批次级操作，不要求手动拼 ID。
- 对触发、暂停、恢复、取消、重试、补偿和重放区分语义；给出哪些动作当前角色可做、哪些需要平台处理及原因。
- 对 defer、quota、no-task、依赖未就绪、worker 不可达、背压和超时提供可行动归因，避免只显示内部错误码。
- 保留精确 trace/id 搜索作为专家工具，同时提供从作业/账期/租户/文件到关联 trace 的路径。

**验收门**

- 以四角色执行典型和对抗旅程：正常批次、依赖晚到、部分失败只重试失败项、重复请求、请求超时、并发运维冲突、配置版本变化。
- 每项终态、分片结果、审计事件与页面聚合一致；非终态及“仍在取消中”等状态有明确更新时间和下一步。
- 角色无法执行的动作不显示为可用主按钮；如需提权/平台协助，页面提供明确交接信息而非只报 403。

### 阶段 4：文件交付、账期结果与对账闭环（P1）

**范围**：Import、Export、Dispatch 及其真实文件、Manifest、计数/金额核对和恢复路径。

**改造**

- 以“哪个业务日/哪批输入/哪份产物/交付到哪里”为主线关联实例、attempt、文件、分片、Sidecar/Manifest 和对象存储产物。
- 对输入/输出记录数、声明总额与实际总额、坏行/跳过阈值、校验和、分片合并和外部交付状态给出逐项结果。系统能确定的数据自动比对；业务容差或冲突策略需有明确来源，不能猜。
- 文件未完整、multipart 中止、对象存储写入不确定、下游响应超时、重复投递等情况要准确区分“未交付/结果未知/已交付”，提供幂等恢复指引。
- 大文件/多文件和非 UTF-8、坏行定位、断点恢复等专业场景保持可用；易用性改造不能只覆盖小样例。

**验收门**

- 以真实 worker、真实 PG/Kafka/对象存储本地容器环境执行至少一条完整成功路径和每类主要失败恢复路径。
- 业务数据逐行或按明确聚合口径对账，含文件数、记录数、分片数及金额/笔数规则；不能只看 job `SUCCESS`。
- 对重试和部分交付检查不重复出账/重复发送，或能够明确识别并处置“下游结果未知”。

### 阶段 5：四角色端到端验收与发布（P0 发布门）

**交付**

- 建立 `角色 × 旅程 × 状态 × 视口` 验收矩阵，含角色菜单/权限、成功、空态、校验错误、部分成功、超时/重连和恢复后状态。
- 对关键流程采集浏览器证据；每个角色在真实 API 和真实本地服务上完成对应授权的操作，并核对业务表/对象存储/审计最终结果。
- 运行现有前端单测、typecheck、lint、Playwright/E2E、后端相关单测/IT、适用 SIM/严格验收；新增测试挂在对应工作流和门禁中，不能以截图取代业务校验。
- 更新用户帮助、运行手册和变更说明；把产品能力和未支持/需平台协助情形明确告诉用户。

**发布门槛**

- 四类角色的核心旅程均有明确通过/失败证据；不能以管理员单角色成功代表租户体验通过。
- 关键业务结果和权限边界通过，所有 P0 未决项清零；P1 风险有负责人、影响、上线限制和复审日期。
- Console 前后端可稳定运行；本地容器化依赖、应用进程、种子数据和测试租户状态均记录，错误环境不能报告成功。
- 任何跳过/外部依赖不可用/浏览器受阻的场景均单独列出，不能从验收分母中静默删除。

## 优先级与范围控制

| 优先级 | 范围 | 说明 |
|---|---|---|
| P0 | 阶段 0 角色/权限事实；阶段 1 状态真实性与高风险预览；阶段 5 上线验收门 | 直接影响权限、重复业务副作用和运维人员对执行状态的判断 |
| P1 | 阶段 2 配置默认与场景表达；阶段 3 日常运行恢复；阶段 4 文件/账期对账 | 补齐用户从准备到结果确认的业务闭环，不等同于单纯界面美化 |
| 暂不纳入 | 重做 RBAC/引入新角色、重写调度核心、数据库宽表大拆分、通用工作流平台、仅为减少按钮而删能力 | 当前审计无证据支持其作为解决体验问题的必要手段；出现权限/性能/模型证据时单独评估 |

实现应按最小完整用户任务切 PR。界面、OpenAPI 和权限同一用户闭环需要同步，但不把五类 Worker 的所有行为塞进一张巨型 PR。高风险状态机先加后端契约/状态测试，再改 UI；数据库改动遵循向前迁移、回滚和业务数据对账规则。

## 总体验收指标

不用“页面更少/字段更少”作 KPI，使用以下可验证指标：

- **任务完成**：每个角色可完成的核心任务比例；角色交接后是否能续接已完成工作。
- **决策正确**：系统默认的来源、覆盖和不适用边界可见；无法推断的业务语义没有静默猜测。
- **状态可信**：已受理、运行中、终态、部分成功、结果未知能被区分；页面状态与后端/业务产物一致。
- **风险可见**：高风险动作在提交前能检查完整影响；数据副作用和恢复责任明确。
- **完整场景**：既有合法 Worker、文件格式、账期、重跑和失败恢复场景均保留并通过代表性验证。
- **权限一致**：菜单、按钮、API、服务校验、审计 actor/tenant 和结果反馈相互一致。

## 回滚与变更控制

- 产品流程变更优先前后兼容；接口、角色授权、状态机或文件契约变化必须有 OpenAPI/i18n/前端生成类型及后端测试同步。
- 不以临时隐藏导航作为权限修复；若临时限制某功能，必须同时有服务端拒绝、说明、责任人和恢复条件。
- 对新默认值保留日志/审计可追溯，不默默重写既有作业；改变已有调度或业务结果语义须独立迁移决议。
- 每个 PR 说明所支持的角色、旅程、已验证状态、未验证项和回滚方式。Full Gate 通过不替代本地业务结果对账。

## 新增故障恢复证据（2026-10-12）

`ImportToExportDeliveryE2eIT` 在 Dispatch 阶段先将本地 outbox 目标指向类型冲突路径，真实 Worker 执行失败并确认输出文件仍未进入 `DISPATCHED`；随后修正渠道目标，在原 job instance 上调用 `retryFailedPartitions`，等待相同实例终态成功，并核对回执、审计和隔离 outbox 中恰好一个 envelope。运行 `./mvnw -pl batch-e2e-tests -am -Dtest=ImportToExportDeliveryE2eIT -Dsurefire.failIfNoSpecifiedTests=false test -ntp`，1/1 通过，Maven reactor `BUILD SUCCESS`。这是本地文件渠道的确定性失败/恢复证据；没有模拟“外部系统已接受但响应丢失”的不确定结果，不可据此宣称所有外部投递重试均已证明 exactly-once。

## 真实认证角色权限补测（2026-10-12）

新增 `ConsoleJobDefinitionAuthenticatedJourneyIntegrationTest`，关闭 `batch.security.bypass-mode`，使用四类 JWT、CSRF Cookie/Header 和真实 HTTP/Testcontainers。ADMIN/TENANT_ADMIN 创建作业成功，DB 中仅有对应租户记录，`console_operation_audit` 中有两条成功 `jobDefinition.create` 事件；AUDITOR/TENANT_USER 请求返回 403 且无作业落库。运行 `./mvnw -pl batch-console-api -am -Dtest=ConsoleJobDefinitionAuthenticatedJourneyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test -ntp`，1/1 通过。此为真实后端认证/授权/审计证据，仅覆盖创建动作，不等同于四角色浏览器旅程或全部读写操作矩阵。

## 关联文档

- [对抗审计与逐项证据](../audit/product-journey-adversarial-audit-2026-10-10.md)
- [租户作业配置与资源边界收敛计划](./tenant-job-configuration-simplification-plan-2026-10-09.md)
- [Console API 角色权限矩阵](../api/console-api-protocol.md#role-permission-matrix)
- 配对前端仓库：`../batch-console`
