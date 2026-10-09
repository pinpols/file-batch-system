package io.github.pinpols.batch.common.dto;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.List;

/** Worker 注册时上报的执行器能力摘要，供 Console 运维展示；不参与调度决策。 */
public record WorkerTaskCapabilityDto(
    String taskType,
    List<String> resourceKinds,
    boolean idempotent,
    boolean cancellable,
    long recommendedTimeoutMillis) {

  public WorkerTaskCapabilityDto {
    if (EmptyChecks.isBlank(taskType) || taskType.length() > 128) {
      throw new IllegalArgumentException("taskType must contain 1 to 128 characters");
    }
    if (EmptyChecks.isEmpty(resourceKinds) || resourceKinds.size() > 16) {
      throw new IllegalArgumentException("resourceKinds must contain 1 to 16 values");
    }
    if (resourceKinds.stream().anyMatch(EmptyChecks::isBlank)) {
      throw new IllegalArgumentException("resourceKinds must not contain blank values");
    }
    if (recommendedTimeoutMillis <= 0) {
      throw new IllegalArgumentException("recommendedTimeoutMillis must be positive");
    }
    resourceKinds = List.copyOf(resourceKinds);
  }
}
