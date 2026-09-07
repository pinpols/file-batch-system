# Kafka / Valkey / MinIO / PostgreSQL 运维补充

本文是四个基础依赖的日常巡检和故障边界。应用控制面只依赖这些服务的外部契约；本项目不把基础服务的
failover、数据修复或重放动作偷偷封装进健康检查。

## 1. 只读巡检入口

```bash
# 本地 Docker 默认端口
bash scripts/ops/inspect-dependencies.sh all

# 只查单个依赖
bash scripts/ops/inspect-dependencies.sh kafka valkey

# 生产/裸机必须显式使用真实地址，不要把 Compose 服务名带到宿主机
BATCH_INFRA_PG_HOST=pg-primary.prod \
BATCH_INFRA_KAFKA_BOOTSTRAP=broker-1.prod:9092,broker-2.prod:9092 \
BATCH_INFRA_VALKEY_HOST=valkey.prod BATCH_INFRA_VALKEY_PORT=6379 \
BATCH_INFRA_MINIO_ENDPOINT=https://minio.prod.example.com \
BATCH_INFRA_MINIO_ACCESS_KEY="$MINIO_ACCESS_KEY" \
BATCH_INFRA_MINIO_SECRET_KEY="$MINIO_SECRET_KEY" \
bash scripts/ops/inspect-dependencies.sh all
```

脚本支持宿主机 CLI；本地没有 CLI 时，会尝试复用正在运行的 Docker 容器：

| 依赖 | 宿主机检查 | Docker fallback | 失败含义 |
|---|---|---|---|
| PostgreSQL | `pg_isready` / `psql` | `batch-postgres-primary` | 连接不可用或状态查询权限不足 |
| Kafka | `kafka-topics.sh` / `kafka-consumer-groups.sh` | `batch-kafka` | Broker 不可达、CLI 缺失或 group 不存在 |
| Valkey | `valkey-cli` / `redis-cli` | `batch-valkey` | PING 失败 |
| MinIO | `/minio/health/ready` / `mc` | 健康端点仍走显式 endpoint | 服务不可用或 bucket/凭据错误 |

没有 CLI 的状态默认是 `WARN`，不会把“无法读取扩展指标”伪装成服务正常。生产门禁可设置
`BATCH_INFRA_STRICT=true`，将可选检查缺失升级为失败。脚本不会输出密码，也不会自动修改数据。

## 2. Kafka

### 日常检查

- Broker 能否列出 topic；重点关注 `batch.task.dispatch.*`、`batch.task.result`、retry、DLQ 和 trigger launch。
- 对 worker consumer group 检查 lag；lag 超过业务窗口阈值时，先看 worker 饱和度和数据库写入压力，再决定是否扩容。
- 不直接删除 topic、重置 group offset 或增加分区。它们会改变重放语义，必须走变更审批和对应 runbook。

### 常见故障边界

| 症状 | 先查 | 处置 |
|---|---|---|
| Broker 不可达 | Broker health、网络、认证、DNS | 暂停新增派发或启用已有 admission/backpressure；不要在应用侧建本地持久队列 |
| lag 持续增长 | group lag、worker heartbeat、PG 写入延迟 | 按 worker 类型扩容；检查分区数和单分区热点 |
| rebalance 卡住 | [kafka-rebalance-stuck.md](./playbooks/kafka-rebalance-stuck.md) | 先 drain，再处理消费者实例；不手工改 offset |
| DLQ 增长 | DLQ 原因码和 tenant/task | 使用现有 `heal-dead-letters.sh`，重放前确认幂等键和业务审批 |

生产 Kafka 至少应满足 `replication.factor >= 3`、`min.insync.replicas >= 2`、关闭 unclean leader
election；本地 Compose 是单 Broker，只能用于功能和故障注入，不代表 HA 证据。

## 3. Valkey

### 日常检查

- `PING`、角色、内存使用、AOF/RDB 状态和连接数。
- 应用配额运行时存储生产必须使用 `FAIL_CLOSED`；Valkey 故障不能长期演变成无限放行。
- ShedLock、SSE 广播、quota 等用途要区分容量和故障影响，不能把“Valkey 可连”当成全部业务正常。

