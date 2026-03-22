package com.example.batch.console.domain.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OutboxDeliveryLogQueryRequest {

    @NotBlank
    private String tenantId;
    private String deliveryStatus;
    private String eventType;
    private String eventKey;
}
