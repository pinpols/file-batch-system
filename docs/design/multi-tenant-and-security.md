# 多租户与安全设计

> 拆自 mega 设计文档 ch.15。已对照当前实现做实际落地标注（角色名、表名、控制面组件以代码为准）。

## 1. 多租户设计

目标不是"加个 `tenant_id` 字段"，而是从配置 / 执行 / 资源 / 运维四个视角明确隔离边界。

### 1.1 隔离边界

- 任务定义、任务实例、文件记录、审计记录**均带租户维度**
- 查询接口和控制台操作**默认按租户过滤**
- 资源配额、Worker Group、优先级**可按租户定义不同策略**
- 高权限运维角色**可跨租户查看，但必须保留审计轨迹**

### 1.2 隔离对象

- **任务**：`job_definition` / `job_instance` / `job_partition` / `job_task` 全部带 `tenant_id`
- **配置**：`file_channel_config` / `workflow_definition` / `pipeline_definition` / `tenant_quota_policy` 全部带 `tenant_id`
- **资源**：worker_group / 配额 / 限流 / 通知渠道按租户独立

### 1.3 当前实现

| 机制 | 位置 |
|---|---|
| 租户范围仓库 | `TenantSchedulerSnapshotRepository` / `TenantQuotaPolicyRepository` / `QuotaRuntimeStateRepository` |
| 跨租户角色判定 | `ConsoleRoles.hasGlobalRole(authorities)` |
| MyBatis SQL 强制租户过滤 | mapper XML 内 `where tenant_id = #{tenantId}` 显式注入 |

## 2. 角色与权限

### 2.1 实际角色（`batch-console-api/.../support/ConsoleRoles.java`）

| 角色常量 | 字符串 | 跨租户 | 说明 |
|---|---|---|---|
| `ADMIN` | `ROLE_ADMIN` | ✅ | 平台管理员，所有操作 |
| `AUDITOR` | `ROLE_AUDITOR` | ✅ | 只读审计 |
| `CONFIG_ADMIN` | `ROLE_CONFIG_ADMIN` | ✅ | 配置发布管理 |
| `TENANT_USER` | `ROLE_TENANT_USER` | ❌ | 单租户业务用户 |
| `USER` | `ROLE_USER` | ❌ | 基础只读 |

> `ConsoleRoles.GLOBAL_ROLES = {ADMIN, AUDITOR, CONFIG_ADMIN}` —— 这三类自动越租户读，但写入 / 操作仍需通过审批 + 审计。

### 2.2 权限粒度（设计目标）

- **任务定义**：读 / 写 / 上下线
- **调度控制**：触发 / 暂停 / 恢复 / 终止
- **补偿控制**：分片重试 / 实例重跑 / 文件重处理 / 死信重放
- **文件访问**：下载 / 重发 / 回执确认
- **系统管理**：限流修改 / 并发修改 / Worker drain / 密钥轮换

### 2.3 租户隔离实施

- 所有运行态查询**必须带 `tenant_id`**
- 控制台接口层**做租户注入**，禁止前端直接指定任意租户
- MyBatis SQL **统一通过参数或拦截器补充租户条件**
- 运维跨租户查询**仅限 GLOBAL_ROLES**，并强制审计
- 导出文件路径和对象前缀**带租户目录前缀**（如 `/{tenant}/outbound/...`）

## 3. 接口安全

- 控制台 API 用 Spring Security + JWT
- 高风险接口要求**二次校验或工单号**（实现：审批工单 + `dead_letter_task` 重放需 `approval_no`）
- 幂等写接口必须支持 `Idempotency-Key`
- 所有写接口记录 `requestId` / `operator` / `clientIp`
- 文件下载接口**不暴露真实对象存储永久凭证**——走预签名 URL 或代理下载

> 安全旁路总开关：`batch.security.bypass-mode`（CLAUDE.md §配置开关规范 + `coding-conventions.md` §21）。生产 profile 强制拒绝 `true`。

## 4. 对象存储访问策略

