# Console Sidebar 菜单树

> 说明：
> - 这里写的是前端侧边栏的“页面可见性”建议，不是安全边界的最终依据。
> - 后端真实鉴权仍以接口上的 `@PreAuthorize`、租户校验和业务门禁为准。
> - 同一页面里如果存在“只读可见”和“写操作可用”两种权限，本文件会拆开说明。

## 角色约定

- `ROLE_ADMIN`：全权限管理员（平台级）
- `ROLE_AUDITOR`：只读审计角色
- `ROLE_TENANT_ADMIN`：配置与运维角色
- `ROLE_TENANT_USER`：租户业务用户（可查看状态、触发作业、下载文件，不可修改配置或运维操作）

## Sidebar 树

### 1. 首页总览

| 模块 | 页面 | 可见角色 | 备注 |
|------|------|----------|------|
| 首页总览 | 控制台首页 / 运营总览 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | 对应 `GET /api/console/ops/summary`、`GET /api/console/ops/summary/events` |
| 首页总览 | 仪表盘统计 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | 对应 `/api/console/dashboard/*` |
| 首页总览 | 调度快照 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | 对应 `/api/console/scheduler/snapshot`、`/history` |
| 首页总览 | 告警趋势 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | 对应 `/api/console/dashboard/alert-trend` |
| 首页总览 | SLA 达标率 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | 对应 `/api/console/dashboard/sla-compliance` |

### 2. 查询中心

| 模块 | 页面 | 可见角色 | 备注 |
|------|------|----------|------|
| 查询中心 | 审计日志 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/audits` |
| 查询中心 | 执行日志 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/execution-logs` |
| 查询中心 | 告警事件 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/alerts` |
| 查询中心 | 批量日 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/batch-days` |
| 查询中心 | 审批单 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/approvals` |
| 查询中心 | 待审批 Catch-Up | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/catch-up-approvals` |
| 查询中心 | 文件记录 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/files` |
| 查询中心 | 作业实例 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/instances` |
| 查询中心 | 作业步骤实例 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/job-step-instances` |
| 查询中心 | 工作流运行 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/workflow-runs` |
| 查询中心 | 工作流节点运行 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/workflow-node-runs` |
| 查询中心 | 文件派发记录 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/file-dispatches` |
| 查询中心 | 文件到达组 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/file-arrival-groups` |
| 查询中心 | 文件错误记录 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/file-errors` |
| 查询中心 | Outbox 投递/重试 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/outbox-deliveries`、`/outbox-retries` |
| 查询中心 | Dead Letter / Retry | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/dead-letters`、`/retries` |
| 查询中心 | Worker 注册信息 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/workers` |
| 查询中心 | 文件通道详情 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/file-channels/{channelCode}` |
| 查询中心 | 文件模板详情 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/file-templates/{templateCode}` |
| 查询中心 | 文件流水线 / 兼容路由 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/file-pipelines`、`/pipeline-definitions` |
| 查询中心 | 文件流水线步骤运行 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/query/file-pipeline-steps` |
| 查询中心 | 文件流水线观测页 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/file-pipeline-observability*` |

### 3. 定义管理

| 模块 | 页面 | 可见角色 | 备注 |
|------|------|----------|------|
| 定义管理 | 作业定义 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | 详情可见四类角色；创建、编辑、删除、启停建议仅 `ROLE_ADMIN` |
| 定义管理 | 工作流定义 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | 详情可见四类角色；创建、编辑、删除、启停仅 `ROLE_ADMIN` |
| 定义管理 | 流水线定义 | `ROLE_ADMIN` | 当前页面建议仅管理员展示 |
| 定义管理 | 文件通道 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` | 列表与详情可见三类角色；新建/编辑建议 `ROLE_TENANT_ADMIN` 及以上；删除仅 `ROLE_ADMIN` |
| 定义管理 | 文件模板 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` | 同文件通道 |
| 定义管理 | 作业定义 Excel | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` | 导出/预览对三类角色可见；应用动作建议仅 `ROLE_ADMIN` / `ROLE_TENANT_ADMIN` |
| 定义管理 | 工作流定义 Excel | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` | 同上 |
| 定义管理 | 文件通道 Excel | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` | 同上 |
| 定义管理 | 文件模板 Excel | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` | 同上 |

### 4. 调度与编排

