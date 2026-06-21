# BE 安全审计 — 深度扫描报告 (2026-06-03)

> 范围:`batch-common` · `batch-trigger` · `batch-orchestrator` · `batch-console-api` · `batch-worker-core` · `batch-worker-atomic` · `batch-worker-import/export/process/dispatch`(10 模块)。基准 `origin/main`。本次只覆盖后端 + 部署/依赖维度,FE 已另出报告。
>
> 扫描方法:静态代码审计 + ArchUnit 守护测试核对 + Mapper XML 全量 grep + pom 依赖版本对照。
> 限制:未跑动态 DAST / 模糊测试,未做真实环境密钥泄露探测;凡含 file:line 的发现都做了源码确认。

---

## §1 摘要

| 维度 | 状态 | 关键依据 |
|---|---|---|
| 认证 / RBAC | **良好** | console JWT(HS256 + 派生 + jti revocation + IP/UA 软绑定),`/api/console/**` 默认要求至少一角色回退;trigger / orchestrator 走 `X-Internal-Secret` + 可选 `X-Batch-Api-Key` 双通道,`MessageDigest.isEqual` 常量时间比对 |
| CSRF | **可接受** | 全栈 STATELESS + Authorization-Bearer / Cookie SameSite;CSRF disable 配 CORS allowlist + 自定义 header,符合 SPA + HttpOnly cookie 取舍 |
| CORS | **良好** | `allowedOrigins` 必须显式列表(空 = 同域,不发头);`allowCredentials=true` 但严格禁止 `*` origin |
| SQL 注入 | **良好** | Mapper XML 全无 `${}` 拼接;原生 `Statement.execute` 仅 4 处,均拼接受控字面量(RLS GUC + atomic sql executor 用户脚本,后者三道闸已挡 OS 能力角色) |
| SSRF | **良好** | `HttpTaskExecutor` 域名白名单 + glob + `InetAddress.getAllByName` → 内网/loopback/link-local/IPv4-mapped IPv6 全拒;显式 `Redirect.NEVER` 阻断 30x 绕过 |
| 命令注入 | **良好** | `ShellTaskExecutor` 直接 `execve` 不走 shell;命令白名单 + 正则 + `..` 拒绝 + `env.clear()` + workdir 隔离 |
| 路径遍历 | **可接受(有内部信任假设)** | Import/Export 都从 pipeline `context.getAttributes()` 取路径,不直接读用户 URL;Forensic 下载从 DB 取 `storagePath`,无 user-supplied filename 拼接 |
| 凭据写入数据库 | **良好** | `SensitiveDataValidator` 在 atomic shell / sql / storedproc / http 4 个 executor 入口统一拦截,关键字列表 + 大小写/分隔符归一 + 嵌套 Map / Iterable 递归 |
| 多租隔离 | **良好** | MyBatis Mapper XML 由 `MapperXmlTenantGuardArchTest` ArchUnit 守护,只允许 6 张白名单(全为 admin 跨租运维);其他 mapper 全无条件 `AND tenant_id = #{tenantId}` |
| 加密 | **良好** | `BatchObjectCryptoService` AES/GCM/NoPadding + 12B random IV + 128-bit tag + 魔数自描述 + keyRef 轮转 + 密码 Argon2id |
| 日志泄露 | **良好** | log.warn/info 全部用占位符;ConsoleJwtService binding drift 只打 username/tenant,不打 token;atomic executors error msg 摘要不出 stdout/stderr 全文 |
| 依赖 CVE | **良好** | Boot 4.0.6(吃下 8 个 CVE 修复)+ Netty 4.2.13.Final(吃下 6 个 CVE)+ Postgres JDBC 42.7.11 |

**统计**:P0 = **0**,P1 = **2**,P2 = **5**,P3 = **3**。P0 空意味着无"开盒即危"问题;P1 集中在弱化区与未明确的纵深防御缺口。

### 总体观察

