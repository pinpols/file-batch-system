# 2026-06-03 FE 用户易用性深扫

> 范围:batch-console 桌面 (`src/views/`) + 移动 (`src/views-mobile/`)
> 视角:运营/接入/管理三类角色完成核心任务时的「能不能/快不快/会不会错」
> 方法:核心 10+ 页源码逐行通读(行号引用真实文件),叠加全仓 grep 旁证
> 关联前序:`2026-06-03-deep-scan-fe-layout-responsive.md` / `2026-06-03-deep-scan-fe-theme-color-a11y.md`(本文不复述布局/配色,只看任务流)

---

## 0. TL;DR 计数

| 级别 | 数量 | 主题分布 |
|---|---|---|
| P0 | 5 | 跨页 select 不留 selection / 移动 tap 区 <44px / JobDef 三个新建入口硬编码中文 / Alert 全量拉回前端切片 / 桌面列表无防抖列搜 |
| P1 | 12 | 时间无相对显示 / 数字无千分位 / 嵌套 drawer 心智重 / 三处批量删无 trash / Workflow 校验弹窗只读无导出 / 等 |
| P2 | 9 | 引导只覆盖 OpsSummary 全 0 / DatetimeColumn 无时区提示 / palette 不带 favorite / 等 |

合计 P0 + P1 = **17**。下文按维度展开,每条带文件:行号 + 现状 + 影响 + 修复路径。

---

## 1. 任务完成度(happy-path 步数 / 断点)

### 1.1 OpsSummary → drill 路径短,但断点多

`src/views/ops/OpsSummary.vue:15-54` 全 0 引导卡片 4 入口设计干净;但页面外其它 drill-down 缺一致性:

- **断点 A**(P1):`OpsMetricGrid` 卡片 click 通过 `@go` emit 出去,但 OpsTrendPanel / DistPanel 的图表点击没有 drill — 用户看到"失败任务趋势 7 天峰值在 5/29"无法点 5/29 直接下钻到当天实例列表。echarts series 已经有 dataIndex,onclick 接通到 `/monitor/job-instances?startDate=2026-05-29&endDate=2026-05-29` 是 10 行的事。
- **断点 B**(P1):`isFreshTenant` 仅看 `pendingApprovals/openAlerts/criticalAlerts/runningJobs/failedJobs` 5 项(L168-176),不看 worker/job 定义数。新租户已经创建了 1 个停用的 job 定义就不算 fresh,引导消失 — 实际还远没接完。建议判 `(definitions==0 && workers==0)` OR 全 0,而不是只看 runtime。
- **断点 C**(P2):无"上次访问"短链。每天 oncall 第一件事是看「我昨天关注的失败 jobCode 今天又挂没」,目前必须重新过滤;palette 有 recentTabs(`stores/tabs.ts`)但 OpsSummary 没接到 recent。

### 1.2 JobInstanceList → 详情 / 重跑 happy-path

`src/views/monitor/JobInstanceList.vue:95-187` 列顺序经过有意识优化(L93 注释说 "决策字段优先"),好。

- **断点**(P1):列表行没有内联"重跑/终止"按钮,只有"详情/分区"两个跳转(L178-184)。失败任务最高频操作是重跑,目前要 3 步(点详情 → 等加载 → 找按钮),mobile MJobInstances 反而做对了内联(`views-mobile/MJobInstances.vue:106-126` RUNNING→终止 / FAILED→重跑)。桌面建议补 `RowActions` 折叠菜单。
- **断点**(P2):URL query 同步只在 `searchInstances()` 调用,date picker `onDateChange` 也同步;但状态从外部跳入(query.status)后用户手动改 status,filter 是同步的但 reset 不会回到原 URL 入口状态 — 误点 reset 后丢失上下文。OpsSummary→JobInstances 链路尤其受伤。

### 1.3 ApprovalList → 双 tab 上下文丢失

`src/views/approvals/ApprovalList.vue:30-38` watch activeTab 同步到 `?tab=`,但 `GeneralApprovalsTab` 内的 filters(status/type/keyword)完全独立、不进 URL。从「告警 ack 后审批」跳转传入 `?status=PENDING` 当前会被 generalTab L236 读一次,但用户切到 catch-up 再切回 general,filter 还在,URL 不变 — 刷新页面或分享链接给同事时 filter 都丢。

