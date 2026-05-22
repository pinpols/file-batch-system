# CI 体系说明

## 概览

项目有 **4 条** CI 流水线,覆盖从 PR 到生产就绪 + 周度容量回归的全流程:

| 流水线 | 触发时机 | 目标 | 超时 |
|---|---|---|---|
| `pr-gate` | PR → main(opened / synchronize / reopened / ready_for_review,非草稿) | 快速反馈,阻断不合格 PR | 45 min |
| `full-ci-gate` | push main(合并 PR 或直推) | 主干质量基线 | 75 min |
| `staging-gate` | full-ci-gate 跑完 success 后**串行链式**触发 / 手动 dispatch | 生产就绪验证(含真实环境) | 90 min |
| `capacity-gate` | 每周一 03:00 cron / 手动 dispatch | 容量基线回归 | — |

## 触发矩阵(开发者视角)

| 场景 | pr-gate | full-ci-gate | staging-gate | capacity-gate |
|---|:---:|:---:|:---:|:---:|
| feature 分支自身 push | — | — | — | — |
| **PR 到 main** | ✅ | — | — | — |
| **PR 合并 → main 收到 push** | — | ✅ | ✅(等 full 成功) | — |
| 直推 main(绕 PR) | — | ✅ | ✅(等 full 成功) | — |
| 每周一 03:00 自动 | — | — | — | ✅ |
| 手动 `workflow_dispatch` | 可手动 | 可手动 | 可手动 | 可手动 |

## 关键设计

- **feature 分支自己 push 不跑任何 gate** — 开发可频繁推送无成本,门禁压力全在 PR 时
- **`staging-gate` 用 `workflow_run` 等 `full-ci-gate` 完成**(不是并行),`if: workflow_run.conclusion == 'success'` 守护 — full 失败 staging 不会跑,避免浪费 90 min
- **直推 main 跳过 pr-gate**(无审查),但 `full-ci-gate + staging-gate` 仍兜底回归
- **`concurrency.group + cancel-in-progress`** 4 个 workflow 全配 — 同分支并发 push / 同 PR 多次推时,旧 run 自动取消省 runner
- **`capacity-gate` 例外**:`cancel-in-progress: false`(容量基线跑到一半被打断会污染数据,等跑完才让下一轮启)
- **pr-gate 与 full-ci-gate 检查项不完全相同**:见下表(pr-gate 重快速反馈,full-ci-gate 重深度回归 + 安全扫描)

## pr-gate 增量 vs full-ci-gate 全量(关键区别)

| 维度 | pr-gate(增量) | full-ci-gate(全量) |
|---|---|---|
| **范围探测** | ✅ 有 — `Detect affected scope` step 按 changed files 决定 | ❌ 永远 full reactor |
| **3 态决策** | `skip` / `partial` / `full` 三档 | 永远 `full` |
| **Maven 范围** | partial 时 `-pl <module> -am -amd` 只跑受影响模块 | 全 10 模块跑 |
| **E2E suite** | partial 时跳过 batch-e2e-tests | 拆 `e2e-shard` 独立 job 25 min 并发跑 |
| **Hadolint / Trivy fs** | ❌ 不跑 | ✅ 跑 |

### pr-gate 自动 escalate 到 full 的"敏感路径"

只要 changed files 命中以下任一,pr-gate 立即升级为 full reactor(不再 partial):

```
pom.xml                    # 根 pom 变 → 全模块依赖可能变
.mvn/*                     # Maven wrapper / 配置
.github/workflows/*        # workflow 自身变
scripts/ci/*               # CI 脚本变
scripts/local/*            # 本地脚本影响 dev 环境一致性
helm/*                     # 部署 chart
docker-compose.yml         # 容器编排
batch-common/*             # 跨模块基础库,改了全部模块都受影响
```

其余 `batch-<module>/*` 命中只升级到该模块 + -am -amd 上下游。

## 非代码提交触发吗?

