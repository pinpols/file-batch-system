# Backlog: Citus 引入方案(运维侧改造为主,benchmark 驱动后启用)

> 状态:**待 benchmark 触发**——单机榨干仍不达目标后才启用。本文是"决定要上之后"的可执行方案,**不是现在的动作**。
> 日期:2026-06-06　模块:docker / K8s / DBA / 运维(应用层改动预计极小)
> 前置:[single-node-throughput-optimization-2026-06-06](./single-node-throughput-optimization-2026-06-06.md) §1.6 / §1.8(触发门槛)
> 对照:[streaming-large-file-import-export-2026-06-06](../verifications/streaming-large-file-import-export-2026-06-06.md) §5.3(为什么 Citus 是终局)

## TL;DR

1. **何时启用**:Tier-A + Tier-B 全做完仍不达目标,**且**有多租户并发洪峰实测信号(单租户单流 Citus 不缩耗时)。
2. **改的是运维,几乎不动应用**:Citus 是 PG 扩展,JDBC 驱动 / SQL / RLS / ON CONFLICT / jsonb 全照旧;主要工作在 docker-compose / K8s / DBA。
3. **路径二选一**:**A** 自托管开源 Citus(免费、可控)/ **B** Azure Cosmos DB for PostgreSQL(托管 Citus,免 AGPL + 免运维)。
4. **唯一阻塞前置 POC**:**RLS 过 coordinator**——`SET LOCAL app.tenant_id` 必须能透传到 worker 分片;**这条不过,整条路径作废**。
5. **数据迁移可在线做**:`create_distributed_table` 原地分片,业务停机最小化。

## 章节速览

| 我想…… | 看这里 |
|---|---|
| 知道什么时候才该读本文档 | §0 决策门槛 |
| 选自托管还是托管版 | §1 路径选择 |
| 看新拓扑长什么样 | §2 拓扑 |
| 知道哪些表怎么分片 | §3 Schema 分类 |
| 看 docker-compose 怎么改 | §4 部署改造 |
| 看 K8s 部署形态 | §5 K8s 形态 |
| 看应用层改动 | §6 应用层 |
| 看那条阻塞 POC 怎么做 | §7 RLS POC |
| 看数据迁移流程 | §8 迁移 |
| 看监控 / 备份 / HA | §9 |
| 看灰度 / 回滚 | §10 |
| 看不做什么 | §11 |
| 找相关代码 / 配置 | §12 |

## 0. 决策门槛(读本文档前先过这关)

**只有以下全部满足时才执行本文档**:

| 门槛 | 状态判定 |
|---|---|
| ① single-node-throughput §1.6 Tier-A 跑完 | 数据已上传至文档 |
| ② Tier-B(B1 多值 INSERT / B2 真 COPY)跑完仍不达单流 < 15s | 数据已上传至文档 |
| ③ 实测**多租户并发洪峰**确认 Citus 是甜点场景(N 个租户同时各跑批) | benchmark 报告 |
| ④ §7 **RLS POC 通过** | POC 报告 |
| ⑤ 法务确认 AGPL v3 政策(仅自托管路径需要) | 法务批复 |

**不满足任一项 → 不要启用本文档**。即便满足,优先 §1 路径 B(托管),自托管是次选。

## 1. 路径选择(自托管 vs 托管)

| 维度 | A. 自托管开源 Citus | B. Azure Cosmos DB for PostgreSQL(托管 Citus) |
|---|---|---|
| License | AGPL v3(自用安全;详见 §11 法务说明) | 商业服务,无 AGPL 风险 |
| 部署 | docker-compose / K8s + 我们自己运维 | Azure 全托管,按节点付费 |
| 运维负担 | 高(节点扩缩 / 升级 / 备份 / HA 全自管) | 低(微软全包) |
| 成本 | 仅硬件 + 工时 | $$$(按 vCore + 存储计费) |
| 升级 / 安全补丁 | 自己跟 | 微软自动 |
| 数据主权 / 合规 | 自托管 | Azure 数据驻留(看 region) |
| 与现有 docker-compose 集成 | 顺,改 §4 即可 | 需把业务库迁出宿主 docker |
| **推荐顺序** | 团队有 DBA + 拒绝云厂商绑定 → A | 大多数情况 → **B**(尤其团队规模小) |

