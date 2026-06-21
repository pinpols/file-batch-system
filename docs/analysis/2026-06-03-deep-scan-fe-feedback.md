# 深度扫描:前端反馈系统(Loading / Error / Toast / 通知 / Confirm / 进度)

- **范围**:`/Users/dengchao/Downloads/batch-console`(Vue 3 + Element Plus + TanStack Query)
- **日期**:2026-06-03
- **方法**:静态阅读 + grep 计数 + 关键路径回溯;不跑 e2e。
- **聚焦面**:Loading 状态层级、Error 翻译/Trace 暴露、Toast 一致性、空态分类、Import/Export 进度、维护/降级 banner、离线 / 网络恢复、TanStack 乐观更新、Web Push、Bell 红点、Confirmation 弹窗与误操作、进度条可信度。

---

## 0. 一句话总览

底层框架(`useAsyncAction` / `DataState` / `EmptyState` / `errorToast(traceId 可复制)` / `MaintenanceBanner` / `DegradationBanner` / `RouteProgressBar` / `useSseAutoReload` / `useNetworkStatus` / `ErrorBoundary`)做得**比同行多得多**,翻译 / TraceId 暴露 / 维护透传 / 幂等静默 / SSE 自愈 / KeepAlive 释放连接配额都有,**但存在 5 个 P0 系统性裂口**和约 10 个 P1 一致性 / 心智落差,集中在:**桌面无任何告警入口(Bell 红点缺失)**、**Web Push 全栈骨架就绪但 0 调用点**、**`ElMessageBox.confirm(...).catch(()=>null)` 让"取消"等同于"确认"**、**Import/Upload 完全无进度感**、**乐观更新零落地**(TanStack mutate 全走悲观刷)。

---

## 1. Loading 状态——三层框架已立,但落地比例与可信度参差

### 1.1 全局 / Route / Action 三层一览

| 层 | 实现 | 是否回退 | 评分 |
|---|---|---|---|
| 全局 fullscreen | **未使用** `ElLoading.service`(grep 0 命中) | — | 合理(SPA 不该锁屏) |
| Route 切换条 | `RouteProgressBar` + `routeProgress` store | `12s safetyTimer` + `220ms hideTimer` 防闪 | 良好 |
| 列表 / 卡片三态 | `DataState` (loading / error / empty / data 自动切骨架) | `TableSkeleton` 6 行,`EmptyState` 9 variants | 良好但落地少 |
| Action 按钮 | `useAsyncAction` busy + cooldown + 自动 onScopeDispose | 回退自动幂等键 + EP `:loading` 自动 disabled | 优秀 |
| Brief | `useBriefActionLoading(280ms)` 保证最少展示防闪 | 由 `useListFilterFeedback` 引用 | 优秀 |

### 1.2 落地裂口(P1)

- `<DataState>` 全站只用了 **11** 处(`grep -c '<DataState'`),`v-loading` 直接刷 **221** 处。新框架并未真正"统一",列表错误态绝大多数仍退化为"暂无数据",**用户无法区分"真空"和"加载失败"**——这正是 `DataState` 出现要解决的问题,但迁移停在了 5% 左右。
- `useAsyncAction` 仅 **26** 处使用,`cooldownMs` 仅 **3** 处显式设(`ApiKeyList` / `SystemParameterList` / `TenantBatchCreateDialog`)。绝大多数"保存 / 提交 / 触发"按钮**没有冷却窗口**,200ms 双击仍可重复触发(下游靠 BE 幂等拦截器 + axios 静默 IDEMPOTENT_REPLAY 救场,但出错时仍是 409 静默)。
- `useBriefActionLoading` 仅在 `useListFilterFeedback` 内部用——筛选闪烁防抖只覆盖列表筛选条这一种场景。

### 1.3 长期停滞回退

- `routeProgress.start()` 有 `12s safetyTimer` 强制 `finish()`,**良好**。
- `useSseAutoReload` 5 次指数退避后 `onFallback` 弹一次"实时推送暂不可用",**良好**。
- 但 `useListLoadState` / `useAsyncAction` **没有超时上限**——一个 hang 在 pending 的请求会一直让按钮 loading,需要靠 axios `timeout`(默认 0)回退。建议给 `client.ts` 默认 `timeout: 30_000`。**(P1)**

### 1.4 计数显示

- 列表分页 `ProPagination` / `TablePagerBar` 复用 EP `total`,合规。
- **没有任何"已选 N 条" / "本批共处理 X 条"的常驻计数**(批量操作场景),只在 toast 字符串里出现(`approveSuccess` 等)。**(P2)**

---

## 2. Error 反馈——HTTP / Biz / Trace 链路完整,但 4xx 文案碎片化

### 2.1 拦截器分流(`src/api/interceptors.ts`,697 行)

读完整文件后,确认了下列分支都做了正确处理(这是同行少见的):

