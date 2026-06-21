# FE 配色 + 主题 + a11y 深度扫描报告

- **日期**:2026-06-03
- **扫描仓库**:`batch-console`(commit `HEAD`,分支 `docs/sdk-manuals-loc-oss-2026-06-03`)
- **范围**:design token / 暗色模式 / WCAG 对比度 / 焦点态 / 状态色 / 图标 / 图表色板 / 主题切换 / 品牌一致性
- **方法**:静态扫描(grep / Read),覆盖 `src/styles/*`、`src/charts/*`、`src/constants/theme.ts`、`src/stores/app.ts`、`src/layout*`、`src/views*`、161 个 Vue 组件
- **范围声明**:**只读不改代码**。所有发现仅作建议,落地在后续 PR。
- **统计基线**
  - Vue 组件 `*.vue`:**161 个**
  - 含 `#xxxxxx` 字面量文件:**43 个**
  - hex 字面量总出现次数:**429 次**(含 token 定义自身)
  - tokens.css 内 hex(定义源):**137 次**(可接受 — 是 token 源)
  - 真业务代码 hex(剔除 tokens.css):约 **292 次**,集中在 ~12 个文件
  - 含 `aria-label`/`role` 的组件:**25 / 161 ≈ 15.5%**

---

## 0. 执行摘要

总体评价:**B+ / 良**。Token 化程度高于一般运维后台,主题/暗色/焦点态/状态色这些"看得见的 a11y 基线"基本到位,**完成度领先同类**。但有若干"看不见"的硬骨头:

- **mobile 体系几乎完全脱离 token 系**(独立 `--ios-*` 调色板 + 大量裸 hex,如 `#007aff`/`#ff3b30`)
- **业务图表硬编码 hex 而不是用 `--color-*` token**(`useOpsSummary.ts` 21 处)
- **Mermaid `classDef`、Workflow Mini DAG 颜色硬编码**(占 hex 文件数大头)
- **色盲安全色板没有任何处理**(7 色 ECharts 调色板红绿同时存在)
- **15.5% 组件带 aria 标签** — 顶栏/侧栏头部覆盖较好,但大量 form / 列表 / 卡片缺 aria-label / role,屏阅读器路径缺少鉴权保护

**问题分布**:P0 **2 个**(色盲安全 / 移动端 token 化) / P1 **5 个** / P2 **6 个**

---

## 1. 配色系统(Design Tokens)

### 1.1 token 现状 — 强项

`src/styles/tokens.css`(360 行)是**核心成就**,清晰分层:

| 类别 | token 数 | 说明 |
| --- | --- | --- |
| 主色 / 状态色 | 5 | `--color-primary` `--color-success` `--color-warning` `--color-danger` `--color-info` |
| 按钮系 | 30+ | 4 个状态 ×{ bg/hover/active/border/text/soft-* },light + dark 双套 |
| 文本灰阶 | 3 | `--color-text-primary/secondary/tertiary` |
| 背景 / 表面 | 6 | `--color-bg-{page,canvas,card,hero}` + border light/normal |
| Pipeline stage 业务色 | 17 | 业务语义独立色板,与主题脱钩,合理 |
| 表格 | 5 | header/stripe/row-hover/current-row/fixed-shadow |
| Layout(侧栏/顶栏/popup/login) | 20+ | 双主题适配 |
| 间距 / 字号 / 圆角 / 动效 | ~20 | space / font-size / radius / motion-duration / motion-ease |

**亮点**:
- `--color-text-tertiary` 注释明确写"原 `#8c8c8c` 4.45:1 不达 WCAG AA,调到 `#6b6b6b` ≈ 5.74:1 通过" — 有意识的对比度治理
- Element Plus 主题变量(`--el-color-primary` 等)**全部反向桥接到自家 token**,而不是用 EP 默认色:`element-override.css` 头 30 行直接 `--el-color-primary: var(--color-primary)`
- **动效 token + `prefers-reduced-motion` 配合**:`app.css` 第 123 行 `@media (prefers-reduced-motion: reduce)` 关 transform/transition,且 ViewTransition 也读了该 query

### 1.2 token 缺口

**[P1-T1] 中性灰阶不足 8-9 阶**

