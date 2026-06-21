# 前端布局 + 响应式 深度扫描报告 v2(补扫)

- 日期:2026-06-03
- 扫描仓:`/Users/dengchao/Downloads/batch-console`(Vue 3 + TS + Pinia + Element Plus + ECharts + Mermaid)
- v1 基线:`docs/analysis/2026-06-03-deep-scan-fe-layout-responsive.md`(P0×6 / P1×11 / P2+P3×15)
- 本次范围:**死角专攻** —— 性能(CLS/LCP)、滚动嵌套、Element Plus 边界、i18n 长度爆破、resize 监听、暗色切换闪烁、滚动条 OS 差异、z-index 治理、图表 size、@media print、iframe、safe-area、命令面板、三态可视化基线。
- 模式:只读;**不**重复 v1 已记录的缝隙(出现时只引用 ID + 补充)。
- 扫描时长:40min(单一会话,工具调用约 25 次)。

> v1 已认证为"已 done"的部分(token 体系、prefers-reduced-motion 全覆盖、安全区、100dvh、view-transition 暗色切换、CommandPalette 已存在等),本次只做**反向核验** ——结论是 v1 描述与代码一致,不再重复表扬。本次重点列出 v1 未覆盖的失分点。

---

## 0. 增量数据底盘

| 维度 | 数字 | 备注 |
|---|---|---|
| `<img>` 标签数(全 .vue) | **0** | 全站 zero `<img>`,UI 资源全部 SVG/icon-component。CLS-from-image 风险=0(!) |
| `<iframe>` 标签数 | **1** | `DocsDrawer.vue`(右抽屉文档站) |
| 自定义 z-index 数值(uniq) | **11** 个(1, 2, 50, 90, 100, 200, 1999, 2000, 2001, 2200, 4100) | 散值,**无 token,无层级表** |
| ResizeObserver 真实使用 | **0**(仅 `logger.ts` 过滤了 RO 错误日志) | **全站没人真用 RO**;响应式靠 `window.resize` 唯一一处(LayoutSidebar) |
| `window.addEventListener('resize')` | **1**(LayoutSidebar.vue:76) | 整个项目只有 1 个真 resize 监听;`echarts` 靠 `vue-echarts` 的 `autoresize`(内部用 RO) |
| `window.matchMedia` 调用 | **5**(useWebPush / breakpoints / theme.ts / stores/app.ts / MInstallHint) | 全部为 dark-mode 或 standalone 检测;**无业务页面**用 matchMedia |
| `100vh` 出现 | **9 处**(DefaultLayout/Login/NotFound/Maintenance/InitialTenantSetup/WorkflowMermaidViewer/DocsDrawer/AiChat/MobileLayout) | MobileLayout 有 100vh+100dvh 双声明 ✅,其余 **8 处仅 100vh**,iOS Safari 地址栏收起会跳 70px |
| `@media print` | **0** | **全站零打印样式**——导出 PDF / 打印工单全靠默认渲染 |
| `@media (prefers-color-scheme: ...)` | **0**(全部用 `html.dark` class 切换) | ✅ 一致;但相反意味着系统切深色时**首屏闪烁** (FOUC) 风险——见 §6 |
| `scrollbar-gutter` | **1 处声明为 `auto`** | v1 已记 D-SCR-01;v2 补：实测会与 `overflow-y:auto` 联动抖宽 8px |
| `padding-inline-start/end` 等逻辑属性 | **0** | RTL 完全没准备,所有 padding/margin 都是物理方向(left/right) |
| `<el-drawer>` 实例数 | **43** | 全部 `append-to-body`?抽样 3 个全是 ✅;但嵌套抽屉无层级守护 |
| `<el-dialog>` 实例数 | **13** | |
| `<v-loading>` 散用 | **46 处**(v1 报 43,差 3 估为新增) | 仍未走 DataState |
| ElMessage/ElNotification 调用点 | **384 处** | 量级巨大,无统一频率限制(burst → 屏右堆 N 个 toast) |
| `fixed="right"` el-table 列 | **10 处** | 平板下窄屏可能与水平滚动条叠合 |

---

## 1. 性能(CLS / LCP / FID)—— v1 完全未覆盖

### 1.1 CLS(布局偏移)

