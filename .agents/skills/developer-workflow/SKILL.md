---
name: developer-workflow
description: 处理 file-batch-system 的开发环境、本地构建、Docker/Compose 开发部署与联调；按需路由配置、脚本、CI 和验收专项 Skill。
---

# 开发工作流总控

## 目标与边界

用于开发人员准备或诊断本地环境、构建项目、构建开发镜像、启动本地/测试 Compose 环境及进行前后端联调。它是流程入口，不替代 Runbook、仓库脚本或领域专项 Skill，也不代表生产部署授权。

## 工作方法

1. 先确认目标是源码构建、单模块开发、全栈本地联调、Docker 镜像构建还是 Compose 开发环境；检查仓库状态、运行时版本、已有进程/容器及目标数据环境，保留用户已有状态。
2. 以 [`docs/runbook/local-development.md`](../../../docs/runbook/local-development.md)、[`docs/runbook/docker-deployment.md`](../../../docs/runbook/docker-deployment.md) 和脚本自身帮助为操作依据。不要从旧报告或记忆拼装过期命令。
3. 依据任务路由专项流程：
   - 环境变量、Compose/Helm、镜像版本和密钥：`configuration-governance`。
   - 模块配置、测试、容器和部署间的所有权/漂移：`module-config-boundaries`。
   - Maven/测试/业务验收及证据分层：`acceptance-validation`。
   - Shell、Python 或独立 SQL 的修改与审查：`script-sql-governance`。
   - CI 镜像构建、发布工作流和 required checks：`ci-governance`。
   - API 或前后端联调：`frontend-backend-contract`。
4. 提交/推送前预检按需组合 `git-pr-workflow`、`code-review-and-gates` 和 `ci-governance`：
   - 确认目标分支、当前 head、暂存/未暂存/未跟踪文件及完整目标分支差异；不暂存、提交或推送。
   - 读取当前 CI workflow、路径过滤、required checks、本地钩子与门禁说明，把本次改动映射到可能触发的检查。
   - 人工复核改动可能导致的 CI 失败点，核对现有本地钩子和验证结果；本地钩子已覆盖的检查不重复运行。
   - 将检查分为已有证据、需补的轻量检查、重型本地检查和只能由 CI/目标环境提供的证据；给出重检查的风险、优先级和准确命令。
   - 预检默认是审查和检查规划，不自动运行重型测试、Sim、Sonar 或远端工作流。用户明确要求执行验证时，再运行相关且未完成的检查并按真实结果报告。
5. 只执行用户请求范围内的构建和本地开发环境操作。运行前确认目标是本机/隔离测试环境，不连接生产资源；按需串行使用共享数据库、端口、消息代理和 Worker。
6. 删除 volume、清库、重置迁移、清理用户文件、覆盖已有镜像标签或修改共享环境都可能丢失状态。不得把这些动作当作普通构建清理；先检查具体影响，遇到会破坏或覆盖数据的步骤时暂停并说明。
7. 记录实际命令、revision、运行时/镜像版本、环境、退出码和日志位置。区分本地构建、容器启动、业务验收、CI 与 staging/生产部署，不将其中一项推断为另一项通过。

## 报告

简要说明采用的 Runbook/脚本、完成的构建或启动步骤、验证结果、环境边界和未执行项。失败时保留首个有效错误并分类为代码、配置、环境或工具链问题。

提交/推送前预检另输出变更范围、适用 CI 检查映射、AI 复核发现、已有本地证据、建议执行的重检查及未验证风险；不把“代码审查未发现问题”表述为“CI 已通过”。

## 当前文档与脚本的方法补充

排查工作树间干扰、旧包或本地启停/构建归属时，按需读取[工作树、构建产物与本地生命周期](references/worktree-lifecycle.md)。只加载相关方法，不把文档列出的操作自动加入执行范围。
