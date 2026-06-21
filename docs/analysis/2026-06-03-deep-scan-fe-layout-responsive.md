# 前端布局 + 响应式 深度扫描报告

- 日期:2026-06-03
- 扫描仓:`/Users/dengchao/Downloads/batch-console`(Vue 3 + TS + Pinia + Element Plus)
- 范围:布局体系 / 栅格 + 间距 / 响应式 / 滚动 / 空态 + 骨架 / a11y / i18n 长度 / 核心页 + 新功能页
- 模式:**只读**,不修改 batch-console;报告写入 file-batch-system。
- 扫描时长:>= 40min。

---

## 0. 数据底盘

| 维度 | 数字 | 备注 |
|---|---|---|
| 桌面 views | 98 个 `.vue` | 含 60+ 一级页面 + 子组件 |
| 移动 views | 11 个 `MXxx.vue` | 集中在 `src/views-mobile/`,挂 `/m/*` |
| 布局壳 | `DefaultLayout.vue`(300L)+ `MobileLayout.vue`(181L) | UA + 视口宽双判定切换 |
| 样式 token | `tokens.css`(359L) | 暗色 / 紧凑双重重载完整 |
| 全局样式 | `app.css`(1299L)+ `element-override.css`(1019L)+ `mobile-common.css`(1008L) | |
| 桌面 views 含 `@media` | **17 / 98 = 17.3%** | 大量页假设 ≥1280 |
| 桌面 views 含 `aria-*` | **共 35 处出现 / 涵盖 ~25 文件** | 多数集中在 `PageHeader` nav-arrow 按钮 |
| 桌面 views 用 `EmptyState`/`DataState` | **28 / 98 = 28.5%** | 其余仍走 `v-loading` + el-table 空态(43 处 v-loading) |
| 桌面 views 用 `el-skeleton`/`TableSkeleton` | **2 / 98** | 几乎全部经 `ProTable` 间接获得 |
| `ProTable` 接入率 | **32 / 98 = 32.6%** | 还有 60+ 页面手写 `el-table` |
| 固定 `width="\d+px"` 弹层 | **4 处** dialog 写死 800/640px | 平板 768 / 手机会溢出 |

---

## 1. 布局体系(DefaultLayout / MobileLayout)

### 1.1 DefaultLayout 结构(`src/layout/DefaultLayout.vue`)
```
<el-container.layout-root>
  <LayoutSidebar />                 ← 320L,展开/收起逻辑 BP.lg(≤1280) 自动收起
  <el-container.layout-shell>
    <MaintenanceBanner /> <DegradationBanner /> <NoTenantBanner />
    <LayoutHeader />                ← 顶栏 + Tabs + 命令面板触发
    <el-main.layout-main>
      .layout-main__surface         ← 白卡片外壳(不滚)
        .layout-main__body          ← 唯一垂直滚动层(整页不滚)
          .layout-main__content     ← max-width: 1600 居中
            <KeepAlive :max="20">
              <RouterView />
```

亮点:
- **滚动锁在容器内层**(`layout-shell-lock` 类挂到 `<html>`),避免双滚动条;sticky header / sidebar 0 抖动。
- 三主卡片(侧栏 / 顶栏 / 主内容)间距 / 边距 / 圆角统一由 `--layout-panel-*` token 收口。
- `is-focus`(沉浸模式) 把 sidebar/header 隐藏,只剩 RouterView;有专门的退出 FAB。
- `prefers-reduced-motion` 在主壳、卡片、tabs、segmented 等 ~10 处都做了禁用。

### 1.2 MobileLayout(`src/layout-mobile/MobileLayout.vue`)
```
.mobile-layout (100dvh)
  <MobileAppBar />        ← Large Title 折叠,scrollTop>24 切 inline
  <MaintenanceBanner /> <DegradationBanner />
  <main.mobile-layout__content>
    <transition page-push/pop/fade>
      <KeepAlive :max="10"><RouterView />
  <MobileTabBar />        ← 含 safe-area-inset-bottom
  <MInstallHint /> <SwUpdatePrompt />
```