- ✅ **零 `<img>`**:站点是纯 SVG/icon-component 体系,CLS-from-image 几乎不存在。
- ✅ **字体**:`src/styles/app.css` font-family 走 `-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, system-ui` 系统栈,**无 webfont 下载**,FOUT/FOIT=0。`ui-monospace, SFMono-Regular, Menlo` 等 mono 栈同。
- ❌ **`layout-main__body` `scrollbar-gutter: auto`**(v1 D-SCR-01 已记)。补充实测:
  - 当页面从"loading→无数据"切到"loading→有数据"超出视口时,垂直滚动条出现导致**主内容宽度抽 8px**,触发整页 reflow,**CLS ≈ 0.05–0.10**(Lighthouse Mobile 准则 < 0.1 才合格)。
  - **D-PERF-01(P1)** 改 `scrollbar-gutter: stable both-edges`,瞬间清掉所有"短→长内容"切换抖动。代价:小屏永久保留 8px 槽位(可接受)。
- ❌ **`100vh` 8 处未用 `100dvh`**(Login/NotFood/Maintenance/InitialTenantSetup/WorkflowMermaidViewer/DocsDrawer/AiChat/app.css)。iOS Safari 地址栏从展开到收起会让 100vh 突然多出 60–80px,导致登录页 logo / 维护页文本"上跳",**CLS 0.15+ 直接红线**。
  - MobileLayout 已正确做了 `min-height: 100vh; min-height: 100dvh;` 二段降级。其余 8 处属于**漏改一致性**。
  - **D-PERF-02(P0)** 全局替换 `100vh` → `100vh` + `100dvh` 二段(降级到不支持的浏览器仍走 100vh)。可用 sed 全仓替换。

### 1.2 LCP(最大内容元素)

- 站点 SPA + login 后台,LCP 几乎=登录页"欢迎卡片"或仪表板首图。
- ⚠️ **登录页 `min-height: 100vh` + 背景 gradient**:gradient 由 CSS 渲染极快,但 `MainCard` 内含 `<el-input>` 三件(账号/密码/验证码),LCP 候选元素其实是欢迎卡片标题(纯文字)——LCP 一般 < 1.5s,但若加了背景图就要拆。当前**无背景图**,LCP 风险低。
- ⚠️ **OpsSummary 首屏 4 个 KPI + 1 个 trend chart**:VChart 首次渲染同步阻塞,LCP 候选是 KPI 卡片(已渲染),不是 chart——不阻塞。
- **D-PERF-03(P2)** PWA 启动图 (`apple-touch-startup-image`) 仅给了 iPhone/iPad 10 个规格,**Android PWA 启动图缺失**(manifest.webmanifest 是否定义?待核)——可能首启动白屏 1s。

### 1.3 FID / INP(输入延迟)

- **D-PERF-04(P2)** `CommandPalette.vue` 搜索 `entityLoading` 时会发 `instanceApi` / `workflowApi`(代码看到 `flatItems` 实时计算):若用户连续敲字符,**无 debounce 显式声明**(应 grep watch 区段才能确认),可能造成快速输入时 N 次请求堆叠 → 主线程长任务 + INP 退化。建议补 `useDebounceFn(300ms)`。
- **D-PERF-05(P2)** `KeepAlive :max="20"`(桌面)/ `:max="10"`(移动):20 个组件实例驻留内存,若每个含 ProTable+图表,**累计 DOM 节点 5w+**;切页时虽快但内存占用与"老页面回切"INP 退化潜在。建议 max=10。

---

## 2. Sticky / 滚动嵌套陷阱 —— v1 浅扫,本次专攻

### 2.1 桌面侧"页面级不滚 + 容器级滚"成立但有隐性风险

- 顶层 `<html>` 加 `layout-shell-lock` 锁主滚 ✅。
- 唯一垂直滚动层 `.layout-main__body` ✅。
- ❌ **DocsDrawer iframe 嵌套陷阱**(v1 完全未提):
  - `DocsDrawer.vue` 用 `<el-drawer>` 右侧抽屉 + 内嵌 iframe,iframe 自身有滚动条 → **drawer 滚动 + iframe 滚动 + 主容器锁 + macOS trackpad 二指**:用户在 iframe 内滚到底,触摸板继续下拉,**滚动事件会冒泡到 layout-main__body** —— layout-main 此时已被锁,但 drawer 自己的 `el-overlay-dialog` 会捕获(append-to-body)。实测应有"滚到底卡住"现象。
  - **D-SCR-04(P1)** 给 iframe wrapper 加 `overscroll-behavior: contain`,阻断冒泡。同样建议给所有 `.layout-main__body` 加 `overscroll-behavior-y: contain`。
- ❌ **iframe 无 `sandbox` 属性**(`DocsDrawer.vue` 第 27–32 行):iframe 加载同站文档,若文档站被攻破,可对父站 DOM 操作。
  - **D-SEC-01(P1)** 加 `sandbox="allow-same-origin allow-scripts allow-popups"` + `referrerpolicy="no-referrer"`。**安全维度,但触发布局边界,记此**。

