package com.example.batch.common.logging;

/**
 * Canonical constants for orchestrator audit/alarm logs.
 *
 * <p>1) {@code logType}: e.g. ALARM / AUDIT / COMPENSATION<br>
 * 2) {@code detailRef}: persistent “detail key” used by log consumers<br>
 * 3) {@code operatorId/operatorType}: canonical operator identity for system-generated logs</p>
 */
public final class AuditLogConstants {

    // ---- logType ----
    public static final String LOG_TYPE_ALARM = "ALARM";
    public static final String LOG_TYPE_AUDIT = "AUDIT";

    // ---- detailRef ----
    public static final String DETAIL_REF_BATCH_DAY_INSTANCE = "batch_day_instance";
    public static final String DETAIL_REF_JOB_SLA = "job-sla";
    public static final String DETAIL_REF_JOB_INSTANCE_SLA_ALERTED_AT = "job_instance.sla_alerted_at";

    // ---- operatorId/operatorType ----
    public static final String OPERATOR_ID_SYSTEM = "SYSTEM";
    public static final String OPERATOR_TYPE_SYSTEM = "SYSTEM";
    public static final String OPERATOR_TYPE_REQUEST = "REQUEST";

    public static final String OPERATOR_ID_SYSTEM_SLA_SCHEDULER = "SYSTEM_SLA_SCHEDULER";
    public static final String OPERATOR_ID_SYSTEM_BATCH_DAY_CUTOFF = "SYSTEM_BATCH_DAY_CUTOFF";
    public static final String OPERATOR_ID_SYSTEM_BATCH_DAY_SETTLE = "SYSTEM_BATCH_DAY_SETTLE";

    private AuditLogConstants() {
    }
}

