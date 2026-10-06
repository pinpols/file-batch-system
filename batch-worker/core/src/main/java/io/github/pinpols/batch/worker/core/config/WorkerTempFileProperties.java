package io.github.pinpols.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 本地临时文件配置（{@code batch.worker}）。
 *
 * <p>把原先 {@code StaleTempFileCleanup} 里的
 * {@code @Value("${batch.worker.stale-temp-file-hours:6}")} 收敛为类型安全配置，保留原有 key。
 * 该 key 直接挂在 {@code batch.worker} 前缀下（无二级分组），故本类只绑定这一个字段。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker")
@SuppressWarnings("ConfigurationProperties") // 与 WorkerConcurrencyProperties 共享前缀，子键互不重叠。
public class WorkerTempFileProperties {

  /** 临时目录中超过该小时数未更新的文件视为陈旧、可清理。 */
  private long staleTempFileHours = 6;
}
