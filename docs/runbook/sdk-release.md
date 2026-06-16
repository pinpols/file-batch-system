# 多语言 BYO SDK 发布 Runbook

> 适用范围:`sdk/{typescript,python,go,rust}` + `sdk/java`(`batch-worker-sdk`,ADR-035 租户自托管 SDK)的**发布校验 + 真发布**流程。
> 工作流:[`.github/workflows/sdk-release-validation.yml`](../../.github/workflows/sdk-release-validation.yml)。
> per-PR 的源码层防漂移见 [`sdk-contract-parity.yml`](../../.github/workflows/sdk-contract-parity.yml) 与 [`docs/sdk/byo-conformance-contract.md`](../sdk/byo-conformance-contract.md);本 runbook 只管「发布」。

## 0. 两条工作流的分工(别混)

| 工作流 | 守什么 | 何时跑 |
|---|---|---|
| `sdk-contract-parity.yml` | **源码层契约不漂移**(5 语言 conformance fixtures + shared-constants parity) | 每个改 SDK 的 PR |
| `sdk-release-validation.yml`(本文) | **打出来的发布产物真能装、真能用** + 协议 spec 无破坏性变更 + 严格门禁发布 | `release`(published)/ 手动 `workflow_dispatch`(默认 dry-run) |

源码全绿 ≠ 包装得上:缺 build 步、入口写错、依赖漏声明,只有装"打出来的包"才挖得出。这是本工作流的核心价值。

## 1. 工作流阶段

```
conformance-gate (5 lang, fail-fast)  ─┐
breaking-change-gate (oasdiff vs prev)─┴─► package + smoke (TS/Py/Java/Go/Rust)
                                              └─► supply-chain (SBOM + changelog, best-effort)
                                                    └─► publish-* (严格门禁,默认不跑)
```

1. **conformance-gate**:复跑 5 语言 contract job(Java/Python/TS/Go/Rust),任一红即拒。
2. **breaking-change-gate**:对 SDK 面向的协议 spec `docs/api/orchestrator-internal.openapi.yaml`,用 `oasdiff breaking --fail-on ERR` 比**上一个 release tag**。删端点 / 删必填 / 改类型即拒(已发出去的旧 SDK 会运行时崩)。首发(无上个 tag)自动跳过。
3. **build + package + smoke install**(最关键):
   - **TypeScript**:`npm run build`(tsc 编译 `src/*.ts` → `dist/*.js + *.d.ts`)→ `npm pack` 出 tarball → 新临时目录 `npm i <tarball>` + 跑最小脚本 `classifyHttp(500,1)`。
     > ⚠️ TS SDK **必须编译后发布**:Node 拒绝对 `node_modules` 下的 `.ts` 做 type-stripping,直接发原始 `.ts` 装上后 `import` 即 `ERR_UNSUPPORTED_NODE_MODULES_TYPE_STRIPPING`。`package.json` 的 `exports`/`main`/`types` 指向 `dist/`,`files` 只含 `dist`。
   - **Python**:`python -m build` 出 sdist + wheel → 全新 venv `pip install <wheel>` → `import batch_worker_sdk` + 引用公共 SPI(`SdkAbstractTaskHandler` / `SdkTaskResult`)。
   - **Java**:`mvn -pl sdk/java -am install` 装到本地 m2 → 独立消费 pom 引 `com.example.batch:batch-worker-sdk:${revision}` 编译最小 handler,证明发布 jar 的入口 + 依赖闭包能被外部工程解析。
   - **Go**:tag-based 无产物 → `go vet` + `go build` + `go list -m`(模块可解析)。
   - **Rust**:`cargo package --no-verify` 产 `.crate`,校验打包元数据完整。
4. **supply-chain(best-effort,失败不挂)**:syft 生成 SBOM(SPDX-JSON,上传 artifact);oasdiff 对上个 release 生成 protocol changelog(上传 artifact)。
   - **TODO**:SLSA provenance attestation(`actions/attest-build-provenance`)待真实发布开通后接入;npm 侧已在 publish 用 `--provenance`(sigstore OIDC)。
5. **publish(严格门禁,默认不跑)**:见 §2。

## 2. 发布门禁(安全第一)

publish-* 每个 job 三重锁,任一不满足都不发:

1. **`if:` 仅 `release` 事件**:`github.event_name == 'release' && !(github.event_name == 'workflow_dispatch' && inputs.dry-run)`。
   - `workflow_dispatch` 触发时第一子句即 false → **所有 publish job 被 skip**。手动触发**永远只跑校验,不发布**。
   - 真发布**只能**经 GitHub Release(published)事件触发。
