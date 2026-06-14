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

## RLS（pooled-sharding 内租户隔离）

分片路由（本目录）与片内 RLS 是正交两层：路由决定「哪片」，RLS 决定「片内只看本租户行」。
shard-1 init 默认**不**施加 RLS（与 dev primary 默认态一致）。要施加：

```sh
psql "$(grep BIZ_SHARD_URL secrets/biz-shards/shard-1.env | cut -d= -f2-)" \
  -f scripts/db/business/rls-phase-a.sql
```
