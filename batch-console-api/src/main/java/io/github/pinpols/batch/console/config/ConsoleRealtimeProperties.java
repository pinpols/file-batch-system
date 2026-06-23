package io.github.pinpols.batch.console.config;

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

  /**
   * P1-5 (pre-launch audit 2026-05-18)：单实例 SSE 订阅上限。默认 1000,超出 subscribe 抛 503。proxy 中断/浏览器崩溃 不触发
   * TCP FIN 时连接永驻,无上限则 CopyOnWriteArrayList 随订阅膨胀,GC + publish snapshot 击穿进程。
   */
  private int maxSubscriptions = 1000;

  /** P1-5：SseEmitter timeout。默认 30 分钟,到期客户端走 EventSource 内置重连机制,僵尸连接周期回收。0 = 永不超时(旧行为)。 */
  private Duration emitterTimeout = Duration.ofMinutes(30);
}