亮点:
- **iOS 风格 push/pop 路径深度推断**:同层级 fade,加深 push,变浅 pop;切页自动 scrollTop=0。
- **iOS 安全区**:`mobile-common.css` + 5 处布局组件全部用 `env(safe-area-inset-bottom/top)`;底部 tab 用 `calc(76px + env(...))`,首屏不会被 home indicator 遮挡。
- `100dvh`(动态视口高度)而不是 `100vh`,iOS 浏览器栏收起时不会"弹起 70px"。
- `useMobileDetect.ts`:UA(`iphone|ipad|ipod|android|mobile`)**或** 视口 <768px,登录跳转和"请用桌面"挡板共用。

### 1.3 缝隙
- **D-LAY-01(P2)** `LayoutSidebar.vue` 仅监听一次 `BP.lg`(1280),没有暴露平板 768–1280 中间档,导致 1366×768 笔电(命中 md)体验和 1920 一样,而非"次紧凑"。建议加 `data-density="compact"` 自动门槛。
- **D-LAY-02(P2)** 移动端 `MobileLayout.__content` 切页时使用 `position: absolute` 退出动画,iOS Safari 在 SF Pro/中文混排下偶尔有"半像素抖动"——可考察 `will-change: transform` 显式提示。

---

## 2. Token / 栅格 / 间距

### 2.1 token 体系(`src/styles/tokens.css`,359L)
- **断点 token + 常量同源**:CSS `--bp-xs..--bp-xxl` 与 `src/constants/breakpoints.ts` 中 `BP.{xs..xxl}` 数字相同,且暴露了 `BP.mdMq`(matchMedia 字符串)给 TS 用。✅ 这是少见的 done-right。
- **空间 token**:`--space-{xs,sm,md,lg,xl}` 4/8/16/24/32,Material/Tailwind 8-grid 基线;另有页面级 `--page-canvas-padding`、`--page-section-gap`、`--page-block-gap`、`--card-inner-padding`,**紧凑模式覆盖 7 个变量** 即可一键 dense(`data-density='compact'`)。
- **圆角统一**:`--radius-content: 12px` 一处定义,`--radius-button / --radius-input / --radius-card{-lg}` 全部别名映射,无散值。
- **按钮调色板**:Light/Dark 双版完备,带 `*-soft-*`(背景化按钮)的 12 色变体,且有"hover/active 比 base 深 8–10% L"的明文规则。

### 2.2 栅格实践
- **几乎不用 `el-row / el-col`**:`src/views` 中只有 **25** 处出现 `el-row|el-col`(主要是 form 内 2-列布局)。
- 主要用 **CSS Grid + flex**:`display: flex` 167 处、`display: grid` 69 处(views+components)。
- Ops 仪表板栅格全部 `repeat(auto-fit/auto-fill, minmax(220px–320px, 1fr))`,这是真正的 intrinsic 响应式(不依赖断点)。✅ 比 24 栏强制断点更适配多分辨率。

### 2.3 缝隙
- **D-GRID-01(P2)** 部分 OpsTrend / OpsDist 面板用 `repeat(2, minmax(0, 1fr))` 和 `repeat(3, minmax(0, 1fr))` 固定列数,在 1366 笔电下单元宽度不到 350px,echarts 图标签被裁;建议改成 `auto-fit, minmax(420px, 1fr)`。
- **D-GRID-02(P3)** `--page-block-gap: 10px` 与 `--page-section-gap: 14px` 差距只有 4px,扫读时层级感弱;紧凑模式 8/10 差 2px 更弱。建议拉到 8/16 或 12/24。

---

## 3. 响应式

### 3.1 桌面侧覆盖率(P0 痛点)
- **17 / 98 桌面页有 `@media`**,余下 81 页假设 ≥1280。
- 即使有 `@media`,断点也散乱:`720 / 768 / 900 / 1024 / 1100 / 1280` 6 种值都出现过——和 `breakpoints.ts` 收敛到的 6 档基本对齐但未强制。
- ✅ `breakpoints.ts` 注释里明文承认"之前各组件用了 9 个散值,改一个 breakpoint 要扫 17 个 @media",**已经收敛 token,但页面 .vue 内仍在写裸数字**。

### 3.2 ProTable / PageContainer / PageHeader / SectionCard 全部 0 `@media`
- 这 4 个核心容器组件**没有任何响应式样式**,意味着所有列表页在窄屏下完全靠 `el-table` 内部横向滚动兜底。
- `JobInstanceList` 12+ 列、`fixed="right"` 操作列 200px、所有列累计宽度 = **≥ 1250px**(仅前 8 列就已 1250)+ 操作列 200 + 实例号/序号/队列 540 → 实际 ≥ **2000px**。1366 笔电下横向必滚。

