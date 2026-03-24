package com.example.batch.common.logging;

/**
 * Canonical MDC keys for JSON-friendly log shipping (Loki/ELK) and cross-service correlation.
 */
public final class StructuredLogField {

    public static final String SERVICE = "service";
    public static final String TENANT_ID = "tenantId";
    public static final String TRACE_ID = "traceId";
    public static final String REQUEST_ID = "requestId";
    public static final String JOB_INSTANCE_ID = "jobInstanceId";
    public static final String TASK_ID = "taskId";
    public static final String WORKER_ID = "workerId";
    public static final String WORKER_TYPE = "workerType";
    public static final String STAGE = "stage";
    public static final String FILE_ID = "fileId";

    private StructuredLogField() {
    }
}