**默认推荐 B**,除非有具体反对理由(数据合规 / 已自管 DBA / 云成本红线)。

---

## 2. 拓扑改造(自托管路径 A 视角)

### 2.1 当前拓扑(单 PG 主从)

```
            ┌───────────────────────┐
写 ───▶     │ batch-postgres-primary │ port 15432
            │ batch_platform 库      │
            │ batch_business 库      │
            └──────────┬─────────────┘
                       │ 流复制
            ┌──────────▼─────────────┐
读 ───▶     │ batch-postgres-replica │ port 15433  (仅 console-api)
            └────────────────────────┘
```

### 2.2 新拓扑(Citus 分片集群)

```
            ┌───────────────────────────┐
写/读 ───▶  │ citus-coordinator        │ port 15432  ← app 连这里,等同今天的 PG
            │   元数据 + 路由 + 计划   │   batch_platform 库(局部表,不分片)
            └───┬──────┬──────┬────────┘   batch_business 库(分布式表 + 引用表)
                │      │      │
        ┌───────▼─┐ ┌──▼──┐ ┌─▼─────┐
        │ worker-1│ │... │ │worker-N│  各持一部分分片
        └────┬────┘ └──┬──┘ └────┬───┘
             │ 流复制(可选,每 worker 一只 replica)
        ┌────▼────┐ ┌──▼──┐ ┌────▼───┐
        │ wkr-1-r │ │... │ │ wkr-N-r│  worker 副本(HA)
        └─────────┘ └────┘ └────────┘
```

- **Coordinator**:应用唯一连接点(替换今天的 `localhost:15432`),存元数据 + 接管查询路由 + 跨片协调
- **Worker**:N 个,每个是真 PostgreSQL,各存一部分分片(按 `hash(tenant_id)`)
- **Worker replica**(可选):每个 worker 一只流复制副本做 HA;**起步可不带,先单写**
- **现有 console-api 的 read replica**:可保留对接 coordinator 的只读副本(coordinator 也能流复制)

### 2.3 起步规模建议

| 阶段 | Coordinator | Worker 数 | Worker replica |
|---|---|---|---|
| POC / 测试 | 1 | 2 | 无 |
| 准生产 | 1 | 4 | 无(灰度无 HA) |
| 生产首发 | 1 | 4-8 | 每 worker 1 只(HA) |
| 扩展 | 1 | 按需加 worker;Citus 在线 rebalancer 自动均衡 | 同步加 |

> Coordinator 单点:Citus 13 起官方支持 coordinator HA(主备 + 自动 failover);**起步可不开**,先单 coordinator。

---

## 3. Schema 分类(哪些表怎么分布)

Citus 三种表分类,改造的核心活就在这——**应用零改动的前提是分类正确**。

### 3.1 决策矩阵

| 类型 | 适用 | 现有表举例 |
|---|---|---|
| **Distributed table**(按 `tenant_id` 分片) | 多租业务大表;PK/UNIQUE 已含 tenant_id | `biz.*` 全部业务表、`batch.job_instance` / `pipeline_instance` / `outbox_event` / `dead_letter_task` 等 |
| **Reference table**(全分片复制) | 小字典 / 配置表;无 tenant_id 维度 | `batch.step_registry` / `batch.biz_table_schema` / `batch.file_template_config`(共享配置)/ `batch.shedlock` |
| **Local table**(只在 coordinator) | 仅 coordinator 用的元数据 | `pg_catalog.*`(自动)/ Quartz `qrtz_*` 表(看是否真需分布) |

