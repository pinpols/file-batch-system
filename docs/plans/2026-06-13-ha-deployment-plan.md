# HA 部署规划 · 自建 K8s/on-prem · operator 自托管

> 2026-06-13。决策前提:**自建 K8s / on-prem**(无云托管)、**operator 自托管基础件**。
> 应用层 HA(调度主备 / CLAIM 幂等 / 任务恢复 / Kafka 重复消费 / 降级 / Hikari failover 探活)**本仓已就绪**,见 `ha-readiness.md`;本规划只讲**基础件 HA 部署 + 落地顺序 + 验证**。

---

## 0. 核心原则:HA 与代码分支正交,一套通用 HA 服务两分支

**HA 部署 90% 与"main / feature 哪个分支"无关。唯一的分叉在 PG 层,而扳机是"是否真的 distribute",不是"选哪个分支"。**

| HA 组件 | 是否分支相关 | 说明 |
|---|---|---|
| Kafka / Redis / MinIO / 应用多副本 | ❌ 无关 | 两分支应用侧配置一致;chart 同一套,只镜像 tag 不同 |
| **PG HA** | 🟡 看是否 distribute | 见下 |

PG 层的真相:
- **普通 Patroni(单 PG 主备)同时兜住 main 和 feature(未 distribute)的代码** —— feature 的复合 PK / 月分区 / NOT EXISTS 全是**标准单机 PG 特性**(已由 plain-PG 全量回归验证:12 模块 BUILD SUCCESS / e2e 41/41)。所以这套 PG HA **与镜像是 main 还是 feature 无关**,只要没跑 `create_distributed_table`。
- **只有真的 `create_distributed_table`(= 多 worker Citus)** 才需要换成 Patroni-Citus 多 group 拓扑。

> **结论:不为两个分支搭两套 HA。** 现在搭**一套通用 HA**(普通 Patroni + Kafka/Redis/MinIO + 应用多副本),它服务 main 镜像、也服务 feature(未分布)镜像;Citus 是**后续 PG 层单点增量**,其余组件一动不动。

## 0.5 部署分支用哪个代码版本

`feature/docker-deploy` 部署分支按 CLAUDE.md **只单向同步 main**,所以它部署的应用镜像 = **main 的代码**(非 Citus)。推论:
- **现在可部署**:main 镜像 + 上面那套通用 HA。立即可上生产。
- **Citus(分布式)不可直接从部署分支起**:需先 `feature/partition-readiness` → main 合并(而合并 gate 在多租户洪峰 benchmark)。在那之前,部署 = 非 Citus = 通用 HA 的"PG 单主备"形态。
- 所以 Citus HA 不是"切开关",是有**前置依赖链**:洪峰 benchmark 达标 → feature 合 main → 部署分支同步 → PG 层增量到 Citus 多 group。

---

## 1. 目标拓扑

```
                          ┌──────────────── K8s 集群 ────────────────┐
  应用(Helm,已就绪)      │  orchestrator×3  console×3  worker×N       │  ← 分支无关,只镜像 tag 不同
                          │        │ 连 svc 名(非 IP),经 PgBouncer    │
  ┌─────────── 通用 HA(现在可落,服务两分支)───────────────────────┐ │
  │ etcd ×3 (Patroni DCS)                                            │ │
  │ 普通 Patroni:PG primary + replica(单 coordinator group)        │ │  ← P0-2;main / feature-未分布 通用
  │ pgBackRest → MinIO(WAL 归档 + base,PITR)                       │ │  ← P0-3
  │ Strimzi: Kafka ×3 (KRaft, RF=3, min.insync=2)                   │ │  ← P0-1
  │ Redis: Sentinel(1主2从+3哨兵)                                  │ │  ← P1-1
  │ MinIO Operator: distributed tenant(≥4 盘 纠删码)              │ │
  │ PgBouncer ×2(transaction mode)                                 │ │  ← P1-2
  └──────────────────────────────────────────────────────────────────┘ │
  ┌─────────── PG 层增量(仅 Citus go 后,其余组件不动)──────────────┐ │
  │ Patroni 切 Citus 模式:coordinator group + worker group 0..N     │ │  ← 阶段4,前置=feature 合 main
  │ 各 group leader+replica;failover 自动 citus_update_node          │ │
  └──────────────────────────────────────────────────────────────────┘ │
                          └────────────────────────────────────────────┘
```
**反亲和**:每类有状态件副本散在不同 K8s node(podAntiAffinity required)。

---

## 2. 逐组件落地

### 2.1 通用 HA(分支无关,现在可落)

| 组件 | 工具 | 规模(起步) | 关键配置 | 应用侧(已就绪) |
|---|---|---|---|---|
| **DCS** | etcd | 3 节点 | Patroni 分布式锁源 | — |
| **PG HA** | **普通 Patroni**(非 Citus 模式) | 1 primary + 1 replica | `synchronous_mode=on`(RPO≈0,按性能容忍调);暴露 `<cluster>-leader` Service | url 走 PgBouncer svc ✅;Hikari keepalive=30s ✅ |
| **备份** | pgBackRest | — | WAL 归档 + 日 base → MinIO | `PostgresBackupStale` 告警 ✅ |
| **Kafka** | Strimzi | 3 broker(KRaft) | topic RF=3 / `min.insync.replicas=2` / offsets+txn RF=3 | acks=all+idempotence / consumer manual_immediate ✅ |
| **Redis** | Sentinel | 1主2从+3哨兵 | ShedLock/quota/cache | cache fail-open ✅;ShedLock 可回退 PG |
| **对象存储** | MinIO Operator | ≥4 盘 EC | WAL/备份也存这 | object store 重试 ✅ |
| **连接池** | PgBouncer | ×2 | transaction mode | Hikari pool 已估算 ✅ |

