package com.example.batch.console.domain.observability.view.dashboard;

import java.math.BigDecimal;

/** dashboard 按 job 维度的 SLA 报表行:执行/成功/失败/违约/平均/最大时长 + partition 总数。 */
public record SlaJobReportView(
    String jobCode,
    String jobName,
    Long totalInstances,
    Long successCount,
    Long failedCount,
    Long slaBreached,
    Long slaOnTime,
    BigDecimal avgDurationSeconds,
    BigDecimal maxDurationSeconds,
    Long totalPartitions) {}
