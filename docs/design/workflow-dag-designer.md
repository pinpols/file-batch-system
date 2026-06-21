# Workflow DAG 编辑器方案

> 2026-06-04 备案。落地 batch-console 的 workflow 图形化编辑器,替代当前「Excel 包导入 + Mermaid 只读 viewer」的孤岛。

## 1. 目标

让租户管理员能在浏览器里**拖拽 / 连线 / 配置参数**完成 workflow DAG 的创建与编辑,出参与 BE 现有 `workflow_definition` + `workflow_node` + `workflow_edge` 三表完全等价。

## 2. 范围边界(避免越界)

| 做 ✅ | 不做 ❌ |
|---|---|
| 4-6 类节点拖拽编辑 | 实时协作(多人同改)— 单人编辑 + 锁机制 |
| 自动布局 + 手动微调 | 自动业务推理("AI 建议下一节点")— 远程未来 |
| 客户端校验(无环 / START 唯一 / GATEWAY 分支数) | 直接拓扑校验数据语义对错(数据对账域 ADR-021) |
| Mermaid 导出 + 复用既有只读 viewer | 替换 BE 数据模型 — 三表结构不动 |
| 版本对比 diff | 版本回滚 — 走"另存为新版本"而非物理回滚 |
| 模板库(Bundle 模板预填) | 大模型生成 DAG — 后续可选 |

## 3. 技术选型

### 库:**AntV X6**

| 维度 | X6 |
|---|---|
| 厂家 | 蚂蚁,Apache 2.0 |
| 文档 | 中文一手,API 稳定 |
| 节点自定义 | 任意 Vue 组件,可套 Element Plus |
| 自动布局 | 内置 dagre / 可换 elkjs |
| 性能 | 单图 500+ 节点流畅(项目 workflow 上限远小于此) |
| 生态 | DolphinScheduler 3.x、字节飞书项目管理、阿里 DataWorks 在用 |

**拒选**:Vue Flow(Vue 生态薄)、bpmn-js(BPMN 标准过重,GATEWAY 语义不完全对齐)、LogicFlow(节点自定义没 X6 灵活)。

### 风格对标

- **Apache DolphinScheduler 3.x** `dolphinscheduler-ui` workflow-instance 编辑页 — 领域最贴近,源码可直接借鉴
- **AWS Step Functions Workflow Studio** — 「画布 + 右侧 inspector + 底部 JSON 同步」三联视图范式
- **n8n** — 画布 UX 细节(吸附 / 多选 / 快捷键 / mini-map / 节点搜索 palette)

## 4. 架构

```
src/views/workflow/
├── designer/
│   ├── WorkflowDesigner.vue          顶层容器(toolbar + 画布 + 侧栏)
│   ├── canvas/
│   │   ├── DagCanvas.vue             X6 实例 wrapper + 事件
│   │   ├── useX6Graph.ts             X6 配置 / 注册节点 / 注册边
│   │   ├── nodes/                    Vue 组件 per 节点类型
│   │   │   ├── StartNode.vue / EndNode.vue
│   │   │   ├── JobNode.vue / FileStepNode.vue
│   │   │   ├── GatewayNode.vue / ApprovalNode.vue
│   │   └── edges/DagEdge.vue
│   ├── inspector/                    右侧节点参数面板
│   │   ├── NodeInspector.vue         (按节点类型分发到子表单)
│   │   ├── JobNodeForm.vue / GatewayNodeForm.vue / ...
│   ├── toolbar/
│   │   ├── DesignerToolbar.vue       保存 / 校验 / 撤销 / 重做 / 自动布局
│   │   ├── NodePalette.vue           左侧拖源 + 模板搜索
│   ├── store/
│   │   └── useDesignerStore.ts       Pinia(节点列表 / 边列表 / 选中 / dirty)
│   ├── validators/
│   │   └── dagValidators.ts          无环 / START 唯一 / GATEWAY 分支等
│   ├── codec/
│   │   ├── definitionToGraph.ts      definition_json → X6 nodes/edges
│   │   ├── graphToDefinition.ts      反向
│   │   └── mermaidExporter.ts        复用 utils/crossDayMermaid 风格
│   └── WorkflowDesigner.test.ts
```

**状态机**(Pinia store):

```typescript
state = {
  nodes: DesignerNode[],
  edges: DesignerEdge[],
  selectedIds: Set<string>,
  dirty: boolean,
  validationErrors: ValidationError[],
  undoStack: Snapshot[],
  redoStack: Snapshot[],
  lockedBy: string | null,    // 单人编辑锁
}
```

## 5. 节点类型

