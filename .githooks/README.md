# Project-level Git Hooks

## 启用（每人 clone 后跑一次）

```bash
git config core.hooksPath .githooks
```

## 当前 hook

### `pre-commit` — 暂存区按需轻量门禁

每次 `git commit` 前，按暂存文件范围运行所有适用检查；一个检查失败不会阻止其他独立检查继续，最后集中列出失败门禁：
1. 始终检查暂存区空白错误和冲突标记
2. Java 变更执行 Spotless 并重新暂存原 Java 文件
3. Shell 变更执行 `bash -n` 和 ShellCheck warning 零容忍
4. Workflow / composite action 变更执行 actionlint
5. 文档、脚本分别执行结构和登记守护；仓库卫生守护始终执行

Maven 单测、Helm、Zizmor、镜像和依赖扫描保留在 pre-push / CI，避免每次提交过重。

### `pre-push` — PR 静态门禁预检

每次 `git push` 前执行 `scripts/local/pre-push-sdk-checks.sh`，按当前分支相对
`origin/main` 的改动预检高频 CI 失败项。独立检查尽量完整执行，包含 clean compile，末尾汇总所有失败；环境缺失或参数错误等无法继续的前置问题会直接终止：

1. 拒绝直接推送 `main` / `master`
2. 新增生产 Java 代码必须使用 `EmptyChecks`
3. 应用层不得直接依赖基础设施适配器或 Redis/Kafka 等直连客户端
4. readiness 文档、Trivy ignore、SDK 配置环境变量和 Java 可读性清单保持同步
5. Java 变更执行受影响模块 clean compile
6. Java、POM 或相关 CI 路由变更执行全部 `*ArchTest` / `*ConventionTest`

本机依赖：`python3`、JDK；提交 Shell 变更需 `shellcheck`，提交 Workflow 变更需 `actionlint`。
`--skip-build` 同时跳过 clean compile 和 Java 治理测试，仅用于快速人工预检；正式 push 不应使用。

若 `git config --show-origin --get core.hooksPath` 仍指向 `.git/hooks` 的旧复制脚本，重新执行
`git config core.hooksPath .githooks`；仓库不维护复制到 `.git/hooks` 后产生的漂移副本。

> **跳过 hook**（不推荐）：`git commit --no-verify`
>
> **紧急跳过 pre-push**（不推荐）：`SKIP_SDK_CHECKS=1 git push`
