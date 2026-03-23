package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CompensationCommandRequest {

    @NotBlank
    private String tenantId;
    @NotBlank
    private String compensationType;
    private Long targetId;
    private String targetInstanceNo;
    private String jobCode;
    private String bizDate;
    private String batchNo;
    private Long relatedFileId;
    private String channelCode;
    private String reason;
    private String operatorId;
    private String approvalId;
    private String strategy;
}
