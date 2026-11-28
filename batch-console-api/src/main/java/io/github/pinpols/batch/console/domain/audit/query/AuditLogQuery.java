package io.github.pinpols.batch.console.domain.audit.query;

import java.time.Instant;

import lombok.Data;

import io.github.pinpols.batch.common.model.PageRequest;

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
    private Long cursorId;
}
