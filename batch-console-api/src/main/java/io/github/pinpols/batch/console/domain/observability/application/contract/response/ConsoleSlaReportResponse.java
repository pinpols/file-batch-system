package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaJobReportView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaReportView;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

/**
 * dashboard sla-report 响应：按 job 维度的 SLA 报表。
 *
 * <p>{@code avgDurationSeconds / maxDurationSeconds} 可为 null，历史 wire 保留显式 null 键 → 不加 {@code
 * NON_NULL}。
 */
public record ConsoleSlaReportResponse(
    String tenantId, Integer periodDays, List<SlaJobEntry> jobs) {

  @Builder
  public record SlaJobEntry(
      String jobCode,
      String jobName,
      Long totalInstances,
      Long successCount,
      Long failedCount,
      Long slaBreached,
      Long slaOnTime,
      Long slaTotalWithSla,
      BigDecimal avgDurationSeconds,
      BigDecimal maxDurationSeconds,
      Long totalPartitions) {

    static SlaJobEntry from(SlaJobReportView row) {
      return SlaJobEntry.builder()
          .jobCode(row.jobCode())
          .jobName(row.jobName())
          .totalInstances(EmptyChecks.isNull(row.totalInstances()) ? 0L : row.totalInstances())
          .successCount(EmptyChecks.isNull(row.successCount()) ? 0L : row.successCount())
          .failedCount(EmptyChecks.isNull(row.failedCount()) ? 0L : row.failedCount())
          .slaBreached(EmptyChecks.isNull(row.slaBreached()) ? 0L : row.slaBreached())
          .slaOnTime(EmptyChecks.isNull(row.slaOnTime()) ? 0L : row.slaOnTime())
          .slaTotalWithSla(EmptyChecks.isNull(row.slaTotalWithSla()) ? 0L : row.slaTotalWithSla())
          .avgDurationSeconds(row.avgDurationSeconds())
          .maxDurationSeconds(row.maxDurationSeconds())
          .totalPartitions(EmptyChecks.isNull(row.totalPartitions()) ? 0L : row.totalPartitions())
          .build();
    }
  }

  public static ConsoleSlaReportResponse from(SlaReportView view) {
    if (view == null) {
      return null;
    }
    return new ConsoleSlaReportResponse(
        view.tenantId(),
        view.periodDays(),
        view.jobs().stream().map(SlaJobEntry::from).toList());
  }
}
