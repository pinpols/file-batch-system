package com.example.batch.orchestrator.application.service.dryrun;

import java.time.LocalDate;
import java.util.Map;
import lombok.Builder;

/**
 * ADR-026 演练入口请求。{@link DryRunLevel} 决定执行哪一层。
 *
 * <ul>
 *   <li>L1 CONFIG_VALIDATE — 至少需要 tenantId + jobCode；bizDate / params 可空。
 *   <li>L2 SCHEDULE_PLAN — 必须 bizDate；输出预计 instance / partition / worker 信号。
 *   <li>L3 EXECUTION_PLAN — 必须 bizDate；下钻到 task 级 SQL explain / 文件路径 / endpoint reachability。
 * </ul>
 */
@Builder
public record DryRunPlanRequest(
    String tenantId,
    String jobCode,
    LocalDate bizDate,
    DryRunLevel level,
    Map<String, Object> params) {}
