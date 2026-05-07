# 系统职责范围分析 — 批量调度系统的边界守护

**文档状态**：架构基准  
**最后更新**：2026-05-06  
**审核周期**：每季度 / 有 PR 涉及新模块时重审

---

## 1. 系统定位

**原则**：批量任务调度 + 文件/任务交付闭环控制面，而非企业通用平台。

**核心职责**：何时跑 → 跑哪个 → 怎么切分 → 谁来跑 → 失败怎么办 → 结果怎么交付 → 能否追溯。

---

## 2. 正面清单 —— 系统应该做的

### 2.1 调度控制面

| 能力 | 范围 | 模块 |
|---|---|---|
| **Trigger 引擎** | Cron / 时区 / DST / 秒级重试 / 业务日可达性 | batch-trigger |
| **Batch Day 生命周期** | 打开 / 切割 / 冻结 / 跳过 / 关闭 / 追补 | batch-orchestrator |
| **DAG 编排** | Job 依赖 / 并行 / 条件分支 / 跨日窗口 / 对账闭合点 | batch-orchestrator workflow |
| **分区策略** | 按日期 / 按量级 / 按业务分组 | batch-orchestrator partition-engine |
| **Job Definition 版本化** | Schema migration / 配置版本对应 / 生效时间戳 | batch-orchestrator job-definition |
| **结果版本管理（ADR-017）** | 输出快照 / 多版本共存 / 手动 promote / 生效决策 | batch-orchestrator result-version |
| **跨日依赖（ADR-018）** | 前一天完结前置 / 前一日 DAG 节点等待 / 业务日时间窗 | batch-orchestrator cross-day-dag |
| **业务日历** | 工作日 / 半天工作日 / 节假日 / 时间窗 | batch-orchestrator business-calendar |
| **Resource Profile** | CPU / 内存 / 磁盘申请与分配 | batch-orchestrator resource-profile |
| **Quota & Fair Share** | 租户级配额 / 按优先级公平调度 | batch-orchestrator quota-engine |

### 2.2 执行引擎

| 能力 | 范围 | 模块 |
|---|---|---|
| **5-Stage WAP** | Import / Export / Process / Dispatch + File validation | batch-worker-{import,export,process,dispatch,core} |
| **SQL Transform** | 数据 staging → 正式的 SELECT-into-staging SQL 执行 | batch-worker-process sql-transform-plugin |
| **File Template** | 文件格式定义 / 字段映射 / 位置参数解析 | batch-worker-core file-template |
| **Worker Routing** | 按 worker_type / capabilityTags / 资源 profile 路由 | batch-orchestrator worker-router |
| **Partition 执行** | 分区级并行 / 分区级重试 / 分区级死信 | batch-worker-core partition-executor |

### 2.3 文件交付闭环

| 能力 | 范围 | 模块 |
|---|---|---|
| **File Object Registry** | MinIO 文件记录 / 校验和 / 生命周期 | batch-orchestrator file-object |
| **Outbox + Kafka** | 批量交付事件 / 下游订阅 / exactly-once 语义 | batch-orchestrator outbox / batch-common kafka-plugin |
| **Input Staging** | 上游文件接收 / 格式校验 / 去重 | batch-worker-import staging-engine |
| **Output Distribution** | 下游发送 / 重试 / 回执确认 | batch-worker-dispatch delivery-engine |
| **数据对账（ADR-021）** | 输入文件行数 ↔ 处理行数 / 金额汇总 / 分区对账 | batch-orchestrator recon-engine |

### 2.4 故障治理

| 能力 | 范围 | 模块 |
|---|---|---|
| **失败分类（ADR-012）** | 7 个失败类型 + 智能重试 / 告警分级 / 降级策略 | batch-common failure-class / batch-orchestrator failure-classifier |
| **重试治理** | 多种策略（fixed / exponential / custom） / 幂等性 / 死信队列 | batch-orchestrator retry-engine / dead-letter |
| **Approval & Compensation** | 运维审批介入点 / 手动补救 / 追补追跳 | batch-console-api approval-controller + compensation-service |
| **Batch Day Replay（ADR-020）** | 某日重放 / 部分步骤重放 / 输出版本选择 | batch-orchestrator batch-day-replay-service |
| **告警升级（ADR-019 SLA）** | P0/P1/P2 分级 / 逐步升级 / 通知 fan-out | batch-orchestrator sla-escalation / batch-common notification-plugin |

### 2.5 运维可观测性

