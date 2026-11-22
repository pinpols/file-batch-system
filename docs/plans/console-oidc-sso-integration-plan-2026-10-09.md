# Console OIDC SSO 接入方案

状态：阶段 1 后端闭环及阶段 2 前端 pilot 登录入口已实现。2026-10-09 修正了 Vite dev/preview 的 OIDC 代理 Host 保留行为，避免后端重定向落到 API 端口；新增登录入口与 callback 重定向回归用例。OIDC API 单测、前端 ESLint 通过；使用系统 Chrome 验证启用态入口可见、callback 错误重定向留在前端 origin。标准 Playwright runner 因缺少 Playwright Chromium 未能启动；当前本地后端 OIDC 关闭，真实 IdP 登录及生产密钥、多副本运行验收仍未完成。

## 1. 结论

采用 **OIDC Authorization Code Flow** 作为首个企业 SSO 协议，优先使用 Spring Security OAuth2 Client 的协议实现，不自研 OIDC/JWT 验签。先支持单个试点租户、单个静态 OIDC registration；试点验收后再决定是否扩展为多租户动态 registration。IdP 负责认证，BFS 负责租户内账号映射、角色授权和本地会话；认证成功后仍由 BFS 签发现有 HttpOnly JWT Cookie。

一期限定：

- 仅支持 OIDC，不实现 SAML、SCIM、通用 IAM 或身份目录同步。
- 仅允许显式绑定到已存在的 `console_user_account`；不按邮箱自动认领账号，不自动创建账号或授予角色。
- issuer、client id、registration id、tenant id 和 callback URL 由受控部署配置维护；client secret 由 Console API 专属 Secret 注入，不进入数据库、共享 Secret、ConfigMap、Git 或 API。配置变更需滚动重启。
- 保留现有账号密码登录作为迁移和应急入口；可按租户策略逐步限制，但不得没有可审计的 break-glass 管理账号。

## 2. 当前基线

- Console 当前使用本地用户名/密码登录，验证通过后由 `ConsoleJwtService` 签发平台 JWT，通过 HttpOnly Cookie 认证后续请求。
- `ConsoleSecurityConfiguration` 使用无状态会话；CSRF、角色授权、租户解析和单账号单会话均属于现有平台会话链。
- `console_user_account.username` 当前全局唯一，账号持有 tenant 和 authorities；表中没有 IdP subject 字段。
- `single-session-enabled` 表示同账号后登录使旧平台会话失效，不是企业 SSO；与 IdP 登录独立。

权威实现：[`ConsoleLoginService`](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleLoginService.java)、[`ConsoleJwtService`](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleJwtService.java)、[`ConsoleSecurityConfiguration`](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ConsoleSecurityConfiguration.java)、[`V34`](../../db/migration/V34__create_console_user_account.sql)、[`V41`](../../db/migration/V41__console_user_account_unique_username.sql)。

## 3. 目标认证流程

```text
用户进入试点 Console 并点击“企业登录”
  -> BFS 使用唯一静态 pilot registration 和固定 tenant 配置
  -> BFS 创建一次性 state、nonce、PKCE verifier 和随机浏览器绑定 Cookie，并保存短时授权请求
  -> 浏览器跳转至 pilot IdP
  -> IdP 完成认证并回调 BFS 固定 callback
  -> BFS 校验 state 与发起浏览器 Cookie 的绑定，再校验 nonce、issuer、audience、签名、有效期和授权码交换结果
  -> 用 (tenant_id, issuer, subject) 查找已绑定的本地账号
  -> 检查账号 enabled 与本地角色；不从未经映射的 IdP claim 提权
  -> BFS 签发现有平台 JWT Cookie，后续请求继续走现有认证、授权、租户守卫
```

一期没有租户选择器或动态租户解析：登录入口只指向唯一静态 pilot registration，租户由服务端部署配置固定。不得从用户可控 `tenantId`、未验证 ID Token claim 或 email domain 决定租户。未来增加多租户入口时，必须先建立可信域名/slug 到 registration 的服务端映射，再单独评审。

