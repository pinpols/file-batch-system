# CI 体系说明

## 概览

项目有两条主要代码门禁流程（PR Gate、main Full CI Gate），另有补充验证与失败处理自动化。补充流程不替代代码合并门禁：

| 工作流 | 分类 | 触发时机 | 目标 | 超时 |
|---|---|---|---|---|
| `pr-gate` | PR 代码门禁 | PR → main(opened / synchronize / reopened / ready_for_review,非草稿) | 快速反馈，阻断不合格 PR | 45 min |
| `sdk-contract-parity` | SDK 契约门禁 | PR、merge queue、每日 16:00 UTC、手动 | 五语言 fixture、共享常量和 conformance 契约 | — |
| `full-ci-gate` | main 全量门禁 | push main、每周日 02:00 UTC、手动 | 主干质量基线 + 安全扫描(含 K8s manifest Checkov) | 75 min |
| `staging-gate` | 补充 E2E 验证 | nightly(每天 18:00 UTC / 北京 02:00)+ workflow_dispatch | 全量 E2E(smoke + critical + regression 全跑,4 shard 并发)，不替代 `full-ci-gate` | — |
| `daily-sim-strict-validation` | 补充真实数据验证 | nightly(每天 13:31 UTC / 北京 21:31)+ workflow_dispatch | 定时触发按最近一次计划时间对应的北京时间日期检查代码/配置变更，延迟跨午夜仍归属原计划日；手动触发按当前北京时间日期。Markdown/RST、`LICENSE`、`NOTICE` 除外。需要验证时同环境先执行 `sim-harness all`，再执行 BE-ACC step 5(strict real-data verification)；strict step 使用 `always()` 采证，不因 sim 失败被短路 | 240 min |
| `docker-image-build` | nightly 镜像构建 | 由 `daily-sim-strict-validation` 在当天有代码/配置变更且 sim + strict 成功后调用；也支持手动和复用调用 | Docker Bake 构建全部应用镜像和运维工具箱镜像；CI 使用 Maven Central 配置并带依赖下载重试；不推送镜像 | 30 min |
| `main-failure-triage` | 失败处理自动化 | main 的 `full-ci-gate` 核心 job 失败 | 自动标记关联 PR 并评论处理要求；无关联 PR 时创建 issue | — |

> **2026-05-23 删除 `capacity-gate` / `promote-staging`**:`capacity-gate` 目标是 `*.svc.cluster.local`(k8s 集群内 DNS),GitHub-hosted runner 永远连不上 → 100% Connection refused;`promote-staging` 要写 `pinpols/file-batch-system-ops` 但仓 / PAT 都没在用,等同 dead code。Checkov K8s manifest 静态扫已迁到 `full-ci-gate`。若未来要恢复真·生产环境验证 / 容量回归 / ops 仓同步,改用 self-hosted runner 部署到集群内,或 staging 暴露公网 ingress + 配 PAT。
>
> `staging-gate` **仍存在**:作为 nightly schedule / staging 分支的全量 E2E 回退闸门(见上表与 `e2e-tier-strategy.md`)。

## CI 依赖与安全扫描版本基线

2026-09-30 已完成第一批 CI 依赖治理升级。当前工作流统一使用：

| 类别 | 当前版本 | 说明 |
|---|---|---|
| `actions/checkout` | v7 | 所有 workflow/composite action 统一 |
| `actions/setup-python` | v7 | Python 3.x 版本由 workflow 输入决定 |
| `actions/setup-java` | v6 | JDK/Maven cache 和发布凭据需按原输入验证 |
| `actions/setup-node` | v7 | npm 发布 job 显式提供 `NODE_AUTH_TOKEN` |
| `actions/setup-go` | v7 | 保留现有 `go-version` / `go-version-file` 输入 |
| `actions/upload-artifact` | v7 | artifact 名称和下载配对保持不变 |
| `docker/setup-buildx-action` | v4 | Buildx/Bake 构建需在 CI 回归 |
| Hadolint Action | v3.5.0 | Dockerfile lint |
| SBOM Action | v0.24.2 | 固定具体 release，不再使用浮动 `v0` |
| Docker Bake Action | v7 | 与 Buildx v4 配套；真实镜像 workflow 仍需升级后运行证据 |
| Squawk / oasdiff | 2.65.0 / 1.32.1 | 迁移安全和 OpenAPI 破坏性变更守护 |
| Trivy CLI | 0.74.0 | `vuln,misconfig` 扫描参数统一 |

