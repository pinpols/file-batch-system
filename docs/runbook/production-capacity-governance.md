# 生产容量与存储增长治理 Runbook

本文把“生产会不会像本地压测一样膨胀”拆成可巡检的边界。结论是：生产数据、WAL、Kafka 日志和对象存储都会增长，这是批量系统的正常运行特征；没有保留策略、归档和水位告警时，正常增长会演变成生产风险。

本 runbook 只定义生产治理、只读巡检和保留计划，不提供一键删除生产数据的命令。清理、归档、分区、PITR 和对象生命周期分别按已登记 runbook 执行。

## 1. 容量增长来源

| 来源 | 是否正常增长 | 治理方式 | 不能做什么 |
|---|---|---|---|
| PostgreSQL 热表 | 正常，随实例、Outbox、文件记录、审计和幂等账本增长 | 归档调度、分区、autovacuum、PITR、水位告警 | 直接在线大批量 `DELETE` 热表 |
| PostgreSQL WAL / 表膨胀 | 正常，压测和批量写入后尤其明显 | WAL 归档、checkpoint、autovacuum、必要时 `pg_repack` | 把 Docker 卷大小等同于业务数据大小 |
| Kafka topic 日志 | 正常，取决于 retention、segment 和消费 lag | 有界 retention、lag 告警、RF/minISR | 生产随意删 topic、重置 offset |
| 对象存储 | 正常，导入/导出/附件/坏行文件都会增长 | bucket 分离、lifecycle、manifest/checksum、备份 | 用本地 MinIO 清理经验替代生产 OSS 策略 |
| 文件通道 | 正常，LOCAL / NAS / SFTP / OSS / API / API_PUSH / EMAIL 都可能产生待投递、回执、暂存和外部失败残留 | 通道沙箱、host key / TLS / egress allowlist、只读探测、失败归档和审计 | 把通道探测失败直接当作可删除目标 |
| Worker Report Outbox | 正常，orchestrator 不可达或 worker 重启时会积压 | PLATFORM_PG 优先，SQLITE 必须声明路径、备份和切换边界 | 生产多个 worker 共享同一个 SQLite 文件 |
| Quartz / Trigger 状态 | 正常，misfire、trigger、job detail 和 outbox 会随计划增长 | misfire 保留、Quartz 表容量巡检、Trigger outbox 水位和索引 | 直接改 Quartz 表绕过 Trigger API |
| 读副本 / 业务分片 | 正常，副本 lag、slot、分片容量和租户 placement 会变化 | lag 阈值、failover 隔离、片级账密、placement 对账 | 把 PG 主库健康等同于副本/分片健康 |
| 观测栈 | 正常，指标、日志、trace 和告警事件会增长 | Prometheus / Loki / Tempo / Jaeger retention、采样和告警路由 | 无限保留调试日志和 100% trace |
| 本地压测残留 | 正常但必须清理 | `load-tests/scripts/cleanup-load-test-environment.sh` | 把压测清理脚本用于生产 |

## 2. 只读巡检入口

```bash
# 本地或 staging，按当前 env 读取地址
bash scripts/ops/inspect-production-capacity.sh

# 生产建议显式传真实地址和严格阈值
BATCH_PROD_CAPACITY_STRICT=true \
BATCH_PROD_CAPACITY_PG_HOST=pg-primary.prod \
BATCH_PROD_CAPACITY_PG_PORT=5432 \
BATCH_PROD_CAPACITY_PG_DATABASE=batch_platform \
BATCH_PROD_CAPACITY_PG_USER=batch_user \
BATCH_PROD_CAPACITY_KAFKA_BOOTSTRAP=broker-1.prod:9092,broker-2.prod:9092 \
BATCH_PROD_CAPACITY_KAFKA_MIN_REPLICATION_FACTOR=3 \
BATCH_PROD_CAPACITY_KAFKA_REQUIRE_RETENTION=true \
BATCH_PROD_CAPACITY_OBJECT_STORE_ENDPOINT=https://s3.prod.example.com \
BATCH_PROD_CAPACITY_OBJECT_STORE_ACCESS_KEY="$S3_ACCESS_KEY" \
BATCH_PROD_CAPACITY_OBJECT_STORE_SECRET_KEY="$S3_SECRET_KEY" \
BATCH_PROD_CAPACITY_OBJECT_STORE_REQUIRE_LIFECYCLE=true \
bash scripts/ops/inspect-production-capacity.sh
```

