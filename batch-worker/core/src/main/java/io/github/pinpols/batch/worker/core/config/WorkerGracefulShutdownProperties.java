package io.github.pinpols.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 优雅停机配置（{@code batch.worker.graceful-shutdown}）。
 *
 * <p>把原先 {@code GracefulKafkaShutdown} 里的
 * {@code @Value("${batch.worker.graceful-shutdown.timeout-seconds:120}")} 收敛为类型安全配置，
 * 保留原有 key。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.graceful-shutdown")
public class WorkerGracefulShutdownProperties {

  /** 等待在途任务收敛的最长秒数。 */
  private long timeoutSeconds = 120;
}
