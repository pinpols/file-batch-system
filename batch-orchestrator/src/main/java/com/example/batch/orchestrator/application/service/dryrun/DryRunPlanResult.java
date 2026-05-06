package com.example.batch.orchestrator.application.service.dryrun;

import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * ADR-026 演练计划结果。
 *
 * <ul>
 *   <li>{@code level} 实际运行的层级；
 *   <li>{@code findings} 每条校验结果（PASS/WARN/ERROR）；
 *   <li>{@code summary} 汇总（counts、scheduledJobs、estimatedPartitions 等）。
 * </ul>
 *
 * <p>{@code success} = 没有 ERROR finding。warning 不阻断 success 判定。
 */
@Builder
public record DryRunPlanResult(
    DryRunLevel level, boolean success, List<DryRunFinding> findings, Map<String, Object> summary) {

  public static DryRunPlanResult of(
      DryRunLevel level, List<DryRunFinding> findings, Map<String, Object> summary) {
    boolean ok = findings.stream().noneMatch(f -> f.severity() == DryRunFinding.Severity.ERROR);
    return DryRunPlanResult.builder()
        .level(level)
        .success(ok)
        .findings(findings)
        .summary(summary)
        .build();
  }
}
