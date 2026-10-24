package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceInsertIfAbsentParam;


import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 在文件到达前登记文件组预期，使空日结算能发现缺失文件。 */
@Service
public class ExpectedWorkRegistrationService {

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
      WorkflowDefinitionEntity workflow =
          workflowDefinitionMapper.selectByTenantAndIdAndEnabled(
              request.tenantId(), request.workflowDefinitionId(), true);
      if (EmptyChecks.isNull(workflow)
          || !request.calendarCode().equals(workflow.calendarCode())) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Expected work calendar must match the workflow calendar");
      }
      ExpectedWorkOccurrenceInsertIfAbsentParam expectedWorkOccurrenceInsertIfAbsentParam = ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
          .tenantId(request.tenantId())
          .calendarCode(request.calendarCode())
          .bizDate(request.bizDate())
          .workflowDefinitionId(request.workflowDefinitionId())
          .sourceType("FILE_GROUP")
          .sourceKey(request.fileGroupCode())
          .expectedAt(request.expectedAt())
          .dueAt(request.dueAt())
          .build();
      int inserted = occurrenceMapper.insertIfAbsent(
          expectedWorkOccurrenceInsertIfAbsentParam);
      return new RegistrationResult(inserted == 1, request.fileGroupCode());
    };
    return transactionTemplate.execute(registration);
  }

  private static void validate(FileGroupExpectation request) {
    boolean requiredFieldsAreValid =
        EmptyChecks.isNotNull(request)
            && valid(request.tenantId(), 64)
            && valid(request.calendarCode(), 128)
            && EmptyChecks.isNotNull(request.workflowDefinitionId())
            && request.workflowDefinitionId() > 0
            && valid(request.fileGroupCode(), 256)
            && EmptyChecks.isNotNull(request.bizDate())
            && EmptyChecks.isNotNull(request.expectedAt());
    boolean dueAtIsValidWhenRequiredFieldsAreValid =
        !requiredFieldsAreValid
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
