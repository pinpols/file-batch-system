package io.github.pinpols.batch.common.persistence.entity;

import java.time.Instant;
import lombok.Data;

/**
 * ADR-010: {@code batch.trigger_outbox_event} 行映射。
 *
 * <p>由 trigger 模块在 fire 时与 trigger_request 同事务写入,TriggerOutboxRelay 周期扫描发布到 Kafka,成功后标 PUBLISHED。
 *
 * <p>字段语义见 {@code db/migration/V80__create_trigger_outbox_event.sql} 注释 + ADR-010 §Schema 节。
 */
@Data
public class TriggerOutboxEventEntity {

  private Long id;
  private String tenantId;
  private String requestId;
  private String topic;

  /** {@link io.github.pinpols.batch.common.dto.LaunchEnvelope} 序列化后的 JSON 串。 */
  private String payload;

  /** 对齐 {@link io.github.pinpols.batch.common.enums.OutboxPublishStatus} code。 */
  private String publishStatus;

  /** 已尝试发布次数(失败时 ++,成功不变)。 */
  private int publishAttempt;

  /** 最近一次发布失败的简短 message,长度上限 2048。 */
  private String lastError;

  private String traceId;

  /** 退避时刻;relay 只扫 {@code next_publish_at <= now()} 的行。 */
  private Instant nextPublishAt;

  private Instant publishedAt;
  private Instant createdAt;
  private Instant updatedAt;
}
