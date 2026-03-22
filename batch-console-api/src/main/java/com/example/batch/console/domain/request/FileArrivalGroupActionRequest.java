package com.example.batch.console.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FileArrivalGroupActionRequest {

    @NotBlank
    private String tenantId;
    @NotBlank
    private String fileGroupCode;
    @NotBlank
    private String action;
    private String reason;
    private Long extendWaitSeconds;
}