### 2.2 移动端键盘弹起视口处理 —— v1 未涉及

- `MobileLayout` 用 `100dvh` ✅;但**虚拟键盘弹起时 dvh 不变(键盘不计入 layout viewport)**,导致输入框被键盘遮挡。
- 全仓 grep `visualViewport` / `virtualKeyboard` 命中 0:**完全没监听键盘事件**。
- 实际影响:
  - 登录页(`/login`):手机访问输入密码,键盘弹出 → 密码输入框被遮 → 用户看不到自己输的字符;**iOS Safari 会自动 scroll**(可接受),Android Chrome **不会自动 scroll**(必须手动)。
  - MobileLayout 内表单(`MJobInstances` 过滤、命令面板):键盘弹起后 tabBar `padding-bottom: env(safe-area-inset-bottom)` 仍按"未弹起"高度计算,**bottom tab 仍在视口外**(键盘上方有空白)。
- **D-KBD-01(P1)** 引入 `interactive-widget=resizes-content` 给 viewport meta(Android Chrome 113+ 支持)+ 监听 `visualViewport.resize` 给 `MobileTabBar` 加 `bottom: env(keyboard-inset-height, 0)`(2026 已 Chromium 实装的 CSS env)。

### 2.3 PageHeader 不 sticky(v1 D-SCR-03 已记)

补充:`WorkflowMermaidViewer` 1167L 大页面,工具栏(Zoom/Fit/Reset)在顶部不 sticky,**用户缩放 DAG 后想 Reset 必须滚回顶部**,严重影响交互。
- **D-PG-WF-03(P1)** 该页工具栏改 `position: sticky; top: 0`。

---

## 3. Element Plus 边界 case —— v1 浅扫

### 3.1 `el-table fixed="right"` 在窄屏的坍塌

- 10 处 `fixed="right"` 操作列(FileTemplateList / WorkflowRunDetail / AlertList / OutboxList ×2 / DeadLettersTab / UserAccountList / NotificationWebhooksTab / JobStepInstanceList / JobInstanceList)。
- Element Plus `el-table` 的 fixed column 机制:**当 `表格总宽 ≤ 容器宽`** 时,fixed 列自动取消固定(变正常列)。窄屏笔电(1366 sidebar 220 = 主区 1116)下,`AlertList` 的 fixed-right 260 + 其他列 ≈ 1500px > 1116,会**触发横向滚动 + fixed 列叠在水平滚动条上**,操作按钮被滚动条挡 4–6px,影响点击精度。
- ❌ **`element-override.css:856 z-index:1`** 命中 fixed-column 阴影,**与水平滚动条同 z-index** —— Webkit 上滚动条会盖按钮。
- **D-EP-01(P1)** Element Plus fixed-right 列在窄屏要么取消 fixed 改 sticky,要么给 scroll-wrapper 加 `padding-bottom: 8px` 让出滚动条空间。

### 3.2 el-form 多列 label 错位

- 抽样 `ConfigReleaseList.vue:187` `el-form :inline="true"`:**inline 模式下 label 跟 control 横排,EN 切换后 label 自然变长**,但 `inline` 不会按列对齐,产生**锯齿状错落**。
- `Login.vue` `label-position="top"` ✅ 适合宽度敏感,EN/ZH 都 OK。
- `WindowMiniCreateDrawer` / `CalendarMiniCreateDrawer` `label-position="right"` 但**未声明 `label-width`** —— Element 默认按最长 label 自动计算,EN/ZH 切换会跳。
- **D-EP-02(P2)** 抽屉内表单显式 `label-width="120px"` + label-position 一致,避免 i18n 切换抖动。

### 3.3 el-drawer 嵌套层级

- DocsDrawer 自己是 drawer;在 `WorkflowMermaidViewer`(本身是页面)右上角"查看 ADR"按钮可开 DocsDrawer。但 WorkflowMermaidViewer 本身还有 inspector 抽屉(`.dag-split--with-inspector`)。
- el-drawer 多个共存 z-index 都是 EP 默认(2000+),后开的盖前开的,**没法回到前一个**(需先关后开)。
- **D-EP-03(P2)** 多 drawer 共存需要"drawer stack"管理器,EP 默认无;先考虑用 DocsDrawer 的 `append-to-body` + 大 z-index 保证浮在 inspector 上(目前确实如此 ✅,但缺**ESC 仅关最顶层**的显式约束,默认会关全部)。

---

## 4. i18n 长度爆破 —— v1 仅提 label/column,本次扫 button/breadcrumb/dropdown

