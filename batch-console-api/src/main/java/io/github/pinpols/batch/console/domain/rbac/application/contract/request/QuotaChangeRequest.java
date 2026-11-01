package io.github.pinpols.batch.console.domain.rbac.application.contract.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 租户配额扩容申请。 */
public record QuotaChangeRequest(
    @NotBlank @Size(max = 64) String field,
    @Min(1) int requestedValue,
    @Size(max = 512) String reason) {}
