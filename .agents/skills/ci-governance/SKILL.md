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
- 门禁统一经 `scripts/ci/run-gate.sh` / `scripts/lib/gate-result.sh` 输出；状态行字段顺序固定为状态、`code`、`gate`、`group`、`mode`、`exit_code`、`action`，补充信息只追加 `reason`。新增门禁必须分配稳定分组，并在 `scripts/ci/README.md`、`docs/runbook/ci.md` 和守卫矩阵登记触发范围。

## 工作流与工具索引

- 按 `.github/workflows/README.md` 核对全部 workflow，按 `.github/actions/README.md` 核对共享步骤，不能只维护 PR/Full 两条流程的说明。
- 新增、改名、删除同步真实跳转、用途、触发条件与写入边界；`check-script-governance.py` 核对索引及脚本头，`scripts/ci/baselines/` 的预算只按已审查差异维护。路径迁移后仍须比对目标分支原预算，不能因旧路径不存在而静默跳过防增长检查。

## Workflow 变更

1. 先确认触发条件、权限、并发组、路径过滤和 required check 名称是否符合实际保护规则。
2. 对 `pull_request_target`、`workflow_run`、写权限 token、自动 approve/auto-merge、下载 artifact 后执行等模式按危险触发器处理；必须有清晰信任边界和最小权限。
3. 按需门禁只能降低无关任务成本，不能让相关变更漏检。路径过滤要覆盖生成文件、脚本、Helm、Compose、文档索引和 paired frontend 契约。
4. 不把失败清理、重跑、取消 run 作为修复。清理 Actions 只在用户明确要求时执行，并限定 failed/cancelled/timed_out 等已结束 run。

## 扫描成本与覆盖面优化

- 先区分安全扫描、静态质量分析、测试、构建和文档守卫；不能用安全扫描 Skill 代替 Sonar/PMD/SpotBugs 等工程质量扫描的路由与结论。
- 优化前记录基线 revision、扫描规则/工具版本、触发条件、实际扫描模块/文件域、耗时和结果类型；优化后在可比条件下复测，并记录覆盖与耗时变化。
- 任何缓存、分片、增量扫描或路径过滤都要验证相关修改会触发扫描、未修改债务不会冒充新增问题；检查 required check 名称、完整扫描路径和报告型结果是否仍可区分。
- 用代表性正反例验证路径分类：相关变更必须执行应有扫描，无关变更可跳过；不得通过删除规则、缩小关键范围、转为 `REPORT`/`SKIP` 或取消 required 状态来制造提速结论。
- 静态分析全量与增量结果分别报告。增量扫描通过不代表全量基线或 Quality Gate 通过；本地耗时改善也不代表 CI 工作流收益，须分别取证。

## 验证与证据

- 修改 workflow 后运行适用的静态检查，例如 `actionlint`、`zizmor` 或仓库封装脚本；没有安装时说明缺口。
- 在线验证要读取真实 check/run 状态和失败日志，不用 UI 猜测替代。
- PR 描述或收尾报告中分别列出本地预检、PR required checks、full gate、sim/staging 的状态。
- 新增或更名门禁时同步 `docs/runbook/ci.md`、本地钩子说明和 required checks 文档。

## 失败诊断与门禁演进

- 门禁失败时先保留失败命令、首个有效错误和 revision，判断是产品代码/测试缺陷、环境或依赖故障、门禁本身误报；不能用重复运行或清理失败记录代替诊断。
- 代码或测试缺陷应修复根因；修后先重跑失败门禁，再跑受影响的完整验证域。环境故障说明影响并恢复环境后重测，不能标为代码通过。
- 只有最小复现证明误报、错误范围或过期契约时才改门禁。新增/修改规则应验证正例与反例、增量与全量路径及相关 workflow；对增量规则确认未变更违规不会误报、变更新增违规会被拦截。
- 检查器保留详细诊断，最终状态使用统一格式；给新格式或分组加正反例，并确认 workflow 路由下的 group 与文档分类一致。
- 不以扩大豁免、全局 suppress、降低 required 状态或弱化断言来消除红灯。确需例外时限定文件/规则/期限与理由，并遵循项目登记流程。

## 常见收口

- Dependabot 或 bot PR 策略变化要同时检查 `.github/dependabot.yml`、自动合并 workflow、ruleset 与安全扫描门禁。
- 失败 run 清理前先确认不会删除仍需追溯的证据；清理后保留 run id、分支和结论摘要。
- 合并后仍要跟踪目标分支 `full-ci-gate`，不要只凭 PR checks 认定主干已全绿。

## 历史经验方法

优化分片/选测/缓存或复核守卫范围和历史误报时，按需读取 [历史经验方法](references/historical-methods.md)。只复用调查与验证方法，当前规则和原始证据分别维护。

## 脚本行为与运行证据

新增或更名入口时，沿workflow→shell/Python→SQL/fixture→最终报告核对输入、退出码和副作用；参考`script-sql-governance`的运行时方法。命令名含verify、plan或dry-run并非零写入保证，`SKIP/NOT_FOUND`也不能由汇总退出0转成全通过。应用制品的部署晋级改用`release-deployment`；本技能负责workflow的触发、权限和门禁，不重复维护部署状态机。
