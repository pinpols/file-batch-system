package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WebhookSubscriptionEntity {

    private Long id;
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
