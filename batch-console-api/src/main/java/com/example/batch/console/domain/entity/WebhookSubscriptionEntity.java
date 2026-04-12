package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.webhook_subscription")
public class WebhookSubscriptionEntity {

  @Id private Long id;
  private String tenantId;
  private String name;
  private String callbackUrl;
  private String eventTypes;
  private String secret;
  private Boolean enabled;
  private String createdBy;
  private String updatedBy;
  private Instant createdAt;
  private Instant updatedAt;
}
