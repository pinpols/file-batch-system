package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.query.FileGroupExpectationDefinition;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.FileGroupExpectationDefinitionMapper;
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

/** Configures recurring file groups and materializes their expectations when each business day opens. */
@Service
public class FileGroupExpectationService {

  private final FileGroupExpectationDefinitionMapper definitionMapper;
  private final ExpectedWorkOccurrenceMapper occurrenceMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final PlatformTransactionManager transactionManager;

  public FileGroupExpectationService(
      FileGroupExpectationDefinitionMapper definitionMapper,
      ExpectedWorkOccurrenceMapper occurrenceMapper,
      WorkflowDefinitionMapper workflowDefinitionMapper,
      PlatformTransactionManager transactionManager) {
    this.definitionMapper = definitionMapper;
    this.occurrenceMapper = occurrenceMapper;
    this.workflowDefinitionMapper = workflowDefinitionMapper;
    this.transactionManager = transactionManager;
  }

  public int configure(DefinitionRequest request) {
    validate(request);
    return RlsTenantContextHolder.runWithTenant(
        request.tenantId(),
        () -> new TransactionTemplate(transactionManager).execute(status -> {
          occurrenceMapper.setTenantContext(request.tenantId());
          WorkflowDefinitionEntity workflow =
              workflowDefinitionMapper.selectByTenantAndIdAndEnabled(
                  request.tenantId(), request.workflowDefinitionId(), true);
          if (EmptyChecks.isNull(workflow)
              || !request.calendarCode().equals(workflow.calendarCode())) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "File-group expectation calendar must match the workflow calendar");
          }
          return definitionMapper.upsert(
              request.tenantId(),
              request.calendarCode(),
              request.workflowDefinitionId(),
              request.fileGroupCode(),
              request.expectedLocalTime(),
              request.dueLocalTime(),
              request.enabled());
        }));
  }

  /** Called inside the batch-day open transaction; duplicate opens safely reuse the unique identity. */
  public int materializeForOpenedBusinessDay(
      String tenantId, String calendarCode, LocalDate bizDate, String timezoneSnapshot) {
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> materializeForOpenedBusinessDayInTenant(
            tenantId, calendarCode, bizDate, timezoneSnapshot));
  }

  private int materializeForOpenedBusinessDayInTenant(
      String tenantId, String calendarCode, LocalDate bizDate, String timezoneSnapshot) {
    occurrenceMapper.setTenantContext(tenantId);
    ZoneId zone = ZoneId.of(timezoneSnapshot);
    int materialized = 0;
    for (FileGroupExpectationDefinition definition :
        definitionMapper.selectEnabledByCalendar(tenantId, calendarCode)) {
      Instant expectedAt = resolve(bizDate, definition.expectedLocalTime(), zone);
      Instant dueAt = EmptyChecks.isNull(definition.dueLocalTime())
          ? null
          : resolve(bizDate, definition.dueLocalTime(), zone);
      materialized += occurrenceMapper.insertIfAbsent(
          tenantId,
          calendarCode,
          bizDate,
          definition.workflowDefinitionId(),
          "FILE_GROUP",
          definition.fileGroupCode(),
          expectedAt,
          dueAt);
    }
    return materialized;
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
        || !valid(request.fileGroupCode(), 128)
        || EmptyChecks.isNull(request.expectedLocalTime())
        || (EmptyChecks.isNotNull(request.dueLocalTime())
            && !request.dueLocalTime().isAfter(request.expectedLocalTime()))) {
      throw new IllegalArgumentException(
          "tenant, calendar, workflowDefinitionId, fileGroupCode and expectedLocalTime are required; dueLocalTime must be later on the same business date");
    }
  }

  private static boolean valid(String value, int maxLength) {
    return Texts.hasText(value) && value.trim().length() <= maxLength;
  }

  public record DefinitionRequest(
      String tenantId,
      String calendarCode,
      Long workflowDefinitionId,
      String fileGroupCode,
      LocalTime expectedLocalTime,
      LocalTime dueLocalTime,
      boolean enabled) {}
}
