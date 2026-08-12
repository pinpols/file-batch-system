package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.util.List;

/** Dashboard 按作业汇总的 SLA 报表。 */
public record SlaReportView(String tenantId, Integer periodDays, List<SlaJobReportView> jobs) {}