> 选取逻辑:**带 tenant_id → distributed by tenant_id**;**不带 + 小表 → reference**;**只 coordinator 访问 → local**。
> CLAUDE.md 已强制「所有 UNIQUE/PRIMARY 必须含 tenant_id」→ distributed 改造前置条件已满足。

### 3.2 Colocation(本系统关键)

按同一键(tenant_id)分片的表必须 **colocate(共置)**,使同租户的 JOIN / 跨表事务**落单 worker 本地执行**(ACID、无分布式开销)。

```sql
-- 第一张分布式表(隐式建 colocation 组)
SELECT create_distributed_table('biz.customer_account', 'tenant_id');

-- 后续分布式表显式 colocate 到同组
SELECT create_distributed_table('biz.transaction', 'tenant_id',
        colocate_with => 'biz.customer_account');
SELECT create_distributed_table('biz.wide_demo', 'tenant_id',
        colocate_with => 'biz.customer_account');
-- ... 所有 biz.* + batch.job_instance / pipeline_instance / outbox_event 同 colocation
```

**含义**:同租户的 customer + transaction + outbox 写都落同一 worker,事务 + FK + RLS 全本地 → 状态机主链零分布式代价。

### 3.3 跨片不友好的查询(需识别 + 改造)

| 不友好场景 | 改造 |
|---|---|
| `SELECT FROM pipeline_instance UNION SELECT FROM workflow_run`(已被 CLAUDE.md 禁) | 已禁,无需动 |
| 无 tenant_id 的全表 SELECT(管理后台审计) | 走 console-api read replica,不走 coordinator |
| 跨租户聚合(BI 报表) | 走专门的离线数仓 / Citus `citus_run_on_all_workers` |

---

## 4. 部署改造(docker-compose 演进路径)

### 4.1 镜像选择

```yaml
# 之前
image: postgres:17

# 之后(自托管路径 A)
image: citusdata/citus:13.0      # Citus 13 + PG 17 内置
```

> Citus 13 与 PG 17 配套,2025-09 发布;若团队 PG 仍是 16 → 用 `citusdata/citus:12.1`(PG 16)。

### 4.2 新 docker-compose 节点(POC 阶段最小集)

```yaml
services:
  citus-coordinator:
    image: citusdata/citus:13.0
    container_name: batch-citus-coordinator
    ports: ["15432:5432"]
    environment:
      POSTGRES_PASSWORD: ${PG_PASS}
      POSTGRES_DB: batch_business           # 业务库(主战场)
      CITUS_ROLE: coordinator
    volumes: ["citus-coord-data:/var/lib/postgresql/data"]

  citus-worker-1:
    image: citusdata/citus:13.0
    container_name: batch-citus-worker-1
    environment: { POSTGRES_PASSWORD: ${PG_PASS}, CITUS_ROLE: worker }
    volumes: ["citus-w1-data:/var/lib/postgresql/data"]

  citus-worker-2:
    image: citusdata/citus:13.0
    container_name: batch-citus-worker-2
    environment: { POSTGRES_PASSWORD: ${PG_PASS}, CITUS_ROLE: worker }
    volumes: ["citus-w2-data:/var/lib/postgresql/data"]

  # 保留原 platform 库在普通 PG(metadata,不分片)—— 可选
  batch-postgres-primary:
    image: postgres:17
    container_name: batch-postgres-primary  # 仅 batch_platform
    ports: ["15431:5432"]
    # ...
```

### 4.3 注册 worker(coordinator 内一次性执行)

```sql
-- 在 coordinator 上:
CREATE EXTENSION citus;
SELECT citus_add_node('citus-worker-1', 5432);
SELECT citus_add_node('citus-worker-2', 5432);
SELECT * FROM citus_get_active_worker_nodes();   -- 验证
```

### 4.4 应用连接改造(最小)

