package com.example.batch.console.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.console.realtime")
public class ConsoleRealtimeProperties {

  /** 单个 tenant + stream 回放缓冲的最大事件数。小于等于 0 表示不按条数裁剪。 */
  private long replayMaxEntries = 20_000L;

  /** 回放缓冲在 Redis 中的保留时长。小于等于 0 表示不设置 TTL。 */
  private Duration replayTtl = Duration.ofHours(24);
}
