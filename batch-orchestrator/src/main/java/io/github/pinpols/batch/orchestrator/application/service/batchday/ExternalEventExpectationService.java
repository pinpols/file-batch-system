package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.jdbc.JdbcExceptionSupport;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceInsertIfAbsentParam;
import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceTransitionStatusByGenerationParam;
import io.github.pinpols.batch.orchestrator.domain.param.ExternalEventExpectationDefinitionUpsertParam;
import io.github.pinpols.batch.orchestrator.domain.query.ExternalEventExpectationDefinition;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
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
import java.time.ZonedDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 配置周期性外部事件，并为每个业务日生成相应的结算预期。 */
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
    try {
      return RlsTenantContextHolder.runWithTenant(request.tenantId(), () -> {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
          occurrenceMapper.setTenantContext(request.tenantId());
          validateWorkflowCalendar(
              request.tenantId(), request.calendarCode(), request.workflowDefinitionId());
          ExternalEventExpectationDefinitionUpsertParam
              externalEventExpectationDefinitionUpsertParam =
                  ExternalEventExpectationDefinitionUpsertParam.builder()
                      .tenantId(request.tenantId())
                      .calendarCode(request.calendarCode())
                      .workflowDefinitionId(request.workflowDefinitionId())
                      .eventCode(request.eventCode())
                      .expectedLocalTime(request.expectedLocalTime())
                      .dueLocalTime(request.dueLocalTime())
                      .enabled(request.enabled())
                      .build();
          return definitionMapper.upsert(externalEventExpectationDefinitionUpsertParam);
        });
      });
    } catch (DataIntegrityViolationException failure) {
      if (JdbcExceptionSupport.isForeignKeyConstraintViolation(
          failure, "fk_external_event_expectation_workflow_calendar")) {
        throw new OperationFailure(
            Kind.CONFLICT,
            "Workflow calendar changed while configuring the external-event expectation",
            failure);
      }
      throw failure;
    }
  }

  /** 明确只删除周期性定义；已经生成的 occurrence 仍保留以供审计。 */
  public boolean deleteDefinition(
      String tenantId, String calendarCode, Long workflowDefinitionId, String eventCode) {
    boolean eventDefinitionIdentityIsInvalid = !valid(tenantId, 64)
        || !valid(calendarCode, 128)
        || EmptyChecks.isNull(workflowDefinitionId)
        || workflowDefinitionId <= 0
        || !valid(eventCode, 128);
    if (eventDefinitionIdentityIsInvalid) {
      throw new IllegalArgumentException(
          "tenant, calendar, workflowDefinitionId and eventCode are required");
    }
    return RlsTenantContextHolder.runWithTenant(tenantId, () -> {
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      return transaction.execute(status -> {
        occurrenceMapper.setTenantContext(tenantId);
        return definitionMapper.delete(tenantId, calendarCode, workflowDefinitionId, eventCode)
            == 1;
      });
    });
  }

  /** 在批次日开启事务内调用；重试会复用稳定的每日事件标识。 */
  public int materializeForOpenedBusinessDay(
      String tenantId, String calendarCode, LocalDate bizDate, String timezoneSnapshot) {
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> materializeOpenedBusinessDayInTransaction(
            tenantId, calendarCode, bizDate, timezoneSnapshot));
  }

  /** 只有相关联的 EVENT 请求进入 LAUNCHED 后，才能关闭预期。 */
  public boolean markSatisfied(SatisfactionRequest request) {
    validate(request);
    return RlsTenantContextHolder.runWithTenant(
        request.tenantId(), () -> markSatisfiedInTransaction(request));
  }

  private int materializeOpenedBusinessDayInTransaction(
      String tenantId, String calendarCode, LocalDate bizDate, String timezoneSnapshot) {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    return transactionTemplate.execute(status -> {
      occurrenceMapper.setTenantContext(tenantId);
      batchDayInstanceMapper.acquireSettlementFence(tenantId, calendarCode, bizDate);
      BatchDayInstanceEntity batchDay =
          batchDayInstanceMapper.selectByTenantCalendarBizDate(tenantId, calendarCode, bizDate);
      boolean existingBatchDayIsTerminal =
          EmptyChecks.isNotNull(batchDay) && isTerminal(batchDay.dayStatus());
      if (existingBatchDayIsTerminal) {
        throw new OperationFailure(
            Kind.CONFLICT,
            "Cannot materialize an external-event expectation for a terminal batch day");
      }
      ZoneId zone = ZoneId.of(timezoneSnapshot);
      int inserted = 0;
      for (ExternalEventExpectationDefinition definition :
          definitionMapper.selectEnabledByCalendar(tenantId, calendarCode)) {
        Long workflowDefinitionId = definition.workflowDefinitionId();
        String eventCode = definition.eventCode();
        Instant expectedAt = resolve(bizDate, definition.expectedLocalTime(), zone);
        Instant dueAt = EmptyChecks.isNull(definition.dueLocalTime())
            ? null
            : resolve(bizDate, definition.dueLocalTime(), zone);
        ExpectedWorkOccurrenceInsertIfAbsentParam expectedWorkOccurrenceInsertIfAbsentParam =
            ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
                .tenantId(tenantId)
                .calendarCode(calendarCode)
                .bizDate(bizDate)
                .workflowDefinitionId(workflowDefinitionId)
                .sourceType("EXTERNAL_EVENT")
                .sourceKey(eventCode)
                .expectedAt(expectedAt)
                .dueAt(dueAt)
                .build();
        inserted += occurrenceMapper.insertIfAbsent(expectedWorkOccurrenceInsertIfAbsentParam);
        String launchedRequestId = triggerRequestMapper.selectLaunchedExternalEventRequestId(
            tenantId, definition.workflowDefinitionId(), definition.eventCode(), bizDate);
        if (Texts.hasText(launchedRequestId)) {
          ExpectedWorkOccurrenceTransitionStatusByGenerationParam
              expectedWorkOccurrenceTransitionStatusByGenerationParam =
                  ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
                      .tenantId(tenantId)
                      .calendarCode(calendarCode)
                      .workflowDefinitionId(definition.workflowDefinitionId())
                      .sourceType("EXTERNAL_EVENT")
                      .sourceKey(definition.eventCode())
                      .bizDate(bizDate)
                      .generation(null)
                      .expectedStatus("EXPECTED")
                      .newStatus("SATISFIED")
                      .triggerRequestId(launchedRequestId)
                      .build();
          occurrenceMapper.transitionStatus(
              expectedWorkOccurrenceTransitionStatusByGenerationParam);
        }
      }
      return inserted;
    });
  }

  private boolean markSatisfiedInTransaction(SatisfactionRequest request) {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    return transactionTemplate.execute(status -> {
      occurrenceMapper.setTenantContext(request.tenantId());
      batchDayInstanceMapper.acquireSettlementFence(
          request.tenantId(), request.calendarCode(), request.bizDate());
      TriggerRequestEntity triggerRequest = triggerRequestMapper.selectByTenantAndRequestId(
          request.tenantId(), request.triggerRequestId());
      boolean launchedEventMatchesExpectation = EmptyChecks.isNotNull(triggerRequest)
          && request.workflowDefinitionId().equals(triggerRequest.getWorkflowDefinitionId())
          && request.bizDate().equals(triggerRequest.getBizDate())
          && TriggerType.EVENT.code().equals(triggerRequest.getTriggerType())
          && request.eventCode().equals(triggerRequest.getExternalEventCode())
          && TriggerRequestStatus.LAUNCHED.code().equals(triggerRequest.getRequestStatus());
      if (!launchedEventMatchesExpectation) {
        throw new OperationFailure(
            Kind.CONFLICT, "Expected event can be satisfied only by its launched EVENT request");
      }
      validateWorkflowCalendarForLaunchedEvent(
          request.tenantId(), request.calendarCode(), request.workflowDefinitionId());
      ExpectedWorkOccurrenceTransitionStatusByGenerationParam
          expectedWorkOccurrenceTransitionStatusByGenerationParam2 =
              ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
                  .tenantId(request.tenantId())
                  .calendarCode(request.calendarCode())
                  .workflowDefinitionId(request.workflowDefinitionId())
                  .sourceType("EXTERNAL_EVENT")
                  .sourceKey(request.eventCode())
                  .bizDate(request.bizDate())
                  .generation(null)
                  .expectedStatus("EXPECTED")
                  .newStatus("SATISFIED")
                  .triggerRequestId(request.triggerRequestId())
                  .build();
      return occurrenceMapper.transitionStatus(
              expectedWorkOccurrenceTransitionStatusByGenerationParam2)
          == 1;
    });
  }

  private void validateWorkflowCalendar(
      String tenantId, String calendarCode, Long workflowDefinitionId) {
    WorkflowDefinitionView workflow = workflowDefinitionMapper.selectByTenantAndIdAndEnabled(
        tenantId, workflowDefinitionId, true);
    boolean workflowIsMissingOrUsesAnotherCalendar =
        EmptyChecks.isNull(workflow) || !calendarCode.equals(workflow.calendarCode());
    if (workflowIsMissingOrUsesAnotherCalendar) {
      throw new OperationFailure(
          Kind.INVALID_CONFIGURATION,
          "External-event expectation must target an enabled Workflow on the same calendar");
    }
  }

  private void validateWorkflowCalendarForLaunchedEvent(
      String tenantId, String calendarCode, Long workflowDefinitionId) {
    WorkflowDefinitionView workflow = workflowDefinitionMapper.selectByTenantAndIdAndEnabled(
        tenantId, workflowDefinitionId, true);
    if (EmptyChecks.isNull(workflow)) {
      workflow = workflowDefinitionMapper.selectByTenantAndIdAndEnabled(
          tenantId, workflowDefinitionId, false);
    }
    boolean workflowIsMissingOrUsesAnotherCalendar =
        EmptyChecks.isNull(workflow) || !calendarCode.equals(workflow.calendarCode());
    if (workflowIsMissingOrUsesAnotherCalendar) {
      throw new OperationFailure(
          Kind.INVALID_CONFIGURATION,
          "External-event expectation must target a Workflow on the same calendar");
    }
  }

  private static Instant resolve(LocalDate bizDate, LocalTime localTime, ZoneId zone) {
    LocalDateTime localOccurrenceTime = LocalDateTime.of(bizDate, localTime);
    ZonedDateTime zonedOccurrenceTime = localOccurrenceTime.atZone(zone);
    return zonedOccurrenceTime.toInstant();
  }

  private static boolean isTerminal(String dayStatus) {
    return "SETTLED".equals(dayStatus)
        || "FAILED".equals(dayStatus)
        || "SKIPPED".equals(dayStatus)
        || "MANUAL_RELEASED".equals(dayStatus);
  }

  private static void validate(DefinitionRequest request) {
    boolean requiredFieldsAreValid = EmptyChecks.isNotNull(request)
        && valid(request.tenantId(), 64)
        && valid(request.calendarCode(), 128)
        && EmptyChecks.isNotNull(request.workflowDefinitionId())
        && request.workflowDefinitionId() > 0
        && valid(request.eventCode(), 128)
        && EmptyChecks.isNotNull(request.expectedLocalTime());
    boolean dueLocalTimeIsValidWhenRequiredFieldsAreValid = !requiredFieldsAreValid
        || EmptyChecks.isNull(request.dueLocalTime())
        || request.dueLocalTime().isAfter(request.expectedLocalTime());
    boolean definitionIsValid =
        requiredFieldsAreValid && dueLocalTimeIsValidWhenRequiredFieldsAreValid;
    if (!definitionIsValid) {
      throw new IllegalArgumentException(
          "tenant, calendar, workflowDefinitionId, eventCode and expectedLocalTime are required; dueLocalTime must be later on the same business date");
    }
  }

  private static void validate(SatisfactionRequest request) {
    boolean requestHasRequiredFields = EmptyChecks.isNotNull(request)
        && valid(request.tenantId(), 64)
        && valid(request.calendarCode(), 128)
        && EmptyChecks.isNotNull(request.workflowDefinitionId())
        && request.workflowDefinitionId() > 0
        && valid(request.eventCode(), 128)
        && valid(request.triggerRequestId(), 128)
        && EmptyChecks.isNotNull(request.bizDate());
    if (!requestHasRequiredFields) {
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