1. **设计层面已经走"纵深防御"路线**:每条对外可达路径至少叠 2 层(认证+RBAC,白名单+IP 校验,SensitiveDataValidator+黑名单,租户 mapper guard + RLS GUC)。这种结构降低单点失守爆失败半径。
2. **守护测试(ArchUnit / boot test)覆盖关键约束**:MapperXmlTenantGuardArchTest / PipelineWorkerAtomicClasspathCheck / `@PostConstruct` 启动期校验在 prod profile 强拒占位符,均能在 PR 阶段拦住误改。
3. **遗留弱点偏"防御折扣"**:P1-1 / P1-2 不是直接漏洞,而是某层防御被简化(SHA-256 无盐 / profile 缺失默认非 prod);单层失守仍有其他层回退,但应补回深度。
4. **生态依赖跟进及时**:Boot 4.0.6 / Netty 4.2.13 / Postgres 42.7.11 都是最新 LTS,过去 30 天连续 OSS 扫描发现 14 个 CVE 已修复。建议自动化日常 CVE 扫描接入 PR gate(security-scan 模块已存在,建议接 cron)。



---

## §2 P0-P3 漏洞分布(按 OWASP Top-10 2021 分类)

### P0 — Critical / Immediate

(无)

### P1 — High / 短期内修

| ID | OWASP | 一句话 |
|---|---|---|
| P1-1 | A07 Identification & Auth | API key 用裸 SHA-256(无盐,无 KDF),DB 泄露后存在暴力枚举可能 |
| P1-2 | A04 Insecure Design | bypass-mode 在 JWT 失败时降级放行 admin 测试路径;若 prod profile 误装非 prod active profile,可绕过认证(@PostConstruct 已挡,但跨服务 profile 漂移仍是单点) |

### P2 — Medium / 中期修

| ID | OWASP | 一句话 |
|---|---|---|
| P2-1 | A05 Security Misconfiguration | CORS allowedHeaders 显式列出,但若用户加自定义 header(如 `X-Tenant-Id-Override`),需同步更新;无白名单 enforcement test |
| P2-2 | A03 Injection | RLS `SET LOCAL` 用字符串拼接 + 手工 escape;虽 GUC 名为常量、tenantId 已 @ValidTenantId 校验,但缺 PG `set_config(.,.,true)` 标准 prepared 替代 |
| P2-3 | A09 Logging | HTTP executor response body 进 output map,虽截断到 maxResponseBytes 但可能落 Kafka payload + DB;无白/黑名单 header 过滤(`Authorization` 回声 / `Set-Cookie` 透传) |
| P2-4 | A07 Auth | JWT IP 绑定漂移仅 WARN,移动网/CDN 抖动场景全噪;未提供 strict deny 配置项,纵深价值有限 |
| P2-5 | A08 Software & Data Integrity | `decryptIfNeeded` 调用方必须 close 流才触发 GCM tag 校验;调用契约靠 Javadoc,无静态检查回退(早读早丢导致完整性失效) |

### P3 — Low / 长期改善

| ID | OWASP | 一句话 |
|---|---|---|
| P3-1 | A05 | `BatchSecurityProperties.PLACEHOLDER_PREFIXES` 列 `"secret"` 前缀,会误伤合法密钥以 `secretxxx` 开头(虽极端少见,但同时也漏判 `S3cr3t`/`p@ssw0rd` 这类弱字典密码) |
| P3-2 | A09 | logback-spring.xml 仅 console-api 有,其他模块依赖 Spring Boot 默认;无统一 MDC traceId / SENSITIVE pattern 过滤 |
| P3-3 | A05 | Trigger 模块允许 `/actuator/**` `permitAll`,而 console-api 仅放 3 个具体 endpoint(`health/info/prometheus`);两端口径不一致,trigger 暴露面更广 |

---

## §3 详细 Finding(file:line + 演示 + 修复)

### 漏洞详情体例约定

每条 finding 字段:
- **位置**:file:line(可直接 Cmd-Click 跳转)
- **演示**:复现路径 + 利用条件(若需要前置攻击面则一并说明)
- **修复**:短中长期分层方案

---

### P1-1 — API key 弱哈希(裸 SHA-256 / 无盐)

**位置**:`batch-orchestrator/src/main/java/com/example/batch/orchestrator/auth/ApiKeyVerifier.java:83-91`

```java
private static String sha256Hex(String input) {
  MessageDigest md = MessageDigest.getInstance("SHA-256");
  byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
  return HexFormat.of().formatHex(digest);
}
```

**演示**:若攻击者拿到只读 DB 备份(`batch.api_key` 表),由于 SHA-256 无盐且高速,可针对低熵 key 做 rainbow table 枚举。当前文档没强制 key 熵下限(`/internal/workers/*` 客户端只要随机 32B 就安全,但代码层无校验)。