→ **P1**:把 tab 子 filter 也写进 URL(`?tab=general&status=PENDING&type=...`),分享 oncall 排查链接刚需。

---

## 2. 信息密度(一屏几 row / 详情排布 / tooltip)

### 2.1 默认 pageSize=15 不加区分

全站列表 pageSize 都是 15(`grep pageSize 15` 命中 14 个文件)。

- **问题**(P2):列表行高 ~48px + 工具栏 ~150px + 分页 ~50px 在 1440 屏剩余 750px 区,15 行刚好溢出 1 屏(需要滚动 0.5 屏)。运维一眼扫描场景 18~20 才合适;告警/审批这类决策列表更适合 25。
- **修复**:加一个 user store 持久化 `lastPageSize`,默认列表 20,告警/审批 25,文件类 15(行高大)。

### 2.2 详情 drawer 列宽偏窄

`WorkflowDefinitionList.vue:148` 详情 drawer size=800px,内含 overview/runs/dag/dsl 4 tab。`el-descriptions :column=2`,key 列 `label-width=160`,value 列展示 workflowCode/Name 完全够。但 DSL tab L247-256 用 JsonPreview 展示 nodes+edges,**没设 maxHeight/虚拟滚动**;实际 50+ 节点的 workflow 一打开整 drawer 滚 2 屏。

→ **P1**:JsonPreview 给 nodes/edges 加 `:max-height=400 :collapsed=true`(只展开顶层数组,深层折叠)。

### 2.3 truncate vs 全文

桌面列表大量 `show-overflow-tooltip` 用于 traceId/description/title,行为合理。但 `JobInstanceList:159` traceId 列宽 180 + show-overflow-tooltip,32 位 traceId 一定截断,鼠标 hover 才能看全 — **trace 是排查 ID,点击跳转才是主用例**;现状是 `<router-link>` 但 `:title` 用了 `colTraceJumpTip`(L168),把 traceId 自身的文本提示淹没了。

→ **P2**:traceId 列加一个独立的"复制"icon(已有 CopyableText 组件未使用),hover 显示"点击跳 Trace, ⌘+点复制"。

### 2.4 数字无千分位

`grep toLocaleString src/utils src/components` **零命中**;仅 mobile views 用 toLocaleString 格式化日期。所有计数(occurrenceCount、runningJobs、totalOnline、count)都裸数字渲染。AlertList L111 `occurrenceCount` 出现 12345 这样的数字非常密集,无千分位很难一眼分辨数量级。

→ **P0**:加 `src/utils/number.ts` 的 `fmtNum(n: number, locale?: string)`,统一调 `n.toLocaleString(locale ?? 'zh-CN')`。所有 metric / count 列改用。

### 2.5 日期无相对时间

`DatetimeColumn.vue:30-32` 只输出 `fmtDatetime` 绝对时间(2026-06-03 14:23:18)。`fmtRelative` 在 utils 里存在(`utils/datetime.ts:72`)但**只有移动端 MOpsSummary/MAlerts 用了**。

桌面 AlertList lastSeenAt / heartbeatAt / firstSeenAt 用户最关心"多久之前",目前必须心算。

→ **P1**:DatetimeColumn 支持 `mode="relative-with-tooltip"`,显示"5 分钟前",tooltip 给绝对时间 + UTC。Worker 心跳列、Alert 列、JobInstance.startedAt 全切。

---

## 3. 心智模型(术语 / 列名 / 状态枚举一致性)

### 3.1 Job / Task / Workflow / Pipeline / Run 命名

实际使用情况:

| 实体 | 列表页 | 详情/运行 | router path | i18n group key |
|---|---|---|---|---|
| Job 定义 | JobDefinitionList | JobDefinitionDetail | `/jobs/definitions` | jobDefinitionList |
| Job 实例 | JobInstanceList | JobInstanceDetail | `/monitor/job-instances` | jobInstanceList |
| Workflow 定义 | WorkflowDefinitionList | drawer 内嵌 | `/workflow/definitions` | workflowDefinitionList |
| Workflow Run | WorkflowRunList | WorkflowRunDetail | `/monitor/workflow-runs` | monitor.runListXxx |
| Pipeline | PipelineDefinitionList | drawer | `/jobs/pipelines` | pipelineDefinitionList |
| Atomic 任务 | AtomicTaskTypeCenter | tab 内嵌 | `/system/atomic-task-types` | atomicTaskTypeCenter |
| 自定义任务类型 | CustomTaskTypeList | drawer | `/ops/custom-task-types` | customTaskTypeList |