| 能力 | 范围 | 模块 |
|---|---|---|
| **Forensic 取证（ADR-022）** | 某批次一键证据包导出 | batch-orchestrator forensic-exporter |
| **Audit Log** | 配置变更 / 人工操作 / 状态流转 | batch-orchestrator audit-log + approval-audit |
| **Execution Log** | Job / Step / Partition 执行记录 + Worker 日志定位 | batch-orchestrator execution-log |
| **静态校验（ADR-025）** | DAG 有效性 / 配置完整性 / 依赖解析 | batch-console-api workflow-validator |
| **Dry-run 模式（ADR-026）** | 配置校验 / 调度计划 / SQL explain | batch-orchestrator dry-run-engine |

### 2.6 Console 运维入口

| 能力 | 范围 | 模块 |
|---|---|---|
| **配置管理** | Workflow / Job / Calendar / Quota / Worker 的 CRUD | batch-console-api 各 controller |
| **运行监控** | 实时状态 / 历史执行 / 关键节点看板 | batch-console-api dashboard-controller |
| **快速操作** | 跳过 / 冻结 / 重开 / 强制释放 / 追补 | batch-console-api batch-day-ops-controller |
| **诊断工具** | Cluster 自检 / 调度逻辑 trace / Worker 健康度 | batch-console-api cluster-diagnostic-controller |

---

## 3. 负面清单 —— 系统明确不做的

| 禁区 | 原因 | 边界 |
|---|---|---|
| **企业级数据治理平台** | 业务含义最终裁定属于 BI/DW 团队 | 只做批量输入/输出对账，不做全域血缘/谱系 |
| **财务核算系统** | 借贷平衡、账户核算是金融后台职责 | 对账可以检测差异，但不参与补账决策 |
| **容器编排平台** | Worker 调度不是我们的重心 | 只做 workerType 路由，不做 K8s affinity/taint/toleration |
| **前端埋点收集平台** | RUM / Sentry 是专门系统的活 | console UI 自己的诊断事件可以记，但不是 telemetry hub |
| **通用 SQL 计算引擎** | dbt / Trino / Spark 才是数据加工 | SQL Transform 只用于 WAP 的 transform 阶段 |
| **全企业审计日志系统** | SIEM / 审计调查平台是合规团队 | 只存本系统内部的状态/操作日志 |
| **通用日志存储平台** | ELK / Loki 是日志中心 | 本系统日志走 access log / application log，聚合到公司日志平台 |
| **跨系统 API 网关** | API Gateway / 服务网格是基础设施 | console API 只暴露本系统能力，不做"万能代理" |

---

## 4. 已识别的中小越界/模糊点

### 4.1 [中度越界] ConsoleTelemetryController

**当前代码**：
```
POST /api/console/telemetry/events
{
  "app": "console-web",
  "userId": "xxx",
  "page": "workflow-list",
  "event": {
    "type": "click_delete_button",
    "props": { "workflowId": "123" }
  }
}
```

**问题**：
- ❌ 接前端任意埋点事件，变成了"前端日志接收平台"
- ❌ INFO/ERROR 打到批量系统日志，污染告警视图
- ❌ 前端流量 QPS 可能远超调度路径，压爆 console-api
- ❌ 各团队都想往这里塞事件，无限扩张

**风险等级**：🟡 中

**建议方案**：

**Option A（推荐删除）**：彻底删掉 `/api/console/telemetry/events`，前端日志走 Sentry / 字节火山引擎应用监控。

**Option B（严格收敛）**：
- 只接受 `console_error` 和 `console_action` 两个 type
- body 大小限制 8KB
- QPS rate-limit（例如 per-user 10 req/min）
- 只记到 WARN 日志不发告警
- 3 个月自动归档删除

---

### 4.2 [边界风险] ConsoleAiController

**当前代码**：
```
POST /api/console/ai/chat
{
  "messages": [...],
  "context": "batch_day_failure"
}
```

**现状评估**：
- ✓ System prompt 约束：只回答批量调度问题
- ✓ 多层防护：authz gate + prompt guard + 审计（SHA-256 哈希，不落明文）
- ✓ 不直接代执行：只给建议，不调写接口
- ✓ 拒绝路径完整：敏感问题明确拒绝

**风险**：
- ❌ 路径太泛：`/chat` 通用化，容易加"帮我写 SQL""帮我分析这个文件"溜出 scope
- ❌ 没有与 forensic / approval 对接：AI 回答被用户照着做，没有更深 trace 关联

**风险等级**：🟡 中（目前守得住，但有扩张通道）

**建议方案**：
1. **路径收敛**：把 `/api/console/ai/chat` 改成具体能力
   ```
   POST /api/console/ai/explain-failure/{instanceId}
   POST /api/console/ai/recommend-cron/{jobCode}
   POST /api/console/ai/diagnose-dag/{workflowId}
   ```
   不留通用 `/chat` 入口

