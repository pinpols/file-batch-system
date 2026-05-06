package com.example.batch.orchestrator.domain.param;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.i18n.LocalizedErrorCarrier;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateTaskStatusParam implements LocalizedErrorCarrier {
  private final String tenantId;
  private final Long id;
  private final String taskStatus;
  private final String resultSummary;
  private final String errorCode;
  private final String errorMessage;

  /** i18n message key,V77+ 写入 job_task.error_key。 */
  private final String errorKey;

  /** i18n 占位符参数 JSON 数组,V77+ 写入 job_task.error_args。 */
  private final String errorArgs;

  /** ADR-012 失败分类（V111）。FAILED 终态时填。 */
  private final String failureClass;

  private final String terminalStatus1;
  private final String terminalStatus2;
  private final String terminalStatus3;
  private final String terminalStatus4;
  private final Long expectedVersion;

  /** 以默认终态常量预填 terminalStatus1-4，调用方只需补 tenantId/id/taskStatus/error/expectedVersion 即可。 */
  public static UpdateTaskStatusParamBuilder withDefaultTerminals() {
    return builder()
        .terminalStatus1(TaskStatus.SUCCESS.code())
        .terminalStatus2(TaskStatus.FAILED.code())
        .terminalStatus3(TaskStatus.CANCELLED.code())
        .terminalStatus4(TaskStatus.TERMINATED.code());
  }
}
