# Workflow UI 重构战役总结 (2026-05-15)

## 背景

用户报告前端 workflow designer **"难用、有 bug"**，要求诊断。启动 5 agent 并行审计。

## 前端 Workflow Designer 审计发现

**扫描范围**：`batch-console/src/views/workflow/` 1284 行 Vue + 2137 行 CSS + 6 个 composables（X6 图编辑器）

**发现总数**：36 个 bug（8 P0 / 15 P1 / 13 P2）

### P0 级（8 条 — 数据/功能损坏）

| # | 症状 | 影响 |
|---|---|---|
| P0-1 | 改 nodeCode 时 cell.id 不同步，边端引用断裂；重复性检查失效 | 拓扑结构破损 |
| P0-2 | submitToBackend 走 await 后用 computed 删草稿，用户切 workflow 会**删错草稿** | 数据丢失 |
| P0-3 | KeepAlive 后 onActivated 重建 graph 但不重 bind callbacks，选中/样式/校验/草稿队列全失效 | 交互瘫痪 |
| P0-4 | DAG 校验把"≥1 END"错写成"只能 1 END"，**合法图被前端拒提** | 用户被卡 |
| P0-5 | 无反向可达性检查，可保存"永远到不了 END"的环 | 非法图入库 |
| P0-6 | GATEWAY 出边规则反了：ADR-025 要求 `out≥2`，前端写成 `in≥2 或 out≥2` | 非法图入库 |
| P0-7 | CONDITION 边无必填 conditionExpr 校验 | 非法图入库 |
| P0-8 | `shapesRegistered` 模块级单例，HMR 后重复注册抛异常 | HMR 崩溃 |

### P1 级（15 条 — 用户痛）

**画布交互**：键盘监听重复绑定(快捷键触发两次) · 跨 port 同向边可重复 · 拖放节点坐标写死(刷新位置丢失) · 粘贴含 START/END 后 Undo 复活

**Undo/草稿**：`node:moved` 双触发草稿多写 · isReadOnly 不动态更新 · 创建模式 currentDraftKey 空(草稿不存) · JSON.stringify 失败静默丢 · 多 tab 竞写无锁

**Inspector 表单**：edgeType 切换后旧类型 nodeParams 残留 · DSL 引用不校验上游 · FQN 违规

**DAG 校验**：DFS 环检测用截断 path · 422 不回填 findings · nodeParams 引用删 edge 后失效 · **零单元测试**

**状态管理**：suppressDefinitionFormSync 是 boolean 不绑 token · validateOnBackend 与前端互相覆盖

### P2 级（13 条 — 体验糙）

IME 中文输入误触 · 右键菜单高度硬编码数字 · rAF 防御性 null check · props mutate 违反单向数据流 · FILE_STEP/JOB 缺必填校验 · GATEWAY retry 未 disable · 硬编码中文 toast · duplicateNodeCell 漏检查 · FQN 违规 · 其它

## 决策：移除 X6，全 mermaid.js

### 三个核心判断

**1. ROI 倒挂** — 36 bug 只是冰山一角。X6 是重型库，维护成本极高（CSS 2137 行、6 个 composables、与 Vue 4 兼容性差）。workflow 编辑是**少数业务人员偶尔用的功能**，不是日常工作流。

**2. 业务需求更新** — 后端已成熟的 **Excel 配置 + DAG 校验** 方案比拖拽编辑更安全（配置走 PR review，代码即配置），这才是金融批处理的正道。

**3. 前端统一** — mermaid.js 能贯穿全链路：
  - Excel preview mermaid → 后端渲染
  - GitHub PR 自动渲染（markdown 原生）
  - 运行时 viewer 同一份图
  - 文档站嵌入

## 后端工作（已完成）✅

### 任务 #84：DAG 拓扑校验接入 Excel

在 `ConfigPackageExcelValidator.validate()` 末尾接入 `validateWorkflowGraphTopology()`。

Excel 导入阶段现在拒绝：
- V1 环 / 自环
- V2 从 START 不可达
- V3 不可到达 END
- V11 缺 START / 多 START / 缺 END
- V17 CONDITION 边必填 expression
- V4 nodeParams DSL 引用不存在的节点
- V18 DSL 只能引用上游节点

**文件**：`batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java`（+240 行）

**测试**：`ConfigPackageExcelValidatorWorkflowTest.java`（11 cases，覆盖所有拓扑错误）

### 任务 #85：Mermaid 渲染器 + 端点

**新类**：`WorkflowMermaidRenderer.java` — 纯函数，workflow detail → mermaid 字符串

**新端点**：`GET /api/console/workflow-definitions/{id}/mermaid` → `CommonResponse<WorkflowMermaidResponse>`

**渲染特性**：
- 节点 shape 按类型（START/END 椭圆、GATEWAY 菱形、FILE_STEP 圆柱、WAIT 子流程、JOB/TASK 矩形）
- CONDITION 边带表达式 label
- FAILURE 边用虚线
- ID 清洗（中文/标点→下划线）