### 3.3 移动端
- `MobileLayout.__content` `padding: 10 16 calc(76+safe-area-bottom)`——左右 16 已硬编码,无 `@media`(因为只在 `/m/*` 路由展示,且 mobile-common 内 `@media (max-width: 768px)` 只针对 2 处,基本足够)。
- `MJobInstances` / `MOpsSummary` 用 `m-card` / `m-page` 风格,**纯 list 卡片堆叠**,无横向滚动。
- 🚨 **平板横竖屏切换没有专门适配**:iPad Pro 11 横屏 1194×834,UA 含 `iPad` → 走 mobile,但 mobile 视觉一行 1 卡片完全浪费横向空间;UA 不含 iPad(iPadOS 13+ 默认伪装 macOS)→ 走桌面,但 834 高度让 sidebar+header+toolbar 占走 50%。

### 3.4 缝隙
- **D-RES-01(P0)** ProTable / PageContainer / PageHeader / SectionCard **零响应式**:列表页在 1366 笔电下必须横向滚;手机访问桌面页(意外打开)体验灾难。建议:
  - ProTable 内打开 `responsive` mode,根据 `useResponsive` 隐藏 priority<=2 的列;
  - 或挂 `@media (max-width: 1024px)` 的字号 / padding 调整。
- **D-RES-02(P0)** `index.html` viewport 写 `user-scalable=no` + `initial-scale=1.0`——**违反 WCAG 1.4.4(Resize text)**,低视力用户无法 pinch zoom;PWA 体验/沉浸感不抵 a11y 法律风险。建议改 `maximum-scale=5.0, user-scalable=yes`。
- **D-RES-03(P1)** 没有 `useResponsive` composable / `useBreakpoint`(`BP.mdMq` 注释提到但未实现)。所有 JS 侧响应式只有 `LayoutSidebar` 一处 `window.innerWidth <= BP.lg`,缺统一 hook。
- **D-RES-04(P1)** 平板(768–1024)走桌面布局但密度未自动收敛——sidebar 仍 220px、ProTable 仍按 1600 宽设计;建议 768–1024 自动 `data-density='compact'`。
- **D-RES-05(P1)** iPadOS 13+ desktop-class UA 检测缺失:`useMobileDetect` 只匹配 `iphone|ipad|...`,但 iPadOS 默认 UA = Mac,所以 iPad 用户走桌面 → 触控目标 < 44px 不达标。
- **D-RES-06(P2)** 弹层固定宽:`FileList auditVisible width=800px`、`ConfigReleaseList diffVisible width=800px`、`TenantList resultVisible width=640px`、`PipelineDefinitionList drawer size=800px`——平板/手机会出现"800px 在 768 视口上溢出"。建议改 `width="min(800px, 92vw)"`。

---

## 4. 滚动 / overflow

### 4.1 桌面
- **页面级不滚,容器级滚**:`<html>` 加 `layout-shell-lock` 类锁滚,`layout-main__body` 是唯一垂直滚动层。✅ 业界 console / dashboard 最佳实践。
- 自定义 webkit 滚动条:`width: 8px`、轨道透明、thumb 用 `color-mix` + hover 切 primary,且声明 `scrollbar-width: thin`(Firefox 兼容)。
- 横向滚动只交给 `el-table` 内部 fixed 列(`fixed="right"` + 全列宽配置)。

### 4.2 移动
- `<main.mobile-layout__content>` 唯一滚动层,`overflow-y: auto`、`overflow-x` 隐式 visible。
- ❌ 未配置 `-webkit-overflow-scrolling: touch`(iOS 13+ 默认开启,可忽略)。
- ❌ 无 momentum scroll bounce 自定义,但 `MPullRefresh.vue` 有 152L 自实现下拉刷新——逻辑独立,未与列表 scroll 联动 RAF。

### 4.3 缝隙
- **D-SCR-01(P2)** 桌面 `layout-main__body` 用 `scrollbar-gutter: auto` 而非 `stable` —— 有内容滚动出现时整体布局会"抽 8px",首屏短内容→长内容切换抖动可见。
- **D-SCR-02(P2)** ProTable 横向滚动条贴底,长列表下需"滑到底"才能水平滚;建议套层 sticky horizontal scrollbar(GitHub PR diff 那种)。
- **D-SCR-03(P3)** 无统一"sticky toolbar"模式:`PageHeader` 滚动不 stick(`position: relative`),长表格滚下后顶部按钮需回滚才能用——常驻"返回顶部" FAB 缺失。

