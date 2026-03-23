package com.example.batch.orchestrator.controller.request;

import lombok.Data;

@Data
public class TaskExecutionReportDto {

    private Long taskId;
    private String tenantId;
    private String workerId;
    private boolean success;
    private String code;
    private String message;
    private String resultSummary;
    private String errorCode;
    private String errorMessage;
}
