package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 步骤创建语句参数；runSeq 在事务锁后分配，id 由 JDBC 回填，其余输入保持不可变。 */
@Getter
@Builder(toBuilder = true)
public class PlatformStepRunInsertParam {
  @Setter
  private Long id;

  private final Long pipelineInstanceId;
  private final String stepCode;
  private final String stageCode;
  private final String stepStatus;
  private final String inputSummaryJson;

  @Setter
  private Integer runSeq;
}
