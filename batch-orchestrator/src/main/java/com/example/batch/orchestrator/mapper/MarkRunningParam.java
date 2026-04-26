package com.example.batch.orchestrator.mapper;

import com.example.batch.common.enums.StepInstanceStatus;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MarkRunningParam {
  private final String tenantId;
  private final Long id;
  private final Instant startedAt;
  private final Long expectedVersion;
  private final String runningStatus;
  private final String createdStatus;
  private final String waitingStatus;
  private final String readyStatus;
  private final String retryingStatus;

  /**
   * 以默认状态常量预填 runningStatus/createdStatus/waitingStatus/readyStatus/retryingStatus， 调用方只需补
   * tenantId/id/startedAt/expectedVersion 即可。
   */
  public static MarkRunningParamBuilder withDefaultStatuses() {
    return builder()
        .runningStatus(StepInstanceStatus.RUNNING.code())
        .createdStatus(StepInstanceStatus.CREATED.code())
        .waitingStatus(StepInstanceStatus.WAITING.code())
        .readyStatus(StepInstanceStatus.READY.code())
        .retryingStatus(StepInstanceStatus.RETRYING.code());
  }
}
