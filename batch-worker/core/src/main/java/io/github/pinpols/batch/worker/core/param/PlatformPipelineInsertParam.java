package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** Pipeline 创建语句参数；仅 id 由 JDBC 回填，其余字段沿用仓库默认值与原 SQL 约束。 */
@Getter
@Builder(toBuilder = true)
public class PlatformPipelineInsertParam {
  @Setter
  private Long id;

  private final String tenantId;
  private final String systemPipelineCode;
  private final Integer systemPipelineVersion;
  private final String jobCode;
  private final String pipelineType;
  private final Long fileId;
  private final Long relatedJobInstanceId;
  private final String currentStage;
  private final String traceId;
  private final String runStatus;
}
