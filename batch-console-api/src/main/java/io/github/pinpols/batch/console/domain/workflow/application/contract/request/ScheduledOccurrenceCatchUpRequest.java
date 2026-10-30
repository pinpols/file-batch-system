package io.github.pinpols.batch.console.domain.workflow.application.contract.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

/** 显式补扫一条被拒绝的定时触发。 */
public record ScheduledOccurrenceCatchUpRequest(
    @ValidTenantId String tenantId,
    @NotNull Instant scheduledFireTime,
    @NotNull LocalDate bizDate,
    @NotBlank @Size(max = 1024) String reason) {}
