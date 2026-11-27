package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.LocalDate;
import lombok.Builder;

/** 绑定一次 PAUSED 运行的空槽恢复；targetRunStatus 由调用方传入，不在 SQL 中固化为状态字面量。 */
@Builder
public record WorkflowRunResumeSlotParam(
        String tenantId,
        Long id,
        Long workflowDefinitionId,
        LocalDate bizDate,
        boolean dryRun,
        String targetRunStatus) {}