| HTTP / 形态 | 处理 |
|---|---|
| 200 + envelope `code` 非成功 | `translateBizMessage(envelope.message)` + traceId toast + reject |
| 401 `/auth/login` 或 `/auth/token` | toast"用户名或密码错误",**不**清登录态 |
| 401 `/auth/me` | localStorage 清 session + 跳 `/login` |
| 401 业务接口 | 静默 refresh 一次,失败不踢人(避免单个 RBAC 不足把整 session 清掉) |
| 403 | `权限不足` toast |
| 404 BizException `code != null` | `资源不存在` + 后端 message |
| 404 Spring 静态资源 fallback | 改写"接口不存在或后端版本不匹配",DEV 模式拼 `VITE_DEV_PROXY_TARGET` 提示 |
| 400 缺 `jobCode` | 专门定向文案,duration 7.5s |
| 409 `IDEMPOTENT_REPLAY` 或 `_autoIdempotencySignature` | **静默 reject**,不弹 toast(双信号互补) |
| 429 / 502 / 503 / 504 + GET | 指数退避 + jitter,最多 2 次 |
| 503 `maintenance:true` | 写入 `appStore.maintenance`,**不**弹 toast |
| 网络错(无 response) | `网络不可达` + DEV 模式拼代理目标 |

### 2.2 BizException 翻译

`translateBizMessage` 用正则 `^error\.[a-z][a-z0-9_]*(?:\.[a-zA-Z0-9_]+)+$` 识别后端下发的 i18n key,命中字典则译,否则原样。**这个机制好,但仅当后端按规范下发 key 时生效**——抽查 `batch-console-api` 后端代码外的提交,大部分 BizException 直接用中文 message,因此 EN 用户**实际上拿到的还是中文**。建议:
- 在 i18n 缺失时,前端再做一层 "中文 message 启发式翻译"(查表 dict 命中常见短语) —— 暂可不做,但记一条 P1。

### 2.3 TraceId 暴露

`utils/errorToast.ts` 渲染 traceId 为 `<code>` 元素 + "复制"按钮 + 整段点击复制 + tooltip。`navigator.clipboard` 失败回退 `execCommand('copy')`。文案显式标 "TraceId" 提示用户能粘给运维。**优秀,行业前 10%**。

### 2.4 落地裂口

- **(P0)**`ElMessageBox.confirm(...).catch(() => null)` 在 `BatchDayReplay.vue:440 / :467` 出现两次。代码模式:
  ```ts
  await ElMessageBox.confirm(...).catch(() => null)
  approving.value = true
  await batchDayReplayApi.approve(...)
  ```
  用户点 **Cancel**,Promise 被 `.catch(() => null)` 吃掉,执行流继续走 `approve()` —— **点取消等于点确认**。批跑回放(BatchDayReplay)是高危 ops 操作(可能影响大量分区数据),这里属于严重 UX 安全 bug。
- **(P1)**`MWorkflowViewer.vue:190` / `WorkflowMermaidViewer.vue:558,592` 用 `.catch(() => null)` 吞了 workflow detail 加载失败 —— viewer 当作"无 run"渲染,用户以为是数据问题而不是接口失败。
- **(P1)**`Login.vue:119`:`authApi.logout({ _silent: true } as AxiosRequestConfig).catch(() => {})` 这里是合理的(登录页静默清旧 cookie),保留。
- **(P1)**`useOpsSummary.ts:244-245` 把两个 dashboard 查询用 `.catch(() => null)` 降级,UI 直接当 null 渲染。OpsSummary 没有任何 "数据加载失败、请重试" 提示,用户看到的是"全 0"的告警概览 —— 在 oncall 场景这是致命误导。
- 全站**仍有 21 处** `ElMessageBox.confirm` 没走 `useDangerConfirm`(见 §6)。

---

## 3. Toast / Notification —— 单通道无优先级

### 3.1 一致性

- 全局只用 `ElMessage.*`(grep 238 处),**`ElNotification` 全站零使用**——即没有右上角持久通知卡片,所有反馈都是顶部 toast。
- `installMessagePatch()` 全局给 4 个类型(`success/warning/info/error`)注入 `grouping: true`,**相同 message+type 不重复堆叠,显示 ×N 计数**。设计合理。
- Duration 默认:`errorToast` 4s(无 trace) / 6.5s(有 trace),`network.offline` 0 (不消失),`network.online` 2s。**没有"优先级 / 队列"概念**——EP `ElMessage` 单 instance 队列模型按时间堆叠,长 trace toast 会被后到的 success toast 推动位置。

### 3.2 防重复

- `grouping: true` 解决 message+type 完全相同的重复。
- 但**不同 traceId 的同一接口 400** 会被视作不同 message → 仍堆叠(`extractHttpErrorMessage` 含 path + message,通常 path 相同 → 合并)。
- `useAsyncAction` 的 busy 阻止前端重复发起 → 上游防重。

### 3.3 缺口

- **(P0)**无 ElNotification 长持续通知卡片 → "异步任务完成 / Export 完成 / Workflow 跑完" 这类**用户不再在页面上**的事件,只能靠 toast(若已离开页面 toast 也不存在)。这就把 §5 "跨页通知"完全推给了 Web Push,而 Web Push 又是 0 调用点(见 §7)。
- **(P1)**`useNetworkStatus.ts` 的"在线"toast `duration: 2000`,而"离线" `duration: 0`,且用 `grouping: true` —— 但 EP grouping 不跨 type 合并,实际行为 OK,只是 `online` toast 来自 listener,**在不实际断网的情况下 navigator 触发误 `online` 事件不会清掉旧 `offlineToast`**(代码靠 `offlineToast` 引用判定,但若多次 `offline → online` 切换其实会逐个开 toast)。**抽查代码逻辑实际无 bug**,只是脆弱。

