package io.github.pinpols.batch.orchestrator.application.trigger;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link StaleCreatedLaunchRecoveryScheduler} 恢复参数（{@code batch.trigger.launch.created-recovery}）。
 *
 * <p>注：{@code poll-interval-millis} 由 {@code @Scheduled(fixedDelayString=...)} 直接读 SpEL，无法走
 * Properties 注入，仅最小静默期与批大小收敛到这里。
 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.launch.created-recovery")
public class StaleCreatedLaunchRecoveryProperties {

  /** 实例创建后需静默多久才纳入恢复扫描（秒）。默认 60。 */
  private long minAgeSeconds = 60L;

  /** 单批扫描的最大记录数。默认 50。 */
  private int batchSize = 50;
}
