# Runbook · 凭据矩阵(Credential Matrix)

> 一张表把「一个租户上线 + 系统运行」涉及的**各类凭据**讲清:**类型 → 存哪 → 怎么注入 → 是否 prod 强校验 → 上线必配否 → 谁负责**。
> 上线一个新租户 / 部署一套新环境时,照本表逐行核对,别遗漏。
> 本文只汇总「凭据」这一横切面;每类凭据的完整机制各有权威文档(末列指针),改机制以那些文档 + 代码为准。

## 0. 三条铁律(先记住)

1. **账密绝不入库当明文**:片级 DB 账密走 secret 后端,**永不进 placement / shard catalog 表**(catalog 的 `secret_ref` 只是引用名,不是密钥);渠道/业务凭据走 `secret_version` 版本化、**应用层再加密**,控制台只能录新版本不能覆盖历史。
2. **平台不绑死任何 vault**:应用代码**不集成任何 secret SDK**,只认 Spring 配置 / env。外部 secret 后端(Vault / AWS Secrets Manager / K8s Secret)经**标准 env / 挂载文件**喂进配置——谁部署谁用自己的后端注入。
3. **prod profile fail-fast 回退**:多个出厂默认占位符在 prod profile 下**拒绝启动**(见下表「prod 强校验」列);非 prod 不阻断但打 WARN。漏开 prod profile ≠ 安全,真上线务必跑在 prod-like profile。

## 1. 凭据矩阵总表

| # | 凭据类型 | 存储位置 | 注入方式 | prod 强校验 | 上线必配 | 负责人 |
|---|---|---|---|---|---|---|
| 1 | **biz 分片片级 DB 账密**(每片 PG 的 writer 账户) | **secret 后端**(dev: `secrets/biz-shards/<key>.env`;prod: Vault / K8s Secret)。**永不入表** | 后端 → env `BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_n_USERNAME/PASSWORD/URL` → `routing.shards[*]` | 启用多片时缺 default key `shard-0` 装配直接拒;账户须 `batch_business_writer`(非 superuser,superuser 被 RLS 豁免=隔离失效) | 启用 `routing.enabled=true` 多片时必配;单片(默认)用主库凭据 | 运维 / 平台 DBA |
| 2 | **渠道凭据**(SFTP/API/OSS dispatch 的账密 / token / 密钥对) | `file_channel_config.config_json`(JSONB)+ `auth_type` 标类型;敏感值推荐走 `secret_version`(版本化、轮换窗口、`secret_payload` 应用层加密),`config_json` 存 `secret_ref` 引用 | 控制台录入 → 写入数据库;渠道适配器按 `secret_version` 版本号选有效凭据 | 无独立 fail-fast(数据面配置,按租户) | 仅当该租户用 DISPATCH(SFTP/API 投递)时必配;纯导入入库+导出落文件用不到 | 配置维护者 / 租户管理员 |
| 3 | **console 用户密码** | `console_user_account` 表,**Argon2id 哈希**(`ConsolePasswordHasher`,Spring Security 默认参数,含随机盐) | 建租户/建用户时录入明文 → 服务端 Argon2id 哈希写入数据库;**首次登录强制改密**(`must_change_password`,`POST /api/console/auth/change-password`) | `ConsoleDefaultPasswordGuard` 拦出厂默认弱口令;prod 不允许沿用默认 | 必配(admin + 各租户用户);批量建租户密码最低 12 位 | 平台管理员 / 租户管理员 |
| 4 | **平台内部密钥** `batch.security.internal-secret` | 配置默认占位符 `internal-secret`(`batch-defaults.yml`);prod 经 `BATCH_INTERNAL_SECRET` 注入 | env `BATCH_INTERNAL_SECRET` → `BatchSecurityProperties.internalSecret`;orchestrator `/internal/**` 用 `X-Internal-Secret` header 校验 | **是**:prod profile 下仍为默认值 `internal-secret` → `IllegalStateException` fail-fast 拒绝启动 | 必配(orchestrator 内部接口鉴权;全部 worker/trigger 调用方共享) | 平台 / 运维 |
| 5 | **console JWT 签名密钥** `console.security.jwt-secret` | 配置默认占位符 `console-jwt-secret-change-me`;prod 经 `BATCH_CONSOLE_JWT_SECRET` 注入 | env `BATCH_CONSOLE_JWT_SECRET` → `ConsoleSecurityProperties.jwtSecret`(`ConsoleJwtService` 派生 HMAC key) | **是**:`ConsoleJwtService.validateSecuritySecrets` prod profile 下默认/弱值 fail-fast | 必配(console 登录态签发/校验) | 平台 / 运维 |
| 6 | **对象加密密钥 / KMS key** `batch.security.kms.keys.*` | 配置 `default-key-ref` + `keys` map(`batch-defaults.yml`,默认 `DEFAULT_TEST`);prod 经 env 注入真实密钥,**避免明文进仓库** | env `BATCH_SECURITY_KMS_DEFAULT_KEY_REF` / `BATCH_SECURITY_KMS_KEYS_*`;模板 `encryption_key_ref` 指向 `secret_version` | 默认关(`content_encryption_enabled=NO`);启用内容加密的租户才用 | 仅当租户/业务开 `content_encryption_enabled=YES`(`encryption_mode=APP_LEVEL`)时必配;`OBJECT_STORAGE_SSE` 走对象存储侧 | 平台(安全) |
| 7 | **对象存储凭据**(S3 协议 access-key / secret-key) | 配置 `batch.storage.s3.*`(`batch-defaults.yml`,dev 默认 `minioadmin`) | env `BATCH_S3_ACCESS_KEY` / `BATCH_S3_SECRET_KEY` / `BATCH_S3_ENDPOINT` 等 → `S3StorageProperties` | 无独立 fail-fast(默认 minioadmin 仅 dev) | 必配(orchestrator + 全 worker + console 都用;接 MinIO/S3/OSS/COS) | 平台 / 运维 |
| 8 | **平台 DB 连接凭据**(console 主库 + read-replica) | 配置 `application.yml`(console:`primary` + `read-replica.replica`,dev 默认 `batch_user` / `batch_pass_123`) | env `BATCH_CONSOLE_PRIMARY_USER/PASSWORD`、`BATCH_CONSOLE_REPLICA_USER/PASSWORD` 等 | **是**:`BatchSecurityProperties` prod profile 下命中已知弱默认口令(`batch_pass_123` 等)→ fail-fast | 必配(主库必配;replica 仅 console 读写分离开启时必配,trigger/orchestrator/worker **禁**引入读写分离) | 运维 / DBA |

