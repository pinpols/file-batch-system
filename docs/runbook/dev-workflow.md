# 开发工作流(dev workflow)

本仓采用 **GitHub Flow 简化版**:只 `main` 一个长期分支,所有工作走短命子分支 + PR + merge 后自动删。

> 决策依据:单团队 / 单部署 / 已有 staging-gate(对 main HEAD 跑真环境 e2e)+ release-please(自动 changelog/tag),无 multi-version 维护需求,Git Flow 的 develop / release / hotfix 长期分支属于纯装饰。

## 分支策略

| 分支 | 命名 | 生命周期 | 用途 |
|---|---|---|---|
| `main` | `main` | 永久 | 唯一长期分支、生产代码、protected |
| 功能 | `feature/<topic>` | 短命 | 新功能(如 `feature/job-priority`) |
| 修复 | `fix/<topic>` | 短命 | 常规 bug 修复 |
| 杂项 | `chore/<topic>` | 短命 | 重构 / 依赖升级 / CI 改动 |
| 文档 | `docs/<topic>` | 短命 | 仅文档改动(自动跳过部分 CI) |
| 紧急 | `hotfix/<topic>` | 短命 | 生产 P0 bug,直接 PR 回 main + 立即 tag |
| Dependabot | `dependabot/*` | 机器人管 | 自动开 / 自动 merge(patch bump)/ 自动删 |
| Release-please | `release-please--*` | 机器人管 | 别动 |

**禁忌**:不开 `develop` / `release` / `feature` 等长期父分支;不在 main 上直接 commit(branch protection 也会拒)。

## 日常开发流程

```bash
# 1. 同步 main
git checkout main && git pull

# 2. 开短命分支
git checkout -b feature/job-priority main

# 3. 改代码 + commit(信息说"为什么"而非"做了什么";无 emoji)
git add . && git commit -m "feat(orchestrator): job 优先级加权调度"

# 4. push + 开 PR
git push -u origin feature/job-priority
gh pr create --base main \
  --title "feat(orchestrator): job 优先级加权调度" \
  --body "..."

# 5. 可选:标 automerge 让 CI 绿了自动合
gh pr edit <PR#> --add-label automerge

# 6. 等 5 个 required check 全绿(unit-it-a / unit-it-b1 / unit-it-b2 / static-checks / security)
#    + codeql ✓(SAST)
#    无 reviewer 要求,自己可 self-merge

# 7. merge 后 GH 自动 squash + 删 PR 分支
```

PR 标题 / commit 信息规范 → [`docs/coding-conventions.md`](../coding-conventions.md)。

## Hotfix(生产 P0)

```bash
git checkout main && git pull
git checkout -b hotfix/oom-leak main
# 改代码 + commit + push + PR
# merge 后立即 tag:
git checkout main && git pull
git tag v1.2.4 && git push origin v1.2.4
```

tag `v*` push 后:
- staging-gate 自动跑(Playwright e2e + Gatling 压测)
- release-please 出 changelog PR

## Release(无 release 分支)

```bash
git checkout main && git pull
git tag v1.3.0 && git push origin v1.3.0
# 后续全自动:staging-gate + release-please
```

> 不需要 `release/1.3.x` 长期分支;tag 即版本锚点。多版本并存场景出现时再加。

## Dependabot 自动归并

- patch bump(`1.2.3 → 1.2.4`)+ 安全告警:自动 approve + auto-merge(`dependabot-auto-merge.yml`)
- minor bump:自动 approve,等人 merge
- major bump:啥都不做,人工 review

详见 [`docs/runbook/ci.md`](ci.md) 「PR 自动归并」小节。

## Label automerge(任意 PR)

给 PR 打 `automerge` 标签 → 等所有 required check 绿了自动 squash merge,无须手动点击。

**安全性**:
- ❌ **不**自动 approve(reviewer approval 仍是 gate)
- ❌ **不**跳过任何 required status check
- ❌ **不**影响 branch protection
- 撤销:删 `automerge` label + `gh pr merge --disable-auto <PR_URL>`

## 各 CI 门禁的角色

按代码生命周期分,共 10 个 workflow:

### PR 阶段(merge 前,阻断 PR)

| Workflow | 触发 | 做什么 | 阻断? |
|---|---|---|---|
| **pr-gate** ~4-5min | PR open/sync + 非 main push | unit + IT + 静态校验 + i18n / api drift / dict / openapi 同步。5 个并发 job(static-checks / unit-it-a / unit-it-b1 / unit-it-b2 / security)| ✓ |
| **codeql** ~4-5min | PR + push main + 周一 cron | Java SAST(污点流分析):SQLi / 反序列化 / SSRF / 路径注入。结果进 Security tab | ✓ |
| **workflow-lint** ~15s | 改 `.github/workflows/**` 或 `.github/actions/**` | actionlint(yml + shellcheck)+ zizmor(action 注入 / token 暴露) | 否 |

### PR 阶段自动操作(不阻断)

| Workflow | 触发 | 做什么 |
|---|---|---|
| **dependabot-auto-merge** | Dependabot 开 PR | patch + 安全告警 → 自动 approve + auto-merge;minor → 自动 approve 等人合;major → 不动 |
| **label-automerge** | 任意 PR 打 `automerge` 标签 | `gh pr merge --auto --squash` 入 GH 等待队列(不跳 check)|

