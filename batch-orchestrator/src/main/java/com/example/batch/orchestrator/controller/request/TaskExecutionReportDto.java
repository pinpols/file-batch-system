package com.example.batch.orchestrator.controller.request;

import lombok.Data;

@Data
public class TaskExecutionReportDto {

    private Long taskId;
    private String tenantId;
    private String workerId;
    /**
     * Worker 侧 traceId，用于在 orchestrator 侧把状态推进/重试/补偿日志串起来。
     */
    private String traceId;
    private boolean success;
    private String code;
    private String message;
    private String resultSummary;
    private String errorCode;
    private String errorMessage;
}
