package io.github.pinpols.batch.orchestrator.application.service.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.mapper.CapacityProfileMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CapacityProfileServiceTest {

  private final CapacityProfileMapper mapper = mock(CapacityProfileMapper.class);
  private final CapacityProfileService service = new CapacityProfileService(mapper);

  @Test
  void jobProfileShouldReturnRatesTotalsAndCoverageGaps() {
    Instant from = Instant.parse("2026-06-30T00:00:00Z");
    Instant to = Instant.parse("2026-06-30T01:00:00Z");
    when(mapper.selectJobProfile("ta", from, to, 10))
        .thenReturn(
            List.of(
                new CapacityProfileRow(
                    "ta",
                    "import_daily",
                    null,
                    "import",
                    2,
                    0,
                    2,
                    0,
                    2_000,
                    1_000,
                    1_500,
                    10 * 1024 * 1024,
                    4_000,
                    0,
                    0)));

    CapacityProfileReport report = service.query("ta", from, to, CapacityProfileGroupBy.JOB, 10);

    assertThat(report.groupBy()).isEqualTo(CapacityProfileGroupBy.JOB);
    assertThat(report.scope()).isEqualTo("BFS_HOT_TABLES");
    assertThat(report.rows()).hasSize(1);
    CapacityProfileRow row = report.rows().get(0);
    assertThat(row.recordsPerSecond()).isEqualTo(2000.0);
    assertThat(row.mbPerSecond()).isEqualTo(5.0);
    assertThat(report.totals().instanceCount()).isEqualTo(2);
    assertThat(report.totals().processedRecords()).isEqualTo(4_000);
    assertThat(report.coverage().knownGaps()).isNotEmpty();
    assertThat(report.coverage().rejectedScopes()).contains("跨平台 FinOps");
    verify(mapper).selectJobProfile("ta", from, to, 10);
  }

  @Test
  void shouldClampLimitAndDefaultToTenantGroup() {
    Instant from = Instant.parse("2026-06-30T00:00:00Z");
    Instant to = Instant.parse("2026-06-30T01:00:00Z");
    when(mapper.selectTenantProfile("ta", from, to, 200)).thenReturn(List.of());

    CapacityProfileReport report = service.query("ta", from, to, null, 500);

    assertThat(report.groupBy()).isEqualTo(CapacityProfileGroupBy.TENANT);
    verify(mapper).selectTenantProfile("ta", from, to, 200);
  }

  @Test
  void shouldRejectTooWideWindow() {
    Instant from = Instant.parse("2026-05-01T00:00:00Z");
    Instant to = Instant.parse("2026-06-30T00:00:00Z");

    assertThatThrownBy(() -> service.query("ta", from, to, CapacityProfileGroupBy.TENANT, 10))
        .isInstanceOf(BizException.class);
  }

  @Test
  void window_exactly31Days_isAccepted() {
    Instant from = Instant.parse("2026-05-01T00:00:00Z");
    Instant to = from.plus(Duration.ofDays(31));
    when(mapper.selectTenantProfile("ta", from, to, 10)).thenReturn(List.of());

    CapacityProfileReport report = service.query("ta", from, to, CapacityProfileGroupBy.TENANT, 10);

    assertThat(report.window().from()).isEqualTo(from);
    assertThat(report.window().to()).isEqualTo(to);
    verify(mapper).selectTenantProfile("ta", from, to, 10);
  }

  @Test
  void window_justOverMax_isRejected() {
    Instant from = Instant.parse("2026-05-01T00:00:00Z");
    Instant to = from.plus(Duration.ofDays(31)).plusSeconds(1);

    assertThatThrownBy(() -> service.query("ta", from, to, CapacityProfileGroupBy.TENANT, 10))
        .isInstanceOf(BizException.class);
  }

  @Test
  void window_rejects_whenFromNotBeforeTo() {
    Instant instant = Instant.parse("2026-06-30T00:00:00Z");

    // from == to
    assertThatThrownBy(
            () -> service.query("ta", instant, instant, CapacityProfileGroupBy.TENANT, 10))
        .isInstanceOf(BizException.class);

    // from > to
    Instant later = instant.plusSeconds(1);
    assertThatThrownBy(() -> service.query("ta", later, instant, CapacityProfileGroupBy.TENANT, 10))
        .isInstanceOf(BizException.class);
  }

  @Test
  void window_null_defaultsToBoundedRange() {
    when(mapper.selectTenantProfile(
            org.mockito.ArgumentMatchers.eq("ta"),
            org.mockito.ArgumentMatchers.any(Instant.class),
            org.mockito.ArgumentMatchers.any(Instant.class),
            org.mockito.ArgumentMatchers.eq(10)))
        .thenReturn(List.of());

    service.query("ta", null, null, CapacityProfileGroupBy.TENANT, 10);

    ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(mapper)
        .selectTenantProfile(eqArg("ta"), fromCaptor.capture(), toCaptor.capture(), eqInt(10));

    Instant resolvedFrom = fromCaptor.getValue();
    Instant resolvedTo = toCaptor.getValue();
    assertThat(resolvedFrom).isNotNull();
    assertThat(resolvedTo).isNotNull();
    assertThat(resolvedFrom).isBefore(resolvedTo);
    // null from/to default to a 24h window ending ~now
    assertThat(Duration.between(resolvedFrom, resolvedTo)).isEqualTo(Duration.ofHours(24));
    assertThat(resolvedTo)
        .isCloseTo(Instant.now(), within(1, java.time.temporal.ChronoUnit.MINUTES));
  }

  @Test
  void limit_belowMin_clampedToMin() {
    Instant from = Instant.parse("2026-06-30T00:00:00Z");
    Instant to = Instant.parse("2026-06-30T01:00:00Z");
    when(mapper.selectTenantProfile("ta", from, to, 1)).thenReturn(List.of());

    service.query("ta", from, to, CapacityProfileGroupBy.TENANT, 0);

    ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mapper)
        .selectTenantProfile(eqArg("ta"), eqInstant(from), eqInstant(to), limitCaptor.capture());
    assertThat(limitCaptor.getValue()).isEqualTo(1);
  }

  @Test
  void limit_null_defaultsTo50() {
    Instant from = Instant.parse("2026-06-30T00:00:00Z");
    Instant to = Instant.parse("2026-06-30T01:00:00Z");
    when(mapper.selectTenantProfile("ta", from, to, 50)).thenReturn(List.of());

    service.query("ta", from, to, CapacityProfileGroupBy.TENANT, null);

    ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mapper)
        .selectTenantProfile(eqArg("ta"), eqInstant(from), eqInstant(to), limitCaptor.capture());
    assertThat(limitCaptor.getValue()).isEqualTo(50);
  }

  private static String eqArg(String v) {
    return org.mockito.ArgumentMatchers.eq(v);
  }

  private static Instant eqInstant(Instant v) {
    return org.mockito.ArgumentMatchers.eq(v);
  }

  private static int eqInt(int v) {
    return org.mockito.ArgumentMatchers.eq(v);
  }
}
