# biz 分片凭据（secrets）

P2 tenant-routing 的**凭据来源**。每个 biz 分片（shard / silo）一份连接凭据文件，
**只放在这里或生产的 secret manager（Vault / KMS / K8s Secret），绝不进 placement 映射表**。

## 为什么凭据和 placement 分离

- **placement 表**（`tenant → placement key`，租户维护）只回答「租户 X 在哪片」——
  一个 key（如 `shard-0` / `silo-big`），不含任何账密。
- **secrets**（本目录 / vault）回答「`shard-0` 这片怎么连」——url / 账号 / 密码。

两者解耦后：轮换密码不动 placement 表；placement 表即使泄露也拿不到库的访问权；
审计边界清晰（谁能读 secrets ≠ 谁能改路由）。这与 Hibernate MultiTenantConnectionProvider /
Azure Shard Map Manager（catalog 存 location 不存 credential）/ Vault 的分层一致。

## 文件约定

每片一个 `<placement-key>.env`，键固定三项（池参数走 `batch.datasource.business` 公共默认）：

```
BIZ_SHARD_URL=jdbc:postgresql://<host>:<port>/batch_business?currentSchema=biz&reWriteBatchedInserts=true
BIZ_SHARD_USERNAME=<user>
BIZ_SHARD_PASSWORD=<password>
```

`*.env` 已被 `.gitignore` 忽略（真凭据不入库）；仓库只提交 `*.env.example` 模板。

本地起两片：

| placement key | 实例 | 宿主机端口 | 模板 |
|---|---|---|---|
| `shard-0` | postgres-primary 的 batch_business（基线库） | 15432 | `shard-0.env.example` |
| `shard-1` | postgres-biz-shard-1（profile `biz-shard`） | 15442 | `shard-1.env.example` |

```sh
cp secrets/biz-shards/shard-0.env.example secrets/biz-shards/shard-0.env
cp secrets/biz-shards/shard-1.env.example secrets/biz-shards/shard-1.env
# 按需改账密;dev 默认账密已填好可直接用
```

## 起第二片 + 验证路由

```sh
# 1) 起 shard-1（建库 + biz schema + biz 表，profile 隔离不扰基线）
docker compose --profile biz-shard up -d postgres-biz-shard-1

# 2) 验证两片 schema 就绪 + 路由解析（用真实 resolver + multiShard 跑实例）
scripts/local/verify-biz-shard.sh
```

## 账户模型（每片 PG 内按用途拆角色,least privilege）

biz 库**不**该用 platform 的 superuser(`batch_user`)读写——superuser 被 RLS 豁免,等于隔离失效。
每片 PG 内按用途拆四个角色(写角色见 `rls-phase-a.sql`,只读排故角色见 `diagnostic-readonly-role.sql`):

| 角色 | 用途 | 权限 | 谁用 |
|---|---|---|---|
| `batch_business_writer` | 应用读写 | DML on biz.*,**RLS 生效** | worker 路由数据源(本目录 secrets 里的账户) |
| `batch_business_admin` | 跨租聚合 / forensic | DML,**BYPASSRLS** | 平台,审计,严禁给 worker |
| `batch_business_readonly` | 单租户排故 | **SELECT only**,RLS 生效 | 人,排查单租户问题(连上后 `SET LOCAL app.tenant_id`) |
| `batch_business_readonly_all` | 跨租户排故 / 对账 | **SELECT only**,BYPASSRLS | 人,跨租排查,审计 + 限人 |

要点回答常见疑问:
- **业务库用业务账户读写吗?** 是——prod 用 `batch_business_writer`(RLS 生效),不是 superuser。
- **需要给 batch 授权吗?** 是——每片 PG 都要跑 grants(`rls-phase-a.sql` 已含),新片 provision 时必跑,否则
  `batch_business_writer` 没权限。授权是 per-shard 的 provisioning 步骤,不靠应用。
- **排故用哪个账户?** 单租户问题用 `batch_business_readonly`;跨租户/对账用 `batch_business_readonly_all`。
  **绝不用 writer 排故**(会误写),**更不用 superuser**(RLS 豁免=看穿所有租户且能写)。

> ⚠️ 本地 dev 的 `shard-*.env.example` 为简化用了 `batch_user`(superuser)——**仅 dev**。prod 必须把
> `BIZ_SHARD_USERNAME` 换成 `batch_business_writer`(并先在该片跑 rls-phase-a + diagnostic-readonly-role)。

每片 provision 角色 + 授权:

```sh
SHARD_URL="$(grep BIZ_SHARD_URL secrets/biz-shards/shard-1.env | cut -d= -f2- | tr -d '\"')"
psql "$SHARD_URL" -f scripts/db/business/rls-phase-a.sql            # writer/admin + grants + RLS
psql "$SHARD_URL" -f scripts/db/business/diagnostic-readonly-role.sql  # 只读排故角色
```

## RLS（pooled-sharding 内租户隔离）

分片路由（本目录）与片内 RLS 是正交两层：路由决定「哪片」，RLS 决定「片内只看本租户行」。
shard-1 init 默认**不**施加 RLS（与 dev primary 默认态一致）。要施加：

```sh
psql "$(grep BIZ_SHARD_URL secrets/biz-shards/shard-1.env | cut -d= -f2-)" \
  -f scripts/db/business/rls-phase-a.sql
```
