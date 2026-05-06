package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR-018 Stage 5/7 — 跨批量日依赖 reconciler 配置。 */
@Data
@ConfigurationProperties(prefix = "batch.workflow.cross-day-dep")
public class CrossDayDependencyReconcileProperties {

  /** 启用开关。 */
  private boolean enabled = true;

  /** 扫描周期（毫秒），默认 60s。 */
  private long pollIntervalMillis = 60_000L;

  /** 单轮处理 WAITING_DEPENDENCY 节点上限。 */
  private int batchSize = 100;

  /**
   * 默认超时秒数（节点未声明 {@code cross_day_dependency_timeout_seconds} 或 = 0 时使用）。 默认 24 小时；超时后 REQUIRED 节点
   * FAIL + alert。
   */
  private long defaultTimeoutSeconds = 86_400L;
}