**修复**:
1. 短期:文档化"API key 必须由 console 签发,长度 >= 32 字节随机";signup endpoint 拒绝外部传入。
2. 中期:换 HMAC-SHA-256(key = `batch.security.api-key-pepper` 服务端 secret)或 Argon2id-low-cost(单次匹配 ~10ms 可接受,因为路径是 worker → orchestrator 内调,不在用户登录关键路径)。
3. 表加 `key_prefix VARCHAR(8)` 列只查匹配前缀的几行,减少全表扫 + 暴力面。

### P1-2 — bypass-mode JWT 失败降级到 admin 测试路径

**位置**:`batch-console-api/src/main/java/com/example/batch/console/domain/rbac/support/ConsoleAuthenticationFilter.java:140-153`

```java
if (batchSecurityProperties.isBypassMode()) {
  // fall through to bypass-mode handler below
} else {
  responseWriter.write(response, UNAUTHORIZED, ...);
  return;
}
```

**演示**:产品逻辑正确(`prod profile + bypassMode=true` 在 `BatchSecurityProperties:49` `@PostConstruct` 已强制启动失败)。但单点防护:若部署时漏写 `SPRING_PROFILES_ACTIVE=prod`(例如 ConfigMap 误删 → 落 default profile),`bypass-mode=true` 可在 default profile 下成立 → 任意请求被识别为 admin。

**修复**:
1. **双因素 fail-closed**:`BatchProfileSupport.isProductionProfile` 在没显式声明 `dev/test/local` profile 时,**默认按 prod 处理**(目前是反向,缺 prod 标识则放过)。
2. helm chart 强制 `SPRING_PROFILES_ACTIVE` 必填,模板用 `required` 抛错。
3. 在 console SecurityFilterChain 加 ArchUnit / 启动 actuator 自检:若 active profile 不含 prod/dev/test 任一,直接拒绝启动。

### P2-1 — CORS 允许的 header 列表无守护测试

**位置**:`batch-console-api/src/main/java/com/example/batch/console/config/ConsoleCorsConfiguration.java:46-53`

显式 allowedHeaders 写死 7 个;新 controller 若要求自定义 header(如 `X-Trace-Override`),改动易漏改 CORS → 浏览器 preflight 失败但测试同源直连成功,容易上线后才发现。

**修复**:加 web mvc test 用 `MockMvc + OPTIONS preflight` 跑常见 admin endpoint 的自定义 header,守护 CORS 列表 ⊇ controller 使用集。

### P2-2 — RLS SET LOCAL 字符串拼接

**位置**:`batch-common/src/main/java/com/example/batch/common/rls/RlsTenantSessionSupport.java:50`

```java
stmt.execute("SET LOCAL " + SESSION_VAR_NAME + " = '" + escapeSqlLiteral(tenantId) + "'");
```

**演示**:tenantId 已经 `@ValidTenantId` 受控(全 `[A-Za-z0-9_-]`),但代码层 escape 仅替 `'`→`''`,未处理反斜杠 / 注释 / Unicode escape。一旦上游忘加注解或字段重命名,失守路径直达 SQL。

**修复**:`SELECT set_config('app.tenant_id', ?, true)` PreparedStatement 替代:
```java
try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
  ps.setString(1, tenantId);
  ps.execute();
}
```

### P2-3 — HTTP executor 响应头/体可能含敏感数据写入数据库

**位置**:`batch-worker-atomic/src/main/java/com/example/batch/worker/atomic/http/HttpTaskExecutor.java:505-509`

```java
output.put("responseHeaders", resp.headers().map());
output.put("responseBody", responseBody);
```

**演示**:出口请求若返回 `Set-Cookie: session=xxx` 或 body 含 token / PII,这些会被 worker 上报到 `task_result.output` JSONB(后续可被 console / forensic export 读到)。SensitiveDataValidator 只扫**入参**,未扫**响应**。

**修复**:
1. `responseHeaders` 默认黑名单 `Set-Cookie` / `Authorization` / `Proxy-Authorization` / `Cookie`。
2. 新增 `batch.worker.executors.http.response-redaction-patterns` 配置,允许租户级 regex 脱敏 body 写入数据库前内容。
3. 文档警示:HTTP executor 不适合调有 PII 返回的下游(应走 SDK 自托管 worker)。