- MinIO 桶**默认私有**
- 下载采用**短时效预签名 URL**
- 服务端只保存对象名，不保存可永久访问外链
- 重要文件支持服务端二次鉴权 + 水印审计
- 文件删除**优先软删除**，再异步清理对象

> 桶生命周期与清理：[`../runbook/minio-lifecycle-policy.md`](../runbook/minio-lifecycle-policy.md)。

## 5. 密钥与凭证轮换

> ✅ **已实现**：`batch.secret_version` 表（V22 migration）

### 5.1 设计原则

- 凭证**不得明文入库**；持久化必须用密钥系统、配置中心加密字段或外部 Secret 管理
- 所有凭证支持 `secret_version`
- 支持**轮换窗口**：新旧凭证并存一段时间
- 支持**老密钥回收前兼容期**
- 渠道适配器**按版本号选择有效凭证**
- 控制台修改凭证**仅允许录入新版本**，不直接覆盖历史
- 轮换事件**必须审计**，并记录影响范围与回滚方式

### 5.2 数据模型（`batch.secret_version`）

| 字段 | 用途 |
|---|---|
| `secret_ref` | 引用名（应用代码用这个查） |
| `secret_name` | 显示名 |
| `version_no` | 版本号（同 ref 内单调递增） |
| `secret_status` | `DRAFT` / `PUBLISHED` / `GRAY` / `ROLLED_BACK` |
| `current_version` | 当前生效版本（同 ref 仅一行 true） |
| `rotation_window_start_at` / `rotation_window_end_at` | 轮换窗口 |
| `effective_from_at` / `effective_to_at` | 兼容期 |
| `secret_payload` | JSONB（应用层进一步加密） |
| `rotation_reason` | 轮换原因审计 |

### 5.3 应用层助手

- `SecretMasking`（`batch-common/.../utils/SecretMasking.java`）—— 日志输出脱敏

## 6. 审计与审批

### 6.1 必须进入审计 + 审批的动作

- 修改 cron / 修改并发限制
- **手工触发生产任务**
- **批量补跑** / **死信重放**
- **文件重新分发**
- **强制终止运行实例**
- **变更对象存储桶策略**

### 6.2 当前实现

审计按域分表（**没有**单一 `audit_log` 通用表）：

| 表 | 用途 | Migration |
|---|---|---|
| `file_audit_log` | 文件流转审计（INBOUND / OUTBOUND / 重发 / 删除） | V6 |
| `job_execution_log` | 任务执行轨迹审计 | V7 |
| `console_ai_audit_log` | AI 控制面请求 / 响应审计 | V11 |
| `config_change_log` | 配置发布 / 灰度 / 回滚审计 | V22 |
| `event_outbox_log` | Outbox 投递追踪 | V21 |

审批：`config_release` 表的 `DRAFT → PUBLISHED → GRAY → ROLLED_BACK` 流转（V22 migration），`config_change_log` 表全程审计。

## 7. 安全基线

- 强制 HTTPS
- 管理员账号开启 MFA
- 重要接口加限流（`TenantActionRateLimiter`：`BATCH_RATE_LIMIT_*` env）
- 关键审计日志**异地保留**
- 定期做权限回收 / 僵尸账号清理

## 8. 文件数据隔离与内容安全

系统处理上游导入、下游导出、中间处理、回执文件时遵循**方向隔离 + 租户隔离 + 业务隔离 + 权限隔离**原则。**禁止**未经授权的人员、渠道、租户或 AI 助手访问文件明文内容。

### 8.1 隔离维度

- **文件方向**：`INBOUND / OUTBOUND / INTERNAL / ACK`
- **租户**：`tenant_id`
- **业务域**：`biz_type`
- **来源系统**：`source_system`
- **目标系统**：`target_system`
- **安全等级**：`security_level`

### 8.2 存储隔离要求