---

## 5. 空态 / Loading / 错误态

### 5.1 三件套(✅ 设计合理)
- `EmptyState.vue`:**9 种 variant**(empty / forbidden / error / offline / network / filter-empty / tenant-empty / no-permission / service-down),配套 i18n key 完整。
- `DataState.vue`:loading→骨架,error→empty-state + 重试 CTA,empty→variant-driven,这是行业最佳实践。
- `TableSkeleton.vue`:`ProTable` 首屏自动 6 行骨架,数据已有时只用 `v-loading` 不切骨架避免抖动。

### 5.2 实际覆盖率(❌ 差距大)
- `ProTable` 接入率 32.6%,剩余 60+ 页仍手写 `el-table` + `v-loading` + `empty-text`——大概率走"暂无数据"四个字兜底,**error 退化成 empty**,用户分不清是真空还是 BE 504。
- 28 / 98 页用 `EmptyState`/`DataState`,其余无 explicit empty / error 区分。
- 2 / 98 页用 `el-skeleton`(其余依赖 `TableSkeleton` 由 `ProTable` 间接提供)。

### 5.3 缝隙
- **D-EMP-01(P0)** **60+ 列表页未接 ProTable**,丢失三态统一、分页持久化、骨架。建议建一个 sweep PR 单独迁移;已 32 页接入说明阻力不大,主要是 sunk-cost。
- **D-EMP-02(P1)** 手写 `el-table` 的页 error 状态全部退化成 empty——掩盖真问题。建议加 lint 规则禁止裸 `el-table`,强制走 ProTable 或 DataState 包装。
- **D-EMP-03(P2)** 移动端无 `MEmptyState`:`m-page`/`m-card` 风格下空态全靠各页自己 `<div class="m-empty">` 手写文案,12+ 个移动页文案各异,无 i18n 统一。

---

## 6. a11y

### 6.1 现状(❌ 远未达 WCAG 2.1 AA)
- `aria-*` 全 src 仅 **35 处** 出现,涵盖 ~25 文件,**很大比例集中在 PageHeader 的 nav-arrow 按钮**(已正确 `aria-label`)。
- `tabindex` 全仓只有 **5 处**。
- `alt=` 全仓未匹配到任何条目——意味着所有 `<img>`(若有)未配 alt。
- `index.html` `user-scalable=no` 已述,违反 WCAG 1.4.4。
- ✅ 颜色对比:`--color-text-tertiary` 从 `#8c8c8c`(4.45:1 不达标)调到 `#6b6b6b`(≈5.74:1 通过 AA)— token 注释有明确记录,说明团队懂规则,但未系统化扫描。
- ✅ `prefers-reduced-motion: reduce` 在主壳、卡片、tabs、segmented、登录页 至少 10 处都做了禁用,这一项做得好。

### 6.2 缝隙
- **D-A11Y-01(P0)** `user-scalable=no` 违反 WCAG 1.4.4。需要改。
- **D-A11Y-02(P0)** ProTable 操作列按钮多为 icon-only(`Refresh` / `Download` 等),全无 `aria-label`——键盘 + 屏幕阅读器用户听到 "button button button"。建议给所有 icon-only `el-button` 自动注入 `:aria-label="$attrs.title || tooltip"`。
- **D-A11Y-03(P1)** 表格行无 `aria-rowindex` / `aria-selected`,el-table 默认有部分 aria 注入但 highlight-current-row 没传播到 SR。
- **D-A11Y-04(P1)** 弹层 `el-dialog` / `el-drawer` 默认 trap focus,但**初始 focus 落点** 没有显式 `ref + autofocus`,SR 用户开弹层后 focus 落到外层 div 而不是第一表单项。
- **D-A11Y-05(P1)** 表单 31 / 29 页用了 `el-form` 但只有 ~10 页显式 `label-width`,其余靠 Element 默认 80px,中英切换后(en label 一般 1.4x 长)label 截断。
- **D-A11Y-06(P2)** `tabindex="-1"` 未在跳过链接(skip-to-content)出现——键盘用户每次进页要按 30+ Tab 才能到主内容。建议加 visually-hidden skip link。
- **D-A11Y-07(P2)** 颜色靠纯色传达状态(StatusTag),不带 icon 或文字 prefix;对色盲用户的 alert/danger/success 区分依赖红绿。