CI 版本基线与运行结果分开记录。每次核验 Full Gate、CodeQL 或镜像工作流时，按目标分支的实际 commit SHA 检查最新 run；`IN_PROGRESS`、`QUEUED`、`SKIPPED` 或其他 SHA 上的成功都不能作为当前提交通过证据。Docker Buildx/Bake、发布凭据、GHES 与 self-hosted runner 兼容性仍须按目标环境验证。后续每次升级按 [CI 外部 Actions 版本升级与验收记录](../backlog/ci-external-action-upgrade-backlog-2026-09-30.md) 运行对应回归。

运行环境约束：

- setup-python/setup-node/setup-go 的 Node 24 运行时要求 GitHub Actions Runner `v2.327.1` 或更高；GitHub-hosted `ubuntu-latest` 满足该要求，self-hosted runner 必须单独核对。
- `actions/upload-artifact@v7` 使用当前 artifact 服务契约；迁移到 GHES 前必须确认 GHES 支持该 major，否则保持独立兼容版本或由平台团队提供替代上传方案。
- Gitleaks `8.30.1` 本轮不盲目更换；已在 Docker `linux/amd64` 用同版 artifact 验证合成 `ghp_...` 正向退出 1、负向退出 0，并通过 PR/Full Gate 安全扫描；继续关注上游规则变化，该样例不代表所有密钥类型。
- CodeQL、Trivy Action、Checkov、发布 Action 和 Sonar 仍按 G7 定期复核，不因本批版本升级自动视为完成。
- `.github/dependabot.yml` 对 GitHub Actions 保留每周版本更新队列，上限为 5；安全更新不受该上限影响。Maven/Docker 的现有限制未在本批调整。

## 触发矩阵(开发者视角)

| 场景 | pr-gate | full-ci-gate |
|---|:---:|:---:|
| feature 分支自身 push | — | — |
| **PR 到 main** | ✅ | — |
| **PR 合并 → main 收到 push** | — | ✅ |
| 直推 main(绕 PR) | — | ✅ |
| 每周日 02:00 UTC 全量巡检 | — | ✅ |
| 手动 `workflow_dispatch` | 可手动 | 可手动 |

## 关键设计

- **feature 分支自己 push 不跑任何 gate** — 开发可频繁推送无成本,门禁压力全在 PR 时
- **直推 main 跳过 pr-gate**(无审查),但 `full-ci-gate` 仍回退回归
- **`concurrency.group + cancel-in-progress`** 全配 — 同分支并发 push / 同 PR 多次推时,旧 run 自动取消省 runner
- **pr-gate 与 full-ci-gate 检查项不完全相同**:见下表(pr-gate 重快速反馈,full-ci-gate 重深度回归 + 安全扫描)
- **main 红线独立于 PR 绿灯**:PR gate 通过只代表候选变更可合入；合入后的 main 只有最新 `full-ci-gate` 通过才可作为发布基线。
- **门禁结果行统一**:本地 hook 与 CI 统一输出 `状态 | code | gate | exit_code | action`；跳过时再输出 `reason`。单步中串行运行多个阻断检查时，每项都必须通过共享 `gate_run` 输出独立结果；具体诊断信息可保留各检查器原有内容。
- **静态门禁失败统一汇总**：PR 与 Full Gate 的 `static-checks` 会继续执行所有相互独立的业务/规范检查，在 job 末尾一次性列出失败代码、名称和退出码后阻断；checkout、构建环境安装等缺失后无法继续的基础前置仍立即失败。本地 pre-commit/pre-push 保持首错即停。
- **SBOM 快照必须同步**：POM 或 CI 门禁变更时，PR Gate 重生成 CycloneDX SBOM 并与 `docs/compliance/sbom.json` 比较；Full Gate 的许可证检查再次复核。动态 artifact 生成成功不等于入库快照已同步。
- **核心术语枚举必须同步**：修改实例、工作流、节点、分片、步骤、任务状态，或调度类型、触发来源、节点类型、运行模式 enum 时，运行 `python3 scripts/ci/check-terminology-doc-sync.py --write`；PR / Full Gate 的只读检查会阻断旧值表。
- **确定性派生产物由 hook 维护**：POM 已暂存且没有同文件未暂存改动时，pre-commit 自动重建并暂存 SBOM，同时执行许可证门禁；`@ConfigurationProperties` 增删时自动重建文档与运行时两份配置治理目录；代码量快照沿用 staged-tree 自动同步。CI 始终只读验证，不用机器人账号回写 PR。
- **语义与安全例外保持人工审批**：Changelog、功能开关说明、环境变量 owner、Java 抑制项、Trivy 忽略项、SQL 例外基线和许可证风险说明不可由门禁自动放宽。CI 应给出修复命令或登记位置，但不替开发者作风险决定。