### 3.4 维护 / 降级 banner

- `MaintenanceBanner` 3 态(blocked / readonly / admin-bypass),ETA 分钟级倒计时,affectedServices chip,WCAG `role="alert"`。**优秀**。
- `DegradationBanner` 接 BE Resilience4j `X-Degraded-Source` header,每 15s 清 TTL 60s 过期源。**优秀**。
- **(P1)** banner 无关闭按钮,且 maintenance 期可被用户当作"系统异常退出"。建议加 "了解更多 / 查看 SLA" 链接。

---

## 4. 空状态——9 个 variant 覆盖好,落地 22 处

### 4.1 EmptyState variant 矩阵

| variant | 文案 i18n key | 用途 |
|---|---|---|
| `empty` | `empty.default` | 默认 |
| `forbidden` | `error.forbidden` | 403 |
| `error` | `empty.error` | 加载失败 |
| `offline` / `network` | `error.networkTitle` | 网络断 |
| `filter-empty` | `empty.filterTitle` | 加了筛选无匹配 |
| `tenant-empty` | `empty.tenantTitle` | 当前租户内空 |
| `no-permission` | `empty.noPermissionTitle` | 角色不足 |
| `service-down` | `empty.serviceDownTitle` | BE 不可用 |

### 4.2 落地

- **22 处** `<EmptyState`、**11 处** `<DataState`。新增 view 用法稳定,但旧 list 仍写裸 `<el-empty>` 或 `empty-text`,用户**无法区分** "本来无数据" vs "筛了无匹配" vs "你没权限"——这正是 9 个 variant 要解决的事。
- **(P1)**`DataState` 内 `emptyVariant` 默认 `'empty'`,11 个调用点里仅 1-2 处显式传 `filter-empty`。**重灾区**:`JobInstanceList`(每个 oncall 用户都看)、`WorkflowRunList`、`AlertList` —— 都没区分。
- **(P1)**没有"网络错引导"——`error` variant 描述固定 `error.subtitle`,不区分网络 / 服务异常退出,用户看不出"该重试还是该等运维"。
- **(P2)**CTA 引导薄弱——`EmptyState` 提供 `#action` slot,实际只在 `DataState` 注入了 "重试"。`tenant-empty` 应当 CTA "切换租户",`no-permission` 应当 CTA "申请权限",目前**全部缺失**。

---

## 5. 进度状态——SSE 自愈一流,但导入 / 上传无进度

### 5.1 SSE vs Polling

- `useSseAutoReload`:KeepAlive 感知 + generation guard + 5 次指数退避(1s→16s)+ `maxRetries` fallback toast。覆盖 6 个 view:`JobInstanceList` / `WorkflowRunList` / `AlertList` / `OutboxList`(2 个 domain) / `PipelineDefinitionList`。
- `useAutoRefresh` 30s 轮询,页面隐藏自动暂停,只用了 **3 处**(`WorkerFingerprintBoard` 之类)。
- `mobileBadges.refresh()` 移动端 30s 拉 `getOpsSummary`,更新 4 个 badge(pendingApprovals / openAlerts / criticalAlerts / failedJobs)。

### 5.2 Approval pending 计数

- **(P0)桌面端完全无"待审批"实时计数**。`mobileBadges` store 只在 `MobileLayout` 挂载时 polling,桌面 sidebar / header 没有任何徽章。审批员在桌面端切到非 Approvals 页后,**对新审批 0 感知**,直到他主动回 Approvals 列表(无 SSE,无轮询)。
- `ApprovalList.vue` 本身 grep 显示**既无 `useSseAutoReload` 也无 `useAutoRefresh` 也无 `setInterval`** —— 进入页面后,数据停留在首次加载,用户主动点刷新才动。这与 `getApprovalsSummary` / `mobileBadges` 后端能力完全脱节。

### 5.3 Workflow / Job 进度

- `JobInstanceList` / `WorkflowRunList` 走 SSE,实时性好。
- `HeartbeatProgressPanel` 用 `el-progress` 显 `extractProgressPercent(details)` 真实数据,**可信(真动)**。
- `BatchDayReplay` 的 progress 列用 `succeededEntries / totalEntries` 真实计算,**可信**。
- `SchedulerSnapshot` / `WorkerFingerprintBoard` 进度条同样真实数据驱动。

### 5.4 Import / Export / Upload 进度

- **(P0)**`useImportWizard` 三步 upload→preview→apply 仅暴露 `upLoading / pvLoading / wbLoading / tplLoading / exportLoading` 五个布尔——**没有任何 percent**。大文件导入(几百行 Excel)用户面对的就是无限旋转的 spinner,**无 ETA、无吞吐、无可信反馈**。
- **(P0)**全站 `onUploadProgress` 0 处使用(axios 提供原生上传进度),Excel/ZIP 上传只能看浏览器底栏。EP `<el-upload>` 也未走 progress slot。Apply 阶段后端处理时间可达分钟级,FE 完全无进度。
- **(P1)**Export 同样 `exportLoading` 一个布尔,无 percent。

### 5.5 进度可信度审查(假动 vs 真动)