| 模块 | 之前 | 之后 |
|---|---|---|
| platform 库(orchestrator / trigger / worker 状态机)| `jdbc:postgresql://primary:15432/batch_platform` | **不变**(继续连原 PG)或 → `citus-coordinator` 拆迁 |
| business 库(worker 业务表读写)| `jdbc:postgresql://primary:15432/batch_business` | **`jdbc:postgresql://citus-coordinator:15432/batch_business`** |
| console-api read replica | `jdbc:postgresql://replica:15433/batch_platform` | **不变** 或 → 对接 coordinator replica |

**推荐拆迁策略**:**业务库(`batch_business`)优先上 Citus**,平台库(`batch_platform`)第一阶段保留原 PG;状态机/控制面不涉及大数据量,分片收益小、风险大,后续再说。

---

## 5. K8s 形态(生产路径,非 docker-compose)

如果上 K8s,推荐用 **CloudNativePG + Citus 镜像** 或直接 **CrunchyData/Citus Operator**:

| 资源 | 角色 |
|---|---|
| `StatefulSet citus-coordinator`(1 replica)| coordinator |
| `StatefulSet citus-worker`(N replicas)| worker 集群,PVC 各自独立 |
| `Service citus-coordinator`(ClusterIP)| app 唯一连接点 |
| `Service citus-worker-headless` | coordinator 内部解析 worker |
| `PodDisruptionBudget` | 防滚动升级把所有 worker 一次拉走 |
| `NetworkPolicy` | coordinator → worker 内部网络,外部禁直连 worker |

**关键**:worker 间也要互通(部分查询有 worker-to-worker shuffle),不能锁死。

---

## 6. 应用层改动(预计极小)

| 改动 | 说明 |
|---|---|
| ✅ JDBC URL(`batch_business` 业务库)指向 coordinator | §4.4 已说 |
| ✅ Flyway 跨 schema 迁移加 `CREATE EXTENSION citus;` 首行 | 仅业务库 |
| ✅ MyBatis SQL **几乎不改** | Citus 透明支持 ON CONFLICT / jsonb / RLS / CTE / 窗口函数 |
| ⚠️ 显式 `CREATE TABLE` 新表后**必须加** `create_distributed_table('...', 'tenant_id', colocate_with => '...')` | 通过 Flyway migration 强制 |
| ⚠️ DDL 语句(ALTER TABLE 等)走 coordinator,Citus 自动传到 worker | 注意 ArchUnit / pr-gate 是否扫 SQL,需放行 |
| ⚠️ 极少数跨片查询(无 tenant_id WHERE)需要重写或加 hint | 静态扫描发现 + 改 |

**不需要改的**:Repository / Mapper / Service / 状态机 / RLS / 事务 / 测试。

---

## 7. ⚠️ RLS 过 coordinator POC(阻塞前置)

**这是采用 Citus 唯一真正的技术风险点。不通过 = 整条路径作废。**

### 7.1 验什么

Citus 把查询分发到 worker 时,coordinator → worker 是新建独立 connection;
**`SET LOCAL app.tenant_id`(`RlsTenantSessionSupport`)在 coordinator 设置后,能否随查询透传到 worker session?**

### 7.2 怎么 POC

```sql
-- 1. coordinator 建一张分布式表 + 启 RLS
CREATE TABLE biz.poc_tenant (tenant_id text NOT NULL, payload text);
SELECT create_distributed_table('biz.poc_tenant', 'tenant_id');
ALTER TABLE biz.poc_tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE biz.poc_tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON biz.poc_tenant
  USING (tenant_id = current_setting('app.tenant_id', true));

-- 2. 两个租户各插一条
INSERT INTO biz.poc_tenant VALUES ('ta','t-a-data'), ('tb','t-b-data');

-- 3. coordinator 上 SET LOCAL,SELECT 应该只看到本租户
BEGIN; SET LOCAL app.tenant_id = 'ta';
SELECT * FROM biz.poc_tenant;   -- 期望:只 't-a-data',无 't-b-data'
COMMIT;
```

### 7.3 三种可能结果

