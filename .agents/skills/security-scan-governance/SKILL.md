---
name: security-scan-governance
description: 用户要求执行本地安全扫描、核实 CI 安全覆盖或评估扫描结果时使用。区分扫描工具、业务编排模块、本地模式与实际 PR/Full Gate 门禁。
---

# 安全扫描治理

## 先确认扫描边界

- 按用户目标和变更面选择 secret、依赖、SAST、文件系统/IaC、镜像或 DAST；不要把某一种扫描结果当成全面安全验证。
- 先读 `docs/runbook/security-scan.md` 和当前 `.github/workflows/pr-gate.yml`、`full-ci-gate.yml`、`sonar-gate.yml`。工作流才是 CI 实际覆盖面的权威来源，Runbook 是本地命令入口。
- `security-scan/` 是调用外部工具的 Java 编排模块，不是扫描引擎。核对目标模式实际调用的工具、版本、参数、报告和退出码。
- Sonar 与安全扫描分开报告。仓库本地 `sonar-scan.sh --incremental` 仍分析整个 Maven 项目，并额外生成 Git 变更行报告；它不等于只扫描改动文件。检查 Sonar workflow 是否启用、Quality Gate 是否成功；`SKIPPED` 不算通过。

## 执行与判读

- 本地统一入口：`bash scripts/ci/security-scan.sh --help`；按场景收窄为 `--mode=secret|deps|sast|filesystem|image|dast`，默认 `--mode=all` 前先确认工具依赖和目标环境。
- DAST 只对隔离或获准的目标执行。需要认证覆盖时按 Runbook 配置认证并启用 `--require-zap-auth`；无认证 401/health-only 结果不能证明受保护 API 安全。
- 不因工具无告警就推断代码无漏洞。对每个告警核对受影响版本、可达路径、部署可利用性和误报依据；忽略项须遵循仓库 owner/reason/expiry 守护。
- CI 结果按工作流、job、提交 SHA 和具体扫描器报告核验。区分 PR 快速检查、Full Gate、本地扫描、DAST/staging；本地 `--mode=deps` 或 `--mode=dast` 的存在不代表 CI 已执行。
- 不把扫描报告、凭据或临时密钥提交进仓库；按脚本/Runbook 指定目录保存报告。

## 参考入口

- 本地模式、依赖和报告：[`docs/runbook/security-scan.md`](../../../docs/runbook/security-scan.md)
- CI 与 Sonar 状态：[`docs/runbook/ci.md`](../../../docs/runbook/ci.md)
- 编排入口：`scripts/ci/security-scan.sh`、`security-scan/README.md`
- 增量 Sonar：`scripts/dev/sonar-scan.sh`、`scripts/dev/sonar-incremental-report.py`
- 需要全面威胁建模或对抗式架构审查时，同时使用 `adversarial-system-review`。
