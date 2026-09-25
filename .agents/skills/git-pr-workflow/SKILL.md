---
name: git-pr-workflow
description: 用户要求在特性分支交付代码、执行提交检查、创建/审查/合并 PR 或清理分支时使用。覆盖本仓库的增量 Sonar、代码量与可读性快照、变更日志、CI、合并和清理流程；不隐含授权合并或删除未合并分支。
---

# Git 与 Pull Request 流程

## 原则

- 仓库 `AGENTS.md`、贡献指南、当前 CI 和用户明确范围优先；该 skill 不替代领域测试或 required checks。
- 不覆盖、暂存或提交无关改动。提交前按文件逐项确认暂存范围。
- 创建 PR、合并、删除远端/本地分支是不同的状态变更；只执行用户要求的动作。收到“提交 PR”不等于获准合并。
- Sonar、代码行快照、changelog 和自动生成清单的状态必须按实际命令结果报告；SKIP、QUEUED、IN_PROGRESS 不算通过。

## 特性分支

1. 阅读适用的 `AGENTS.md`、贡献指南和相关模块说明。
2. 检查仓库根目录、remote、当前分支、worktree、暂存区和完整 diff；查看是否有未完成 merge/rebase。
3. 需要最新基线时 `git fetch`，确认目标分支和本地改动后，从最新目标分支创建短生命周期特性分支。不要直接在受保护主分支开发。
4. 保留已有工作区改动。目标分支落后时，先确认 rebase/合并不会覆盖工作，不用 reset/checkout 丢弃或强推来“整理”状态。

## 实现与验证

1. 按变更域追踪调用方、授权与租户边界、事务、并发、幂等、迁移、脚本运行时和错误处理；按风险选择定向验证，不以全绿旧快照替代当前代码测试。
2. Java 多模块测试使用 Maven Wrapper，并根据依赖闭包使用 `-am`；记录真实测试类、用例数及是否依赖 Testcontainers/Docker。
3. 本地提交门禁：显式暂存拟交付文件，审查 `git diff --cached`，再运行 `bash scripts/local/pre-commit-checks.sh`；提交 hook 会按暂存文件域路由检查，并可能自动格式化或新增生成快照，执行后必须重新审查暂存差异。
4. **Lean LOC 快照**：tracked 源码变化由 pre-commit 使用暂存树重生成 `docs/stats/loc-current-lean.md` 并自动暂存。手工复核可运行 `python3 scripts/ci/check-loc-snapshot.py`；不要用旧快照覆盖当前结果。
5. **Java 可读性清单**：pre-push 可能自动刷新 `docs/analysis/java-readability-inventory-2026-08-12.md` 并拒绝本次 push。若仅清单排序/行数按生成逻辑变化，审查后单独暂存并提交该生成物，再重试 push；不要用 `SKIP_SDK_CHECKS=1` 绕过。
6. **Changelog**：有用户可见功能、重要缺陷/安全修复、生产配置、部署、迁移或外部契约影响时，按根 `CHANGELOG.md` 的 `[Unreleased]` 分类追加条目。只有 `docs/agent-baseline.md` 或 ADR/架构权威约束变化才更新 `docs/changelog.md`。提交后运行 `python3 scripts/ci/check-changelog-sync.py --base origin/main`。
7. **Sonar**：先确认本地 Sonar 服务/凭据可用及 `origin/main` 已更新。按需运行 `bash scripts/dev/sonar-scan.sh --incremental --base-ref origin/main`；需要刷新覆盖率时添加 `--with-tests`。检查增量 issues、changed-lines 和 hotspots 报告，修复本次引入的问题。报告位于 `reports/sonar/<timestamp>/`，通常是本地产物，不要随意纳入 PR。仓库 `.github/workflows/sonar-gate.yml` 当前默认关闭；若结果为 skipped 或未配置，不得描述为 Sonar 通过。
8. 根据变更域运行 Shell/文档/配置/依赖/安全/API 等守护。全量 Full Gate、sim、BE-ACC 和 Sonar 是不同验证层级，不要互相替代；未运行则在 PR 中写明。

## 提交与 PR

1. 提交前再次核对 branch、`git diff --cached --check`、暂存文件清单和生成物；提交信息描述实际意图，不把 Sonar 报告、日志、密钥或构建产物误纳入。
2. 推送前检查 pre-push hook。自动更新清单导致失败时，阅读生成差异、按要求提交，再重试；不要反复空推或绕过门禁。
3. PR 描述包含目的、关键实现、影响范围、已通过的命令/测试、尚未运行的验证和残余风险。若需要前后端契约，确认配对仓库同步。
4. 创建 PR 后检查 required checks 的实时状态。只有全部必需检查实际成功且必要 review/审批满足后，才报告可合并；失败时读取失败日志、修复并重新验证。Sonar 的 `SKIPPED` 不是成功证据。

## 合并与清理

1. 只有用户明确要求合并，且仓库保护规则允许、required checks 通过、所需 review 完成时才合并；检查或 review 未完成时停止在开放 PR 状态。
2. 合并操作返回不等于已合并。记录 PR 的 `baseRefName`、`headRefName`、`headRefOid`、`state` 和 `mergeCommit`；确认 `state=MERGED`，fetch 目标分支，并验证 `mergeCommit` 是目标分支的祖先。仓库使用 squash merge 时，特性分支原始提交通常不是目标分支祖先；不得仅凭 `git branch -d` 的结果判断 PR 是否合并。
3. 只有确认合并后才清理分支。先确认待清理的分支名及远端 ref 仍对应已核实的 PR head，避免删除该分支上的后续提交。检查 `git worktree list --porcelain`，包括当前 worktree；目标分支仍被任何 worktree 检出时，不得删除。当前 worktree 干净时先切换到已更新的目标分支；其他 worktree 仍检出目标分支时先保留该分支并处理 worktree。只有在 PR 已合并、其 `mergeCommit` 已包含于目标分支且分支指向已记录的 `headRefOid` 时，才可按用户授权删除对应远端分支及本地分支。Squash/rebase 后 `git branch -d` 可能因提交图不相连而拒绝；此时仅在上述证据全部成立后，对明确核实的分支使用 `git branch -D`，不得将强制删除作为常规清理方式。清理后运行 `git fetch --prune` 并复核状态。未合并、检查失败、远端 ref 已变化或仍被 worktree 使用的分支不得删除。
4. 收尾核对主分支、工作区、远端分支和 PR 状态；报告 commit、PR、检查、merge commit、分支清理结果及任何剩余 worktree。

## Sonar 与快照参考

- 本地 Sonar 命令说明：`scripts/dev/sonar-scan.sh`、`scripts/dev/README.md`。
- CI 增量/全量门禁边界及 Sonar 默认状态：`docs/runbook/ci.md`。
- LOC 口径与归档：`docs/stats/README.md`、`docs/stats/archive/README.md`。
- 代码量自动刷新：`scripts/local/pre-commit-checks.sh`；可读性清单生成：`scripts/ci/report-java-readability-inventory.py`。
