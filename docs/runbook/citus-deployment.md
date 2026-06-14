# Citus 部署 / 切换运维手册

> 状态:**待 go 决策**——Citus 是否启用取决于多租户并发洪峰 benchmark(`citus-introduction-plan-2026-06-06.md` §0 门槛③,**尚未跑**)。本文是"决定启用后照着跑"的 SOP,不是现在的动作。
> 适用分支:`feature/partition-readiness`(Citus 铺路常驻分支)。
> 前置阅读:`docs/backlog/citus-introduction-plan-2026-06-06.md`(选型/拓扑/分类)、`docs/design/partition-idempotency-decision.md`(幂等降级)、`docs/analysis/citus-poc-gates-2026-06-11.md`(两道生死门 POC)。

## 0. 双栈一句话

同一份代码两栈通用,切换 = **3 个动作**:
1. datasource 指向 **coordinator**(默认单机指向 plain PG `15432`)
2. 开 `batch.citus.enabled=true`(启动期 fail-fast 校验 GUC)
3. 跑一次 `scripts/db/citus/01-distribute.sql`(把表分布化;只做一次)

不做以上 3 步 = 纯 plain PG 单机(已全量回归验证,e2e 41/41)。**不引 Citus 时本文全程不用看。**

## 1. 集群拓扑

```
app(orchestrator/trigger/worker/console)
        │ 全连 coordinator(等同今天的单 PG)
        ▼
  citus-coordinator  (localhost:25432)  ← 元数据 + 路由 + 计划
        ├── citus-worker-1   各持一部分分片(hash(tenant_id))
        └── citus-worker-2
```
- 本地 dev 起集群:`bash scripts/local/citus-cluster.sh up`(1 coord + 2 worker,自动注册 + 配 GUC)
- 生产:coordinator + N worker,每 worker 可选流复制副本做 HA(起步可单写)。**生产 docker-compose / Helm 产物属部署交付物,见 §6。**

## 1b. 节点横向扩展在 Citus 下的边界

**应用层不变**:orchestrator / trigger / worker / console 在 Citus 版**与单机版完全相同**——同一套双栈代码,仍是无状态、按 Kafka 分区 + outbox 分片 + ShedLock + StatefulSet 稳定身份水平扩。Citus 只动数据层,不改应用层无状态性。

**Citus 把数据层共享天花板摊开**(单 PG 的写入/存储瓶颈 → 按 `tenant_id` 分片到多 worker 节点),但应用节点的扩展因此多 3 个数据层约束:

| # | 约束 | 含义 | 详见 |
|---|---|---|---|
| ① | **连接扇出** | app Hikari 池 **× 分片数**在 coordinator 放大,默认配置秒打爆 `too many clients`;扩 app 节点不再线性加连接,须守三元组 `Σ(app 池) ≤ citus.max_shared_pool_size ≤ worker max_connections` | §2 / §4;实测 `docs/analysis/citus-w8-runtime-findings-2026-06-11.md` §连接三元组 |
| ② | **协调器是新汇聚点** | 所有 app 查询经 coordinator 路由/扇出(等同今天的单 PG 入口,见 §1)。它**路由不执行**,比单 PG 扛全部活轻,但仍是中心漏斗——不是"无限免费扩"。HA 走 Patroni 原生 Citus 模式(coordinator 也要主备),不是单点 | §1 |
| ③ | **业务库分布化是计划、未验** | 当前 sim/POC(`scripts/sim/env-citus.sh`)**只把平台 `batch.*` 分布式化**,`biz.*` 仍走原单机 PG(`batch_business_part`)——业务数据扩展这块**还是单机 PG 的故事**。§3.4 部署计划打算把 business 也 distribute,但**尚未在真集群验证**,属待验缺口 | §3.4 |

**与吞吐结论的闭环**:当前实测瓶颈在控制面(launch 消费并发 / claim 争用,~20→33 jobs/s,见 `docs/verifications/multitenant-peak-single-node-ceiling-2026-06-13.md`),**不在 PG**。所以上面数据层的横向扩展收益,要等控制面并发先拉满(launch 消费并发 + outbox 分片)、瓶颈真的回到 PG 之后才兑现——这也是门槛①②"单机榨干"未达前不启用 Citus 的原因。

## 2. 必配 GUC(coordinator,`ALTER SYSTEM` + reload)

这三个**漏配会出静默故障**,`CitusRuntimeStartupCheck` 启动期会拦前两个:

| GUC | 值 | 漏配后果 |
|---|---|---|
| `citus.propagate_set_commands` | `local` | 🔴 `SET LOCAL app.tenant_id` 不透传 worker → **RLS 读静默返空、不报错** |
| `citus.enable_unsafe_triggers` | `on` | workflow_run 租户一致性触发器被拒;带触发器的分布式表写入失败 |
| `citus.max_shared_pool_size` | 按 worker `max_connections` / app 池 / 分片数三元组定(dev 用 25) | coordinator→worker 连接扇出打爆 `too many clients` |

