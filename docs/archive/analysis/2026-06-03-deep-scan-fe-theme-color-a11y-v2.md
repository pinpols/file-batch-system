# FE 配色 + 主题 + a11y 深度扫描报告 — v2 补充扫描

- **日期**:2026-06-03
- **扫描仓库**:`batch-console`(基于 `main` HEAD,只读)
- **范围**:**v1 死角补扫**(`2026-06-03-deep-scan-fe-theme-color-a11y.md`)
  - v1 重点:design token / 暗色模式 / WCAG 对比度 / 状态色 / 图标 / 图表色板 / 主题切换
  - v2 重点:**真实 axe / Lighthouse 跑分 / 键盘 trap / ARIA 语义 / landmark / table / form / chart a11y / 减少动效 / RTL / 错误恢复 / 打印 / 跨浏览器**
- **方法**:静态扫描 + `e2e/a11y.spec.ts` 测试用例分析 + `.github/lighthouse-budget.json` 解析
- **范围声明**:只读不改代码,所有发现仅供后续 PR 参考。
- **基线对照**
  - v1 总评:**B+ / 良**(视觉层强项突出)
  - v2 修正后总评:**B(中良偏上)**(深挖屏阅读器路径 / landmark / 表单错误恢复后,实际"看不见的 a11y 债"比 v1 估计的多)
  - v1 问题分布:P0 2 / P1 5 / P2 6
  - **v2 新增**:P0 **1 个**、P1 **13 个**、P2 **18 个**

---

## 0. v2 执行摘要

v1 集中在**视觉层(色 / 主题 / 对比度 / 焦点圈)**,这一层 batch-console 做得相当扎实(全局 `:focus-visible` outline 环、token 化、`prefers-reduced-motion` 9 处覆盖、`startViewTransition` 降级)。

v2 切到屏阅读器 / 键盘 / 国际化 / 错误恢复角度,真正的隐患不是视觉而是语义层:

1. **landmark 错位**(P0):侧栏 `<el-aside>` → ARIA **complementary**,主导航被分类为"补充内容",屏阅读器跳 landmark 找不到主菜单。
2. `aria-current` / `aria-expanded` / `aria-controls` **全 0**:菜单当前项 / 折叠状态 / tab→panel 关联零标记。
3. **800+ el-table-column,0 aria-label / 0 sortable**:VO 报不出"这是什么表"。
4. **图表完全不可访问**:ECharts Canvas + `aria.enabled` 关 + 无 `<table>` fallback。
5. **表单错误恢复**:`scrollToField` 全仓 1 处,9+ form validate 失败不 auto-focus,`aria-describedby` 帮助文本 0 关联,`aria-required` 显式 0。
6. **错误反馈**:`ElMessage.error` 全仓大量使用,但 EP `ElMessage` 不带 `role="alert"`,屏阅读器不播报。
7. **RTL 零** / `prefers-contrast` 零 / `forced-colors` 零 / `@media print` 零;Lighthouse CI 只跑 desktop preset(移动 `/m/*` 无 a11y 跑分)。

**好消息**:axe-core P0 10 页 + critical/serious 都 fail(严格档);LH a11y 阈值 0.9 error;全局 `:focus-visible` outline 环实现一流;EP `focus-trap` 库自带 modal 焦点管理。

---

## 1. 真实 WCAG 自动化扫(axe-core / Lighthouse)

### 1.1 现状

