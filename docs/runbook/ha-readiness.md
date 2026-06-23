# 生产高可用就绪 Checklist(P0 / P1)

> 上生产前逐项过。**核心结论**:应用层(调度主备 / 幂等 / 任务恢复 / Kafka 重复消费 / 降级)在本仓**已建好**;真实短板全在**基础件 HA**,且这些**与 Citus 正交**(单机 PG 生产同样需要)。Citus 是扩展不是 HA,不在本清单 P0/P1。
>
> 每项分:**① 运维件(部署/可选托管)** + **② 应用侧(代码/配置,本仓状态)** + **验证** + **回滚**。

## 0. 部署模型(先明确)

- **应用 8 服务**:K8s 容器多副本(Helm `helm/batch-platform`,values-prod / values-canary)。✅ 就绪。
- **基础件**:应用只连地址(`values-prod` 里 `*.svc.cluster.local`),**chart 不自建**。两条路可混用:
  - **K8s operator 自托管容器**:PG=CloudNativePG/Patroni、Kafka=Strimzi、Redis=redis-operator/Sentinel、MinIO=MinIO-Operator。
  - **云托管(非容器)**:Aurora/RDS、MSK、ElastiCache、S3——改 url/bootstrap 即可,应用零改动。**团队小优先托管,省 P0 一大半运维。**

---

## P0(没有这些不谈生产 HA;与 Citus 无关、都得做)

### P0-1 Kafka 多 broker + RF≥3
- **现状**:dev compose 默认单 broker、RF=1(单机 dev 默认,不变)。**配置已就位**——多 broker / RF=3 / min.insync=2 的可部署配置全部落地,不再"靠 ops 凭空建":
  - **prod(K8s)**:`deploy/ha/20-kafka-strimzi.yaml`(Strimzi `KafkaNodePool` replicas=3 + `Kafka` CR `default.replication.factor=3` / `min.insync.replicas=2` / offsets/txn 内部 topic RF=3 / `auto.create.topics.enable=false`)。
  - **本地演练**:`docker-compose.kafka-ha.yml`(可选 override,3 broker + RF=3 + min.insync=2,**默认不启用**,单 broker dev 不受影响)。
  - **topic 创建**:`scripts/data/init-kafka-topics.sh` / `init-tenant-topics.sh` 的 RF 与 min.insync.replicas 均**可配**(`KAFKA_TOPIC_REPLICATION_FACTOR` 默认 1 / `KAFKA_TOPIC_MIN_INSYNC_REPLICAS` 默认空;prod 设 3 / 2,建 topic 时随 `--config min.insync.replicas=2` 一并落地)。
  - **helm**:`helm/values-prod.yaml` `kafka.broker.{replicas=3,replicationFactor=3,minInsyncReplicas=2}` 声明期望拓扑(chart 不自建 broker,值须与 Strimzi CR 一致)。
- **① 运维件(ops 启用步骤)**:① 装 Strimzi operator(`scripts/ha/install-operators.sh`);② 改 `deploy/ha/20-kafka-strimzi.yaml` 占位符(`STORAGE_CLASS` 等)后 `scripts/ha/apply-stage123.sh`;③ 建业务 topic 时带 `KAFKA_TOPIC_REPLICATION_FACTOR=3 KAFKA_TOPIC_MIN_INSYNC_REPLICAS=2`;④ 存量 RF=1 topic 用 `kafka-reassign-partitions` 提到 RF=3。MSK 路径:3-AZ broker,topic/内部 topic RF=3 + min.insync=2 同理。
- **② 应用侧**:✅ **已做**——producer `acks=all` + `enable.idempotence=true` + `retries=5` + `delivery.timeout.ms`(`batch-defaults.yml`);consumer `ack-mode=manual_immediate`(at-least-once)+ CLAIM 唯一性防重复执行。
- **RPO 目标**:**RF=3 + min.insync.replicas=2 + producer acks=all 下,已确认(acked)的在途事件 RPO=0(0 丢)**——写入要求至少 2 个同步副本落盘才返回成功,单 broker 盘损不丢已 ack 事件;配 `unclean.leader.election=false`(Strimzi CR 推荐)杜绝落后副本当 leader 造成已确认数据回退。对照:当前单 broker RF=1,broker 盘损=在途事件只能靠 outbox republish 回退,无硬 RPO。
- **验证**:滚动重启 1 broker,生产/消费不中断、无丢消息;`kafka-topics --describe` 看 RF=3 / ISR=3 / Configs 含 `min.insync.replicas=2`。本地用 `docker-compose.kafka-ha.yml`(见其头注释 §验证)先验;真集群按 `deploy/ha/README.md` §验证表(阶段 2)。
- **回滚**:topic RF 不可在线降;先扩 broker 再 `kafka-reassign-partitions` 提 RF。

### P0-2 PG / Citus coordinator 自动 failover
- **现状**:流复制 standby 在(primary/replica),但 **failover 是手动 promote**(`playbooks/pg-primary-failover.md`),无 Patroni/etcd。
- **① 运维件**:Patroni + etcd(或 CloudNativePG);VIP/Service 名(`pg-primary.db.svc`)指向当前 leader,**app 连 VIP 不连 IP**(values-prod 已是 svc 名 ✅)。上 Citus 时 coordinator 同样要 HA(Citus 13 支持 coordinator 主备),worker 各自也要副本。
- **② 应用侧**:✅ **已做**——url 走 svc 名非硬编码 IP;Hikari **keepalive=30s**(本次补:`HikariPgSessionSupport`,failover 后主动探活剔除指向旧主的死连接)+ `connection-timeout=5s`;状态机强一致(读写分离仅 console-api)。
- **验证**:Patroni 触发 switchover,观察应用日志——切换窗口内允许少量重试 WARN,**不应** Connection reset 雪崩;keepalive 探活在 ~30s 内剔除旧连接。
- **回滚**:Patroni `failover`/`switchover` 切回;app 无需动(连 VIP)。

