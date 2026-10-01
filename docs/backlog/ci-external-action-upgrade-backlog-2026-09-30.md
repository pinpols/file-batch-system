# CI 外部 Actions 版本升级与验收记录

> 盘点日期：2026-09-30。版本状态是该日期的上游发布快照；实施时必须重新核对官方 release、安全公告、运行时要求和本仓调用契约。
>
> 当前待办状态以 [`../analysis/todo-master.md`](../analysis/todo-master.md) 的 G7 为准。本文件用于记录盘点结果和升级验收，不是第二份状态总表。

> 2026-10-01 状态复核：通用 Action、Hadolint、SBOM Action、Squawk 和 oasdiff 的升级已通过相关 PR 门禁，最新 `main` Full Gate 与 CodeQL 也已通过。Docker Buildx/Bake 引用已升级并通过静态门禁，但仓库尚无升级后的真实镜像 workflow 运行记录；该项仍需在下一次镜像构建时取证。

## 盘点结论

此次盘点覆盖 `.github/workflows/` 中的第三方 Actions、安全扫描器和 CI 安装的迁移/API 守护工具。仓库已配置 Dependabot GitHub Actions ecosystem 周期检查，但新主版本、直接下载的 CLI 版本和需要兼容性验证的升级仍需维护者评审。

| Action | 仓库当前引用 | 盘点时上游版本 | 建议 | 风险与验收 |
|---|---|---|---|---|
| `actions/checkout` | v7 | v7 | ✅ 已统一到 v7 | 相关 PR 门禁及最新 `main` Full Gate 已通过；特殊子模块/LFS 输入仍按使用场景验证 |
| `actions/setup-python` | v7 | v7 | ✅ 已统一到 v7 | Python 版本解析、缓存和现有工具链已通过相关门禁；self-hosted runner 版本仍由部署环境确认 |
| `actions/setup-java` | v6 | v6 | ✅ 已升级到 v6 | JDK/Maven cache 已通过主干构建；发布凭据只在真实发布任务验证 |
| `actions/setup-node` | v7 | v7 | ✅ 已统一到 v7 | Node 版本与 npm cache 已通过现有门禁；发布 token 仍只在发布任务使用 |
| `actions/setup-go` | v7 | v7 | ✅ 已统一到 v7 | Go SDK 与多 SDK 门禁已使用当前引用 |
| `actions/upload-artifact` | v7 | v7 | ✅ 已统一到 v7 | 现有门禁 artifact 上传已通过；GHES 兼容性不由 GitHub-hosted runner 证据覆盖 |
| `docker/setup-buildx-action` / `docker/bake-action` | v4 / v7 | v4.1.0 / v7 | 🟡 引用与静态门禁已完成 | 待下一次真实镜像 workflow 验证 BuildKit driver、缓存、Bake 构建和镜像元数据 |

## 安全扫描与供应链工具

| 工具 | CI 当前引用 | 盘点结论 | 待办 / 验收 |
|---|---|---|---|
| Gitleaks | `8.30.1`，PR Gate 与 Full Gate 下载官方 Linux x64 压缩包 | 上游 issue #2170 曾报告 `8.30.1` 对 GitHub PAT 等规则漏检；当前没有更高稳定版本可直接替换 | ✅ Docker `linux/amd64` 基础正负样例及 PR/Full Gate 安全扫描已通过；继续关注上游规则变化，该样例不代表所有密钥类型 |
| Trivy CLI | `0.74.0` | ✅ PR Gate 和 Full Gate 固定同版；`config` 参数已改为 `misconfig` | 本地扫描及主干 Full Gate 已通过，后续随漏洞库和版本更新持续复核 |
| `aquasecurity/trivy-action` | `v0.36.0` | 盘点时上游最新 release；保留 | 跟随 Dependabot/上游 release 定期复核 |
| Checkov Action | `bridgecrewio/checkov-action@v12` | 上游当前文档仍使用 v12；引用的是可变 major tag，工具实际版本随 Action 维护节奏更新 | 不因版本数字看似旧就盲升；复核 tag、底层 Checkov 版本和 `CKV_K8S_*` 结果后再变更 |
| Hadolint Action | `hadolint/hadolint-action@v3.5.0` | 已升级到当前引用 | ✅ 静态门禁和主干 Full Gate 已通过 |
| GitHub CodeQL Action | `github/codeql-action/*@v4` | 当前主版本；保留 | 关注 GitHub 弃用公告和 runner 兼容要求 |
| SBOM Action | `anchore/sbom-action@v0.24.2` | 已从浮动 major tag改为具体 release tag 并继续补丁升级 | ✅ SDK release validation 静态门禁通过；真实发布 artifact 仍在发布任务验证 |
| Sonar Scanner | 可选 `sonar-gate` 使用 Maven Scanner `5.7.0.6970`；本地脚本同版本默认值 | Sonar 门禁默认关闭，不属于当前必需 PR/Full Gate；不作为当前阻断性安全扫描 | 启用 Sonar Gate 或变更 Sonar 服务时，复核插件与服务器兼容矩阵并执行全量/增量扫描 |

