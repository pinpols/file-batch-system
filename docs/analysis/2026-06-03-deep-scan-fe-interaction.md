# 深扫 FE 交互层 — batch-console (Vue3 + TS + Pinia + Element Plus)

- 日期: 2026-06-03
- 范围: `/Users/dengchao/Downloads/batch-console` 整仓只读扫描,聚焦 **交互层** (表单 / 键盘 / 筛选排序分页 / 批量 / 拖拽 / 复制粘贴 / 撤销 / 错误恢复 / Toast / 模态 / 核心场景)
- 方法: grep + 阅读关键 view/composable/util 源码; 不跑 build/test
- 输出形式: 问题清单(P0/P1/P2) + 一致性观察 + 验证脚本建议
- 文档约定: 路径全部相对 batch-console 仓根

---

## 0. 高层结论 (TL;DR)

batch-console 在通用基础设施上做得相当扎实:

- `ProTable` 统一了空态/错误态/骨架屏/分页持久化(`pro-table:pageSize:` per-route localStorage)
- `ListPageQueryBar` 统一查询区布局/loading/refresh
- `useListFilterFeedback` / `useBriefActionLoading` 统一短查询/操作的视觉反馈
- `useFormValidate` 把 `await formRef.value?.validate().catch(...)` 样板压成一行
- `useDangerConfirm` 强制危险操作"动词+对象+后果+可逆性"四段
- `errorToast` + `messagePatch` (强制 `grouping=true`) 统一 toast 形态
- `interceptors.ts` 把 401/403/404/5xx/maintenance/网络重试/refresh-token/silent flag 各种边界都覆盖到了

**但交互层有 11 个 P0 / 14 个 P1 缺口**,集中在:

1. **整站零 dirty-check / leave-prompt** — 用户在任何抽屉/弹窗里填到一半,点遮罩或按 Esc 直接丢
2. **CSRF/XSRF token 完全缺失** — cookie-based JWT + `withCredentials: true`,但请求头无 CSRF token
3. **批量操作只有 1 个页面用了** (`GeneralApprovalsTab`),其余 30+ 列表页无多选/批量删/批量启用
4. **筛选条件 URL 双向同步组合化空缺** — 仅 `useRouteFilters` 这一个 composable,实际 view 里**零调用**,所有页面手写部分映射,从详情页 back 回列表筛选丢失
5. **键盘可达性不及格** — 大部分 dialog/drawer 打开不 autofocus,无 Tab/Esc 标准化,焦点陷阱缺失
6. **请求无 AbortController** — 切页/快速重搜时旧请求继续跑,response race condition 不可控

---

## 1. 表单 (Form / Validation)

### 1.1 P0 — 整站零异常数据保护
**位置**: 全仓所有 `el-dialog` / `el-drawer` (共 30 个 dialog + 38 个 drawer)
**证据**:
- `grep -rn "beforeRouteLeave|onBeforeRouteLeave|beforeunload|isDirty" src --include="*.vue" → 0 hit`
- 唯一的 `:before-close` handler 模式(`src/views/job/JobDefinitionList.vue:1118`, `views/config/ConfigReleaseList.vue:414`, `views/system/UserAccountList.vue:455`, `views/ops/BatchDayReplay.vue:396`, `views/job/components/WindowMiniCreateDrawer.vue:184`, `views/job/components/CalendarMiniCreateDrawer.vue:142`)只检查 `if (saving.value) return`,**没有一处比对 form 与初始 snapshot**

**典型实现**:
```ts
function onCreateDrawerClose(done: () => void) {
  if (createSaving.value) return  // 保存中阻止关闭
  done()                          // 没保存就直接关 — 用户输入全丢
}
```

**影响**:
- 用户在 `JobDefinitionWizard.vue` (754 行,5 步向导,十几个字段)填到第 4 步,误点遮罩 → 全部清空
- `TenantBatchCreateDialog.vue` 批量录入 30 个租户,意外按 Esc → 全丢
- 全站零 `beforeunload` 监听,刷新/关 tab 也无警告

**建议**:
1. 写一个 `useDirtyGuard(initialSnapshot, currentRef)` composable,返回 `isDirty` 计算 + `confirmLeave` 包装
2. 所有抽屉默认 `:close-on-click-modal="false"` (现在已经在 ApiKeyList 和 wizard 个别地方做了,需统一)
3. `:before-close` 改为 `if (saving.value) return; if (isDirty.value) { await confirmLeave(); done() } else done()`
4. 路由级也要装 `onBeforeRouteLeave`(当前 Wizard 在 5 步之间切换都无保护)