2. **在相关 ADR 加显式声明**：
   > AI 是辅助分析工具，永远不直接调任何写接口（launch / cancel / retry）。每个 AI 建议都在 audit_log 里标 `suggestion_from_ai=true`，可追溯。

3. **与 approval + forensic 对接**：
   - AI 建议 + 用户确认 → 生成 approval_request
   - Forensic 包导出时一并抓出"这个决策是否参考了 AI 建议"

---

### 4.3 [扩张风险] SqlTransformComputePlugin

**当前代码**：业务方写 SQL，worker 在 SQL Transform 阶段执行：
```sql
INSERT INTO staging_xxx_daily
SELECT 
  col1,
  col2,
  CASE WHEN col3 > 0 THEN col4 ELSE 0 END as processed
FROM staging_xxx_raw
WHERE business_date = ${businessDate}
```

**现状评估**：
- ✓ 有 SqlTransformComputeSqlValidator
- ✓ 有 SqlTransformComputeSecurityProperties（禁止 DDL / 跨库 JOIN）
- ✓ 限制在 5-stage WAP 的 transform 阶段

**风险**：
- ❌ "租户随便写 SQL 当 ETL 跑" → 接近 dbt / Spark SQL 的活，越界
- ❌ Validator 力度不够：有没有防止 `SELECT * FROM prod_customer_table`？有没有防止跨业务 JOIN？
- ❌ 执行权限：worker 用什么库账号？有行级权限控制吗？

**风险等级**：🟡 中

**建议方案**：

在新版 ADR-021（数据对账闭环）/ ADR-026（dry-run 模式）中加明确的 §范围限制：

> **SqlTransform 使用规范**：
> - 只用于 WAP 的 transform 阶段（select-into-staging 模式）
> - 禁止 DDL / DROP / CREATE / ALTER
> - 禁止跨业务表 JOIN（只允许同业务日期的 staging 表自 JOIN 和 lookup 维表）
> - 禁止 CALL procedure / EXECUTE 动态 SQL
> - 执行时用只读账号或行级权限隔离
> - 超过 N 秒自动 kill，防止长时间锁表

**加强 Validator**：
```java
SqlTransformComputeSqlValidator
  - checkNoDDL()
  - checkNoDropTruncate()
  - checkNoJoinOutsideBiz(businessId)
  - checkNoCallProcedure()
  - checkTimeoutMs(maxMs)
```

---

### 4.4 [概念模糊] ConsoleResourceTagController

**当前代码**：
```
GET /api/console/resource-tags/keys
GET /api/console/resource-tags/{key}/values
POST /api/console/resource-tags/{resourceId}
{
  "key": "worker_capability",
  "value": "gpu"
}
```

**问题**：
- ❓ "资源标签"概念太通用，可贴到任何东西上
- ❓ 用得好：worker capability + job 分组（接 ADR-027）
- ❌ 用得不好：蔓延到 file_record / business_entity 上，变成"自由 key-value 平台"

**风险等级**：🟡 中（接近犯错但还没犯）

**建议方案**：

在 ADR-027（资源亲和性）中明确 scope：

> **Resource Tag 的使用范围**：
> - ✓ Worker capability：`{"gpu": "nvidia-a100"}`, `{"cpu_arch": "x86_64"}`, `{"memory_gb": 256}`
> - ✓ Job 分组标签：`{"team": "settlement"}`, `{"region": "cn-shanghai"}`
> - ✗ 不允许贴到 `file_record` / `business_entity` / `approval_request`
> - ✗ 不允许用户随意创建 key（预定义白名单）

**代码加约束**：
```java
private static final Set<String> ALLOWED_TAG_KEYS = Set.of(
    "worker_capability",
    "job_group",
    "region",
    "team",
    // ... 预定义 20 个以内
);
```

---

### 4.5 [轻度模糊] ConsoleEventCatalogController

**当前代码**：
```
GET /api/console/event-types
GET /api/console/topics
```

**问题**：
- ❓ 如果只是"本系统内部注册的 event_type / topic 列表"→ 合理元数据
- ❌ 如果扩展成"业务事件总线目录" + "让用户从 UI 注册新 event" → 越界（EventBridge / NATS schema registry 的活）

**风险等级**：🟢 低（但要监控）

**建议方案**：
- ✓ GET 接口保留，暴露只读元数据（本系统已注册的 topic / event）
- ✗ 删掉任何 POST/PUT event_type 的接口
- ✓ 在 ADR 中明确：event catalog 是运维只读浏览，不是"用户自服务定义事件"

---

## 5. 没问题的"看似可疑实则合理"清单

这些可能看起来在做"奇怪的事"，但其实是调度系统应该做的。列出来防止被误伤。

