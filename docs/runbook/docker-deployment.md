# Docker 部署基线

仓库现在提供两种 Docker 使用方式：

- `docker-compose.yml`：本地基础依赖
- `deploy/docker/compose/app.yml`：应用容器部署
- `deploy/docker/compose/observability.yml`：可选观测栈叠加层

建议按环境选择对应的 env 文件：

- `.env.local`：本地开发
- `.env.test`：测试环境
- `.env.prod`：生产环境模板

## 环境标识与 Compose 项目名

- 普通本地 Docker 栈固定使用 Compose 项目名 `batch-platform`，网络名为
  `batch-platform_batch-network`。
- `be-acceptance` 是验收脚本和日志的运行标识，不是普通本地 Docker 栈的项目名。
- Docker 观测标签由 `DEPLOYMENT_ENVIRONMENT` 注入，本地默认值为 `local`；生产 Helm
  部署必须使用 `helm/values-prod.yaml` 的 `production` 覆盖值。
- `COMPOSE_PROJECT_NAME` 仍可由压测或隔离环境显式覆盖，但不同工作树不能共用固定的
  `batch-*` 容器名；切换工作树前应先停止上一套栈。

## 构建应用镜像

```bash
./scripts/docker/build-apps.sh
```

默认会启用 `DOCKER_BUILDKIT=1` 和 `COMPOSE_DOCKER_CLI_BUILD=1`。
如需切换环境，可在执行前指定 `COMPOSE_ENV_FILE=.env.test` 或 `COMPOSE_ENV_FILE=.env.prod`。

## 启动完整容器栈

```bash
./scripts/docker/up-apps.sh
```

这会启动：

- PostgreSQL
- Kafka
- Kafka UI
- MinIO
- Redis
- `batch-trigger`
- `batch-orchestrator`
- `batch-worker-import`
- `batch-worker-export`
- `batch-worker-process`
- `batch-worker-dispatch`
- `batch-console-api`

默认宿主机端口：

| 服务 | 地址 |
|---|---|
| console-api | `http://localhost:18080` |
| Kafka broker | `localhost:19092` |
| Kafka UI | `http://localhost:18090` |
| MinIO API | `http://localhost:19000` |
| MinIO Console | `http://localhost:19001` |

Compose 应用服务（Console API、Trigger、Orchestrator 与五类 Worker）默认只绑定 `127.0.0.1`，用于防止内部 API 暴露到外部网卡。需要远程访问时，设置 `APP_BIND_IP=0.0.0.0`，并在主机防火墙限制来源地址；内部接口仍要求服务间凭据。PostgreSQL、Kafka、MinIO、Valkey 等基础设施端口由各自端口变量控制，按本地联调需要单独开放。

## MinIO 卷与运行身份

前后端应用的 `batch:batch`（10001:10001）和 MinIO 的 1001:1001 是版本化的镜像与卷权限契约，不提供单独的运行时 UID/GID 覆盖变量。调整身份时必须一起重建镜像、修改 Compose/Helm、停机处理已有卷所有权并重新验收；仅覆盖 `user` 会导致权限不一致。

MinIO 及 `minio-init` 以 UID/GID 1001 运行。`minio-volume-init` 是非 root 的一次性任务：具名 `minio-data` 卷挂载到镜像内由 1001 持有的目录，预备后 MinIO 再将同一卷以 `nocopy` 挂到实际数据目录 `/bitnami/minio/data`。卷权限不匹配时预备任务会失败，不会自动改动旧数据。

旧版 Compose 把 `minio-data` 挂在 `/data`，但镜像实际把对象写入 `/bitnami/minio/data` 的匿名卷。升级前用 `docker inspect batch-minio` 核对挂载并备份实际数据；改挂正确路径**不会自动迁移匿名卷中的 bucket 和对象**。若选择清空重建，应先停止并移除旧容器，再仅删除确认属于该容器的旧数据卷，随后以原 `minio-data` 名称创建新卷；不要使用会清理其他服务卷的 `docker compose down -v`。

### 非 root 验收

```bash
docker top batch-minio -eo pid,user,uid,gid,comm
docker top batch-valkey -eo pid,user,uid,gid,comm
docker inspect batch-minio --format '{{range .Mounts}}{{println .Destination .Name}}{{end}}'
docker compose --env-file .env.local run --rm --no-deps minio-volume-init
docker compose --env-file .env.local ps -a minio-init
```

MinIO 主进程及 `minio-init` 应为 1001:1001，Valkey 的 PID 1 与服务进程均应为镜像内的 `valkey` 用户；MinIO 数据挂载应是 `/bitnami/minio/data` 对应的具名 `minio-data` 卷。卷预备命令应返回 0；`minio-init` 应以退出码 0 完成。运维脚本以 `run --rm` 执行卷预备任务，结束后不会保留在 `ps -a` 中。Tempo/Collector 的身份与卷检查见[观测栈运行手册](./observability-stack.md)。如预备任务提示旧卷不可写，先停止受影响服务并备份，再离线修正卷权限；不要放宽为 777，也不要让业务容器以 root 运行。已删除的旧 MinIO 卷无法从新空卷恢复。

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
业务栈和观测栈仍然是分开的 compose 文件，但会通过 `${COMPOSE_PROJECT_NAME:-batch-platform}_batch-network` 共享网络互通。

应用容器的文件日志会写到 `./logs/current/docker/*.log`（兼容路径 `./logs/docker` 指向 `./logs/current/docker`），可直接在本地查看或 `tail -f`。

## 停止

```bash
./scripts/docker/down-apps.sh
```

## 说明

- 应用镜像使用统一的 `deploy/docker/Dockerfile.app`
- 运维工具箱镜像使用 `deploy/docker/Dockerfile.ops-toolbox`，CI 会随 `docker-image-build` 一起构建校验；该镜像只用于临时巡检容器 / Job，不作为业务服务基础镜像
- 构建时通过 `MODULE` 参数选择模块
- 运行时通过 `depends_on` 等待数据库、Kafka topic 初始化和 MinIO bucket 初始化完成
- 镜像内置 `curl`，用于容器健康检查
- 只有 `console-api`、`trigger`、`orchestrator` 暴露 HTTP 健康检查；三个 worker 是非 Web 进程，靠容器重启策略和启动顺序保障
- `console-api` 的普通 REST 接口可以直接做负载均衡；SSE 实时接口通过 Redis Pub/Sub 广播并结合 replay buffer 回放，允许多实例部署
- `console-api` realtime 层会消费 Redis Pub/Sub 并转发到本机 SSE 连接，不再依赖 sticky session
