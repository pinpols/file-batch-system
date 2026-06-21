# Backlog: Citus 引入方案(运维侧改造为主,benchmark 驱动后启用)

> 状态:**待 benchmark 触发**——单机榨干仍不达目标后才启用。本文是"决定要上之后"的可执行方案,**不是现在的动作**。
> 日期:2026-06-06　模块:docker / K8s / DBA / 运维(应用层改动预计极小)
> 前置:[single-node-throughput-optimization-2026-06-06](./single-node-throughput-optimization-2026-06-06.md) §1.6 / §1.8(触发门槛)
> 对照:[streaming-large-file-import-export-2026-06-06](../verifications/streaming-large-file-import-export-2026-06-06.md) §5.3(为什么 Citus 是终局)

## TL;DR

1. **何时启用**:Tier-A + Tier-B 全做完仍不达目标,**且**有多租户并发洪峰实测信号(单租户单流 Citus 不缩耗时)。
2. **改的是运维,但应用层改动 ≠ 极小**——见下方 ⚠️ 修正。
3. **路径二选一**:**A** 自托管开源 Citus(免费、可控)/ **B** Azure Cosmos DB for PostgreSQL(托管 Citus,免 AGPL + 免运维)。
4. **两个并列阻塞前置 POC**:① **RLS 过 coordinator**(`SET LOCAL app.tenant_id` 透传 worker,不过则路径作废)② **PK 复合化爆失败半径**(见 §0.5)。
5. **数据迁移可在线做**:`create_distributed_table` 原地分片,业务停机最小化。

> ## ⚠️ 0.5 实测硬阻塞前置(2026-06-07 schema 实扫,修正本文档原"几乎不动应用"的乐观假设)
>
> 本文档原假设"CLAUDE.md 强制 UNIQUE 含 tenant_id → distributed 前置满足、应用几乎不动"。**实扫 DDL 后该假设被推翻**:
>
> | 阻塞 | 实测 | 性质 |
> |---|---|---|
> | **PK 是单列 `id BIGSERIAL`** | `job_instance` 等 23 张候选表 PK 不含 tenant_id(只有 UNIQUE 含)。Citus distributed **要求 PK 含分片键** | 🔴 硬阻塞·大:改复合 `(tenant_id,id)` 连带应用层千+ 处 `findById`/FK/mapper,**应用重构级,非"几乎不动"** |
> | **二级 UNIQUE 不含 tenant_id** | `job_partition`/`pipeline_step_run`/`workflow_node_run` 等的 `UNIQUE(parent_id, ...)` | 🔴 硬阻塞·中:每个 UNIQUE 都须含分片键 |
> | **缺 tenant_id 列** | `pipeline_step_run` / `workflow_node_run`(run 明细子表,见 CLAUDE.md 多租隔离第②类豁免) | 🔴 须补列 + 回填(Citus 驱动) |
> | **definition 表分类** | `job/pipeline/workflow_definition` 宜 **reference** 非 distributed | 🟡 决策 |
> | **序列 / useGeneratedKeys** | 43 处 mapper `useGeneratedKeys`,distributed insert 取自增 id 行为 | 🟡 POC 验证 |
>
> **修正后定位**:Citus 不是"运维改造为主、应用极小",而是 **PK 复合化驱动的跨 23 表 + 千处应用代码重构**(估 12-20 周)。所以 §7 RLS POC **之外**必须再加一个 **PK 复合化 POC**(选 1-2 张核心表先改复合主键,实测应用层爆失败半径),两个 POC 都过才进入实施。
>
> **2026-06-10 复核(实库直查)**:上述阻塞全部仍成立——10/10 核心表(`job_instance`/`pipeline_instance`/`outbox_event`/`workflow_run`/`file_record`/`trigger_request`/`dead_letter_task`/`job_partition`/`pipeline_step_run`/`workflow_node_run`)PK 仍为单列 `id`;`useGeneratedKeys` 43 → **49 处**(阻塞面在涨)。已采取的止血:CLAUDE.md §多租隔离新增「新表 PK 前瞻」(新多租大表一律复合 PK);洪峰 benchmark(门槛③)已排入 `worker-throughput-benchmark-plan-2026-06-07.md` P2。另:`outbox_event`/`job_instance` 月分区(partition-migration 01/02)同日在本地环境实跑——**触发新硬阻塞后已回滚**:分区键进 UNIQUE 打破 `ON CONFLICT (tenant_id,event_key)` 等 upsert 契约,orchestrator outbox 写入全失败(6 个 mapper 受影响,清单见脚本 01 头注释)。**该教训直接外推到 Citus**:distributed table 同样要求 UNIQUE 含分片键,同一批 ON CONFLICT 全部会失败——§0.5 的"PK 复合化爆失败半径"POC 必须把 `grep -r 'on conflict'` 全量 mapper 清单纳入评估范围,这是比 `findById` 更隐蔽的爆失败点。

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
| **看 License 评估(AGPL 安全区,含本系统已用 MinIO/Grafana/Loki/Tempo 统一评估)** | **§11** |
| 看不做什么 | §12 |
| 找相关代码 / 配置 | §13 |

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

## 11. License 评估(AGPL v3 自托管;**本系统统一权威源**)