- 检视所有 `<el-progress>` 5 处,**全部由真实数值驱动**——没有发现"假动"(纯 CSS 动画 / 计时模拟)的情况,这点干得不错。

---

## 6. Confirmation —— 21 处旧调用 + 2 处吞 Cancel 是 P0

### 6.1 useDangerConfirm 设计

- `verb + target + consequence + irreversible` 四段结构,irreversible 时:
  - 标题加 `⚠️ {verb}{target}`,文案"此操作不可恢复"
  - `confirmButtonClass: 'el-button--danger'` —— **唯一把按钮染红的入口**
  - `closeOnClickModal: false` 防误关
  - confirm 按钮文案自动拼 `确认{verb}`
- 用户点取消会 reject,外层 `try/catch` 处理。**默认 reject 语义是正确的**。

### 6.2 落地

- 50 处用了 `confirmDanger`,**21 处仍是裸 `ElMessageBox.confirm`**(WorkerManagement.drain/takeover 仍裸 confirm;ConfigSecretsTab.rotate 裸 confirm;FileList / ArrivalGroupList / PartitionView / AlertRoutingPanel / WorkflowDefinitionList / UserAccountList / JobDefinitionList / PipelineDefinitionList / TenantPackageImportWizard 各 1-2 处)。
- 这些直接调用用全部**:
  - 没有 `confirmButtonClass: 'el-button--danger'` —— danger 按钮不红;
  - 文案只有 `type: 'warning'`,没有 consequence 段;
  - cancelButton 在 confirm 左边(EP 默认),违反国内多数后台"危险操作右下、取消左下"惯例;
  - 大多数用 `t('xxx.confirmText')` 通用文案,**抽查文案如 `workerManagement.drainConfirmText`**:`即将 drain worker {code},正在执行的任务会迁移走` —— 还行;`rotateConfirmText`:`确定要轮换密钥 {name} 吗?` —— **不说后果**。

### 6.3 P0 / P1 缺陷

- **(P0)**`BatchDayReplay.vue:440,467` 模式 `await ElMessageBox.confirm(...).catch(() => null)` —— Cancel 不阻断流程(见 §2.4)。
- **(P1)** 21 处裸 confirm 应迁移到 `useDangerConfirm`,统一文案 + 红色按钮 + 后果段;同时给 `WorkerManagement.takeover / drain / warmup` 改 irreversible 半区(takeover 实际可逆,drain 可逆,但生产影响大)。
- **(P1)**`useDangerConfirm` 缺"输入确认 token" 选项(注释里写了 "需类型确认 token(可选)" 但未实现)。物理删除租户 / 永久销毁 secret 这类不可逆操作,应当强制用户输入资源名才能 confirm —— 行业最佳实践(GitHub / GitLab 都这么干)。

### 6.4 wording 与按钮顺序

- EP 默认 `confirm` 在右,`cancel` 在左,与 macOS 一致,与 Windows / 中国后台常见相反。`useDangerConfirm` 没显式设置 `distinguishCancelAndClose`,体感无问题。但 irreversible 时 `closeOnPressEscape: true` —— **不可恢复操作允许 Esc 即关闭**,大多数 banking 后台会禁用,值得讨论。**(P2)**

---

## 7. Web Push / Bell / 跨页通知 —— 骨架就绪,0 接入

### 7.1 useWebPush 设计

- iOS 16.4+ standalone 检测、VAPID 公钥拉取、`urlBase64ToUint8` 转换、SW 订阅、上报 BE(`/api/console/push/subscribe`)。骨架完整,**良好**。

### 7.2 实际接入

- **(P0)** `grep -rn requestPushPermission src/` 在非 `useWebPush.ts` 本身的命中**为 0**。也就是说:
  - 没有任何 UI 入口让用户启用 Web Push;
  - `isPushSupported` / `isInStandalone` / `unsubscribePush` 全是 dead code;
  - 后端 `/api/console/push/vapid-public-key` 已实现(假设)但前端**从未调用**;
  - 用户离开 console 后,即便有告警 / 审批待办,**没有任何方式收到通知**(没有 ElNotification 系统通知卡片,没有 Web Push,没有 email 集成在前端展示)。
- 注释里写"调用方应在用户显式行动(点击 Enable notifications 按钮)后调",**那个按钮不存在**。

### 7.3 Bell / 红点 / 待审批 / 告警 / 任务

- **(P0)桌面 `LayoutHeader.vue` 通览(562 行)右侧只有**:命令面板 / 工具下拉(docs/locale/theme/focus) / 租户 chip / 用户名下拉。
  - **没有 Bell 图标**;
  - **没有红点**;
  - **没有任何 pendingApprovals / openAlerts / criticalAlerts 实时计数**;
  - **没有"最近 N 条通知"下拉面板**。
- 移动端 `MobileTabBar` 有红点(直接渲染 `mobile-tab__badge`,数字 99+ 处理),`mobileBadges` store 已经把数据准备好了 —— **后端能力存在,前端 store 存在,桌面端 UI 完全缺失**。
- `el-badge` 组件全站 0 命中。

### 7.4 Notification permission

- `Notification.requestPermission()` 只在 `useWebPush.ts` 内部出现一次(还没 UI 入口触发)。也意味着浏览器原生 Notification(`new Notification()`)**也没用上** —— 即便用户给了权限,告警来了 SW 收不到 push、JS 也不主动 `new Notification()`,这条链路完全断。

