package com.example.batch.common.observability;

/**
 * Micrometer 指标命名规范常量。
 *
 * <p>命名约定:{@code batch.<module>.<area>.<metric>.<unit>}
 *
 * <ul>
 *   <li>module = {@code trigger / orchestrator / worker / console}
 *   <li>area = 业务域(job / outbox / quartz / wheel / dispatch / process / audit ...)
 *   <li>metric = 计量项(total / duration / failure / lag / count ...)
 *   <li>unit = 可选,Timer / Histogram 必须带 seconds / ms / bytes;Counter / Gauge 可省
 * </ul>
 *
 * <p>tag 命名:全部 snake_case,优先用 tenantId / jobType / status / errorCode 这些跨模块 通用 tag 名;避免高基数 tag(如
 * jobInstanceId)直接打。
 *
 * <p>本类只放跨模块共享的指标名常量,模块私有指标在各模块 Metrics 类自管。
 */
public final class BatchMetricsNames {

  // ─── job 生命周期(orchestrator 主导) ───────────────────────────────
  public static final String JOB_DURATION_SECONDS = "batch.orchestrator.job.duration.seconds";
  public static final String JOB_COMPLETION_TOTAL = "batch.orchestrator.job.completion.total";
  public static final String JOB_FAILURE_TOTAL = "batch.orchestrator.job.failure.total";

  // ─── workflow 生命周期(orchestrator 主导) ──────────────────────────
  public static final String WORKFLOW_DURATION_SECONDS =
      "batch.orchestrator.workflow.duration.seconds";
  public static final String WORKFLOW_COMPLETION_TOTAL =
      "batch.orchestrator.workflow.completion.total";

  // ─── outbox 共用(各模块自有 outbox 表都用,加 module tag 区分) ─────
  public static final String OUTBOX_BACKLOG_GAUGE = "batch.outbox.backlog";
  // 注:OUTBOX_PUBLISH_LATENCY 常量已删(2026-06-03 audit) — 实际 Timer 用
  // "batch.outbox.publish.duration"(DefaultScheduleForwarder @Timed),
  // 字符串从未被 import,保留只会误导后续接入,以 @Timed 字面量为单一权威源.
  public static final String OUTBOX_GIVE_UP_TOTAL = "batch.outbox.events.give_up.total";

  // ─── claim 链路(worker 抢任务) ────────────────────────────────────
  public static final String CLAIM_LATENCY_MS = "batch.worker.claim.latency.ms";
  public static final String CLAIM_FAILURE_TOTAL = "batch.worker.claim.failure.total";

  // ─── ADR-046 P2 多行 claim/report 批量(控制面 churn O(N)→O(N/K)) ───
  /** 单次 claim-batch 的 partition 数分布(DistributionSummary):看 K 实际多大 = churn 省了多少。 */
  public static final String BATCH_CLAIM_SIZE = "batch.task.batch_claim.size";

  /** claim-batch 逐项 outcome 计数(Counter,tag outcome=claimed|skipped)。 */
  public static final String BATCH_CLAIM_ITEMS_TOTAL = "batch.task.batch_claim.items.total";

  /** 单次 report-batch 的 partition 数分布(DistributionSummary)。 */
  public static final String BATCH_REPORT_SIZE = "batch.task.batch_report.size";

  /** report-batch 逐项 outcome 计数(Counter,tag outcome=ok|failed):批内部分失败可观测。 */
  public static final String BATCH_REPORT_ITEMS_TOTAL = "batch.task.batch_report.items.total";

  /** 批量 outcome tag key。 */
  public static final String TAG_OUTCOME = "outcome";

  // ─── tag key 标准化(避免各处 hard-code "tenantId" / "tenant_id" 漂) ───
  public static final String TAG_TENANT = "tenant_id";
  public static final String TAG_JOB_TYPE = "job_type";
  public static final String TAG_STATUS = "status";
  public static final String TAG_ERROR_CODE = "error_code";
  public static final String TAG_MODULE = "module";
  public static final String TAG_WORKER_TYPE = "worker_type";
  // ADR-026 dry-run:job 终态指标按 dry_run 维度切分,避免演练流量污染真实 SLA / 错误率统计。
  // 值固定 "true" / "false",低基数,可以安全打到 completion + failure 计数器。
  public static final String TAG_DRY_RUN = "dry_run";

  private BatchMetricsNames() {}
}
