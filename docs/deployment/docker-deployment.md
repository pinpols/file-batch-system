# Docker 部署基线

仓库现在提供两种 Docker 使用方式：

- `docker-compose.yml`：本地基础依赖
- `docker-compose.app.yml`：应用容器部署
- `docker-compose.observability.yml`：可选观测栈叠加层

建议按环境选择对应的 env 文件：

- `.env.local`：本地开发
- `.env.test`：测试环境
- `.env.prod`：生产环境模板

## 构建应用镜像

```bash
docker compose --env-file .env.local -f docker-compose.yml -f docker-compose.app.yml --profile apps build
```

## 启动完整容器栈

```bash
./scripts/docker/up-apps.sh
```

这会启动：

- PostgreSQL
- Kafka
- MinIO
- Redis
- `batch-trigger`
- `batch-orchestrator`
- `batch-worker-import`
- `batch-worker-export`
- `batch-worker-dispatch`
- `batch-console-api`

## 启动观测栈

```bash
./scripts/docker/observability/up.sh
```

观测栈会额外启动：

- Prometheus
- PostgreSQL exporter
- Redis exporter
- Kafka exporter
- OTel Collector
- Jaeger
- Loki
- Grafana

对应脚本在 [scripts/docker/observability/](../../scripts/docker/observability/)。

如果你只需要业务运行，不需要监控面板和 trace/log 链路，这一层可以不启。
业务栈和观测栈仍然是分开的 compose 文件，但会通过 `${COMPOSE_PROJECT_NAME:-batch-local}_batch-network` 共享网络互通。

应用容器的文件日志会写到 `./logs/docker/*.log`，可直接在本地查看或 `tail -f`。

## 停止

```bash
./scripts/docker/down-apps.sh
```

## 说明

- 应用镜像使用统一的 `docker/Dockerfile.app`
- 构建时通过 `MODULE` 参数选择模块
- 运行时通过 `depends_on` 等待数据库、Kafka topic 初始化和 MinIO bucket 初始化完成
- 镜像内置 `curl`，用于容器健康检查
- 只有 `console-api`、`trigger`、`orchestrator` 暴露 HTTP 健康检查；三个 worker 是非 Web 进程，靠容器重启策略和启动顺序保障
- `console-api` 的普通 REST 接口可以直接做负载均衡；SSE 实时接口通过 Redis Streams 做共享事件源，允许多实例部署
- `console-api` realtime 层会消费 Redis Stream 并转发到本机 SSE 连接，不再依赖 sticky session
