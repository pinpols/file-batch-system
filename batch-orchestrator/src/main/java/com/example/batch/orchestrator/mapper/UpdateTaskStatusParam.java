package com.example.batch.orchestrator.mapper;

import com.example.batch.common.enums.TaskStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateTaskStatusParam {
  private final String tenantId;
  private final Long id;
  private final String taskStatus;
  private final String resultSummary;
  private final String errorCode;
  private final String errorMessage;
  private final String terminalStatus1;
  private final String terminalStatus2;
  private final String terminalStatus3;
  private final String terminalStatus4;
  private final Long expectedVersion;

  /**
   * 以默认终态常量预填 terminalStatus1-4，调用方只需补 tenantId/id/taskStatus/error/expectedVersion 即可。
   */
  public static UpdateTaskStatusParamBuilder withDefaultTerminals() {
    return builder()
        .terminalStatus1(TaskStatus.SUCCESS.code())
        .terminalStatus2(TaskStatus.FAILED.code())
        .terminalStatus3(TaskStatus.CANCELLED.code())
        .terminalStatus4(TaskStatus.TERMINATED.code());
  }
}
