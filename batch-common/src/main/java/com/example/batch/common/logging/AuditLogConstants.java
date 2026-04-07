package com.example.batch.common.logging;

/**
 * Orchestrator 审计/告警日志的规范常量。
 *
 * <p>1) {@code logType}：如 ALARM / AUDIT / COMPENSATION<br>
 * 2) {@code detailRef}：日志消费方使用的持久化明细键<br>
 * 3) {@code operatorId/operatorType}：系统生成日志的规范操作者标识</p>
 */
public final class AuditLogConstants {

    // ---- 日志类型 ----
    public static final String LOG_TYPE_ALARM = "ALARM";
    public static final String LOG_TYPE_AUDIT = "AUDIT";

    // ---- 明细引用键 ----
    public static final String DETAIL_REF_BATCH_DAY_INSTANCE = "batch_day_instance";
    public static final String DETAIL_REF_JOB_SLA = "job-sla";
    public static final String DETAIL_REF_JOB_INSTANCE_SLA_ALERTED_AT = "job_instance.sla_alerted_at";

    // ---- 操作者标识/类型 ----
    public static final String OPERATOR_ID_SYSTEM = "SYSTEM";
    public static final String OPERATOR_TYPE_SYSTEM = "SYSTEM";
    public static final String OPERATOR_TYPE_REQUEST = "REQUEST";

    public static final String OPERATOR_ID_SYSTEM_SLA_SCHEDULER = "SYSTEM_SLA_SCHEDULER";
    public static final String OPERATOR_ID_SYSTEM_BATCH_DAY_CUTOFF = "SYSTEM_BATCH_DAY_CUTOFF";
    public static final String OPERATOR_ID_SYSTEM_BATCH_DAY_SETTLE = "SYSTEM_BATCH_DAY_SETTLE";

    private AuditLogConstants() {
    }
}