| 提交类型 | pr-gate | full-ci-gate |
|---|---|---|
| 纯 `docs/**.md` | ⚠️ workflow 触发但 Detect 判 `skip`,maven 不跑(几秒结束) | ✅ 全跑(无 paths-ignore) |
| 纯 `.github/workflows/*.yml` | ✅ workflow 触发 + 升级 full(workflow 自身改要全测) | ✅ 全跑 |
| 纯 `helm/*` | ✅ workflow 触发 + 升级 full | ✅ 全跑 |
| 纯 `scripts/local/*` | ✅ workflow 触发 + 升级 full | ✅ 全跑 |
| 纯 `db/migration/*.sql` | ⚠️ workflow 触发但 Detect 不在 case 列表 → `skip`(**潜在漏洞** — DB 改动建议手动触发 full) | ✅ 全跑 |
| 纯 `docs/api/console-api.openapi.yaml` | ⚠️ 同上 `skip`(但 OpenAPI 漂移在 setup-build-env 已有 `check-console-openapi-paths.py` 守护) | ✅ 全跑 |

**结论**:full-ci-gate 没配 `paths-ignore` — main 任何 push 都触发,**含纯文档 / 配置**。pr-gate 用 scope 探测省 runner,但 db/migration / OpenAPI 这类不在 case 列表里的"会影响运行时但 PR 不会自动升 full"的路径有盲区,改这类时建议手动 `workflow_dispatch` 触发 full-ci-gate 兜底。

## capacity-gate(单列)

走 staging 真实环境用 Gatling 跑 `CapacityBaselineSimulation`(25→200 users stepped-ramp),内置 SLO 断言:

| SLO | 阈值 |
|---|---|
| write p95 | < 500ms |
| read p99 | < 300ms |
| 错误率 | < 1% |

失败 = 容量退化,排查并**阻断生产发布**。手动 dispatch 可覆盖参数 `job_code` / `tenant_id`。

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

> **mvnd 注意事项**
>
> 本项目本地测试脚本使用 mvnd（Maven Daemon v1.0.5）。mvnd 存在一个已知的工作区读取器缺陷：
> `test` 阶段无法正确从 reactor 解析跨模块依赖，会回退到 `~/.m2` 的旧版 JAR，
> 导致编译失败或测试运行时出现 `NoClassDefFoundError`。
>
> `run-tests.sh` 所有模式在执行测试前均会先运行 `clean install -DskipTests`（通过
> `maybe_build()` 函数），将最新 JAR 写入 `~/.m2` 来规避此问题。
> 使用 `--skip-build` 标志可跳过此步骤（仅用于 `make test-parallel` 并行场景，
> 此时由 `--build-only` 统一完成一次构建）。
>
> 若切换至标准 `mvn`，可移除该预装步骤。

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
make test-all           # 单元 + 集成 + E2E（串行）
make test-build         # 仅构建，不跑测试
make test-parallel      # 三类测试并行执行（构建一次，unit/it/e2e 并发）
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

## PR 自动归并（auto-merge）

两条独立路径，都基于 GitHub 原生 `gh pr merge --auto`（等所有 required check 绿了自动 squash merge），**不跳 check、不无 review merge**。

| 路径 | 触发 | 是否自动 approve | 是否自动 enable auto-merge |
|---|---|---|---|
| `dependabot-auto-merge.yml` | Dependabot 开的 PR | patch bump / 安全告警 → ✅ | patch bump → ✅ |
| `label-automerge.yml` | 任意人开 PR + 打 `automerge` 标签 | ❌（必须有人 review approve） | ✅ |

**用法（label 路径）**：
1. 开 PR
2. 自己 review 一遍觉得 OK
3. 给 PR 贴 `automerge` 标签
4. Reviewer approve 后，所有 required check 一变绿 GH 自动 squash merge

**撤销**：移除 `automerge` label + 在 PR 页面点 "Disable auto-merge"，或命令 `gh pr merge --disable-auto <PR_URL>`。

**前置条件**（一次性，repo Settings）：
- General → "Allow auto-merge" 必须勾上
- Branch protection 必须设了 required status checks（否则 `--auto` 会立即 merge 失去保护）

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
