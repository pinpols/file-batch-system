package io.github.pinpols.batch.console.domain.audit.application.contract.query;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;

@EqualsAndHashCode(callSuper = false)
@Data
public class ConsoleAiAuditLogQueryRequest extends PageQueryRequest {

    private String tenantId;
    private String sessionId;
    private String operatorId;
    private String promptCategory;
    private String promptDecision;
    private String fromTime;
    private String toTime;
}