## 开源多人协作策略

多人并行提交时，单个 PR 绿并不能证明“合并后主干仍绿”。本项目按以下规则处理：

1. **main 受保护**：禁止直接 push；所有变更通过 PR、required checks 和 review。管理员 bypass 只用于仓库治理紧急场景，不能作为常规合并方式。
2. **启用 GitHub merge queue**：仓库 Settings → Branches / Rulesets 中对 `main` 开启 merge queue，让候选 PR 在“临时合并结果”上跑 required checks，减少多个 PR 分别绿色但合到一起红的情况。merge queue 是仓库设置，不能完全由代码文件强制；Ruleset 同时要求 scope、PR、SDK 契约检查。
3. **PR gate 是合入门禁，full-ci-gate 是发布门禁**：开源贡献者不要求本地安装完整 hook；关键规则必须在 PR / full CI 中兜底。本地 hook 只减少返工，不承担最终可信边界。
4. **main full-gate 红即冻结发布**：不以任何单个 PR gate 通过作为上线依据。直到 main 最新 `full-ci-gate` 重新通过，release / deploy 均应暂停。
5. **失败自动归责**：`full-ci-gate` 内置的 `main-failure-triage` job 会在 main 的核心 job 失败后，根据失败 run 的 `head_sha` 找关联 PR，贴 `main-broken` / `needs-fix` 并评论处理要求；找不到 PR 时创建 issue。该 job 不使用 `workflow_run`，避免高权限跨 workflow 触发风险。
6. **修复优先级**：小且确定的问题走 follow-up PR；原因不清、影响上线窗口或需要长时间排查时，维护者优先 revert 导致 main 变红的 PR，再让作者重新提交修复版。
7. **连续合并难定位时按顺序二分**：用 main 的 merge 顺序和 `full-ci-gate` 首次失败的 `head_sha` 定位第一个坏提交；不要在红 main 上连续堆多个修复尝试。

维护者处理 checklist：

```bash
gh run view <failed-full-ci-run-id> --log-failed
gh pr list --search "<head-sha>" --state all
gh pr comment <pr-number> --body "main full-ci-gate failed: <run-url>"
git fetch origin main
git switch -c revert/main-broken-<short-sha> origin/main
git revert <merge-or-squash-commit-sha>
gh pr create --base main --head revert/main-broken-<short-sha> --title "revert: restore green main" --body "Reverts <sha> because main full-ci-gate is red: <run-url>"
```

## pr-gate 增量 vs full-ci-gate 全量(关键区别)

