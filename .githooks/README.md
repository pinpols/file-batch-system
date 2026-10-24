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

变更范围复用 `scripts/ci/detect-change-scope.py --staged`，门禁分组为 `hygiene`、`quality`、`docs`、`config`、`supply-chain` 和 `governance`。支持全局、分组、单门禁模式开关（总开关关闭 > 单门禁 > 分组 > 全局 > 默认阻断）；例如 `BATCH_GATE_GROUP_MODES='quality=report,docs=ignore' git commit`。`report` 会实际执行并汇总失败但不阻断，`ignore` 不执行对应检查。仅对明确选定的本地 hook 使用这些模式；CI required checks 必须阻断。

Maven 单测、Helm、Zizmor、镜像和依赖扫描保留在 pre-push / CI，避免每次提交过重。

### `pre-push` — PR 静态门禁预检

每次 `git push` 前执行 `scripts/local/pre-push-sdk-checks.sh`，按当前分支相对
`origin/main` 的改动预检高频 CI 失败项。独立检查尽量完整执行，包含 clean compile，末尾汇总所有失败；环境缺失或参数错误等无法继续的前置问题会直接终止：

变更范围使用同一分类器的 `--base <base> --head HEAD` 输入；Java 治理测试仅在 Java/Maven 或其明确入口变化时执行，不因任意 workflow 改动启动整个治理测试集。统一分组为 `local-pre-push`，支持上述模式开关。

1. 拒绝直接推送 `main` / `master`
2. 新增生产 Java 代码必须使用 `EmptyChecks`
3. 应用层不得直接依赖基础设施适配器或 Redis/Kafka 等直连客户端
4. readiness 文档、Trivy ignore、SDK 配置环境变量和 Java 可读性清单保持同步
5. Java 变更的架构/约定守卫与受影响模块 clean compile 均在一次性 detached worktree 中使用仓库 Maven Wrapper 执行，避免本机 Maven 版本或共享构建产物竞态干扰结果
6. Java、POM 或相关 CI 路由变更从干净字节码执行全部 `*ArchTest` / `*ConventionTest`

pre-push 通过统一门禁执行器，分组为 `local-pre-push`，支持 `BATCH_GATE_MODE`、
`BATCH_GATE_GROUP_MODES`、`BATCH_GATE_MODES` 和 `BATCH_GATE_ENABLED`。报告模式仍运行检查并输出实际退出码；忽略模式不执行检查；默认阻断。

本机依赖：`python3`、JDK；提交 Shell 变更需 `shellcheck`，提交 Workflow 变更需 `actionlint`。
`--skip-build` 同时跳过 clean compile 和 Java 治理测试，仅用于快速人工预检；正式 push 不应使用。

若 `git config --show-origin --get core.hooksPath` 仍指向 `.git/hooks` 的旧复制脚本，重新执行
`git config core.hooksPath .githooks`；仓库不维护复制到 `.git/hooks` 后产生的漂移副本。

> **跳过 hook**（不推荐）：`git commit --no-verify`
>
> **显式忽略本地 pre-push**（不推荐）：`BATCH_GATE_GROUP_MODES=local-pre-push=ignore git push`

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [pre-commit](pre-commit) | 在提交前执行本仓库暂存变更的轻量质量门禁。 |
| [pre-push](pre-push) | 在推送前执行本仓库的分域构建与质量检查。 |