- 上游导入 / 下游导出 / 中间临时 / 错误 / 回执文件用**不同对象前缀或目录前缀**
- 对象 Key 包含**租户 / 业务 / 方向 / 业务日期 / 批次**等关键维度，避免不同租户和不同方向文件混存
- 原始文件、处理中间文件、归档文件**不得使用同一路径空间覆盖写入**
- 临时文件目录**设置生命周期并自动清理**，禁止长期保留处理中间明文

### 8.3 元数据与内容分级

- **元数据**（文件号、状态、大小、业务日期、哈希、来源/目标）→ 调度、监控、审计可读
- **文件内容、错误行明细、导出明细、原始载荷**→ 默认受限
- 控制台**区分**「元数据查看」「内容预览」「文件下载」「重分发」不同权限
- 默认情况下，控制台列表与 AI 助手**仅使用元数据和脱敏摘要**，不直接返回文件原文

## 9. 脱敏与加密策略（配置化，**默认关闭**）

仅在租户、业务域、模板或渠道明确要求时开启。

### 9.1 配置项

| 键 | 默认 | 用途 |
|---|---|---|
| `preview_masking_enabled` | `NO` | 预览脱敏 |
| `error_line_masking_enabled` | `NO` | 错误行脱敏 |
| `log_masking_enabled` | `NO` | 明细日志脱敏 |
| `content_encryption_enabled` | `NO` | 文件内容加密 |
| `download_requires_approval` | `NO` | 下载审批 |
| `masking_rule_set` | — | 脱敏规则集编码 |
| `encryption_mode` | — | `OBJECT_STORAGE_SSE` / `APP_LEVEL` |
| `encryption_key_ref` | — | 密钥引用（指向 `secret_version`） |

### 9.2 运行规则

1. 默认不开内容级脱敏 / 加密
2. 高敏文件 / 合规要求启用时，对应开关**显式设 `YES`**
3. 即使业务模板未开内容脱敏，**日志基线**仍不得直接打印完整文件明文 / 完整错误行明文 / 完整导出结果明文
4. 预览脱敏与下载控制**独立配置**，允许"可脱敏预览、不可直接下载"
5. 内容加密启用后，**同步管理密钥版本号、轮换窗口、兼容期**

## 10. 日志排查与安全平衡

安全加固不能以牺牲可排障性为代价。靠"**元数据定位 + 受控明文访问**"实现平衡。

### 10.1 日志基线

- 默认记录 `tenantId / jobId / taskId / pipelineInstanceId / fileId / rowNo / errorCode / checksum`
- 禁止直接打印完整文件内容、整行明细、完整下游请求报文
- 错误详情优先写**受控错误表 / 错误文件 / 审计附件**，而不是直接写应用日志

### 10.2 受控调试

- 短时调试开关，仅针对指定租户 / 指定批次 / 指定文件生效
- **Break-Glass 紧急查看**：高权限申请 + 短时授权 + 全量审计 + 超时自动回收
- 默认提供脱敏预览；原始文件下载和明文查看需独立授权或审批
- AI 助手只消费脱敏后的日志、元数据、统计信息，**不接触原始上下游文件明文**

### 10.3 对象存储访问

- 应用通过服务端身份访问对象存储，**普通用户不直接持有桶级访问权限**
- 文件下载通过**短时效预签名链接或平台代理下载**实现
- 预签名链接**最小化权限和时效**，禁止生成长期有效的公开下载地址

## 11. AI 输入脱敏与权限边界

AI 仅作为 Console 控制面辅助能力，遵循「**先鉴权 → 再裁剪 → 后脱敏 → 最后出域**」顺序。

### 11.1 输入边界

- AI 输入源**仅限**控制台可见的元数据 / 脱敏日志 / 统计指标 / 配置草稿 / 错误摘要
- 原始上下游文件、完整错误行、完整请求 / 响应报文**默认不得**直接送入模型
- 如需让 AI 参考样例，**只允许脱敏样本 / 截断样本 / 人工审核后的授权副本**
- AI 输入前必须执行**租户校验、资源权限校验、字段级脱敏**
- AI **不得跨租户拼接**上下文（A 租户的日志不能送 B 租户问答）

