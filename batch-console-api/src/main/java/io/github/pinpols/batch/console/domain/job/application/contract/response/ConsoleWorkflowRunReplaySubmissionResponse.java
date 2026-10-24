package io.github.pinpols.batch.console.domain.job.application.contract.response;

/** Accepted Console approval and its durable Orchestrator replay operation. */
public record ConsoleWorkflowRunReplaySubmissionResponse(Long replayOperationId, String approvalNo) {}