| 维度 | pr-gate(增量) | full-ci-gate(全量) |
|---|---|---|
| **范围探测** | ✅ 有 — `scripts/ci/detect-change-scope.py` 按 changed files 决定 | ❌ 永远 full reactor |
| **3 态决策** | `skip` / `partial` / `full` 三档 | 永远 `full` |
| **Maven 范围** | partial 时 `-pl <module> -am -amd` 只跑受影响模块 | 全 10 模块跑 |
| **E2E suite** | partial 时跳过 batch-e2e-tests | 拆 `e2e-shard` 独立 job 25 min 并发跑 |
| **Hadolint / Trivy fs** | ❌ 不跑 | ✅ 跑 |
| **文本 UTF-8 编码** | PR 相对目标分支扫描变更文本 | 全仓扫描 |
| **测试约定（`@DisplayName` + 方法命名）** | 相对 `docs/governance/test-conventions-baseline.txt` 只拦**新增**缺口（中文 `@DisplayName` 类级/方法级；方法名只接受 `shouldXxx_whenYyy` / `方法名_条件_预期`，禁用形状直接失败） | 同一份基线全量复核 |
| **运行时 UTF-8 配置** | Java / SDK / config / CI 变更时核对 Compose、Dockerfile、Helm、Testcontainers | 全量核对 |

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
| 纯 `docs/**.md` | ⚠️ workflow 触发但范围探测判 `docs-only`,Maven 不跑(几秒结束) | ⏭️ `paths-ignore` 不触发 |
| 纯 `.github/workflows/*.yml` | ✅ workflow 触发 + 升级 full(workflow 自身改要全测) | ✅ 全跑 |
| 纯 `helm/*` | ✅ workflow 触发 + 升级 full | ✅ 全跑 |
| 纯 `scripts/local/*` | ✅ workflow 触发 + 升级 full | ✅ 全跑 |
| 纯 `db/migration/*.sql` | ✅ database 静态检查运行（Flyway、migration safety、注释覆盖）；Maven scope 可跳过 | ✅ 全跑 |
| 纯 `docs/api/console-api.openapi.yaml` | ✅ api 路由同步与 OpenAPI 破坏性变更检查运行；Maven scope 可跳过 | ✅ 全跑 |

**结论**:`full-ci-gate` 对明确列入 `paths-ignore` 的纯文档/许可证/编辑器配置不触发；
SDK 纯变更由 SDK workflow 负责，`docs/api/**` 等契约路径不在忽略列表。PR gate 对 database
变更运行 Flyway 结构/checksum、迁移安全和数据库注释检查；这些静态检查不依赖 Maven scope，
不应因 Maven `skip` 而漏跑。OpenAPI 有独立路径同步与破坏性变更检查。

### 统一变更范围探测

`.github/actions/detect-change-scope` 是 workflow 的统一入口，底层使用
`scripts/ci/detect-change-scope.py`，是后端仓库 CI 的范围分类唯一实现。它输出
`java`、`sql`、`database`、`scripts`、`docs`、`config`、`api`、`sdk`、`ci`、
`tests`、`docker`、`helm`、`maven` 和 `unknown` 布尔字段，并在 GitHub Actions 中
同时写入 Job outputs 和 Step summary。一个文件可以命中多个域，例如 Flyway SQL
同时命中 `sql` 与 `database`，SDK 共享常量同时命中 `sdk` 与 `docs`/`api`。
已登记的根目录运行时和质量工具配置归入 `config`，不会单独触发 Maven unit；未登记的新路径
仍归入 `unknown` 并保守执行，避免新增文件静默绕过 Java 回归。

安全规则：PR 直接比较 `base` 与 `head` 提交树计算真实差异；merge queue、push、schedule、手工
触发等没有可靠 PR 差异的事件统一回退全范围；未知文件不算 `docs-only`。新增 workflow
应复用该探测器，不要重新添加路径 glob。它只负责“哪些范围被改动”，不替代 ruleset
required checks，也不允许用范围探测绕过跨域 secret scan 或 main full gate。当前 required
contexts 由 Ruleset 管理，至少包含 `pr-gate-scope`、`sdk-contract-scope`、PR 快速门禁和
SDK 五语言契约矩阵。

---

## 检查项汇总

### 阻断项（任意失败 → 流水线失败）

