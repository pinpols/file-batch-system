package com.example.batch.console.domain.entity;

import lombok.Data;

import java.time.Instant;

@Data
public class FileErrorRecordEntity {

    private Long id;
    private String tenantId;
    private Long fileId;
    private Long pipelineInstanceId;
    private Long pipelineStepRunId;
    private Long recordNo;
    private String errorCode;
    private String errorMessage;
    private String errorStage;
    private Boolean skipped;
    private String skipAction;
    private String rawRecord;
    private Instant createdAt;
}