| 模块 | 看起来像 | 实际职责 | 判定 |
|---|---|---|---|
| `ConsoleClusterDiagnosticController` | 像 K8s diagnostic | shedlock / workers / outbox queue / terminal children 的内部诊断 | ✓ 调度自检 |
| `ConsoleApprovalController` | 像独立审批平台 | 仅 batch config / batch_day 治理的审批流，没扩到 IT-wide | ✓ 配套治理 |
| `ConsoleReportExcelController` | 像 BI 报表系统 | 只导出运维数据（config-releases / audits / scheduler-snapshot / workers） | ✓ 运维导出 |
| `ConsoleNotificationController` + Webhook | 像通用通知中台 | 仅 batch 系统告警 fan-out，不接其它系统告警 | ✓ 告警通路 |
| Quartz 替代（trigger 模块） | 像通用调度框架 | 只服务本平台 trigger，没暴露给业务方调度任意 job | ✓ 内部组件 |
| ADR-017 `result_version` | 像 git for data | 只存 outputs Map + 版本元数据，不复制业务表完整记录 | ✓ ADR-017 已守 |
| ADR-024 `archive_storage_metadata` | 像数据湖 catalog | 仅本平台 archive 表的生命周期元数据 | ✓ ADR-024 已守 |

---

## 6. 短期行动清单

按优先级排序，前三项立即开工：

### P0 — 本月内

- [ ] **删除或严格收敛 ConsoleTelemetryController**
  - Option A：删掉，前端接 Sentry
  - Option B：如选收敛，加 rate-limit + 字段白名单 + 3 个月自动删除
  - 关联 PR：修改 batch-console-api

- [ ] **AI 接口路径收敛 + ADR 声明**
  - 改 `/api/console/ai/chat` → `/api/console/ai/explain-failure`, `/api/console/ai/recommend-cron`
  - 在 ADR-021 / ADR-026 中加 §AI 不直接执行写接口
  - 关联 PR：修改 batch-console-api + 更新 ADR

### P1 — 2 周内

- [ ] **SqlTransform 加强 Validator + 文档**
  - 补 checkNoJoinOutsideBiz / checkTimeoutMs
  - 在 ADR-021 中加 §SqlTransform 使用规范
  - 关联 PR：修改 batch-worker-process + ADR

- [ ] **Resource Tag 约束 + 预定义白名单**
  - 代码加 ALLOWED_TAG_KEYS 白名单
  - 在 ADR-027 中加 §Resource Tag 使用范围
  - 关联 PR：修改 batch-console-api + ADR

### P2 — 本月底前完成

- [ ] **Event Catalog 删掉写接口**
  - 确认没有 POST /api/console/topics / POST /api/console/event-types
  - 如有，删掉；只保留 GET 只读
  - 关联 PR：修改 batch-console-api

---

## 7. 定期审核机制

**频率**：
- **季度正式审核**：每 Q 末梳理一遍有没有新功能越界
- **PR 触发审核**：涉及新模块或新能力时在 PR 描述中标 `[scope-check]`

**审核清单**：
```
- [ ] 这个新功能属于上述 5 大类吗？
- [ ] 有没有引入负面清单里的禁区？
- [ ] 有没有扩大了现有 4-5 个模糊点的范围？
- [ ] 有没有和其它开源调度系统（Airflow / Control-M / Argo）的职责重叠？
- [ ] 是否需要在 ADR 中加边界声明？
```

---

## 8. 参考 ADR 列表

与职责边界相关的 ADR：

| ADR | 主题 | 关键约束 |
|---|---|---|
| ADR-012 | 失败分类 | 只为重试/告警/补偿分类，不扩大为"全企业故障分析" |
| ADR-021 | 数据对账闭环 | 只对账批量输入/输出/结果，不做全域数据治理 |
| ADR-022 | Forensic 一键取证 | 只做运行证据包，不做合规审计调查平台 |
| ADR-023 | 多日历联动 | 只服务调度日历，不做"全球日历 SaaS" |
| ADR-025 | Workflow 静态校验 | 配置校验防事故，不做通用 DSL 验证框架 |
| ADR-026 | dry-run 模式 | 计划/校验/explain，不做全链路执行模拟 |
| ADR-027 | 资源亲和性 | 只做 workerType 路由 + capability tag，不做 K8s 调度器 |

---

## 结语

**总体结论**：系统当前 **没有大幅越界**，大部分模块职责清晰。但有 4-5 处中小越界/模糊点需要在短期（本月内）加强护栏，防止通过 PR 小步扩张而最终越界。

**核心原则**：当有新功能诉求时，先问：**这是批量调度系统应该自己做，还是应该对接专门系统（数据治理 / RUM / 审计 / 编排）？**
