# Runbook · biz 多租户分片路由(tenant-routing / Tiered)

> 应用层把租户路由到不同 biz PG 实例的能力。**自研、零依赖**(不引 Citus / ShardingSphere),
> 走 Spring `AbstractRoutingDataSource`。设计背景见 `docs/plans/biz-tiered-tenancy-plan-2026-06-14.md`
> 与 `docs/analysis/scaling-state-and-biz-path-2026-06-14.md`。RLS(片内租户隔离)是正交的另一层,
> 见 `docs/runbook/multi-tenant-rls.md`。

## 1. 它解决什么 / 不解决什么

- **解决**:biz 数据层水平扩展——把租户分到多片 PG(池化 shard + 大租户 silo 独占),突破单实例上限。
- **不解决**:控制面吞吐(瓶颈在 orchestrator launch 消费,见 throughput 分析)、平台库(`batch.*`)扩展。
- **为什么不是 Citus**:RLS 在 Citus 分布表上失效(GUC 不跨节点传播,PoC 实证返 0 行/写报错);
  且当前无多租洪峰写墙需求(PG 写有 10-15× 余量)。tenant-routing 用 vanilla PG,对标 Notion(480 片)/ Figma(自研 DBProxy)。

## 2. 三层模型(Tiered)

| 层 | 含义 | 本能力对应 |
|---|---|---|
| Pool | 共享库 + RLS(现状) | 单片(`routing.enabled=false`,默认) |
| Pooled-sharding | 多租户共享一片,片内 RLS | `shard-0..N-1`,hash 路由 |
| Silo | 单租户独占一片 | `silo-*`,placement 表/config 指派 |

一套配置可混用(Tiered):多数租户 hash 进池化片,少数大租户 silo 独占。

### 维护面:谁表驱动 / 谁配置驱动

| 类别 | 内容 | 在哪 | 怎么维护 |
|---|---|---|---|
| **placement 映射** | 租户 → 哪片(`tenant→key`) | 表 `business_tenant_placement` | **console API 在线 CRUD + 审计**,即时生效(最迟一个缓存 TTL) |
| **分片目录 / 拓扑登记** | 有哪些片 + 位置(host/port/db)+ 状态 | 表 `business_shard_catalog` | console API 在线登记(供「分片列表」视图 + placement key 白名单);**但生效仍需 worker 重启重建池** |
| **路由策略** | `enabled` / `placementSource` / `pooledShardCount` / `shardMaximumPoolSize` / `placementCacheTtlMs` | 配置(env/yml) | **改配置 + 重启 worker**;无表、无 console |
| **实际连接池(各片 url + 账密)** | worker 真正连哪些片、用什么凭据 | 配置 `routing.shards[*]` + **secrets/vault** | 改配置/secret + 重启;账密经 secret 后端注入(见 §9),**永不入表** |

> 一句话:**「租户在哪片」是数据 → 表 + console 在线维护;「有哪些片/容量/策略/凭据」是拓扑与容量 → 配置 + secrets,变更是部署事件(改配置/重启),catalog 表只负责登记可见与校验,不负责让运行中的池动态增减。**

## 3. 架构与关键类

```
请求(带 RLS tenant 上下文 RlsTenantContextHolder)
  └─ BusinessRoutingDataSource (extends AbstractRoutingDataSource)
       └─ determineCurrentLookupKey() = resolver.resolve(currentTenant)
            ├─ CONFIG: HashAndSiloPlacementResolver  (hash 取模 + siloOverrides)
            └─ TABLE : DbTablePlacementResolver       (placement 表覆盖 + hash 兜底)
       └─ 选中 placement key → 对应 shard 的 HikariDataSource
```

