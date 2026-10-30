package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.List;
import lombok.Builder;

/** 陈旧步骤清理参数；仅处理本次已失败 Pipeline 列表，状态与错误口径保持独立。 */
@Builder
public record FileGovernanceStaleStepParam(
    String tenantId,
    String failedStatus,
    String pendingStatus,
    String runningStatus,
    String retryingStatus,
    String failedPipelineStatus,
    String errorCode,
    String errorMessage,
    List<Long> pipelineInstanceIds) {}
