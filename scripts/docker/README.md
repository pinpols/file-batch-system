# 容器脚本说明

这里放只针对 Docker / Docker Compose 的入口脚本。

## 常用脚本

- `build-apps.sh`：构建本地应用镜像；单服务自动走 Maven 依赖闭包，多服务共享全 reactor
- `up-apps.sh`：启动本地基础依赖 + 应用容器
- `down-apps.sh`：停止本地基础依赖 + 应用容器（只 stop，不 down）
- `reset-dev.sh`：按 Compose project 精确预览/清空开发容器、命名卷和专用网络；默认不删除镜像
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
- `reset-dev.sh` 默认只预览；执行删除需要 `--apply`，并建议再次确认 Docker context。它拒绝
  `prod`、`production`、`staging`、`uat`、`preprod` 和 `default` 项目名，不执行全局
  `docker system prune`。开发环境完整重置示例：

  ```bash
  bash scripts/docker/reset-dev.sh
  bash scripts/docker/reset-dev.sh --apply
  bash scripts/docker/reset-dev.sh --apply --include-images
  ```

  命名卷包含 PostgreSQL、Kafka、MinIO 和 Valkey 数据；删除后需要重新执行 Flyway、seed 和
  MinIO/Kafka 初始化。BuildKit 缓存是 Docker 全局资源，不由该脚本处理；按保留周期清理请用
  `scripts/local/cleanup-disk.sh`。
- 应用启动前的业务库 bootstrap 使用 `--no-recreate`，仅确保 PostgreSQL 已启动并补齐 DDL/RLS；因此从不同 worktree 定向重启应用不会误滚动数据库容器。无参数全量启动仍会按 Compose 配置正常收敛基础设施。
- 构建应用镜像时优先使用 `./scripts/docker/build-apps.sh`，这样会默认开启 BuildKit 和 Docker CLI build
- 只重建 Atomic：`./scripts/docker/build-apps.sh worker-atomic`，脚本会自动传入 `BUILD_MODE=module`
- 整套 8 个镜像显式共享一次 builder：`BUILD_MODE=all ./scripts/docker/build-apps.sh`
- 整套并行构建也可使用 `docker buildx bake`；CI 叠加 `docker-bake.ci.hcl` 复用 GitHub Actions 远程缓存，并构建 `ci` 组覆盖应用镜像和 `ops-toolbox` 运维工具箱镜像
- 标准构建入口会写入 OCI `org.opencontainers.image.revision` 标签；工作树不干净时标签追加
  `-dirty`，容量基线据此拒绝把未提交代码或旧镜像当作当前提交测试。
- 手工执行 `docker buildx bake` 时应传入 `BUILD_REVISION=$(git rev-parse HEAD)`；CI 已自动使用
  `${{ github.sha }}`。未声明时标签为 `unknown`，只能用于普通联调，不能通过容量基线门禁。
- 观测栈的快捷入口也可以直接用 `make observability-up` / `make observability-down`

## 磁盘清理

本地磁盘与测试残留清理由 `scripts/local/cleanup-disk.sh` 按批次处理。脚本默认只预览；执行必须同时传入 `--apply` 和精确仓库名。脚本**永不删除 Docker 镜像**，也不删除应用/基础环境容器、命名卷或数据库数据；routing-sim 分片只有显式带 BFS 所有权标签的新建测试容器才可清理。同名 Compose/持久分片容器不会被选中。BuildKit 缓存是 Docker 全局缓存，会影响其他仓库的后续构建。

```bash
# 预览可清理项和 Docker 占用
bash scripts/local/cleanup-disk.sh

# 第一批：清理已退出的 BFS 测试容器、已退出的本项目初始化容器和业务分片残留
bash scripts/local/cleanup-disk.sh --batch test-residue
bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --batch test-residue

# 第二批：预览/清理超过 7 天的 BuildKit 缓存，不影响镜像
bash scripts/local/cleanup-disk.sh --batch build-cache
bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --batch build-cache

# 合并执行上述两批；保留所有镜像和数据卷
bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --batch safe

# 可选：清理本地显式复用的 Valkey/MinIO 测试容器。只在测试已停止后使用
bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --include-local-reuse-containers

# 磁盘紧张时，额外清理全部未使用的 BuildKit 缓存（仍不删除镜像）
bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --all-build-cache

# 匿名卷可能包含已移除测试容器的数据，必须单独检查预览后再选择启用
bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --retention-days 14 --include-anonymous-volumes

# 压测后清理观测栈命名卷（会清空 Prometheus/Loki/Tempo/Grafana 历史）：
bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --include-observability-volumes

# 清理应用归档日志（只处理 `logs/archive/app`，默认保留最近 7 天）：
bash scripts/local/cleanup-disk.sh --apply --confirm-root file-batch-system --include-app-logs
```

观测卷、历史日志和 Maven `target` 目录也必须通过独立参数显式启用。不要使用全局镜像清理或带卷的系统级清理命令；它们无法可靠区分应用/基础环境镜像与可丢弃资源，也无法区分测试卷和业务数据卷。
