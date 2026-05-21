# 本地开发环境与联调说明

这里说明本地开发、联调和验证所需的基础依赖，默认通过 Docker Compose 启动：

- PostgreSQL 16
- Kafka 4.1.2（KRaft 单节点）
- MinIO
- Redis

## 设计对齐点

- PostgreSQL 采用单库多 schema：`batch_platform` 库下预建 `batch`、`quartz`
- 业务导入导出单独使用 `batch_business` 库，默认预建 `biz` schema
- Kafka 预建设计文档推荐 Topic
- MinIO 预建开发桶 `batch-dev`

## 文件说明

- `docker-compose.yml`：本地依赖编排
- `.env.example`：环境变量样例
- `.env.local`：本地默认环境变量
- `.env.test`：测试环境环境变量
- `.env.prod`：生产环境模板变量
- `docker/postgres/init/000-create-business-db.sql`：业务库与业务 schema 初始化
- `docker/postgres/init/001-create-schemas.sql`：数据库 schema 初始化
- `scripts/data/init-kafka-topics.sh`：Kafka Topic 初始化
- `scripts/data/init-minio.sh`：MinIO bucket 初始化
- `scripts/local/build-apps.sh`：单独打包本地 Java 应用模块
- `scripts/local/start-all.sh`：一键启动本地依赖 + Java 模块
- `scripts/local/stop-all.sh`：停止本地 Java 模块，可选停止 Docker 依赖（只 stop，不 down）
- `scripts/docker/down-apps.sh`：停止本地应用容器栈（只 stop，不 down）
- `docs/deployment/docker-deployment.md`：Docker 部署基线说明

## 启动

### 仅启动基础依赖

```bash
docker compose --env-file .env.local -f docker-compose.yml up -d
```

### 一键启动本地联调全栈

首次启动或代码有变更时，先构建：

```bash
bash scripts/local/build-apps.sh
```

再启动：

```bash
bash scripts/local/start-all.sh
```

说明：首次使用时先将 `.env.example` 复制为 `.env.local`。启动脚本默认使用 `.env.local`，如需切换环境可先设置 `COMPOSE_ENV_FILE=.env.test` 或 `COMPOSE_ENV_FILE=.env.prod`。脚本会等待 PostgreSQL、MinIO、Redis 健康检查通过，并确认 Kafka topic / MinIO bucket 初始化完成后，再启动 Java 模块。默认情况下 `start-all.sh` 不会自动执行 Maven 打包；如需“构建 + 启动”，可使用 `BUILD=1 bash scripts/local/start-all.sh`。

`start-all.sh` / `restart.sh` 会在启动 fat jar 前 **source 同一份 `COMPOSE_ENV_FILE`**：时区以 **`BATCH_TIMEZONE_DEFAULT_ZONE`** 为准（未设置则默认 `Asia/Shanghai`），并导出 **`TZ`**（默认同值，兼容仍在 `.env` 里写 `TZ=` 的旧模板）；**`BATCH_LOCALE`**（默认 `C.UTF-8`）驱动 **`LANG`/`LC_ALL`**，与本机 JVM / 容器一致。

在 **IDE 中直接 Run**（不经过上述脚本）时，请在 Run Configuration 里至少设置 **`BATCH_TIMEZONE_DEFAULT_ZONE`** 与 **`BATCH_LOCALE`**（与同仓库 `.env.local` 一致）；若需 JVM 默认区与之一致，请再设 **`TZ`** 为同一 IANA 值（例如与 `BATCH_TIMEZONE_DEFAULT_ZONE` 均填 `Asia/Shanghai`），或使用 VM options `-Duser.timezone=Asia/Shanghai`。
如果某些 Java 模块已经在运行，脚本会跳过它们，只补启动未运行或已退出的模块。

查看状态：

```bash
docker compose ps
```

查看日志：

```bash
docker compose logs -f postgres
docker compose logs -f kafka
docker compose logs -f minio
docker compose logs -f redis
```

## 连接信息

### PostgreSQL

- Host：`localhost`
- Port：`15432`
- Platform Database：`batch_platform`
- Business Database：`batch_business`
- Username：`batch_user`
- Password：`batch_pass_123`
- Platform Schemas：`batch`、`quartz`
- Business Schema：`biz`

