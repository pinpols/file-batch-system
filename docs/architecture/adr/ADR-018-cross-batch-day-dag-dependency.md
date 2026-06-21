# ADR-018 · 跨批量日 DAG 依赖（pipe 模型）

- **Status**: Accepted（Stage 2-4 已落 V109 + WAITING_DEPENDENCY + BizDateArithmetic + CrossDayDependencyResolver；Stage 5-7 排期中）
- **Date**: 2026-05-06（Accepted: 2026-05-06）
- **Supersedes**: —
- **Related**: ADR-009（workflow 节点 output / DSL）/ ADR-017（result_version 主模型，本 ADR 强依赖）/ §14.3.2（设计层缺口）/ `docs/architecture/workflow-dependency-guide.md`

## 背景

现有 workflow 主模型紧绑单 `bizDate`：

- `workflow_run.biz_date` 单一值；workflow_node_run 只能引用同 run 内上游节点的 output（ADR-009 限定 `$.nodes.<X>.output.<key>`）；
- 真实场景大量需要"跨日依赖"：
  - **月度汇总**：5 月报表读取 5/1 ~ 5/31 共 31 个日表；
  - **T+5 调账**：T+5 的 settlement job 必须等 T 日的清算 EFFECTIVE；
  - **回写式补数**：T+1 的对账若发现 T 日数据错，需要先 trigger T 日 result_version promote 再跑 T+1。
- 当前回退：worker 自己 SQL 拼 `WHERE biz_date BETWEEN ? AND ?`，orchestrator 失去依赖图的可视化与重放管控；workflow_dependency_guide.md 也明确点出"跨批量日依赖未支持"。
- ADR-017 一旦落地，"上游某 bizDate 的 EFFECTIVE 版本"成为可寻址锚点，本 ADR 才有意义。

## 决策（提案）

采用 **pipe 模型**：保持 `workflow_run` 单 bizDate 不变，新增 `workflow_node.cross_day_dependencies` 描述跨日上游；orchestrator 在节点启动前用 `CrossDayDependencyResolver` 把它解析为对 `result_version` 的查询，找不到 EFFECTIVE 就停在 `WAITING_DEPENDENCY` 等。

被拒方案（gather）：让 workflow_run 携带多个 bizDate / 二维 (jobCode, bizDate) 依赖图。schema 改动大、引入 workflow 状态机的二维笛卡尔，不值得。详见"替代方案"。

### 依赖声明

`workflow_node.cross_day_dependencies` JSONB 数组：

```json
[
  {
    "jobCode": "DAILY_PNL",
    "bizDateOffset": -1,
    "scope": "REQUIRED",
    "consumeVersionStrategy": "EFFECTIVE_ONLY"
  },
  {
    "jobCode": "RPT_DETAIL",
    "bizDateOffset": "MONTH_START",
    "scope": "REQUIRED",
    "consumeVersionStrategy": "EFFECTIVE_ONLY"
  },
  {
    "jobCode": "MARKET_DATA",
    "bizDateRange": "PREV_5_BIZ_DAYS",
    "scope": "OPTIONAL",
    "consumeVersionStrategy": "LATEST_INCLUDING_PENDING"
  }
]
```

字段语义：

| 字段 | 类型 | 说明 |
|---|---|---|
| `jobCode` | string | 上游 job_definition.job_code |
| `bizDateOffset` | int / enum | `-1` (T-1) / `-7` / `MONTH_START` / `MONTH_END` / `QUARTER_START` 等；用 `BizDateArithmetic` 工具基于 `business_calendar` 计算 |
| `bizDateRange` | enum | `PREV_5_BIZ_DAYS` / `MTD_TO_YESTERDAY` / `LAST_4_WEEKS`；多个上游版本，用于聚合场景 |
| `scope` | enum | REQUIRED：缺则等；OPTIONAL：缺则跳过引用，节点照常启动 |
| `consumeVersionStrategy` | enum | `EFFECTIVE_ONLY`（默认）/ `LATEST_INCLUDING_PENDING` / `SPECIFIC_VERSION`（需配 `version_no`） |

### 解析时机

```
workflow_node 进入 READY_FOR_DISPATCH
    │
    ▼
CrossDayDependencyResolver.resolve(node, workflowRun)
    │
    ├── 计算每条依赖的 (jobCode, bizDate, version_no?)
    ├── 查 result_version (按 ADR-017 contract)
    │
    ├──[全部命中]── 把 outputs 注入 node payload (沿用 ADR-009 DSL 的 $.crossDay.<jobCode>.<key>)
    │                ▼
    │           节点启动
    │
    └──[REQUIRED 缺失]── workflow_node_run.status = WAITING_DEPENDENCY
                          ▼
                      CrossDayDependencyReconciler 周期扫描，命中再启动
```