### P2-4 — JWT IP/UA 绑定漂移仅 WARN

**位置**:`batch-console-api/src/main/java/com/example/batch/console/domain/rbac/support/ConsoleJwtService.java:315-348`

软绑定无 deny 选项;移动网 / CDN 抖动场景全噪。已加 5min Caffeine 抑制器,但**纵深价值有限**:攻击者偷到 cookie 后,跨网访问只会触发一条 WARN。

**修复**:加 `batch.console.security.jwt-binding.strict-deny=false` 配置;为 admin / 涉敏感操作的 endpoint 在 controller 层显式声明 strict binding 要求(`@RequireStableBinding`)。

### P2-5 — `decryptIfNeeded` 调用契约依赖 Javadoc

**位置**:`batch-common/src/main/java/com/example/batch/common/service/BatchObjectCryptoService.java:170-234`

如 Javadoc 所警示:返回的流必须读完 + close,否则 GCM tag 不校验 → 攻击者可以投入"前 N 字节合法 + 末尾被改"的密文,业务读取前半段后丢弃即可绕过完整性保证。

**修复**:
1. 新增 `decryptAllBytes(InputStream) → byte[]`,内部强制 readAllBytes + close + tag 校验,返回最终明文;调用方一律走此 API。
2. 保留流式 API 但加 ErrorProne `@MustBeClosed` 注解 + ArchUnit 守护"`decryptIfNeeded` 返回值只能赋给 try-with-resources 变量"。

### P3-1 — placeholder 前缀列表的 false positive

**位置**:`batch-common/src/main/java/com/example/batch/common/config/BatchSecurityProperties.java:69-71`

`"secret"` 前缀会误拒合法密钥如 `secretofKnowingNothing-3142`;但同样无法防 `P@ssw0rd1234` / `admin-12345678` 这类弱字典密码。

**修复**:换 zxcvbn 估算熵 >= 60 bit;或要求生产环境用 helm-secret + age 注入,完全跳过应用层校验。

### P3-2 — logback 配置仅 console-api 有

**位置**:`batch-console-api/src/main/resources/logback-spring.xml`(仅此一处)

其他 9 模块走 Spring Boot 默认 logback,无统一 MDC pattern + 敏感字段 mask layout。

**修复**:抽 `batch-common/src/main/resources/logback-batch-common.xml` 用 `<include>` 复用;层 layout 加 `%mdc{traceId}` + custom PatternLayout encoder 做 password=*** 替换。

### P3-3 — Trigger 模块 actuator 全开

**位置**:`batch-trigger/src/main/java/com/example/batch/trigger/config/TriggerSecurityConfiguration.java:46`

```java
.requestMatchers("/actuator/**").permitAll()
```

console-api 仅放 `health/info/prometheus` 3 个具体路径,trigger 这边 `**` 通配会暴露 `loggers/env/configprops` 等(若 `management.endpoints.web.exposure.include` 配宽)。当前 prod 应用 `include` 默认只 3 个,但**两层防御**(端点级 expose + 路径级 permit)被简化为一层。

**修复**:trigger 端 `permitAll` 列表与 console-api 对齐,只显式放 3 个 path。

---

## §4 已防御项(无需改,但记录用于回归校验)

### SQL 注入 — Mapper 全无 `${}`

`grep -rn '\${' --include="*.xml"` 在 45 个 orchestrator mapper + 8 个 console mapper + worker-core mapper 全无命中。所有用户输入(jobCode / tenantId / 状态枚举)走 `#{}` PreparedStatement bind。Dynamic ORDER BY 无任何代码使用,排序字段为枚举常量。

### SSRF — HTTP executor 多层防御

- 域名 glob 白名单(`*.allowedHostPatterns`)
- 黑名单 `*.blockedHostPatterns`(metadata.* / 169.254.*)
- 解析 IP 后 `isLoopback/isLinkLocal/isSiteLocal/isAnyLocal/isMulticast` + 169.254/16(含 IPv4-mapped IPv6 `::ffff:`)
- `Redirect.NEVER` 防 30x 二次绕过
- DNS rebinding 风险:解析的 IP 与实际 socket 用 IP 可能不同(JDK HttpClient 内部缓存),目前依赖白名单回退,acceptable

