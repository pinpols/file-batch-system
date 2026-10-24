package io.github.pinpols.batch.console.domain.job.application.contract.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 操作员为单个失败 Workflow 节点提交审批前返回的冻结预览。 */
public record ConsoleWorkflowNodeReplayPreviewResponse(
    String tenantId,
    String operationKey,
    Long sourceWorkflowRunId,
    Long sourceWorkflowNodeRunId,
    String sourceNodeCode,
    Integer sourceNodeRunSeq,
    Long sourceJobInstanceId,
    String jobCode,
    LocalDate bizDate,
    Integer sourceConfigVersion,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String previewToken,
    Instant expiresAt,
    List<String> warnings) {}
