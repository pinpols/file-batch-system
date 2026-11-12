package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.utils.EmptyChecks;

/**
 * 把任务派发消息自带的定位与调度字段写入 MDC。
 *
 * <p>仅覆盖两处消费入口完全相同的那部分字段；{@code TOPIC}、{@code WORKER_TYPE}、{@code WORKER_ID} 的来源因入口而异，由调用方自行写入。
 */
public final class TaskMessageMdc {

  private TaskMessageMdc() {}

  /** 依次写入租户、链路、任务、实例、分区，以及调度上下文中的工作流运行、业务日和尝试序号；null 值由 {@link BatchMdc#put} 跳过。 */
  public static void putMessageFields(TaskDispatchMessage message) {
    BatchMdc.put(StructuredLogField.TENANT_ID, message.tenantId());
    BatchMdc.put(StructuredLogField.TRACE_ID, message.traceId());
    Object rawTaskId = message.taskId();
    String mdcTaskId = EmptyChecks.isNull(rawTaskId) ? null : String.valueOf(message.taskId());
    BatchMdc.put(StructuredLogField.TASK_ID, mdcTaskId);
    Object rawJobInstanceId = message.jobInstanceId();
    String mdcJobInstanceId =
        EmptyChecks.isNull(rawJobInstanceId) ? null : String.valueOf(message.jobInstanceId());
    BatchMdc.put(StructuredLogField.JOB_INSTANCE_ID, mdcJobInstanceId);
    Object rawPartitionId = message.jobPartitionId();
    String mdcPartitionId =
        EmptyChecks.isNull(rawPartitionId) ? null : String.valueOf(message.jobPartitionId());
    BatchMdc.put(StructuredLogField.PARTITION_ID, mdcPartitionId);
    if (EmptyChecks.isNotNull(message.schedulingContext())) {
      Object rawWorkflowRunId = message.schedulingContext().workflowRunId();
      String mdcWorkflowRunId = EmptyChecks.isNull(rawWorkflowRunId)
          ? null
          : String.valueOf(message.schedulingContext().workflowRunId());
      BatchMdc.put(StructuredLogField.WORKFLOW_RUN_ID, mdcWorkflowRunId);
      Object rawBizDate = message.schedulingContext().bizDate();
      String mdcBizDate = EmptyChecks.isNull(rawBizDate)
          ? null
          : message.schedulingContext().bizDate().toString();
      BatchMdc.put(StructuredLogField.BATCH_DAY, mdcBizDate);
      Object rawAttempt = message.schedulingContext().attemptNo();
      String mdcAttempt = EmptyChecks.isNull(rawAttempt)
          ? null
          : String.valueOf(message.schedulingContext().attemptNo());
      BatchMdc.put(StructuredLogField.ATTEMPT, mdcAttempt);
    }
  }
}