---

## 8. 离线 / 网络断网 —— 框架完整,与 axios retry 协同好

- `useNetworkStatus` 用 `navigator.onLine` + `online/offline` 事件,offline 持久 toast(`duration: 0`),online 2s success toast。
- 与 `interceptors.ts` 的 GET 指数退避(网络错也算 retryable)协同 —— **短抖用户无感,长断网才弹**,合理。
- `EmptyState` 有 `offline` / `network` variant,但 `DataState` 不会自动识别 error 是不是 NetworkError,目前一律 `variant: 'error'`。**(P2)**:可以扩展 `DataState` 判 `error?.message === 'Network Error'` 就切 `network` variant。

---

## 9. TanStack Query 与乐观更新 —— 配置好,乐观 0 落地

### 9.1 全局配置(`main.ts`)

```
staleTime: 30s, gcTime: 5min, retry: 1, refetchOnWindowFocus: false
```

`staleTime: 30s` 对 oncall 监控场景**偏长**(JobInstance 列表数据 30s 内不重取,即便用户切回 tab)。建议 SSE 接管的 view 显式 `staleTime: 0` 让 SSE 抖动后能马上 refetch。**(P1)**

### 9.2 使用面

- `composables/queries/` 仅 3 个 hook:`useJobDefinitionsPaged` / `useWorkers` / `useConsoleMeta`。
- `api/queries/` 2 个文件。
- **(P0)** `onMutate` / `setQueryData` / `optimisticUpdate` **全站 0 命中**。所有 mutation(approve / reject / drain / takeover / restart / delete...)都走"等 mutation 完 → invalidateQueries → 重拉" 的悲观路径。
  - 后果:大列表批量审批,每点一次 approve 都要等一次 round-trip 才能看到状态变化,**用户体感慢 200-500ms**。
  - 经典优化:`onMutate` 把这条 row 状态先改为 "审批中",失败 `onError` 回滚。但这需要稳定的 row identity 设计,目前 mutation 函数大都散在 view 里,迁移成本约 1-2 sprint。

### 9.3 跨租户失效

- `useJobDefinitionsPaged` queryKey 里包含 `effectiveTenantId`,切租户自动失效。✓
- `useTenantReload` 给非 TanStack 的列表手动注册 callback,租户切换后清空。✓

---

## 10. P0 / P1 / P2 缺陷清单

### P0(共 5 条,阻塞性 / 安全相关)

| # | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| P0-1 | `views/ops/BatchDayReplay.vue:440,467` | `await ElMessageBox.confirm(...).catch(() => null)` 让 Cancel 等于 Confirm,导致 ops 高危操作被误执行 | 改成 `try { await confirmDanger({...}) } catch { return }` 标准 pattern |
| P0-2 | `layout/components/LayoutHeader.vue` 整个 right 区 | 桌面无 Bell / 红点 / pending 计数入口,审批员 / 告警 oncall 切走页面后零感知 | 加 `<el-badge>` + Bell icon + 下拉面板(复用 `mobileBadges` store) |
| P0-3 | `composables/useWebPush.ts` | Web Push 全栈骨架完整,UI 入口 0 处,实际无人能开启,后端 endpoint 浪费 | 在用户设置页 / Bell 面板加 `Enable notifications` 按钮 + permission 状态 chip |
| P0-4 | `composables/useImportWizard.ts` + 全站 upload | 0 处 `onUploadProgress` / 0 percent,大文件导入无进度无 ETA | axios 配 `onUploadProgress`,wizard 渲染 `<el-progress>`,apply 阶段后端轮询 status |
| P0-5 | `views/approvals/ApprovalList.vue` + `views/ops/composables/useOpsSummary.ts:244-245` | Approvals 无 SSE/轮询;OpsSummary 用 `.catch(() => null)` 吞失败显 0 → oncall 误判系统正常 | Approvals 接 `useSseAutoReload({domain:'approvals'})`;OpsSummary 加 error 态 + 重试 |

### P1(共 12 条,体验 / 一致性)

| # | 问题 | 范围 |
|---|---|---|
| P1-1 | `DataState` 11 处 vs `v-loading` 221 处,空态分类未真正铺开 | 全站 list 视图 |
| P1-2 | `useAsyncAction` 仅 26 处,`cooldownMs` 仅 3 处,双击防抖未铺开 | 所有 mutation 按钮 |
| P1-3 | 21 处裸 `ElMessageBox.confirm` 未迁移到 `useDangerConfirm`,danger 按钮不红、无 consequence | 见 §6.2 文件清单 |
| P1-4 | `DataState.emptyVariant` 11 处调用绝大多数没传 `filter-empty` / `no-permission`,空态退化 | `JobInstanceList` / `WorkflowRunList` / `AlertList` |
| P1-5 | TanStack `staleTime: 30s` 全局过长,与 SSE 实时性冲突 | `main.ts` |
| P1-6 | 0 处乐观更新,大列表批量 mutation 体感慢 | TanStack 改造 |
| P1-7 | `useDangerConfirm` 缺 "输入资源名才能确认" 二次保护 | 物理删 / 销毁 secret |
| P1-8 | axios client 未设默认 `timeout`,hang 的请求让按钮永久 loading | `api/client.ts` |
| P1-9 | `WorkflowMermaidViewer` 等用 `.catch(() => null)` 吞详情失败,UI 当无数据 | 见 §2.4 |
| P1-10 | `MaintenanceBanner` 无关闭按钮、无"了解更多"链接 | 用户教育 |
| P1-11 | `useNetworkStatus` 不主动 `extractHttpErrorMessage` 联动:断网期间 toast 还在显示业务 4xx | 拦截器在 offline 时跳过弹 toast |
| P1-12 | `OpsSummary` 没有 error 态,失败查询渲染为 0 | 见 P0-5 |

