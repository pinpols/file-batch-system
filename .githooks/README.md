# Project-level Git Hooks

## 启用（每人 clone 后跑一次）

```bash
git config core.hooksPath .githooks
```

## 当前 hook

### `pre-commit` — Spotless auto-format

每次 `git commit` 前：
1. 检查 staged Java 文件
2. 跑 `mvn spotless:apply` 自动修格式
3. 重新 `git add` 已修改的 staged 文件
4. 继续 commit

这样 push 上去 spotless 一定通过，CI 不再提醒格式。

> **跳过 hook**（不推荐）：`git commit --no-verify`
