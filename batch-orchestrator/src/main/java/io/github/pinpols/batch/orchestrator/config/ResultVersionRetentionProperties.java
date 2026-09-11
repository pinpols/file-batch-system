package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR-017 §GC / 保留策略 — result_version 保留窗口配置。 */
@Data
@ConfigurationProperties(prefix = "batch.result-version.retention")
public class ResultVersionRetentionProperties {

  /** 启用开关。设为 false 时 scheduler 直接 skip。 */
  private boolean enabled = true;

  /** 扫描周期（毫秒），默认 1h。 */
  private long pollIntervalMillis = 3_600_000L;

  /** 单轮处理上限。 */
  private int batchSize = 500;

  /** SUPERSEDED 超过此天数 → ARCHIVED；payload_json 同时清空（释放 JSONB 体积）。 */
  private int supersededDays = 90;

  /** ARCHIVED 在 batch 热表保留此天数；archive 镜像不由该任务删除。 */
  private int archivedDays = 365;
}
