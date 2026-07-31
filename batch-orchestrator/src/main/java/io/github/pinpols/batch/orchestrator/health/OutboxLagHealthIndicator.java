package io.github.pinpols.batch.orchestrator.health;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
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
 * <p>动机:outbox 积压是任务交付链路的早期信号 —— 通常是 Kafka producer 失联 / consumer 没 ack / relay scheduler 异常退出。比从
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
      Health.Builder builder = (backlog >= properties.getBacklogThreshold() || stalePublishing > 0
              ? Health.down()
              : Health.up())
          .withDetail("backlog", backlog)
          .withDetail("backlogThreshold", properties.getBacklogThreshold())
          .withDetail("stalePublishing", stalePublishing)
          .withDetail("stalePublishingTimeoutSeconds", properties.getStalePublishingSeconds());
      return builder.build();
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(OutboxLagHealthIndicator.class, "catch:Exception", ex);
      // Outbox 是控制面的持久交付链路。DB 查询失败时必须摘出 readiness，
      // 否则实例仍接收新任务但无法可靠推进状态，形成静默积压。
      return Health.down()
          .withDetail("error", ex.getClass().getSimpleName())
          .withDetail("reason", "outbox health query unavailable")
          .build();
    }
  }
}
