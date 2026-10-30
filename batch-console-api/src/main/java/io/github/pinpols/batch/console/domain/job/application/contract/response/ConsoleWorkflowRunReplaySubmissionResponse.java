package io.github.pinpols.batch.console.domain.job.application.contract.response;

/** 已受理的 Console 审批及其持久化 Orchestrator 重放操作。 */
public record ConsoleWorkflowRunReplaySubmissionResponse(
    Long replayOperationId, String approvalNo) {}