> 速记:**4 / 5 / 6 / 8 这四类有 prod fail-fast**(内部密钥 / JWT / KMS-启用时 / DB 弱口令),漏注入会在 prod-like profile 直接拒绝启动;1 在多片装配期拒缺 default;2 / 3 / 7 靠流程与守卫而非启动 fail-fast。

## 2. 关键约束补充

- **凭据轮换(渠道/加密)**:`secret_version` 支持 `DRAFT/PUBLISHED/GRAY/ROLLED_BACK` 状态 + 轮换窗口(`rotation_window_*`)+ 兼容期(`effective_*`),控制台**只录新版本不覆盖历史**,轮换事件进 `config_change_log` 审计。详见 `docs/design/multi-tenant-and-security.md` §5。
- **biz 片凭据轮换**:换密码只动 secret 后端 + 重启(或热加载)worker,**不动** placement / shard catalog 表。账户固定用 `batch_business_writer`(非 superuser)。详见 `docs/runbook/biz-tenant-routing.md` §9。
- **secret 脱敏**:日志输出统一经 `SecretMasking`(`batch-common/.../utils/SecretMasking.java`),不打全量凭据明文。
- **dev vs prod 落差**:dev 大量用出厂默认(`minioadmin` / `batch_pass_123` / `internal-secret` / biz 片用 superuser `batch_user`)纯为本地起得来;**prod 一律换真实凭据 + prod-like profile 跑**,靠上表 fail-fast 列回退。

## 3. 权威文档指针

| 凭据 | 权威文档 |
|---|---|
| biz 分片片级账密 | `docs/runbook/biz-tenant-routing.md` §6/§9、`secrets/biz-shards/README.md` |
| 渠道凭据 / `secret_version` 轮换 | `docs/design/multi-tenant-and-security.md` §5/§9、`docs/design/data-model-ddl.md`(`file_channel_config` / `secret_version` DDL) |
| console 密码 / 登录 | `docs/runbook/console-login-encryption.md`、`docs/runbook/first-tenant-config-quickstart.md` §1 |
| 平台内部密钥 / DB 弱口令 fail-fast | `BatchSecurityProperties`(`batch-common`)、`batch-defaults.yml` |
| KMS / 内容加密 | `docs/design/multi-tenant-and-security.md` §9 |
| 对象存储 / DB 连接 / 读写分离 | `batch-defaults.yml`、`docs/runbook/read-replica.md`、`docs/runbook/feature-switches.md` |
