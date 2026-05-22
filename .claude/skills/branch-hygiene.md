# Branch Hygiene

强制 5 条规则，避免 PR / 分支残留 + 过期 base 上写代码。本 session 已踩过 6+ 次坑(误 commit 到 `feature/fe-bugfixed`、PR #23/26/27/28 合后本地 3 个分支没删、5 个 renovate PR 积压、Bash cd 持久化导致 commit 落错仓/错分支)。

## When to apply

每次涉及 git 分支 / PR 操作时；session 结束前最后扫一遍。

## 5 条规则

### 0. 开工前 — 同步 main + 切到特性分支

每次动代码前 **4 步,顺序重要**:

1. `git fetch origin --prune` — 拉最新远程 + 清死引用
2. `git checkout main && git pull --ff-only` — 落后就停下来想为什么(为何 ff 不上?是不是有人推了不应该推的 / 是不是本地 main 有未推的脏 commit)
3. `git log --oneline origin/main..feature/<name>` 或反向 — 看特性分支 vs main 差几条;落后 main 太多先 rebase / merge main 进来再开干,**不要在过期 base 上写代码**
4. `git checkout feature/<name>` 或 `git worktree add ../wt-xxx feature/<name>`;**绝不在 main 直接写代码**

**worktree 推荐场景**:跨仓 / 并行两条独立分支 / 想保留 main 干净 working tree 备查。

合并 / PR 之前 **再 `git fetch + 比一次 vs origin/main 差异`**,避免推上去才发现冲突。

### 1. 合 PR **必带** `--delete-branch`

```bash
# ❌
gh pr merge 27 --squash

# ✅
gh pr merge 27 --squash --delete-branch
```

例外:branch 上还有后续依赖 PR(stacked PRs)。这时显式说明再省略。

### 2. 推代码前 **先 `git branch --show-current`** 对一遍

每次 `git push` / `git commit` 前确认当前分支名是预期的。本 session 真实坑:在 BE 仓 cd 后没注意 git 当前分支是 `feature/fe-bugfixed`(从其它 session 留下的),直接 commit + push,污染了别人的分支。

```bash
# ✅ commit 前 1 行核对
git branch --show-current
# 不是预期 → git checkout main && git checkout -b <真正的分支名>
```

### 3. 合后 **本地同名分支必删**

`gh pr merge --delete-branch` 只删远程。本地的需要单独删:

```bash
# 合后回 main 同步,然后清本地
git checkout main && git pull --ff-only
git branch -D <分支名>           # 单个
# 或一次清所有"远程已删"的本地分支:
git fetch --prune && \
  git branch -vv | awk '/: gone\]/{print $1}' | xargs -r git branch -D
```

### 4. Session 结束前扫一次 stale

报告(不自动删,让人确认):

```bash
git branch -vv | awk '/: gone\]/{print "  stale local: "$1}'
gh pr list --state open --limit 20  # 本仓有没有忘合的 PR
git branch -r | grep -v 'HEAD ->' | wc -l  # 远程分支数 vs 预期数
```

## Quick Reference

| 时机 | 命令 |
|---|---|
| 开工前 | `git fetch --prune && git checkout main && git pull --ff-only && git log --oneline origin/main..feature/<br>` |
| 切分支 | `git checkout feature/<br>` 或 `git worktree add ../wt-xxx feature/<br>` |
| commit 前 | `git branch --show-current` 核对 |
| 合 PR 前 | `git fetch && git log --oneline origin/main..HEAD`(再确认 base 没漂) |
| 合 PR | `gh pr merge N --squash --delete-branch` |
| 合后 | `git checkout main && git pull --ff-only && git branch -D <br>` |
| session 末 | `git fetch --prune && git branch -vv \| awk '/: gone\]/{print $1}'` |

## Red flags — 出现就停

- 准备写代码但当前还在 `main` 分支(不是 feature/*)
- 准备 push 但没核对 `git branch --show-current`
- `git pull --ff-only` 报 "Not possible to fast-forward"(本地 main 脏了,先排查别 reset)
- 特性分支落后 origin/main ≥10 commit 还在上面继续写
- 合 PR 但漏 `--delete-branch`
- 本地 `git branch` 列表里有 ≥3 个非 main / 非 feature/* 的临时分支
- 同仓 `gh pr list --state open` 累积 ≥3 个机器人 PR(Renovate / release-please)
- 跨仓操作(BE/FE)前没 `cd` 确认 + `git branch --show-current` 双核对

## 跨仓陷阱(本 session 反复中招)

Bash tool 的 working directory 跨命令持久化,但 git 当前分支是 **磁盘状态** 不是 shell 状态。`cd repo-a && git checkout x` 后再 `cd repo-b && git checkout y` 是两个独立 checkout。但只 `cd repo-a` 后下一个命令默认在 repo-a。

→ 多仓操作时,每条 git 命令前显式 `cd <绝对路径>` 或一行内 `&&` 串。
