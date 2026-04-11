package com.example.batch.orchestrator.mapper;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class UpdateStepProgressParam {
    private final String tenantId;
    private final Long id;
    private final String stepStatus;
    private final Integer retryCount;
    private final Long relatedFileId;
    private final String resultSummary;
    private final String errorCode;
    private final String errorMessage;
    private final Instant finishedAt;
    private final Long expectedVersion;
}
