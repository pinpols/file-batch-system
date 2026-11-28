package io.github.pinpols.batch.console.domain.job.application.contract.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 在原请求尚未建档时设置放弃围栏。 */
public record BatchDayCatchUpAbandonRequest(
        @NotBlank @Size(max = 128) String tenantId,
        @NotNull LocalDate bizDate,
        @NotBlank @Size(max = 128) String calendarCode) {}