## 迁移与 API 契约守护工具

| 工具 | CI 当前版本 | 上游盘点版本 | 建议与验收 |
|---|---|---|---|
| `squawk-cli` | `2.65.0` | npm `2.65.0` | 🟡 已升级；本批无迁移文件，安全门禁按规则跳过；后续迁移变更仍需对受支持/拒绝的 SQL 样例回归 |
| `oasdiff` | `1.32.1`（PR Gate 与 SDK release workflow） | `1.32.1` | ✅ 已升级，OpenAPI 变更门禁已实跑；继续覆盖 compatibility/breaking fixture |

## 保持现状并定期复核

| Action / 工具 | 当前引用 | 盘点结论 |
|---|---|---|
| `aquasecurity/trivy-action` | v0.36.0 | 盘点时为上游最新发布；本仓 Trivy CLI 固定版本已在本轮升至 `0.74.0`，扫描参数统一为 `vuln,misconfig` |
| `docker/bake-action` | v7 | 已升级；真实镜像 workflow 证据仍与 Buildx setup action 一起补齐 |
| `github/codeql-action` | v4 | 当前使用主版本；按 GitHub CodeQL 发布和弃用公告复核 |
| `hadolint/hadolint-action` | v3.5.0 | 已完成补丁升级；按上游 release 定期复核 |
| `bridgecrewio/checkov-action` | v12 | 单凭主版本无法判定新旧；升级前核对上游 tag/release、镜像来源和参数兼容 |
| `dtolnay/rust-toolchain` | stable | 这是有意选择的滚动工具链通道，不按固定版本 Action 升级处理；Rust 依赖仍以 lockfile 为准 |
| `pypa/gh-action-pypi-publish` | release/v1 | 使用上游维护的发布通道；核对供应链安全公告与 OIDC 发布配置，不擅自改为不兼容主版本 |

安全扫描结论详见上方安全扫描与供应链工具表，避免同一工具维护两份版本状态。

## 升级顺序

1. ✅ 已完成第一批 `checkout`、`setup-python/java/node/go` 和 artifact Actions 升级。
2. ✅ 已完成 Hadolint、SBOM Action 和 Squawk/oasdiff 版本更新及适用门禁验证；Buildx/Bake 待下一次真实镜像 workflow 补运行证据。
3. GitHub Actions Dependabot 已开启每周版本更新队列，上限 5；再处理浮动 tag、未明确 pin 到 release 的第三方 Action 时，同步考虑 Dependabot 更新和人工审查负担。
4. 每批升级都运行 `actionlint`、对应 workflow、PR Gate；影响主构建/安全流程时再运行 Full Gate。CI 未实际运行的结果不得标记为通过。

## 上游参考

- [actions/checkout releases](https://github.com/actions/checkout/releases)
- [actions/setup-python releases](https://github.com/actions/setup-python/releases)
- [actions/setup-java releases](https://github.com/actions/setup-java/releases)
- [actions/setup-node releases](https://github.com/actions/setup-node/releases)
- [actions/setup-go releases](https://github.com/actions/setup-go/releases)
- [actions/upload-artifact releases](https://github.com/actions/upload-artifact/releases)
- [docker/setup-buildx-action releases](https://github.com/docker/setup-buildx-action/releases)
- [hadolint/hadolint-action releases](https://github.com/hadolint/hadolint-action/releases)
- [github/codeql-action releases](https://github.com/github/codeql-action/releases)
- [aquasecurity/trivy releases](https://github.com/aquasecurity/trivy/releases)
- [aquasecurity/trivy-action releases](https://github.com/aquasecurity/trivy-action/releases)
- [Gitleaks issue #2170: v8.30.1 detection regression report](https://github.com/gitleaks/gitleaks/issues/2170)
- [oasdiff releases](https://github.com/oasdiff/oasdiff/releases)
- [squawk-cli on npm](https://www.npmjs.com/package/squawk-cli)
- [anchore/sbom-action releases](https://github.com/anchore/sbom-action/releases)