脚本检查：

- PostgreSQL：数据库逻辑大小、Top 热表、dead tuple、Outbox 积压、Trigger 已接收未建实例、超过保留窗口的热表数据、幂等账本行数、核心分区存在性。
- Kafka：核心 topic 是否可读、分区数、副本因子、topic retention 和 cleanup policy。
- 对象存储：业务 bucket / AI 附件 bucket 是否可访问、容量、lifecycle 是否可读取。

运行时 profile 另由 `inspect-runtime-governance.sh` 检查，覆盖 NAS / SFTP / API / API_PUSH /
EMAIL / OSS / LOCAL 通道、Worker Report Outbox、Quota / ShedLock、读副本、业务分片、Quartz、
观测和 OpenLineage。该检查只验证 profile 是否完整和生产基线是否 fail-close，不连接外部系统。

默认 `WARN` 不阻断，`BATCH_PROD_CAPACITY_STRICT=true` 会把 warning 作为失败，用于上线准入或定期巡检。

## 3. 保留治理计划入口

`inspect-production-capacity.sh` 解决“现在有没有容量风险”，`plan-production-retention.sh` 解决“哪些域还缺保留策略或应该进入归档评审”。后者同样只读，只输出 `OK` / `WARN` / `PLAN`，不会归档、清理、调整 topic 配置、改 lifecycle 或清 Redis key。

```bash
# 本地或 staging，按当前 env 读取地址
bash scripts/ops/plan-production-retention.sh

# 生产建议显式传真实地址和严格阈值
BATCH_PROD_RETENTION_STRICT=true \
BATCH_PROD_RETENTION_PG_HOST=pg-primary.prod \
BATCH_PROD_RETENTION_PG_PORT=5432 \
BATCH_PROD_RETENTION_PG_DATABASE=batch_platform \
BATCH_PROD_RETENTION_PG_USER=batch_readonly \
BATCH_PROD_RETENTION_KAFKA_BOOTSTRAP=broker-1.prod:9092,broker-2.prod:9092 \
BATCH_PROD_RETENTION_KAFKA_REQUIRE_TOPIC_RETENTION=true \
BATCH_PROD_RETENTION_OBJECT_STORE_ENDPOINT=https://s3.prod.example.com \
BATCH_PROD_RETENTION_OBJECT_STORE_ACCESS_KEY="$S3_READONLY_ACCESS_KEY" \
BATCH_PROD_RETENTION_OBJECT_STORE_SECRET_KEY="$S3_READONLY_SECRET_KEY" \
BATCH_PROD_RETENTION_OBJECT_STORE_REQUIRE_LIFECYCLE=true \
BATCH_PROD_RETENTION_REDIS_HOST=redis.prod \
BATCH_PROD_RETENTION_REDIS_PORT=6379 \
BATCH_PROD_RETENTION_REDIS_PATTERNS='batch:*' \
bash scripts/ops/plan-production-retention.sh
```

计划脚本检查：

- PostgreSQL：`archive_policy` 是否覆盖核心运行态表、归档是否启用、超过保留窗口的终态作业实例 / Outbox / Trigger 候选量、幂等账本规模。
- Kafka：核心 topic 是否具备 topic 级有界 `retention.ms`。如果平台统一使用 broker 默认 retention，可用分域严格度降级并在上线准入记录说明。
- 对象存储：目标 bucket 是否可读取 lifecycle。策略细分仍在对象存储平台侧配置，应用仓库只验证“有证据可查”。
- Valkey / Redis：按模式抽样检查 key TTL。脚本只做 `SCAN` + `TTL`，不读取敏感值、不写入、不清理。