| 节点 | BE 映射 | 配置字段 | UI |
|---|---|---|---|
| **START** | `workflow_node.node_type=START`(唯一) | 无 | 圆形绿点 |
| **END** | `workflow_node.node_type=END`(可多个,等价 OR) | 无 | 圆形红点 |
| **JOB** | `node_type=JOB` + `related_job_code` | jobCode 下拉 / 失败重试 / 超时 / 跳过条件 | 长方形,标 job 名 |
| **FILE_STEP** | `node_type=FILE_STEP` + `related_pipeline_code`(单向引 pipeline_definition) | pipelineCode 下拉 / 入参映射 | 长方形带文件 icon |
| **GATEWAY** | `node_type=GATEWAY` + `gateway_strategy`(AND/OR/XOR) | 策略 / 默认分支 / 条件表达式 | 菱形 |
| **APPROVAL** | `node_type=APPROVAL` + `approval_template_code` | 审批模板 / 超时降级 | 长方形带审批 icon |

**校验规则**(`dagValidators.ts`):
1. 必须恰好一个 `START`
2. 必须至少一个 `END`
3. 无孤立节点(每个节点必须可从 START 到达)
4. 无环(DFS 检测)
5. `GATEWAY` 出度 ≥ 2
6. `JOB` / `FILE_STEP` 必须配 jobCode / pipelineCode
7. 跨域引用 `FILE_STEP.related_pipeline_code` 必须在 pipeline_definition 表存在(API 校验)

## 6. 交互设计(抄好的部分)

| 行为 | 参考 | 细节 |
|---|---|---|
| 节点拖入 | DolphinScheduler | 左侧 palette → 鼠标按住拖到画布释放生成 |
| 连线 | n8n | 节点右边 hover 出连接点,拖到目标节点左边 |
| 多选 | n8n | 框选 + Shift 加选,Delete 批删 |
| 右侧 inspector | Step Functions | 选中节点自动展开,失焦保存到 store(非提交) |
| JSON 同步视图 | Step Functions | 可选切换底部 tab,看 definition_json 实时 |
| 自动布局 | DolphinScheduler | 工具栏按钮,dagre TB / LR 切换 |
| 撤销/重做 | n8n | Ctrl+Z / Ctrl+Y,store 维护 snapshot 栈(最多 50 步) |
| 快捷键 | n8n | Delete / Ctrl+A / Ctrl+S / Ctrl+Z/Y / Ctrl+D 复制 |
| mini-map | n8n | 右下角缩略图,大图导航必备 |
| 节点搜索 | n8n | Ctrl+K 弹出节点 palette 关键词搜 |
| 校验态 | DolphinScheduler | 节点描红 + 顶部 banner + 提交时弹错误列表 |
| 单人锁 | 简化 | 进入页面 PUT `/lock`,5min 自动续期,leave PUT `/unlock`;别人进来只读 + banner |

## 7. 数据契约

### 加载

```
GET /api/console/workflow/definitions/{id}/full
→ { definition: WorkflowDefinition, nodes: [...], edges: [...], lockedBy: string | null }
```

### 保存(全量替换)

```
PUT /api/console/workflow/definitions/{id}/full
  body: { definition: {...}, nodes: [...], edges: [...] }
  → 同事务删旧 node/edge 再插新,version 自增
```

### 锁

```
PUT /api/console/workflow/definitions/{id}/lock      → { lockedBy, expiresAt }
DELETE /api/console/workflow/definitions/{id}/lock   → 204
PUT /api/console/workflow/definitions/{id}/lock/renew → { expiresAt }
```

### 跨域校验

```
GET /api/console/queries/pipeline-definitions/codes?tenantId=
  → FILE_STEP 下拉数据源
GET /api/console/queries/job-definitions/codes?tenantId=
  → JOB 下拉数据源
```

(部分已存在,差缺补)

## 8. 实施阶段

### Spike(1 周)
- 接 X6 + Pinia store + DAG canvas 跑通
- 实现 START / END / JOB 3 类节点 + 拖拽 + 连线 + 删除 + 自动布局
- 与 `definition_json` 单向同步(只读取,先不保存)
- Mermaid 导出复用 `utils/crossDayMermaid.ts` 思路
- 出 spike PR,内部 review 验证路径通

### MVP(2 周)
- 加 GATEWAY / FILE_STEP / APPROVAL
- 右侧 inspector + 节点表单
- 客户端 validators 全套
- 保存到 BE(走 PUT /full + 单人锁)
- 校验态 UI(节点描红 + banner)
- 撤销 / 重做 / 快捷键
- 单测覆盖 validators + codec

### Polish(1 周)
- mini-map + 节点搜索 palette
- 版本对比 diff(双画布并排)
- 模板库("Bundle 导入"的模板预填)
- e2e 冒烟(创建 → 校验 → 保存 → 重开 → 修改 → 提交)
- i18n 双语 + a11y(键盘可达)

### 总:**4 周**(纯前端 2.5 + BE 接口配合 1 + e2e 0.5)

## 9. BE 配合工作量(并行)

- `PUT /workflow/definitions/{id}/full` 全量替换端点(同事务)
- 三个锁端点 + Redis lock 实现(基于已有 ShedLock 或 Redisson)
- `pipeline-definitions/codes` / `job-definitions/codes` 轻量下拉端点(若已无)
- OpenAPI yaml + protocol changelog 同步

