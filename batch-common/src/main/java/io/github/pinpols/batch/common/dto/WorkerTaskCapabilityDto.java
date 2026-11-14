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
  /**
   * Worker 注册时上报的任务类型编码长度上限（注册契约上限，能力摘要整体随注册信息存储，
   * 不对应单独的列）。
   */
  private static final int TASK_TYPE_MAX_LENGTH = 128;

  /** 单个任务能力可声明的资源种类上限。 */
  private static final int MAX_RESOURCE_KINDS = 16;

  public WorkerTaskCapabilityDto {
    if (EmptyChecks.isBlank(taskType) || taskType.length() > TASK_TYPE_MAX_LENGTH) {
      throw new IllegalArgumentException("taskType must contain 1 to 128 characters");
    }
    if (EmptyChecks.isEmpty(resourceKinds) || resourceKinds.size() > MAX_RESOURCE_KINDS) {
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
