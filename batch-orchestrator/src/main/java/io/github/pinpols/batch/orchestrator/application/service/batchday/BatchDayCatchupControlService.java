package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.jdbc.JdbcExceptionSupport;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayCatchupControlEntity;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayCatchupEventParam;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayCatchupPauseStateParam;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayCatchupControlMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 按租户隔离并记录审计的自动批次日补扫暂停/恢复控制。 */
@Service
@RequiredArgsConstructor
public class BatchDayCatchupControlService {

  private static final String CALENDAR_REFERENCE_CONSTRAINT =
      "fk_batch_day_catchup_control_business_calendar";

  private final BatchDayCatchupControlMapper controlMapper;
  private final BusinessCalendarMapper calendarMapper;
  private final PlatformTransactionManager transactionManager;
  private final BatchDateTimeSupport dateTimeSupport;

  public BatchDayCatchupControlEntity current(String tenantId, String calendarCode) {
    validateIdentity(tenantId, calendarCode);
    return RlsTenantContextHolder.runWithTenant(
        tenantId, () -> readCurrentInTransaction(tenantId, calendarCode));
  }

  public BatchDayCatchupControlEntity change(PauseCommand command) {
    validate(command);
    String tenantId = command.tenantId().trim();
    String calendarCode = command.calendarCode().trim();
    String trimmedAction = command.action().trim();
    String action = trimmedAction.toUpperCase(Locale.ROOT);
    String operator = command.operatorId().trim();
    String reason = command.reason().trim();
    PauseCommand normalizedCommand =
        new PauseCommand(tenantId, calendarCode, action, operator, reason);
    Instant now = dateTimeSupport.nowInstant();
    try {
      return RlsTenantContextHolder.runWithTenant(
          tenantId, () -> changeInTransaction(normalizedCommand, now));
    } catch (DataIntegrityViolationException exception) {
      if (!isCatchupControlCalendarReferenceViolation(exception)) {
        throw exception;
      }
      throw new OperationFailure(
          Kind.CONFLICT, "Business calendar changed while updating catch-up control", exception);
    }
  }

  private BatchDayCatchupControlEntity readCurrentInTransaction(
      String tenantId, String calendarCode) {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    return transactionTemplate.execute(status -> {
      controlMapper.setTenantContext(tenantId);
      ensureEnabledCalendar(tenantId, calendarCode);
      BatchDayCatchupControlEntity current = controlMapper.selectCurrent(tenantId, calendarCode);
      return EmptyChecks.isNull(current)
          ? new BatchDayCatchupControlEntity(
              tenantId, calendarCode, false, null, null, null, dateTimeSupport.nowInstant())
          : current;
    });
  }

  private BatchDayCatchupControlEntity changeInTransaction(PauseCommand command, Instant now) {
    String tenantId = command.tenantId();
    String calendarCode = command.calendarCode();
    String action = command.action();
    String operator = command.operatorId();
    String reason = command.reason();
    boolean pause = "PAUSE".equals(action);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    return transactionTemplate.execute(status -> {
      controlMapper.setTenantContext(tenantId);
      ensureEnabledCalendar(tenantId, calendarCode);
      controlMapper.insertIfMissing(tenantId, calendarCode);
      BatchDayCatchupControlEntity current = controlMapper.selectForUpdate(tenantId, calendarCode);
      if (EmptyChecks.isNull(current)) {
        throw new IllegalStateException("Catch-up control row could not be initialized");
      }
      if (current.paused() == pause) return current;
      BatchDayCatchupPauseStateParam batchDayCatchupPauseStateParam =
          BatchDayCatchupPauseStateParam.builder()
              .tenantId(tenantId)
              .calendarCode(calendarCode)
              .paused(pause)
              .pausedAt(pause ? now : current.pausedAt())
              .pausedBy(pause ? operator : current.pausedBy())
              .pauseReason(pause ? reason : current.pauseReason())
              .updatedAt(now)
              .build();
      int updated = controlMapper.updatePauseState(batchDayCatchupPauseStateParam);
      if (updated != 1) {
        throw new OperationFailure(
            Kind.CONFLICT, "Batch-day catch-up control changed concurrently");
      }
      BatchDayCatchupEventParam batchDayCatchupEventParam = BatchDayCatchupEventParam.builder()
          .tenantId(tenantId)
          .calendarCode(calendarCode)
          .action(action)
          .operatorId(operator)
          .reason(reason)
          .occurredAt(now)
          .build();
      controlMapper.insertEvent(batchDayCatchupEventParam);
      return controlMapper.selectCurrent(tenantId, calendarCode);
    });
  }

  private void ensureEnabledCalendar(String tenantId, String calendarCode) {
    if (EmptyChecks.isNull(
        calendarMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true))) {
      throw new OperationFailure(Kind.NOT_FOUND, "Enabled business calendar was not found");
    }
  }

  private static boolean isCatchupControlCalendarReferenceViolation(Throwable failure) {
    return JdbcExceptionSupport.isForeignKeyConstraintViolationInChain(
        failure, CALENDAR_REFERENCE_CONSTRAINT);
  }

  private static void validate(PauseCommand command) {
    boolean hasRequiredFields = EmptyChecks.isNotNull(command)
        && Texts.hasText(command.tenantId())
        && Texts.hasText(command.calendarCode())
        && Texts.hasText(command.action())
        && Texts.hasText(command.operatorId())
        && Texts.hasText(command.reason());
    if (!hasRequiredFields) {
      throw new IllegalArgumentException(
          "tenant, calendar, action, operator and reason are required");
    }
    String trimmedOperatorId = command.operatorId().trim();
    if (trimmedOperatorId.length() > 128) {
      throw new IllegalArgumentException(
          "tenant, calendar, action, operator and reason are required");
    }
    String trimmedReason = command.reason().trim();
    if (trimmedReason.length() > 1024) {
      throw new IllegalArgumentException(
          "tenant, calendar, action, operator and reason are required");
    }
    String trimmedAction = command.action().trim();
    String action = trimmedAction.toUpperCase(Locale.ROOT);
    if (!"PAUSE".equals(action) && !"RESUME".equals(action)) {
      throw new IllegalArgumentException("action must be PAUSE or RESUME");
    }
  }

  private static void validateIdentity(String tenantId, String calendarCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(calendarCode)) {
      throw new IllegalArgumentException("tenantId and calendarCode are required");
    }
  }

  public record PauseCommand(
      String tenantId, String calendarCode, String action, String operatorId, String reason) {}
}
