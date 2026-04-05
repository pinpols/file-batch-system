# 观测栈 Docker 脚本

这里放可观测性相关的 Docker 启动脚本。它和业务容器脚本分开，方便单独启停和排障。

## 目录职责

- 启动 / 停止观测栈
- 查看观测栈容器状态
- 跟踪观测栈日志
- 复用本仓库现有的健康检查脚本

## 脚本清单

- `up.sh`：只启动 Prometheus / Exporter / OTel Collector / Jaeger / Loki / Grafana
- `down.sh`：停止观测栈容器，但不删除容器、网络或卷
- `status.sh`：查看观测栈容器状态
- `logs.sh`：跟踪观测栈日志
- `inspect.sh`：执行观测巡检

## 启动方式

```bash
./scripts/docker/observability/up.sh
```

默认使用 `.env.local`。如需切换环境：

```bash
COMPOSE_ENV_FILE=.env.test ./scripts/docker/observability/up.sh
```

## 说明

- 这套脚本只使用 `docker-compose.observability.yml`
- 观测栈通过共享的 `batch-network` 直接抓取业务容器和 exporter
- `Prometheus` 是观测栈的一部分，不是业务运行必需项
- 如果业务应用容器已经在跑，观测栈可以直接抓取它们的 `/actuator/prometheus`
- 系统 CPU / 内存 / 磁盘 / 网络 / 负载指标由 node_exporter 和 cAdvisor 提供