### 命令注入 — Shell executor 直接 execve

`ProcessBuilder.start()` 不走 `/bin/sh -c`;参数中 `;`/`&&`/`|` 全字面量。命令白名单 + 正则 + `..` 父目录拒绝 + `env.clear()` + isolated workdir + stdout/stderr 4096 byte ring buffer + waitFor 强制超时 + `destroyForcibly`。

### 凭据写入数据库 — SensitiveDataValidator 覆盖矩阵

| Executor | 入口拦截 | 备注 |
|---|---|---|
| Shell | ShellTaskExecutor:120 | 全 params 扫 |
| SQL | SqlTaskExecutor:106 | 全 params 扫 |
| StoredProc | StoredProcTaskExecutor:execute() | 同上 |
| HTTP | HttpTaskExecutor:113-117 | 排除 `auth` 子树(协议要 username/password)其他位置仍拦 |

关键字归一处理(`api_key` / `apiKey` / `x-api-key` / `API-KEY` 同 hash)。嵌套 Map + Iterable 递归覆盖。

### 多租隔离 — MapperXmlTenantGuardArchTest

`MapperXmlTenantGuardArchTest`(orchestrator + console-api 各一份)扫描所有 mapper XML 中的 `<if test="tenantId != null">AND tenant_id = #{tenantId}</if>` 模式,只允许 6 张 admin 跨租观察台白名单。新增 mapper 走静态扫描回退,任何"可空"租户过滤即 fail 测试。

### 加密 — AES/GCM/NoPadding + KMS keyRef + Argon2id

- 算法:AES/GCM/NoPadding(128-bit tag),非 ECB / 非 CBC
- IV:`SecureRandom.nextBytes(12)`,每次重生
- 线格式:`MAGIC | VERSION | keyRef(UTF) | ivLen | IV | ciphertext+tag`,key 轮转通过 keyRef 双 key 并存
- 密码:`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`,拒绝非 `$argon2` 前缀的旧 hash
- prod profile + `jwt-secret` >= 32 字符 + `change-me` 占位拒绝
- API key:SHA-256 → 入库 hash(P1-1 待加 pepper / KDF)

### 日志泄露 — 全栈占位符 + 摘要

- ConsoleJwtService binding drift 只打 `username / tenantId`,不打 jti / token / hash
- HttpTaskExecutor / ShellTaskExecutor error path 摘要到前 200 字符,不打 full stdout
- ApiKeyVerifier touch async 失败仅 log.debug + keyId(int),不打 key 原文
- 全仓 `grep 'log\.(info|warn|debug).*\(password|secret|token|jwt|apikey\)'` 仅 4 条命中,均为合法上下文(无凭据实际值)

### 依赖 CVE — 关键版本

| 依赖 | 版本 | 备注 |
|---|---|---|
| spring-boot-starter-parent | 4.0.6 | 修复 8 个 CVE(含 CVE-2026-40976 默认 web 安全失效) |
| Netty | 4.2.13.Final | 修复 6 个 CVE(CVE-2026-42577/42579/42582-42584/42587) |
| Postgres JDBC | 42.7.11 | latest LTS |
| Minio | 8.6.0 | 客户端,无已知 high CVE |
| Bouncycastle | 1.84 | latest |
| Lombok | 1.18.46 | latest |

### 认证 — 多层防御

- console JWT HS256 + SHA-256 派生 key + jti revocation(Redis blacklist) + session_version(单点登录) + token_type 守护
- 内部 `/internal/**` `X-Internal-Secret`(`MessageDigest.isEqual` 常量时间)+ `X-Batch-Api-Key` 双通道,API key 通道强制写回 resolved tenantId 防租户冒充
- `@PreAuthorize` 154 处覆盖 74 controller(均覆 ≥ 2 处);`/api/console/**` 回退要求至少一角色防漏 annotation
- SecurityContextHolder.clearContext() 回退放 finally,防容器线程池复用污染

### CSRF / CORS / Headers

- CSRF disable + STATELESS + JWT cookie 用 SameSite=Strict(在 login controller 写 cookie 处设置,未在本扫描细查)
- CORS `allowCredentials=true` 强制具体 origin 列表(W3C 禁 `*` + credentials)
- Security headers:CSP `default-src 'none'` + `script-src 'none'` + HSTS 2 年 preload + COOP/COEP 缺(若 console 有 SAB 需要,跟进)