### P2(共 6 条,优化)

- P2-1:批量操作"已选 N 条"常驻计数缺失。
- P2-2:`useDangerConfirm` 不可逆操作允许 Esc 关闭,可讨论。
- P2-3:`EmptyState` 多数 variant 缺 CTA(`tenant-empty` 不引导切租户)。
- P2-4:无 ElNotification 长持续通知卡片,toast 单通道。
- P2-5:`DataState` 不能自动从 `error.message` 推断 `network` variant。
- P2-6:`useNetworkStatus` 跨多次 offline→online 边界场景脆弱,建议加 `previousOnline` 状态机。

---

## 11. 修复路径建议(2 个 sprint)

**Sprint A(2 周)—— 收 P0**

1. 改掉 BatchDayReplay 两处 `.catch(() => null)`(0.5 天)。
2. 桌面 Bell 组件:`LayoutHeader` 加 `NotificationBell.vue`,内含 `<el-badge>` + 下拉 panel,复用 `mobileBadges` store + 桌面侧增加 5 min polling fallback(若无 SSE),实时 SSE 接入 `notifications` domain(2-3 天)。
3. Web Push 启用入口:Bell 面板"设置"区加 `Enable browser notifications` 按钮调 `requestPushPermission`,展示 `Notification.permission` 状态(1 天)。
4. Upload progress:`api/client.ts` 暴露 `onUploadProgress` 钩子,`useImportWizard.upload()` 写 percent ref,wizard 模板加 `<el-progress :percentage="uploadPct">`(1-2 天)。
5. Approvals SSE:`ApprovalList` 接 `useSseAutoReload({domain:'approvals'})`,后端补 SSE domain;过渡期用 `useAutoRefresh` 30s polling(0.5 天)。
6. OpsSummary 错误态:去掉 `.catch(() => null)`,加 `<EmptyState variant="error">` + 重试(0.5 天)。

**Sprint B(2 周)—— 收 P1**

1. `axios timeout 30s` + `_silent` toast 在 offline 时跳过(0.5 天)。
2. `DataState` 全站推广脚本化迁移 :10-15 个高流量 list 强迁(2-3 天)。
3. 21 处裸 confirm → `useDangerConfirm`(2 天)。
4. `useAsyncAction` 全站推广 + `cooldownMs: 300` 默认(2 天)。
5. TanStack `staleTime` 分场景调:SSE 接管 view → 0,普通配置查询保持 30s(0.5 天)。
6. 在 5 个最高频列表(JobInstance / WorkflowRun / Alert / Outbox / Approval)做乐观更新原型(3-4 天)。

---

## 12. 与 BE 的协同点

- BE 需要新增 `notifications` SSE domain(对接 `useSseAutoReload`,Bell 实时推)——已经有 `alerts` / `approvals` 队列基础,挂事件桥即可。
- BE `getOpsSummary` 已经返回 `pendingApprovals / openAlerts / criticalAlerts / failedJobs`,前端桌面 Bell 直接复用(已验证 store 接通)。
- Web Push 后端 endpoint(VAPID / subscribe / unsubscribe)已实现(由 `useWebPush.ts` 调用契约推断),只缺前端入口。
- 维护模式 `X-Maintenance` / 降级 `X-Degraded-Source` header 协议已落地,**继续保留,不要拆**。
- BizException i18n key 协议下发对 EN 用户重要,建议 BE 团队规范化所有 `error.{domain}.{code}` 字典并补 EN 翻译。

---

## 13. 与既有审计的差异

- 之前 `frontend-issue-handoff-2026-05-17.md` / `workflow-ui-redesign-campaign-2026-05-15.md` 聚焦于 workflow 编辑器替换 + 列表布局 + 配色。
- 本次扫描专注**反馈系统的可信度链路**,P0 集中在"用户感知不到 / 操作被误执行 / 离开页面后零通知" —— 这与之前的"视觉 + 布局"维度不重叠。
- `industry-benchmark-improvement-plan.md` 提到要"建立 confirm + toast + empty 三件套" —— 本次确认前两件已建,empty 已有 9 variant,但**铺开比例 5-15%**,卡在迁移而非设计。

---

## 14. 评分(满分 5)

