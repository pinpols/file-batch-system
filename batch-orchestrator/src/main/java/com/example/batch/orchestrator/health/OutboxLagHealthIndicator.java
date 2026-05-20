package com.example.batch.orchestrator.health;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Outbox 积压健康探针。
 *
 * <p>触发 DOWN 的条件:
 *
 * <ul>
 *   <li>{@code NEW + FAILED} 状态事件总数 ≥ {@link OutboxLagHealthProperties#backlogThreshold}
 *   <li>{@code PUBLISHING} 状态卡住超过 {@code stalePublishingTimeoutSeconds} 的事件数 > 0
 * </ul>
 *
 * <p>动机:outbox 积压是任务交付链路的早期信号 —— 通常是 Kafka producer 失联 / consumer 没 ack / relay scheduler 挂了。比从
 * Grafana 告警发现更快被 k8s readiness 摘流。
 */
public class OutboxLagHealthIndicator implements HealthIndicator {

  private static final List<String> PENDING_STATUSES = List.of("NEW", "FAILED");
  private static final String PUBLISHING_STATUS = "PUBLISHING";

  private final OutboxEventMapper mapper;
  private final OutboxLagHealthProperties properties;

  public OutboxLagHealthIndicator(OutboxEventMapper mapper, OutboxLagHealthProperties properties) {
    this.mapper = mapper;
    this.properties = properties;
  }

  @Override
  public Health health() {
    try {
      long backlog = mapper.countByStatuses(PENDING_STATUSES);
      long stalePublishing =
          mapper.countStalePublishing(PUBLISHING_STATUS, properties.getStalePublishingSeconds());
      Health.Builder builder =
          (backlog >= properties.getBacklogThreshold() || stalePublishing > 0
                  ? Health.down()
                  : Health.up())
              .withDetail("backlog", backlog)
              .withDetail("backlogThreshold", properties.getBacklogThreshold())
              .withDetail("stalePublishing", stalePublishing)
              .withDetail("stalePublishingTimeoutSeconds", properties.getStalePublishingSeconds());
      return builder.build();
    } catch (Exception ex) {
      // 健康探针不能因为 DB 抖动让 readiness 持久 DOWN(DataSource 自己的 HealthIndicator 已覆盖此场景);
      // 这里返 UNKNOWN 保留可见性,具体连接错误依然在 DataSource indicator 报
      SwallowedExceptionLogger.warn(OutboxLagHealthIndicator.class, "catch:Exception", ex);
      return Health.unknown().withDetail("error", ex.getClass().getSimpleName()).build();
    }
  }
}
