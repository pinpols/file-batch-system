# 观测栈 Docker 环境

这是一套独立的可观测性 Docker 环境，和业务容器分开管理。

## 目标

- 不影响业务容器的启动和停止
- 可以单独拉起 Prometheus / Exporter / OTel Collector / Jaeger / Loki / Grafana
- 复用仓库里的 `/actuator/prometheus`、结构化日志和 Trace 接入

## 目录结构

- [scripts/docker/observability/](../../../scripts/docker/observability/)：启动、停止、状态、日志和巡检脚本
- [docs/observability/](../../../docs/observability/)：观测配置、指标基线、OTel 接入、Grafana 数据源

## 启动方式

```bash
./scripts/docker/observability/up.sh
```

或者直接用顶层入口：

```bash
./scripts/docker/up-observability.sh
make observability-up
```

默认使用 `.env.local`。如果要切换环境：

```bash
COMPOSE_ENV_FILE=.env.test ./scripts/docker/observability/up.sh
```

启动脚本会自动检查并创建共享网络 `${COMPOSE_PROJECT_NAME:-batch-local}_batch-network`，避免观测栈先启动时找不到基础网络。

## 停止方式

```bash
./scripts/docker/observability/down.sh
```

或者直接用顶层入口：

```bash
./scripts/docker/down-observability.sh
make observability-down
```

## 常用辅助命令

```bash
./scripts/docker/observability/status.sh
./scripts/docker/observability/logs.sh
./scripts/docker/observability/inspect.sh
```

## 包含的组件

- `postgres-exporter`
- `redis-exporter`
- `kafka-exporter`
- `otel-collector`
- `jaeger`
- `loki`
- `grafana`
- `prometheus`

## 依赖关系

- 共享网络：`${COMPOSE_PROJECT_NAME:-batch-local}_batch-network`
- 基础依赖：PostgreSQL、Kafka、MinIO、Redis
- 应用服务：`console-api`、`trigger`、`orchestrator`、`worker-*`

## 说明

- `Prometheus` 不是业务运行必需容器
- 只有在需要指标、链路和日志联调时才启动观测栈
- 如果业务容器已启动，Prometheus 会直接抓取各服务的 `/actuator/prometheus`
- Worker 指标也会通过共享网络直接抓取，不依赖宿主机端口映射
