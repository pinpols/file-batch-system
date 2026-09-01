# 脚本目录说明

这里收纳项目的运行、测试、巡检和自愈脚本。

## 目录分工

- `scripts/local/`：本地 JVM 开发——启停、构建、测试
- `scripts/docker/`：Docker / Docker Compose 容器操作（构建镜像、启停容器、观测栈管理）
- `scripts/ops/`：运维巡检与自愈（inspect-*、heal-*、trigger-compensation）
- `scripts/data/`：数据初始化与加载（init-kafka、init-minio、load-*）
- `scripts/ci/`：CI / staging 统一回归入口和门禁脚本（说明见 [scripts/ci/README.md](ci/README.md)）
- `scripts/db/`：数据库维护、种子数据、备份恢复和分区迁移演练

## 主要入口

- `scripts/ci/run-full-regression.sh`：统一回归入口，支持默认测试、IT/E2E、压测 smoke、部署 smoke、部署升级 / 回滚验证和巡检
- `scripts/ci/run-staging-live-smoke.sh`：staging live rollout / rollback smoke 的便捷入口
- `scripts/ci/security-scan.sh`：本地 / CI 安全扫描一键入口，编排 `gitleaks` / `dependency-check` / `semgrep` / `trivy` / `ZAP`
- `scripts/ci/check-console-openapi-paths.py`：Console OpenAPI 与 `Console*Controller` 路由一致性检查（CI 与本地均可运行，详见 [scripts/ci/README.md](ci/README.md)）
- `scripts/local/run-tests.sh --e2e`：本地运行 E2E 测试（`batch-e2e-tests`）
- `scripts/local/health-check-infra.sh`：基建健康检查(PG primary/replica / Kafka / Redis / MinIO),协议层探测 + env-var 驱动,本机 / staging / CI 通用。`make dev-health` 是别名
- `scripts/local/import-copy-worth-benchmark.sh`：IMPORT LOAD 写入微基准,判断 PG COPY 是否值得进入代码改造
- `scripts/ops/inspect-all.sh`：本地巡检总入口

## 使用建议

- 先看每个脚本文件头部的注释，通常会说明前置条件、环境变量和示例命令
- `scripts/ci/run-full-regression.sh` 自带 `usage()`，直接执行 `bash scripts/ci/run-full-regression.sh --help` 可以查看参数
- 容器启动/停止类入口优先看 `scripts/docker/`
- `scripts/ops/` 下的巡检和自愈脚本适合在本地或 staging 前做验证
- `scripts/data/` 下的数据加载脚本适合初始化开发/测试环境

## 执行环境约定

脚本分三类，不要混用：

- **Docker-only**：`scripts/docker/`、`scripts/sim/`、`scripts/sim-4day/` 和少量本地灾备演练脚本，默认依赖 Docker Compose 的容器名，例如 `batch-postgres-primary`、`batch-kafka`、`batch-minio`。这些脚本用于本地受管验证栈，不承诺直接连外部环境。
- **Host/auto**：`scripts/ops/`、`scripts/data/`、`load-tests/scripts/` 中的通用巡检、初始化、压测脚本优先通过环境变量连接外部服务；能本机执行就不要求进容器。
- **CI 专用**：`scripts/ci/` 内脚本由 GitHub Actions 组合调用，单独执行前先看文件头部说明。

非容器环境建议安装这些本机客户端：

- PostgreSQL client：提供 `psql`、`pg_dump`、`pg_basebackup`。macOS 可用 `brew install libpq`，并把 `libpq/bin` 加入 `PATH`。
- Kafka CLI：提供 `kafka-topics.sh`、`kafka-consumer-groups.sh`。设置 `KAFKA_BIN_DIR=/path/to/kafka/bin`，或直接设置 `KAFKA_TOPICS_BIN=/path/to/kafka-topics.sh`。
- MinIO client：提供 `mc`，用于对象存储 seed 和检查。
- `curl`、`jq`、Python 3：巡检、HTTP 调用和轻量数据处理使用。脚本默认优先找 `python3`，可通过 `PYTHON_BIN=/path/to/python3` 或 `PYTHON=/path/to/python3` 覆盖。

通用环境变量：

- `BATCH_SCRIPT_RUNTIME=auto|host|docker`：支持该开关的脚本默认 `auto`。`host` 强制使用本机客户端；`docker` 强制使用 Docker 容器内客户端。
- PostgreSQL：`PGHOST`、`PGPORT`、`PGUSER`、`PGPASSWORD`、`PGDATABASE`。
- PostgreSQL 客户端：默认依次尝试宿主机 `psql`、Python `psycopg`、Docker 容器内客户端；可用 `BATCH_PSQL_BIN=/path/to/psql`、`BATCH_PG_CLIENT_MODE=python|host|docker` 指定模式。Python fallback 依赖 `scripts/requirements-postgres.txt`，宿主机和 Docker 均不可用且未安装 fallback 时明确失败，不会跳过 SQL。
- Kafka：`KAFKA_BOOTSTRAP_SERVER` 或 `KAFKA_HOST_BOOTSTRAP`，以及 `KAFKA_BIN_DIR` / `KAFKA_TOPICS_BIN`。
- 对象存储：`BATCH_S3_ENDPOINT`、`BATCH_S3_ACCESS_KEY`、`BATCH_S3_SECRET_KEY`、`BATCH_S3_BUCKET`。
- Python：`PYTHON_BIN` 或 `PYTHON`，用于指定本机 Python 3 解释器。

## 相关文档

- [docs/testing/README.md](../docs/testing/README.md)
- [docs/testing/release-gate.md](../docs/testing/release-gate.md)
- [docs/runbook/go-live-staging-execution.md](../docs/runbook/go-live-staging-execution.md)
