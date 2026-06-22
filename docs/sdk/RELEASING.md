# SDK 发布手册(RELEASING)

多语言 BYO Worker SDK(Java / Python / TypeScript / Go / Rust)的对外发布流程、所需凭据、以及**首次发布前必须替换的占位命名**。

> 现状:发布**管线已搭好**(`.github/workflows/sdk-release-validation.yml` 全 5 语言 conformance gate → breaking-change gate → package+smoke → supply-chain → gated publish;`sdk-python-publish.yml` 单独的 PyPI 通道)。但代码里命名空间全是 `com.example.*` / `@batch/*` 占位,**registry 不接受占位名**;且各 registry 凭据需仓库管理员配 secrets。本文件列清"从占位到真发"的待办。

---

## 0. 一次性前置(首次发布前)

### 0.1 替换占位命名空间

| 语言 | 占位 | 改成(示例) | 位置 |
|---|---|---|---|
| Java | `com.example.batch`(groupId) | `io.yourorg.batch` | 根 `pom.xml` `<groupId>`(级联所有模块);Central 拒绝 `com.example.*` |
| Python | `batch-worker-sdk`(可能已可用) | 在 PyPI 查重名,必要时 `yourorg-batch-worker-sdk` | `sdk/python/pyproject.toml` `[project].name` |
| TypeScript | `@batch/worker-sdk` | `@yourorg/batch-worker-sdk` | `sdk/typescript/package.json` `name` |
| Rust | `batch-worker-sdk` | 在 crates.io 查重名 | `sdk/rust/Cargo.toml` `[package].name` |
| Go | `github.com/pinpols/file-batch-system/...` | 你的仓库实际路径 | `sdk/go/go.mod` module 路径 |

同时把 `sdk/java/pom.xml` 里发布元数据占位(`<url>` / `<scm>` / `<developers>` 的 `your-org`)改成真实值——**Maven Central 强制校验**这些字段。

### 0.2 注册 registry 账号 + 命名空间所有权

