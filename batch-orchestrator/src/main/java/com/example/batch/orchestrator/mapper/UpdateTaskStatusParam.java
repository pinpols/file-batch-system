package com.example.batch.orchestrator.mapper;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateTaskStatusParam {
    private final String tenantId;
    private final Long id;
    private final String taskStatus;
    private final String resultSummary;
    private final String errorCode;
    private final String errorMessage;
    private final String terminalStatus1;
    private final String terminalStatus2;
    private final String terminalStatus3;
    private final String terminalStatus4;
    private final Long expectedVersion;
}
