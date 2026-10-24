package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayCatchupControlEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayCatchupControlMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Audited, tenant-scoped pause/resume control for automatic batch-day catch-up. */
@Service
@RequiredArgsConstructor
public class BatchDayCatchupControlService {

  private final BatchDayCatchupControlMapper controlMapper;
  private final BusinessCalendarMapper calendarMapper;
  private final PlatformTransactionManager transactionManager;
  private final BatchDateTimeSupport dateTimeSupport;

  public BatchDayCatchupControlEntity current(String tenantId, String calendarCode) {
    validateIdentity(tenantId, calendarCode);
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> new TransactionTemplate(transactionManager).execute(status -> {
          ensureEnabledCalendar(tenantId, calendarCode);
          BatchDayCatchupControlEntity current =
              controlMapper.selectCurrent(tenantId, calendarCode);
          return EmptyChecks.isNull(current)
              ? new BatchDayCatchupControlEntity(
                  tenantId, calendarCode, false, null, null, null, dateTimeSupport.nowInstant())
              : current;
        }));
  }

  public BatchDayCatchupControlEntity change(PauseCommand command) {
    validate(command);
    String tenantId = command.tenantId().trim();
    String calendarCode = command.calendarCode().trim();
    String action = command.action().trim().toUpperCase(Locale.ROOT);
    String operator = command.operatorId().trim();
    String reason = command.reason().trim();
    boolean pause = "PAUSE".equals(action);
    Instant now = dateTimeSupport.nowInstant();
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> new TransactionTemplate(transactionManager).execute(status -> {
          ensureEnabledCalendar(tenantId, calendarCode);
          controlMapper.insertIfMissing(tenantId, calendarCode);
          BatchDayCatchupControlEntity current =
              controlMapper.selectForUpdate(tenantId, calendarCode);
          if (EmptyChecks.isNull(current)) {
            throw new IllegalStateException("Catch-up control row could not be initialized");
          }
          if (current.paused() == pause) return current;
          int updated = controlMapper.updatePauseState(
              tenantId,
              calendarCode,
              pause,
              pause ? now : current.pausedAt(),
              pause ? operator : current.pausedBy(),
              pause ? reason : current.pauseReason(),
              now);
          if (updated != 1) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Batch-day catch-up control changed concurrently");
          }
          controlMapper.insertEvent(tenantId, calendarCode, action, operator, reason, now);
          return controlMapper.selectCurrent(tenantId, calendarCode);
        }));
  }

  private void ensureEnabledCalendar(String tenantId, String calendarCode) {
    if (EmptyChecks.isNull(
        calendarMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true))) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Enabled business calendar was not found");
    }
  }

  private static void validate(PauseCommand command) {
    if (EmptyChecks.isNull(command)
        || !Texts.hasText(command.tenantId())
        || !Texts.hasText(command.calendarCode())
        || !Texts.hasText(command.action())
        || !Texts.hasText(command.operatorId())
        || !Texts.hasText(command.reason())
        || command.operatorId().trim().length() > 128
        || command.reason().trim().length() > 1024) {
      throw new IllegalArgumentException(
          "tenant, calendar, action, operator and reason are required");
    }
    String action = command.action().trim().toUpperCase(Locale.ROOT);
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
