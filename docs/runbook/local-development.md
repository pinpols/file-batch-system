# 本地开发环境与联调说明

这里说明本地开发、联调和验证所需的基础依赖，默认通过 Docker Compose 启动：

- PostgreSQL 16
- Kafka 4.1.2（KRaft 单节点）
- MinIO

## 设计对齐点

- PostgreSQL 采用单库多 schema：`batch_platform` 库下预建 `batch`、`quartz`
- 业务导入导出单独使用 `batch_business` 库，默认预建 `biz` schema
- Kafka 预建设计文档推荐 Topic
- MinIO 预建开发桶 `batch-dev`

## 文件说明

- `docker-compose.yml`：本地依赖编排
- `.env.local`：本地默认环境变量
- `.env.test`：测试环境环境变量
- `.env.prod`：生产环境模板变量
- `docker/postgres/init/000-create-business-db.sql`：业务库与业务 schema 初始化
- `docker/postgres/init/001-create-schemas.sql`：数据库 schema 初始化
- `scripts/local/init-kafka-topics.sh`：Kafka Topic 初始化
- `scripts/local/init-minio.sh`：MinIO bucket 初始化
- `scripts/local/start-all.sh`：一键启动本地依赖 + Java 模块
- `scripts/local/stop-all.sh`：停止本地 Java 模块，可选停止 Docker 依赖
- `docs/deployment/docker-deployment.md`：Docker 部署基线说明

## 启动

### 仅启动基础依赖

```bash
docker compose --env-file .env.local -f docker-compose.yml up -d
```

### 一键启动本地联调全栈

```bash
bash scripts/local/start-all.sh
```

说明：启动脚本默认使用 `.env.local`，如需切换环境可先设置 `COMPOSE_ENV_FILE=.env.test` 或 `COMPOSE_ENV_FILE=.env.prod`。脚本会等待 PostgreSQL、MinIO 健康检查通过，并确认 Kafka topic / MinIO bucket 初始化完成后，再启动 Java 模块。
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

- Bootstrap Servers：`localhost:9092`
- Docker 内部网络地址：`kafka:29092`

默认 Topic：

- `batch.task.dispatch.import`
- `batch.task.dispatch.export`
- `batch.task.dispatch.dispatch`
- `batch.task.result`
- `batch.task.retry`
- `batch.task.dead-letter`

### MinIO

- API：`http://localhost:9000`
- Console：`http://localhost:9001`
- Access Key：`minioadmin`
- Secret Key：`minioadmin123`
- Bucket：`batch-dev`

## 常用命令

进入 PostgreSQL：

```bash
docker exec -it batch-postgres psql -U batch_user -d batch_platform
```

查看 Kafka Topic：

```bash
docker exec -it batch-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

停止环境：

```bash
bash scripts/local/stop-all.sh
```

如果需要连数据卷一起清理：

```bash
docker compose down -v
```