| 结果 | 处理 |
|---|---|
| ✅ 透传成功,RLS 在 worker 生效 | 路径绿,继续 §8 |
| 🟡 透传需配 `citus.propagate_set_commands = 'transaction'`(社区有此参数) | 配上,re-POC,通过则绿 |
| ❌ 任何配置都无法透传 | **路径死,放弃 Citus**,改走 §1.7 应用层分片或 hardware scale-up |

### 7.4 兜底验证

- 跨租户隔离:租户 A session 强行 `SELECT FROM biz.poc_tenant WHERE tenant_id='tb'` → **必须空行或拒绝**(Citus + RLS 双层防线)
- 跨片查询时 RLS 仍生效:UNION/JOIN 多分片查询不绕过策略

---

## 8. 数据迁移(从单 PG → Citus,业务库优先)

### 8.1 在线 / 离线两种

| 策略 | 适用 | 停机 | 复杂度 |
|---|---|---|---|
| A. **离线迁移**(推荐首版) | 维护窗口可接受 30-60min | 是 | 低 |
| B. **在线迁移**(双写 + 切流) | 7x24 不能停 | 否 | 高 |

### 8.2 离线迁移流程

1. **预演**:全量 dump 旧 PG `batch_business` → restore 到 Citus coordinator(仍是普通表)
2. **建分布式**:在 coordinator 跑 `SELECT create_distributed_table(...)` 把表逐张转分布式;数据自动按 tenant_id 散到 workers
3. **校验**:`SELECT count(*) FROM biz.*` 旧 vs 新,**逐表逐租户对账**(脚本生成)
4. **预演通过 → 维护窗口**:停 worker → 增量 dump+restore → 转分布式 → 切 JDBC URL → 启 worker
5. **回滚预案**:见 §10

### 8.3 在线迁移流程(后续可选)

- 阶段 1:Citus 集群和旧 PG 并行,业务双写(应用层加 dual-write toggle);影子读 Citus 对账
- 阶段 2:对账 0 差异 → 读流量灰度 (1% → 100%)
- 阶段 3:切写主到 Citus,旧 PG 改为只接 fallback
- 阶段 4:旧 PG 下线

**风险**:dual-write 一致性 / 旧 PG 与 Citus 序列冲突 / 状态机延迟差异。**首版强烈不建议**,等团队对 Citus 熟悉。

---

## 9. 监控 / 备份 / HA 改造

### 9.1 监控

| 指标 | 工具 |
|---|---|
| coordinator 健康 / 连接数 / QPS | postgres_exporter(已有,改 target)|
| worker 健康(2-N 个) | postgres_exporter × N,新加 |
| **Citus 专属**:分片均衡度 / colocation 命中率 / 跨片查询比例 | `citus_stat_tenants` / `citus_stat_statements`(Citus 视图)+ Grafana 面板 |
| 在线 rebalance 进度 | `citus_rebalance_status()` |

### 9.2 备份

| 数据 | 方式 |
|---|---|
| Coordinator 元数据 | `pg_dump`(同今天)+ WAL 归档 |
| Worker 数据 | **每个 worker 独立 pg_dump + WAL**;或基于 PG 物理备份(pgBackRest 推荐) |
| **整库 PITR** | pgBackRest 统一备份所有节点 + 时间点恢复;**关键能力,不能省** |

⚠️ Citus 没有"整集群一键备份"原生工具,需自己 orchestrate;pgBackRest 是社区主流选项。

### 9.3 HA

| 角色 | HA 策略 |
|---|---|
| Coordinator | 流复制 + Patroni 自动 failover(Citus 13 起官方文档支持)|
| Worker | 每个 worker 配 1 只流复制 replica + Citus `citus.replication_factor=2`(写双发);**或** Patroni 给每个 worker 做主从 |
| Rebalancer | 节点挂时自动从健康副本提升;Citus 内置 |

---

## 10. 灰度 / 切流 / 回滚

### 10.1 灰度(自托管路径)

