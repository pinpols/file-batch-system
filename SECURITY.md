# Security Policy

## Supported Versions

Security fixes are applied to the current main development line.

## Implemented Security Features

以下安全防护措施已在当前代码库中落地：

### 路径遍历防护
- **`PathSanitizer`**（`batch-common`）：集中路径验证工具类，拒绝 null / 空白 / `..` 遍历路径，支持沙箱目录约束。所有文件操作入口（`DispatchFileContentResolver`、`ReceiveStep`、NAS 派发）均在解析前调用此类。
- **`RemoteFilesystemDispatchSupport`**：NAS 路径在写入前调用 `.normalize()` + 沙箱校验。

### 传输安全
- **SFTP StrictHostKeyChecking**（`SftpDispatchChannelAdapter`）：默认启用主机密钥验证（`yes`），可通过渠道配置中的 `sftp_strict_host_key_checking=no` 显式关闭（会输出 WARN 日志）。生产环境应提供 `sftp_known_hosts_path`。

### XML 安全
- **XXE 防护**（`ParseStep`）：已设置 `ACCESS_EXTERNAL_DTD=` 和 `ACCESS_EXTERNAL_SCHEMA=`，禁止外部 DTD / Schema 解析，防止 XML 外部实体注入。

### 幂等与事务安全
- **`DatabaseIdempotencyGuard`**：全局幂等层，通过数据库唯一约束防止并发重复执行（V38 `idempotency_record` 表）。
- **`TaskDispatchOutboxService`**：所有写入方法标注 `@Transactional(propagation = MANDATORY)`，强制在外部事务内运行，防止非事务上下文下的孤立 Outbox 写入。

### 死信队列
- **`DeadLetterPublisher`**（`batch-worker-core`）：任务执行异常时写入 `batch.task.dead-letter` Topic，错误信息截断至 2000 字符，Kafka 发送异常静默吞噬，不影响主链路。

### 访问控制与脱敏
- **`ConsoleTenantGuard`**：控制台 API 请求强制租户隔离，跨租户请求直接 403。
- **`ConsoleAiPromptGuard`**（AI Gateway）：AI 提示词请求的内容安全守卫，含分类（REJECTED_DISABLED / SAFETY / SCOPE / APPROVED）和审计日志写入数据库（`console_ai_audit_log`）。
- **`ContentMaskingUtils`**：PII 脱敏支持 STRICT / PCI / GDPR 三档规则集（卡有效期、IPv4、邮编等）。

### 凭证管理注意事项
- SMTP / SFTP 密码目前以 `String` 形式传递（`M-8`），无法安全擦除。生产环境建议使用密钥文件认证替代密码认证。
- 控制台用户密码使用 Argon2id 哈希存储（V35 迁移）。

## Reporting a Vulnerability

If you find a security issue, report it privately instead of opening a public
issue.

Please include:

- A short description of the issue
- Affected module or endpoint
- Reproduction steps
- Expected impact
- Any proposed mitigation

## Contact

Use the repository owner contact or a private security channel. Replace the
placeholder below before publishing the repository publicly:

- `security@your-domain.example`

