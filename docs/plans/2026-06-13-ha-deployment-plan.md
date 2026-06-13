# HA 部署规划 · 自建 K8s/on-prem · operator 自托管 · P0+P1+Citus

> 2026-06-13。决策前提:**自建 K8s / on-prem**(无云托管)、**operator 自托管基础件**、范围 **P0+P1+Citus**。
> 应用层(调度主备 / CLAIM 幂等 / 任务恢复 / Kafka 重复消费 / 降级 / Hikari failover 探活)**本仓已就绪**,见 `ha-readiness.md`;本规划只讲**基础件 HA 部署 + 落地顺序 + 验证**。
> ⚠️ Citus go/no-go 数据(多租户洪峰 benchmark)**仍未跑**——基础件可按"双栈兼容"先搭(不跑 distribute 即纯 PG HA),distribute/启用留到 benchmark 拍板,详见 §4 阶段 4。

---

## 0. 目标拓扑(全 operator 自托管)

```
                          ┌──────────────── K8s 集群 ────────────────┐
  应用(Helm,已就绪)      │  orchestrator×3  console×3  worker×N       │
                          │        │ 连 svc 名(非 IP),经 PgBouncer    │
  ┌───────────────────────┼────────┴───────────────────────────────┐ │
  │ etcd ×3 (Patroni DCS)  │                                          │ │
  │ Patroni(Citus 模式)   │  coordinator group: leader + replica     │ │  ← P0-2 + Citus HA
  │   ├ coordinator 主/备  │  worker group 0..N: 各 leader + replica  │ │     一套搞定
  │   └ worker groups      │  failover 自动 citus_add_node 重注册      │ │
  │ pgBackRest → 对象存储  │  WAL 归档 + base(PITR)                   │ │  ← P0-3
  ├────────────────────────────────────────────────────────────────┤ │
  │ Strimzi: Kafka ×3 (KRaft, RF=3, min.insync=2)                    │ │  ← P0-1
  │ Redis: Sentinel(1主N从+3哨兵) 或 redis-operator                 │ │  ← P1-1
  │ MinIO Operator: distributed tenant(≥4 节点纠删码)              │ │  ← 对象存储 HA
  │ PgBouncer(transaction mode) ×2                                  │ │  ← P1-2 扇出
  └──────────────────────────────────────────────────────────────────┘
```

**反亲和**:每类有状态件的副本必须散在不同 K8s node(podAntiAffinity required);etcd/Kafka/coordinator 各占独立故障域。

---

## 1. 基石决策:Patroni 3.x 原生 Citus 模式(一套覆盖 P0-2 + Citus HA + worker HA)

on-prem Citus HA 的标准答案不是 CloudNativePG(不原生管 Citus 多节点),而是 **Patroni 3.0+ 的 Citus 支持**:

- `patroni.yaml` 加 `citus:` 段,Patroni 把整个 Citus 集群当一个 DCS(etcd)管理:**coordinator group**(group 0)+ **N 个 worker group**,每 group 一个 leader + ≥1 replica。
- **自动 failover**:任一 group 的 leader 挂 → Patroni 在该 group 内 promote replica,并**自动 `citus_update_node` 把新地址登记到 coordinator 元数据**——这正是手写 `citus-cluster.sh` 做不到的(它只 add_node 一次、无 failover)。
- **GUC 一处配**:`propagate_set_commands=local` / `enable_unsafe_triggers=on` / `max_shared_pool_size` 写进 Patroni `bootstrap.dcs.postgresql.parameters`,所有节点一致,新 replica 自动继承——配合本仓 `CitusRuntimeStartupCheck` 启动期校验,双保险。
- **双栈**:同一 Patroni 栈,不跑 `01-distribute.sql` = 就是普通 PG 主备(单 coordinator group 当主备 PG 用)→ 满足"benchmark 前先搭 HA、不提前启用分片"。

> 不选 CloudNativePG 的原因:它对 Citus distributed 多 worker group 不是一等公民;Patroni `--citus` 是社区在 on-prem 跑 Citus HA 的既定路径。若**确定不上 Citus**,则 CloudNativePG 更省心——但本轮范围含 Citus,故 Patroni。

---

## 2. 逐组件落地

| 组件 | 工具 | 规模(起步) | 关键配置 | 应用侧(已就绪) |
|---|---|---|---|---|
| **DCS** | etcd | 3 节点 | Patroni 的分布式锁源;独立 PV | — |
| **PG/Citus** | Patroni 3.x(Citus 模式) | coordinator 1主1备 + worker group×2(各1主1备)起步 | 3 个 Citus GUC;`synchronous_mode=on`(coordinator 强一致,RPO≈0);streaming replica | url 走 PgBouncer svc 名 ✅;Hikari keepalive=30s ✅;CitusRuntimeStartupCheck ✅ |
| **备份** | pgBackRest | — | WAL 连续归档 + 日 base → MinIO bucket;Citus 每 group 独立 stanza + coordinator 元数据 | `PostgresBackupStale` 告警 ✅ |
| **Kafka** | Strimzi operator | 3 broker(KRaft) | topic RF=3 / `min.insync.replicas=2` / offsets+txn RF=3 | acks=all+idempotence+retries / consumer manual_immediate ✅ |
| **Redis** | Sentinel(3 哨兵+1主2从)或 redis-operator | 3+3 | ShedLock/quota/cache 用;`maxmemory-policy` 按用途 | cache fail-open ✅;ShedLock 可回退 PG DCS(`redis-shedlock-down.md`) |
| **对象存储** | MinIO Operator | distributed tenant ≥4 盘 | 纠删码(EC:2 起);WAL/备份也存这 | object store 重试 + bounded read ✅ |
| **连接池** | PgBouncer | ×2(K8s svc 前置) | transaction mode;`max_shared_pool_size`/worker max_connections/app 池三元组对齐 | Hikari pool 已按需求估算 ✅ |