## 4. 身份、账号与配置模型

### 4.1 外部身份绑定

增加独立的外部身份映射，不把 OIDC 专有字段塞入 `console_user_account`：

| 字段 | 语义 |
|---|---|
| `tenant_id` | 归属租户，由受信任的登录入口解析 |
| `account_id` | 现有本地 `console_user_account.id` |
| `issuer` | 精确校验后的 OIDC `iss` |
| `subject` | OIDC 稳定主体标识 `sub`；保存 IdP 返回的精确 ASCII 值（最多 255 字符），不使用可变的 username/email 作主键 |
| `created_at` / `created_by` | 绑定审计信息 |

唯一性按 `(tenant_id, issuer, subject)` 建立，以允许同一外部主体在不同租户拥有不同本地权限；一期还应约束 `(tenant_id, account_id)` 唯一，避免同一租户本地账号被多个外部 subject 绑定。映射必须约束本地账号 tenant 与映射 tenant 一致；迁移以复合外键保证，而不能只依赖 controller 传参。该表属于 `batch` 控制面，不加入 `biz` 业务表 RLS 闭世界清单。

一期由全局管理员通过受审计 API 将 IdP `subject` 显式绑定到已存在的本地账号，并可按租户查看/解绑。禁止仅凭相同邮箱自动绑定，禁止 IdP 返回角色直接覆盖本地 authorities。后续若改为用户自助绑定，必须新增本地强认证/近期再认证与 CSRF 防护设计。

### 4.2 OIDC 客户端配置与密钥

- 单个静态 OIDC registration 配置包含 issuer、client id、精确 redirect URI、registration id 与一个试点 tenant id。
- `BATCH_CONSOLE_SSO_OIDC_ENABLED` 默认关闭；关闭时不执行 IdP discovery，SSO 发起/回调不签发平台会话，不影响现有本地登录。
- pilot client secret 只从 Console API 专属 Kubernetes Secret / Compose Console 服务环境注入；禁止写入数据库、共享 Secret、ConfigMap、Git、日志或 API 响应。不引入新的配置中心或 Secret adapter。
- issuer 必须 HTTPS；loopback HTTP 需显式配置且只允许在 `local`/`test` profile。启动时验证 discovery metadata、issuer 一致性和固定 callback 路径。
- 配置变更、启停和 secret 轮换通过部署变更审计，并滚动重启生效；一期不承诺运行时热更新。

## 5. 关键安全与运行边界

1. **协议安全**：Authorization Code + PKCE；逐次随机且一次性消费的 state/nonce；state 必须同时匹配发起登录浏览器持有的短期 HttpOnly、SameSite=Lax Cookie；严格校验 issuer、audience、签名算法、JWKS、`exp`/`iat`/`nbf`；拒绝 open redirect 和任意 callback URL。
2. **无状态应用会话兼容**：当前 API 使用 `SessionCreationPolicy.STATELESS`。OIDC 授权请求不能依赖普通 HttpSession；需实现短 TTL、一次性、跨副本共享的授权请求存储（优先 Redis，并限制 key、TTL、大小和读取次数），保证多副本 callback 可完成。
3. **Cookie/CSRF**：成功 callback 后复用现有 Cookie 属性、JWT 签发、session version 与安全响应；仅对固定 OAuth callback 使用框架要求的安全例外，不全局关闭 CSRF。回调不得把 access token 放入 URL 或前端可读存储。
4. **权限**：OIDC 仅证明身份，不自动授予 BFS 角色。RBAC 仍由本地账号决定；一期拒绝未绑定身份。租户解析失败、账号停用、身份冲突和不支持的 claim 均 fail closed。
5. **退出和 IdP 故障**：本地 logout 必须立即撤销 BFS 会话；不承诺一期实现跨 IdP 的全局 Single Logout。IdP 暂时不可用时，已签发平台 JWT 按现有 TTL/撤销策略运行；新登录失败不得降级为未经验证的身份。应急本地账号须受强认证、告警和审计保护。
6. **限流与隐私**：复用登录 IP 限流和失败防护；审计记录 tenant、平台用户名、结果和 trace/request ID，不记录 subject、authorization code、ID/access/refresh token、完整 claim 或 secret。
7. **会话策略**：现有“单账号单会话”保持独立开关和原语义，不将其描述为 SSO，也不因接入 OIDC 自动改变。

