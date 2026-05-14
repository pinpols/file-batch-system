# ADR-030 · 产物内容验收 SPI（`ContentVerifier`）

- **状态**：Accepted
- **决策日期**：2026-05-14
- **关联**：架构 truth 第12轮（产物内容验收无框架）；ADR-013（Tracing/Metrics 基础设施）；ADR-021（数据对账 —— 不复用本 SPI）

## 背景

任务终态 `SUCCESS` 只保证状态机正确推进，**不**保证产物内容合格——导出可能写出空文件、
DISPATCH 可能没回执、PROCESS 可能 publishedCount=0。现状只有 E2E 测试做内容断言
（`ExportContentVerificationE2eIT`、`DispatchReceiptVerifier`），生产没有等价的运行时校验框架。

每次有人想加"再判一次产物"逻辑：
- 改 stage 的 hot path（污染业务代码）
- 没有统一的 metrics 出口（告警规则各自写）
- 没有统一的失败码命名空间（导致告警面板碎片化）

## 决策

在 `batch-common` 引入 **`ContentVerifier` SPI** + **`ContentVerifierRegistry`** + Micrometer
指标，承接所有"任务终态后的产物快照判定"。

### 接口

```java
public interface ContentVerifier {
  String code();                 // 业务码，进 metric tag
  Set<JobType> appliesTo();      // 限定适用的 job_type
  default String stageCode() {}  // null = 任何 stage
  VerifyResult verify(VerifyContext context);
}

public record VerifyContext(
    String tenantId, JobType jobType, Long jobInstanceId, Long taskId,
    String stageCode, Map<String, Object> payload) { ... }

public record VerifyResult(
    boolean passed, String code, String message,
    Map<String, Object> evidence) { ... }
```

### Registry

`ContentVerifierRegistry`（`@Component`）：
- 启动期通过 Spring `ObjectProvider<ContentVerifier>` 自动收集所有 bean
- 按 `(jobType, stageCode)` 路由：`verifiersFor(jobType, stage)` 返回适配集合
- `run(verifier, context)` 包计时与异常吞咽：
  - Verifier 抛异常 → 转成 `VerifyResult.fail(<code>_EXCEPTION, ...)`，调用方继续，不影响后续 verifier
  - 永远返回非 null 结果

### Metrics

- `batch.verifier.duration{code, outcome=pass|fail|error}` Timer
- `batch.verifier.failures{code, reason}` Counter（reason = `VerifyResult.code()`）

只把 `code` / `outcome` / `reason` 作为 tag —— 这三项是有限基数，安全。`tenantId` / `taskId`
**不**进 tag（high-cardinality 会爆 Prometheus）。

### 失败语义

Verifier 失败默认**不**中止任务。调用方决定升级路径：
- 软告警：`Metrics` 触发 Prometheus alert
- 中等：把失败写入 `outbox_event`（事件类型 `verifier.failure.v1`）让运维订阅
- 硬中止：worker stage hook 把 task 转为 `FAILED`（仅业务侧明确要求时配置）

本 PR 只交付 SPI + Registry + 1 个示例 verifier；接入硬中止的场景由后续 PR 按需添加。

## 实现路径

| 阶段 | 内容 | 状态 |
|---|---|---|
| A | SPI + Registry + 单测 | ✅ 落地 |
| B | 1 个 `ExportFileNonEmptyVerifier`（在 batch-worker-export） | ✅ 落地 |
| C | worker stage hook 接入 `AbstractPipelineStepExecutionAdapter` 成功路径 + 失败结果落入 `attributes.verifierFailures` + 透传给 `TaskExecutionReport.verifierFailures` | ✅ 落地 |
| D | `DispatchReceiptPresentVerifier` + `ProcessPublishedCountVerifier` | ✅ 落地 |
| E | worker 端透传字段就位（`TaskExecutionReport.verifierFailures` 由 hook 填充）；orchestrator 侧 outbox 持久化 + console 告警面板由 F 阶段做 | ✅ worker 端 / 🔜 orchestrator 端 |
| F | orchestrator 消费 `TaskExecutionReport.verifierFailures`，在 task SUCCESS 事务内写 `outbox_event(event_type='verifier.failure.v1')`；console realtime 订阅展示 | 🔜 后续 PR |
| G | 硬中止策略：业务侧明确"verifier 失败要把 task 翻成 FAILED"时，新增 attributes 标记让 hook 决定是否覆盖 success | 🔜 仅按需 |

## 范围红线（防越界）

参考 CLAUDE.md "ADR 实施范围纪律"：

- ✅ 任务终态后的"快照式"产物校验
- ✅ 跨 worker 一致的判定（导出非空 / 回执存在）
- ❌ 业务字段校验（field validation） —— 已有 IMPORT `PreprocessStep`
- ❌ 跨 job_instance 数据治理（数据对账） —— 走 ADR-021
- ❌ 实时合规审计流 —— 不是这个 SPI 的职责

## 替代方案（已驳回）

- **直接复用 `E2eVerifier`（测试 helper）**：测试侧接口耦合 testcontainer + builder
  pattern，不适合生产 SPI。
- **把校验逻辑写进 `AbstractTaskConsumer`**：堆积越来越多 if-jobtype，违反 CLAUDE.md "分支消除" 规则。
- **走 Spring Validation `@Valid`**：那是字段约束，不是产物级断言。

## 后果

- `batch-common` 多 3 个类 + 1 个测试；其它模块按需添加 verifier，零侵入
- 告警面板可以围绕 `batch.verifier.failures{code, reason}` 建立 SLO
- 后续若发现 verifier 跑太慢（unlikely，目前都是 in-memory 计算），可以加超时控制
