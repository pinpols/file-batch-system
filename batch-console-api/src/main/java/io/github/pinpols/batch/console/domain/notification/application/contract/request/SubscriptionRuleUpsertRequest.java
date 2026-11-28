package io.github.pinpols.batch.console.domain.notification.application.contract.request;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class SubscriptionRuleUpsertRequest {

    @NotBlank
    @Size(max = 128)
    private String ruleName;

    @NotBlank
    @Size(max = 64)
    private String channelCode;

    @NotBlank
    @Size(max = 512)
    private String eventTypes;

    @Size(max = 128)
    private String severityFilter;

    @Size(max = 512)
    private String jobCodeFilter;

    private Boolean enabled = Boolean.TRUE;
}