| 检查项 | 工具 / 脚本 | 触发流水线 |
|---|---|---|
| 应用与基础设施版本对齐 | `check-version-alignment.sh`：应用发布版本、基础服务镜像环境值、Testcontainers 镜像和 Compose/Sim 运行入口对齐 | PR Gate；Full CI Gate |
| OpenAPI 路径对齐 | `check-console-openapi-paths.py` | 全部（setup-build-env） |
| Flyway 文件结构与 checksum 漂移 | `validate-flyway-schema.sh` | PR：database / CI 文件域；已有迁移 checksum 变化阻断 |
| Flyway 危险 DDL | `check-migration-safety.sh`（Squawk，diff-only） | PR：database / CI 文件域；扫描新增或修改的迁移文件，危险 DDL 阻断 |
| 新增数据库对象注释覆盖 | `check-db-comment-coverage.sh`（diff-only） | PR 与 Full CI：database 变更 |
| 注释语言 | `check-comment-language.py --staged` | 本地 pre-commit 增量预检；暂不阻断 PR / Full CI |
| 文本编码 | `check-utf8-encoding.py --staged` | 本地只检查暂存文件；PR 检查目标分支差异；Full Gate 全仓扫描 |
| 应用及基础设施 locale | `check-infrastructure-utf8.py` | 配置/Java/SDK/CI 变更时核对全量配置矩阵 |
| 模块依赖边界 | `check-dependency-boundaries.py` | 全部（run-full-regression） |
| 编译 + 单元测试 | Maven `test` | 全部 |
| 集成测试 (`*IntegrationTest` / 非 E2E `*IT`) | Maven `verify -DskipITs=false` | full-ci-gate；`check-integration-test-coverage.py` 守护含集成测试的主 reactor 模块必须进入 full-ci verify shard |
| E2E 套件 (`*E2eIT`) | Maven `test` `-pl batch-e2e-tests` | full-ci-gate |

> 约定/架构守护（`*ArchTest`、`*ConventionTest`，如 `RepositoryMapReturnConventionTest`、`PositionalArgsConventionTest`）
> 不单独接线 workflow：它们随上述「编译 + 单元测试」的 Maven `test` 全量执行并阻断，触发范围即该行的「全部」。
> 这类测试型守卫的登记入口是 [约定约束与漂移防护总账](../audit/convention-drift-guard-index.md) 的守卫矩阵（`check-*` / `validate-*` 脚本另由 [scripts/ci/README.md](../../scripts/ci/README.md) 登记）。

### 提醒项（失败只通知，不阻断流水线）

| 检查项 | 工具 | 触发流水线 | 说明 |
|---|---|---|---|
| PMD 代码规约 | `maven-pmd-plugin` | 全部（run-full-regression） | 对齐 docs/agent-baseline.md 规约 |
| Spotless 代码格式 | `spotless-maven-plugin` | 全部（run-full-regression） | Palantir Java Format 2.92.0（Google 风格兼容、120 列） |
| 覆盖率门禁 | JaCoCo `jacoco:check` | 全部（run-full-regression） | 行覆盖率 ≥ 60%，初始阈值，后续提升 |
| Secret 扫描 | `security-scan.sh --mode=secret` | pr-gate、full-ci-gate | 扫描密钥泄漏 |
| 依赖漏洞扫描 | Trivy `fs`（vuln） | full-ci-gate | 已知 CVE；OWASP dependency-check 的 NVD 全量下载在 CI 上过慢（5 分钟超时仍下不到 1/5）且不拦门禁，2026-08 起 CI 由 trivy 覆盖，`--mode=deps` 保留本地按需使用 |
| Dockerfile lint | Hadolint | full-ci-gate | `deploy/docker/Dockerfile.app`、`deploy/docker/Dockerfile.ops-toolbox` |
| 文件系统安全扫描 | Trivy `fs` | full-ci-gate | CRITICAL/HIGH 漏洞 + IaC 配置；漏洞扫描读取带治理元数据的 `.trivyignore`，配置误报仅允许在 `.trivyignore.yaml` 中按规则和路径精确豁免；扫描前运行 `bash scripts/ci/install-upstream-modules.sh` 预热 Maven 本地缓存并安装 reactor 产物，降低依赖解析触发 Maven Central 限流的概率 |
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

## 本地 Git Hook 门禁

本地 hook 只承担**快速失败**和**提交前防低级漂移**，不替代 PR / full-ci / staging / sim 验证。设计原则：

- `pre-commit` 按暂存文件域路由，尽量只扫暂存命中的文件；CI 仍保留全量扫描。
- `pre-push` 面向分支级轻量契约，允许使用 `origin/main...HEAD` 的增量基线。
- Maven 编译、PMD、单元/集成/E2E、镜像、安全全量扫描不放进 `pre-commit`，继续由 CI 负责。

### pre-commit

