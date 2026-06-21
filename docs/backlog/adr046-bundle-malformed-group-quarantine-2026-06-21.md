# Backlog: 文件束到达组「确定性坏数据」隔离(quarantine)终态

> 状态:**暂不做**(已加 metric 回退,见下「已做的回退」)。仅当生产真出现畸形束组反复刷错时再上。
> 日期:2026-06-21　模块:batch-orchestrator（`FileGovernanceScheduler` / `BundleArrivalLauncher`）
> 来源:ADR-046 文件束三类束 E2E 收尾后的复审残留项 P2(两路独立复审一致命中)。
> 相关 PR:[#628](https://github.com/pinpols/file-batch-system/pull/628)（harden arrival group bundle semantics：launch 成功后才标 TRIGGERED）、[#629](https://github.com/pinpols/file-batch-system/pull/629)（命名空间化 + 本条的 metric 回退）。

## 现象 / 根因

到达组满足条件后 `FileGovernanceScheduler.triggerArrivalGroup` 调 `BundleArrivalLauncher.launchIfBundle`。
launcher 对**确定性坏数据**会 fail-fast 抛 `IllegalStateException`:

- 同组成员 `metadata_json` 携带**互相冲突的 `bundleJobCode`**（`mergeJobCode`）。
- 同组成员 `bizDate` 不一致（`mergeBizDate`，#628 后 arrival group key 已含 bizDate,正常不该再发生）。
- 有束绑定但**无 `bundleJobCode`** / 有 `bundleJobCode` 但**无可用绑定** / 缺 bizDate（`validateCandidate`）。

#628 把异常处理改成「launch 成功才标 TRIGGERED，失败保持 `WAITING_ARRIVAL`」——**这对瞬时故障(DB 抖动、orchestrator 重启)是对的**(组保持 retryable,不丢触发)。
但对**永久性坏数据**(上面这些 config/数据错,retry 永远不会成功),后果是:

| 层面 | 后果 |
|---|---|
| 调度 | 每轮 governance sweep(默认 ~30s)重新 evaluate → 重新 `launchIfBundle` → 重新抛 → 重新 catch |
| 日志 | 每 tick 打一条 ERROR(catch 块在 `updateGroupState` 幂等跳过之前,不被去重) |
| 可观测 | #629 前:**无 metric、无 audit、无终态**——运维看不到,只能翻日志 |
| 状态 | 组永远卡 `WAITING_ARRIVAL`,无逃生通道 |

触发条件:某束作业的批次清单/file_record metadata 被写坏(清单版本不一致、人工 backfill 错误等)。低概率但非零,且一旦发生是静默 livelock。

## 已做的回退(#629)

`FileGovernanceScheduler.triggerArrivalGroup` 的 launch-fail catch 加了 counter
`batch.file.arrival.bundle.launch.failed`。运维可对该 metric 配告警 → 永久坏数据从「静默刷日志」变成「可触达人工」。
**保持 retryable、不引终态**——因为有些「坏」其实是可恢复的(如迟到的成员文件补齐后绑定就齐了),引终态有误丢可恢复组的风险。这是状态主机审慎原则下的最小修。

## 本条要做的(若上)

把「瞬时 launch 失败」与「确定性配置/数据坏」分开处理,给后者一个**显式隔离终态**,停止无意义重试 + 留人工修复入口:

1. **区分异常类型**:`BundleArrivalLauncher` 对确定性校验失败抛一个**专用异常**(如 `BundleGroupInvalidException`),与瞬时 launch 失败(`LaunchService` 抛的其它 `RuntimeException`)区分。
2. **新增组级隔离状态**:`FileGovernanceScheduler` catch 到专用异常时,把组迁入新的 `QUARANTINED`/`MANUAL` 终态(不再每 tick 重试),并落一条 governance audit(带失败原因:mixed-jobCode / missing-binding / …)。瞬时失败仍走现有「保持 WAITING_ARRIVAL retryable」。
3. **人工恢复入口**:console-api 加一个「重置隔离组 → 重新 evaluate」的运维动作(走 `ConsoleOrchestratorProxyService` → orchestrator internal,符合「console 不直接写状态」红线),供修完数据后重放。
4. **状态机改动审慎**:这一条动的是 orchestrator 状态主机(到达组状态流转),改动面比看上去大——新状态要进 `FileGovernanceMapper` 的状态枚举/查询/监控投影,要和现有 `WAITING_ARRIVAL`/`TRIGGERED`/`TIMEOUT` 的语义、幂等跳过逻辑、metrics gauge 一起对齐。**必须按「先定语义再写码、小 PR」推进**,不要和功能开发混在一起。

## 为什么暂不做(YAGNI)

- 触发要「坏数据」,正常清单/scanner 写入路径不会产生混合 jobCode(同一清单一个 jobCode)。
- #628 已把 arrival group key 加 bizDate,跨 bizDate 混组这条已基本不会发生。
- #629 的 metric 已把「静默」变「可告警」,运维侧的硬伤已堵。
- 引终态/人工恢复入口是状态主机改动,风险 > 当前收益。等生产真出现该 metric 报警、且确认是高频/需自动隔离时再上。

## 验收(若上)

- 单测:`BundleArrivalLauncher` 对各畸形组抛专用异常;`FileGovernanceScheduler` 对专用异常迁 QUARANTINED、对瞬时异常保持 WAITING_ARRIVAL。
- IT:坏 metadata 组多轮 sweep 后进 QUARANTINED 且不再重试;console 重置动作能让其重新 evaluate。
- metric/audit:隔离时落 audit + 既有 `batch.file.arrival.bundle.launch.failed` counter 不回退。
