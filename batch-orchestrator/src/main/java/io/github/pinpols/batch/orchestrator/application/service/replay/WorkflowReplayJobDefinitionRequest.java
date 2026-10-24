package io.github.pinpols.batch.orchestrator.application.service.replay;

import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import lombok.Builder;

/** 冻结整 Workflow 重放图中单个节点的配置选择。 */
@Builder
public record WorkflowReplayJobDefinitionRequest(
    String tenantId,
    String nodeCode,
    String jobCode,
    WorkflowRunExecutionSnapshot sourceSnapshot,
    JobDefinitionEntity latestDefinition,
    ConfigVersionPolicy policy,
    Integer specifiedVersion) {}