## 6. 分阶段实施

### 阶段 0：需求与威胁模型确认

- 选定首个试点租户和 IdP，确认 issuer、回调域、用户标识和运维联系人。
- 一期入口固定到唯一 pilot registration；不提供租户选择器、租户 slug 路由或 email-domain 自动发现。
- 确认账号采用本地预置绑定、应急本地登录保留策略、IdP 下线后的访问窗口和审计保留要求。
- 确认当前 secret manager/secret_version 能否安全承载按租户解析的 OIDC client secret。

**出口条件**：生产启用前，安全评审批准租户识别、账号绑定、角色来源和 break-glass 策略。当前开发只实现单租户、管理员预绑定和默认关闭的本地闭环。

### 阶段 1：后端 OIDC 最小闭环（本地实现完成）

- 接入 Spring Security OAuth2 Client；支持受控部署配置中的单个 pilot registration，不支持动态/数据库 registration。
- 实现短时共享授权请求存储、浏览器绑定的登录发起与 callback 校验、外部身份绑定查询和现有平台 JWT Cookie 签发。
- 加映射表迁移、租户一致性约束、审计及启停配置守卫；映射表属于 `batch` 控制面，不属于 `biz` 业务 RLS 闭世界范围；不修改现有账号密码登录语义。
- 为新增的 pre-login 查询/发起端点更新后端 OpenAPI，并与配对 `batch-console` 的生成类型及调用方同步；OAuth callback 本身按浏览器重定向协议验证，不暴露 token JSON API。
- 配对前端的 Vite dev/preview、生产 Nginx 将 OAuth 授权和 callback 路径反代至 Console API；Service Worker SPA fallback 排除这些路径。
- 若启用状态不能安全同步到所有副本，使用版本化 cache invalidation 或先保持运维发布重启生效，不做伪热更新。

当前代码已包含 Spring OAuth2 Client、Redis 一次性授权请求仓库、显式外部身份映射、本地角色校验、复用平台 JWT Cookie、管理员映射 API、默认关闭配置和数据库约束 IT。OIDC 授权发起使用位于 OAuth2 重定向过滤器之前的登录 IP 限流；本地回环 HTTP 例外要求显式 local/test profile 且拒绝任何 prod-like profile，并支持 IPv4/IPv6 loopback。Testcontainers 协议集成测试使用隔离 OIDC Provider 验证 Authorization Code、state 重放拒绝、nonce、PKCE、JWKS 签名、显式账号绑定及平台 Cookie；它不替代真实 IdP 验收。

测试资产按职责放置：协议集成测试保留在 `batch-console-api/src/test/java/.../integration`，隔离 IdP 使用测试进程内的 `MockWebServer`，PG/Redis 使用现有 `AbstractIntegrationTest` 的 Testcontainers。当前不需要独立 Keycloak 容器、Compose 文件或包装 shell 脚本；单类可直接通过 Maven 定向运行：

