package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

import lombok.Data;

import java.time.Instant;

@Data
public class AuditLogQuery {

    private String tenantId;
    private String operationType;
    private String operationResult;
    private String operatorId;
    private Long fileId;
    private String traceId;
    private Instant fromTime;
    private Instant toTime;
    private PageRequest pageRequest;
}