### 11.2 输出边界

- AI 输出**仅允许**：建议 / 草稿 / 风险提示 / 审查结论 / 参数优化建议
- AI 输出**不得**：直接落库覆盖生产配置、直接执行补偿 / 重发 / 重跑 / 强制完成等高风险动作
- AI 输出包含可能暴露敏感信息字段时，**经过二次脱敏与审计落盘**
- 人工确认动作**与 AI 输出绑定审计**，记录"谁采纳、采纳了什么、何时生效"

### 11.3 当前实现

| 控制面组件 | 位置 |
|---|---|
| `ConsoleAiPromptGuard` | `batch-console-api/.../service/ConsoleAiPromptGuard.java`（关键字阻断 + 长度限制 + 域分类） |
| `ConsoleAiAuthorizationService` | `batch-console-api/.../service/ConsoleAiAuthorizationService.java` |
| `DefaultConsoleAiAuditService` | `batch-console-api/.../infrastructure/DefaultConsoleAiAuditService.java`（写 `console_ai_audit_log` 表，V11） |
| `AiPromptGateResult` | `batch-console-api/.../support/AiPromptGateResult.java` |
| 阻断策略枚举 | `AiPromptDecision`（`APPROVED` / `REJECTED_DISABLED` / `REJECTED_SAFETY` / `REJECTED_SCOPE`） |
| 域分类枚举 | `AiPromptCategory`（`PLATFORM` / `WORKFLOW` / `FILE_GOVERNANCE` / `OPERATIONS` / `OUT_OF_SCOPE`） |

## 12. 配置发布、灰度与回滚治理

> ✅ **已实现**：`batch.config_release` + `batch.config_change_log` 表（V22 migration）

定义态配置（`job_definition` / `workflow_definition` / `pipeline_definition` / `file_template_config`）**不应**在控制台直接修改后立即影响生产。须纳入发布治理。

### 12.1 配置生命周期

`DRAFT` → `PUBLISHED` → `GRAY` → `ROLLED_BACK`（→ `ARCHIVED`）

### 12.2 关键字段

| 字段 | 用途 |
|---|---|
| `version_no` | 版本号（单调递增） |
| `config_status` | 生命周期状态 |
| `effective_from_at` / `effective_to_at` | 生效窗口 |
| `gray_scope` | JSONB（按租户 / Worker 组 / 流量比例的灰度范围） |
| `published_at` / `created_by` / `updated_by` | 审计字段 |
| `rolled_back_at` | 回滚时间戳 |

### 12.3 运行规则

1. 草稿版可自由编辑，**不直接生效**
2. 发布时**生成新版本，旧版本保留**
3. **灰度版可按租户 / Worker 组 / 任务组 / 流量比例**逐步生效
4. **已创建实例继续绑定创建当时版本**，不被在线配置修改"穿透"
5. 支持**回滚到上一发布版本**，并保留版本 diff 与审计记录
6. 高风险配置变更（cron / 路由 / 并发 / 模板 / SQL）**必须进入审批与审计**

## 相关文档

- [`../architecture/architecture-truth.md`](../architecture/architecture-truth.md) — 架构基线
- [`../architecture/adr/ADR-007-dual-datasource.md`](../architecture/adr/ADR-007-dual-datasource.md) — 单 PG 双 schema 隔离
- [`../runbook/minio-lifecycle-policy.md`](../runbook/minio-lifecycle-policy.md) — MinIO 桶生命周期
- [`../runbook/security-scan.md`](../runbook/security-scan.md) — 安全扫描 SOP
- [`../runbook/feature-switches.md`](../runbook/feature-switches.md) — 安全旁路 / 限流 / 配额相关开关
- [`../coding-conventions.md`](../coding-conventions.md) §21 — `batch.security.bypass-mode` 规范
- [data-model-ddl.md](./data-model-ddl.md) — 含 `secret_version` / `config_release` / `config_change_log` 等表 DDL
