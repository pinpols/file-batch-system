package com.example.batch.worker.core.support;

import java.util.Map;

/**
 * Common interface for domain-specific pipeline stage contexts.
 *
 * <p>This is the worker-side execution context, not the orchestrator-side
 * workflow context. The canonical terminology is documented in
 * {@link com.example.batch.orchestrator.domain.pipeline.ExecutionContext} and
 * {@code docs/architecture/core-model.md}.
 *
 * <p>All three worker domains (import / export / dispatch) carry the same base
 * fields. Implementing this interface allows {@link AbstractStageExecutor} to
 * access those fields without reflection, keeping the generic stage loop
 * compile-time safe across all three pipelines.
 */
public interface ExecutionContext {

    String getTenantId();

    String getJobCode();

    String getWorkerId();

    /**
     * Mutable attribute bag used to share state between pipeline stages.
     * Keys are defined in {@link PipelineRuntimeKeys}.
     */
    Map<String, Object> getAttributes();
}
