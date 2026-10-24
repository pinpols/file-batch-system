package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;

/** Exact pending Workflow replay identity promoted to execution after approval. */
@Builder
public record ReplayOperationStartParam(
    String tenantId,
    Long operationId,
    String targetRequestId,
    String approver,
    Instant now) {}