关键参数：

| 域 | 开关 | 严格模式 | 关键参数 |
|---|---|---|---|
| PostgreSQL | `BATCH_PROD_RETENTION_CHECK_POSTGRES` | `BATCH_PROD_RETENTION_POSTGRES_STRICT` | `BATCH_PROD_RETENTION_OLD_RUNTIME_DAYS`、`BATCH_PROD_RETENTION_OLD_OUTBOX_DAYS`、`BATCH_PROD_RETENTION_OLD_TRIGGER_DAYS`、`BATCH_PROD_RETENTION_DEDUP_WARN_ROWS` |
| Kafka | `BATCH_PROD_RETENTION_CHECK_KAFKA` | `BATCH_PROD_RETENTION_KAFKA_STRICT` | `BATCH_PROD_RETENTION_KAFKA_BOOTSTRAP`、`BATCH_PROD_RETENTION_KAFKA_TOPICS`、`BATCH_PROD_RETENTION_KAFKA_REQUIRE_TOPIC_RETENTION` |
| 对象存储 | `BATCH_PROD_RETENTION_CHECK_OBJECT_STORE` | `BATCH_PROD_RETENTION_OBJECT_STORE_STRICT` | `BATCH_PROD_RETENTION_OBJECT_STORE_ENDPOINT`、`BATCH_PROD_RETENTION_OBJECT_STORE_BUCKETS`、`BATCH_PROD_RETENTION_OBJECT_STORE_REQUIRE_LIFECYCLE` |
| Valkey / Redis | `BATCH_PROD_RETENTION_CHECK_REDIS` | `BATCH_PROD_RETENTION_REDIS_STRICT` | `BATCH_PROD_RETENTION_REDIS_HOST`、`BATCH_PROD_RETENTION_REDIS_PORT`、`BATCH_PROD_RETENTION_REDIS_PATTERNS`、`BATCH_PROD_RETENTION_REDIS_SCAN_LIMIT` |

该入口已经接入 `scripts/ops/inspect-all.sh`，可通过
`BATCH_INSPECT_SKIP_PRODUCTION_RETENTION_PLAN=true` 临时跳过。执行型清理仍只允许在对应 runbook 和变更审批下按域运行，不能把 `PLAN` 输出直接视为可删除清单。

## 4. 分域参数

生产治理支持按域拆分开关、阈值和严格度。总开关 `BATCH_PROD_CAPACITY_STRICT` 只是默认值；任一分域
`*_STRICT` 显式设置后，以分域值为准。

| 域 | 开关 | 严格模式 | 关键参数 |
|---|---|---|---|
| PostgreSQL | `BATCH_PROD_CAPACITY_CHECK_POSTGRES` | `BATCH_PROD_CAPACITY_POSTGRES_STRICT` | `BATCH_PROD_CAPACITY_TABLE_SIZE_WARN_MB`、`BATCH_PROD_CAPACITY_DEAD_TUPLE_WARN_COUNT`、`BATCH_PROD_CAPACITY_OUTBOX_BACKLOG_WARN_COUNT`、`BATCH_PROD_CAPACITY_TRIGGER_BACKLOG_WARN_COUNT`、`BATCH_PROD_CAPACITY_DEDUP_WARN_COUNT`、`BATCH_PROD_CAPACITY_OLD_RUNTIME_WARN_DAYS` |
| Kafka | `BATCH_PROD_CAPACITY_CHECK_KAFKA` | `BATCH_PROD_CAPACITY_KAFKA_STRICT` | `BATCH_PROD_CAPACITY_KAFKA_BOOTSTRAP`、`BATCH_PROD_CAPACITY_KAFKA_TOPICS`、`BATCH_PROD_CAPACITY_KAFKA_MIN_REPLICATION_FACTOR`、`BATCH_PROD_CAPACITY_KAFKA_REQUIRE_RETENTION` |
| 对象存储 | `BATCH_PROD_CAPACITY_CHECK_OBJECT_STORE` | `BATCH_PROD_CAPACITY_OBJECT_STORE_STRICT` | `BATCH_PROD_CAPACITY_OBJECT_STORE_ENDPOINT`、`BATCH_PROD_CAPACITY_OBJECT_STORE_BUCKETS`、`BATCH_PROD_CAPACITY_OBJECT_STORE_REQUIRE_LIFECYCLE` |
| 运行时治理 profile | `BATCH_INSPECT_SKIP_RUNTIME_GOVERNANCE` | 无分域严格度；生产 profile 内置 fail-close 校验 | `BATCH_DISPATCH_CHANNEL_TYPES_GOVERNED`、`BATCH_DISPATCH_NAS_SANDBOX_ROOT`、`BATCH_WORKER_REPORT_OUTBOX_STORAGE`、`BATCH_TRIGGER_MISFIRE_PENDING_RETENTION_DAYS`、`BATCH_OBSERVABILITY_RETENTION_DAYS` |