JDBC：

```text
jdbc:postgresql://localhost:15432/batch_platform
```

业务库 JDBC：

```text
jdbc:postgresql://localhost:15432/batch_business
```

说明：当前只预建业务库和 `biz` schema，具体业务表后续按真实字段模型再创建。

### Kafka

- Bootstrap Servers：`localhost:19092`
- Docker 内部网络地址：`kafka:29092`

默认 Topic：

- `batch.task.dispatch.import`
- `batch.task.dispatch.export`
- `batch.task.dispatch.dispatch`
- `batch.task.result`
- `batch.task.retry`
- `batch.task.dead-letter`

### MinIO

- API：`http://localhost:19000`
- Console：`http://localhost:19001`
- Access Key：`minioadmin`
- Secret Key：`minioadmin123`
- Bucket：`batch-dev`

## Console 默认账号

系统通过 Flyway 迁移脚本预置以下账号（用户名全局唯一，登录时无需指定租户，后端自动解析所属租户）：

| 用户名 | 密码 | 角色 | 说明 |
|--------|------|------|------|
| `admin` | `admin123` | `ROLE_ADMIN` | 全权限管理员 |
| `auditor` | `auditor123` | `ROLE_AUDITOR` | 只读审计角色 |
| `config-admin` | `config123` | `ROLE_TENANT_ADMIN` | 配置与运维角色 |
| `tenant-user` | `tenant123` | `ROLE_TENANT_USER` | 租户业务用户（可查看状态、触发作业、下载文件） |

所有账号默认属于 `default-tenant` 租户。

## 常用命令

进入 PostgreSQL：

```bash
docker exec -it batch-postgres psql -U batch_user -d batch_platform
```

查看 Kafka Topic：

```bash
docker exec -it batch-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:19092 \
  --list
```

停止环境：

```bash
bash scripts/local/stop-all.sh

# 停止应用容器栈，但保留容器与网络
bash scripts/docker/down-apps.sh
```

如果需要连数据卷一起清理：

```bash
docker compose down -v
```

---

# 本地环境变量补充（合并自 local-development.md）


> 基于 `.env.example` 默认值。如果你改过 `.env.local` 里的端口，以实际配置为准。
>
> 启动命令：
> ```bash
> # 基础服务
> docker compose --env-file .env.local up -d
> # 可观测性（Prometheus / Grafana / Jaeger / Loki）
> docker compose -f docker-compose.observability.yml --env-file .env.local up -d
> ```

---

## 应用服务

| 服务 | 地址 | 备注 |
|------|------|------|
| Console API | http://localhost:18080 | 批量调度控制台后端 |
| Trigger | http://localhost:18081 | 定时触发器 |
| Orchestrator | http://localhost:18082 | 调度编排器 |

---

## 基础设施

| 服务 | 地址 | 账号 | 密码 |
|------|------|------|------|
| PostgreSQL | localhost:15432 | `batch_user` | `batch_pass_123` |
| Redis | localhost:16379 | — | — |
| Kafka | localhost:19092 | — | — |
| MinIO API | http://localhost:19000 | `minioadmin` | `minioadmin123` |
| MinIO 控制台 | http://localhost:19001 | `minioadmin` | `minioadmin123` |

PostgreSQL 数据库名：`batch_platform`，业务库 schema：`biz`（`batch_business`）

---

## 可观测性

| 服务 | 地址 | 账号 | 密码 |
|------|------|------|------|
| Grafana | http://localhost:13000 | `admin` | `admin` |
| Prometheus | http://localhost:19090 | — | — |
| Jaeger UI | http://localhost:16686 | — | — |
| Loki | http://localhost:13100 | — | — （API only，通过 Grafana 查询）|

### Exporter 端口（Prometheus scrape，一般不需要直接访问）

| Exporter | 地址 |
|----------|------|
| Redis Exporter | http://localhost:19121/metrics |
| Postgres Exporter | http://localhost:19187/metrics |
| Kafka Exporter | http://localhost:19308/metrics |
| Node Exporter | http://localhost:19100/metrics |
| cAdvisor | http://localhost:19101/metrics |
