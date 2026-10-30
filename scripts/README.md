# 脚本目录说明

这里收纳项目的运行、测试、巡检和自愈脚本。


## 如何找入口与新增脚本

| 需要做什么 | 目录 | 职责边界 |
|---|---|---|
| CI 检查、回归编排、门禁问题复现 | [ci](ci/README.md) | 执行脚本、[历史基线](ci/baselines/README.md)、配套 SQL 和守卫自测分别归组；[全部工作流](../.github/workflows/README.md)逐项登记 |
| 本地 JVM 构建、服务启停、验收与联调 | [local](local/README.md) | 针对当前工作树和开发环境，不承担生产发布 |
| Docker/Compose 开发环境生命周期 | [docker](docker/README.md) | 构建、启停、状态和观测栈；镜像内部资源见 [容器入口](../deploy/docker/README.md) |
| 受控交付、不可变镜像清单与回滚 | [deploy](deploy/README.md) | 发布行为与只读巡检区分，按目标环境确认执行 |
| 巡检、证据采集、运维计划与受控处置 | [ops](ops/README.md) | README 区分只读与写入；原 observability/staging 的只读入口已归入此处 |
| 数据库巡检、备份、清理与演练 | [db](db/README.md) | 操作工具与配套 SQL；正式结构演进保留在根 db/migration |
| 初始化外部依赖与测试环境数据 | [data](data/README.md) | topic、ACL、对象存储及环境数据准备，不替代 Flyway |
| 可复现生成 API/字典等资产 | [codegen](codegen/README.md) | 从权威输入生成，不作为手工配置入口 |
| 本地诊断、源码维护和实验工具 | [dev](dev/README.md) | 原 tools 的一次性源码工具归入此处；运行后复核生成差异 |
| 集群高可用部署与故障演练 | [ha](ha/README.md) | 集群变更与受控演练，不混入日常只读 ops |
| 对象存储生命周期策略 | [minio](minio/README.md) | 脚本、profile 与策略 JSON 配套维护；保留领域边界 |
| 单轮或跨业务日场景验证 | [sim](sim/README.md)、[sim-4day](sim-4day/README.md) | 场景入口与本目录配套 SQL；跨场景共享数据在根 test-data |
| 共享 Shell/Python 能力 | [lib](lib/README.md) | 供入口引用，不重复复制环境、进程与传输逻辑 |

新文件先按职责归入已有目录，再判断它是入口、共享库、测试、配置还是配套 SQL。不要因单个工具新建一级目录；也不要仅因文件少而合并有独立配置/执行边界的目录。新增入口在最近的职责 README 维护跳转和用途，子目录在上级 README 登记。

