# 脚本目录说明

这里收纳项目的运行、测试、巡检和自愈脚本。

## 目录分工

- `scripts/local/`：本地 JVM 开发——启停、构建、测试
- `scripts/docker/`：Docker / Docker Compose 容器操作（构建镜像、启停容器、观测栈管理）
- `scripts/ops/`：运维巡检与自愈（inspect-*、heal-*、trigger-compensation）
- `scripts/data/`：数据初始化与加载（init-kafka、init-minio、load-*）
- `scripts/ci/`：CI / staging 统一回归入口和门禁脚本（说明见 [scripts/ci/README.md](ci/README.md)）
- `scripts/db/`：数据库维护（Flyway 历史重置等）

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

## 相关文档

- [docs/testing/README.md](../docs/testing/README.md)
- [docs/testing/release-gate.md](../docs/testing/release-gate.md)
- [docs/testing/staging-live-deploy-smoke-checklist.md](../docs/testing/staging-live-deploy-smoke-checklist.md)
