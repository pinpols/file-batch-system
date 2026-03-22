package com.example.batch.console.domain.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FileErrorRecordQueryRequest {

    @NotBlank
    private String tenantId;
    private Long fileId;
    private String errorStage;
    private String errorCode;
    private Boolean skipped;
}
