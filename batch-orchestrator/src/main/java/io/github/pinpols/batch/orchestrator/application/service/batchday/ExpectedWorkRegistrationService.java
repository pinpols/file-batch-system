package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;
import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceInsertIfAbsentParam;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 在文件到达前登记文件组预期，使空日结算能发现缺失文件。 */
@Service
public class ExpectedWorkRegistrationService {

  /** expected_work_occurrence.tenant_id VARCHAR(64)（V263__expected_work_occurrence.sql）。 */
  private static final int TENANT_ID_MAX_LENGTH = 64;

  /** expected_work_occurrence.calendar_code VARCHAR(128)（V263__expected_work_occurrence.sql）。 */
  private static final int CALENDAR_CODE_MAX_LENGTH = 128;

  /**
   * 文件组编码写入 expected_work_occurrence.source_key，该列为 VARCHAR(256)
   * （V263__expected_work_occurrence.sql）。
   */
  private static final int FILE_GROUP_CODE_MAX_LENGTH = 256;

  private final ExpectedWorkOccurrenceMapper occurrenceMapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final PlatformTransactionManager transactionManager;

  public ExpectedWorkRegistrationService(
      ExpectedWorkOccurrenceMapper occurrenceMapper,
      BatchDayInstanceMapper batchDayInstanceMapper,
      WorkflowDefinitionMapper workflowDefinitionMapper,
      PlatformTransactionManager transactionManager) {
    this.occurrenceMapper = occurrenceMapper;
    this.batchDayInstanceMapper = batchDayInstanceMapper;
    this.workflowDefinitionMapper = workflowDefinitionMapper;
    this.transactionManager = transactionManager;
  }

  public RegistrationResult register(FileGroupExpectation request) {
    validate(request);
    return RlsTenantContextHolder.runWithTenant(
        request.tenantId(), () -> registerInTenantTransaction(request));
  }

  private RegistrationResult registerInTenantTransaction(FileGroupExpectation request) {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    TransactionCallback<RegistrationResult> registration = status -> {
      occurrenceMapper.setTenantContext(request.tenantId());
      batchDayInstanceMapper.acquireSettlementFence(
          request.tenantId(), request.calendarCode(), request.bizDate());
      WorkflowDefinitionView workflow = workflowDefinitionMapper.selectByTenantAndIdAndEnabled(
          request.tenantId(), request.workflowDefinitionId(), true);
      if (EmptyChecks.isNull(workflow) || !request.calendarCode().equals(workflow.calendarCode())) {
        throw new OperationFailure(
            Kind.INVALID_CONFIGURATION, "Expected work calendar must match the workflow calendar");
      }
      ExpectedWorkOccurrenceInsertIfAbsentParam expectedWorkOccurrenceInsertIfAbsentParam =
          ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
              .tenantId(request.tenantId())
              .calendarCode(request.calendarCode())
              .bizDate(request.bizDate())
              .workflowDefinitionId(request.workflowDefinitionId())
              .sourceType("FILE_GROUP")
              .sourceKey(request.fileGroupCode())
              .expectedAt(request.expectedAt())
              .dueAt(request.dueAt())
              .build();
      int inserted = occurrenceMapper.insertIfAbsent(expectedWorkOccurrenceInsertIfAbsentParam);
      return new RegistrationResult(inserted == 1, request.fileGroupCode());
    };
    return transactionTemplate.execute(registration);
  }

  private static void validate(FileGroupExpectation request) {
    boolean requiredFieldsAreValid = EmptyChecks.isNotNull(request)
        && valid(request.tenantId(), TENANT_ID_MAX_LENGTH)
        && valid(request.calendarCode(), CALENDAR_CODE_MAX_LENGTH)
        && EmptyChecks.isNotNull(request.workflowDefinitionId())
        && request.workflowDefinitionId() > 0
        && valid(request.fileGroupCode(), FILE_GROUP_CODE_MAX_LENGTH)
        && EmptyChecks.isNotNull(request.bizDate())
        && EmptyChecks.isNotNull(request.expectedAt());
    boolean dueAtIsValidWhenRequiredFieldsAreValid = !requiredFieldsAreValid
        || EmptyChecks.isNull(request.dueAt())
        || !request.dueAt().isBefore(request.expectedAt());
    boolean requestIsValid = requiredFieldsAreValid && dueAtIsValidWhenRequiredFieldsAreValid;
    if (!requestIsValid) {
      throw new IllegalArgumentException(
          "tenant, calendar, workflowDefinitionId, fileGroupCode, bizDate and expectedAt are required; dueAt must not precede expectedAt");
    }
  }

  private static boolean valid(String value, int maxLength) {
    return Texts.hasText(value) && value.trim().length() <= maxLength;
  }

  public record FileGroupExpectation(
      String tenantId,
      String calendarCode,
      Long workflowDefinitionId,
      String fileGroupCode,
      LocalDate bizDate,
      Instant expectedAt,
      Instant dueAt) {}

  public record RegistrationResult(boolean inserted, String fileGroupCode) {}
}