脚本头及内部说明按 [编码规约 §19.5](../docs/coding-conventions.md#195-自动化脚本的头注释与说明)；索引与用途由 `python3 scripts/ci/check-script-governance.py` 检查。基线按真实差异收紧，不能自动扩张；新增/改名 CI 工作流和共享 Action 同步各自 README。

## 目录分工

- `scripts/local/`：本地 JVM 开发——启停、构建、测试
- `scripts/docker/`：Docker / Docker Compose 容器操作（构建镜像、启停容器、观测栈管理）
- `scripts/deploy/`：不可变 release manifest 与 Compose 发布、校验和回滚
- `scripts/docker/reset-dev.sh`：按 Compose project 清空本地开发容器、数据卷和专用网络（默认预览）
- `scripts/ops/`：运维巡检与自愈（inspect-*、heal-*、trigger-compensation）
- `scripts/data/`：数据初始化与加载（init-kafka、init-minio、load-*）
- `scripts/minio/`：MinIO / S3 兼容对象存储 lifecycle 策略与下发脚本；生产 apply 必须使用专用账号和显式确认。
- `scripts/lib/minio-mc.sh`：通过独立运行的固定版本 CLI 容器访问 MinIO；默认镜像 `bitnamilegacy/minio:2025.7.23-debian-12-r1` 已验证内置 `mc`，更换镜像时需重新确认。
- `scripts/ci/`：CI / staging 统一回归入口和门禁脚本（说明见 [scripts/ci/README.md](ci/README.md)）
- 高风险操作（`rm`、数据库 DROP/TRUNCATE/DELETE、Kafka topic 删除、S3 清理、Redis 键删除、Docker 资源删除）由 [高风险操作治理](../docs/runbook/high-risk-operations.md) 统一盘点；破坏性命令必须有目标确认或本地服务边界。
- `scripts/db/`：数据库维护、种子数据、备份恢复和分区迁移演练
- `scripts/codegen/`：OpenAPI、错误码字典和契约 fixture 等可复现代码生成
- `scripts/dev/`：开发期诊断、演示和本机工具，不作为生产运维入口
- `scripts/ha/`：高可用部署与故障转移演练
- `scripts/lib/`：Shell/Python 共享函数库，只供其他脚本引用
- `scripts/sim/`、`scripts/sim-4day/`：受管环境场景模拟与四日链路验证

## 主要入口

- `python3 scripts/local/manage-logs.py`：本地日志留存与归档预览；策略在 `config/local-log-retention.json`，执行与恢复说明见[本地日志手册](../docs/runbook/local-log-layout.md)。

- `scripts/ci/run-full-regression.sh`：统一回归入口，支持默认测试、IT/E2E、压测 smoke、部署 smoke、部署升级 / 回滚验证和巡检
- `scripts/ci/run-staging-live-smoke.sh`：staging live rollout / rollback smoke 的便捷入口
- `scripts/ci/security-scan.sh`：本地 / CI 安全扫描一键入口，编排 `gitleaks` / `dependency-check` / `semgrep` / `trivy` / `ZAP`
- `scripts/ci/check-console-openapi-paths.py`：Console OpenAPI 与 `Console*Controller` 路由一致性检查（CI 与本地均可运行，详见 [scripts/ci/README.md](ci/README.md)）
- `scripts/local/run-tests.sh --e2e`：本地运行 E2E 测试（`batch-e2e-tests`）
- `scripts/local/pre-commit-checks.sh`：由项目 Git hook 调用，按暂存文件域执行轻量提交门禁。Java / Shell / Mapper XML 文件级守护采用增量扫描；跨文件关系类守护（文档引用、配置默认值、功能开关、`.env` 安全、Maven 依赖边界、Helm 环境变量、LOC 快照、仓库卫生等）按域触发全局校验，边界见 [docs/runbook/ci.md](../docs/runbook/ci.md#本地-git-hook-门禁)
- `scripts/local/health-check-infra.sh`：基建健康检查(PG primary/replica / Kafka / Redis / MinIO),协议层探测 + env-var 驱动,本机 / staging / CI 通用。`make dev-health` 是别名
- `scripts/local/pg-replica-failover-drill.sh`、`scripts/local/redis-sentinel-ha-drill.sh`：仅本机 Docker 的隔离 PG/Valkey HA 演练；使用唯一 Compose project，退出会清理专用数据卷。
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

外部或受管环境建议安装这些本机客户端，或在 CI / bastion / 运维工具箱中提供等价 CLI：

- PostgreSQL client：提供 `psql`、`pg_dump`、`pg_basebackup`。macOS 可用 `brew install libpq`，并把 `libpq/bin` 加入 `PATH`。
- Kafka CLI：提供 `kafka-topics.sh`、`kafka-consumer-groups.sh`。设置 `KAFKA_BIN_DIR=/path/to/kafka/bin`，或直接设置 `KAFKA_TOPICS_BIN=/path/to/kafka-topics.sh`。
- MinIO client：提供 `mc`，用于对象存储 seed 和检查。
- `curl`、`jq`、Python 3：巡检、HTTP 调用和轻量数据处理使用。脚本默认优先使用仓库 `.venv/bin/python`，不存在时回退到 `python3`；可通过 `PYTHON_BIN=/path/to/python3` 或 `PYTHON=/path/to/python3` 覆盖。

Python 脚本依赖由 `scripts/requirements.txt` 统一管理。本地首次使用时运行 `make python-env`。仓库的 Make 目标、Git
钩子和 Shell 入口会自动优先使用 `.venv/bin/python`，无需激活虚拟环境；仍可通过 `PYTHON_BIN` 或 `PYTHON` 显式覆盖。
不要给受 PEP 668 管理的系统 Python 使用 `--break-system-packages`。

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

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [python.sh](python.sh) | 仓库统一 Python 入口：优先使用 .venv，并校验 Python 3 版本。 |

- [codegen 目录](codegen/README.md)：可复现契约和文档生成入口与使用说明。

- [data 目录](data/README.md)：环境数据初始化入口与使用说明。

- [db 目录](db/README.md)：数据库维护与验证入口与使用说明。

- [deploy 目录](deploy/README.md)：受控发布与回滚入口与使用说明。

- [dev 目录](dev/README.md)：开发诊断与维护入口与使用说明。

- [docker 目录](docker/README.md)：Docker/Compose 开发环境入口与使用说明。

- [ha 目录](ha/README.md)：集群高可用部署与演练入口与使用说明。

- [lib 目录](lib/README.md)：共享脚本函数入口与使用说明。

- [local 目录](local/README.md)：本地构建、服务生命周期与验收入口与使用说明。

- [minio 目录](minio/README.md)：对象存储策略下发入口与使用说明。

- [ops 目录](ops/README.md)：运维巡检与受控处置入口与使用说明。

- [sim 目录](sim/README.md)：多租户 SIM 场景入口与使用说明。

- [sim-4day 目录](sim-4day/README.md)：跨业务日 SIM 场景入口与使用说明。