示例：上线前要求 PG / Kafka 严格阻断，但对象存储由 SRE 在独立窗口补 lifecycle 证据：

```bash
BATCH_PROD_CAPACITY_POSTGRES_STRICT=true \
BATCH_PROD_CAPACITY_KAFKA_STRICT=true \
BATCH_PROD_CAPACITY_OBJECT_STORE_STRICT=false \
BATCH_PROD_CAPACITY_OBJECT_STORE_REQUIRE_LIFECYCLE=true \
bash scripts/ops/inspect-production-capacity.sh
```

该入口已经接入 `scripts/ops/inspect-all.sh`，可通过
`BATCH_INSPECT_SKIP_PRODUCTION_CAPACITY=true` 临时跳过。PR Gate / Full Gate 通过
`scripts/ci/check-production-capacity-governance.py` 校验脚本、只读 SQL、runbook、索引和 workflow
入口同步，避免生产容量治理退化成无人维护的文档。

## 5. 四环境治理配置

PostgreSQL、Kafka、Valkey / Redis、MinIO 的治理参数已固化在
`config/ops-governance/{local,test,benchmark,prod}.env`。同一 profile 也登记文件通道、
状态后端、调度状态、拓扑、观测和外部端点治理参数。这些 profile 是环境级基线，不是生产批量删除脚本，
也不绑定本仓库的 Docker Compose。Compose、Helm、Kubernetes Operator、托管服务控制台和跳板机脚本都只是
把这组基线落到目标环境的适配层。

| 环境 | profile | 覆盖内容 | 生效方式 |
|---|---|---|---|
| 本地开发 | `local.env` | PG WAL/checkpoint/autovacuum、Kafka broker/topic retention、Valkey 内存/AOF、对象生命周期和运行时治理边界 | 可由 Compose、裸机脚本或 IDE 环境变量加载 |
| 场景测试 / sim | `test.env` | 比本地更长的证据保留窗口，仍保持有界增长 | 由测试环境部署系统或脚本显式加载 |
| 压测 | `benchmark.env` | 更积极的 autovacuum、短 Kafka/Object retention、较大 Valkey 数据预算 | 压测环境独立加载；压测后仍跑专用清理 |
| 生产 | `prod.env` | 生产基线值、RF/minISR、较长 retention、对象生命周期和 fail-close 安全基线 | 作为 Helm / Operator / 托管服务 / 平台配置对照；生产 apply 必须变更审批 |

预览 profile：

```bash
bash scripts/ops/apply-infra-governance.sh --profile local
```

外部环境可使用自己的基线文件，不要求落在仓库目录内：

```bash
bash scripts/ops/apply-infra-governance.sh --profile-file /etc/batch/prod-governance.env
bash scripts/ops/inspect-runtime-governance.sh --profile-file /etc/batch/prod-governance.env
```

对目标 Kafka / S3 兼容对象存储显式下发支持脚本的治理项：

