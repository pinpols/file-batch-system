package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker capability tags 审计配置（{@code batch.worker.audit}）。
 *
 * <p>把原先 {@code WorkerCapabilityTagsAuditScheduler} 里的
 * {@code @Value("${batch.worker.audit.capability-tags-log-sample-limit:10}")} 收敛为类型安全配置，
 * 保留原有 key。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.audit")
public class WorkerCapabilityTagsAuditProperties {

  /** 单轮审计日志最多打印多少条样例明细。 */
  private int capabilityTagsLogSampleLimit = 10;
}
