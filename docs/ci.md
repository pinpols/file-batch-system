# CI 体系说明

## 概览

项目有三条 CI 流水线，覆盖从 PR 到生产就绪的全流程：

| 流水线 | 触发时机 | 目标 | 超时 |
|---|---|---|---|
| `pr-gate` | PR → main（非草稿） | 快速反馈，阻断不合格 PR | 45 min |
| `full-ci-gate` | push main | 主干质量基线 | 75 min |
| `staging-gate` | push main | 生产就绪验证（含真实环境） | 90 min |

`full-ci-gate` 与 `staging-gate` 在同一次 push 后**并行**触发，互不依赖。

---

## 检查项汇总

### 阻断项（任意失败 → 流水线失败）

| 检查项 | 工具 / 脚本 | 触发流水线 |
|---|---|---|
| OpenAPI 路径对齐 | `check-console-openapi-paths.py` | 全部（setup-build-env） |
| 模块依赖边界 | `check-dependency-boundaries.py` | 全部（run-full-regression） |
| 编译 + 单元测试 | Maven `test` | 全部 |
| 集成测试 (`*IntegrationTest`) | Maven `test` | full-ci-gate、staging-gate |
| E2E 套件 (`*E2eIT`) | Maven `test` `-pl batch-e2e-tests` | full-ci-gate、staging-gate |
| Helm lint + template 渲染 | Helm CLI | staging-gate |
| 部署 smoke（升级 / 回滚） | `run-full-regression.sh --with-deploy-smoke` | staging-gate |
| 负载 smoke（Gatling） | `run-full-regression.sh --with-load-smoke` | staging-gate |

### 提醒项（失败只通知，不阻断流水线）

| 检查项 | 工具 | 触发流水线 | 说明 |
|---|---|---|---|
| PMD 代码规约 | `maven-pmd-plugin` | 全部（run-full-regression） | 对齐 CLAUDE.md 规约 |
| Spotless 代码格式 | `spotless-maven-plugin` | 全部（run-full-regression） | Google Java Format |
| 覆盖率门禁 | JaCoCo `jacoco:check` | 全部（run-full-regression） | 行覆盖率 ≥ 60%，初始阈值，后续提升 |
| Secret 扫描 | `security-scan.sh --mode=secret` | pr-gate、full-ci-gate | 扫描密钥泄漏 |
| 依赖漏洞扫描 | `security-scan.sh --mode=deps` | full-ci-gate | 已知 CVE |
| Dockerfile lint | Hadolint | full-ci-gate | `docker/Dockerfile.app` |
| 文件系统安全扫描 | Trivy `fs` | full-ci-gate | CRITICAL/HIGH 漏洞 + IaC 配置 |
| DAST 动态扫描 | `security-scan.sh --mode=dast` | staging-gate | 目标：staging console-api |
| K8s manifest 安全 | Checkov | staging-gate | Helm chart 安全基线 |

> **提醒项升阻断策略**：移除对应步骤的 `continue-on-error: true`（workflow）或脚本中的 `|| true`（run-full-regression.sh），
> 阈值稳定后逐步收紧，不建议一次全部升级。

---

## pr-gate 增量扫描

pr-gate 会根据 PR 变更文件范围决定 Maven 构建粒度：

| 变更范围 | Maven 行为 |
|---|---|
| 影响全局（`pom.xml`、`.github/`、`scripts/ci/`、`helm/`、`batch-common/` 等） | 全量 reactor |
| 仅单个模块（如 `batch-console-api/`） | 仅构建受影响模块及其依赖（`-pl ... -am -amd`） |
| 仅 `load-tests/` | 只编译 load-tests，跳过 reactor |
| 无 Java 相关变更 | 跳过 Maven gate |

所有路径下均跳过集成测试套件（`--skip-it-suite`），保证 PR 反馈在 45 分钟内完成。

---

## 本地运行

所有常用操作通过根目录 `Makefile` 统一入口，`make help` 查看全部 target。

### 本地环境

```bash
make dev-build          # 构建所有模块 jar
make dev-start          # 启动基础设施 + 全部 Java 进程
make dev-stop           # 停止所有 Java 进程
make dev-restart        # dev-stop → dev-build → dev-start
```

### 测试

```bash
make test               # 单元 + 集成（默认）
make test-unit          # 仅单元，秒级无容器
make test-it            # 仅集成，需 Docker
make test-e2e           # E2E 套件
```

### CI 回归

```bash
make ci                             # 全量（等同 full-ci-gate）
make ci-pr                          # 跳过集成测试（PR 风格）
make ci-module M=batch-console-api  # 指定模块
```

### 静态检查

```bash
make check-openapi      # OpenAPI 路径校验
make check-deps-boundary# 模块依赖边界
make pmd                # PMD 规约
make spotless           # 格式检查
make spotless-fix       # 一键修复格式（提交前）
make coverage           # 覆盖率门禁
```

### 安全扫描

```bash
make scan-secret        # Secret 扫描
make scan-deps          # 依赖漏洞
make scan-dast          # DAST（需本地服务已启动）
```

### 测试数据 / 数据库

```bash
make data-demo          # 导入演示数据
make data-demo-reset    # 清空并重新导入
make data-system        # 系统测试数据
make data-kafka         # 初始化 Kafka topics
make data-minio         # 初始化 MinIO buckets
make db-reset-flyway    # 清空 Flyway 历史
```

### 运维

```bash
make ops-inspect        # 全量巡检
make ops-heal-stuck     # 修复卡死 outbox
make ops-heal-dead      # 修复死信队列
make ops-heal-drain     # 修复 drain 超时
make ops-heal-retry     # 修复待重试任务
make ops-heal-partitions# 修复分区不均衡
make ops-compensate     # 触发补偿
```

---

## 依赖自动更新（Renovate）

配置文件：`.github/renovate.json`

| 类型 | 策略 |
|---|---|
| Maven patch 版本 | 自动合并 |
| Maven minor / major 版本 | 开 PR，人工审核 |
| GitHub Actions | 自动合并 |
| Spring Boot 父 pom | 单独 PR，指派 `idengzhao` 审核 |

更新窗口：每周一 09:00 前。

---

## 产物归档

| 产物 | 来源流水线 | 保留天数 |
|---|---|---|
| Surefire 测试报告 | pr-gate、full-ci-gate、staging-gate | 14 / 14 / 30 天 |
| Gatling 压测报告 | staging-gate | 30 天 |

---

## 关键文件索引

```
.github/
  workflows/
    pr-gate.yml              # PR 门禁
    full-ci-gate.yml         # 主干质量门禁
    staging-gate.yml         # 生产就绪门禁
  actions/
    setup-build-env/         # 共享 setup：JDK、Maven cache、OpenAPI 校验
  renovate.json              # 依赖自动更新配置

scripts/ci/
  run-full-regression.sh     # 所有 Maven 回归的统一入口
  check-console-openapi-paths.py   # OpenAPI 路径对齐校验
  check-dependency-boundaries.py   # 模块依赖边界校验
  security-scan.sh           # 安全扫描入口（secret / deps / dast）

build/
  pmd-ruleset.xml            # PMD 规则集（对齐 CLAUDE.md）

pom.xml                      # 父 pom：JaCoCo agent、PMD、Spotless 插件配置
```
