package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class EventDeliveryLogEntity {

  private Long id;
  private String tenantId;
  private Long outboxEventId;
  private String eventType;
  private String eventKey;
  private String targetTopic;
  private String targetWorkerId;
  private String deliveryStatus;
  private Integer deliveryAttempt;
  private String deliverySummary;
  private String errorMessage;

  /** i18n message key,V77+ 写入;读路径按当前 Locale 渲染时优先于 errorMessage。 */
  private String errorKey;

  /** i18n 占位符参数 JSON 数组,与 errorKey 一起支持历史日志按 Locale 重渲染。 */
  private String errorArgs;

  private String traceId;
  private Instant createdAt;
  private Instant updatedAt;
}