现仅 3 阶文本灰(primary/secondary/tertiary) + border/border-light + `--button-neutral-*`。行业基线(Tailwind slate-50→950 是 11 阶,Ant Design gray-1→13 是 13 阶)。后果:
- 业务页 disabled / placeholder / divider / shimmer / skeleton 时常找不到合适灰,落到 `#909399`/`#dcdfe6` 等 EP 老色或裸 hex
- 已观察到 `CommandPalette.vue`、tokens 内 `--button-disabled-text: #94a3b8` 等都是临场取色

**建议**:补 `--color-neutral-50/100/200/300/400/500/600/700/800/900`(以 slate 系或纯灰系为准),将所有现有灰色 token 改 alias。

**[P1-T2] 没有 `--color-info` 区分主色**

当前 `--color-info: #1677ff` = `--color-primary`,语义不可区分。banner / 提示 / 中性 tag 想用"信息蓝(非品牌主)"时无可用 token。Element Plus `info` 默认是灰色,这里被改成主色,**反而破坏了 EP 默认 info 灰语义**。

### 1.3 真业务文件 hex 占用 Top

| 文件 | hex 数 | 评估 |
| --- | --- | --- |
| `mobile-common.css` | 32 | **P0 — 完全独立 `--ios-*` 调色板,与 token 体系平行** |
| `useOpsSummary.ts` | 21 | **P0 — 业务图表逻辑硬编码状态色,应读 token** |
| `CommandPalette.vue` | 21 | OK — 全是 `var(--token, #fallback)` 形态,fallback 作降级,可接受 |
| `WorkflowMermaidViewer.vue` | 18 | P1 — Mermaid `classDef` 字符串 + 图例样式硬编码 |
| `echarts.ts` | 18 | P1 — 7 色 palette + 轴/网格灰阶硬编码 |
| `app.css` | 14 | 主要是 token 定义补充,可接受 |
| `TenantPackageImportWizard.vue` | 11 | 大部分 `var(--token, #fallback)`,**1 处** `color: #fff` 写死,可接受 |
| `MobileAppBar.vue` | 10 | P0 — iOS 蓝硬编码 |
| `JsonNode.vue` | 8 | P2 — JSON 高亮配色,token 化可行 |
| `MobileTabBar.vue` | 8 | P0 — iOS 蓝 + 红徽章硬编码 |

---

## 2. 暗色模式

### 2.1 现状 — 强项

`html.dark` 类切换(不靠 `@media (prefers-color-scheme)` 单一硬连),完整流程:

```
读 localStorage('batch-console:theme')
  → ThemePreference: 'system' | 'light' | 'dark'
  → 'system' 时 matchMedia('(prefers-color-scheme: dark)') + addEventListener('change')
  → resolveEffectiveTheme → applyThemeToDocument
  → document.documentElement.classList.toggle('dark')
  + 支持 startViewTransition(配合 prefers-reduced-motion 自动降级)
```

**优秀点**:
- ✅ **三态:system/light/dark**(`stores/app.ts` toggle 周期化)
- ✅ **持久化**:`THEME_STORAGE_KEY = 'batch-console:theme'`
- ✅ **跟随系统**:`matchMedia('change')` 监听,实时变化
- ✅ **`color-scheme: dark`**:`tokens.css:238` 给浏览器原生控件染色提示
- ✅ **View Transition**:整页色调过渡,有 reduced-motion 降级
- ✅ **暗色独立配色** 而非仅反转滤镜:`html.dark` 下所有按钮/表格/侧栏 token 完整重写
- ✅ **mobile 现在跟 html.dark 同步**(`mobile-common.css:42` 注释"原先只听 prefers-color-scheme,用户手动切浅色但系统是暗色时表现错乱")— 这是历史已遇到问题后的修复

### 2.2 暗色缺口

**[P1-D1] 暗色下 stage 调色板未切换**

