# 本地开发脚本

本地 JVM 模式下的启停、构建和测试脚本。

## 脚本列表

- `start-all.sh`：一键启动本地联调环境（基础依赖 + 6 个 Java 模块）
- `stop-all.sh`：分阶段停止本地 Java 进程
- `health-check-infra.sh`：基建健康检查(PG primary/replica / Kafka / Redis / MinIO)。
  协议层探测 + env-var 驱动,**任何环境可用**(本机走 `.env` 默认,staging/CI 覆盖
  `PG_PRIMARY_HOST` / `KAFKA_BOOTSTRAP` / `MINIO_URL` 等)。
  - `--quiet`:只 exit code(供 start-all.sh fail-fast 调用)
  - `--no-replica` / `--no-kafka` / `--no-minio`:跳过某项(staging 无 replica 时)
  - `make dev-health` 是它的别名
- `watchdog.sh`：长时间联调时挂在另一 tab 自动拉起被系统回收的 worker 进程
（macOS 闲置数小时会回收 JVM；docker-compose 模式不需要本脚本，靠 docker
自带 `restart: unless-stopped` 兜底）
- `build-apps.sh`：Maven 打包六个应用模块（-DskipTests）
- `run-tests.sh`：**本地一键测试入口**（推荐）
  - 默认：单元 + 集成（跳过 E2E）
  - `--unit`：仅单元测试，秒级，无容器
  - `--it`：仅集成测试（`*IntegrationTest`）
  - `--e2e`：仅 E2E 测试（`*E2eIT`）
  - `--all`：单元 + 集成 + E2E 全量
  - `-- <mvn args>`：透传 Maven 参数
- `docker-path.sh`：工具函数，确保 docker 在 PATH 中（供其他脚本 source）

## 运行前提

- Docker / Docker Desktop、JDK
- `start-all.sh` / `stop-all.sh` 默认使用 `.env.local`
- `start-all.sh` / `restart.sh` 启动 fat jar 前 source `COMPOSE_ENV_FILE`，并默认导出：`BATCH_TIMEZONE_DEFAULT_ZONE`（时区单一配置源）、`TZ`（同值派生）、`BATCH_LOCALE` / `LANG` / `LC_ALL`（locale 单一配置源）
- `start-all.sh` 默认只启动不打包；如需构建：先 `build-apps.sh` 或 `BUILD=1 start-all.sh`

## 常见顺序

1. `build-apps.sh`（首次或代码变更时）
2. `start-all.sh`
3. `scripts/data/init-kafka-topics.sh`、`scripts/data/init-minio.sh`（通常 Docker Compose 自动完成）
4. `health-check-infra.sh`(或 `make dev-health`)— 起 BE 前 / 联调遇怪问题先看基建
5. `run-tests.sh`（日常开发）
6. `run-tests.sh --all`（提交前）
7. `scripts/ops/inspect-all.sh`（巡检）

## 相关目录

- `[scripts/ops/](../ops/)`：巡检与自愈脚本
- `[scripts/data/](../data/)`：数据初始化与加载
- `[scripts/docker/](../docker/)`：Docker 容器操作
