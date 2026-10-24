package io.github.pinpols.batch.orchestrator.application.service.batchday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayCatchupControlMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@DisplayName("批量日追平预览的日期范围与只读边界")
class BatchDayCatchupPreviewServiceTest {

  private BusinessCalendarMapper calendarMapper;
  private BatchDayInstanceMapper batchDayMapper;
  private BatchDayCatchupControlMapper catchupControlMapper;
  private BatchDayCatchupPreviewService service;

  @BeforeEach
  void setUp() {
    calendarMapper = mock(BusinessCalendarMapper.class);
    batchDayMapper = mock(BatchDayInstanceMapper.class);
    catchupControlMapper = mock(BatchDayCatchupControlMapper.class);
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    Instant now = Instant.parse("2026-05-05T00:30:00Z");
    service = new BatchDayCatchupPreviewService(
        calendarMapper,
        batchDayMapper,
        catchupControlMapper,
        timezoneProvider,
        new BatchDateTimeSupport(Clock.fixed(now, ZoneId.of("UTC")), timezoneProvider),
        noopTransactionManager());
    when(calendarMapper.selectFirstByTenantAndCodeAndEnabled("t1", "CAL", true))
        .thenReturn(calendar(LocalTime.of(6, 0)));
  }

  @DisplayName("从持久游标只读预览有界后续原始日期")
  @Test
  void shouldPreviewOnlyNextBoundedRawDatesFromPersistedCursorWithoutWrites() {
    when(batchDayMapper.selectOpenCursorDate("t1", "CAL")).thenReturn(LocalDate.of(2026, 5, 1));
    ReflectionTestUtils.setField(service, "maxDatesPerScan", 2);

    BatchDayCatchupPreview preview = service.preview("t1", "CAL");

    assertThat(preview.currentBizDate()).isEqualTo(LocalDate.of(2026, 5, 5));
    assertThat(preview.lastScannedBizDate()).isEqualTo(LocalDate.of(2026, 5, 1));
    assertThat(preview.cursorSource()).isEqualTo("PERSISTED_CURSOR");
    assertThat(preview.remainingRawDateCount()).isEqualTo(4);
    assertThat(preview.nextScanRawBizDates())
        .containsExactly(LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 3));
    assertThat(preview.plannedThroughRawBizDate()).isEqualTo(LocalDate.of(2026, 5, 3));
    assertThat(preview.historicalGapsBeforeAnchorAreChecked()).isFalse();
    verify(batchDayMapper).selectOpenCursorDate("t1", "CAL");
    verifyNoMoreInteractions(batchDayMapper);
  }

  @DisplayName("cutoff 前使用前一业务日并展示历史锚点")
  @Test
  void shouldUsePreviousBizDateBeforeCalendarCutoffAndReportLegacyAnchor() {
    Instant beforeCutoff = Instant.parse("2026-05-04T21:59:00Z");
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    service = new BatchDayCatchupPreviewService(
        calendarMapper,
        batchDayMapper,
        catchupControlMapper,
        timezoneProvider,
        new BatchDateTimeSupport(Clock.fixed(beforeCutoff, ZoneId.of("UTC")), timezoneProvider),
        noopTransactionManager());
    when(batchDayMapper.selectOpenCursorDate("t1", "CAL")).thenReturn(null);
    when(batchDayMapper.selectLatestBizDateAtOrBefore("t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(LocalDate.of(2026, 5, 2));

    BatchDayCatchupPreview preview = service.preview("t1", "CAL");

    assertThat(preview.currentBizDate()).isEqualTo(LocalDate.of(2026, 5, 4));
    assertThat(preview.lastScannedBizDate()).isEqualTo(LocalDate.of(2026, 5, 2));
    assertThat(preview.cursorSource()).isEqualTo("LATEST_BATCH_DAY_BASELINE");
    assertThat(preview.nextScanRawBizDates())
        .containsExactly(LocalDate.of(2026, 5, 3), LocalDate.of(2026, 5, 4));
    verify(batchDayMapper).selectLatestBizDateAtOrBefore("t1", "CAL", LocalDate.of(2026, 5, 4));
  }

  @DisplayName("有界只读范围内仅返回缺失日期候选")
  @Test
  void shouldReturnOnlyMissingDatesAsCandidatesWithinBoundedReadOnlyRange() {
    when(batchDayMapper.selectBizDatesInRange(
            "t1", "CAL", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 4)))
        .thenReturn(java.util.List.of(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 3)));

    BatchDayHistoricalGapInventory inventory = service.inventoryHistoricalGaps(
        "t1", "CAL", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 4));

    assertThat(inventory.inspectedDateCount()).isEqualTo(4);
    assertThat(inventory.missingRecordCandidates())
        .containsExactly(LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 4));
    assertThat(inventory.interpretation()).contains("candidates only");
    verify(batchDayMapper)
        .selectBizDatesInRange("t1", "CAL", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 4));
    verifyNoMoreInteractions(batchDayMapper);
  }

  @DisplayName("查询前拒绝超过九十天的历史盘点")
  @Test
  void shouldRejectHistoricalInventoryRangeOverNinetyDaysBeforeQuerying() {
    assertThatThrownBy(() -> service.inventoryHistoricalGaps(
            "t1", "CAL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("90 days");
    verifyNoMoreInteractions(batchDayMapper);
  }

  @DisplayName("查询前拒绝晚于当前业务日的历史盘点")
  @Test
  void shouldRejectHistoricalInventoryAfterCurrentBusinessDateBeforeQuerying() {
    assertThatThrownBy(() -> service.inventoryHistoricalGaps(
            "t1", "CAL", LocalDate.of(2026, 5, 6), LocalDate.of(2026, 5, 6)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current business date");
    verifyNoMoreInteractions(batchDayMapper);
  }

  @DisplayName("cutoff 前本地日期按未来业务日拒绝历史盘点")
  @Test
  void shouldTreatBeforeCutoffLocalDateAsFutureForHistoricalInventory() {
    Instant beforeShanghaiCutoff = Instant.parse("2026-05-04T21:59:00Z");
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    service = new BatchDayCatchupPreviewService(
        calendarMapper,
        batchDayMapper,
        catchupControlMapper,
        timezoneProvider,
        new BatchDateTimeSupport(
            Clock.fixed(beforeShanghaiCutoff, ZoneId.of("UTC")), timezoneProvider),
        noopTransactionManager());

    assertThatThrownBy(() -> service.inventoryHistoricalGaps(
            "t1", "CAL", LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current business date");
    verifyNoMoreInteractions(batchDayMapper);
  }

  private BusinessCalendarEntity calendar(LocalTime cutoff) {
    return BusinessCalendarEntity.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .timezone("Asia/Shanghai")
        .cutoffTime(cutoff)
        .enabled(true)
        .build();
  }

  private static PlatformTransactionManager noopTransactionManager() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }
}