---

### 1.2 P0 — `el-form` 校验时机不一致
**位置**: `views/job/JobDefinitionWizard.vue:413-416`, `views/job/components/CalendarMiniCreateDrawer.vue:113-138`, `views/system/components/TenantCopyConfigDialog.vue:134`, 等等
**证据**:
- 同一字段类型用了 3 种 trigger: `'blur'` (主流) / `'change'` (`CalendarMiniCreateDrawer.vue:138`) / `['blur', 'change']` (`CalendarMiniCreateDrawer.vue:113`)
- `MetaSelect`/`el-select` 没有 blur 事件意义,但还在写 `trigger: 'blur'` (`PipelineDefinitionList.vue:591`),用户**选完不会立即校验**,要点提交才知道错
- 大量自定义 `validator` 用 callback 旧 API (`JobDefinitionWizard.vue:400` / `TenantCopyConfigDialog.vue:130`),Element Plus 推荐 `Promise<void>` 风格

**建议**:
- 在 `useFormValidate` 旁边加一个 `defaultRuleTriggers` 常量: input 类用 `'blur'`,select/checkbox/date-picker 用 `'change'`,密码强度类用 `['blur', 'input']`
- 老的 `(rule, value, cb) => cb()` validator 统一改 `async (_, v) => { if (!ok) throw new Error(msg) }`
- ESLint 规则: select rule trigger 必须含 `'change'`

---

### 1.3 P1 — `useFormValidate` 推广没做完
- `grep -c "useFormValidate" → 29 views`
- `grep -c "formRef.value?.validate" → 11 views`  (剩余的样板)
- 剩 11 处还是直接调用用,包括 `views/login/Login.vue:131`, `JobDefinitionList.vue:1126`, `JobDefinitionWizard.vue` 等核心场景
- 建议在 ESLint 加 `no-restricted-syntax` 禁止 `formRef.*.validate(`,强制走 composable

### 1.4 P1 — 字段默认值散落,无 schema 统一
- 每个 dialog 内手写 `reactive({ jobCode: '', jobName: '', ... })` + reset 时再赋空串(`WindowMiniCreateDrawer.vue:200-204` 一字一字清),漏 reset 的字段会在二次打开时带上旧值
- 建议: 每个 form 配一个 `createInitial(): Form` 工厂,open/close/reset 都走 `Object.assign(form, createInitial())`

### 1.5 P1 — label-width 不统一
- `grep label-width | awk` 显示: 88px(18) / 120px(14) / 100px(2) / 92px(1) / 84px(1) / 72px(1) / 56px(1) / 190px(1) / 160px(1) / 128px(1)
- 用户在不同对话框看到 label 区宽度跳动,扫视性差
- 建议: 用 `--form-label-w-sm/md/lg` CSS 变量(72/96/128),全站收口三档

### 1.6 P1 — `size` 属性混用
- `size="small"` 276 处 / `size="large"` 6 处 / `size="default"` 4 处 / 多数省略默认 medium
- Login 页用 `size="large"`,普通 dialog 表单不指定(medium),query bar 经常用 small → 同一个用户在三种尺寸之间切换
- 建议: 全站统一在 `App.vue` 注入 `<el-config-provider size="default">`,仅 Login 这种"独立心智"页例外

### 1.7 P2 — 异步 validator 无防抖 / 无 cancel
- `views/job/JobDefinitionList.vue:1057` 等地方有自定义 validator,但调用 BE 的远程 validator 全仓只有零星几个,且无防抖,Tab 切换时旧请求继续解析

---

## 2. 键盘 & 焦点 (Keyboard / A11y)

### 2.1 P0 — Dialog/Drawer 打开不 autofocus 输入框
**位置**: 全部 30 个 `el-dialog` 和 38 个 `el-drawer`
**证据**:
- 桌面端 `grep "nextTick.*focus|inputRef.value?.focus|autofocus" src/views src/components → 0` (仅 `CommandPalette.vue:340` 例外)
- 移动端 mobile views 反而做了 (`MJobInstances.vue` 等多处)
- 打开"新建作业向导"用户必须再用鼠标点 jobCode 输入框才能开始打字

