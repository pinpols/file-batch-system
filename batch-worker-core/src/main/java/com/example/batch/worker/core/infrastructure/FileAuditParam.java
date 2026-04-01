package com.example.batch.worker.core.infrastructure;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FileAuditParam {
    private final Long fileId;
    private final String tenantId;
    private final String operationType;
    private final String operationResult;
    private final String operatorType;
    private final String operatorId;
    private final String traceId;
    private final String evidenceRef;
    private final Object detailSummary;
}
