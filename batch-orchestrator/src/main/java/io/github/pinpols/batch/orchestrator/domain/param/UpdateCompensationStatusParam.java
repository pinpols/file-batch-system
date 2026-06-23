package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateCompensationStatusParam {
  private final String tenantId;
  private final Long id;
  private final String commandStatus;
  private final Long relatedJobInstanceId;
  private final Long relatedFileId;
  private final String resultSummary;
  private final String errorCode;
  private final String errorMessage;
  // i18n: 与其他 Update*Param 一致透传 errorKey/errorArgs 三元组,Mapper XML updateStatus 引用了这两字段。
  private final String errorKey;
  private final String errorArgs;
  private final Instant finishedAt;
}