| 触发范围 | 本地检查 | 扫描粒度 |
|---|---|---|
| 所有提交 | `git diff --cached --check` | 暂存区 |
| 所有提交 | `check-utf8-encoding.py --staged` 及编码门禁单测 | **增量**：暂存文件严格 UTF-8 解码，含 NUL 的文本也会检查；已登记二进制后缀跳过，Full Gate 另做全仓扫描 |
| 所有提交 | `check-comment-language.py --staged` | **增量预检**：仅检查暂存 diff 新增说明性注释；当前不作为 PR / Full CI 阻断项 |
| Java 暂存文件 | `spotless:apply` | 受 Maven 插件能力限制，执行仓库 Spotless apply；随后重新暂存 Java 文件 |
| Java 暂存文件 | Java 日志治理及扫描器自测、可读性约定、文本块格式、抑制项注册表、`Map/List/Set.of` 空值风险 | **增量**：仅传入暂存 Java 文件；日志门禁禁止直接标准输出、`printStackTrace()` 和生产日志中的原始异常 message；对应 CI 无参全量 |
| Java 暂存文件 | Lombok 与依赖注入规约及其扫描器单测 | **增量**：pre-commit 检查暂存区涉及的生产 Java 文件并运行扫描器单测；PR 对目标分支变更文件检查并运行单测；Full Gate 全量基线与单测 |
| MyBatis Mapper XML 暂存文件 | PostgreSQL generated key 列约束、禁止位置式 `INSERT ... SELECT *` | **增量**：仅传入暂存 Mapper 文件；对应 CI 无参全量 |
| Shell 暂存文件 | `bash -n`、ShellCheck、Shell Linux 可移植性 | **增量**：仅暂存 Shell 文件；对应 CI 无参全量 |
| Workflow / composite action | `actionlint` | 全仓 workflow 语义检查 |
| `scripts/*` / `load-tests/scripts/*` / `.githooks/*` | 脚本治理注册表 | 全局脚本登记与命名约束 |
| 文档变更 | 文档结构、文档日期策略、代码与文档路径引用、核心术语枚举同步 | 全局文档关系检查 |
| `.env*` 变更 | 环境文件 Shell 安全 | 全局 env 文件检查 |
| YAML / Compose / env 默认值变更 | 配置默认值同步、功能开关注册表 | 按域触发的全量一致性检查 |
| 配置 / Java / SDK 变更 | `check-infrastructure-utf8.py` | **全量矩阵**：核对 8 个应用服务、基础服务、Kafka HA、测试服务、Helm、Dockerfile、Testcontainers 和新建 PostgreSQL 编码参数 |
| `pom.xml` / `*/pom.xml` 变更 | Maven 模块依赖边界 | 全局依赖图检查 |
| `helm/*` 变更 | Helm 环境变量同步、Helm 生产 overlay 安全 | 全局 Helm / 配置一致性检查 |
| 数据库 / 脚本 / 文档 / 配置 / CI 变更 | 生产容量治理自动化 | 校验生产容量巡检脚本、只读 SQL、runbook、索引和 workflow 入口同步 |
| tracked 源码/脚本/配置变更 | Lean LOC 快照重生成与校验 | 基于暂存树生成 `docs/stats/loc-current-lean.md` |
| 所有提交 | 仓库卫生 | 全局仓库约束 |

### pre-push

| 检查 | 扫描粒度 |
|---|---|
| 禁推 `main` / 受保护分支 | 当前 push ref |
| `check-empty-checks.py --base <base>` | 增量 |
| `check-infrastructure-abstraction-boundaries.py --base <base>` | 增量:与 PR merge-base 比较引用语句及出现次数,既有项不因纯日志修改而阻断 |
| `check-readiness-doc-sync.py --base <base>` | 增量 |
| Java 新增行编码反例（FQN、`@Autowired`、`@Transactional` 位置、`RuntimeException`、日志拼接、`ZoneId.systemDefault`、`Charset.forName`） | 增量：PR 新增有效代码行 |
| `check-direct-client-boundaries.py` | 全量：业务层直连客户端是跨模块边界 |
| `check-trivy-ignore-expiry.py` | 全量：安全白名单有效期 |
| `check-env-file-shell-safety.py` | 全量：所有 tracked env 文件 |
| `check-sdk-config-env-parity.py` | 全量：SDK 配置环境变量对齐 |
| `check-config-defaults-sync.py --check`、`check-helm-env-sync.py` | 按域触发的全量：配置 / Helm / Compose 变更时运行 |
| `check-feature-switch-registry.py` | 按域触发的全量：功能开关、YAML、Helm 变更时运行 |
| `check-config-governance.py`、`check-env-variable-governance.py` | 按域触发的全量：配置绑定、环境变量治理入口变更时运行 |
| `check-production-capacity-governance.py` | 按域触发的全量：生产容量巡检脚本、只读 SQL、runbook 和 workflow 入口变更时运行 |
| `check-hardcoded-runtime-config.sh` | 按域触发的全量：运行配置、脚本、容器、测试基础设施变更时运行 |
| `check-changelog-sync.py --base <base>` | 按域触发的增量：发布敏感配置、契约、迁移或架构规范变更时运行 |
| Java readability inventory 自动刷新 | Java 变更时刷新全局清单 |
| Shell 脚本检查 / Docker bake 配置解析 | 仅变更命中的文件或配置 |