| 维度 | 评分 | 备注 |
|---|---|---|
| Loading 框架设计 | 4.5 | useAsyncAction + DataState + RouteProgressBar 三层完整 |
| Loading 铺开比例 | 2.0 | DataState 5%、useAsyncAction 15% |
| Error 拦截器 | 5.0 | HTTP/Biz/Trace 分流 + 静默 refresh + 维护透传 + 幂等静默 |
| Error 翻译 | 3.5 | i18n key 机制可,中文 message 回退缺 EN |
| TraceId 暴露 | 5.0 | 复制按钮 + 整段点击 + clipboard fallback |
| Toast 一致性 | 4.0 | grouping 防重 + 错误统一走 errorToast,但 ElMessage 散落 238 处 |
| 空态分类 | 4.0 | 9 variant 设计好,铺开少 |
| 维护 / 降级 banner | 5.0 | ETA + 子系统 chip + 协议透传 |
| 离线 / 重连 | 4.0 | navigator.onLine + axios retry 协同,UI 联动差 |
| 进度可信度 | 4.0 | 都是真数据,但 Import/Upload 完全缺 |
| Bell 红点(桌面) | 0.0 | 不存在 |
| Web Push 接入 | 0.5 | 骨架完整,0 调用点 |
| Confirmation 安全 | 3.0 | useDangerConfirm 好,但 21 处直接调用用 + 2 处吞 Cancel |
| 乐观更新 | 0.0 | 0 处 |
| **综合** | **3.2 / 5** | 设计强,落地割裂 |

---

## 15. 附录:关键文件清单

- 拦截器:`src/api/interceptors.ts` (697 lines)
- 错误 toast:`src/utils/errorToast.ts`
- 异步动作:`src/composables/useAsyncAction.ts`
- 列表加载态:`src/composables/useListLoadState.ts` / `src/components/common/DataState.vue`
- 空态:`src/components/common/EmptyState.vue`
- 危险确认:`src/composables/useDangerConfirm.ts`
- 网络:`src/composables/useNetworkStatus.ts`
- 维护:`src/composables/useMaintenancePolling.ts` / `src/components/common/MaintenanceBanner.vue`
- 降级:`src/components/common/DegradationBanner.vue`
- 路由进度:`src/components/common/RouteProgressBar.vue` / `src/stores/routeProgress.ts`
- SSE:`src/composables/useSseAutoReload.ts`
- Web Push:`src/composables/useWebPush.ts`(**未接入**)
- Error 边界:`src/components/common/ErrorBoundary.vue`
- 移动徽章:`src/stores/mobileBadges.ts` / `src/layout-mobile/MobileTabBar.vue`
- 桌面 Header:`src/layout/components/LayoutHeader.vue`(**缺 Bell**)
- 导入向导:`src/composables/useImportWizard.ts`(**缺 percent**)

## 16. 详细取证 —— 高频反馈链路 walk-through

### 16.1 用户点 "保存配置" 按钮的完整反馈链(理想路径)

1. `@click="save.run(row)"`,`save = useAsyncAction(async () => api.upsert(row))`
2. `busy.value = true` → EP `el-button :loading` 自动 disabled + spinner —— **用户无法二次点**。
3. axios `request.use`:自动生成 `Idempotency-Key`(stable signature on body+url+tenantId+method),写入 `_autoIdempotencySignature` 以便响应阶段标记完成。
4. 请求出门,BE 写入数据。
5. axios `response.use`:`markAutoIdempotencyCompleted(cfg)`,记 info 日志(只摘 envelope),空 data。
6. `save.run` resolve,`busy = false`,cooldown 计时(若配置)。
7. 调用方 `await save.run(...)` 后 `ElMessage.success(t('xxx.saveOk'))`。

**整条链路 0 bug**,但**条件**是调用方真的用了 `useAsyncAction`。

### 16.2 用户连点 5 次 "保存"(`useAsyncAction` 缺失场景)

1. 第一次 click 出门 → BE 处理中 200ms。
2. 第 2-5 次 click(用户手抖 / 网络慢觉得没响应):**EP button 没 :loading 就不会 disabled** → 5 个请求出门。
3. 第 2-5 个请求 axios `request.use` 计算 signature → 命中 2s 复用窗口 → 复用同一 `Idempotency-Key`。
4. BE 幂等拦截器 → 后 4 个返 409 IDEMPOTENT_REPLAY。
5. axios `response.use(error)`:检测 `_autoIdempotencySignature || envelope.code === 'IDEMPOTENT_REPLAY'` → **静默 reject**,不弹 toast,不写 error log。
6. 第一个请求成功 → `ElMessage.success` 弹一次。

**结论**:即便 `useAsyncAction` 缺失,L2(FE 自动 key)+ L3(BE 幂等)双拦截让用户感知到的就是"按钮没反应再点几下,最后弹一次成功"。**风险**:若用户认为没保存又改了字段再点,第 6 次 click 落到不同 signature 上,这次是新提交,**会用到改后的字段**——若 BE 已经处理了第一次的数据,UI 没及时 refetch,用户看到旧值会困惑。

### 16.3 用户在审批列表停留 10 分钟期间收到 5 条新审批

- 桌面端:**完全不感知**(无 SSE、无轮询、无 Bell 红点、无 ElNotification 卡片)。
- 移动端:`MobileLayout` 30s polling `getOpsSummary` 更新 `pendingApprovals`,`MobileTabBar` 红点 `5`(或 `99+`)。
- 用户主动点刷新或切到别页再回来 → 走 KeepAlive deactivated → activated 不强制 reload(KeepAlive 默认缓存最近 20 个 view)。
- **(P0)** 这是 §7 P0-2 / P0-5 的核心场景。

### 16.4 用户在 BatchDayReplay 详情页点 "审批 → 取消"

