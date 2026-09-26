---
name: ci-governance
description: 审查或修改 GitHub Actions、PR gate、full-ci-gate、merge queue、required checks、自动合并和失败 run 清理时使用。重点区分合入门禁、发布门禁和本地预检，避免 CI 漏检与危险触发器。
---

# CI 与主干门禁治理

## 门禁分层

- PR gate 是合入门禁；`full-ci-gate` 是主干/发布可信度门禁；本地 pre-commit/pre-push 是快速预检。三者不能互相替代。
- `success`、`skipped`、`cancelled`、`timed_out` 和 `neutral` 要分别报告。跳过不等于通过，排队或运行中也不算通过。
- 主干 full gate 失败时，先定位 `head_sha`、工作流、失败 job 和最近合入 PR；不要继续发版或移动 tag，直到有修复、回滚或明确豁免。
- 开源或多人协作场景优先依赖 required checks、ruleset/branch protection 和 merge queue，不依赖个人本地钩子兜底。

## Workflow 变更

1. 先确认触发条件、权限、并发组、路径过滤和 required check 名称是否符合实际保护规则。
2. 对 `pull_request_target`、`workflow_run`、写权限 token、自动 approve/auto-merge、下载 artifact 后执行等模式按危险触发器处理；必须有清晰信任边界和最小权限。
3. 按需门禁只能降低无关任务成本，不能让相关变更漏检。路径过滤要覆盖生成文件、脚本、Helm、Compose、文档索引和 paired frontend 契约。
4. 不把失败清理、重跑、取消 run 作为修复。清理 Actions 只在用户明确要求时执行，并限定 failed/cancelled/timed_out 等已结束 run。

## 验证与证据

- 修改 workflow 后运行适用的静态检查，例如 `actionlint`、`zizmor` 或仓库封装脚本；没有安装时说明缺口。
- 在线验证要读取真实 check/run 状态和失败日志，不用 UI 猜测替代。
- PR 描述或收尾报告中分别列出本地预检、PR required checks、full gate、sim/staging 的状态。
- 新增或更名门禁时同步 `docs/runbook/ci.md`、本地钩子说明和 required checks 文档。

## 常见收口

- Dependabot 或 bot PR 策略变化要同时检查 `.github/dependabot.yml`、自动合并 workflow、ruleset 与安全扫描门禁。
- 失败 run 清理前先确认不会删除仍需追溯的证据；清理后保留 run id、分支和结论摘要。
- 合并后仍要跟踪目标分支 `full-ci-gate`，不要只凭 PR checks 认定主干已全绿。