### 2.2 PG 层增量到 Citus(仅 Citus go 后)

前置:**洪峰 benchmark 达标 + feature/partition-readiness 合 main + 部署分支同步**。然后:
- Patroni 从普通模式**迁移到 Citus 模式**(加 `citus:` 段 + worker group)——这是一次**有状态迁移**,不是切 flag:需把现有单 PG 数据搬进 coordinator group,再扩 worker group,跑 `01-distribute.sql`。
- Patroni 3.x Citus 模式优势:自动管 coordinator + worker group failover + `citus_update_node` 重注册(优于手写 `citus-cluster.sh` 一次性 add_node)。
- GUC(`propagate_set_commands=local` / `enable_unsafe_triggers=on` / `max_shared_pool_size`)写进 Patroni DCS 参数;配本仓 `CitusRuntimeStartupCheck` 启动期校验。
- **其余组件(Kafka/Redis/MinIO/应用/PgBouncer)零改动。**

---

## 3. 网络 / 连接(避免硬编码 IP)

- 应用 → **PgBouncer Service** → Patroni `<cluster>-leader` Service(只指当前 leader);failover 时 app/PgBouncer 不动,endpoint 自动切。
- `helm/values-prod.yaml`:`postgresql.url` 指 PgBouncer svc、Kafka 指 Strimzi bootstrap svc、Redis 指 Sentinel svc —— **全 svc 名零 IP**(本仓已是此形态)。

---

## 4. 落地顺序

**阶段 1 · DCS + 通用 PG HA(P0-2)** —— *分支无关,现在可做*
1. etcd×3。
2. 普通 Patroni(1 主 1 备),GUC/参数配好(暂不开 Citus 模式)。
3. app 经 PgBouncer → Patroni leader svc。
4. ✅ 验证:`patronictl switchover`,app 切换窗口仅少量重试 WARN、无 Connection reset 雪崩(Hikari keepalive 生效)。

**阶段 2 · Kafka + Redis + MinIO HA(P0-1 / P1-1)** —— *分支无关*
5. Strimzi 3 broker,topic RF=3 / min.insync=2。
6. Redis Sentinel;app 指 Sentinel svc。
7. MinIO distributed tenant;迁对象存储。
8. ✅ 验证:逐个滚动重启 1 节点,生产/消费/缓存/读写不中断。

**阶段 3 · 备份 + 演练(P0-3,上线硬门)** —— *分支无关*
9. pgBackRest WAL 归档 + 日 base → MinIO;push 新鲜度指标。
10. ✅ **恢复演练**:逻辑全量 + PITR 到某时刻,记 RTO。**演练过才算完成。**

> **阶段 1-3 完成 = 生产可上(单 PG HA 全量)**,服务 main 镜像;换 feature(未分布)镜像同样适用,HA 不动。

**阶段 4 · PG 层增量到 Citus** —— *前置:洪峰 benchmark 达标 + feature 合 main*
11. Patroni 迁 Citus 模式 + 扩 worker group(各 1 主 1 备)。
12. 数据迁入 + `01-distribute.sql` + `batch.citus.enabled=true`。
13. ✅ 验证:kill worker group leader → Patroni 自动 promote + 重注册,分布式查询不中断。
> 不达标则**永久停在阶段 3**——已是生产可用的单机 PG HA,无任何损失。

---

## 5. 交付物(按分支纪律)

operator 清单/CR/values 属部署产物 → **`feature/docker-deploy` 分支**(同步 main 镜像)。需产出:
- **通用 HA(阶段1-3)**:etcd StatefulSet + **普通 Patroni** StatefulSet + leader Service + Strimzi `Kafka` CR + Redis Sentinel + MinIO `Tenant` CR + PgBouncer + pgBackRest 配置/CronJob/演练脚本 + `values-prod` svc 地址对齐。
- **Citus 增量(阶段4)**:Patroni Citus 模式 ConfigMap + worker group StatefulSet + 迁移 runbook(单 PG → Citus)——**单独交付,前置 feature 合 main**。
- 每件配 §4 failover 验证脚本(kill leader / 断网 / 滚动重启)。

---

## 6. 待决 / 风险

1. **节点故障域**:全自托管有状态件多(etcd3 + PG 主备 + Kafka3 + Redis6 + MinIO4)——确认 K8s 节点数 / 故障域够散,否则反亲和排不下,HA 名存实亡。
2. **同步复制 vs 性能**:`synchronous_mode=on` 给 RPO≈0 但写延迟升;异步则 failover 可能丢最后几笔(靠 outbox 7 天 republish + CLAIM 幂等兜)。按业务容忍定。
3. **自托管运维深度**:Patroni / Strimzi / MinIO operator 需团队具备 operator 运维能力;无专职 DBA/SRE 时长期成本显著高于云托管——值得再确认"自建"是否硬约束。
4. **Citus 迁移成本**:阶段 4 是有状态迁移(单 PG → 多 group + distribute),非平滑切换;务必在 benchmark 确证需要后再投入。

---

## 关联
- `ha-readiness.md`(P0/P1 checklist + 应用侧已做对照)
- `backup-and-pitr.md`(备份/PITR/演练)· `citus-deployment.md`(Citus GUC/distribute/开关)
- `playbooks/pg-primary-failover.md`(Patroni 后由 patronictl 替代手动 promote)
- `helm/values-prod.yaml`(基础件连接地址)