| 模块 | 页面 | 可见角色 | 备注 |
|------|------|----------|------|
| 调度与编排 | 触发器管理 | `ROLE_ADMIN` | 注册、暂停、恢复、注销均建议仅管理员展示 |
| 调度与编排 | 调度器控制 | `ROLE_ADMIN` | 暂停全部 / 恢复全部仅管理员 |
| 调度与编排 | 作业实例 | `ROLE_ADMIN` | 取消 / 终止等强操作仅管理员 |
| 调度与编排 | 工作流运行 | `ROLE_ADMIN` | 取消 / 终止 / 跳过节点仅管理员 |
| 调度与编排 | 作业运维 | `ROLE_ADMIN` / `ROLE_TENANT_USER` | `ROLE_TENANT_USER` 仅可触发作业；补偿、重跑、死信回放、Catch-Up 审批仅 `ROLE_ADMIN` |
| 调度与编排 | 资源队列 | `ROLE_ADMIN` | 新建 / 编辑 / 启停仅管理员 |
| 调度与编排 | 批次窗口 | `ROLE_ADMIN` | 新建 / 编辑 / 启停仅管理员 |
| 调度与编排 | 工作日历 | `ROLE_ADMIN` | 假日导入、编辑、删除仅管理员 |
| 调度与编排 | 配额策略 | `ROLE_ADMIN` | 仅管理员 |

### 5. 运维管理

| 模块 | 页面 | 可见角色 | 备注 |
|------|------|----------|------|
| 运维管理 | Worker 列表 | `ROLE_ADMIN` / `ROLE_TENANT_ADMIN` | Worker 运维入口 |
| 运维管理 | Worker 排空 / 下线 / 接管 | `ROLE_ADMIN` / `ROLE_TENANT_ADMIN` | 强操作页面 |
| 运维管理 | 运行中的 Worker 任务 | `ROLE_ADMIN` / `ROLE_TENANT_ADMIN` | 对应 `/api/console/workers/{workerCode}/claimed-tasks` |
| 运维管理 | 告警治理 | `ROLE_ADMIN` / `ROLE_TENANT_ADMIN` | 确认、静默、关闭告警 |
| 运维管理 | 配置发布单 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` | 查询可见；创建 / 发布 / 灰度 / 回滚建议仅 `ROLE_ADMIN` |
| 运维管理 | 配置审批 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` | 查看审批详情可见三类角色；提交审批 / 批准 / 拒绝仅 `ROLE_ADMIN`。对应 `/api/console/config/releases/{releaseId}/submit-approval`、`/approval`、`/approvals/{approvalId}/approve`、`/reject` |
| 运维管理 | 配置同步 | `ROLE_ADMIN` | 跨环境配置导出 / 预览 / 导入 + 同步日志。对应 `/api/console/config/sync/*` |
| 运维管理 | 密钥版本 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` | 查询可见；轮换仅 `ROLE_ADMIN` |
| 运维管理 | 配置变更日志 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` | 只读 |
| 运维管理 | 通知渠道管理 | `ROLE_ADMIN` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | 渠道 CRUD + 测试发送。对应 `/api/console/notifications/channels/*` |
| 运维管理 | 通知订阅规则 | `ROLE_ADMIN` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | 订阅规则 CRUD。对应 `/api/console/notifications/rules/*` |
| 运维管理 | 通知投递日志 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | 只读。对应 `/api/console/notifications/delivery-logs` |
| 运维管理 | AI 助手 | `ROLE_ADMIN` / `ROLE_AUDITOR` | 当前配置只允许这两个角色 |

### 6. 元数据与公共入口

| 模块 | 页面 | 可见角色 | 备注 |
|------|------|----------|------|
| 元数据与公共入口 | 枚举元数据 | `ROLE_ADMIN` / `ROLE_AUDITOR` / `ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER` | `/api/console/meta/*` |
| 元数据与公共入口 | 登录页 | 未登录可见 | `/console-login.html` |
| 元数据与公共入口 | 当前用户信息 | 已登录可见 | `/api/console/auth/me` |
| 元数据与公共入口 | 登录换取 Token | 未登录可见 | `/api/console/auth/login` |

### 7. 平台管理（仅 ROLE_ADMIN 可见）

> 本分组的所有入口只对 `ROLE_ADMIN` 展示。其他角色登录后不应看到此菜单。

#### 7.1 租户管理

| 页面 | 可见角色 | 对应接口 | 说明 |
|------|----------|----------|------|
| 租户列表 | `ROLE_ADMIN` / `ROLE_TENANT_ADMIN` | `GET /api/console/tenants` | 支持 keyword、status 过滤 |
| 租户详情 | `ROLE_ADMIN` / `ROLE_TENANT_ADMIN` | `GET /api/console/tenants/{tenantId}` | |
| 新建租户 | `ROLE_ADMIN` | `POST /api/console/tenants` | tenantId 格式受正则约束 |
| 编辑租户 | `ROLE_ADMIN` | `PUT /api/console/tenants/{tenantId}` | 仅 name/description 可改 |
| 暂停租户 | `ROLE_ADMIN` | `POST /api/console/tenants/{tenantId}/suspend` | |
| 激活租户 | `ROLE_ADMIN` | `POST /api/console/tenants/{tenantId}/activate` | |

