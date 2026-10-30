package io.github.pinpols.batch.orchestrator.application.service.replay;

import java.util.UUID;
import lombok.Builder;

/** 将预览与接纳绑定到同一个失败 JOB 节点，防止重放目标漂移。 */
@Builder
public record WorkflowNodeReplayCommand(
    String tenantId,
    Long workflowRunId,
    Long workflowNodeRunId,
    String reason,
    String requestedBy,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String traceId,
    UUID operationKey,
    String previewToken) {}
