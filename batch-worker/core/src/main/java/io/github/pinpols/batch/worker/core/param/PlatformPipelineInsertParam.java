package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;
import lombok.Getter;

/** Pipeline 创建语句参数；行标识与运行代际经 RETURNING 返回，其余字段沿用仓库默认值与原 SQL 约束。 */
@Getter
@Builder(toBuilder = true)
public class PlatformPipelineInsertParam {
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
