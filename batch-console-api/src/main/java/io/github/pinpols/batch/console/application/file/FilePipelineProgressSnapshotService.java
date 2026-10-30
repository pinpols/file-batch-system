package io.github.pinpols.batch.console.application.file;

import io.github.pinpols.batch.console.shared.progress.PipelineStepProgressSnapshot;
import java.util.List;

public interface FilePipelineProgressSnapshotService {
  List<PipelineStepProgressSnapshot> stepsAuthorized(String tenantId, Long pipelineInstanceId);
}
