# Docker 部署基线

仓库现在提供两种 Docker 使用方式：

- `docker-compose.yml`：本地基础依赖
- `docker-compose.app.yml`：应用容器部署

## 构建应用镜像

```bash
docker compose -f docker-compose.yml -f docker-compose.app.yml --profile apps build
```

## 启动完整容器栈

```bash
docker compose -f docker-compose.yml -f docker-compose.app.yml --profile apps up -d
```

这会启动：

- PostgreSQL
- Kafka
- MinIO
- `batch-trigger`
- `batch-orchestrator`
- `batch-worker-import`
- `batch-worker-export`
- `batch-worker-dispatch`
- `batch-console-api`

## 停止

```bash
docker compose -f docker-compose.yml -f docker-compose.app.yml --profile apps down
```

## 说明

- 应用镜像使用统一的 `docker/Dockerfile.app`
- 构建时通过 `MODULE` 参数选择模块
- 运行时通过 `depends_on` 等待数据库、Kafka topic 初始化和 MinIO bucket 初始化完成
- 镜像内置 `curl`，用于容器健康检查
- 只有 `console-api`、`trigger`、`orchestrator` 暴露 HTTP 健康检查；三个 worker 是非 Web 进程，靠容器重启策略和启动顺序保障