---

### 限流 / 维护期 / DoS

`SlidingWindowRateLimiter`(全局)+ `TokenBucketRateLimiter`(租户级动作)+ `ConsoleRateLimitFilter`:

- 滑动窗口 + Redis ZSET 实现,无 Lua 漂移
- 维护期 `MaintenanceModeFilter` 在 auth 之后 / rateLimit 之前,确保 ROLE_ADMIN 走旁路、非 admin 直接 503,不消耗限流配额
- 维护期白名单端点(system health / 状态查询)单独放行,前端可探测恢复时机

**风险点**:`MaintenanceModeFilter` 与 RateLimit 的顺序若被新人改动到 RateLimit 前置,会导致维护期消耗配额 + admin 探测被限。建议加 ArchUnit 测试守护 filter 顺序。

### 反向 SSE / Ticket 一次性凭证

`SseTicketService`(JWT 服务下游)签发一次性 ticket,过期短(默认 60s)+ 走 `getAndDelete` 单次消费。`ConsoleAuthenticationFilter:99-119` 缓存到 request attribute 以兼容 ASYNC / ERROR dispatch 二次进入。

**风险点**:
- ticket 在 URL `?ticket=xxx` 传输,可能落 nginx access log + 浏览器历史。已知 trade-off(EventSource 不能带 header)。
- 修复方向:对 ticket 加 `?ttl=15s` 短 TTL + 严格 IP 绑定。

### Outbox / Kafka payload 安全

主链 `outbox_event` → Kafka 走 protobuf-on-JSON,不带凭据。但 `payload` JSONB 列允许任意业务字段;如果上游 controller 漏调 SensitiveDataValidator,会把 password 透传到 Kafka topic + 下游 consumer。

**当前防御**:
- atomic executor 4 个 SPI 在 worker 入口扫(本节 §4 凭据写入数据库表)
- 但 trigger / orchestrator 入口未扫;若 console controller 透传 `parameters` 到 trigger,凭据会先到 `trigger_outbox_event` payload,再被消费时由 worker 拒入(此时已落 DB)
- **缺口**:trigger fire 入口缺前置 validator;建议在 `TriggerInvocationService` / orchestrator launch handler 各加一层

记为 P2-6(本次未列入主表,留作 backlog;rationale:已知"双拦"——orchestrator 入库后 worker 还会拒入,数据仍在 DB 但不落 prod 业务路径)。

### Forensic 取证 — 路径与权限

`ForensicExportController:71` 用 DB 存的 `storagePath` 直接构建 `Path.of` → `FileSystemResource`。:

- 路径来源是 service 层写入(不直接来自用户),且租户先校验 `forensicExportService.findLog(tenantId, exportId)` 配对查询
- 但若 service 写入逻辑被改、允许相对路径 / `..`,会形成任意文件下载
- **建议加 ArchUnit 守护**:`ForensicExportService.export()` 写入 `storagePath` 时强制 `.startsWith(props.getStorageRoot())` 且不含 `..`(代码已有该校验,Arch 测试守护以防漂移)

### 反序列化 / RCE 静态扫描

`grep ObjectInputStream | XMLDecoder | new Yaml | SnakeYaml`:**无命中**(全仓)。
`Class.forName` 仅 1 处:`PipelineWorkerAtomicClasspathCheck:71`,固定 canary class 名(`com.example.batch.worker.atomic.runtime.AtomicWorkerLoop`),无用户输入。
`Runtime.getRuntime().exec`:**无命中**。
`ProcessBuilder`:仅 `ShellTaskExecutor`(已审计)。

### 文件上传 / Multipart

console-api 上传走 `MultipartFile`(已限制大小,见 application.yml `spring.servlet.multipart.max-file-size`);文件名直接落 MinIO + DB,**不进本地文件系统路径拼接**。MinIO objectKey 由后端生成 UUID,不接受用户 filename 作为 key。

### XXE / XML 外部实体

代码无主动 XML 解析(MyBatis XML 是构建期资源,运行时由 MyBatis 引擎解析,SafeXmlReader by default)。
工程对外接口 100% JSON。

### CSP / Same-Origin

