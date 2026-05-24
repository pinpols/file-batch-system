# Console-API 读写分离（PG streaming replication）

## 背景

P2-4：console-api 海量查询时把主库压测掉。引入 PG hot standby + Spring `AbstractRoutingDataSource`，让 `@Transactional(readOnly = true)` 标注的查询路由到从库，主库只承接写。

代码侧已就绪：
- `ReadReplicaDataSourceConfiguration`（按 `readOnly` 标志路由）
- 9 个核心 query service 类级标注 `@Transactional(readOnly = true)`
- docker-compose 已写好主库流复制参数 + 从库容器 + console-api 环境变量

剩下：启动 + 翻开关。

## 一、本地启动从库

### 全新环境（无旧 postgres-data volume）

```bash
# 1. 启动主库（首次 initdb 自动建 replicator 用户 + 应用流复制参数）
docker compose up -d postgres

# 2. 启动从库（按 profile 启）
docker compose --profile replica up -d postgres-replica
```

首次启动时 `entrypoint.sh` 会从主库 `pg_basebackup` 拉一份完整数据到 `postgres-replica-data` volume，然后进入 hot standby 模式订阅 WAL。

### 已有旧 postgres-data volume（重要）

旧 volume 的 PG 容器**不会自动**：
- 重新跑 `init/*.sh` → **没有 replicator 用户**
- 应用 docker-compose `command:` 里新加的 `wal_level=replica` / `max_wal_senders=10` → **需要重启容器**

操作步骤（保留旧数据）：

```bash
# 1. 重启主库以应用新的 command 参数（wal_level 等是启动时参数，无法 reload）
docker compose restart postgres

# 2. 一次性手动创建 replicator 用户（init 脚本不会重跑）
docker exec -it batch-postgres psql -U batch_user -d batch_platform <<'SQL'
CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'repl_pass_dev_only';
SQL

# 3. 给 replicator 用户加 pg_hba 放行
docker exec -it batch-postgres bash -c 'cat >> "$PGDATA/pg_hba.conf" <<EOF

# P2-4: streaming replication
host replication replicator 0.0.0.0/0 scram-sha-256
EOF
pg_ctl reload -D "$PGDATA"'

# 4. 现在可以启从库了
docker compose --profile replica up -d postgres-replica
```

如果不在意旧数据（开发环境 OK，生产**禁止**），最干净是直接重置：

```bash
docker compose down
docker volume rm batch-plaform_postgres-data batch-plaform_postgres-replica-data
docker compose up -d postgres
docker compose --profile replica up -d postgres-replica
```

## 二、验证流复制健康

```bash
# 主库视角：查 replication slot / standby 连接
docker exec -it batch-postgres psql -U batch_user -d batch_platform \
  -c "SELECT pid, application_name, client_addr, state, sync_state FROM pg_stat_replication;"

# 期望输出：1 行 state=streaming
# 如果空表：从库没连上，看 batch-postgres-replica 容器日志
```

```bash
# 从库视角：确认在 standby 模式
docker exec -it batch-postgres-replica psql -U batch_user -d batch_platform \
  -c "SELECT pg_is_in_recovery();"

# 期望：t（true）
```

```bash
# 写入主库后从库可读到
docker exec -it batch-postgres psql -U batch_user -d batch_platform \
  -c "CREATE TABLE IF NOT EXISTS public.replica_probe (id int, ts timestamp); INSERT INTO public.replica_probe VALUES (1, now());"

sleep 1

docker exec -it batch-postgres-replica psql -U batch_user -d batch_platform \
  -c "SELECT * FROM public.replica_probe;"
# 期望看到刚才写入的行
```

## 三、翻开 console-api 路由开关

### docker-compose 模式（`--profile apps`）

在 `.env.local` 设置：

```bash
BATCH_CONSOLE_READ_REPLICA_ENABLED=true
```

重启 console-api：

