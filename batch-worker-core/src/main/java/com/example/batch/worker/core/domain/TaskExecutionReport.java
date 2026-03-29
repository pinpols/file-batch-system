package com.example.batch.worker.core.domain;

import lombok.Data;

@Data
public class TaskExecutionReport {

    private Long taskId;
    private String tenantId;
    private String workerId;
    /**
     * Worker 侧的 traceId，用于让 orchestrator 同一条任务实例的日志/审计能够串起来。
     */
    private String traceId;
    private boolean success;
    private String code;
    private String message;
    private String resultSummary;
    private String errorCode;
    private String errorMessage;
}
