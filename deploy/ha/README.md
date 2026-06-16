# 阶段 1-3 通用 HA · operator 清单(自建 K8s/on-prem)

> 落地 `docs/plans/2026-06-13-ha-deployment-plan.md` 阶段 1-3 的**通用 HA**(分支无关:服务 main 镜像,也服务 feature 未分布镜像)。
> 这些是 **K8s operator CR / manifest 模板**——集群相关项(storageClass、镜像版本、副本数、密钥、域名)用占位符,**ops 按真实集群改后 apply**,并在真集群跑 §验证。本目录文件**不可在本机验证**(无集群),只做语法/结构正确性保证。
>
> Citus(阶段 4)**不在此目录**:前置是洪峰 benchmark 达标 + `feature/partition-readiness` 合 main,届时同一 postgres-operator 加 worker 即可,见规划 §2.2 / §4 阶段 4。

## 关键选型(对规划的执行期优化)

规划原写"etcd + 手搓 Patroni + 独立 PgBouncer";落地改用 **Zalando postgres-operator**——它本身就是 Patroni,一个 `postgresql` CR 同时提供:
- PG HA(Patroni,**用 K8s 原生 DCS,免单独 etcd**)+ 自动 failover
- **内置 PgBouncer** 连接池(`enableConnectionPooler`)= P1-2
- **WAL 归档 + 逻辑备份**到 S3/MinIO(`logical_backup` + WAL-E/WAL-G)= P0-3 基础
- **原生 Citus 支持**(后续阶段 4 同 operator 加 worker group,不换栈)

其余:Kafka=Strimzi、Redis=spotahome redis-operator(`RedisFailover`)、对象存储=MinIO Operator(`Tenant`)。

## 前置:先装 operator(集群级,各一次)

| operator | 安装 | 提供 |
|---|---|---|
| Zalando postgres-operator | helm repo `postgres-operator-charts` | `postgresql` CR(Patroni HA + PgBouncer + 备份 + Citus) |
| Strimzi | helm repo `strimzi` | `Kafka` CR(KRaft 多 broker) |
| spotahome redis-operator | helm repo `redis-operator` | `RedisFailover` CR(Sentinel HA) |
| MinIO Operator | `kubectl krew`/helm `minio-operator` | `Tenant` CR(分布式纠删码) |

> 没有 operator 时这些 CR 不会被 reconcile。装 operator 是 ops 集群初始化的一部分,本目录假设已装。

## apply 顺序(= 规划阶段 1-3)

推荐用 `scripts/ha/` 脚本(幂等、带就绪等待与防呆):

```bash
scripts/ha/install-operators.sh          # ① 装 4 operator(集群级一次;版本用 env 锁定)
MINIO_AK=.. MINIO_SK=.. REDIS_PASSWORD=.. scripts/ha/bootstrap-secrets.sh   # ② 建凭据(不落盘)
# 改完 deploy/ha/*.yaml 占位符(STORAGE_CLASS 等)后:
scripts/ha/apply-stage123.sh             # ③ 按序 apply + kubectl wait 就绪
scripts/ha/failover-drill.sh all         # ④ 故障演练(混沌)——仅非生产/演练窗口跑
```

或手工 apply(等价):

```bash
kubectl apply -f deploy/ha/00-namespaces.yaml
kubectl apply -f deploy/ha/10-postgres-zalando.yaml   # 阶段1 PG HA(含 PgBouncer)
kubectl apply -f deploy/ha/20-kafka-strimzi.yaml      # 阶段2
kubectl apply -f deploy/ha/30-redis-failover.yaml
kubectl apply -f deploy/ha/40-minio-tenant.yaml
# 阶段3:备份在 postgres CR 内声明;恢复演练见 backup-and-pitr.md §2
```

应用侧连接(`helm/values-prod.yaml`)指向这些 svc:
- PG:`<cluster>-pooler.<ns>.svc`(PgBouncer)→ 自动指向 Patroni leader
- Kafka:`<cluster>-kafka-bootstrap.<ns>.svc:9092`
- Redis:`rfs-<name>.<ns>.svc:26379`(Sentinel)
- 对象存储:`minio.<ns>.svc:9000`

## 验证(真集群,逐项做完才算阶段完成)

| 阶段 | 混沌动作 | 期望 |
|---|---|---|
| 1 PG | `kubectl delete pod <pg-leader>` | postgres-operator 30s 内 promote replica;app 仅少量重试 WARN、无 Connection reset 雪崩(Hikari keepalive=30s) |
| 2 Kafka | 滚动重启 1 broker | 生产/消费不中断;`kafka-topics --describe` ISR=3 |
| 2 Redis | `delete` master pod | Sentinel 选新 master,缓存/选主短抖后恢复 |
| 2 MinIO | `delete` 1 minio pod | 纠删码读写不中断 |
| 3 备份 | 触发恢复演练 | 逻辑全量 + PITR 成功,记 RTO(`backup-and-pitr.md` §2) |

## Kafka broker HA(P0-1)·启用 + 验证细节

> 目标:broker≥3 + topic RF=3 + `min.insync.replicas=2` + `unclean.leader.election=false`,配 producer `acks=all`(已在 `batch-defaults.yml`)→ **已确认在途事件 RPO=0(0 丢)**。
> 配置已全部就位(本目录 `20-kafka-strimzi.yaml` 的 `Kafka` CR + `KafkaNodePool` replicas=3),以下是 **ops 启用步骤**。

1. **建集群**(broker / 内部 topic RF):`apply-stage123.sh` 已含 `20-kafka-strimzi.yaml`——CR 内 `default.replication.factor=3` / `min.insync.replicas=2` / `offsets.topic.replication.factor=3` / `transaction.state.log.replication.factor=3` / `transaction.state.log.min.isr=2`,`auto.create.topics.enable=false`(应用显式建 topic、防误配单副本)。
2. **建业务 topic**(应用 topic RF / min.insync):跑 topic init 时带——
   ```bash
   KAFKA_BOOTSTRAP_SERVER=batch-kafka-bootstrap.kafka.svc:9092 \
   KAFKA_TOPIC_REPLICATION_FACTOR=3 \
   KAFKA_TOPIC_MIN_INSYNC_REPLICAS=2 \
     sh scripts/data/init-kafka-topics.sh
   ```
   (`KAFKA_TOPIC_MIN_INSYNC_REPLICAS` 会随 `--config min.insync.replicas=2` 落到每个 topic。)
3. **存量 RF=1 topic 提副本**:`kafka-reassign-partitions --generate/--execute` 把 RF 提到 3(在线扩,不可在线降)。
4. **生产推荐项核对**:`unclean.leader.election.enable=false`(默认 Kafka 4.x 即 false;显式确认)、producer 侧 `acks=all` + `enable.idempotence=true`(仓内已配)。

**验证**:
```bash
# RF=3 / ISR=3 / 含 min.insync.replicas=2
kubectl -n kafka exec batch-kafka-pool-0 -- bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic batch.task.dispatch.import
# 滚动重启 1 broker：生产/消费不中断；ISR 短暂掉到 2（≥ min.insync，仍可写），恢复后回 3
kubectl -n kafka delete pod batch-kafka-pool-1
```
本地无集群时,用根目录 `docker-compose.kafka-ha.yml`(可选 3 broker override,默认不启用)先验同一套行为,命令见该文件头注释 §验证。

## 占位符清单(apply 前必改)

`STORAGE_CLASS` / 镜像 tag / `*_REPLICAS` / `NODE_POOL` 反亲和标签 / 各 Secret(`*-credentials`)/ 资源 requests-limits / `DOMAIN`。
