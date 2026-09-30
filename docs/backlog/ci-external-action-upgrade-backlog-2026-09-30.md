# CI 外部 Actions 版本升级待办

> 盘点日期：2026-09-30。版本状态是该日期的上游发布快照；实施时必须重新核对官方 release、安全公告、运行时要求和本仓调用契约。
>
> 当前待办状态以 [`../analysis/todo-master.md`](../analysis/todo-master.md) 的 G7 为准。本文件用于记录盘点结果和升级验收，不是第二份状态总表。

## 盘点结论

此次盘点覆盖 `.github/workflows/` 中的第三方 Actions、安全扫描器和 CI 安装的迁移/API 守护工具。仓库已配置 Dependabot GitHub Actions ecosystem 周期检查，但新主版本、直接下载的 CLI 版本和需要兼容性验证的升级仍需维护者评审。

| Action | 仓库当前引用 | 盘点时上游版本 | 建议 | 风险与验收 |
|---|---|---|---|---|
| `actions/checkout` | v4、v6 | v7 | 将旧引用统一到 v7 | 检查 `fetch-depth`、凭据持久化、子模块和 LFS 等实际输入；运行全部 workflow lint 与 PR/Full Gate |
| `actions/setup-python` | v5、v6 | v7 | 统一到 v7 | v6 起使用 Node 24；确认 runner 满足要求，验证 Python 版本解析、缓存和 PyYAML 工具链 |
| `actions/setup-java` | v5 | v6 | 升级到 v6 | 复核 JDK 发行版/版本矩阵、Maven cache 和发布凭据配置；检查下载校验行为变化 |
| `actions/setup-node` | v5 | v7 | 升级到 v7 | 复核 Node 版本文件、npm 缓存及发布 job 行为 |
| `actions/setup-go` | v5 | v7 | 升级到 v7 | 复核 `go-version` / `go-version-file` 与缓存 key；运行 Go SDK 和多 SDK 联测 |
| `actions/upload-artifact` | v4、v7 | v7 | 将 v4 升至 v7 | 检查 artifact 名称唯一性、保留期、下载配对及跨 job 汇总 |
| `docker/setup-buildx-action` | v3 | v4.1.0 | 评估后升级 v4 | 复核 Buildx/BuildKit 版本、driver、缓存、Bake 构建和镜像元数据；本地构建及 CI 镜像构建都要验证 |

## 安全扫描与供应链工具

| 工具 | CI 当前引用 | 盘点结论 | 待办 / 验收 |
|---|---|---|---|
| Gitleaks | `8.30.1`，PR Gate 与 Full Gate 下载官方 Linux x64 压缩包 | 上游 issue #2170 曾报告 `8.30.1` 对 GitHub PAT 等规则漏检；报告关闭，但 issue 的复现环境是 Homebrew arm64，不能据此直接断言 CI 下载的 Linux artifact 同样受影响。本机对照运行的合成样例连 `8.30.0` 也未命中，故该样例/调用不是已验证的阳性对照 | **P1**：对 CI 实际 Linux artifact 运行经规则确认的正向合成 secret fixture 和负向 fixture，确认命中退出码/无命中退出码；验证前不把该版本标成安全，也不贸然换成未验证版本 |
| Trivy CLI | 本次修复已改为 `0.74.0` | ✅ 已完成：PR Gate 和 Full Gate 固定同版；`config` 参数改为 `misconfig` | 本地 `trivy fs --scanners vuln,misconfig --severity CRITICAL,HIGH --exit-code 1 .` 退出码 0；Full Gate 尚需远端运行确认 |
| `aquasecurity/trivy-action` | `v0.36.0` | 盘点时上游最新 release；保留 | 跟随 Dependabot/上游 release 定期复核 |
| Checkov Action | `bridgecrewio/checkov-action@v12` | 上游当前文档仍使用 v12；引用的是可变 major tag，工具实际版本随 Action 维护节奏更新 | 不因版本数字看似旧就盲升；复核 tag、底层 Checkov 版本和 `CKV_K8S_*` 结果后再变更 |
| Hadolint Action | `hadolint/hadolint-action@v3.3.0` | 上游已有 v3.4.0；其同步了 Hadolint v2.15.0 | 待升级并回归 Dockerfile warning/error 基线 |
| GitHub CodeQL Action | `github/codeql-action/*@v4` | 当前主版本；保留 | 关注 GitHub 弃用公告和 runner 兼容要求 |
| SBOM Action | `anchore/sbom-action@v0` | 使用浮动 major tag；上游已发布 `v0.24.0` | 评估改为经过验证的具体 release tag，并确认 Syft 版本、SBOM 格式和产物字段稳定 |
| Sonar Scanner | 可选 `sonar-gate` 使用 Maven Scanner `5.7.0.6970`；本地脚本同版本默认值 | Sonar 门禁默认关闭，不属于当前必需 PR/Full Gate；不作为当前阻断性安全扫描 | 启用 Sonar Gate 或变更 Sonar 服务时，复核插件与服务器兼容矩阵并执行全量/增量扫描 |

## 迁移与 API 契约守护工具

| 工具 | CI 当前版本 | 上游盘点版本 | 建议与验收 |
|---|---|---|---|
| `squawk-cli` | `2.58.0` | npm `2.65.0` | 待升级；对受支持/拒绝的 Flyway SQL 样例跑迁移安全门禁，比较规则变化后更新基线说明 |
| `oasdiff` | `1.19.0`（PR Gate 与 SDK release workflow） | `1.32.1` | 待升级；对 compatibility/breaking fixture、SDK release changelog 和 OpenAPI 空 schema 边界回归，确认 `--fail-on ERR` 语义不变 |

## 保持现状并定期复核

| Action / 工具 | 当前引用 | 盘点结论 |
|---|---|---|
| `aquasecurity/trivy-action` | v0.36.0 | 盘点时为上游最新发布；本仓 Trivy CLI 固定版本已在本轮升至 `0.74.0`，扫描参数统一为 `vuln,misconfig` |
| `docker/bake-action` | v6 | 当前使用主版本；与 Buildx setup action 分开评估 |
| `github/codeql-action` | v4 | 当前使用主版本；按 GitHub CodeQL 发布和弃用公告复核 |
| `hadolint/hadolint-action` | v3.3.0 | 上游已有 v3.4.0；升级项见安全扫描与供应链工具表 |
| `bridgecrewio/checkov-action` | v12 | 单凭主版本无法判定新旧；升级前核对上游 tag/release、镜像来源和参数兼容 |
| `dtolnay/rust-toolchain` | stable | 这是有意选择的滚动工具链通道，不按固定版本 Action 升级处理；Rust 依赖仍以 lockfile 为准 |
| `pypa/gh-action-pypi-publish` | release/v1 | 使用上游维护的发布通道；核对供应链安全公告与 OIDC 发布配置，不擅自改为不兼容主版本 |

安全扫描结论详见上方安全扫描与供应链工具表，避免同一工具维护两份版本状态。

## 升级顺序

1. 先升级 `checkout`、`setup-python/java/node/go` 和 artifact Actions，按调用场景分组提交，避免一次改动造成难以定位的 CI 故障。
2. 单独升级 Buildx setup action，验证 Buildx builder、Docker Bake、本地构建和 CI 镜像构建。
3. 再处理浮动 tag、未明确 pin 到 release 的第三方 Action；评估完整 tag 或 commit SHA pinning 时同步考虑 Dependabot 更新和人工审查负担。
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
