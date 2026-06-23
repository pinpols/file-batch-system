package io.github.pinpols.batch.worker.dispatchs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 分发回执轮询配置属性。 */
@Data
@ConfigurationProperties(prefix = "batch.worker.dispatch.receipt-poll")
public class DispatchReceiptPollProperties {

  private boolean enabled = true;
  private long intervalMillis = 60_000L;
  private int batchSize = 50;

  /**
   * 仅轮询 dispatched_at &gt; now() - 此值 的记录,防止历史 zombie PENDING 行(如旧 E2E 测试遗留)永远刷 WARN。 默认 7
   * 天,生产可调更短(如 24h);超时未确认的 PENDING 应由独立的 zombie-archive 任务处理。
   */
  private long pendingMaxAgeSeconds = 7L * 24 * 60 * 60;
}
