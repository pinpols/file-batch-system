package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class DeadLetterTaskEntity {

    private Long id;
    private String tenantId;
    private String sourceType;
    private Long sourceId;
    private String deadLetterReason;
    private String payloadRef;
    private String replayStatus;
    private Integer replayCount;
    private Instant lastReplayAt;
    private String lastReplayResult;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;
}
