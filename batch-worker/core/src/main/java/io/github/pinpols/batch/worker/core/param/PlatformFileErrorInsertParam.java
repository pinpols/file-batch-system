package io.github.pinpols.batch.worker.core.param;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 文件错误写入参数；仅 id 由 JDBC 回填，可空结构化错误与引用字段沿用原 SQL 语义。 */
@Getter
@Builder(toBuilder = true)
public class PlatformFileErrorInsertParam {
  @Setter
  private Long id;

  private final String tenantId;
  private final Long fileId;
  private final Long pipelineInstanceId;
  private final Long pipelineStepRunId;
  private final Long recordNo;
  private final String errorCode;
  private final String errorMessage;
  private final String errorStage;
  private final Boolean isSkipped;
  private final String skipAction;
  private final String rawRecordJson;
  private final Long sourceRowNum;
  private final String sourceColumn;
  private final String errorKey;
  private final String errorArgs;
}
