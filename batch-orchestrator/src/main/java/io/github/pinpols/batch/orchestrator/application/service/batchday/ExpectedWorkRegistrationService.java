package io.github.pinpols.batch.orchestrator.application.service.batchday;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Registers a file-group expectation before arrival so empty-day settlement can see missing files. */
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
        request.tenantId(),
        () -> new TransactionTemplate(transactionManager).execute(status -> {
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
          int inserted = occurrenceMapper.insertIfAbsent(
              request.tenantId(),
              request.calendarCode(),
              request.bizDate(),
              request.workflowDefinitionId(),
              "FILE_GROUP",
              request.fileGroupCode(),
              request.expectedAt(),
              request.dueAt());
          return new RegistrationResult(inserted == 1, request.fileGroupCode());
        }));
  }

  private static void validate(FileGroupExpectation request) {
    if (EmptyChecks.isNull(request)
        || !valid(request.tenantId(), 64)
        || !valid(request.calendarCode(), 128)
        || EmptyChecks.isNull(request.workflowDefinitionId())
        || request.workflowDefinitionId() <= 0
        || !valid(request.fileGroupCode(), 256)
        || EmptyChecks.isNull(request.bizDate())
        || EmptyChecks.isNull(request.expectedAt())
        || (EmptyChecks.isNotNull(request.dueAt())
            && request.dueAt().isBefore(request.expectedAt()))) {
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
