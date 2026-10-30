package io.github.pinpols.batch.console.domain.job.application.contract.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 使用原请求键接续冻结批次。 */
public record BatchDayCatchUpContinueRequest(
    @NotBlank @Size(max = 128) String tenantId) {}