### DSL 扩展（ADR-009 复用）

新增引用语法（不破坏现有）：

```
$.crossDay.<jobCode>.<offsetTag>.output.<key>
$.crossDay.<jobCode>.<offsetTag>.payload_ref     -- 指向 file_record / OSS
```

`<offsetTag>` 是 cross_day_dependencies 数组里某条的稳定 alias（默认 `t_minus_1` / `t_minus_7` / `month_start` 等，可显式命名）。多版本范围引用：

```
$.crossDay.<jobCode>.range.<rangeTag>.outputs    -- List<Map<String,Object>>，按 bizDate 升序
```

### 状态机扩展

`workflow_node_run.status` 新增 `WAITING_DEPENDENCY`（已有 WAITING / READY 之外）：

```
CREATED → READY → WAITING_DEPENDENCY (跨日依赖未齐) → READY → RUNNING → SUCCESS/FAILED
                       ▲
                       └── 由 CrossDayDependencyReconciler 推回 READY
```

### 调度治理

- **Reconciler**：`CrossDayDependencyReconciler` 按 ShedLock 周期扫 `WAITING_DEPENDENCY` 节点，逐一重新跑 resolver；
- **超时**：`workflow_node.cross_day_dependency_timeout_seconds`（默认 24h），超时后按 `scope` 决策：REQUIRED → 节点 FAIL；OPTIONAL → 跳过引用启动；
- **审计**：每次 resolver 命中 / 缺失写 `job_execution_log`，便于排查"为什么我等了"；
- **重放联动**（ADR-020）：上游 EFFECTIVE 切换会发 `result_version.changed` 事件，下游节点 WAITING_DEPENDENCY 可即时唤醒。

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | `workflow_node` 加 JSONB 列 `cross_day_dependencies` + `cross_day_dependency_timeout_seconds`；migration 老节点该列 NULL（无影响） |
| 模块 | orchestrator 加 `CrossDayDependencyResolver` + `CrossDayDependencyReconciler`；workflow worker 收到 payload 后无感知（DSL 由 orchestrator 解开） |
| 配置 console | workflow 编辑器需要增加"跨日依赖"区块 UI（非后端） |
| 兼容性 | 老 workflow 列为 NULL → 行为不变；启用跨日依赖只对显式声明的 workflow 生效 |
| 性能 | 单 node 启动时同步多查 N 个 result_version；预期 N ≤ 30，命中索引 < 5ms |

## 实施分阶段

| Stage | 范围 | 估算 | 守护 |
|---|---|---|---|
| 1 | ADR-017 落地 | (前置) | (前置) |
| 2 | schema：`workflow_node.cross_day_dependencies` JSONB + 解析 POJO | 1 天 | schema migration test |
| 3 | `BizDateArithmetic`（基于 business_calendar 解 offset / range） | 2 天 | 跨节假日单测 |
| 4 | `CrossDayDependencyResolver` + DSL 扩展（`$.crossDay.X.Y.output.Z`） | 3 天 | resolver 单测 + DSL 解析测 |
| 5 | `WAITING_DEPENDENCY` 状态机 + reconciler + ShedLock | 2 天 | 集成测试 |
| 6 | 跨日 workflow E2E（5 月汇总 demo） | 2 天 | E2E 测试 |
| 7 | 超时治理 + 审计 + 告警 | 1 天 | scheduler 单测 |

总：~11-12 人天（不含 ADR-017）。

## 替代方案（被拒绝）

### 方案 B：gather 模型（workflow_run 二维化）

让 `workflow_run` 持有 bizDate 集合，依赖图变成 `(jobCode, bizDate)` 二维节点。

**拒绝原因**：
1. workflow_run 状态机要从 1 维（节点拓扑）扩到 2 维（节点 × bizDate），状态空间爆失败；
2. 现有 workflow_node_run / partition / task 全部需要双键改造，回归面巨大；
3. 跨 workflow 的同一 bizDate 仍然要靠 result_version 解耦，最终还是要走 ADR-017 — gather 模型只是把 result_version 的复杂度搬到 workflow_run 里去；
4. 实现成本 ~30+ 人天，远超 pipe 模型，且**多数业务场景仍可用 pipe 表达**（聚合靠 OPTIONAL + range，调账靠 REQUIRED + offset）。

### 方案 C：在 worker 层自己 SQL 拼 bizDate range

