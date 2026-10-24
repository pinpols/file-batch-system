package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.query.ExternalEventExpectationDefinition;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExternalEventExpectationDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Configures recurring external events and materializes their per-day settlement expectations. */
@Service
public class ExternalEventExpectationService {

  private final ExternalEventExpectationDefinitionMapper definitionMapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final ExpectedWorkOccurrenceMapper occurrenceMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final PlatformTransactionManager transactionManager;

  public ExternalEventExpectationService(
      ExternalEventExpectationDefinitionMapper definitionMapper,
      BatchDayInstanceMapper batchDayInstanceMapper,
      ExpectedWorkOccurrenceMapper occurrenceMapper,
      TriggerRequestMapper triggerRequestMapper,
      WorkflowDefinitionMapper workflowDefinitionMapper,
      PlatformTransactionManager transactionManager) {
    this.definitionMapper = definitionMapper;
    this.batchDayInstanceMapper = batchDayInstanceMapper;
    this.occurrenceMapper = occurrenceMapper;
    this.triggerRequestMapper = triggerRequestMapper;
    this.workflowDefinitionMapper = workflowDefinitionMapper;
    this.transactionManager = transactionManager;
  }

  public int configure(DefinitionRequest request) {
    validate(request);
    return RlsTenantContextHolder.runWithTenant(
        request.tenantId(),
        () -> new TransactionTemplate(transactionManager).execute(status -> {
          occurrenceMapper.setTenantContext(request.tenantId());
          validateWorkflowCalendar(
              request.tenantId(), request.calendarCode(), request.workflowDefinitionId());
          return definitionMapper.upsert(
              request.tenantId(),
              request.calendarCode(),
              request.workflowDefinitionId(),
              request.eventCode(),
              request.expectedLocalTime(),
              request.dueLocalTime(),
              request.enabled());
        }));
  }

  /** Called in the batch-day opening transaction; retries reuse the stable daily event identity. */
  public int materializeForOpenedBusinessDay(
      String tenantId, String calendarCode, LocalDate bizDate, String timezoneSnapshot) {
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> new TransactionTemplate(transactionManager).execute(status -> {
          occurrenceMapper.setTenantContext(tenantId);
          batchDayInstanceMapper.acquireSettlementFence(tenantId, calendarCode, bizDate);
          ZoneId zone = ZoneId.of(timezoneSnapshot);
          int inserted = 0;
          for (ExternalEventExpectationDefinition definition :
              definitionMapper.selectEnabledByCalendar(tenantId, calendarCode)) {
            inserted += occurrenceMapper.insertIfAbsent(
                tenantId,
                calendarCode,
                bizDate,
                definition.workflowDefinitionId(),
                "EXTERNAL_EVENT",
                definition.eventCode(),
                resolve(bizDate, definition.expectedLocalTime(), zone),
                EmptyChecks.isNull(definition.dueLocalTime())
                    ? null
                    : resolve(bizDate, definition.dueLocalTime(), zone));
          }
          return inserted;
        }));
  }

  /** Close an expectation only after its correlated EVENT request has reached LAUNCHED. */
  public boolean markSatisfied(SatisfactionRequest request) {
    validate(request);
    return RlsTenantContextHolder.runWithTenant(
        request.tenantId(),
        () -> new TransactionTemplate(transactionManager).execute(status -> {
          occurrenceMapper.setTenantContext(request.tenantId());
          batchDayInstanceMapper.acquireSettlementFence(
              request.tenantId(), request.calendarCode(), request.bizDate());
          validateWorkflowCalendar(
              request.tenantId(), request.calendarCode(), request.workflowDefinitionId());
          TriggerRequestEntity triggerRequest = triggerRequestMapper.selectByTenantAndRequestId(
              request.tenantId(), request.triggerRequestId());
          if (EmptyChecks.isNull(triggerRequest)
              || !request.workflowDefinitionId().equals(triggerRequest.getWorkflowDefinitionId())
              || !request.bizDate().equals(triggerRequest.getBizDate())
              || !TriggerType.EVENT.code().equals(triggerRequest.getTriggerType())
              || !request.eventCode().equals(triggerRequest.getExternalEventCode())
              || !TriggerRequestStatus.LAUNCHED.code().equals(triggerRequest.getRequestStatus())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Expected event can be satisfied only by its launched EVENT request");
          }
          return occurrenceMapper.transitionStatus(
                  request.tenantId(),
                  request.calendarCode(),
                  request.workflowDefinitionId(),
                  "EXTERNAL_EVENT",
                  request.eventCode(),
                  request.bizDate(),
                  null,
                  "EXPECTED",
                  "SATISFIED",
                  request.triggerRequestId())
              == 1;
        }));
  }

  private void validateWorkflowCalendar(
      String tenantId, String calendarCode, Long workflowDefinitionId) {
    WorkflowDefinitionEntity workflow = workflowDefinitionMapper.selectByTenantAndIdAndEnabled(
        tenantId, workflowDefinitionId, true);
    if (EmptyChecks.isNull(workflow) || !calendarCode.equals(workflow.calendarCode())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "External-event expectation must target an enabled Workflow on the same calendar");
    }
  }

  private static Instant resolve(LocalDate bizDate, LocalTime localTime, ZoneId zone) {
    return LocalDateTime.of(bizDate, localTime).atZone(zone).toInstant();
  }

  private static void validate(DefinitionRequest request) {
    if (EmptyChecks.isNull(request)
        || !valid(request.tenantId(), 64)
        || !valid(request.calendarCode(), 128)
        || EmptyChecks.isNull(request.workflowDefinitionId())
        || request.workflowDefinitionId() <= 0
        || !valid(request.eventCode(), 128)
        || EmptyChecks.isNull(request.expectedLocalTime())
        || (EmptyChecks.isNotNull(request.dueLocalTime())
            && !request.dueLocalTime().isAfter(request.expectedLocalTime()))) {
      throw new IllegalArgumentException(
          "tenant, calendar, workflowDefinitionId, eventCode and expectedLocalTime are required; dueLocalTime must be later on the same business date");
    }
  }

  private static void validate(SatisfactionRequest request) {
    if (EmptyChecks.isNull(request)
        || !valid(request.tenantId(), 64)
        || !valid(request.calendarCode(), 128)
        || EmptyChecks.isNull(request.workflowDefinitionId())
        || request.workflowDefinitionId() <= 0
        || !valid(request.eventCode(), 128)
        || !valid(request.triggerRequestId(), 128)
        || EmptyChecks.isNull(request.bizDate())) {
      throw new IllegalArgumentException(
          "tenant, calendar, workflowDefinitionId, eventCode, bizDate and triggerRequestId are required");
    }
  }

  private static boolean valid(String value, int maxLength) {
    return Texts.hasText(value) && value.trim().length() <= maxLength;
  }

  public record DefinitionRequest(
      String tenantId,
      String calendarCode,
      Long workflowDefinitionId,
      String eventCode,
      LocalTime expectedLocalTime,
      LocalTime dueLocalTime,
      boolean enabled) {}

  public record SatisfactionRequest(
      String tenantId,
      String calendarCode,
      Long workflowDefinitionId,
      String eventCode,
      LocalDate bizDate,
      String triggerRequestId) {}
}
