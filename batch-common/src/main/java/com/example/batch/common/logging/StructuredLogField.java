package com.example.batch.common.logging;

/** 用于 JSON 日志传输（Loki/ELK）和跨服务关联的规范 MDC 键。 */
public final class StructuredLogField {

  public static final String SERVICE = "service";
  public static final String TENANT_ID = "tenantId";
  public static final String TRACE_ID = "traceId";
  public static final String REQUEST_ID = "requestId";
  public static final String JOB_INSTANCE_ID = "jobInstanceId";
  public static final String TASK_ID = "taskId";
  public static final String WORKER_ID = "workerId";
  public static final String WORKER_TYPE = "workerType";
  public static final String RUN_MODE = "runMode";
  public static final String STAGE = "stage";
  public static final String FILE_ID = "fileId";

  private StructuredLogField() {}
}
