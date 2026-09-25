# Project-level Git Hooks

## 启用（每人 clone 后跑一次）

```bash
git config core.hooksPath .githooks
```

## 当前 hook

### `pre-commit` — 暂存区按需轻量门禁

每次 `git commit` 前：
1. 始终检查暂存区空白错误和冲突标记
2. Java 变更执行 Spotless 并重新暂存原 Java 文件
3. Shell 变更执行 `bash -n` 和 ShellCheck warning 零容忍
4. Workflow / composite action 变更执行 actionlint
5. 文档、脚本分别执行结构和登记守护；仓库卫生守护始终执行

Maven 单测、Helm、Zizmor、镜像和依赖扫描保留在 pre-push / CI，避免每次提交过重。

### `pre-push` — PR 静态门禁预检

每次 `git push` 前执行 `scripts/local/pre-push-sdk-checks.sh`，按当前分支相对
`origin/main` 的改动预检高频 CI 失败项：

1. 新增生产 Java 代码必须使用 `EmptyChecks`
2. 应用层不得直接依赖基础设施适配器或 Redis/Kafka 等直连客户端
3. readiness 文档、Trivy ignore、SDK 配置环境变量和 Java 可读性清单保持同步
4. Java 变更执行受影响模块 clean compile

本机依赖：`python3`；提交 Shell 变更需 `shellcheck`，提交 Workflow 变更需 `actionlint`。

> **跳过 hook**（不推荐）：`git commit --no-verify`
>
> **紧急跳过 pre-push**（不推荐）：`SKIP_SDK_CHECKS=1 git push`
