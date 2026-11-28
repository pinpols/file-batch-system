package io.github.pinpols.batch.console.application.file;

import java.util.List;

import io.github.pinpols.batch.console.shared.progress.PipelineStepProgressSnapshot;

public interface FilePipelineProgressSnapshotService {
    List<PipelineStepProgressSnapshot> stepsAuthorized(String tenantId, Long pipelineInstanceId);
}