**问题**(P1):
- "Run" 词在 WorkflowRunList 用了,但 JobInstance 列没叫 JobRun — 心智 split。RunsOverview 又是聚合两者,标签写 `runs.jobSection / workflowSection`。建议术语规约:Job 侧统一叫 **instance**,Workflow 侧统一叫 **run**,文档/i18n 群里给一句话解释「instance = 单 job 一次执行;run = 一次 DAG 跨多 instance」。目前没有任何术语表(`docs/design/page-naming-convention.md` 提到了但未生效)。
- Atomic 节点 vs 自定义 taskType 两个页面**对未启用代码同样的概念用不同形容词** — "Atomic" 是平台 4 类原子节点(http/shell/sql/script),"自定义 taskType" 是租户 SDK 上报。普通运营第一次见会以为是同一个东西的两个 tab。建议在 sidebar 改名"内置 Task 类型"/"租户 Task 类型"或加副标题。

### 3.2 状态枚举

`MetaSelect enum-key`:instanceStatus / workflowRunStatus / approvalStatus / alertStatus / severity / scheduleType 共 6 类,后端 enum 字典(`useConsoleMetaEnumsQuery`)做了完整候选。
StatusTag(`statusTagResolve.ts`)把 6 类映射到 EP tag 5 色(success/warning/info/danger/primary)。

- **隐患**(P1):MJobInstances.vue:228-244 `statusChipClass` 手写了一份 switch — 跟桌面 StatusTag 各走各的。当前 mobile 把 `WAITING/CREATED→warning`,桌面 instanceStatus 枚举(检查 `statusTagResolve.ts` 是否一致)如果有差异 = 两端对同一个状态显示不同颜色。
- **隐患**(P2):JobInstance.dryRun 在桌面 L99 用 plain info 灰 tag 单独标识,但 mobile 完全没有 dryRun 标识 — oncall 在手机上误把 dryRun 当生产实例的可能性存在。

### 3.3 三类角色看到的菜单

`navigation.ts` 加 `minRole` 在每个 child,组也有 minRole:
- **VIEWER**:35 项里能看 ~22(workspace/runs/files 大部分 + 工作流/作业定义只读 + 配置只读)
- **OPERATOR**:能看 ~32(再加 ops/triggers/quota/审批/配置发布)
- **ADMIN**:能看全 ~35(parameters/api-keys/user-accounts/governance hidden 3 项)

**问题**(P1):
- 入口隐藏 ≠ 数据隐藏。VIEWER 进 JobDefinitionList 看不到"新建/Bundle 导入"按钮(`canMutateConfig` gate, L6/12/16),但仍然看到行操作的"启用/停用/归档"按钮(`rowActions` 未 gate),点了才报 403 — bad surprise。建议 rowActions 内部按 permission filter。
- VIEWER 在 AlertList 仍能点 ack/silence/close 按钮(`AlertList.vue:131-159` 无 v-if),点了走 confirmDanger,确认后才 403 toast。同样问题。

→ **P0**:做一次 `usePermission()` 全站审计,所有破坏性按钮 v-if 包一下。

---

## 4. 搜索查询

### 4.1 全局 ⌘K Palette

`CommandPalette.vue:121-166` 行为干净:
- term <2 字符不打实体接口、纯数字 / 纯 hex(traceId) 走 jumpItems 不打接口
- 300ms 防抖(L165)
- generation token 防 race(L134, 152)
- jobs + workflows 并发各拉 pageSize=5

**问题**:
- (P1)只搜 jobCode + workflowCode 两个字段。alert title / instanceNo / approvalNo 搜不到。oncall "刚刚那条 ALERT-2026XXXX 在哪" 必须先去 AlertList。
- (P2)入口提示弱。LayoutHeader 有 ⌘K hint 但只读用户/平板用户可能从未发现。OpsSummary 全 0 引导卡片可以加一张"搜索"卡。

