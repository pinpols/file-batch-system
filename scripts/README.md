# 脚本目录说明

这里收纳项目的运行、测试、巡检和自愈脚本。

## 目录分工

- `scripts/ci/`：CI / staging 统一回归入口和门禁脚本（说明见 [scripts/ci/README.md](ci/README.md)）
- `scripts/local/`：本地开发、联调、巡检、自愈脚本

## 主要入口

- `scripts/ci/run-full-regression.sh`：统一回归入口，支持默认测试、IT/E2E、压测 smoke、部署 smoke、部署升级 / 回滚验证和巡检
- `scripts/ci/run-staging-live-smoke.sh`：staging live rollout / rollback smoke 的便捷入口
- `scripts/ci/security-scan.sh`：本地 / CI 安全扫描一键入口，编排 `gitleaks` / `dependency-check` / `semgrep` / `trivy` / `ZAP`
- `scripts/ci/check-console-openapi-paths.py`：Console OpenAPI 与 `Console*Controller` 路由一致性检查（CI 与本地均可运行，详见 [scripts/ci/README.md](ci/README.md)）
- `scripts/local/run-e2e-tests.sh`：本地运行 `batch-e2e-tests`
- `scripts/local/inspect-all.sh`：本地巡检总入口

## 使用建议

- 先看每个脚本文件头部的注释，通常会说明前置条件、环境变量和示例命令
- `scripts/ci/run-full-regression.sh` 自带 `usage()`，直接执行 `bash scripts/ci/run-full-regression.sh --help` 可以查看参数
- `scripts/local/` 下的脚本更偏操作型，适合在本地或 staging 前做验证

## 相关文档

- [docs/testing/README.md](../docs/testing/README.md)
- [docs/testing/release-gate.md](../docs/testing/release-gate.md)
- [docs/testing/staging-live-deploy-smoke-checklist.md](../docs/testing/staging-live-deploy-smoke-checklist.md)
