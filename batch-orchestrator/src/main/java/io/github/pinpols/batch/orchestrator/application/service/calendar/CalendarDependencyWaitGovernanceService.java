package io.github.pinpols.batch.orchestrator.application.service.calendar;

import io.github.pinpols.batch.orchestrator.domain.param.CalendarDependencyManualActionParam;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyWaitEntity;
import io.github.pinpols.batch.orchestrator.mapper.CalendarDependencyWaitMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 面向 Console 的持久化日历依赖等待只读查询和审计决策请求。 */
@Service
@RequiredArgsConstructor
public class CalendarDependencyWaitGovernanceService {

  private static final int MAX_PAGE_SIZE = 200;

  private final CalendarDependencyWaitMapper waitMapper;
  private final PlatformTransactionManager transactionManager;
  private final BatchDateTimeSupport dateTimeSupport;

  public List<CalendarDependencyWaitEntity> list(
      String tenantId, String calendarCode, String waitStatus, int limit) {
    requireText(tenantId, "tenantId");
    String normalizedStatus = normalizeOptional(waitStatus);
    if (EmptyChecks.isNotNull(normalizedStatus)
        && !List.of(
                CalendarDependencyWaitEntity.STATUS_WAITING,
                CalendarDependencyWaitEntity.STATUS_OPENED,
                CalendarDependencyWaitEntity.STATUS_SKIPPED)
            .contains(normalizedStatus)) {
      throw new IllegalArgumentException("Unsupported calendar dependency wait status");
    }
    int pageSize = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> {
          TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
          return transactionTemplate.execute(status -> {
            waitMapper.setTenantContext(tenantId);
            String normalizedCalendarCode = normalizeOptional(calendarCode);
            return waitMapper.selectRecent(
                tenantId, normalizedCalendarCode, normalizedStatus, pageSize);
          });
        });
  }

  public CalendarDependencyWaitEntity requestManualAction(ManualActionCommand command) {
    validate(command);
    String trimmedAction = command.action().trim();
    String action = trimmedAction.toUpperCase(Locale.ROOT);
    String operatorId = command.operatorId().trim();
    String reason = command.reason().trim();
    Instant now = dateTimeSupport.nowInstant();
    return RlsTenantContextHolder.runWithTenant(
        command.tenantId(),
        () -> {
          TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
          return transactionTemplate.execute(
              status -> requestManualActionInTransaction(command, action, operatorId, reason, now));
        });
  }

  private CalendarDependencyWaitEntity requestManualActionInTransaction(
      ManualActionCommand command, String action, String operatorId, String reason, Instant now) {
    waitMapper.setTenantContext(command.tenantId());
    CalendarDependencyWaitEntity wait = waitMapper.selectByTenantCalendarBizDate(
        command.tenantId(), command.calendarCode(), command.bizDate());
    if (EmptyChecks.isNull(wait)
        || !CalendarDependencyWaitEntity.STATUS_WAITING.equals(wait.waitStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Calendar dependency wait is no longer actionable");
    }
    if (EmptyChecks.isNotNull(wait.manualAction())) {
      boolean manualWaitResolutionIsUnchanged = wait.manualAction().equals(action)
          && wait.manualOperatorId().equals(operatorId)
          && wait.manualReason().equals(reason);
      if (manualWaitResolutionIsUnchanged) {
        return wait;
      }
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A manual action is already pending for this wait");
    }
    CalendarDependencyManualActionParam calendarDependencyManualActionParam = CalendarDependencyManualActionParam.builder()
        .tenantId(command.tenantId())
        .id(wait.id())
        .manualAction(action)
        .operatorId(operatorId)
        .reason(reason)
        .requestedAt(now)
        .build();
    int updated = waitMapper.requestManualAction(calendarDependencyManualActionParam);
    if (updated != 1) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Calendar dependency wait changed concurrently");
    }
    return waitMapper.selectByTenantCalendarBizDate(
        command.tenantId(), command.calendarCode(), command.bizDate());
  }

  private static void validate(ManualActionCommand command) {
    boolean manualActionCommandIsInvalid =
        EmptyChecks.isNull(command)
        || !Texts.hasText(command.tenantId())
        || !Texts.hasText(command.calendarCode())
        || EmptyChecks.isNull(command.bizDate())
        || !Texts.hasText(command.operatorId())
        || !Texts.hasText(command.reason())
        || command.operatorId().trim().length() > 128
        || command.reason().trim().length() > 1024;
    if (manualActionCommandIsInvalid) {
      throw new IllegalArgumentException(
          "tenant, calendar, bizDate, operator and reason are required");
    }
    String action = "";
    if (EmptyChecks.isNotNull(command.action())) {
      String trimmedAction = command.action().trim();
      action = trimmedAction.toUpperCase(Locale.ROOT);
    }
    if (!CalendarDependencyWaitEntity.MANUAL_ACTION_RELEASE.equals(action)
        && !CalendarDependencyWaitEntity.MANUAL_ACTION_SKIP.equals(action)) {
      throw new IllegalArgumentException("action must be RELEASE or SKIP");
    }
  }

  private static String normalizeOptional(String value) {
    if (!Texts.hasText(value)) {
      return null;
    }
    String trimmedValue = value.trim();
    return trimmedValue.toUpperCase(Locale.ROOT);
  }

  private static void requireText(String value, String field) {
    if (!Texts.hasText(value)) throw new IllegalArgumentException(field + " is required");
  }

  public record ManualActionCommand(
      String tenantId,
      String calendarCode,
      LocalDate bizDate,
      String action,
      String operatorId,
      String reason) {}
}
