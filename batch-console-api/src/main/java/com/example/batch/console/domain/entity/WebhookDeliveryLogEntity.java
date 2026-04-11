package com.example.batch.console.domain.entity;

import lombok.Data;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Table("batch.webhook_delivery_log")
public class WebhookDeliveryLogEntity {

    @Id private Long id;
    private String tenantId;
    private Long subscriptionId;
    private String eventType;
    private String payloadJson;
    private Integer httpStatus;
    private String responseBody;
    private String deliveryStatus;
    private Integer attempt;
    private Instant nextRetryAt;
    private Instant createdAt;
}
