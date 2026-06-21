# E2E 分级策略(smoke / critical / regression)

> R3-2 闭环 Round-1 TOP-7 — e2e 反馈周期从「全跑 9h」降到「pr-gate 15min」。
>
> 落地 PR:`feature/r3-2-e2e-tier-smoke-pr-gate`(2026-06-02)

## 背景

`batch-e2e-tests` 共 26 个 `*E2eIT`,串行总耗 ~9h,4 shard 并发后 ~10min wall-clock,
但仍占 pr-gate 时间预算的 60%+。问题:**绝大多数 PR 改 1-2 模块,跑全量 E2E 性价比
极低**。Round-1 TOP-7 反馈"pr-gate 等不起 25min,实际要 10min 内拿到信号"。

## 三级标签

JUnit5 `@Tag(...)` 类级别标注(已写到 `*E2eIT.java` class 上),Maven Surefire
profile `e2e-smoke` 用 JUnit5 tag 表达式 `smoke | critical` 过滤。

| Tag | 用途 | 触发器 | 测试数 | 时长 |
|---|---|---|---|---|
| `smoke` | 主链 happy path,**绝不能漏** | pr-gate | 4 | ~5min |
| `critical` | 关键场景(失败/补偿/多租/DLQ) | pr-gate | 8 | ~10min |
| `regression` | 默认(无显式标),覆盖边界/load/sensor | nightly staging-gate | ~14 | ~10min(并发) |

> 所有 e2e 测试已有的 `@Tag("e2e")` 不动(作为「与 unit IT 区分」的总开关)。

## 当前分类(2026-06-02)

### smoke(4)— 主链单条 happy path,4 类核心 worker 各一

| 测试 | 覆盖主链 |
|---|---|
| `ImportPipelineE2eIT` | IMPORT pipeline(register→launch→claim→execute→report) |
| `ExportPipelineE2eIT` | EXPORT pipeline 全链 |
| `ProcessPipelineE2eIT` | PROCESS pipeline 全链 |
| `DispatchPipelineE2eIT` | DISPATCH pipeline 全链 |

### critical(8)— 关键正确性、可靠性、多租隔离

| 测试 | 关键点 |
|---|---|
| `AtomicTaskPipelineE2eIT` | Atomic worker SPI 主链(shell/sql 等专属隔离) |
| `DedupJobLaunchE2eIT` | launch 去重(同一 bizDate 重复 launch) |
| `OutboxForwarderE2eIT` | Outbox→Kafka forwarding 关键路径 |
| `FullChainTenantPropagationE2eIT` | tenant_id 跨模块传递守护 |
| `TriggerAsyncLaunchFullChainE2eIT` | trigger fire→orchestrator launch 异步链路 |
| `ImportFailurePipelineE2eIT` | 失败回滚 + outbox retry |
| `MultiTenantConcurrentE2eIT` | 多租户并发(t1/t2 不互相污染) |
| `DeadLetterApprovalReplayE2eIT` | DLQ + 审批 + replay 闭环 |

### regression(14,默认)

剩余无 smoke/critical 标记的 `*E2eIT`,nightly 全量跑。代表:`OutboxPollMarginsYamlE2eIT`、
`AtomicTaskLoadE2eIT`、`SensorWaitFixtureE2eIT`、`WorkerProcessRestartRecoveryE2eIT` 等。

## 归类规则(新加 / 改 e2e 时按此判断)

新加 `*E2eIT` 时,**默认不加** smoke/critical 标(即 regression),只有满足以下条件
才升级:

### smoke 准入(任一)
1. 测试覆盖「主链一条 worker 类型(IMPORT/EXPORT/PROCESS/DISPATCH/ATOMIC)」的 **happy path**。
2. **不依赖** 失败注入、retry、补偿、replay。
3. 单测能稳定 < 90s(含 testcontainers 启动,本地实测)。

### critical 准入(任一)
1. 失败 / 补偿 / outbox retry / DLQ / 审批等**可靠性主路径**。
2. 多租隔离 / tenant 传递守护。
3. 异步链路关键 join(trigger→orchestrator、orchestrator→worker)。
4. 业务防重(去重、唯一约束)。
5. SPI 关键扩展点(Atomic worker 等)。

### 默认 regression(无标)
1. 边界/极端值(margins、超时、大 payload)。
2. Load / 压力。
3. Sensor / 长 wait fixture。
4. 重启 / drain 等运维场景(非主链)。

## 触发器对照

| Workflow | 何时跑 | 包含 |
|---|---|---|
| `pr-gate.yml` job `e2e-smoke` | PR open / push 到非-main 分支 | `smoke + critical`(12 个,~15min) |
| `full-ci-gate.yml` job `e2e-shard` | push 到 main | **全量** 4 shard 并发(沿用旧分组) |
| `staging-gate.yml` job `e2e-shard-full` | 每天 02:00 北京时间 + workflow_dispatch | **全量** 4 shard 并发(~10min) |

> full-ci-gate 仍跑全量,作为 main push 的最终守护。staging-gate 是 nightly + 手动
> 回退,主要价值是「下班期间在 staging branch 上对 release candidate 全跑」。

## 命令速记

```bash
# 本地只跑 smoke + critical(等价 pr-gate)
mvn -pl batch-e2e-tests test -Pe2e-smoke

# 本地只跑 smoke
mvn -pl batch-e2e-tests test -Dgroups=smoke

# 本地只跑 critical
mvn -pl batch-e2e-tests test -Dgroups=critical

# 本地全跑(等价 staging-gate)
mvn -pl batch-e2e-tests test

# 本地只跑 regression(排除 smoke/critical)
mvn -pl batch-e2e-tests test -DexcludedGroups='smoke,critical'
```

## 标签维护

- **加新 E2eIT**:先按归类规则判断,默认不加 smoke/critical(走 regression)。
- **升级 regression → critical**:在 PR 描述说明「这是哪类可靠性主路径」,reviewer
  按上方准入条件审。
- **降级 critical → regression**:谨慎,优先看是否真的属边界场景(如曾误升)。
- **smoke 不轻易扩**:smoke 名额按 worker 类型上限 4-5 个,防膨胀回到「全跑」窘境。

## 反预期场景

- pr-gate 漏掉 regression bug:**预期** — regression 的设计就是 nightly 回退,fail
  时回滚 staging branch / hotfix。如果同类 bug 反复在 regression 上失败,考虑升级该
  case 到 critical。
- smoke 跑得不稳:**严重** — smoke 是反馈周期的根基,flaky 必须当 P0 修。

## 关联

- Round-1 TOP-7 反馈:`docs/analysis/...`(TOP-7 表)
- e2e shard 分组依据:`docs/runbook/e2e-it-optimization-2026-05-22.md`
- 测试约定:`CLAUDE.md` §测试约定