### 4.2 列表查询条

- (P0)**桌面所有列表查询都靠"点搜索按钮"或回车触发,没有列搜索防抖**。Alert 已经把全量拉回前端切片(`AlertList.vue:285 queryAlertsAll`),所以前端 slice 是 O(1),但每次改 filter 也走 `runSearch` + `slicePage`。Workflow 定义 L542 走真后端(分页 + 过滤),却没用防抖 — 用户改完 workflowCode 必须手动点搜。MJobInstances mobile 反而做对了 250ms 防抖(L319-322)。
  → 桌面 ListPageQueryBar 加 `debounceMs` prop,文本输入类 input 自动 trigger search。
- (P0)**Alert 全量拉回前端切片**(`queryAlertsAll`),大租户告警上万会引起页面卡顿;搜索/筛选纯前端,跟 BE 真实 paging API 不一致。建议补真 paging endpoint 或加 maxResults 限制 + UI 提示"展示前 1000 条,使用过滤精确查找"。
- (P1)WorkflowRunList traceId/runStatus 都是后端搜索但 workflowCode 是前端 cache 内 find(`L204 resolveDefId`),用户输入不在缓存的 code 会直接显示空 — 应当 fallback 提示"未找到该 workflowCode 定义,请检查拼写"而不是默默清空。

### 4.3 搜索范围说明

所有 query 都没"我在搜什么"说明。例如 keyword 字段(`approvals.keywordPlaceholder`)实际是 4 字段 OR(`approvalNo + requesterId + targetType + targetId`,见 `GeneralApprovalsTab.vue:258`),用户不知道。
→ (P1)HelpLabel 已有,所有 keyword/multi-field 输入都加。

---

## 5. 数据展示

### 5.1 JSON 格式化

`JsonPreview` 在 9+ 处使用(descriptor / DSL / nodes / edges / draft)。组件未读但从用法看支持折叠;问题是 `CustomTaskTypeList.vue:160-162` fallback `<pre>{{ raw }}</pre>` 把无法解析的 descriptor 原样渲染,长字串不折行也无 max-height,大 descriptor 把整个 drawer 超过。
→ (P2)pre 加 `max-height:300px; overflow:auto`。

### 5.2 长字串 truncate

`show-overflow-tooltip` 用得多,行为一致。但 mobile 的 `MJobInstances:71` job code 长 50 字符的 `m-card__title` 没有 text-overflow,会换行影响卡片高度参差。
→ (P2)`m-card__title` CSS 加 1 行截断。

### 5.3 货币/字节单位

未发现项目里有字节大小 / 文件 size 列(FileList 的 size 字段值得检查),全文搜没有 formatBytes utility。文件中心 FileList 如果展示 size 是裸 bytes,1234567 直接渲染显然不可读。
→ (P1)缺 formatBytes,FileList / Attachment 类页面影响。

### 5.4 时区

`fmtDatetime` 不带时区后缀(L13-15),用户跨时区协作(海外 oncall)会误判。`toIsoDateTime` 出站统一 UTC,但显示侧不告知。
→ (P2)DatetimeColumn 头加小 `(本地)` 灰字 + tooltip 给 UTC ISO 双显。

---

## 6. 批量任务 UX

### 6.1 selection 跨页丢失(P0)

**全仓 `reserve-selection` 零命中**。`GeneralApprovalsTab.vue:66` 是项目里唯一一个 type=selection 表,但 el-table 默认 selection 是按 reference 比较;翻页 + reload 后 selection 数组被覆盖(因为 ProTable @change=slicePage 会刷新 rows ref)。

**重现**:用户筛 100 条 PENDING 审批,选 page1 10 条(toolbar 显示 10);翻到 page2 → selection 数组在 onSel 触发后变成 page2 选中(0 条);批量按钮 disable。用户以为"我刚才选的 10 条要再来一次"。

→ **修复**:`<el-table :row-key="row => row.approvalNo" reserve-selection>` + 表列加 `:reserve-selection="true"`。

### 6.2 批量进度反馈

