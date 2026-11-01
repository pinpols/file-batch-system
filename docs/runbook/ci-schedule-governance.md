# CI 变更触发与定时运行治理

本文是 `file-batch-system` 与配对前端 `batch-console` 的 CI 定时策略权威记录。具体 job 内容和 required 状态仍以各仓库 workflow、Ruleset 及 [CI Runbook](./ci.md) 为准。

## 原则

1. **代码变更优先触发**：PR 执行变更范围内的必需检查；合入后由 main push 验证主干。重型端到端测试如不适合阻塞 PR，应至少对相关 main 路径变更触发，并保留手动入口。
2. **定时任务必须有独立价值**：安全情报/扫描规则更新、runner/工具链漂移、依赖环境变化等不由源码提交驱动的风险，可以定时检查。普通编译、单测和本地容器集成测试不得仅因“每天到了”而重复执行。
3. **无变更要显式跳过**：以代码或配置变更为条件的定时验证，应产出可辨识的 `SKIP` 原因，不把跳过描述为测试通过。
4. **避免重复全量回归**：若 main push 已运行同一测试清单，额外每日全量 schedule 必须证明能发现新的独立风险；否则改为相关路径触发、低频漂移检查或手动复验。
5. **低频不等于无主**：每项 schedule 必须有维护责任、目的、UTC 时间、资源成本和移除/降频条件；仓库 workflow 的维护者承担责任，频率至少每季度复核一次。

## 当前策略

| 仓库 / 工作流 | 代码变更触发 | 定时触发 | 判断与边界 |
|---|---|---|---|
| BE `pr-gate` | PR、merge queue | 无 | 变更范围门禁；不设 nightly |
| BE `full-ci-gate` | main push（按路径排除纯文档/SDK） | 每周日 02:00 UTC | 维护责任：后端 CI；用托管 runner、外部依赖解析和全量路径路由做低频漂移检查；不是每日回归 |
| BE `daily-sim-strict-validation` | 无直接 push；每日比较最近成功验证提交与当前提交 | 每日 13:31 UTC | 代码或配置有变化、无成功基线或最近验证失败时运行真实 sim + strict；仅文档变化可跳过，手动可强制。历史查询失败时检测失败而非视为通过 |
| BE `sdk-contract-parity` | 所有 PR（job 按 SDK 变更范围执行）、merge queue | 无 | 五语言契约/live transport 随 SDK 变更验证；不做每日无变更空跑 |
| BE `sdk-orchestrator-e2e` | SDK、样例、Orchestrator、Trigger、迁移、协议和运行脚本相关 main push | 无 | 非 required 真栈 E2E；相关变更后及时验证，手动复验可用，不每日重建整套 Compose 环境 |
| BE `staging-gate` | 手动 | 无 | 与 Full CI 的 E2E 清单重叠；仅用于独立复验，不再每日重复 |
| BE CodeQL | PR、main push | 每周一 03:00 UTC | 维护责任：后端安全/CI；保留，查询规则和安全分析环境可独立更新 |
| BE fuzzing | 手动 | 每周二 03:15 UTC | 维护责任：后端安全/CI；保留为安全边界随机/性质测试，不提升为每日全量任务 |
| BE Scorecard | main push | 每周三 02:23 UTC | 维护责任：后端安全/CI；保留为仓库供应链姿态快照 |
| BE quarterly dependency review | 手动 | 每季度首日 03:00 UTC | 维护责任：依赖治理；低频盘点，不自动改依赖或创建 PR |
| FE `pr-gate` / `frontend-ci` | PR、main push；兼容套件手动 | 无 | PR 主门禁执行 Node 基线的类型、单测与构建；文档站自动构建，同版本重复兼容套件仅手动运行 |
| FE `full-ci-gate` | PR、main push | 每周一 02:00 UTC | 维护责任：前端 CI；每周 runner/依赖/门禁漂移检查；Docker/Trivy/Lighthouse 仅非 PR 事件执行 |
| FE `build-image` | main push、版本 tag | 每日 19:00 UTC | 维护责任：前端交付；定时先检查前后端前一日变更；无相关变更不构建，后端变更还要等待 BE sim 成功 |
| FE CodeQL | PR、main push | 每周一 02:30 UTC | 维护责任：前端安全/CI；保留，安全规则和漏洞情报可独立更新 |
| FE `staging-gate` | 发布 tag、手动 | 无 | 维护责任：发布验收；面向真实 staging，不做无变更 nightly |
| FE quarterly dependency inventory | 手动 | 每季度首日 03:00 UTC | 维护责任：依赖治理；仅依赖盘点，不自动升级 |

`sdk-orchestrator-e2e` 暂不直接绑定 Full 成功事件：Full 会排除纯多语言 SDK 变更，而这些变更仍必须触发真实 SDK 栈验证。若将来串行，必须同时保持纯 SDK 变更覆盖，并绑定实际通过的 commit；不能仅用 workflow 名称或最新成功 run 推断。

BE `sdk-orchestrator-e2e` 的路径过滤见 `.github/workflows/sdk-orchestrator-e2e.yml`；新增其依赖的生产模块、迁移、Compose 文件、环境模板、SDK E2E 共享脚本或启动脚本时，必须同步扩展该 `paths` 清单。路径漏配会使非 required 的全链路验证静默不触发。

## 已知边界与后续改进

前端夜间镜像门禁仍按前一日变更查找后端 sim run；当需要后端验证时，会检查成功 run 的提交覆盖目标后端代码并固定该目标提交。前端自身的变更检测仍依赖日历日，失败跨日后需要手动复验；改为上次成功构建的提交基线仍待完成。

## 定时变更检测要求

- 对重型测试/镜像构建，变更检测范围包括实现代码、测试、配置、迁移、容器/部署、脚本、依赖锁文件及 workflow 本身；仅文档变化通常不触发业务全链路测试。
- 长期采用“距上次成功验证的提交”作为稳健基线；日历日检测只能作为有明确时区和重试操作说明的临时实现。失败后无新提交时仍须有显式重验路径。
- 定时扫描使用独立身份和最小权限；计划触发、排队、跳过、失败和成功分别统计。`SKIPPED` 不是测试成功，也不应进入成功率分母当作成功。
- 若 workflow 只为验证源代码行为，优先改成 PR/main 变更触发；若需验证外部时变输入，明确该输入及其漂移为何不能由漏洞扫描/依赖更新门禁替代。

## 复核清单

每季度及变更 CI 架构时复核：

1. schedule 是否与 main/PR 已覆盖的 job 重复；是否仍有无需等 schedule 的相关代码变更路径。
2. schedule 是否有独立外部漂移源或明确的定期测试价值；没有则删除或改成变更触发。
3. 变更路径是否包含源码、测试、配置、SQL 迁移、容器、脚本、依赖锁和 workflow。
4. 失败后无新提交时是否能重跑；无变更 skip 是否给出稳定原因且不冒充 pass。
5. 多个重型工作流是否错峰；同一时段 runner 饱和是否导致排队、超时或噪声告警。
6. workflow 改动后运行 `actionlint`、路径门禁正反例和文档链接/索引检查；在线状态另查 GitHub Actions，不以本地静态检查替代。

## 关联入口

- [后端 CI Runbook](./ci.md)
- 前端 CI Runbook：`batch-console/docs/runbook/ci.md`
- [CI 与测试质量治理标准](../standards/ci-test-quality-governance.md)
- 前端测试体系：`batch-console/docs/testing/README.md`