2. **`environment: release-publish`**:在仓库 Settings → Environments 配 required reviewers / wait timer / 分支限制,真发布前**人工审批**。
3. **token 全走 `secrets.*`,本文件零明文**:npm / PyPI 优先用 OIDC(`id-token: write` + provenance / Trusted Publishing),无需明文 token;crates.io / Maven 用 `secrets.*`。

## 3. Ops 真发布步骤

### 3.1 一次性:配 secrets 与 environment

仓库 Settings → Environments → 新建 **`release-publish`**:
- 勾 **Required reviewers**(发布审批人);
- 可选 wait timer;
- 限制可部署分支为 `main` / tag。

在 `release-publish` environment(或 repo)secrets 里按需配:

| Registry | 凭据 | 说明 |
|---|---|---|
| **npm** | (OIDC) | 优先用 npm provenance / OIDC,无需 token。若用 token:`NPM_TOKEN`(automation token) |
| **PyPI** | (OIDC) | 优先 [Trusted Publishing](https://docs.pypi.org/trusted-publishers/),在 PyPI 项目侧绑本仓 + workflow,无需 token |
| **crates.io** | `CARGO_REGISTRY_TOKEN` | crates.io API token |
| **Maven Central** | `MAVEN_USERNAME` / `MAVEN_PASSWORD` / `MAVEN_GPG_PASSPHRASE` | Central Portal 凭据 + GPG 签名口令(注:`sdk/java/pom.xml` 当前未配 `maven-gpg-plugin` / `central-publishing-plugin`,真发 Central 前需补 release profile;见 §4) |
| **Go** | 无 | tag-based,无 registry 推送 |

### 3.2 发布一个版本

1. 确认 `main` 上 SDK 代码 + 版本号已就绪(各语言版本号见 §4)。
2. 在 GitHub 建 **Release**(打 tag,`Publish release`)。
3. `release: published` 触发本工作流:
   - gate + package + smoke 全绿后,publish-* job 进入 `release-publish` environment;
   - 审批人在 Actions 页面 **Review deployments** → Approve;
   - 各 registry 依次发布。
4. 发布后核对:npm / PyPI / crates.io 页面出现新版本;Go 下游 `go get ...@<tag>` 可拉取。

### 3.3 只想验证不发布(日常 / 演练)

Actions → `sdk-release-validation` → **Run workflow**(`workflow_dispatch`),`dry-run` 保持默认 `true`。
跑 gate + package + smoke + supply-chain,**不触发任何 publish**(publish job 因 `if:` 第一子句被 skip)。

## 4. 各语言版本号 + 打包元数据现状

| 语言 | 版本源 | 打包命令 | 备注 |
|---|---|---|---|
| TypeScript | `sdk/typescript/package.json` `version`(`1.1.0`) | `npm run build && npm pack` | `exports`/`main`/`types` → `dist/`;`prepack` 自动 tsc 编译;`publishConfig.access=restricted` |
| Python | `sdk/python/src/batch_worker_sdk/_version.py`(`0.5.0a0`,hatch 读) | `python -m build` | sdist + wheel;依赖 httpx/pydantic/aiokafka |
| Rust | `sdk/rust/Cargo.toml` `version`(`1.1.0`) | `cargo package` | 默认 zero-dep;补了 `repository`/`readme`/`keywords`/`categories` |
| Go | git tag | (无) | module `github.com/pinpols/file-batch-system/batch-worker-sdk-go` |
| Java | 根 pom `${revision}` | `mvn -pl sdk/java -am package` | artifactId `batch-worker-sdk`;**Central 发布 profile(gpg + central plugin)待补**,当前 `deploy` 走默认 distributionManagement |

> 发布前版本号对齐:各语言版本号目前**各自独立**,非强制与根 pom `${revision}` 同步(SDK 是松耦合的租户产物)。发版时按各语言自身的 SemVer 推进。

## 5. 本地自验(发版前 smoke)

```bash
# TypeScript:build + pack + 干净目录装 tarball 跑最小脚本
cd sdk/typescript && npm install && npm run build && npm pack --pack-destination /tmp/pkg
# Python(需 3.12):build + venv 装 wheel + import
cd sdk/python && python3.12 -m build && python3.12 -m venv /tmp/v && /tmp/v/bin/pip install dist/*.whl
# Go(本机需 export GOROOT=/usr/local/opt/go/libexec)
cd sdk/go && go vet ./... && go build ./...
# Rust / Java 交 CI(本机常缺 cargo / JDK25 慢)
```