---

## 3. 网络 / 连接路径(避免硬编码 IP)

- 应用 → **PgBouncer Service**(coordinator) → Patroni leader。Patroni 暴露 `<cluster>-leader` K8s Service(只指向当前 leader);PgBouncer 连这个 leader Service。**failover 时 app/PgBouncer 不动**(Service endpoint 自动切)。
- `helm/values-prod.yaml` 的 `postgresql.url` 指向 PgBouncer svc;Kafka bootstrap 指向 Strimzi 的 `*-kafka-bootstrap` svc;Redis 指向 Sentinel svc。**全 svc 名,零 IP**(本仓已是此形态)。

---

## 4. 落地顺序(分 4 阶段,每阶段可独立验证、可回滚)

**阶段 1 · DCS + 单机等价 PG HA(P0-2 基础)**
1. 起 etcd×3。
2. Patroni 起 **单 coordinator group(1主1备)**,先**不开 Citus 分片**(等价普通 PG 主备)。GUC 全配好。
3. app 切 PgBouncer→Patroni leader svc。
4. ✅ 验证:`patronictl switchover`,app 切换窗口仅少量重试 WARN、无雪崩(Hikari keepalive 生效)。

**阶段 2 · Kafka + Redis + 对象存储 HA(P0-1 / P1-1)**
5. Strimzi 起 3 broker,迁移 topic 到 RF=3;`min.insync.replicas=2`。
6. Redis Sentinel 起;app 指 Sentinel svc。
7. MinIO Operator distributed tenant 起;迁对象存储。
8. ✅ 验证:逐个滚动重启 1 节点,生产/消费/缓存/读写不中断。

**阶段 3 · 备份 + 演练(P0-3,上线硬门)**
9. pgBackRest WAL 归档 + 日 base → MinIO;push 新鲜度指标。
10. ✅ **恢复演练**:逻辑全量恢复 + PITR 到某时刻,记 RTO(`backup-and-pitr.md` §2)。**演练过才算 P0-3 完成。**

**阶段 4 · Citus 分片(仅当洪峰 benchmark 判定需要)**
11. Patroni 扩出 worker groups(各 1主1备),开 Citus 模式。
12. 跑 `scripts/db/citus/01-distribute.sql`;开 `batch.citus.enabled=true`(CitusRuntimeStartupCheck 校验)。
13. ✅ 验证:kill 一个 worker group leader → Patroni 自动 promote + 重注册,分布式查询不中断。
> **门槛**:阶段 1-3 不依赖 Citus 决策,可立即推进;阶段 4 **gate 在多租户洪峰 benchmark**(`worker-throughput-benchmark-plan` P2),不达标则停在阶段 3 = 单机 PG HA 全量,已是生产可上状态。

---

## 5. 交付物(按分支纪律)

operator 清单/CR/values 属**部署产物 → `feature/docker-deploy` 分支**(CLAUDE.md),不进本仓主线代码。需产出:
- `etcd` StatefulSet + Patroni(Citus 模式)StatefulSet + leader Service + ConfigMap(GUC)
- Strimzi `Kafka` CR(3 broker,RF=3)
- Redis Sentinel manifests / redis-operator CR
- MinIO `Tenant` CR(distributed EC)
- PgBouncer Deployment + Service
- pgBackRest 配置 + WAL 归档 CronJob + 恢复演练脚本
- `helm/values-prod.yaml` 更新各 svc 地址(已是 svc 名,确认对齐新栈命名)

每件配 §4 的 failover 验证脚本(混沌:kill leader / 断网 / 滚动重启)。

---

## 6. 待决 / 风险

1. **节点资源**:全 operator 自托管,有状态件多(etcd3 + PG 多 group×2 + Kafka3 + Redis6 + MinIO4)——确认 K8s 节点数 / 故障域够散(否则反亲和排不下,HA 名存实亡)。
2. **同步复制 vs 性能**:coordinator `synchronous_mode=on` 给 RPO≈0 但写延迟升;按业务容忍度定(异步则 failover 可能丢最后几笔,靠 outbox 7天 republish + CLAIM 幂等兜)。
3. **Citus 提前投入**:本轮选了含 Citus,但 go 数据没出——阶段 4 的人力(worker group 运维 + 连接扇出调优)是为"可能不需要的能力"提前付;建议阶段 1-3 先落,阶段 4 等 benchmark。
4. **运维深度**:Patroni-Citus / Strimzi / MinIO operator 都需团队具备 operator 运维能力;无专职 DBA/SRE 时,这套自托管的长期成本显著高于云托管——值得再确认"自建"是否硬约束。

---

## 关联
- `ha-readiness.md`(P0/P1 checklist,应用侧已做对照)
- `backup-and-pitr.md`(备份/PITR/演练)
- `citus-deployment.md`(Citus GUC/distribute/开关)
- `playbooks/pg-primary-failover.md`(切主剧本,Patroni 后由 patronictl 替代手动 promote)
- `helm/values-prod.yaml`(基础件连接地址)
