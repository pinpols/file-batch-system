package com.example.batch.worker.core.support;

/**
 * Standard failure codes for stage execution results.
 *
 * <ul>
 *   <li>{@link #BUSINESS_ERROR} — expected business error thrown by a step (BizException)</li>
 *   <li>{@link #INFRA_ERROR} — unexpected infrastructure/runtime exception</li>
 *   <li>{@link #STEP_NOT_FOUND} — no step implementation registered for the given implCode</li>
 *   <li>{@link #PIPELINE_STEP_MISSING} — pipeline step definitions are absent or empty</li>
 * </ul>
 */
public enum StageFailureCode {

    BUSINESS_ERROR,
    INFRA_ERROR,
    STEP_NOT_FOUND,
    PIPELINE_STEP_MISSING
}
