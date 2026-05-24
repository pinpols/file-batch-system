# CI 脚本说明

本目录存放 GitHub Actions 与本地均可复用的 CI 门禁脚本。

## `run-full-regression.sh`

统一 Maven 回归入口：默认测试、`*IT` / E2E、可选压测 smoke、部署 smoke、升级 / 回滚验证与巡检。参数与行为以脚本内 `usage()` 为准。

```bash
bash scripts/ci/run-full-regression.sh --help
```

## `run-staging-live-smoke.sh`

staging live rollout / rollback smoke 的薄封装：默认开启 live deploy smoke 和 deployment verification，直接复用 `run-full-regression.sh`。

```bash
bash scripts/ci/run-staging-live-smoke.sh
```

## `collect-flaky.sh` / `collect-flaky.py`

汇总 surefire / failsafe 报告里 `<flakyFailure>` / `<flakyError>`(`rerunFailingTestsCount=2` 自动重跑产生的 flaky-but-pass 用例),输出人读 summary 和(GH Actions 下)`$GITHUB_STEP_SUMMARY` Markdown 表。已在 `run-full-regression.sh` 末尾自动调用,**恒以 0 退出**,不阻断 CI。治理流程见 [`docs/runbook/ci.md`](../../docs/runbook/ci.md#flaky-治理)。

```bash
bash scripts/ci/collect-flaky.sh
bash scripts/ci/collect-flaky.sh -- --json build/flaky.json --warn-threshold 3
```

## `security-scan.sh`

本地 / CI 安全扫描一键入口：先打包 `security-scan` 独立 Java 模块，再按参数执行 secret、依赖、SAST、文件系统、镜像和 ZAP 扫描。默认执行全量扫描，可通过 `--mode` 收窄范围。

```bash
bash scripts/ci/security-scan.sh --help
```

## `check-console-openapi-paths.py`

校验 `docs/api/console-api.openapi.yaml` 中 `/api/console` 下的 **GET/POST** 路径是否与 `batch-console-api` 里 `Console*Controller` 的 `@RequestMapping` + `@GetMapping` / `@PostMapping` 一致，避免文档与实现漂移。

**依赖**：Python 3、PyYAML。

```bash
python3 -m pip install pyyaml
python3 scripts/ci/check-console-openapi-paths.py
```

成功时打印路由数量并以退出码 `0` 结束；不一致时打印「仅 OpenAPI」与「仅代码」的差异并以 `1` 结束。

**接入的 workflow**（均在 checkout 之后、Java 构建之前执行）：`.github/workflows/pr-gate.yml`、`.github/workflows/full-ci-gate.yml`、`.github/workflows/staging-gate.yml`。

## `check-dependency-boundaries.py`

校验依赖边界约束：

- `batch-common` 不得新增对象存储、OTEL exporter、AI SDK、Excel 处理等运行时重依赖
- 全业务模块不得引入 `spring-boot-starter-data-jdbc`（持久层统一 MyBatis；见 ADR-001）
- `batch-console-api` 与 `batch-orchestrator` 须在运行时 POM 中同时出现 `spring-boot-starter-jdbc` 与 `mybatis-spring-boot-starter`（脚本会校验）

```bash
python3 scripts/ci/check-dependency-boundaries.py
```

成功时打印 `OK: dependency boundaries satisfied.` 并以退出码 `0` 结束；违反约束时打印错误并以 `1` 结束。