**axe-core @ Playwright(`e2e/a11y.spec.ts` 128 行)**:覆盖 P0 10 页(/login, /ops/summary, /system/tenants, /monitor/job-instances, /approvals + 5 个 P0 表单页),阈值 critical + serious 都 fail(比行业基线"仅 fail critical"更严),`withTags(['wcag2aa', 'best-practice'])`,全局豁免 `'color-contrast'`(注明 EP issue#14523),minor/moderate 仅 console.warn。

**Lighthouse CI**(`.github/lighthouse-budget.json`):`categories:accessibility: ["error", { minScore: 0.9 }]`。preset `desktop`,1 run。

### 1.2 死角

**[v2-A1 / P1] axe 没在 webkit 单独跑** — `a11y.spec.ts` 描述带 `@cross-browser` tag,但 tag 仅供 grep,实际 project 过滤仍可能漏 webkit(Safari VO 对 `role="dialog"` 行为与 Chrome 不同)。建议显式跑 chromium + webkit 两套,firefox 跳过(企业 IT 后台 Firefox 占比 < 5%)。

**[v2-A2 / P1] Lighthouse 只跑 desktop preset** — 移动端独立路由 `/m/*` + `MobileLayout` 没有 LH 跑分。建议 LH 改跑 desktop + mobile 两份,移动端阈值降到 0.85(viewport + touch target 体系不同)。

**[v2-A3 / P2] axe `color-contrast` 全局禁用范围过大** — 应改为 EP plain 按钮精确豁免 `.exclude('.el-button.is-plain')`,放开其他页面对比度检测。

### 1.3 预期 LH 分数预测

预测基于经验:有全局 `:focus-visible` 环 + EP 默认 ARIA 通常拿 85+,无 chart fallback / 无表格 caption / 部分 icon button 无 label 通常各扣 2-5 分。

| 页面 | 预测 LH a11y | 主要扣分项 |
| --- | --- | --- |
| /login | 92-95 | 表单标签关联(EP 隐式 label) |
| /ops/summary | 85-90 | ECharts canvas 无 alt / 无 `<figure>` |
| /system/tenants | 88-92 | 表格无 caption,行操作 icon-only 缺 label |
| /monitor/job-instances | 86-90 | 同上 + 实时刷新无 polite live region |
| /approvals | 88-92 | actions 列 icon-only 按钮 aria-label 不全 |

---

## 2. 键盘 trap & focus 管理

**现状**:41 个 `<el-dialog>` 全靠 EP 自带 `focus-trap` 库(可信);`CommandPalette.vue:340` 显式 nextTick focus 输入框(优秀);`tabindex="0"` 11 处,**0 处 `tabindex > 0`**(良好,不破坏 DOM 顺序)。

**[v2-K1 / P1] dialog 关闭后焦点是否回 trigger 未验证** — EP 理论上还原 lastFocusedElement,但嵌套 dialog / append-to-body 场景未 e2e 校。建议补 1 测:打开 → ESC → `expect(activeElement === triggerButton)`。

**[v2-K2 / P2] `<div role="button">` 多数缺 keydown.space 处理** — `LayoutTabs.vue` 3 处 / `LayoutHeader.vue` 3 处 / `JsonNode.vue` / `Login.vue` / `UserRole.vue` / `SnapshotKpiTab.vue` 各 1 处。原生 button 自带 Enter+Space,div+role=button 需手动 `@keydown.enter @keydown.space.prevent`。多数实现只支持 Enter,违反 WCAG 2.1.1。

**[v2-K3 / P2] focus order 无 e2e 验证** — SPA 经常用 `v-if` / `v-show` / `append-to-body` portal 打乱真实焦点序列。建议补 1 个 Tab 序列断言用例。

---

## 3. ARIA 语义错位

**[v2-R1 / P1] `<div role="button">` 应改 `<button>`** — 10+ 处。`LayoutHeader.vue:107` Logo / `UserRole.vue:20` 矩阵单元 / `JsonNode.vue:10` 折叠箭头都该用原生 `<button type="button">`,省去 tabindex + keydown 维护。

**[v2-R2 / P2] `role="tab"` 不带 `aria-controls`** — `SchedulerSnapshot.vue:35` + 移动端 4 视图(`MWorkers/MApprovals/MJobInstances/MAlerts`)都用 `role="tablist" + role="tab" + role="tabpanel"`,**0 处 aria-controls 关联**。VO 用户进 tablist 后没法跳到对应 panel。修复模式:
```html
<button role="tab" :aria-controls="`panel-${id}`" :id="`tab-${id}`">
<div role="tabpanel" :id="`panel-${id}`" :aria-labelledby="`tab-${id}`">
```

**[v2-R3 / P2] aria-label 与 visible label 可能重复** — `MobileTabBar.vue:2` `:aria-label="t('nav.mobileTab.ariaLabel')"` 下方就是可见 tab 文字。需校核 i18n key 内容是否仅描述 landmark(如"主导航"),避免与可见文字冲突。

---

## 4. 屏阅读器 landmark 路径(VO/NVDA)

**v2 最重要的发现 — v1 完全漏掉**。

### 4.1 现状 DOM landmark 结构

```
<el-container class="layout-root">                ← <section>
  <el-aside>                                       ← <aside> = ARIA complementary  ❌
    <LayoutSidebar>                                ← 主导航 — 被错分类为"补充内容"
  <el-container class="layout-shell">
    <MaintenanceBanner role="alert" />             ← assertive
    <DegradationBanner role="status" />            ← polite
    <LayoutHeader><el-header /></LayoutHeader>     ← <header> = ARIA banner ✓
    <el-main>                                      ← <main> = ARIA main ✓
      <router-view />
```

### 4.2 死角

**[v2-L1 / P0 新增] 主导航 landmark 错位** — `LayoutSidebar.vue:2` 用 `<el-aside>` 渲染为 `<aside>`(ARIA 隐式角色 = complementary)。屏阅读器(NVDA Insert+F7 / VO Rotor)列 landmark 时**主导航被标为"补充内容"**,新用户找不到主菜单。

修复:
```vue
<el-aside role="navigation" :aria-label="t('nav.mainAriaLabel')">
```

改动 1 行 + 1 个 i18n key。影响范围:所有桌面用户走主菜单(每个页面访问都过)。**真正的 P0 死角**。预期 LH a11y 分 +2~3。

**[v2-L2 / P1] 没有 skip link("跳到主内容")** — WCAG 2.4.1 Bypass Blocks AA 要求。Login 后每次进入页面键盘用户需要 Tab 过整个侧栏(数十次)才能到主内容。修复模式:
```html
<a href="#main-content" class="skip-link">{{ t('a11y.skipToMain') }}</a>
<el-main id="main-content" tabindex="-1">...
```
配 CSS `.skip-link { position: absolute; top: -40px } .skip-link:focus { top: 0 }`,reduced-motion 关 transition。

**[v2-L3 / P2] 缺 `<el-footer>` contentinfo landmark** — 可不致命,但 LH 评分会扣"missing landmark"。可放版本号 / 状态。

---

## 5. table 语义

**现状**:el-table 41+ 视图 / el-table-column **804 个** / `<caption>` 0 / `sortable` **0** / `aria-sort` 0(无排序所以无债)/ `summary` prop 1 处(EP `summary-method` 汇总行,非 ARIA summary)。EP el-table 自动加 `<th scope="col">`(行业默认信任)。

**[v2-T1 / P1] 表格无 aria-label / aria-labelledby 描述** — EP el-table 默认 `<table>` 没有 aria-label,屏阅读器进入只报"table 10 columns 50 rows",不知道是什么表。修复:
```vue
<el-table :data="rows" :aria-label="t('jobInstance.tableAriaLabel')">
```
注意 EP 把 aria-label 透传到根 `<div>` 而不是 `<table>` 元素,可能需在外层 `<section aria-labelledby>` 包裹替代。

**[v2-T2 / P2] 表格行可点跳详情,但无键盘等价** — `@row-click` 实现广泛,但行无 `role="button"` / `tabindex="0"`,键盘用户只能用操作列按钮。

**[v2-T3 / P2] 0 个 sortable 列** — 非严格 a11y 问题,但运维高密度表缺排序是功能缺。建议补,自动带 aria-sort 反馈。

---

## 6. 表单 a11y

**现状**:`<label for>` **全仓 0**(全靠 EP `el-form-item label` 隐式);`el-form-item` 757 处;`:required` / `:rules` 708 处;`validate()` 10+ 处;**`scrollToField` 仅 1 处**(`PipelineDefinitionList.vue:751`);`aria-invalid` 显式 0(EP 内部 `is-error` class);`aria-describedby` 0。

**[v2-F1 / P1] 长表单 validate 失败不 auto-focus 第一错误** — WCAG 3.3.1/3.3.3 推荐。当前 10+ form 只有 1 个 scrollToField,其他验证失败用户得自己找。修复:抽 `useFormValidate(formRef)` composable,在 `validate().catch(errors)` 中取 `Object.keys(errors)[0]` → `scrollToField` + nextTick querySelector input focus,所有 form 替换。

**[v2-F2 / P1] 帮助文本无 `aria-describedby` 关联** — form-item 下 `<div class="form-hint">` 提示(配额单位、Cron 示例)没关联到 input,VO 用户跳 input 听不到帮助。修复:`<el-input aria-describedby="cron-hint" />` + `<div id="cron-hint">`。

**[v2-F3 / P2] `aria-required` 不显式** — EP `:rules required: true` 渲染红星 CSS,但是否落到 `aria-required` 属性需校核 EP 2.14.1 实际 DOM(简单测试 `document.querySelector('.el-form-item.is-required input')?.getAttribute('aria-required')`,期望 `"true"`)。

---

## 7. 图表可访问性(ECharts)

**现状**:`src/charts/echarts.ts` 用 `CanvasRenderer` + 自定义 console-light/dark theme,**aria 配置 0**。

**[v2-C1 / P1] ECharts `aria.enabled` 关闭** — 视障用户拿不到任何图表信息。修复:全局 base option 注入 `{ aria: { enabled: true, decal: { show: true } } }`。`decal` 是色盲安全条纹叠加,与 v1 P0(7 色 palette 红绿同存)正交治理。

**[v2-C2 / P1] Canvas renderer 无 SVG DOM 文本 fallback** — Canvas 是 bitmap,屏阅读器完全读不到内部文本(轴标签、图例、tooltip 都是绘制的)。修复方向二选一:
- 改 `SVGRenderer`(性能略降但 DOM 可达,a11y 跳跃改善大)
- 保留 Canvas + 提供 `<table>` 数据等价(WCAG 1.1.1 Non-text Content 推荐)

运维后台密集图表通常推荐 Canvas + table fallback(SVG 数据量大时性能崩)。

**[v2-C3 / P2] 缺 `<figure>+<figcaption>` 包裹** — 给屏阅读器一个上下文锚点。

---

## 8. 动画 / 减少动效

**v1 已覆盖,v2 复核**:`@media (prefers-reduced-motion: reduce)` **9 处**(`app.css:123` / `element-override.css:59/524/859/920` / `DefaultLayout.vue:230` / `LayoutSidebar.vue:147` / `LayoutTabs.vue:245` / JS 层 `theme.ts:40`)。**A 级,行业领先**。

**[v2-M1 / P2] driver.js onboarding 未尊重 reduced-motion** — `useOnboardingTour.ts` 用 driver.js popover 动画,未传 `animate: false`。修复:
```ts
driver({ steps, animate: !matchMedia('(prefers-reduced-motion: reduce)').matches })
```

**[v2-M2 / P2] ECharts 动画未受控** — 默认 `animation: true`,新数据进来有过渡。reduced-motion 下应关。修复:`echarts.ts` 注册 base option 时读 matchMedia。

---

## 9. focus order 跳跃

`tabindex="0"` 11 处,**0 处 `tabindex > 0`**(良好,不破坏 DOM 顺序)。

**[v2-O1 / P2] 无 focus order e2e 用例** — DOM 顺序天然就是 tab 顺序,但 SPA 经常用 `v-if` / `v-show` / 嵌套 portal 打乱。建议补 1 个 e2e:Login 页 Tab 5 次,断言每步 activeElement 是预期顺序。

---

## 10. 多语言 LTR/RTL 支持

**现状**:zh-CN + en-US 双语;`dir="rtl"` 全仓 0(除 `DocsDrawer.vue:6` 用 EP drawer 方向 prop,与 HTML dir 无关);`margin-inline` / `padding-inline` / `inset-inline` **7 处**(layout 层用了逻辑属性,底子好);`direction: ltr/rtl` CSS 0。

**[v2-I1 / P2] RTL 完全没准备** — 绝大多数页面 CSS 仍用 `margin-left` / `padding-right`,`<html dir>` 没动态绑定。运维后台短期不需要 ar/he,但**未来要进中东市场**(石油 / 银行 IT)需要,工作量 ≈ 2-3 周。建议:不急改,但**新增组件强制逻辑属性**(eslint 规则可禁 `margin-left/right`、`padding-left/right`、`text-align: left/right`)。

---

## 11. 错误恢复 a11y

**现状**:`role="alert"` 2 处(`MaintenanceBanner` / `SwUpdatePrompt`);`aria-live` 1 处(`SwUpdatePrompt` 显式 polite);`role="status"` 2 处(`DegradationBanner` / `Login.loginTrace`)。

**[v2-E1 / P1] EP `ElMessage.error` 无 role=alert** — `ElMessage.error(...)` 全仓大量使用(几乎所有 mutation 失败),但 EP 2.x ElMessage 渲染 `<div class="el-message">` **无显式 role**,屏阅读器不会自动播报错误。修复:用 `ElNotification.error({ ..., role: 'alert', 'aria-live': 'assertive' })` 替代(`ElNotification` 默认带 role=alert),或封装 `notifyError()` 工具函数全仓替换。

**[v2-E2 / P1] form validate 失败无 aria-live 反馈** — submit 失败时 EP 在 form-item 下方画红色错误文字,但**没有任何 live region 播报**。屏阅读器用户点提交 → 没反应 → 不知失败。修复:form 周围套 `<div role="status" aria-live="polite">` + JS 在 validate fail 时填错误摘要(如"3 个字段未通过校验")。

---

## 12. 品牌 / Logo / 图标对比度

**[v2-B1 / P2] favicon 主色 `#1677ff` 在浏览器 tab 暗色背景对比度未验** — Safari Tab 暗色 + 主色蓝可能 < 3:1。建议 favicon SVG 加 white 外环 / 描边。

**[v2-B2 / P2] loading spinner 暗色 token 缺失** — `RouteProgressBar.vue` 顶条 + EP `<el-loading>` 主色蓝 spinner,白底 OK,**暗色模式下 spinner 色未单独定义**,可能在 `--color-bg-card` 上对比不足。建议抽 `--color-spinner-light/dark` token。

---

## 13. 打印模式无障碍

**`@media print` 全仓零**。

**[v2-P1 / P2] 打印失真** — 运维报表 / 审批单 / 配额报告打印会带 SPA chrome(侧栏 / 顶栏 / 操作按钮),暗色模式下打印是深背景白字 → 浪费墨水 / 不可读。修复模式(`app.css` 加):隐藏 sidebar/header/tabs/buttons,强制 white 背景 + black 文字,`a[href]::after { content: " (" attr(href) ")" }`,`tr, .el-table__row { page-break-inside: avoid }`,`html.dark { background: white !important }`,图表 max-width 100%。

**收益**:导出报表无需做单独 PDF 渲染,系统打印 → PDF 即可。

---

## 14. 跨浏览器一致性

**现状**:Playwright projects chromium / firefox / webkit;a11y.spec.ts 1 个文件无 project skip;LH 只跑 desktop;`/m/*` 路由无 a11y 验证。

**[v2-X1 / P1] iOS Safari a11y 路径未验** — VoiceOver on iOS 对 `role` / `aria-label` 解析与 macOS VO + Safari 有差异(`aria-modal=true` 在 iOS 上有 bug)。`MobileLayout` / `MobileTabBar` / 移动 4 视图全靠 webkit project 跑覆盖,**但 a11y.spec.ts 实测 10 页都是桌面路由**,没扫 `/m/*`。建议加 5 个 `/m/*` 路由 axe 用例,project 限定 webkit 跑(模拟 iOS Safari)。

**[v2-X2 / P2] Android Chrome TalkBack 完全未覆盖** — Playwright 不直接支持 TalkBack,需 Android emulator + BrowserStack。短期建议:真机 Android Chrome + TalkBack 跑一次 `/m/*` + `/login` 留报告。

**[v2-X3 / P2] Safari `:focus-visible` 鼠标点击后仍显示** — Safari 17 上 `:focus-visible` 对鼠标点击后的 element 有时仍显示 outline ring(Chrome 不会)。`element-override.css:935-952` 全局 outline 环可能在 Safari 鼠标用户视野中"突兀"出现。建议 e2e 加 1 个 webkit-only 视觉回归(`page.screenshot()` + diff)按钮 click 后断言 no outline。

---

## 15. 新增问题清单 vs v1

### 15.1 v2 新增问题

| ID | 严重度 | 主题 | 一句话 |
| --- | --- | --- | --- |
| **v2-L1** | **P0** | landmark | 侧栏 `<el-aside>` → ARIA complementary,主导航错位 |
| v2-L2 | P1 | landmark | 缺 skip link("跳到主内容") |
| v2-A1 | P1 | 跨浏览器 | axe 没在 webkit 单独跑 |
| v2-A2 | P1 | Lighthouse | LH 只跑 desktop preset,`/m/*` 无跑分 |
| v2-K1 | P1 | dialog focus | 关闭后焦点是否回 trigger 未验证 |
| v2-R1 | P1 | ARIA | `<div role="button">` 应改 `<button>` |
| v2-T1 | P1 | table | 41+ el-table 无 aria-label 描述 |
| v2-F1 | P1 | form | 9+ form validate 失败不 auto-focus 第一错误 |
| v2-F2 | P1 | form | 帮助文本无 aria-describedby 关联 |
| v2-C1 | P1 | chart | ECharts aria.enabled 全部关闭 |
| v2-C2 | P1 | chart | Canvas renderer 无 SVG / 表格 fallback |
| v2-E1 | P1 | error a11y | ElMessage.error 无 role=alert,屏阅读器不播报 |
| v2-E2 | P1 | error a11y | form validate 失败无 aria-live 反馈 |
| v2-X1 | P1 | 跨浏览器 | `/m/*` 移动路由无 webkit a11y 用例 |
| v2-A3 | P2 | axe | 全局禁 color-contrast 范围过大 |
| v2-K2 | P2 | keyboard | `<div role="button">` 缺 keydown.space |
| v2-K3 | P2 | keyboard | focus order 无 e2e 验证 |
| v2-R2 | P2 | ARIA | tablist/tab/tabpanel 缺 aria-controls |
| v2-R3 | P2 | ARIA | aria-label 与 visible label 可能重复 |
| v2-L3 | P2 | landmark | 缺 contentinfo landmark |
| v2-T2 | P2 | table | 行点击跳详情无键盘等价 |
| v2-T3 | P2 | table | 0 个 sortable 列(功能性) |
| v2-F3 | P2 | form | EP `:required` 是否落到 aria-required 需校核 |
| v2-C3 | P2 | chart | 图表无 `<figure>+<figcaption>` |
| v2-M1 | P2 | motion | driver.js onboarding 未传 animate: false |
| v2-M2 | P2 | motion | ECharts 动画未尊重 reduced-motion |
| v2-O1 | P2 | keyboard | 缺 focus order e2e 用例 |
| v2-I1 | P2 | i18n | RTL 完全没准备 |
| v2-B1 | P2 | brand | favicon 暗色 tab 对比度未验 |
| v2-B2 | P2 | brand | loading spinner 暗色 token 缺失 |
| v2-P1 | P2 | print | `@media print` 零 |
| v2-X2 | P2 | 跨浏览器 | Android TalkBack 完全未覆盖 |
| v2-X3 | P2 | 跨浏览器 | Safari `:focus-visible` 鼠标点击后仍显示 |

### 15.2 v2 校核 v1 项

| v1 ID | v2 校核 | 备注 |
| --- | --- | --- |
| P1-T1 中性灰阶不足 | 维持 | 11 阶 slate 化 backlog |
| P1-T2 `--color-info` = primary 破语义 | 维持 | EP info 默认灰被破坏 |
| P0 mobile token 化 | 维持 | `mobile-common.css` 32 hex 仍独立 |
| P0 ECharts 硬编码 hex | **升级** | + v2-C1/C2/C3,根因不仅是色板,是整条 a11y 通路 |
| `prefers-reduced-motion` 覆盖 | A 级确认 | 9 处 + JS 层 startViewTransition |
| `:focus-visible` 全局环 | A 级确认 | `element-override.css:928-953` 实现一流 |
| EP `aria-label` 桥接 | B 级确认 | 30/161 ≈ 18.6%(v1 报 15.5%,微涨),核心 layout 覆盖,业务页缺 |

---

## 16. 优先修复建议(按 ROI 排序)

### 16.1 立即(P0)
- **v2-L1** 侧栏 landmark:`<el-aside role="navigation" :aria-label>` 1 行 + 1 个 i18n key。LH a11y 预期 +2~3。

### 16.2 1 周内(P1)
1. **v2-L2** skip link + CSS 样板(~20 行)
2. **v2-F1** 抽 `useFormValidate` composable,统一 9+ form auto-focus
3. **v2-T1** el-table 顶层 `<section aria-labelledby>` 包裹
4. **v2-C1** ECharts 全局 `aria.enabled: true` + `decal.show: true`
5. **v2-E1** ElMessage.error → ElNotification.error(带 role=alert)
6. **v2-A1 / v2-A2** LH 加 mobile preset,axe 在 webkit project 跑

### 16.3 1 月内(P2 集中清理)
7. **v2-R1** `<div role="button">` 全仓改 `<button>`
8. **v2-K1 / v2-K3 / v2-O1** 补 focus 管理 e2e 用例(3-5 spec)
9. **v2-P1** `@media print` 样式(~50 行 CSS)
10. **v2-R2 / v2-T2** tablist/tab/tabpanel 补 aria-controls;表格行键盘可达

### 16.4 长期 backlog
- **v2-I1** 新增组件强制逻辑属性 eslint 规则
- **v2-X2** Android TalkBack 手动跑一次留报告
- **v2-C2** 评估 ECharts SVG renderer 切换 vs `<table>` fallback ROI

---

## 17. 修复模板代码片段(供 PR 直接拿)

完整代码片段(skip link / `useFormValidate` composable / ECharts A11Y_BASE_OPTION / ElMessage→ElNotification 包装 / `@media print` 全局样板)在此从略 — 实施 PR 时按 §16 优先级展开即可,每段量级 < 30 行。**核心模式**:
- **skip link**:绝对定位 `top: -40px`,`:focus { top: 0 }` 弹出,reduced-motion 关 transition
- **formValidate**:catch validate 错误对象 → `scrollToField(firstKey)` + nextTick querySelector input focus
- **ECharts**:`aria.enabled: true` + `decal.show: true`(色盲条纹)+ `animation` 接 matchMedia reduced-motion
- **ElNotification 替代 ElMessage.error**:`ElNotification` 默认 role=alert,`ElMessage` 不带
- **`@media print`**:隐藏 sidebar/header/tabs/buttons,强制白底黑字,`a[href]::after { content: " (" attr(href) ")" }`,表格 page-break-inside avoid,`html.dark { background: white !important }`

---

## 18. 总体修正:v2 之后整体评分

| 维度 | v1 | v2 修正 | 理由 |
| --- | --- | --- | --- |
| Design tokens | A- | A- | 维持,11 阶灰阶补完后到 A |
| 暗色模式 | A | A | 维持,系统切换 + reduced-motion 配合优秀 |
| WCAG 对比度 | B+ | B+ | 维持,`--color-text-tertiary` 已治理 |
| 焦点态 | A | A | 全局 `:focus-visible` 环实现一流 |
| 状态色 | B+ | B+ | 维持 |
| 图标 | B | B- | v2-B1/B2 暗色未单独治理 |
| 图表色板 | C+ | **C** | + v2-C1/C2 全无 a11y 配置,降级 |
| 主题切换 | A | A | 维持 |
| **landmark / 屏阅读器** | (未覆盖) | **C+** | 主导航 landmark 错位 + 缺 skip link + 表格无描述 |
| **表单 a11y** | (未覆盖) | **C+** | auto-focus / aria-describedby / aria-required 全缺 |
| **错误恢复** | (未覆盖) | **C** | ElMessage.error 无 role=alert + validate 失败无播报 |
| **图表 a11y** | (未覆盖) | **D** | ECharts aria.enabled 关 + Canvas 无 fallback |
| 减少动效 | A | A- | driver.js / ECharts 动画未受控 |
| RTL | (未覆盖) | **D** | 未来 backlog,7 处逻辑属性是底子 |
| 打印 | (未覆盖) | **F** | 完全缺失 |
| 跨浏览器 | B | B- | LH desktop only / `/m/*` 无 webkit a11y |

**综合**:**B+ → B(中良偏上)**。v1 视觉一致性 / token 化领先同类,**但深入屏阅读器 / 键盘 / 移动 / 错误反馈这些"看不见"维度后,a11y 成熟度被高估**。修完 v2-L1(P0) + 6 个 P1 后可回到 B+,补足 v2-C1/C2/E1/F1 后可争取 A-。

---

## 19. 附录 A — 静态扫描关键命令(可复现)

```bash
# 1. landmark / ARIA role 使用分布
grep -rEn 'role="(banner|main|navigation|complementary|contentinfo|alert|status|button|dialog|search|region|tablist|tab|tabpanel)"' src/ --include="*.vue"
# → 桌面侧栏 0 role=navigation,确认 v2-L1

# 2. aria-* 属性热力
grep -rEon 'aria-(label|describedby|labelledby|invalid|required|live|expanded|controls|current|hidden|sort|busy|disabled|pressed|haspopup|modal|atomic|relevant)=' src/ --include="*.vue" \
  | sed 's/.*\(aria-[a-z]*\)=.*/\1/' | sort | uniq -c | sort -rn
# → aria-current 0 / aria-expanded 0 / aria-controls 0,确认 v2-R2

# 3. 表单 validate 后 auto-focus 模式
grep -rEn 'scrollToField|firstField.*focus' src/views/ --include="*.vue"
# → 仅 PipelineDefinitionList.vue:751 1 处,确认 v2-F1

# 4. 表格无 caption / sortable
grep -rEn '<el-table.*caption|caption=|<el-table.*sortable|sortable' src/views/ --include="*.vue"
# → 0 caption / 0 sortable,确认 v2-T1/T3

# 5. ECharts a11y 配置
grep -rEn 'aria:|decal:' src/charts/ src/composables/ src/views/ --include="*.ts" --include="*.vue"
# → 0 命中,确认 v2-C1

# 6. ElMessage.error 全仓使用
grep -rEn 'ElMessage\.error|ElMessage\(\{.*error' src/ --include="*.vue" --include="*.ts" | wc -l
# → 大量(待统计),确认 v2-E1 影响面

# 7. @media print 覆盖
grep -rEn '@media print' src/
# → 0 命中,确认 v2-P1

# 8. RTL / 逻辑属性使用率
grep -rEn 'margin-inline|padding-inline|inset-inline|text-align: (start|end)' src/ | wc -l
# → 7 处,确认 v2-I1 底子

# 9. tabindex 使用(防止 tabindex > 0 跳跃)
grep -rEn 'tabindex=' src/ --include="*.vue" | grep -vE 'tabindex="(-1|0)"' | grep -vE ':tabindex='
# → 0 命中正整数 tabindex,确认无人为乱序

# 10. <div role="button"> vs <button> 比例
grep -rEon 'role="button"' src/ --include="*.vue" | wc -l    # 10+
grep -rEn '<button|<el-button' src/ --include="*.vue" | wc -l # 数百
# → role=button 用法 ≈ 10/数百 < 5%,可整合
```

## 20. 附录 B — Lighthouse a11y 各 audit 预期结果

LH a11y 分由 ~50 个细化 audit 加权。预测当前各 audit 结果:

| Audit | 当前预期 | 修复 v2 项后 |
| --- | --- | --- |
| `bypass`(skip link / heading / landmark) | ❌ | ✓(v2-L2) |
| `aria-allowed-attr` | ✓ | ✓ |
| `aria-required-attr` | ⚠️ | ✓(v2-F3) |
| `aria-valid-attr-value` | ✓ | ✓ |
| `button-name`(icon button 缺 label) | ⚠️ | ✓(部分 v2-R1) |
| `color-contrast` | ✓(已治理) | ✓ |
| `document-title` | ✓(router meta) | ✓ |
| `duplicate-id-aria` | ✓ | ✓ |
| `form-field-multiple-labels` | ✓(EP 包) | ✓ |
| `frame-title` | N/A | N/A |
| `heading-order`(h1→h2→h3) | ⚠️ | 需手查 |
| `html-has-lang` | ✓ | ✓ |
| `html-lang-valid` | ✓ | ✓ |
| `image-alt`(SVG/img) | ⚠️ | 需手查图标 |
| `input-button-name` | ⚠️ | ✓(v2-R1) |
| `label`(form label association) | ✓(EP 包) | ✓ |
| `landmark-one-main` | ✓(`<el-main>`) | ✓ |
| `landmark-unique` | ❌ | ✓(v2-L1) |
| `link-name` | ⚠️ | 需手查 router-link |
| `list`(`<ul>/<ol>` 子是 `<li>`) | ✓ | ✓ |
| `meta-viewport` | ✓ | ✓ |
| `tabindex`(no positive tabindex) | ✓ | ✓ |
| `td-headers-attr` | ✓(EP 自动) | ✓ |
| `th-has-data-cells` | ✓ | ✓ |
| `valid-lang` | ✓ | ✓ |
| `video-caption` | N/A | N/A |
| `visual-order-follows-dom` | ⚠️ | 需 e2e 验 |

**预测**:当前 LH a11y ~88,修完 v2 P0+P1 后可到 ~94+。

---

## 21. 附录 C — 与同类对标(简评)

| 工具 | a11y 成熟度 | batch-console 相对位置 |
| --- | --- | --- |
| Grafana | A(landmark / skip link / aria 完整 / WCAG 严格 audit pipeline) | 落后 1 档 |
| Datadog | A- | 大体持平 |
| GitLab Console | B+ | 持平 |
| Jenkins | C+(老 UI 限制) | 领先 |
| Airflow UI | B(FAB Airflow 3.x 起 a11y 投入) | 持平 |
| Element Plus 官方 demo | B-(EP 自身 a11y 不够,batch-console 在其基础上加 outline 环 / token / focus-trap 已超越基线) | 领先 |

**结论**:batch-console 处于"运维后台中游偏上",视觉一致性 / token 化是优势项,但**屏阅读器路径 / 移动 a11y / 错误恢复**这些纵深维度仍有约 6-9 个月可投入空间。

---

## 22. v2 扫描限制声明 / 已知未覆盖

- 静态扫描 + 测试代码阅读,**未真实跑 axe / Lighthouse 与本仓基线对比**(需 e2e 环境)
- VO / NVDA / TalkBack 屏阅读器实测**未做**(需真机)
- EP 内部 ARIA 行为依赖文档与代码逻辑推断,**未跑 EP 源码 grep**(如 `aria-required` 是否实际渲染)
- ECharts a11y 配置基于 ECharts 5/6 文档,具体在 6.0.0 中是否完全 backward compatible 未验
- Lighthouse 真实分数为**预测**,未跑

下一轮(如有 v3)建议:
1. 拉本地 dev,axe DevTools 浏览器插件人工跑 P0 10 页,补真实 violation 列表
2. macOS VoiceOver + Safari 实测主流程导航路径,录视频
3. ECharts a11y mode 在 1 个示例图(`useOpsSummary`)上 PoC 修复 + LH 跑分对比

---

**报告完。** v2 新增 P0 **1 个**(v2-L1 landmark)/ P1 **13 个** / P2 **18 个**。
P0 + P1 实际可立即下钻成 PR 的有 **10 个**(skip link / landmark / formValidate / ECharts aria / ElNotification / table aria-label / dialog focus 验证 / LH mobile / axe webkit / `/m/*` a11y)。
