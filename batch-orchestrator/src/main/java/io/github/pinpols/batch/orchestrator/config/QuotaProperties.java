package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配额（quota）相关配置：
 *
 * <ul>
 *   <li>{@link #runtimeStore}：运行时状态后端选择，{@code redis}（默认）走 Lua 原子脚本， {@code database} 走 PG @Version
 *       乐观锁（遗留路径，故障降级用）。
 *   <li>{@link Snapshot}：Redis → PG 周期 snapshot 配置；仅 {@code runtimeStore=redis} 生效。
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "batch.quota")
public class QuotaProperties {

  private String runtimeStore = "redis";

  private Snapshot snapshot = new Snapshot();

  @Data
  public static class Snapshot {
    private boolean enabled = true;
    private long intervalMillis = 300_000L;
  }
}
