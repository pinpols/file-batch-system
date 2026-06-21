# CI 体系说明

## 概览

项目有 **2 条** CI 门禁流水线:

| 流水线 | 触发时机 | 目标 | 超时 |
|---|---|---|---|
| `pr-gate` | PR → main(opened / synchronize / reopened / ready_for_review,非草稿) | 快速反馈,阻断不合格 PR | 45 min |
| `full-ci-gate` | push main(合并 PR 或直推) | 主干质量基线 + 安全扫描(含 K8s manifest Checkov) | 75 min |

> **2026-05-23 删除 `staging-gate` / `capacity-gate` / `promote-staging`**:前两条目标是 `*.svc.cluster.local`(k8s 集群内 DNS),GitHub-hosted runner 永远连不上 → 100% Connection refused;后一条要写 `pinpols/file-batch-system-ops` 但仓 / PAT 都没在用,等同 dead code。Checkov K8s manifest 静态扫已迁到 `full-ci-gate`。若未来要恢复真·生产环境验证 / 容量回归 / ops 仓同步,改用 self-hosted runner 部署到集群内,或 staging 暴露公网 ingress + 配 PAT。

## 触发矩阵(开发者视角)

| 场景 | pr-gate | full-ci-gate |
|---|:---:|:---:|
| feature 分支自身 push | — | — |
| **PR 到 main** | ✅ | — |
| **PR 合并 → main 收到 push** | — | ✅ |
| 直推 main(绕 PR) | — | ✅ |
| 手动 `workflow_dispatch` | 可手动 | 可手动 |

## 关键设计

- **feature 分支自己 push 不跑任何 gate** — 开发可频繁推送无成本,门禁压力全在 PR 时
- **直推 main 跳过 pr-gate**(无审查),但 `full-ci-gate` 仍兜底回归
- **`concurrency.group + cancel-in-progress`** 全配 — 同分支并发 push / 同 PR 多次推时,旧 run 自动取消省 runner
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

---

## 检查项汇总

### 阻断项（任意失败 → 流水线失败）

| 检查项 | 工具 / 脚本 | 触发流水线 |
|---|---|---|
| OpenAPI 路径对齐 | `check-console-openapi-paths.py` | 全部（setup-build-env） |
| 模块依赖边界 | `check-dependency-boundaries.py` | 全部（run-full-regression） |
| 编译 + 单元测试 | Maven `test` | 全部 |
| 集成测试 (`*IntegrationTest`) | Maven `test` | full-ci-gate |
| E2E 套件 (`*E2eIT`) | Maven `test` `-pl batch-e2e-tests` | full-ci-gate |

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
| K8s manifest 安全 | Checkov | full-ci-gate | Helm chart 安全基线 |

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
make ops-heal-stuck     # 修复长期停滞 outbox
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

## flaky 治理

surefire / failsafe 配置 `rerunFailingTestsCount=2`(pom.xml ~224 行):首次 fail 后再跑 2 次,任一过即标 **flaky-but-pass**,不污染主分支绿。问题是:这些飘的用例若没人盯,会在主干上越堆越多,直到某次同时失败 3 次彻底翻红。

### 监控脚本

`scripts/ci/collect-flaky.sh`(底层 `collect-flaky.py`,纯 Python 3 标准库,无外部依赖)。扫所有模块 `target/{surefire,failsafe}-reports/TEST-*.xml`,提 `<flakyFailure>` / `<flakyError>` 节点。

- **接入位置**:`run-full-regression.sh` 末尾,跑完测试后自动调用 —— 因此 `pr-gate` / `full-ci-gate` / `make ci*` 全链路都会跑。脚本恒 `exit 0`,**永不阻断已绿 build**(flaky 本就允许 pass)。
- **输出**:
  - stdout:人读 summary(模块 / 类#方法 / 重试次数 / 首条错误摘要)
  - GH Actions:自动写 `$GITHUB_STEP_SUMMARY` Markdown 表,直接在 run 页面看
  - 可选 `--json <path>`:机读 JSON,留给后续趋势分析 / 告警
  - 可选 `--warn-threshold N`(默认 5):超阈值在 stderr 打 WARN(仍不阻断)

```bash
# 本地手动跑(需先有 target/*-reports/)
bash scripts/ci/collect-flaky.sh
bash scripts/ci/collect-flaky.sh -- --json build/flaky.json --warn-threshold 3
```

### 治理流程(运维定期巡检)

1. **每周一巡**:翻最近一周 `full-ci-gate` 的 step summary(或下载 surefire-reports artifact 跑 `collect-flaky.sh`),记录 flaky 用例 Top N。
2. **建治理 issue**:同一用例连续 ≥ 2 周出现 → 开 issue 派给原作者 / 模块 owner,标 `flaky-test` label。
3. **修不动就隔离**:确认无法稳定的,改成 `@Disabled("flaky — see #<issue>")` 暂时下线,避免长期遮蔽真问题。**禁**直接删测试 —— 必须先有 issue 跟踪原因。
4. **结构性原因**:flaky 集中在某模块(如 testcontainers Kafka / Redis 等待时序),走 `AbstractIntegrationTest` 调容器超时 / Awaitility 等待,而不是每个测试自己固定 sleep。

### 为什么不阻断 build

CI gate 阻断要满足「确定性 fail」前提;flaky 用例第一次 fail 是噪声,阻断就把噪声升级成主干 red,反而让开发者忽略后续真问题。阻断由人工治理 issue 兜底,脚本只负责**让 flaky 可见**。

---

## 产物归档

| 产物 | 来源流水线 | 保留天数 |
|---|---|---|
| Surefire 测试报告 | pr-gate、full-ci-gate | 14 天 |

---

## 耗时基线(2026-05-23 snapshot)

最近一次成功跑的总耗时与 job 分布。指标用于回归告警:任一 wf 超基线 +50% 需排查。

| Workflow | 总耗时 | 触发 | 目标 | 状态 |
|---|---|---|---|---|
| pr-gate | 4:21 | PR / push | ≤6m | ✅ |
| codeql | 4:21 | PR / push / 周 | ≤6m | ✅ |
| workflow-lint | 0:18 | 改 `.github/workflows/**` | ≤1m | ✅ |
| full-ci-gate | 6:19 | push main / nightly / 手动 | ≤10m | ✅(已贴目标) |
| build-image | 2:53 | push main / tag | ≤6m | ✅(-73% vs 旧 10:54) |

### Job 级分布

**pr-gate(5 job 并行,瓶颈 unit-it-b2)**
- static-checks 1:57 / security 1:16 / unit-it-a 2:49 / unit-it-b1 3:02 / **unit-it-b2 4:14** ← critical path

**full-ci-gate(9 job 并行,瓶颈 security-scan)**
- static-checks 1:36 / unit-it-a 3:04 / unit-it-b1 3:13 / unit-it-b2 4:06 / e2e-shard 1-4 各 4:23-4:58 / **security-scan 6:15** ← critical path

**build-image(7 模块并行,瓶颈 orchestrator)**
- batch-worker-{import,dispatch,process,export,console-api,trigger} 1:49-2:26 / **batch-orchestrator 2:47** ← critical path

> 2026-05-23:PR #27 合并后,本仓不再有 staging-gate / capacity-gate / promote-staging — 之前的 skip 三件套已删,流水线进一步精简。

---

## 关键文件索引

```
.github/
  workflows/
    pr-gate.yml              # PR 门禁
    full-ci-gate.yml         # 主干质量门禁(含安全扫 + Checkov)
    build-image.yml          # 镜像构建
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