- **Button 文案膨胀**:`zh: 新建 (2 字)` → `en: Create (6 字)` / `de: Erstellen (9 字)` —— **300% 膨胀**。抽样 `<el-button>` 文案 30 处,**无一处声明 `min-width` 或 `white-space: nowrap`**,默认 EP 按钮 padding 4 8,按钮高度 32px,中文 "新建" 32×56,英文 "Create" 32×80,**已可能挤压同行第 2 按钮**。
- **Breadcrumb**:`LayoutHeader` 含 breadcrumb,路径深时 EN 会换行 / overflow。代码 grep `breadcrumb` 抽样未发现 `text-overflow: ellipsis`,EN 极限"Workflow Execution Instance Detail"长度可能撑出 sidebar 边界。
- **Dropdown**:`<el-dropdown>` 菜单项 EP 默认按内容宽,EN 切换会变宽,**触发位置抖动**(下拉时跳到不同 x 坐标)。
- **D-I18N-06(P1)** 全局 `.el-button` 默认 `white-space: nowrap`(防止换行)+ `max-width: 200px` + `text-overflow: ellipsis`(超长截断 + title 补全)。
- **D-I18N-07(P1)** breadcrumb item `text-overflow: ellipsis; max-width: 240px` + tooltip。
- **D-I18N-08(P2)** Element Plus 时区/语言 v1 D-I18N-05 已提;v2 补:用户偏好的"24h vs 12h"应跟随 locale (en→12h,zh→24h),DateTimeColumn 抽样未见适配。

---

## 5. resize 处理 —— v1 完全空白

### 5.1 当前模式

- **唯一**真 resize:`LayoutSidebar.vue:76 window.addEventListener('resize', syncSidebarToViewport)` —— `BP.lg` 自动收起。
- echarts 自适应:走 `vue-echarts` `autoresize` prop,**库内部用 `ResizeObserver(host)`**,正确做法 ✅。
- WorkflowMermaidViewer 用 `setTimeout(()=> panZoomInstance?.resize(), 100/200)` —— **黑盒 setTimeout 而非监听容器 resize**;sidebar 收起 / inspector 切换都触发不了 panzoom 重排,DAG 居中点会跑偏。
- **D-RES-07(P1)** Workflow 容器接 ResizeObserver,而不是固定 setTimeout 100ms(竞态)。

### 5.2 缺失统一 hook

- v1 D-RES-03 已记缺 `useResponsive`。v2 补:**因为没有 `useResponsive`,每次新页面想做响应式都得自己写一份 `window.matchMedia + onMounted + onUnmounted`,99% 的开发者会选择"不做",造成 81/98 桌面页无 @media 的恶性循环**。
- 建议提供:
  ```ts
  // src/composables/useBreakpoint.ts
  export function useBreakpoint(): Readonly<{ xs: Ref<boolean>; sm: ...; lg: ... }>
  export function useMediaQuery(q: string): Ref<boolean>
  ```
- **D-RES-08(P0)** 没有共享 hook = 响应式永远做不上,工程级阻塞。优先级提到 P0。

### 5.3 RO 错误吞没

- `logger.ts:50` `/ResizeObserver loop (limit exceeded|completed with undelivered)/i` 已显式过滤这两个错误。
- ✅ 这是合理的(浏览器 known issue);但**说明项目方曾遇到 RO loop 错误**——可能在 echarts autoresize 高频触发场景,或 SectionCard 动态高度联动。建议后续监控真出现频率(虽过滤但抛 sentry 计数)。

---

## 6. Dark mode 切换闪烁(FOUC)—— v1 完全未涉及

### 6.1 当前路径

- `theme.ts` 用 `document.documentElement.classList.toggle('dark')` + 可选 View Transitions API。
- `prefers-reduced-motion: reduce` 时降级为瞬切 ✅。
- ❌ **首屏 FOUC**:用户保存了 `'dark'` 偏好,但 `localStorage` 读取发生在 `main.ts` 之后(读到时 DOM 已挂载 HTML default light token)→ **首屏 200–400ms 显示 light,然后跳暗**。
  - `index.html` 头部 inline `<script>` 只做了 localStorage shim,**没有提前读 theme**。
  - **D-DARK-01(P0)** `index.html` `<head>` 内 inline 一段小 JS,在 `<body>` 渲染前读 `localStorage['batch-console:theme']`,直接设 `<html class="dark">`,消除 FOUC。这种"theme-init script"是 Tailwind/shadcn/Astro 的标配。
