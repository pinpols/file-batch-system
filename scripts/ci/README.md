# CI 脚本说明

本目录存放 GitHub Actions 与本地均可复用的 CI 门禁脚本。

## `run-full-regression.sh`

统一 Maven 回归入口：默认测试、`*IT` / E2E、可选压测 smoke、部署 smoke、升级 / 回滚验证与巡检。参数与行为以脚本内 `usage()` 为准。

```bash
bash scripts/ci/run-full-regression.sh --help
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