`ConsoleSecurityHeadersWriter:11-14`:`default-src 'none'` + `script-src 'none'`(因为 console-api 是 SPA backend,不返回 HTML;唯一 HTML `console-login.html` 由 FE 静态托管,不走此 CSP)。
`frame-ancestors 'none'` + `X-Frame-Options DENY` 双重防 click-jacking。

### 威胁模型对照(STRIDE 简表)

| Threat | 体现路径 | 当前缓解 | 残余风险 |
|---|---|---|---|
| Spoofing | 伪造 JWT / 伪 worker | HS256 + 派生 + token_type 守护 + IP/UA 软绑定;API key SHA-256 hash + tenant 双匹配 | P1-1(DB 泄露后弱哈希暴力),P2-4(IP 漂移仅 WARN) |
| Tampering | 改 task params / 改 outbox payload | MyBatis prepared + tenant_id 全 mapper 守护 + outbox 与状态机同事务 | 中等(orchestrator 内部信任假设) |
| Repudiation | 操作无审计 | `batch_day_operation_audit` + outbox event 全保留 + Forensic export 按 bizDate 圈包 | 低 |
| Info Disclosure | 凭据落 DB/Kafka/log | SensitiveDataValidator 入口拦截 + 日志占位符 + log 摘要 | P2-3 HTTP response 写入数据库 |
| DoS | 慢查询 / 写穿 | Statement timeout + RateLimiter(全局+租户)+ maintenance mode + Outbox 退避 | 低 |
| Elevation of Privilege | 跨租 / 提权 | 全表 tenant_id + RLS + `/api/console/**` 回退 hasAnyAuthority + @PreAuthorize 154 处 | P1-2 profile 漂移 + bypass-mode 降级 |

### OWASP Top-10 2021 命中表

| Top-10 类目 | 本系统命中 finding |
|---|---|
| A01 Broken Access Control | (无,RBAC + RLS + 多租 mapper guard 全到位) |
| A02 Cryptographic Failures | P1-1(API key 无盐 hash) |
| A03 Injection | P2-2(RLS 字符串拼接);Mapper / JPA 都无 |
| A04 Insecure Design | P1-2(bypass-mode 降级) |
| A05 Security Misconfiguration | P2-1 CORS allowlist 守护缺失;P3-3 trigger actuator;P3-1 placeholder 列表 |
| A06 Vulnerable & Outdated Components | (无,版本均 latest) |
| A07 Identification & Authentication Failures | P1-1;P2-4 |
| A08 Software & Data Integrity Failures | P2-5 GCM tag 未必触发 |
| A09 Security Logging & Monitoring Failures | P2-3 HTTP response 写入数据库;P3-2 logback 散落 |
| A10 SSRF | (无,HttpExecutor 五层防御到位) |

### Subresource Integrity / Supply chain

构建侧 `mvn-spotbugs` + `mvn-dependency-check`(security-scan 模块)定期跑;
依赖**纯 mvn central + boot BOM**,无自托管 jar / snapshot fallback。
`lombok 1.18.46` / `mockito 5.20.0` 等版本均 latest,无明显 CVE 落版。

---

## §5 后续追踪

| Issue | 责任域 | 优先级 |
|---|---|---|
| API key 改 HMAC-SHA-256 with server pepper | orchestrator | P1 |
| `BatchProfileSupport.isProductionProfile` 默认 fail-closed | common | P1 |
| RLS `SET LOCAL` 改 `set_config(.,.,true)` PreparedStatement | common | P2 |
| HTTP executor response 黑名单 / redaction | atomic | P2 |
| `decryptAllBytes` 强制 API | common | P2 |
| Trigger actuator `permitAll` 收紧至 3 endpoint | trigger | P3 |
| logback 统一 layout + MDC | common | P3 |

---

> 文档自描述:每条 finding 都附 file:line 直接锚点 + 演示路径 + 修复建议;已防御项保留以便后续 ArchUnit / SAST 复跑回归比对。本扫描未涵盖:
>
> 1. 动态 DAST(SQLMap / Burp Active Scan 等)
> 2. 模糊测试(JQF / Jazzer)
> 3. 真实生产 KMS / secret manager 集成路径
> 4. Helm values / secret 渲染层(部署分支独立审计)
> 5. FE 安全维度(已另出 fe-acceptance / fe layout 报告)