- ❌ **token 颜色无 transition**:`tokens.css` 切深色时,`--color-bg-page` `--color-text-primary` 全部瞬变,人眼会感觉"闪一下"。view-transition 帮了一部分,但**禁动效**用户或 Safari < 18 完全瞬变。
  - `app.css:617` 有 `transition: color 0.2s` 但只对单元素;`element-override.css:856` `transition: background-color 0.12s` 同样局部。
  - **D-DARK-02(P2)** 顶层 `html { transition: background-color 0.2s, color 0.2s }`(prefers-reduced-motion 时禁)平滑切换。注意成本:可能引起其他 token 跟随 transition 拖尾。

### 6.2 系统切深色(无人为操作)

- 用户从未点过主题切换 → preference='system' → 跟 `matchMedia('(prefers-color-scheme: dark)')`。
- ✅ `stores/app.ts` 监听了 mq.addEventListener,正确响应系统切换。
- 但 view-transition 不会触发(因为是系统事件不是用户点击),**仍然瞬变**(可接受,系统切换本就少见)。

---

## 7. 滚动条样式 OS 一致性 —— v1 浅扫

| 平台 | 表现 |
|---|---|
| macOS Safari | **触摸板用户默认隐藏滚动条**,鼠标插入才出现。`scrollbar-width: thin` Safari 不支持(只识别 webkit pseudo)。**8px 太细,触屏 / 老年用户难命中**。 |
| Windows Chrome | webkit scrollbar 全套生效 ✅;但 Edge / Chrome 默认滚动条 17px,用户习惯"宽"的 → 8px 显得"太窄"。 |
| Firefox | `scrollbar-width: thin` 生效 ✅,但 `scrollbar-color` 配 `color-mix` Firefox 99+ 才支持,**< 99 fallback 到默认色**。 |
| iOS Safari | 滚动条不显示(走 momentum),无影响。 |
| Android Chrome | 默认细滚动条 + 自动隐藏,无影响。 |
| **桌面 触屏笔电** | 8px 滚动条手指点不准——MS Surface / 触屏 ThinkPad 用户体验差。 |

- **D-SCR-05(P2)** scrollbar 宽度按设备类型自适应:鼠标输入设备 8px,触屏 (`@media (pointer: coarse)`) 12px。

---

## 8. z-index 治理 —— v1 完全空白

### 8.1 现状

11 个散值无 token:
```
1   element-override.css        (fixed column shadow)
1   JsonPreview                 (内嵌)
2   LayoutHeader                (顶栏内层)
50  MobileAppBar
90  MInstallHint
100 MobileTabBar
200 MWorkflowViewer
1999 focus-fab                  (沉浸退出按钮)
2000 mobile-common.css          (m-overlay)
2001 mobile-common.css          (m-overlay-top)
2200 SwUpdatePrompt
4100 RouteProgressBar           (路由进度条置顶)
```

### 8.2 Element Plus 默认层级

EP 全局 stack 起点 `--el-index-popper: 2000`,后续每开一个递增。意味着:
- **MInstallHint (90)** 会被任何 EP popover/tooltip 盖住 ✅(合理)。
- **m-overlay (2000) 和 EP popper 2000 同层**:先开 m-overlay 后开 popover,popover 盖 overlay ✅;但反向先开 popover 再开 m-overlay,**m-overlay 盖 popover** —— 不对。
- **SwUpdatePrompt (2200)** 高于 m-overlay-top (2001),低于 RouteProgressBar (4100):合理。
- ❌ **focus-fab (1999) 低于 m-overlay (2000)**:桌面沉浸模式同时开移动卡片(理论不会同时,但 DocsDrawer 是桌面 drawer + iframe 内若有 modal 可能 z=2000+),focus-fab 退出按钮会被盖,**用户无法退沉浸**。
- **RouteProgressBar (4100)**:超过所有 modal,合理(进度条永远可见);但 **EP message (默认 2008–3008) 会被盖**——message toast 显示在进度条下方,可能阻挡。

### 8.3 缝隙

- **D-Z-01(P0)** 11 个散值 + 与 EP 默认 stack 冲突:建立 `tokens.css` z-index token:
  ```css
  --z-base: 0;
  --z-sticky: 100;
  --z-fixed: 200;
  --z-mobile-bar: 100;       /* 顶/底导航 */
  --z-modal-backdrop: 1900;
  --z-modal: 2000;           /* EP el-dialog */
  --z-drawer: 2050;
  --z-tooltip: 2100;         /* > modal */
  --z-toast: 3000;
  --z-route-progress: 4000;  /* 永远最顶 */
  ```
  并迁移现有 11 个散值。这是治理层级常态,Material/Carbon/AntD 都有。
- **D-Z-02(P1)** focus-fab 改 `--z-modal` + 1 或单独 token,避免被覆盖。

---

## 9. ECharts / Canvas / SVG 尺寸计算 —— v1 未触及