### Merge 到 main 后

| Workflow | 触发 | 做什么 |
|---|---|---|
| **full-ci-gate** ~7min | push main + 周一 nightly cron + 手动 | pr-gate 全套 + e2e 4 shard 矩阵 + 完整安全扫。**main 守底**,挂了说明 main 真坏,要 hotfix |
| **build-image** | push main | 构建 Docker image + push 镜像仓 + Trivy 扫漏洞 |

### Release / 上线

| Workflow | 触发 | 做什么 |
|---|---|---|
| **promote-staging** | full-ci-gate 绿 | 推 image 到 staging registry / 触发 staging 部署 |
| **staging-gate** | tag `v*` push + 手动 | 对真 staging URL 跑 Gatling 压测 / Playwright 真环境 e2e |

### 运维

| Workflow | 触发 | 做什么 |
|---|---|---|
| **capacity-gate** | 周期 cron | 容量 / load smoke 检查,失败发告警(详见 [`ci.md`](ci.md) § capacity-gate)|

### 一句话用法

| 想看 | 看哪个 |
|---|---|
| 我的 PR 能不能合 | **pr-gate + codeql** 全绿 |
| main 现在坏没坏 | **full-ci-gate** 最新一次 |
| 这次 release 能不能上 | **staging-gate**(tag 后)|
| 依赖有没有 CVE | **codeql**(代码漏洞)+ Trivy(build-image 里,镜像漏洞)|
| 我的 yml 改对了没 | **workflow-lint** |

完整 CI 设计细节 → [`ci.md`](ci.md)。

## main 分支保护(只读说明)

| 项 | 值 |
|---|---|
| Required PR | ✓ |
| Required reviewers | **0**(无人工审核要求,单人仓友好) |
| Required status checks | `unit-it-a` `unit-it-b1` `unit-it-b2` `static-checks` `security` |
| Strict(必须基于 latest main)| ✓ |
| Enforce admins | ❌(admin 可 override,紧急 hotfix 用)|
| Force push / 删除 | ❌ 禁 |
| Conversation resolution | ✓ 必须解决 review 评论 |
| Auto-merge | ✓ 允许 |
| Delete branch on merge | ✓ |

修改需 admin 在 repo Settings → Branches/Rulesets 改;改完同步本文档。

## 谁能提代码 / 权限模型

仓库是 **public**,但 push 权限只给 collaborator。任何人能 fork + cross-repo PR,但**直接 push 到仓内分支必须授权**。

### 当前权限盘点

| 设置 | 值 | 含义 |
|---|---|---|
| 可见性 | `public` | 任何人能 clone / fork / 开 cross-repo PR |
| Collaborators | 只 `pinpols`(admin) | 只 owner 能直接 push 到仓内分支 |
| Push main | branch protection 禁直接 push | 必须走 PR,**任何人**(含 admin)都不能直接 push main |
| Merge PR | write 权限以上 | 路人能开 PR,但 merge 是仓内 collaborator 点 |

### 谁能"提代码"

| 角色 | 能做什么 | 需要授权? |
|---|---|---|
| owner(`pinpols`) | clone / `git push origin feature/xxx` / 开 PR / merge | 已是 admin |
| 路人(任何 GH 用户) | fork → 自己 fork 改 → 开 **cross-repo PR** | ❌ public 仓自带 |
| 想让 ta 在仓内直接开分支 | clone / `git push origin feature/xxx` | ✓ 加 collaborator |

### 加协作者

```bash
# permission 等级:pull(只读) / triage(管 issue) / push(读写) / maintain(管 release) / admin
gh api repos/pinpols/file-batch-system/collaborators/<username> \
  -X PUT -f permission=push
```

被邀请方在 GitHub 邮件 / 通知里 accept 后生效。

### 撤销

```bash
gh api -X DELETE repos/pinpols/file-batch-system/collaborators/<username>
```

## 常见情况

**Q: 多人协作时想恢复 reviewer 要求?**
A: repo Settings → Rulesets → `main protection` → pull_request rule → `required_approving_review_count: 1`,顺手把 `enforce_admins` 也开 true。同步本表。

**Q: 误在 main 上 commit 了(没 push)**
A: `git reset --soft HEAD~1` → `git stash` → `git checkout -b feature/xxx` → `git stash pop` → 正常 PR。

**Q: PR 跟 main 漂了 conflict**
A: `gh pr update-branch <PR#>`(GH 自动 rebase / merge main)→ 解 conflict → push。Dependabot 用 `@dependabot recreate` 评论触发。

**Q: 我想撤回已 merge 的 PR**
A: `gh pr revert <PR#>` 自动开 revert PR,正常 review + merge。**不要**force-push main(已禁)。

**Q: CI 真的挂了怎么紧急 merge?**
A: 不存在"绕过 CI 紧急 merge";如果 main 已经红(被强 merge 等)走 hotfix 修绿。

## 相关文档

- CI 流水线:[`docs/runbook/ci.md`](ci.md)
- 编码规范:[`docs/coding-conventions.md`](../coding-conventions.md)
- 部署:[`docs/runbook/docker-deployment.md`](docker-deployment.md)
- Release SemVer:[`docs/runbook/releasing.md`](releasing.md)