**拒绝原因**：丧失 orchestrator 视角的依赖图，跨节点重放、补数、版本路由都要重写一遍。当前痛点根源就是这个。

## 不变量

1. 跨日依赖只能引用 `result_version.status='EFFECTIVE'`（除非显式声明 `LATEST_INCLUDING_PENDING`），不引用 RUNNING / FAILED；
2. 解析失败的引用永远 fail-fast，不会用 stale / partial 数据回退（杜绝静默错配）；
3. `WAITING_DEPENDENCY` 占用资源 = 0：不持锁、不占 worker，只在 reconciler 扫描时短暂消耗 DB；
4. `CrossDayDependencyResolver` 永远不写状态，只读 + 决策；状态变更由 reconciler / dispatcher 写。

## 验收标准

- 单测：`CrossDayDependencyResolverTest`（offset / range / timeout / OPTIONAL fallback / 业务日历跨节假日）
- IT：T-1 上游 EFFECTIVE 缺失时下游停 WAITING_DEPENDENCY；T-1 promote 后下游唤醒
- E2E：5 月月度汇总 workflow 拉 31 个日表 EFFECTIVE 跑通
- 性能基准：30 条依赖项的解析 < 50ms（连 DB cache hit）

## 开放问题（已收敛）

| # | 问题 | 决策 |
|---|---|---|
| 1 | 多 workflow 嵌套 | **复用 ADR-017 主模型**。workflow 整体 SUCCESS 时 orchestrator 写 `result_version` 行，`business_key=workflow:{wfCode}:{bizDate}`（已写入 ADR-017 §业务主键定义表）。下游跨日依赖 `jobCode` 等同 workflow code 即可消费，不引入 workflow 专属 result_version 类型 |
| 2 | 依赖图可视化 | **依赖列表 + 抽屉展开**（不在 workflow 拓扑里塞 T-N 节点）。Console workflow 详情页保留单 bizDate 拓扑视图，跨日依赖在节点详情侧栏列出 (jobCode, offsetTag, EFFECTIVE/PENDING) 三列；UI 排期由前端跟进，不在本 ADR 后端范围 |
| 3 | bizDateRange 硬上限 | **默认上限 90 天**。`BizDateArithmetic` 解析 range 时检查跨度，超过 `batch.workflow.cross-day.range-max-days`（默认 90）的依赖在 validator 阶段拒绝；业务确实需要更长范围，走自定义 resolver bean 注册（扩展点保留），不在主模型放宽阈值 |
| 4 | 循环依赖检测 | **WorkflowGraphValidator 在启用时强制检查**：发现 OPTIONAL 节点跳过依赖后输出仍被下一日同 jobCode 依赖（"传染性退化"路径）就 fail-fast 拒绝启用。validator 实现复用现有 `WorkflowDagService` graph traversal，加跨日维度的 fixed-point 检查 |

### 不会做（以及原因）

- ❌ **不让 workflow_run 携带多 bizDate**（gather 模型）—— 已在「替代方案 B」明确拒绝；状态机二维笛卡尔不可承受
- ❌ **不允许跨日依赖引用 RUNNING / FAILED 版本** —— 不变量 #1；唯一让步是显式声明 `LATEST_INCLUDING_PENDING`（消费 PENDING 的版本由 ops 自负风险）
- ❌ **不静默回退 stale / partial 数据** —— 不变量 #2；解析失败永远 fail-fast 写 audit log
- ❌ **不在 worker 内部做依赖解析** —— 解析必经 orchestrator，保留单一状态主机 + 重放可控（不变量 #4）
- ❌ **v1 不做"按 partition 跨日依赖"** —— 跨日依赖只到 job-level；如确需 partition-level 走 ADR-017 §开放问题 #1 的扩展路径

### 实施记录

| Stage | 状态 | commit |
|---|---|---|
| 1. ADR-017 落地 | ✓（前置） | ADR-017 实施记录 |
| 2. schema (V109) `workflow_node.cross_day_dependencies` + WAITING_DEPENDENCY | ✓ | `8b5d61c4` |
| 3. BizDateArithmetic（offset / range，含节假日跳过） | ✓ | `7131a540` |
| 4. CrossDayDependencyResolver + DSL 扩展 `$.crossDay.X.Y` | ✓ | `c90c8725` |
| 5. WAITING_DEPENDENCY reconciler + ShedLock | ☐ | pending |
| 6. 跨日 workflow E2E（月汇总 demo） | ☐ | pending |
| 7. 超时治理 + 审计 + 告警 | ☐ | pending |
