# 开源工程治理规范

## 目标与边界

本规范借鉴成熟开源项目的评审、所有权、安全响应和供应链实践，但不复制与当前维护规模不匹配的流程。编码格式仍以
[`../coding-conventions.md`](../coding-conventions.md) 为准，CI 质量治理仍以
[`../runbook/ci.md`](../runbook/ci.md) 为准。

当前治理原则：

- 格式、静态检查和扫描工具保持少而明确，不为提高工具数量重复引入同类扫描器；
- GitHub 平台保护是主干事实来源，本地 hook 不能代替 ruleset 和 required checks；
- 安全发现、依赖更新、制品来源和高风险变更分别治理，不用单一分数代替安全结论；
- 流程强度随维护者数量提升，单维护者阶段不伪造无法满足的双人审批。

## 仓库安全基线

| 能力 | 当前要求 | 事实来源 |
|---|---|---|
| 私密漏洞报告 | 启用 GitHub Private Vulnerability Reporting，公开 Issue/PR 不接收漏洞细节 | 根 [`SECURITY.md`](../../SECURITY.md) + GitHub Security Settings |
| 密钥防护 | 启用 Secret Scanning 和 Push Protection；non-provider patterns、validity checks 在当前仓库能力不可用时保持关闭，平台支持后再启用 | GitHub Security Settings |
| 依赖治理 | Dependabot alerts 持续发现；自动版本/安全修复 PR 关闭；季度计划任务生成一次跨生态盘点，由维护者建立一张集中 PR | [季度依赖集中治理](../runbook/quarterly-dependency-governance.md) |
| 主干保护 | 必须经 PR、required checks、讨论解决；只允许 squash；不保留永久 bypass actor | GitHub `main protection` ruleset |
| CodeQL | PR 必须稳定产生 `Analyze (java)` 状态；纯文档可由范围探测安全跳过重分析。只有该 workflow 已在 main 生效后，才能把此状态加入 required checks，避免 PR 永久等待 | GitHub ruleset + [`codeql.yml`](../../.github/workflows/codeql.yml) |
| Action 供应链 | 外部 Action 固定 40 位 commit SHA，尾部保留版本注释 | [`../runbook/ci.md`](../runbook/ci.md) + [`check-github-action-pinning.py`](../../scripts/ci/check-github-action-pinning.py) |
| Scorecard | 每周及 main 推送生成 SARIF；只用于治理漂移和整改排序，不按总分阻断 PR | [`scorecard.yml`](../../.github/workflows/scorecard.yml) |

### 审批升级条件

当前只有一个有效维护者时，ruleset 的审批数保持为 `0`，但 PR、required checks 和讨论解决仍必须满足。出现第二位稳定维护者后，必须在同一变更中完成：

1. `required_approving_review_count=1`；
2. `require_code_owner_review=true`；
3. `require_last_push_approval=true`；
4. 按实际负责模块拆分 `CODEOWNERS`，不使用虚假的团队或占位账号。

## 高风险变更提案

普通修复、内部重构、测试补充和不改变外部行为的性能优化不需要额外提案。满足以下任一条件时，应在编码前按
[`bep-template.md`](./bep-template.md) 建立轻量 BEP，并在 PR 中链接：

- 新增或破坏 Console/OpenAPI、SDK、Kafka、文件格式或数据库外部契约；
- 改变作业、任务、分片、补偿、重放、租约或终态状态机；
- 改变多租户隔离、认证授权、密钥、插件执行或安全旁路；
- 引入新基础设施组件、跨模块同步依赖或生产数据迁移；
- 改变容量上限、HA/DR、发布/回滚或不可逆运维行为。

已有 ADR 负责记录最终架构决策；BEP 负责实现前评审。简单变更不同时维护 BEP 和 ADR，避免重复文档。

## 生产就绪评审

跨组件且影响生产正确性、隔离、恢复或容量的变更，在上线前使用
[`production-readiness-review-template.md`](./production-readiness-review-template.md)。评审至少覆盖：

- 兼容、迁移、灰度和回滚；
- 超时、重试、幂等、并发、资源上限和故障恢复；
- 指标、日志、Trace、告警、值班动作和审计；
- 定向测试、真实依赖验证、容量证据和仍未覆盖的风险。

评审模板不用于每个 PR，也不能把本地测试描述成 staging 或生产证据。

## 制品供应链

现阶段保留 SBOM、许可证和 SDK registry provenance。生产镜像和可下载 SDK 制品的目标状态是：

1. 构建由受保护 workflow 和受控 release environment 触发；
2. 产物生成 SBOM、不可变 digest 和 SLSA provenance；
3. 使用 OIDC/keyless 签名，部署前验证签名、provenance、仓库和 commit；
4. 签名或验证失败时 fail-close，不用开发机生成的声明冒充发布证明。

真实 registry、environment 和部署端尚未配置前，只保留实现入口和运行手册，不把待办描述为已完成。

## 依赖更新批次

依赖更新采用持续发现、季度集中实施的方式。Dependabot 不再自动创建 PR，详细操作见[季度依赖集中治理](../runbook/quarterly-dependency-governance.md)。仓库采用以下规则控制噪声和风险：

1. 普通版本和安全告警持续可见，但版本更新与安全自动修复 PR 均关闭；每季度计划任务只生成 artifact 与 Actions Summary，维护者决定实施后建立一张集中 PR；
2. Docker tag 和 digest 没有可信的统一 semver 语义，纳入同一季度盘点，但必须通过真实镜像构建与启动回归后再合入；
3. `maven` Docker 构建镜像不自动升级，因为镜像 tag 同时携带 JDK 主版本，必须与 Java 运行时基线同步；
4. 跨生态批次只汇总可由同一组 Java、供应链和 SBOM 门禁验证的更新；基础镜像跨发行版和 Java 基线不得为了减少 PR 数量强行捆绑；
5. Maven 依赖变更必须在同一 PR 重建并提交 `docs/compliance/sbom.json`，CI 只读比对，不在高权限 workflow 中执行 PR 内 Maven 配置并回写代码；
6. Critical/High 且可达的安全漏洞不等待季度窗口，单独按安全修复流程处理；仍禁止机器人自动合入。

## 验证

```bash
python3 scripts/ci/check-github-action-pinning.py
python3 scripts/ci/check-docs-structure.py
bash scripts/ci/check-shell-scripts.sh
actionlint
```

平台设置需额外使用 `gh api` 或 GitHub Settings 实查；仓库内 YAML 和注释不能证明设置已经生效。

## 明确不做

- 不为了模仿其他项目更换当前稳定的 Palantir 120 列格式；
- 不把 OpenSSF Scorecard 总分设为 required check；
- 不增加与 CodeQL、PMD、Trivy、Checkov 或现有 Secret 检查重复的扫描器；
- 不要求普通内部重构编写 BEP/生产就绪评审；
- 不在只有一个维护者时启用无法完成的独立审批规则。