| 关注点 | 类 / 文件 | 位置 |
|---|---|---|
| placement 接口 | `BusinessPlacementResolver` | batch-common `tenant/routing` |
| hash + silo | `HashAndSiloPlacementResolver` | 同上 |
| 表驱动 + 缓存兜底 | `DbTablePlacementResolver` | 同上 |
| placement 表读(MyBatis) | `BusinessTenantPlacementMapper`(+XML)/ `MyBatisTenantPlacementRepository` / `BusinessTenantPlacementEntity` | batch-common |
| 路由数据源 | `BusinessRoutingDataSource` / `BusinessRoutingDataSourceFactory`(single/multiShard) | 同上 |
| 装配收敛 | `BusinessDataSourceBuilder` / `BusinessPlacementResolverFactory` | batch-common `config` |
| 配置 | `BusinessRoutingProperties`(`batch.datasource.business.routing`) | 同上 |
| placement 表 | `V170__business_tenant_placement.sql` | db/migration |
| shard catalog 表 | `V171__business_shard_catalog.sql`(拓扑登记 + key 白名单源) | db/migration |
| console 管理 API | `ConsoleBusinessTenantPlacement*` / `ConsoleBusinessShardCatalog*`(`/api/console/ops/tenant-placements`、`/shard-catalog`) | batch-console-api |
| worker 接线 | 各 `BusinessDataSourceConfiguration` | import / export / process |

> 只有 import / export / process 三个 worker 持有 biz 数据源;dispatch / atomic / SDK 不碰 biz,无需改。

## 4. 事务与凭据(设计约束)

- **事务**:单租户的一个 task 只落**一片**,事务在该片内完成,**不跨片、无 XA**。路由 key 在事务开始前由
  tenant 上下文确定,事务内稳定。跨片操作不是本能力范畴(批批语义下不需要)。
- **凭据**:每片账密走 **secrets / vault**(`secrets/biz-shards/<key>.env` 或 K8s Secret),
  **绝不入 placement 表**。placement 表只存 `tenant → key`。对标 Hibernate MultiTenantConnectionProvider /
  Azure Shard Map Manager(catalog 存 location 不存 credential)。

## 5. placement 来源:CONFIG vs TABLE

`batch.datasource.business.routing.placement-source`:

- **CONFIG**(默认):hash 取模(`pooled-shard-count`)+ `silo-overrides` Map。改 placement = 改配置 + 重启。
- **TABLE**:读 `batch.business_tenant_placement`(platform 库,运维/租户在线维护),**表命中优先**,
  未登记的租户退回 hash。整表缓存 `placement-cache-ttl-ms`(默认 5s,**0=每次查库仅测试用**),迁片登记最迟一个 TTL 生效。
  降级区分:**已有缓存 + 重载失败 → 保留 stale**(silo 路由仍正确,不被 hash 误路由);**冷启动读失败 → 退 hash**
  (此时表里本无 silo 指派可丢)。`TenantPlacementRepository` 读失败抛出由 resolver 据此分支。

迁片/silo 指派(TABLE 模式):
```sql
INSERT INTO batch.business_tenant_placement (tenant_id, placement_key, updated_by)
VALUES ('big-corp', 'silo-big', 'ops:alice')
ON CONFLICT (tenant_id) DO UPDATE SET placement_key = EXCLUDED.placement_key, updated_at = now();
```

## 6. DB 账户模型(每片 PG,least privilege)

| 角色 | 用途 | 权限 | 谁用 |
|---|---|---|---|
| `batch_business_writer` | 应用读写 | DML on biz.*,**RLS 生效** | worker 路由数据源 |
| `batch_business_admin` | 跨租聚合 / forensic | DML,**BYPASSRLS** | 平台,审计;禁给 worker |
| `batch_business_readonly` | 单租户排故 | **SELECT only**,RLS 生效 | 人(连后 `SET LOCAL app.tenant_id`) |
| `batch_business_readonly_all` | 跨租户排故 / 对账 | **SELECT only**,BYPASSRLS | 人,审计 + 限人 |

脚本:`scripts/db/business/rls-phase-a.sql`(writer/admin + 授权 + RLS)、
`scripts/db/business/diagnostic-readonly-role.sql`(两只读角色)。

- **应用账户用 `batch_business_writer`,不要 superuser**(superuser 被 RLS 豁免 = 隔离失效)。
  dev 的 `shard-*.env.example` 用 `batch_user`(superuser)**仅 dev 简化**,prod 必须改。
- **排故**:单租户问题用 `readonly`,跨租用 `readonly_all`;**绝不用 writer 排故**(会误写)。

## 7. 开一片(本地 / 幂等)

