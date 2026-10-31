package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.config.BatchDayScanProperties;
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
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 对应 BatchDayOpenScheduler 中初始化原始日期游标并执行有界扫描的只读操作。 */
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
  private final BatchDayScanProperties batchDayScanProperties;

  public BatchDayCatchupPreview preview(String tenantId, String calendarCode) {
    boolean calendarScopeIsIncomplete =
        EmptyChecks.isBlank(tenantId) || EmptyChecks.isBlank(calendarCode);
    if (calendarScopeIsIncomplete) {
      throw new IllegalArgumentException("tenantId and calendarCode are required");
    }
    TransactionTemplate readOnlyTransaction = new TransactionTemplate(transactionManager);
    readOnlyTransaction.setReadOnly(true);
    return RlsTenantContextHolder.runWithTenant(
        tenantId, () -> previewInReadOnlyTransaction(tenantId, calendarCode, readOnlyTransaction));
  }

  public BatchDayHistoricalGapInventory inventoryHistoricalGaps(
      String tenantId, String calendarCode, LocalDate fromDate, LocalDate throughDate) {
    boolean calendarScopeIsIncomplete =
        EmptyChecks.isBlank(tenantId) || EmptyChecks.isBlank(calendarCode);
    if (calendarScopeIsIncomplete) {
      throw new IllegalArgumentException("tenantId and calendarCode are required");
    }
    boolean catchupDateRangeIsInvalid = EmptyChecks.isNull(fromDate)
        || EmptyChecks.isNull(throughDate)
        || throughDate.isBefore(fromDate);
    if (catchupDateRangeIsInvalid) {
      throw new IllegalArgumentException("A valid inclusive date range is required");
    }
    long days = ChronoUnit.DAYS.between(fromDate, throughDate) + 1;
    if (days > MAX_INVENTORY_DAYS) {
      throw new IllegalArgumentException("Historical inventory range cannot exceed 90 days");
    }
    TransactionTemplate readOnlyTransaction = new TransactionTemplate(transactionManager);
    readOnlyTransaction.setReadOnly(true);
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> inventoryInReadOnlyTransaction(
            tenantId, calendarCode, fromDate, throughDate, days, readOnlyTransaction));
  }

  private BatchDayCatchupPreview previewInReadOnlyTransaction(
      String tenantId, String calendarCode, TransactionTemplate readOnlyTransaction) {
    return readOnlyTransaction.execute(status -> {
      catchupControlMapper.setTenantContext(tenantId);
      BusinessCalendarEntity calendar =
          businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true);
      if (EmptyChecks.isNull(calendar)) {
        throw new IllegalArgumentException("Enabled business calendar was not found");
      }
      Instant previewInstant = dateTimeSupport.nowInstant();
      return buildPreview(calendar, previewInstant);
    });
  }

  private BatchDayHistoricalGapInventory inventoryInReadOnlyTransaction(
      String tenantId,
      String calendarCode,
      LocalDate fromDate,
      LocalDate throughDate,
      long days,
      TransactionTemplate readOnlyTransaction) {
    return readOnlyTransaction.execute(status -> {
      catchupControlMapper.setTenantContext(tenantId);
      BusinessCalendarEntity calendar =
          businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true);
      if (EmptyChecks.isNull(calendar)) {
        throw new IllegalArgumentException("Enabled business calendar was not found");
      }
      Instant currentInstant = dateTimeSupport.nowInstant();
      LocalDate currentBizDate = resolveCurrentBusinessDate(calendar, currentInstant);
      if (throughDate.isAfter(currentBizDate)) {
        throw new IllegalArgumentException(
            "Historical inventory cannot include dates after the current business date");
      }
      Set<LocalDate> existing = new HashSet<>(batchDayInstanceMapper.selectBizDatesInRange(
          tenantId, calendarCode, fromDate, throughDate));
      List<LocalDate> missing = Stream.iterate(
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
    });
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

    long remaining = Math.max(0, ChronoUnit.DAYS.between(cursor, currentBizDate));
    int scanLimit = Math.max(1, batchDayScanProperties.getCatchupDaysPerScan());
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
    boolean cutoffHasNotBeenReached = localNow.toLocalTime().isBefore(cutoff);
    if (cutoffHasNotBeenReached) {
      return localNow.toLocalDate().minusDays(1);
    }
    return localNow.toLocalDate();
  }
}
