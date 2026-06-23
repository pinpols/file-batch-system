package io.github.pinpols.batch.orchestrator.domain.param;

import io.github.pinpols.batch.common.i18n.LocalizedErrorCarrier;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateStepProgressParam implements LocalizedErrorCarrier {
  private final String tenantId;
  private final Long id;
  private final String stepStatus;
  private final Integer retryCount;
  private final Long relatedFileId;
  private final String resultSummary;
  private final String errorCode;
  private final String errorMessage;

  /** i18n message key,V78+ 写入 job_step_instance.error_key。 */
  private final String errorKey;

  /** i18n 占位符参数 JSON 数组。 */
  private final String errorArgs;

  private final Instant finishedAt;
  private final Long expectedVersion;
}