```sh
scripts/local/provision-biz-shard.sh <placement-key> <host-port>
# 例: scripts/local/provision-biz-shard.sh shard-1 15442
#     scripts/local/provision-biz-shard.sh silo-big 15443
```
一次完成:起 PG → 建库建表 → rls-phase-a(角色+授权+RLS)→ 只读角色 → 写 secret(存在不覆盖)。可重跑。
compose 里 `postgres-biz-shard-1`(profile `biz-shard`)是 shard-1 的「生产形态」声明。

## 8. 启用多片路由(配置)

默认 `enabled=false` = 单片无损(全租户落 shard-0 = 现库,零行为变更)。开启示例(env / relaxed binding):

```
BATCH_DATASOURCE_BUSINESS_ROUTING_ENABLED=true
BATCH_DATASOURCE_BUSINESS_ROUTING_PLACEMENT_SOURCE=TABLE   # 或 CONFIG
BATCH_DATASOURCE_BUSINESS_ROUTING_POOLED_SHARD_COUNT=2
# 每片凭据从 secrets 注入(key 必须含 default shard-0):
BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_KEY=shard-0
BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_URL=...
BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_USERNAME=batch_business_writer
BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_PASSWORD=...
BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_1_KEY=shard-1
BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_1_URL=...
...
```
约束:`shards` 的 key 必须覆盖 resolver 可能返回的全部 key(含 default `shard-0` + 各 silo);
`pooled-shard-count` 须与 `shard-0..N-1` 数量一致;缺 default key 装配直接拒绝。

## 9. 凭据注入(secret 后端 → env → 配置)

**应用不集成任何 secret SDK**,只认 Spring 配置 `batch.datasource.business.routing.shards[*].url/username/password`。
凭据来源是外部 secret 后端,经**标准 env/文件注入**喂进上面那组配置——故意不把系统绑死到某个 vault,
谁部署谁用自己的后端注入。链条:

```
外部 secret 后端(Vault / AWS Secrets Manager / K8s Secret)
   → 注入成 env 变量 / 挂载文件
      → Spring relaxed binding 绑定 BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_n_PASSWORD 等
         → BusinessRoutingProperties.shards[*]
```

**绝不**把账密写进 placement 表 / shard catalog 表(catalog 的 `secret_ref` 只是引用名,指向后端里的条目,不是密钥)。

- **dev**:`secrets/biz-shards/<key>.env` 本地文件(gitignore),由 `provision`/`verify` 脚本读成 env。非外部系统。
- **prod 示例 ① K8s Secret → env**:

  ```yaml
  env:
    - name: BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_1_PASSWORD
      valueFrom:
        secretKeyRef: { name: biz-shard-1, key: password }
    - name: BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_1_USERNAME
      valueFrom:
        secretKeyRef: { name: biz-shard-1, key: username }
  ```

- **prod 示例 ② Vault → env/文件**:Vault Agent / CSI Provider 把 secret 渲染成 env 或挂载文件,
  应用读到的仍是同一组 `routing.shards[*]` 配置,代码无感知。轮换密码只动后端 + 重启(或热加载)worker,
  不动 placement / catalog 表。

> 账户用 `batch_business_writer`(非 superuser,见 §6);secret 后端里存的就是各片 writer 的账密。

## 10. 验证

```sh
scripts/local/verify-biz-shard.sh
```
对两片真实 PG(shard-0=primary、shard-1=provision)跑活体:经路由 DS 读回每片 `__shard_identity`,
证明每个租户连接物理落到 resolver 选定实例;并证明 TABLE 模式表覆盖 hash、未登记走 hash。
单测:`HashAndSiloPlacementResolverTest` / `BusinessRoutingDataSource*Test` / `DbTablePlacementResolverTest`。
活体测试 env-gated(`BIZ_SHARD_0_URL` 未设自动跳过),不进常规 CI。

## 11. 生产激活清单(代码外的 ops 动作)

代码 / 本地基础设施已闭环;真正上量需要:

1. **N 个真实 PG 主机**(非单机多容器);各跑 provision 的等价(建库表 + rls-phase-a + 只读角色)。
2. **secrets 接真实后端**(Vault / KMS / K8s Secret),应用账户切 `batch_business_writer`(非 superuser)。
3. **placement 表填真实租户**(TABLE 模式):silo 指派 / 迁片登记走运维流程。
4. **容量与再平衡**:pooled 片满了加片需 rehash(`pooled-shard-count` 变更会改 hash 落点)——
   迁移期用 placement 表把受影响租户钉到原片,避免 rehash 抖动;新租户走新分母。