**建议**: 包一层 `BaseDialog` / `BaseDrawer`,接受 `autofocus-target="jobCode"`,内部 `watch(visible)` 配 nextTick + DOM querySelector focus

### 2.2 P0 — Enter 提交在多数表单不生效
- `@submit.prevent` + `native-type="submit"` 配对仅在 `Login.vue` 一处完整 (form 上 `@submit.prevent="handleLogin"` + button `native-type="submit"`)
- 其他 dialog 大都 `@submit.prevent` 空挂(`JobDefinitionList.vue:233`),用户在最后一个字段按 Enter 啥也不发生,必须用鼠标点"确定"
- `grep "native-type=\"submit\"" → 12 处` vs `el-dialog → 30 处`

**建议**: dialog 内 form 强制走 `<el-form @submit.prevent="submit"><...><el-button native-type="submit">`,Enter 自动提交

### 2.3 P0 — 无 Esc 关闭一致性
- Dialog/Drawer 默认 Esc 关,但 `:close-on-press-escape="false"` 在 `ApiKeyList.vue:144` 单独关掉
- 全局只有 `DefaultLayout.vue:83` 把 Esc 用来退 focusMode;`JobInstanceDetail.vue:629` 把 Esc 当返回
- 没有"Esc 关 dialog vs Esc 触发表单 reset/cancel"的统一规范,用户行为不可预测

### 2.4 P0 — 焦点陷阱 (focus trap) 缺失,叠加 dialog 时 Tab 漏走背景
- `grep "trap-focus|FocusTrap" → 0 hit`
- EP `el-dialog` 自带 modal,但当 dialog 内再开 `ElMessageBox.prompt` (如 `GeneralApprovalsTab.vue:319` 的 approveRow),焦点会逃出顶层 modal 落到后面的页面 row
- 测试: 在审批页打开"批量驳回",在 prompt 框按 Tab 几次能 focus 到背景的查询输入框

### 2.5 P1 — Tab 顺序在自定义 query 区错乱
- `ListPageQueryBar` 用 `el-form inline`,字段顺序 = 模板顺序,但很多页把 MetaSelect 和 el-input 混着放,select 内部 popper 的 tabindex=-1,Tab 一次会跳过整个组件
- 建议: 给 query bar 加 keyboard nav 验证 Playwright e2e

