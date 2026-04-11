package com.example.batch.orchestrator.domain.pipeline;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class StepResult {

    private String stepCode;
    private String stepStatus;
    private String message;
    private String workerId;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private Long durationMs;
}