```bash
docker compose --profile apps up -d --force-recreate console-api
```

### 本地 jar 模式（`scripts/local/start-all.sh`）

本地 jar 不读 docker-compose env，需在 shell 里导出后再启动：

```bash
export BATCH_CONSOLE_READ_REPLICA_ENABLED=true
# 容器内地址 postgres-replica:5432 在宿主机不可达，本地 jar 走 localhost:15433
export BATCH_CONSOLE_REPLICA_URL="jdbc:postgresql://localhost:15433/batch_platform"
export BATCH_CONSOLE_REPLICA_USER=batch_user
export BATCH_CONSOLE_REPLICA_PASSWORD=batch_pass_123
export BATCH_CONSOLE_PRIMARY_URL="jdbc:postgresql://localhost:15432/batch_platform"
export BATCH_CONSOLE_PRIMARY_USER=batch_user
export BATCH_CONSOLE_PRIMARY_PASSWORD=batch_pass_123

scripts/local/restart.sh console
```

启动日志应有：

```
console read-replica enabled: primary=jdbc:postgresql://localhost:15432/batch_platform, replica=jdbc:postgresql://localhost:15433/batch_platform
```

## 四、验证查询真的路由到从库

最直接的方式：临时停从库 → 让查询失败定位是否从从库读。

```bash
# 停从库
docker compose stop postgres-replica

# 调一个只读查询接口（任意 console GET /api/console/queries/* 都行）
curl -s "http://localhost:18080/api/console/queries/job-instances?tenantId=default-tenant&pageNo=1&pageSize=10" \
  -H "X-Console-User: admin" -H "X-Tenant-Id: default-tenant"

# 预期：500 内部错误（连 postgres-replica 失败）
# 看 console-api 日志会有 "could not connect to server: postgres-replica"
```

写路径仍然正常（走主库）：

```bash
# 试一个 POST 接口（如新建租户/job 等），应正常
# 然后恢复从库
docker compose start postgres-replica
```

## 五、健壮性：连接熔断 + lag-aware quarantine

console-api 的 `ReadReplicaRoutingDataSource` 不是"试一下从库失败就报错"的简单代理，而是带短路保护的电路开关。

### 5.1 连接失败熔断（旧）
- 配置项：`batch.console.read-replica.failure-threshold` / `quarantine-seconds`（默认 3 次 / 30s）
- 行为：连续 N 次抛 `SQLException` → 进入 quarantine，期内所有读请求改走主库；期满后下一次重试从库

### 5.2 Lag-aware quarantine（2026-05 新增）
旧的纯连接级熔断有漏洞：从库**连得上但 replay 停滞**（WAL 段被清 / disk 慢 / 主从断了但 replica 进程没死）时检测不出来。

- 配置项：
  - `batch.console.read-replica.lag-threshold-seconds`（默认 30s，0 = 禁用）
  - `batch.console.read-replica.lag-check-interval-seconds`（默认 10s）
- 实现：`ReplicaLagMonitor` 周期采主库 `pg_stat_replication`
  - 取 `MAX(replay_lag)` 秒数 + `COUNT(*)` streaming replica 数
  - lag > 阈值，或 streaming 数 = 0 → 调 `ReadReplicaRoutingDataSource.markQuarantined(reason)` 主动隔离
- Prometheus gauges：
  - `batch.console.replica.replay_lag_seconds` — 当前 max(replay_lag)，-1 = 未知
  - `batch.console.replica.streaming_count` — streaming 状态 replica 数，-1 = 未知

### 5.3 Prometheus 告警（4 条，`docker/observability/prometheus-batch-rules.yml`）

| 告警 | 表达式 | 持续 | 严重程度 |
|---|---|---|---|
| `PostgresReplicationStopped` | `pg_stat_replication_count == 0` | 1m | critical |
| `PostgresReplicationLagHigh` | `pg_replication_lag > 30` | 5m | warning |
| `PostgresReplicationLagCritical` | `pg_replication_lag > 300` | 1m | critical |
| `PostgresReplicationSlotInactive` | `pg_replication_slots_active == 0` | 5m | warning |

