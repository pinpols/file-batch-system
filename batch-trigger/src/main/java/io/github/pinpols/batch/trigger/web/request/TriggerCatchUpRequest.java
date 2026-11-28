package io.github.pinpols.batch.trigger.web.request;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class TriggerCatchUpRequest {

    @NotBlank
    private String tenantId;

    private String requestId;
    private Long pendingId;
    private String reason;
}