5. **监控**:每片连接池 / 慢查 / biz 大表增长告警(已有增长告警,见监控)。

## 12. 故障排查

| 现象 | 排查 |
|---|---|
| 路由到错片 | 查 `business_tenant_placement` 该租户行;TABLE 缓存未刷可等一个 TTL 或重启;确认 `pooled-shard-count` 与片数一致 |
| 某租户看不到数据 | 大概率连错片或 RLS 未 `SET LOCAL app.tenant_id`;用 `batch_business_readonly` 连对应片核 |
| 启用多片后启动失败 | `shards` 缺 default key `shard-0`;或某片凭据/URL 错、片未 provision |
| placement 表读不到 | fail-open 会退 hash + 打 WARN `load business_tenant_placement failed`;查 platform 库表是否建(V170)与授权 |
| 排故该用哪个账户 | 单租户 `batch_business_readonly`;跨租 `batch_business_readonly_all`;**不要** writer / superuser |

## 13. biz 表分区(片内,与分片正交)

分片把租户摊到多片;**分区**解决「单片内单表仍大」。两者正交,分片不强制分区。PG 声明式分区要求
**分区键必须进 PK + 每个 UNIQUE**——这是改造点与阻塞所在。`create_biz_tables.sql` 是规范 schema(biz 不走 Flyway)。

已落地(`create_biz_tables.sql`,真实 PG 验证幂等/FK/路由):

| 表 | 方案 | PK | 说明 |
|---|---|---|---|
| `customer_account` | HASH(tenant_id) ×4 | (tenant_id, id) | 实体表;UNIQUE 已含 tenant_id,**幂等不变** |
| `settlement_batch` | HASH(tenant_id) ×4 | (tenant_id, id) | FK 父;复合 PK 让子表 FK 能引用 |
| `settlement_detail` | HASH(tenant_id) ×4 | (tenant_id, id) | FK 改复合 `(tenant_id, batch_id)→settlement_batch(tenant_id,id)` |
| `risk_score` | RANGE(score_date) + DEFAULT | (tenant_id, score_date, id) | UNIQUE 已含 score_date,**幂等不变**;后续可按月切分区 + archive |
| `transaction` | RANGE(txn_date) + DEFAULT | (tenant_id, txn_date, id) | UNIQUE → `(tenant_id, txn_no, txn_date)`;ON CONFLICT 同步;txn_date 对 txn_no 不变 → **幂等等价** |
| `risk_alert` | RANGE(alert_date) + DEFAULT | (tenant_id, alert_date, id) | 纯导出源无 upsert;UNIQUE → `(tenant_id, alert_id, alert_date)`,**零幂等风险** |

> HASH 选 tenant_id 的关键好处:所有 biz UNIQUE 本就含 tenant_id → **只重建 PK,不动 UNIQUE/ON CONFLICT/模板** = 幂等中性。
> RANGE 时序表里,risk_score/risk_alert 的 UNIQUE 加日期不影响幂等(score_date 本就在;risk_alert 不 upsert);
> **唯一动幂等语义的是 transaction**:UNIQUE 加 `txn_date`,模板 `conflictColumns` 同步成 `[tenant_id, txn_no, txn_date]`
> (改动点:`batch-e2e-tests/.../multi-tenant-seed.sql` ×3、`docs/test-data/sim-e2e-bootstrap.sql`、手工 seed `sim-stage3c-export-source.sql`)。
> **prod 自定义模板**:用户侧 `IMP-TRANSACTION-CSV` 的 conflictColumns 须同样加 `txn_date`,否则 ON CONFLICT 不匹配新约束。

全 6 表已落地并在真实 PG 验证(幂等/FK/HASH 路由/RANGE 落分区)。

**存量数据迁移**:`create_biz_tables.sql` 的 `CREATE TABLE IF NOT EXISTS` 只对新库/新片生效,**不会重分区已存在的非分区表**。
已上线的 biz 库要分区须走维护窗口的重建迁移(建分区新表 → 拷数据 → 切换 → 重建 FK),先在副本演练,
动手前 `grep -ri 'on conflict'` 全量核对幂等(项目硬规则)。
