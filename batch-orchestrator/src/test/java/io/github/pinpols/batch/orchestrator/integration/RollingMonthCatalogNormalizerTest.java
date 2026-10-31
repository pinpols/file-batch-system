package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("滚动月分区目录规范化,验证跨月后的分区名和边界能落到同一相对清单")
class RollingMonthCatalogNormalizerTest {

  @Test
  @DisplayName("相邻两个月的分区名、紧凑名和日期边界归一成同一相对文本")
  void shouldNormalizeAdjacentMonths_whenCatalogDateCrossesMonth() {
    LocalDate october = LocalDate.of(2026, 10, 31);
    LocalDate november = LocalDate.of(2026, 11, 1);
    String octoberCatalog = """
        batch.outbox_event_p_2026_09
        batch.outbox_event_p_2026_10
        batch.job_instance_p_2026_10_pkey
        batch.console_usage_daily_202609
        FOR VALUES FROM ('2026-09-01') TO ('2026-10-01')
        FOR VALUES FROM ('2026-09-01 00:00:00+08') TO ('2026-10-01 00:00:00+08')
        """;
    String novemberCatalog = """
        batch.outbox_event_p_2026_10
        batch.outbox_event_p_2026_11
        batch.job_instance_p_2026_11_pkey
        batch.console_usage_daily_202610
        FOR VALUES FROM ('2026-10-01') TO ('2026-11-01')
        FOR VALUES FROM ('2026-10-01 00:00:00+08') TO ('2026-11-01 00:00:00+08')
        """;

    assertThat(RollingMonthCatalogNormalizer.normalize(octoberCatalog, october))
        .isEqualTo(RollingMonthCatalogNormalizer.normalize(novemberCatalog, november));
  }

  @Test
  @DisplayName("窗口外的日期和普通数量不被改写")
  void shouldLeaveUnrelatedCatalogText_whenTokensFallOutsideWindow() {
    String catalog = "COLUMN\t4979\tnote 1999_01 and 199901 and 123456";

    assertThat(RollingMonthCatalogNormalizer.normalize(catalog, LocalDate.of(2026, 11, 1)))
        .isEqualTo(catalog);
  }
}