`runBatchApprove` / `runBatchReject` (L362-403) 单次 await,无中途进度;选 200 条提交,看到 loading 状态直到 BE 返回全部结果(success / partial-fail / fail),无 "100/200..." 进度。
→ (P1)BE 接口若有 `failedItems` 字段,前端弹结果 dialog,标记哪些 approvalNo 失败 + 原因;目前只有一句 toast `批量通过 N 条`,部分失败用户不知道。

### 6.3 部分失败处理

`batchApprovedToast` 用 selected.length(L375),**不论 BE 实际成功几条都报全成功**。如果 BE 返回 partial success 这里直接撒谎。
→ (P0)从 BE response 取 actualSuccessCount,partial 用 warning toast + dialog 列失败项。

---

## 7. 移动端

### 7.1 tap 区 <44px(P0)

`mobile-common.css` 中:
- `.m-page__title-action` 32x32(L153-154) — MJobInstances 顶部搜索图标按钮
- `.m-btn` height 38(L390) — 卡片底部"重跑/终止"按钮
- iOS HIG 标准 44x44,Material 48x48。

→ 改 m-btn → 44,改 title-action → 40 内含 padding 触达 44。

### 7.2 手势

`MPullRefresh` 下拉刷新覆盖了所有 mobile 页面,行为一致。但**没有"长按 jobCode 复制"** — 仅 `MJobInstances:79` 加了 `m-copy-text` click 复制 instanceNo,jobCode 长按无反馈。
→ (P2)统一加 `@longpress` 复制。

### 7.3 键盘弹起遮挡

MSearchBar(L41-47)在搜索 toggle 时 nextTick focus,但没有 `scroll-into-view` 处理。在小屏(iPhone SE)键盘弹起后,SearchBar 可能被键盘盖住。
→ (P2)focus 后 `el.scrollIntoView({ block: 'center' })`。

### 7.4 SLA filter chip

`MJobInstances:50-57` 有视觉清晰的 filter chip 显示当前 SLA 过滤;**桌面无对应 chip**:JobInstanceList 通过 query string 进入"今天失败"但没显式"已应用过滤条件 X"提示,reset 后才知道。
→ (P1)桌面 ProTable 借鉴 mobile filter chip 模式,顶部显示 "已应用:状态=失败 起 2026-06-01"。

---

## 8. 历史 / 收藏 / 快捷入口

### 8.1 现状

- ⌘K Palette 有 `recent` section(L309-314),来自 `tabsStore.list` 最近访问 path。
- 桌面 tabsStore (L11) 最多 12 条 MRU FIFO。
- **无收藏 / pin / star**:tabsStore 没有 favorite 字段。

### 8.2 影响

P1:运维 oncall 每天打开同样的 5-6 个页面(OpsSummary / JobInstanceList?status=FAILED&date=today / AlertList?severity=CRITICAL / Approvals?status=PENDING / WorkerManagement),目前每次重新过滤 + 翻页。
→ pin 一份"我的快捷过滤",写到 user prefs(`stores/auth.userInfo` 已有 store)。LayoutHeader 加 ⭐ 入口。

### 8.3 OpsSummary 不展示"我最近访问"

OpsSummary 仅做全 0 引导 + 4 指标 panel,不展示 "Recent jobs you viewed" / "Failed yesterday" 这种个性化视窗。
→ P2:OpsExtraPanel 加一个 "我的近期" tab(读 tabsStore + 个人 lastSeenFailureIds)。

---

## 9. 新手引导

### 9.1 现状

- OpsSummary 全 0 时显示 4 卡片引导(导入配置包/新建作业/租户/Worker)。
- `useOnboardingTour` 存在(`DefaultLayout.vue:69`),但具体覆盖哪些页面未深查。
- AtomicTaskTypeCenter L13-22 顶部 info alert "introBody" 介绍"这是只读 schema";WorkerFingerprintBoard / CustomTaskTypeList 同样有 intro alert — 一致性好。
- JobDefinitionList **三个新建入口**(L5-23):"向导新建" / "Bundle 导入" / "创建"(主按钮)— 无任何说明何时用哪个,纯靠用户猜。还是硬编码中文(见 §11.1)。

### 9.2 缺什么