> 2026-05 本地 dev 环境主从断 11 天没人发现 → 旧 SQLException-only 检测漏洞，lag-aware quarantine + 告警就是修补这条。

## 六、常见问题

### 主从延迟（lag）
- `SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) FROM pg_stat_replication;` 在主库查，单位 byte
- 单机本地：基本 0 ms；真实生产跨网络：几 ms 到几百 ms
- 业务上能容忍延迟的查询才走从库（dashboard / 历史列表）；强一致查询（提交后立即读）保持 readWrite，强制走主库
- 持续 > `lag-threshold-seconds` 时由 §5.2 自动 quarantine 切主

### 从库重置
如果从库数据坏了 / WAL 跟不上 / 主库 retention 不够丢了 WAL，需重新引导：

```bash
docker compose stop postgres-replica
docker volume rm batch-plaform_postgres-replica-data
docker compose --profile replica up -d postgres-replica
# 容器启动时 entrypoint 会重新 pg_basebackup
```

### 主库 wal_keep_size 不够
如果从库长时间停机后再连，主库 WAL 已被清理 → pg_basebackup 报 `requested WAL segment has already been removed`。
两个选择：
1. 重新引导（见上）
2. 调大主库 `wal_keep_size`（默认本地 64MB；生产建议 1GB+，配合 replication slot 更稳）

### 切换回单库（关闭读写分离）
```bash
BATCH_CONSOLE_READ_REPLICA_ENABLED=false
docker compose --profile apps up -d --force-recreate console-api
docker compose stop postgres-replica  # 节省资源；不停也可以，容器自跑无影响
```

console-api 启动时不会创建从库连接池，走 Spring Boot 默认主 DataSource 自动配置，行为同历史。

## 七、为什么只有 console-api 启用（架构边界）

读写分离是 **BFF 层优化**，不是全模块的存储架构。以下表格 + 解释明确**只对 console-api 启用、其他模块直连主库** 是有意识的架构决策，不是疏忽。

### 7.1 模块数据访问全景

| 模块 | platform 库 | business 库 | 读写分离 |
|---|---|---|---|
| **batch-console-api** | ✅ `ReadReplicaRoutingDataSource` 路由 | ❌ | ✅ **唯一** |
| batch-orchestrator | ✅ 直连主库（pool=30） | ❌ | ❌ |
| batch-trigger | ✅ 直连主库（含 `QRTZ_*`）| ❌ | ❌ |
| batch-worker-core | ❌（框架，无 DB 直连）| ❌ | — |
| batch-worker-import | ✅ 直连主库 | ✅ 直连主库 | ❌ |
| batch-worker-export | ✅ 直连主库 | ✅ 直连主库 | ❌ |
| batch-worker-process | ✅ 直连主库 | ✅ 直连主库 | ❌ |
| batch-worker-dispatch | ✅ 直连主库 | ❌ | ❌ |

### 7.2 为什么不给主链路加（**核心理由**）

#### ① 强一致性是硬约束

主链路的状态机依赖"**读自己刚写的数据**"（read-after-write）：

```
T1: orchestrator INSERT job_instance(status=CREATED) + outbox_event 同事务
T2: orchestrator 别处 SELECT job_instance WHERE status=CREATED  ← 必须能读到 T1
T3: outbox poller SELECT outbox WHERE status=NEW                ← 必须能读到 T1
T4: worker CLAIM 后立即 SELECT 验证 lease                        ← 必须能读到自己刚写
```

PG **异步流复制**有秒级延迟（local 毫秒，跨机房 100ms-2s）。任意一处 read-after-write 落到从库 → state machine race condition → 死锁 / 丢任务 / 重复执行。

