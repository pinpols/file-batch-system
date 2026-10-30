# Workflow DAG 编辑器设计与实现基线

> 本文描述当前 batch-console 的 X6 图形编辑器、后端 Workflow 定义契约和 Mermaid 查看能力。当前实现以配对前端 `src/views/workflow/designer/WorkflowDesigner.vue`、`src/views/workflow/WorkflowMermaidViewer.vue` 及本仓库 Workflow API/校验代码为准；原始 Spike/MVP/Polish 排期只作设计背景，不作为待办状态。

## 当前定义身份与配置复制

`workflowCode`是定义的稳定编码，创建去重跨当前版本进行；编辑递增版本后不能再次创建同码独立定义。配置导入按精确编码读取最高版本，不能用列表模糊搜索结果定位写入对象；列表搜索行为保持不变。

配置包每个编码只携带当前最高版本与其图，当前版本停用时不回退旧启用版本。历史快照仍通过定义ID及版本读取，不随配置导出被删除。真实数据库及认证接口回归见[2026-10-27验证](../review/frontend-backend-bug-review-2026-10-27.md#backend-validation-record)。

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

## 5. 当前节点与校验边界

| 节点 | 后端语义 | 当前画布 |
|---|---|---|
| START / END | 持久化控制节点，不派 Worker | 支持 |
| JOB | `related_job_code` 引用同租户 Job，按需生成实例 | 支持 |
| GATEWAY | 分支/汇聚；joinMode 为 ALL/ANY/N_OF | 支持 |
| WAIT | 持久化 Sensor 等待，按 ADR-028 处理超时 | 后端支持；当前 `DesignerNodeType` 未列入，不宣称画布可编辑 |
| TASK / FILE_STEP / APPROVAL | TASK 为旧根实现；FILE_STEP 已退役；没有独立 APPROVAL 节点 | 不作为新图节点配置 |

WAIT 的 `timeout_seconds` 和 `poll_interval_seconds` 必须是可表示为 64 位整数的正整数秒，且超时必须大于轮询间隔。小数和超范围数值会被拒绝，运行时对已存配置也执行相同的整数校验。需要继续探测时，下次时间取探测或退避时间与超时截止时间中较早且可表示者，避免因退避推迟超时处理；两者均无法表示时，节点以传感器配置错误结束。探针已经匹配或达到失败阈值时无需计算下次探测时间。实际处理时间仍受调度间隔及系统负载影响。

客户端 `dagValidators.ts` 校验唯一 START、至少一个 END、START 无入边、END 无出边、非 END 有出边、可达性、无环、边引用、JOB 编码与条件表达式。GATEWAY 可用于单出边汇聚，入度≥2 时必须配置 joinMode；单入边网关可省略，但显式配置时也必须有效。N_OF 阈值必须为 1 至入边数之间的整数，不截断小数或溢出值。非 GATEWAY 节点可省略汇聚配置并默认 ALL；显式配置时同样必须使用有效模式，多个入边的 N_OF 阈值也按上述范围验证。服务端静态校验是权威，前端即时校验不能替代它。

启用定义或日程时，JOB 引用必须在同租户存在且已启用；未启用草稿可以按后端规则保留暂不可用引用。全量保存需编辑锁及 expectedVersion，运行准入再由 Orchestrator 校验；停用动作不应被坏引用阻止。不能通过图配置嵌套 Workflow，也不能用审批节点代替操作审批。

JOB 节点参数只补充根请求中不存在的键；根请求显式传入的值，包括 `null`，优先于节点参数。Job 定义的默认参数优先级低于这些运行时参数。

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

## 7. 当前数据契约

| 操作 | 端点与约束 |
|---|---|
| 读取图 | `GET /api/console/workflow-definitions/{id}?tenantId=...`，返回 detail/nodes/edges |
| 全量保存 | `PUT /api/console/workflow-definitions/{id}/full`；body 为 `definition`（含 nodes/edges）、`expectedVersion` 与适用锁字段；同事务更新并追加版本快照 |
| 申请锁 | `PUT /api/console/workflow-definitions/{id}/lock?tenantId=...` |
| 续锁 | `PUT /api/console/workflow-definitions/{id}/lock/renew?tenantId=...` |
| 释放锁 | `DELETE /api/console/workflow-definitions/{id}/lock?tenantId=...` |
| 历史版本 | `GET /api/console/workflow-definitions/{id}/versions/{version}?tenantId=...` |
| 发起运行 | `POST /api/console/workflow-definitions/{id}/trigger`，保持原 Idempotency-Key；未知结果只查 trigger-status |

不使用 `/workflow/definitions` 旧路径，不给 FILE_STEP 提供 Pipeline 下拉。节点、边及日程字段映射以配对前端 `src/api/workflowDesigner.ts`、codec 和后端 DTO 为准，不能只更新画布 JSON 丢弃未展示字段。Console OpenAPI 是生成类型来源。

## 7.1 历史设计排期的适用范围

下列 Spike/MVP/Polish 是原立项拆分，不能当作当前待办或全量完成声明；当前待办和证据见整合方案与验收矩阵。

## 8. 实施阶段

### Spike(1 周)
- 接 X6 + Pinia store + DAG canvas 跑通
- 实现 START / END / JOB 3 类节点 + 拖拽 + 连线 + 删除 + 自动布局
- 与 `definition_json` 单向同步(只读取,先不保存)
- Mermaid 导出复用 `utils/crossDayMermaid.ts` 思路
- 出 spike PR,内部 review 验证路径通

### MVP(2 周)
- 原计划涉及 GATEWAY；FILE_STEP/APPROVAL 已退出当前图设计，不继续按旧排期实施
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

## 前端编辑会话生命周期

桌面设计器只保留一个活动画布，离页时销毁并释放已获取的锁，不进入普通页面 KeepAlive 缓存。同一设计器路由切换定义时复用该活动实例，按定义 ID、租户和请求代次核对详情、保存及锁回包；旧会话不能覆盖当前画布或锁。

切换已有/新建定义会重置完整元数据，切租户重新读取当前目标；无租户时保持只读。存在未保存内容时，离页或切换定义需确认，取消后保留当前草稿与锁。保存基线来自实际提交快照，请求期间的新编辑保持未保存；新建成功后的路由升级保留当前画布并获取新定义锁。

前端隔离或失效的申请仍可能已在服务端获得锁；没有锁会话 token 时，不能盲目补发旧 DELETE，以免释放同用户的新锁。窗口卸载的 keepalive 请求只是尽力释放，未送达时由现有 TTL 回收，不保证立即释放。后端版本 CAS 与锁归属仍是写保护的最终边界。前端回归及浏览器数据来源见[当轮记录](../../../batch-console/docs/reports/2026-10-26-frontend-bug-remediation.md#全工程续审第二批)。
