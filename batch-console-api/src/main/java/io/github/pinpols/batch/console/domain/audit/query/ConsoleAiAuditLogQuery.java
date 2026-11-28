package io.github.pinpols.batch.console.domain.audit.query;

import java.time.Instant;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;

@Builder
public record ConsoleAiAuditLogQuery(
        String tenantId,
        String sessionId,
        String operatorId,
        String promptCategory,
        String promptDecision,
        Instant fromTime,
        Instant toTime,
        PageRequest pageRequest,
        Long cursorId) {

    public static ConsoleAiAuditLogQuery ofTenant(String tenantId, PageRequest pageRequest) {
        return builder().tenantId(tenantId).pageRequest(pageRequest).build();
    }
}