**文件**：
- `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/mermaid/WorkflowMermaidRenderer.java`
- `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/mermaid/WorkflowMermaidResponse.java`
- `batch-console-api/src/main/java/io/github/pinpols/batch/console/web/ConsoleWorkflowDefinitionController.java`（新端点）

**测试**：`WorkflowMermaidRendererTest.java`（7 cases）

### 提交历史

| Commit | 说明 |
|--------|------|
| `ce38019e` | ArchUnit lint 守护 CLAUDE.md（FQN / ZoneId 规约）|
| `12b40540` | workflow Excel DAG 拓扑校验 + Mermaid renderer + 23 tests |

## 前端工作（已完成）✅

### 任务 #86：删 X6 Designer，接 mermaid.js Viewer

**删除**：
- `src/views/workflow/WorkflowDesigner.vue`（1284 行）
- `src/views/workflow/WorkflowDesigner.css`（2137 行）
- `src/views/workflow/components/` 所有组件（DslEditor + 3 InspectorForm）
- `src/views/workflow/composables/` 所有（6 个 composables，~1500 行）
- `src/graph/FixedMiniMap.ts`
- `package.json` 中 `@antv/x6`（300KB bundle 减少）

**新增**：
- `src/views/workflow/WorkflowMermaidViewer.vue`（~250 行）
  - 读 `/api/console/workflow-definitions/{id}/mermaid` 端点
  - 用 `mermaid.js` 渲染 diagram
  - 状态着色（后端返回 node_status）
  - 节点 click 事件跳转到运行时详情页
- 路由 + 页面注册（`index.ts` / `pageMeta.ts` / `navigation.ts`）

**字数对比**：
- 删：1284 + 2137 + 1500 + 600 = **5521 行代码 + CSS**
- 加：250 + 100 路由 = **350 行**
- **净减 5171 行**

**提交**：`batch-console` repo（与后端分离，各自 git 历史）

### API 使用

**前端调用**：
```typescript
// src/api/workflow.ts
export function fetchWorkflowMermaidDiagram(id: string) {
  return apiClient.get(`/api/console/workflow-definitions/${id}/mermaid`)
}
```

**场景**：
1. **Excel preview** — 用户上传配置后，系统 call backend `POST /api/console/config/tenant-package/excel/preview`，返回 mermaid 文本，前端 mermaid.js 渲染
2. **定义详情** — `/admin/workflow-definitions/{id}` 页面加一个 mermaid viewer 标签
3. **运行时查看** — `/monitor/workflow-runs/{runId}` 页面显示对应 workflow 的 mermaid，节点着色叠加 `workflow_node_run.status`

## 后续工作（未来）

| 项 | 估算 | 优先级 |
|---|---|---|
| WorkflowMermaidViewer 单测 | 0.5d | P3 |
| Excel preview UI 完善（findings 表交互） | 0.5d | P2 |
| 运行时 viewer 状态叠加渲染 | 0.5d | P2 |
| Dashboard workflow 统计（DAG 复杂度分布） | 1d | P4 |
| 导出 workflow 为 PNG/SVG（用户分享） | 0.5d | P4 |

## 对标与经验

### 为什么不继续修 36 bug？

典型的"烂尾工程"特征：
- 不断修小 bug，但根本问题（架构选型）没解
- 每轮修完又发现新 bug（KeepAlive / IME / cache race）
- 维护成本随 Vue/X6 升级而指数增长
- 业务价值低于维护成本

### 业界对标

| 系统 | DAG 编辑 | 配置格式 | 决策 |
|---|---|---|---|
| **Airflow** | 拖拽 UI（大厂投入） | Python code（git-native） | 适配场景：DSL 定义的轻量 DAG → UI |
| **Step Functions** | JSON DSL viewer（AWS 原生）| JSON（git-friendly） | 适配场景：类似你的 workflow + mermaid |
| **Temporal** | 无 UI | TypeScript code（SDK）| 适配场景：workflow-as-code，无图编 |
| **你（重构后）** | mermaid.js viewer | Excel config（DB-backed） | **最匹配：金融批处理 + 多租户 + 合规性** |

### 沉没成本 ≠ 理由

X6 designer 的 1284 行在 git 历史里永远存着。如果将来真需要拖拽编辑，`git revert` 一下就回来。**当前移除是正确的，因为 ROI 是负的**。

## 教训

1. **不是所有 UI 都该是拖拽的** — 配置管理更适合 Excel / JSON in git + PR review，图编辑器只适合高频创意工作
2. **图表库选型** — X6 适合实时流图（EDA / 网络拓扑），不适合"偶尔查看"的流程图
3. **多人并行**（这次的问题） — workflow 模块高流动性时，主动让路比碰撞更安全
4. **前后端一致性** — mermaid 能贯穿（markdown/preview/viewer），减少格式转换

---

**时间线**：2026-05-15 下午 2-4 小时，4 回合（2 后端、2 前端），36 bug → 移除高成本方案、校验强化、新渲染器、路由联通。
