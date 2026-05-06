package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.sla")
public class SlaGovernanceProperties {

  private boolean enabled = true;
  private long pollIntervalMillis = 30000L;
  private int batchSize = 200;

  /**
   * SLA 升级再触发延迟（秒）。首次告警后 {@code instance_status} 仍为 RUNNING / READY / WAITING 且 {@code
   * sla_alerted_at} 早于 {@code now - escalationDelaySeconds} 时，scanner 以 {@link #escalationSeverity}
   * 与 {@code JOB_SLA_VIOLATION_ESCALATED} alertType 再发一条告警，alert_event 按 (tenant, alertType,
   * resourceKey) fingerprint 去重，每次重复扫描会 merge 到同一行而非刷屏。设为 0 表示关闭升级。
   */
  private long escalationDelaySeconds = 1800L;

  /** 升级告警的 severity（默认 ERROR）。 */
  private String escalationSeverity = "ERROR";
}
