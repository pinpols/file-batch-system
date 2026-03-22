package com.example.batch.console.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RerunRequest {

    @NotBlank
    private String tenantId;
    @NotBlank
    private String jobCode;
    @NotBlank
    private String bizDate;
    private Long targetId;
    private String targetInstanceNo;
    private String batchNo;
    private Long relatedFileId;
    private String reason;
    private String operatorId;
    private String approvalId;
    private String strategy;
}
