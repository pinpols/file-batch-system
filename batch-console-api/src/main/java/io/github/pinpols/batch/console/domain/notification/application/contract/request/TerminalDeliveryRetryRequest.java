package io.github.pinpols.batch.console.domain.notification.application.contract.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 人工确认接收端未处理后,为原投递开启一轮有界重试。 */
public record TerminalDeliveryRetryRequest(
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    String requestId,

    @Min(1) int expectedAttempt,
    @NotBlank @Size(max = 500) String reason,
    @NotBlank @Size(max = 500) String reconciliationReference,
    @AssertTrue boolean confirmedNotDelivered) {}