租户是平台最顶层的隔离单元。**新租户必须先在此创建，再推送配置（tenant-init），最后创建账号。**  
`tenant-init` 的 `targetTenantIds` 不传时广播到所有 `ACTIVE` 租户。

#### 7.2 账号管理

| 页面 | 可见角色 | 对应接口 | 说明 |
|------|----------|----------|------|
| 账号列表 | `ROLE_ADMIN` | `GET /api/console/users` | 可按 tenantId / keyword 过滤 |
| 账号详情 | `ROLE_ADMIN` | `GET /api/console/users/{id}` | 不展示密码哈希 |
| 新建账号 | `ROLE_ADMIN` | `POST /api/console/users` | 密码 Argon2id 哈希；username 全局唯一 |
| 编辑账号 | `ROLE_ADMIN` | `PUT /api/console/users/{id}` | 仅 displayName / authoritiesCsv |
| 重置密码 | `ROLE_ADMIN` | `POST /api/console/users/{id}/reset-password` | |
| 启用账号 | `ROLE_ADMIN` | `POST /api/console/users/{id}/enable` | |
| 禁用账号 | `ROLE_ADMIN` | `POST /api/console/users/{id}/disable` | |
| 删除账号 | `ROLE_ADMIN` | `DELETE /api/console/users/{id}` | |

可用角色（authoritiesCsv 取值）：

| 值 | 说明 |
|----|------|
| `ROLE_ADMIN` | 平台超级管理员 |
| `ROLE_TENANT_ADMIN` | 配置与运维管理员 |
| `ROLE_AUDITOR` | 只读审计 |
| `ROLE_TENANT_USER` | 租户业务用户 |
| `ROLE_USER` | 默认最低权限 |

多角色用逗号分隔，例如 `ROLE_TENANT_ADMIN,ROLE_AUDITOR`。

#### 7.3 租户配置批量初始化

| 页面 | 可见角色 | 对应接口 | 说明 |
|------|----------|----------|------|
| 批量初始化 | `ROLE_ADMIN` | `POST /api/console/config/tenant-init` | 覆盖 10 类配置 |
| 配置复制 | `ROLE_ADMIN` | `POST /api/console/config/tenant-copy` | 源租户配置→目标租户 |

`tenant-init` 支持的 10 类配置（单次 API 调用全部推送）：

| 序号 | 配置类型 | 说明 |
|------|----------|------|
| 1 | 作业定义 | jobDefinitions |
| 2 | 工作流定义 | workflowDefinitions |
| 3 | 流水线定义 | pipelineDefinitions |
| 4 | 文件通道 | fileChannels |
| 5 | 文件模板 | fileTemplates |
| 6 | 资源队列 | resourceQueues |
| 7 | 批次窗口 | batchWindows |
| 8 | 工作日历 | businessCalendars（含假期列表） |
| 9 | 配额策略 | quotaPolicies |
| 10 | 告警路由 | alertRoutings |

新租户标准上线流程：
1. `POST /api/console/tenants` — 创建租户记录
2. `POST /api/console/config/tenant-init` — 一次性推送所有配置（或用 tenant-copy 从模板租户复制）
3. `POST /api/console/users` — 创建账号并分配角色

## 建议的前端侧边栏分组

如果前端要做固定 sidebar，建议直接按下面的分组落：

1. `首页总览`
2. `查询中心`
3. `定义管理`
4. `调度与编排`
5. `运维管理`
6. `元数据`
7. `平台管理`（仅 `ROLE_ADMIN` 可见）

## 菜单展示规则建议

- 前端只根据角色决定“是否展示菜单”，不要把角色当成最终安全判断。
- 有些页面是“可见但部分按钮不可用”，例如：
  - 作业定义、工作流定义、文件通道、文件模板
  - 配置发布单、密钥版本
  - 查询中心里的审计/日志/运行态详情
- 高危动作建议继续在按钮级别做二次收敛：
  - `ROLE_ADMIN` 负责删除、发布、回滚、终止、重跑、审批执行等
  - `ROLE_TENANT_ADMIN` 负责配置和运维类常规写操作
  - `ROLE_AUDITOR` 只读
  - `ROLE_TENANT_USER` 可查看状态和触发作业，不可修改配置或执行运维操作