```bash
bash scripts/ops/apply-infra-governance.sh \
  --profile-file /etc/batch/prod-governance.env \
  --apply-kafka-topics \
  --apply-minio-lifecycle
```

PostgreSQL 和 Valkey 的参数是启动配置，必须通过对应部署系统、数据库 Operator、托管服务参数组或滚动发布生效；
禁止用 `ALTER SYSTEM` / `CONFIG SET` 临时改生产，避免留下不可审计状态。Kafka topic retention
由 `scripts/data/init-kafka-topics.sh` 幂等下发；MinIO lifecycle 由
`scripts/minio/apply-lifecycle.sh` 下发，生产 `--apply` 必须设置
`MINIO_LIFECYCLE_PROD_ACK=I_UNDERSTAND_PRODUCTION_LIFECYCLE`，且拒绝本地默认 root 凭据。

运行时治理 profile 只做只读校验：

```bash
bash scripts/ops/inspect-runtime-governance.sh --profile prod
```

该入口不连接 NAS / SFTP / SMTP / API 下游，不验证真实凭据，也不创建或删除文件。真实通道连通性、
host key、TLS 证书、egress allowlist 和目标目录权限必须在对应环境用最小权限账号单独验收。

## 6. 运行时依赖边界

生产应用运行时不需要 Python。Java 服务、Worker 和 SDK 的运行镜像不能因为治理脚本引入 Python
运行时依赖。

生产容量巡检分两类环境：

| 环境 | 是否需要 Python | 说明 |
|---|---|---|
| 生产应用 Pod / 容器 | 不需要 | 只运行 Java 服务和对应 worker，不运行 CI 门禁脚本 |
| 运维机 / 跳板机 / CI Runner | 按需需要 | Python 只用于 `scripts/ci/check-*.py` 这类治理门禁；容量巡检脚本本身是 Bash |
| 生产只读巡检 | 不要求 Python | `inspect-production-capacity.sh` 主要依赖 `psql`、Kafka CLI 和 `mc`；缺少某个客户端时按分域严格度返回 WARN 或 FAIL |

这些客户端不要求生产应用环境自带。推荐交付方式是独立运维工具箱镜像，按巡检窗口以临时容器、
跳板机容器或 Kubernetes Job 运行；应用 Pod / 容器不安装 `psql`、Kafka CLI、`mc`、`redis-cli`、Docker CLI 或
Python 门禁依赖。`ops-toolbox` 不进入应用发布镜像，也不作为业务服务镜像的基础层。
工具箱容器必须以非 root 用户运行，默认 UID/GID 为 `10001:10001` 的 `batch` 用户。
`docker-image-build` CI 会构建该工具箱镜像，作为 Dockerfile、基础镜像 tag 和 CLI 复制路径的发布前校验；
该校验不表示工具箱进入应用服务镜像或随业务 Pod 常驻运行。

仓库提供的 `ops-toolbox` 是一种可选客户端打包方式，适合本地和自托管 Compose；生产也可以使用
平台已有的 bastion、Kubernetes Job、CI Runner 或 SRE 工具箱，只要提供等价的 `psql`、Kafka CLI、
`mc`、`redis-cli` 和只读凭据。

```bash
# 进入工具箱 shell
bash scripts/ops/run-toolbox.sh

# 在工具箱内跑生产容量巡检。Compose 网络内默认连接 postgres-primary / kafka / minio。
bash scripts/ops/run-toolbox.sh bash scripts/ops/inspect-production-capacity.sh

# 只跑 PostgreSQL，Kafka / 对象存储由平台侧独立巡检时关闭。
BATCH_PROD_CAPACITY_CHECK_KAFKA=false \
BATCH_PROD_CAPACITY_CHECK_OBJECT_STORE=false \
  bash scripts/ops/run-toolbox.sh bash scripts/ops/inspect-production-capacity.sh
```

工具箱版本由 `.env` 控制，默认与本地基础依赖对齐：

