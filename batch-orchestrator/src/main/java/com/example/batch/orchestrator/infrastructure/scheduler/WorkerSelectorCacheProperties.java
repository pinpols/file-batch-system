package com.example.batch.orchestrator.infrastructure.scheduler;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P2-3: WorkerSelector 的 Redis 缓存配置。
 *
 * <ul>
 *   <li>{@code enabled=false}（默认）：完全直通 PG，行为同历史
 *   <li>{@code enabled=true}：按 {@code (tenantId, workerGroup)} 缓存 ONLINE worker 列表，{@code
 *       ttlMillis} 过期
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "batch.scheduler.worker-cache")
public class WorkerSelectorCacheProperties {

  private boolean enabled = false;
  private long ttlMillis = 5_000L;
}
