package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.Objects;
import lombok.Builder;

/** 一次等待启动状态转换，目标与来源仍由Mapper具名入口决定。 */
@Builder
public record BatchDayWaitingLaunchTransitionParam(
    String tenantId,
    String requestId,
    String releasedBy,
    String expectedStatus,
    String targetStatus) {

  public BatchDayWaitingLaunchTransitionParam {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(expectedStatus, "expectedStatus");
    Objects.requireNonNull(targetStatus, "targetStatus");
  }
}