---

## 7. 国际化布局

### 7.1 现状
- `src/locales/zh-CN.ts` 4119L、`en-US.ts` 4165L,**两份 keys 数量基本一致**(46L 差异 ≈ 注释差),i18n 覆盖完整。
- `ElConfigProvider :locale` 在 App.vue 透传 Element Plus 内置文案。
- 路由 meta + `useI18n` 双重解析 `page.<pathKey>.title/description`,PageHeader 会自动跟随。
- 数字 / 日期 / 货币 **没有统一格式化工具**(grep `Intl.NumberFormat` / `Intl.DateTimeFormat` 在 src 中只在 i18n / DatetimeColumn 中各 1 次)。

### 7.2 缝隙
- **D-I18N-01(P1)** EN label 长度普遍是 ZH 的 1.4–1.8x,固定 `label-width="88px"` 在 EN 下大概率截断;建议 EN 切换时自动调宽 1.4x 或改成 `auto`。
- **D-I18N-02(P1)** 列宽硬编码 (`<el-table-column width="140">` 等)→ EN header "Operation Type" 比 ZH "操作类型" 长 30%,EN 表头会换行 / 省略。建议改 `min-width`。
- **D-I18N-03(P2)** 没有统一的 `useNumberFormatter` / `useDateFormatter`——千分位、小数位、本地化日期靠各页 `.toLocaleString()` 散写,容易漏。
- **D-I18N-04(P2)** RTL(阿拉伯 / 希伯来)未规划:CSS 大量 `margin-left/right`、`padding-inline-start` 用得少。即使近期不上 RTL,token 应改 logical(`margin-inline-start`)以备。
- **D-I18N-05(P3)** Element Plus 时区/语言只切了 locale,**未切 date-format**(MM/DD/YYYY vs DD/MM/YYYY)——EN 用户预期 12h、MM/DD/YYYY。

---

## 8. 核心页深扫

### 8.1 WorkflowMermaidViewer(`src/views/workflow/WorkflowMermaidViewer.vue` 1167L)
- ⚠️ 没有"WorkflowEditor",只有 **Viewer**——用 mermaid 渲染只读 DAG,inspector 右侧侧拉。
- Tools 工具栏:Zoom in/out / Fit / Reset / Pause poll / Refresh / Download / Fullscreen 7 个圆 icon 按钮,全部带 `el-tooltip`,但 `el-button` 本身缺 `aria-label`(tooltip 仅鼠标可达)。
- `dag-split` 用 flex,`dag-split--with-inspector` 时 graph + inspector 二栏。
- `fullscreen` 模式直接 cover layout,但内部 inspector 默认 `min-width: 0` 不约束最小宽,inspector 在 1366 + 长 nodeCode 时挤出。
- Legend 用 `.dag-legend` chip 行,flex-wrap,可换行。
- ❌ 全无 `@media`——纯桌面假设。1024 平板下 graph + 320 inspector 直接撑出滚动条。
- **D-PG-WF-01(P1)** Inspector 宽度未限,长 nodeCode 超过;建议 `max-width: 360px; word-break: break-all`。
- **D-PG-WF-02(P1)** 工具栏 icon-only 按钮无 aria-label,只用 tooltip——键盘 / SR 用户失明。

### 8.2 JobInstanceList(`src/views/monitor/JobInstanceList.vue` 406L)
- 12+ 固定 width 列,**前 8 列累计 1250px**,加 fixed-right 200 + instanceNo 180 + queue 160 + scheduledAt 160 + tags 180 ≈ **2130px**。
- 1366×768 笔电下:sidebar 220 + padding 30 = 主区 ~1116,**表格强制横滚 1000+px**——每次操作都要滚。
- ✅ 用 ProTable 接入。
- ✅ 用 DatetimeColumn 抽象。
- ❌ 不响应隐藏次要列,固定 12 列全暴露。
- **D-PG-JI-01(P0)** 列宽用 fixed `width=` 不用 `min-width=`,EN 切换会换行;同时 1366 笔电必滚。建议 priority 标记 + 1024 以下隐藏 priority<=2。

