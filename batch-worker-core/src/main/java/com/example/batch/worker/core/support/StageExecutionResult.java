package com.example.batch.worker.core.support;

/**
 * Marker interface for domain-specific stage results.
 *
 * <p>All three result record types ({@code ImportStageResult}, {@code ExportStageResult},
 * {@code DispatchStageResult}) share the same three fields.  Implementing this interface
 * lets {@link AbstractStageExecutor} read the outcome without reflection.
 */
public interface StageExecutionResult {

    boolean success();

    /** Failure code — {@code "SUCCESS"} when the stage succeeded. */
    String code();

    String message();
}
