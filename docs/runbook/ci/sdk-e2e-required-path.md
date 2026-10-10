# SDK E2E 与 live-transport 门禁边界

本页说明平台 SDK 契约门禁、真实 Orchestrator E2E 和 live-transport 的当前触发与阻断边界。工作流变更后应同步核对本页与相关 workflow。

## 1. `sdk-orchestrator-e2e`(样例 worker × 真 orchestrator)

该工作流在 SDK、样例、Orchestrator、Trigger、迁移、协议和运行脚本相关 main 变更后，以及 `workflow_dispatch` 时启动真实基础设施与 Orchestrator/Trigger，执行五语言样例 worker 的 register、launch、dispatch、claim、execute、report 和终态断言。它**不是 PR required check**；但单次 workflow run 中任一语言失败都会使该 run 失败。

| lang | 当前状态 | 说明 |
|---|---|---|
| go | 执行 | `scripts/ci/run-sdk-orchestrator-e2e.sh go` |
| python | 执行 | 同一真实 Orchestrator E2E fixture |
| java | 执行 | 同一真实 Orchestrator E2E fixture |
| typescript | 执行 | 同一真实 Orchestrator E2E fixture |
| rust | 执行 | 同一真实 Orchestrator E2E fixture |

语言矩阵及硬断言由 [workflow](../../../.github/workflows/sdk-orchestrator-e2e.yml) 和共享 runner 决定；该 workflow 不配置逐语言 `continue-on-error`。

## 2. `sdk-live-transport`(真 Kafka broker + HTTP fake)

`sdk-contract-parity.yml` 内的独立 job，使用真实 Redpanda broker 验证五语言 SDK transport/lifecycle。该 job 当前是 **非 required 信号**；契约 fixture 及五语言 parity 则由 `sdk-contract-required` 聚合门禁阻断合并。`parity-report` 汇总结果，不替代 required contract checks。

### 若未来评估将 live-transport 转为 required

需要先评估 broker 稳定性、隔离策略、有效运行样本和分支保护 required context，再单独决策。本节是未来评估条件，不表示当前已有切换计划或已满足条件。

当前 required 状态以 workflow 的 `sdk-contract-required` job 及仓库 ruleset 为准；不得仅凭 Full CI、手动真栈 E2E 或 `parity-report` 绿灯推断 live-transport 已成为 required check。

## 关联

- 工作流:`.github/workflows/sdk-orchestrator-e2e.yml`、`.github/workflows/sdk-contract-parity.yml`
- runner:`scripts/ci/run-sdk-orchestrator-e2e.sh`、`scripts/ci/run-sdk-live-transport-gate.sh`
- 配置总表:`docs/sdk/config-reference.md`
