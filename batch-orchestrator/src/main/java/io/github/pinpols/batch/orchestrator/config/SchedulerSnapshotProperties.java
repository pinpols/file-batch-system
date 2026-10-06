package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调度快照配置（{@code batch.scheduler}）。
 *
 * <p>把原先 {@code TenantSchedulerSnapshotRecorder} 里的
 * {@code @Value("${batch.scheduler.snapshot-persist-enabled:true}")} 收敛为类型安全配置，保留原有 key。
 */
@Data
@ConfigurationProperties(prefix = "batch.scheduler")
public class SchedulerSnapshotProperties {

  /** 是否把租户调度快照持久化到 PG；false 时跳过写库。 */
  private boolean snapshotPersistEnabled = true;
}