```sql
ALTER SYSTEM SET citus.propagate_set_commands = 'local';
ALTER SYSTEM SET citus.enable_unsafe_triggers = on;
ALTER SYSTEM SET citus.max_shared_pool_size = 25;   -- 生产按容量调
SELECT pg_reload_conf();
```

## 3. 部署步骤(go 决策后)

1. **起集群** + 注册 worker(`citus_add_node`)+ 配 §2 GUC。
2. **建库 + 跑 schema**:coordinator 上建 `batch_platform` / `batch_business`,Flyway V1..V179 原生跑通(复合 PK + 月分区都是标准 PG,coordinator 直接受)。
3. **分布化**:`psql -d batch_platform -f scripts/db/citus/01-distribute.sql`——49 表 distributed(按 tenant_id)+ 32 reference,内含 6 条 Citus 约束处置(FK 序、暂存、unsafe_triggers)。**一次性、幂等自检**。
4. **business 库**:手工脚本建表(`scripts/db/business/*`)+ RLS(`rls-phase-a.sql`)后同样分布化。
5. **app 切配置**:datasource url → `jdbc:postgresql://<coord>:25432/batch_platform`;`batch.citus.enabled=true`(见 §5)。
6. **启动**:`CitusRuntimeStartupCheck` 校验 GUC + 确是 Citus 库,不符 fail-fast。日志见 "Citus 运行时自检通过"。
7. **冒烟**:触发一个 import job,验 job_instance/outbox 落分布式表、Kafka 流转、worker CLAIM 终态 SUCCESS。

## 4. 连接扇出调优

app Hikari 池 × 分片数会在 coordinator→worker 放大。三元组:`worker.max_connections` ≥ `citus.max_shared_pool_size` ≥ 覆盖各 app 池峰值之和。`max_shared_pool_size` 可热载(`pg_reload_conf`),先保守再压测上调。

## 5. 开关 `batch.citus.enabled`

| 项 | 值 |
|---|---|
| 配置 key | `batch.citus.enabled` |
| 默认 | **false**(单机/普通 PG,`CitusRuntimeStartupCheck` 不加载) |
| 开启 | `true` → 启动期 fail-fast 校验 §2 前两个 GUC + `pg_extension` 含 citus |
| env | `BATCH_CITUS_ENABLED` |
| 风险 | 🟢 低(只加启动校验,不改运行逻辑;漏配宁可拒启动也不静默错) |
| 回滚 | 设回 false + datasource 指回单机 PG;**不跑 distribute 即纯 PG** |

> 运行时适配(FOR UPDATE 租户路由、NOT EXISTS 幂等、复合 PK)是**无条件写法、两栈通用**,不受此开关控制——开关只管启动校验。

## 6. 部署产物缺口(待 go 决策 + 部署分支)

**目前只有本地 dev 脚本 `citus-cluster.sh`,没有生产 docker-compose / Helm 的 Citus 产物。** 按 CLAUDE.md 分支纪律,`docker-compose*.yml` / `helm/*` 属 `feature/docker-deploy`,**不进本分支**。go 决策后需在部署分支补:
- `docker-compose.citus.yml`:coordinator + N worker + 卷 + healthcheck + §2 GUC(command flags)+ 启动后 `citus_add_node` 注册 + 跑 distribute 的 init。可直接把 `citus-cluster.sh` 的逻辑声明化。
- Helm:coordinator StatefulSet + worker StatefulSet(N 副本)+ 注册 Job + GUC ConfigMap。
- 备份/PITR:Citus 下每 worker 独立备份 + coordinator 元数据备份,见 `backup-and-pitr.md` 需补 Citus 章节。

## 7. 回滚(切回单机)

1. app datasource 指回单机 PG `15432`,`batch.citus.enabled=false`,重启。
2. 单机 PG 上同样跑 Flyway V1..V179(复合 PK + 分区,纯 PG 兼容)——**不跑 distribute**。
3. 数据迁移:Citus → 单机需 `pg_dump`/逻辑导出回灌(distributed 表 dump 出来是普通表)。

## 关联
- `docs/backlog/citus-introduction-plan-2026-06-06.md` — 选型/拓扑/分类/决策门槛(权威源)
- `docs/design/partition-idempotency-decision.md` — outbox 幂等降级 + 守护
- `docs/analysis/citus-poc-gates-2026-06-11.md` / `citus-w8-runtime-findings-2026-06-11.md` — POC + 运行时实测
- `scripts/local/citus-cluster.sh` / `scripts/db/citus/01-distribute.sql` — dev 集群 + 分布化脚本