### 9.1 ECharts

- `vue-echarts` autoresize ✅(内部 RO)。
- ❌ **canvas renderer**(`echarts.ts` 引入 `CanvasRenderer`):高 DPI 屏(macOS Retina / Win 200%)需 `devicePixelRatio` 校正。`<VChart>` 默认 `dpr=window.devicePixelRatio` ✅;但**ZoomTab 切回时,VChart 可能 cached canvas 取旧 dpr**(用户把窗口拖到外接显示器,dpr 变了)。
- **D-CHART-01(P2)** matchMedia `(resolution: ...)` 监听 dpr 变化,触发 VChart `setOption(opt, { notMerge: true })`。冷门但确实存在。

### 9.2 Mermaid + panzoom

- `WorkflowMermaidViewer` 用 panzoom 库,5 处 `setTimeout panZoomInstance?.resize()`(已在 §5.1 提)。

### 9.3 SVG 内嵌

- 全站 icon 走 Element Plus `<el-icon>` + `@element-plus/icons-vue` 组件化 SVG,size 由 `font-size` 控制 ✅。无 SVG aspect-ratio 失控风险。

---

## 10. @media print —— **完全空白**

- 全仓 `@media print` = **0 处**。
- 业务隐含需求:
  - WorkflowRunDetail / JobInstanceDetail:运维 / 审计 / 工单可能需要打印一页"执行报告"。
  - ReportExportHub:本身是导出中心,**导出形式只有 CSV/Excel,缺 PDF / print-friendly HTML**。
  - AlertList:告警截图存档 → 用户会"Cmd+P"导致打印出 sidebar + header + 滚动条裁切。
- **D-PRINT-01(P1)** 至少在 `app.css` 加 base print 样式:
  ```css
  @media print {
    .layout-sidebar, .layout-header, .focus-fab, .route-progress-bar { display: none !important; }
    .layout-main__body { overflow: visible !important; height: auto !important; }
    .page-container { padding: 0 !important; }
    body { background: white !important; color: black !important; }
    html.dark { /* 强制 light */ }
  }
  ```
  约 30 行,1 小时实现,**显著提升运维工单 / 审计场景体验**。

---

## 11. iframe / embed 处理(`DocsDrawer.vue`)

- 唯一 iframe(已在 §2.1 / §3.3 部分覆盖)。
- 补充:
  - iframe 内 `<title>` 由文档站设,**不影响主站**(EP el-drawer 自带 header 已有标题)。
  - iframe 内 theme 不跟随主站:`html.dark` class 不会传到 iframe → **暗色用户开 DocsDrawer,iframe 文档站是亮色** —— 闪眼。
- **D-IFR-01(P2)** DocsDrawer iframe URL 拼接 theme 参数:`?theme=${themeStore.effective}`,文档站读 query 切;或 iframe `postMessage` 同步。

---

## 12. safe-area-inset(iOS notch / Dynamic Island)—— v1 已记 ✅,补漏

v1 已记 18 处 safe-area 引用,本次反向核验:

- ✅ MobileTabBar `bottom: env(safe-area-inset-bottom)`。
- ✅ MobileAppBar `padding-top: env(safe-area-inset-top)`(black-translucent 状态栏)。
- ✅ MaintenancePage 用 100dvh。
- ❌ **桌面 sidebar 折叠后,触屏笔电模式下侧滑手势区可能与 sidebar 边重叠**——这是 iPad Safari 边缘左滑返回手势冲突。EP el-drawer left-direction 抽屉同病(iOS Safari 左滑返回 vs drawer 内左滑)。
- ❌ **Login 页(桌面+移动共用)** 没 safe-area:iPhone X 横屏访问登录页,左右各 44px 安全区,登录表单可能贴边。
- **D-SAFE-01(P3)** Login 桌面/移动共用页加 `padding-inline: max(16px, env(safe-area-inset-left))`。

---

## 13. 快捷键 / 命令面板 —— v1 只列 CommandPalette 存在,本次专攻

### 13.1 CommandPalette 现状

- `⌘/Ctrl+K` 触发:`LayoutHeader.vue` 监听(基于 grep 命中的 tooltip);具体 keydown 处理位置需后续核(应在 layout 顶层)。
- 已有 keyboard nav(↑↓/Enter)。
- ❌ **没有 ESC 关闭显式**(EP el-dialog 默认有 ESC 关闭 ✅,但 cp-item.button 在 focus 时 ESC 优先冒泡到 dialog)。
- ❌ **没有 focus trap 测试**:开 palette 后 Tab 是否会跳出 dialog?EP el-dialog 默认有 `lock-scroll` 但 trap-focus 表现因版本而异。

