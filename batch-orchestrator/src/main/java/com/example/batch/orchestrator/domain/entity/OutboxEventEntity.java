package com.example.batch.orchestrator.domain.entity;

import lombok.Data;

import java.time.Instant;

@Data
public class OutboxEventEntity {

    private Long id;
    private String tenantId;
    private String aggregateType;
    private Long aggregateId;
    private String eventType;
    private String eventKey;
    private String payloadJson;
    private String publishStatus;

    /**
     * outbox 投递尝试序号。
     *
     * <p>它与运行时实体上保存的业务重试计数是不同的概念。
     */
    private Integer publishAttempt;

    private Instant nextPublishAt;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;
}
