package io.github.pinpols.batch.orchestrator.application.service.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.mapper.CapacityProfileMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
