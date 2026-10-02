# 批量账号开户后端设计

> 状态：前后端已编码；数据库迁移、真实服务联测与生产审计验收尚未完成。优先级和实施状态以 [当前待办](../analysis/todo-master.md#批量账号开户bulk-user-provisioning) 为准。
> 适用范围：Console 给已有租户批量创建登录账户；不改变批量建租户接口的语义。

## 目标与现状

平台管理员需要为多个租户开通账户，租户管理员需要为本租户开通员工账户。当前 `ConsoleUserAccountController` 只有单条 `POST /api/console/users`，`ConsoleUserAccountService` 负责租户和角色守卫；`POST /api/console/tenants/batch` 创建的是租户及其初始账户。前端已有 `batch-console/docs/backlog/bulk-user-provisioning.md` 待办，正式接口仍缺失。

本设计沿用 [ADR-032](../architecture/adr/ADR-032-four-role-rbac-redesign.md) 的四角色模型和现有账号表。账户仍归 Console/RBAC 域，不接入作业调度、Worker 或 11-sheet 租户配置包。

## 范围与约束

| 项目 | 第一版约束 |
|---|---|
| 调用者 | `ROLE_ADMIN` 可跨租户开户；`ROLE_TENANT_ADMIN` 只能给本租户开户；其余角色无权调用 |
| 账号角色 | 每行指定四个正式角色之一；平台角色 `ROLE_ADMIN`、`ROLE_AUDITOR` 固定归属 `system`，只能由平台管理员授予 |
| 租户角色 | `ROLE_TENANT_ADMIN`、`ROLE_TENANT_USER` 必须绑定真实且允许开户的业务租户；租户管理员提交的 `tenantId` 由服务端固定为其登录租户 |
| 大小 | 首版最多 500 条非空数据行（无论校验结果）；超过上限直接拒绝，不排后台任务 |
| 文件 | 优先 XLSX；若增加 CSV，沿用相同字段、编码约束和校验结果契约；文件不包含密码 |
| 写入 | 一个 Apply 请求对应一个数据库事务；任一行失败时整批不创建账户 |
| 删除 | 不提供批量物理删除，沿用账号停用与租户暂停机制 |

模板最小字段为 `tenantId`、`username`、`displayName`、`role`。平台角色的 `tenantId` 必须为 `system`；租户管理员的模板可留空 `tenantId`，服务端用其 principal 租户覆盖。模板不能包含密码、密码哈希、`enabled` 或审计字段。首版角色按单值处理，不在 Excel 中支持权限组合。

## 接口与状态

正式路径与 DTO 已写入 `docs/api/console-api.openapi.yaml`；下表是当前实现。

| 方法与路径 | 语义 |
|---|---|
| `GET /api/console/users/batch/template` | 下载无密码字段的模板 |
| `POST /api/console/users/batch/preview` | 上传、解析、校验并返回行级问题和 `previewToken`；不创建账户 |
| `POST /api/console/users/batch/preview/{previewToken}/patch` | 逐行修正；修正后重新校验并增加预览版本 |
| `POST /api/console/users/batch/apply/{previewToken}` | 以预览版本确认提交，返回 `operationId`、计数和创建结果 |
| `GET /api/console/users/batch/operations/{operationId}` | 查询已提交批次的非敏感结果，供超时后的确认和运维审计 |
| `GET /api/console/users/batch/operations?requestId=...` | 响应丢失时以提交前生成的 requestId 查询本人批次摘要；无记录返回 null |

预览结果包含文件摘要、总行数、有效/错误行数以及 `{rowNo, username, tenantId, role, errorCode, message}`。Apply 请求带预览版本和 `Idempotency-Key`。版本不符、令牌过期或操作者变化时拒绝提交。不能由前端循环调用单账号接口来模拟 Apply。

预览会话存于 Redis，TTL 15 分钟，绑定操作者和源文件摘要；每次校验和 Apply 都按当前 principal 重新计算租户范围与角色权限。只存校验字段，不存密码或原始文件。Redis 不可用时预览失败，不静默退化为单机内存。

## 校验与事务

1. 解析文件时限制大小、行数、列数和单元格长度；拒绝公式、外部链接和非预期工作表。记录原始行号，空行不得改变报错定位。
2. 将单条创建和批量创建的用户名、租户状态、角色授予与字段校验归到同一账户开户服务。Controller 只负责鉴权、请求绑定和响应，不复制业务规则。
3. 批次内以 `lower(username)` 检查重复，再与存量账户做大小写不敏感冲突预检。检查租户真实存在且状态允许开户，平台角色不得绑业务租户。
4. Apply 在事务中重新校验 principal、租户状态、角色权限、用户名冲突和预览版本；不把 Preview 结果当成提交时仍成立的事实。
5. 现有 V41 唯一约束是 `UNIQUE (username)`，而账号查询按 `lower(username)` 匹配。V220 新增 `lower(username)` 唯一索引。部署前必须运行 `SELECT lower(username), count(*) FROM batch.console_user_account GROUP BY lower(username) HAVING count(*) > 1`，历史冲突需人工处置；迁移在冲突时失败，不自动删除账号。并发时以索引为最终裁决，整批回滚。
6. 批次成功时在同一事务持久化不含凭据的操作摘要，关联 `batchOperationId`、`requestId`、操作者、文件摘要和租户集合。逐行校验问题仅由 Preview 返回；成功批次的每行凭据仅在 Apply 响应中返回，不在批次查询中重放。失败时由现有审计切面记录失败，不伪报部分成功。

这里的“逐行结果”主要用于 Preview 定位错误。Apply 是全批原子操作，结果只能是全部创建或全部未创建；前端待办中的“部分失败/失败项重试”应按这一契约改为修正后重新预览、重新提交。若日后需要部分成功，另行评估事务、结果保留和补偿语义。

## 幂等、审计与凭据

现有 `ConsoleIdempotencyInterceptor` 对相同完成态请求返回冲突，不保存成功响应体。因此批量 Apply 除要求 `Idempotency-Key` 外，在持久化批次记录中保存操作者、目标租户集合、文件摘要和请求 ID；预览版本仅在提交时校验，不写入批次记录。当前保留 409 语义，客户端通过请求 ID 查询批次摘要确认结果，不能重放取回一次性凭据。

`@AuditAction` 应使用独立动作名 `user.batchCreate`，记录 `batchOperationId`、操作者、目标租户和行数。跨租户批次的审计须能逐租户检索；不能只把所有目标归在 `system`。上传、Preview 和 Apply 的参数及响应日志均不得包含原始文件或密码；包含凭据的入口显式 `recordParams=false`，审计只写脱敏摘要。

当前选择：服务端为每个账户生成独立随机初始密码，只保存 Argon2id 哈希，设 `must_change_password=true`，仅在 Apply 成功响应中交付给当前操作者；文件、Redis 预览和可重复查询的批次记录都不保存明文。一次性响应丢失时只能走管理员逐账号重置流程。当前 `must_change_password` 是非阻断提醒，界面明确说明并未强制改密。生产启用前还需确认企业允许管理员一次性查看初始凭据；若要求邀请或强制改密，需另立安全需求。

页面关闭即清除前端内存中的凭据；网络超时但事务成功时通过 requestId 查询非敏感批次，随后人工重置密码。无明文恢复、批量重置或邀请渠道。Apply 生产开放仍取决于真实 PG/Redis 联测、安全评审及运维交付确认。

## 已实现与待验收

- 已编码：XLSX 模板与上传、最多 500 行预览、逐行修正、大小写重复校验、租户/角色守卫、Redis 共享会话、原子 Apply、V220 唯一索引与操作表、一次性凭据响应、前端弹窗及中英文文案。
- 已执行：后端定向单测、前端类型检查、API 单测、隔离 API 桩的 Playwright 页面 E2E（含桌面/390px 预览与结果态）和静态检查；不代替真实链路验收。
- 待验收：真实 PG 迁移及并发冲突回滚、Redis 多副本与会话过期、前台真实上传/修正/确认/丢包恢复、跨租户和多用户冲突、密码安全交付。平台管理员跨租批次当前只写一条总操作审计，`tenant_ids` 在批次表中；**按每个目标租户分别检索审计尚未实现**，生产放量前需补齐。

## 交付步骤与验收

1. 确定凭据交付方式、批次结果保留期和大小写冲突数据处置；评审数据库前向迁移。
2. 提取单条与批量共用的开户规则，落地共享 Preview 会话、Apply 事务、批次查询、审计和 OpenAPI 契约。
3. 同步配对前端的账户页面、生成类型、文案和测试，按后端全批原子语义更新其批量开户待办。
4. 运行单测与真实 PostgreSQL 集成测试：跨租户拒绝、平台角色越授、无效/暂停租户、批次内重复、存量大小写冲突、并发 Apply、事务回滚、同键重放、超时后查询、审计脱敏。
5. 前后端联测模板下载、Preview 修正、Apply、一次性凭据交付和失败后重新预览；用不同租户管理员验证数据不可串租。

本设计不要求新建通用 IAM、SCIM/SSO、邮件或短信邀请系统，也不把账户导入纳入批量任务主链。
