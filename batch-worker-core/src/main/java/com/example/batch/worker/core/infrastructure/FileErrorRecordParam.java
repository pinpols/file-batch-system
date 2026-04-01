package com.example.batch.worker.core.infrastructure;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FileErrorRecordParam {
    private final String tenantId;
    private final Long fileId;
    private final Long pipelineInstanceId;
    private final Long pipelineStepRunId;
    private final Long recordNo;
    private final String errorCode;
    private final String errorMessage;
    private final String errorStage;
    private final boolean skipped;
    private final String skipAction;
    private final Object rawRecord;
}
