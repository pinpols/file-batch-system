package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayCatchupControlEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayCatchupControlMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only counterpart to the raw-date cursor initialization and bounded scan in BatchDayOpenScheduler. */
@Service
@RequiredArgsConstructor
public class BatchDayCatchupPreviewService {

  private static final LocalTime DEFAULT_CUTOFF_TIME = LocalTime.of(6, 0);
  private static final long MAX_INVENTORY_DAYS = 90;

  private final BusinessCalendarMapper businessCalendarMapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final BatchDayCatchupControlMapper catchupControlMapper;
  private final BatchTimezoneProvider timezoneProvider;
  private final BatchDateTimeSupport dateTimeSupport;
  private final PlatformTransactionManager transactionManager;

  @Value("${batch.batch-day.catchup-days-per-scan:14}")
  private int maxDatesPerScan = 14;

  public BatchDayCatchupPreview preview(String tenantId, String calendarCode) {
    if (EmptyChecks.isNull(tenantId)
        || EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isNull(calendarCode)
        || EmptyChecks.isBlank(calendarCode)) {
      throw new IllegalArgumentException("tenantId and calendarCode are required");
    }
    TransactionTemplate readOnlyTransaction = new TransactionTemplate(transactionManager);
    readOnlyTransaction.setReadOnly(true);
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> readOnlyTransaction.execute(status -> {
          BusinessCalendarEntity calendar =
              businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled(
                  tenantId, calendarCode, true);
          if (EmptyChecks.isNull(calendar)) {
            throw new IllegalArgumentException("Enabled business calendar was not found");
          }
          return buildPreview(calendar, dateTimeSupport.nowInstant());
        }));
  }

  public BatchDayHistoricalGapInventory inventoryHistoricalGaps(
      String tenantId, String calendarCode, LocalDate fromDate, LocalDate throughDate) {
    if (EmptyChecks.isNull(tenantId)
        || EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isNull(calendarCode)
        || EmptyChecks.isBlank(calendarCode)) {
      throw new IllegalArgumentException("tenantId and calendarCode are required");
    }
    if (EmptyChecks.isNull(fromDate)
        || EmptyChecks.isNull(throughDate)
        || throughDate.isBefore(fromDate)) {
      throw new IllegalArgumentException("A valid inclusive date range is required");
    }
    long days = java.time.temporal.ChronoUnit.DAYS.between(fromDate, throughDate) + 1;
    if (days > MAX_INVENTORY_DAYS) {
      throw new IllegalArgumentException("Historical inventory range cannot exceed 90 days");
    }
    TransactionTemplate readOnlyTransaction = new TransactionTemplate(transactionManager);
    readOnlyTransaction.setReadOnly(true);
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> readOnlyTransaction.execute(status -> {
          BusinessCalendarEntity calendar =
              businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled(
                  tenantId, calendarCode, true);
          if (EmptyChecks.isNull(calendar)) {
            throw new IllegalArgumentException("Enabled business calendar was not found");
          }
          LocalDate currentBizDate =
              resolveCurrentBusinessDate(calendar, dateTimeSupport.nowInstant());
          if (throughDate.isAfter(currentBizDate)) {
            throw new IllegalArgumentException(
                "Historical inventory cannot include dates after the current business date");
          }
          Set<LocalDate> existing = new HashSet<>(batchDayInstanceMapper.selectBizDatesInRange(
              tenantId, calendarCode, fromDate, throughDate));
          List<LocalDate> missing = java.util.stream.Stream.iterate(
                  fromDate, date -> !date.isAfter(throughDate), date -> date.plusDays(1))
              .filter(date -> !existing.contains(date))
              .toList();
          return new BatchDayHistoricalGapInventory(
              tenantId,
              calendarCode,
              fromDate,
              throughDate,
              Math.toIntExact(days),
              missing,
              "Missing records are candidates only. Current calendar rules may differ from historical rules; review holidays, roll-forward dates, disaster overrides, and dependency waits before any backfill.");
        }));
  }

  private BatchDayCatchupPreview buildPreview(BusinessCalendarEntity calendar, Instant now) {
    LocalDate currentBizDate = resolveCurrentBusinessDate(calendar, now);

    LocalDate persistedCursor =
        batchDayInstanceMapper.selectOpenCursorDate(calendar.tenantId(), calendar.calendarCode());
    BatchDayCatchupControlEntity control =
        catchupControlMapper.selectCurrent(calendar.tenantId(), calendar.calendarCode());
    LocalDate latestExisting = null;
    String cursorSource = "PERSISTED_CURSOR";
    LocalDate cursor = persistedCursor;
    if (EmptyChecks.isNull(cursor)) {
      latestExisting = batchDayInstanceMapper.selectLatestBizDateAtOrBefore(
          calendar.tenantId(), calendar.calendarCode(), currentBizDate);
      cursor = EmptyChecks.isNull(latestExisting) ? currentBizDate.minusDays(1) : latestExisting;
      cursorSource = EmptyChecks.isNull(latestExisting)
          ? "CURRENT_DATE_BASELINE"
          : "LATEST_BATCH_DAY_BASELINE";
    }

    long remaining =
        Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(cursor, currentBizDate));
    int scanLimit = Math.max(1, maxDatesPerScan);
    long plannedCount = Math.min(remaining, scanLimit);
    LocalDate nextDate = plannedCount == 0 ? null : cursor.plusDays(1);
    LocalDate plannedThrough = plannedCount == 0 ? null : cursor.plusDays(plannedCount);
    List<LocalDate> dates = plannedCount == 0
        ? List.of()
        : LongStream.rangeClosed(1, plannedCount).mapToObj(cursor::plusDays).toList();
    BatchDayCatchupPreview preview = new BatchDayCatchupPreview(
        calendar.tenantId(),
        calendar.calendarCode(),
        currentBizDate,
        cursor,
        cursorSource,
        nextDate,
        plannedThrough,
        remaining,
        scanLimit,
        dates,
        false,
        EmptyChecks.isNotNull(control) && Boolean.TRUE.equals(control.paused()),
        EmptyChecks.isNull(control) ? null : control.pausedAt(),
        EmptyChecks.isNull(control) ? null : control.pausedBy(),
        EmptyChecks.isNull(control) ? null : control.pauseReason());
    return preview;
  }

  private LocalDate resolveCurrentBusinessDate(BusinessCalendarEntity calendar, Instant now) {
    ZoneId zone = timezoneProvider.resolveOrDefault(calendar.timezone());
    LocalTime cutoff =
        EmptyChecks.isNull(calendar.cutoffTime()) ? DEFAULT_CUTOFF_TIME : calendar.cutoffTime();
    ZonedDateTime localNow = now.atZone(zone);
    return localNow.toLocalTime().isBefore(cutoff)
        ? localNow.toLocalDate().minusDays(1)
        : localNow.toLocalDate();
  }
}
