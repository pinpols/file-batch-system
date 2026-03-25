package com.example.batch.worker.core.support;

import java.util.Map;

/**
 * Common interface for domain-specific pipeline job contexts.
 *
 * <p>All three worker domains (import / export / dispatch) carry the same
 * base fields.  Implementing this interface allows {@link AbstractStageExecutor}
 * to access those fields without reflection, keeping the generic stage loop
 * compile-time safe across all three pipelines.
 */
public interface PipelineContext {

    String getTenantId();

    String getJobCode();

    String getWorkerId();

    /**
     * Mutable attribute bag used to share state between pipeline stages.
     * Keys are defined in {@link PipelineRuntimeKeys}.
     */
    Map<String, Object> getAttributes();
}
