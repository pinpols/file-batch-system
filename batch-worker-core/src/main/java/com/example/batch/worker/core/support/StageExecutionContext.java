package com.example.batch.worker.core.support;

/**
 * Immutable context snapshot for a single stage execution.
 *
 * <p>Carried through MDC so every log line emitted during a stage automatically includes
 * the canonical correlation fields (tenantId / jobInstanceId / taskId / stage / workerId).
 */
public record StageExecutionContext(
        String tenantId,
        String jobInstanceId,
        String taskId,
        String stage,
        String workerId
) {
}