### 增量与全量边界

适合增量的检查：单文件语法、单文件可读性、单文件 Shell 可移植性、只依赖新增行的编码反例。

必须全量的检查：跨文件索引、文档链接、配置矩阵、Helm/Compose/YAML 对齐、模块依赖、直连客户端边界、LOC 快照、安全白名单、SDK/runtime 对齐、Maven/PMD/测试/E2E。把这些改成单文件增量会漏掉跨文件漂移。

### UTF-8 编码治理

- 源码、脚本、SQL、配置、文档和 SDK 协议文本必须以无 BOM UTF-8 保存；唯一例外是登记在 `check-utf8-encoding.py` 中、用于导入兼容测试的 UTF-8 BOM fixture。
- PR 对目标分支的新增/修改文本做增量扫描；本地 pre-commit 检查暂存路径；Full Gate 重新扫描全仓，防止未触及的历史文件或路径分类缺口漏检。
- 运行 locale 由 `BATCH_LOCALE` 派生 `LANG` / `LC_ALL`。PostgreSQL `--encoding=UTF8` 只影响新初始化的数据目录，不自动改写已有 PGDATA；既有数据库需单独查询 `server_encoding`，需要变更时走迁移/备份恢复流程。
- Kafka、对象存储和 Valkey 的协议载荷是字节数据，不存在服务端全局“文本编码开关”；平台 JSON/SDK 协议明确 UTF-8，业务文件的合作方字符集仍在导入/导出边界显式声明。

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
> 使用 `--skip-build` 标志可跳过此步骤（仅用于显式 opt-in 的 `make test-parallel ALLOW_PARALLEL_TESTS=1`，
> 此时由 `--build-only` 统一完成一次构建）。默认推荐串行运行，避免 Docker/CPU/内存竞争。
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
make test-parallel ALLOW_PARALLEL_TESTS=1  # 显式 opt-in 后并行；汇总所有子任务退出码
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

标签路径由 `label-automerge.yml` 监听 PR 门禁和 `full-ci-gate` 回退门禁；所有 required check 通过后执行普通 squash merge，**不跳 check、不绕过分支保护**。

| 路径 | 触发 | 是否自动 approve | 是否自动 enable auto-merge |
|---|---|---|---|
| `label-automerge.yml` | 任意人开 PR + 打 `automerge` 标签 | ❌ | ✅ |

> Dependabot PR(`.github/dependabot.yml` 配置的 Maven 等生态)不走独立 auto-merge workflow,需人工或经 `automerge` 标签走 `label-automerge.yml` 路径。

**用法（label 路径）**：
1. 开 PR
2. 自己 review 一遍觉得 OK
3. 给 PR 贴 `automerge` 标签
4. 所有 required check 一变绿后自动 squash merge

**撤销**：移除 `automerge` label + 在 PR 页面点 "Disable auto-merge"，或命令 `gh pr merge --disable-auto <PR_URL>`。

**前置条件**（一次性，repo Settings）：
- General → "Allow auto-merge" 必须勾上
- Branch protection / Ruleset 必须要求 `pr-gate-scope`、`sdk-contract-scope`、
  `static-checks`、`unit-it-a`、`unit-it-b1`、`unit-it-b2`、`security-scan`、
  `sdk-contract-required`。后者聚合 `validate fixtures` 和五语言契约矩阵，避免矩阵
  版本名漂移导致 Ruleset required context 失效。否则 `--auto` 可能在部分契约检查完成前合并。
