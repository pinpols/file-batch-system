package com.example.batch.console.web.response;

import java.time.Instant;
import lombok.Data;

@Data
public class AiAuditLogResponse {

    private Long id;
    private String tenantId;
    private String requestId;
    private String traceId;
    private String sessionId;
    private String operatorId;
    private String promptCategory;
    private String promptDecision;
    private String modelName;
    private String promptPreview;
    private String responsePreview;
    private String refusalReason;
    private Instant createdAt;
}
