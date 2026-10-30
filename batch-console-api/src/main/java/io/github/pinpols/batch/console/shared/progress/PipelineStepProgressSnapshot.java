package io.github.pinpols.batch.console.shared.progress;

public record PipelineStepProgressSnapshot(
    Long stepId, Integer runSeq, String stepStatus, Long rowsProcessed, Long lastHeartbeatAt) {}