`tokens.css` 17 个 `--color-stage-*`(blue-500/violet-500 等 Tailwind 500 系)在 `html.dark` 下**未重新定义**。暗底卡片上 500 系饱和色对比足够,但 violet-500(#8b5cf6) / indigo-500(#6366f1) / sky-500(#0ea5e9) 在深底卡片(#1a1f26)上对比度只在 3.5-4.2 区间,**边缘不达 WCAG AA 4.5:1 文本要求**(若直接用作小图标 stroke + 文字是边缘的)。

**建议**:暗色下整体上提一档到 400 系或加 luminosity 修正。

**[P1-D2] mobile 暗色补但 token 不一致**

`html.dark .mobile-layout` 重写了 `--ios-bg-grouped: #000000`(纯黑) — iOS 标准做法,但与桌面端 `--color-bg-page: #0f1419`(几乎不黑)语义割裂,同一屏两套黑度。

**[P2-D3] 暗色 hover 边框对比未审计**

`--color-border: #2d333b` 在 `--color-bg-card: #1a1f26` 上仅 ~1.6:1 — 暗色边界基本"看得见但不显眼"。这是行业常态(GitHub/Linear 暗色 border 也这量级),但若用户反馈"卡片轮廓难辨"需作色阶调整。

---

## 3. WCAG 对比度

### 3.1 已校核

| 配对 | 估算对比度 | WCAG |
| --- | --- | --- |
| `--color-text-primary #1f1f1f` × `--color-bg-card #ffffff` | ~16.7:1 | AAA |
| `--color-text-secondary #595959` × `#ffffff` | ~7.0:1 | AAA |
| `--color-text-tertiary #6b6b6b` × `#ffffff` | ~5.74:1 | **AA ✓** (注释明确改过) |
| `--button-primary-bg #1677ff` × `#ffffff` text | ~4.5:1 | AA(临界) |
| `--button-warning-bg #d97706` × `#ffffff` text | ~3.4:1 | **AA Fail**(常规文本) / AA Large ✓ |
| `--button-success-bg #16a34a` × `#ffffff` text | ~3.3:1 | **AA Fail**(常规) / AA Large ✓ |
| `--button-danger-bg #ef4444` × `#ffffff` text | ~3.7:1 | **AA Fail**(常规) / AA Large ✓ |

### 3.2 关键发现

**[P1-W1] 实色按钮文字 ≤ 4.5:1**

success/warning/danger 实色按钮上的白字,WCAG AA(4.5:1)边缘不达 — 按钮文字通常按 "AA Large(3:1)" 评 — Element Plus 默认色板也是这一档,行业普遍接受。**但若需 AAA 合规** ,需把按钮 bg 进一步加深(success 调到 #15803d / danger 调到 #b91c1c)。

**[P1-W2] `--color-text-tertiary` 在表格 stripe 上对比下降**

`--color-text-tertiary #6b6b6b` × `--table-stripe-bg #f7f9fc` = ~5.5:1(仍达 AA),但 × `--table-row-hover-bg`(8% 蓝混白)时约 ~5.0:1 — 临界。表格内 placeholder/secondary 文本需注意。

**[P2-W3] soft 按钮 text 在 soft bg 上**

`--button-warning-soft-text: #b45309` × `--button-warning-soft-bg: #fffbeb` ≈ 8.4:1 ✓ — 这一组没问题,已审。

---

## 4. 焦点态(`:focus-visible`)

### 4.1 现状 — 强项

`element-override.css:927-1001` 有**一整段 a11y baseline**,覆盖:

- ✅ 全局 `.el-button` / `.el-link` / `.el-pagination` / `.el-menu-item` / `.el-tabs__item` / `.el-dropdown-menu__item` / `button` / `a` / `[role='button']` / `[tabindex]` 全部 `:focus-visible` → `outline: 2px solid var(--button-focus-ring)` + offset 2px + border-radius
- ✅ `--button-focus-ring` 在暗色下从 38% 提到 52%(强化暗底可见性)
- ✅ input/select 走特殊路径:外圈 4px blur 软光晕 + 1px 主色边,**视觉不变形**(行业里很多后台直接 outline 会让 input 跳尺寸)
- ✅ 表格行 `:focus-visible` 内描边 — 键盘 Tab 进入行能看到
- ✅ `:focus-within:has(:focus-visible)` 内描边给 PageHeader / SectionCard 整体提示

### 4.2 缺口

**[P0-F1] `outline: none` 出现在 7 处非 wrapper 路径**

```
src/layout/LayoutTabs.vue:366:                          outline: none;
src/layout/components/LayoutHeader.vue:446,513:        outline: none;
src/layout-mobile/MSearchBar.vue:125:                  outline: none;
src/views/system/UserRole.vue:322:                      outline: none;
src/views/config/TenantPackageImportWizard.vue:1233:   outline: none;
src/views/scheduler/components/SnapshotKpiTab.vue:39:  outline: none;
```

其中:
- `LayoutTabs.vue:366`、`LayoutHeader.vue:446,513`、`UserRole.vue:322`、`SnapshotKpiTab.vue:39`、`MSearchBar.vue:125` 需要**逐一审视是否同时给了 `:focus-visible` 替代**。如果只是无差别 `outline: none`,键盘用户在这些元素上看不到焦点环。
- `element-override.css` 的 `!important` 全局 ring 大概率能覆盖大部分,但 `outline: none` 写在更具体的选择器上会赢。

**建议**:作专项排查,逐个加 `&:focus-visible { outline: 2px solid var(--button-focus-ring); }`。

**[P2-F2] tabindex 串场未验证**

未做键盘 Tab 串场静态扫描(需运行时),需做一次 Playwright a11y 跑 axe-core,验证 Tab 顺序、modal trap、登录后焦点回归等。

---

## 5. 状态色一致性

### 5.1 已统一

- `StatusTag.vue` + `statusTagResolve.ts` 全站统一状态 → EP type(`success`/`warning`/`danger`/`info`) 映射,`el-tag :type` 端通过 token 染色
- Element Plus `--el-color-{success,warning,danger,info}` **桥接到 token**(element-override 头部),故 `el-button type="success"` / `el-tag` / `el-message` / `el-alert` 颜色统一
- `useDangerConfirm.ts` 用 `var(--el-color-danger, #f56c6c)` 模式

### 5.2 缺口

**[P0-S1] 业务图表硬编码状态色破坏一致性**

`src/views/ops/composables/useOpsSummary.ts`:

```ts
{ name: 'OPEN', data: ..., color: '#ff4d4f' },     // 应 = var(--color-danger)
{ name: 'ACKED', data: ..., color: '#1677ff' },    // 应 = var(--color-primary)
{ name: '失败率 %', color: '#ff7a45' },             // 自创 orange,既不是 warning 也不是 danger
{ name: 'SLA 达标', color: '#52c41a' },             // 应 = var(--color-success)
{ name: 'Critical', value: critical, color: '#ff4d4f' },
{ name: '重试积压', value: retry, color: '#faad14' },
slaPct >= 99 ? '#52c41a' : slaPct >= 95 ? '#faad14' : '#ff4d4f'  // 阈值色,应读 token
```

后果:**调主题色后 Ops 图表不会跟着变**。21 处全部需要改成 `getComputedStyle(document.documentElement).getPropertyValue('--color-success')` 模式或抽 `usePaletteToken()` composable。

**[P1-S2] Mermaid classDef 颜色硬编码**

`WorkflowMermaidViewer.vue:718-722`、`crossDayMermaid.ts` 类似模式:

```js
'  classDef running fill:#3b82f6,stroke:#1d4ed8,color:#fff\n'
'  classDef success fill:#10b981,stroke:#047857,color:#fff\n'
'  classDef failed fill:#ef4444,stroke:#b91c1c,color:#fff\n'
'  classDef waiting fill:#f59e0b,stroke:#b45309,color:#fff\n'
```

Mermaid 不读 CSS var,只能字符串插值。建议运行时读 token 然后拼接到模板。

**[P1-S3] WorkflowMiniDag 同样硬编码**

`components/workflow/WorkflowMiniDag.vue` 5 处 `#fff`、5 处状态色,与 Mermaid 类似。

---

## 6. 图标语义

### 6.1 现状

- Element Plus Icons + 自定义 svg
- 45 个 .vue 文件用了 ElIcon
- 25 个 .vue 文件有 aria-label / role
- 顶栏(LayoutHeader)icon-only 按钮**全部带 `aria-label`** 且包 `el-tooltip` — 这一块实现较完整
- `StatusTag.vue` 内"色块圆点"用 `aria-hidden="true"` 屏阅读器忽略 — 正确(文字本身已传达状态)

### 6.2 缺口

**[P1-I1] aria 覆盖率仅 15.5%**

161 个 Vue 文件,只有 25 个出现 aria-* / role — 表单 / 列表行 / 卡片 / 业务页大量缺 a11y 标记。后果:屏阅读器读到大量"button button button"而无功能区分。

**抽查**:
- `LayoutSidebar.vue` 未看到 `aria-current="page"` 给当前菜单 — 需补
- 业务列表页 `Table` 行未配 `role="row"`(EP 默认会加,需确认)
- icon-only `el-link` 是否都有 `aria-label`?需扫

**[P2-I2] 图表无文字 alt**

`<v-chart>` 组件未观察到 `role="img"` + `aria-label` — 屏阅读器用户拿不到图表语义。建议给关键 KPI 图表加 aria-label 摘要(SR-only summary div)。

---

## 7. 图表配色

### 7.1 现状

- `echarts.ts` 注册 `console-light` / `console-dark` 两套主题,**主题统一**
- 7 色 palette:`['#1677ff', '#52c41a', '#faad14', '#ff4d4f', '#722ed1', '#13c2c2', '#eb2f96']`
- 暗色用同 PALETTE,只换字色/网格色 — 在暗底下饱和度合理
- ECharts 主题写法标准,line.smooth 等图形规约统一

### 7.2 缺口

**[P0-C1] 色板不是色盲安全(Color Blind Safe)**

7 色 palette **同时包含红(#ff4d4f)和绿(#52c41a)**,典型红绿色盲(占男性人口 ~8%)无法区分。SLA 达标(绿) vs SLA 违约(红)在 useOpsSummary 直接用了红绿对比 — **运维大屏红绿盲用户全废**。

行业方案:
- 改用 Okabe-Ito 8 色色盲安全板:`#0072B2 #D55E00 #009E73 #CC79A7 #F0E442 #56B4E9 #E69F00 #000000`
- 或 Tableau 10:加灰阶 + 蓝橙搭配,避红绿同图
- 关键二元状态(达标/违约、成功/失败) **同时用形状/图案区分**(线型 dashed / 数据点形状 / 填充纹理)
- 用户偏好:`prefers-contrast: more` / 自定义"色盲模式"开关切到安全板

**[P1-C2] palette 与 button token 解耦**

PALETTE 第一色 `#1677ff` 写死,而 `--button-primary-bg` 暗色下变为 `#3b82f6`(亮蓝)。两者**不同步**。建议 echarts 主题改为从 `getComputedStyle` 读 token 后注册,主题切换时 reregister。

**[P2-C3] palette 仅 7 色,Top-N 图超 7 类后会循环**

Ops 大屏 TopN 触发类型/Worker 加载分布有可能 >7 类,需要扩到 10-12 色或在 buildHorizontalTopNOption 内做"Top-N + others"截断。

---

## 8. 主题切换易用性

### 8.1 现状 — 优秀

- ✅ **秒切**:`classList.toggle('dark')`,token 全部走 CSS var,无需重渲染组件
- ✅ **持久化**:`localStorage('batch-console:theme')`
- ✅ **跟随系统**:`matchMedia change` 实时
- ✅ **三态循环**:system → light → dark
- ✅ **View Transition**:整页过渡(支持的浏览器),不闪
- ✅ **入口**:顶栏工具 dropdown,有 i18n label(`themeActionLabel`)
- ✅ **prefers-reduced-motion** 把 View Transition 自动关掉

### 8.2 缺口

**[P2-T1] 入口隐藏在二级 dropdown 内**

`LayoutHeader.vue` 主题切换在"⋯ 工具"dropdown 里(为简化顶栏),不熟悉的用户找不到。建议:**首次访问时若与系统不一致**(localStorage 无值且系统暗色), Toast 提示一次"已为你切换到深色模式"。

**[P2-T2] 没有 high-contrast 模式**

未见 `prefers-contrast: more` 适配。法政/无障碍合规线高的客户可能要求。建议:加一套 `html[data-contrast='high']` 把 `--color-text-secondary/tertiary` 收紧到 #333、border 加粗。

---

## 9. 品牌一致性

### 9.1 现状

- **登录页**:`Login.vue:218-231` "BC" logo,渐变 `linear-gradient(135deg, var(--color-primary), #0f5ed9)`
- **侧栏**:`LayoutSidebar.vue:6-12` "BC" logo + Batch Console subtitle
- **品牌主色**:`--color-primary: #1677ff` 统一
- 主体字体:`-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif`(echarts FONT 与全局 reset 一致)

### 9.2 缺口

**[P1-B1] 登录页 logo 渐变第二色硬编码 `#0f5ed9`**

`Login.vue:229`: `background: linear-gradient(135deg, var(--color-primary), #0f5ed9);` — 第二色应是 token,否则改主色后渐变不和谐。

**[P2-B2] mobile logo / brand 缺失**

`MobileAppBar.vue` 用 `linear-gradient(135deg, #007aff 0%, #5ac8fa 100%)` — iOS 系统蓝,**不是品牌主色**!移动端品牌识别破坏。

**[P2-B3] 通知 / 邮件模板未审计**

本次扫描仅前端 SPA,后端推送/邮件/Web Push 模板的 logo 与色彩**未覆盖**,需另一轮跨仓审计(通知模块在 `file-batch-system` 后端 + push 服务)。

**[P2-B4] favicon / apple-touch-icon 未审计**

`public/` 内 favicon / manifest.json / 各尺寸 PWA icon 是否色彩品牌一致,本次未读取。

---

## 10. 优先级清单

### P0(2 个 — 阻塞合规 / 跨终端体验割裂)

| # | 标题 | 影响范围 |
| --- | --- | --- |
| **P0-1** | **图表色板非色盲安全** — 红绿同图,8% 男性用户无法区分 SLA 达标/违约 | `echarts.ts` PALETTE + `useOpsSummary.ts` 21 处 |
| **P0-2** | **mobile 调色板游离于 token 体系外** — `--ios-*` 独立板,9 个 mobile 文件 ~60 处硬编码 `#007aff`/`#ff3b30`/`#34c759` | `mobile-common.css`、`MobileAppBar.vue`、`MobileTabBar.vue`、6 个 mobile 视图 |

### P1(5 个 — 主题完整性 / a11y baseline)

| # | 标题 | 主要文件 |
| --- | --- | --- |
| **P1-1** | 业务状态色硬编码(图表/Mermaid/MiniDag)— 主题切换不跟随 | `useOpsSummary.ts`、`WorkflowMermaidViewer.vue`、`WorkflowMiniDag.vue`、`crossDayMermaid.ts` |
| **P1-2** | `outline: none` 7 处未配套 `:focus-visible` 替代,键盘用户焦点丢失 | `LayoutTabs/Header`、`UserRole`、`SnapshotKpiTab`、`MSearchBar`、`TenantPackageImportWizard` |
| **P1-3** | 中性灰阶仅 3 阶 — disabled / placeholder / divider 找不到合适灰,补 8-9 阶 | `tokens.css` |
| **P1-4** | aria-label / role 覆盖率仅 15.5%(25/161),业务页屏阅读器路径缺少鉴权保护 | 全站 .vue |
| **P1-5** | 暗色下 stage 调色板(17 个)未重定义,部分 500 系色对深底卡片对比 ~3.5:1 | `tokens.css` `html.dark` 段 |

### P2(6 个 — 体验打磨)

| # | 标题 |
| --- | --- |
| **P2-1** | success/warning/danger 实色按钮文字 ≤ 4.5:1(AA Large pass,AAA fail);视情况加深 |
| **P2-2** | 登录页渐变第二色 `#0f5ed9` 硬编码,改主色后不和谐 |
| **P2-3** | echarts 主题不读 token,light/dark 主色与 button-primary-bg 不同步 |
| **P2-4** | 主题切换隐藏在二级 dropdown,首次访问无引导 |
| **P2-5** | 缺 `prefers-contrast: more` 高对比模式 |
| **P2-6** | mobile logo 用 iOS 系统蓝,丢失品牌识别;favicon / 邮件模板 / Web Push 跨终端品牌一致性未审 |

---

## 11. 已做得好的列表(避免误伤)

- ✅ tokens.css 是**该项目最值得保留的资产**之一,边距/圆角/动效/颜色全 token,密度模式(`data-density='compact'`)只改一层
- ✅ Element Plus 主题变量**反向桥接到自家 token**,而不是相反
- ✅ 主题切换三态(system/light/dark)+ View Transition + reduced-motion 配合
- ✅ `:focus-visible` baseline 一整段,覆盖 input/select 不变形细节
- ✅ Status Tag 全站统一(StatusTag + resolve 映射 + EP type)
- ✅ `--color-text-tertiary` 主动改色达 WCAG AA(代码注释里说明了)
- ✅ ECharts 注册了 light / dark 两套主题
- ✅ 顶栏 icon-only 按钮 100% 配 aria-label + tooltip
- ✅ mobile 已踩过"只听 prefers-color-scheme"的问题并修复(注释保留 — 防回归)
- ✅ Pipeline stage 业务色板与 UI 主题脱钩(语义独立,正确的产品决策)

---

## 12. 建议的执行顺序

1. **P0-1 色盲安全色板**:落 `Okabe-Ito` palette + key 图表加形状辅标(2-3 天,需视觉确认)
2. **P0-2 mobile token 化**:将 `--ios-*` 当作语义别名映射到 `--color-*`(1-2 天)
3. **P1-1 业务状态色硬编码**:抽 `usePaletteToken()` composable,改造 `useOpsSummary.ts` + Mermaid 模板字符串(1 天)
4. **P1-2 `outline: none` 排查**:7 个文件配套 `:focus-visible`(0.5 天)
5. **P1-3 灰阶补全**:补 50→900 9 阶(0.5 天)
6. **P1-4 a11y 拉一轮 axe-core 跑 Playwright**:得出 critical 数,按 ROI 分批补 aria(独立 sprint)
7. **P1-5 暗色 stage 色板调整**(0.5 天)
8. **P2** 视产品节奏穿插

---

## 附录 A:关键文件清单

- `/Users/dengchao/Downloads/batch-console/src/styles/tokens.css`(token 单一来源)
- `/Users/dengchao/Downloads/batch-console/src/styles/element-override.css`(EP 桥接 + `:focus-visible` baseline)
- `/Users/dengchao/Downloads/batch-console/src/styles/app.css`(布局 + reduced-motion)
- `/Users/dengchao/Downloads/batch-console/src/constants/theme.ts`(主题决议 + ViewTransition)
- `/Users/dengchao/Downloads/batch-console/src/stores/app.ts`(主题状态 + matchMedia 监听)
- `/Users/dengchao/Downloads/batch-console/src/charts/echarts.ts`(ECharts 主题)
- `/Users/dengchao/Downloads/batch-console/src/views/ops/composables/useOpsSummary.ts`(P0/P1 重灾区)
- `/Users/dengchao/Downloads/batch-console/src/views/workflow/WorkflowMermaidViewer.vue`(Mermaid 硬编码)
- `/Users/dengchao/Downloads/batch-console/src/components/workflow/WorkflowMiniDag.vue`
- `/Users/dengchao/Downloads/batch-console/src/layout-mobile/styles/mobile-common.css`(P0 — mobile 调色板独立)
- `/Users/dengchao/Downloads/batch-console/src/layout-mobile/MobileAppBar.vue` / `MobileTabBar.vue`
- `/Users/dengchao/Downloads/batch-console/src/components/common/StatusTag.vue` + `statusTagResolve.ts`

## 附录 B:扫描方法快照

```bash
# hex 字面量统计
grep -rEo '#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}\b' src \
  --include='*.vue' --include='*.ts' --include='*.tsx' --include='*.scss' --include='*.css' \
  | sort | uniq -c | sort -rn

# 主题切换
grep -rE 'prefers-color-scheme|matchMedia|html\.dark|data-theme' src

# 焦点态
grep -rE ':focus-visible|outline:\s*none' src

# aria
grep -rE 'aria-label|aria-labelledby|role=' src --include='*.vue' -l
```

---

**报告完。** 建议起一个 epic 把 P0/P1 合在一个 sprint 里推 — 体量约 5-7 人日,完成后会显著提升 a11y / 色盲 / 多终端品牌一致性的合规水平。