```bash
./mvnw -pl batch-console-api -am \
  -Dtest=ConsoleOidcAuthorizationCodeIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试需要 Docker/Testcontainers。只有后续要验证真实 IdP 特有行为或手动浏览器联测时，才增加独立、可选的 IdP 环境；Compose 文件应放在 `deploy/docker/compose/` 并配套操作文档，不放进后端 `src/test`，也不加入默认应用栈。

### 阶段 2：Console 登录体验（pilot 入口已实现）

- 在登录页提供单个试点“企业登录”入口；只有服务端启用 pilot registration 时才展示，不接收用户可控租户标识。
- 处理跳转中、取消、IdP 拒绝、绑定缺失、账号禁用、callback 失败等明确状态；不得把 IdP 错误原文或 token 显示给用户。
- 成功后复用既有 auth profile、菜单和路由权限；不另建前端 RBAC 状态源。

当前前端入口通过公开 provider 查询决定是否展示；callback 失败只呈现固定本地化提示，密码登录保持可用。成功后的 profile/menu 仍由既有本地会话流程加载。真实浏览器联调及不同 IdP 的拒绝/取消 UX 待 pilot IdP 确定后验收。

### 阶段 3：运维、灰度与验收

- 先对单个测试租户启用，保留应急管理员账号，观测成功/失败、state/nonce 校验失败、未知 subject、IdP 超时和 callback 错误率。
- 执行密钥轮换、IdP 不可用、错误 issuer/audience、租户错配、账号禁用、解绑、Redis 授权请求存储故障和多副本 callback 演练。
- 通过安全评审和运行手册后再逐租户放量；租户切回本地登录需有审计和明确回滚步骤。

### 阶段 4：按客户需求评估扩展

- **JIT 自动开户**：只有真实需求出现后再设计；需租户显式 opt-in、默认最低权限、账号停用/离职同步和审计，不能默认按 email 自动认领。
- **SAML / SCIM**：分别评估协议适配和生命周期同步，不能作为 OIDC 首期的隐式附带能力。
- **多 IdP / 域名发现 / IdP 发起登录 / Single Logout**：均需单独威胁建模与验收，不纳入一期。

## 7. 验收矩阵

| 层级 | 必须验证 |
|---|---|
| 单元测试 | issuer/audience/expiry/签名/nonce/state 校验；tenant+issuer+subject 映射；账号禁用和无映射拒绝；角色仅取本地；secret 与 token 脱敏 |
| 数据库 IT | 映射唯一约束、跨租户外键/一致性、并发重复绑定、RLS 和解绑审计 |
| 协议集成测试 | `ConsoleOidcAuthorizationCodeIntegrationTest` 覆盖 Testcontainers PG/Redis + 进程内 Mock OIDC Provider 的 code flow、state 一次性、nonce、PKCE、签名、账号绑定、平台 Cookie 与重放拒绝；本轮未重跑该后端 IT。真实 IdP、JWKS 轮换、错误 issuer/audience 和 provider 故障演练仍待做 |
| Console 联测 | 已验证启用态登录入口显示及 callback 错误重定向停留在前端 origin；Playwright runner 缺少浏览器二进制，标准 E2E 未运行。成功/拒绝/取消/绑定缺失完整真实 IdP UX、Cookie 安全属性和 auth profile 兼容仍需 pilot IdP 验收 |
| 多副本/恢复 | authorize 在副本 A、callback 到副本 B；Redis 临时不可用；一次性 state 重放拒绝；切换/回滚后现有本地登录不受损 |
| 上线验收 | 单租户灰度、审计可检索、secret 轮换演练、IdP 故障告警、break-glass 操作和回滚演练 |

## 8. 上线前决策与阻塞项

一期实现决策已固定：单 pilot tenant、单静态 registration、管理员显式预绑定、本地 RBAC 为权限唯一来源、本地密码登录保留、client secret 使用 Console API 专属部署 Secret、配置变更通过滚动重启生效；不做 email 自动绑定或 IdP 组/角色同步。

生产启用前仍必须由项目方确认：

1. 真实试点租户、IdP issuer、Console callback 域名和 IdP 运维联系人。
2. Console 专属 Secret 的实际密钥管理来源、轮换责任人与轮换演练。
3. break-glass 本地管理员的保管、告警和审计流程。
4. 真实 IdP 联测、跨副本 Redis state、IdP 故障和回滚证据。

生产启用前仍需确认首个真实 IdP/租户域名、break-glass 策略和运维联系人。当前本地实现不得对外宣称已完成生产 SSO；真实可用性以 IdP 联测、Secret 轮换、多副本 callback 和故障回滚证据为准。
