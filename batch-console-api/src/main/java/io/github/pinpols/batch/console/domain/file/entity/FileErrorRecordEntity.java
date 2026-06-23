package io.github.pinpols.batch.console.domain.file.entity;

import io.github.pinpols.batch.common.i18n.AbstractLocalizedErrorEntity;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class FileErrorRecordEntity extends AbstractLocalizedErrorEntity {

  private Long id;
  private String tenantId;
  private Long fileId;
  private Long pipelineInstanceId;
  private Long pipelineStepRunId;
  private Long recordNo;
  private String errorCode;

  private String errorStage;
  private Boolean skipped;
  private String skipAction;
  private String rawRecord;
  private Instant createdAt;
}