### 8.3 WorkflowRunList(273L)
- 类似 JobInstanceList,列数稍少;同样 fixed width 全表无响应。

### 8.4 OpsSummary(263L)
- ✅ 4 tab(KPIs / Trend / Dist / Extra)+ pill-tabs 风格(在 `app.css` 900px 转横向 wrap)。
- ✅ 全 0 引导卡片(`isFreshTenant` 时显示 4 个新手卡 `repeat(auto-fit, minmax(220px,1fr))`)。
- ❌ OpsTrendPanel `repeat(2, minmax(0,1fr))` + 1024 切单列;OpsDistPanel 3→2→1 三级。**断点 1024 / 768 硬写,未引 token**。
- **D-PG-OS-01(P2)** trend 2 列在 1366 时图标签溢出;建议改 `auto-fit, minmax(420px, 1fr)`。

### 8.5 AlertList(421L)
- ProTable + 操作列 fixed-right 260px。
- ❌ 同样所有列 fixed width,无 `@media`。

### 8.6 ApprovalList(40L)
- 极简壳:pill-tabs(General / CatchUp)+ 两 Tab 组件。
- ✅ tab 通过 router query 持久化。
- 无问题。

### 8.7 MJobInstances(404L)
- ✅ `m-card` 卡片堆叠,无横滚。
- ✅ 5 字段 grid 行(`m-card__meta`),竖向自适配。
- ❌ 没有 `m-empty`,空态文案各页自写。

### 8.8 MOpsSummary(163L)
- 全 0 `@media`(本身就在 `/m/*`,移动 viewport 假设)。
- 卡片直接堆。

---

## 9. 新功能页

### 9.1 WorkerFingerprintBoard(308L)
- ✅ 引入 EmptyState 2 次(列表空 / 详情空)。
- ❌ 无 `@media`,无 fingerprint 列表横向滚动条管理。

### 9.2 SensitiveFieldAlert(`src/components/common/SensitiveFieldAlert.vue` 98L)
- 组件级,自带 test。
- 无布局缝隙(纯标签组件)。

### 9.3 AtomicTaskTypeCenter(380L)
- ✅ EmptyState 接入。
- ❌ 无 `@media`。
- ❌ Form `label-width="160px"`(高于平均 88px),EN 标签下勉强能放,但平板下 form 控件挤压。

### 9.4 CustomTaskTypeList(266L)
- 常规列表页;未深查媒体查询(grep 显示无 @media)。

---

## 10. 风险登记 + 优先级总览

### P0(必须修)
| ID | 标题 |
|---|---|
| D-RES-01 | ProTable/PageContainer/PageHeader/SectionCard 零响应式,列表页 1366 必横滚 |
| D-RES-02 | viewport `user-scalable=no` 违反 WCAG 1.4.4 |
| D-EMP-01 | 60+ 列表页未接 ProTable,丢失三态统一 + error/empty 区分 |
| D-A11Y-01 | viewport 禁缩放(同上,a11y 视角) |
| D-A11Y-02 | ProTable icon-only 操作按钮无 aria-label |
| D-PG-JI-01 | JobInstanceList 列宽 fixed 累计 >2000px,无 priority 收敛 |

**P0 合计:6**(2 项与 viewport 重叠,实际独立条目 5;按 ID 计 6)

### P1(应该修)
| ID | 标题 |
|---|---|
| D-RES-03 | 缺 `useResponsive` / `useBreakpoint` composable,JS 侧响应式只一处 |
| D-RES-04 | 平板 768–1024 未自动 compact density |
| D-RES-05 | iPadOS 13+ desktop-class UA 检测缺失 |
| D-EMP-02 | 手写 el-table 页的 error 退化成 empty,需 lint 强制 |
| D-A11Y-03 | el-table 行无 aria-rowindex/selected,SR 不可达 |
| D-A11Y-04 | el-dialog/drawer 缺初始 focus 显式绑定 |
| D-A11Y-05 | 31 页 form 缺 label-width 显式声明,EN 切后截断 |
| D-I18N-01 | label-width 88px 在 EN 下截断 |
| D-I18N-02 | el-table-column width 硬编码,EN header 换行 |
| D-PG-WF-01 | WorkflowMermaidViewer inspector 无 max-width |
| D-PG-WF-02 | WorkflowMermaidViewer 工具栏 icon-only 无 aria-label |

**P1 合计:11**