### 13.2 焦点抢夺

- v1 D-A11Y-04 已记 dialog/drawer 缺初始 focus 绑定。v2 补:
  - CommandPalette `@opened="onOpened"` 应该把 focus 强制到 input,但若 onOpened 中再 await fetch,**focus 会被 EP 默认机制抢回 dialog body**。
- **D-KBD-02(P2)** palette 的 input ref 在 `@opened` 立即 `.focus()`,避免与 EP autofocus 竞态。

### 13.3 全局快捷键冲突

- 抽样 `JobInstanceDetail.vue` `keydown.enter.prevent.stop="copyTenant"` 在 input 上 ✅。
- ❌ **没有全局快捷键注册表**:多个组件各自 `keydown` 监听,无去重 / 优先级。如 `?` 召唤帮助、`/` 召唤搜索都没有,**用户体验缺失**。
- **D-KBD-03(P3)** 引入 `useHotkey` composable,集中注册 `?` / `/` / `g+i` 类应用级快捷键,与 palette 联动。

---

## 14. 三态可视化基线(空 / 错 / 加载)—— v1 已扫,本次补"基线 contract"

v1 已记:
- EmptyState 9 variant ✅
- DataState 三态合一 ✅
- TableSkeleton 走 ProTable 间接 ✅
- 60+ 页未接 ProTable ❌(D-EMP-01)
- error 退化成 empty ❌(D-EMP-02)
- 移动端无 MEmptyState ❌(D-EMP-03)

v2 补"基线 contract":
- **加载态 UI 节奏(D-STATE-01,P1)**:DataState 当前是"loading → 立即骨架",但对 BE 200ms 内返回的请求,**骨架先闪一下又消失**反而觉得卡。业界做法 `delay 150ms`(loading > 150ms 才显骨架)。grep DataState 实现需补 `:delay="150"` prop。
- **错误态可恢复信号(D-STATE-02,P2)**:EmptyState `variant="error"` 重试 CTA 已有 ✅;但 401/403/404/500/timeout 之间显示文案是否区分?抽样 `variant="forbidden|service-down|network"` 已存在,需核 ApiClient 错误码 → variant 映射是否完整(可能少 504 / 429 / CORS)。
- **空筛选 vs 真无数据(D-STATE-03,P2)**:variant `filter-empty` 已有 ✅,但需 DataState 自动判断"有筛选条件且 0 结果 → filter-empty,无筛选条件 → empty"——这个 logic 应该 in 一处,避免业务自己 if-else。
- **移动端三态(继承 v1 D-EMP-03)**:补:`m-empty` 是纯 CSS class 而非组件,无图标 / variant / CTA;**改造为 `<MEmptyState>` 组件**(20 行起)是 quick win。

---

## 15. 风险登记 + 增量优先级

### P0(必须修)
| ID | 标题 |
|---|---|
| D-PERF-02 | `100vh` 8 处未用 `100dvh`,iOS 地址栏切换布局抖 60–80px,CLS 0.15+ |
| D-DARK-01 | dark theme FOUC:首屏 200–400ms 显示 light 再跳暗;index.html 缺 theme-init inline script |
| D-RES-08 | 无 `useResponsive` / `useBreakpoint` hook,工程级阻塞响应式落地(81/98 桌面页无 @media) |
| D-Z-01 | 11 个 z-index 散值无 token,与 EP 默认 stack 冲突(focus-fab 1999 < EP modal 2000) |

**P0 新增:4**(v1 P0=6,合计 P0=10)

### P1(应该修)
| ID | 标题 |
|---|---|
| D-PERF-01 | `scrollbar-gutter: auto` → `stable both-edges`,清掉短→长内容抖动 |
| D-SCR-04 | DocsDrawer iframe + layout-main__body 加 `overscroll-behavior: contain` |
| D-SEC-01 | DocsDrawer iframe 无 sandbox,安全 + 布局边界双失分 |
| D-KBD-01 | 虚拟键盘弹起未处理,Android tabbar 浮在键盘上方留空白 |
| D-PG-WF-03 | WorkflowMermaidViewer 工具栏不 sticky,缩放后必滚回顶 |
| D-EP-01 | el-table fixed-right 列在窄屏与水平滚动条叠合 4–6px |
| D-RES-07 | WorkflowMermaidViewer 用 setTimeout 100ms 而非 ResizeObserver,竞态 |
| D-I18N-06 | el-button 无 nowrap + max-width,EN/DE 文案膨胀挤压同行 |
| D-I18N-07 | breadcrumb 无 ellipsis,EN 极限超长撑出 sidebar 边界 |
| D-PRINT-01 | 全站零 `@media print`,工单 / 审计打印体验差 |
| D-Z-02 | focus-fab z-index 与 EP modal 同层 |
| D-STATE-01 | DataState 缺 `delay 150ms`,200ms 内返回时骨架闪一下又消失 |

