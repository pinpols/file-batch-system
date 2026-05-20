package com.example.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Webhook 投递 Relay 调度参数。
 *
 * <p>之前散落在 {@link com.example.batch.console.service.WebhookDeliveryRelay} 字段上的 3 个
 * {@code @Value};收敛到这里方便联调时 一次性 override。
 */
@Data
@ConfigurationProperties(prefix = "batch.webhook.relay")
public class WebhookRelayProperties {

  /** Relay 轮询间隔(ms)。默认 60 000(1 分钟)。 */
  private long pollIntervalMillis = 60_000L;

  /** 单批最多扫多少条 retry 记录。默认 50。 */
  private int batchSize = 50;

  /**
   * Relay 端绝对最大重试次数(含 dispatcher 的 burst attempts)。默认 8 = dispatcher 3 + relay 5。 达到该值标 GIVE_UP +
   * 报警。
   */
  private int absoluteMaxAttempts = 8;
}
