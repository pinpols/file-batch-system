package io.github.pinpols.batch.console.domain.param;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 资源队列创建参数；仅 id 由 JDBC 回填，其余输入保持不可变。 */
@Getter
@Builder
public class ResourceQueueInsertParam {
  @Setter
  private Long id;

  private final String tenantId;
  private final String queueCode;
  private final String queueName;
  private final String queueType;
  private final Integer maxRunningJobs;
  private final Integer maxRunningPartitions;
  private final Integer maxQps;
  private final String workerGroup;
  private final String resourceTag;
  private final String priorityPolicy;
  private final Integer fairShareWeight;
  private final Boolean enabled;
  private final String description;
}
