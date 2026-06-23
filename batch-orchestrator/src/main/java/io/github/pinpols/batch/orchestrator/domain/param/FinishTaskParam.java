package io.github.pinpols.batch.orchestrator.domain.param;

import io.github.pinpols.batch.common.i18n.LocalizedErrorCarrier;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FinishTaskParam implements LocalizedErrorCarrier {
  private final String tenantId;
  private final Long id;
  private final String taskStatus;
  private final String expectedStatus;
  private final String resultSummary;
  private final String errorCode;
  private final String errorMessage;

  /** i18n message key,V77+ 写入 job_task.error_key。 */
  private final String errorKey;

  /** i18n 占位符参数 JSON 数组。 */
  private final String errorArgs;

  /** ADR-012 失败分类（V111）。FAILED 终态时填，SUCCESS 应为 null。 */
  private final String failureClass;

  private final Long expectedVersion;
}
