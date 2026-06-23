package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Job-level timeout 回退强制器配置。
 *
 * <p>背景：{@code job_definition.timeout_seconds} 列从 V4 起存在但无 enforcer，业务声明的"30 分钟 timeout"实际不生效（参考
 * {@code docs/analysis/orchestrator-vs-industry-2026-05-03.md} §2.1）。本 enforcer 周期扫 RUNNING 中超期的
 * {@code job_instance}，CAS 推到 FAILED 终态，让 timeout 真正起作用。
 *
 * <p>与 {@code PartitionLeaseReclaimScheduler} 的差异：lease reclaim 回退 worker 心跳丢失（worker 宕机），timeout
 * enforcer 回退业务跑得太久（worker 在但任务卡住）。两者语义不同，互补。
 */
@Data
@ConfigurationProperties(prefix = "batch.timeout")
public class TimeoutEnforcerProperties {

  /** 是否启用 timeout enforcer。默认开启。 */
  private boolean enabled = true;

  /** 扫描间隔（毫秒）。默认 60s — timeout 是分钟级语义，不需要更短。 */
  private long pollIntervalMillis = 60_000L;

  /** 单次扫描的批量大小，避免长事务。 */
  private int batchSize = 100;

  /**
   * workflow_run stuck 判定阈值（秒）。
   *
   * <p>默认 1800s（30 分钟）— 比 timeout enforcer 60s 周期宽松，让 task outcome 有充分时间正常 propagate； 阈值过短可能误判正常长跑
   * workflow。{@code WorkflowRunStuckReconciler} 选 RUNNING 中且 updated_at 早于 (now - 此值) 的
   * workflow_run 进入候选。
   */
  private long workflowStuckThresholdSeconds = 1800L;
}
