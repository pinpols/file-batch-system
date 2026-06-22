# 容器脚本说明

这里放只针对 Docker / Docker Compose 的入口脚本。

## 常用脚本

- `build-apps.sh`：构建本地应用镜像，默认启用 BuildKit
- `up-apps.sh`：启动本地基础依赖 + 应用容器
- `down-apps.sh`：停止本地基础依赖 + 应用容器（只 stop，不 down）
- `up-observability.sh`：启动本地观测栈
- `down-observability.sh`：停止本地观测栈（只 stop，不 down）
- `observability/`：观测栈独立脚本目录

## 日志位置

- 应用容器的文件日志会落到 `./logs/current/docker/*.log`
- 兼容路径 `./logs/docker` 会指向 `./logs/current/docker`
- 你仍然可以用 `docker compose logs -f <service>` 看容器标准输出

## 使用建议

- 默认使用 `.env.local`
- 如需切换环境，可设置 `COMPOSE_ENV_FILE=.env.test` 或 `COMPOSE_ENV_FILE=.env.prod`
- 这类脚本不管理本地 Java 进程，只管理容器
- 构建应用镜像时优先使用 `./scripts/docker/build-apps.sh`，这样会默认开启 BuildKit 和 Docker CLI build
- 观测栈的快捷入口也可以直接用 `make observability-up` / `make observability-down`