| 参数 | 作用 |
|---|---|
| `OPS_TOOLBOX_PYTHON_VERSION` | 工具箱基础 Python 版本，仅服务 CI/治理脚本，不进入应用镜像 |
| `OPS_TOOLBOX_POSTGRES_CLIENT_MAJOR` | `psql` 客户端主版本，应与 PostgreSQL 主版本一致 |
| `KAFKA_IMAGE_TAG` | Kafka CLI 来源镜像版本，与本地 Kafka broker 复用同一制品 |
| `MINIO_MC_IMAGE_REPOSITORY` / `MINIO_MC_IMAGE_TAG` | `mc` 来源镜像，与本地 MinIO 初始化容器复用同一制品 |

Kafka 不是只复制一个入口脚本。工具箱会从官方 Kafka 镜像复制 `bin/`、`libs/`、`config/` 和
`licenses/` 到 `/opt/kafka-client`，并在构建期执行 `kafka-topics.sh --version` 校验 classpath。
`/usr/local/bin/kafka-*.sh` 只是便捷 wrapper，实际仍执行 `/opt/kafka-client/bin` 下的官方 CLI。

工具箱同时安装 `redis-cli`，用于 Valkey/Redis 只读巡检、quota / ShedLock / 缓存定位和连接验证。
生产自愈或删除 key 仍需走对应 runbook 和变更审批，不能把工具箱当作默认清理入口。

## 7. 生产账号与权限边界

生产容量巡检必须使用最小权限账号，不能复用应用写账号、root / superuser、MinIO 管理员账号或
Redis 管理账号。工具箱只提供客户端，不降低目标系统的权限要求。

| 组件 | 巡检账号要求 | 禁止 |
|---|---|---|
| PostgreSQL | 只读账号；允许连接目标库、读取 `pg_catalog` / `information_schema`、读取被巡检 schema 的表统计和行数 | 使用 SUPERUSER、表 owner、迁移账号或具备 DDL/DML 权限的应用写账号 |
| Kafka | 只读 admin / describe 权限；允许 list / describe topic 和读取 topic config | 使用可删除 topic、修改 config、reset offset 或生产消费组写权限的账号 |
| 对象存储 | 只读账号；允许目标 bucket 的 `list`、`stat`、`du`、读取 lifecycle/ilm 元数据 | 使用 root access key、可删除对象、可改 bucket policy/lifecycle 的管理员账号 |
| Valkey / Redis | 只读 ACL；允许 `PING`、`INFO`、`TTL`、`SCAN` 等诊断命令 | 使用 `DEL`、`FLUSH*`、`CONFIG`、`ACL`、`EVAL`、写入和管理权限 |

生产执行前必须确认凭据来源是 Secret / Vault / 平台密钥管理，不从 `.env.example`、本地默认密码或
开发 MinIO root key 继承。若某组件暂时无法提供只读账号，该域应以 `*_STRICT=false` 降级为
人工验收项，并在上线准入记录中说明原因和补齐计划。

文档外部链接清理不属于生产容量治理。仓库文档结构门禁只检查仓库内相对链接和锚点，不联网检查
`https://` 外部 URL，避免第三方站点波动阻断 CI。外部链接失效应作为文档维护事项处理，不应影响
生产运行或容量巡检。

## 8. PostgreSQL 治理口径

生产要同时看三类大小：

1. `pg_database_size(current_database())`：数据库逻辑大小。
2. Top relation `pg_total_relation_size`：表和索引热点。
3. 文件系统 PGDATA / WAL：包含膨胀、WAL、FSM/VM、临时文件和未归还空间。

本地压测里常见“库只有几百 MB，但 Docker volume 多 GB”并不矛盾。普通 `VACUUM` 会复用空间，不保证归还给文件系统；WAL 和历史 segment 也会留在卷内。生产不能靠删卷解决，必须按下面顺序治理：

