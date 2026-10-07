# CI 体系说明

## 固定契约治理

PR 的 `PR_JAVA_CONTRACT` 检查变更生产 Java；规则或治理注册表变化则全量复扫。Full Gate 的 `FULL_JAVA_CONTRACT` 检查全部生产 Java，二者均执行守卫自测。检查点是固定响应类型、注册有限域及已有协议键复用，不替代 PMD/Sonar 或接口运行验证。当前违规基线为空，动态例外只按精确类路径和方法签名登记。注册表变更属于 CI 变更，不视为纯文档。判定及操作命令见 [CI 脚本说明](../../scripts/ci/README.md#java-固定契约与协议值守卫)。

## 概览

长期治理原则见 [CI 与测试质量治理](../standards/ci-test-quality-governance.md)；本文只说明具体工作流与操作入口。

项目有两条主要代码门禁流程（PR Gate、main Full CI Gate），另有补充验证与失败处理自动化。补充流程不替代代码合并门禁：

| 工作流 | 分类 | 触发时机 | 目标 | 超时 |
|---|---|---|---|---|
| `pr-gate` | PR 代码门禁 | PR → main(opened / synchronize / reopened / ready_for_review,非草稿) | 快速反馈，阻断不合格 PR | 45 min |
| `sdk-contract-parity` | SDK 契约门禁 | PR、merge queue、每日 16:00 UTC、手动 | 五语言 fixture、共享常量和 conformance 契约 | — |
| `full-ci-gate` | main 全量门禁 | push main、每周日 02:00 UTC、手动 | 主干质量基线 + 安全扫描(含 K8s manifest Checkov) | 75 min |
| `staging-gate` | 补充 E2E 验证 | nightly(每天 18:00 UTC / 北京 02:00)+ workflow_dispatch | 全量 E2E(smoke + critical + regression 全跑,6 shard 并发)；Java 架构/约定守卫独立并发，不替代 `full-ci-gate` | — |
| `daily-sim-strict-validation` | 补充真实数据验证 | nightly(每天 13:31 UTC / 北京 21:31)+ workflow_dispatch | 定时触发按最近一次计划时间对应的北京时间日期检查代码/配置变更，延迟跨午夜仍归属原计划日；手动触发按当前北京时间日期。Markdown/RST、`LICENSE`、`NOTICE` 除外。需要验证时同环境先执行 `sim-harness all`，再执行 BE-ACC step 5(strict real-data verification)；strict step 使用 `always()` 采证，不因 sim 失败被短路 | 240 min |
| `docker-image-build` | nightly / 可选发布镜像构建 | 由 `daily-sim-strict-validation` 在当天有代码/配置变更且 sim + strict 成功后调用；也支持手动和复用调用 | 默认只用 Docker Bake 构建全部应用镜像和运维工具箱镜像；显式 `publish=true` 时登录 GHCR、推送 SHA 镜像并上传含 immutable digest 的 backend image set。CI 使用 Maven Central 配置并带依赖下载重试 | 30 min |
| `OpenSSF Scorecard` | 供应链治理报告 | push main、每周三、手动 | 生成 SARIF 并上传 Code Scanning；不按总分阻断 PR | 20 min |
| `quarterly-dependency-review` | 依赖集中治理盘点 | 每季度首日、手动 | 生成多生态更新报告和单个治理 Issue；不改代码、不创建 PR | 25 min |
| `main-failure-triage` | 失败处理自动化 | main 的 `full-ci-gate` 核心 job 失败 | 自动标记关联 PR 并评论处理要求；无关联 PR 时创建 issue | — |

> **2026-05-23 删除 `capacity-gate` / `promote-staging`**:`capacity-gate` 目标是 `*.svc.cluster.local`(k8s 集群内 DNS),GitHub-hosted runner 永远连不上 → 100% Connection refused;`promote-staging` 要写 `pinpols/file-batch-system-ops` 但仓 / PAT 都没在用,等同 dead code。Checkov K8s manifest 静态扫已迁到 `full-ci-gate`。若未来要恢复真·生产环境验证 / 容量回归 / ops 仓同步,改用 self-hosted runner 部署到集群内,或 staging 暴露公网 ingress + 配 PAT。
>
> `staging-gate` **仍存在**:作为 nightly schedule / staging 分支的全量 E2E 回退闸门(见上表与 `e2e-tier-strategy.md`)。

## CI 依赖与安全扫描版本基线

2026-09-30 已完成第一批 CI 依赖治理升级。当前工作流统一使用：

| 类别 | 当前版本 | 说明 |
|---|---|---|
| `actions/checkout` | v7（固定提交 SHA） | 所有 workflow/composite action 统一 |
| `actions/setup-python` | v7 | Python 3.x 版本由 workflow 输入决定 |
| `actions/setup-java` | v6 | JDK/Maven cache 和发布凭据需按原输入验证 |
| `actions/setup-node` | v7 | npm 发布 job 显式提供 `NODE_AUTH_TOKEN` |
| `actions/setup-go` | v7 | 保留现有 `go-version` / `go-version-file` 输入 |
| `actions/upload-artifact` | v7 | artifact 名称和下载配对保持不变 |
| `actions/download-artifact` | v8 | Full Gate 质量趋势汇总测试报告 |
| `docker/setup-buildx-action` | v4 | Buildx/Bake 构建需在 CI 回归 |
| Hadolint Action | v3.5.0 | Dockerfile lint |
| SBOM Action | v0.24.3 | 固定具体 release，不再使用浮动 `v0` |
| Docker Bake Action | v7 | 与 Buildx v4 配套；真实镜像 workflow 仍需升级后运行证据 |
| Squawk / oasdiff | 2.65.0 / 1.32.1 | 迁移安全和 OpenAPI 破坏性变更守护 |
| Trivy CLI | 0.74.0 | `vuln,misconfig` 扫描参数统一 |

CI 版本基线与运行结果分开记录。每次核验 Full Gate、CodeQL 或镜像工作流时，按目标分支的实际 commit SHA 检查最新 run；`IN_PROGRESS`、`QUEUED`、`SKIPPED` 或其他 SHA 上的成功都不能作为当前提交通过证据。Docker Buildx/Bake、发布凭据、GHES 与 self-hosted runner 兼容性仍须按目标环境验证。后续每次升级按 [CI 外部 Actions 版本升级与验收记录](../backlog/ci-external-action-upgrade-backlog-2026-09-30.md) 运行对应回归。

OpenSSF Scorecard 是 main/定时的供应链治理报告，不属于 PR required checks，也不以总分决定合并。其 workflow 失败表示扫描链路本身需要修复；SARIF 中的发现按 [`../standards/open-source-governance.md`](../standards/open-source-governance.md) 分级治理。外部 Action 必须固定 40 位 SHA；季度依赖盘点只创建 Issue 和 artifact，由维护者建立一张人工 PR 并核对 release、变更说明和所需权限。

CodeQL 的 `Analyze (java)` 只有在 `codeql.yml` 已于 main 生效、并确认纯文档 PR 也会创建该检查后，才能加入 ruleset required checks。配置顺序必须是先合 workflow、用代码变更和纯文档 PR 各验证一次，再更新 ruleset；反向操作会使被 `paths-ignore` 跳过的 PR 永久等待。

运行环境约束：

- setup-python/setup-node/setup-go 的 Node 24 运行时要求 GitHub Actions Runner `v2.327.1` 或更高；GitHub-hosted `ubuntu-latest` 满足该要求，self-hosted runner 必须单独核对。
- `actions/upload-artifact@v7` 使用当前 artifact 服务契约；迁移到 GHES 前必须确认 GHES 支持该 major，否则保持独立兼容版本或由平台团队提供替代上传方案。
- Gitleaks `8.30.1` 本轮不盲目更换；已在 Docker `linux/amd64` 用同版 artifact 验证合成 `ghp_...` 正向退出 1、负向退出 0，并通过 PR/Full Gate 安全扫描；继续关注上游规则变化，该样例不代表所有密钥类型。
- CodeQL、Trivy Action、Checkov、发布 Action 和 Sonar 仍按 G7 定期复核，不因本批版本升级自动视为完成。
- `.github/dependabot.yml` 对 Maven、GitHub Actions 和 Docker 设置季度解析但将版本 PR 上限设为 0；仓库级 Dependabot security updates 关闭自动修复 PR，安全告警仍持续发现。`quarterly-dependency-review` 每季度只生成报告和一个 Issue，维护者按[季度依赖集中治理](./quarterly-dependency-governance.md)创建一张人工 PR。Maven 更新必须同步入库 SBOM；Docker 构建镜像的 JDK 主版本继续人工决策。
- 所有外部 Action 必须使用 40 位提交 SHA；尾部版本注释仅用于可读性。`check-github-action-pinning.py` 在本地、PR 和 Full Gate 阻止浮动 tag/branch 回流。

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
- **CodeQL 分层执行**：PR 使用 Java `build-mode: none` 缩短 required check；main push、定时和手工运行保留手工全量编译，继续覆盖构建生成代码和精确依赖。仓库若引入 Kotlin，必须先恢复构建模式再合入。
- **非测试 job 不准备 Testcontainers**：静态检查、安全扫描和 CodeQL 通过 `cache-testcontainers: false` 跳过容器镜像恢复；单元/集成/E2E 仍保留镜像缓存。
- **门禁结果行统一**:本地 hook 与 CI 统一输出 `状态 | code | gate | exit_code | action`；跳过时再输出 `reason`。单步中串行运行多个阻断检查时，每项都必须通过共享 `gate_run` 输出独立结果；具体诊断信息可保留各检查器原有内容。
- **静态门禁失败统一汇总**：PR 与 Full Gate 的 `static-checks` 会继续执行所有相互独立的业务/规范检查，在 job 末尾一次性列出失败代码、名称和退出码后阻断；checkout、构建环境安装等缺失后无法继续的基础前置仍立即失败。本地 pre-commit/pre-push 保持首错即停。
- **SBOM 快照必须同步**：POM 或 CI 门禁变更时，PR Gate 重生成 CycloneDX SBOM 并与 `docs/compliance/sbom.json` 比较；Full Gate 的许可证检查再次复核。动态 artifact 生成成功不等于入库快照已同步。
- **核心术语枚举必须同步**：修改实例、工作流、节点、分片、步骤、任务状态，或调度类型、触发来源、节点类型、运行模式 enum 时，运行 `python3 scripts/ci/check-terminology-doc-sync.py --write`；PR / Full Gate 的只读检查会阻断旧值表。
- **确定性派生产物由 hook 维护**：POM 已暂存且没有同文件未暂存改动时，pre-commit 自动重建并暂存 SBOM，同时执行许可证门禁；`@ConfigurationProperties` 增删时自动重建文档与运行时两份配置治理目录；代码量快照沿用 staged-tree 自动同步。CI 始终只读验证，不用机器人账号回写 PR。
- **语义与安全例外保持人工审批**：Changelog、功能开关说明、环境变量 owner、Java 抑制项、Trivy 忽略项、SQL 例外基线和许可证风险说明不可由门禁自动放宽。CI 应给出修复命令或登记位置，但不替开发者作风险决定。

## 开源多人协作策略

多人并行提交时，单个 PR 绿并不能证明“合并后主干仍绿”。本项目按以下规则处理：

1. **main 受保护**：禁止常规直推；所有变更通过 PR 和 required checks，讨论必须解决。当前只有一个维护者，因此不伪造独立审批；新增第二位维护者后按开源治理规范启用 1 个独立审批和 Code Owner 审批。ruleset 不保留永久 bypass actor；紧急恢复需显式修改 ruleset 并保留平台审计记录。
2. **merge queue 按协作规模启用**：当前不声称已启用。并发活跃 PR 稳定达到 3 个以上、出现“单 PR 绿但连续合并后 main 红”时，再在 ruleset 启用 merge queue，并先确认 required workflows 支持 `merge_group`。单维护者阶段不为形式完整增加队列等待和维护成本。
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
| **单元测试路由** | 四个保守分片信号；公共边界全跑，叶子模块只跑所属分片 | 永远全跑 |
| **Maven 范围** | 每个被选分片先用 `install -DskipTests -am` 构建依赖，再只测试本分片模块；PR unit 分片排除 `*IntegrationTest` | 全部固定分片并发执行；依赖只构建一次/分片，目标模块执行完整 unit + IT |
| **E2E suite** | 不运行，由合入后门禁回退 | 28 个测试按实测 LPT 拆为 6 个并发 shard |
| **Hadolint / Trivy fs** | ❌ 不跑 | ✅ 跑 |
| **文本 UTF-8 编码** | PR 相对目标分支扫描变更文本 | 全仓扫描 |
| **测试约定（`@DisplayName` + 方法命名）** | 相对 `docs/governance/test-conventions-baseline.txt` 只拦**新增**缺口（中文 `@DisplayName` 类级/方法级；方法名只接受 `shouldXxx_whenYyy` / `方法名_条件_预期`，禁用形状直接失败） | 同一份基线全量复核 |
| **运行时 UTF-8 配置** | Java / SDK / config / CI 变更时核对 Compose、Dockerfile、Helm、Testcontainers | 全量核对 |

### PR 单元分片路由

范围探测输出 `unit-a-required`、`unit-b1-required`、`unit-b2-workers-required` 和
`unit-b2-console-required`。规则如下：

| 路径 | 执行分片 |
|---|---|
| Orchestrator、Java SDK | A |
| Worker Core | A、B1、B2 Worker |
| Trigger、Process、Dispatch | B1 |
| Import、Export、Atomic | B2 Worker |
| Console API | B2 Console |
| Common、Test Support、数据库迁移、任意 POM、Maven Wrapper、未知路径或未登记 `batch-*` 模块 | 全部 |

`unit-it-b2` 是 Ruleset 使用的稳定聚合 context；内部 Worker/Console 子分片任一失败都会使聚合失败。

## 非代码提交触发吗?

| 提交类型 | pr-gate | full-ci-gate |
|---|---|---|
| 纯 `docs/**.md` | ⚠️ workflow 触发但范围探测判 `docs-only`,Maven 不跑(几秒结束) | ⏭️ `paths-ignore` 不触发 |
| 纯 `.github/workflows/*.yml` | ✅ CI 静态检查；Maven 单元跳过 | ✅ 全跑 |
| 纯 `helm/*` | ✅ 配置/部署静态检查；Maven 单元跳过 | ✅ 全跑 |
| 纯 `scripts/local/*` | ✅ 脚本静态检查；Maven 单元跳过 | ✅ 全跑 |
| 纯 `db/migration/*.sql` | ✅ database 静态检查 + 全单元分片 | ✅ 全跑 |
| 纯 `docs/api/console-api.openapi.yaml` | ✅ api 路由同步与 OpenAPI 破坏性变更检查运行；Maven scope 可跳过 | ✅ 全跑 |

**结论**:`full-ci-gate` 对明确列入 `paths-ignore` 的纯文档/许可证/编辑器配置不触发；
SDK 纯变更由 SDK workflow 负责，`docs/api/**` 等契约路径不在忽略列表。PR gate 对 database
变更运行 Flyway 结构/checksum、迁移安全和数据库注释检查；这些静态检查不依赖 Maven scope，
不应因 Maven `skip` 而漏跑。OpenAPI 有独立路径同步与破坏性变更检查。

### 统一变更范围探测

`.github/actions/detect-change-scope` 是 workflow 的统一入口，底层使用
`scripts/ci/detect-change-scope.py`，是后端仓库 CI 的范围分类唯一实现。它输出
`java`、`sql`、`database`、`scripts`、`docs`、`config`、`api`、`sdk`、`ci`、
`tests`、`docker`、`helm`、`maven`、`unknown` 及四个单元分片布尔字段，并在 GitHub Actions 中
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
> 由 PR / Full / Staging 的 `java-governance` job 独立执行，业务 unit/IT 显式排除这两类后缀。
> `check-java-governance-test-coverage.py --verify-reports` 要求每个源码类都产生 Surefire XML，零用例或漏跑会阻断。
> 这类测试型守卫的登记入口是 [约定约束与漂移防护总账](../audit/convention-drift-guard-index.md) 的守卫矩阵（`check-*` / `validate-*` 脚本另由 [scripts/ci/README.md](../../scripts/ci/README.md) 登记）。
> Python / Shell / 配置/契约守卫在 `static-checks` 中独立命名执行，不混入 Maven unit/IT；
> 为避免重复 checkout 和 JDK 初始化，它们暂不拆成额外 runner job。
> 本地 pre-commit 只核对治理测试源码清单；pre-push 在 Java、POM 或相关 CI 路由变化时调用
> `run-java-governance-tests.sh` 真实执行同一组测试，在线 workflow 也复用该入口。

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
| 公共代码、测试基础设施、数据库迁移、POM/Maven Wrapper、未知路径 | 四个单元分片全部执行 |
| 仅单个叶子模块（如 `batch-console-api/`） | 仅执行所属分片；Maven 先构建依赖闭包，再只测试叶子模块 |
| Worker Core | 执行 Core 自身和三组 Worker 分片，不启动 Console 分片 |
| 仅 CI、脚本、部署配置或文档 | 跳过 Maven 单元测试，保留命中域的静态门禁 |
| 无 Java 相关变更 | 跳过 Maven gate |

PR 所有单元分片均设置 `-DskipITs=true`；集成和 E2E 由 Full Gate 回退。详细证据与回退条件见 [CI 门禁耗时分析与优化记录](../analysis/ci-gate-runtime-optimization-2026-10-07.md)。

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

> Dependabot 自动版本和安全修复 PR 已关闭。季度治理 PR 由维护者创建，不走独立 auto-merge workflow；如需自动等待门禁，只能由人工添加 `automerge` 标签走 `label-automerge.yml`。

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

必需门禁的 `rerunFailingTestsCount` 固定为 `0`，首次失败就是失败，不允许用全局重跑掩盖回归。确认受外部时序影响且无法立即修复的测试，才可使用 `@FlakyTest(issue, owner, expiresOn)` 临时隔离；默认测试排除 `flaky` tag，每周或手动 Full Gate 通过 `run-flaky-quarantine.sh` 单独执行并最多重跑两次。

```bash
python3 scripts/ci/check-flaky-test-governance.py
bash scripts/ci/run-flaky-quarantine.sh
```

隔离规则：

1. 必须先建 Issue，写明可复现条件和修复计划。
2. `owner` 使用 GitHub 账号或团队，`expiresOn` 使用绝对日期；到期未处理会阻断静态门禁。
3. 禁止用 `@Disabled` 替代隔离，禁止在必需门禁命令行重新打开全局重跑。
4. 根因修复后删除 `@FlakyTest`；`collect-flaky.sh` 仅汇总隔离执行产生的首次失败记录。

## 覆盖率、变异测试与趋势

- PR / Full Gate 的 Java shard 使用 `check-diff-coverage.py` 校验本次变更的可执行行覆盖率，最低 80%；存量 Bundle 25% 和核心类 80% 棘轮继续保留。
- 每周及手动 Full Gate 使用 `run-critical-mutation.sh` 对 `FileStateMachine` 和 `DefaultLifecycleEventMapper` 运行 PIT，阈值为变异杀死率 70%、覆盖率 80%。范围保持小而稳定，不做全仓变异测试。
- `quality-trend` job 汇总当前 Surefire/Failsafe XML，并读取最近 30 次 `full-ci-gate` 的成功率和耗时；JSON/Markdown 产物保留 90 天。报告包含测试总量、失败/跳过、首次失败重跑、E2E 成功、失败类型、禁用测试和软门禁数量。
- 软门禁统一登记在 `docs/governance/soft-gates.json`；每项必须有 owner、当前基线、升级期限和目标。CodeQL 上传的有限重试属于传输容错，不计为软门禁。

---

## 产物归档

| 产物 | 来源流水线 | 保留天数 |
|---|---|---|
| Surefire 测试报告 | pr-gate、full-ci-gate | 14 天 |

---

## 耗时基线（2026-10-07）

优化前最近成功运行的基线。取消运行不进入样本；任一 workflow P90 连续三次超过目标上限 50% 需排查。

| Workflow | 样本 | 优化前 P50 | 优化前 P90 | 优化后目标 |
|---|---:|---:|---:|---:|
| pr-gate | 20 | 8:10 | 8:58 | P50 3:30-5:00 |
| PR CodeQL | 18 | 8:19 | 9:58 | P50 3:00-5:00 |
| full-ci-gate | 20 | 8:26 | 11:44 | P50 4:30-6:00 |
| staging-gate | 未纳入本轮样本 | — | — | 六片全量 E2E 4:00-5:30；Java 治理组并发，不进入关键路径 |

### Job 级分布

**pr-gate（优化前）**

- 最近一次首个 runner 等待 47 秒；`unit-it-b2` 482 秒，其中 Maven 测试 424 秒。
- 优化后 B2 拆为 Worker / Console 并行，叶子模块只启动所属分片；required context 名仍为 `unit-it-b2`。

**full-ci-gate（优化前）**

- 最近一次最长 Unit / E2E job 分别为 475 / 477 秒。
- 优化后 Unit B2 拆分，四个 Java shard 不再通过 `-am` 重复执行上游测试；E2E 从 4 片改为实测 LPT 六片，每片测试体基线 148-179 秒。
- 静态守卫与 unit/IT 分离且去掉 action-pinning 重复执行；Java Arch/Convention 守卫单独并发执行，统一入口本地基线约 68 秒。

**CodeQL（优化前）**

- 最近 PR 运行约 9:02：环境 44 秒、编译 289 秒、分析 136 秒、上传 7 秒。
- PR 改为 Java no-build；main/定时/手工仍使用 manual build，在线连续 10 次运行后更新本节实测数据。

完整样本、运行链接和回退标准见 [CI 门禁耗时分析与优化记录](../analysis/ci-gate-runtime-optimization-2026-10-07.md)。

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
    setup-build-env/         # 共享 setup：JDK、Maven cache、OpenAPI 校验；Testcontainers 缓存可关闭
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

### CodeQL 构建模式

- PR：`build-mode: none`，仍运行 `security-extended` 查询并上传 SARIF，作为 required `Analyze (java)`。
- main push、schedule、workflow_dispatch：`build-mode: manual`，执行跳过测试的全 reactor 编译后分析。
- 无构建模式只适用于当前纯 Java 仓库；引入 Kotlin 或发现生成源码漏析时必须恢复 PR 手工构建。
- PR 与 main 告警差异需要人工解释，不能仅因 PR 更快就认定覆盖等价。

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