### 故障处理

1. 先确认是网络、认证、主从切换还是内存淘汰。
2. quota 使用 `FAIL_CLOSED` 时，短时拒绝新请求是预期保护；不要直接改成 `FAIL_OPEN`。
3. ShedLock/调度异常按 [redis-shedlock-down.md](./playbooks/redis-shedlock-down.md) 处理，禁止直接清空锁键。
4. 检查 AOF/RDB 和 Sentinel/Operator 状态后再恢复流量；应用重启不是第一步。

生产需要主从/哨兵或等价托管 HA，并明确 maxmemory、淘汰策略、持久化、备份和恢复责任。

## 4. MinIO

### 日常检查

- readiness、磁盘水位、节点 offline、bucket 可访问性。
- 生产 bucket 开启版本管理、生命周期和必要的 Object Lock；清理 incomplete multipart 必须先确认保留窗口。
- 对象 checksum、manifest 和导出临时对象是业务闭环的一部分，不能只看 HTTP 200。

### 故障处理

| 症状 | 处理 |
|---|---|
| readiness 失败 | 查磁盘、网络、节点状态和凭据；暂停新的大批量导出 |
| bucket 访问失败 | 核对 endpoint、租户凭据、bucket policy；不要在脚本里写入默认密码 |
| multipart 残留 | 先列出上传、核对保留时间，再按生命周期策略清理；禁止无条件 `abort` 全桶 |
| 对象缺失/损坏 | 先保留审计证据和 manifest，再从版本/备份恢复；不要覆盖现有对象 |

MinIO Console 地址只用于人工运维，应用统一使用 S3 API endpoint；密钥从 Secret/环境变量注入。

## 5. PostgreSQL

### 日常检查

- 连接可用性、`pg_is_in_recovery()`、连接数、锁等待、WAL/磁盘水位和迁移失败记录。
- 运行表、outbox、日志和 dedup ledger 按已有分区/留存 runbook 管理，不直接在线 `DELETE` 热表。
- 备份按 [backup-and-pitr.md](./backup-and-pitr.md) 执行：base backup + WAL archive + 两个业务库逻辑导出，并定期做真实恢复演练。

### 故障处理

1. 主库不可用：先确认是否正在 failover，按 [pg-primary-failover.md](./playbooks/pg-primary-failover.md) 切换，不让应用各自猜主库。
2. 锁等待或连接耗尽：先定位 blocker、连接池和慢 SQL，再限流/暂停派发；不要批量 kill 未识别会话。
3. 迁移失败：停止发布，保留 Flyway 失败记录和日志，按迁移 checklist 修复；不手工删除 history 行。
4. 数据误删：走 PITR/逻辑恢复到隔离实例，核对租户、manifest 和计数后再回写；禁止直接覆盖生产库。

无 `psql` 的环境可使用仓库公共 `scripts/lib/env-common.sh` 的 `psql` wrapper：它会按
`BATCH_PG_CLIENT_MODE` 选择宿主机客户端、Python `psycopg` fallback 或 PostgreSQL 容器客户端。

## 6. 检查矩阵和上线门槛

| 场景 | 推荐入口 | 允许自动修复 |
|---|---|---|
| 日常只读巡检 | `inspect-dependencies.sh all` + `inspect-all.sh` | 不允许 |
| Kafka lag | dependency script + observability | 仅扩容/限流按变更流程 |
| Valkey 故障 | dependency script + ShedLock/quota playbook | 不清锁、不改 fail-open |
| MinIO 对象异常 | readiness + manifest/checksum + lifecycle SOP | 不全桶清理 |
| PG 故障 | dependency script + HA/PITR runbook | 不直接改业务状态 |

巡检通过只表示“依赖在当前时刻可访问”，不等价于 HA、RTO/RPO 或数据恢复演练已经达标；这些必须
保留 staging/生产同构的演练证据。