| 阶段 | 范围 | 持续时间 |
|---|---|---|
| Stage 0 | POC 集群(2 worker)单租户写读 + RLS POC | 1-2 周 |
| Stage 1 | 准生产集群 4 worker;1 个非关键租户切到 Citus | 1-2 周 |
| Stage 2 | 5-10 个租户;监控 SLA + 业务一致性 | 2-4 周 |
| Stage 3 | 全租户切换;旧 PG 业务库**保留 30 天**只读快照,不接流量 | — |
| Stage 4 | 旧 PG 业务库下线 | — |

### 10.2 回滚(任何阶段都必须可回退)

| 场景 | 回滚动作 |
|---|---|
| Stage 0-1 失败 | 切回旧 PG JDBC URL;Citus 集群保留排查 |
| Stage 2 部分租户出问题 | 该租户切回旧 PG;Citus 仍服务其他租户 |
| Stage 3 全切后发现问题 | 旧 PG 仍是快照态,只能"重新迁回"(费时);**这就是为什么 Stage 1-2 必须充分** |
| RLS 透传线上回归(理论已 POC 过) | 立即切回旧 PG,**安全优先于性能**(P0 事件)|

### 10.3 切流开关(应用层)

业务库 JDBC URL 走环境变量(已是):
```yaml
batch.datasource.business.url: ${BATCH_BUSINESS_DB_URL:jdbc:postgresql://primary:15432/batch_business}
```
切流 = 改 env + 滚动重启 worker;**无代码改动**。

---

## 11. 不做(YAGNI / 越界 / 红线)

- **不做** platform 库(`batch_platform`)迁 Citus(控制面、量小、状态机强依赖单事务);第一阶段保留原 PG
- **不做** 业务表跨 colocation 组分散(同租户表必须 colocate 在一起,否则跨片事务激增)
- **不做** 修改 Citus 源码 + 对外提供服务(触发 AGPL 网络条款);自用免改安全
- **不做** 多主双写复制(BDR/pglogical)替代 Citus;状态机强一致不适用
- **不做** 把 Citus 当"魔法加速器"宣传——它对**单租户单流**不缩耗时(详见 streaming-large-file §5.3 Citus 加速维度分析)
- **不做** 跨 region 部署 worker(网络延迟会毁掉 colocation 收益);单 region + 多 AZ

## 12. 关联

### 12.1 配置 / 部署

- `docker-compose.yml`(§4 改造点;新增 coordinator + workers)
- `.env`(`POSTGRES_IMAGE_TAG` → `CITUS_IMAGE_TAG`)
- `batch-worker-*/src/main/resources/application*.yml`(`business.url` 指向 coordinator;`platform.url` 不变)
- `docker/postgres/init/*.sql`(改 `CREATE EXTENSION citus;` 首行 + `create_distributed_table(...)` 调用)
- `scripts/db/business/create_biz_tables.sql`(同步加 `create_distributed_table`)
- Flyway business 库 migrations(新表必须配套 distributed/reference 声明)

### 12.2 监控

- Grafana 新增 dashboard:Citus cluster overview / 分片均衡 / `citus_stat_tenants` / rebalance 状态
- 告警新增:worker down / rebalance stuck / 跨片查询比例 > 阈值

### 12.3 文档 / 决策依据

- 触发门槛:[single-node-throughput-optimization-2026-06-06](./single-node-throughput-optimization-2026-06-06.md) §1.6 / §1.8
- 为什么 Citus 是终局:[streaming-large-file-import-export-2026-06-06](../verifications/streaming-large-file-import-export-2026-06-06.md) §5.3
- 多租 / RLS / colocation 前置已满足:CLAUDE.md「多租隔离」

### 12.4 分支纪律

- 运维改造(`docker-compose.yml` / `.env` / `docker/postgres/*` / 部署脚本)→ `feature/docker-deploy`(部署分支)
- 业务代码改动(Flyway migration / yml 配置常量 / 新增 ArchUnit 规则)→ `feature/<topic>` → PR → main
- **两类不能互相 PR**(CLAUDE.md「分支用途」)
