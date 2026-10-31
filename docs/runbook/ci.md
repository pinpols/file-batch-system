# CI 体系说明

## 固定契约治理

PR 的 `PR_JAVA_CONTRACT` 检查变更生产 Java；规则或治理注册表变化则全量复扫。Full Gate 的 `FULL_JAVA_CONTRACT` 检查全部生产 Java，二者均执行守卫自测。检查点是固定响应类型、注册有限域及已有协议键复用，不替代 PMD/Sonar 或接口运行验证。当前违规基线为空，动态例外只按精确类路径和方法签名登记。注册表变更属于 CI 变更，不视为纯文档。判定及操作命令见 [CI 脚本说明](../../scripts/ci/README.md#java-固定契约与协议值守卫)。

## 概览

`PR_SIM_RUNTIME_CLEANUP_TEST` / `FULL_SIM_RUNTIME_CLEANUP_TEST` 分别在 policy / full-static 组执行 Sim 初始化、容量脚本清理和混合画像契约测试。PR 按脚本、数据库或 CI 变更触发；本地 pre-commit 的脚本测试组也执行容量用例。清理失败必须非零，且不覆盖主流程原始失败码；混合画像须传入 Workflow ID、独立分配业务日，并拒绝只完成部分子 Job 的容量结论。

长期治理原则见 [CI 与测试质量治理](../standards/ci-test-quality-governance.md)；本文只说明具体工作流与操作入口。
前后端 schedule 的保留依据、代码变更检测要求和季度复核清单见[CI 定时治理](./ci-schedule-governance.md)。
全部 CI 守卫的分类目录、判定正反例以及快速失败/失败汇总边界见[CI 脚本说明](../../scripts/ci/README.md#完整守护清单)。守卫、执行入口和配套 SQL 均在职责 README 登记；[工作流总索引](../../.github/workflows/README.md)覆盖全部 workflow，[共享 Actions 索引](../../.github/actions/README.md)覆盖复用步骤。新增、改名和删除均由 `check-script-governance.py` 核对真实跳转、用途与路径，不能只写文件名。

门禁统一通过 `scripts/lib/gate-result.sh` / `scripts/ci/run-gate.sh` 执行。`group` 表示稳定职责分组，`mode` 只有 `enforce`、`report`、`ignore`：阻断模式执行并传播失败，报告模式执行但只记录真实退出码，忽略模式明确不执行；范围路由未命中则是 `SKIP / mode=conditional`。统一执行器去重旧成功状态，旧失败/报告/忽略/跳过以`诊断 |`保留其原因；合规嵌套结果不丢弃，最终状态由执行器输出。结果字段单行编码、日志/摘要及失败汇总遵循[共用输出契约](../../scripts/ci/README.md#门禁结果格式)。模式控制使用 `BATCH_GATE_MODE`、`BATCH_GATE_GROUP_MODES`、`BATCH_GATE_MODES` 或单次调用的 `--mode`，优先级和命令示例见[CI 脚本说明](../../scripts/ci/README.md#分组开关和模式)。required checks 必须保持 `enforce`；仓库 workflow 中任何报告/忽略项都要以 `soft-gate-id` 登记到 `docs/governance/soft-gates.json`，不得用散落的 `BATCH_CI_SKIP_*`、`|| true` 或裸 `exit 0` 吞掉结果。

项目有两条主要代码门禁流程（PR Gate、main Full CI Gate），另有补充验证与失败处理自动化。补充流程不替代代码合并门禁：

| 工作流 / job | 分类 | 触发时机 | 实际职责 | 超时 |
|---|---|---|---|---|
| `pr-gate` | PR 合入门禁 | PR → main（opened / synchronize / reopened / ready_for_review，非草稿）、`merge_group`、手动 | 按变更范围执行静态检查和测试分片 | 各 job 单独设置，不设统一 workflow 超时 |
| `sdk-contract-parity` | SDK 契约门禁 | 所有 PR（按 SDK 变更范围执行）、`merge_group`、手动 | 五语言共享契约验证；`sdk-contract-required` 聚合结果是稳定门禁，live transport 在 SDK 相关 PR 上运行但不属于 required 聚合 | 各 job 单独设置 |
| `full-ci-gate` | main 全量回归 | main push、每周日 02:00 UTC、手动 | 主干静态检查、单元/集成测试、E2E 和安全检查；定时/手动还运行测试质量分析 | 各 job 单独设置，不设统一 workflow 超时 |
| `staging-gate` | 手动后端 E2E 复验 | 手动 | GitHub-hosted runner 上的 Java 治理与并行后端 E2E，依赖由 Testcontainers 管理；不连接外部 staging，不执行部署 | 各 job 单独设置 |
| `daily-sim-strict-validation` | 变更感知的定时真实数据验证 | 每天 13:31 UTC（北京 21:31）、手动（`force=true` 可强制执行） | 检测当日代码/配置变化后运行 `sim-harness all` 和 BE-ACC strict；纯 Markdown/RST、`LICENSE`、`NOTICE` 不触发当天验证。手动运行按当前北京时间日期判断；定时运行使用计划触发时间所属日期。SIM 失败不跳过 strict，但启动失败/取消/runner 丢失不算通过。只有两项成功才构建 nightly 镜像 | 验证 job 240 min；内部步骤分别设限 |
| `daily-backend-reliability` | 每日隔离故障回归 | 每天 20:17 UTC（北京次日 04:17）、手动 | 在独立 GitHub-hosted runner 调用 `scripts/local/backend-reliability-tests.sh`，用 Testcontainers 验证文件完整性、并发、checkpoint 续跑、NAS 发布、Kafka 故障恢复、Outbox exactly-once 与消费者背压；上传 Surefire 报告。不会连接 Compose Sim 或 staging，也不执行 PITR/HA 切换 | 120 min |
| `docker-image-build` | 变更感知的镜像构建 | 由 `daily-sim-strict-validation` 在当天有代码/配置变更且 sim + strict 成功后调用；也支持手动和复用调用 | 默认只用 Docker Bake 构建全部应用镜像和运维工具箱镜像；显式 `publish=true` 时登录 GHCR、推送 SHA 镜像并上传含 immutable digest 的 backend image set。CI 使用 Maven Central 配置并带依赖下载重试 | 30 min |
| `compose-cd` | 受控手动交付 | 仅从 `main` 手动触发 | 固定前后端 main SHA，复用两个镜像构建工作流并组装统一 digest manifest；可选连接 staging Docker 主机执行 Compose、health/version、前端 Playwright 与 Lighthouse，验收失败时回滚。它不随 push 自动部署，真实 staging 需先配置 GitHub Environment 和主机凭据 | 分 job 设置；Playwright 50 min |
| `prepare-platform-release` | 联合版本准备 | 维护者手动选择 patch/minor/major | 校验双仓版本后计算同一目标版本，并创建/补齐两仓 Release PR；需要安装到两仓的 GitHub App 凭据，不合并、不打 tag、不发布 | 15 min |
| `OpenSSF Scorecard` | 供应链治理报告 | push main、每周三、手动 | 生成 SARIF 并上传 Code Scanning；不按总分阻断 PR | 20 min |
| `quarterly-dependency-review` | 依赖集中治理盘点 | 每季度首日、手动 | 生成多生态 artifact 与 Actions Summary；不创建 Issue/PR | 25 min |
| `main-failure-triage`（job） | 失败处理自动化 | `full-ci-gate` 核心 job 失败 | 自动标记关联 PR 并评论处理要求；无关联 PR 时创建 issue | job 级 |

> **2026-05-23 删除 `capacity-gate` / `promote-staging`**:`capacity-gate` 目标是 `*.svc.cluster.local`(k8s 集群内 DNS),GitHub-hosted runner 永远连不上 → 100% Connection refused;`promote-staging` 要写 `pinpols/file-batch-system-ops` 但仓 / PAT 都没在用,等同 dead code。Checkov K8s manifest 静态扫已迁到 `full-ci-gate`。若未来要恢复真·生产环境验证 / 容量回归 / ops 仓同步,改用 self-hosted runner 部署到集群内,或 staging 暴露公网 ingress + 配 PAT。
>
> `staging-gate` 仍存在，但当前仅由手动事件触发；名称不代表它会部署到 staging 集群。其职责是 GitHub-hosted runner 上的独立全量 E2E 与 Java 治理复验。

其他独立工作流：`codeql.yml` 在 PR、main push 和每周定时运行（当前未配置手动触发）；`sdk-orchestrator-e2e.yml` 在相关 main 变更后/手动执行五语言真实 Orchestrator E2E（非 required），先构建一次 Orchestrator/Trigger 镜像并预热 BuildKit GHA 缓存，五个语言 job 仍各自启动隔离服务栈；`workflow-lint.yml` 检查 workflow/action、Zizmor 配置及其 Python 工具依赖变更；`license-review.yml` 检查依赖许可变更；`strict-verify.yml` 提供手动严格校验及 PR dry-run；`sonar-gate.yml` 独立于 Full Gate，在 main Java/构建相关变更、夜间和手动场景运行，不延迟 Full Gate；`fuzzing.yml` 按其 workflow 配置单独运行。以上工作流是否 required 以仓库 Ruleset 当前配置为准，不由 workflow 文件名推断。

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

CI 版本基线与运行结果分开记录。每次核验 Full Gate、CodeQL 或镜像工作流时，按目标分支的实际 commit SHA 检查最新 run；`IN_PROGRESS`、`QUEUED`、`SKIPPED` 或其他 SHA 上的成功都不能作为当前提交通过证据。Docker Buildx/Bake、发布凭据、GHES 与 self-hosted runner 兼容性仍须按目标环境验证。后续每次升级按 [CI 外部 Actions 版本升级与验收记录](../audit/ci-external-action-upgrade-review-2026-09-30.md) 运行对应回归。

OpenSSF Scorecard 是 main/定时的供应链治理报告，不属于 PR required checks，也不以总分决定合并。其 workflow 失败表示扫描链路本身需要修复；SARIF 中的发现按 [`../standards/open-source-governance.md`](../standards/open-source-governance.md) 分级治理。外部 Action 必须固定 40 位 SHA；季度依赖盘点只生成 artifact 与 Actions Summary，由维护者需要时建立一张人工 PR 并核对 release、变更说明和所需权限。

CodeQL 的 `Analyze (java)` 只有在 `codeql.yml` 已于 main 生效、并确认纯文档 PR 也会创建该检查后，才能加入 ruleset required checks。配置顺序必须是先合 workflow、用代码变更和纯文档 PR 各验证一次，再更新 ruleset；反向操作会使被 `paths-ignore` 跳过的 PR 永久等待。

运行环境约束：

- setup-python/setup-node/setup-go 的 Node 24 运行时要求 GitHub Actions Runner `v2.327.1` 或更高；GitHub-hosted `ubuntu-latest` 满足该要求，self-hosted runner 必须单独核对。
- `actions/upload-artifact@v7` 使用当前 artifact 服务契约；迁移到 GHES 前必须确认 GHES 支持该 major，否则保持独立兼容版本或由平台团队提供替代上传方案。
- Gitleaks `8.30.1` 本轮不盲目更换；已在 Docker `linux/amd64` 用同版 artifact 验证合成 `ghp_...` 正向退出 1、负向退出 0，并通过 PR/Full Gate 安全扫描；继续关注上游规则变化，该样例不代表所有密钥类型。
- CodeQL、Trivy Action、Checkov、发布 Action 和 Sonar 仍按 G7 定期复核，不因本批版本升级自动视为完成。
- `.github/dependabot.yml` 对 Maven、GitHub Actions 和 Docker 设置季度解析但将版本 PR 上限设为 0；仓库级 Dependabot security updates 关闭自动修复 PR，安全告警仍持续发现。`quarterly-dependency-review` 每季度只生成 artifact 与 Actions Summary，维护者按[季度依赖集中治理](./quarterly-dependency-governance.md)在需要实施时创建一张人工 PR。Maven 更新必须同步入库 SBOM；Docker 构建镜像的 JDK 主版本继续人工决策。
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

- **治理基线扩张必须显式审批**：PR 和 Full Gate 将 `direct-config-key-access-baseline.txt`、`test-conventions-baseline.txt` 与 Java contract count 基线对比目标分支；新增/扩大项须在 `docs/governance/baseline-expansion-approvals.json` 登记 owner、理由、复核日期、移除条件，且 JSON 计数必须匹配批准值。精确治理文件变更进入 `ci` 范围，普通 Markdown 不因此触发 CI 专项守卫。

- **feature 分支自己 push 不跑任何 gate** — 开发可频繁推送无成本,门禁压力全在 PR 时
- **直推 main 跳过 pr-gate**(无审查),但 `full-ci-gate` 仍回退回归
- **`concurrency.group + cancel-in-progress`** 全配 — 同分支并发 push / 同 PR 多次推时,旧 run 自动取消省 runner
- **pr-gate 与 full-ci-gate 检查项不完全相同**:见下表(pr-gate 重快速反馈,full-ci-gate 重深度回归 + 安全扫描)
- **main 红线独立于 PR 绿灯**:PR gate 通过只代表候选变更可合入；合入后的 main 只有最新 `full-ci-gate` 通过才可作为发布基线。
- **CodeQL 分层执行**：PR 使用 Java `build-mode: none` 和默认高精度查询缩短 required check；main push 和每周定时运行保留手工全量编译与 `security-extended`，继续覆盖构建生成代码、精确依赖和扩展热点。仓库若引入 Kotlin，必须先恢复构建模式再合入。
- **非测试 job 不准备 Testcontainers**：静态检查、安全扫描和 CodeQL 通过 `cache-testcontainers: false` 跳过容器镜像恢复；单元/集成/E2E 仍保留镜像缓存。
- **门禁结果行统一**:本地 hook 与 CI 统一输出 `状态 | code | gate | group | mode | exit_code | action`；跳过时再输出 `reason`。单步中串行运行多个阻断检查时，每项都必须通过共享 `gate_run` 输出独立结果；具体诊断信息可保留各检查器原有内容。
- **静态门禁失败统一汇总**：PR 将 policy、supply-chain、Java quality 三路并行执行，各路在末尾汇总自身失败，再由稳定的 `static-checks` required context 聚合；Full Gate 仍在单个 `static-checks` 中汇总。checkout、构建环境安装等缺失后无法继续的基础前置仍立即失败。本地 pre-commit/pre-push 执行完所有独立检查后汇总失败；依赖前置产物的步骤在其生成失败时停止该步骤，其他门禁继续执行。
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
| **Maven 范围** | 每个被选分片先用 `install -DskipTests -am` 构建依赖，再只测试本分片模块；PR unit 分片按编译后的类名排除 `*IntegrationTest`，但不排除 `*IT` | 固定分片并发执行；编排长类按清单独立运行并合并 JaCoCo 执行数据后检查覆盖率，目标模块执行 unit + IT |
| **E2E suite** | 不运行，由合入后门禁回退 | 当前 49 个测试类拆为 20 个并发 shard；每个分片自行安装上游模块；恢复长类按方法分片，filesystem 后端独立片显式启用对应配置 |
| **Hadolint / Trivy SBOM 与配置扫描** | ❌ 不跑 | ✅ 跑 |
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

`unit-it-a`、`unit-it-b1` 和 `unit-it-b2` 是 Ruleset 使用的稳定聚合 context。编排和 Console 各拆为主片与集成补充片，Trigger/PROCESS/DISPATCH 拆为单测片与集成片；`unit-it-b2` 还汇总 Worker 与 Java 治理结果。每组按可执行行合并 JaCoCo XML 并维持 80% 差异覆盖率门槛，报告缺失或需要执行的分片被跳过会失败。拆分前基线为第 3611 次运行：编排约 10 分 36 秒、Console 约 10 分 46 秒；拆分后墙钟耗时以实际 CI 为准。

更改 `scripts/ci/run-pr-coverage-integration.sh` 或 `scripts/ci/check-diff-coverage.py` 时，范围探测会启动四个测试范围，确保执行器和覆盖率汇总的变更得到真实 CI 验证；其他不影响业务测试的 CI 改动仍不启动 Maven 单测。

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

编排集成测试的 MyBatis SQL 明细默认由日志级别控制，避免无条件输出导致 CI 日志截断；排查 SQL 时可临时将对应 Mapper 的日志级别设为 `DEBUG`。批日补跑进程恢复 E2E 失败时会额外保存 Console 子进程日志，供定位请求与启动问题。

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
| OpenAPI 路径对齐 | `check-console-openapi-paths.py` | PR 静态策略、Full Gate 静态检查、手动 Staging Gate；每个流程执行一次 |
| Flyway 文件结构与迁移漂移 | `validate-flyway-schema.sh` | PR：database / CI 文件域；缺号、重复版本及已有迁移有效 SQL 改动均阻断，修正使用增量迁移 |
| Flyway 危险 DDL | `check-migration-safety.sh`（Squawk，diff-only） | PR：database / CI 文件域；扫描新增迁移和已有迁移的有效 SQL 改动，纯注释改动不触发历史 DDL 误报 |
| 新增数据库对象注释覆盖 | `check-db-comment-coverage.sh`（diff-only） | PR 与 Full CI：database 变更 |
| 新增平台表文档登记 | `check-new-table-doc-coverage.py`（增量） | PR 与 Full CI：database、docs 变更；新增 `batch` / `archive` 表须登记，已有表目录条目不得删除 |
| 注释语言 | `check-comment-language.py --staged` | 本地 pre-commit 增量预检；暂不阻断 PR / Full CI |
| 文本编码 | `check-utf8-encoding.py --staged` | 本地只检查暂存文件；PR 检查目标分支差异；Full Gate 全仓扫描 |
| 应用及基础设施 locale | `check-infrastructure-utf8.py` | 配置/Java/SDK/CI 变更时核对全量配置矩阵 |
| 模块依赖边界 | `check-dependency-boundaries.py` | 全部（run-full-regression） |
| 编译 + 单元测试 | Maven `test` | 全部 |

`orchestrator-internal-openapi-drift.yml` 另对精选的 Orchestrator 内部 API 做 spec → Controller 路径存在性检查，当前仍为 log-only，不是 required check；解析支持方法级 mapping 显式路径及无参根路径。字段级 request/response schema 仍由 OpenAPI、序列化契约测试和人工复核共同维护。
| 集成测试 (`*IntegrationTest` / 非 E2E `*IT`) | Maven `verify -DskipITs=false` | full-ci-gate 的 Surefire 选测包含这两类；PR unit 分片也可能运行类名为 `*IT` 的测试。归档诊断成本探针 `ArchiveDiagnosticCadenceProbeIT` 按用户要求延后性能评估，需显式选择运行；归档功能集成测试仍保留。`check-integration-test-coverage.py` 只守护含集成测试的主 reactor 模块进入 full-ci verify shard，不核验每个测试实际执行 |
| E2E 套件 (`*E2eIT`) | Maven `test` `-pl batch-e2e-tests` | full-ci-gate |
| Sim 配置包 Excel schema | `python3 scripts/sim/fix-fixture-xlsx.py --check` | 本地：权威 Java schema、四份工作簿或生成/修复脚本变更；PR：Java/test/script/CI 域；Full Gate：全量 |

> 约定/架构守护（`*ArchTest`、`*ConventionTest`，如 `RepositoryMapReturnConventionTest`、`PositionalArgsConventionTest`）
> 由 PR / Full / Staging 的 `java-governance` job 独立执行，业务 unit/IT 显式排除这两类后缀。
> `check-java-governance-test-coverage.py --verify-reports` 要求每个源码类都产生 Surefire XML，零用例或漏跑会阻断。
> E2E 静态与运行完整性守卫的正反例由 `PR_E2E_SHARD_COVERAGE_TEST` 在 Java/CI 域触发，`FULL_E2E_SHARD_COVERAGE_TEST` 在 Full Gate 执行；方法分片还核对每个指定方法的报告与跳过状态。
> `MapperStatusWriteConventionTest` 扫描各应用模块的 Mapper XML，禁止普通 `UPDATE SET` 与 `ON CONFLICT ... DO UPDATE SET` 固定写入状态字面量；使用 `bash scripts/ci/run-java-governance-tests.sh` 执行。它不审查 `WHERE` / `SELECT` 的业务状态集合，共享生命周期还需 Mapper 契约测试，专项业务筛选由专项测试维护。
> `ServiceEntryGuardArchTest` 按入口语义验证核心服务入口 fail-safe 行为；Orchestrator `ControllerPersistenceBoundaryArchTest` 与 Console `LayerBoundaryArchTest` 分别禁止 Controller/Web 直连 Mapper/Repository；Orchestrator 与 Console 的 `EntityModelNamingArchTest` 强制领域 `entity` 包只含 `XxxEntity`；`EntityApiBoundaryArchTest` 强制 Orchestrator 与 Console HTTP 方法签名不暴露 Entity，零豁免。Controller/Service/Entity 分层规范见 `docs/coding-conventions.md` §4.1.1、§6.3、§8.2.10。执行入口：`bash scripts/ci/run-java-governance-tests.sh`。
> `PositionalArgsConventionTest` 还守护已治理 Query 不回退为多 `null` 位置构造，并确保状态型参数对象不重新暴露通用 Builder 或公共全参构造器；语义上是否应纳入受控工厂集合仍由代码审查决定。
> 同一测试还按当前 PR 的变更行检查生产 Java 方法实参与 `return` 的三层以上嵌套调用；短 fluent receiver、lambda 函数体、测试代码和未变更历史代码排除。
> 这类测试型守卫的登记入口是 [约定约束与漂移防护总账](../standards/convention-drift-guard-index.md) 的守卫矩阵（`check-*` / `validate-*` 脚本另由 [scripts/ci/README.md](../../scripts/ci/README.md) 登记）。
> Python / Shell / 配置/契约守卫在 PR 的 `static-policy`、Full/Staging 的 `static-checks` 中独立命名执行，不混入 Maven unit/IT；
> PR 的 SBOM/Trivy 与 PMD/Spotless 分别由 `static-supply-chain`、`static-java-quality` 并发执行，稳定的 `static-checks` 只聚合结果。
> `test-data/sim/**/*.xlsx` 按 test 域路由；权威 Java schema 变更也会执行配置包校验，schema 漂移会在 PR 阶段阻断，不再等 nightly Sim 才暴露。
> 本地 pre-commit 只核对治理测试源码清单；pre-push 在 Java、POM 或相关 CI 路由变化时调用
> `run-java-governance-tests.sh` 真实执行同一组测试，在线 workflow 也复用该入口。

### 提醒项（失败只通知，不阻断流水线）

| 检查项 | 工具 | 触发流水线 | 说明 |
|---|---|---|---|
| PMD 代码规约 | `maven-pmd-plugin` | 全部（run-full-regression） | 对齐编码规约：Mapper公共方法≥5、Service公共接口≥6、其它方法≥7；构造器按既有规则豁免，边界由真实PMD夹具守护 |
| Spotless 代码格式 | `spotless-maven-plugin` | 全部（run-full-regression） | Palantir Java Format 2.92.0（Google 风格兼容、120 列） |
| 覆盖率门禁 | JaCoCo `jacoco:check` | 全部（run-full-regression） | 行覆盖率 ≥ 60%，初始阈值，后续提升 |
| Secret 扫描 | `security-scan.sh --mode=secret` | pr-gate、full-ci-gate | 打包独立扫描模块时运行其单测，再扫描密钥泄漏；不等于 SAST 扫描 |
| 依赖漏洞扫描 | Trivy `sbom`（CRITICAL/HIGH） | PR 供应链门禁（依赖/镜像/部署输入及安全工作流变更）；Full Gate | 扫描当前提交生成的 CycloneDX 依赖清单；不需要为 Trivy 单独编译安装整个 Maven reactor。容器/Helm 配置改由专用 `trivy config` 扫描器检查，避免文件系统扫描器解析 Maven POM 并访问远端依赖仓库。OWASP dependency-check 的 NVD 全量下载在 CI 上过慢且不拦门禁，2026-08 起由 Trivy 覆盖；`--mode=deps` 保留本地按需使用 |
| Dockerfile lint | Hadolint | full-ci-gate | `deploy/docker/Dockerfile.app`、`deploy/docker/Dockerfile.ops-toolbox` |
| 文件系统安全扫描 | Trivy `sbom` + `config` | PR Gate（供应链或 PR/Full Gate 安全工作流变更）；Full Gate | 漏洞扫描读取当前提交的 CycloneDX BOM 并阻断 CRITICAL/HIGH；IaC 配置扫描使用 `trivy config`，同样阻断 CRITICAL/HIGH。漏洞豁免读取带治理元数据的 `.trivyignore`，配置误报仅允许在 `.trivyignore.yaml` 中按规则和路径精确豁免 |
| K8s manifest 安全 | Checkov | full-ci-gate | Helm chart 安全基线 |

> **提醒项升阻断策略**：优先把统一执行模式从 `report` 改为 `enforce`，并同步删除 `soft-gates.json` 中的临时登记；工具或 job 级适配仍使用 `continue-on-error` 时，也必须先改为真实失败退出再移除适配。阈值稳定后逐步收紧，不建议一次全部升级。

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

PR 所有单元分片均设置 `-DskipITs=true`；集成和 E2E 由 Full Gate 回退。详细证据与回退条件见 [CI 门禁耗时分析与优化记录](../verifications/ci-gate-runtime-optimization-2026-10-07.md)。

---

## 本地 Git Hook 门禁

本地 hook 只承担**快速预检、完整诊断和提交前防低级漂移**，不替代 PR / full-ci / staging / sim 验证。设计原则：

Shell Linux 兼容守卫在 PR / Full 默认扫描工作树中现存的已跟踪及未跟踪 `.sh` 文件，
避免路径迁移后读取仍在索引中的已删除文件而中断；本地显式文件检查采用相同边界。
此过滤不改变兼容规则或未跟踪文件覆盖，回归入口见 [CI 脚本说明](../../scripts/ci/README.md)。

- `pre-commit` 按暂存文件域路由，尽量只扫暂存命中的文件；CI 仍保留全量扫描。
- `pre-push` 面向分支级轻量契约，允许使用 `origin/main...HEAD` 的增量基线。
- pre-push 的 Java 全限定类型检查使用提交版源码识别实际代码，注释、普通字符串和文本块不视为类型引用；扫描失败按门禁失败处理。
- 两个 hook 复用 `detect-change-scope.py`：pre-commit 使用 `--staged`，pre-push 使用 `--base/--head`；相同 scope 名称作为按需路由依据，不再独立维护第二套域分类。
- hook 统一使用 `run-gate.sh` / `gate-result.sh` 的 `enforce`、`report`、`ignore` 控制；分组或单门禁覆盖只影响本地 hook，不得用于放宽 CI required checks。
- 一个检查失败不阻断其他独立检查；pre-push 即使静态检查失败也继续运行适用的 clean compile，并在结尾报告失败总数与诊断项。
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
| 配置 / Java / SDK 变更 | `check-infrastructure-utf8.py` | **全量矩阵**：核对 8 个应用服务、基础服务、Kafka HA、测试服务及 Sim SFTP/MockServer、Helm、Dockerfile、Testcontainers 和新建 PostgreSQL 编码参数 |
| `pom.xml` / `*/pom.xml` 变更 | Maven 模块依赖边界 | 全局依赖图检查 |
| `helm/*` 变更 | Helm 环境变量同步、Helm 生产 overlay 安全 | 全局 Helm / 配置一致性检查 |
| 数据库 / 脚本 / 文档 / 配置 / CI 变更 | 生产容量治理自动化 | 校验生产容量巡检脚本、只读 SQL、runbook、索引和 workflow 入口同步 |
| tracked 源码/脚本/配置变更 | Lean LOC 快照重生成与校验 | 基于暂存树生成 `docs/stats/loc-current-lean.md` |
| 所有提交 | 仓库卫生 | 全局仓库约束 |

pre-commit 分组为 `hygiene`、`quality`、`docs`、`config`、`supply-chain`、`governance`；pre-push 使用 `local-pre-push`。模式控制示例：`BATCH_GATE_GROUP_MODES='quality=report,docs=ignore' git commit`，或 `BATCH_GATE_GROUP_MODES='local-pre-push=report' git push`。`report` 会执行并记录真实退出码但不阻断，`ignore` 不执行；默认仍为 `enforce`。

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
| `check-hardcoded-runtime-config.sh` | 按域触发的全量：运行配置、脚本、容器、测试基础设施变更时运行；检查 BE 验收脚本的平台服务端口以及观测脚本的 Console/Orchestrator 地址必须来自公共环境配置 |
| `check-test-config-sources.py` | PR、Full Gate 与本地 pre-push；确保各模块导入共享测试配置且不在 `application-test.yml` 重复定义共享项；不拦截 Java 测试的场景覆盖值 |
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
make ops-inspect-stuck-outbox # 只读检查长期停滞 outbox
make ops-inspect-dead-letters  # 只读检查死信；人工重放经 Console 审批
make ops-heal-drain     # 修复 drain 超时
make ops-heal-retry     # 修复待重试任务
make ops-heal-partitions# 修复分区不均衡
make ops-compensate     # 触发补偿
```

---

## 依赖更新配置

仓库同时保留 `.github/renovate.json` 与 `.github/dependabot.yml` 配置。配置文件存在不等于对应 GitHub App 已启用；实际 PR 行为还取决于组织/仓库安装状态。

| 配置 | 声明的行为 |
|---|---|
| Renovate (`.github/renovate.json`) | Maven patch 与 GitHub Actions 规则声明 automerge；Maven minor/major 不自动合并；Spring Boot 父 POM 不自动合并并配置 reviewer |
| Dependabot (`.github/dependabot.yml`) | Maven、GitHub Actions、Docker 更新周期为季度，版本 PR 数上限为 0；安全更新由仓库级设置控制 |
| `quarterly-dependency-review` | 生成依赖盘点 artifact 与 Actions Summary，不自动创建 PR |

不得仅凭配置推断 Renovate App 正在运行或 PR 会自动合并；执行状态应通过最近一次 bot PR/run 和仓库 App 设置确认。季度依赖盘点的操作步骤见[季度依赖集中治理](./quarterly-dependency-governance.md)。

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
- required contexts 必须与当前 Ruleset 完全一致；merge queue 目前是可选协作策略，只有 Ruleset 实际启用后，才要求相关 workflow 的 `merge_group` 触发覆盖完整。

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

- PR / Full Gate 的 Java shard 使用 `check-diff-coverage.py` 校验本次变更的可执行行覆盖率，最低 80%；存量 Bundle 25% 和核心类 80% 棘轮继续保留。PR 的编排、调度、Worker 和 Console 运行已验证的集成场景，编排、Trigger/PROCESS/DISPATCH 和 Console 的并行片分别在 `unit-it-a`、`unit-it-b1` 和 `unit-it-b2` 合并覆盖率；Full Gate 执行完整集成测试。
- 每周及手动 Full Gate 使用 `run-critical-mutation.sh` 对 `FileStateMachine` 和 `DefaultLifecycleEventMapper` 运行 PIT，阈值为变异杀死率 70%、覆盖率 80%。范围保持小而稳定，不做全仓变异测试。
- PR 和手动 Staging Gate 在各测试任务结束后汇总当前 Surefire/Failsafe XML、任务状态和分片结果，产物保留 14 天；Full Gate 的 `quality-trend` 同样汇总并读取最近 30 次运行的成功率和已完成运行的平均耗时，产物保留 90 天。成功任务缺少规定报告时汇总失败；失败或跳过的任务显示原状态，不把缺失报告当成成功。E2E matrix 成功时逐片核对报告。报告按分片列出测试累计耗时；该耗时不是作业墙钟或 runner 排队时间。Java XML 汇总不包含单独工作流中的多语言 SDK、前端 Vitest/Playwright 或 Python/Shell 门禁测试，不能作为这些测试的通过证明。
- `sdk-contract-parity` 的 `SDK tests / consolidated report` 单独汇总 Java、Python、TypeScript、Go、Rust 各版本的契约测试，以及作为独立执行项的五语言真实传输门禁；原始日志和真实退出码随各自 artifact 保存 14 天。测试任务成功却缺少日志会使汇总失败；未运行和跳过不会记为成功。此汇总记录执行项状态，不声称已统计每个语言的测试用例数量；真实传输与静态契约结果分列。
- 报告/忽略模式统一登记在 `docs/governance/soft-gates.json`；每项必须有分组、模式、控制方式、owner、当前基线、升级期限和目标。CodeQL 上传的有限重试属于传输容错，不计为软门禁。

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
| PR CodeQL | 18 | 8:19 | 9:58 | P50 2:30-4:30 |
| full-ci-gate | 20 | 8:26 | 11:44 | P50 4:30-6:00 |
| staging-gate | 未纳入本轮样本 | — | — | 六片全量 E2E 4:00-5:30；Java 治理组并发，不进入关键路径 |

### Job 级分布

**pr-gate（优化前）**

- 最近一次首个 runner 等待 47 秒；`unit-it-b2` 482 秒，其中 Maven 测试 424 秒。
- 优化后 B2 拆为 Worker / Console 并行，叶子模块只启动所属分片；required context 名仍为 `unit-it-b2`。
- PR #1181 的单体 `static-checks` 约 5:41，重型供应链和 Java 静态质量步骤与 policy 串行；现已拆为三路叶子 job，并保留 `static-checks` 聚合名称。连续 10 次在线样本后再填写实际 P50/P90。

**full-ci-gate（优化前）**

- 最近一次最长 Unit / E2E job 分别为 475 / 477 秒。
- 优化后 Unit B2 拆分，四个 Java shard 不再通过 `-am` 重复执行上游测试；E2E 从 4 片改为实测 LPT 六片，每片测试体基线 148-179 秒。
- 静态守卫与 unit/IT 分离且去掉 action-pinning 重复执行；Java Arch/Convention 守卫单独并发执行，统一入口本地基线约 68 秒。

**CodeQL（优化前）**

- 最近 PR 运行约 9:02：环境 44 秒、编译 289 秒、分析 136 秒、上传 7 秒。
- PR 改为 Java no-build + 默认高精度查询，且不再准备 Maven/JDK 构建环境；main/定时/手工仍使用 manual build + `security-extended`，在线连续 10 次运行后更新本节实测数据。

完整样本、运行链接和回退标准见 [CI 门禁耗时分析与优化记录](../verifications/ci-gate-runtime-optimization-2026-10-07.md)。

---

## 关键文件索引

- [全部工作流](../../.github/workflows/README.md)：逐项介绍触发条件、检查目的、结果与写入边界，包括 SDK、定时验证、发布和供应链流程。
- [共享 Actions](../../.github/actions/README.md)：构建环境、Python 依赖及变更范围复用步骤。
- [CI 脚本与门禁](../../scripts/ci/README.md)：执行命令、判定正反例、失败处理与配套 SQL。
- [CI 历史基线](../../scripts/ci/baselines/README.md)：内联 SQL、Java 抑制与高风险命令预算，只能按真实差异和评审维护。
- [本地钩子](../../.githooks/README.md)：pre-commit/pre-push 使用方式；[脚本目录](../../scripts/README.md)按任务寻找本地与运维入口。
- [PMD 规则](../../config/quality/pmd-ruleset.xml)及根 [pom.xml](../../pom.xml)：Java 静态质量与测试插件配置。质量目录中的 XML 通过统一范围探测器标记为 `java/config/ci`，规则独立变更也会触发 PR 的 Java 静态质量检查；README 变更仍按文档处理。

### 文档、脚本与工作流索引防漂移

| 校验 | PR | Full CI | 本地 |
|---|---|---|---|
| docs/ 当前及归档 README 的逐层跳转与用途、当前内部链接 | docs/ci 变更执行 DOCS_STRUCTURE | static-checks 执行 | pre-commit 按 docs 域执行 |
| 文档生命周期、完整日期与归档分类 | docs/ci 变更执行 DOC_TIMESTAMP_POLICY | static-checks 执行 | pre-commit 按 docs 域执行 |
| 自动化脚本头、说明语言、脚本/SQL/基线索引与 workflow/action 索引 | scripts/database/ci 域执行 SCRIPT_GOVERNANCE；仓库其他位置的 Shell 也分类到 scripts | static-checks 执行 | pre-commit 按共享分类结果执行 |
| 上述守卫的正反例与路径分类测试 | ci 域变更时由 CI 质量测试组执行 | CI 质量测试组执行 | pre-commit 的脚本测试组执行 |

脚本头固定格式及范围见 [编码规约 §19.5](../coding-conventions.md#195-自动化脚本的头注释与说明)。索引校验要求有真实跳转和用途、目标存在、子目录有上级入口；不能证明用途文字或脚本命令在目标环境已验收。工作流和本地门禁的接入检查属于配置事实，远端 run 仍须针对目标 SHA 单独读取。

### CodeQL 构建模式

- PR：`build-mode: none`，使用 CodeQL 默认高精度查询并上传 SARIF，作为 `Analyze (java)` 检查。
- main push、schedule：`build-mode: manual`，执行跳过测试的全 reactor 编译，并使用 `security-extended` 分析。
- SARIF 由 `github/codeql-action/analyze` 原生上传。同一 job 内不得针对同一 tool/category 再调用 `upload-sarif` 重试；GitHub 会将后续调用判定为重复上传。上传故障通过重新运行 job 处理。
- 无构建模式只适用于当前纯 Java 仓库；引入 Kotlin 或发现生成源码漏析时必须恢复 PR 手工构建。
- PR 与 main 告警差异需要人工解释，不能仅因 PR 更快就认定覆盖等价。

### SonarQube CI

`.github/workflows/sonar-gate.yml` 是独立工作流，不是 Full Gate 的 job 或 required check。main Java/构建/Sonar 工具变更会单独触发它；此外每日 18:23 UTC（北京时间次日 02:23）按最近 24 小时相关变更条件运行，也支持手动触发。该工作流独立执行 `clean test` 和 JaCoCo 报告，不消费 Full Gate artifact；因此它可能在 Full Gate 之后完成或失败，但不延迟 Full Gate 结果。Community Build 仅支持 main 分析，非 main 手动运行会明确失败；夜间仅在最近 24 小时有 Java、Maven、Sonar 扫描脚本/辅助脚本或该 workflow 改动时继续。无需外部 Sonar 主机、GitHub Sonar secret 或管理员 token：工作流在 runner 上启动固定 digest 的 SonarQube Community Build 容器（6 GiB、3 CPU），执行 Sonar Way Quality Gate，并将新代码基线设为滚动 30 天；最后删除容器。扫描报告作为短期 workflow artifact 保留 7 天。

容器和数据库不跨任务持久化，因此 issue 状态、分析历史和项目配置不会保留；质量门禁每次按完整 Git 历史计算最近 30 天新代码，不依赖 Sonar 历史。Sonar 工作流独立构建测试，不消费 Full Gate artifact，也不接受 PR artifact。该方案不对 PR 执行 Sonar 分支分析，也不提供跨提交的 issue 生命周期/趋势。Java PR 的 Sonar 变更行审阅仍使用本地增量扫描。不得将临时扫描结果描述成持久 Sonar 项目状态。Sonar 不替代现有 PMD、Spotless、SpotBugs、依赖扫描和测试门禁；是否作为 required check 仍以仓库 Ruleset 为准。

### 本次 Workflow 切换的人工跳过原因合同

`check-openapi-breaking.sh` 依 ADR-048 和整合计划 I6/R35 精确登记 `POST /api/console/workflow-runs/{id}/skip-node` 新增必填 query `reason`。这是已批准的真实收紧，前后端必须同步部署；不是工具误报。登记只匹配该方法、路径、位置、参数名和差异种类，其他新增必填参数仍失败。定向正反例入口：`python3 -m unittest scripts/ci/tests/test_openapi_skip_reason_cutover.py`。

2026-10-31 用户批准本次切换保留请求限制并接受不兼容：作业定义创建和更新的 `priority` 为 1–9；出箱投递查询 `eventType` 最长 64、`targetTopic` 最长 256，出箱重试查询 `eventType` 最长 64；Workflow 定义创建和完整更新的节点 `nodeName` 必填。其中部分是补录既有服务或数据库约束，其他会收紧旧请求。门禁只登记这 9 条具体差异，前后端需按同一契约同步交付；旧客户端须先适配。合入 main 后，这些差异不再属于对 main 的增量，可在后续门禁维护中移除登记。使用 CI 固定版本 oasdiff 运行 `python3 -m unittest scripts/ci/tests/test_openapi_approved_request_constraints.py`，验证授权项与相邻未授权项。