> **结论先行**:✅ **本系统的用法(自托管 + 不改源码 + JDBC 跨进程访问)落在 AGPL 安全区,无 license 风险。**
> 本节同时覆盖本系统**已用的其他 4 个 AGPL 软件**(MinIO / Grafana / Loki / Tempo),作为单一权威评估源;
> `docs/runbook/{minio-lifecycle-policy,object-storage-s3-backends,observability-stack}.md` 指向本节,**不重复评估**。

### 11.1 本系统已用 AGPL v3 软件清单(都在用,且都安全)

| 软件 | 用途 | License | 引入时间 | 评估 |
|---|---|---|---|---|
| **MinIO Server** | 对象存储主存(导入 ingress / 导出 outbound / 错误输出) | AGPL v3 | 早期已用 | ✅ 安全 |
| **Grafana** 11.6.0 | 可观测性面板 | AGPL v3 | observability stack | ✅ 安全 |
| **Grafana Loki** 3.5.0 | 日志聚合 | AGPL v3 | observability stack | ✅ 安全 |
| **Grafana Tempo** 2.6.1 | 分布式追踪 | AGPL v3 | observability stack | ✅ 安全 |
| **Citus**(本文档) | PG 横向写扩展 | AGPL v3 | 待 benchmark 触发 | ✅ 安全(同前 4 个) |

**重要事实**:Citus 不是新风险,而是**第 5 个同类组件**——和 MinIO 等的 license 拓扑、用法、判断完全一致。法务若批了 MinIO,Citus 必批(同先例)。

### 11.2 AGPL v3 网络条款触发条件(必须 3 条同时满足才触发)

| 触发条件 | 本系统 5 个 AGPL 软件 |
|---|---|
| ① 修改了 AGPL 软件源码 | ❌ 不修改(只装扩展 / 改配置 / 跑 SQL,不改 C/Go 源码)|
| ② 用户通过网络访问到**修改版** | ❌ 用户访问 console-api / FE,**不直连** MinIO / Grafana / Citus |
| ③ 把该软件作为服务**对外提供** | ❌ 内网后端,非 BaaS / DBaaS 售卖 |

**三条全踩才触发,本系统一条没踩 → 完全安全**。

### 11.3 业界先例(同拓扑、同 license、同结论)

| 公司 / 项目 | AGPL 软件 | 自托管? | 用户直连? | 触发了? |
|---|---|---|---|---|
| Microsoft Azure | Citus | ✅ 自营托管 | 间接 | ❌ |
| 大量金融 / 政企 / SaaS 公司 | MinIO | ✅ | ❌ | ❌ |
| 全行业 | Grafana / Loki / Tempo | ✅ | 内部运维 | ❌ |
| 早期 MongoDB(AGPL 时代) | 全行业 | ✅ | ❌ | ❌ |

**事实**:AGPL 自 2007 年发布至今,**没有任何"自托管不改源码"的判例被起诉或被要求开源**。FSF 自己也明文确认:跨进程标准协议通信(JDBC / S3 API / HTTP)**不构成衍生作品**,不传染。

### 11.4 法务沟通模板(同 MinIO/Grafana 当年的话术)

如需法务确认,15 分钟一次性走完:

> 我们要引入 PostgreSQL 扩展 Citus(AGPL v3)。用法:
> 1. **不改源码**——只装扩展、改配置、跑 SQL。
> 2. **自托管在内网**——非对外 DBaaS。
> 3. **应用层通过 JDBC 网络访问**——跨进程标准协议。
> 4. **用户访问 app(console-api),不直连 Citus**。
>
> 公司**已在用 4 个同 license、同拓扑的 AGPL 软件**:MinIO(对象存储)/ Grafana(可观测面板)/ Loki(日志)/ Tempo(追踪)。
> Citus 是同一判例的延伸,请法务确认可复用。

法务说 OK → 走自托管路径 A(免费);法务硬政策禁 AGPL → 走路径 B(**Azure Cosmos DB for PostgreSQL**,微软托管,商业服务,完全绕开 AGPL,法务不用走流程)。详见 §1 路径选择。

### 11.5 真正不能做的(AGPL 红线,本系统也不做)

- ❌ 修改 Citus / MinIO / Grafana 源码后**对外提供服务**(触发 AGPL 网络条款 → 必须公开你的修改)
- ❌ 把 Citus / MinIO 当 BaaS / DBaaS **卖给第三方租户**(对外提供 AGPL 软件本身)
- ❌ fork AGPL 软件闭源后商业化

本系统这 3 条**都不踩**,所以本节结论 = 安全。

## 12. 不做(YAGNI / 越界 / 红线)

- **不做** platform 库(`batch_platform`)迁 Citus(控制面、量小、状态机强依赖单事务);第一阶段保留原 PG
- **不做** 业务表跨 colocation 组分散(同租户表必须 colocate 在一起,否则跨片事务激增)
- **不做** 修改 Citus 源码 + 对外提供服务(触发 AGPL 网络条款);自用免改安全
- **不做** 多主双写复制(BDR/pglogical)替代 Citus;状态机强一致不适用
- **不做** 把 Citus 当"隐式加速器"宣传——它对**单租户单流**不缩耗时(详见 streaming-large-file §5.3 Citus 加速维度分析)
- **不做** 跨 region 部署 worker(网络延迟会毁掉 colocation 收益);单 region + 多 AZ

## 13. 关联

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
