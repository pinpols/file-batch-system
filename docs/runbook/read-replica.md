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
docker volume rm batch-local_postgres-data batch-local_postgres-replica-data
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

## 五、常见问题

### 主从延迟（lag）
- `SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) FROM pg_stat_replication;` 在主库查，单位 byte
- 单机本地：基本 0 ms；真实生产跨网络：几 ms 到几百 ms
- 业务上能容忍延迟的查询才走从库（dashboard / 历史列表）；强一致查询（提交后立即读）保持 readWrite，强制走主库

### 从库重置
如果从库数据坏了 / WAL 跟不上 / 主库 retention 不够丢了 WAL，需重新引导：

```bash
docker compose stop postgres-replica
docker volume rm batch-local_postgres-replica-data
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

## 六、生产部署补充

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
