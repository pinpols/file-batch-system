package io.github.pinpols.batch.console.domain.job.param;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 批次窗口创建参数；仅 id 由 JDBC 回填，其余输入保持不可变。 */
@Getter
@Builder
public class BatchWindowInsertParam {
  @Setter
  private Long id;

  private final String tenantId;
  private final String windowCode;
  private final String windowName;
  private final String timezone;
  private final String startTime;
  private final String endTime;
  private final String endStrategy;
  private final String outOfWindowAction;
  private final Boolean allowCrossDay;
  private final Boolean enabled;
  private final String description;
}