1. 先确认业务残留：活跃实例、Outbox、Trigger ACCEPTED、Kafka lag 是否归零。
2. 再看热表：`file_record`、`pipeline_step_run`、`pipeline_progress`、`outbox_event`、`job_instance`、幂等账本。
3. 再看 dead tuple 和长事务，确认 autovacuum 是否被阻塞。
4. 只有在维护窗口、备份确认、空间必须立即归还时，才评估 `pg_repack` 或等价在线重整；`VACUUM FULL` 属于强锁操作，不作为常规手段。

关联 runbook：

- [PG 表分区运维](./pg-table-partitioning.md)
- [幂等 dedup ledger 留存治理](./dedup-ledger-retention.md)
- [PostgreSQL 备份 / PITR / 容量护栏](./backup-and-pitr.md)

## 9. Kafka 治理口径

Kafka 增长主要由 topic retention、segment、消费 lag 和副本因子决定。生产核心原则：

- 业务 topic 必须有明确 retention。长期审计证据应落 PostgreSQL 归档或对象存储，不靠 Kafka 永久保留。
- 生产 `replication.factor >= 3` 且 `min.insync.replicas >= 2`；本地单 broker 只能做功能验证。
- lag 持续增长时，先查 worker 饱和度、PostgreSQL 写入和 direct topic 热点，再扩容或限流。
- 不在生产直接删除 topic、重置 group offset 或缩短 retention 到分钟级清盘；这些都会改变重放语义。

本地压测需要快速回收 Kafka 空间时，使用 `load-tests/scripts/cleanup-load-test-environment.sh --apply --confirm-project batch-platform --kafka-reset-topics`，该命令只用于本机 Compose 压测环境。

## 10. 对象存储治理口径

对象存储需要物理分桶和生命周期治理：

- 批量文件、导入/导出中间文件、坏行文件、AI 附件使用独立 bucket 或稳定前缀，不能用“image”这种会误导范围的属性名承载所有附件。
- 上传完成信号必须有 manifest / checksum / 长度校验，不能只依赖对象存在。
- 导入 staging、导出 draft、坏行文件、分发归档分别设置 lifecycle；合规要求更长保留的租户使用专属 bucket 或策略。
- 生产 lifecycle 在对象存储侧执行，应用侧只负责记录业务索引和校验信息。

关联 runbook：

- [对象存储生命周期策略](./minio-lifecycle-policy.md)
- [S3 后端](./object-storage-s3-backends.md)
- [Filesystem](./object-storage-filesystem.md)

## 11. 压测与生产的边界

压测允许批量造数和批量清理；生产不允许。压测脚本新增磁盘水位和清理入口，只证明本地环境可控，不等价于生产容量治理已经达标。

| 场景 | 允许动作 |
|---|---|
| 本地压测 | 清压测租户、压测 runId、Kafka 测试 topic、MinIO 压测前缀、VACUUM ANALYZE |
| staging 压测 | 只清专用测试租户和专用 bucket/prefix，保留报告证据 |
| 生产 | 只读巡检、归档、生命周期、备份恢复演练和变更审批后的保留策略调整 |

## 12. 上线准入清单

- [ ] `inspect-production-capacity.sh` 在目标环境可跑通，严格模式没有失败。
- [ ] `plan-production-retention.sh` 在目标环境可跑通，`PLAN` 项均有对应归档、lifecycle、retention 或 TTL 处置记录。
- [ ] 巡检使用 PostgreSQL / Kafka / 对象存储 / Valkey 最小权限账号，没有复用本地默认凭据或生产管理员凭据。
- [ ] `ops-toolbox` 只作为临时巡检容器 / Job 使用，没有进入应用发布镜像或业务服务基础镜像，容器身份为非 root。
- [ ] PostgreSQL 备份、WAL 归档和恢复演练有证据。
- [ ] Outbox、Trigger、运行实例、幂等账本有保留期和归档口径。
- [ ] Kafka 核心 topic 有副本、minISR、retention 和 lag 告警。
- [ ] 对象存储 bucket 分离，lifecycle 可查，关键对象有 checksum/manifest。
- [ ] 压测残留清理只作用于测试租户、测试 runId 和测试前缀，没有复用生产清理入口。