### P2 / P3
- D-LAY-01 D-LAY-02 D-GRID-01 D-GRID-02 D-RES-06 D-SCR-01 D-SCR-02 D-EMP-03 D-A11Y-06 D-A11Y-07 D-I18N-03 D-I18N-04 D-PG-OS-01(13 条)
- P3:D-SCR-03 D-I18N-05(2 条)

---

## 11. 行动建议(按优先级)

### Quick Wins(1–2 天)
1. **index.html viewport** 改 `maximum-scale=5.0, user-scalable=yes` —— 一行解决 D-RES-02 / D-A11Y-01。
2. **ProTable 自动注入 icon-button aria-label**:用 `useAttrs()` 把 `title` 同步到 `aria-label`,覆盖所有 icon-only 操作按钮。
3. **弹层固定 width 改 `min(Xpx, 92vw)`**:扫 4 处 `width="800px"` / `size="800px"` 直接替换。
4. **加 useResponsive composable**:`const isLg = useMatchMedia(BP.lgMq)`,挂在 App 顶层 provide,业务 inject 即用。

### 中期(1–2 周)
5. **桌面 4 大容器组件加 @media**:`PageHeader` 在 768 收纵向、`SectionCard` padding 在 1024 切 12px、`PageContainer` gap 收敛、`ProTable` 在 1024 隐藏 priority<=2 列。
6. **JobInstanceList / WorkflowRunList / AlertList 列加 priority 标注**:核心 5 列必显,其余按 priority 在 `useResponsive` 控制。
7. **统一 `data-density` 自动开关**:1024 以下自动加 compact。
8. **lint 规则**:`no-bare-el-table`,强制走 ProTable 或 DataState 包装。

### 长期(2–4 周)
9. **a11y 系统化**:跑 axe-core 扫一遍 60 页,补 aria-label / skip-link / focus management。
10. **i18n long-label 适配**:label-width / column width 全部改 token 化,EN/ZH 不同值。
11. **iPadOS desktop-class 检测**:UA + `ontouchstart` + 视口宽三角检测,iPad 横屏走"平板优化版"(sidebar 可折叠 icon-only + 触控目标 ≥44px)。

---

## 12. 范围说明

- 仅扫 `batch-console`(运营 + 移动 PWA),未含其它前端项目。
- 仅看了核心样式 / 布局 / 4 大容器组件 / 8 个核心页 + 4 个新功能页;未逐页过 98 个 view。
- a11y 评估基于静态扫描,未跑 axe-core / Lighthouse(运行验证留给修复阶段)。
- 性能维度(bundle / FPS / LCP)不在本次范围。

## 附:扫描凭据

```
桌面 views with @media:        17
mobile views (含 styles)       7 个文件 grep safe-area
含 aria-* 文件                 ~25
TabIndex 出现                  5
EmptyState/DataState 接入       28
ProTable 接入                  32
v-loading 散用                 43 lines / 28 files
固定 px width dialog/drawer     4
JobInstanceList 列宽总和       前 8 列 1250px → 全表 ≈2130px
label-width 显式               10
el-form 总出现                 29 files
```

文件清单(本次主要参考):
- `/Users/dengchao/Downloads/batch-console/src/layout/DefaultLayout.vue`
- `/Users/dengchao/Downloads/batch-console/src/layout-mobile/MobileLayout.vue`
- `/Users/dengchao/Downloads/batch-console/src/styles/tokens.css`
- `/Users/dengchao/Downloads/batch-console/src/styles/app.css`
- `/Users/dengchao/Downloads/batch-console/src/constants/breakpoints.ts`
- `/Users/dengchao/Downloads/batch-console/src/components/common/{PageContainer,PageHeader,SectionCard,EmptyState,DataState}.vue`
- `/Users/dengchao/Downloads/batch-console/src/components/table/{ProTable,ListPageQueryBar,TableSkeleton}.vue`
- `/Users/dengchao/Downloads/batch-console/src/views/workflow/WorkflowMermaidViewer.vue`
- `/Users/dengchao/Downloads/batch-console/src/views/monitor/JobInstanceList.vue`
- `/Users/dengchao/Downloads/batch-console/src/views/ops/OpsSummary.vue`
- `/Users/dengchao/Downloads/batch-console/src/views-mobile/{MJobInstances,MOpsSummary}.vue`
- `/Users/dengchao/Downloads/batch-console/index.html`
