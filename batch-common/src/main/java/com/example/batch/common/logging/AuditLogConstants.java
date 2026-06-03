package com.example.batch.common.logging;

/**
 * Orchestrator 审计/告警日志的规范常量。
 *
 * <p>1) {@code logType}：如 ALARM / AUDIT / COMPENSATION<br>
 * 2) {@code detailRef}：日志消费方使用的持久化明细键<br>
 * 3) {@code operatorId/operatorType}：系统生成日志的规范操作者标识
 */
public final class AuditLogConstants {

  public static final String LOG_TYPE_ALARM = "ALARM";
  public static final String LOG_TYPE_AUDIT = "AUDIT";

  public static final String DETAIL_REF_BATCH_DAY_INSTANCE = "batch_day_instance";
  public static final String DETAIL_REF_JOB_SLA = "job-sla";
  public static final String DETAIL_REF_JOB_INSTANCE_SLA_ALERTED_AT = "job_instance.sla_alerted_at";
  // 2026-06-03 P1 audit 补全(deep-scan-be-business-ops):
  public static final String DETAIL_REF_DEAD_LETTER_TASK = "dead_letter_task";
  public static final String DETAIL_REF_OUTBOX_EVENT = "outbox_event";
  public static final String DETAIL_REF_WORKFLOW_NODE_RUN = "workflow_node_run";

  public static final String AUDIT_OP_DEAD_LETTER_REPLAY = "DEAD_LETTER_REPLAY";
  public static final String AUDIT_OP_OUTBOX_CLEANUP = "OUTBOX_CLEANUP";
  public static final String AUDIT_OP_OUTBOX_REPUBLISH = "OUTBOX_REPUBLISH";
  public static final String AUDIT_OP_WORKFLOW_NODE_SKIP = "WORKFLOW_NODE_SKIP";

  public static final String OPERATOR_ID_SYSTEM = "SYSTEM";
  public static final String OPERATOR_TYPE_SYSTEM = "SYSTEM";
  public static final String OPERATOR_TYPE_REQUEST = "REQUEST";

  public static final String OPERATOR_ID_SYSTEM_SLA_SCHEDULER = "SYSTEM_SLA_SCHEDULER";
  public static final String OPERATOR_ID_SYSTEM_BATCH_DAY_OPEN = "SYSTEM_BATCH_DAY_OPEN";
  public static final String OPERATOR_ID_SYSTEM_BATCH_DAY_CUTOFF = "SYSTEM_BATCH_DAY_CUTOFF";
  public static final String OPERATOR_ID_SYSTEM_BATCH_DAY_SETTLE = "SYSTEM_BATCH_DAY_SETTLE";

  private AuditLogConstants() {}
}
