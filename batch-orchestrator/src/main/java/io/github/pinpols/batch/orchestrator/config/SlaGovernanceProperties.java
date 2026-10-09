package io.github.pinpols.batch.orchestrator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 任务 SLA 监控、告警与升级参数。 */
@Data
@Validated
@ConfigurationProperties(prefix = "batch.sla")
public class SlaGovernanceProperties {

  private boolean enabled = true;
  private long pollIntervalMillis = 30000L;
  private int batchSize = 200;

  @Valid
  private final JobMonitoring jobMonitoring = new JobMonitoring();

  /** 作业定义上的独立软 SLA 扫描；与硬超时和旧实例级 SLA 保持明确边界。 */
  @Data
  public static class JobMonitoring {
    private boolean enabled = true;

    @Min(5_000)
    @Max(600_000)
    private long pollIntervalMillis = 30_000L;

    @Min(1)
    @Max(100)
    private int batchSize = 50;

    /** 首次启用和积压恢复时只补报近期失败，避免扫描并告警整个历史分区表。 */
    @Min(60)
    @Max(2_592_000)
    private int failedPartitionLookbackSeconds = 3600;
  }

  /**
   * SLA 升级再触发延迟（秒）。首次告警后 {@code instance_status} 仍为 RUNNING / READY / WAITING 且 {@code
   * sla_alerted_at} 早于 {@code now - escalationDelaySeconds} 时，scanner 以 {@link #escalationSeverity}
   * 与 {@code JOB_SLA_VIOLATION_ESCALATED} alertType 再发一条告警，alert_event 按 (tenant, alertType,
   * resourceKey) fingerprint 去重，每次重复扫描会 merge 到同一行而非产生日志噪音。设为 0 表示关闭升级。
   */
  private long escalationDelaySeconds = 1800L;

  /** 升级告警的 severity（默认 ERROR）。 */
  private String escalationSeverity = "ERROR";
}