### P0-3 备份 / PITR(数据安全底线)
- **现状**:**未实现**(只有 `backup-and-pitr.md` runbook)。
- **① 运维件**:`pg_basebackup` 日 base + WAL 连续归档(`archive_command` 指独立故障域)+ `pg_dump` 两库逻辑导出;Citus 下每 worker 独立备份 + coordinator 元数据。
- **② 应用侧**:✅ 备份新鲜度告警 `PostgresBackupStale`(`prometheus-batch-rules.yml`,gauge 缺失/>26h critical)已就位,等运维 push 指标。备份脚本骨架 `scripts/db/backup/pg-backup.sh`(base + 两库逻辑 dump + 可选 WAL 清理 + 新鲜度指标 push)已落地,等运维按 §1.3 cron 编排。
- **SLO 目标**:**RTO ≤ 30min / RPO ≤ 5min**(依据 + 调紧路径见 `backup-and-pitr.md` §1.4)。RPO 由 WAL `archive_timeout=300` 封顶,RTO 由 base+WAL replay / `pg_restore -j4` 保证。
- **验证**:**至少跑一次恢复演练**(逻辑全量 + PITR 到某时刻),记录并对 RTO 做 SLO 断言——`bash scripts/db/backup/dr-drill.sh`(默认 WARN,`--strict-rto` 超阈值硬 fail;`backup-and-pitr.md` §2)。**没演练过的备份=没有备份。**
- **回滚**:N/A(本身是回退)。

### P0-4 应用多副本(已就绪,确认)
- **① 运维件**:Helm 设 orchestrator/console 3 副本、worker N 副本、K8s PodDisruptionBudget。
- **② 应用侧**:✅ **已做**——调度器 ShedLock 跨实例互斥(N 实例只 Leader 跑同一 job,等效主备);worker 无状态、CLAIM 抢占;PDB/反亲和在 Helm values。
- **验证**:删 1 个 orchestrator pod,调度不停(ShedLock 转移);删 worker pod,在跑任务被 lease 超时重派(P0-5)。

### P0-5 任务恢复机制(已就绪,确认)
- **② 应用侧**:✅ **已做**——lease + heartbeat(`worker_heartbeat`/lease_expire_at)、`PartitionLeaseReclaimScheduler`、`StaleCreatedLaunchRecoveryScheduler`、`JobInstanceTimeoutEnforcer`、死信 + 自动重试(0 give-up 实测);状态机 PENDING/RUNNING/SUCCESS/FAIL/TIMEOUT 齐。
- **验证**:kill 执行中 worker,任务 lease 过期后自动 reclaim 重派,终态正确、不重复执行(CLAIM 幂等)。

---

## P1(推荐)

### P1-1 Redis Sentinel / Cluster
- **现状**:Redis 单点。ShedLock 选主 + quota + cache 依赖它。
- **① 运维件**:Sentinel(3 哨兵 + 1 主 N 从)或 Cluster;或 ElastiCache。
- **② 应用侧**:✅ cache fail-open(Redis 挂→直通 DB,`worker-cache.enabled` fail-open);⚠️ ShedLock 依赖 Redis,Redis 全挂期间调度互斥退化——可配 ShedLock 走 PG 回退(`docs/runbook/redis-shedlock-down.md`)。
- **验证**:Sentinel 主从切换,缓存/选主短暂抖动后恢复,业务不崩。

### P1-2 连接池 / 扇出治理(上 Citus 重点;单机可选)
- **现状**:无 PgBouncer。Citus 下 app 池 × 分片数放大已撞 "too many clients"(W8)。
- **① 运维件**:PgBouncer(transaction mode)前置;Citus 调 `citus.max_shared_pool_size`(三元组:worker max_connections ≥ max_shared_pool_size ≥ app 池峰值和)。
- **② 应用侧**:✅ 各模块 Hikari `maximum-pool-size` 已按需求估算注释配置(orchestrator 50 等)。
- **验证**:压测下 worker 无 "too many clients";PgBouncer `SHOW POOLS` 无大量 waiting。

---

## P2(后续,不在本轮)
跨机房 / 跨地域 / 异地容灾。

---

## 一句话排序
**先 P0-1 Kafka HA + P0-2 PG Patroni + P0-3 备份演练 → P1-1 Redis Sentinel → (确需扩展再)Citus + P1-2 PgBouncer。** 应用层已就位,基础件按上表逐项落地(自托管 operator 或云托管二选一);Helm 的 svc 地址是"假设运维会建",chart 不自建,别误以为装了 Helm 就有 HA。

## 关联
- `playbooks/pg-primary-failover.md` / `backup-and-pitr.md` / `redis-shedlock-down.md`
- `helm/values-prod.yaml`(基础件连接地址 + Kafka broker 期望拓扑)、`docker/observability/prometheus-batch-rules.yml`(HA 告警)
- **Kafka broker HA**:`deploy/ha/20-kafka-strimzi.yaml`(prod CR) + `deploy/ha/README.md`(apply/验证) + `docker-compose.kafka-ha.yml`(本地 3 broker 演练 override) + `scripts/data/init-kafka-topics.sh`(RF / min.insync 可配)