- `strict_required_status_checks_policy=true`，并启用 main 的 merge queue，避免多个 PR
  分别通过后合并结果失真。

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

CI gate 阻断要满足「确定性 fail」前提;flaky 用例第一次 fail 是噪声,阻断就把噪声升级成主干 red,反而让开发者忽略后续真问题。阻断由人工治理 issue 回退,脚本只负责**让 flaky 可见**。

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
| staging-gate | — | nightly schedule / 手动 | — | 全量 E2E 回退闸门 |

### Job 级分布

**pr-gate(5 job 并行,瓶颈 unit-it-b2)**
- static-checks 1:57 / security-scan 1:16 / unit-it-a 2:49 / unit-it-b1 3:02 / **unit-it-b2 4:14** ← critical path

**full-ci-gate(9 job 并行,瓶颈 security-scan)**
- static-checks 1:36 / unit-it-a 3:04 / unit-it-b1 3:13 / unit-it-b2 4:06 / e2e-shard 1-4 各 4:23-4:58 / security-scan 6:15（历史值，含 OWASP dependency-check NVD 下载）

> 2026-08-09:security-scan job 移除 OWASP dependency-check 步骤（NVD 下载 5 分钟超时仍只下到 70k/352k，且该步骤 continue-on-error 不拦门禁；依赖漏洞已由同 job 的 Trivy fs 覆盖），job 预计从 6:15 降到 ~2:00，full-ci-gate 瓶颈随之变为 e2e-shard。

> 2026-05-23:PR #27 合并后,本仓删除了 `capacity-gate` / `promote-staging`(dead code,见本文档开头说明);`staging-gate` 仍保留为 nightly 全量 E2E 回退闸门。

---

## 关键文件索引

```
.github/
  workflows/
    pr-gate.yml              # PR 门禁
    sdk-contract-parity.yml  # 五语言 SDK 契约门禁
    full-ci-gate.yml         # 主干质量门禁(含安全扫 + Checkov)
    staging-gate.yml         # nightly / 手动 全量 E2E 回退闸门
    daily-sim-strict-validation.yml # nightly sim + strict，成功后调用镜像构建
    docker-image-build.yml   # 手动 / reusable 镜像构建
    label-automerge.yml      # automerge 标签自动归并
  actions/
    setup-build-env/         # 共享 setup：JDK、Maven cache、OpenAPI 校验
    detect-change-scope/     # 共享变更范围探测入口
  renovate.json              # 依赖自动更新配置

scripts/ci/
  run-full-regression.sh     # 所有 Maven 回归的统一入口
  check-console-openapi-paths.py   # OpenAPI 路径对齐校验
  check-dependency-boundaries.py   # 模块依赖边界校验
  security-scan.sh           # 安全扫描入口（secret / deps / dast）

build/
  pmd-ruleset.xml            # PMD 规则集（对齐 docs/agent-baseline.md）

pom.xml                      # 父 pom：JaCoCo agent、PMD、Spotless 插件配置
```
### Sonar 门禁（预留，默认关闭）

仓库已预留 `.github/workflows/sonar-gate.yml`，但默认不执行，不纳入当前
required checks。只有配置仓库变量 `SONAR_GATE_ENABLED=true` 后才会运行。
Java 生产代码变更应按 [Sonar Runbook](sonar.md) 在本地运行增量审阅；本地执行情况与 CI 门禁状态分开报告。当前工作流关闭或显示 `SKIPPED` 时，不得视为 Sonar 通过。

启用前配置：

- Secret：`SONAR_TOKEN`
- Variable：`SONAR_HOST_URL`（可选，默认 `https://sonarcloud.io`）
- Variable：`SONAR_PROJECT_KEY`（可选，默认 `file-batch-system`）
- Variable：`SONAR_ORGANIZATION`（SonarCloud 必填；自建 SonarQube 可不填）

启用后工作流会等待 Sonar Quality Gate 结果。现有 PMD、Spotless、SpotBugs、
依赖扫描和测试门禁保持不变，Sonar 不替代这些检查。
