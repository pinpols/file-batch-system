# GitHub Actions 工作流索引

本页登记本仓库全部工作流的用途、触发条件与执行边界；具体路径过滤、输入、权限和 job 以链接的 YAML 为准。排障和本地复现见 [CI 运行手册](../../docs/runbook/ci.md)，检查器用法见 [CI 脚本索引](../../scripts/ci/README.md)，复用步骤见 [共享 Actions](../actions/README.md)。新增、改名或删除工作流必须同步本索引，由脚本治理守卫检查实际跳转和用途。

## 合入与主干检查

| 工作流 | 触发条件 | 用途与结果边界 |
|---|---|---|
| [pr-gate.yml](pr-gate.yml) | 非草稿 PR → main、merge_group、手动 | 按变更范围并行执行静态治理、单测及定向集成测试分片和安全扫描；编排、Console 并行片分别合并覆盖率，汇总 Java 测试报告；static-checks 汇总静态分组 |
| [full-ci-gate.yml](full-ci-gate.yml) | main push（有路径排除）、周日 02:00 UTC、手动 | 主干静态检查、单元/集成测试、并行 E2E、安全扫描；定时/手动补充测试质量分析。main-failure-triage 是本工作流内的失败归责 job，可写 PR 标签/评论或 Issue |
| [codeql.yml](codeql.yml) | PR → main、main push（有路径排除）、周一 03:00 UTC | Java CodeQL；PR 使用无构建模式，main/定时使用编译和扩展查询；上传安全分析结果 |
| [workflow-lint.yml](workflow-lint.yml) | workflow/action、Zizmor 配置或其 Python 工具依赖变更的 PR 和 main push | actionlint 与 zizmor 检查 Actions 语法和安全配置 |
| [license-review.yml](license-review.yml) | main PR 中的 POM、许可脚本及本工作流变更 | 依赖许可证审查；覆盖范围按路径过滤，不能替代漏洞扫描 |
| [orchestrator-internal-openapi-drift.yml](orchestrator-internal-openapi-drift.yml) | 内部 OpenAPI、Controller 或本工作流变更的 PR；手动 | 检查文档声明的路径是否存在于 Controller；当前是登记的报告型软门禁，不核对反向全集及字段 schema |
| [sonar-gate.yml](sonar-gate.yml) | main 的 Java/构建/扫描工具变更、每天 18:23 UTC、手动 | 独立执行 Sonar 分析与 Quality Gate 校验；结果与 Full Gate 分开判断 |

## SDK 验证与发布

| 工作流 | 触发条件 | 用途与结果边界 |
|---|---|---|
| [sdk-contract-parity.yml](sdk-contract-parity.yml) | PR → main、merge_group、手动 | 五语言共享 fixture 与契约测试；sdk-contract-required 汇总稳定门禁，独立汇总各语言和 live transport 测试日志。live transport 不纳入该 required 门禁 |
| [sdk-python.yml](sdk-python.yml) | Python SDK/共享 fixture 相关 PR、main push；手动 | Python lint、格式和版本矩阵测试 |
| [sdk-orchestrator-e2e.yml](sdk-orchestrator-e2e.yml) | main 的 SDK、服务、迁移、容器等相关变更；手动 | 预热镜像后，在五语言各自隔离服务栈运行真实 Orchestrator/Trigger E2E；不自动替代 PR 合入检查 |
| [sdk-release-validation.yml](sdk-release-validation.yml) | GitHub Release published、手动 | 契约/破坏性变更、打包和供应链验证；发布 job 依据事件、dry-run、版本及 Environment 条件执行，涉及包仓库写入。手动 dry-run 默认为 true |
| [sdk-python-publish.yml](sdk-python-publish.yml) | sdk-python-v*.*.* tag push、手动 | 构建并发布 Python 包；手动 dry-run=true 指上传 TestPyPI，仍是外部写入，不表示只读演练 |

## 定时与手动验证

| 工作流 | 触发条件 | 用途与结果边界 |
|---|---|---|
| [daily-sim-strict-validation.yml](daily-sim-strict-validation.yml) | 每天 13:31 UTC、手动 | 按北京时间检查当日代码/配置变更，依次跑 SIM 与 BE strict；SIM 失败仍尝试 strict，两项成功才调用镜像构建。force 可绕过无变更判断 |
| [daily-backend-reliability.yml](daily-backend-reliability.yml) | 每天 20:17 UTC、手动 | 在隔离 runner 用 Testcontainers 验证并发、checkpoint、文件完整性和依赖故障恢复；不连接已有 SIM/staging |
| [staging-gate.yml](staging-gate.yml) | 仅手动 | 后端 Java 治理及并行完整 E2E 复验并汇总测试报告；使用 runner/Testcontainers，名称不表示会部署或连接 staging 集群 |
| [strict-verify.yml](strict-verify.yml) | 指定严格验收脚本变更的 main PR；手动 | PR 和默认手动模式只跑前置依赖 dry-run；手动 live 启动隔离 PG/Valkey 与 Console API 做真实数据验证，独立于 PR/Full 聚合 |
| [fuzzing.yml](fuzzing.yml) | 周二 03:15 UTC、手动 | Jazzer 安全边界模糊测试；结果不等同于全系统安全审计 |

## 构建与交付自动化

| 工作流 | 触发条件 | 用途与写入边界 |
|---|---|---|
| [docker-image-build.yml](docker-image-build.yml) | 手动、workflow_call | Docker Bake 构建应用及工具箱镜像；publish=false 默认只构建，显式发布时推送 GHCR 并输出不可变 digest 镜像集 |
| [compose-cd.yml](compose-cd.yml) | 仅手动，执行限制到 main | 固定双仓 SHA、构建并组装 digest manifest；deploy_staging=true 才连接配置的 Docker 主机部署，并执行 smoke/E2E/Lighthouse 与失败回滚 |
| [prepare-platform-release.yml](prepare-platform-release.yml) | 手动选择 patch/minor/major | 使用 GitHub App 准备双仓统一版本的 Release PR；创建 PR，不自动合并或发布 |
| [label-automerge.yml](label-automerge.yml) | PR labeled/synchronize/reopened/ready_for_review | 仅对同仓非草稿且带 automerge 标签的 PR 登记 GitHub 原生 squash auto-merge；等待平台 required checks，具有合并写入能力 |

## 供应链治理报告

| 工作流 | 触发条件 | 用途与结果边界 |
|---|---|---|
| [scorecard.yml](scorecard.yml) | main push、周三 02:23 UTC、手动 | OpenSSF Scorecard 生成并上传 SARIF；不按总分阻断 PR |
| [quarterly-dependency-review.yml](quarterly-dependency-review.yml) | 季度首日 03:00 UTC、手动 | 生成多生态依赖盘点 artifact 与 Summary；不自动创建 Issue 或 PR |

上述时间均为工作流 cron 的 UTC 时间。路径过滤和 job 条件可能造成跳过；跳过不等于通过。required checks 与 Environment 审批以 GitHub 当前配置为准，本页不把文件名、检查成功或存在某个 workflow 当作已启用保护规则的证据。
