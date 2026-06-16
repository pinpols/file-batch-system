package com.example.batch.worker.core.infrastructure;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FileErrorRecordParam {
  private final String tenantId;
  private final Long fileId;
  private final Long pipelineInstanceId;
  private final Long pipelineStepRunId;
  private final Long recordNo;
  private final String errorCode;
  private final String errorMessage;
  private final String errorStage;
  private final boolean skipped;
  private final String skipAction;
  private final Object rawRecord;
  // Excel 物理定位:source_row_num = 1-based 物理行号(非 Excel 路径为空);
  // source_column = 出错列的表头名(无法定位到列时为空)。两者向后兼容,默认 null。
  private final Long sourceRowNum;
  private final String sourceColumn;
}