- (P1)JobDefinitionList 三按钮加 tooltip 解释场景:向导 = 单作业 GUI 步骤,Bundle = 批量 import,创建 = 高级直接 form。或合并为单按钮 + dropdown。
- (P2)WorkflowDefinitionList 的"新建"实际跳转到 `/config/tenant-package` 配置包导入(L487-489),按钮叫"新建"但其实是导入,文案说谎。

### 9.3 useOnboardingTour

`shouldShowOnboarding` 进 DefaultLayout 调一次(L69),但实际激活条件未细查。建议跑一次 fresh tenant + ADMIN 账号,看 tour 是否真的能引导走完接入。

---

## 10. 错误恢复 / 误操作可撤回

### 10.1 confirmDanger

`useDangerConfirm.ts` 强制"动词 + 对象 + 后果 + 是否可恢复"(L8-9 注释明确说解决"19 处 ElMessageBox.confirm 文案不明确"问题),该约束合理。
- AlertList ack/silence/close 三处都接了 confirmDanger,且 ack/silence 标 `irreversible:false`,close 标 `irreversible:true`(L382)— 准确分级。
- WorkflowDefinitionList.archiveRow 标 `irreversible:false`,后果文案"软归档,实际禁用可恢复"— 诚实。

### 10.2 trash / 30 天回收

**没有任何 trash 机制**。"归档"在 workflow 是禁用(物理保留),但 API Key 吊销(`UserAccountList` 未读但按命名)、Tag 删除、Tenant 删除等是否走 trash 未知。
→ (P1)BE 已经有 deleted_at(2026-06-02 `deep-scan-be-architecture.md` 提及软删),FE 缺"已归档 / 回收站"列表入口,用户没有"我删错了" undo 路径。建议至少 Tag / Trigger / Tenant 三处加回收站 tab。

### 10.3 单条操作的 undo

ElMessage.success toast 时无 "撤销" action button。常见模式(Gmail 7 秒 undo)在 mobile MJobInstances retry 等场景特别需要。
→ (P2)封装 `successWithUndo(toast, undoFn, ttl=7000)`。

---

## 11. 认知负担(入口 / 嵌套 drawer / 决策点)

### 11.1 硬编码中文(P0,踩 CLAUDE.md 红线)

`JobDefinitionList.vue:10` 直接 `向导新建`,`:13` `Bundle 导入`。CLAUDE.md 写了 "所有用户可见字符串**必须** `t('namespace.key')`,**禁硬编码** zh/en",`npm run check:i18n` 也会跑。这两处可能通过(因为它们不在 locale 文件里所以没"缺 key"),但**红线就是红线**。

→ 立即修:加 `jobDefinitionList.headerWizard` / `headerBundle` 两 key,zh + en 1:1 写。

### 11.2 嵌套 drawer

WorkflowDefinitionList drawer 内 4 tab,其中 "DAG" tab 又包 WorkflowMiniDag 组件,有 "打开完整 DAG" 按钮跳新页(`openFullDag` L406-410)— 跳之前会 close drawer。**问题**:用户从 detail drawer 跳 DAG 全屏,看完 DAG 想回 detail,只能返回再点详情再切 tab,3 步。
→ (P1)DAG 全屏页加"返回 detail drawer"按钮,带上 row.id 直接重开 drawer 到 dag tab。

### 11.3 决策点过多

OpsSummary 4 tab(kpis/trend/dist/extra)+ 子 panel 各种图;**对新用户**进来要判断"我现在要看哪个 tab"。
→ (P2)给每 tab label 加 1 行说明文案(类似 AtomicTaskTypeCenter 顶部 alert),hover/选中切换时显示。

### 11.4 actions 区拥挤

`AlertList:130-162` 操作列 width=260,3 按钮(ack/silence/close)+ loading 状态;移动端没操作,桌面 narrow viewport(≤1280)被横向滚动。
→ (P1)用 RowActions 折成"确认 + 更多 [静默/关闭]",参考 WorkflowDefinitionList 的 inline-limit 模式(`rowActions`)。

---

## 12. 角色 UX 矩阵

