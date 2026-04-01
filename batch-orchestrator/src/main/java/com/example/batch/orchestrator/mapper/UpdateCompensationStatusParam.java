package com.example.batch.orchestrator.mapper;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateCompensationStatusParam {
    private final String tenantId;
    private final Long id;
    private final String commandStatus;
    private final Long relatedJobInstanceId;
    private final Long relatedFileId;
    private final String resultSummary;
    private final String errorCode;
    private final String errorMessage;
    private final Instant finishedAt;
}
