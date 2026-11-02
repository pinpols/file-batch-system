# 前后端 Bug 审查与验证汇总（2026-10-27）

## 2026-11-02：表单与页面文案治理进度

另据 `batch-console/src/router/index.ts` 中直接懒加载的 Vue 组件盘点，涉及 68 个桌面及 13 个移动视图文件；这只统计该文件中的直接导入，不包含嵌套组件或其他动态路由，不能视为全部页面及文案的完整清单。各页具体状态仍需逐项核查。

补充路由口径：按同一文件的直接懒加载字面路由统计，共 84 条，指向上述 81 个唯一 Vue 文件（桌面 71 条、移动 13 条）；同一页面可能对应多个入口，仍不包含动态路由和嵌套组件。作业及 Workflow 运行详情、Worker 详情、审批两页签等尚缺明确的完整页面状态证据，不能继承列表页的核查结论。

前后端均在已有工作区的 `codex/closure-review` 分支处理。前端按实际组件盘点，`src/views` 中 58 个 Vue 文件包含 74 个 Element Plus 表单，`src/components` 中另有 8 个文件包含 8 个；移动页面及自定义表单还需单独检查。该盘点不是逐页视觉审查结果，全部表单及页面文案尚未核查完毕。

本批调整租户复制、初始化、新建和批量新建表单的标签布局、字段分组或宽度；复制及初始化的配置类型使用本地化名称，传输值不变。同步精简租户配置、实现选择和删除后果等提示，中英文键保持对应。租户复制页面曾在 1280×900 深色浏览器中检查并执行真实复制结果回查；该截图和操作仅覆盖复制表单及所测场景，不能代替其他表单、主题、视口或全部文案的审查。剩余页面应逐项检查视觉布局、文案和实际交互，再按受影响范围验证。

前端执行 `npm run typecheck`、`npm run check:i18n`、四个改动组件的 ESLint 检查，以及租户相关四个测试文件（68 项）均通过；`E2E_BASE_URL=http://127.0.0.1:5178 npm run test:e2e:visual` 的 1 项登录页基线通过，不覆盖本批表单。默认端口下的首次视觉基线启动被中断后使用独立端口重跑。后端文档结构、日期策略和代码引用检查通过。

续查补充：配置发布、文件模板与渠道、作业包导入、配额、资产新鲜度、API Key、系统参数、账户及通知配置等紧凑表单调整为顶部标签；通知规则的渠道字段实际使用编码，界面文案由“渠道 ID”改为“渠道编码”。作业包导入和配额等提示去除不必要的实现术语。上述续改有源码检查及定向组件测试，但未完成对应表单浏览器截图、主题、窄屏和键盘复核；静态表单清单不含所有自定义表单，也不能作为全站文案逐项审查完成的证据。

续改后执行 `npx vitest run --maxWorkers=2` 对租户复制、初始化、通知规则、配置发布、文件模板及作业包六个测试文件复验，共 152 项通过；改动 Vue 文件的 ESLint、`npm run check:i18n` 和 `git diff --check` 通过。类型检查在本轮续改期间通过，但未覆盖随后增加的仅文案调整；全站视觉审查与完整测试未运行。

再次续查：移动端 14 个 Vue 页面完成源码级用户可见静态文本和输入控件扫描，调整补跑请求标识、链路标识与工作流类型展示，并为执行日志搜索添加可访问名称。公共组件内 8 个 Element Plus 表单及常用录入助手完成源码核对：重试策略复用现有翻译，链路 ID 粘贴入口改为可聚焦按钮；编码向导说明与实际五个域选项对齐。`MWorkflowViewer.context.test.ts` 和 `operationsToolkit.test.ts` 共 9 项通过；i18n 和改动组件 ESLint 检查通过。上述源码扫描及单测不代表浏览器视觉、键盘操作或所有状态验收。

隔离组件预览在中文深色主题下检查了租户新建、批量新建、初始化和复制抽屉的 1280×900 与 390×900 视口，可见控件未横向溢出；复制抽屉在窄屏的底部按钮占三行。该预览拦截全部 API，不代表真实服务或其他主题、语言、状态通过。预览发现链路 ID 粘贴按钮的 Enter 和空格被 Tooltip 拦截；修复后通过 Tab 聚焦及 Enter、空格、鼠标操作复核，模拟剪贴板均读取一次、正确填值且未提交表单。桌面作业、工作流、文件和配置页面的 51 个 Vue 文件完成静态文本与固定属性扫描；调度、治理、运维页面做了枚举展示命中检查。两者仍不能代表全量文案逐项审读或浏览器验收。

根据视觉反馈再次调整租户复制抽屉：租户选择在宽屏并列、窄屏纵向排列，去除重复的卡片边框，以分隔线组织内容；三个独立对比工具移至预览选项附近，固定底栏保留取消、试运行和正式复制。各工具继续使用原有请求和权限条件。内置浏览器隔离预览核对了深色中文桌面及中英文 390×900 窄屏；桌面源和目标字段的标签顶点相同、输入框顶点相同。预览拦截 API，未重新执行真实服务写入；不同权限、结果、错误和其他主题仍需单独核对。

本轮继续用隔离组件预览复核表单：英文业务日历和批次窗口的固定宽度标签曾换行并侵入下一行，改为顶部标签后在桌面及 390×900 窄屏检查，窗口时间输入与底栏可见。资源队列的三个容量输入由逐行改为响应式排列，复测发现末项受通用样式影响下移 8px，修正后桌面三项输入框及标签的顶点分别一致；英文 390×900 下两项同排、第三项换行。预览仅使用样例和拦截的 API，不代表真实后端、其他状态或全站视觉验收。同时根据后端枚举补齐队列、窗口和日历的中英文显示，列表中实际读取到点策略的列改用对应名称；已有接口值不变。

新建租户、新建账户、通知渠道和通知规则抽屉在焦点切换时会提前触发必填错误；已将焦点设置移到抽屉打开完成后，并清除打开过程产生的校验状态。隔离浏览器复核四处首次打开，通知规则还验证关闭重开及空提交；新增组件回归覆盖四个入口的焦点、初始状态及空提交校验。登录页视觉基线复跑 1 项通过，该基线不覆盖上述抽屉。

本轮定向复测：`NotificationTenantFormFocus`、`NotificationChannelsTab`、`NotificationRulesTab`、`governanceListFilters`、`JobMonitoringFields` 共 5 个测试文件、20 项通过；`GovernanceEditors`、`QueueConfigWindowPreview`、`QueueConfigHolidays` 共 3 个文件、11 项通过。首次命令同时指定的 `QueueConfig.test.ts` 实际不存在，Vitest 未执行该路径，已改为上述实际存在的治理测试文件；不得将不存在的路径计为通过。

继续复核工作流依赖、文件上传和等待治理：工作流依赖的两个选择框曾相差 9px，修复后顶点一致；其固定宽度弹窗在 390px 视口裁切，调整后完整可见。文件上传弹窗原宽 620px，在 390px 视口裁切右侧，调整后上传字段及底栏可见。共享编码组件在窄屏压缩业务名称输入，已改为分行并补可访问名称。批次日摘要窄屏由两列改为单列，桌面仍为两列；历史缺口按钮及等待关系搜索按钮与相邻日期控件已对齐，持久等待空态不再被过大行高拉开。上述检查使用隔离数据和拦截 API，不构成实际业务写入验证。批次日治理、等待关系图和展示辅助三文件 17 项通过；文件列表及模板相关测试 17 项通过。

API Key 界面新增空权限使用通配范围的提示，以及后端已有的 `worker.read`、`worker.execute` 选项；账户列表的启用状态与角色名称使用已有词条，未知状态或角色保留明确的回退显示。相关组件及角色测试 11 项通过；未真实创建密钥。文件渠道的认证、回执等枚举补齐真实后端取值的中英文文案，模拟 `PASSWORD`、`SYNC` 已在中英文界面核对。隔离文件预览曾出现一次 ResizeObserver 警告，尚未确认真实应用是否复现。

续查配置及调度：配置发布版本对比在 390px 视口裁切，现已适配窄屏并在隔离预览核对版本选择与按钮；导入向导英文长选项改为换行，桌面及窄屏可见。批次窗口窄屏筛选标签及状态摘要不再逐字挤压，单次补跑弹窗不再裁切；相关预览未提交业务操作。自助配额申请先用组件测试复现 `12abc` 和 `12.9` 被截断成 `12` 提交，随后要求完整正整数；非法值不提交、合法值保持原语义。自助配额、重跑、补偿和触发器申请初始页面均在 390px 隔离预览检查，未执行真实申请。

本轮继续检查监控、运维和工作流表单：通知重投弹窗及工作流列表弹窗调整窄屏宽度，通知事件、等级及工作流选项补充中英文显示，保留原有传输值。批次重放的范围选项在英文窄屏原先被裁切，已调整选项排列；结果版本筛选框与查询按钮的顶边由错位调整为一致，桌面实测均为 121.14px。下表记录实际查看的初始状态；均使用隔离夹具，未执行真实写入，其他状态、主题及真实服务仍待核查。

通知与用量相关定向测试 `npx vitest run --maxWorkers=2 src/views/system/components/NotificationTerminalDeliveriesTab.test.ts src/views/system/components/NotificationRoutePreview.test.ts src/views/observability/usageSummary.test.ts` 共 3 个文件、8 项通过。组件测试及上述隔离预览不构成完整浏览器或真实服务验收。

批次重放与工作流网关定向测试 `npx vitest run --maxWorkers=2 src/views/ops/BatchDayReplay.test.ts src/views/workflow/designer/inspector/GatewayNodeForm.test.ts` 共 2 个文件、12 项通过；国际化检查 `npm run check:i18n` 和类型检查 `npm run typecheck` 通过。


继续核查系统、作业、文件及公共工具表单：维护设置恢复时间的默认值和上限由占位文字改为持续提示，日期控件与表单等宽；作业向导窄屏改为顶部标签，步骤显示保留当前步骤说明，桌面步骤长标题行距调整；作业包导入弹窗适配窄屏并允许模式选项换行。文件模板及渠道校验信息改为中英文可读字段名，校验规则和提交值不变。隔离预览中曾看到枚举代码，补齐夹具元数据后确认显示正常，未据此修改生产枚举。以上未执行真实写入；公共 Cron 预览请求因本地服务未连接失败，未验证服务端结果。

文件模板定向测试 `npx vitest run --maxWorkers=2 src/views/file-center/FileTemplateList.context.test.ts` 19 项通过；文件表单和维护设置的目标 ESLint、`npm run check:i18n` 通过。上述结果只覆盖运行命令及本轮记录的状态。

工作流批量触发弹窗在英文 390px 原固定宽度裁切，现按视口限制宽度并复拍确认表格区域及底栏可见，未发起触发；`npx vitest run --maxWorkers=2 src/components/workflow/WorkflowBulkTriggerDialog.test.ts` 3 项及该组件 ESLint 通过。作业向导/导入相关 `npx vitest run --maxWorkers=2 src/views/job/JobBundleContextRisks.test.ts` 63 项及两个受影响 Vue 文件 ESLint 通过；后续向导步骤和真实导入未验证。

治理表单续查：新建窗口的起止时间、假日的业务日期与其他输入控件宽度统一；队列、窗口和日历的必填提示使用本地化字段名，配额相关英文提示和搜索占位去除内部属性名。英文 390px 隔离预览复核窗口、假日和配额初始状态，未执行提交。`npx vitest run --maxWorkers=2 src/views/governance/QueueConfig.validation.test.ts src/views/governance/QueueConfigWindowPreview.test.ts src/views/governance/QueueConfigHolidays.test.ts` 3 文件 12 项通过，涉及文件 ESLint 与国际化检查通过。

工作流监控策略和试运行弹窗在窄屏原固定宽度裁切；现分别采用响应式宽度，前者根据空间排列规则控件，后者将表单标签上置并在窄屏纵向排列 L1/L2/L3。隔离预览复核工作流监控英文桌面及 390px 初始状态，以及试运行英文 390px 三个档位与条件日期；未保存策略或发起试运行。`npx vitest run --maxWorkers=2 src/components/workflow/WorkflowMonitoringPolicyDialog.test.ts` 6 项、`npx vitest run --maxWorkers=2 src/components/dialogs/DryRunPlanDialog.test.ts` 5 项及各组件 ESLint 通过。告警路由编辑功能当前关闭，编辑表单不可达，未记为已验证。

配置同步预览中，目标 JSON 工具栏被面板裁切，现调整容器布局；目标租户占位改为实际多选提示，未选时仍使用当前租户。原子任务参数表单窄屏标签改为上置，保留桌面布局；我的账户仅查看初始状态，未输入凭据。`npx vitest run --maxWorkers=2 src/views/system/components/ConfigSyncTab.test.ts` 27 项、两个受影响组件 ESLint 与国际化检查通过；均未执行真实提交。

视觉基线 `E2E_BASE_URL=http://127.0.0.1:5246 npm run test:e2e:visual` 1 项通过，该测试仅覆盖中文深色登录入口，不覆盖本批表单。

身份绑定抽屉因标签已置顶却保留旧左缩进，操作按钮与字段错位；移除缩进并调整窄屏空态行高，英文桌面及 390px 隔离预览复核，组件 ESLint 通过。资源标签的新建抽屉仅通过夹具直接打开，未核实父页面入口。工作流列表英文筛选占位改为用户可读文案，校验摘要避免单复数错误，级别复用本地化并在未知值时回退；英文 390px 隔离预览查看运行弹窗及模拟校验结果，未触发工作流。工作流列表定向 ESLint、国际化检查和 `npx vitest run --maxWorkers=2 src/components/table/ListPageQueryBar.test.ts` 2 项通过。

初始化租户页面窄屏标签上置，租户编码提示支持长词换行并去除重复建议；初始密码要求改为持续可见说明，区分前端要求的至少 12 位和混合字符建议。英文 390px 隔离预览复核初始表单与说明；登录页另查看空值、服务不可达及单点失败夹具状态，未输入凭据或提交。`npx vitest run --maxWorkers=2 src/components/common/TenantIdInput.test.ts src/views/login/Login.test.ts` 2 文件 3 项、涉及文件 ESLint 与国际化检查通过。

非 `<el-form>` 页面续查：作业实例英文 390px 批量结果弹窗原固定宽度裁切，现适配视口并通过隔离内存响应复拍；`npx vitest run --maxWorkers=2 src/views/monitor/JobInstanceList.test.ts` 10 项及目标 ESLint 通过，未执行真实重试。工作流运行列表英文窄屏筛选初始状态已看，搜索占位改为可读文案。文件到达组英文桌面及窄屏查看筛选与样例表格；流水线观测英文窄屏查看四个页签筛选和空态，确认操作及非空详情仍待查。

移动文件筛选原使用后端不存在的 `PROCESSING` / `COMPLETED` 状态，现使用后端 `fileStatus` 字典，并补全中英文状态名称；英文 390px 模拟数据下选择 Loaded 后列表从 2 条变为 1 条。日志的 Trace ID 占位说明精确匹配。`npx vitest run --maxWorkers=2 src/views-mobile/MFileList.filter.test.ts` 1 项通过，目标 ESLint 与国际化检查通过；筛选结果为隔离夹具，不代表真实后端验收。

上述续改后的当前工作区再次执行 `npm run typecheck` 和 `npm run check:i18n` 均通过；`E2E_BASE_URL=http://127.0.0.1:5246 npm run test:e2e:visual` 1 项通过，仍仅覆盖中文深色登录入口。报告更新后执行文档结构、日期策略及代码文档引用检查均通过；这些检查不等同于全站视觉或真实业务验收。

本轮复核租户复制与审计筛选：复制类型原桌面英文长标签换行导致对齐不整齐，网格改为自适应列宽，英文桌面及中英文 390px 已复拍；隔离差异详情的配置类型和动作改用中英文名称，未知值回退原值。英文 390px 使用模拟源、目标及预览响应查看详情，未真实复制；矩阵及错误状态待查。`npx vitest run --maxWorkers=2 src/views/system/components/TenantCopyConfigDialog.test.ts` 38 项、相关 ESLint 与国际化检查通过。

业务审计与操作审计依据各自 SQL 区分操作人 ID 部分匹配和精确匹配，统一 Trace ID 等筛选文字；英文 390px 隔离空态复拍可读。`npm run check:i18n` 及词条 ESLint 通过，真实查询和数据结果未验证。

移动页续查：英文 390px 在完整移动布局夹具下查看作业及 Worker 非空卡片和搜索，Worker 三个操作按钮在窄屏改为按空间换行，避免英文动作文字挤成两行；此前缺移动布局包装导致的浅色按钮低对比属于夹具问题，不作为生产缺陷。移动告警搜索明确说明只搜索已加载记录；当前页无匹配但还有后续页时保留加载入口，分页场景由定向测试覆盖。页面样例和定向测试使用模拟数据，未操作真实告警、作业或 Worker；预览服务日志有一次 Worker GET 经代理失败（502），不能称所有请求均被拦截，真实后端未验证。

文件状态展示续查发现桌面和移动颜色表仍引用后端不存在的旧状态，现根据后端实际 11 项更新共享类型和颜色映射，移动端复用同一映射并对未知值回退中性。`npx vitest run --maxWorkers=2 src/components/common/statusTagResolve.test.ts src/views-mobile/MFileList.filter.test.ts` 2 文件 16 项及相关 ESLint 通过；未改变后端状态或接口值。

移动告警加载后续页失败时，原实现仍提前递增页码，下一次重试会跳过失败页；已在请求成功后推进页码。定向测试先复现请求页序 `[1, 2, 3]`，修复后验证 `[1, 2, 2]` 和第二页结果。`npx vitest run --maxWorkers=2 src/views-mobile/MAlerts.search.test.ts src/views-mobile/MFileList.filter.test.ts src/components/common/statusTagResolve.test.ts` 3 文件 18 项与相关 ESLint 通过；告警结果及分页均为测试夹具。

续查共享状态调用方：作业关联文件表改用共享文件状态展示，已知状态使用本地化名称与对应颜色，未知值仍回退原值；移动文件页在元数据字典缺失时从已有共享状态映射取得可选项，字典可用时仍以服务端为准。两项均先用测试复现；`npx vitest run --maxWorkers=2 src/views-mobile/MFileList.filter.test.ts src/views/job/components/JobRelatedFilesTab.status.test.ts src/views/job/components/JobRelatedFilesTab.test.ts src/components/common/statusTagResolve.test.ts` 4 文件 22 项及目标 ESLint 通过，真实后端和对应状态颜色的视觉验收未执行。

租户复制差异原因由后端返回四种固定英文，本轮只对已确认原文精确翻译，其他原因保留原文。组件测试新增四种已知原因和未知值回退，先红后修；`npx vitest run --maxWorkers=2 src/views/system/components/TenantCopyConfigDialog.test.ts` 43 项及目标 ESLint、国际化检查通过。中文桌面隔离预览已复核四条已知原因的翻译及未知原因原样回退；未执行真实复制。

移动审批的分页失败重试与告警页存在同样的跳页问题；修复为成功后推进页码，`npx vitest run --maxWorkers=2 src/views-mobile/MApprovals.pagination.test.ts` 1 项和目标 ESLint 通过，测试复现失败后的请求页序由 `[1, 2, 3]` 修正为 `[1, 2, 2]`。英文 390px 审批初始卡片及搜索为模拟数据，未提交审批。

移动补跑列表根据实际查询关联的审批状态展示和统计待审：后端查询固定返回 `requestStatus=ACCEPTED`，原页面按请求状态的 PENDING 统计导致待审为零。现优先展示审批状态，旧记录缺审批号时显示只读原因；窄屏日期不再拆断。英文 390px 用真实字段形态的夹具复拍，未提交；`npx vitest run --maxWorkers=2 src/views-mobile/MApprovals.pagination.test.ts src/views-mobile/MCatchUp.status.test.ts` 2 文件 3 项、目标 ESLint 和国际化检查通过。

租户列表筛选依据后端 `keyword` 同时匹配租户 ID 与名称的实际行为更新文案，中文 390px 复拍可读；触发器列表英文窄屏查看筛选及行按钮，未执行状态变更。相关 ESLint 和国际化检查通过。

事件目录默认目录视图原将请求失败显示为无数据，且刷新失败可能误提示成功；现显示失败及重试，并保留失败反馈。`npx vitest run --maxWorkers=2 src/views/observability/EventCatalog.error.test.ts src/views/observability/eventCatalogPresentation.test.ts` 2 文件 6 项及目标 ESLint 通过；英文 390px 模拟失败态已查看，隔离预览计数一次 Enter 产生一次表单提交。Outbox 英文窄屏仅查看筛选和空态，夹具曾提示阻断写请求；非空及真实服务状态未验证。

本批汇总复验：`npx vitest run --maxWorkers=2 src/views/system/components/TenantCopyConfigDialog.test.ts src/views-mobile/MAlerts.search.test.ts src/views-mobile/MApprovals.pagination.test.ts src/views-mobile/MCatchUp.status.test.ts src/views-mobile/MFileList.filter.test.ts src/views/job/components/JobRelatedFilesTab.status.test.ts src/components/common/statusTagResolve.test.ts src/views/observability/EventCatalog.error.test.ts` 8 文件 69 项通过；`npm run check:i18n`、`npm run check:docs`、前后端 `git diff --check` 通过。首次 `npm run typecheck` 在 `MCatchUp.vue` 报状态字段可能为 null；展示函数参数类型补充 null 后重跑通过，该修正不改变运行逻辑。`E2E_BASE_URL=http://127.0.0.1:5246 npm run test:e2e:visual` 1 项通过，仅覆盖中文深色登录页。

工作流定义图在隔离浏览器中复现黑色节点遮住文字：SVG 安全净化移除 Mermaid 内嵌样式，而定义图未提供替代样式。移动端及桌面组件现使用项目可信样式恢复节点、文本和连线，未改变净化规则；英文浅色桌面、英文浅色及中文深色 390px 移动预览已复拍，移动样例节点可打开详情。桌面通过预览夹具捕获实际导出 Blob，核对节点填充和描边、连线描边、文字居中属性；未验证真实服务数据及所有图形类型。`npx vitest run --maxWorkers=2 src/utils/trustedMermaidSvg.test.ts src/views-mobile/MWorkflowViewer.context.test.ts src/views-mobile/MOutbox.test.ts` 3 文件 19 项及桌面组件 ESLint 通过。

移动 Outbox 的固定分段筛选在英文 390px 裁切，改为下拉并使用后端实际重试状态字典；策略缺失时不显示分隔符。移动下拉浮层挂载到 body 后无法继承局部主题变量，暗色下回退为黑字；现回退全局主题色。Outbox 深色英文、浅色中文及移动文件筛选深色英文的隔离预览已复核。夹具第一次路由错误导致一次代理 GET 502，未发生真实写入；真实服务及其他状态未验证。

链路诊断窄屏筛选与说明原被压入同一行，已调整为响应式分行；血缘范围文字不再使用数字指标的大字号。两页中英文可读文案及后端已知范围、缺口名称已补齐，未知值保留原文。英文 390px 链路样例和中文 390px 血缘样例由隔离预览复核；`npx vitest run --maxWorkers=2 src/views/observability/TraceDiagnostic.test.ts src/views/observability/LineageEvidence.test.ts src/views/observability/LineageRelationExplorer.test.ts` 3 文件 13 项及目标 ESLint、国际化检查通过，未验证真实服务。

配置导入向导上传会话原在租户切换后保留，可能在新上下文中仍对原上传租户应用；切换时现清理会话并使在途上传、预览与确认回调失效，已发出的服务端应用请求不可撤回。`npx vitest run --maxWorkers=2 src/views/config/TenantPackageImportWizard.test.ts` 7 项及目标 ESLint、类型、国际化、API 漂移检查通过；中文 390px 预览及英文 390px 应用阻塞态使用隔离数据，未真实上传或应用。报表导出界面中文卡片、租户标识和错误提示改为用户可读文案，不影响导出接口。

本批最终复验：`npx vitest run --maxWorkers=2 src/views/config/TenantPackageImportWizard.test.ts src/views/observability/TraceDiagnostic.test.ts src/views/observability/LineageEvidence.test.ts src/views/observability/LineageRelationExplorer.test.ts src/views-mobile/MOutbox.test.ts src/views-mobile/MWorkflowViewer.context.test.ts src/utils/trustedMermaidSvg.test.ts` 7 文件 39 项通过；`npm run typecheck`、`npm run check:i18n` 通过。`E2E_BASE_URL=http://127.0.0.1:5246 npm run test:e2e:visual` 1 项通过，该基线仅覆盖中文深色登录页，不覆盖本批工作流图、导入或链路页面。

继续检查对齐：租户复制的对比工具原与标签横排，按钮左边界偏离其他表单控件；现将标签置于按钮上方，按钮与其他控件左侧对齐。隔离预览复核了深色中文桌面和英文 390px，未连接真实服务或执行复制。`npx vitest run --maxWorkers=2 src/views/system/components/TenantCopyConfigDialog.test.ts` 43 项、该组件 ESLint 通过；`E2E_BASE_URL=http://127.0.0.1:5384 npm run test:e2e:visual` 1 项通过，仅覆盖登录页。

通知管理使用隔离样例复核了中文浅色桌面与 390px 的渠道、规则编辑抽屉，以及窄屏路由模拟的作业事件条件与结果空态；未发现明确对齐问题，未修改代码。该检查未连接真实服务，英文、深色、完整键盘路径及其他状态仍待查。

作业定义向导英文桌面的执行策略和触发步骤中，长标签换行与下一行重叠；现调整标签列宽并允许标签按内容撑高。英文桌面复拍及英文 390×844 第 3–6 步检查未见重叠或横向裁切；第 4 步非法 JSON 显示错误并阻止继续。工作流和告警路由的中英文提示去除内部表名。隔离预览未提交，热更新时部分 GET 因代理被禁止而失败；`npx vitest run --maxWorkers=2 src/views/job/JobBundleContextRisks.test.ts` 63 项、目标 ESLint 和 `npm run check:i18n` 通过。

工作流图继续复核时发现净化后失败边与跨日边的虚线丢失，桌面节点带 Mermaid 渲染前缀的 ID 也未被点击处理识别。三个图组件补回虚线样式，桌面按实际渲染 ID 匹配节点；隔离浏览器样例中普通、失败、跨日边分别为实线、虚线、虚线，实际桌面导出 SVG 保持同样线型，点击节点可打开对应详情。`npx vitest run src/views/workflow/WorkflowMermaidViewer.test.ts src/components/workflow/WorkflowMiniDag.test.ts src/utils/trustedMermaidSvg.test.ts src/utils/crossDayMermaid.test.ts` 4 文件 14 项通过。未连接真实后端或验证其他图形类型。

本次最终联合复验：`npx vitest run --maxWorkers=2 src/views/system/components/TenantCopyConfigDialog.test.ts src/views/job/JobBundleContextRisks.test.ts src/views/workflow/WorkflowMermaidViewer.test.ts src/components/workflow/WorkflowMiniDag.test.ts src/utils/trustedMermaidSvg.test.ts src/utils/crossDayMermaid.test.ts` 6 文件 120 项通过；上述受影响 Vue 文件及新增测试的 ESLint、两仓 `git diff --check` 通过。

有依赖数据的等待关系图在英文 390px 下将拓扑容器撑出视口，连带裁切说明与工具栏；现限制网格列宽并保留图形区域自身滚动，工具换行后也与首行左对齐。模拟两条依赖的桌面和窄屏预览已查看；窄屏组件宽度及滚动宽度由 `342 / 1216px` 调整为 `342 / 342px`，图内可单独滚动。`npx vitest run --maxWorkers=2 src/views/scheduler/components/dependency-waits/WaitRelationshipGraph.test.ts src/views/scheduler/waitRelationshipGraph.test.ts src/components/workflow/WorkflowMiniDag.test.ts` 3 文件 18 项及两个组件 ESLint 通过；`E2E_BASE_URL=http://127.0.0.1:5386 npm run test:e2e:visual` 1 项通过，视觉基线仅覆盖登录页。

作业列表快速创建和编辑抽屉在英文桌面与 390×844 使用隔离样例检查；筛选及表单占位从内部字段名改为用户可读文案，过长的无队列提示简化。`npm run check:i18n` 和两语言文件 ESLint 通过；未连接真实服务或提交。

作业触发弹窗原多行参数输入区仅显示两行，现按类型精简说明并调整输入高度；英文 390px 已复核示例和按钮，输入非法 JSON 后弹窗保留并显示错误。克隆确认弹窗也已查看，未确认操作。`npx vitest run --maxWorkers=2 src/views/job/JobBundleContextRisks.test.ts` 63 项、类型、国际化及目标 ESLint 通过；视觉基线 1 项仅覆盖登录页。

新建文件模板时，两个 JSON 字段自动填入空对象导致映射助手默认禁用；现仅对这两个字段保留空值，手动输入 `{}` 或已有配置时仍禁止草案覆盖。英文隔离预览检查了导入窄屏及导出桌面草案的本地生成和应用，未保存；`npx vitest run --maxWorkers=2 src/views/file-center/FileTemplateList.context.test.ts src/views/file-center/MappingDraftAssistant.test.ts src/components/common/JsonTextareaInput.test.ts` 3 文件 34 项及目标 ESLint 通过。草案响应为夹具，不证明真实服务生成结果。

智能助手输入框补充可访问名称、审计筛选文案去除内部字段名。共享游标分页在英文窄屏原将页大小截断，现允许换行并复核智能助手审计及文件列表；后者文件 ID 与链路 ID 的提示按后端精确匹配语义修正。中文桌面、中英文 390px 隔离预览未连接真实服务；曾出现一次 ResizeObserver 警告，尚未确认真实应用是否复现。`npx vitest run --maxWorkers=2 src/views/file-center/FileList.arrival.test.ts src/composables/useAiChatSession.test.ts` 2 文件 28 项、国际化与目标 ESLint 通过，登录视觉基线 1 项通过。

本批最终联合复验：`npx vitest run --maxWorkers=2 src/views/file-center/FileTemplateList.context.test.ts src/views/file-center/MappingDraftAssistant.test.ts src/components/common/JsonTextareaInput.test.ts src/views/job/JobBundleContextRisks.test.ts src/views/file-center/FileList.arrival.test.ts src/composables/useAiChatSession.test.ts src/views/scheduler/components/dependency-waits/WaitRelationshipGraph.test.ts src/views/scheduler/waitRelationshipGraph.test.ts src/components/workflow/WorkflowMiniDag.test.ts` 9 文件 143 项通过；`npm run check:i18n`、`npm run typecheck` 与两仓 `git diff --check` 通过。

告警路由目前仅供历史审计，原空态提示新增及自动分发，与实际功能不符；已调整中英文空态和搜索提示。隔离预览覆盖中文桌面及 390px 空态、英文 390px 空态和一条只读记录，未启用不可达的编辑器；`npx vitest run --maxWorkers=2 src/views/observability/AlertRoutingPanel.contract.test.ts` 1 项、国际化和两语言 ESLint 通过。预览出现一次 ResizeObserver 警告，真实应用是否复现尚未确认。

报表中心隔离预览检查了中文和英文 390px 趋势与导出页面及中文搜索；搜索框补充可访问名称，下载按钮标明各自报表名称，趋势图为每日数值提供可访问文本，切换按钮暴露当前状态。中文说明和工作节点文案同步精简。未真实下载、复制或访问服务；目标 ESLint 通过，登录视觉基线 1 项通过，仅覆盖登录页。

文件到达组按后端查询核对：`arrived_count` 是文件记录总数，等待、超时及触发计数是其子集，原百分比会重复计数；现显示记录数和子集数，同租户、同组编码的多状态行按组去重，并将 `WAITING_ARRIVAL` 纳入等待组。组状态可交叉，当前查询不提供应到分母，相关说明已同步前端使用指南。中文 390px 隔离样例验证等待状态与无匹配筛选，英文 390px 核对双列摘要及完整状态；未连真实服务。`npx vitest run --maxWorkers=2 src/views/file-center/arrivalGroupPresentation.test.ts src/components/common/statusTagResolve.test.ts` 2 文件 15 项、目标 ESLint、国际化和文档检查通过；新增断言曾先复现等待数及组数错误。

审批中心英文窄屏状态分段原裁切“已拒绝”，现改为两列并验证可选择。补跑列表明确区分请求状态，详情分别显示请求状态与可空的审批状态，缺失时显示横线；相关中英文提示去除内部字段名。英文 390px 隔离样例检查了状态筛选、审批弹窗和 `ACCEPTED` / `PENDING` 详情，未提交；`npx vitest run --maxWorkers=2 src/views/approvals/ActionContextRisks.test.ts src/views/approvals/components/CatchUpApprovalsTab.test.ts` 2 文件 34 项、类型、国际化及目标 ESLint 通过。初次测试命令误含不存在的测试路径，已用上述实际路径复跑。

工作节点管理的英文状态筛选原被固定宽度裁切，现让状态名完整显示并在窄屏换行；渠道详情窄屏由两列改为单列，避免类型和超时时间逐字折行，桌面仍保留两列。筛选占位与已知状态改为用户可读名称，未知状态保留原值。隔离样例检查了英文桌面和 390px 筛选、已认领任务与渠道详情，未执行写入；夹具阻断 SSE 及热更新后的代理请求，不能作为真实服务错误结论。`npx vitest run --maxWorkers=2 src/views/worker/OperationalContextRisks.test.ts src/views/worker/workerActions.test.ts src/components/common/statusTagResolve.test.ts` 3 文件 31 项、目标 ESLint 和国际化检查通过，登录视觉基线 1 项通过。

本批联合复验：`npx vitest run --maxWorkers=2 src/views/file-center/arrivalGroupPresentation.test.ts src/components/common/statusTagResolve.test.ts src/views/approvals/ActionContextRisks.test.ts src/views/approvals/components/CatchUpApprovalsTab.test.ts src/views/observability/AlertRoutingPanel.contract.test.ts` 5 文件 50 项通过；`npm run check:i18n` 和 `npm run typecheck` 通过。该范围不包括全部页面。

加入工作节点后的最终联合复验：`npx vitest run --maxWorkers=2 src/views/file-center/arrivalGroupPresentation.test.ts src/components/common/statusTagResolve.test.ts src/views/approvals/ActionContextRisks.test.ts src/views/approvals/components/CatchUpApprovalsTab.test.ts src/views/observability/AlertRoutingPanel.contract.test.ts src/views/worker/OperationalContextRisks.test.ts src/views/worker/workerActions.test.ts` 7 文件 67 项通过；国际化及两仓 `git diff --check` 通过。

配置发布新建抽屉曾因表单与抽屉抢焦点而在打开时立即显示必填错误；现待抽屉打开后聚焦首字段，离开空字段时仍正常校验。发布详情窄屏由两列改为单列并按用户时区显示发布时间；英文 390px 隔离预览检查了初始焦点、离开字段的错误提示及详情，未保存。`npx vitest run --maxWorkers=2 src/views/config/ConfigReleaseList.test.ts` 17 项与目标 ESLint 通过。

作业实例与工作流运行详情的状态使用已有本地化和未知值回退；实例时间及工作流运行信息在窄屏由两列改为单列。英文 390px 隔离样例复拍了状态、时间和链路 ID；诊断抽屉仅确认可打开，夹具提示遮挡了视觉检查。`npx vitest run --maxWorkers=2 src/views/monitor/MonitorContextRisks.test.ts src/components/common/statusTagResolve.test.ts src/views/monitor/workflowNodeExecution.test.ts` 3 文件 31 项、目标 ESLint、类型和国际化检查通过；未连接真实服务。

告警列表窄屏卡片的正文与操作原相互挤压，现分行排列；列表加载失败原误显示无匹配告警，现保留已有数据并提示失败。确认、静默及关闭文案依实际状态和 Alertmanager 桥接行为修正，前端可观测性使用指南同步。英文桌面、390px 正常及模拟失败态通过隔离预览检查，未执行写入；`npx vitest run --maxWorkers=2 src/views/observability/AlertList.error.test.ts src/views/monitor/MonitorContextRisks.test.ts` 2 文件 16 项、目标 ESLint、类型、国际化及文档检查通过。视觉基线仅覆盖登录页。

作业定义详情及共享向导汇总的描述表在英文 390px 原将长编码和日期拆成多行，现按视口在窄屏使用单列、桌面保留两列；调度操作按钮在窄屏换行。隔离样例检查了详情的基本、调度、资源、触发、文件、工作流、告警及向导汇总，未提交；`npx vitest run --maxWorkers=2 src/views/job/JobBundleContextRisks.test.ts src/views/job/components/JobRelatedFilesTab.status.test.ts` 2 文件 64 项及目标 ESLint 通过。

精确查询兼容边界：现有联合发布文档要求前后端同一版本集合，但 Compose 晋级未保证后端先于前端启动，浏览器中已加载的页面也不会自动同步切换。旧 Console 不支持 `exactJobCode`；兼容回退的 `jobCode` 仍是模糊查询，不能证明精确结果、计数或分页正确。该功能的上线需要先更新并核验全部后端副本，再启用依赖该条件的新前端；混版及回滚期间的结果尚未验证。

作业详情及实例详情的最近运行原使用 `jobCode` 模糊条件后取首页，可能混入相似编码、使正确记录被挤出。后端新增可选 `exactJobCode`，列表和计数均在分页前精确过滤，保留原模糊条件；同参数并传时精确条件优先。前端各单作业入口与“查看全部”使用精确条件，列表清晰标注精确模式、修改编码后回到模糊搜索；租户与详情不一致时提示切回，不静默切换。切换作业期间的详情和懒加载旧响应不再覆盖当前内容，已发出的操作不能撤回。API 协议、发布说明和前端使用指南已更新，OpenAPI 类型由仓库工具生成。

`./mvnw -pl batch-console-api -am -Dtest=JobInstanceMapperXmlTest,JobInstanceQueryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` 10 项通过，包含隔离 Testcontainers PostgreSQL 17.11 的相似编码、下划线、租户隔离、分页、双参数优先级和旧模糊查询；日志 `/private/tmp/codex-job-instance-exact-precedence-20261102.log`。前端 `npx vitest run --maxWorkers=2 src/views/job/JobDefinitionDetail.context.test.ts src/api/queries/instances.test.ts src/views/monitor/JobInstanceList.test.ts src/composables/useInstanceNoSearch.test.ts src/views/job/components/MiniCreateTimezone.test.ts src/components/dialogs/DryRunPlanDialog.test.ts src/views/job/JobBundleContextRisks.test.ts` 7 文件 102 项、类型、国际化、生成类型一致性和相关 ESLint 通过；OpenAPI 路径检查 439 项通过。独立源码复核未发现该范围内新增具体缺陷，未做真实前后端联合浏览器验收。

详情主刷新失败时原会使运行页签在途请求失效并可能误显示空态，首次加载失败也会误报作业不存在；现保留同一作业的已有详情和在途页签数据，并显示失败及重试。新增回归先复现失败，修复后 `npx vitest run --maxWorkers=2 src/views/job/JobDefinitionDetail.context.test.ts src/views/job/JobBundleContextRisks.test.ts` 2 文件 75 项、类型、国际化及目标 ESLint 通过；失败期间保留的数据可能不是最新，已写入使用指南。未做真实后端故障注入。

本批最终联合复验：`npx vitest run --maxWorkers=2 src/views/job/JobDefinitionDetail.context.test.ts src/api/queries/instances.test.ts src/views/monitor/JobInstanceList.test.ts src/composables/useInstanceNoSearch.test.ts src/views/job/components/MiniCreateTimezone.test.ts src/components/dialogs/DryRunPlanDialog.test.ts src/views/job/JobBundleContextRisks.test.ts src/views/config/ConfigReleaseList.test.ts src/views/observability/AlertList.error.test.ts src/views/monitor/MonitorContextRisks.test.ts` 10 文件 138 项通过；`npm run gen:api:check`、`npm run typecheck` 和 `npm run check:i18n` 通过。

工作流运行列表英文 390px 隔离样例中，定义 ID 列标题原被截断；现加宽该列，复拍显示完整标题、状态与详情入口。`npx vitest run --maxWorkers=2 src/views/monitor/MonitorContextRisks.test.ts` 15 项及该页面 ESLint 通过；视觉基线 1 项仅覆盖登录页。实时连接票据被隔离夹具拒绝，真实服务、筛选结果及其他状态未验证。

原子任务配置的 SQL 和 HTTP 请求体输入改为多行，保存配置选择框补充可访问名称。英文深色 390px 隔离样例查看 SQL、Shell、HTTP；SQL 和 HTTP 请求体的多行输入在预览中保留换行。`npx vitest run --maxWorkers=2 src/views/system/AtomicTaskTypeCenter.timeout.test.ts src/views/system/atomicTaskConfigDraft.test.ts` 2 文件 7 项、目标 ESLint 和登录视觉基线 1 项通过。未逐项操作存储过程执行器，也未连接服务或保存。

文件流水线的投递与错误记录页签原在查询失败时显示空数据，刷新也可能误提示成功；现显示失败和重试，已有记录时保留并提示可能过期，切换页签后的迟到响应不会覆盖当前总数。英文 390px 隔离样例复核了两个页签的首次失败；英文桌面和窄屏查看了运行列表有数据状态，已有数据后失败仅有组件测试证据。`npx vitest run --maxWorkers=2 src/views/file-center/FilePipelineObservability.error.test.ts src/api/filePipelineQuery.test.ts` 2 文件 13 项、类型、国际化、目标 ESLint 通过；登录视觉基线 1 项不覆盖流水线。可观测性使用指南已更新；未连接真实后端或验证进度流。

作业步骤页输入非法实例 ID 原会变成无条件查询；现提示有效的正整数范围并禁止该查询，清空后恢复，超出 JavaScript 安全整数但处于后端 Long 范围的编号保留十进制原文。步骤编码与类型在窄屏调整排列并支持查看完整值；分区表窄屏取消固定操作列，避免遮住分区键查看入口。英文深色桌面与 390px 隔离预览复核了步骤页和分区页，以及完整分区键弹层，未连接真实后端。`npx vitest run src/views/monitor/JobStepInstanceList.test.ts src/api/jobStepInstances.test.ts src/views/monitor/workflowNodeExecution.test.ts` 3 文件 11 项及目标 ESLint 通过。

本批联合复验：`npx vitest run --maxWorkers=2 src/views/monitor/JobStepInstanceList.test.ts src/api/jobStepInstances.test.ts src/views/monitor/workflowNodeExecution.test.ts src/views/file-center/FilePipelineObservability.error.test.ts src/api/filePipelineQuery.test.ts src/views/system/AtomicTaskTypeCenter.timeout.test.ts src/views/system/atomicTaskConfigDraft.test.ts src/views/monitor/MonitorContextRisks.test.ts` 8 文件 46 项通过；`npm run typecheck`、`npm run check:i18n` 及两仓 `git diff --check` 通过。

继续检查对齐：工作流定义详情的长编码、名称和时间在窄屏双栏中被挤碎，现按视口改为窄屏单栏、桌面双栏，详情时间与列表使用相同显示时区。英文深色隔离预览检查了 390×844 和桌面详情，以及窄屏调度的 Cron、固定频率和手动状态；未保存调度配置。身份绑定表格的用户名列由过窄调整为最小 160px，英文深色桌面样例中标题可完整显示；其他列仍可横向滚动。预览使用合成数据并拦截写请求，不代表真实服务验证。

继续检查页面失败状态：配额列表加载失败时现显示错误及重试，刷新失败不再提示成功；容量画像某些时间段加载失败时保留总览、提示趋势不完整，失败时间段显示为缺失而不是零。容量趋势缺失的回归测试先复现原有零值，再验证修复；容量画像使用指南同步说明。`npx vitest run --maxWorkers=2 src/views/ops/CapacityProfile.test.ts src/views/ops/capacityProfilePresentation.test.ts src/views/governance/QuotaPanel.test.ts src/utils/datetime.test.ts` 4 文件 29 项通过；目标 ESLint 和国际化检查通过。上述页面尚未进行真实后端故障注入。

身份绑定列表加载失败不再同时显示“没有绑定”；绑定或解绑请求失败且回查不可用时提示结果未知，回查期间切换租户则不展示旧操作结果。`npx vitest run src/views/system/components/OidcIdentityBindingsDrawer.test.ts` 5 项及对应 ESLint 通过；错误态和有数据表格通过英文桌面深色隔离样例检查，未操作真实身份数据。

本批类型检查首轮发现新增容量画像测试样例缺少生成契约中的必填字段；补全样例后 `npm run typecheck` 重跑通过。联合运行容量画像、配额、身份绑定及日期格式 5 个测试文件共 34 项通过；国际化、前端文档检查、后端文档结构、日期策略、引用检查及相关文件的 `git diff --check` 通过。

再次复看租户复制：中文深色隔离样例的 1280×800、390×844 初始表单、对比工具和合成差异预览未发现需再修的明确错位，本次未修改复制组件；不代表真实数据及全部状态已验证。批次窗口中文桌面已有跨天窗口编辑样例也未发现明确对齐问题，未保存。触发器列表英文 390px 的固定操作列遮挡工作流编码，现仅在窄屏取消固定，桌面仍固定；隔离样例复核了筛选和横向滚动，`npx eslint src/views/system/TriggerList.vue` 与该文件 `git diff --check` 通过，真实服务及其他语言未验证。

移动执行日志续查：首次请求失败原显示无日志，加载更多失败会清空已有记录；切换租户后的迟到响应也会覆盖当前列表。现显示持久错误和重试入口，翻页失败保留已加载记录并在列表末尾重试同一页，切换查询或租户时清空旧结果并拒绝旧响应。三个组件回归分别先复现失败，再通过；中文深色 390×844 隔离样例复核了首次失败、翻页失败及两次重试恢复，未连接真实服务。

资产新鲜度策略的必填提示与“作业编码”字段统一，编辑图标增加包含作业编码的可访问名称；英文深色 390×844 隔离样例复核编辑抽屉，未保存。业务日历中文字段的补跑术语与后端协议统一，文案修改后未重新截图。`npx vitest run --maxWorkers=2 src/views-mobile/MExecutionLog.test.ts src/views/ops/AssetFreshnessPolicies.test.ts` 两文件 6 项、相关 ESLint、国际化和前端文档检查通过。新增的无障碍测试首轮类型检查暴露测试 API 用法错误，修正后 `npm run typecheck` 重跑通过。

工作流设计器的条件状态补查：英文深色 280px 网关检查覆盖两个入边、两个出边和 N_OF 数量条件；Start/End 节点输入 257 字符后显示 256 字符上限错误。依赖新增弹窗的 CONDITION 表达式在英文桌面和 390×844 样例中未发现字段及底栏错位。均为隔离组件数据，未保存或连接后端。

依赖等待三个页签查询失败时原会同时展示错误和空表；现隐藏对应空表，持久化页签也隐藏分页，刷新后重新展示结果。`npx vitest run src/views/scheduler/components/DependencyWaitPanel.test.ts` 3 项先复现后通过，目标 ESLint 和前端文档检查通过。英文深色 390×844 隔离样例检查了日历失败及刷新恢复和作业首次失败；作业刷新浏览器操作超时，未记作通过。夹具曾误将依赖快照构造成对象产生类型警告，不能据此推断真实接口异常。

Webhook 编辑原允许修改名称，但前后端更新契约不接收该字段；现保留创建时填写，编辑时禁用名称，避免修改静默丢失。投递日志弹窗宽度适配窄屏。英文深色 390×844 隔离样例检查了编辑抽屉和投递日志，未保存或连接真实服务；`npx vitest run src/views/system/components/NotificationWebhooksTab.test.ts` 7 项、目标 ESLint 和前端文档检查通过。现有使用指南同步记录名称的修改限制。

工作流设计器边属性在英文深色 280px 夹具中，长编码产生的表达式校验提示曾覆盖下一字段；现让错误提示参与布局，复核显示错误底部与下一字段分离，输入有效表达式后错误消失。`npx vitest run --maxWorkers=2 src/views/workflow/designer/inspector/EdgeInspector.test.ts src/views/workflow/designer/inspector/JobNodeForm.test.ts` 2 文件 28 项及目标 ESLint 通过；未保存或连接真实服务。

移动作业列表首屏查询失败原会显示无实例，自动翻页失败没有可见重试，切换租户或状态后迟到的旧结果也可能覆盖或追加到当前列表。现分别显示首屏和列表末尾重试，保留已加载记录并重试原游标；新查询丢弃旧范围的迟到响应。SLA 筛选在加载或失败时不显示虚假的零条，游标滚动列表去除始终显示第一页的文案。五个定向测试覆盖失败恢复和请求乱序；中文深色 390×844 隔离样例复核首屏、翻页失败及重试恢复，未连接真实服务。对应运行与审批使用指南已同步。

本批联合复验：`npx vitest run --maxWorkers=2 src/views-mobile/MJobInstances.error.test.ts src/views/scheduler/components/DependencyWaitPanel.test.ts src/views/system/components/NotificationWebhooksTab.test.ts src/views/workflow/designer/inspector/EdgeInspector.test.ts src/views/workflow/designer/inspector/JobNodeForm.test.ts` 5 文件 43 项、`npm run typecheck`、`npm run check:i18n`、`npm run check:docs`、目标 ESLint 和两仓相关文件 `git diff --check` 通过。

移动工作节点页原有状态操作与桌面规则不一致，且确认排空期间切换租户后会向新租户提交旧节点编码；定向测试先复现该请求。现复用同一 Worker 状态规则和写权限/维护冻结判断，接管须确认并说明节点退役；确认后重新核对租户、账号、权限和节点状态。列表失败时提示重试，已有数据标明可能过期并禁用操作，旧租户的迟到列表不会覆盖当前列表。英文深色 390×844 隔离样例检查了首屏失败重试、三种节点状态、接管确认和只读角色禁用，未确认接管或连接真实服务；运行与审批指南同步更新。

文件详情英文深色 390×844 长名称样例原被双栏挤碎，现窄屏单栏、1280×800 桌面保留双栏。批次日治理英文 390×844 历史缺口日期输入左侧原不一致，改为窄屏标签上置和等宽；确认弹窗从约 195px 调整为 358px，1280×800 桌面筛选保持并排。上述均为隔离样例、未提交写操作；文件预览因夹具未注册 `DatetimeColumn` 有组件解析警告，批次日夹具有未配置路由警告，不将其作为生产问题或完整构建证据。

原子任务配置的预览与复制原把存储过程列表和 HTTP 对象展示为字符串，而保存会解析为数组或对象；现复用保存的参数归一结果，无效值显示提示并禁止复制。类型错误提示同时涵盖数字和 JSON 的填写要求。三个回归先复现后通过；英文深色 390×844 存储过程隔离样例检查了合法数组和非法列表，未保存或连接真实服务。作业与流程使用指南已同步。

本批联合复验：`npx vitest run --maxWorkers=2 src/views-mobile/MWorkers.context.test.ts src/views/worker/workerActions.test.ts src/views/file-center/FileList.arrival.test.ts src/views/scheduler/components/BatchDayGovernanceDrawer.test.ts src/views/scheduler/batchDayGovernance.test.ts src/views/system/AtomicTaskTypeCenter.timeout.test.ts src/views/system/atomicTaskConfigDraft.test.ts` 7 文件 31 项通过；`npm run typecheck`、`npm run check:i18n`、`npm run check:docs`、目标 ESLint 和两仓相关文件 `git diff --check` 通过。文件列表原有测试不验证详情布局，该项以隔离浏览器复核为证。

移动补跑审批查询失败原显示无申请，切换租户时旧列表可能迟到覆盖，已发出的审批返回后也会在新租户误显示旧结果；现提供失败重试、过期数据提示，维护冻结及查询不可用时禁用审批，并按租户和登录上下文隔离列表与操作结果。已发出的请求不能撤销。中文深色 390×844 隔离样例检查首次失败重试、待审批及历史只读记录、维护冻结和只读角色，未提交；对应运行与审批使用指南已更新。

工作流设计器作业节点将“关联 Job / jobCode”等字段和必填提示改为用户可读的作业编码文案。英文深色 280px 隔离样例同时展示名称过长和未选择作业错误，未覆盖下一字段；长编码依赖状态因浏览器导航超时未验证。`npx vitest run --maxWorkers=2 src/views/workflow/designer/inspector/JobNodeForm.test.ts` 21 项、国际化和目标 ESLint 通过；未连接真实服务。

批次日范围重放预览的每日状态改为中英文可读名称，未知状态仍显示原值，候选数量列不再误标为进度；切换试运行时隐藏不适用的结果策略，与单日表单一致。两个新场景先复现失败；`npx vitest run src/views/ops/BatchDayReplay.test.ts` 15 项、目标 ESLint 和国际化检查通过。英文深色 390×844 隔离样例复核了模拟预览及试运行条件字段，未提交或连接真实服务。预览过程中出现 ResizeObserver 和热更新后模拟数据类型警告，不能据此推断真实服务问题。

作业监控策略的旧编辑抽屉原在切换租户后仍可向新租户发送旧作业 ID；现随租户、账号或写权限变化关闭旧编辑，迟到保存结果不覆盖新上下文，已发送请求仍需核对原租户。编辑按钮名称包含作业编码。英文深色 390×844 隔离样例中，长编码曾将抽屉关闭按钮挤出视口、固定操作列遮挡表头；现标题可换行并在窄屏取消固定操作列，复核 CRON 的绝对/相对完成时限及依赖作业相对时限。`npx vitest run src/views/observability/JobMonitoringPolicies.context.test.ts` 6 项及目标 ESLint 通过，使用指南已同步；未连接真实后端或保存。预览出现 ResizeObserver 警告，真实应用是否复现尚未确认。

本批联合复验：`npx vitest run --maxWorkers=2 src/views-mobile/MCatchUp.context.test.ts src/views-mobile/MCatchUp.status.test.ts src/views-mobile/MWorkers.context.test.ts src/views/ops/BatchDayReplay.test.ts src/views/workflow/designer/inspector/JobNodeForm.test.ts src/views/observability/JobMonitoringPolicies.context.test.ts` 6 文件 56 项通过；`npm run typecheck`、`npm run check:i18n`、`npm run check:docs`、目标 ESLint 及相关 `git diff --check` 通过。并行修改期间的类型检查曾因测试中的模拟未知状态超出生成类型失败，明确类型转换后重跑通过。

文件格式的高级选项原使用不可键盘聚焦的链接，现使用按钮并暴露展开状态；中英文 390px 隔离样例检查了 Tab 聚焦、Enter 展开、空格收起及分隔/固定宽格式条件。定向测试 2 文件 23 项和目标 ESLint 通过；仅核查格式组件，未覆盖完整抽屉或真实后端。

账号重置弹窗固定 480px 时在 390px 视口裁掉右侧及关闭按钮，现随视口收缩；英文深色模拟长账号在 390×844 与 1280×800 下复核，未输入或提交密码。该页面目标 ESLint 和 `git diff --check` 通过，账号创建、编辑及真实服务不在本次证据范围。

作业向导新建时，作业编码和类型原未绑定表单校验字段，导致必填规则不能执行；现仅为可编辑状态绑定。窄屏日历与窗口输入框原被新建按钮挤窄，已改为纵向排列，并去掉按钮文字与图标重复的加号。英文深色 390×844 隔离样例复核了第一步错误提示及第二步 Manual/Cron 排列；最后精简的占位文案未能复拍，未覆盖其余步骤或真实提交。新增两项校验测试先失败、修复后通过。

移动审批首次查询失败原显示空列表，切换租户或筛选后旧响应可能覆盖当前列表，拒绝确认期间切租户可能向新租户提交旧审批；现区分查询失败、统计失败和空结果，提供重试，并使过期请求及确认不作用于新上下文；只读角色和维护冻结时禁用操作。统计失败不隐藏已加载的列表。相关运行与审批使用指南已更新；已发出的请求不能撤销。中文深色 390×844 隔离样例复核了首次失败、重试后列表、统计失败及只读角色按钮禁用；未连接真实服务或提交审批。

本批定向联合复验：`npx vitest run --maxWorkers=2 src/views-mobile/MApprovals.context.test.ts src/views-mobile/MApprovals.pagination.test.ts src/views/file-center/FileTemplateFormatFields.test.ts src/views/file-center/FileTemplateList.context.test.ts src/views/job/components/JobConfigBasicForm.validation.test.ts src/views/job/components/JobConfigBasicForm.contract.test.ts src/views/job/JobBundleContextRisks.test.ts` 7 文件、100 项通过；受影响文件 ESLint、`npm run check:i18n` 和 `npm run check:docs` 通过。这些结果不代表全部表单或完整业务验收。

再次复看租户复制初始表单：本人复看中文深色桌面及 390px 隔离样例，另在 1280×800 和 390×844 复拍中，字段与配置类型网格未发现明确错位；目标租户实际必填但缺少标记，现与源租户一致显示必填星号，校验和提交行为不变。`npx vitest run --maxWorkers=2 src/views/system/components/TenantCopyConfigDialog.test.ts` 43 项及组件 ESLint 通过；未复核所有主题、结果及权限状态，未执行真实复制。

类型复核首次发现公共复制组件将可聚焦 SVG 元素误标为仅 HTMLElement，已按 `tabbable` 返回类型修正声明，未改变运行逻辑。重跑 `npm run typecheck` 通过；`npx vitest run --maxWorkers=2 src/components/common/CopyableText.test.ts src/views/system/components/TenantCopyConfigDialog.test.ts` 2 文件、62 项及相应 ESLint 通过。

账户密码表单空提交时，校验提示原采用绝对定位，与下一字段标签间距过小；现让提示参与布局，保留原校验规则。英文深色隔离样例在桌面及 390px 查看了空提交，调整后复拍 390px 错误与下一字段的间距；未输入真实密码或调用服务。`npx eslint src/views/system/MyAccount.vue` 和该文件 `git diff --check` 通过，其他语言及提交状态未复核。

批量账号导入预览的桌面校验原因原被截断且无法查看，现允许完整换行；390px 长用户名原挤压行号，现用户名和租户换行而行号保持单行。英文深色模拟两行的预览及行编辑初始状态修前、修后实拍，相关文件 ESLint 和 `git diff --check` 通过；未真实上传或保存，其他主题待查。

项目视觉门禁 `E2E_BASE_URL=http://127.0.0.1:5710 npm run test:e2e:visual` 1 项通过；该基线只覆盖中文深色登录页，不覆盖上述表单。隔离服务已停止。

资产新鲜度策略表单的时间和数值控件原短于其他字段，现统一为表单宽度；过期宽限按秒提交，表单标签补明单位，列表仍按原有方式格式化。英文桌面及 390px 编辑隔离样例复拍，控件右边线一致；中文 390px 新建和英文新建核对单位，英文窄屏检查空作业编码提示。`npx vitest run --maxWorkers=2 src/views/ops/AssetFreshnessPolicies.test.ts` 3 项、相关 ESLint 和国际化检查通过；未真实保存或连接服务。

工作流监控策略已配置、长编码及只读状态的隔离预览未能加载，不能计为视觉复核；`npx vitest run --maxWorkers=2 src/components/workflow/WorkflowMonitoringPolicyDialog.test.ts` 6 项通过仅作组件测试证据。

本批联合检查：`npx vitest run --maxWorkers=2 src/views/ops/AssetFreshnessPolicies.test.ts src/components/workflow/WorkflowMonitoringPolicyDialog.test.ts` 2 文件、9 项通过；三个改动页面及中英文词条的 ESLint、`npm run check:i18n`、`npm run check:docs` 和相关 `git diff --check` 通过。后端统一报告对应文档结构、日期和代码路径引用检查通过。

工作流监控策略预览恢复后，英文深色模拟已配置策略、长编码桌面及 390px 可见；说明框原与首项标签紧贴，现补足间距，窄屏滚动到底部可见关闭和保存按钮。只读状态的字段禁用且无保存按钮，模拟读取失败显示重试且保存禁用。`npx vitest run --maxWorkers=2 src/components/workflow/WorkflowMonitoringPolicyDialog.test.ts` 6 项及目标 ESLint 通过；未真实保存。

账户页的说明改为面向用户的账号确认和修改密码描述，英文密码占位明确混合字符是强度建议；移动审批卡片将插值后顺序错误的“由 提交 用户”改为“提交人 用户”，英文对应 `Submitted by`。本批文案依据源码和先前隔离预览调整，修改后的浏览器复拍待查。`npx vitest run --maxWorkers=2 src/views-mobile/MApprovals.context.test.ts src/views-mobile/MApprovals.pagination.test.ts src/components/workflow/WorkflowMonitoringPolicyDialog.test.ts` 3 文件、16 项及相关 ESLint、国际化检查通过。

系统参数抽屉的错误提示改为参与布局，避免紧贴下一字段；新建时输入非法键、取消后编辑合法参数原会保留旧错误，现打开新建和编辑时清除旧校验。新增 `SystemParameterList.validation.test.ts` 使用真实 Element Plus 表单复现该路径，修前失败、修后通过；英文深色桌面及 390px 隔离样例复拍错误和编辑状态，未真实保存。该新增测试文件目前未跟踪，交付时需一并纳入。

租户放置分片键的非法格式提示原单独占据表单行，长文案行距过大且距离输入框较远；现移至字段下方并设置告警语义。英文深色桌面新建/编辑及 390px 非法键隔离样例复拍，保存仍禁用；未真实提交。预览夹具未注册埋点指令产生警告，不能视作生产错误。`npx vitest run --maxWorkers=2 src/views/ops/TenantPlacementList.test.ts` 1 项及目标 ESLint 通过；错误与输入的无障碍关联仍待进一步验证。

本批联合验证：`npx vitest run --maxWorkers=2 src/views/system/SystemParameterList.validation.test.ts src/views/ops/TenantPlacementList.test.ts src/views-mobile/MApprovals.context.test.ts src/views-mobile/MApprovals.pagination.test.ts src/components/workflow/WorkflowMonitoringPolicyDialog.test.ts` 5 文件、18 项通过；相关 ESLint、`npm run typecheck`、`npm run check:i18n`、`npm run check:docs` 及已跟踪文件 `git diff --check` 通过；后端文档结构、日期和代码路径引用检查通过。`E2E_BASE_URL=http://127.0.0.1:5710 npm run test:e2e:visual` 1 项通过，仅覆盖登录页；临时服务已停止。

批次窗口创建表单的错误提示改为参与布局，避免紧贴下一标签；时间范围箭头与输入框对齐。英文深色桌面和 390×844 空提交隔离预览修前、修后复拍；`npx vitest run --maxWorkers=2 src/views/job/components/MiniCreateTimezone.test.ts` 8 项、组件 ESLint 通过。未验证其他语言、时间输入和成功提交。

自助重跑和补偿的实例选项原直接显示状态代码，现复用共享状态组件显示中英文名称，未知状态保留原值；补偿提示去掉内部类型和服务器实现术语。新增 `SelfServiceInstanceStatus.test.ts` 覆盖两种申请的中英文和未知状态回退，4 项通过；`SensitiveTenantActions.test.ts` 与 `statusTagResolve.test.ts` 共 19 项通过。新测试文件目前未跟踪；本项尚未做浏览器复拍或真实申请。

分片目录编辑抽屉的端口和连接池控件原短于其他输入，现局部设为等宽；英文深色模拟管理员桌面编辑测得字段均为 520px，390×844 复拍边线及底栏。`npx vitest run --maxWorkers=2 src/views/ops/ShardCatalogList.test.ts` 1 项、组件 ESLint 通过；未真实保存，本次未覆盖新建和错误态，隔离夹具缺少埋点指令产生警告。

租户批量新建表单的空提交错误改为参与布局，避免紧贴下一字段；源租户说明明确留空不复制配置，无可用源时显示实际空态，密码说明补明至少 12 位并将强度作为建议。英文深色桌面及 390×844 隔离样例查看空错误、长名称源选择和两种初始化模式，未输入密码或真实创建；密码提示在错误态按现有规则隐藏。`npx vitest run --maxWorkers=2 src/views/system/components/TenantBatchCreateDialog.test.ts` 1 项通过。夹具预扫描有 PWA 虚拟模块警告，最终预览正常，中文和真实服务未复核。

本批联合验证：`npx vitest run --maxWorkers=2 src/views/system/components/SelfServiceInstanceStatus.test.ts src/views/system/components/SensitiveTenantActions.test.ts src/components/common/statusTagResolve.test.ts src/views/job/components/MiniCreateTimezone.test.ts src/views/ops/ShardCatalogList.test.ts` 5 文件、32 项通过；租户批量新建定向 1 项通过。`npm run typecheck`、`npm run check:i18n`、`npm run check:docs`、相关 ESLint 及后端文档结构、日期和代码引用检查通过；项目视觉基线 `E2E_BASE_URL=http://127.0.0.1:5710 npm run test:e2e:visual` 1 项通过，只覆盖登录页，临时服务已停止。

自助补偿和重跑在 390px 英文长标签原会换行错位，现改为顶部标签；实例选项中的共享状态标签受全局下拉样式影响原分行且圆点错位，已局部恢复同行显示。补偿在作业和业务日已选后改提示“选择目标实例”，中文作业编码标签同步统一。中文补偿及英文重跑的模拟候选在深色 390px 复拍，已知状态显示本地化名称，未知值原样显示；未发送申请或连接真实后端。

日历快捷创建空提交错误原紧贴下一字段，现让错误提示参与布局；英文深色桌面及 390×844 模拟空提交复拍，底栏可见。`npx vitest run --maxWorkers=2 src/views/job/components/MiniCreateTimezone.test.ts` 8 项和组件 ESLint 通过，未创建日历或核查其他语言。

取证导出窄屏日期输入由固定宽度改为与其他字段等宽；已确认的 PROCESSING、COMPLETED、FAILED 使用中英文名称，未知状态保留原值。英文深色 390px 日期逆序错误态修前后复拍；`npx vitest run --maxWorkers=2 src/views/ops/OpsDiagnostic.test.ts` 23 项覆盖状态及未知值，成功结果修后浏览器复拍未完成，未真实导出。夹具依赖预扫描有 PWA 虚拟模块警告，最终加载成功。

租户初始化的英文配置类型网格改为自适应列宽，与复制表单一致；共享 JSON 输入框为解析错误增加中英文前缀并保留浏览器原始定位信息，调整长错误行距。初始化表单非空错误显示详细提示，空值保留必填提示，避免重复。此前非对象 JSON 或纯空格可能通过校验并进入请求或引发解析错误；现拒绝数组、null、字符串、数字及纯空格，对象按原内容提交。相关失败场景先复现、修复后通过；英文深色桌面及 390px 隔离预览复核语法、非对象和空值提示，未真实初始化。使用指南 `batch-console/docs/user-guide/platform-governance.md` 已同步对象要求；新增 `TenantInitConfigDialog.validation.test.ts` 尚未跟踪。浏览器原生错误详情仍可能为英文，预览依赖扫描/热更新产生过夹具警告。

本批阶段性联合测试 `npx vitest run --maxWorkers=2 src/views/system/components/SelfServiceInstanceStatus.test.ts src/views/system/components/SensitiveTenantActions.test.ts src/components/common/statusTagResolve.test.ts src/views/job/components/MiniCreateTimezone.test.ts src/views/ops/OpsDiagnostic.test.ts src/components/common/JsonTextareaInput.test.ts src/views/system/components/TenantInitConfigDialog.validation.test.ts src/views/system/components/TenantInitConfigDialog.test.ts src/views/system/components/ConfigSyncTab.test.ts src/views/file-center/FileTemplateList.context.test.ts src/views/job/components/JobConfigBasicForm.validation.test.ts src/views/job/components/JobConfigBasicForm.contract.test.ts` 12 文件、139 项通过；之后新增纯空格修正，最终 `npx vitest run --maxWorkers=2 src/components/common/JsonTextareaInput.test.ts src/views/system/components/TenantInitConfigDialog.validation.test.ts src/views/system/components/TenantInitConfigDialog.test.ts` 3 文件、33 项通过。首次类型检查发现新增双语测试的 locale 类型错误，修正后 `npm run typecheck` 通过；相关 ESLint、国际化、前端文档及后端文档结构、日期和代码路径检查通过。项目视觉基线 `E2E_BASE_URL=http://127.0.0.1:5710 npm run test:e2e:visual` 1 项通过，仅覆盖登录页。

文件上传选择文件组后的业务日期控件原短于其他字段，现局部设为等宽；英文深色桌面修后测得控件均为 548px，390px 复拍条件项及滚动底栏；模拟文件组加载失败显示错误。`npx vitest run --maxWorkers=2 src/views/file-center/FileList.arrival.test.ts` 2 项及目标 ESLint 通过，测试不证明上传链路，未选择文件或真实上传；夹具有 PWA、自动导入组件和 ResizeObserver 警告，不能外推生产。

资源队列快捷编辑器在英文深色桌面和 390×844 隔离新建样例中，容量输入对齐，最大整数可见，滚动底栏可达；本次未改该组件，父页面空提交和真实保存未验证。密钥配置详情使用模拟引用在英文深色 390px 查看，长引用及 JSON 可见，未执行密钥操作；夹具缺少路由注入并出现临时翻译警告，不能视为生产环境验证。

运行总览的完成和等待统计卡原不可筛选却暴露按钮语义；现仅可筛选卡具有按钮、焦点及按下状态，支持空格切换。`npx vitest run --maxWorkers=2 src/views/runs/RunsOverview.test.ts` 2 项通过；该测试文件尚未跟踪，交付时需纳入。英文深色 390px 模拟预览验证 Running 卡空格切换、筛选和焦点。列表夹具为空，不代表非空详情或真实服务通过。

补充盘点（当时快照）：直接懒加载路由为 84 条、81 个唯一视图（桌面 68、移动 13），不含动态嵌套；当时报告尚无明确页面级浏览器证据的候选包括 `/ops/summary`、`/runs` 非空明细、`/m/ops/summary`、`/m/jobs/:id` 和 `/m/workflow/run/:id`。后续已补充这些页面的模拟预览，具体状态见下文；子组件或列表证据不能外推整页。该快照的 views/views-mobile/components 共 258 个 Vue 文件，其中 `ErrorDetailsText.vue` 和 `FileTemplateFormatFields.vue` 未跟踪，交付快照需包含。

本批联合验证：`npx vitest run --maxWorkers=2 src/views/runs/RunsOverview.test.ts src/views/file-center/FileList.arrival.test.ts src/views/governance/GovernanceEditors.test.ts` 3 文件、6 项通过；相关 ESLint、`git diff --check`、后端文档结构/日期/代码路径引用检查通过。`E2E_BASE_URL=http://127.0.0.1:5710 npm run test:e2e:visual` 1 项通过，仅覆盖登录页；服务已停止。

本批 `npm run typecheck` 首次失败：`src/views-mobile/MExecutionLog.vue:22` 的键盘事件处理参数窄于组件事件联合类型。该文件在检查期间由并行任务更新，另有类型检查进程运行；本批未覆盖其改动，需在并行修改结束后重新核对及验证，不能计为通过。

维护兜底页是独立路由，原本没有挂载只在常规布局运行的维护状态轮询，因此页面声称自动恢复但实际不会自动回跳。现复用现有 `useMaintenancePolling`，同时让长公告和受影响服务名称在窄屏换行；中英文提示不再写死错误的 30 秒。新增未跟踪 `MaintenancePage.polling.test.ts` 先失败、修后通过，覆盖轮询、恢复回跳和卸载停止；英文深色 390px 隔离样例复拍长公告。预览禁网络导致模拟请求失败 toast，不代表生产现象；真实维护环境未验证。`batch-console/docs/engineering/maintenance-degradation.md` 已更新。

移动作业详情的详情及步骤请求失败原分别误显示不存在或无步骤，现内联提示并可重试；步骤重试保留已加载详情。中文深色 390px 模拟步骤失败、重试及长编码已复拍，长步骤现可换行且状态可见；详情首屏失败仅组件测试覆盖，未连接真实服务。新增回归先红后绿，`MJobInstanceDetail.test.ts` 16 项通过，运行与审批使用指南已同步。

运维概览切租户时，旧汇总、趋势及扩展报告迟到会覆盖当前租户数据；现按现有请求作用域隔离，切换时清空旧值并在扩展面板打开时重载，时间范围切换也拒绝旧趋势。3 个场景先红后绿，未跟踪 `useOpsSummary.context.test.ts` 7 项通过；英文深色桌面及 390px 模拟 KPI、摘要失败状态已复核。预览屏蔽 SSE 导致断连和错误 toast，不代表生产；未连接真实服务。可观测性使用指南已同步。

配置同步的预览和应用原允许数组、null、字符串及数字作为配置 JSON 根值；现仅接受对象，四类非对象场景先红后绿；英文桌面配置类型网格调整以容纳长标签。英文深色 390px 隔离预览复核非对象和语法错误、操作区；`ConfigSyncTab.test.ts` 31 项通过，未真实应用，导入使用指南已同步。夹具预扫描的 PWA 模块警告不代表生产构建问题。

本批联合验证：`npx vitest run --maxWorkers=2 src/views/MaintenancePage.polling.test.ts src/composables/useMaintenancePolling.test.ts src/views-mobile/MJobInstanceDetail.test.ts src/views/ops/composables/useOpsSummary.context.test.ts src/views/system/components/ConfigSyncTab.test.ts` 5 文件、57 项通过；并行任务调整移动执行日志事件类型后，`npm run typecheck` 重跑通过，关闭了上一批类型失败记录。国际化、相关 ESLint、前端文档及后端文档结构/日期/代码引用检查通过；`E2E_BASE_URL=http://127.0.0.1:5710 npm run test:e2e:visual` 1 项通过，仅覆盖登录页。

维护恢复补充浏览器复核：隔离路由及内存状态返回先维护中、后恢复，真实组件自动轮询后跳转到模拟原路径；未修改真实维护配置或调用真实服务。预览路由未注册空路径产生夹具警告，不代表生产路由。文档不可用、404 和移动端桌面功能提示也在英文深色 390px 初始状态查看，未操作恢复或导航按钮。

运行总览的作业和工作流栏加载失败原显示空记录，现分别显示错误和重试；切租户后旧请求也可能覆盖新租户列表及统计，现复用租户请求作用域并立即清理旧值。失败和延迟响应均先复现再修复。英文深色桌面及 390px 模拟非空记录、长编码、详情链接与单栏失败重试已检查；未真实跳转详情或连接真实服务。`RunsOverview.context.test.ts`、`RunsOverview.loadError.test.ts` 两个新增测试未跟踪，交付时需纳入；运行与审批指南已同步。

工作流进度首屏请求失败且无快照时原同时显示保留快照与空态，现显示失败和重试；已有快照时保留内容及过期提示。中文深色 390px 隔离样例复核失败状态及稳定渲染后的 DAG；初始蓝块为渲染中间态、夹具 offline 状态不符合生产枚举，均未据此修改产品。未连接真实服务，运行与审批指南已同步。

API Key 列表英文停用状态原显示中文、详情显示布尔值；现列表与详情复用本地化状态标签，创建表单错误提示参与布局。英文深色 390px 模拟停用记录详情已复核，未创建或写入真实密钥；预览夹具缺少 DatetimeColumn 注册，该区域不计入真实应用验证。新增 `ApiKeyList.status.test.ts` 未跟踪。

本批联合验证：`npx vitest run --maxWorkers=2 src/views/runs/RunsOverview.context.test.ts src/views/runs/RunsOverview.loadError.test.ts src/views/runs/RunsOverview.test.ts src/components/workflow/WorkflowExecutionPanel.test.ts src/views-mobile/MWorkflowRunProgress.test.ts src/composables/useWorkflowExecutionProgress.test.ts src/views/system/ApiKeyList.status.test.ts src/views/system/ApiKeyList.scopeHint.test.ts src/views/MaintenancePage.polling.test.ts src/composables/useMaintenancePolling.test.ts` 10 文件、21 项通过；类型、国际化、相关 ESLint、前端文档与后端文档结构/日期/代码引用检查通过。项目视觉基线 `E2E_BASE_URL=http://127.0.0.1:5710 npm run test:e2e:visual` 1 项通过，仅覆盖登录页，服务已停止。

本批核查与整改：通知渠道和规则的英文 390px 抽屉中，连续校验提示距下一字段仅 2px；标准抽屉表单现让错误提示参与排版，样例间距为 16px，桌面渠道错误态也已复看。隔离预览阻断真实请求和写入，其他抽屉状态仍须分别核查。

工作流版本对比的长节点编码原会撑开窄屏双栏；现窄屏上下排列并允许长编码换行，同时限制表格列宽。桌面及 390px 非空长编码使用模拟数据复看，比较逻辑未改；未连接真实服务。移动运维汇总请求失败原显示“暂无数据”，现明确显示失败及重试，并缩小错误区留白；390px 中文非空和失败状态使用模拟数据复看，未验证真实服务及完整导航。失败后重试恢复指标的测试先红后绿，移动运维行为已同步至可观测性使用指南。

共享错误详情原在列表内将错误码和消息直接连在一起，完整详情却含空格；现两处一致。测试先复现失败后修正，中文深色 390px 隔离预览检查了截断、键盘打开完整内容及 Escape 关闭并恢复焦点。`ErrorDetailsText.vue` 和 `ErrorDetailsText.test.ts` 目前均未跟踪，交付快照需纳入；预览未连接真实服务。

本批联合验证：`npx vitest run --maxWorkers=2 src/components/common/ErrorDetailsText.test.ts src/components/common/CopyableText.test.ts src/views-mobile/MOpsSummary.context.test.ts src/views/system/components/NotificationChannelsTab.test.ts src/views/system/components/NotificationRulesTab.test.ts src/views/system/components/NotificationTenantFormFocus.test.ts src/views/workflow/designer/diff/diffAlgorithm.test.ts` 7 文件、52 项通过；类型、国际化、相关 ESLint、前端文档及差异格式检查通过。项目视觉基线 `E2E_BASE_URL=http://127.0.0.1:5710 npm run test:e2e:visual` 1 项通过，仅覆盖登录页，不能替代上述页面的视觉回归。

移动工作流运行页选中长节点编码时，原文本会伸出 390px 视口；现允许选中区域换行。中文浅色隔离样例复核了非空 DAG、节点选择、长编码及失败/重连态，长编码右边界由约 593px 收至 370px；未连接真实服务或验证详情跳转和重试恢复。`npx vitest run --maxWorkers=2 src/views-mobile/MWorkflowRunProgress.test.ts src/components/workflow/WorkflowExecutionPanel.test.ts src/composables/useWorkflowExecutionProgress.test.ts` 3 文件、8 项通过；相关 ESLint 和差异格式检查通过。夹具缺少作业详情路由并在调整视口时出现一次 ResizeObserver 提示，尚无生产复现证据。

配额策略新建抽屉在英文深色桌面及 390×844 隔离样例中检查了空提交的必填提示、数值字段和底栏，未发现明显错位；未填写有效值、保存或连接真实服务。夹具未加载生产 PWA 插件，预扫描出现模块警告，不作为产品构建结果。

文件渠道编辑表单在英文深色桌面和 390px 隔离样例中检查了长编码、字段和 JSON 错误；原来输入已提示无效时保存仍会发送请求。后端要求非空配置为 JSON 对象，现保存前校验语法及对象根类型，空白沿用原有处理；语法错误和数组、null、数字、字符串场景先红后绿，空值与有效对象也有定向覆盖。`npx vitest run --maxWorkers=2 src/views/file-center/FileTemplateList.context.test.ts src/components/common/JsonTextareaInput.test.ts` 2 文件、42 项通过，目标 ESLint 和国际化检查通过。浏览器复核为模拟请求，未连接真实服务；文件导入使用指南已同步。`src/locales/fileTemplateList.zh-CN.ts` 和 `src/locales/fileTemplateList.en-US.ts` 均为既有未跟踪文件，本次新增的中英文提示需随文件一同交付。

本批最终联合复验：`npx vitest run --maxWorkers=2 src/views/file-center/FileTemplateList.context.test.ts src/components/common/JsonTextareaInput.test.ts src/views-mobile/MWorkflowRunProgress.test.ts src/components/workflow/WorkflowExecutionPanel.test.ts src/composables/useWorkflowExecutionProgress.test.ts` 5 文件、50 项通过；类型、国际化、受影响文件 ESLint、前端文档和后端文档结构、日期、路径引用检查通过。

配置导入错误表的展开编辑区原受宽表裁切，390px 下保存按钮超出视口，单条错误时表体高度也不足以展示输入区；现按表格可视宽度布局展开区并提高错误表最小高度。英文深色隔离样例在桌面和 390px 复看，横向滚动表格后错误提示、输入及按钮仍可见；未连接真实服务或保存。`npx vitest run --maxWorkers=2 src/views/config/TenantPackageImportWizard.test.ts` 7 项通过，目标 ESLint 与差异格式检查通过。

分片目录表单原只以短暂通用提示反馈空字段及非法分片键；现分别在字段旁显示错误，修正后即时消失，重新打开表单清除旧错误，原提交校验条件保持不变。英文深色隔离样例检查桌面及 390px 空提交和长键错误；未连接真实服务或执行有效值保存。`npx vitest run --maxWorkers=2 src/views/ops/ShardCatalogList.test.ts` 1 项通过，该新增测试尚未跟踪；相关 ESLint 与差异格式检查通过。

移动 Worker 页英文深色 390px 隔离样例检查非空、长编码、长分组、首屏失败及离线筛选，未发现明显横向溢出；未连接真实服务，节点操作为只读权限且未执行。上述隔离预览可能产生未加载生产插件或指令的夹具警告，不作为产品构建结果。

本批联合复验：`npx vitest run --maxWorkers=2 src/views/config/TenantPackageImportWizard.test.ts src/views/ops/ShardCatalogList.test.ts` 2 文件、8 项通过；类型、国际化、相关 ESLint、差异格式及后端文档结构、日期和代码路径引用检查通过。

移动文件列表原未展示加载失败：首屏失败误显示空列表，后续页失败没有反馈。现显示失败和重试，保留已加载记录并重试失败页；新增未跟踪 `MFileList.error.test.ts` 两个场景先红后绿。英文深色 390px 隔离样例复核首屏失败、重试及长文件名，后续页失败仅组件测试覆盖；未连接真实服务。文件使用指南已同步。

资源标签新建保存失败时原会关闭抽屉并清空输入；现失败保留键值，成功后关闭。新增未跟踪 `TagResourceTab.save.test.ts` 先红后绿；英文深色 390px 隔离样例复核长键值与模拟失败，未连接真实服务。平台治理指南已同步。

工作流依赖弹窗使用内存 DAG 检查中文及英文 390px 的长节点、条件输入和操作按钮，并模拟条件依赖创建；未见明确错位。未连接真实服务或保存设计，重复依赖和循环依赖错误状态仍待核查。

本批联合复验：`npx vitest run --maxWorkers=2 src/views-mobile/MFileList.error.test.ts src/views-mobile/MFileList.filter.test.ts src/views/system/components/TagResourceTab.save.test.ts src/views/system/components/tagResourceIdentity.test.ts` 4 文件、12 项通过；类型、相关 ESLint、前端文档、差异格式及后端文档结构、日期和代码引用检查通过。

移动 Outbox 原在查询失败后显示空列表和零条摘要；现显示失败与重试，加载或失败时隐藏摘要，切换租户清除错误状态。失败后重试成功场景先红后绿，`npx vitest run --maxWorkers=2 src/views-mobile/MOutbox.test.ts` 11 项通过。英文深色 390px 隔离样例复核模拟失败、重试及非空长编码，未连接真实服务；可观测性指南已同步。

批次窗口快捷创建使用英文深色 390px 隔离样例检查了长名称、无效编码、时间缺失、跨天输入及模拟保存失败，未发现明显错位，失败时输入保留；未连接真实服务或验证完整时间规则。

审计文件 ID 筛选原转换为 JavaScript 数字，Long 最大值会舍入、非数字成为 NaN 并被当成无筛选、数字 0 也被请求包装器省略。现页面保留去除首尾空格后的输入文本，包装器保留 0；后端按字符串解析 Long 并拒绝无效值。上述场景先红后绿，英文及中文深色 390px 隔离样例检查了筛选与失败状态；未连接真实服务。`AuditList.fileId.test.ts` 为新增未跟踪文件；可观测性指南已同步。

本批联合复验：`npx vitest run --maxWorkers=2 src/api/observabilityQueries.test.ts src/views/observability/AuditList.fileId.test.ts src/views-mobile/MOutbox.test.ts` 3 文件、19 项通过；类型、相关 ESLint、前端文档、差异格式及后端文档结构、日期和代码路径引用检查通过。

配置发布版本对比原在未选择版本 B 时显示 `0`；现显示选择提示并保持比较禁用，选中后仍按原 ID 比较，回归测试先红后绿。英文深色桌面及 390px 隔离样例检查两版本模拟比较、长路径和未选择状态；未连接真实服务。

移动工作流定义页节点详情的长作业编码和 Worker 组原会挤压标签并溢出，现可在 390px 内换行；有效 ID 加载失败时增加重试，无效 ID 保持原提示。新增失败后重试及无效 ID 回归先红后绿，中文深色 390px 隔离样例复核长编码和模拟恢复；未连接真实服务。运行与审批指南已同步。

本批联合复验：`npx vitest run --maxWorkers=2 src/views/config/ConfigReleaseList.test.ts src/views-mobile/MWorkflowViewer.context.test.ts` 2 文件、25 项通过；类型、相关 ESLint、前端文档、差异格式及后端文档结构、日期和代码引用检查通过。

移动告警列表原在首屏失败时显示暂无告警、翻页失败时仅短暂提示；现明确显示失败和重试，翻页失败保留记录并重试同一页。新增首屏及翻页失败断言先红后绿，`MAlerts.search.test.ts` 为未跟踪文件；英文深色 390px 隔离样例复核失败、重试和长告警，未连接真实服务。可观测性指南已同步。

作业向导第 2 步的错误提示原紧贴下一字段，现参与正常布局；英文长错误在 390px 换行后与下一项保持 18px 间距。英文深色桌面及 390px 隔离样例检查了 CRON、长关联编码和连续错误；未连接真实服务或保存。`npx vitest run --maxWorkers=2 src/views/job/JobBundleContextRisks.test.ts` 63 项通过。

队列新建原对缺失必填字段仅显示短暂提示；现将编码和类型错误保留在对应字段旁，修正后清除，提交校验条件不变。英文深色 390px 隔离样例检查空提交、修正及模拟保存失败；未连接真实服务。`QueueConfig.validation.test.ts` 为既有未跟踪测试，本次扩展需一并交付。

本批联合复验：`npx vitest run --maxWorkers=2 src/views/governance/QueueConfig.validation.test.ts src/views/governance/GovernanceEditors.test.ts src/views-mobile/MAlerts.search.test.ts src/views/job/JobBundleContextRisks.test.ts` 4 文件、72 项通过；类型、相关 ESLint、前端文档、差异格式及后端文档结构、日期和代码引用检查通过。

自助配额申请在英文 390px 空提交时，长标签被挤成两行并紧贴下一字段；现采用顶部标签并让错误提示参与布局，模拟样例中连续错误到下一字段均为 18px。`npx vitest run --maxWorkers=2 src/views/system/components/SelfServiceQuotaChangeTab.validation.test.ts` 3 项通过，类型、相关 ESLint 和差异格式检查通过；未连接真实服务或执行有效提交。

覆盖对账：当前 `src/views`、`src/views-mobile`、`src/components` 中有 66 个 Vue 文件包含 82 个 `<el-form>` 标签，均已登记到下表，且每个文件至少有一种隔离预览；文件级预览不代表每个表单实例、角色或主要状态均已检查。13 个直接懒加载移动视图也都有至少一种页面级浏览器样例。后续已补查移动审批提交人文案、作业详情首屏失败及模拟重试恢复；工作流运行的重试恢复和详情跳转也已用隔离路由检查，真实服务保存及实际权限操作不由模拟预览证明。

桌面路由对账：当前直接懒加载桌面路由 71 条、68 个唯一视图；报告原未找到明确整页预览记录的 10 个候选，现均补充了至少一种隔离页面样例，结果及边界如下。无记录不等于确认此前从未查看，单个样例也不代表全状态验收。

暂停时的整页台账：

| 路由 | 本次证据及未完成项 |
| --- | --- |
| `/ops/custom-task-types` | 英文深色桌面空/非空及详情、390px 非空；窄屏首列额外遮挡未确认。 |
| `/ops/worker-fingerprints` | 英文深色桌面空/非空、390px 非空；摘要成功时指纹分组被隐藏、失败时误显示空态的问题已修复，长构建标识可换行。新增未跟踪 `WorkerFingerprintBoard.summary.test.ts` 先失败后通过，3 项通过；相关 ESLint 和差异格式检查通过。真实服务未验证，可观测性使用指南待同步。 |
| `/workers/my-workers` | 英文深色桌面空/非空、390px 非空；禁网夹具的元数据请求产生提示，不能外推生产。 |
| `/scheduler/snapshot` | 英文深色桌面及 390px 模拟非空策略和摘要；夹具缺少路由元数据、日期列组件和真实依赖等待数据，对应内容未验证。 |
| `/scheduler/batch-days` | 英文深色桌面及 390px 模拟批次日日历；窄屏网格可内部横向滚动至周末；真实服务未验证。 |
| `/system/users` | 英文深色桌面及 390px 模拟账号与权限；登录详情已按现有断点在窄屏改为单列，桌面保留两列，目标 ESLint 和差异格式检查通过。本次修改后全局 typecheck 尚未运行。 |
| `/system/tags`、`/config/management`、`/observability/queries`、`/self-service` | 英文 390px 模拟管理员初始空态；自助配额入口及其抽屉已查看。标签管理桌面还查看了标签搜索切换，配置管理桌面查看了 Secrets 切换。非空数据、实际权限和真实写入未验证；预览路由元数据和组件注册缺失已在夹具修正，不能作为产品问题。 |

用户要求停止后未继续审查或修改业务代码；后续如恢复，应先复核当前工作区，再完成上述未验证项及适用检查，不将隔离预览和局部测试作为全站或真实环境验收。

工作区交付边界：当前前端有 10 个未跟踪生产源文件被现有代码引用，包括 `ErrorDetailsText.vue`、`FileTemplateFormatFields.vue`、文件上传及校验工具、作业及工作流表单校验工具，以及 4 个拆分的中英文词条文件。它们当前位于工作树，但不会出现在仅跟踪文件的 diff 中；后续交付必须一并核对和纳入。未跟踪测试另按实际改动检查；本次未暂存或提交。

视觉基线复验：`E2E_BASE_URL=http://127.0.0.1:5710 npm run test:e2e:visual` 1 项通过，仅覆盖登录入口；本次临时服务已停止。

补充隔离预览：中文深色 390px 移动审批卡片的“提交人”及姓名显示正常；移动作业详情首屏模拟失败后可重试并显示实例及空步骤。英文深色 390px 通知路由模拟结果中，长规则、渠道和订阅名称可截断并查看完整内容。均未连接真实服务或执行写操作，且不代表其他状态已验证。

继续补查：英文深色 390px 用量统计展示两条模拟记录、趋势图和长字段，选择版本后摘要及列表同步筛选；结果版本治理只读模拟两条结果并查看详情，长字段正常换行。其固定操作列原遮挡窄屏状态列，现仅窄屏取消固定；390px 复拍可见状态列和横向滚动后的详情操作，1024px 操作列仍固定。中文深色 390px 移动工作流运行模拟首屏失败、重试恢复后点击作业实例，抵达隔离内存路由；不代表生产路由守卫或真实服务联验。

结果版本治理定向验证：`npx vitest run --maxWorkers=2 src/views/ops/components/ResultVersionGovernancePanel.test.ts` 1 项、目标 ESLint 与差异格式检查通过；固定列布局由上述隔离页面复拍确认。

移动工作流路由补充：源码中，已登录且判定为移动端并未指定 `desktop=1` 时，守卫会将该作业实例路径映射到移动详情；本次没有在生产路由和真实认证下实测。`npx vitest run --maxWorkers=2 src/router/mobileWorkflowRouting.test.ts src/views-mobile/MWorkflowRunProgress.test.ts src/components/workflow/WorkflowExecutionPanel.test.ts src/composables/useWorkflowExecutionProgress.test.ts` 4 文件、10 项通过，但路由测试未断言此作业实例映射，不能替代实测。

工具结果态补查：英文深色 390px 的 CRON 模拟结果原把日期从中间拆行，现每个时间整体换行；文件名结果标签原被长值挤成三行，现窄屏上下排列，长文件名可查看完整内容并用 Escape 关闭。两处均以隔离内存结果复拍，非法 CRON 提示也已查看；未连接真实服务。共享 CRON 输入也用于作业表单，相关作业测试已复验，其他调用场景尚未单独复拍。

本批联合复验：`npx vitest run --maxWorkers=2 src/api/system.test.ts src/views/job/JobBundleContextRisks.test.ts` 2 文件、64 项通过；类型、相关 ESLint、前端文档、差异格式及后端文档结构、日期和代码路径引用检查通过。测试未自动验证 CSS，布局证据来自上述模拟页面复拍。

### 表单逐文件检查台账

以下由当前源码中的 `<el-form>` 清点，包含桌面页面及公共组件；动态组件和自定义表单另查。状态只表示实际证据，空项不代表有缺陷。

| 前端文件 | 表单数 | 浏览器核查状态 |
| --- | ---: | --- |
| `src/views/config/ConfigReleaseList.vue` | 2 | 隔离预览：中文窄屏版本对比初始状态；英文桌面及 390px 未选择版本、模拟比较及长路径，新建焦点及必填错误、详情样例；真实保存及其他状态待查 |
| `src/views/config/TenantPackageImportWizard.vue` | 1 | 隔离预览：英文桌面及窄屏上传、错误行展开及横向滚动，中文 390px 预览、英文 390px 应用阻塞；真实上传/保存/应用及其他状态待查 |
| `src/views/file-center/FileList.vue` | 1 | 隔离预览：英文桌面及 390px 文件组上传条件和底栏、模拟目标加载失败；另有详情长名称；未真实上传，其他状态待查 |
| `src/views/file-center/FileTemplateList.vue` | 2 | 隔离预览：模板、渠道新建及渠道列中英文；英文桌面及 390px 渠道长编码与 JSON 错误，英文 390px 模板编辑初始状态，导入窄屏及导出桌面映射草案的模拟生成与应用；格式子组件中英文 390px 高级项键盘交互；真实保存及其他状态待查 |
| `src/views/governance/GovernanceCalendarEditor.vue` | 1 | 隔离预览：英文深色桌面和 390px 编辑、中文深色 390px 新建；未保存，其他状态待查 |
| `src/views/governance/GovernanceQueueEditor.vue` | 1 | 隔离预览：英文深色桌面及 390px 新建容量、大整数、底栏；父页面空提交、修正和模拟失败；真实保存及其他状态待查 |
| `src/views/governance/QueueConfig.vue` | 2 | 隔离预览：英文 390px 队列新建空提交、修正和模拟保存失败，以及新建窗口及假日初始状态；中文桌面已有跨天窗口编辑及时间预览；真实提交及其他状态待查 |
| `src/views/governance/QuotaPanel.vue` | 1 | 隔离预览：英文深色桌面及 390px 新建空提交、中文桌面编辑和失败态；有效值保存及其他状态待查 |
| `src/views/job/JobDefinitionList.vue` | 3 | 隔离预览：英文桌面及 390px 快速创建和编辑抽屉，英文 390px 作业包导入弹窗与滚动底栏；未提交，其他状态待查 |
| `src/views/job/JobDefinitionWizard.vue` | 1 | 隔离预览：英文桌面第 1–5 步、英文 390px 第 1–6 步；第 2 步长编码和连续错误、第 4 步非法 JSON 阻止继续；真实提交及其他状态待查 |
| `src/views/job/components/CalendarMiniCreateDrawer.vue` | 1 | 隔离预览：英文深色桌面及 390px 空提交，中文深色 390px 空提交与非法编码错误，错误到下一标签间距均为 16px；有效提交及其他状态待查 |
| `src/views/job/components/WindowMiniCreateDrawer.vue` | 1 | 隔离预览：英文深色桌面及 390px 空提交、时间输入、跨天开关及模拟保存失败；其他语言、真实提交及完整时间规则待查 |
| `src/views/login/Login.vue` | 1 | 隔离预览：英文 390px 初始、空值校验、服务不可达及单点失败；未登录，其他状态待查 |
| `src/views/observability/AlertRoutingPanel.vue` | 1 | 隔离预览：中文桌面及 390px 空态、英文 390px 空态与只读记录；运行时关闭编辑，其他状态待查 |
| `src/views/observability/JobMonitoringPolicies.vue` | 1 | 隔离预览：英文 390px 编辑初始、长标题和 CRON/依赖作业条件；未保存，其他状态待查 |
| `src/views/observability/UsageSummary.vue` | 1 | 隔离预览：英文 390px 筛选、空态、模拟非空趋势及版本筛选；真实服务及其他状态待查 |
| `src/views/ops/AssetFreshnessPolicies.vue` | 1 | 隔离预览：英文桌面及 390px 编辑、新建单位与必填提示；中文 390px 新建单位；未保存，其他状态待查 |
| `src/views/ops/BatchDayReplay.vue` | 2 | 隔离预览：英文 390px 新建及日期范围重放初始、模拟范围预览和试运行条件字段；提交及其他状态待查 |
| `src/views/ops/OpsDiagnostic.vue` | 1 | 隔离预览：英文 390px 初始与日期逆序错误；成功结果状态修后复拍待查，未真实导出 |
| `src/views/ops/ShardCatalogList.vue` | 1 | 隔离预览：英文桌面及 390px 新建初始和空提交、长键错误，深色编辑对齐；有效保存及其他状态待查 |
| `src/views/ops/TenantPlacementList.vue` | 1 | 隔离预览：英文深色桌面新建/编辑及 390px 非法键；未提交，其他状态待查 |
| `src/views/ops/components/ResultVersionGovernancePanel.vue` | 1 | 隔离预览：中文桌面筛选对齐、英文桌面及 390px 只读模拟结果、详情和横向滚动；真实服务及其他状态待查 |
| `src/views/scheduler/BatchDayWindow.vue` | 2 | 隔离预览：中文窄屏补跑抽屉及单次补跑弹窗、桌面摘要；提交和其他状态待查 |
| `src/views/scheduler/components/BatchDayGovernanceDrawer.vue` | 3 | 隔离预览：桌面摘要及查询，英文 390px 操作确认、原因输入和缺口结果；未提交，其他状态待查 |
| `src/views/scheduler/components/DependencyWaitPanel.vue` | 3 | 隔离预览：窄屏持久等待筛选与空态，英文 390px 日历失败及恢复、作业失败；其他状态和作业重试待查 |
| `src/views/scheduler/components/dependency-waits/WaitRelationshipGraph.vue` | 1 | 隔离预览：桌面空态和筛选，以及英文桌面、390px 两条依赖数据图与明细；真实服务及其他状态待查 |
| `src/views/setup/InitialTenantSetup.vue` | 1 | 隔离预览：英文 390px 初始表单与说明；未输入凭据或提交，其他状态待查 |
| `src/views/system/AiChat.vue` | 1 | 隔离预览：中文深色桌面、中英文 390px 会话和审计空态；未发送消息，真实服务及其他状态待查 |
| `src/views/system/ApiKeyList.vue` | 1 | 隔离预览：新建、权限及英文 390px 停用详情；真实密钥创建和其他状态待查 |
| `src/views/system/AtomicTaskTypeCenter.vue` | 2 | 隔离预览：英文桌面及 390px 表单，英文窄屏 SQL、Shell、HTTP 输入及预览，存储过程合法数组及非法列表；提交与其他状态待查 |
| `src/views/system/MaintenanceSettings.vue` | 1 | 隔离预览：英文 390px 日期输入及恢复时间提示；提交和其他状态待查 |
| `src/views/system/MyAccount.vue` | 1 | 隔离预览：英文深色桌面及 390px 空提交校验，390px 修后复拍；未输入凭据或提交，其他状态待查 |
| `src/views/system/SystemParameterList.vue` | 1 | 隔离预览：英文深色桌面及 390px 新建错误、关闭后编辑；未保存，其他状态待查 |
| `src/views/system/UserAccountList.vue` | 3 | 隔离预览：新建、编辑及重置初始状态；英文深色 390px 与桌面长账号重置弹窗；未输入密码或提交，其他状态待查 |
| `src/views/system/UserBatchProvisioningDialog.vue` | 1 | 隔离预览：英文深色桌面及 390px 模拟两行预览和行编辑初始；真实上传、保存及其他状态待查 |
| `src/views/system/components/ConfigSyncTab.vue` | 2 | 隔离预览：英文桌面网格、390px 非对象与语法错误；真实提交和其他状态待查 |
| `src/views/system/components/NotificationChannelsTab.vue` | 1 | 隔离预览：首次打开、焦点与空提交；中文浅色桌面及 390px 编辑抽屉；英文桌面及 390px 连续错误提示；其他状态待查 |
| `src/views/system/components/NotificationRoutePreview.vue` | 1 | 隔离预览：英文 390px 初始状态及事件选择、模拟规则和订阅结果与长名称；真实服务及其他状态待查 |
| `src/views/system/components/NotificationRulesTab.vue` | 1 | 隔离预览：首次打开、焦点与空提交；中文浅色桌面及 390px 编辑抽屉；英文 390px 连续错误提示；其他状态待查 |
| `src/views/system/components/NotificationTerminalDeliveriesTab.vue` | 1 | 隔离预览：中文 390px 重投弹窗初始状态；未提交，其他状态待查 |
| `src/views/system/components/NotificationWebhooksTab.vue` | 1 | 隔离预览：英文桌面及 390px 新建初始抽屉，英文 390px 编辑及投递日志；提交和其他状态待查 |
| `src/views/system/components/OidcIdentityBindingsDrawer.vue` | 1 | 隔离预览：英文桌面及 390px 初始空态、英文桌面样例表格；未写入，其他状态待查 |
| `src/views/system/components/SelfServiceCompensationTab.vue` | 1 | 隔离预览：中文 390px 模拟实例候选及提示；提交和其他状态待查 |
| `src/views/system/components/SelfServiceQuotaChangeTab.vue` | 1 | 隔离预览：中文窄屏初始状态、英文深色 390px 空提交及连续错误；提交由组件测试覆盖，真实服务待查 |
| `src/views/system/components/SelfServiceRerunTab.vue` | 1 | 隔离预览：英文 390px 模拟实例候选与状态；提交和其他状态待查 |
| `src/views/system/components/SelfServiceTriggerPauseTab.vue` | 1 | 隔离预览：英文窄屏初始状态；提交和其他状态待查 |
| `src/views/system/components/TagResourceTab.vue` | 1 | 隔离预览：英文 390px 新建长键值和模拟保存失败；未验证父页面入口、真实提交及其他状态 |
| `src/views/system/components/TenantBatchCreateDialog.vue` | 1 | 隔离预览：英文深色桌面及 390px 空错误、源选择和两种初始化模式；真实创建、中文及其他状态待查 |
| `src/views/system/components/TenantCopyConfigDialog.vue` | 1 | 隔离预览：桌面中文、窄屏中英文；其余状态待查 |
| `src/views/system/components/TenantFormDialog.vue` | 1 | 隔离预览：桌面及窄屏样例；其余状态待查 |
| `src/views/system/components/TenantInitConfigDialog.vue` | 1 | 隔离预览：英文桌面及 390px 网格、语法、非对象和空值提示；真实初始化及其他状态待查 |
| `src/views/workflow/WorkflowDefinitionList.vue` | 2 | 隔离预览：英文 390px 筛选、运行弹窗、模拟校验结果、调度条件状态和详情，桌面详情；未运行或保存，其他状态待查 |
| `src/views/workflow/designer/inspector/DependencyCreateDialog.vue` | 1 | 隔离预览：中文桌面、英文窄屏初始状态，英文桌面及 390px CONDITION 表达式与内存依赖创建；真实保存、重复和循环错误状态待查 |
| `src/views/workflow/designer/inspector/EdgeInspector.vue` | 1 | 隔离预览：英文 280px 初始、长编码条件表达式错误及改正；其他状态待查 |
| `src/views/workflow/designer/inspector/EndNodeForm.vue` | 1 | 隔离预览：英文 280px 初始表单及 257 字符名称错误；其他状态待查 |
| `src/views/workflow/designer/inspector/GatewayNodeForm.vue` | 1 | 隔离预览：英文 280px 初始、分支和汇聚及 N_OF 数量条件；其他状态待查 |
| `src/views/workflow/designer/inspector/JobNodeForm.vue` | 1 | 隔离预览：英文 280px 初始、名称过长与缺少作业错误；长编码依赖状态及其他状态待查 |
| `src/views/workflow/designer/inspector/StartNodeForm.vue` | 1 | 隔离预览：英文 280px 初始表单及 257 字符名称错误；其他状态待查 |
| `src/components/dialogs/DryRunPlanDialog.vue` | 1 | 隔离预览：英文 390px L1/L2/L3 及条件业务日期；未运行试算，结果及其他状态待查 |
| `src/components/table/ListPageQueryBar.vue` | 1 | 工作流列表英文 390px 筛选栏隔离预览；其他调用页面与状态待查 |
| `src/components/tools/CronToolPanel.vue` | 1 | 隔离预览：英文 390px 初始、模拟三次运行时间及非法表达式；真实服务及其他状态待查 |
| `src/components/tools/FileNamePreviewPanel.vue` | 1 | 隔离预览：英文 390px 初始、模拟长文件名结果及完整查看；真实服务及其他状态待查 |
| `src/components/tools/RetryTimelinePanel.vue` | 1 | 隔离预览：英文 390px 初始表单与时间线；其他状态待查 |
| `src/components/tools/TimezoneConverterPanel.vue` | 1 | 隔离预览：英文 390px 初始表单与转换结果；其他状态待查 |
| `src/components/workflow/WorkflowBulkTriggerDialog.vue` | 1 | 隔离预览：英文 390px 初始弹窗、样例表格与底栏；提交及其他状态待查 |
| `src/components/workflow/WorkflowMonitoringPolicyDialog.vue` | 1 | 隔离预览：英文桌面及 390px 已配置、长编码和底栏，另核对只读及读取失败；未保存，其他状态待查 |

### 非 `<el-form>` 输入组件候选清单

静态识别模板中含原生输入或常见 Element 输入控件、但不含 `<el-form>` 的文件。命中不等于独立表单或存在缺陷；其中部分已在前文或其他页面复核。动态组件、封装控件和未列出的标签仍需沿路由与调用方补查。

**src/views：39 个文件**

- `src/views/approvals/components/CatchUpApprovalsTab.vue`
- `src/views/approvals/components/GeneralApprovalsTab.vue`
- `src/views/file-center/ArrivalGroupList.vue`
- `src/views/file-center/FilePipelineObservability.vue`
- `src/views/file-center/FileTemplateFormatFields.vue`
- `src/views/file-center/MappingDraftAssistant.vue`
- `src/views/governance/components/GovernanceFilterBar.vue`
- `src/views/job/components/JobConfigBasicForm.vue`
- `src/views/job/components/JobDefinitionTaxonomyFilters.vue`
- `src/views/job/components/JobMonitoringFields.vue`
- `src/views/log/ExecutionLog.vue`
- `src/views/monitor/JobInstanceDetail.vue`
- `src/views/monitor/JobInstanceList.vue`
- `src/views/monitor/JobStepInstanceList.vue`
- `src/views/monitor/WorkflowRunDetail.vue`
- `src/views/monitor/WorkflowRunList.vue`
- `src/views/observability/AuditList.vue`
- `src/views/observability/EventCatalog.vue`
- `src/views/observability/LineageEvidence.vue`
- `src/views/observability/LineageRelationExplorer.vue`
- `src/views/observability/OperationAuditList.vue`
- `src/views/observability/OutboxList.vue`
- `src/views/observability/TraceDiagnostic.vue`
- `src/views/observability/components/ChannelReceiptsTab.vue`
- `src/views/observability/components/DeadLettersTab.vue`
- `src/views/observability/components/ExecutionLogsTab.vue`
- `src/views/observability/components/RetriesTab.vue`
- `src/views/ops/CapacityProfile.vue`
- `src/views/ops/components/OpsExtraPanel.vue`
- `src/views/ops/components/TenantDailyProgress.vue`
- `src/views/reports/ReportExportHub.vue`
- `src/views/scheduler/BatchDayList.vue`
- `src/views/system/TenantList.vue`
- `src/views/system/TriggerList.vue`
- `src/views/system/UserRole.vue`
- `src/views/system/components/NotificationDeliveryLogsTab.vue`
- `src/views/system/components/TagSearchTab.vue`
- `src/views/worker/WorkerManagement.vue`
- `src/views/workflow/designer/toolbar/NodeSearchBox.vue`

**src/views-mobile：1 个文件**

- `src/views-mobile/MExecutionLog.vue`

**src/components：16 个文件**

- `src/components/common/AiAssistantLauncher.vue`
- `src/components/common/AiImageAttachmentPicker.vue`
- `src/components/common/AiTextAttachmentPicker.vue`
- `src/components/common/CodeNameBuilder.vue`
- `src/components/common/CommandPalette.vue`
- `src/components/common/CronExprInput.vue`
- `src/components/common/DateRangePresetPicker.vue`
- `src/components/common/InstantPicker.vue`
- `src/components/common/InstantRangePicker.vue`
- `src/components/common/JsonTextareaInput.vue`
- `src/components/common/MetaSelect.vue`
- `src/components/common/ProPagination.vue`
- `src/components/common/StrongPasswordInput.vue`
- `src/components/common/TenantIdInput.vue`
- `src/components/common/TenantSelect.vue`
- `src/components/common/TraceIdInput.vue`

## 2026-11-02：租户复制未知结果的真实浏览器验证

后端基线 `fb7ec82a05bb3c3d722afe243e2db22a656e1730`，前端基线 `26bd888157e616da3acf11b11ed028ed4a04c7d3`；验证前后两仓均无未提交源码变更。`./mvnw -ntp -pl batch-console-api -am -Dmaven.test.skip=true package` 成功，测试跳过；日志 `/private/tmp/closure-main-console-build.log`。

执行 `python3 /private/tmp/wdi-tenant-copy-browser-draft/runner.py`：真实 Chromium 登录、认证和 CSRF；在专属 PostgreSQL 和 Valkey 中将源租户 `RESOURCE_QUEUE` 复制到两个目标，在确认真实服务端提交及两个目标回执后中断浏览器响应，刷新后使用原请求键回查。页面结果、目标队列和数据库修订一致，回查没有产生第二次写请求。最终通过日志 `/private/tmp/closure-tenant-copy-browser-retry9.log`，专属进程和容器已清理。该结果仅覆盖同一登录会话、该配置类型和本地隔离服务，不外推所有配置类型、跨账号、目标部署或混版场景。

首次因本机缺少对应 Chromium 未进入登录；随后若干次因临时探针混淆两个下拉选项或复选框定位超时，均未提交复制请求，保留 `/private/tmp/closure-tenant-copy-browser.log` 和 `/private/tmp/closure-tenant-copy-browser-retry*.log`。最终测试针对各自关联的下拉列表定位。此前作业包浏览器验证与本次租户复制验证属于不同运行快照，不能合称单次全链路验收。

## 2026-11-01：本地关键边界与前后端联验续查

**后续串行复验（覆盖下方对应的历史待验状态）**：修复复制弹窗租户切换和作业包迟到响应后，`npx vitest run --maxWorkers=2 src/utils/configBatchRecovery.test.ts src/api/configBatchRecovery.test.ts src/api/configBatchMutationKeys.test.ts src/views/system/components/TenantCopyConfigDialog.test.ts src/views/job/JobBundleContextRisks.test.ts` 共 5 个文件、109 项通过，日志 `/private/tmp/wdi-config-batch-frontend-retest-20261101.log`。前述真实 HTTP 回查暴露的 JSON 序列化问题修复后，`./mvnw -ntp -pl batch-console-api -am '-Dtest=ConfigBatchOperationRecoveryServiceTest,ConsoleConfigBatchRecoveryControllerTest,ConfigSyncAuditRecoveryIntegrationTest#shouldRecoverAuthenticatedJobBundleImportWithRepeatedTargets+shouldRecoverAuthenticatedTenantCopyWithoutRepeatingTargetWrites+shouldRecoverAuthenticatedJobBundleCreate+shouldRecoverConfirmedStrictRollbackAfterTargetWrite' -Dsurefire.failIfNoSpecifiedTests=false test` 共 18 项通过，包含 4 项真实认证 HTTP 和隔离 PostgreSQL 用例，日志 `/private/tmp/wdi-config-batch-http-fixed-20261101.log`。这些结果不等同于网络断连后的真实浏览器联验。

**补充静态检查**：当前前端源码 `npm run typecheck`、`npm run check:i18n`、`npm run gen:api:check`、`npm run check:docs` 及本批受影响文件 ESLint 均通过；后端文档结构和脚本治理检查通过。脚本治理首次发现实际触发观察记录的两个 SQL 未列入索引，补充 `scripts/db/README.md` 后重跑通过。两仓的工作区和暂存区 `git diff --check` 均通过。

**后端受影响类串行复验**：`./mvnw -ntp -pl batch-console-api -am '-Dtest=ConfigBatchOperationMapperIntegrationTest,ConsoleIdempotencyRequestIdentityTest,ConsoleIdempotencyInterceptorTest,TenantConfigInitOutcomeTest,DefaultConsoleTenantConfigInitApplicationServiceTest,ConfigBatchOperationRecoveryServiceTest,ConsoleConfigBatchRecoveryControllerTest,ConsoleTenantConfigInitValidationTest,DefaultConsoleTenantConfigCopyServiceTest,DefaultConsoleJobBundleApplicationServiceTest,ConsoleJobBundleControllerTest,TenantWipeScriptsIntegrityIntegrationTest,ConfigSyncAuditRecoveryIntegrationTest#shouldRecoverAuthenticatedJobBundleImportWithRepeatedTargets+shouldRecoverAuthenticatedTenantCopyWithoutRepeatingTargetWrites+shouldRecoverAuthenticatedJobBundleCreate+shouldRecoverConfirmedStrictRollbackAfterTargetWrite' -Dsurefire.failIfNoSpecifiedTests=false -Dtestcontainers.reuse.enable=false test` 退出 1：114 项中 110 项通过、4 项错误、0 断言失败、0 跳过。4 项均属 `ConfigSyncAuditRecoveryIntegrationTest`，首个错误为隔离 PostgreSQL 容器等待启动超时，Spring 上下文未能初始化，业务未执行；剩余 3 项受上下文失败阈值影响。其余包括真实数据库 Mapper 5 项和租户清理脚本 3 项通过；日志 `/private/tmp/wdi-console-target-full-20261101.log`。这次失败不能覆盖同一 4 项此前成功的记录，也不能称本轮全部通过。

超时的 4 项随后以 `./mvnw -ntp -pl batch-console-api -am '-Dtest=ConfigSyncAuditRecoveryIntegrationTest#shouldRecoverAuthenticatedJobBundleImportWithRepeatedTargets+shouldRecoverAuthenticatedTenantCopyWithoutRepeatingTargetWrites+shouldRecoverAuthenticatedJobBundleCreate+shouldRecoverConfirmedStrictRollbackAfterTargetWrite' -Dsurefire.failIfNoSpecifiedTests=false -Dtestcontainers.reuse.enable=false test` 单独重跑，4 项通过，日志 `/private/tmp/wdi-http-four-retry-20261101.log`；此前 114 项整批命令仍记录为失败。

**边界与新增测试状态**：双独立 Orchestrator 进程缓存失效及独立 Worker 请求在途时导入渠道配置的测试已通过，证据见下。浏览器探针已补容器匿名卷清理和断连断言，运行结果见下。仅针对实际复现或确定的契约缺陷修复；复制弹窗跨标签账号切换候选尚无该路径复现，不据此添加生产防御。

**真实浏览器首次尝试**：使用当前源码构建的 Console 可执行包、专属 PostgreSQL/Valkey 与前端快照执行探针，空库迁移成功、Console 就绪且匿名请求返回 401；页面提交和断连阶段探针报错，未取得恢复结果，退出 1。脱敏记录 `/private/tmp/wdi-browser-recovery-run-20261101.log`，本次隔离进程和容器按 owner 清理；具体原因仍在定位，不能作为业务或浏览器联验通过。构建命令 `./mvnw -ntp -pl batch-console-api,batch-worker/dispatch -am -Dmaven.test.skip=true package` 成功，该命令跳过测试。

**真实浏览器第二次尝试**：修复临时 Vite 字体目录白名单并增加阶段诊断后，探针在导入弹窗选择目标租户时超时，尚未提交；日志 `/private/tmp/wdi-browser-recovery-retry-20261101.log`。该次 Vite 日志有依赖优化引起页面重载，但不能据此断定它就是选择超时的原因。测试夹具已增加导航观察与失败诊断，待重新验证；尚无浏览器端原键回查通过证据。

**真实浏览器第三次尝试**：已观测到开发服务器触发主页面重载；重载后探针尝试再次打开导入弹窗时，已有弹窗仍存在而点击超时，尚未发送业务请求。日志 `/private/tmp/wdi-browser-recovery-retry2-20261101.log`；本轮不能证明或否定业务恢复能力，临时夹具待修正。

**真实浏览器最终重跑通过（快照边界）**：修正多选框的实际聚焦、标签选择和目标确认后，`python3 /private/tmp/wdi-browser-recovery-draft/runner.py` 退出 0；真实 Chromium 登录、CSRF、两个目标正式导入，拦截到后端成功及数据库写入后丢弃浏览器响应，刷新页面以原键只读回查，核对页面未知提示、完成提示、逐目标结果、原请求完成标记、配置及修订号，未重复写入。日志 `/private/tmp/wdi-browser-recovery-retry4-20261101.log`；仅清理本轮 owner 资源。此为作业包导入本地链路，不代表租户复制已做同等浏览器断连复验或目标环境验收。运行期间另一任务恢复对共享前端工作树的改动：浏览器使用临时前端副本和此前构建的 Console JAR，隔离库实际迁移至新增 V375；这不是两个仓库最新源码的原子快照，不能外推为最新整仓联验。

**端到端测试编译问题**：双进程用例首次编译因该模块没有 Lombok 而失败，已改用现有 SLF4J 依赖；第二次暴露现有 SDK 测试将列表传给字符串类型的清理服务参数，已按当前服务契约传入单个租户字符串。两次均未运行目标用例，原日志分别为 `/private/tmp/wdi-two-orchestrators-20261101.log` 和 `/private/tmp/wdi-two-orchestrators-retry-20261101.log`；修正后的定向验证结果另记。

**独立进程缓存联验通过**：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=RuntimeConfigImportCacheE2eIT#shouldInvalidateIndependentOrchestratorCachesForImportedTenant' -Dsurefire.failIfNoSpecifiedTests=false -Dtestcontainers.reuse.enable=false test` 退出 0，1 项通过。两个不同 Orchestrator JVM 各自预热两个租户缓存，正式导入后核对数据库及目标回执、两进程失效事件、新配置读取和另一租户原值。日志 `/private/tmp/wdi-two-orchestrators-retry2-20261101.log`。本测试采用 120 秒本地缓存和 1 小时对账间隔以隔离自然过期/周期对账，使用测试安全旁路；不代表生产参数、鉴权、混版或目标环境验证。

**独立 Worker 在途导入联验通过**：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=WorkerProcessRestartRecoveryE2eIT#shouldCompleteInFlightDispatchWhenChannelIsImported+shouldContinueDispatching_whenWorkerProcessRestarted' -Dsurefire.failIfNoSpecifiedTests=false -Dtestcontainers.reuse.enable=false test` 退出 0，2 项通过。独立 Dispatch Worker 的旧地址请求被真实接收后，测试执行正式导入并确认新地址及目标回执，再放行旧请求，核对任务成功和新地址未收到旧请求；原有进程重启用例同时通过。日志 `/private/tmp/wdi-worker-inflight-target-20261101.log`。导入通过应用服务执行，此项不覆盖 Console HTTP 鉴权或目标部署环境。

**原键回查整改进行中**：已为租户复制及作业包创建、导入接入按原 HTTP 身份登记和逐目标回执；成功回执与目标写入同事务，缺失项保持未知，重复目标按序号区分。前端在正式提交前保存原键，支持同账号、原请求租户及同标签页刷新后的只读回查；用户可确认停止跟踪，该操作不改变服务端结果。历史请求、无键操作、试运行、跨标签页及服务端未登记的请求不保证可回查。当前不保存原请求正文，不自动续跑未确认目标；混合新旧版本、独立角色完整部署及目标环境未验证。

**本批验证与未完成项**：前端 `npx vitest run --maxWorkers=2 src/utils/configBatchRecovery.test.ts src/api/configBatchRecovery.test.ts src/api/configBatchMutationKeys.test.ts src/views/system/components/TenantCopyConfigDialog.test.ts src/views/job/JobBundleContextRisks.test.ts`：5 个文件、94 项通过（`/private/tmp/wdi-config-batch-frontend-final-20261101.log`）；前端 i18n、文档与 API 生成一致性检查通过。首批后端定向运行中，真实 PostgreSQL Mapper 事务及普通角色隔离 5 项通过；另有 1 项纯单测因 Mockito 缺少空回执桩报错，补齐后 `ConfigBatchOperationRecoveryServiceTest` 5 项通过。随后包含真实认证 HTTP 三个新用例的重跑失败：隔离 PostgreSQL 启动超时，三项均未进入业务；同次运行后段出现多个 class 缺失，同时有其他 Maven 进程使用同一项目和 `target` 目录，不能把该次失败或既有单项通过作为当前完整结果。日志分别为 `/private/tmp/wdi-config-batch-recovery-target-20261101.log`、`/private/tmp/wdi-config-batch-service-rerun-20261101.log`、`/private/tmp/wdi-config-batch-recovery-full-target-20261101.log`。待其他任务结束并稳定源码后，应串行重跑受影响类及真实认证用例，再以新版制品运行已准备的真实 HTTP 响应断连回查探针；当前尚无该修复的真实浏览器与后端联验结论。

本批最终前端类型检查因资源竞争由本任务终止，exit 143；补充“停止跟踪”前的类型检查曾通过，但不代表最终源码已复验。停止时误将另一前端任务的 `vue-tsc` 进程识别为本任务进程并发送 SIGTERM，该检查被中断，未修改其文件；随后已停止本任务自己的重复类型检查。两任务的类型检查均需各自复验。

并发期间，工作树索引新增了本批 V373、回查代码和其他任务的 V374 等新文件；本任务未执行暂存，保持观察到的索引状态，不据此推断用户希望提交。其他任务仍在修改和构建同一工作树时，不重新使用共享 Maven `target` 或覆盖其文件。

**租户重置衔接待验**：两份租户重置 SQL 已补删除被清理目标租户的 V373 回执，并删除被清理审计租户的操作及其级联回执；未被清理的审计操作保留原目标列表，已删回执的目标仍为未知。`TenantWipeScriptsIntegrityIntegrationTest` 已补相应隔离数据库案例，但尚未运行。脚本需要已应用 V373 且执行账号具备跨租户清理权限，不曾在共享数据库执行。混版期间旧实例不会登记新回执，旧实例仍处理写请求时不能承诺原键恢复覆盖所有请求。

**前端续核待整改**：复制抽屉的同步 watcher 在租户 store 更新、localStorage 尚未写入时读取请求租户，可能错误显示租户不一致并隐藏待查记录；需保持旧结果同步失效，待 store 写入完成后刷新列表并补真实 setter 顺序的组件回归。作业包页面处理回查和提交的迟到响应时，未再次比较实际请求租户头；跨标签切换后的影响以及成功后本地记录清理失败的提示需补针对性复核和修复。保存原键与拦截器读取租户头之间的跨标签交错尚未复现；该拦截器为同步执行，不能按普通异步微任务竞态认定为现有 Bug。上述事项因配对前端工作树正由另一任务使用，尚未修改或验证。

**并行任务暂停后的复验进展**：复制弹窗用真实 Pinia setter 的切租户用例首红（1 项失败，日志 `/private/tmp/wdi-copy-tenant-switch-red-20261101.log`）；作业包用例模拟另一标签切换请求租户后，旧成功响应错误清除原记录，首红 1 项（`/private/tmp/wdi-job-recovery-late-tenant-first-red-20261101.log`）。两个修复已写入，修复后测试尚待运行。后端 4 条真实认证 HTTP/隔离 PostgreSQL 用例已进入业务，但全部失败：实际响应把 Jackson 2 的 JSON 节点按普通对象输出；原目标写入和已知回滚已发生，首次原始响应的重复目标更新计数为 1，失败位于回查结果或摘要。日志 `/private/tmp/wdi-config-batch-http-retry-20261101.log`。已将响应边界改为目标回执 record 和普通元数据对象；需修正相关测试并重跑后才能称为修复通过。另一标签切换账号时复制弹窗的迟到响应目前只有源码候选，尚无该具体路径的真实浏览器复现。

**原始目标**：完成本地可执行的配置导入、缓存失效和在途任务；事务、锁、租约与副作用；SQL 状态、租户隔离、归档恢复；配置复制与作业包未知结果、跨账号权限、浏览器真实后端操作及告警最终收件验证。分别记录当前源码、实测层级、未验证和目标环境依赖，不以独立测试结果推断完整链路。

**工作区与授权**：后端 `file-batch-system`、前端 `batch-console` 开始时均为 `perf/local-loadtest-2026-10-31`；审查期间两仓当前分支被重命名为 `fix/bug-fixes`，HEAD 和工作区路径未变，既有未提交改动保留。隔离 Testcontainers 和本地测试接收端用于本轮验证；本机现有共享 Compose 与其数据不作为写入目标。未发送外部通知。

**环境重建后的角色用例复验**：`./mvnw -ntp -pl batch-console-api -am '-Dtest=ConfigCacheInvalidationOutboxIntegrationTest#shouldRequireTableGrantsAndPolicy_whenDeliveryRoleClaims' -Dsurefire.failIfNoSpecifiedTests=false test`：隔离 PostgreSQL 等真实依赖启动，指定方法 1 项通过、0 失败、0 错误、0 跳过，exit 0；日志 `/private/tmp/wdi-outbox-role-method-20261101.log`。该结果只覆盖该方法的表授权、RLS 策略和领取边界，不代表分角色部署的整条链路已验收。

**待业务决定**：共享文件多作业归档的回看口径（首次、逐作业快照或最新状态），以及显式补跑作业的请求日历与历史冻结日历归属，均不预设答案、不按 Bug 修改。两项分别需要业务方裁定；其他验证继续。

**隔离数据库定向验证 PASS**：`./mvnw -ntp -pl batch-orchestrator -am '-Dtest=WorkflowArchiveIntegrityIntegrationTest#shouldSerializeWorkflowNodeAttachmentAgainstJobInstanceDelete+shouldRetainHotTreeAndRollbackColdNodesWhenRootCopyFails+shouldAcceptMatchingColdCopiesWhenRetryingArchive,ArchiveColdStorageIntegrationTest#shouldSerializeCompensationAssociationWithArchive+shouldNotLoseCommittedStepUpdateDuringArchive+shouldRetainHotTree_whenLogReferencesForeignTenantInstance,MultiTenantIsolationIntegrationTest#shouldNotEnforceTimeout_whenLastRunningTaskWasReclaimedAfterScan+shouldNotExposeJobInstanceAcrossTenants+shouldRejectForgedCrossTenantWorkflowAssociation' -Dsurefire.failIfNoSpecifiedTests=false test`：9 项通过，0 失败、0 错误、0 跳过；PostgreSQL 17.11 隔离容器从空库应用 371 个 Flyway 迁移。覆盖归档并发锁、复制失败回滚和重试、跨租户保护及租约回收后的旧超时候选；不代表全部事务或目标环境通过。完整日志 `logs/verification/local-boundaries-2026-11-01/archive-lock-isolation.log`。

**前端提示 PASS，恢复仍未完成**：配置复制、作业包导入和创建向导的正式请求遇网络中断、5xx 或冲突时提示结果可能未知并要求核对目标；明确 400 和预演不误提示。两个组件测试文件 61 项通过，定向 ESLint、i18n、`npm run check:docs` 和最后一次 `npm run typecheck` 通过。该提示不保存原请求或支持跨刷新回查，不能称为持久回执恢复；同版真实浏览器与后端待执行。

**缓存失效竞态首红（历史）**：`OrchestratorConfigCacheRefillRaceIntegrationTest#shouldNotRefillOldDefinitionAfterInvalidation` 在隔离真实 Redis 和受控 Mapper 下固定旧读取→失效→旧读取回填，后续读期望“新配置”实际得到“旧配置”；1 项失败、0 错误、0 跳过，Maven exit 1，首红日志 `/private/tmp/config-cache-refill-race-first-20261101.log`。该首红确认缓存层交错缺陷；后续补充了下述真实 PostgreSQL 配置导入联合验证。相同源码模式涉及作业、Workflow、日历、窗口、配额及本地正负缓存；后续已实现局部修复并完成下述定向验证；Cluster 键槽、混合版本及容量边界仍须分别验证。

**失效发布故障首红及后续验证**：修复前源码中 Console 在配置提交后先删除旧键，再推进 Redis 全局和键版本并广播；异常只记录日志/指标，ConfigSync receipt 对账不补发失效，也不覆盖直接初始化、复制、Excel 及常规编辑。全局版本推进后键版本失败、或 Redis 整段不可用期间提交后恢复，可能使旧 v2 值继续命中；首轮定向注入因测试 JDBC 使用未规范化大写策略码查询不到行而失败，未到恢复断言，日志 `/private/tmp/config-invalidation-key-failure-first-20261101.log`。夹具修正后已取得有效首红：真实 PG/Redis 的配置导入和成功回执均已确认，定向注入一次配额 key revision 写入失败，全局版本 1→2、键版本 1→1；真实 subscriber 对账后 30 秒仍读取旧配额 10，而数据库新值为 20。1 项因 Awaitility 超时报错、exit 1，日志 `/private/tmp/config-invalidation-key-failure-rerun-20261101.log`；另在专属非复用 Valkey 容器暂停期间实际提交导入和成功回执，恢复后清本地缓存，全局/配额版本均 1→1，30 秒仍读旧值 10（预期 20）；1 项 Awaitility 超时报错、exit 1，日志 `/private/tmp/config-invalidation-outage-first-20261101.log`。测试确认自有容器已解除暂停并恢复读取。两类故障均已复现；后续持久队列的同 JVM 真实 PG/Valkey 恢复用例已通过，见下文。旧实例兼容与目标环境仍需核查。

**事务缓存定向首红**：`OrchestratorConfigCacheServiceTest` 的事务隔离与活跃事务回源场景 5 项失败、0 错误、0 跳过，显示 RR/Serializable 仍会读缓存，READ COMMITTED 事务的旧正值和旧缺失仍可回填本地缓存；日志 `/private/tmp/config-cache-transaction-first-20261101.log`。这属于受控单测；真实 PostgreSQL/MyBatis 会话交错的联合用例随后已通过，见下文。Workflow 重放预览及启动存在明确的固定快照调用路径，性能与故障恢复边界仍需另验。

**状态契约及 SDK 恢复局部 PASS**：本轮 `LeaseParentLifecycleSqlContractTest`、`CapacityProfileMapperXmlTest#shouldMatchJobLifecycle_whenClassifyingCapacityProfiles`、`JobInstanceStatusSqlContractTest` 共 6 项通过，属于 Mapper XML 与状态枚举集合对照；`SdkOrdinaryLeaseRecoveryE2eIT` 的 3 个指定方法通过，覆盖隔离独立进程提交后强杀、已提交报告响应断连及幂等任务经 Outbox/Kafka 恢复。日志 `logs/verification/local-boundaries-2026-11-01/sdk-dispatch-status.log`；这不是所有 Mapper/真实供应商验收。独立 Dispatch 的本地 API_PUSH 接收持久化后崩溃用例也通过：重领后收敛为 UNKNOWN，接收台账仅一条；执行 JAR 与当前编译输出的 Dispatch 136、worker-core 204、common 417 个 class 逐项一致。完整命令：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=LeaseParentLifecycleSqlContractTest,CapacityProfileMapperXmlTest#shouldMatchJobLifecycle_whenClassifyingCapacityProfiles,JobInstanceStatusSqlContractTest,SdkOrdinaryLeaseRecoveryE2eIT#shouldBlockUnsafeEffectAfterSdkProcessIsKilled+shouldRecoverSdkReportAfterCommittedResponseIsDisconnected+shouldRecoverSafeEffectThroughProductionOutboxAndKafka,WorkerReportLostAfterBusinessCommitE2eIT#shouldNotRedeliverAfterWorkerCrash_whenApiPushAcceptedBeforeOutcomePersisted' -Dsurefire.failIfNoSpecifiedTests=false test`，SQL 6 项和 E2E 4 项通过，exit 0。

**缓存修复定向单测 PASS**：`./mvnw -ntp -pl batch-orchestrator -am '-Dtest=OrchestratorConfigCacheServiceTest,OrchestratorRedisSupportRevisionTest,OrchestratorConfigInvalidationSubscriberTest' -Dsurefire.failIfNoSpecifiedTests=false test`：当前源码 35 项通过、0 失败/错误/跳过；日志 `/private/tmp/config-cache-unit-final-20261101.log`（此前 34 项日志 `/private/tmp/config-cache-unit-fixed-20261101.log`）。局部修复为按租户/类型的通配和精确版本区分共享缓存、本地失效代次保护回填、显式固定快照事务直接查库及禁止活跃事务回源污染缓存，并对已确认的 Redis 命令超时作降级。该结果不证明真实 PG 并发、双实例/Worker 联合、Redis 故障期间提交后的恢复、key revision 部分失败、版本回退、混合旧实例或性能；上述边界仍开放。

**未知结果恢复的现状和约束**：ConfigSync 在目标提交同一事务中写目标回执，并以原请求键建立审计身份；复制与作业包旧入口没有复用该身份。复制会重新读取源配置，故新请求可能对应不同快照；ConfigSync 当前也不在服务端保存完整导入正文。可靠的原键对账需明确操作类型和键命名空间，防止污染既有同步日志或跨入口键冲突；仅所有目标都有收据时可确认终态，缺收据仍为未知。自动补跑还需要保护原始快照并围栏在途请求，不能依据找不到状态就自动换键重发。此段为当前源码核查和待实施设计约束，非已完成恢复。

**持久失效测试首次环境失败与重试**：`./mvnw -ntp -pl batch-console-api -am -Dtest=ConfigCacheInvalidationOutboxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` 在专属 Testcontainers Kafka 启动时等待 `RECOVERY → RUNNING` 超时，3 项均为上下文初始化错误，未执行业务断言；exit 1，首错日志 `/private/tmp/config-outbox-isolation-20261101.log`。此前同类旧版源码 3 项通过不覆盖本次测试隔离改动。随后按受影响的三个类进行单次串行重试，同机专属 Kafka 启动成功，当前版本 `ConfigCacheInvalidationOutboxIntegrationTest` 3 项通过、0 失败/错误/跳过；两类 Redis E2E 随后也通过，整体命令 exit 0，日志 `/private/tmp/config-invalidation-three-target-retry-20261101.log`。未清理用户的共享服务或将环境失败归因于业务逻辑。

**持久失效本地定向复验 PASS**：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=ConfigCacheInvalidationOutboxIntegrationTest,ConfigCacheInvalidationRecoveryE2eIT,ConfigInvalidationReconcileOrderingE2eIT' -Dsurefire.failIfNoSpecifiedTests=false test` exit 0；真实 PostgreSQL 的队列事务和领取隔离 3 项、真实 PG/Valkey 的局部版本写入失败与 Redis 暂停恢复 2 项、广播丢失与对账交错 1 项，合计 6 项通过、0 失败/错误/跳过。日志 `/private/tmp/config-invalidation-three-target-retry-20261101.log`；故障容器已解除暂停。该验证为隔离依赖及同 JVM 服务/订阅器，不覆盖独立进程、分角色部署、Redis 已确认后的数据回退、混版或吞吐容量。

**独立主库角色授权工具 PASS**：新增 `scripts/db/config-cache-invalidation-role.sql`，由 DBA 显式指定既有可信角色和 grant/revoke，不创建账号、不授权 PUBLIC，也不更改已有 schema、V232 或其他业务权限；重复执行和冲突检查均在同一事务内。`./mvnw -ntp -pl batch-console-api -am '-Dtest=ConfigCacheInvalidationRoleSqlIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test` exit 0，隔离 PostgreSQL 空库执行全部 372 项 Flyway 迁移，2 项通过、0 失败/错误/跳过，覆盖含空格和引号角色、权限与 RLS 隔离、重复授权/撤销以及非法参数和策略冲突。日志 `/private/tmp/wdi-config-role-sql-target-rename-20261101.log`；`check-script-governance.py`、文档结构和差异空白检查通过。此为脚本和数据库局部验证，未对共享或目标数据库执行授权，Spring 分账号部署及原有 V232 函数权限仍待验证。

**持久失效运行成本**：当前 V372 对受保护配置表变更写入待投递记录，成功提交后既有立即失效和 relay 后续投递可能各推进一次版本并发布事件。重复投递是现有实现事实，是否影响吞吐和资源尚未测量，不能推断性能瓶颈；也不为消除此重复引入未经验证的确认协议。

**持久失效独立数据库角色验证**：V372 默认只对 Flyway 执行账号建立策略，而 Console primary 账号可独立配置。隔离 PostgreSQL 空库执行真实 372 项迁移后，以独立 LOGIN、NOSUPERUSER、NOBYPASSRLS 账号和真实 MyBatis Mapper 验证：仅有表权限但无专属 RLS 策略时 `lockNext()` 返回空；授予可信角色专属策略后可领取、认领和确认，普通角色仍不可见；手动写入另需 INSERT 与序列 USAGE。探针退出 0，日志 `/private/tmp/wdi-config-role-20261101-ZtHfBk/run.log`，专属容器已清理。新增的同类 JUnit 仍未运行；该探针不证明 Spring 分账号部署或 V232 等既有函数权限已配置。当前未修改生产授权，不能以默认同账号的通过结果覆盖分角色部署。

**作业包提交后响应丢失已复现**：使用当前 Console 可执行 JAR、独立 PostgreSQL/Valkey 和真实登录、CSRF，经本机代理转发真实 Job Bundle 导入；代理在收到服务端 200 和完整结果后关闭客户端连接。两个目标的提交由原始响应和数据库独立核对：相同原键再次提交返回 409、两目标 revision 未变化；换新键再次提交时两目标 revision 均增加。`python3 /private/tmp/wdi_loss_runner.py` exit 0；探针及 SQL、日志保留于 `logs/verification/local-boundaries-2026-11-01/job-bundle-response-loss/`，探针 SHA-256 为 `ce4ed444f1a3b1af5ac4bb2b656ae28db989daa615a936ce2ad51dce9f2c8171`。4 次此前尝试分别停在文件存储配置、PG 初始化 stderr 缺失、内部密钥配置和临时探针命名冲突；均未提交业务，首错保留于 `/private/tmp/wdi-loss-run-{yy04qev6,sqo336rc,krp_gj0w,bno79i7_}/`。本轮仅证实这个实际 HTTP 丢响应路径及现有键行为，不代表原键回查已实现、浏览器通过或部分目标事务异常已复现；专属容器与进程已清理。

**未知结果恢复的未定方案**：Job Bundle 和租户复制原键回查接口及持久恢复尚未实现。两条针对未定接口的新增测试草稿未执行、不会证明真实网络断连，已备份至 `/tmp/wdi-unimplemented-recovery-tests.patch` 后从测试类移出，原有测试内容保留。随后针对现有作业包导入的真实响应丢失用例执行单方法测试，但专属 Kafka 启动超时，测试上下文未完成、业务断言未执行，首错 `/private/tmp/wdi-response-loss-20261101.log`；本轮新增的未验证测试已另行备份并从工作区移出。复制预览只能核对当前数据，不能证明原请求的逐项目标结果。

**前端 E2E 类型检查边界**：`npx tsc --noEmit -p e2e/tsconfig.json` 失败，首错为已有测试缺 Node 类型；显式加入 Node 类型后仍有其他存量 E2E 类型或路径错误。仅针对 `closure-recovery-real.spec.ts` 及其依赖用临时配置补 Node 与路径别名检查通过，不代表全 E2E 类型通过；真实浏览器执行仍待完成。

**真实浏览器隔离环境，执行中**：当前源码以 `./mvnw -ntp -pl batch-console-api -am -DskipTests package` 打包成功（测试跳过，不计为测试通过），仅用于专属 PG/Valkey、Console 与 Vite 的浏览器验证；不会复用本机共享 Compose。隔离库已迁移至 V371，Console 关闭安全旁路且匿名 `/api/console/auth/me` 返回 401；原浏览器用例首轮因缺少项目版本 Chromium 未进入测试，首错 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-original.log`，已在私有临时目录安装对应浏览器。原有 ConfigSync 用例随后进入业务，两个目标提交和回执断言执行后，页面等待同步日志标签超时；当时 Vite 正在依赖优化并重载，环境因素是待验证候选，尚未排除页面问题。该轮日志 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-original-retry.log`，现正单例复验；测试独占租户和故障触发器清理计数均为 0；本次 Console/Vite 进程已退出、19880/15175 无监听、专属标签容器为 0。首错与通过日志检查无测试口令后已复制到忽略目录 `logs/verification/local-boundaries-2026-11-01/`。

**跨账号会话风险，修复中**：真实浏览器 trace 显示登录页在 logout 204 后再次执行兜底 logout，两次复用同一自动幂等键而出现 409；自动键未包含会话身份，快速跨账号切换存在错误复用风险。另有旧配置页请求在登出后返回 401 并触发 token 刷新；旧会话 401 在账号切换后仍发起刷新已由受控测试先红（`/private/tmp/wdi-session-stale-first-20261101.log`）。现针对 logout 停止自动注入幂等键，并让请求捕获会话代次、阻止旧错误刷新/重试/提示；`interceptors.integration.test.ts` 与 `auth.test.ts` 共 43 项通过，目标 ESLint 通过。真实浏览器进一步复现跨标签页 Cookie 竞态：旧标签页的 logout 204 被延迟，新标签页成功登录且 `/auth/me` 返回 200，释放旧响应后新会话变为 401；首红 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-cookie-first.log`。内存代次本身不能阻止浏览器处理 Set-Cookie；当前版本已以同源 Web Locks 协调密码登录/登出/换发，并以 Chromium 双标签验证该复现时序。旧标签、无锁/非安全上下文、OIDC 回调和超时后响应仍未覆盖；refresh 等其他写 Cookie 路径尚未完整验证。本轮仅使用隔离账号与数据。

**认证追踪续项**：后端 `/auth/token` 成功响应不返回 accessToken，前端此前却要求该字段，真实形态单测先红 `/private/tmp/wdi-refresh-cookie-first-20261101.log`；修复应按解包后的成功响应判断，不能向浏览器脚本泄露 token。登录页挂载时无条件 logout 会使另一已登录标签失效，组件测试先红 `/private/tmp/wdi-login-page-logout-first-20261101.log`，已移除该历史兜底；失效 Cookie 公开登录先有过滤器测试，现也已通过隔离 Chromium 真实登录专项。当前认证、登录页、维护和降级等 6 个受影响测试文件合计 66 项通过，日志 `/private/tmp/wdi-auth-final-66-20261101.log`；最终 `npm run typecheck` 也已通过（`/private/tmp/wdi-auth-final-typecheck-20261101.log`）。

**第二次隔离浏览器环境首错**：Console 启动时遗漏 `BATCH_PLATFORM_DB_*`，错误尝试默认 `localhost:15432` 并因空密码认证失败，未完成认证或执行 SQL；浏览器因此收到 502，未进入业务。原日志 `/private/tmp/wdi-auth-lock-20261101-eNQEbt/playwright.log`。随后确认独立启用的 read-replica 使用独立配置，通用数据源变量不能覆盖其默认地址；第二次进程启动日志仍出现该默认目标。已停进程、核对并禁用该功能，重启时所有 DB/Redis/存储指向专属目标或不可达端口，实际建立 TCP 连接仅见专属 PG 61073，匿名请求返回 401。浏览器专项结束后测试租户及故障触发器均为 0，专属 Console/Vite 已退出、19880/15175 无监听、专属标签容器为 0。不得把本次写成全程未尝试共享地址。

**跨标签 Cookie 协调，待浏览器复验**：对当前版本同源页面的密码登录、登出和换发请求使用 Web Locks 排队，锁等待支持超时和取消，不抢占持锁请求；旧登录加密迟到和排队期间会话变化均有受控首红。当前源码的 8 个受影响前端测试文件 74 项、ESLint 和 typecheck 通过，日志 `/private/tmp/wdi-auth-lock-snapshot-final-20261101.log`、`/private/tmp/wdi-auth-lock-snapshot-typecheck-20261101.log`。无 Locks/非安全上下文沿用原行为，旧标签、未参与客户端、OIDC 回调与超时后迟到响应均不能视为已覆盖；隔离 Chromium 已通过两个专项：真实旧登出响应扣留时新标签登录排队并最终保持新账号，以及失效 HttpOnly Cookie 下不自动登出并成功登录；日志 `/private/tmp/wdi-auth-lock-20261101-eNQEbt/playwright-final.log`。该结果不覆盖旧标签/OIDC/其他浏览器或无 API 环境。标准语义见 [Web Locks 规范](https://www.w3.org/TR/web-locks/)，适配边界见前端浏览器支持文档。

**跨标签复现数据库范围**：临时认证复现环境迁移时读取到了正在编写、尚未稳定的 V372；它仅作为旧 Console JAR 的隔离认证复现数据，不构成迁移成功或同版后端验证。最终迁移验证必须在 V372 稳定后使用全新隔离库重建。

**跨标签身份状态候选**：浏览器 Cookie 在同一存储分区共享，但当前 `authSession.ts` 代次按页面内存保存。一个标签切账号后，另一个标签可能继续展示旧 profile/权限，而请求使用新 Cookie；隔离 Chromium 双标签已实测旧页显示 admin、同页 `/auth/me` 实际为 auditor；正常关闭引导后从旧页面真实登出，会将新标签 auditor 会话从 200 变为 401。日志 `/private/tmp/wdi-cross-account-20261101-F5zWmQ/playwright-second.log`；首轮因引导遮罩未能完成操作的日志亦保留。该轮使用先前 Console JAR 与尚未稳定的 V372 临时库，仅证明认证身份错配，不构成当前后端或迁移验收；本轮 fixture 租户及故障触发器均清理为 0，专属进程、端口和标签容器已退出。需核对跨标签共享代次、休眠恢复与在途请求，不把锁串行或单页账号切换视为该问题已解决。

**跨标签当前版本浏览器局部 PASS**：前端共享会话代次、恢复时身份复核和旧登出确认拦截，相关受影响前端测试 89 项、typecheck 和目标 ESLint 通过。当前版本使用同版 Console JAR、专属 PG/Valkey 和 Chromium 验证：首轮 3 项中认证锁及失效 Cookie 登录 2 项通过，跨标签项在认证断言前被延迟新手引导遮挡；只修正引导等待并重跑该项，1 项通过。该项核对旧 admin 确认框打开后 auditor 登录、旧页及 `/auth/me` 变为 auditor，确认旧框不发送 logout 且 auditor 会话仍有效。测试源码两次间只改引导等待，后端和前端业务源码摘要一致；首错与复验分别见 `logs/verification/local-boundaries-2026-11-01/current-auth-playwright-first.log`、`current-auth-playwright-retry.log`，fixture 租户及故障触发器均清理为 0。未协调的旧标签/客户端、OIDC、超时迟到响应和其他业务确认框不在本次通过范围；补充源码复核：配置发布、租户复制和作业包页面已监听主体或权限变化并使旧操作代次失效；不能仅凭局部确认函数推断这些页面会以新身份提交。文件重派、归档和到达确认等其他操作仍需实际双账号时序验证，尚未证实越权写入，未据此添加防御。

**JWT 撤销容忍期已复现，修复中**：真实签发/解码器与受控撤销存储测试证实普通 token 的撤销 TTL 少覆盖 clock skew，且过 exp 但仍在容忍期的 token 注销后仍可认证；日志 `logs/verification/local-boundaries-2026-11-01/auth-first-red.log`。已令撤销 TTL 覆盖实际接受窗口并保留存储异常传播；首红方法及新增边界 6 项通过，两类完整回归共 36 项通过；真实 Redis 和多节点时钟仍未验证。

**认证过滤器异常已复现，修复中**：有效 Cookie 的 `filterChain.doFilter` 与鉴权共用 `catch(Exception)`；受控负例证实下游异常逸出时公开路径执行两次、私有路径吞掉异常并误处理为鉴权失败。适用范围限未被下游框架处理的异常；首红日志 `logs/verification/local-boundaries-2026-11-01/auth-first-red.log`，已将下游调用移出认证异常捕获；首红方法及新增边界 6 项通过，两类完整回归共 36 项通过。

**认证相关类完整定向回归 PASS**：`./mvnw -ntp -pl batch-console-api -am '-Dtest=ConsoleAuthenticationFilterTest,ConsoleJwtServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test` exit 0；过滤器 16 项、JWT 20 项均通过，日志 `logs/verification/local-boundaries-2026-11-01/auth-classes.log`。无撤销 store、无 jti 沿用原兼容行为，极端 skew 配置及真实 Redis/多节点时钟不在通过范围。

**认证响应类型校正**：生成 OpenAPI 类型保留可选 `accessToken` 供旧客户端兼容，当前后端 Cookie 响应不返回它。已删除前端内部错误必填字段和未被生产调用方消费的 token 返回值；相关 9 个测试文件 84 项、ESLint 和 typecheck 通过，日志 `/private/tmp/wdi-auth-wire-final-suite-20261101.log`、`/private/tmp/wdi-auth-wire-final-typecheck-20261101.log`。最近两项浏览器通过先于该内部类型变更，未在此变更后重跑。

**ConfigSync 真实浏览器局部 PASS**：隔离浏览器与关闭安全旁路的同版 Console、独占 PostgreSQL/Valkey 联验，`closure-recovery-real.spec.ts` 原有“真实 ConfigSync 核对”单例 1/1 通过：真实登录与 CSRF、真实 HTTP 导入和受控审计故障后，两个目标及回执提交，浏览器核对转 SUCCESS，刷新及数据库回读无重复。首次导入由 `page.request` 发起，不是页面按钮；日志 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-original-direct.log`。此前经仪表盘导航的两次超时保留，不据此认定根因；页面按钮与跨账号专项最终 1/1 通过（43.2 秒）：页面提交两个独占目标、预设审计故障后回读两个目标及回执，页面切换 auditor/admin 验证写入口权限和原待核查隔离，刷新后按同键核对至 SUCCESS，数据库无重复；网络哨兵仅允许该请求的预期 500。最终日志 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-page-fifth.log`。首次选择器遮挡及第三轮 logout 409 / 旧请求 401 首错分别保留于 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-page-first.log` 和 `playwright-page-third.log`。该浏览器结果产生于移除登录页预清理之前，最后源码版本需重跑；证据限此隔离环境和 ConfigSync 场景，不能外推旧复制/作业包持久恢复或所有账号并发路径。

**CI 分片静态检查 PASS**：五个新增 E2E 已登记到 Full CI 与 Staging 的独立分片；`bash scripts/ci/check-e2e-shard-coverage.sh` 验证两份清单覆盖当前 54 个类、无漂移，`actionlint .github/workflows/full-ci-gate.yml .github/workflows/staging-gate.yml` 退出 0。该结果不代表 E2E 实际通过或远端 CI 已执行。

**隔离 Redis 复验局部 PASS**：上述联合 reactor 中 `OrchestratorConfigCacheRefillRaceIntegrationTest` 与 `OrchestratorConfigCacheServiceIntegrationTest` 各 3 项、合计 6 项通过，覆盖已复现的迟到旧读取及缓存基础路径；后三个跨服务 E2E 仍未结束。缓存键与事务语义已同步至 [配置治理运行手册](../runbook/config-governance.md)，其中明确 Redis 故障、旧实例和性能限制。

**配置导入与缓存本地联合 PASS**：`RuntimeConfigImportCacheE2eIT` 4 项通过、0 失败/错误/跳过；真实 PG/Redis 下核对配置同步与目标回执、同 JVM 两份独立缓存和订阅器、固定快照事务及 READ COMMITTED/MyBatis 的旧正值/旧缺失，含只读 REQUIRES_NEW 与外层事务恢复。日志 `/private/tmp/config-runtime-joined-20261101.log`。这是修复后结果；不等同两个独立进程或 Redis 故障恢复验收；Dispatch 结果见下。

**真实 Alertmanager 本地联合 PASS**：`AlertmanagerDeliveryChainE2eIT` 1 项通过、0 失败/错误/跳过。隔离 PostgreSQL 中经真实 Orchestrator 告警服务提交，异步发布到真实 Alertmanager，再经真实 Console HTTP 到本地 Webhook；接收端先返 503 后成功，验证同阶段幂等键、发生及关闭后的恢复通知和数据库回执。应用共进程装配且租户权限为测试替身、本地接收端非真实供应商；不代表生产权限、独立部署或外部供应商验收。日志 `/private/tmp/config-runtime-joined-20261101.log`，配置 E2E 结果另见本节。

**修正测试装配后联合 PASS**：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=OrchestratorConfigCacheRefillRaceIntegrationTest,OrchestratorConfigCacheServiceIntegrationTest,RuntimeConfigImportCacheE2eIT,RuntimeConfigImportDispatchE2eIT,AlertmanagerDeliveryChainE2eIT' -Dsurefire.failIfNoSpecifiedTests=false test` exit 0、BUILD SUCCESS；真实 Redis 6 项和三类 E2E 共 6 项全部通过，0 失败/错误/跳过。Dispatch 场景先冻结旧渠道，再实际导入新配置并核对回执，最后由真实 Worker 在旧 LOCAL 目录投递而新目录无信封；使用隔离 PG/Redis/Kafka、同 JVM Worker 和手动驱动出箱，非独立进程或外部渠道。日志 `/private/tmp/config-runtime-joined-20261101.log`。

**验证快照说明**：SDK/SQL reactor 启动后，`OrchestratorRedisSupport.java` 的 import 顺序和日志换行曾被调整；本批复用格式调整前的类文件，已核对仅为空白与 import 顺序变化。后续格式检查及相关验证应按最终源码重核，不将格式调整后的源码误称为本批重新编译。

**首次运行失败记录**：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=RuntimeConfigImportCacheE2eIT,RuntimeConfigImportDispatchE2eIT,AlertmanagerDeliveryChainE2eIT' -Dsurefire.failIfNoSpecifiedTests=false test`；原始日志 `/private/tmp/wdi-local-boundary-e2e-20261101.log`。首个告警用例 FAIL：复用测试应用时无关的 Import Worker 在就绪事件自注册读超时；双缓存用例 FAIL：MOCK Web 环境未绑定 Worker 端口。Dispatch 用例 FAIL：测试专用 Console 扫描漏掉 `DownstreamFallback`。三类均是测试应用装配问题，业务断言尚未执行，整个命令 exit 1；随后修正测试装配并按下述联合 reactor 定向复验通过，首错不直接归因于生产逻辑。

## 2026-11-01：短断言、薄包装与 Map 候选复核续记

两仓分别位于 `file-batch-system` 和 `batch-console`；开始时为 `perf/local-loadtest-2026-10-31`，审查中同一 HEAD 上重命名为 `fix/bug-fixes`，保留已有未提交改动。原始分母是短断言 321 项、后端薄包装 110 项和 public Map 返回值 60 项，逐编号或逐方法结论仍以本报告相应台账为准，不外推到清单以外的测试、Map 入参和接口。

- 后端短断言 B001–B104 对照当前方法、输入、断言和相邻测试逐项复核，涉及 54 个文件的摘要均与台账快照一致；99 项保留，5 项保留先前增强。B105–B208 对照当前方法及相关上下文，四个已删除的 Worker 上下文非空用例仍由对应真实启动与注册端口测试覆盖。B123/B124 补验证参数错误码及必填国际化 key；B136 补充空 Workflow ID 时节点、连线及作业 Mapper 无调用；B144 补充 misfire 日志中的实际触发名；B156 更正固定哈希样本的说明和名称，避免误称实时读取迁移；B194 同时验证故障渠道熔断和正常渠道放行。B143 的必填 Bean 注入在同类测试中虽会先执行，仍保留其独立装配检查以支持单独运行和定位。相关受影响类已随下述定向命令通过。
- 前端短断言 F001–F113 对照当前断言与相邻场景复核；F015 更名为准确描述的只读回退，F023/F024 验证实际 Plus/Menu 组件，F068/F069 精确验证密码强度边界与上限，F100–F103 验证具体日志及仅在属性中出现的筛选词。`npx vitest run src/utils/iconResolve.test.ts src/utils/passwordGenerator.test.ts src/utils/logger.test.ts src/constants/theme.test.ts`：4 文件 40 项通过，日志 `/private/tmp/wdi-short-assertion-fe-final-20261101.log`；四个文件的 Prettier 检查和差异空白检查通过。未运行浏览器 E2E。
- 60 项 public Map 返回值的当前文件摘要与台账一致，逐项对照方法实现及已记录的调用边界；保留动态参数、插件和 JSON/Excel 既有契约，不因词法形式强行改为对象。110 项后端薄包装已逐方法对照当前实现和调用位置：原有 9 项已简化，本轮再移除 2 项纯转发，其余 99 项保留；生产 profile 判断和配额策略转换保留原求值分支及回退语义。受影响类已随下述定向命令通过。

后端受影响类验证：`./mvnw -ntp -pl batch-console-api,batch-orchestrator,batch-trigger,batch-worker/atomic,batch-worker/dispatch -am '-Dtest=ConsoleAiPromptGuardTest,ConsolePasswordHasherTest,WorkflowGraphValidatorTest,MisfireHandlerIntegrationTest,DispatchChannelCircuitBreakerTest,AtomicRuntimeStatusServiceTest,DatabaseQuotaRuntimeStateServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test`，exit 0，7 类合计 98 项通过，0 失败、0 错误、0 跳过；日志 `/private/tmp/wdi-candidate-final-targeted-20261101.log`。其中 misfire 测试使用隔离 Testcontainers，其他为受控单测；编译依赖模块不代表运行了全部模块测试。

台账的 SHA-256 仍是原建表快照，并非本轮修改后的摘要；本轮增强和更名会使对应文件摘要变化。候选结论为源码/测试设计判断；只有明确列出并完成的运行命令才是执行证据。

## 2026-11-01：候选逐项台账与验证进度

两仓在原 `perf/local-loadtest-2026-10-31` 工作区继续，当前分支名为 `fix/bug-fixes`。原 321 个短断言候选已按[逐项台账](#2026-11-01短断言候选逐项处置台账)记录处理：290 项保留，22 项增强，4 项删除，1 项合并并增强，4 项更名；此分母包含先前批次的改动，不代表全工程所有测试。原 110 个后端薄包装和清单生成器列出的 60 个 public Map 返回候选也已逐项登记；Map 入参、隐式 public 和清单之外的其他形式不在该分母内。下方各批“未完成”描述为当时状态，以本节及各台账为当前进度。

本轮另将 TypeScript SDK 默认错误码断言改用独立协议字面量；前端时间戳测试补 UTC 精确值，HTML 清洗测试验证清洗器输出而非仅验证恒等 mock，认证状态测试更名以反映 session 标记。诊断页 E2E 的集群区块断言限定于集群卡片标题并使用现有国际化来源；E2E 用例列表可加载、对应组件测试通过，真实后端浏览器执行尚未完成。

4 个 Worker 保留的注册端口测试均在隔离容器的真实依赖上通过；OTel 空上下文定向测试通过。5 个字符串转换包装的受影响类测试已通过；浏览器 E2E 尚未执行。

## 2026-11-01：本轮受影响测试验证

- `./mvnw -ntp -pl batch-orchestrator,batch-worker/import -am '-Dtest=ImportRecordGovernanceServiceTest,ImportDataQualityServiceTest,DefaultWorkflowDagServiceTest,DefaultRetryGovernanceServiceTest,FileGovernanceMetricsCacheServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test`：五个测试类合计 90 项通过，0 失败、0 错误、0 跳过。新增 `ImportDataQualityServiceTest` 核查缺失上下文、缺失载荷和非字符串载荷；日志 `/private/tmp/string-wrappers-review-20261101.log`。
- `./mvnw -ntp -pl batch-worker/atomic,batch-worker/dispatch,batch-worker/import,batch-worker/export -am '-Dtest=BatchWorkerAtomicApplicationIntegrationTest,BatchWorkerDispatchApplicationIntegrationTest,BatchWorkerImportApplicationIntegrationTest,BatchWorkerExportApplicationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`：四个保留的注册端口用例均通过，各 1 项，0 失败、0 错误、0 跳过；真实中间件由隔离 Testcontainers 提供，日志 `/private/tmp/worker-context-review-20261101.log`。
- `./mvnw -ntp -pl batch-common '-Dtest=OtelTraceContextTest' test`：4 项通过、0 失败、0 错误、0 跳过；无活跃链路场景显式使用根上下文并在结束时恢复。
- `node --test --experimental-strip-types tests/handler.test.ts`（`sdk/typescript`）：4 项通过，默认错误码使用独立预期值。
- `npx vitest run src/utils/datetime.test.ts`（前端）：21 项通过；`npx vitest run src/utils/safeHtml.test.ts src/stores/auth.test.ts`：27 项通过；`npx vitest run src/views/ops/OpsDiagnostic.test.ts`：20 项通过。
- `npx playwright test e2e/ops-diagnostic.spec.ts --list`（前端）仅确认 4 个用例可被加载，未执行浏览器测试。诊断页组件测试使用 mock；本轮未针对真实后端执行浏览器 E2E，不能视为端到端通过。

## 2026-11-01：薄包装清单续核

后端原始 110 个词法候选按[逐项台账](#2026-11-01后端薄包装历史候选逐项台账)核对当前方法及直接用途：11 项已简化，其余 99 项因具名业务判断、必要的格式转换、边界封装或测试夹具而保留。新增简化 `BatchWindowGateTest.mockWorkerSelector` 和 `LoadStepTest.mockPayload`，并将 5 个仅做可空字符串转换的单次包装替换为 `Objects.toString(value, null)`；输入只求值一次，空值与非空值语义保持不变。该结论仅覆盖原始 110 项。

`./mvnw -ntp -pl batch-orchestrator,batch-worker/import -am '-Dtest=BatchWindowGateTest,LoadStepTest' -Dsurefire.failIfNoSpecifiedTests=false test`：分别 4 项、18 项通过，0 失败、0 错误、0 跳过。Map 60 项当前逐方法结论见[Map 台账](#2026-11-01当前-public-map-候选逐项台账)，结论仅针对列出的返回类型候选，输入参数、隐式 public 等不在分母内。短断言当前结果以文首和逐项台账为准。

## 2026-11-01：测试候选与 Map 台账续核

- `PreprocessRangeSliceTest.shouldConcatenatePartitionsWithoutDuplicateOrGap` 原有四分片输入和无损结果保留，复用同类已有的切片辅助断言，移除重复的测试内切片循环。`./mvnw -ntp -pl batch-worker/import -am '-Dtest=PreprocessRangeSliceTest' -Dsurefire.failIfNoSpecifiedTests=false test`：15 项通过。
- `ProcessStagingPartitionMaintenanceTest` 原来仅凭清理数为零不能证明默认开关关闭：测试实例未配置清理依赖，开关错误开启也可能返回零。现增加默认开关为 false 和 mapper 无操作断言。`./mvnw -ntp -pl batch-worker/process -am '-Dtest=ProcessStagingPartitionMaintenanceTest' -Dsurefire.failIfNoSpecifiedTests=false test`：4 项通过。
- `HikariPgSessionSupportTest` 的长度断言改为检查截断后的完整预期字符串，保留原 63 字符上限并验证前缀。`./mvnw -ntp -pl batch-common '-Dtest=HikariPgSessionSupportTest' test`：3 项通过。以上均未修改生产逻辑。
- 文件切片末行无换行、空行和 CRLF 分别保留；回放集成测试的暂停、状态不匹配、外部结果未知和不同载荷属于不同场景；租户清理测试的同租户、跨租户和冷数据也不能因使用同一辅助方法删除。`RetryScheduleStatusTest` 的数量断言用于检出新增状态，与现有各编码断言不重复。
- public Map 原始 60 个候选现已逐方法列入[当前台账](#2026-11-01当前-public-map-候选逐项台账)，包含源码摘要、60 个当前已核查保留、0 个待核查项；Kafka producer 配置需透传和覆盖 client 参数，诊断端点保持现有条件字段和 wire 格式，JSON 转换及递归脱敏承载不定字段，故不强制改成固定对象。

候选台账中待核查不代表缺陷。其余短断言、后端薄包装及 Map 项仍未全部逐一裁定，用户目标尚未完成。

## 2026-11-01：短断言与动态 Map 再核对

- `TriggerOutboxReleaseGovernorTest.disabled_returnsConfiguredLimitWithoutReadingLag` 原来只断言返回配置上限，未验证名称所述的不读取 lag；保留原断言并补充对 lag monitor 无调用的验证。`./mvnw -ntp -pl batch-trigger -am '-Dtest=TriggerOutboxReleaseGovernorTest' -Dsurefire.failIfNoSpecifiedTests=false test`：7 项通过，0 失败、0 错误、0 跳过。生产逻辑未变。
- `CorrelationIdsTest` 的裁剪、非法字符、长度与回退输入不同；`ProcessPublishedCountVerifierTest` 的数字和数字字符串覆盖不同载荷类型；前端 `statusTagResolve` 的显式默认值、隐式默认值与未知状态、引导完成状态和权限空列表分别具有独立断言，保留。
- `BatchDayReplayService.failedStatuses` 与 `isCancellable` 对应失败重放范围及可取消状态的不同业务判断，保留具名方法。`MapJsonbTypeHandler` 的三个方法实现 MyBatis 的结果读取重载；`ParseSupport.parseHints/readJsonObject` 分别处理不同格式和插件可扩展 JSON；SDK `ProgressReporter.latest` 承载执行线程写入、续租线程读取的动态进度，均保留 Map。上述 Map 与包装结论为静态源码及直接调用关系核查。

本轮未对其余候选逐一完成上下文裁定，不代表原始短断言、包装与 Map 清单已全部关闭。

## 2026-11-01：短断言及包装、Map 续核

- `datetime.test.ts` 中两个空字符串用例调用和断言完全相同，且名为 typed 的用例没有额外输入或类型覆盖；删除一项，保留另一项。`npx vitest run src/utils/datetime.test.ts`：21 项通过。相邻的 null、undefined、无效日期及时间戳输入不同，继续保留。
- `queryFormOptions.test.ts` 的空输入、提取 undefined、去重排序及空白裁剪分别检查不同路径；`formatAiUsd.test.ts`、`errorCatalog.test.ts` 的零金额/普通金额与网络/未知错误分别属于不同场景，保留。
- `workerActions.test.ts` 的 ONLINE、DRAINING、OFFLINE 断言对应不同的操作集，保留；ONLINE 用例标题原来漏写预期中的排空动作，已更名以匹配原有断言。`npx vitest run src/views/worker/workerActions.test.ts`：4 项通过。两个 Worker fingerprint 空响应测试分别调用列表和汇总接口；`structuredSummary` 和 `pipelineStepSummary` 的短断言分别覆盖文本截断、各阶段字段与异常输入，保留。
- `DefaultWorkflowNodeDispatchService.isJobNode/isWaitNode` 命名不同节点分支；HTTP transport 的 `sameHost` 在 DNS 回调中统一处理 IPv6 括号和大小写比较，保留，避免将业务或网络边界嵌入调用点。
- 文件渠道和模板的 `toMap` 由 `TenantConfigPackageExcelApplyService` 在旧配置与导入列合并时调用，保留其旧 snake_case/缺失键行为；日历 `toExportRow` 被配置包 Excel 导出消费，保留最终行的 Map 格式。上述为静态调用关系核查，未更改后端代码，也未运行后端测试。

本轮只对上述已列位置作出结论；其余短断言、后端包装及 Map 候选仍未逐项核完，不能宣称全部整改完成。

## 2026-11-01：候选续核与幂等键测试修正

- 前端 `src/utils/idempotency.test.ts` 的非空和类型断言原先分别调用两次生成器，现对同一个返回值断言；无 `crypto` 场景使用 `finally` 恢复全局状态，断言失败也不会污染后续测试。`npx vitest run src/utils/idempotency.test.ts`：4 项通过；本批只改测试，不改变幂等键实现。
- 后端薄包装抽查：`CalendarBizDateResolver.previousWorkday` 和 `nextWorkday` 分别命名节假日向前、向后滚动，并传入对应方向和诊断文本；`ActiveTaskLeaseRegistry` 的状态更新和原子值读取包装承载租约状态操作边界，保留。这些结论不代表其余包装均已核查。
- 使用 `python3 scripts/ci/report-java-readability-inventory.py --output /private/tmp/readability-candidates-20261101.md` 重新生成当前词法清单，退出码 0；指定范围有 60 个 public Map 返回候选，分布于 44 文件。`CursorCodec.decode` 承载可扩展分页排序键，其调用方包括会话分页和按 id 的通用查询；`RunModeSupport.copyWithDefault` 复制原有业务参数并规范化运行模式，调用方是启动参数解析；两者保留 Map。数字是词法候选而非缺陷，也不覆盖所有 Map 入参与私有方法。

本轮仍未逐项完成全部短断言、后端薄包装和 Map 候选；历史清单与当前代码可能存在漂移，后续以当前函数、调用方及测试夹具为准，不能按候选数量批量删改。用户要求的目标尚未完成。

## 2026-11-01：重复判空和事件处理候选续改

在当前后端 `perf/local-loadtest-2026-10-31` 工作区和配对前端工作区继续处理。历史 71 个重复判空模式中，前两批已简化 10 处；本批按实际类型、工具方法对 null 的处理和表达式求值核对后，又简化 39 个文件中的 57 处局部值或不可变记录字段判断。当前源码扫描剩余 4 处，均直接读取请求对象可变字段 `scheduleExpr`；为保持原读取次数和求值行为，本批保留。扫描模式和范围与原始候选相同，不代表其他防御判断均已审完。

前端 `MyWorkers.onRefresh` 和审批表格的 `selectableRow` 确认为简单转发，调用处改为直接使用原函数。配对前端此前检出 `main`，本批先撤回对这两个文件的修改；用户随后指定使用与后端同名分支，现已在原前端工作区创建并切换至 `perf/local-loadtest-2026-10-31`，重新应用相同修改，保留既有未提交内容。`npx vitest run src/views/approvals/ActionContextRisks.test.ts` 在切换分支前、相同修改内容下运行：1 个文件、26 项通过；该测试不构成 MyWorkers 页面刷新交互验证。前端旧候选清单含模板事件回调，很多回调内部仍有筛选、重置及加载操作；不能根据单行正则将其视为无用包装。

前端原 84 个包装语法候选按当前函数声明和调用形态核对：78 个调用搜索、重置或刷新辅助函数并提供场景回调，需保留事件参数与筛选、分页或加载行为；上述 2 个直接转发已简化。其余 4 个中，`ProTable.scheduleRecompute` 保留 `nextTick` 调度，`useTenantReload.onErr` 同时用于同步异常和异步拒绝，文档生成脚本的 `escapeLabel` 命名 Markdown 转义规则，E2E 的 `ts` 每次调用生成时间戳；不按一次调用删除。历史行号已有漂移，本结论针对候选名称在当前文件中的实现，不覆盖候选之外的其他抽象。

后端定向测试：`./mvnw -ntp -pl batch-console-api,batch-orchestrator,batch-trigger,batch-worker/dispatch,batch-worker/export,batch-worker/import,batch-worker/process,sdk/java/core -am '-Dtest=AckDispatchStepTest,AlertNotificationReceiptServiceTest,AlertmanagerNotifyServiceTest,BatchDayCatchupPreviewServiceTest,BatchDayOpenSchedulerTest,BatchDaySettleSchedulerTest,BatchManifestTest,ConfigSyncAuditRecoveryTest,ConsoleAiPropertiesTest,ConsolePushApprovalNotifierTest,ControlTotalEvaluatorTest,DefaultConsoleApprovalApplicationServiceTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultWorkerRegistryServiceTest,DefaultWorkflowDagServiceTest,DefaultWorkflowNodeDispatchServiceTest,JobDependencyWaitingLaunchServiceTest,LaunchParamResolverTest,PersistentWaitReadServiceTest,ProcessStagingGenerationCleanupFenceTest,QuartzMisfireRecoveryConfigurationTest,SdkTaskContextTest,StoreStepTest,SystemPipelineCatalogTest,TaskDispatcherTest,TrailerControlRecordTest,TriggerApiKeyVerifierTest,TriggerSchedulerFacadeTest,WorkflowDefinitionMutationValidationTest,WorkflowNodeReplayTargetResolverTest,WorkflowRunReplaySourceResolverTest' -Dsurefire.failIfNoSpecifiedTests=false test` 成功，实际合计 433 项、0 失败、0 错误、0 跳过；完整类名和命令见日志 `/private/tmp/wdi-overdefense-batch-20261101.log`。Maven 按依赖编译上游模块，此结果不代表完整测试或全部变更位置均有独立用例。

后续核对 `WorkflowDagValidatorTest`：旧 Pipeline 已存在的测试并未构造该前提，其输入和错误码与邻近的 FILE_STEP 退役测试相同；删除重复测试，保留同时检查异常码和消息键的测试。`./mvnw -ntp -pl batch-console-api -am '-Dtest=WorkflowDagValidatorTest,WorkflowConditionEvaluatorTest' -Dsurefire.failIfNoSpecifiedTests=false test` 成功，实际仅 `WorkflowDagValidatorTest` 运行 22 项并通过：该 reactor 不包含 orchestrator，因此并未运行后者。`WorkflowConditionEvaluator.findAtomicOperator` 只转发至 `AtomicOperator.find`，调用处改为直接调用；随后执行 `./mvnw -ntp -pl batch-orchestrator -am '-Dtest=WorkflowConditionEvaluatorTest' -Dsurefire.failIfNoSpecifiedTests=false test`，23 项通过。

原 321 个短断言和其余前后端包装、Map 等候选已按逐项台账完成上下文裁定；候选命中不等于问题，未按候选数量批量删改。

补充逐组核对：`resourceCodeBuilder.test.ts` 的短断言分别约束规范化、长度和首字符边界、版本后缀与资源码拼装；`metaEnumPick.test.ts` 区分未匹配、缺失输入、空数组和无候选键；`taskHeartbeat.test.ts` 区分百分数、比例换算、四舍五入和字段优先级。`HttpTaskExecutorIpBlockTest` 中的地址覆盖不同协议族、网段及公网反例，不能只因断言形式相同删除。这些结论依据对应完整测试类及其输入与断言，未据此推断其余短断言已完成核查。

另核对 `DelimitedCodecTest` 的普通字段、引号中分隔符、末尾空字段和自定义分隔符；`QuotaResetPolicyTest` 的空值、空白、未知配置和不同策略托管状态；`ValidTenantIdTest`、`ValidResourceCodeTest` 的 null、空白及长度边界；以及前端 `text.test.ts`、`safeJson.test.ts`、`theme.test.ts`、`lastApiMeta.test.ts` 的字符截断、JSON 输入、主题迁移和缺省值边界。各自测试不同输入或状态，保留；本次没有因其断言短而合并或删除。

## 2026-10-31：补跑日方法拆分以通过 NCSS

`d23a51483` 的 pr-gate 里，`DefaultConsoleJobApprovalService.catchUpBatchDay` 的 NCSS 为 60，达到 PMD `NcssCount` 方法阈值。请求作业码的清洗抽到 `sanitizedRequestedJobCodes`。空列表仍返回 null；非空时仍过滤空白、按 128 截断并去重。`request.getJobCodes()` 仍只调用一次。同一提交刷新 `docs/stats/java-readability-inventory.md`，行数变化来自本分支已经落地的判空收敛。

验证：`batch-console-api`、`batch-orchestrator`、`batch-trigger`、`batch-worker/process`、`batch-worker/export` 及 reactor 依赖的 `pmd:check` 通过。`DefaultConsoleJobApprovalServiceWorkflowTest` 14 项通过。未改 SQL、接口或迁移。

## 2026-10-31：检查通过后复用的访问器只取一次

接收方和字段不是同一表达式时，判空仍然保留。本轮只处理检查通过后还会再次调用同一 getter 的 16 个生产文件：先确认接收方，再绑定字段，后续使用这份局部变量。空请求、空集合和失败分支仍按原顺序返回，不提前调用被短路的 getter。`quartzIdentity` 的租户标识仍只按 null 拒绝。一次调用的私有薄包装和前端包装候选仍按原记录保留。

验证：触及文件定向 Spotless 通过。定向测试合计 297 项、0 失败。覆盖 `CalendarBizDateResolverTest` 9、`DefaultTriggerServiceTest` 32、`DefaultTaskCreationServiceTest` 25、`WorkflowRunReplayPreviewServiceTest` 40、`ChildJobLaunchSupportTest` 5、`DefaultPartitionDispatchServiceTest` 14、`DefaultPartitionLifecycleServiceTest` 19、`DefaultTaskAssignmentServiceTest` 30、`TaskOutcomeDagProgressorTest` 2、`TaskOutcomeSummaryBuilderTest` 8、`DefaultWorkflowRunLaunchServiceReplayTest` 11、`WorkflowRunReportLockTest` 2、`SqlTemplateExportSqlValidatorTest` 41、`SqlTransformComputeSqlValidatorTest` 25、`ConsoleFilePipelineProgressBridgeTest` 10、`WorkflowDagValidatorTest` 24。未跑全 reactor 测试，未跑增量 Sonar，也未改 SQL、接口或迁移。

## 2026-10-31：同一取值的重复判空再收敛

上一节只处理简单标识符。本轮处理同一 getter 或访问器被判空两次的 20 处：先取一次局部变量，再交给 `isBlank` 或 `isEmpty`。通过检查后还会使用该值时，后续也用这份局部变量。`WorkflowRunExecutionSnapshot.from`、启动请求校验、`quartzIdentity` 和冻结图 `nodes()` 仍先确认接收方非空，再调用 getter。`quartzIdentity` 的租户标识仍只按 null 拒绝，空白字符串保持原口径。

另外 10 处已经是同一简单值或已绑定 getter 的 `isNotNull(x) && isNotBlank(x)`、`isNotNull(x) && !isBlank(x)`，收成 `isNotBlank(x)`。接收方和字段不是同一表达式的组合保持原样。一次调用的私有薄包装和前端包装候选仍按原记录保留。

验证：触及文件定向 Spotless 通过。定向测试合计 291 项、0 失败；`PlatformHttpClientTest` 另有 1 项跳过。覆盖 `NamespacedObjectStoreTest` 3、`QuartzLaunchJobTest` 12、`TriggerSchedulerFacadeTest` 25、`PlatformHttpClientTest` 15、`BatchDayReplayServiceTest` 31、`DefaultPartitionLifecycleServiceTest` 19、`NodePartitionProgressCalculatorTest` 3、`DefaultWorkflowRunLaunchServiceReplayTest` 11、`WorkflowRunExecutionSnapshotTest` 11、`DefaultLaunchValidationServiceTest` 16、`DefaultWorkerRegistryServiceTest` 35、`JobDependencyWaitingLaunchServiceTest` 69、`BatchManifestTest` 9、`DefaultConsoleJobApprovalServiceWorkflowTest` 14、`ConsoleSelfServiceJobServiceTest` 5、`WorkflowExecutionGraphAssemblerTest` 9、`TenantConfigPackageRowProjectionsMonitoringPolicyTest` 4。同名 Worker Core 的 `DefaultWorkerRegistryServiceTest` 7 项也通过，该类未改。未跑全 reactor 测试，未跑增量 Sonar，也未改 SQL、接口或迁移。

## 2026-10-31：简单变量重复判空收敛

2026-10-28 扫描列出的 71 处 `EmptyChecks.isNull(x) || isBlank/isEmpty(x)` 都是同一简单标识符。`isBlank` 与 `isEmpty` 的各重载已经把 null 视为空，两段判断结果相同。现只保留后一段调用。null 集合仍在进入 `size()` 或 `stream()` 之前返回，短路顺序保持。

本轮改动落在当前 `main` 的 45 个生产文件。方法调用形式仍会求值两次，包括 `request.traceId()`、`request.getJobCodes()`、`run.getTenantId()` 与 `node.getNodeCode()`，本轮未改。一次调用的私有薄包装和前端包装候选仍按原记录保留。

验证：触及文件定向 Spotless 通过。`EmptyChecksTest`、`SdkTaskContextTest`、`TaskDispatchExecutorTest`、`PipelineProgressQueryServiceTest` 合计 25 项通过（batch-common 3、Java SDK 13、Worker Core 6、Console 3）。随后 Trigger、Orchestrator、Import、Export、Process、Dispatch 生产编译通过。未跑全 reactor 测试，也未改 SQL、接口或迁移。

## 2026-10-30：批量日补跑根请求修复复核

沿 `BUG-20261030-BATCHDAY-CATCHUP-ROOT` 及 `BUG-20261030-BATCHDAY-PARENT-IDENTITY` 核对当前源码：批日调度器生成 `tenant:batch-day-catchup:calendar:date:job` 键；根请求读取及失败日扫描都按租户、该键、`CATCH_UP` 类型和空父 Workflow 限定，避免同键子 Job 遮蔽根请求。V369 部分唯一索引覆盖该键前缀的同租户 `CATCH_UP` 根请求，不限制子请求。Trigger 通用按键回查也排除子请求，但保留别名匹配和原有排序/单条选择；其业务范围比批日根请求查询更广，不能把两条 SQL 简化为同一口径。

本次仅核对现有 SQL、索引和直接调用链，未重复运行此前成功的真实数据库、启动服务及独立进程回查测试；具体证据和测试环境限制见[治理记录](../audit/backend-25-package-governance-2026-10-26.md)。这不证明所有 Trigger 请求类型或目标环境已完成验证。

## 2026-10-30：通知投递日志直接消费者复核

核对 `BUG-20261030-NOTIFICATION-JSONB-MASK` 与 `BUG-20261030-NOTIFICATION-LOG-UI`：后端以 snake_case 返回已遮罩的 JSON 值，OpenAPI 和前端生成类型将两个动态字段声明为 `unknown`；前端 API 适配器原样传递 JSON，并将渠道、尝试次数和错误摘要映射为页面使用的字段。当前通知投递日志页面展示渠道、事件、状态、次数和错误摘要，筛选使用事件与渠道，并不展示或字符串化这两个 JSON 字段。租户切换时页面用请求作用域排除过期响应。

本次为当前源码与直接消费者静态复核，前端适配器、页面及适配器测试的 SHA-256 前 12 位分别为 `039b3ceeffba`、`a5e1a19f639b`、`d21c0add56fb`；未重复运行此前成功的测试。隔离数据库与真实 HTTP 遮罩验证、前端模拟客户端和组件测试的范围分别见[治理记录](../audit/backend-25-package-governance-2026-10-26.md)相应条目；真实浏览器与真实后端同次联验及生产权限仍未验证。

## 2026-10-30：文件身份解析修复的直接调用链核对

针对 `BUG-20261029-FILE-ID-NUMERIC` 核对当前启动、任务创建和结果回报代码：三处身份转换均先转文本并以 `Long.parseLong` 解析，只接受正整数；小数、溢出及无效值不会通过数值截断关联到其他文件。启动时有效父实例按租户查询，未解析时沿既有空值分支处理；文件身份由引用校验器检查。任务创建对同一解析结果执行引用校验并写入步骤，结果回报将解析结果传入步骤进度更新。Workflow 启动的父实例也使用同一启动参数解析器和租户查询。

本次保留现有优先级：普通任务按正值候选回退；DISPATCH 遇到已提供的 `bundleSourceFileId` 或 `fileId` 时使用相应字段的解析结果；启动参数则先选择首个非空字段再解析。没有更改无效值应否拒绝及候选回退语义。三处生产源码 SHA-256 前 12 位分别为 `4e7fbafa3e76`、`973507789425`、`89d034f9f401`，对应启动解析、任务创建、结果载荷解析。此为源码与直接调用链复核，未重复运行先前成功的 51 项定向测试及两个真实数据库测试；历史执行证据和跨进程未验证边界见[治理记录](../audit/backend-25-package-governance-2026-10-26.md)。

## 2026-10-30：四项证据新鲜度核对

本次核对下节四份来源：Trigger Relay 与 Mapper 的 SHA-256 仍分别为 `619c7405d0cb`、`9c67d71e95f7`；配置初始化集成测试和前端生成类型已变化，当前摘要分别为 `44a5fab394c5`、`9b6e3423cb63`。当前测试仍包含真实数据库 savepoint 回滚首项、提交第二项及统计断言；这只是源码核对，不能单凭它认定变化后的整类测试已运行。前端类型随后因 Workflow 节点名称契约变更重新生成并做过生成漂移及类型检查，见[治理记录](../audit/backend-25-package-governance-2026-10-26.md#已执行验证与未关闭项)的 `BUG-20261030-WORKFLOW-NODE-NAME`；不推断全部页面交互已验证。本次未重复运行业务测试或前端检查。

## 2026-10-28：剩余四项定向核查

本次核查以下当前源码和直接契约，未发现新增确认缺陷，未修改生产代码：

1. `TenantConfigInitCommitOutcomeIntegrationTest` 新增 savepoint 反例：实际回滚首项、提交第二项，并检查数据库与 created/failed 统计；`TenantConfigInitApplyHandlers.applySpecs` 在嵌套事务完成后才记录成功。已有真实 PostgreSQL 先红后绿及六分支证据见[治理记录](../audit/backend-25-package-governance-2026-10-26.md#已执行验证与未关闭项)，该证据对应当时快照，本次未重跑。
2. `TriggerOutboxRelay` 与 `TriggerOutboxEventMapper.xml`：当前批次领取生成 UUID，成功、失败和放弃按状态及 token 围栏更新，超时回收清除 token；写入型 RETURNING 声明刷新缓存。V361 同步主表与归档镜像。历史真实 PostgreSQL 重领反例与 Kafka 定向证据见同一治理记录的 BUG-20261027-TRG-02；未复验双实例锁失效和存量升级。
3. `src/types/api.generated.ts`：对配对后端本地 OpenAPI 执行 `npm run gen:api:check`，生成比较通过（`/private/tmp/wdi-four-api-drift-20261028.log`）。这是契约生成一致性检查，不代表完整前端交互验收。

读取时四份来源 SHA-256 依次为 `615a6e16452ff016fc90b71182329cae3271a4ec6523c351e0fa8a4c33efde18`、`619c7405d0cb42bf18c4d61c2a72892e517a47cf92a88b0ac5a7ea19eb59c4a6`、`9c67d71e95f78f91473f8b17bcd24f3574be992cae469021d082b69864dee80b`、`fee525808716d9c3dfc39609b30dcf90e1f6fcef4e681b645d2046b8d15d47b6`。按用户要求，仅针对新发现问题的类运行测试；本次未发现新增确认问题，未重跑业务类测试。下方10月27日待复核状态为当时记录，以本节为最新核查结论。

## 2026-10-28：前后端及未跟踪源码模式扫描

按用户要求只记录，本次未修改业务或测试代码，未运行测试。通过 Git 已跟踪与未跟踪且未忽略的文件清单扫描后端 4,548 份、前端 1,044 份 Java/TypeScript/JavaScript/Vue 源码，排除依赖与构建产物；该分母是文件枚举，不等于逐文件语义审核。以下行号为扫描快照，后续变动时应重新定位。

- **重复判空**：后端71处、45个文件符合 `isNull(x) || isBlank/isEmpty(x)` 模式；已核对工具对 null 的处理，简单局部变量的重复分支可作为精简候选。逐处仍须检查类型重载、求值顺序、上下文及实际维护状态。
- **一次调用的私有薄包装**：后端110处语法候选，仅以单文件出现次数和单行 return 初筛；有领域命名、递归、方法引用、反射、接口/代理或职责边界时可能合理，不等同110项缺陷。
- **前端包装**：84处语法候选；Vue 模板、事件绑定、导出和测试调用可能不以脚本中的普通调用表达式出现，数量不等于无用包装，必须核对模板及外部引用。只针对单行函数形态筛选，其他防御与抽象尚未语义审完。

### 重复判空候选

| 文件 | 候选位置 |
|---|---|
| [batch-common/src/main/java/io/github/pinpols/batch/common/enums/SystemPipelineDefinition.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/enums/SystemPipelineDefinition.java) | 10、19 |
| [batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchKafkaTopicNamespace.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchKafkaTopicNamespace.java) | 33 |
| [batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) | 126、130 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/DefaultConsoleApprovalApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/DefaultConsoleApprovalApplicationService.java) | 192 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ConsoleAiProperties.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ConsoleAiProperties.java) | 227 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/application/progress/PipelineProgressQueryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/application/progress/PipelineProgressQueryService.java) | 26 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/DefaultConsoleFileChannelApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/DefaultConsoleFileChannelApplicationService.java) | 183 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/entity/JobDefinitionEntity.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/entity/JobDefinitionEntity.java) | 27、33 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertNotificationReceiptService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertNotificationReceiptService.java) | 27 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertmanagerNotifyService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertmanagerNotifyService.java) | 200 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) | 134 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowDefinitionSaveRequest.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowDefinitionSaveRequest.java) | 67、79 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowScheduleUpdateRequest.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowScheduleUpdateRequest.java) | 56、84、96 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigSyncApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigSyncApplicationService.java) | 125、249 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigInitApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigInitApplicationService.java) | 40 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitTenantExecutor.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitTenantExecutor.java) | 168 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/PersistentWaitReadService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/PersistentWaitReadService.java) | 41 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/batchday/BatchDayCatchupPreviewService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/batchday/BatchDayCatchupPreviewService.java) | 46、48、61、63 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/WorkflowNodeReplayTargetResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/WorkflowNodeReplayTargetResolver.java) | 33 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySourceResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySourceResolver.java) | 45 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java) | 361、376 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | 516、622、970 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/query/JobDependencyRule.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/query/JobDependencyRule.java) | 13 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRouter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRouter.java) | 29 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDayOpenScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDayOpenScheduler.java) | 471 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDaySettleScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDaySettleScheduler.java) | 145、381 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/JobDependencyWaitingLaunchReconciler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/JobDependencyWaitingLaunchReconciler.java) | 61 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java) | 269、404 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/JobDependencyWaitingLaunchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/JobDependencyWaitingLaunchService.java) | 69、73、84、377、403、411、468、502 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchParamResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchParamResolver.java) | 89 |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/auth/TriggerApiKeyVerifier.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/auth/TriggerApiKeyVerifier.java) | 50 |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/QuartzMisfireRecoveryConfiguration.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/QuartzMisfireRecoveryConfiguration.java) | 40 |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java) | 422 |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java) | 43、86、88 |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java) | 196、198、232 |
| [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStep.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStep.java) | 70 |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java) | 172 |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ControlTotalEvaluator.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ControlTotalEvaluator.java) | 41 |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/TrailerControlRecord.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/TrailerControlRecord.java) | 59 |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/BatchManifest.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/BatchManifest.java) | 83 |
| [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingGenerationCleanupFence.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingGenerationCleanupFence.java) | 48 |
| [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/stage/PlatformProcessStagingGenerationCoordinator.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/stage/PlatformProcessStagingGenerationCoordinator.java) | 90 |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java) | 586 |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkCrossDayResult.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkCrossDayResult.java) | 22 |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java) | 78 |

### 后端薄包装候选

| 文件 | 候选位置 |
|---|---|
| [batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java) | 42 `sanitizeTopicSegment` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/security/RequestSignatures.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/security/RequestSignatures.java) | 90 `upper` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java) | 255 `matches` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/storage/FilesystemObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/FilesystemObjectStore.java) | 406 `syntheticEtag` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) | 125 `physicalMarker`、129 `logicalMarker` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java) | 335 `isTerminal` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleKafkaLagQueryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleKafkaLagQueryService.java) | 126 `cacheSegment` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ReadReplicaRoutingDataSource.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ReadReplicaRoutingDataSource.java) | 143 `observeReplicaConnection` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/ConsoleAiCostService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/ConsoleAiCostService.java) | 252 `unpriced` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java) | 853 `resolveSoftRuntimeSeconds` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleAlertApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleAlertApplicationService.java) | 106 `normalizeStatus` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TerminalNotificationDeliveryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TerminalNotificationDeliveryService.java) | 275 `notification` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) | 165 `nullToDash`、169 `truncate` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/realtime/ConsoleRealtimeEventHub.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/realtime/ConsoleRealtimeEventHub.java) | 281 `normalizeStream`、285 `normalizeFilter` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleOidcIdentityService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleOidcIdentityService.java) | 101 `isAscii` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleAuthenticationFilter.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleAuthenticationFilter.java) | 223 `isPublicAuthPath` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleLoginService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleLoginService.java) | 90 `invalidCredentials` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleSessionRegistry.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleSessionRegistry.java) | 184 `sessionStateTtl` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java) | 832 `requiredHeaders`、1029 `fileNameOrDefault` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java) | 212 `first` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantJobDefinitionApplySupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantJobDefinitionApplySupport.java) | 514 `resolveSoftRuntime` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java) | 1111 `resourceQueueExists` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/ConsoleOidcAuthenticationSuccessHandler.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/ConsoleOidcAuthenticationSuccessHandler.java) | 85 `isAscii` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/RedisOidcAuthorizationRequestRepository.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/RedisOidcAuthorizationRequestRepository.java) | 184 `validBrowserBinding` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/approval/OrchestratorApprovalClient.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/approval/OrchestratorApprovalClient.java) | 160 `hasText` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ratelimit/ConsoleRateLimitFilter.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ratelimit/ConsoleRateLimitFilter.java) | 153 `isExpensivePath` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/OutboxEventKeyGenerator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/OutboxEventKeyGenerator.java) | 116 `truncate` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/plan/DefaultSchedulePlanBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/plan/DefaultSchedulePlanBuilder.java) | 268 `clampMaxPartitionCount` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/ratelimit/TokenBucketRateLimiter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/ratelimit/TokenBucketRateLimiter.java) | 203 `configSupplierFor` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/scheduler/TenantSchedulerSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/scheduler/TenantSchedulerSnapshotService.java) | 246 `emptyBacklog` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DefaultDryRunPlanService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DefaultDryRunPlanService.java) | 116 `defaultParamsHas` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java) | 1244 `firstText` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java) | 741 `safeUrlQuery` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java) | 721 `stringValue` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java) | 873 `failedStatuses`、1182 `isCancellable` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySnapshotResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySnapshotResolver.java) | 309 `readProtectedDispatchChannelSnapshots` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultPartitionDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultPartitionDispatchService.java) | 93 `buildContext`、220 `initialPartitionStatus` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java) | 194 `buildBusinessKey` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java) | 664 `stringValue` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | 921 `isJobNode`、925 `isWaitNode` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowConditionEvaluator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowConditionEvaluator.java) | 324 `findAtomicOperator` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java) | 668 `readProtectedDispatchChannelSnapshots` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifier.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifier.java) | 226 `verifyCacheKey` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceLatencyMetrics.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceLatencyMetrics.java) | 64 `text` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java) | 476 `defaultText` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java) | 550 `defaultText` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceStorageMaintenance.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceStorageMaintenance.java) | 316 `sanitizeTrace` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/governance/AlertmanagerEmitPublisher.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/governance/AlertmanagerEmitPublisher.java) | 232 `stripTrailingSlash` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransport.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransport.java) | 118 `sameHost` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java) | 292 `normalizePolicy` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaReservationSupport.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaReservationSupport.java) | 54 `notApplicable` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/OrchestratorConfigInvalidationSubscriber.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/OrchestratorConfigInvalidationSubscriber.java) | 230 `eventKey` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/CrossDayDependencyReconciler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/CrossDayDependencyReconciler.java) | 165 `loadWorkflowRun` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultResourceScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultResourceScheduler.java) | 254 `isWaitingRetry` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelector.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelector.java) | 182 `currentLoadOrZero` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/GlobalJobAdmissionGuard.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/GlobalJobAdmissionGuard.java) | 38 `hasHardCap` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/observability/JobLifecycleMetricsRecorder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/observability/JobLifecycleMetricsRecorder.java) | 164 `resolveErrorCode` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifier.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifier.java) | 101 `hasValidSignatureShape` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java) | 354 `firstHeartbeat` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchBatchDayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchBatchDayService.java) | 423 `isMissingLaunchContext`、427 `isIncompleteBatchDay` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/capacity/CapacityProfileServiceTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/capacity/CapacityProfileServiceTest.java) | 201 `eqInt` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowReplayJobDefinitionResolverTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowReplayJobDefinitionResolverTest.java) | 178 `snapshot` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceArrivalGroupGuardTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceArrivalGroupGuardTest.java) | 401 `bizDate` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransportTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransportTest.java) | 258 `selections` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchWindowGateTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchWindowGateTest.java) | 189 `mockWorkerSelector` |
| [batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/MapperStatusWriteConventionTest.java](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/MapperStatusWriteConventionTest.java) | 111 `withoutComments` |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java) | 301 `isDedicatedRecoveryJob` |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerStatusQueryFacade.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerStatusQueryFacade.java) | 126 `unknown` |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | 88 `resolveCutoffTime`、105 `previousWorkday`、109 `nextWorkday` |
| [batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireApplicationRestartIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireApplicationRestartIntegrationTest.java) | 960 `jdbcUrlWithApplicationName` |
| [batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicRuntimeStatusService.java](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicRuntimeStatusService.java) | 113 `isProdProfile` |
| [batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java) | 455 `isLocalPath` |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java) | 537 `startExecution`、541 `lastConfirmedNanosValue` |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerRoutingPolicy.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerRoutingPolicy.java) | 192 `escapeRegex` |
| [batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapperTest.java](../../batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapperTest.java) | 830 `actualReplayCapability` |
| [batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapterTest.java](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapterTest.java) | 145 `port` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/infrastructure/S3ExportStorage.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/infrastructure/S3ExportStorage.java) | 135 `digestToHex` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/region/ExportRegionResolver.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/region/ExportRegionResolver.java) | 109 `firstNonBlank` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java) | 225 `isParameterStart`、229 `isParameterPart` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java) | 233 `defaultText` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportDataQualityService.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportDataQualityService.java) | 142 `asString` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java) | 466 `stringValue` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java) | 610 `isManifestMode` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java) | 217 `hasTemplateConfigSnapshot` |
| [batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java) | 353 `mockPayload` |
| [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java) | 390 `values` |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/RequestSigner.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/RequestSigner.java) | 77 `upper` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureContractTest.java) | 109 `fixturesDir`、113 `openapiPath` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureRequestSideContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureRequestSideContractTest.java) | 51 `fixturesDir` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/SharedConstantsParityTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/SharedConstantsParityTest.java) | 60 `yamlPath` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/KafkaConsumerAuthFailureTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/KafkaConsumerAuthFailureTest.java) | 49 `mockConsumer` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherTest.java) | 79 `objectMap` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandlerTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandlerTest.java) | 48 `ctxNoSql` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClientTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClientTest.java) | 499 `attemptedAddresses` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/scheduler/LeaseRenewalSchedulerTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/scheduler/LeaseRenewalSchedulerTest.java) | 51 `objectMap` |
| [sdk/java/spring/src/test/java/io/github/pinpols/batch/sdk/autoconfigure/BatchWorkerSdkAutoConfigurationTest.java](../../sdk/java/spring/src/test/java/io/github/pinpols/batch/sdk/autoconfigure/BatchWorkerSdkAutoConfigurationTest.java) | 185 `registeredHandlers` |
| [sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java](../../sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java) | 473 `asMap` |

### 前端薄包装候选

| 文件 | 候选位置 |
|---|---|
| [e2e/c-plus-coverage.spec.ts](../../../batch-console/e2e/c-plus-coverage.spec.ts) | 22 `ts` |
| [scripts/lib/docs-publication.mjs](../../../batch-console/scripts/lib/docs-publication.mjs) | 114 `escapeLabel` |
| [src/components/table/ProTable.vue](../../../batch-console/src/components/table/ProTable.vue) | 389 `scheduleRecompute` |
| [src/composables/useTenantReload.ts](../../../batch-console/src/composables/useTenantReload.ts) | 27 `onErr` |
| [src/views/approvals/components/CatchUpApprovalsTab.vue](../../../batch-console/src/views/approvals/components/CatchUpApprovalsTab.vue) | 210 `onSearch`、219 `onReset` |
| [src/views/approvals/components/GeneralApprovalsTab.vue](../../../batch-console/src/views/approvals/components/GeneralApprovalsTab.vue) | 424 `selectableRow`、482 `onSearch`、489 `reset` |
| [src/views/config/ConfigReleaseList.vue](../../../batch-console/src/views/config/ConfigReleaseList.vue) | 640 `onSearch`、647 `reset` |
| [src/views/file-center/ArrivalGroupList.vue](../../../batch-console/src/views/file-center/ArrivalGroupList.vue) | 224 `onSearch`、233 `onReset` |
| [src/views/file-center/FileList.vue](../../../batch-console/src/views/file-center/FileList.vue) | 934 `applyStatusFilter`、956 `applyTodayFilter`、1041 `onSearch`、1057 `reset` |
| [src/views/file-center/FilePipelineObservability.vue](../../../batch-console/src/views/file-center/FilePipelineObservability.vue) | 777 `onSearch`、785 `onReset` |
| [src/views/file-center/FileTemplateList.vue](../../../batch-console/src/views/file-center/FileTemplateList.vue) | 982 `onChannelSearch`、986 `resetChannels`、994 `onSearch`、1001 `reset` |
| [src/views/governance/QueueConfig.vue](../../../batch-console/src/views/governance/QueueConfig.vue) | 819 `onTabSearch`、827 `onTabReset` |
| [src/views/governance/QuotaPanel.vue](../../../batch-console/src/views/governance/QuotaPanel.vue) | 268 `onQuotaSearch`、275 `onQuotaReset`、284 `onQuotaRefresh` |
| [src/views/job/JobDefinitionList.vue](../../../batch-console/src/views/job/JobDefinitionList.vue) | 786 `onSearch`、792 `reset`、805 `onRefreshDefinitions` |
| [src/views/monitor/JobInstanceList.vue](../../../batch-console/src/views/monitor/JobInstanceList.vue) | 757 `resetQuery`、775 `searchInstances` |
| [src/views/monitor/JobStepInstanceList.vue](../../../batch-console/src/views/monitor/JobStepInstanceList.vue) | 140 `onQuerySearch`、147 `onQueryReset` |
| [src/views/monitor/PartitionView.vue](../../../batch-console/src/views/monitor/PartitionView.vue) | 236 `applyFilter`、244 `resetFilter` |
| [src/views/monitor/WorkflowRunDetail.vue](../../../batch-console/src/views/monitor/WorkflowRunDetail.vue) | 696 `applyNodeFilter`、705 `resetNodeFilter` |
| [src/views/monitor/WorkflowRunList.vue](../../../batch-console/src/views/monitor/WorkflowRunList.vue) | 318 `search`、325 `resetQueryBar` |
| [src/views/observability/AlertList.vue](../../../batch-console/src/views/observability/AlertList.vue) | 525 `search`、538 `reset` |
| [src/views/observability/AlertRoutingPanel.vue](../../../batch-console/src/views/observability/AlertRoutingPanel.vue) | 283 `onSearch`、292 `onReset`、303 `onRefresh` |
| [src/views/observability/AuditList.vue](../../../batch-console/src/views/observability/AuditList.vue) | 230 `search`、237 `reset` |
| [src/views/observability/OperationAuditList.vue](../../../batch-console/src/views/observability/OperationAuditList.vue) | 268 `search`、275 `reset` |
| [src/views/observability/components/ChannelReceiptsTab.vue](../../../batch-console/src/views/observability/components/ChannelReceiptsTab.vue) | 204 `applyFilter`、213 `resetFilter` |
| [src/views/observability/components/DeadLettersTab.vue](../../../batch-console/src/views/observability/components/DeadLettersTab.vue) | 380 `applyFilter`、389 `resetFilter` |
| [src/views/observability/components/RetriesTab.vue](../../../batch-console/src/views/observability/components/RetriesTab.vue) | 188 `applyFilter`、197 `resetFilter` |
| [src/views/scheduler/BatchDayList.vue](../../../batch-console/src/views/scheduler/BatchDayList.vue) | 459 `onSearch`、466 `reset` |
| [src/views/system/AiChat.vue](../../../batch-console/src/views/system/AiChat.vue) | 487 `onAuditSearch`、497 `onAuditReset` |
| [src/views/system/ApiKeyList.vue](../../../batch-console/src/views/system/ApiKeyList.vue) | 327 `onSearch`、334 `onReset` |
| [src/views/system/SystemParameterList.vue](../../../batch-console/src/views/system/SystemParameterList.vue) | 189 `onSearch`、195 `onReset` |
| [src/views/system/TenantList.vue](../../../batch-console/src/views/system/TenantList.vue) | 328 `onSearch`、337 `onReset` |
| [src/views/system/TriggerList.vue](../../../batch-console/src/views/system/TriggerList.vue) | 211 `onSearch`、218 `onReset` |
| [src/views/system/UserAccountList.vue](../../../batch-console/src/views/system/UserAccountList.vue) | 455 `onSearch` |
| [src/views/system/components/NotificationChannelsTab.vue](../../../batch-console/src/views/system/components/NotificationChannelsTab.vue) | 414 `applyChannelFilter`、422 `resetChannelFilter` |
| [src/views/system/components/NotificationDeliveryLogsTab.vue](../../../batch-console/src/views/system/components/NotificationDeliveryLogsTab.vue) | 171 `applyLogFilter`、179 `resetLogFilter` |
| [src/views/system/components/NotificationRulesTab.vue](../../../batch-console/src/views/system/components/NotificationRulesTab.vue) | 366 `applyRuleFilter`、374 `resetRuleFilter` |
| [src/views/system/components/NotificationWebhooksTab.vue](../../../batch-console/src/views/system/components/NotificationWebhooksTab.vue) | 391 `applyWebhookFilter`、399 `resetWebhookFilter` |
| [src/views/worker/MyWorkers.vue](../../../batch-console/src/views/worker/MyWorkers.vue) | 89 `onRefresh` |
| [src/views/worker/WorkerManagement.vue](../../../batch-console/src/views/worker/WorkerManagement.vue) | 707 `onWorkerSearch`、713 `resetWorkers`、948 `onChannelSearch`、955 `resetChannels` |
| [src/views/workflow/WorkflowDefinitionList.vue](../../../batch-console/src/views/workflow/WorkflowDefinitionList.vue) | 992 `onSearch`、999 `reset` |

## 2026-10-28：过度实现初筛

本次对相对当前 HEAD 有差异的 1,060 份已跟踪后端生产 Java 文件做定向模式初筛，关注重复空值判断和仅使用一次的私有薄包装；这是候选筛选，不代表全量语义审查，也不包括未跟踪文件。配对前端本次未做同等范围扫描。

- **重复空值判断**：`isNull(x) || isBlank(x)` 与 `isNull(x) || isEmpty(x)` 命中61处、37份文件。`EmptyChecks.isBlank` 和集合 `isEmpty` 已处理 null；例如 `NamespacedObjectStore.physicalMarker/logicalMarker`、`TaskDispatchExecutor` 的批量入参判断中的额外 `isNull` 可省。这里的判空本身仍有业务意义，应仅去掉重复部分；61处是机械候选，修改前须逐处核对重载、求值与调用现场。
- **一次性薄包装候选**：`DefaultTenantConfigPackageExcelService.requiredHeaders` 仅转发 `ConfigPackageSheetSpecs.requiredImportHeaders`，只有一处调用，调用点可直接使用后者。是否精简还应结合邻近代码可读性决定；不按单次调用机械删除具有领域含义或代理边界的方法。
- 本次只扫描、未修改业务代码、未跑业务测试；其他防御校验、前端、测试 mock、未跟踪源码和更复杂的抽象尚未完成审查，不能据此声称过度实现已全部找出。

## 2026-10-30：测试必要性初步核查

在配对 `fix/wdi-current` 工作树，对 Git 已跟踪及未跟踪、未忽略测试文件做字节级重复检查：后端 1,434 份 `*Test.java`，前端 410 份 `.test.ts` / `.spec.ts`，未发现完全相同的文件。此项不能识别不同写法的行为重复；不包含其他命名、脚本及所有测试资源，不代表全量语义审查。

- **建议合并，不能直接删除**：[UpdateBatchDayReplayEntryStatusParamTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/domain/param/UpdateBatchDayReplayEntryStatusParamTest.java) 的 `shouldBuildWhenRequiredFieldsArePresent` 与后续 `shouldBuildLegalStateShapesFromNamedFactories` 都以 `succeededWithResult` 验证成功状态和时间；但前者独有 ID 断言。可将该断言移入后者并保留必填字段负例，再考虑移除前者；当前未修改测试，也未验证合并。
- **保留**：前端 [storageKeys.test.ts](../../../batch-console/src/constants/storageKeys.test.ts) 以独立预期保护已发布持久化键兼容；不是简单重复常量。后端 Worker DTO 测试中的上限、集合快照及参数工厂测试中的必填字段/状态形态均有独立契约作用，不能仅凭 getter 断言判断无用。

本次只读代码、登记结果，未运行测试；尚未逐项比较全部测试的行为覆盖，不能推断其他测试均有必要或存在多少可删除项。

## 短断言候选复核进展

对原321项短断言候选检查了索引，已核实以下代表性场景；尚未逐项完成全部上下文审查，不能据此认定其余项可删或应保留。本轮仅记录，未修改、删除或运行测试。

- `iconResolve.test.ts` 的 `toBeDefined()` 和 `not.toBeNull()` 分别排除 `undefined` 和 `null`，并非重复；此前将其称为重复候选的口头判断已更正。该测试对具体图标身份的证明仍弱于其标题，是否加强应另评估。
- `lastApiMeta.test.ts` 对 `null` 和 `undefined` 分别测试输入边界，`metaEnumPick.test.ts` 同理；虽然当前实现统一处理，两个公开输入不应仅因输出相同而删除。
- `HttpTaskExecutorIpBlockTest` 不同地址涉及 IPv4、IPv6、映射地址、元数据地址与公网反例，应保留各自安全边界；`PreprocessRangeSliceTest` 的无尾换行、空行和 CRLF 也分别测试不同文件格式。
- `RetryScheduleStatusTest.shouldContainSixValues` 锁定枚举总数，六个编码断言只证明已列举值的编码，新增第七项时不会失败，因此不能认定数量断言与编码断言重复。
- `resourceCodeBuilder.test.ts` 中空值、最小/最大长度及首字符规则为不同边界；`theme.test.ts` 的默认值、已保存值和旧值迁移为不同状态，不因代码短而合并。

## 测试候选上下文复核续记

**10组后端相同正文**：第1组分别实例化 Job 与 Workflow 通知消费者，验证各自无效身份不产生业务动作；第2组分别执行 HTTP 与 Shell 执行器的日志脱敏；第3、4、5、7、8组的 ArchUnit 规则分别导入各模块的生产类；第9组分别启动五类 Worker 并验证各自端口，以上保留各自覆盖。第6组 Workflow DAG 两个 FILE_STEP 拒绝用例与第10组 Readiness 两个空结果用例的重复结论见下文；保留其更完整断言或按真实业务状态补充反例后再考虑合并，不直接删除。

**9组前端相同正文**：第1、2、3、4、6、7、8组分别处于不同路由、页面或功能区域；第9组处于资源标签和搜索标签两个不同 tab，不能仅凭正文相同删除。第5组两条 Outbox stats 200 检查的请求相同，但分别处于 Trigger 链和 Outbox 故障恢复的串行场景，且后者先创建故障数据；可讨论合并通用存活检查，但需先确认单独运行、失败定位和阶段前置条件，暂不判为可删。多处 UI 测试在控件不可见时提前返回，或仅断言容器存在；应作为低有效性改进候选，不能把它们的成功视为实际操作成功。

**仍未完成**：以上针对已有19组相同正文候选核对业务上下文；另有后端208、前端113个短断言语法候选，尚未逐个核实，不应报告整个候选集已完成。未修改、删除或执行测试；以上仅基于当前代码静态核对。

## 2026-10-30：测试重复性全量索引与上下文复核

只读前后端 Git 已跟踪及未跟踪、未忽略的 `*Test.java`、`.test.ts`、`.spec.ts`：后端 1,434 个文件、前端 410 个文件。简单解析器识别后端 8,646 个、前端 2,011 个测试体，按去除空白的正文计算相同组；未识别测试、其他命名和动态生成测试不在测试体计数内。相同正文未必测试相同对象，短断言也未必无效；以下为筛选和有限上下文核查，**不代表全量测试已逐项语义审核**。没有删除或修改测试，也未执行测试。

### 已核实的重复及处置建议

1. `WorkflowDagValidatorTest.shouldFail_whenFileStepPipelineCodeMissing` 与 `shouldFail_whenFileStepPipelineNotFound`：同类、相同输入与断言，且不存在不同的类级 fixture；可合并为一个退役节点拒绝用例。后者标题称旧 Pipeline 存在，但该测试没有建立这种状态；如该场景仍需保障，应补真实可区分前提，而不是将当前测试视为证据。
2. `ReadinessServiceTest.shouldNotBeReady_whenRerunNotEffective` 与 `shouldNotBeReady_whenNoAssetPartition`：相同 mock 返回空、相同调用与断言；同类 `shouldNotBeReady_whenAssetPartitionNotEffective` 覆盖该结果且断言更多。可考虑保留覆盖更完整的用例；若要证明“重跑未生效”，应另用能表达重跑状态的真实场景。
3. 上一轮登记的 `UpdateBatchDayReplayEntryStatusParamTest.shouldBuildWhenRequiredFieldsArePresent` 与具名工厂测试部分重复；先迁移独有 ID 断言，再评估合并。

### 不应按正文重复直接删除

- ArchUnit 的相同规则在各模块以不同 `CLASSES` 包范围执行；五种 Worker 的端口测试分别启动不同应用，均不能因正文相同而合并删除。
- 前端 `tag-ops.spec.ts` 两个 Tag Key 测试的正文相同，但 `beforeEach` 分别进入资源标签与搜索标签，作用于不同 tab，应保留独立业务覆盖。其他跨页面 E2E 相同正文同样须先确认路由和上下文。
- 前端页面操作测试存在条件不满足直接 return、只断言容器存在的低信息候选；这属于测试真实性改进线索，不能直接作为删除依据，也不能当作真实业务链通过证据。

### 相同正文的候选组

后端10组、前端9组，以下保留全部初筛位置供逐项复核。行号是扫描快照；除上文明确说明外，均未认定可删除。

**后端**

1. [JobTerminalNotificationConsumerTest.java:72](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/notification/infrastructure/JobTerminalNotificationConsumerTest.java) `shouldDropInvalidIdentityWithoutBusinessAction`；[WorkflowTerminalNotificationConsumerTest.java:72](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/notification/infrastructure/WorkflowTerminalNotificationConsumerTest.java) `shouldDropInvalidIdentityWithoutBusinessAction`
2. [HttpTaskExecutorTest.java:96](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorTest.java) `shouldMaskUnexpectedErrorInLog`；[ShellTaskExecutorTest.java:63](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/shell/ShellTaskExecutorTest.java) `shouldMaskUnexpectedErrorInLog`
3. [CodingConventionsArchTest.java:20](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/CodingConventionsArchTest.java) `shouldForbidDefaultTimezoneReads_whenOutsideTimezoneInfrastructure`；[OrchestratorCodingConventionsArchTest.java:18](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoJvmDefaultTimeZoneUsage_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:18](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `zoneIdSystemDefault_isForbidden`；[ConsoleCodingConventionsArchTest.java:24](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidJvmDefaultTimeZone_whenRuleChecked`
4. [CodingConventionsArchTest.java:26](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/CodingConventionsArchTest.java) `shouldForbidEncodingLookupByName_whenOutsideEncodingUtils`；[OrchestratorCodingConventionsArchTest.java:24](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoNameBasedCharsetConstruction_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:24](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `charsetForName_isForbidden`；[ConsoleCodingConventionsArchTest.java:30](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidCharsetLookupByName_whenRuleChecked`
5. [CodingConventionsArchTest.java:32](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/CodingConventionsArchTest.java) `shouldForbidRecordSuffix_whenNamingPersistentOrDomainClasses`；[OrchestratorCodingConventionsArchTest.java:30](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoRecordSuffixTypeName_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:30](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `recordSuffix_isForbidden`；[ConsoleCodingConventionsArchTest.java:36](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidRecordSuffixTypeName_whenRuleChecked`
6. [WorkflowDagValidatorTest.java:168](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/validation/WorkflowDagValidatorTest.java) `shouldFail_whenFileStepPipelineCodeMissing`；[WorkflowDagValidatorTest.java:179](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/validation/WorkflowDagValidatorTest.java) `shouldFail_whenFileStepPipelineNotFound`
7. [OrchestratorCodingConventionsArchTest.java:36](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoTransactionalEventListener_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:36](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `transactionalOnEventListener_isForbidden`；[ImportCodingConventionsArchTest.java:23](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/arch/ImportCodingConventionsArchTest.java) `shouldRejectTransactional_whenEventListenerMethodAnnotated`；[ConsoleCodingConventionsArchTest.java:42](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidTransactionalOnEventListener_whenRuleChecked`
8. [OrchestratorCodingConventionsArchTest.java:42](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoTransactionalScheduledMethod_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:42](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `transactionalOnScheduled_isForbidden`；[ImportCodingConventionsArchTest.java:29](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/arch/ImportCodingConventionsArchTest.java) `shouldRejectTransactional_whenScheduledMethodAnnotated`；[ConsoleCodingConventionsArchTest.java:48](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidTransactionalOnScheduledMethod_whenRuleChecked`
9. [BatchWorkerAtomicApplicationIntegrationTest.java:35](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/integration/BatchWorkerAtomicApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerDispatchApplicationIntegrationTest.java:31](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/BatchWorkerDispatchApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerProcessApplicationIntegrationTest.java:50](../../batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/BatchWorkerProcessApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerImportApplicationIntegrationTest.java:32](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/BatchWorkerImportApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerExportApplicationIntegrationTest.java:31](../../batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/BatchWorkerExportApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`
10. [ReadinessServiceTest.java:92](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/readiness/ReadinessServiceTest.java) `shouldNotBeReady_whenRerunNotEffective`；[ReadinessServiceTest.java:107](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/readiness/ReadinessServiceTest.java) `shouldNotBeReady_whenNoAssetPartition`

**前端**

1. [scheduler-governance-ops.spec.ts:153](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `状态筛选 → 查询`；[alert-outbox-ops.spec.ts:154](../../../batch-console/e2e/alert-outbox-ops.spec.ts) `重试 tab — 状态筛选`
2. [scheduler-governance-ops.spec.ts:199](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `刷新`；[scheduler-governance-ops.spec.ts:291](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `刷新`；[alert-outbox-ops.spec.ts:178](../../../batch-console/e2e/alert-outbox-ops.spec.ts) `刷新`；[trigger-ops.spec.ts:29](../../../batch-console/e2e/trigger-ops.spec.ts) `刷新`；[file-ops.spec.ts:54](../../../batch-console/e2e/file-ops.spec.ts) `刷新按钮`；[tag-ops.spec.ts:58](../../../batch-console/e2e/tag-ops.spec.ts) `刷新`；[log-ops.spec.ts:19](../../../batch-console/e2e/log-ops.spec.ts) `刷新按钮可用`
3. [scheduler-governance-ops.spec.ts:204](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `关键字筛选 → 查询`；[alert-outbox-ops.spec.ts:146](../../../batch-console/e2e/alert-outbox-ops.spec.ts) `重试 tab — 关键字搜索`；[file-ops.spec.ts:215](../../../batch-console/e2e/file-ops.spec.ts) `关键字搜索 → 查询`
4. [scheduler-governance-ops.spec.ts:212](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `启用 / 停用切换 → 确认 → toast`；[scheduler-governance-ops.spec.ts:272](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `启用 / 停用切换 → 确认 → toast`
5. [08-outbox-stuck-republish.spec.ts:92](../../../batch-console/e2e/flows/08-outbox-stuck-republish.spec.ts) `1. 查 outbox stats — 通道存活`；[01-trigger-instance.spec.ts:57](../../../batch-console/e2e/flows/01-trigger-instance.spec.ts) `4. outbox stats 接口可访问 → 发布通道存活`
6. [user-account-ops.spec.ts:28](../../../batch-console/e2e/user-account-ops.spec.ts) `刷新`；[worker-ops.spec.ts:47](../../../batch-console/e2e/worker-ops.spec.ts) `刷新按钮`
7. [ops-shard-catalog-crud.spec.ts:29](../../../batch-console/e2e/ops-shard-catalog-crud.spec.ts) `页可达 + 新建入口可见`；[ops-tenant-placement-crud.spec.ts:23](../../../batch-console/e2e/ops-tenant-placement-crud.spec.ts) `页可达 + 新建入口可见`
8. [monitor-ops.spec.ts:150](../../../batch-console/e2e/monitor-ops.spec.ts) `分片列表加载`；[worker-ops.spec.ts:118](../../../batch-console/e2e/worker-ops.spec.ts) `文件渠道列表加载`；[log-ops.spec.ts:15](../../../batch-console/e2e/log-ops.spec.ts) `页面正常打开并展示表格`
9. [tag-ops.spec.ts:42](../../../batch-console/e2e/tag-ops.spec.ts) `Tag Key 输入 → 查询`；[tag-ops.spec.ts:73](../../../batch-console/e2e/tag-ops.spec.ts) `Tag Key 搜索`

后端另有208个、前端113个短断言语法候选，尚未逐一确认；短小不等于无效，边界、状态机、持久化键及独立协议预期应保留。下一步应优先核对这些候选的具体行为和相邻测试，而非按数字批量删除。

## 2026-11-01：测试与规约候选续核

核对基线：后端仓库的 `perf/local-loadtest-2026-10-31`，HEAD `0258697d14229e9da1bddb547943acf9d2481338`，治理台账有既有未提交修改；前端配对仓库 `main`，HEAD `e44c3b2d29e29805d95a48bafc167e8d5725129d`。只读代码并在本报告留记录，未修改业务或测试代码。

### 确认可以提出的规约整改

| 位置 | 证据与建议 | 范围和验证要求 |
|---|---|---|
| [BatchKafkaTopicNamespace](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchKafkaTopicNamespace.java) `apply` | `isNull(name) || isBlank(name)` 对同一个方法参数重复检查；`isBlank` 经 `Texts.hasText` 对 null 返回 true。可删除前半项，保留空白拒绝。 | 仅检查该表达式；实施时验证 null、空白和合法命名空间语义及异常。 |
| [NamespacedObjectStore](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) `physicalMarker`、`logicalMarker` | `isNull(marker) || isBlank(marker)` 的 null 分支被后者包含；可保留后者并维持 null、空白 marker 原值返回。 | 不删除判空功能，不改 prefix/marker 处理或对象存储契约。 |
| [PipelineProgressQueryService](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/application/progress/PipelineProgressQueryService.java) `normalize` | `isEmpty(ids)` 对 null 和空集合均返回 true，前面的 `isNull(ids)` 可省；后续 size 与元素判断的短路顺序保留。 | 验证 null、空、超限及非法 ID；不能提前执行 stream。 |
| [DefaultTenantConfigPackageExcelService](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java) `requiredHeaders` | 私有方法只转发 `ConfigPackageSheetSpecs.requiredImportHeaders`，当前类只有一个调用，可考虑在调用处直接使用目标方法。 | 可读性建议；保持实参求值次数、异常和表头校验，非业务 Bug。 |

### 确认不应机械整改

- 前端 [ChannelReceiptsTab.vue](../../../batch-console/src/views/observability/components/ChannelReceiptsTab.vue) 的 `applyFilter`、`resetFilter` 被模板的搜索、重置和回车事件引用，并承载草稿/已应用条件与刷新流程；旧的脚本内调用次数不能证明其无用。
- `iconResolve.test.ts` 的非 undefined 和非 null 断言排除不同值；`lastApiMeta` 对 null 和 undefined 是独立输入边界。短断言和测试体相似本身不构成删除理由。
- 历史 321 条短断言的名称和记录的正文片段均能在当前配对仓库找到；这是存在性检查，不证明 fixture、生产调用方、断言覆盖或源码快照整体未变，更不代表逐项审查完成。
- Map 已有处理及动态 Map 合理保留的分类见[25 包治理记录](../audit/backend-25-package-governance-2026-10-26.md)，不把 public Map 词法命中直接计为违规。

**尚未完成**：原321个短断言、71个重复判空、后端110个及前端84个薄包装候选尚未逐项完成当前上下文核验；上述为本次可独立定位的结果，不将候选总数等同需整改数。未运行编译或测试；将来实施时仅对实际受影响范围做验证。

## 2026-11-01：Worker 判空续改与证据重放范围

`TaskDispatchExecutor` 的 `workerId` 和 `messages`、`DefaultTaskExecutionWrapper` 的 `results`、`items` 与 `tasks` 都是同一表达式内的参数或局部值；`isBlank/isEmpty` 已处理 null，移除六个重复的 `isNull`，保留认领、租约和原来的后续短路条件。`./mvnw -ntp -pl batch-worker/core -am '-Dtest=TaskDispatchExecutorTest,DefaultTaskExecutionWrapperTest,DefaultTaskExecutionWrapperClaimLeaseTest' -Dsurefire.failIfNoSpecifiedTests=false test`：三类41项通过，0失败、错误、跳过；日志 `/private/tmp/wdi-overdefense-worker-20261101.log`。此测试针对本次代码改动，不是对历史45个编号重放。

用户要求历史“首红、复验待证”只指定测试类，不重复运行；当前台账提供的按主题测试载体为：

| 主题 | 可指定的测试类或方法 | 局限 |
|---|---|---|
| 批日补跑、审批、日切 | `ConsoleBatchDayCatchUpHttpIntegrationTest`、`BatchDayCutoffMultiTenantIntegrationTest` | 两项历史业务口径仍需先裁定；不声称覆盖全部编号。 |
| 文件束与分发 | `DispatchPipelineE2eIT#shouldDeliverEveryFile_whenBundleDispatchTaskExpandsIntoSubtasks` | 用例有真实依赖，范围限对应分发路径。 |
| 传感器、演练计划 | `WorkflowSensorApplicationContextRestartIntegrationTest`、`DefaultDryRunPlanServiceTest` | 不自动覆盖其他超时和重试编号。 |
| 归档血缘 | `SameJobMultiRunArchiveLineageIntegrationTest` | 跨作业共享文件历史口径仍待裁定。 |
| 配置初始化/同步 | `TenantConfigInitCommitOutcomeIntegrationTest`、`TenantConfigBundleCompletenessIntegrationTest` | 只覆盖各自事务结果和配置包场景。 |
| 内容脱敏 | `ContentMaskingUtilsTest` 以及按具体编号选取对应服务/HTTP 集成测试 | 工具类测试不能替代各输出通道和真实 HTTP 的证据；未对28个编号逐个映射。 |

上述清单来自[25包交付锚点](../audit/backend-25-package-governance-2026-10-26.md#交付锚点)，是选择性测试载体，不是45编号的完整一对一映射。本次没有重放这些历史证据，也不将历史首红变为当前通过。

## 2026-11-01：已确认候选的首批整改

在后端当前工作区 `perf/local-loadtest-2026-10-31`（HEAD `0258697d`）处理本报告已明确可简化项：Kafka 名称和对象存储 marker 的重复 null/blank 判断、Pipeline 进度参数的重复 null/empty 判断保留 `isBlank`/`isEmpty` 语义及短路；配置包表头的私有薄转发移除并以局部变量承接原结果。

测试只合并已证实同前提、同调用和同断言的 Workflow DAG、Readiness 用例；参数工厂用例合并时保留 ID 与 now 断言。前端 `iconResolve.test.ts` 合并重复的已知图标用例，保留非 undefined 与非 null 的两个独立断言及不同 fallback 对照；`npx vitest run src/utils/iconResolve.test.ts` 3/3 通过（`/private/tmp/wdi-overdefense-icon-20261101.log`）。未删除测试类；被删除测试名称所描述的“历史 Pipeline 已存在”“重跑未生效”并未由其原夹具分别构造，不把合并当成这些场景已有证据。

定向验证：`./mvnw -ntp -pl batch-console-api,batch-orchestrator -am '-Dtest=BatchKafkaTopicNamespaceTest,NamespacedObjectStoreTest,PipelineProgressQueryServiceTest,DefaultTenantConfigPackageExcelServiceWorkbookTest,UpdateBatchDayReplayEntryStatusParamTest,WorkflowDagValidatorTest,ReadinessServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test` 成功，7 类合计41项，0失败、0错误、0跳过；日志 `/private/tmp/wdi-overdefense-target-20261101.log`。此命令按依赖编译上游模块，不等于完整 reactor 全量测试。原71处重复判空词法模式目前仍有67处、42文件；其余短断言、前后端薄包装及 Map 候选尚未逐项裁定，本批完成不等于全部整改。

## 当前核查结论

本报告为本轮前后端审查与验证的唯一记录，合并原后端审查、后端验证和前端整改三份10月27日报告，并删除被替代文件；问题、首红、命令、历史覆盖数字和环境限制保留在下文。10月26日独立历史记录保留。当前执行范围以[差异审查清单](../plans/todo-master.md#当前差异审查清单)为准，下文历史“继续全仓”安排不再作为本轮执行指令。

- **工作位置**：配对 `wdi-current` 工作树，分支均为 `fix/wdi-current`；后端HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`，前端HEAD `662d254e2f8b97f93021de548815470d2291c997`。未修改main或冻结参考树，未暂存、提交或发布。
- **本次完成**：原14项待复核来源的读取快照已核对，未发现新增的已确认Bug；收尾时13项仍与读取内容一致，1份测试被并发追加用例，新增内容待复核。未修改生产或测试代码。此结论限于下表关注点，不将这些文件全文或其所在模块标为全部已审。
- **验证边界**：按“只跑有问题的类”执行。此次没有确认新的代码缺陷，也没有因此启动Java/前端测试、编译或全模块回归。下文运行结果为历史源码快照的证据，本次没有重新产生业务测试通过数。
- **比较限制**：原14项中10项当前摘要不同、4项与待复核台账摘要相同；4项相同只说明自登记后未继续漂移，不等于此前已通过。旧摘要未附完整旧文件正文，不能宣称已恢复并逐字证明所有旧新变换等价；本次依据当前源码、只读冻结参考内容、原问题及反例核对所列不变量。
- **复用范围**：已核对日切原首红/修复后命令与日志、Worker生命周期/领取CAS、配置事务及前端静态路由历史证据的归属；没有将旧编译、旧全量测试或文件哈希一致当作当前完整运行保证。

### 原14项风险片段复核

表中摘要固定本次读取内容，完整旧摘要仍保留在原CSV。该CSV继续作为历史覆盖记录，不重写为本轮全文件审核完成。

| 来源 | 本次核对结论 | 本次读取快照SHA-256前12位 |
|---|---|---|
| [DefaultConsoleConfigSyncApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigSyncApplicationService.java) | 目标身份先验拒绝；原键查询受租户约束；typed审计行、独立审计事务及收据核对仍衔接 | `026a63680ceb` |
| [TenantConfigCopyTypeLocaleTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigCopyTypeLocaleTest.java) | 两种默认语言与三类协议值断言保留，finally恢复Locale | `8a1f9d1927c5` |
| [TenantConfigInitCommitOutcomeIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitCommitOutcomeIntegrationTest.java) | 原提交后确认异常路径已核对；收尾并发新增savepoint回滚统计用例，新增内容待复核 | `d0345c6979d4` |
| [TenantConfigInitOutcomeTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitOutcomeTest.java) | 未知提交停止扇出并恢复上下文；已知回滚继续；非法目标零执行断言保留 | `c3f8f370f5b2` |
| [TenantConfigBundleCompletenessIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigBundleCompletenessIntegrationTest.java) | 201队列、最高版本及合法150节点/296边断言保留，SQL参数顺序与本租户清理核对 | `8848586855c8` |
| [BatchDayCutoffMultiTenantIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayCutoffMultiTenantIntegrationTest.java) | 多租户各自审计、版本推进一次及未来/冻结项保持断言保留 | `6170b2a2449a` |
| [CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | 按实际Instant比较日切；重叠取首次、缺口取跳时边界；原DST及等于日切反例仍在 | `3b4e5429a8c3` |
| [TriggerAdmissionDrainTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/config/TriggerAdmissionDrainTest.java) | 排队后再次检查排水；三入口各有拒绝零调用与正常接纳反例，线程收尾仍在 | `ccb24198c9c7` |
| [TriggerSchedulingOwnershipTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/infrastructure/scheduler/TriggerSchedulingOwnershipTest.java) | 实际组件扫描有非空正例，并断言Trigger不注册日切状态任务；不等同全部调度实现证明 | `e2eef600fe29` |
| [ExternalEventApiKeyHttpIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/ExternalEventApiKeyHttpIntegrationTest.java) | 认证/来源绑定、固定业务日、原事件重投及范围外Workflow拒绝、请求/Outbox计数断言保留 | `61f49716eea4` |
| [TaskDispatchExecutor.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java) | 空输入仍短路；仅认领成功项执行；批末清理未执行项，不重跑已执行业务 | `158dbab885cd` |
| [ActiveTaskLeaseRegistry.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java) | 清理仅匹配tenant/worker/invocation且未启动项；判断与移除共用写锁，已启动项保留 | `f1e6261ab2c4` |
| [WorkerRuntimeState.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerRuntimeState.java) | computeIfPresent内仍用current != expected判实例身份，迟到心跳不能替换新注册 | `f77787a08a3a` |
| [WorkerReportOutboxCoordinator.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxCoordinator.java) | 成功/失败/损坏处理传递完整领取行；凭据CAS仍由Repository约束，计数仅在生效后增加 | `e8af342dbed1` |

### 新变化与未验证边界

`TenantConfigInitCommitOutcomeIntegrationTest.java` 在本次读取后新增 `shouldCountOnlyCommittedItems_whenNestedItemRollsBackAtCompletion`，不能沿用读取快照的核查结论覆盖新用例。表中保留本次读取摘要，原确认异常用例结论仍限其当时内容；该文件最新新增内容及对应运行验证单列待复核，未覆盖其他工作的修改或并行启动编译。

对原已审/部分审查来源只做摘要新鲜度检查，后端278项、前端140项；此操作不是新增418项语义审核。另发现以下3项与原登记摘要不同，不沿用旧通过结论：

- 后端 `TriggerOutboxRelay.java` 与 `TriggerOutboxEventMapper.xml`：当前变化内容待专项核对，未据文件变化提前认定Bug，也未额外启动Trigger模块测试。
- 前端 `src/types/api.generated.ts`：生成契约当前摘要变化，未重跑生成漂移或前端全量测试；未据此宣称已有组件证据覆盖新契约。

上述3项及收尾新增测试内容均仅登记变化，共4处待复核，不自动扩大当前核查为新的全仓扫描。URI/JDBC/计量/Nginx等原候选仍按主清单判定与本轮修复的直接关系后安排，不计为已修复问题。

### 合并与轻量检查

本次迁移仅重组文档标题、链接与当前状态；三份原报告的正文证据及命令保留，后端命令在后端根目录、前端命令在前端根目录执行。合并检查不与业务运行验证混计。

- `bash scripts/python.sh scripts/ci/check-docs-structure.py`：后端文档结构通过。
- `python3 scripts/ci/check-doc-timestamp-policy.py`：文档日期命名通过。
- 前端 `npm run check:docs`：75份当前文档通过。
- 迁移完整性核对：原报告225行非标题正文、7个代码块和70个原章节映射均保留；三份原文件已删除，已迁移链接/锚点核对通过。
- 收尾来源核对：13份与本次读取内容一致；1份测试的新用例和另外3份变化保留待复核，未新增业务测试通过结论。

- [后端问题与历史审查](#backend-review-record)
- [后端历史运行验证](#backend-validation-record)
- [前端问题与历史运行验证](#frontend-review-record)

<a id="backend-review-record"></a>

<a id="backend-review-后端代码-bug-续审2026-10-27"></a>

## 后端问题与历史审查

> 以下保留各批次当时的发现和验证，不将历史进度或继续审查安排当作当前完成结论。

> **2026-10-27范围调整**：全工程逐文件续审已停止，后续以[当前差异审查清单](../plans/todo-master.md#当前差异审查清单)为准，优先核对已有修复及直接影响。本报告保留各批次当时的发现、覆盖数字与验证；正文中的继续审查安排属于原计划，不代表当前执行范围。代码审查仍暂停，本次仅调整清单。

<a id="backend-review-范围与状态"></a>

### 范围与状态

继续[原逐文件任务](../plans/todo-master.md#前后端全工程逐文件-bug-续审2026-10-26)，只审查Bug与运行风险。既有配对 `wdi-current` 工作树均为 `fix/wdi-current`，后端HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`，前端HEAD `662d254e2f8b97f93021de548815470d2291c997`。保留共享暂存/未暂存/未跟踪改动，未提交或发布。

本日截至后端定位与产物验收批次确认并修复十六项，运行证据见[当日验证](#backend-validation-record)。全工程逐文件审查仍未完成，持续清单沿用[原任务内容摘要台账](backend-bug-file-coverage-2026-10-26.csv)，日期为任务起点而非当前源码冻结日期；源文件变化后需复核。

<a id="backend-review-确认问题"></a>

### 确认问题

| ID | 触发与旧行为 | 修复与证据 |
|---|---|---|
| BUG-20261027-01 / P2 | 配置复制服务用 `PageRequest(1,5000)` 读取完整包，但公共请求将页大小限制为200，且只读取第一页；同步导出、复制/差异和矩阵依赖同一入口，会漏配置，Workflow边也被截断 | 复用既有Mapper空分页全量读取契约，保留租户与只读重复读快照、普通列表200条限制；真实PG验证201条队列及合法150节点/296边完整读取 |
| BUG-20261027-02 / P2 | 矩阵显式指定展示列表外的基准时，只读取展示租户，未加载基准，所有行可能得到空 `driftFields` | 额外读取基准供比较，响应仍只包含请求租户；基准缺少作业按缺失事实计算存在性差异。两个新增用例先红后绿，原默认/显式列表内基准用例保留 |
| BUG-20261027-03 / P1 | 源租户同时作为复制目标时，UPSERT将去除环境字段的渠道包回写源租户；真实认证HTTP返回200且原端点/配置标记均被清空 | 服务在读取源包前拒绝源目标重合，HTTP400且原配置不变；直接内部调用同样拒绝，中英文错误键验证通过。前端更换源时移除重合项，FE-BUG-42记录同一风险链的页面守卫 |
| BUG-20261027-04 / P2 | 复制、预览、矩阵及作业包导入的目标/作业元素缺少非空白约束；前者可返回200/500，作业包旧HTTP返回200且已写入前一合法租户 | 租户/作业元素增加NotBlank，初始化应用服务在任何目标事务前校验全部身份；类型null原已在HTTP反序列化层拒绝，NotNull仅补齐Bean声明，不计独立运行时Bug；可选空集合原语义保留 |
| BUG-20261027-05 / P2 | 配置类型大小写转换依赖JVM默认语言，土耳其语/阿塞拜疆语环境拒绝合法小写类型 | 使用Locale.ROOT；两环境回归先出现非法类型异常，修复后解析与兼容简写一致，默认Locale在测试后恢复 |
| BUG-20261027-06 / P1 | Workflow导入使用模糊编码搜索的第一条结果定位写入，UPSERT可改名并改图到相似编码，SKIP_EXISTING误跳过应创建的编码 | 增加精确编码最高版本查询；两种模式的真实PG回归均先红，修复后创建正确编码并保留原相似定义及图 |
| BUG-20261027-07 / P2 | 创建去重只查版本1；当前行升版后同码创建返回200并新增独立定义 | 创建按精确编码检查当前最高版本，真实认证HTTP返回409且仍一条定义；没有删除历史快照或修改迁移 |
| BUG-20261027-08 / P2 | 配置包导出同编码多版本物理定义为多条无版本Spec，旧定义可在目标重复应用并覆盖当前意图 | 按租户/编码只读最高版本及其图；当前版本停用仍保留停用，不回退旧启用版本。真实PG单项导出回归先红后绿 |
| BUG-20261027-09 / P2 | 执行池强停或停机线程被中断后，shutdownNow只移走排队任务，Future未终结，监听方继续等待到任务执行超时 | 取消shutdownNow返回的未启动Future，两条强停路径先红后绿；保留运行中业务的协作中断和原有宽限，不将Future取消等同于业务线程结束 |
| BUG-20261027-10 / P2 | 心跳请求在途时下线移除注册，迟到响应无条件put恢复旧注册；同标识新注册也被覆盖，并执行旧平台指令 | 缓存按原注册实例原子更新，已删除或替换时忽略回包及其指令；真实线程交错两项先红后绿，结构相等的新对象同样受保护 |

<a id="backend-review-影响路径"></a>

### 影响路径

- [复制与配置包服务](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java) → 同步导出/预览、租户复制/差异和作业矩阵。九类当前配置以及Workflow节点/边完整读取；首批复用既有空分页，后续增加精确编码和每编码最高版本两条查询，未修改Flyway。
- [作业矩阵构建器](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java) → [矩阵请求](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/contract/request/config/TenantConfigMatrixRequest.java) 的显式基准语义。OpenAPI描述与配对生成类型同步，无结构字段或路由新增。
- 前端复制面板的校验、写入及三类预览异步边界另记[前端FE-BUG-41/42](#frontend-review-record)，不把后端数据完整性与前端状态修复混成一个问题。

<a id="backend-review-排除与剩余范围"></a>

### 排除与剩余范围

首个图夹具使用203节点，超过现行DAG的200节点上限；虽然数据库可保存，其失败不作为正常业务缺陷证明。现使用实际DAG校验器通过的150节点/296边图。节点上限继续有效，不能据此限制边数或放宽节点准入。

新增原始队列/图测试先红，切换完整读取后旧Mock严格匹配非空分页的三项回归首轮失败；改为严格匹配空分页契约，业务断言不变。没有通过扩大页大小、取消公共上限或忽略失败解决问题。

源版本选择、源目标重合及请求元素由本批关闭；完整配置读取的目标容量/N+1、各配置类别的字段覆盖、目标环境字段保留、选项数据变化及旧复制入口持久未知结果恢复继续追查。真实浏览器跨账号联验、目标部署、所有SDK/脚本和全工程逐文件审查未完成。

<a id="backend-review-排除的候选与验证边界"></a>

### 排除的候选与验证边界

`appendCreatedVersionSnapshot`没有给临时请求设置nodes/edges，不等于快照丢图：实际消费者`WorkflowDefinitionWriteSupport.appendVersionSnapshot`在同一事务中重新读取节点/边并序列化。沿消费者取证后排除，未据此改代码。

配置身份阶段合跑140项，其中真实PostgreSQL61项；租户XML守卫、格式、432路由及配对类型漂移另列验证记录。现有物理历史行未被清理，普通列表的模糊搜索与历史版本接口保留原语义；本轮只改配置定位与当前版本读取。

<a id="backend-review-当前覆盖快照"></a>

### 当前覆盖快照

后端6,369项：157已审、59部分、6,152待审、1待复核；前端1,255项：95已审、30部分、1,130待审。新增Locale回归计入分母。并发改动的`BatchDayCutoffMultiTenantIntegrationTest`保留原证据与旧摘要，未替其他修改者销项。本批未提交、暂存或清理现有改动；共享索引内容不保证静止。

<a id="backend-review-配对作业包与向导续审"></a>

### 配对作业包与向导续审

前端FE-BUG-43至47继续沿作业包调用方核对异步反馈、关联选项、步骤校验和管理员权限入口，首红与最新227文件/1,417项等证据见[配对当日报告](#frontend-review-作业包与向导续审fe-bug-43至47)。后端本批只维护API/OpenAPI和运维说明；没有放宽ADMIN_ONLY或新增Java行为，因此不增加后端Bug计数或将旧140项重算为新运行结果。

最新前端清单1,256项：99已审、31部分、1,126待审；后端仍6,369项：157已审、59部分、6,152待审、1待复核。前端清单新增一份组件测试，权限store及路由库存测试实际全文核对，主路由与大组件仍部分。

<a id="backend-review-worker执行租约与心跳续审"></a>

### Worker执行、租约与心跳续审

两项新增问题分别定位于`TaskExecutionPool`的强停队列处理，以及`DefaultHeartbeatService`/`WorkerRuntimeState`的注册身份。排队Future的取消只针对shutdownNow实际移出的任务；`DefaultTaskExecutionWrapper`已有CancellationException路径负责返回取消结果，没有自动重跑业务或把运行中线程标为已退出。

下线与重新注册的回归使用真实线程、Latch和真实并发缓存，远程renew由可控替身阻塞；证明本地交错，未据此宣称真实Orchestrator网络、Pod强杀或全部停机组合已验证。Worker Core全模块370项通过，上游755项通过、5项环境条件跳过；完整命令及首红见当日验证记录。

续租HTTP明确404/409拒绝与其他错误传播已沿实现核对；原注释将所有4xx概括成不可认领并不符合代码，已修正说明，不计新增运行时Bug。注册缓存snapshot的消费者当前只读，没有仅因其返回视图而改集合语义。大执行包装器、完整HTTP客户端、抽象循环及共享关闭流程仍按实际片段留部分范围；五类Worker的全部业务执行、SDK与脚本继续审查。

<a id="backend-review-worker批次覆盖快照"></a>

#### Worker批次覆盖快照

后端6,369来源：177已审、61部分、6,127待审、4待复核；前端1,256来源：99已审、31部分、1,126待审。Worker核心新增23项全文审查完成，其他读取片段继续标部分。没有将Core370项测试通过自动记为该模块所有文件已审，原并发变化的日切测试仍待复核。本批未新增代码文件，未修改前端语义来源，未提交或暂存。

<a id="backend-review-收尾来源变化"></a>

#### 收尾来源变化

`TriggerAdmissionDrainTest`、`TriggerSchedulingOwnershipTest`及`ExternalEventApiKeyHttpIntegrationTest`在收尾核对时被并发修改，原已审内容摘要保留并转待复核，未覆盖改动；原日切测试待复核保留。最新后端177已审、61部分、6,127待审、4待复核，共6,369；前端99已审、31部分、1,126待审，共1,256。本轮Worker源码摘要未变，测试结果不外推到新Trigger内容。共享E2E变化只更新待审摘要。

<a id="backend-review-worker回报队列的执行身份与领取所有权"></a>

### Worker回报队列的执行身份与领取所有权

**BUG-20261027-11 / P1，已修复并验证。** 回报领取只携带行ID和载荷，确认删除及失败更新未绑定领取者。相同执行再次入队，或陈旧回收后由新轮询领取，旧HTTP响应仍可删除新报告、消耗新领取者重试预算或将其标为GIVE_UP。PostgreSQL和SQLite真实SQL均复现。现在每次领取生成claim_token，读取尝试次数、删除、重试、损坏载荷与耗尽放弃都要求ID、PUBLISHING及凭据一致；重新入队和退出领取时使旧凭据失效。写入返回行的MyBatis语句声明affectData/flushCache，确认属于事务写操作。放弃计量只在CAS实际生效后累加，同时覆盖耗尽路径，迟到失败不再产生虚假的重试/放弃告警。

**BUG-20261027-12 / P1，已修复并验证。** 原唯一键为租户/任务，旧执行的迟到回报会覆盖新执行的结果，反向顺序同样丢失一份已完成结果。现按租户/任务/partition_invocation_id去重；同执行仍更新一行，不同执行各自保存并独立确认。无执行身份统一以空串作为存储键，JSON载荷原有null不变。回报重投只提交结果，不重跑业务；过期执行仍由Orchestrator拒绝，不绕过服务端围栏。

新增V360仅用于ADR-048全新数据库、配套版本停旧启新。非空旧PG队列在任何结构修改前拒绝迁移并保留原数据；SQLite不自动升级、清空或搬运旧文件，校验不兼容结构后失败关闭。同版本文件重启保留待发结果。此前自动升级SQLite的草案已撤销，没有将兼容旧运行队列纳入实现。

核心实现为WorkerReportOutboxRepository、两个Mapper/XML及Coordinator，真实PG/SQLite参数化回归覆盖旧确认、陈旧回收、失败预算、损坏放弃、乱序执行、空身份去重及合法重试/放弃。同版本SQLite重开与两种旧存储拒绝另有正反例。Worker Core当前393项通过；上游755项通过、5项环境跳过；全新PG完整Flyway测试4项通过。首红、夹具错误和命令见当日验证记录。

载荷为合法JSON但业务身份缺失、极端重试配置溢出、BackendGuard完整切换组合及更大范围关闭组合尚未完成验证；前两项目前只列候选，不能计为已确认Bug。Repository和Coordinator本批在清单保留部分审查，不因定向回归而关闭全部输入与配置边界。平台迁移快照只更新实测发生变化的列、约束、索引三个摘要类别，未重生成种子或其他快照。

<a id="backend-review-bug-20261027-13--p2主机语言导致后端守卫误判"></a>

#### BUG-20261027-13 / P2：主机语言导致后端守卫误判

WorkerReportOutboxBackendGuard将存储枚举按默认语言转小写。土耳其语/阿塞拜疆语把SQLITE转为sqlıte，与原sqlite不相等；共享StatefulBackendGuard按已登记backend/identity精确比较，因此相同本地库在换语言重启后会要求不存在的后端切换。已改Locale.ROOT，保留原路径、功能键和切换策略。新增4项覆盖两语言及两存储，SQLite两项先失败、PG两项原本通过；原3项继续保留，定向7项和最终Worker Core397项通过。当前验证没有宣称真实多副本后端迁移已演练。

<a id="backend-review-本批范围盘点修正"></a>

#### 本批范围盘点修正

清单补入此前扩展名过滤漏掉的60份后端、8份前端自有文本来源，包括本批4份新源码/迁移，以及Spring服务注册、环境/HCL、schema摘要/种子、测试数据、包声明、Nginx模板、PWA清单和治理辅助资产。法律声明等辅助文本也显式列入，不隐含已审；二进制、依赖/构建/IDE产物、文档/Skill不计代码分母。当前后端6,429项：194已审、70部分审查、6,157待审、8待复核；前端1,264项的最新状态见配对报告。新增盘点不计审查完成，旧报告中分母为其当时快照。全工程目标仍未完成。

<a id="backend-review-下一批候选初查"></a>

#### 下一批候选初查

沿后端身份消费者初读StatefulBackendIdentity，发现对完整数据库位置、Redis Sentinel主名等统一小写，可能将大小写有业务含义的资源合并；尚未完成真实消费者及切换契约取证，不计为确认Bug。初读PipelineVerifierHook的默认语言转换，尚须核对内置及扩展适配器是否会传入合法小写类型；不能只凭toUpperCase无Locale就认定生产可绕过验证。这两份来源按实际阅读片段标部分，后续继续验证。

回报批次最终内容摘要核对又发现TenantConfigCopyTypeLocaleTest、TenantConfigInitCommitOutcomeIntegrationTest、TenantConfigInitOutcomeTest及TenantConfigBundleCompletenessIntegrationTest已被共享修改，保留旧摘要转待复核，与既有4份合计8份。没有覆盖这些测试或沿用配置批次旧通过结论；Outbox本批来源摘要未变。

<a id="backend-review-有状态后端与产物验收续审"></a>

### 有状态后端与产物验收续审

<a id="backend-review-bug-20261027-14--p2资源名折叠大小写绕过切换保护"></a>

#### BUG-20261027-14 / P2：资源名折叠大小写绕过切换保护

StatefulBackendIdentity原先把整个数据库地址、Sentinel主名、文件系统桶名和对象存储接入地址统一转小写。资源名仅大小写不同的两个目标因此产生相同身份；真实PostgreSQL登记后，四种无凭据切换都错误返回已验证。现在仅归一化协议与主机名，资源名称、路径和原始百分号编码保留；数据库认证段、查询及片段仍按原规则剥离。不同位置再次受既有一次性凭据和审计约束，失败拒绝保留原登记与唯一基线历史，不自动修改历史绑定。

单元四个反例先失败，真实PG四个拒绝反例也先失败，修复后均通过。此处验证的是定位序列化与持久守卫，不是四类远端存储的数据搬迁或容量演练。S3接入查询/片段的敏感值处理仍列进一步审查，不以本批宣称所有URI输入已完整关闭。

<a id="backend-review-bug-20261027-15--p1致命失败被可空错误码软化"></a>

#### BUG-20261027-15 / P1：致命失败被可空错误码软化

PipelineVerifierHook以firstFatalCode非空代替致命失败事实。合法失败元数据缺少错误码时，真实注册器仍返回失败，但Hook返回NO_FATAL；使用真实Hook/注册器的适配器回归实际返回成功。若后面还有一个带码的致命失败，首个失败信息也会被覆盖。

现在用独立boolean保留首个致命失败，其错误码和消息仍可空，由适配器原有VERIFIER_FATAL兜底处理。修复后适配器发出失败落库调用、返回失败且不发成功调用；这是单元级仓储替身验证，没有把该断言写成真实Pipeline数据库收敛。原有软告警、通过、未注册和未知类型反例继续保留。

<a id="backend-review-bug-20261027-16--p2redis生效配置未进入后端身份"></a>

#### BUG-20261027-16 / P2：Redis生效配置未进入后端身份

RuntimeInfrastructureInspector仅从Environment读取独立host/port/database和扁平Sentinel字符串，忽略URL优先级、索引列表、Cluster、MasterReplica及DataRedisConnectionDetails覆盖。客户端可连接新位置，守卫却继续拿旧属性或localhost身份比较；真实PG对五种配置来源变化均错误放行。

探针现使用与仓库当前Boot客户端相同的DataRedisConnectionDetails，依框架顺序选择Sentinel、Cluster、MasterReplica或Standalone；只读取定位，不读取用户名密码或建立连接。无有效定位时失败关闭。ComponentScan与AutoConfiguration回退均更新，Quota守卫直接消费该身份，原坐标DTO和重复属性解析移除。当前独立节点/Sentinel身份格式保留，新增两类拓扑有明确前缀；未增加环境变量或默认值，未改业务Redis连接、Quota算法或导入语义。

本地锁定Boot 4.1.1的真实配置绑定10项及真实PG守卫15项验证此边界；后者含五类来源变化、四类大小写变化和原六项。真实Redis配额6项、真实数据库配额12项及Quota守卫7项通过。没有验证真实Sentinel/Cluster/MasterReplica迁移、多副本切换或容量；JDBC自定义连接覆盖与手写客户端工厂仍待核对。

<a id="backend-review-bug-20261027-13扩展与排除的候选"></a>

#### BUG-20261027-13扩展与排除的候选

同一默认语言根因还影响对象存储FILESYSTEM和Quota REDIS两处后端标识。两组各7项中2个合法大写配置先抛不支持类型，S3/DATABASE及原分支为通过反例；两处仅改Locale.ROOT，沿用13号，不重复计Bug。

Pipeline类型转换的语言候选已排除：DictEnum.fromCode按equalsIgnoreCase匹配，土耳其语/阿塞拜疆语下小写import仍匹配并执行致命校验。两个真实注册器反例通过，未机械修改toUpperCase；已核对内置四类文件Worker的固定类型来源。后续继续审查注册器计量异常与失败返回的隔离、URI查询敏感值及JDBC覆盖，不把候选提前算修复。

<a id="backend-review-本批最新内容摘要快照"></a>

### 本批最新内容摘要快照

后端6,430来源：206已审、72部分、6,138待审、14待复核；前端1,265来源：102已审、38部分、1,125待审。本批各新增一份验证来源。ConfigSync应用服务、Calendar业务日解析器、TaskDispatchExecutor、ActiveTaskLeaseRegistry、WorkerRuntimeState、OutboxCoordinator六份共享生产来源又发生变化，保留旧摘要转待复核，与原八份测试合计14份；未替其他修改者销项。已核对WorkerRuntimeState仍按注册对象身份比较，Outbox的领取凭据和CAS仍在，其他片段继续审查。

上述分母是当前清单，不回写前段历史批次数字。整仓仍未完成逐文件Bug审核；Source盘点、配置/注入门禁和局部测试通过均不替代人工审查。当前专题继续保留URI查询敏感值、JDBC覆盖、计量异常及Nginx代理/日志候选。无提交、暂存、发布或主工作树修改。

<a id="backend-validation-record"></a>

<a id="backend-validation-后端-bug-续审验证2026-10-27"></a>

## 后端历史运行验证

> 以下保留各批次当时的发现和验证，不将历史进度或继续审查安排当作当前完成结论。

> **2026-10-27范围调整**：后续执行范围见[当前差异审查清单](../plans/todo-master.md#当前差异审查清单)。以下验证仍限于各自源码与环境快照；本次重新制定清单没有重跑编译或测试，也没有将旧结果升级为当前全工程通过。

本记录对应[当日确认问题](#backend-review-record)，延续原配对 `fix/wdi-current` 工作树。只使用测试容器、随机端口和自有场景数据，没有修改运行中的业务数据库或冻结工作树。

<a id="backend-validation-首个失败与根因"></a>

### 首个失败与根因

- `/private/tmp/wdi-config-bundle-completeness-red.log`：真实PostgreSQL的资源队列预期201条、实际200条。最初图夹具203节点也被截断，但超过当前节点准入上限，未将其计为正常业务证据；随后改为DAG校验器实际通过的150节点、296条边。
- `/private/tmp/wdi-config-bundle-completeness-green.log`：真实PG两个用例通过，旧复制Mock中的三个用例因仍匹配非空分页而失败。改为严格匹配空分页，保留依赖、日历、渠道与去凭据的原断言。
- `/private/tmp/wdi-config-matrix-baseline-red.log`：基准在展示范围外的两项新增用例失败，原列表内基准两项通过；旧代码实际返回空差异。

<a id="backend-validation-首批业务验证"></a>

### 首批业务验证

首批 **39项通过、0失败/错误/跳过**，其中 **22项真实PostgreSQL**：配置完整性2项、同步收据/认证HTTP7项、严格初始化与缓存事务13项。日志 `/private/tmp/wdi-tenant-copy-backend-final.log`。完整集合阶段另有17项通过，与39项重叠，不累计。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=TenantConfigBundleCompletenessIntegrationTest,DefaultConsoleTenantConfigCopyServiceTest,TenantConfigMatrixBuilderTest,DefaultConsoleConfigSyncBundleMappingTest,ConfigSyncAuditRecoveryIntegrationTest,TenantConfigBatchInitStrictAtomicityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

真实PG验证201条队列全部进入配置包和差异Overlay，合法图保留150节点及296条边。矩阵新增测试验证外部基准加载与缺失作业事实，输出不额外加入基准行。

前端定向17项及全量 **226文件/1,389项通过**，实际命令、首红、构建与边界见[配对报告](#frontend-review-record)。前端组件/API替身、后端真实数据库和认证HTTP分别取证，未将其称为真实浏览器完整旅程。

<a id="backend-validation-限制"></a>

### 限制

没有执行容量、生产网络故障或目标部署验收，没有修改迁移。全量集合的内存/延迟规模、其余配置类别全部真实数据矩阵、当批尚待审的源版本选择与源目标重合已由后续批次修复；旧入口未知结果恢复继续审查。全工程内容摘要台账只更新实际读过的范围；盘点和本地全量单测不是全工程语义审查。

<a id="backend-validation-补充门禁与来源边界"></a>

### 补充门禁与来源边界

- `./mvnw -ntp -pl batch-console-api -am spotless:check`通过（`/private/tmp/wdi-tenant-copy-spotless-final.log`）。
- `python3 scripts/ci/check-console-openapi-paths.py`通过，432条路由一致（`/private/tmp/wdi-tenant-copy-openapi-paths.log`）。
- 配对生成类型按当前OpenAPI描述重生成，漂移检查通过。前端最终226文件/1,389项、构建和受影响文件lint通过；维护预览的初始组件假设已按服务端POST冻结契约更正，首红与最终结果见配对报告，未修改后端维护白名单。
- 首批清单快照后端6,368（146已审、55部分、6,167待审）、前端1,255（95已审、28部分、1,132待审）。新来源和并发变化只盘点；实际只读过分页片段的Mapper、请求元素和源版本仍标部分。全工程未完成。

<a id="backend-validation-后续批次源目标保护集合元素类型语言与workflow身份"></a>

### 后续批次：源目标保护、集合元素、类型语言与Workflow身份

<a id="backend-validation-首红及根因"></a>

#### 首红及根因

- `/private/tmp/wdi-copy-self-target-red.log`：认证HTTP/PG及内部调用两项均失败。真实响应200，原渠道`target_endpoint`与配置标记均变为null，预期400且两项保留；这不是仅静态推断。修复后读取源包前拒绝重合，新增中英文错误解析用例通过。
- `/private/tmp/wdi-copy-request-elements-red.log`：23项中15失败、8通过，包括9项Bean元素校验及14项认证HTTP。旧HTTP有200/500，也有已在其他边界返回400的反例；未把原本已拒绝的分支计新缺陷。现为租户/作业元素增加NotBlank、类型元素增加NotNull，另补可选集合省略/为空仍合法的正例。
- `/private/tmp/wdi-copy-type-locale-red.log`：土耳其语及阿塞拜疆语两项均因合法小写类型抛出IllegalArgumentException而错误退出。使用Locale.ROOT后通过；测试使用资源锁并恢复默认Locale，没有改变宿主机配置。
- `/private/tmp/wdi-workflow-definition-identity-red.log`：真实PG三项先失败；UPsert改写相似编码，SKIP_EXISTING跳过应建编码，升版后创建返回200/两条定义，预期409/一条。
- `/private/tmp/wdi-workflow-current-definition-red.log`：补齐有效原图夹具并加入同码多版本导出后，四项均失败。修复精确编码查询、全版本去重及最高版本导出后通过；停用的最高版本不会回退到旧启用版本。

独立阶段51项（公共10＋Console41）、集合/语言阶段78项、Workflow阶段92项均按各自快照保留日志；与最终合跑重叠，不累计相加。

<a id="backend-validation-workflow身份阶段合跑"></a>

#### Workflow身份阶段合跑

**129项通过、0失败/错误/跳过**，为`batch-common`11项加`batch-console-api`118项；其中真实PostgreSQL55项（认证/收据23、严格事务15、完整配置包3、Workflow变更10、版本写契约4）。日志 `/private/tmp/wdi-copy-workflow-governance-final.log`。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=TenantConfigBatchInitStrictAtomicityIntegrationTest,ConfigSyncAuditRecoveryIntegrationTest,TenantConfigBundleCompletenessIntegrationTest,WorkflowDefinitionMutationValidationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultConsoleTenantConfigCopyServiceTest,WorkflowDefinitionWriteContractIntegrationTest,ConsoleWorkflowDefinitionMutationIntegrationTest,ConfigSyncTargetIdentityValidationTest,TenantConfigCopyTypeLocaleTest,DefaultConsoleConfigSyncBundleMappingTest,TenantConfigMatrixBuilderTest,BizMessageResolverTest,MessagesPropertiesParityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

原Mock改用精确编码/最高版本查询，保留正常创建、跳过、托管单作业目标和版本CAS断言；创建后精确版本读取夹具直接返回刚保存实体，避免旧的多次返回序列提前截断后续图/快照路径。未删测试、扩大白名单或改变普通列表模糊搜索。

<a id="backend-validation-门禁及配对边界"></a>

#### 门禁及配对边界

- `MapperXmlTenantGuardArchTest`通过（`/private/tmp/wdi-workflow-identity-tenant-guard.log`），实际SQL租户谓词与参数绑定纳入检查。
- 受影响reactor `spotless:check`通过（`/private/tmp/wdi-copy-identity-spotless.log`）。
- `python3 scripts/ci/check-console-openapi-paths.py`通过，432路由一致（`/private/tmp/wdi-copy-identity-openapi.log`）。
- OpenAPI说明更新后生成配对类型，`npm run gen:api:check`通过（`/private/tmp/wdi-copy-identity-api-drift.log`）。
- 前端新增三项先红、修复后20项及全量226文件/1,392项通过；构建、类型、i18n与受影响文件ESLint通过，详见配对FE-BUG-42记录。

本批未修改Flyway、没有自动清理已有同码物理行或历史快照。没有验证实际部署容量、完整浏览器旅程、所有配置字段及环境覆盖策略，全工程逐文件审查继续进行。

<a id="backend-validation-类型null反例的后续复核"></a>

#### 类型null反例的后续复核

补测复制、差异、同步导出和同步预览四个真实HTTP入口，未加同步DTO NotNull时均已返回400；日志是反序列化层的InvalidNull错误。`/private/tmp/wdi-sync-type-elements-red.log`共5项仅1项Bean声明预期失败，4项HTTP通过，不是新API缺陷。四个选择器补齐NotNull声明属于契约元数据一致性，不计新增Bug；保留真实HTTP反例，并更正了早期可能成功的静态推断。集合元素Bug仍由那些已复现的租户/作业200/500及写入行为证明。

<a id="backend-validation-作业包导入的整组目标校验"></a>

#### 作业包导入的整组目标校验

`/private/tmp/wdi-job-bundle-target-elements-red.log`：6项中5失败、1通过。两个真实认证HTTP反例在目标列表为“合法目标＋null/空白”时返回200，且查询确认第一个目标已写入资源队列；空串已被原Size约束拒绝，不能算新失败。三个应用层用例原本未抛参数异常。

作业包DTO补齐NotBlank，初始化应用服务在任何目标事务前检查整个列表；未扩大事务到全批，合法目标的业务失败隔离及未知提交结果向上传播保持原语义。`/private/tmp/wdi-job-bundle-target-elements-green.log`定向8项通过（真实HTTP/PG3项，应用层5项）；最终关联结果另列，不与早期计数累加。

<a id="backend-validation-配置身份阶段最终合跑与清单"></a>

### 配置身份阶段最终合跑与清单

`/private/tmp/wdi-config-identity-transaction-final.log`完整命令退出0：**140项通过，0失败、错误、跳过**（公共模块11项、Console129项）。其中真实PostgreSQL **61项**：认证/审计/请求28、严格事务15、Workflow变更10、版本写契约4、完整配置包3、未知提交1。这个合跑包含前述129/131项和定向8项，不将重叠批次相加。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=TenantConfigBatchInitStrictAtomicityIntegrationTest,ConfigSyncAuditRecoveryIntegrationTest,TenantConfigBundleCompletenessIntegrationTest,WorkflowDefinitionMutationValidationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultConsoleTenantConfigCopyServiceTest,WorkflowDefinitionWriteContractIntegrationTest,ConsoleWorkflowDefinitionMutationIntegrationTest,ConfigSyncTargetIdentityValidationTest,TenantConfigCopyTypeLocaleTest,DefaultConsoleConfigSyncBundleMappingTest,TenantConfigMatrixBuilderTest,BizMessageResolverTest,MessagesPropertiesParityTest,TenantConfigInitOutcomeTest,TenantConfigInitCommitOutcomeIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

当前清单后端6,369项（157已审、59部分、6,152待审、1待复核），前端1,255项（95已审、30部分、1,130待审）；以CSV内容摘要为准。只新增一份Locale测试；并发变化的日切测试保留旧摘要待复核。大服务、Mapper XML及作业包面板按已读取片段继续标部分，没有把测试通过当作全文审查。前端本批语义代码的全量单测仍为226文件/1,392项；本次后续仅更新OpenAPI生成说明。

<a id="backend-validation-收尾检查"></a>

#### 收尾检查

以下命令均观察到退出0，日志前缀为`/private/tmp/wdi-config-identity-`：

| 实际命令 | 结果/日志后缀 |
|---|---|
| `./mvnw -ntp -pl batch-console-api -am spotless:check` | 受影响reactor通过，`final-spotless.log` |
| `./mvnw -ntp -pl batch-console-api -am -Dtest=MapperXmlTenantGuardArchTest -Dsurefire.failIfNoSpecifiedTests=false test` | 2项通过，`final-tenant-guard.log` |
| `python3 scripts/ci/check-console-openapi-paths.py` | 432路由通过，`doc-routes.log` |
| `python3 scripts/ci/check-docs-structure.py` | 通过，`doc-structure-final.log` |
| `python3 scripts/ci/check-doc-timestamp-policy.py` | 通过，`doc-date.log` |
| `python3 scripts/ci/check-code-doc-references.py` | 通过，`doc-references.log` |
| `python3 scripts/ci/check-changelog-sync.py` | 通过，`doc-changelog-final.log` |
| 前端 `npm run typecheck` | 当前生成类型通过，`final-frontend-typecheck.log` |
| 前端 `npm run check:docs`、`npm run check:changelog` | 通过，`frontend-docs.log`、`frontend-changelog.log` |

作业包说明更新后再次执行前端`npm run gen:api`及`npm run gen:api:check`，两项退出0，日志为`/private/tmp/wdi-job-bundle-identity-api-gen.log`及`/private/tmp/wdi-job-bundle-identity-api-drift.log`。两仓`git diff --check`退出0；CSV行尾提示不代表运行缺陷，没有重写其他治理清单。全量后端业务测试、真实浏览器旅程和目标部署未在本批执行。

<a id="backend-validation-配对作业包向导继续验证"></a>

### 配对作业包/向导继续验证

后续FE-BUG-43..47仅改前端行为与后端接口说明，本批未重新执行Java测试；前一140项/真实PG61项保留原作用范围。前端最终227文件/1,417项、构建、定向lint及重新生成API类型漂移通过，独立首红和命令见[当日前端报告](#frontend-review-record)。全工程内容摘要清单以最新CSV和TODO为准，不沿用前段1,255项前端分母。

<a id="backend-validation-worker生命周期续审验证"></a>

### Worker生命周期续审验证

<a id="backend-validation-首红和定向恢复"></a>

#### 首红和定向恢复

- `./mvnw -ntp -pl batch-worker/core -am -Dtest=TaskExecutionPoolConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`：首红`/private/tmp/wdi-worker-pool-shutdown-red.log`共5项，新增2项失败、原3项通过。正常强停和调用线程已中断两条路径的排队Future都未取消；实际排队业务没有执行。修复后同命令5项通过（`/private/tmp/wdi-worker-pool-shutdown-green.log`）。
- `./mvnw -ntp -pl batch-worker/core -am -Dtest=DefaultHeartbeatServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：首红`/private/tmp/wdi-worker-heartbeat-late-red.log`9项中新增2项失败、原7项通过。真实线程让移除/替换发生在renew已发出而响应未返回时，旧put复活注册或覆盖新对象；修复后9项通过（`/private/tmp/wdi-worker-heartbeat-late-green.log`）。新注册字段相等但实例不同，不能用Data生成的equals替代所有权身份。
- 期间15类关联回归102项通过（`/private/tmp/wdi-worker-lifecycle-regression.log`），该快照早于心跳修复；与最终完整模块重叠，不加总。

<a id="backend-validation-完整受影响模块"></a>

#### 完整受影响模块

```sh
./mvnw -ntp -pl batch-worker/core -am test
```

命令退出0，日志`/private/tmp/wdi-worker-core-all-final.log`：**Worker Core 47份报告/370项全部通过，无失败、错误、跳过**，包含真实PG的Report Outbox 3项。上游`batch-common`692项中690通过、2跳过；`batch-test-support`68项中65通过、3跳过。整个reactor记录1,130项，其中**1,125通过、5跳过，零失败/错误**。没有将其他模块旧XML报告混入本次统计，模块聚合与命令日志一致。

5项均为已有条件化活体验证：2项缺少`BIZ_SHARD_0_URL`，3项缺少`S3_COMPAT_POC_ENDPOINT`。本轮没有设置环境变量绕过条件、删除测试或放宽断言；多分片路由及外部S3供应商活体契约仍未验证。

后续只修正claim/renew接口和实现中的注释：明确404/409才是业务拒绝，其他HTTP错误传播；修正非幂等起点方法的复制错误说明。行为未变，当前`./mvnw -ntp -pl batch-worker/core -am spotless:check`退出0（`/private/tmp/wdi-worker-lifecycle-spotless-final.log`）。没有执行全部五类Worker、全后端业务测试、真实网络故障/Pod滚动或目标环境验收；不可中断业务线程仍沿原看门狗与运维处理。

<a id="backend-validation-worker批次清单与剩余范围"></a>

#### Worker批次清单与剩余范围

当前后端6,369来源：177已审、61部分、6,127待审、4待复核；前端1,256来源：99已审、31部分、1,126待审。清单逐项使用当前文件摘要；未读完的HTTP客户端、执行包装器、抽象循环和跨组件关闭组合继续标部分。未执行另外四类Worker的全模块测试，也未执行完整前端测试：本批前端源码摘要未变，沿用前批227文件/1,417项限定快照。全工程目标继续进行。

<a id="backend-validation-收尾来源变化"></a>

#### 收尾来源变化

`TriggerAdmissionDrainTest`、`TriggerSchedulingOwnershipTest`及`ExternalEventApiKeyHttpIntegrationTest`在收尾核对时被并发修改，原已审内容摘要保留并转待复核，未覆盖改动；原日切测试待复核保留。最新后端177已审、61部分、6,127待审、4待复核，共6,369；前端99已审、31部分、1,126待审，共1,256。本轮Worker源码摘要未变，测试结果不外推到新Trigger内容。共享E2E变化只更新待审摘要。

<a id="backend-validation-worker批次文档检查"></a>

#### Worker批次文档检查

`python3 scripts/ci/check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`全部退出0；日志前缀`/private/tmp/wdi-worker-lifecycle-doc-`分别接`structure/timestamp/references/changelog.log`。配对前端`npm run check:docs`退出0，日志`/private/tmp/wdi-worker-lifecycle-frontend-docs.log`。当前`git diff --check`退出0。最初收尾脚本在发现已审来源漂移时主动中止，未据旧状态销项；随后显式转待复核并更新上述清单。

<a id="backend-validation-回报队列的领取cas与执行身份验证"></a>

### 回报队列的领取CAS与执行身份验证

<a id="backend-validation-首红与夹具问题分别保留"></a>

#### 首红与夹具问题分别保留

| 日志（均位于`/private/tmp/`） | 实际结果与归因 |
|---|---|
| `wdi-report-outbox-ownership-red.log` | 初始11项/8失败，其中4个陈旧回收失败含时间夹具错误，不能直接作为有效首红 |
| `wdi-report-outbox-ownership-valid-red.log` | 时间改为实际领取时刻后，11项中8项失败、3通过，证实两存储的迟到确认/失败/放弃覆盖 |
| `wdi-report-outbox-invocation-identity-red.log` | 15项中2失败，PG及SQLite不同执行互相覆盖，其他13项通过 |
| `wdi-report-outbox-stale-metric-red.log` | SimpleMeterRegistry不支持try-with-resources导致测试编译失败，仅夹具错误 |
| `wdi-report-outbox-giveup-metrics-valid-red.log` | 5项中2失败，失效领取的损坏指标误增、合法重试耗尽指标漏增 |
| `wdi-report-outbox-nonempty-cutover-red.log` | 非空旧PG队列拒绝的单项失败；为新迁移补上ADR-048切换前置守卫，不作为新增运行时Bug编号 |
| `wdi-report-outbox-core-all-final.log` | 22个PG/SQLite夹具错误，Spring SQL分隔器无法执行DO块；改用真实Flyway及隔离测试资源目录，未删迁移或跳过负例 |
| `wdi-report-outbox-guarded-focused-final.log` | 最终定向27项通过：Repository22（PG12、SQLite10）＋Coordinator5，无失败/错误/跳过 |
| `wdi-report-outbox-fresh-schema-red.log` | 全新数据库迁移成功，结构摘要3类别与旧预期不同；核对本次列/唯一键DDL后仅更新这3行，保留首红 |

<a id="backend-validation-最终受影响模块及全新数据库"></a>

#### 最终受影响模块及全新数据库

以下顺序执行且均观察到退出0，不与早期定向或生命周期批次重复加总：

```sh
./mvnw -ntp -pl batch-worker/core -am test
./mvnw -ntp -pl batch-orchestrator -am \
  -Dtest=LocalFlywayPlatformMigrationsIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

日志分别为`/private/tmp/wdi-report-outbox-core-all-verified.log`和`/private/tmp/wdi-report-outbox-fresh-migrations-verified.log`。当前Worker Core **393项全部通过**；上游common692项中690通过/2跳过，test-support68项中65通过/3跳过。reactor共1,153项，**1,148通过、5跳过**，零失败/错误。跳过仍为已有多业务分片2项及外部S3活体3项，未伪造环境或取消条件。

平台真实PostgreSQL完整Flyway迁移测试 **4项全部通过**，执行当前全链到V360，并核对结构/种子快照。仅更新fresh-platform-schema-manifest.sha256的COLUMN（4,958→4,959）、CONSTRAINT和INDEX三个实测摘要；未使用批量重写快照开关。Worker测试资源只复制V96/V360到独立fixture目录，通过Flyway执行，避免测试目录被当作完整迁移链自动扫描。

V360非空旧队列负例、SQLite旧结构拒绝及原载荷保留、同版本文件重开均由定向测试验证；本轮没有改共享数据库或尝试旧库升级。没有验证真实网络/多Pod故障、全五类Worker、容量或目标部署；配对前端无本批语义修改，旧227文件/1,417项结果只保留原快照。

<a id="backend-validation-后端身份语言复核后的最终结果"></a>

#### 后端身份语言复核后的最终结果

`./mvnw -ntp -pl batch-worker/core -am -Dtest=WorkerReportOutboxBackendGuardTest -Dsurefire.failIfNoSpecifiedTests=false test`首红日志为`/private/tmp/wdi-report-outbox-backend-locale-red.log`：7项中SQLite两语言2失败、5通过，实际得到sqlıte而非sqlite。Locale.ROOT修复后同命令7项通过，日志`wdi-report-outbox-backend-locale-green.log`。

再次执行`./mvnw -ntp -pl batch-worker/core -am test`，日志`/private/tmp/wdi-report-outbox-core-locale-verified.log`，退出0：**Worker Core397项全部通过**，上游仍755通过/5环境跳过，总1,157项中**1,152通过、5跳过**，零失败/错误。此为当前完整模块证据，覆盖前一393项，不累计相加；此前全新迁移4项仍对应相同V360与schema摘要。当前`./mvnw -ntp -pl batch-worker/core -am spotless:check`退出0，日志`wdi-report-outbox-last-spotless.log`。

补齐漏盘文本来源后分母后端6,429、前端1,264，不将新的待审资产算完成。当前状态及源码摘要以两仓CSV和TODO为准；当前PG/SQLite测试不替代全工程逐文件审查。

<a id="backend-validation-回报批次收尾门禁"></a>

#### 回报批次收尾门禁

以下检查均观察到退出0：后端`python3 scripts/ci/check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`（日志前缀`/private/tmp/wdi-outbox-docs-`，后缀分别为`structure/timestamp/references/changelog-final.log`）；`check-schema-governance-assets.py`通过。仓库锁定Squawk对V360单文件检查0问题（`/private/tmp/wdi-outbox-final-migration.log`）。前端`npm run check:docs`检查75份现行文档通过（`/private/tmp/wdi-outbox-frontend-docs-final.log`）。两仓`git diff --check`退出0；配对旧治理CSV有CRLF提示，未重写该文件。

测试后只修正PG/SQLite测试中的过期缓存说明注释，不改变测试体；继续核对全文后该测试来源标为已审。最新后端6,429项：194已审、70部分审查、6,157待审、8待复核；前端1,264项：101已审、35部分审查、1,128待审。已审/部分来源摘要均与当前文件一致；8份并发变动测试继续保留旧摘要待复核。本批全部写入既有配对wdi-current工作树，没有提交、暂存或修改main工作树。

<a id="backend-validation-后端定位与致命验收结果验证"></a>

### 后端定位与致命验收结果验证

<a id="backend-validation-独立首红与合法反例"></a>

#### 独立首红与合法反例

| 日志（`/private/tmp/`） | 实际结果 |
|---|---|
| `wdi-backend-resource-identity-red.log` | 身份单元6项中4失败/2通过；Maven快速失败导致下游未运行，未算真实PG证据 |
| `wdi-backend-resource-guard-red.log` | 单独执行真实PG守卫10项，新增4个大小写切换均未拒绝而失败，原6项通过 |
| `wdi-object-backend-locale-red.log`、`wdi-quota-backend-locale-red.log` | 两组各7项，2个配置解析错误、其余5项通过；错误是实际不支持fılesystem/redıs，不是环境问题 |
| `wdi-pipeline-fatal-null-code-red.log` | Hook/真实注册器9项中2失败，分别为致命事实丢失、首个失败信息被覆盖 |
| `wdi-pipeline-fatal-result-red.log` | 适配器5项中1失败，实际success=true；修复后验证失败调用、无成功调用及原兜底码 |
| `wdi-redis-effective-identity-red.log` | 真实Boot绑定10项中8失败，默认与扁平Sentinel两项为通过反例；临时入口等价委托原坐标读取，未把缺符号编译错误当首红 |
| `wdi-redis-effective-guard-red.log` | 真实PG15项中新增五类配置变化均未被拒绝，其他10项通过 |

原坐标DTO及重复解析在最终实现中移除，框架连接信息供探针与客户端共用。绑定测试禁用真实Redis客户端类加载，只验证框架属性、优先级、覆盖和凭据不进入身份；真实Redis业务结果另由集成类验证。

<a id="backend-validation-最终运行结果"></a>

#### 最终运行结果

```sh
./mvnw -ntp -pl batch-worker/core -am test
```

`/private/tmp/wdi-identity-core-current-final.log`退出0：**Worker Core402项全部通过**；common711项中709通过/2跳过；test-support77项中74通过/3跳过。共1,190项，**1,185通过、5环境跳过**，零失败/错误。5项仍为多业务分片2项及外部S3供应商活体3项；未删除或绕过条件。此为本批最新完整模块结果，覆盖前一400项及旧397/393项，不能重复加总。

```sh
./mvnw -ntp -pl batch-orchestrator -am \
  -Dtest=QuotaRuntimeBackendGuardTest,RedisQuotaRuntimeStateIntegrationTest,QuotaRuntimeStateIntegrationTest,StatefulBackendIdentityTest,StatefulBackendGuardIntegrationTest,RuntimeInfrastructureInspectorTest,ObjectStorageBackendGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`/private/tmp/wdi-identity-quota-runtime-final.log`退出0：common24、test-support15、Orchestrator25项全部通过；Orchestrator为守卫7＋真实PG配额12＋真实Redis配额6。上游39项与完整模块重叠，未累计为额外通过数。之后AutoConfiguration回退与Component路径定向39项通过（`wdi-identity-auto-wiring-final.log`），最终完整模块包含更新后的配置和夹具。

<a id="backend-validation-共享来源与格式首错"></a>

#### 共享来源与格式首错

`/private/tmp/wdi-identity-spotless-final.log`首次失败于共享修改后的WorkerReportOutboxRepository：null判定已换为EmptyChecks.isNull，但三元表达式排版不符合格式器。保存当时文件到`/private/tmp/wdi-shared-outbox-repository-format-before.java`，仅应用排版，保留该判定；已读isNull实现等价于引用==null。Outbox定向34项随后通过（`wdi-identity-outbox-shared-recheck.log`），最终完整模块也包含这些用例。

当前`./mvnw -ntp -pl batch-worker/core,batch-orchestrator -am spotless:check`退出0（`wdi-identity-spotless-current-final.log`）。没有扩大格式目标到无关来源或改白名单。新增PG测试SQL使用独立命名文本块，回读结果先承接再断言；fixture命名空间由本类共享，契约预期独立保留。

<a id="backend-validation-证据限制与后续"></a>

#### 证据限制与后续

本批未修改Flyway、API或SDK协议，也未执行全部Orchestrator/Console/五类Worker、真实Sentinel/Cluster迁移、容量和目标部署。前轮全新Flyway4项是相同迁移来源的独立证据；本批不能代替发布验收。URI查询敏感值、JDBC覆盖与注册器计量故障继续列候选。配对Nginx缺失静态制品的真实HTTP回归见当日前端记录，鉴权替身不能证明真实认证。

<a id="backend-validation-定位批次清单与来源变化"></a>

#### 定位批次清单与来源变化

当前后端6,430项（206已审、72部分、6,138待审、14待复核）、前端1,265项（102已审、38部分、1,125待审）。新增的两份验证代码进入原清单。六份共享生产来源在本批核对时发生内容变化，转待复核且保留原摘要；不能用本批测试结论为这些新内容整体销项。已读到的Worker注册身份与Outbox CAS关键条件仍保留，后续全文复核继续进行。

配置治理、默认值同步、Java注入与测试配置来源四项实际命令退出0，日志依次为`/private/tmp/wdi-identity-config-governance.log`、`wdi-identity-config-defaults.log`、`wdi-identity-injection.log`、`wdi-identity-test-config-sources.log`。默认值同步中的8项网络拓扑差异和既有1项实例命名白名单按原设计报告，未修改白名单。它们是静态门禁，不是全工程语义审查。

<a id="frontend-review-record"></a>

<a id="frontend-review-前端-bug-续审租户复制上下文2026-10-27"></a>

## 前端问题与历史运行验证

> 以下保留各批次当时的发现和验证，不将历史进度或继续审查安排当作当前完成结论。

> **2026-10-27范围调整**：原全工程逐文件执行方式已停止，改按[当前差异审查清单](../plans/todo-master.md#当前差异审查清单)核对已有修复及直接影响。本文保留历史批次及其证据，原继续审查安排不再是当前执行指令。代码审查仍暂停，本次只调整清单。

<a id="frontend-review-范围与基线"></a>

### 范围与基线

继续[原前后端逐文件任务](../plans/todo-master.md#前后端全工程逐文件-bug-续审2026-10-26)。现有 `wdi-current/batch-console`、`fix/wdi-current`，HEAD `662d254e2f8b97f93021de548815470d2291c997`；后端配对HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`。已有改动保留，未提交或发布。[26日原始证据](../../../batch-console/docs/reports/2026-10-26-frontend-bug-remediation.md)继续保留。

<a id="frontend-review-fe-bug-41--p2复制操作与预览异步失效"></a>

### FE-BUG-41 / P2：复制操作与预览异步失效

[复制面板](../../../batch-console/src/views/system/components/TenantCopyConfigDialog.vue)原先在校验完成后才设置忙碌状态，重复点击能排出多笔写请求；关闭、编辑、账号/权限或维护变化后，旧校验仍继续复制。旧复制响应可关闭重开的面板，三类预览也能恢复旧输入的结果并覆盖最新读取。

修复绑定面板、输入、主体和读取代次，写操作额外绑定写权限代次。校验前即占用本次操作；输入、账号或面板变化立即清空旧预览，旧读取收尾不能解除新请求的忙碌状态。发送使用独立目标与类型数组，迟到写结果不作用于新面板。这些预览端点虽为业务只读，但均使用POST，因此与正式复制/试运行一起按服务端HTTP方法策略冻结。已发送业务不因关闭面板撤销。

<a id="frontend-review-证据"></a>

### 证据

- 首红 `/private/tmp/wdi-tenant-copy-scope-red.log`：14项中13失败、1正常复制正例通过。
- 修复后17项通过（`/private/tmp/wdi-tenant-copy-scope-green2.log`），包括旧读取不解除新忙碌及矩阵无作业不发送的反例；维护边界在后续服务端复核中修正，见下。
- `npm run test:unit` 全量 **226文件/1,389项通过**（`/private/tmp/wdi-tenant-copy-all-unit-final.log`）；较早1,386项快照不代替补充反例后的结果。
- 类型、受影响文件ESLint和生产构建按实际命令执行，日志 `/private/tmp/wdi-tenant-copy-typecheck.log`、`/private/tmp/wdi-tenant-copy-lint-final.log`、`/private/tmp/wdi-tenant-copy-build.log`。后续协议说明及生成类型同步另保留最终构建/漂移结果。

后端配置包完整集合读取和外部矩阵基准为两项独立修复，见[后端当日报告](#backend-review-record)和[真实数据库验证](#backend-validation-record)。没有把组件替身称为真实浏览器跨账号联验。

<a id="frontend-review-未覆盖与下一步"></a>

### 未覆盖与下一步

旧复制入口没有同步导入的持久原键与目标收据恢复能力；未知结果仍需人工核对。源目标重合在下段FE-BUG-42处理，后端源版本选择另见当日续审；选项数据变化、配置字段和环境覆盖等继续追查。组件标记部分审查，全工程逐文件任务保持进行，不将本轮定向/全量单测通过作为全部人工审核完成。

<a id="frontend-review-fe-bug-41维护契约复核与当批证据"></a>

#### FE-BUG-41维护契约复核与当批证据

初版组件用例认为业务只读预览可在维护期间发送，但没有核对服务端。随后通读 `MaintenanceModeFilter`，确认其按HTTP方法统一冻结POST，三个预览并无旁路；组件替身的放行不能证明真实系统可用。修正预期后先红1项/原16项通过（`/private/tmp/wdi-tenant-copy-maintenance-red.log`），再让前端遵循服务端冻结契约，未改变过滤器或扩大维护白名单。

最终 `npm run test:unit` **226文件/1,389项通过，无失败/跳过**（`/private/tmp/wdi-tenant-copy-all-unit-verified.log`）；`npm run build`及受影响文件ESLint通过（`/private/tmp/wdi-tenant-copy-build-verified.log`、`/private/tmp/wdi-tenant-copy-lint-verified.log`）。OpenAPI新增外部基准描述后重新生成类型，`npm run gen:api:check`通过（`/private/tmp/wdi-config-matrix-api-drift.log`），后端432条路由核对通过。纯契约描述变更没有增加接口结构或权限。

<a id="frontend-review-fe-bug-42--p2更换源后保留重合目标"></a>

### FE-BUG-42 / P2：更换源后保留重合目标

选项列表虽过滤源租户，原表单目标值并未随源变化移除重合项；再次点击复制可把处理后的包回写源。现更换源时只移除重合项，保留其他目标，提交前再复核；不会通过更改已有在途请求的目标数组实现保护。本项是同一风险链的前端守卫，后端实际数据破坏和读写前拒绝见BUG-20261027-03，不重复称为两次生产事故。

首红 `/private/tmp/wdi-copy-source-target-ui-red.log`：新增3项失败、原17项通过；修复后20项通过（`/private/tmp/wdi-copy-source-target-ui-green.log`），覆盖目标移除、异常重合状态拒绝和其余目标保留。

最终 `npm run test:unit` **226文件/1,392项通过、0失败/跳过**（`/private/tmp/wdi-copy-source-target-front-all.log`），`npm run build`及受影响组件/测试/语言包ESLint通过（`/private/tmp/wdi-copy-identity-front-build.log`、`/private/tmp/wdi-copy-identity-front-lint.log`）；构建含类型与i18n检查。OpenAPI说明重新生成类型后漂移检查通过。

当前用户说明、API协议和运行手册已同步。后端最终关联140项、真实PG61项为独立层级证据；未进行真实浏览器跨账号旅程或目标部署验证。旧复制未知结果持久恢复、目标环境字段保留与完整字段覆盖仍待继续审查。

<a id="frontend-review-fe-bug-42阶段清单与契约说明收尾"></a>

### FE-BUG-42阶段清单与契约说明收尾

当批1,255个来源：95已审、30部分、1,130待审。作业包列表与向导只初读提交/目标/结果片段，异步上下文及完整映射仍待验证，不因接口单测通过升级为全文已审。

后端新增作业包整组目标校验说明后重新执行`npm run gen:api`与`npm run gen:api:check`均退出0（日志`/private/tmp/wdi-job-bundle-identity-api-gen.log`、`/private/tmp/wdi-job-bundle-identity-api-drift.log`）；`npm run typecheck`退出0（`/private/tmp/wdi-config-identity-final-frontend-typecheck.log`）。后续只更新生成注释，未改变组件语义，沿用上段1,392项当前语义快照，不重复计数。

<a id="frontend-review-作业包与向导续审fe-bug-43至47"></a>

### 作业包与向导续审：FE-BUG-43至47

继续同一`fix/wdi-current`配对工作树，本批后端只同步接口和运维说明，未变更Java或数据库行为。原140项后端结果只属于上一批源码快照；本批前端单测与构建单独取证。

| ID | 触发与旧行为 | 修复范围 |
|---|---|---|
| FE-BUG-43 / P2 | 作业包导入发出后切租户、账号、权限/维护代次、改输入或关闭重开，旧成功仍提示、关闭新面板并刷新新上下文；完成时还读取当前试运行开关 | 绑定输入/面板修订与租户生命周期，反馈只作用于发起范围；请求及结果提示使用发送时模式 |
| FE-BUG-44 / P2 | 打包新建等待时修改作业编码，旧成功用新编码导航；切换上下文或卸载后仍提示/导航，处理器缺少在途重复守卫 | 固定提交编码，绑定输入/步骤/主体/写权限修订与租户生命周期；旧反馈失效，在途提交零重复发送 |
| FE-BUG-45 / P2 | 元数据或关联渠道的旧租户响应覆盖当前队列/选项，切租户后旧选择保留到新读取完成 | 整个元数据→关联读取共享范围代次；切租户/操作者同步清空旧选项和选择，旧请求不能续发读取、覆盖结果或解除新读取忙碌 |
| FE-BUG-46 / P2 | 异步步骤校验期间多次点击叠加推进；旧租户/操作者的校验继续推进当前步骤 | 校验期间锁定一次推进，校验完成核对输入/上下文及原步骤；输入变化需重新校验 |
| FE-BUG-47 / P2 | 前端允许租户管理员进入打包向导，普通读取角色也看到可用导出动作；服务端控制器当前为ADMIN_ONLY | 向导路由、写入及导出动作对齐已有ROLE_ADMIN契约；普通单作业创建仍按原配置写权限提供，不放宽服务端授权 |

<a id="frontend-review-首红与回归"></a>

#### 首红与回归

- `/private/tmp/wdi-job-bundle-context-red.log`：16项中14失败、2正常路径通过，真实组件配合可控Promise复现旧响应和重复提交，旧导航实际携带JOB_B而提交的是JOB_A。
- `/private/tmp/wdi-job-wizard-associations-red.log`：新增3项失败、原16通过，旧渠道/队列覆盖和旧选择残留均由组件可观察结果证明。
- `/private/tmp/wdi-job-wizard-step-red.log`：新增3项失败、原19通过，重复点击实际叠加推进两格，旧上下文校验仍推进一格。
- `/private/tmp/wdi-job-bundle-permission-red.log`：组件入口与路由各1项失败，原25项通过。导出单项首红`/private/tmp/wdi-job-bundle-export-permission-red.log`按名称只执行新增1项并失败，其余23项被过滤，不算通过或删除测试。
- `/private/tmp/wdi-job-bundle-permission-green.log`：最终定向2文件/28项通过、无跳过（作业包组件24项、路由库存4项）。权限依据服务端`ConsoleJobBundleController`的现有ADMIN_ONLY与前端路由消费者，不把standalone MockMvc当作真实方法授权验证。

当前用户指南、角色矩阵、根Changelog、API协议与运维手册同步。前端的失效判断不会取消已发送的服务端业务；响应不确定仍需核对原目标，旧复制/作业包入口尚未具有配置同步的完整持久原键/收据恢复。关联类型完整字段映射、容量与真实浏览器/跨账号/目标环境验证继续开放。

<a id="frontend-review-本批最终验证与覆盖"></a>

#### 本批最终验证与覆盖

- `npm run test:unit`：**227文件/1,417项通过，无失败/跳过**，日志`/private/tmp/wdi-job-bundle-context-all-final.log`。前一1,414项快照在补齐角色/导出反例之前，不作为最终计数；各阶段用例重叠，不累加。
- `npm run build`：类型、i18n及生产/PWA构建通过，日志`/private/tmp/wdi-job-bundle-context-build-final.log`。
- `npx eslint src/views/job/JobDefinitionList.vue src/views/job/JobDefinitionWizard.vue src/views/job/JobBundleContextRisks.test.ts src/router/index.ts src/router/routeInventory.test.ts`退出0，日志`/private/tmp/wdi-job-bundle-context-lint-all-final.log`。
- 更新OpenAPI说明后执行`npm run gen:api`及`npm run gen:api:check`均退出0，日志`/private/tmp/wdi-job-bundle-context-api-gen.log`、`/private/tmp/wdi-job-bundle-context-api-drift.log`。

最新清单**1,256来源：99已审、31部分、1,126待审**。新增一份24项组件测试；路由库存测试、权限store及其测试完成全文核对。大列表、向导和路由主文件仍按实际片段标部分。全工程逐文件审查保持进行，当前前端单测不等同于真实后端、浏览器或全部文件审查。

<a id="frontend-review-文档与契约收尾"></a>

#### 文档与契约收尾

`npm run check:docs`及`npm run check:changelog`均退出0，日志`/private/tmp/wdi-job-bundle-context-docs.log`、`/private/tmp/wdi-job-bundle-context-changelog.log`。配对后端实际执行`python3 scripts/ci/check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`与`check-console-openapi-paths.py`，全部退出0；日志前缀`/private/tmp/wdi-job-bundle-context-be-doc-`分别接`structure/date/references/changelog/routes.log`。路由一致性为432条。两仓源码摘要复核均无漂移，已有待复核项仍保留；两仓`git diff --check`退出0。当前无新增编译或运行失败，不将尚未执行的浏览器/真实账号边界、全部后端测试及剩余逐文件审查记为通过。

<a id="frontend-review-部署文本与清单补漏续审"></a>

### 部署文本与清单补漏续审

补入此前扩展名过滤遗漏的8份文本资产：Nginx模板、E2E声明、PWA清单、示例数据及辅助治理文本。当前1,264项：101已审、35部分审查、1128待审；新增盘点不计语义审查。声明已逐项对照运行配置导出，PWA入口及7个图标引用核对；Nginx模板和片段、E2E配置仍按实际读取范围标部分。

当前没有新增已确认前端Bug或语义修改，未重跑组件测试/构建；原227文件/1,417项及构建仅保留原来源快照。静态资源不存在时的Nginx全局404回退列为待运行验证候选，不能仅凭模板推断计为已确认Bug；真实代理、长连接、浏览器PWA安装/离线及目标部署仍未验。后端当前回报队列批次见[配对验证](#backend-validation-回报队列的领取cas与执行身份验证)。

<a id="frontend-review-静态制品错误状态续审"></a>

### 静态制品错误状态续审

<a id="frontend-review-fe-bug-48--p2缺失静态制品错误返回成功入口"></a>

#### FE-BUG-48 / P2：缺失静态制品错误返回成功入口

真实Nginx复验确认，缺失JS、字体、图片和version.json先被各location判404，却被server级error_page重定向为200的SPA HTML；icons/manifest则因通用history回退返回同样的错误内容。脚本/版本读取因此得到内容类型和内容不符的成功响应，不能据HTTP200判断制品可用。

删除全局404转入口，只由普通前端路径承担history回退。icons前缀和manifest精确路径独立检查存在性，固定文件名仍使用no-cache；已存在资源内容、hash资源缓存、相对/docs跳转和API转发保留。对新指令显式包含原安全头片段，未更改登录、权限或后端契约。Dockerfile和模板注释同步实际BACKEND_UPSTREAM_HOST变量名，镜像版本和构建指令未改。

<a id="frontend-review-首红最终回归及排除的推断"></a>

#### 首红、最终回归及排除的推断

- `/private/tmp/wdi-nginx-routing-red.log`最初9场景中2失败，实际/assets缺失JS和缺失version.json均200/SPA HTML。
- 扩展后的`wdi-nginx-routing-extended-red.log`及仓库回归`wdi-nginx-routing-contract-red.log`覆盖15个HTTP子场景，6失败/9通过。父测试随子失败，不能把父层再计一个业务缺陷。
- `npm run test:nginx-routing`最终退出0，`/private/tmp/wdi-nginx-routing-contract-final.log`记录15个HTTP子场景全通过；Node连父层共16条，无失败/取消/跳过。使用Dockerfile声明的不可变Nginx 1.30.5/Alpine镜像`sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94`，实际加载仓库nginx.conf、模板和片段，启动后另执行nginx -t。
- 已登录文档缺页原本就是404，API404也原本正确；初始关于文档会落入SPA的静态推断已由运行反例排除，没有算作新修复。相对/docs的301、正常文档/脚本/图标/清单及普通前端路径作为正例保留。

测试脚本只生成独占临时制品和鉴权204替身，发布随机回环端口，清理先核对本例容器标签。缺失清单仅删除生成的临时文件；未写共享应用环境或删除生产制品。它验证真实Nginx HTTP路由，不证明真实认证、根用户限制、完整应用镜像、PWA安装、浏览器交互或目标部署。本批没有新增Vue/TS行为修改，未重跑组件全集或生产bundle；旧227文件/1,417项保留原快照，不重算为本批结果。

<a id="frontend-review-剩余范围"></a>

#### 剩余范围

Nginx上游Host/转发端口与OIDC、访问日志中的查询数据、SSE/上传/完整代理链仍待追查；模板及相关配置继续按实际范围标部分。新增HTTP回归来源计入原逐文件清单，不把某一服务器回归当全前端审核完成。配对后端定位与产物致命失败的证据见[后端当日记录](#backend-validation-后端定位与致命验收结果验证)。

<a id="frontend-review-静态制品批次清单与轻量检查"></a>

#### 静态制品批次清单与轻量检查

最新前端1,265来源：102已审、38部分、1,125待审；新增一份Node/Docker HTTP回归，模板、核心配置、构建与代理相关入口仍按实际阅读范围标部分。后端清单6,430来源中14份共享变动待复核，不将旧证据套到新内容。

`npx eslint scripts/tests/nginx-routing.test.mjs`、`npx prettier --check scripts/tests/nginx-routing.test.mjs`、`npm run check:env`、`npm run check:test-fixture-sources`均退出0；日志前缀为`/private/tmp/wdi-nginx-routing-`，分别为`eslint.log`、`prettier.log`、`env.log`和`fixture-sources.log`。未新增默认CI/发布阻断策略，专用回归由独立npm命令运行。

## 2026-11-01：短断言历史候选定位索引

以下仅保存 2026-10-30 筛选快照的文件和测试名称，便于逐项定位；名称和测试体可能已变化或合并。此表**不是逐项审核结论**，已核查与已整改结果以上文具体记录为准。路径分别相对后端仓库或配对前端仓库。

### file-batch-system

| 测试文件 | 快照中的候选测试 |
|---|---|
| `batch-common/src/test/java/io/github/pinpols/batch/common/config/HikariPgSessionSupportTest.java` | `shouldTruncateApplicationName_whenExceedingSixtyThreeChars` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/constants/BatchFileConstantsPartitionTagTest.java` | `shouldInsertTagBeforeExtension`、`shouldAppendTag_whenNoExtension`、`shouldReturnUnchanged_whenSinglePartition` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/CatchUpPolicyTypeTest.java` | `fromCodeOrDefault_unknownCode_returnsNone` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/ConfigLifecycleStatusTest.java` | `shouldContainFiveValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/DeadLetterReplayStatusTest.java` | `shouldContainFiveValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/JobTypeTest.java` | `shouldNotExposeWorkflowAsExecutableJobType`、`shouldProvideNonNullBatchType_whenEnumeratingEveryJobType` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/PartitionStatusTest.java` | `shouldContainNineValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/RetryPolicyTypeTest.java` | `shouldContainThreeValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/RetryScheduleStatusTest.java` | `shouldContainSixValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/TaskStatusTest.java` | `shouldContainSevenValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/WorkflowJoinModeTest.java` | `fromCodeOrDefault_unknownCode_returnsAll` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/i18n/MessagesPropertiesParityTest.java` | `shouldRejectDuplicateKeysAndBlankValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/jdbc/JdbcMappedSqlValidatorTest.java` | `shouldNormalizeValidIdentifierToLowerCase`、`shouldQuotePgIdentifier` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/logging/LogSanitizerTest.java` | `shouldReplaceEveryUnicodeLineBreakWithUnderscore` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/page/CursorCodecTest.java` | `shouldReturnNull_whenCursorIsEmpty` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/security/CredentialEnvResolverTest.java` | `returnsNull_whenNull` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/security/DnsResolveGuardTest.java` | `shouldAllow_whenDocumentationGlobalIpv6` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/service/BatchObjectCryptoServiceTest.java` | `decryptNullBytes_shouldReturnNull`、`decryptEmptyBytes_shouldReturnEmpty`、`resolveKeyRef_fallsBackToDefault_whenSecurityMapEmpty` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/spi/task/TaskResultTest.java` | `shouldDefaultOutputToEmptyMap_whenOutputAbsent` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/stateful/StatefulBackendIdentityTest.java` | `shouldPreserveResourceCase_whenBuildingBackendIdentity` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/storage/S3ObjectStoreExceptionMappingTest.java` | `shouldReportMissing_whenKeyDoesNotExist` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/utils/ContentMaskingUtilsTest.java` | `shouldApplyTextRulesToMalformedJsonLikeInput` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/utils/CorrelationIdsTest.java` | `normalize_trimsAndKeepsSafeCharacters`、`normalize_replacesUnsafeCharactersAndControls`、`normalize_capsAtHeaderColumnLength`、`normalize_usesFallbackWhenInputBlankOrInvalid` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/utils/IdGeneratorTest.java` | `shouldReturn32HexChars_whenGeneratingTraceId`、`shouldReturn32HexChars_whenGeneratingInvocationId` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/utils/OtelTraceContextTest.java` | `shouldReturnNull_whenNoActiveSpan`、`shouldFallBackToGeneratedId_whenNoActiveSpan` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidBizDateValidatorTest.java` | `shouldTrimWhitespace_whenValidating` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidResourceCodeTest.java` | `shouldReject_whenCodeNull`、`shouldReject_whenCodeEmpty`、`shouldReject_whenCodeBlank` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidTenantIdTest.java` | `shouldReject_whenTenantIdNull`、`shouldReject_whenTenantIdBlank`、`shouldAccept_whenTenantIdAtMaxLength`、`shouldReject_whenTenantIdExceedsMaxLength` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/config/ConsoleOidcPropertiesTest.java` | `shouldAllowEmptySettings_whenOidcIsDisabled` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/service/ConsoleAiPromptGuardTest.java` | `shouldThrowBizExceptionForNullPrompt`、`shouldThrowBizExceptionForBlankPrompt` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/job/support/BuiltinTaskTypeGuardTest.java` | `shouldAllowType_whenJobTypeNullOrBlank`、`shouldAllowBuiltinType_whenAnyGrantedRoleIsPlatformAdmin` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/ops/infrastructure/TriggerProxyJsonContractTest.java` | `shouldReturnEmptyList_whenDownstreamRowsAreNull` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/ops/service/WorkerCompatibilityEvaluatorTest.java` | `shouldReportOk_whenMajorOneFormatsVary` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/ConsolePasswordHasherTest.java` | `shouldMatchFlywaySeedArgon2Hash` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/TenantScopeTest.java` | `shouldReturnTenantId_whenNonBlank` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/captcha/TencentCaptchaVerifierTest.java` | `shouldReportTencent_whenQueryingProviderName` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/param/WorkflowDefinitionWriteParamsTest.java` | `shouldRejectMissingRequiredField` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/web/support/WorkflowMermaidRendererTest.java` | `shouldReturnHeaderOnly_whenDetailMissing` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ReadReplicaHappyPathIntegrationTest.java` | `shouldRouteToReplica_whenTransactionReadOnly` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/support/cache/ConsoleQueryCacheServiceTest.java` | `keySegment_keepsShortSafeValuesReadable` |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/PipelineWorkerAtomicIsolationOptInArchTest.java` | `shouldOptInIsolationCheck_whenImportWorker`、`shouldOptInIsolationCheck_whenExportWorker`、`shouldOptInIsolationCheck_whenProcessWorker`、`shouldOptInIsolationCheck_whenDispatchWorker` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/scheduler/DryRunSchedulingPriorityTest.java` | `shouldKeepConfiguredPriority_whenFormalTask`、`shouldKeepNullPriority_whenFormalTaskHasNoPriority` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultCompensationServiceTest.java` | `shouldThrowWhenCommandIsNull` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `shouldThrowWhenTenantIdIsBlankOnStartDrain`、`shouldThrowWhenWorkerCodeIsBlankOnStartDrain`、`shouldThrowWhenTenantIdIsBlankOnForceOffline`、`shouldThrowWhenWorkerCodeIsBlankOnListClaimedTasks`、`warmup_throwsWhenTenantBlank` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnEmptyWhenDefinitionIdIsNull`、`shouldReturnEmptyWhenCurrentNodeCodeIsBlank`、`shouldReturnFalseWhenWorkflowRunIdIsNull`、`shouldReturnFalseWhenDefinitionIdIsNull`、`shouldReturnFalseWhenNodeCodeIsBlank` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/WorkflowConditionEvaluatorTest.java` | `shouldMatchEqualityWithDoubleEquals`、`shouldReturnFalseWhenPayloadKeyMissing` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/sensor/HttpPollSensorPolicyTest.java` | `matchExpr_missingPath_returnsFalseNotThrow` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/BizDateArithmeticTest.java` | `shouldReturnNull_whenNamedOffsetUnknown`、`shouldReturnEmpty_whenRangeTagUnknown`、`shouldReturnEmpty_whenBizDayCountExceedsCap` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowFanOutSupportTest.java` | `parseSpec_nullWhenItemsExprBlank` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowGraphValidatorTest.java` | `shouldReportClean_whenWorkflowIdMissing` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowWaitSensorSpecValidatorTest.java` | `shouldIgnoreNonWaitNode`、`shouldRejectUnknownSensorType` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/OrchestratorGracefulShutdownTest.java` | `shouldNotBeDrainingInitially` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/mq/BatchTopicResolverTest.java` | `shouldReturnNull_whenWorkerTypeUnsupported` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRoutingPolicyTest.java` | `shouldReturnNullForNullCandidates`、`shouldReturnNullForEmptyCandidates` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/statemachine/DefaultLifecycleEventMapperTest.java` | `shouldResolveEnumTargetAsState`、`shouldAllowTerminalSelfLoop` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/AdminTestDataCleanupControllerIntegrationTest.java` | `shouldRejectAmbiguousInstanceId_whenNeighborIsInSameTenant`、`shouldRejectAmbiguousInstanceId_whenNeighborIsInAnotherTenant`、`shouldRejectAmbiguousInstanceId_whenNeighborIsArchived` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/ConcurrentTaskClaimIntegrationTest.java` | `assignWorker_onlyOneWorkerWins_acrossRepeatedLaunches` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectRetryWhenDryRunPayloadUnsafe`、`shouldRejectRetryWhenFormalPayloadClaimsDryRun`、`shouldRollbackFileRedispatchWhenParentIsPaused`、`shouldRollbackFileRedispatchWhenTaskCannotReset`、`shouldRejectFileRedispatchWhenExternalOutcomeIsUnknown`、`shouldRejectFileRedispatchWhenRecordIsNotKnownFailed`、`shouldRejectFileRedispatchWhenSingleTaskHasNoFileAssociation`、`shouldRejectRedispatchWhenPayloadTargetDiffers` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/StartupSelfCheckIntegrationTest.java` | `shouldExposeSelfCheckComponent_whenContextStarts` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/DefaultPrioritySchedulerTest.java` | `shouldReturnDefaultPriorityFiveWhenRequestIsNull`、`shouldReturnMediumBandWhenPriorityIsNull` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReturnNoneWhenValueIsNull`、`shouldReturnNoneWhenValueIsBlank`、`shouldReturnNoneWhenValueIsUnknown`、`shouldReportNoneAsNotRuntimeManaged`、`shouldReportCalendarDayAsRuntimeManaged`、`shouldReportSlidingWindowAsRuntimeManaged` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifierTest.java` | `shouldRejectRequest_whenSignatureHeaderMissing` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/service/LaunchParamResolverTest.java` | `shouldReturnNullForNullDeadline` |
| `batch-test-support/src/test/java/io/github/pinpols/batch/common/rls/RlsClosedWorldCheckIntegrationTest.java` | `shardIdentity_defaultExempted` |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/application/TriggerOutboxReleaseGovernorTest.java` | `disabled_returnsConfiguredLimitWithoutReadingLag` |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/infrastructure/TriggerGracefulShutdownTest.java` | `shouldNotBeDrainingInitially` |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/BatchTriggerApplicationIntegrationTest.java` | `contextLoads_succeeds`、`quartzScheduler_isStarted` |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/MisfireHandlerIntegrationTest.java` | `shouldWireMisfireHandlerBean`、`shouldLoadQuartzLaunchJobForMisfireAuditPath`、`shouldNotThrowWhenHandlingMisfire` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_loopback_ipv4`、`rejects_loopback_ipv6`、`rejects_link_local_metadata_ipv4`、`rejects_link_local_metadata_ipv4_mapped_ipv6`、`rejects_private_class_a`、`rejects_private_class_c`、`rejects_any_local`、`accepts_public_literal`、`accepts_public_literal_ipv6`、`rejects_ipv6_ula_aws_metadata`、`rejects_ipv6_ula_fc00_range`、`rejects_ipv6_ula_fd_range`、`rejects_ipv6_link_local` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/integration/BatchWorkerAtomicApplicationIntegrationTest.java` | `shouldExposeApplicationContext_whenStarted` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicIsolationStartupCheckTest.java` | `noDualUseEnabled_noThrow` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutorTest.java` | `taskType_isSparkSubmit` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutorHardeningTest.java` | `shouldClassifyAsDdl_whenStatementIsDoBlock`、`shouldClassifyAsDdl_whenDoBlockLowercase` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutorTest.java` | `shouldReturnSingleStatement_whenNoSemicolon` |
| `batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskReportBatchTest.java` | `reconcilesEachResponseIdentity` |
| `batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/checkpoint/CheckpointPartitionGuardTest.java` | `nullMeansSinglePartition_allow` |
| `batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxPgIntegrationTest.java` | `claimNext_returnsEmptyWhenNothingDue` |
| `batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/support/ActiveTaskLeaseRegistryTest.java` | `shouldReturnEmptySnapshotWhenNoLeases` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/DispatchTaskExecutorTest.java` | `shouldReportDispatchTaskType_whenTaskTypeQueried` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelCircuitBreakerTest.java` | `shouldAllowWhenNoFailuresRecorded`、`shouldIsolateFailuresByChannelKey` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelTypePolicyTest.java` | `shouldReturnCanonicalType_whenInputHasSpacesAndLowercase`、`shouldReturnEmpty_whenTypeUnknown` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchManifestSupportTest.java` | `shouldKeepNull_whenBusinessDateMissing` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/BatchWorkerDispatchApplicationIntegrationTest.java` | `shouldLoadApplicationContext_whenStarted` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/DispatchSpiEquivalenceIntegrationTest.java` | `shouldContainDispatchTaskType_whenListingRegisteredTypes` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStepTest.java` | `stage_returnsAck` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/CompensateDispatchStepTest.java` | `stage_returnsCompensate` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/CompleteDispatchStepTest.java` | `stage_returnsComplete` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStepTest.java` | `stage_returnsDispatch` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/PrepareDispatchStepTest.java` | `stage_returnsPrepare` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/RetryDispatchStepTest.java` | `stage_returnsRetry` |
| `batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/infrastructure/ExportTaskExecutorTest.java` | `shouldReportExportTaskType_whenReadingTaskType` |
| `batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/BatchWorkerExportApplicationIntegrationTest.java` | `shouldLoadContext_whenApplicationStarts` |
| `batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/ExportSpiEquivalenceIntegrationTest.java` | `shouldRegisterExportTaskType_whenContextLoads` |
| `batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidatorTest.java` | `validate_ignoresParameterTextOutsideSqlParameters` |
| `batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/stage/format/GenerateCursorCodecTest.java` | `roundTrip_preservesBoolean` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/config/ImportScannerPropertiesTest.java` | `doneFileSuffix_defaultsToDotDone`、`doneFileNaming_defaultsToAppendFullName` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportTaskExecutorTest.java` | `shouldReportImportTaskType_whenReadingTaskType` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationCoercionsTest.java` | `decimalValue_returnsNull_forNonNumericText` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/BatchWorkerImportApplicationIntegrationTest.java` | `shouldLoadContext_whenApplicationStarts` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/ImportSpiEquivalenceIntegrationTest.java` | `shouldRegisterImportTaskType_whenContextLoads` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/FeedbackStepTest.java` | `shouldReturnStageFeedback_whenStageAccessed` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java` | `shouldReturnLoadStage` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessRangeSliceTest.java` | `shouldSliceLosslessly_whenLastLineHasNoTrailingNewline`、`shouldSliceLosslessly_whenEmptyLinesPresent`、`shouldSliceLosslessly_whenCrlfLineEndings` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStreamEligibilityTest.java` | `shouldAllowDirectStreaming_whenTemplateMissing` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/ValidateStepTest.java` | `shouldReturnValidateStage` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingPartitionMaintenanceTest.java` | `shouldKeepRowsUntilDeletionIsEnabled` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/infrastructure/ProcessTaskExecutorTest.java` | `shouldReportProcessTaskType_whenTaskTypeQueried` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/infrastructure/verifier/ProcessPublishedCountVerifierTest.java` | `shouldPass_whenPublishedCountPositive`、`shouldPass_whenPublishedCountIsNumericString` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/ProcessSpiEquivalenceIntegrationTest.java` | `shouldContainProcessTaskType_whenListingRegisteredTypes` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/ProcessStagingCleanupYamlIntegrationTest.java` | `shouldDisableStagingOrphanCleaner_whenTestProfileActive` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/CommitStepTest.java` | `shouldReturnStageCommit_whenStageAccessed` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/FeedbackStepTest.java` | `shouldReturnStageFeedback_whenStageAccessed` |
| `examples/task-spi-plugin/sftp-push-executor/src/test/java/io/github/pinpols/batch/ext/sftp/SftpPushTaskExecutorTest.java` | `shouldReturnSftpPushType_whenTaskTypeQueried`、`shouldDeclareNetAndDiskCapability_whenCapabilityQueried` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/BatchPlatformClientConfigValidationTest.java` | `shouldAcceptAllDefaults` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/TaskTypeDescriptorAssemblyTest.java` | `shouldReturnNullDescriptor_whenHandlerDoesNotDeclare` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/WorkerFingerprintTest.java` | `shouldReturnCurrentJvmPid_whenReadingProcessId` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherClaimJitterTest.java` | `shouldReturnZero_whenBaseDelayIsZero` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodecTest.java` | `shouldParsePlainFields`、`shouldParseQuotedFieldContainingDelimiter`、`shouldYieldEmptyTrailingField`、`shouldSupportCustomDelimiter` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/idempotent/IdempotencyKeyResolverTest.java` | `shouldReturnLiteral_whenNoPlaceholder` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/task/SdkTaskResultTest.java` | `shouldReturnEmptyOutput_whenOutputAbsent` |
| `sdk/typescript/tests/decide-backpressure.test.ts` | `decideBackpressure default paused=false: no resume in band` |
| `sdk/typescript/tests/handler.test.ts` | `handler: taskFailure default errorCode = EXECUTION_FAILED` |
| `sdk/typescript/tests/kafka-offset-policy.test.ts` | `loweredCeiling: first withhold sets the ceiling to the record offset`、`commitBlockedByWithheld: no ceiling → never blocked`、`resolveFromBeginning: defaults to false (latest), parity with the 4 other SDKs`、`resolveFromBeginning: an explicit value always wins` |
| `sdk/typescript/tests/shared-constants-parity.test.ts` | `schema_versions_supported parity`、`worker_runtime_states parity`、`sensitive_keywords parity`、`task_statuses parity`、`report_error_codes parity` |

### batch-console

| 测试文件 | 快照中的候选测试 |
|---|---|
| `e2e/alert-outbox-ops.spec.ts` | `重试 tab 默认激活` |
| `e2e/c-plus-coverage.spec.ts` | `actuator/health 暴露 200` |
| `e2e/excel-import.spec.ts` | `下载配置包模板按钮可见`、`导出当前配置包按钮可见` |
| `e2e/file-ops.spec.ts` | `默认展示运行列表 tab` |
| `e2e/flows/01-trigger-instance.spec.ts` | `1. 查到一个可触发的 jobDefinition` |
| `e2e/notification-management.spec.ts` | `旧 webhooks 路由重定向` |
| `e2e/ops-diagnostic.spec.ts` | `集群诊断区块存在` |
| `e2e/ops-shard-catalog-crud.spec.ts` | `页可达 + 新建入口可见` |
| `e2e/ops-tenant-placement-crud.spec.ts` | `页可达 + 新建入口可见` |
| `e2e/reports-ops.spec.ts` | `展示所有报表卡片`、`header 显示 tenantId 标签` |
| `e2e/smoke.spec.ts` | `根路径重定向到运营概览` |
| `src/api/adapters.test.ts` | `returns correct slice for page 2`、`returns partial last page` |
| `src/api/atomicTaskTypes.test.ts` | `returns empty array when payload is empty` |
| `src/api/customTaskTypes.test.ts` | `returns null for invalid JSON` |
| `src/api/meta.test.ts` | `payload 不是对象时返回 {}(防御后端异常)` |
| `src/api/taskHeartbeat.test.ts` | `reads percent in 0-100 range`、`treats 0-1 floats as ratios and scales up`、`rounds to 0.1`、`prefers percent over progress when both present` |
| `src/api/workerFingerprint.test.ts` | `listWorkerFingerprints normalizes null response to empty array`、`getWorkerFingerprintSummary normalizes null response to empty array` |
| `src/components/common/EmptyState.test.ts` | `没有操作时不渲染空的操作容器` |
| `src/components/common/SensitiveFieldAlert.test.ts` | `handles invalid JSON string gracefully (no render)` |
| `src/components/common/docsRegistry.test.ts` | `docKey 全为 kebab-case(避免 camelCase / snake_case 混用)`、`未知 docKey 返回空字符串(避免 iframe 加载未定义页)` |
| `src/components/common/statusTagResolve.test.ts` | `falls back to fallback arg when value empty`、`falls back to em-dash when value empty and no fallback`、`returns info for unknown values when no local label override` |
| `src/composables/useFormValidate.test.ts` | `required 默认 blur`、`formRef 未挂载时 validate() 返回 false 不抛` |
| `src/composables/useOnboardingTour.test.ts` | `首次访问且无完成标记时展示引导`、`已完成引导时不再展示` |
| `src/constants/theme.test.ts` | `defaults to dark when no user preference is stored`、`preserves stored user preference`、`migrates legacy system default to dark once` |
| `src/directives/permission.test.ts` | `keeps element when permissions list empty` |
| `src/stores/auth.test.ts` | `isLoggedIn is false when no token`、`hasPermission returns false when no userInfo` |
| `src/stores/tabs.test.ts` | `returns -1 for unknown key` |
| `src/utils/aiMessagePresentation.test.ts` | `keeps approved answer content` |
| `src/utils/datetime.test.ts` | `handles epoch ms number`、`returns raw string for invalid date` |
| `src/utils/errorCatalog.test.ts` | `falls back to network`、`returns undefined when nothing matches` |
| `src/utils/formatAiUsd.test.ts` | `uses cents for zero and ordinary amounts` |
| `src/utils/iconResolve.test.ts` | `已知图标名:返回对应 EP 图标 Component`、`未知图标名:返回 fallback(Menu)而非 undefined,保菜单可渲染` |
| `src/utils/idempotency.test.ts` | `returns a non-empty string` |
| `src/utils/lastApiMeta.test.ts` | `stores a valid meta object`、`sets value to null when called with undefined`、`sets value to null when called with null` |
| `src/utils/logRedact.test.ts` | `sanitizeParams returns undefined for null/undefined` |
| `src/utils/logger.test.ts` | `logClick writes type=click`、`keeps legitimate error messages`、`filters by keyword in name`、`filters by keyword in props`、`returns all when no filter` |
| `src/utils/metaEnumPick.test.ts` | `returns first matching non-empty group`、`skips missing keys and returns first hit`、`skips empty arrays`、`returns [] when no key matches`、`returns [] for undefined input`、`returns [] for null input`、`returns [] when called with no keys` |
| `src/utils/passwordGenerator.test.ts` | `throws when length < 4`、`excludes ambiguous characters (0/O/I/l/1)`、`extra point at length ≥ 12`、`caps at 4 (extreme)` |
| `src/utils/pipelineStepSummary.test.ts` | `PARSE 步骤取 parsedCount`、`GENERATE 取 recordCount`、`未映射 stage(如 PREPARE)且 summary 含计数 → 回退优先级列表`、`已解析对象入参(非字符串)也支持`、`计数字段非数字(脏数据)→ 跳过不误显` |
| `src/utils/queryFormOptions.test.ts` | `returns sorted unique non-empty values`、`returns empty array for empty input`、`filters out undefined picks` |
| `src/utils/resourceCodeBuilder.test.ts` | `uppercases lower`、`keeps digits + underscores`、`empty → EMPTY`、`< 3 chars → LENGTH`、`> 30 chars → LENGTH`、`digit-leading rejected (BIZ_FORMAT requires letter first)`、`exact 30 chars boundary OK`、`accepts v-prefixed`、`accepts bare digits`、`builds without version`、`builds with version`、`normalizes biz input on the fly`、`drops bad version silently`、`empty existing → empty` |
| `src/utils/safeHtml.test.ts` | `returns the sanitized string unchanged` |
| `src/utils/safeJson.test.ts` | `returns empty string unchanged`、`falls back to original string on malformed JSON` |
| `src/utils/sensitiveKeys.test.ts` | `returns empty for clean object`、`detects direct sensitive keys`、`parses JSON string input` |
| `src/utils/structuredSummary.test.ts` | `truncates plain text summaries` |
| `src/utils/text.test.ts` | `keeps short values unchanged`、`truncates CJK text by characters rather than bytes`、`does not split a surrogate pair` |
| `src/utils/userErrorMessage.test.ts` | `preserves non-API client-side validation errors` |
| `src/views/monitor/canReplayFailedWorkflowJobNode.test.ts` | `allows only a failed JOB node with a linked instance in a failed Workflow run` |
| `src/views/monitor/workflowNodeSkipReason.test.ts` | `marks old skipped rows without a recorded reason`、`does not show skip copy for other node states` |
| `src/views/scheduler/batchDayPresentation.test.ts` | `builds leap-year month boundaries in local time` |
| `src/views/system/atomicRuntimeModel.test.ts` | `不可用时即使混有executor字段也不展示为真实状态` |
| `src/views/worker/workerActions.test.ts` | `在线节点只展示下线和接管动作`、`排空中节点不再展示排空和预热`、`离线节点只允许预热` |
| `src/views/workflow/designer/codec/mermaidExporter.test.ts` | `empty snapshot → header only` |

## 2026-11-01：当前 public Map 候选逐项台账

本表固定 11 月 1 日清单的 60 个返回类型词法候选及读取时源文件摘要；行号可能漂移，重载按行区分。已核查结论对应前文所列源码和调用边界；待核查不代表违规。输入参数、隐式 public 接口等不在此分母内。

| 文件和行 | 方法 | SHA-256 前 12 位 | 结论 |
|---|---|---|---|
| [BatchKafkaProducerSupport.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/config/BatchKafkaProducerSupport.java#L36) | `stringProducerConfig` | `fd7f26eb0d1e` | 保留：传递任意 Kafka client 配置并供调用方覆盖 |
| [RunModeSupport.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/context/RunModeSupport.java#L17) | `copyWithDefault` | `358ea291406c` | 保留：复制不定业务参数并规范化模式 |
| [BatchRuntimeStatusEndpoint.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/diagnostics/BatchRuntimeStatusEndpoint.java#L51) | `status` | `3228b82711cf` | 保留：Actuator 现有 wire 及条件出现的诊断字段 |
| [CursorCodec.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/page/CursorCodec.java#L45) | `decode` | `e92c60c35010` | 保留：不同分页调用方的动态排序键 |
| [JsonUtils.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/JsonUtils.java#L91) | `toMap` | `83fb7fc5ea33` | 保留：对任意对象提供通用 JSON 转换 |
| [SecretMasking.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java#L65) | `maskSensitiveKeys` | `ebeb1927bf06` | 保留：递归处理不定字段，重载分别提供自定义敏感键 |
| [SecretMasking.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java#L71) | `maskSensitiveKeys` | `ebeb1927bf06` | 保留：递归处理不定字段，重载分别提供自定义敏感键 |
| [SensitiveEvidenceMasking.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/SensitiveEvidenceMasking.java#L22) | `maskMetadata` | `c1bbb6bf6836` | 保留：血缘响应中的不定 metadata 脱敏 |
| [FileChannelConfigQueryView.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/view/FileChannelConfigQueryView.java#L26) | `toMap` | `acd8910bc2af` | 保留：Excel 导入旧值合并兼容键和缺省值 |
| [FileTemplateConfigQueryView.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/view/FileTemplateConfigQueryView.java#L61) | `toMap` | `109914737586` | 保留：Excel 导入旧值合并兼容键和缺省值 |
| [ConsoleJobConfigViews.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/view/ConsoleJobConfigViews.java#L42) | `toMap` | `d2ac11479f8b` | 保留：批量窗口 Excel 行按旧键和 null 语义导出 |
| [ConsoleJobOpsSupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/ConsoleJobOpsSupport.java#L525) | `parsePayload` | `e4ddc76a4f26` | 保留：接收调用方可变业务参数 JSON |
| [ResourceQueueRow.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/view/ResourceQueueRow.java#L43) | `toMap` | `524267332ca1` | 保留：Excel 列读取及 null 单元格契约 |
| [ConfigPackageExcelSchema.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelSchema.java#L133) | `toExportRow` | `08da296bda0a` | 保留：日历 Excel 导出行 |
| [ConsoleMapSupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleMapSupport.java#L18) | `mapOf` | `8e40cd9b97c4` | 保留：组装多类配置审计 JSON 的动态 detail |
| [PartitionDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/PartitionDispatchService.java#L55) | `effectiveParams` | `395ed8f676da` | 保留：T1 参数传递至 T2 并构建动态任务载荷 |
| [WorkflowNodePayloadBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java#L299) | `nodeOutput` | `0868cea66d76` | 保留：工作流表达式读取各节点动态输出 |
| [WorkflowNodePayloadBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java#L304) | `workflowRunFields` | `0868cea66d76` | 保留：工作流表达式上下文的可选字段 |
| [WorkflowNodePayloadBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java#L333) | `nodeOutput` | `0868cea66d76` | 保留：工作流表达式读取各节点动态输出 |
| [WorkflowNodePayloadBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java#L338) | `workflowRunFields` | `0868cea66d76` | 保留：工作流表达式上下文的可选字段 |
| [WorkflowNodePayloadBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java#L362) | `parsePayloadMap` | `0868cea66d76` | 保留：Workflow 作业原始动态载荷解析 |
| [WorkflowRunExecutionSnapshot.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java#L584) | `dispatchSecretBindings` | `a3fb85b85ea6` | 保留：按节点返回渠道编码索引的冻结密钥绑定 |
| [FileGovernanceViews.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/FileGovernanceViews.java#L99) | `toCacheValues` | `30a1cc16740a` | 保留：序列化并消费既有延迟样本缓存结构 |
| [FileGovernanceViews.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/FileGovernanceViews.java#L119) | `toCacheValues` | `30a1cc16740a` | 保留：序列化并消费既有延迟样本缓存结构 |
| [SensorFileArrivalView.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/SensorFileArrivalView.java#L18) | `toProbeOutput` | `7227d388aafe` | 保留：Sensor 命中输出供动态表达式消费 |
| [MapJsonbTypeHandler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java#L37) | `getNullableResult` | `230a48c9eec6` | 保留：MyBatis 不同结果来源的动态 JSONB 重载 |
| [MapJsonbTypeHandler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java#L43) | `getNullableResult` | `230a48c9eec6` | 保留：MyBatis 不同结果来源的动态 JSONB 重载 |
| [MapJsonbTypeHandler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java#L48) | `getNullableResult` | `230a48c9eec6` | 保留：MyBatis 不同结果来源的动态 JSONB 重载 |
| [DispatchChannelSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchChannelSnapshotService.java#L41) | `pin` | `c084f72aa7df` | 保留：在业务参数中冻结动态渠道快照 |
| [DispatchChannelSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchChannelSnapshotService.java#L166) | `pinWorkflowReplay` | `c084f72aa7df` | 保留：在重放业务参数中应用批准的渠道快照和绑定 |
| [DispatchSecretSnapshotResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchSecretSnapshotResolver.java#L34) | `pin` | `4587d4a3e29d` | 保留：在业务参数中按渠道冻结密钥版本 |
| [FileTemplateSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/FileTemplateSnapshotService.java#L41) | `pin` | `4fcc703e3415` | 保留：业务参数中冻结模板快照 |
| [FileTemplateSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/FileTemplateSnapshotService.java#L90) | `pinWorkflowReplay` | `4fcc703e3415` | 保留：重放业务参数中应用批准的模板快照 |
| [PlatformFileRecordRepository.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java#L31) | `loadFileRecord` | `cc2ab89dc58e` | 保留：具名投影转成共享阶段上下文和插件既有键值契约 |
| [PlatformFileRecordRepository.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java#L48) | `loadFileRecordByStoragePath` | `cc2ab89dc58e` | 保留：共享文件记录格式供导入与导出恢复读取 |
| [PlatformFileTemplateRepository.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileTemplateRepository.java#L18) | `loadLatestTemplateConfig` | `6704c9fc9f92` | 保留：模板投影供解析器和插件消费现有配置键 |
| [PlatformPipelineRunRepository.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformPipelineRunRepository.java#L102) | `loadLatestSucceededStepOutputSummary` | `d68ebabd9f2a` | 保留：不同阶段的 JSON 输出供跳过恢复透传 |
| [PlatformFileRecordView.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileRecordView.java#L40) | `toPluginValues` | `853b28c9f13b` | 保留：向现有 Worker 文件插件边界转换 snake_case 字段 |
| [PlatformFileTemplateConfigView.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileTemplateConfigView.java#L61) | `toPluginValues` | `764b9f0b5bd7` | 保留：向现有 Worker 模板插件边界转换字段 |
| [FrozenFileTemplateResolver.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/FrozenFileTemplateResolver.java#L31) | `resolve` | `ea17a46960ae` | 保留：冻结与实时来源统一为动态模板配置 |
| [ChannelConfigMerge.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/ChannelConfigMerge.java#L106) | `merge` | `a02cfb20e29e` | 保留：合并列和渠道 JSON 并保留插件配置契约 |
| [FileDispatchRepository.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/FileDispatchRepository.java#L36) | `loadFile` | `938069df6a9c` | 保留：分发准备将文件字段放入阶段插件上下文 |
| [FileDispatchRepository.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/FileDispatchRepository.java#L44) | `loadFile` | `938069df6a9c` | 保留：分发准备将文件字段放入阶段插件上下文 |
| [FileDispatchRepository.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/FileDispatchRepository.java#L52) | `loadChannel` | `938069df6a9c` | 保留：渠道配置用于合并并供派发和回执消费 |
| [DispatchChannelConfigView.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/mapper/DispatchChannelConfigView.java#L25) | `toPluginValues` | `76c13107e046` | 保留：渠道投影用于派发和回执插件配置 |
| [ExportConfigValueSupport.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/config/ExportConfigValueSupport.java#L19) | `toMap` | `8b228a22660e` | 保留：导出模板的动态 JSON 或 Map 规范化 |
| [GenericJdbcMappedExportDataPlugin.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java#L83) | `loadBatch` | `3177938e44cc` | 保留：插件查询按模板选择列并返回动态批次行 |
| [SqlTemplateExportDataPlugin.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java#L91) | `loadBatch` | `96c18ed23cad` | 保留：实现导出插件契约并传入生成阶段 |
| [ValidationConfigSupport.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationConfigSupport.java#L20) | `toMap` | `c278f4d42fc4` | 保留：读取 Map 或 JSON 的动态校验配置 |
| [ValidationConfigSupport.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationConfigSupport.java#L42) | `firstMap` | `c278f4d42fc4` | 保留：按候选键提取动态规则 |
| [ValidationConfigSupport.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationConfigSupport.java#L52) | `payloadToMap` | `c278f4d42fc4` | 保留：客户载荷供按字段名的规则求值使用 |
| [ValidationRuleSetMerger.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationRuleSetMerger.java#L32) | `merge` | `e31799c29ad1` | 保留：合并动态字段映射和显式规则集 |
| [ParseSupport.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java#L62) | `parseHints` | `4e51523686f6` | 保留：不同格式解析器读取各自动态提示 |
| [ParseSupport.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java#L76) | `readJsonObject` | `4e51523686f6` | 保留：Map 和 JSON 文本均可传入的配置边界 |
| [TaskDispatcher.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java#L618) | `progressSnapshot` | `989784d9a66d` | 保留：续租请求携带 handler 自定义进度快照 |
| [SdkRowResult.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/SdkRowResult.java#L63) | `toOutput` | `69342074d3c4` | 保留：转为任务结果 output 的既有字段契约 |
| [SdkTypedParameters.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkTypedParameters.java#L71) | `toOutputMap` | `d5aa6605663a` | 保留：将业务泛型结果转为可变结构的 output |
| [ProgressReporter.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/ProgressReporter.java#L29) | `latest` | `84274e0c30ed` | 保留：跨线程可扩展的进度快照 |
| [SdkTaskStoppedException.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskStoppedException.java#L24) | `breakPosition` | `46afd4757d0e` | 保留：携带 handler 定义的续跑断点并进入取消结果 |
| [FakeBatchPlatform.java](../../sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java#L233) | `registrations` | `2f58c33e8612` | 保留：记录接收到的注册 JSON 请求体供测试读取 |

## 2026-11-01：后端薄包装历史候选逐项台账

以下为 10 月 28 日 110 个词法候选，摘要为建表时当前文件内容。每个候选单独裁定，待核查不等于问题；源码变化后须重验。

| 文件 | 方法 | 当前 SHA-256 前 12 位 | 结论 |
|---|---|---|---|
| [TokenBucketRateLimiter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/ratelimit/TokenBucketRateLimiter.java) | `configSupplierFor` | `0b7b8be09af8` | 保留：命名并缓存限流桶配置供应器 |
| [FilesystemObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/FilesystemObjectStore.java) | `syntheticEtag` | `25720c586bc2` | 保留：命名文件大小和时间生成的本地 ETag |
| [TenantConfigMatrixBuilder.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java) | `first` | `ddaece8f2600` | 保留：空列表安全选取首项 |
| [SqlTemplateExportSqlValidator.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java) | `isParameterStart` | `edaa845aaf28` | 保留：命名 SQL 参数首字符规则 |
| [SqlTemplateExportSqlValidator.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java) | `isParameterPart` | `edaa845aaf28` | 保留：命名 SQL 参数后续字符规则 |
| [OkHttpOrchestratorExternalHttpTransport.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransport.java) | `sameHost` | `5d7370669615` | 保留：DNS 回调中的地址规范化比较 |
| [DefaultWorkerRegistryService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java) | `firstHeartbeat` | `c8c61cdfd7aa` | 保留：明确注册时服务端时间和忽略 Worker 时钟的约束 |
| [MapperStatusWriteConventionTest.java](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/MapperStatusWriteConventionTest.java) | `withoutComments` | `38399abfb7ac` | 保留：命名检查前 XML 注释剔除步骤 |
| [ImportRecordGovernanceService.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java) | `stringValue` | `0bd63406a1d5` | 已简化：使用标准库保持空值与字符串转换语义 |
| [TriggerStatusQueryFacade.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerStatusQueryFacade.java) | `unknown` | `2530a33ef0a9` | 保留：为未知触发状态构建具名结果 |
| [BatchDayReplayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java) | `failedStatuses` | `8a258c5cfe36` | 保留：命名失败重放范围 |
| [BatchDayReplayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java) | `isCancellable` | `8a258c5cfe36` | 保留：命名可取消状态业务判断 |
| [WorkflowRunReplaySnapshotResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySnapshotResolver.java) | `readProtectedDispatchChannelSnapshots` | `443dca888f59` | 保留：区分渠道快照与模板快照的领域语义 |
| [DefaultWorkflowDagService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java) | `stringValue` | `c71e37547f75` | 已简化：使用标准库保持空值与字符串转换语义 |
| [PreprocessStep.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java) | `hasTemplateConfigSnapshot` | `7f33ed33d5df` | 保留：命名模板快照存在条件 |
| [ConsoleLoginService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleLoginService.java) | `invalidCredentials` | `9506606b6a60` | 保留：命名认证失败异常和错误键 |
| [WorkflowRunExecutionSnapshot.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java) | `readProtectedDispatchChannelSnapshots` | `a3fb85b85ea6` | 保留：区分渠道快照与模板快照的领域语义 |
| [FileGovernanceArrivalGroupGuardTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceArrivalGroupGuardTest.java) | `bizDate` | `e119b8a9c69d` | 保留：测试行数据中的日期转换 |
| [DefaultConsoleJobDefinitionApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java) | `resolveSoftRuntimeSeconds` | `04b03cc0d7c2` | 保留：命名请求值与监控默认值的选择 |
| [TerminalNotificationDeliveryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TerminalNotificationDeliveryService.java) | `notification` | `5478eae0afdd` | 保留：具名构造通知目的地及其可空字段 |
| [AlertmanagerEmitPublisher.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/governance/AlertmanagerEmitPublisher.java) | `stripTrailingSlash` | `3b4f94447486` | 保留：命名地址末尾斜线规范化 |
| [DefaultConsoleAlertApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleAlertApplicationService.java) | `normalizeStatus` | `d19fb8ae1abe` | 保留：命名告警状态归一及缺省口径 |
| [StatefulBackendGuard.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java) | `matches` | `92cf8b7b847f` | 保留：命名后端及身份同时匹配的条件 |
| [DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | `isJobNode` | `2c2014fad378` | 保留：命名 JOB 分支判断 |
| [DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | `isWaitNode` | `2c2014fad378` | 保留：命名 WAIT 分支判断 |
| [DefaultRetryGovernanceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java) | `stringValue` | `a3930e16154c` | 已简化：使用标准库保持空值与字符串转换语义 |
| [JsonFixtureContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureContractTest.java) | `fixturesDir` | `04eb34dcd58c` | 保留：命名 SDK 契约 fixture 路径 |
| [JsonFixtureContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureContractTest.java) | `openapiPath` | `04eb34dcd58c` | 保留：命名权威 OpenAPI 路径 |
| [ConsoleSessionRegistry.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleSessionRegistry.java) | `sessionStateTtl` | `f0b6d998580c` | 保留：命名会话状态 TTL 及默认策略 |
| [FileGovernanceScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java) | `defaultText` | `8cd602fffdc5` | 保留：对空白配置使用指定回退值 |
| [CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | `resolveCutoffTime` | `3b4e5429a8c3` | 保留：命名日历截止时间默认策略 |
| [CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | `previousWorkday` | `3b4e5429a8c3` | 保留：明确向前滚动和诊断方向 |
| [CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | `nextWorkday` | `3b4e5429a8c3` | 保留：明确向后滚动和诊断方向 |
| [SmtpEmailDispatchChannelAdapterTest.java](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapterTest.java) | `port` | `0cba3e27f436` | 保留：封装 SMTP 测试服务器监听端口 |
| [ConsoleOidcAuthenticationSuccessHandler.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/ConsoleOidcAuthenticationSuccessHandler.java) | `isAscii` | `1341a4ec47e3` | 保留：命名 OIDC 身份安全字符判断 |
| [ConsoleRateLimitFilter.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ratelimit/ConsoleRateLimitFilter.java) | `isExpensivePath` | `4f124920e7e6` | 保留：命名昂贵请求路径策略 |
| [BatchWindowGateTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchWindowGateTest.java) | `mockWorkerSelector` | `e6c04f11f3ad` | 已简化：直接创建局部 mock，移除单次转发 |
| [CrossDayDependencyReconciler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/CrossDayDependencyReconciler.java) | `loadWorkflowRun` | `c63b9fc30cf3` | 保留：命名跨日依赖读取 Workflow 入口 |
| [BatchWorkerSdkAutoConfigurationTest.java](../../sdk/java/spring/src/test/java/io/github/pinpols/batch/sdk/autoconfigure/BatchWorkerSdkAutoConfigurationTest.java) | `registeredHandlers` | `358446979ac4` | 保留：封装测试读取已注册处理器的类型转换 |
| [QuartzMisfireApplicationRestartIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireApplicationRestartIntegrationTest.java) | `jdbcUrlWithApplicationName` | `ec1fc3e5f3c2` | 保留：命名 JDBC 连接参数拼接规则 |
| [ImportDataQualityService.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportDataQualityService.java) | `asString` | `e6aede729991` | 已简化：使用标准库保持空值与字符串转换语义 |
| [ConsolePushApprovalNotifier.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) | `nullToDash` | `10c29a6435b9` | 保留：命名通知内容空白回退规则 |
| [ConsolePushApprovalNotifier.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) | `truncate` | `10c29a6435b9` | 保留：命名通知文本长度裁剪规则 |
| [DefaultFileGovernanceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java) | `safeUrlQuery` | `e9e6f226ee68` | 保留：命名查询参数编码与空值策略 |
| [ConfigPackageExcelValidator.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java) | `resourceQueueExists` | `4f12b9b4cad2` | 保留：命名配置包引用校验的资源队列存在性 |
| [DefaultConsoleKafkaLagQueryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleKafkaLagQueryService.java) | `cacheSegment` | `d9bcfe37b2b1` | 保留：命名缓存键空过滤值回退策略 |
| [WorkflowConditionEvaluator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowConditionEvaluator.java) | `findAtomicOperator` | `1bc8f11251c2` | 已简化：调用点直接使用枚举方法 |
| [RequestSigner.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/RequestSigner.java) | `upper` | `2c9cfc3848b3` | 保留：明确签名方法的空值与固定 Locale 规范化 |
| [WorkflowReplayJobDefinitionResolverTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowReplayJobDefinitionResolverTest.java) | `snapshot` | `38ca0d6362e9` | 保留：命名测试的工作流运行快照构造 |
| [TenantSchedulerSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/scheduler/TenantSchedulerSnapshotService.java) | `emptyBacklog` | `2ddb3214b402` | 保留：命名空积压快照并隐藏多零构造参数 |
| [RequestSignatures.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/security/RequestSignatures.java) | `upper` | `14657130a62e` | 保留：明确签名方法的空值与固定 Locale 规范化 |
| [AtomicRuntimeStatusService.java](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicRuntimeStatusService.java) | `isProdProfile` | `6676c3c02779` | 已简化：原调用处直接使用统一 profile 判断 |
| [FileGovernanceStorageMaintenance.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceStorageMaintenance.java) | `sanitizeTrace` | `7ae5b9ed527a` | 保留：命名清理任务标识字符清洗规则 |
| [DefaultWorkerSelector.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelector.java) | `currentLoadOrZero` | `1d00baca90b4` | 保留：命名 Worker 负载缺省值 |
| [PlatformHttpClientTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClientTest.java) | `attemptedAddresses` | `e25ccd4239bd` | 保留：测试读取连接地址的不可变快照 |
| [ReadReplicaRoutingDataSource.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ReadReplicaRoutingDataSource.java) | `observeReplicaConnection` | `23fc8c36bac3` | 保留：命名副本连接代理的类型边界 |
| [OrchestratorApprovalClient.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/approval/OrchestratorApprovalClient.java) | `hasText` | `10126f07810d` | 保留：多个校验共享非空文本判断 |
| [OutboxEventKeyGenerator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/OutboxEventKeyGenerator.java) | `truncate` | `20402acd735d` | 保留：命名事件键最大长度约束 |
| [ImportIngressScanner.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java) | `isManifestMode` | `4ef9b1cd5aa2` | 保留：命名导入扫描完成文件模式 |
| [ConsoleAuthenticationFilter.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleAuthenticationFilter.java) | `isPublicAuthPath` | `2787ed9b541d` | 保留：命名认证过滤器的公开路径策略 |
| [JobLifecycleMetricsRecorder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/observability/JobLifecycleMetricsRecorder.java) | `resolveErrorCode` | `fe6e9cf355a1` | 保留：命名指标错误码回退规则 |
| [RequestSignatureVerifier.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifier.java) | `hasValidSignatureShape` | `60659e510bc3` | 保留：命名签名结构校验 |
| [DefaultResourceScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultResourceScheduler.java) | `isWaitingRetry` | `986583cbaab2` | 保留：命名重试等待条件 |
| [DefaultDryRunPlanService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DefaultDryRunPlanService.java) | `defaultParamsHas` | `39e79ade78f1` | 保留：命名作业默认参数存在判断 |
| [ConsoleOidcIdentityService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleOidcIdentityService.java) | `isAscii` | `75c944069896` | 保留：命名 OIDC 身份字符集判断 |
| [DefaultTaskExecutionWrapperTest.java](../../batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapperTest.java) | `actualReplayCapability` | `4addff71f8e6` | 保留：与声明能力对照的实际能力测试夹具 |
| [ConsoleClusterDiagnosticService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java) | `isTerminal` | `7b0f1c0c40ed` | 保留：命名诊断中的终态分类 |
| [TenantJobDefinitionApplySupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantJobDefinitionApplySupport.java) | `resolveSoftRuntime` | `a54f233a1070` | 保留：命名请求与监控默认值的选择 |
| [QuotaReservationSupport.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaReservationSupport.java) | `notApplicable` | `b1f4f3d57156` | 保留：具名构造不适用的预检结果 |
| [TaskConsumerRoutingPolicy.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerRoutingPolicy.java) | `escapeRegex` | `92b5c8f68c12` | 保留：命名主题路由正则转义 |
| [SparkSubmitTaskExecutor.java](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java) | `isLocalPath` | `56aac06fd59f` | 保留：命名本地输出路径条件 |
| [LeaseRenewalSchedulerTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/scheduler/LeaseRenewalSchedulerTest.java) | `objectMap` | `33fe2db0a28c` | 保留：集中测试载荷类型转换并局部抑制泛型告警 |
| [ExportRegionResolver.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/region/ExportRegionResolver.java) | `firstNonBlank` | `4cc33bd536b3` | 保留：命名区域配置的别名优先级 |
| [ConsoleRealtimeEventHub.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/realtime/ConsoleRealtimeEventHub.java) | `normalizeStream` | `47a4ff78b293` | 保留：命名实时流默认名称规则 |
| [ConsoleRealtimeEventHub.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/realtime/ConsoleRealtimeEventHub.java) | `normalizeFilter` | `47a4ff78b293` | 保留：命名实时流筛选条件归一规则 |
| [PrepareStep.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java) | `defaultText` | `1cf66dc8834b` | 保留：命名导出批次文本默认值 |
| [FileGovernanceRepository.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java) | `defaultText` | `2ece931b7b11` | 保留：命名文件治理审计字段回退值 |
| [ActiveTaskLeaseRegistry.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java) | `startExecution` | `f1e6261ab2c4` | 保留：封装租约执行状态的原子转换 |
| [ActiveTaskLeaseRegistry.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java) | `lastConfirmedNanosValue` | `f1e6261ab2c4` | 保留：封装续租时间原子读取 |
| [S3ExportStorage.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/infrastructure/S3ExportStorage.java) | `digestToHex` | `e3ec201d9c9c` | 保留：命名校验摘要的十六进制表示 |
| [SharedConstantsParityTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/SharedConstantsParityTest.java) | `yamlPath` | `52ccf4af50b3` | 保留：命名多语言共享契约的权威文件 |
| [NamespacedObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) | `physicalMarker` | `f0ebb9a7f826` | 保留：命名分页游标到物理命名空间的转换 |
| [NamespacedObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) | `logicalMarker` | `f0ebb9a7f826` | 保留：命名物理分页游标到逻辑命名空间的转换 |
| [ConsoleAiCostService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/ConsoleAiCostService.java) | `unpriced` | `399e48b0b5a1` | 保留：具名表示无定价的预留结果 |
| [QuartzMisfireRecoveryListener.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java) | `isDedicatedRecoveryJob` | `366506667e70` | 保留：命名误触发恢复作业识别规则 |
| [SqlTransformComputeSpec.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java) | `values` | `e023bd74b064` | 保留：命名按指定列提取源行值的过程 |
| [ResultVersionWriter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java) | `buildBusinessKey` | `b8868c529292` | 保留：命名结果版本业务身份构造 |
| [RedisOidcAuthorizationRequestRepository.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/RedisOidcAuthorizationRequestRepository.java) | `validBrowserBinding` | `c4cc6fb570f2` | 保留：命名浏览器绑定令牌格式验证 |
| [DefaultPartitionDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultPartitionDispatchService.java) | `buildContext` | `7b12fdfcc583` | 保留：封装关联派发上下文与分区的构造 |
| [DefaultPartitionDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultPartitionDispatchService.java) | `initialPartitionStatus` | `7b12fdfcc583` | 保留：命名分区初始状态决策 |
| [CapacityProfileServiceTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/capacity/CapacityProfileServiceTest.java) | `eqInt` | `d300e97aa1a6` | 保留：集中参数匹配类型转换以配合原有测试 |
| [DatabaseQuotaRuntimeStateService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java) | `normalizePolicy` | `52651883e3b4` | 已简化：原分支直接承接同一枚举转换结果 |
| [BatchTopics.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java) | `sanitizeTopicSegment` | `46320967644d` | 保留：命名 Kafka 主题片段字符规范 |
| [DefaultSchedulePlanBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/plan/DefaultSchedulePlanBuilder.java) | `clampMaxPartitionCount` | `d114c375582d` | 保留：命名分区数量上限及缺省规则 |
| [FakeBatchPlatform.java](../../sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java) | `asMap` | `2f58c33e8612` | 保留：测试平台解析可变报文的安全快照 |
| [OrchestratorConfigInvalidationSubscriber.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/OrchestratorConfigInvalidationSubscriber.java) | `eventKey` | `acd4a64f4ba0` | 保留：命名缓存失效事件的身份键 |
| [SqlAtomicHandlerTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandlerTest.java) | `ctxNoSql` | `763f963534e9` | 保留：命名无 SQL 测试上下文构造 |
| [KafkaConsumerAuthFailureTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/KafkaConsumerAuthFailureTest.java) | `mockConsumer` | `72974af1a7d7` | 保留：局部封装泛型 mock 的 unchecked 警告 |
| [LoadStepTest.java](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java) | `mockPayload` | `84ff83f5eec5` | 已简化：直接创建局部 mock，移除单次转发 |
| [OkHttpOrchestratorExternalHttpTransportTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransportTest.java) | `selections` | `3a4cd12bec5b` | 保留：封装测试代理选择计数器读取 |
| [DefaultCompensationService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java) | `firstText` | `a35391dbebc0` | 保留：命名重跑批次号优先级回退 |
| [LaunchBatchDayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchBatchDayService.java) | `isMissingLaunchContext` | `36983152976c` | 保留：命名启动上下文缺失判断 |
| [LaunchBatchDayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchBatchDayService.java) | `isIncompleteBatchDay` | `36983152976c` | 保留：命名批次日身份缺失判断 |
| [DefaultTenantConfigPackageExcelService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java) | `requiredHeaders` | `fba6a4b428ec` | 已简化：调用点直接使用原方法 |
| [DefaultTenantConfigPackageExcelService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java) | `fileNameOrDefault` | `fba6a4b428ec` | 保留：命名配置包上传文件名回退 |
| [FileGovernanceLatencyMetrics.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceLatencyMetrics.java) | `text` | `1ef9239b20c4` | 已简化：使用标准库保持空值与字符串转换语义 |
| [ApiKeyVerifier.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifier.java) | `verifyCacheKey` | `edc0680a3842` | 保留：命名验证缓存的密钥摘要及租户身份 |
| [TaskDispatcherTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherTest.java) | `objectMap` | `1d1b6c615f57` | 保留：集中测试载荷类型转换并局部抑制泛型告警 |
| [JsonFixtureRequestSideContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureRequestSideContractTest.java) | `fixturesDir` | `9b317fff942d` | 保留：命名 SDK 请求契约 fixture 路径 |
| [GlobalJobAdmissionGuard.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/GlobalJobAdmissionGuard.java) | `hasHardCap` | `6e45da3ec0e8` | 保留：命名全局并发上限是否启用 |

## 2026-11-01：短断言候选逐项处置台账

固定 10 月 30 日的 321 项短断言语法候选；每项的源文件摘要取建表时当前内容，均已逐项给出处置结论。候选短不意味着无效或应删除。路径分别相对后端或前端仓库。

| 编号 | 仓库 / 文件 | 测试名称 | 当前文件 SHA-256 前 12 位 | 结论 |
|---|---|---|---|---|
| B001 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/page/CursorCodecTest.java` | `shouldReturnNull_whenCursorIsEmpty` | `5e376d3789ce` | 保留：null 与空排序键编码为无游标 |
| B002 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/web/support/WorkflowMermaidRendererTest.java` | `shouldReturnHeaderOnly_whenDetailMissing` | `7d31e8a0febc` | 保留：空 Workflow 详情的图表头输出 |
| B003 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/idempotent/IdempotencyKeyResolverTest.java` | `shouldReturnLiteral_whenNoPlaceholder` | `f0954dc6697b` | 保留：无占位符幂等键原样返回 |
| B004 | `file-batch-system/sdk/typescript/tests/handler.test.ts` | `handler: taskFailure default errorCode = EXECUTION_FAILED` | `b6ea7fc5c20d` | 已增强：独立断言默认错误码协议字面量 |
| B005 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ReadReplicaHappyPathIntegrationTest.java` | `shouldRouteToReplica_whenTransactionReadOnly` | `a54f2e61b6aa` | 保留：真实只读事务路由副本，与主库写事务不同 |
| B006 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/RetryDispatchStepTest.java` | `stage_returnsRetry` | `81d7bfa15a17` | 保留：分发重试阶段的标识路由契约 |
| B007 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `shouldThrowWhenTenantIdIsBlankOnStartDrain` | `6a6890f9370a` | 保留：开始排空时空租户拒绝 |
| B008 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `shouldThrowWhenWorkerCodeIsBlankOnStartDrain` | `6a6890f9370a` | 保留：开始排空时空 Worker 编码拒绝 |
| B009 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `shouldThrowWhenTenantIdIsBlankOnForceOffline` | `6a6890f9370a` | 保留：强制下线时空租户拒绝 |
| B010 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `shouldThrowWhenWorkerCodeIsBlankOnListClaimedTasks` | `6a6890f9370a` | 保留：查询认领任务时空 Worker 编码拒绝 |
| B011 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `warmup_throwsWhenTenantBlank` | `6a6890f9370a` | 保留：预热时空租户拒绝 |
| B012 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodecTest.java` | `shouldParsePlainFields` | `6e81291668c1` | 保留：分隔、引号、空尾或自定义分隔符各有边界 |
| B013 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodecTest.java` | `shouldParseQuotedFieldContainingDelimiter` | `6e81291668c1` | 保留：分隔、引号、空尾或自定义分隔符各有边界 |
| B014 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodecTest.java` | `shouldYieldEmptyTrailingField` | `6e81291668c1` | 保留：分隔、引号、空尾或自定义分隔符各有边界 |
| B015 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodecTest.java` | `shouldSupportCustomDelimiter` | `6e81291668c1` | 保留：分隔、引号、空尾或自定义分隔符各有边界 |
| B016 | `file-batch-system/batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskReportBatchTest.java` | `reconcilesEachResponseIdentity` | `3fa12c4bae0c` | 保留：参数化覆盖批量响应乱序、外来、重复、缺失与格式异常 |
| B017 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/infrastructure/ProcessTaskExecutorTest.java` | `shouldReportProcessTaskType_whenTaskTypeQueried` | `8ef9eee25d7f` | 保留：PROCESS 执行器注册类型 |
| B018 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectRetryWhenDryRunPayloadUnsafe` | `4ae0d6d3989a` | 保留：真实数据库演练任务非法载荷多种边界及不变状态 |
| B019 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectRetryWhenFormalPayloadClaimsDryRun` | `4ae0d6d3989a` | 保留：正式任务被错误标为演练的相反方向 |
| B020 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRollbackFileRedispatchWhenParentIsPaused` | `4ae0d6d3989a` | 保留：父作业暂停导致重派失败及回滚 |
| B021 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRollbackFileRedispatchWhenTaskCannotReset` | `4ae0d6d3989a` | 保留：任务非失败状态阻止重派及回滚 |
| B022 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectFileRedispatchWhenExternalOutcomeIsUnknown` | `4ae0d6d3989a` | 保留：任务外部结果未知时的重派阻断 |
| B023 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectFileRedispatchWhenRecordIsNotKnownFailed` | `4ae0d6d3989a` | 保留：不同投递记录状态及未知结果的阻断 |
| B024 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectFileRedispatchWhenSingleTaskHasNoFileAssociation` | `4ae0d6d3989a` | 保留：任务缺失文件关联时阻断 |
| B025 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectRedispatchWhenPayloadTargetDiffers` | `4ae0d6d3989a` | 保留：普通和 bundle 文件或渠道目标不一致 |
| B026 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/ValidateStepTest.java` | `shouldReturnValidateStage` | `bc6da7e6c7f2` | 保留：IMPORT VALIDATE 阶段标识 |
| B027 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/mq/BatchTopicResolverTest.java` | `shouldReturnNull_whenWorkerTypeUnsupported` | `124c230cc946` | 保留：未知 Worker 类型不生成主题 |
| B028 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/WorkflowConditionEvaluatorTest.java` | `shouldMatchEqualityWithDoubleEquals` | `9108c174484f` | 保留：双等号表达式的独立兼容语法 |
| B029 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/WorkflowConditionEvaluatorTest.java` | `shouldReturnFalseWhenPayloadKeyMissing` | `9108c174484f` | 保留：条件载荷缺失键时不匹配 |
| B030 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/FeedbackStepTest.java` | `shouldReturnStageFeedback_whenStageAccessed` | `be24d611f98d` | 保留：PROCESS FEEDBACK 阶段标识 |
| B031 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/BatchPlatformClientConfigValidationTest.java` | `shouldAcceptAllDefaults` | `6c3504d3219f` | 保留：必需连接配置齐全时默认时限配置应有效 |
| B032 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowWaitSensorSpecValidatorTest.java` | `shouldIgnoreNonWaitNode` | `15e00f1b019f` | 保留：非 WAIT 节点不运行传感器校验 |
| B033 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowWaitSensorSpecValidatorTest.java` | `shouldRejectUnknownSensorType` | `15e00f1b019f` | 保留：未知 WAIT 类型返回专属错误码 |
| B034 | `file-batch-system/examples/task-spi-plugin/sftp-push-executor/src/test/java/io/github/pinpols/batch/ext/sftp/SftpPushTaskExecutorTest.java` | `shouldReturnSftpPushType_whenTaskTypeQueried` | `1bc0106e7f04` | 保留：扩展 SFTP 执行器注册类型 |
| B035 | `file-batch-system/examples/task-spi-plugin/sftp-push-executor/src/test/java/io/github/pinpols/batch/ext/sftp/SftpPushTaskExecutorTest.java` | `shouldDeclareNetAndDiskCapability_whenCapabilityQueried` | `1bc0106e7f04` | 保留：SFTP 所需网络与磁盘能力 |
| B036 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/StartupSelfCheckIntegrationTest.java` | `shouldExposeSelfCheckComponent_whenContextStarts` | `65710ad365cf` | 保留：单独定位启动自检 Bean 装配 |
| B037 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidBizDateValidatorTest.java` | `shouldTrimWhitespace_whenValidating` | `6874e286d40a` | 保留：业务日期去除前后空白后校验 |
| B038 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultCompensationServiceTest.java` | `shouldThrowWhenCommandIsNull` | `6812f4d2c821` | 保留：补偿请求为 null 时拒绝 |
| B039 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/ConcurrentTaskClaimIntegrationTest.java` | `assignWorker_onlyOneWorkerWins_acrossRepeatedLaunches` | `f60ad57eae7b` | 保留：真实并发认领在多个新批次重复验证唯一胜出 |
| B040 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/BizDateArithmeticTest.java` | `shouldReturnNull_whenNamedOffsetUnknown` | `1cfd3f1558d3` | 保留：未知命名日期偏移返回 null |
| B041 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/BizDateArithmeticTest.java` | `shouldReturnEmpty_whenRangeTagUnknown` | `1cfd3f1558d3` | 保留：未知日期范围标签返回空范围 |
| B042 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/BizDateArithmeticTest.java` | `shouldReturnEmpty_whenBizDayCountExceedsCap` | `1cfd3f1558d3` | 保留：超限营业日范围拒绝扩张 |
| B043 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportTaskExecutorTest.java` | `shouldReportImportTaskType_whenReadingTaskType` | `044553edd04d` | 保留：IMPORT 执行器注册类型 |
| B044 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/AdminTestDataCleanupControllerIntegrationTest.java` | `shouldRejectAmbiguousInstanceId_whenNeighborIsInSameTenant` | `d63870c9ae14` | 保留：同租户重号时清理隔离 |
| B045 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/AdminTestDataCleanupControllerIntegrationTest.java` | `shouldRejectAmbiguousInstanceId_whenNeighborIsInAnotherTenant` | `d63870c9ae14` | 保留：跨租户重号时清理隔离 |
| B046 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/AdminTestDataCleanupControllerIntegrationTest.java` | `shouldRejectAmbiguousInstanceId_whenNeighborIsArchived` | `d63870c9ae14` | 保留：冷历史重号时清理隔离 |
| B047 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/i18n/MessagesPropertiesParityTest.java` | `shouldRejectDuplicateKeysAndBlankValues` | `cf23a9b3dd11` | 保留：独立中英文资源文件的重复键及空值约束 |
| B048 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicIsolationStartupCheckTest.java` | `noDualUseEnabled_noThrow` | `90a0df12cfd7` | 保留：无双用途执行器时自检放行 |
| B049 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/RetryScheduleStatusTest.java` | `shouldContainSixValues` | `d3340bcde9d7` | 保留：枚举数量约束与各编码检查互补 |
| B050 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/WorkflowJoinModeTest.java` | `fromCodeOrDefault_unknownCode_returnsAll` | `73c0de5bde7a` | 保留：未知 Join 模式的默认解析，与抛错入口不同 |
| B051 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/service/BatchObjectCryptoServiceTest.java` | `decryptNullBytes_shouldReturnNull` | `a7b5d81bfbd0` | 保留：null 解密输入返回 null |
| B052 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/service/BatchObjectCryptoServiceTest.java` | `decryptEmptyBytes_shouldReturnEmpty` | `a7b5d81bfbd0` | 保留：空数组解密输入返回空数组 |
| B053 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/service/BatchObjectCryptoServiceTest.java` | `resolveKeyRef_fallsBackToDefault_whenSecurityMapEmpty` | `a7b5d81bfbd0` | 保留：安全上下文为空时选择默认密钥引用 |
| B054 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationCoercionsTest.java` | `decimalValue_returnsNull_forNonNumericText` | `a8dc48db19c1` | 保留：非数字输入的转换回退 |
| B055 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/task/SdkTaskResultTest.java` | `shouldReturnEmptyOutput_whenOutputAbsent` | `03a57a6c41d7` | 保留：SDK 空输出契约 |
| B056 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/BatchTriggerApplicationIntegrationTest.java` | `contextLoads_succeeds` | `c883e0501dd9` | 保留：触发器启动装配 Quartz 和迁移回调 |
| B057 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/BatchTriggerApplicationIntegrationTest.java` | `quartzScheduler_isStarted` | `c883e0501dd9` | 保留：Quartz 已启动状态与装配不同 |
| B058 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessRangeSliceTest.java` | `shouldSliceLosslessly_whenLastLineHasNoTrailingNewline` | `3947f1f65bf1` | 保留：末行、空行和 CRLF 为不同切片输入 |
| B059 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessRangeSliceTest.java` | `shouldSliceLosslessly_whenEmptyLinesPresent` | `3947f1f65bf1` | 保留：末行、空行和 CRLF 为不同切片输入 |
| B060 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessRangeSliceTest.java` | `shouldSliceLosslessly_whenCrlfLineEndings` | `3947f1f65bf1` | 保留：末行、空行和 CRLF 为不同切片输入 |
| B061 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/ops/service/WorkerCompatibilityEvaluatorTest.java` | `shouldReportOk_whenMajorOneFormatsVary` | `ca646f6a86a0` | 保留：参数化覆盖 SDK 主版本格式及前缀后缀 |
| B062 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/OtelTraceContextTest.java` | `shouldReturnNull_whenNoActiveSpan` | `26c97c53ff6b` | 已增强：显式空上下文并恢复线程上下文 |
| B063 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/OtelTraceContextTest.java` | `shouldFallBackToGeneratedId_whenNoActiveSpan` | `26c97c53ff6b` | 已增强：显式空上下文验证追踪号回退 |
| B064 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidResourceCodeTest.java` | `shouldReject_whenCodeNull` | `d82262411118` | 保留：null、空串和空白输入各自受验证约束 |
| B065 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidResourceCodeTest.java` | `shouldReject_whenCodeEmpty` | `d82262411118` | 保留：null、空串和空白输入各自受验证约束 |
| B066 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidResourceCodeTest.java` | `shouldReject_whenCodeBlank` | `d82262411118` | 保留：null、空串和空白输入各自受验证约束 |
| B067 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingPartitionMaintenanceTest.java` | `shouldKeepRowsUntilDeletionIsEnabled` | `074590cc5cc9` | 已增强：检查默认开关和无删除操作 |
| B068 | `file-batch-system/batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/infrastructure/ExportTaskExecutorTest.java` | `shouldReportExportTaskType_whenReadingTaskType` | `193944a1840a` | 保留：EXPORT 执行器注册类型 |
| B069 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/config/HikariPgSessionSupportTest.java` | `shouldTruncateApplicationName_whenExceedingSixtyThreeChars` | `d99161ecff6b` | 已增强：校验截断内容及长度 |
| B070 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/scheduler/DryRunSchedulingPriorityTest.java` | `shouldKeepConfiguredPriority_whenFormalTask` | `a9638875be36` | 保留：正式任务显式优先级 |
| B071 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/scheduler/DryRunSchedulingPriorityTest.java` | `shouldKeepNullPriority_whenFormalTaskHasNoPriority` | `a9638875be36` | 保留：正式任务缺省优先级 |
| B072 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/config/ConsoleOidcPropertiesTest.java` | `shouldAllowEmptySettings_whenOidcIsDisabled` | `1f01b25a2311` | 保留：关闭 OIDC 时允许空配置 |
| B073 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidTenantIdTest.java` | `shouldReject_whenTenantIdNull` | `0c5b75e92a48` | 保留：必填与长度边界各自独立 |
| B074 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidTenantIdTest.java` | `shouldReject_whenTenantIdBlank` | `0c5b75e92a48` | 保留：必填与长度边界各自独立 |
| B075 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidTenantIdTest.java` | `shouldAccept_whenTenantIdAtMaxLength` | `0c5b75e92a48` | 保留：必填与长度边界各自独立 |
| B076 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidTenantIdTest.java` | `shouldReject_whenTenantIdExceedsMaxLength` | `0c5b75e92a48` | 保留：必填与长度边界各自独立 |
| B077 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/DispatchTaskExecutorTest.java` | `shouldReportDispatchTaskType_whenTaskTypeQueried` | `2706d7128038` | 保留：DISPATCH 执行器注册类型 |
| B078 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/captcha/TencentCaptchaVerifierTest.java` | `shouldReportTencent_whenQueryingProviderName` | `383d082af595` | 保留：腾讯验证码提供方标识 |
| B079 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReturnNoneWhenValueIsNull` | `e92c1207c211` | 保留：空配额策略回退 NONE |
| B080 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReturnNoneWhenValueIsBlank` | `e92c1207c211` | 保留：空白配额策略回退 NONE |
| B081 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReturnNoneWhenValueIsUnknown` | `e92c1207c211` | 保留：未知配额策略回退 NONE |
| B082 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReportNoneAsNotRuntimeManaged` | `e92c1207c211` | 保留：NONE 策略不由运行时托管 |
| B083 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReportCalendarDayAsRuntimeManaged` | `e92c1207c211` | 保留：日历日配额策略由运行时托管 |
| B084 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReportSlidingWindowAsRuntimeManaged` | `e92c1207c211` | 保留：滑动窗口配额策略由运行时托管 |
| B085 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/DefaultPrioritySchedulerTest.java` | `shouldReturnDefaultPriorityFiveWhenRequestIsNull` | `1551e3019e08` | 保留：空调度请求的默认优先级 |
| B086 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/DefaultPrioritySchedulerTest.java` | `shouldReturnMediumBandWhenPriorityIsNull` | `1551e3019e08` | 保留：空优先级的默认优先级档位 |
| B087 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java` | `shouldReturnLoadStage` | `84ff83f5eec5` | 保留：IMPORT LOAD 阶段标识 |
| B088 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/security/CredentialEnvResolverTest.java` | `returnsNull_whenNull` | `6767dc650826` | 保留：空凭据引用的解析结果 |
| B089 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/CatchUpPolicyTypeTest.java` | `fromCodeOrDefault_unknownCode_returnsNone` | `f9104d818911` | 保留：未知补跑策略的默认解析 |
| B090 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutorTest.java` | `shouldReturnSingleStatement_whenNoSemicolon` | `07cfc21da3de` | 保留：无分号 SQL 不被错误拆分 |
| B091 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchManifestSupportTest.java` | `shouldKeepNull_whenBusinessDateMissing` | `3d3b1401d5a6` | 保留：无业务日的 manifest 字段仍为 null |
| B092 | `file-batch-system/batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/PipelineWorkerAtomicIsolationOptInArchTest.java` | `shouldOptInIsolationCheck_whenImportWorker` | `4f2ee2f001ab` | 保留：IMPORT Worker 必须注册隔离启动检查 |
| B093 | `file-batch-system/batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/PipelineWorkerAtomicIsolationOptInArchTest.java` | `shouldOptInIsolationCheck_whenExportWorker` | `4f2ee2f001ab` | 保留：EXPORT Worker 必须注册隔离启动检查 |
| B094 | `file-batch-system/batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/PipelineWorkerAtomicIsolationOptInArchTest.java` | `shouldOptInIsolationCheck_whenProcessWorker` | `4f2ee2f001ab` | 保留：PROCESS Worker 必须注册隔离启动检查 |
| B095 | `file-batch-system/batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/PipelineWorkerAtomicIsolationOptInArchTest.java` | `shouldOptInIsolationCheck_whenDispatchWorker` | `4f2ee2f001ab` | 保留：DISPATCH Worker 必须注册隔离启动检查 |
| B096 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_loopback_ipv4` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B097 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_loopback_ipv6` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B098 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_link_local_metadata_ipv4` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B099 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_link_local_metadata_ipv4_mapped_ipv6` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B100 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_private_class_a` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B101 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_private_class_c` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B102 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_any_local` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B103 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `accepts_public_literal` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B104 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `accepts_public_literal_ipv6` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B105 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_ipv6_ula_aws_metadata` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B106 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_ipv6_ula_fc00_range` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B107 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_ipv6_ula_fd_range` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B108 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_ipv6_link_local` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B109 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/WorkerFingerprintTest.java` | `shouldReturnCurrentJvmPid_whenReadingProcessId` | `90663e395c35` | 保留：Worker 进程号与当前 JVM 进程身份一致 |
| B110 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStepTest.java` | `stage_returnsAck` | `0d587c9aa46b` | 保留：分发 ACK 阶段标识 |
| B111 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/DispatchSpiEquivalenceIntegrationTest.java` | `shouldContainDispatchTaskType_whenListingRegisteredTypes` | `ba6650656b87` | 保留：DISPATCH Worker 的 SPI 注册 |
| B112 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/CompensateDispatchStepTest.java` | `stage_returnsCompensate` | `dfb2f2e5ce08` | 保留：分发 COMPENSATE 阶段标识 |
| B113 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/TaskStatusTest.java` | `shouldContainSevenValues` | `190bbfaa9d25` | 保留：任务状态数量约束与各状态编码断言互补 |
| B114 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/CompleteDispatchStepTest.java` | `stage_returnsComplete` | `98ffb7300a7f` | 保留：分发 COMPLETE 阶段标识 |
| B115 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/config/ImportScannerPropertiesTest.java` | `doneFileSuffix_defaultsToDotDone` | `2e39b538462b` | 保留：导入完成文件默认后缀 |
| B116 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/config/ImportScannerPropertiesTest.java` | `doneFileNaming_defaultsToAppendFullName` | `2e39b538462b` | 保留：导入完成文件默认命名策略 |
| B117 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/ContentMaskingUtilsTest.java` | `shouldApplyTextRulesToMalformedJsonLikeInput` | `51b6c32ad14c` | 保留：损坏 JSON 文本中的敏感字段脱敏 |
| B118 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/integration/BatchWorkerAtomicApplicationIntegrationTest.java` | `shouldExposeApplicationContext_whenStarted` | `3592df593af6` | 已删除：同类端口验证已覆盖上下文启动和注入 |
| B119 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/BatchWorkerDispatchApplicationIntegrationTest.java` | `shouldLoadApplicationContext_whenStarted` | `c8a42a0b4c7c` | 已删除：同类端口验证已覆盖上下文启动和注入 |
| B120 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/job/support/BuiltinTaskTypeGuardTest.java` | `shouldAllowType_whenJobTypeNullOrBlank` | `90317f255af8` | 保留：内置类型入口对缺失类型的既有行为 |
| B121 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/job/support/BuiltinTaskTypeGuardTest.java` | `shouldAllowBuiltinType_whenAnyGrantedRoleIsPlatformAdmin` | `90317f255af8` | 保留：多角色包含平台管理员时的授权 |
| B122 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherClaimJitterTest.java` | `shouldReturnZero_whenBaseDelayIsZero` | `d5f19b5c5db1` | 保留：零基础延迟下不得生成抖动 |
| B123 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/service/ConsoleAiPromptGuardTest.java` | `shouldThrowBizExceptionForNullPrompt` | `c5fe12376b12` | 已增强：空提示词拒绝时验证参数错误码及必填国际化 key |
| B124 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/service/ConsoleAiPromptGuardTest.java` | `shouldThrowBizExceptionForBlankPrompt` | `c5fe12376b12` | 已增强：空白提示词拒绝时验证参数错误码及必填国际化 key |
| B125 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/RetryPolicyTypeTest.java` | `shouldContainThreeValues` | `4d4163c5e874` | 保留：重试策略枚举项数量约束 |
| B126 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStreamEligibilityTest.java` | `shouldAllowDirectStreaming_whenTemplateMissing` | `ce656393c7c5` | 保留：无模板时直接流式导入 |
| B127 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/DeadLetterReplayStatusTest.java` | `shouldContainFiveValues` | `563648867408` | 保留：死信重放状态数量约束 |
| B128 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/ImportSpiEquivalenceIntegrationTest.java` | `shouldRegisterImportTaskType_whenContextLoads` | `9ec8f7170391` | 保留：IMPORT Worker 的 SPI 注册 |
| B129 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/OrchestratorGracefulShutdownTest.java` | `shouldNotBeDrainingInitially` | `c2e301e1e6a9` | 保留：编排器关闭协调初始非排空状态 |
| B130 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/JobTypeTest.java` | `shouldNotExposeWorkflowAsExecutableJobType` | `577ba0414ac9` | 保留：WORKFLOW 不作为可执行 Job 类型 |
| B131 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/JobTypeTest.java` | `shouldProvideNonNullBatchType_whenEnumeratingEveryJobType` | `577ba0414ac9` | 保留：逐枚举验证批类型映射非空 |
| B132 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/jdbc/JdbcMappedSqlValidatorTest.java` | `shouldNormalizeValidIdentifierToLowerCase` | `cf523ed482a4` | 保留：SQL 标识符标准化 |
| B133 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/jdbc/JdbcMappedSqlValidatorTest.java` | `shouldQuotePgIdentifier` | `cf523ed482a4` | 保留：PostgreSQL 标识符引用格式 |
| B134 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/spi/task/TaskResultTest.java` | `shouldDefaultOutputToEmptyMap_whenOutputAbsent` | `1e3f863fb77c` | 保留：Worker 通用任务结果的空输出契约 |
| B135 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/sensor/HttpPollSensorPolicyTest.java` | `matchExpr_missingPath_returnsFalseNotThrow` | `679e2ea9eec6` | 保留：HTTP 传感表达式缺失路径返回 false |
| B136 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowGraphValidatorTest.java` | `shouldReportClean_whenWorkflowIdMissing` | `ba75a942f4bc` | 已增强：缺失 Workflow ID 不产生错误图，且不查询节点、连线和作业 |
| B137 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/constants/BatchFileConstantsPartitionTagTest.java` | `shouldInsertTagBeforeExtension` | `8f05cc000053` | 保留：文件扩展名前插入分片标记 |
| B138 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/constants/BatchFileConstantsPartitionTagTest.java` | `shouldAppendTag_whenNoExtension` | `8f05cc000053` | 保留：无扩展名文件追加分片标记 |
| B139 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/constants/BatchFileConstantsPartitionTagTest.java` | `shouldReturnUnchanged_whenSinglePartition` | `8f05cc000053` | 保留：单分片文件名不变 |
| B140 | `file-batch-system/batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/ExportSpiEquivalenceIntegrationTest.java` | `shouldRegisterExportTaskType_whenContextLoads` | `54fb45624b71` | 保留：EXPORT Worker 的 SPI 注册 |
| B141 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutorTest.java` | `taskType_isSparkSubmit` | `fa2b1abf6088` | 保留：Spark 执行器任务类型 |
| B142 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/MisfireHandlerIntegrationTest.java` | `shouldWireMisfireHandlerBean` | `ab7ffc7ad343` | 保留：misfire handler 实现类型的装配契约 |
| B143 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/MisfireHandlerIntegrationTest.java` | `shouldLoadQuartzLaunchJobForMisfireAuditPath` | `ab7ffc7ad343` | 保留：QuartzLaunchJob 在启动上下文中的单独装配检查 |
| B144 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/MisfireHandlerIntegrationTest.java` | `shouldNotThrowWhenHandlingMisfire` | `ab7ffc7ad343` | 已增强：未知触发名的 misfire 调用不中断调度并记录对应触发名 |
| B145 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/TenantScopeTest.java` | `shouldReturnTenantId_whenNonBlank` | `ceaad68afe0c` | 保留：非空租户 ID 原样返回 |
| B146 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/infrastructure/verifier/ProcessPublishedCountVerifierTest.java` | `shouldPass_whenPublishedCountPositive` | `09cd8a28d4a1` | 保留：数字与数字字符串为不同输入类型 |
| B147 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/infrastructure/verifier/ProcessPublishedCountVerifierTest.java` | `shouldPass_whenPublishedCountIsNumericString` | `09cd8a28d4a1` | 保留：数字与数字字符串为不同输入类型 |
| B148 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/support/cache/ConsoleQueryCacheServiceTest.java` | `keySegment_keepsShortSafeValuesReadable` | `fc94f54ae1c7` | 保留：安全短缓存键的可读性 |
| B149 | `file-batch-system/batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/stage/format/GenerateCursorCodecTest.java` | `roundTrip_preservesBoolean` | `e4cd42db83d8` | 保留：游标 Boolean 类型往返 |
| B150 | `file-batch-system/sdk/typescript/tests/decide-backpressure.test.ts` | `decideBackpressure default paused=false: no resume in band` | `c76ed32647cb` | 保留：未暂停时背压区间不触发恢复 |
| B151 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/IdGeneratorTest.java` | `shouldReturn32HexChars_whenGeneratingTraceId` | `c227afd20a8d` | 保留：一般追踪号格式契约，与明确无 Span 场景不同 |
| B152 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/IdGeneratorTest.java` | `shouldReturn32HexChars_whenGeneratingInvocationId` | `c227afd20a8d` | 保留：调用号格式契约，与追踪号是不同入口 |
| B153 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelTypePolicyTest.java` | `shouldReturnCanonicalType_whenInputHasSpacesAndLowercase` | `daffd532ab83` | 保留：渠道类型空格与大小写归一 |
| B154 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelTypePolicyTest.java` | `shouldReturnEmpty_whenTypeUnknown` | `daffd532ab83` | 保留：未知渠道类型不被接受 |
| B155 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/stateful/StatefulBackendIdentityTest.java` | `shouldPreserveResourceCase_whenBuildingBackendIdentity` | `d867dbc6a70d` | 保留：参数化验证大小写敏感后端资源身份 |
| B156 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/ConsolePasswordHasherTest.java` | `shouldMatchFlywaySeedArgon2Hash` | `bb7e3f7e298f` | 已更名为 `shouldMatchKnownArgon2Hash`：固定 Argon2id 样本验证；该用例不读取 Flyway 迁移 |
| B157 | `file-batch-system/sdk/typescript/tests/shared-constants-parity.test.ts` | `schema_versions_supported parity` | `926060cb2022` | 保留：TS schema 版本与独立 YAML 契约一致 |
| B158 | `file-batch-system/sdk/typescript/tests/shared-constants-parity.test.ts` | `worker_runtime_states parity` | `926060cb2022` | 保留：TS Worker 运行状态与独立 YAML 契约一致 |
| B159 | `file-batch-system/sdk/typescript/tests/shared-constants-parity.test.ts` | `sensitive_keywords parity` | `926060cb2022` | 保留：TS 敏感词清单与独立 YAML 契约一致 |
| B160 | `file-batch-system/sdk/typescript/tests/shared-constants-parity.test.ts` | `task_statuses parity` | `926060cb2022` | 保留：TS 任务状态与独立 YAML 契约一致 |
| B161 | `file-batch-system/sdk/typescript/tests/shared-constants-parity.test.ts` | `report_error_codes parity` | `926060cb2022` | 保留：TS 上报错误码与独立 YAML 契约一致 |
| B162 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/BatchWorkerImportApplicationIntegrationTest.java` | `shouldLoadContext_whenApplicationStarts` | `59f99f546a77` | 已删除：同类端口验证已覆盖上下文启动和注入 |
| B163 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/logging/LogSanitizerTest.java` | `shouldReplaceEveryUnicodeLineBreakWithUnderscore` | `a504808b2d43` | 保留：多种 Unicode 换行日志注入清理 |
| B164 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/TaskTypeDescriptorAssemblyTest.java` | `shouldReturnNullDescriptor_whenHandlerDoesNotDeclare` | `d84813d775d5` | 保留：SDK handler 未声明 descriptor 的缺省值 |
| B165 | `file-batch-system/batch-test-support/src/test/java/io/github/pinpols/batch/common/rls/RlsClosedWorldCheckIntegrationTest.java` | `shardIdentity_defaultExempted` | `5f088a3860b8` | 保留：分片身份表 RLS 默认例外配置 |
| B166 | `file-batch-system/batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/support/ActiveTaskLeaseRegistryTest.java` | `shouldReturnEmptySnapshotWhenNoLeases` | `6384309003f6` | 保留：无租约时续租快照为空 |
| B167 | `file-batch-system/batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/checkpoint/CheckpointPartitionGuardTest.java` | `nullMeansSinglePartition_allow` | `20a6d3dc5cec` | 保留：空分区数按单分区处理 |
| B168 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/CorrelationIdsTest.java` | `normalize_trimsAndKeepsSafeCharacters` | `41f677ca52aa` | 保留：规范化、非法字符、长度或回退规则 |
| B169 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/CorrelationIdsTest.java` | `normalize_replacesUnsafeCharactersAndControls` | `41f677ca52aa` | 保留：规范化、非法字符、长度或回退规则 |
| B170 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/CorrelationIdsTest.java` | `normalize_capsAtHeaderColumnLength` | `41f677ca52aa` | 保留：规范化、非法字符、长度或回退规则 |
| B171 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/CorrelationIdsTest.java` | `normalize_usesFallbackWhenInputBlankOrInvalid` | `41f677ca52aa` | 保留：规范化、非法字符、长度或回退规则 |
| B172 | `file-batch-system/sdk/typescript/tests/kafka-offset-policy.test.ts` | `loweredCeiling: first withhold sets the ceiling to the record offset` | `d196f165bac9` | 保留：首次阻止提交建立 offset 下界 |
| B173 | `file-batch-system/sdk/typescript/tests/kafka-offset-policy.test.ts` | `commitBlockedByWithheld: no ceiling → never blocked` | `d196f165bac9` | 保留：无 offset 上界时不阻止提交 |
| B174 | `file-batch-system/sdk/typescript/tests/kafka-offset-policy.test.ts` | `resolveFromBeginning: defaults to false (latest), parity with the 4 other SDKs` | `d196f165bac9` | 保留：Kafka 缺省从最新位置消费 |
| B175 | `file-batch-system/sdk/typescript/tests/kafka-offset-policy.test.ts` | `resolveFromBeginning: an explicit value always wins` | `d196f165bac9` | 保留：显式 Kafka 起始策略优先于默认值 |
| B176 | `file-batch-system/batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidatorTest.java` | `validate_ignoresParameterTextOutsideSqlParameters` | `837e408afa03` | 保留：SQL 参数之外的相似文本不受参数校验干扰 |
| B177 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/service/LaunchParamResolverTest.java` | `shouldReturnNullForNullDeadline` | `9b170a4af1f3` | 保留：缺失截止时间解析结果 |
| B178 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/application/TriggerOutboxReleaseGovernorTest.java` | `disabled_returnsConfiguredLimitWithoutReadingLag` | `5d5e12952331` | 已增强：断言禁用时无 lag 读取 |
| B179 | `file-batch-system/batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/BatchWorkerExportApplicationIntegrationTest.java` | `shouldLoadContext_whenApplicationStarts` | `5e5ba8e238e7` | 已删除：同类端口验证已覆盖上下文启动和注入 |
| B180 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/PartitionStatusTest.java` | `shouldContainNineValues` | `c409ffac974f` | 保留：分区状态枚举数量约束 |
| B181 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/PrepareDispatchStepTest.java` | `stage_returnsPrepare` | `ff63ff8c51ee` | 保留：分发 PREPARE 阶段标识 |
| B182 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/storage/S3ObjectStoreExceptionMappingTest.java` | `shouldReportMissing_whenKeyDoesNotExist` | `a29c22ebfdbb` | 保留：S3 不存在与无权限错误的区别 |
| B183 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/infrastructure/TriggerGracefulShutdownTest.java` | `shouldNotBeDrainingInitially` | `c49897f6b707` | 保留：Trigger 优雅停机初始非排空状态 |
| B184 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/ProcessSpiEquivalenceIntegrationTest.java` | `shouldContainProcessTaskType_whenListingRegisteredTypes` | `3cd79f380cd5` | 保留：PROCESS Worker 的 SPI 注册 |
| B185 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStepTest.java` | `stage_returnsDispatch` | `7d330a4127bf` | 保留：分发 DISPATCH 阶段标识 |
| B186 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnEmptyWhenDefinitionIdIsNull` | `752a065113d4` | 保留：缺少定义 ID 不解析后续节点 |
| B187 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnEmptyWhenCurrentNodeCodeIsBlank` | `752a065113d4` | 保留：空节点编码不解析后续节点 |
| B188 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnFalseWhenWorkflowRunIdIsNull` | `752a065113d4` | 保留：缺少 Workflow 运行 ID 不允许派发 |
| B189 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnFalseWhenDefinitionIdIsNull` | `752a065113d4` | 保留：缺少定义 ID 不允许派发 |
| B190 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnFalseWhenNodeCodeIsBlank` | `752a065113d4` | 保留：空节点编码不允许派发 |
| B191 | `file-batch-system/batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxPgIntegrationTest.java` | `claimNext_returnsEmptyWhenNothingDue` | `83910f28a209` | 保留：真实数据库无到期报告时不认领 |
| B192 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/ops/infrastructure/TriggerProxyJsonContractTest.java` | `shouldReturnEmptyList_whenDownstreamRowsAreNull` | `7f2044c5bd64` | 保留：下游空列表的租户过滤结果 |
| B193 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelCircuitBreakerTest.java` | `shouldAllowWhenNoFailuresRecorded` | `386f4ca7ce35` | 保留：无故障的渠道熔断器初始状态 |
| B194 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelCircuitBreakerTest.java` | `shouldIsolateFailuresByChannelKey` | `386f4ca7ce35` | 已增强：同时验证故障渠道已熔断和正常渠道仍放行 |
| B195 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/statemachine/DefaultLifecycleEventMapperTest.java` | `shouldResolveEnumTargetAsState` | `bfb40b8c8197` | 保留：枚举状态到目标状态的映射 |
| B196 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/statemachine/DefaultLifecycleEventMapperTest.java` | `shouldAllowTerminalSelfLoop` | `bfb40b8c8197` | 保留：终态自环幂等 |
| B197 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/ConfigLifecycleStatusTest.java` | `shouldContainFiveValues` | `5a72fc143e66` | 保留：配置生命周期状态数量约束 |
| B198 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/ProcessStagingCleanupYamlIntegrationTest.java` | `shouldDisableStagingOrphanCleaner_whenTestProfileActive` | `15dcdec92ee6` | 保留：测试 profile 关闭 PROCESS 清理调度 |
| B199 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifierTest.java` | `shouldRejectRequest_whenSignatureHeaderMissing` | `eabe044a51e5` | 保留：缺少签名请求头时拒绝 |
| B200 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRoutingPolicyTest.java` | `shouldReturnNullForNullCandidates` | `423801bfd418` | 保留：null Worker 候选 |
| B201 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRoutingPolicyTest.java` | `shouldReturnNullForEmptyCandidates` | `423801bfd418` | 保留：空 Worker 候选集合 |
| B202 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/CommitStepTest.java` | `shouldReturnStageCommit_whenStageAccessed` | `86e923aa9c7e` | 保留：PROCESS COMMIT 阶段标识 |
| B203 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutorHardeningTest.java` | `shouldClassifyAsDdl_whenStatementIsDoBlock` | `c758caca1909` | 保留：大写 PostgreSQL DO 块为 DDL |
| B204 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutorHardeningTest.java` | `shouldClassifyAsDdl_whenDoBlockLowercase` | `c758caca1909` | 保留：小写 PostgreSQL DO 块为 DDL |
| B205 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/param/WorkflowDefinitionWriteParamsTest.java` | `shouldRejectMissingRequiredField` | `31524b01e74d` | 保留：参数化检查 Workflow 写入的各必填字段 |
| B206 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowFanOutSupportTest.java` | `parseSpec_nullWhenItemsExprBlank` | `c3c26efdd198` | 保留：空 fan-out 表达式不生成展开配置 |
| B207 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/FeedbackStepTest.java` | `shouldReturnStageFeedback_whenStageAccessed` | `604b15ab5922` | 保留：IMPORT FEEDBACK 阶段标识 |
| B208 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/security/DnsResolveGuardTest.java` | `shouldAllow_whenDocumentationGlobalIpv6` | `f7138dc50641` | 保留：文档用途 IPv6 的现行放行契约 |
| F001 | `batch-console/src/utils/aiMessagePresentation.test.ts` | `keeps approved answer content` | `b8387a88bec5` | 保留：批准的 AI 回答正文应展示 |
| F002 | `batch-console/src/utils/lastApiMeta.test.ts` | `stores a valid meta object` | `92c85329e965` | 保留：有效值、null 和 undefined 独立输入 |
| F003 | `batch-console/src/utils/lastApiMeta.test.ts` | `sets value to null when called with undefined` | `92c85329e965` | 保留：有效值、null 和 undefined 独立输入 |
| F004 | `batch-console/src/utils/lastApiMeta.test.ts` | `sets value to null when called with null` | `92c85329e965` | 保留：有效值、null 和 undefined 独立输入 |
| F005 | `batch-console/src/utils/datetime.test.ts` | `handles epoch ms number` | `3a3cb1ad5298` | 已增强：保留默认格式检查并用 UTC 校验 epoch 实际值 |
| F006 | `batch-console/src/utils/datetime.test.ts` | `returns raw string for invalid date` | `3a3cb1ad5298` | 保留：无效日期按原文回退 |
| F007 | `batch-console/src/utils/errorCatalog.test.ts` | `falls back to network` | `f59ce4df4ec2` | 保留：网络错误与无匹配路径不同 |
| F008 | `batch-console/src/utils/errorCatalog.test.ts` | `returns undefined when nothing matches` | `f59ce4df4ec2` | 保留：网络错误与无匹配路径不同 |
| F009 | `batch-console/src/utils/formatAiUsd.test.ts` | `uses cents for zero and ordinary amounts` | `46db042ecd0f` | 保留：金额格式化边界 |
| F010 | `batch-console/src/utils/queryFormOptions.test.ts` | `returns sorted unique non-empty values` | `13c48ce5ade1` | 保留：空输入、空值和去重排序不同场景 |
| F011 | `batch-console/src/utils/queryFormOptions.test.ts` | `returns empty array for empty input` | `13c48ce5ade1` | 保留：空输入、空值和去重排序不同场景 |
| F012 | `batch-console/src/utils/queryFormOptions.test.ts` | `filters out undefined picks` | `13c48ce5ade1` | 保留：空输入、空值和去重排序不同场景 |
| F013 | `batch-console/src/constants/theme.test.ts` | `defaults to dark when no user preference is stored` | `b214db7a0a70` | 保留：缺省、保存值和历史迁移不同状态 |
| F014 | `batch-console/src/constants/theme.test.ts` | `preserves stored user preference` | `b214db7a0a70` | 保留：缺省、保存值和历史迁移不同状态 |
| F015 | `batch-console/src/constants/theme.test.ts` | `migrates legacy system default to dark once` | `b214db7a0a70` | 已更名：无标记历史 system 偏好读取回退深色，读取本身不执行迁移 |
| F016 | `batch-console/src/utils/logRedact.test.ts` | `sanitizeParams returns undefined for null/undefined` | `ab4106a1cb6a` | 保留：null 与 undefined 参数不生成日志对象 |
| F017 | `batch-console/e2e/alert-outbox-ops.spec.ts` | `重试 tab 默认激活` | `693423e3785b` | 保留：Outbox 初始重试 tab 状态 |
| F018 | `batch-console/src/stores/tabs.test.ts` | `returns -1 for unknown key` | `3994cb80f664` | 保留：关闭不存在的 tab 返回 -1 |
| F019 | `batch-console/e2e/excel-import.spec.ts` | `下载配置包模板按钮可见` | `9d85670a1912` | 保留：默认页面模板下载入口可见，独立于场景选择后下载 |
| F020 | `batch-console/e2e/excel-import.spec.ts` | `导出当前配置包按钮可见` | `9d85670a1912` | 保留：默认身份配置包导出入口可见，与指定角色回导场景独立 |
| F021 | `batch-console/src/api/workerFingerprint.test.ts` | `listWorkerFingerprints normalizes null response to empty array` | `1b59278f03f6` | 保留：两个接口各自的空响应契约 |
| F022 | `batch-console/src/api/workerFingerprint.test.ts` | `getWorkerFingerprintSummary normalizes null response to empty array` | `1b59278f03f6` | 保留：两个接口各自的空响应契约 |
| F023 | `batch-console/src/utils/iconResolve.test.ts` | `已知图标名:返回对应 EP 图标 Component` | `2ca9db934b7a` | 已合并并增强：已知图标精确解析为 Plus，未知名称回退 Menu |
| F024 | `batch-console/src/utils/iconResolve.test.ts` | `未知图标名:返回 fallback(Menu)而非 undefined,保菜单可渲染` | `2ca9db934b7a` | 已增强：未知名称及空值均精确回退 Menu |
| F025 | `batch-console/src/components/common/SensitiveFieldAlert.test.ts` | `handles invalid JSON string gracefully (no render)` | `ec474dc11d5e` | 保留：无效敏感字段 JSON 不渲染告警 |
| F026 | `batch-console/src/utils/idempotency.test.ts` | `returns a non-empty string` | `083d0f89d82f` | 已增强：单次生成结果的类型和非空一致断言 |
| F027 | `batch-console/e2e/ops-shard-catalog-crud.spec.ts` | `页可达 + 新建入口可见` | `3cebf6614a54` | 保留：串行 CRUD 前的独立分片目录入口冒烟 |
| F028 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `uppercases lower` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F029 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `keeps digits + underscores` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F030 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `empty → EMPTY` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F031 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `< 3 chars → LENGTH` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F032 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `> 30 chars → LENGTH` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F033 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `digit-leading rejected (BIZ_FORMAT requires letter first)` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F034 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `exact 30 chars boundary OK` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F035 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `accepts v-prefixed` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F036 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `accepts bare digits` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F037 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `builds without version` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F038 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `builds with version` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F039 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `normalizes biz input on the fly` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F040 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `drops bad version silently` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F041 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `empty existing → empty` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F042 | `batch-console/e2e/smoke.spec.ts` | `根路径重定向到运营概览` | `b4feddbf35cd` | 保留：根路径跳转到运维概览 |
| F043 | `batch-console/src/utils/pipelineStepSummary.test.ts` | `PARSE 步骤取 parsedCount` | `db777d80109a` | 保留：不同阶段字段及异常输入 |
| F044 | `batch-console/src/utils/pipelineStepSummary.test.ts` | `GENERATE 取 recordCount` | `db777d80109a` | 保留：不同阶段字段及异常输入 |
| F045 | `batch-console/src/utils/pipelineStepSummary.test.ts` | `未映射 stage(如 PREPARE)且 summary 含计数 → 回退优先级列表` | `db777d80109a` | 保留：不同阶段字段及异常输入 |
| F046 | `batch-console/src/utils/pipelineStepSummary.test.ts` | `已解析对象入参(非字符串)也支持` | `db777d80109a` | 保留：不同阶段字段及异常输入 |
| F047 | `batch-console/src/utils/pipelineStepSummary.test.ts` | `计数字段非数字(脏数据)→ 跳过不误显` | `db777d80109a` | 保留：不同阶段字段及异常输入 |
| F048 | `batch-console/src/utils/safeHtml.test.ts` | `returns the sanitized string unchanged` | `3dc117efb48b` | 已增强：清洗器返回不同文本时原样传递清洗结果 |
| F049 | `batch-console/src/api/meta.test.ts` | `payload 不是对象时返回 {}(防御后端异常)` | `1ccdd5049e9e` | 保留：元数据枚举接口空响应回退空对象 |
| F050 | `batch-console/src/utils/metaEnumPick.test.ts` | `returns first matching non-empty group` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F051 | `batch-console/src/utils/metaEnumPick.test.ts` | `skips missing keys and returns first hit` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F052 | `batch-console/src/utils/metaEnumPick.test.ts` | `skips empty arrays` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F053 | `batch-console/src/utils/metaEnumPick.test.ts` | `returns [] when no key matches` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F054 | `batch-console/src/utils/metaEnumPick.test.ts` | `returns [] for undefined input` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F055 | `batch-console/src/utils/metaEnumPick.test.ts` | `returns [] for null input` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F056 | `batch-console/src/utils/metaEnumPick.test.ts` | `returns [] when called with no keys` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F057 | `batch-console/src/stores/auth.test.ts` | `isLoggedIn is false when no token` | `beedb463ec52` | 已更名：未设置 session 标记的登录状态 |
| F058 | `batch-console/src/stores/auth.test.ts` | `hasPermission returns false when no userInfo` | `beedb463ec52` | 保留：缺少用户信息时无权限 |
| F059 | `batch-console/e2e/file-ops.spec.ts` | `默认展示运行列表 tab` | `5663bc67b7cb` | 保留：流水线观测运行列表默认 tab |
| F060 | `batch-console/src/views/monitor/workflowNodeSkipReason.test.ts` | `marks old skipped rows without a recorded reason` | `e28ec3ed77e5` | 保留：历史跳过节点缺少原因的兼容文案 |
| F061 | `batch-console/src/views/monitor/workflowNodeSkipReason.test.ts` | `does not show skip copy for other node states` | `e28ec3ed77e5` | 保留：非跳过节点不显示跳过文案 |
| F062 | `batch-console/src/api/atomicTaskTypes.test.ts` | `returns empty array when payload is empty` | `f9d606c29c00` | 保留：原子任务类型空列表响应 |
| F063 | `batch-console/src/api/customTaskTypes.test.ts` | `returns null for invalid JSON` | `4bce8222129d` | 保留：自定义任务描述非法 JSON 解析回退 |
| F064 | `batch-console/src/views/system/atomicRuntimeModel.test.ts` | `不可用时即使混有executor字段也不展示为真实状态` | `74f78eaff53a` | 保留：运行状态不可用时不误展示静态字段 |
| F065 | `batch-console/src/views/workflow/designer/codec/mermaidExporter.test.ts` | `empty snapshot → header only` | `7ef62b26c858` | 保留：空 Workflow 图的 Mermaid 头部 |
| F066 | `batch-console/src/utils/passwordGenerator.test.ts` | `throws when length < 4` | `de6d00da4499` | 保留：密码长度低于下限时拒绝生成 |
| F067 | `batch-console/src/utils/passwordGenerator.test.ts` | `excludes ambiguous characters (0/O/I/l/1)` | `de6d00da4499` | 保留：密码不包含易混字符 |
| F068 | `batch-console/src/utils/passwordGenerator.test.ts` | `extra point at length ≥ 12` | `de6d00da4499` | 已增强：比较字符类别相同的 11、12 字符密码强度 |
| F069 | `batch-console/src/utils/passwordGenerator.test.ts` | `caps at 4 (extreme)` | `de6d00da4499` | 已增强：极长复杂密码的强度精确为上限 4 |
| F070 | `batch-console/src/views/worker/workerActions.test.ts` | `在线节点只展示下线和接管动作` | `79bc5544a7dd` | 已更名：标题与 ONLINE 原有操作断言一致 |
| F071 | `batch-console/src/views/worker/workerActions.test.ts` | `排空中节点不再展示排空和预热` | `79bc5544a7dd` | 保留：不同 Worker 状态对应不同操作集 |
| F072 | `batch-console/src/views/worker/workerActions.test.ts` | `离线节点只允许预热` | `79bc5544a7dd` | 保留：不同 Worker 状态对应不同操作集 |
| F073 | `batch-console/e2e/flows/01-trigger-instance.spec.ts` | `1. 查到一个可触发的 jobDefinition` | `ce6052a17152` | 保留：真实触发流程对作业定义存在的前置检查 |
| F074 | `batch-console/src/api/taskHeartbeat.test.ts` | `reads percent in 0-100 range` | `7ba0db0a5c78` | 保留：比例、百分数、精度及优先级不同场景 |
| F075 | `batch-console/src/api/taskHeartbeat.test.ts` | `treats 0-1 floats as ratios and scales up` | `7ba0db0a5c78` | 保留：比例、百分数、精度及优先级不同场景 |
| F076 | `batch-console/src/api/taskHeartbeat.test.ts` | `rounds to 0.1` | `7ba0db0a5c78` | 保留：比例、百分数、精度及优先级不同场景 |
| F077 | `batch-console/src/api/taskHeartbeat.test.ts` | `prefers percent over progress when both present` | `7ba0db0a5c78` | 保留：比例、百分数、精度及优先级不同场景 |
| F078 | `batch-console/src/utils/sensitiveKeys.test.ts` | `returns empty for clean object` | `c144b2c3a67b` | 保留：无敏感字段对象的反例 |
| F079 | `batch-console/src/utils/sensitiveKeys.test.ts` | `detects direct sensitive keys` | `c144b2c3a67b` | 保留：直接敏感字段识别 |
| F080 | `batch-console/src/utils/sensitiveKeys.test.ts` | `parses JSON string input` | `c144b2c3a67b` | 保留：JSON 文本输入的敏感字段识别 |
| F081 | `batch-console/src/api/adapters.test.ts` | `returns correct slice for page 2` | `97302b9898c2` | 保留：分页第二页切片 |
| F082 | `batch-console/src/api/adapters.test.ts` | `returns partial last page` | `97302b9898c2` | 保留：分页不足一页的尾页 |
| F083 | `batch-console/src/utils/structuredSummary.test.ts` | `truncates plain text summaries` | `5bf1d96ef232` | 保留：文本截断行为 |
| F084 | `batch-console/e2e/reports-ops.spec.ts` | `展示所有报表卡片` | `947db579c576` | 保留：报表导出卡片总数 |
| F085 | `batch-console/e2e/reports-ops.spec.ts` | `header 显示 tenantId 标签` | `947db579c576` | 保留：报表中心展示租户标签 |
| F086 | `batch-console/src/utils/safeJson.test.ts` | `returns empty string unchanged` | `ecf5fe539fb7` | 保留：空文本与非法 JSON 行为不同 |
| F087 | `batch-console/src/utils/safeJson.test.ts` | `falls back to original string on malformed JSON` | `ecf5fe539fb7` | 保留：空文本与非法 JSON 行为不同 |
| F088 | `batch-console/e2e/c-plus-coverage.spec.ts` | `actuator/health 暴露 200` | `886f1907a8f1` | 保留：Console 服务 health 端点状态 |
| F089 | `batch-console/src/components/common/statusTagResolve.test.ts` | `falls back to fallback arg when value empty` | `8a169b9c2830` | 保留：状态为空使用指定默认标签 |
| F090 | `batch-console/src/components/common/statusTagResolve.test.ts` | `falls back to em-dash when value empty and no fallback` | `8a169b9c2830` | 保留：状态为空使用隐式默认标签 |
| F091 | `batch-console/src/components/common/statusTagResolve.test.ts` | `returns info for unknown values when no local label override` | `8a169b9c2830` | 保留：未知状态的默认颜色 |
| F092 | `batch-console/src/composables/useOnboardingTour.test.ts` | `首次访问且无完成标记时展示引导` | `acaf78e70608` | 保留：首次访问展示引导 |
| F093 | `batch-console/src/composables/useOnboardingTour.test.ts` | `已完成引导时不再展示` | `acaf78e70608` | 保留：完成标记阻止重复展示 |
| F094 | `batch-console/src/views/monitor/canReplayFailedWorkflowJobNode.test.ts` | `allows only a failed JOB node with a linked instance in a failed Workflow run` | `6ab6409f2d1e` | 保留：失败 Workflow 中关联作业节点可重放的正例 |
| F095 | `batch-console/e2e/ops-diagnostic.spec.ts` | `集群诊断区块存在` | `92ac3c31a029` | 已增强：限定集群诊断卡片标题而非匹配任意诊断文案 |
| F096 | `batch-console/src/directives/permission.test.ts` | `keeps element when permissions list empty` | `5081b134793f` | 保留：空权限列表的显式准入语义 |
| F097 | `batch-console/src/components/common/EmptyState.test.ts` | `没有操作时不渲染空的操作容器` | `88710e9f5c5a` | 保留：无操作时空状态不生成操作容器 |
| F098 | `batch-console/src/utils/userErrorMessage.test.ts` | `preserves non-API client-side validation errors` | `2a196371a12b` | 保留：前端本地验证异常原文 |
| F099 | `batch-console/src/utils/logger.test.ts` | `logClick writes type=click` | `cf4674799e76` | 保留：点击日志类型 |
| F100 | `batch-console/src/utils/logger.test.ts` | `keeps legitimate error messages` | `cf4674799e76` | 已增强：断言实际保留的错误名称 |
| F101 | `batch-console/src/utils/logger.test.ts` | `filters by keyword in name` | `cf4674799e76` | 已增强：断言名称关键词筛选返回的具体条目 |
| F102 | `batch-console/src/utils/logger.test.ts` | `filters by keyword in props` | `cf4674799e76` | 已增强：关键词只存在于属性，验证属性筛选结果 |
| F103 | `batch-console/src/utils/logger.test.ts` | `returns all when no filter` | `cf4674799e76` | 已增强：无筛选时验证全部具体日志条目 |
| F104 | `batch-console/src/composables/useFormValidate.test.ts` | `required 默认 blur` | `fbf2e873b8d6` | 保留：必填规则默认 blur 触发器 |
| F105 | `batch-console/src/composables/useFormValidate.test.ts` | `formRef 未挂载时 validate() 返回 false 不抛` | `fbf2e873b8d6` | 保留：未挂载表单的安全失败 |
| F106 | `batch-console/src/components/common/docsRegistry.test.ts` | `docKey 全为 kebab-case(避免 camelCase / snake_case 混用)` | `c5c768c197b1` | 保留：文档注册键命名规则 |
| F107 | `batch-console/src/components/common/docsRegistry.test.ts` | `未知 docKey 返回空字符串(避免 iframe 加载未定义页)` | `c5c768c197b1` | 保留：未知文档键返回空路径 |
| F108 | `batch-console/src/utils/text.test.ts` | `keeps short values unchanged` | `22ae23420c9d` | 保留：短文本、中文和代理对边界 |
| F109 | `batch-console/src/utils/text.test.ts` | `truncates CJK text by characters rather than bytes` | `22ae23420c9d` | 保留：短文本、中文和代理对边界 |
| F110 | `batch-console/src/utils/text.test.ts` | `does not split a surrogate pair` | `22ae23420c9d` | 保留：短文本、中文和代理对边界 |
| F111 | `batch-console/e2e/ops-tenant-placement-crud.spec.ts` | `页可达 + 新建入口可见` | `59572125f679` | 保留：串行 CRUD 前的独立租户分片入口冒烟 |
| F112 | `batch-console/src/views/scheduler/batchDayPresentation.test.ts` | `builds leap-year month boundaries in local time` | `34dac61712f4` | 保留：本地月份范围包含闰年末日 |
| F113 | `batch-console/e2e/notification-management.spec.ts` | `旧 webhooks 路由重定向` | `6f944632a3cb` | 保留：旧 Webhook 路由兼容跳转 |
