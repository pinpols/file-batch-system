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

  /** 回执 HTTP 连接超时，单位毫秒。 */
  private long connectTimeoutMillis = 5_000L;

  /** 回执 HTTP 读取超时，单位毫秒。 */
  private long readTimeoutMillis = 15_000L;

  /** 回执 HTTP 写入超时，单位毫秒。 */
  private long writeTimeoutMillis = 5_000L;

  /** 回执 HTTP 单次调用总超时，单位毫秒。 */
  private long callTimeoutMillis = 30_000L;

  /**
   * 仅轮询 dispatched_at &gt; now() - 此值 的记录,防止历史 zombie PENDING 行(如旧 E2E 测试遗留)永远刷 WARN。 默认 7
   * 天,生产可调更短(如 24h);超时未确认的 PENDING 应由独立的 zombie-archive 任务处理。
   */
  private long pendingMaxAgeSeconds = 7L * 24 * 60 * 60;
}
