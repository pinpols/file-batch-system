# SDK e2e / live-transport — 非阻塞 → 必需(required)收口路径

本页记录两条 SDK CI 信号从「非阻塞、易 flake」演进到「分支保护 required check」的判定标准与操作步骤。改动 `.github/workflows/sdk-orchestrator-e2e.yml` 与 `.github/workflows/sdk-contract-parity.yml` 的 gating 时对照本页。

## 1. `sdk-orchestrator-e2e`(样例 worker × 真 orchestrator)

真栈往返(register / claim / report / 心跳 directive),`nightly cron + workflow_dispatch` 触发,**始终不进分支保护 required checks**(全栈启动数分钟 + 偶发 flaky,不适合每-PR 硬门禁)。矩阵内每条腿的 `continue-on-error` 是「这条腿本身是否让整个 workflow run 判失败」的开关。

| lang | 当前状态 | 说明 |
|---|---|---|
| go | 阻塞(`continue-on-error=false`) | 已校准的真往返信号 |
| python | 阻塞 | 同上 |
| java | 非阻塞(`continue-on-error`) | #649 GA 接入,runner 已支持真往返;绿稳一轮后转阻塞 |
| typescript | 非阻塞 · **scaffold** | 矩阵已登记,但 `scripts/ci/run-sdk-orchestrator-e2e.sh` 尚无 ts case;workflow 里先占位打 `PENDING` |
| rust | 非阻塞 · **scaffold** | 同 typescript |

### typescript / rust 收口步骤

1. 由各自语言 agent 给 `scripts/ci/run-sdk-orchestrator-e2e.sh` 补 `typescript)` / `rust)` 的 build + start case(消费 `examples/self-hosted-sdk/sample-tenant-worker-{typescript,rust}`),对齐 go/python/java case 的 worker_code / API-key seed / topic 预建约定。
2. 删除 `sdk-orchestrator-e2e.yml` 里 Run 步骤对 `typescript|rust` 的 `PENDING` 占位分支,改为直接 `bash scripts/ci/run-sdk-orchestrator-e2e.sh "${{ matrix.lang }}"`。
3. 首次 `workflow_dispatch` 跑通阶段 A(注册 + 心跳落 `worker_registry`),校准阶段 B 派单路由。
4. 一条腿连续 N 次 nightly 绿(建议 N≥5)后,从 `continue-on-error` 表达式里去掉该 lang,转阻塞。

> 注:该 workflow 整体不进 required checks;去 `continue-on-error` 只是让 nightly run 在该腿红时整体判红(告警更硬),不是 PR 门禁。

## 2. `sdk-live-transport`(真 Kafka broker + HTTP fake)

`sdk-contract-parity.yml` 内的独立 job,起真 Redpanda broker 打五语言 SDK 的 transport/lifecycle,是 fake-only contract 之外唯一覆盖真 offset-commit / rebalance 的信号。当前 **非 required**(真 Kafka 偶发 flake:broker 启动竞态、offset 提交时序)。`parity-report` 只 log-only 汇总,不 gate。

### 转 required 的前置(flake 治理)

1. **broker 就绪探针**:`Start Redpanda` 后加显式 `rpk cluster health` / `nc -z` 轮询直到 broker 可用,替代裸 `sleep`(消除启动竞态)。
2. **job 级重试隔离**:对 live-transport step 加一次自动重试(如 `nick-fields/retry` 或脚本内 `for attempt in 1 2`),把「首跑偶发」与「真回归」区分开;重试仍红才判失败。
3. **topic / group 隔离**:每次 run 用带 `${{ github.run_id }}` 后缀的 topic / consumer-group,避免并发 run 之间 offset 串扰(参考 sim harness 的 batchNo 隔离教训)。
4. **判定标准**:连续 20 个 run(PR + nightly 混合)零非回归性红后,把 `sdk-live-transport` 加入分支保护 required checks,并把 `parity-report` 对它的依赖从 log-only 提升为硬 gate(或单独设 required)。

### 操作(满足前置后)

- 分支保护(仓库 settings / ruleset)required status checks 勾选 `sdk live transport (Kafka + HTTP fake)`。
- 本页表格状态更新为 required,并在 `docs/changelog.md` 记一笔。

## 关联

- 工作流:`.github/workflows/sdk-orchestrator-e2e.yml`、`.github/workflows/sdk-contract-parity.yml`
- runner:`scripts/ci/run-sdk-orchestrator-e2e.sh`、`scripts/ci/run-sdk-live-transport-gate.sh`
- 配置总表:`docs/sdk/config-reference.md`
