package com.example.batch.worker.core.domain;

import lombok.Data;

@Data
public class TaskExecutionReport {

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