| 任务 | VIEWER | OPERATOR | ADMIN |
|---|---|---|---|
| 看仪表盘 | ✅ | ✅ | ✅ |
| 查 job 实例 | ✅ | ✅ | ✅ |
| 重跑实例 | ❌ 行为缺 gate(P0) | ✅ | ✅ |
| 审批 | ❌(整页 OPERATOR gate) | ✅ | ✅ |
| 处理告警 ack/silence | ❌ 按钮可见点了 403(P0) | ✅ | ✅ |
| 改 workflow 启用 | ❌ 同上 | ✅ | ✅ |
| 改系统参数 | ❌ | ❌ | ✅ |
| 用户管理 | ❌ | ❌ | ✅ |
| 看 Audit | ✅(只读) | ✅ | ✅ |

**核心问题**:VIEWER 错觉"我可以操作",实际点了才知道不行。**P0 全站按钮 gate 审计**(§3.3 提)。

---

## 13. P0 / P1 行动清单

### P0(必须修,影响信任 / 红线)

1. **JobDefinitionList 硬编码中文**(`src/views/job/JobDefinitionList.vue:10,13`)— 踩 CLAUDE.md 红线,5 分钟修。
2. **跨页 selection 丢失**(`src/views/approvals/components/GeneralApprovalsTab.vue:66`)— 加 row-key + reserve-selection。
3. **批量审批 partial success 谎报**(`L375 batchApprovedToast`)— 从 response 取真成功数。
4. **AlertList 全量拉回前端切片**(`L285 queryAlertsAll`)— 大租户长期停滞;补真 paging 或限 maxResults。
5. **桌面按钮无 permission gate**(AlertList ack/silence/close、WorkflowDefinitionList toggleRow、JobDefinitionList rowActions 等)— VIEWER 点了才 403,做一次全站 v-if 审计。

### P1(影响日常效率)

6. 列表查询条文本输入无 250ms 防抖(桌面通用)。
7. DatetimeColumn 不显示相对时间;Worker / Alert / JobInstance 强需求。
8. 数字无千分位(全站 metric / count / occurrenceCount)。
9. JobInstanceList 行内缺"重跑/终止" inline action(mobile 已有,桌面缺)。
10. JobDefinitionList 三新建入口语义不清,需 tooltip / 合并 dropdown。
11. WorkflowDefinitionList "新建"按钮文案谎言(实际跳导入配置包)。
12. ⌘K Palette 不搜 alert title / instanceNo / approvalNo。
13. ApprovalList tab 子 filter 不进 URL,分享链接丢上下文。
14. WorkerManagement / CatchUp 等无"我的快捷过滤"收藏。
15. Drawer 内嵌 JsonPreview 大 DSL 无 maxHeight,超过 drawer。
16. AlertList 操作列 260px 横向拥挤,改 RowActions 折叠。
17. WorkflowDefinitionList DAG 全屏返回 drawer 路径断。

---

## 14. 旁证(确认事实的 grep 列表)

```
grep -rn "reserve-selection" src/                # 0 命中 → §6.1
grep -rn "toLocaleString" src/utils src/components/  # 0 命中 → §2.4
grep -rn "向导新建\|Bundle 导入" src/views/      # 2 命中 → §11.1
grep -rn "min-height: 4[48]px" src/layout-mobile/ # 仅 list-row 用,m-btn=38 → §7.1
grep -n "queryAlertsAll" src/api/alertsQuery.ts  # 前端切片 → §6.3
grep -n "fmtRelative" src/views/                 # 0 命中,只 mobile 用 → §2.5
```

---

## 15. 范围边界 / 未覆盖

- 未跑 dev server 截图实测,本文全部基于源码静态分析。`isFreshTenant` 跨判定、`useOnboardingTour` 实际触发条件、各按钮 ARIA 文案、表单错误展示模式、SSE 重连 UX、网络断开 UX 这些"运行时才暴露"的问题未覆盖。建议:本 P0/P1 列表落地后,补一轮 Playwright + axe 自动检 + 手动 VIEWER/OPERATOR/ADMIN 三角色 walkthrough。
- 移动端不写自动化测试是项目规定(CLAUDE.md),本文 mobile 章节属人工 review。
- 「角色 UX 矩阵」只覆盖核心 8 任务,完整矩阵建议作 separate doc。
- 国际化对齐:本扫描默认中文用户,英文(en-US)label 实际渲染未核对,有可能 zh 长 en 短导致按钮宽度问题。
