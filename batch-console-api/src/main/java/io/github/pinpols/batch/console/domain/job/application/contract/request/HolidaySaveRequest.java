package io.github.pinpols.batch.console.domain.job.application.contract.request;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
public class HolidaySaveRequest {
    @NotNull
    private String tenantId;

    @NotNull
    private String bizDate;

    @NotNull
    private String dayType;

    private String holidayName;
    private String description;
}