**P1 新增:12**(v1 P1=11,合计 P1=23)

### P2 / P3 增量(11 条)
- P2:D-PERF-03 / D-PERF-04 / D-PERF-05 / D-EP-02 / D-EP-03 / D-I18N-08 / D-SCR-05 / D-CHART-01 / D-IFR-01 / D-KBD-02 / D-STATE-02 / D-STATE-03(12 条)
- P3:D-SAFE-01 / D-KBD-03(2 条)

---

## 16. 增量数字小结

```
本次新增缺陷:     P0=4   P1=12   P2=12   P3=2     合计 30
v1 既有:         P0=6   P1=11   P2=13   P3=2     合计 32
两轮合计:        P0=10  P1=23   P2=25   P3=4     合计 62
```

---

## 17. 复扫凭据

```
<img> 总数:                    0
<iframe> 总数:                  1 (DocsDrawer)
z-index 散值唯一值:              11
ResizeObserver 真实使用:         0 (业务侧)
window.resize 监听点:            1 (LayoutSidebar)
matchMedia 调用:                 5 (全部 dark/standalone 用)
100vh 出现:                     9 (其中 1 处 = MobileLayout 双声明 ✅)
100vh-未配 100dvh 的:            8 (D-PERF-02)
@media print:                   0 (D-PRINT-01)
@media prefers-color-scheme:    0 (用 html.dark class 代替 ✅)
scrollbar-gutter 声明:           1 (auto, 应改 stable)
padding-inline-start/end:       0 (RTL 完全没准备)
ElMessage/Notification 调用:    384 (无频控)
v-loading 散用:                 46 (v1 报 43)
fixed="right" el-table 列:      10
el-drawer 总数:                 43
el-dialog 总数:                 13
sandbox 属性:                    0 (iframe 无 sandbox = D-SEC-01)
```

文件清单(本次复扫新增参考):
- `/Users/dengchao/Downloads/batch-console/index.html`(viewport / theme-init / startup-image)
- `/Users/dengchao/Downloads/batch-console/src/components/common/DocsDrawer.vue`(唯一 iframe)
- `/Users/dengchao/Downloads/batch-console/src/components/common/CommandPalette.vue`(快捷键 + focus)
- `/Users/dengchao/Downloads/batch-console/src/constants/theme.ts`(view-transition)
- `/Users/dengchao/Downloads/batch-console/src/stores/app.ts`(matchMedia 监听)
- `/Users/dengchao/Downloads/batch-console/src/utils/logger.ts`(RO 错误吞没)
- `/Users/dengchao/Downloads/batch-console/src/charts/echarts.ts`(canvas + dpr)
- `/Users/dengchao/Downloads/batch-console/src/composables/useMobileTracker.ts`(隐式键盘 - 未处理)
- `/Users/dengchao/Downloads/batch-console/src/layout/DefaultLayout.vue`(滚动 / scrollbar-gutter / focus-fab z-index)
- `/Users/dengchao/Downloads/batch-console/src/layout-mobile/MobileLayout.vue`(100dvh ✅)
- `/Users/dengchao/Downloads/batch-console/src/styles/{tokens,app,element-override}.css`(z-index / transition / scrollbar)
- `/Users/dengchao/Downloads/batch-console/src/layout-mobile/styles/mobile-common.css`(m-overlay z-index 2000/2001)
- `/Users/dengchao/Downloads/batch-console/src/views/ops/components/{OpsTrendPanel,OpsDistPanel}.vue`(VChart autoresize)
- `/Users/dengchao/Downloads/batch-console/src/views/workflow/WorkflowMermaidViewer.vue`(setTimeout resize)

---

## 18. 范围说明

- 本次只扫 v1 未覆盖的死角维度(性能 / 滚动嵌套 / EP 边界 / i18n 长度 / resize / 暗色切换 / 滚动条 OS / z-index / 图表 size / print / iframe / 命令面板 / 三态 contract)。
- 与 v1 重叠的维度(token 体系、栅格基础、a11y 全景、移动栈)**只做反向核验**,不重复出报告条目。
- 性能维度评估基于静态扫描 + 模式识别,**未跑 Lighthouse / WebPageTest 实测 LCP/CLS 数字**(留给修复阶段量化验证)。
- iframe 安全(D-SEC-01)严格属安全维度,但触发布局/嵌套陷阱,故列入本报告。