### 2.6 P1 — 快捷键文档化空缺,只有 Cmd+K
- `DefaultLayout.vue:67` 全局 `Cmd/Ctrl+K` 打开 CommandPalette
- 没有 / 没暴露: `Cmd+S` 保存表单 / `Cmd+Enter` 提交 / `Cmd+\` 折叠侧栏 / `r` 列表刷新等
- 建议: 在 CommandPalette 顶部放一行"快捷键"chip,onboarding tour 也带上

---

## 3. 筛选 / 排序 / 分页

### 3.1 P0 — `useRouteFilters` 已写好但**零调用**
**位置**: `src/composables/useRouteFilters.ts:73`
**证据**:
- composable 实现完整,onMounted 从 query 恢复 + watch deep + replace 同步
- `grep -rn "useRouteFilters" src/views → 0`
- 实际 view 各自手写: `GeneralApprovalsTab.vue:316` `filters.status: route.query.status ? ...` 只恢复一个字段;`ApprovalList.vue:32` 只同步 `tab` 一字段;`WorkflowDefinitionList.vue` / `JobDefinitionList.vue` 都没做

**影响**:
- 用户从 `/monitor/job-instances?status=FAILED&jobCode=xxx` 这种深链进入,只看到一半参数生效
- 用户筛 50 个条件 → 进详情 → router.back → **筛选全清空**,得重新选
- 复制 URL 分享给同事 → 同事打开看到的是默认全量

**建议**:
1. 全列表页强制走 `useRouteFilters({ status: '', jobCode: '', dateRange: undefined })`
2. ProTable 的 `page` / `pageSize` 也应该入 URL (现在只 localStorage 持久化,跨用户/隐私模式丢)
3. PR template 加 checklist: "新列表页?用了 useRouteFilters 吗?"

### 3.2 P0 — 分页 page 不进 URL,返回上一页跳 1
- `ProTable` 只 localStorage 持久化 `pageSize`,`page` 完全在内存
- 用户翻到第 3 页 → 点详情 → router.back → 回到第 1 页
- 建议:`useRouteFilters` 扩展支持 `page` / `pageSize` query 双向

### 3.3 P1 — 排序 (sort-change) 全仓未做服务端排序
- `grep -rn "sort-change|@sortChange|sort-orders" → 几乎 0` (主要 ProTable 透传 el-table,但 view 不监听)
- 用户点 column 头排序只是前端本地排;翻页后又乱了
- 建议: ProTable 暴露 `@sort-change` 默认拼到 query (`sortBy`, `sortDir`),BE 已支持的接口走服务端

### 3.4 P1 — "清空" 行为不一致
- `ApprovalList`: reset 把 status/type/keyword 各字段一个一个清(`GeneralApprovalsTab.vue:308`)
- `WorkflowDefinitionList`: `reset()` 调用 `runReset` (走 useListFilterFeedback)
- 没有统一 reset → BE 重查的链路,有的页 reset 后还要再点 Search
- 建议: `useListFilterFeedback.runReset` 内部直接调 `load()` 不让业务自己接

### 3.5 P1 — 翻页时旧请求不取消 (race condition)
- `grep AbortController → 0 hit`
- 翻页 1→2→3 快点时,response 回来顺序可能 3→1→2,最后展示第 1 页内容(stale)
- 建议: ProTable 内部维护一个 `currentLoadToken`,response 比对再 setData

### 3.6 P2 — 分页大小持久化跨页污染
- `pro-table:pageSize:` 用 `route.path` 当 key,但 query 不同视为同一页,**所有 tenant、所有 filter 共享**,一般无大问题但跨 tenant 不期望

---

## 4. 批量操作 (Bulk Actions)

### 4.1 P0 — 全站仅 1 个页面用了 type="selection"
- `grep 'type="selection"' → 仅 GeneralApprovalsTab.vue:66`
- 其余列表页(JobDefinition / WorkflowDefinition / ConfigRelease / TenantList / UserAccountList / FileTemplate / Trigger / ...)都是 row-level 操作
- 用户要批量启用 50 个 job → 只能一个一个点

**建议**:
- 列出"业务上有批量需求"的列表(DBA & PM 评估),先给 JobDefinition / WorkflowDefinition / ConfigRelease 三个加
- 复用 `GeneralApprovalsTab` 已有模式: `el-table-column type="selection" :selectable="..."` + toolbar 按钮 `:disabled="!selection.length"`
- 提供 `useTableSelection<T>()` composable 统一 row 可选谓词 / cross-page selection / 最大数限制

### 4.2 P0 — 批量操作零进度反馈
- `runBatchApprove` (`GeneralApprovalsTab.vue:362`) 调一次 BE 批量接口,等接口返回再 toast
- 如果批量 500 条 BE 处理 30s,前端只显示 button loading,没有"已处理 120/500"进度
- BE 已有部分 SSE / progress 端点(从 `useSseAutoReload` 看出来),没接到批量上

**建议**: 批量接口走 SSE,前端给 `el-progress` + 实时计数

### 4.3 P0 — 没有"取消正在进行的批量"按钮
- 用户启动批量后,只能等;没有 abort 按钮、没有 AbortController、没有 BE cancel endpoint 调用

### 4.4 P1 — 选区跨页保持
- el-table 默认翻页清空 selection,GeneralApprovalsTab 也没做跨页保持
- 用户翻第 2 页再勾时第 1 页的勾自动消失,容易误以为"批量针对全部"

### 4.5 P1 — 批量错误聚合无前端表达
- 假设 BE 批量 500 条返回 `{success: 450, failed: 50, errors: [...]}`,前端 (`batchApprove`) 直接 `ElMessage.success('批量通过 500 条')`,把失败的 50 条藏起来了
- 建议: 批量结果用 `MessageBox` 列详情,可下载失败 csv

---

## 5. 拖拽 (Drag & Drop)

### 5.1 P1 — Workflow 编辑器**不存在**,只有 viewer
- `src/views/workflow/WorkflowMermaidViewer.vue` 是只读 mermaid 渲染 + node click inspector
- locale 里残留"画布" / "saveStatusSyncing" / "提交到后端" 字符串,实际没编辑入口
- 但 `WorkflowDefinitionList.vue` 没有"编辑工作流"按钮也是事实 — 全站不能图形化编辑 DAG,只能改 JSON / YAML(从 Bundle 导入)

**取舍**: 如果产品定位就是"运维监控控制台,DAG 用代码 / 模板生成",当前实现合理。**但需要删掉 locale 里残留的编辑字段**(避免后人误以为有 editor 改了一半)

### 5.2 P1 — 文件拖入只两处
- `JsonTextareaInput.vue:11` 支持把 JSON 文件拖到 textarea
- `TenantPackageImportWizard.vue:33-36` 支持拖文件到上传区
- 没有边界检测(无 `dragleave` outside detection,用户拖出去时高亮仍亮)
- 没有键盘替代(无 "Cmd+V 粘贴文件" 提示给键盘用户)
- TenantPackageImportWizard `onZoneDragEnter` 用 `preventDefault` 但没维护 `dragCounter`,子元素触发 dragleave 时会闪烁

### 5.3 P2 — `draggable` 抽屉对桌面意义不大
- `WindowMiniCreateDrawer.vue:12` / `CalendarMiniCreateDrawer.vue:12` 加了 `draggable`,但用户在 dialog 拖来拖去价值小,反而焦点容易丢
- 评估是否需要,或加 a11y 标注

---

## 6. 复制粘贴

### 6.1 (好) — `useCopy` + `CopyableText` + `lastApiMeta` 流畅
- `useCopy.ts:11` 优先 `navigator.clipboard.writeText`,降级到 textarea + execCommand
- `CopyableText` 全列表大量使用
- traceId 在 toast 里也能直接点复制 (`errorToast.ts:55-72`)

### 6.2 P1 — `useCopy` 桌面 view 几乎没复用
- 桌面 view 调 `navigator.clipboard.writeText` 直接的有 (`Login.vue:152`),没走 composable,失败时无统一 toast
- 移动端 (`MJobInstances` 等)正确用 `useCopy`
- 建议: ESLint 禁止 `navigator.clipboard.writeText` 直接调用,强制走 `useCopy`

### 6.3 P2 — 粘贴大文本 / 大 JSON 无 size 上限
- `JsonTextareaInput` 接受任意大小粘贴,粘 20MB JSON 直接长期停滞浏览器
- 建议: 限制 1MB,超过给"内容过大,请改用文件上传"提示

---

## 7. 撤销 / 重做 / 离开提示

### 7.1 P0 — 无 Undo (跨整站) — 见 §1.1
- `grep undo|redo|历史栈 → 0 业务 hit`
- 危险性强的操作 (`useDangerConfirm` 保护) 没有"30s 内可撤销"窗口
- 建议: 至少在批量删除/吊销后给 Toast 带"撤销"action(BE 支持 soft-delete 的话),GMail 风格

### 7.2 P0 — 关闭 tab / 刷新无 beforeunload 警告
- `grep beforeunload → 0`
- Wizard 填到一半刷新,数据全无
- 建议: `useDirtyGuard` 同时挂 `beforeunload`

---

## 8. 错误恢复 (401 / 403 / 500 / CSRF / Session)

### 8.1 (好) — 401 流程清晰分级
- `interceptors.ts:586-620` 把 401 按 token-exchange / session-auth / 业务接口三类分流
- 业务 401 自动尝试一次 silent refresh,失败才 toast,不踢出
- session 真过期(`/auth/me` 401) → 清 flag + `window.location.href = '/login'`

### 8.2 P0 — **CSRF / XSRF token 完全缺失**
**位置**: `src/api/client.ts:14`
**证据**:
```ts
withCredentials: true,  // 自动带 HttpOnly cookie batch_console_token
```
- 但 axios 默认 `xsrfHeaderName='X-XSRF-TOKEN'` / `xsrfCookieName='XSRF-TOKEN'` 没显式配
- `grep -rn "XSRF|csrf|CSRF" src/api → 仅文档注释命中`
- 没有 BE 下发 CSRF token 的痕迹

**影响**: 标准 CSRF 攻击向量。SameSite=Lax 只防 top-level GET CSRF,但 POST + Same-Site=Lax 在某些条件 / iframe 嵌套时仍可绕。
**建议**:
- BE 下发 `XSRF-TOKEN` cookie (non-HttpOnly),axios 默认 interceptor 会自动回 header
- 或显式 `apiClient.defaults.xsrfCookieName='XSRF-TOKEN'`
- 即便 BE 已经做了 `SameSite=Strict`,也建议加 CSRF token 做纵深防御

### 8.3 P0 — Session 过期跳 login,**无 redirect 参数**
- `interceptors.ts:599` `window.location.href = '/login'` 直接整页刷,没带 `?redirect=current-path`
- Login.vue 已经支持 `route.query.redirect` (line 138),但 interceptors 没传
- 用户在某个深页面操作,session 突然过期 → 登录后被踢回首页 → 之前工作上下文全丢

**修复**:
```ts
const here = encodeURIComponent(location.pathname + location.search)
window.location.href = `/login?redirect=${here}`
```

### 8.4 P1 — Refresh token 失败仍 toast"未授权"
- `interceptors.ts:619` 注释说"不登出,toast 提示",但用户连续看 3 个 toast(因为同时有 3 个请求)很烦
- 上面有 `tryRefreshToken` 去重,但 toast 不去重(messagePatch 的 grouping 能合并相同 message 但 trace 不同)

### 8.5 P1 — 5xx 错误对用户毫无 actionable 信息
- `interceptors.ts:665` 默认走 `请求失败（HTTP 500）` + msg
- 没有"复制完整 trace 给运维"快捷按钮(traceId 已带但提示文案不引导)
- 没有 retry CTA — 用户只能手刷整页

### 8.6 P1 — 维护模式 (`maintenance`) 弹窗静音覆盖
- `interceptors.ts:521-540` 维护模式时 reject 一个带 `silenced: true` 的 error
- 业务代码若没判断 `error.maintenance`,会进入 `loadError.value = err` → ProTable 显示错误态空白,没有跳维护页提示

---

## 9. 加载反馈 (Loading / Skeleton)

### 9.1 (好) — ProTable 三态切换合理
- `loading && !data.length` → Skeleton
- `error && !data.length` → ErrorState + Retry CTA
- 其余走 `v-loading`

### 9.2 P1 — 按钮 loading 不一致
- 一些 dialog submit 按钮带 `:loading="saving"`,一些只 `:disabled`
- `Login.vue:73` 文案双形态: `loading ? '登录中…' : '登录'` ;别处 button 是 loading 状态文案不变(图标在转,文字不变)→ 用户视觉提示不一致

### 9.3 P1 — Skeleton 行数硬编码 6
- `ProTable.skeletonRows: 6`,实际 pageSize 经常是 15/20/50
- 用户期待 skeleton ≈ 实际行数,6 行 skeleton 让感知"内容很少"

### 9.4 P2 — 路由切换无 progress bar 全覆盖
- 有 `RouteProgressBar.vue` 组件存在,但 `DefaultLayout` 内位置可能不够显眼
- 大列表 SSR / 异步 component lazy 切换时,top progress bar 应该足够明显

---

## 10. Toast / Notification

### 10.1 (好) — `messagePatch` 强制 grouping
- 相同 message+type 自动合并 ×N 计数(`messagePatch.ts:14`),解决重复 toast
- 但 grouping 比对的是 `message` 字符串,error toast 用 `h()` 渲染 VNode,**不会合并**(等价于关闭去重) → 网络抖动连续 5 个 5xx 还是会刷 5 个 toast

### 10.2 P1 — Toast 队列上限不存在
- EP `ElMessage` 同时叠 10 个会堆叠过多,没有 maxCount
- 建议: 维护一个 ringbuffer,>5 时把最早的 close 掉

### 10.3 P1 — 通知中心缺失
- toast 4-7s 就消失,关键信息(创建成功的 No / approval 通过的提示)用户没注意就没了
- 建议: 加一个站内通知中心(`web-push` 已有,延伸到 in-app),用户能事后回看

### 10.4 P2 — `ElMessage` vs `ElNotification` vs `showErrorToast` 三套混用
- 大部分用 `ElMessage`,但 `JobInstanceDetail.vue` rerun 成功用 `ElNotification`(带 action button),`errorToast` 自有 VNode
- 没有统一"短消息用哪个"规范

---

## 11. Modal / Drawer

### 11.1 P0 — 嵌套 dialog 焦点逃逸
- `WorkflowDefinitionList` 内点击 row → 跳详情 (good)
- 但 `JobDefinitionList.vue` 同时有 create-drawer / edit-drawer / dependencies-drawer / dialog 4 套,部分 dialog 里 `append-to-body` 但层级 z-index 容易错乱
- 测试: 打开 create-drawer → 在里面打开任何二级 dialog → Esc 行为不可预测

### 11.2 P1 — `close-on-click-modal="true"` 默认是数据杀手
- 已发现仅 5 处显式设 `false`:`Login` / `Wizard` 内 prefill dialog / `ApiKeyList` / TenantBatchCreate / 个别 drawer
- 其余 ~25 个 dialog 都默认 true,用户填到一半误点遮罩 → 关
- 建议: 全站包装 `BaseDialog` 默认 `close-on-click-modal="false"`,只读弹窗显式 opt-in

### 11.3 P1 — Drawer size 不统一
- 480 / 520 / 600 / 720 / 800px 混用
- 建议: `--drawer-w-narrow/regular/wide` 三档

---

## 12. 核心场景深扫

### 12.1 Login (`views/login/Login.vue`)
**好**:
- `<el-form @submit.prevent="handleLogin">` + `native-type="submit"` Enter 提交完整
- `autocomplete="username"` / `"current-password"` ARIA 友好
- 失败保留 traceId 一键复制
- 挂载时主动 `_silent` logout 清失效 cookie (line 124)

**待改 (P1)**:
- 无 caps lock 检测 (输密码常见困扰)
- 无登录失败次数限制提示(后端可能限流,前端无引导)
- **SSO 入口不存在**: `grep SSO|oauth` 在 view 层无登录入口;后端可能未做,前端预留位置也没有
- "忘记密码" 链接缺失

### 12.2 Approval (`views/approvals/*`)
**好**:
- 唯一一个用 `type="selection"` 的页面
- selectable 谓词 (`selectableRow`) 排除终态,UI 防御正确
- 批量/单条都用 `ElMessageBox.prompt` 收 reason

**待改**:
- (P0) 批准/驳回成功后整表 `load()`,丢失当前 page 位置 (line 313 `page.value = 1`)
- (P1) 批量驳回 prompt 只有一个 reason 输入,不允许逐行写不同理由
- (P1) 没有"驳回理由"的常用模板下拉
- (P1) Catch-up tab 和 General tab 各自独立 filters,**不共享 tenantId / requesterId 上下文**,跨 tab 重设

### 12.3 Workflow 列表 (`views/workflow/WorkflowDefinitionList.vue`)
**好**: 走 `EmptyState` 引导首次创建
**待改**:
- (P0) 没有详情页编辑入口(workflow 只能从 Bundle 导入)
- (P1) 没有"复制为新工作流"(用户基于现有 70% 复用很常见)
- (P1) `enabled` 切换是行内 switch?需确认,否则没有快速启停

### 12.4 Job Rerun (`views/monitor/JobInstanceDetail.vue`)
**好**:
- `useDangerConfirm({ verb: '重跑', target: ..., consequence })` 标准化警告
- 成功后用 `ElNotification` 给"看新实例 / 留在当前"双 CTA (line 444-456)
- 键盘快捷 `r` 触发 (line 605, 633)
- Esc 返回 (line 629)

**待改**:
- (P1) 没有"批量重跑选中失败实例" — 用户经常一次性 30 个 fail
- (P1) 重跑参数 (override params) 透传方式不直观,要点开 expansion panel
- (P2) 快捷键 `r` 在输入框 focus 时也会触发(需检查 `e.target` 是否 contenteditable / input)

### 12.5 Tenant 初始化 / 复制配置 wizard
- 多步表单,**几乎没有 step-level dirty guard**;用户跳到下一步又跳回会丢
- (P0) 见 §1.1

---

## 13. 跨切面观察

### 13.1 i18n 完整度
- `zh-CN.ts` / `en-US.ts` 都有,部分 view 直接 hardcode 中文(`JobDefinitionWizard.vue:398` 等)
- ESLint 加 `no-restricted-syntax` 检测裸中文字符串

### 13.2 路由 query 类型不安全
- `String(route.query.foo)` 散落,`String(undefined) === 'undefined'` bug 多次见
- 建议: 写 `useTypedRouteQuery<T>()` composable

### 13.3 Pinia store 与组件耦合
- `useTenantStore` / `useAuthStore` 在 view 层直接 import,没有抽象层
- 多租户切换时(`useTenantReload(load)`)各 view 自己挂监听,统一抽象可以省 30+ 行 boilerplate

### 13.4 测试覆盖
- 看到 `*.test.ts` 仅在 `composables` / `utils` / 部分 component(`SensitiveFieldAlert.test.ts` / `statusTagResolve.test.ts`)
- view 级 e2e 在 `e2e/` 目录(Playwright),但没扫到具体覆盖,需后续单独评估

---

## 14. 验证脚本建议

```bash
# 找还在写裸 validate 调用的 view
grep -rn "formRef\.value?\.validate\|formRef\.value\.validate" src --include="*.vue"

# 找未走 useDangerConfirm 的二次确认 (可能漏后果说明)
grep -rn "ElMessageBox\.confirm" src --include="*.vue"

# 找未带 redirect 的 location.href = '/login'
grep -rn "location\.href.*login" src --include="*.ts" --include="*.vue"

# 找 close-on-click-modal 未禁的有表单 dialog (反向: 列出所有 dialog,人工 review 哪些是表单)
grep -rn "el-dialog" src --include="*.vue" | grep -v "close-on-click-modal"

# 找 router.push/replace 但 query 类型未保护
grep -rn 'String(route\.query' src --include="*.vue"

# 找列表页未走 useRouteFilters
for f in $(grep -rln "ListPageQueryBar" src/views --include="*.vue"); do
  grep -q "useRouteFilters" "$f" || echo "MISSING: $f"
done
```

---

## 15. 修复优先级矩阵

| ID | 标题 | 严重度 | 影响面 | 工作量 | 建议节奏 |
|----|------|--------|--------|--------|----------|
| §1.1 | 整站异常数据保护 | P0 | 全站 30 dialog+38 drawer | M (新 composable + 改用) | Sprint+1 |
| §1.2 | 校验时机不一致 | P0 | ~40 form | S | Sprint+1 |
| §2.1 | Dialog autofocus | P0 | 全站 | S (一个 wrapper) | Sprint+1 |
| §2.2 | Enter 提交 | P0 | 全站 form | S | Sprint+1 |
| §2.4 | Focus trap | P0 | dialog 嵌套场景 | M | Sprint+2 |
| §3.1 | useRouteFilters 推广 | P0 | 30+ 列表页 | M | Sprint+1 |
| §3.2 | page 进 URL | P0 | 同上 | S | Sprint+1 |
| §4.1 | 批量操作覆盖 | P0 | 3-5 个重点列表 | L (含 BE) | Sprint+2 |
| §4.2/3 | 批量进度+取消 | P0 | 同上 | L (含 BE SSE) | Sprint+3 |
| §7.1/2 | Undo + beforeunload | P0 | 全站 | M | Sprint+2 |
| §8.2 | CSRF token | P0 | 全 API | S (BE 配合) | 立即 |
| §8.3 | Login redirect | P0 | session 过期 | XS | 立即 |
| §1.3-1.6 | 校验/默认值/label/size 一致性 | P1 | 全站 | M | Sprint+2 |
| §2.5/6 | Tab 顺序 + 快捷键 | P1 | 全站 | S | Sprint+3 |
| §3.3-3.5 | 排序+race+清空 | P1 | 列表页 | M | Sprint+2 |
| §4.4/5 | 跨页选区 + 失败聚合 | P1 | 批量页 | M | Sprint+3 |
| §5.1/2 | Workflow 编辑器决策 + drag UX | P1 | workflow + import | L | 评估 |
| §6.2 | useCopy 统一 | P1 | 全站 | S | Sprint+2 |
| §8.4-6 | 401 toast 去重 + 5xx 引导 + maintenance | P1 | API 层 | M | Sprint+2 |
| §9.2-3 | 按钮 loading + skeleton 行数 | P1 | 全站 | S | Sprint+2 |
| §10.2-3 | toast 队列上限 + 通知中心 | P1 | 全站 | M-L | Sprint+3 |
| §11.1-3 | 嵌套 dialog + close-on-modal + drawer 尺寸 | P1 | 全站 | M | Sprint+2 |
| §12.1-5 | 核心场景细节 | 混合 | 5 个核心 view | M | Sprint+2~3 |

**统计**: P0 = **11 个**议题 / P1 = **14 个**议题 / P2 = **8 个**(略)

---

## 16. 一句话总结

**底盘很稳,交互层"细节人本"不足**: ProTable / interceptors / DangerConfirm / messagePatch 这类基础设施做得相当成熟,但**用户在表单里填到一半的所有交互场景都没保护**(异常数据/autofocus/Enter/Esc/leave 提示全缺),**列表页 URL 状态机也没真正落地**(useRouteFilters 写了不调用),**CSRF token 缺失** 是安全短板。建议下个迭代集中做"表单可恢复性 + URL 状态机 + CSRF" 这一组主题,投入产出比最高。
