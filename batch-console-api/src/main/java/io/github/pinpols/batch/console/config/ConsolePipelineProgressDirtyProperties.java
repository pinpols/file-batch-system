package io.github.pinpols.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pipeline-progress dirty 扫描发布器配置（{@code batch.console.pipeline-progress-dirty}）。
 *
 * <p>把原先 5 个同前缀 {@code @Value} 收敛为类型安全配置，默认值与历史 {@code @Value} 默认值保持一致。
 * 发布器仍会对取值做下限 / 上限收敛，本类只承载绑定。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.pipeline-progress-dirty")
public class ConsolePipelineProgressDirtyProperties {

  /** 扫描间隔（毫秒），发布器收敛到下限 1000。 */
  private long intervalMillis = 5_000L;

  /** 首次调度延迟（毫秒），发布器收敛到下限 0。 */
  private long initialDelayMillis = 10_000L;

  /** 回看重叠窗口（毫秒），补偿 updated_at 精度与并发写入。 */
  private long lookbackOverlapMillis = 2_000L;

  /** 同一 pipeline 的最小发布间隔（毫秒），发布器收敛到下限等于扫描间隔。 */
  private long throttleMillis = 10_000L;

  /** 单次扫描最大行数，发布器收敛到 [1, 2000]。 */
  private int batchSize = 500;
}
