# ADR-032 控制台 4 角色 RBAC 重设计

- **状态**:Accepted
- **日期**:2026-05-21
- **影响**:`batch-console-api` 权限模型 / 数据库现存账号 / 前端权限指令

## 背景

历史角色矩阵(5 档,平铺):

| 角色 | 作用域 | 现状 |
|---|---|---|
| `ROLE_ADMIN` | 全局 | 平台超级管理员 |
| `ROLE_CONFIG_ADMIN` | 全局 | "配置管理员",权限层级介于 ADMIN 与 USER 之间,无显式租户范围 |
| `ROLE_AUDITOR` | 全局 | 全局只读 |
| `ROLE_TENANT_USER` | 本租户 | 普通业务员 |
| `ROLE_USER` | 本租户 | 历史兼容,语义同 TENANT_USER |

问题:

1. **缺位「租户管理员」**:`CONFIG_ADMIN` 跨租户,无法在「每租户 1-3 个管理员自管员工」场景下使用 —— 平台方被迫管理所有账号
2. **菜单/权限对齐困难**:CONFIG_ADMIN 既不是 ADMIN 也不是 TENANT_USER,5 角色 × N 端点的 `@PreAuthorize` 表达式难维护
3. **量级失衡**:500 租户场景下,平台方需手工维护 ~1500 个 TENANT_ADMIN 账号(每租户 1-3 个),不可持续

## 决策

**新模型**:4 角色,平台 vs 租户 × 写 vs 读 二维矩阵。

|  | 写权限 | 只读权限 |
|---|---|---|
| **平台级**(跨租户) | `ROLE_ADMIN` | `ROLE_AUDITOR` |
| **租户级**(本租户) | `ROLE_TENANT_ADMIN` | `ROLE_TENANT_USER` |

`ROLE_USER` 保留为向后兼容常量,语义等同 `TENANT_USER`,新代码禁用。
`ROLE_CONFIG_ADMIN` 合并升级为 `ROLE_ADMIN`(V149 迁移)。

### 角色能力

- **ROLE_ADMIN** (3-5 人,平台方固定):一切操作;租户 CRUD、跨租户审计、维护模式开关、强 reset Outbox。
- **ROLE_AUDITOR** (1-2 人,合规):跨租户**只读**,任何写操作 403。
- **ROLE_TENANT_ADMIN** (每租户 1-3 人,租户自管):**仅本租户内**加减员工(只能授 TENANT_ADMIN/TENANT_USER)、改密码、Job/Pipeline/Workflow/调度窗口/文件模板/Webhook 配置;不能跨租户、不能升 ADMIN、不能改自己租户的配额硬上限。
- **ROLE_TENANT_USER** (每租户 5-50 人):**本租户内**只读 + 触发执行 + 提审批申请;不改配置、不加账号。

### 跨租户拒绝守卫层级

1. `@PreAuthorize` 类级粗筛(`hasAnyAuthority(ADMIN, TENANT_ADMIN, ...)`)
2. **Service 层强制注入** `tenantId`(`ConsoleUserAccountService` 等):非全局角色调用 `list / create / update` 一律忽略请求中的 `tenantId`,强制使用 `principal.tenantId`
3. Service 层对**目标账号 `tenantId`** 校验:跨租户操作 → `error.account.cross_tenant_denied` (403)
4. Service 层对 `authoritiesCsv` 校验:TENANT_ADMIN 试图授予 ADMIN/AUDITOR → `error.account.role_grant_denied` (403)

四层独立,任一漏掉都不致命。

### 菜单注册表

`ConsoleMenuRegistry` 维持 3 档显示等级 (`VIEWER < TENANT_ADMIN < ADMIN`),`resolveRole` 由 `authorities` 推导:

- `ROLE_ADMIN` → `ADMIN`
- `ROLE_TENANT_ADMIN` → `TENANT_ADMIN`
- `ROLE_AUDITOR` / `ROLE_TENANT_USER` / `ROLE_USER` → `VIEWER`

调整:`/governance/queues` 从 `ADMIN` 下放为 `TENANT_ADMIN`(队列/窗口/日历是租户级配置 CRUD);`/ops/diagnostic` 保持 `ADMIN`(有 outbox cleanup / republish 等破坏性副作用)。

## 范围边界

✅ 做:控制台 console-api 权限模型重设计;现存账号 V149 自动升级;前端权限指令同步;Service 层守卫单测全覆盖。

❌ 不做:
- 不引入第三方 IAM(Keycloak / Casdoor 等),保持自研 JWT
- 不做 ABAC / 资源级 ACL(`@PreAuthorize` 上 SpEL 表达式上限是 `tenant + role`,不引入 resource-id 维度)
- 不做委派/授权链(TENANT_ADMIN 不能"临时充当"其他租户 TENANT_ADMIN)
- 不做超级管理员 IP 白名单等网络层管控(那是 WAF / 基础设施层职责)

## 实施

| 模块 | 落地点 |
|---|---|
| BE 常量 | `ConsoleRoles.java` 加 `TENANT_ADMIN`,`hasGlobalRole` 收敛为 ADMIN+AUDITOR |
| BE 菜单 | `ConsoleMenuRegistry.java` `resolveRole` 切到 `ROLE_TENANT_ADMIN` |
| BE @PreAuthorize | 36 个 controller / config:`ROLE_CONFIG_ADMIN` → `ROLE_TENANT_ADMIN` |
| BE Service 守卫 | `ConsoleUserAccountService` 4 层守卫:`enforceTenantScope` / `assertSameTenantOrGlobal` / `enforceGrantableAuthorities` / `normalizeAuthorities` |
| DB 迁移 | `V149__role_redesign_config_admin_upgrade.sql`:`ROLE_CONFIG_ADMIN` → `ROLE_ADMIN` |
| i18n | `error.account.cross_tenant_denied` / `error.account.role_grant_denied` (zh/en) |
| FE | `tenantAccess.ts` `canSwitchTenant` 收敛为 ADMIN+AUDITOR;`stores/auth.ts` `isTenantUser` 包括 TENANT_ADMIN;`UserAccountList.vue` ROLE_OPTIONS 按 `isPlatformAdmin` 过滤 |
| 测试 | `ConsoleUserAccountServiceTest` 11 测试覆盖 4 层守卫;`tenantAccess.test.ts` 更新 |

## 风险

- **既有 CONFIG_ADMIN 账号自动升 ADMIN**:权限被放大;迁移备注「历史 CONFIG_ADMIN 是全局信任,与新 ADMIN 等价」,但运维需在 V149 上线后审计 CONFIG_ADMIN 历史登录列表,确认是否真的需要 ADMIN 权限,不需要则手工降级为 TENANT_ADMIN。
- **TENANT_ADMIN 自服务首次发放**:平台方仍需为每个租户发放第一个 TENANT_ADMIN 账号(ADMIN 操作),发放后该 TENANT_ADMIN 自行派生 TENANT_USER。第一发放渠道未自动化,沿用 `POST /api/console/users` 手工流程。