- **Maven Central**:在 [Sonatype Central Portal](https://central.sonatype.com/) 注册并**验证 namespace 所有权**(`io.yourorg` 需 DNS TXT 或 GitHub 验证)。生成 Portal token(user token)。
- **PyPI**:推荐 **Trusted Publishing(OIDC)**——在 PyPI 项目 Settings → Publishing 里把本仓库 + workflow(`sdk-release-validation.yml` 的 `publish-pypi` job / `sdk-python-publish.yml`)加为可信发布者,**无需 token**。
- **npm**:创建 org `@yourorg`,生成 **automation** access token(可发 scoped 包)。
- **crates.io**:登录获取 API token(`cargo login`)。
- **Go**:无 registry——发布 = 打 git tag,`proxy.golang.org` 首次 `go get` 自动抓取。

### 0.3 配 GitHub secrets + environment

在仓库 **Settings → Secrets and variables → Actions** 配:

| Secret | 用途 |
|---|---|
| `MAVEN_USERNAME` / `MAVEN_PASSWORD` | Central Portal token(user/pass 形式),写入 CI 的 `~/.m2/settings.xml` server[id=central] |
| `MAVEN_GPG_PRIVATE_KEY` | GPG 私钥(ASCII-armored),CI `gpg --import`;Central 要求签名 |
| `MAVEN_GPG_PASSPHRASE` | GPG passphrase,maven-gpg-plugin loopback 读取 |
| `NPM_TOKEN` | npm automation token(`publish-npm` 用;`--provenance` 走 OIDC 无需额外配) |
| `CARGO_REGISTRY_TOKEN` | crates.io token(`publish-crates` 用) |
| PyPI | 用 Trusted Publishing(OIDC),**无 secret** |

在 **Settings → Environments** 建 **`release-publish`** environment:加 required reviewers / wait timer / 限制可部署分支——所有 `publish-*` job 都 `environment: release-publish`,真发前**强制人工审批**。

---

## 1. 发布一次(正常流程)

### 1.1 版本号

各语言独立版本,源码各自维护:
- Java:根 `pom.xml` `<revision>`(去掉 `-SNAPSHOT`)。
- Python:`sdk/python/pyproject.toml`(hatch 动态版本,见 `_version.py`)。
- TypeScript:`sdk/typescript/package.json` `version`。
- Rust:`sdk/rust/Cargo.toml` `version`。
- Go:由 git tag 决定。

遵循 SemVer。**协议破坏性变更**(删端点 / 删必填 / 改类型)必须走主版本 + 弃用流程——`sdk-release-validation.yml` 的 `breaking-change-gate` 会对上一个 release tag 跑 `oasdiff --fail-on ERR`,有 ERR 拒绝发布。

### 1.2 触发

发布管线由 **GitHub Release published** 事件触发(`sdk-release-validation.yml`):

1. 在仓库建一个 Release(打 tag,如 `sdk-v1.2.0` 或语言专属 `sdk-python-v1.2.0`)。
2. workflow 跑:conformance gate(5 语言)→ breaking-change gate → package+smoke(装"打出来的产物"跑最小 handler)→ supply-chain(SBOM + changelog)→ **publish-\*(gated)**。
3. `publish-*` job 在 `release-publish` environment 审批通过后,各自发到 registry。

**演练(不真发)**:`workflow_dispatch` 手动触发,`dry-run` 默认 `true`——只跑校验 + package/smoke,所有 publish job 被 skip。验证管线绿了再建 Release 真发。

Python 也可走独立的 `sdk-python-publish.yml`(tag `sdk-python-v*.*.*` 触发;`workflow_dispatch` + `dry-run` 上传 TestPyPI)。

---

## 2. 各语言机制速查

| 语言 | 产物 | 发布机制 | gated job |
|---|---|---|---|
| **Java** | jar + sources + javadoc + 签名 | `mvn -Prelease deploy` → central-publishing-maven-plugin 上传 Central(`autoPublish=false`,控制台人工确认) | `publish-maven` |
| **Python** | wheel + sdist | `gh-action-pypi-publish`(Trusted Publishing/OIDC) | `publish-pypi` / `sdk-python-publish.yml` |
| **TypeScript** | npm tarball | `npm publish --provenance --access restricted` | `publish-npm` |
| **Rust** | `.crate` | `cargo publish` | `publish-crates` |
| **Go** | 无(tag-based) | 打 git tag,proxy 自动抓 | `publish-go`(仅留痕) |

### Java 细节(`-Prelease` profile)

`sdk/java/pom.xml` 的 `release` profile(默认构建不激活)接入:
- **flatten-maven-plugin** 覆写为 `ossrh` 模式——产出去父化(父 pom `com.example.batch:batch-platform` 不在 Central)、含 Central 必填元数据、剥掉 test 依赖的**独立 pom**。
- **maven-source-plugin** / **maven-javadoc-plugin**——Central 必需的 sources + javadoc jar(javadoc 关 doclint、不因 warning 挂)。
- **maven-gpg-plugin**——`verify` 阶段签名,passphrase 经 `MAVEN_GPG_PASSPHRASE`。
- **central-publishing-maven-plugin**——`publishingServerId=central`,`autoPublish=false`(上传后留控制台人工确认,避免误发不可撤)。

本地验证(不签名):
```bash
mvn -pl sdk/java -am -DskipTests -Prelease -Dgpg.skip=true package
# 产出 target/*-sources.jar、*-javadoc.jar,以及去父化的 .flattened-pom.xml
```

> ⚠ 真发前务必先做 §0.1:把根 `<groupId>` 从 `com.example.batch` 改成自有已验证命名空间,否则 Central 上传会被拒。

---

## 3. 发布后

- **验证可装**:`sdk-release-validation.yml` 的 package+smoke 已在干净环境装过产物;发布后再 `pip install` / `npm i` / `cargo add` / `go get` 真实拉一次确认 registry 可见(Central 同步有分钟级延迟)。
- **SBOM / changelog**:`supply-chain` job 产出 `sdk-sbom.spdx.json` 与 `sdk-protocol-changelog.txt`(artifact);附到 Release notes。
- **provenance**:npm 已 `--provenance`;SLSA attestation(`actions/attest-build-provenance`)为 TODO,真发开通后接。

---

## 4. 回滚 / 撤回

- **Maven Central**:`autoPublish=false` 给了"上传后、确认前"的撤回窗口——控制台 drop 即可。一旦 publish 到 Central**不可删**,只能发修订版。
- **npm**:72h 内可 `npm unpublish`(超时只能 deprecate)。
- **PyPI / crates.io**:**不可删**已发版本,只能 yank(crates)/ 发新版。
- **Go**:tag 一旦被 proxy 缓存不可变;删 tag 无效,发新 tag。

⇒ 不可撤的居多,**务必先 dry-run + package/smoke 全绿 + environment 审批**再真发。

---

## 5. 关联

- `.github/workflows/sdk-release-validation.yml` —— 主发布管线
- `.github/workflows/sdk-python-publish.yml` —— Python 独立 PyPI 通道
- `.github/workflows/sdk-contract-parity.yml` —— per-PR 契约不漂移门禁
- `docs/sdk/byo-conformance-contract.md` —— 跨语言契约
- `docs/sdk/wire-protocol.md` —— 协议权威(§A schema / §B 错误 / §C 重试)