即使引入，每个 query 都要标 `@RouteToPrimary` 强制走主库——结果就是从库零路由，纯增复杂度无收益。

#### ② 主链路本来就是写为主，分离没收益

| 模块 | 读 / 写比例（粗估）| 读分离潜在收益 |
|---|---|---|
| **console-api** | 99 : 1（用户翻页 / 监控 / 仪表盘） | ✅ 高 |
| orchestrator | 1 : 5（写状态机为主，读只验证）| 🔴 负收益 |
| trigger | 1 : 3 | 🔴 同上 |
| worker | 2 : 5 | 🔴 同上 |

读分离的本质是把"无强一致性要求的读"卸到从库。主链路根本没有这种读。

#### ③ 复杂度税

引入意味着每个模块要：
- 加 `ReadReplicaRoutingDataSource` 配置类
- 给所有 `@Transactional` 标 `readOnly=true/false`（不标默认走主库 → 从库永远空闲）
- 给所有"读后立即写"代码加 `@RouteToPrimary`
- 加 fail-open / quarantine / metric
- 测试要双库覆盖

console-api 一份配置 + 改 9 个 query service 加 `readOnly=true` 就值，因为 99% 是读。其他模块改一遍后，发现 `@RouteToPrimary` 几乎覆盖所有 query → 从库吃灰 → 白做。

### 7.3 什么时候才重新评估

| 触发场景 | 处理 |
|---|---|
| 新加**报表 / 数据分析**模块（如 `batch-analytics`），完全只读 | ✅ 引入读写分离，且**只读**——不挂主库写路径 |
| orchestrator 出现**纯历史查询**端点（如"查 30 天前归档实例"），不涉及状态推进 | ✅ 该端点单独走 `@Transactional(readOnly=true)` + 从库；其他保持主库 |
| 主库 CPU 长期 > 80% 且**读 SQL 占大头** | 重新评估；但更可能的根因是缺索引 / 慢 SQL，**先优化主库** |
| 全局读 TPS 主库扛不住 | 那时大概率已经在做分库分表了（Phase 3 范畴），见 `docs/architecture/scalability-assessment.md` §6 |

> **结论**：console-api 走读写分离是 BFF 层的优化；主链路（trigger / orchestrator / worker）的强一致性需求决定了它们必须直连主库——这是**架构正交决策**，不是临时省事。**未来真要引入只在"新增的纯读模块 / 纯读端点"上做，绝不反向给现有主链路加。**

## 八、生产部署补充

本地 docker-compose 的从库是单容器演示用。生产建议：
- **从库实例独立部署**（Patroni / Cloud RDS replica / 自建主从）
- **多从库 + 负载均衡**（HAProxy / pgpool / 应用侧轮询）—— 本路由代码只支持单从库；多从库改 `AbstractRoutingDataSource` 的 `determineCurrentLookupKey` 加轮询
- **replication slot** 替代 `wal_keep_size` 做 WAL 保留：从库不会再丢 WAL，但主库会因 slot 占用一直堆 WAL 直到从库连上（要监控）
- **同步复制**（`synchronous_commit=on` + `synchronous_standby_names`）：主库写要等从库 ack 才返回；增强一致性但牺牲写延迟，按业务取舍

## 相关代码

- `batch-console-api/.../config/ReadReplicaDataSourceConfiguration.java` — 路由 DataSource 配置
- `batch-console-api/.../config/ReadReplicaProperties.java` — 配置 binding
- `batch-console-api/src/main/resources/application.yml` `batch.console.read-replica.*` — 默认值
- `docker/postgres-replica/entrypoint.sh` — 从库 bootstrap 脚本
- `docker/postgres/init/003-create-replication-user.sh` — 主库初始化时建 replicator 用户

## 相关参考

- `docs/architecture/rework-classification.md` Phase 2 第 1 项
- `docs/architecture/scalability-assessment.md` §6 路线图