预估 **3-4 BE 天**。

## 10. 验收

- 用户能在 5 分钟内创建一个含 GATEWAY 的 8 节点 workflow 并跑通
- 校验拦截 100% 已知错误形态(单测覆盖)
- 千节点画布交互不卡(X6 默认即可)
- 双人并发编辑同一 workflow,后到只读 + 看到 lockedBy banner
- 浏览器关闭再开,dirty 提示恢复(useDirtyForm 已具备)
- Mermaid 导出与 `WorkflowMermaidViewer` 渲染一致

## 11. 风险

| 风险 | 缓解 |
|---|---|
| X6 学习曲线 | spike 阶段 review 内部知识沉淀 docs/design/x6-cheatsheet.md |
| BE 三表同事务全量替换性能 | 节点上限 200 / DAG 单次保存 < 100ms 验证(load-tests 加 case) |
| 单人锁过期窗口冲突 | 5min TTL + 心跳续期,leave 主动 unlock + 提示 banner |
| 与 Bundle Excel 导入并存 | 共用同一 definition_json schema,Bundle 导入仍可作为模板入口 |
| Mermaid viewer 与新 designer 双源不一致 | viewer 改为只渲染 designer 同一份输出的 mermaid string |

## 12. 不在本次范围(后续 follow-up)

- DAG 执行实时态叠加(节点高亮当前 RUNNING / SUCCESS / FAILED — 与 designer 解耦,在 viewer 侧加层)
- 多人协同编辑(yjs / OT — 重活,等业务需求)
- 节点级权限(VIEWER 角色只看不改 — 现有 `v-permission` 适配即可,简单)
- 大模型辅助生成 DAG

## 13. BE Spike 已落地状态(2026-06-04)

> 本节由 BE Spike PR 维护;FE 接入对账以此为准。

### 端点(URL 前缀沿用 `/api/console/workflow-definitions`,**不另立** `/api/console/workflow/definitions/`)

| 第 7 节 spec | 实际落地 path | 状态 |
|---|---|---|
| `PUT /workflow/definitions/{id}/full` | `PUT /api/console/workflow-definitions/{id}/full` | 落地 |
| `PUT /workflow/definitions/{id}/lock` | `PUT /api/console/workflow-definitions/{id}/lock` | 落地 |
| `DELETE /workflow/definitions/{id}/lock` | `DELETE /api/console/workflow-definitions/{id}/lock` | 落地 |
| `PUT /workflow/definitions/{id}/lock/renew` | `PUT /api/console/workflow-definitions/{id}/lock/renew` | 落地 |
| `GET /queries/job-definitions/codes` | 同 | 落地 |
| `GET /queries/pipeline-definitions/codes` | 同 | 落地 |
| `GET /workflow/definitions/{id}/full` | 复用现有 `GET /api/console/workflow-definitions/{id}` | 已具备(不另立 path) |

### 字段补充

- `PUT .../full` body 形态:`{ definition: WorkflowDefinitionSaveRequest, expectedVersion?: number, lockToken?: string }`
- `expectedVersion` 用作乐观锁谓词;不传则跳过版本冲突校验
- `lockToken` Spike 阶段忽略,锁归属以 SecurityContext.username 为权威
- `workflowCode` 不可改(全量替换时若 body 试图改 → `INVALID_ARGUMENT`)

### 锁实现

- **Redis SETNX**(`StringRedisTemplate.opsForValue().setIfAbsent`),独立 `WorkflowDesignLockService`
- Key:`wf-design-lock:{tenantId}:{definitionId}`;Value:JSON `{ lockedBy, expiresAt }`;TTL:5min,renew 重置
- **不走 ShedLock**(后者是 scheduled job 进程级互斥,语义不复用)
- 进程崩溃 / Redis 重启锁丢失即重新申请,无持久化

### RBAC

- `full` + `lock`(3 端点):`ROLE_ADMIN | ROLE_TENANT_ADMIN`
- `codes`(2 端点):沿用 `ConsoleQueryController` 类级 `ROLE_ADMIN | ROLE_AUDITOR | ROLE_TENANT_ADMIN | ROLE_TENANT_USER`
- 租户作用域:全部走 `ConsoleTenantGuard.resolveTenant`

### 错误码 i18n

`error.workflow_design_lock.{held_by_other, required, not_owner, expired, serialize_failed}` + `error.workflow_full_update.{code_immutable, version_conflict}`,en + zh_CN 已落 `messages.properties` / `messages_zh_CN.properties`。

### 测试

- `WorkflowDesignLockServiceTest`(6 case)
- `ConsoleWorkflowFullUpdateControllerTest`(3 case)
- `mvn -pl batch-console-api test -DskipITs` 全部通过(844 / 0 fail / 1 skip)
