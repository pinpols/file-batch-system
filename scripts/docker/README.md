# 容器脚本说明

这里放只针对 Docker / Docker Compose 的入口脚本。

## 常用脚本

- `build-apps.sh`：构建本地应用镜像；单服务自动走 Maven 依赖闭包，多服务共享全 reactor
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
- 只重建 Atomic：`./scripts/docker/build-apps.sh worker-atomic`，脚本会自动传入 `BUILD_MODE=module`
- 整套 8 个镜像显式共享一次 builder：`BUILD_MODE=all ./scripts/docker/build-apps.sh`
- 整套并行构建也可使用 `docker buildx bake`；CI 叠加 `docker-bake.ci.hcl` 复用 GitHub Actions 远程缓存
- 观测栈的快捷入口也可以直接用 `make observability-up` / `make observability-down`

## 磁盘清理

本地磁盘清理由 `scripts/local/cleanup-disk.sh` 统一处理。脚本默认只预览；执行模式默认清理超过保留期的 Docker 构建缓存和悬空镜像。
观测栈命名卷、历史运行日志、Maven `target`、数据库文件和当前日志默认不清理，需显式参数开启。

```bash
# 预览
bash scripts/local/cleanup-disk.sh

# 清理超过 7 天的可再生 Docker 缓存
bash scripts/local/cleanup-disk.sh --apply

# 磁盘紧张时清理全部未使用的 BuildKit 缓存
bash scripts/local/cleanup-disk.sh --apply --all-build-cache

# 每个镜像仓库只保留 latest（无 latest 时保留最新版本）和容器引用版本
bash scripts/local/cleanup-disk.sh --apply --prune-old-image-tags

# 明确确认后，再清理超过 14 天且无引用的 Docker 匿名卷
bash scripts/local/cleanup-disk.sh --apply --retention-days 14 --include-anonymous-volumes

# 压测后清理观测栈命名卷（会清空 Prometheus/Loki/Tempo/Grafana 历史）：
bash scripts/local/cleanup-disk.sh --apply --include-observability-volumes

# 清理历史应用日志（不清理当前活跃日志文件，默认保留最近 7 天；包含 `logs/archive/app`）：
bash scripts/local/cleanup-disk.sh --apply --include-app-logs
```

历史运行日志和 Maven `target` 目录也必须通过独立参数显式启用。不要使用 `docker system prune --volumes`，它无法区分可丢弃测试卷和需要保留的数据卷。
