package com.example.batch.orchestrator.application.trigger;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link TriggerRequestLaunchReconciler} 周期回写参数。
 *
 * <p>注:{@code poll-interval-millis} 由 {@code @Scheduled(fixedDelayString=...)} 直接读 SpEL,无法走
 * Properties 注入,仅 min-age + batch-size 收敛到这里。
 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.launch.reconcile")
public class TriggerLaunchReconcilerProperties {

  /** 静默期(秒):刚 ack 的 trigger_request 让 consumer writeBack 自处理,过该时长再兜底。默认 300。 */
  private int minAgeSeconds = 300;

  /** 单批扫描的最大记录数。默认 200。 */
  private int batchSize = 200;
}