1. `doApprove()` 调用。
2. `await ElMessageBox.confirm(...).catch(() => null)` → 用户点 Cancel → Promise reject(`'cancel'`)→ `.catch(() => null)` 吃掉 → 继续往下。
3. `approving.value = true` 按钮变 loading。
4. **`batchDayReplayApi.approve(...)` 真的发请求出去** → 后端 approve 成功 → `currentSession` 更新为 approved 状态。
5. `ElMessage.success(approveOk)` 弹"已审批"。
6. 用户:???我刚点的是取消。

**P0**。修复模板:
```ts
try {
  await confirmDanger({
    verb: t('batchDayReplay.approveBtn'),
    target: t('batchDayReplay.sessionTarget', { id: ... }),
    consequence: t('batchDayReplay.approveConsequence'),
  })
} catch {
  return  // cancel
}
// then proceed
```

### 16.5 用户在 OpsSummary 看到"告警 0、失败 Job 0、待审批 0"

- `useOpsSummary.ts:244-245` 把 `getDashboardSlaReport` / `getDashboardTenantUsage` 用 `.catch(() => null)` 降级。
- 任一接口偶发 500 → 返 null → UI 渲染 null 为 0 / `—`。
- oncall 工程师早上 9 点打开 OpsSummary 看到全 0 → 假定"昨晚没事" → 实际是 BE 一个 dashboard 接口异常退出。
- **P0**。修复:把 catch 改成 `.catch((e) => { dashboardSlaError.value = e; return null })`,并在 UI 区块显式渲染 `<EmptyState variant="error" :on-retry="reload" />`。

### 16.6 用户上传 50 MB Excel

- `<el-upload>` 默认 onChange → `useImportWizard.onFile`
- `upLoading.value = true`
- POST 出门,axios 没设 `onUploadProgress` → 浏览器原生 fetch 进度只有浏览器底栏知道。
- 用户面对 spinning loader 30-60s,**完全不知道是上传中、还是 BE 处理中、还是长期停滞**。
- 中间任何瞬时网络抖动 → axios 重试(GET 才重试,POST 不重试)→ 直接失败 → toast"网络不可达"。
- 用户的反应:重新选文件、再传一遍。
- **P0**。这正是 P0-4。

### 16.7 用户网络断了 3 秒(地铁)

1. 浏览器 offline 事件触发 → `useNetworkStatus` 持久 toast"网络断开"。
2. 期间用户点的所有按钮:axios 请求出门时 fetch 立刻报 NetworkError。
3. axios `response.use(error)`:无 response → 走 retry 路径(2 次,指数退避 0.5s + 1s),5s 总耗时。
4. 5s 后用户 online → retry 命中 → 成功。
5. `useNetworkStatus` online toast。

**这条链路设计合理,但**:期间 toast 同时有"网络断开"持久 + 各个 mutation 的"请求失败"(P1 mutation 不重试) → toast 堆叠 3-5 条。建议:axios 拦截器看 `navigator.onLine === false` 时直接跳过弹 toast,只 reject。**(P1-11)**

---

## 17. 文案审计(抽样 15 处)

| 文案 key | 中文 | 评分 | 备注 |
|---|---|---|---|
| `empty.default` | "暂无数据" | 3 | 通用文案,可 |
| `empty.error` | "加载失败" | 2 | 缺重试引导文案 |
| `empty.filterTitle` | "无匹配数据" | 4 | 区分清 |
| `empty.noPermissionTitle` | "权限不足" | 3 | 缺"申请权限"CTA |
| `error.forbidden` | "访问受限" | 3 | OK |
| `network.offline` | "网络已断开,请检查连接" | 5 | 清晰 |
| `network.online` | "网络已恢复" | 5 | 清晰 |
| `pwa.updateReady` | "新版本已下载" | 3 | 缺"包含什么更新" |
| `nav.confirmLogout` | "确认退出登录?" | 2 | 没说后果(未保存草稿等) |
| `workerManagement.drainConfirmText` | "即将 drain worker {code}" | 3 | "drain" 中文用户不一定懂 |
| `configSecretsTab.rotateConfirmText` | "确定要轮换密钥 {name} 吗?" | 2 | 不说轮换影响 |
| `batchDayReplay.approveConfirmHint` | "确认审批此批次回放?" | 2 | 不说回放规模 |
| `maintenance.bannerBlocked` | "系统维护中,部分功能不可用" | 5 | 清晰 |
| `degradation.bannerHeadline` | "服务降级中" | 3 | 一般用户不懂"降级"含义 |
| `pwa.offlineReady` | "已可离线访问" | 4 | 好 |

**文案统一原则建议**(给 BE 团队和 i18n 维护者):

1. 标题:**动词开头**(确认 / 提交 / 删除...),不用问句。
2. 内容:1 句说**对象**,1 句说**后果**(可省略 reversibility,由组件加默认尾巴)。
3. 按钮:`confirm` 文案 = `标题动词 + 对象`,`cancel` = "取消"。
4. Toast:**成功**用过去式("已审批" 而非 "审批成功"),**失败**用动词+原因("审批失败:权限不足")。
5. 国际化:所有专有词("drain" / "warmup" / "degraded")提供 zh 注释或 tooltip。

---

— Audit by Opus 4.7, 2026-06-03
