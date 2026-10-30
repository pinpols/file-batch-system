package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.Collection;
import lombok.Builder;

/** 作业终态收尾历史 Pipeline 步骤时的租户、父作业、目标状态与终态守卫。 */
@Builder
public record CloseRunningPipelineStepsParam(
    String tenantId,
    Long id,
    String terminalInstanceStatus,
    String interruptedStepStatus,
    Collection<String> terminalStatusCodes) {}
